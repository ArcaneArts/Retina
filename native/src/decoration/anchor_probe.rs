//! Bounded complete plant/vine-program experiment, excluded from production.
//! Independent-column programs parallelize within an anchor while retaining order.
use super::*;
use placement::{IntConfig, IntProvider, Modifier, Predicate};
use std::{
    sync::mpsc,
    time::{Duration, Instant},
};
const OPS: usize = 24;
const OVERLAY: usize = 4096;
const CAP: usize = 2048;
const HEADER: usize = 32;
const COL: usize = 16;
const STATUS: usize = 4;

fn column_independent(recipe: &Recipe) -> bool {
    let Kind::AttachmentGrowth { growth } = &recipe.feature else {
        return false;
    };
    let Some((
        Predicate::Material {
            offset: [0, 0, 0],
            allowed: air,
        },
        directions,
    )) = growth.vine_program()
    else {
        return false;
    };
    let outputs: Vec<_> = directions.iter().map(|v| v.0).collect();
    if outputs.iter().any(|v| air.binary_search(v).is_ok()) {
        return false;
    }
    fn invariant(p: &Predicate, air: &[u16], outputs: &[u16]) -> bool {
        match p {
            Predicate::Material { offset, allowed } if offset[0] != 0 || offset[2] != 0 => {
                let mut membership = air
                    .iter()
                    .chain(outputs)
                    .map(|v| allowed.binary_search(v).is_ok());
                let first = membership.next();
                membership.all(|v| Some(v) == first)
            }
            Predicate::AllOf { predicates } | Predicate::AnyOf { predicates } => {
                predicates.iter().all(|p| invariant(p, air, outputs))
            }
            Predicate::Not { predicate } => invariant(predicate, air, outputs),
            _ => true,
        }
    }
    directions.iter().all(|(_, p)| invariant(p, air, &outputs))
        && recipe
            .placement
            .iter()
            .flat_map(|p| p.iter())
            .all(|m| match m {
                Modifier::BlockPredicateFilter { predicate } => invariant(predicate, air, &outputs),
                _ => true,
            })
}

struct Compiled {
    words: Vec<u32>,
    ops: Vec<[u32; 8]>,
    states: [Vec<[u32; 5]>; 2],
    vines: Option<u32>,
    outputs: Vec<u16>,
}
fn words64(n: u64) -> [u32; 2] {
    [n as u32, (n >> 32) as u32]
}
fn threshold(mut predicate: impl FnMut(f64) -> bool) -> u64 {
    // First rejected 53-bit unit draw. This retains f32 rounding and f64
    // subtraction semantics without asking the shader to approximate them.
    let (mut lo, mut hi) = (0, 1u64 << 53);
    while lo < hi {
        let mid = lo + (hi - lo) / 2;
        if predicate(mid as f64 / (1u64 << 53) as f64) {
            lo = mid + 1;
        } else {
            hi = mid;
        }
    }
    lo
}
impl Compiled {
    fn int(&mut self, p: &IntProvider) -> Option<u32> {
        let v = match p {
            IntProvider::Value(v) | IntProvider::Config(IntConfig::Constant { value: v }) => {
                [0, *v as u32, 0, 0]
            }
            IntProvider::Config(IntConfig::Uniform {
                min_inclusive: a,
                max_inclusive: b,
            }) => [1, *a as u32, *b as u32, 0],
            IntProvider::Config(IntConfig::BiasedToBottom {
                min_inclusive: a,
                max_inclusive: b,
            }) => [2, *a as u32, *b as u32, 0],
            IntProvider::Config(IntConfig::Trapezoid { min, max, plateau }) => {
                [3, *min as u32, *max as u32, *plateau as u32]
            }
            _ => return None,
        };
        // The two-word shader modulo uses 16-bit limbs. Keep every divisor
        // within its proven arithmetic bound; unsupported ranges stay on CPU.
        let span = v[2] as i32 as i64 - v[1] as i32 as i64;
        if v[0] != 0 && !(0..=8192).contains(&span) {
            return None;
        }
        let i = self.words.len() as u32;
        self.words.extend(v);
        Some(i)
    }
    fn predicate(&mut self, p: &Predicate, out: &mut Vec<[u32; 8]>) -> Option<()> {
        match p {
            Predicate::InsideWorldBounds { .. } => return None,
            Predicate::True => out.push([0; 8]),
            Predicate::Material { offset, allowed } => {
                let i = self.words.len() as u32;
                self.words.extend(allowed.iter().map(|v| *v as u32));
                out.push([
                    1,
                    offset[0] as u32,
                    offset[1] as u32,
                    offset[2] as u32,
                    i,
                    allowed.len() as u32,
                    u32::from(
                        self.outputs
                            .iter()
                            .any(|v| allowed.binary_search(v).is_ok()),
                    ),
                    u32::from(
                        self.outputs
                            .iter()
                            .any(|v| allowed.binary_search(v).is_err()),
                    ),
                ]);
            }
            Predicate::Not { predicate } => {
                self.predicate(predicate, out)?;
                out.push([2, 0, 0, 0, 0, 0, 0, 0]);
            }
            Predicate::AllOf { predicates } | Predicate::AnyOf { predicates } => {
                let all = matches!(p, Predicate::AllOf { .. });
                out.push([if all { 0 } else { 5 }, 0, 0, 0, 0, 0, 0, 0]);
                for p in predicates {
                    self.predicate(p, out)?;
                    out.push([if all { 3 } else { 4 }, 0, 0, 0, 0, 0, 0, 0]);
                }
            }
        }
        (out.len() <= 64).then_some(())
    }
    fn new(recipe: &Recipe, request: ChunkRequest) -> Option<Self> {
        let states = match &recipe.feature {
            Kind::Plant { states } => states.as_slice(),
            Kind::AttachmentGrowth { growth } if growth.vine_program().is_some() => &[],
            _ => return None,
        };
        let program = recipe.placement.as_ref()?;
        if program.len() > OPS
            || program
                .iter()
                .filter(|v| matches!(v, Modifier::Count { .. }))
                .count()
                > 8
        {
            return None;
        }
        let mut c = Self {
            words: Vec::new(),
            ops: Vec::new(),
            states: Default::default(),
            vines: None,
            outputs: match &recipe.feature {
                Kind::AttachmentGrowth { growth } => {
                    growth.vine_program()?.1.into_iter().map(|v| v.0).collect()
                }
                _ => Vec::new(),
            },
        };
        for (index, m) in program.iter().enumerate() {
            let mut o = [0; 8];
            match m {
                Modifier::Count { count } => {
                    o[0] = 1;
                    o[1] = c.int(count)?;
                }
                Modifier::HeightRange { height } => {
                    use super::placement_height::{Anchor, HeightConfig, HeightProvider};
                    let resolve = |a: &Anchor| match a {
                        Anchor::Absolute(v) => *v,
                        Anchor::AboveBottom(v) => request.min_y.saturating_add(*v),
                        Anchor::BelowTop(v) => {
                            (request.min_y + request.height as i32 - 1).saturating_sub(*v)
                        }
                    };
                    let p = match height {
                        HeightProvider::Constant(v)
                        | HeightProvider::Config(HeightConfig::Constant { value: v }) => {
                            IntProvider::Value(resolve(v))
                        }
                        HeightProvider::Config(HeightConfig::Uniform {
                            min_inclusive,
                            max_inclusive,
                        }) => {
                            let (a, b) = (resolve(min_inclusive), resolve(max_inclusive));
                            if b as i64 - a as i64 > 8192 {
                                return None;
                            }
                            if a >= b {
                                IntProvider::Value(a)
                            } else {
                                IntProvider::Config(IntConfig::Uniform {
                                    min_inclusive: a,
                                    max_inclusive: b,
                                })
                            }
                        }
                        _ => return None,
                    };
                    o[0] = 10;
                    o[1] = c.int(&p)?;
                }
                Modifier::InSquare => o[0] = 2,
                Modifier::Offset { x, y, z } => {
                    o[0] = 3;
                    o[1] = c.int(x)?;
                    o[2] = c.int(y)?;
                    o[3] = c.int(z)?;
                }
                Modifier::RarityFilter { chance } => {
                    o[0] = 4;
                    o[1..3].copy_from_slice(&words64(threshold(|u| u < 1.0 / *chance as f64)));
                }
                Modifier::RandomChance { chance } => {
                    o[0] = 4;
                    o[1..3].copy_from_slice(&words64(threshold(|u| (u as f32) < *chance)));
                }
                Modifier::Select { min, max } => {
                    o[0] = 5;
                    o[1..3].copy_from_slice(&words64(threshold(|u| u < *min)));
                    o[3..5].copy_from_slice(&words64(threshold(|u| u < *max)));
                }
                Modifier::Heightmap { map } => {
                    o[0] = 6;
                    o[1] = *map as u32;
                }
                Modifier::Biome => o[0] = 7,
                Modifier::BlockPredicateFilter { predicate } => {
                    let mut ops = Vec::new();
                    c.predicate(predicate, &mut ops)?;
                    o[0] = 8;
                    o[1] = c.words.len() as u32;
                    o[2] = ops.len() as u32;
                    c.words.extend(ops.into_iter().flatten());
                }
                Modifier::SurfaceWaterDepthFilter { max_water_depth } => {
                    o[0] = 9;
                    o[1] = *max_water_depth as u32;
                }
                _ => return None,
            }
            o[7] = u32::from(
                program
                    .iter()
                    .skip(index + 1)
                    .any(|v| matches!(v, Modifier::Count { .. })),
            );
            c.ops.push(o);
        }
        for band in 0..2 {
            let active: Vec<_> = states
                .iter()
                .filter(|s| s.band == 0 || s.band == band as u8 + 1)
                .collect();
            let total: f64 = active.iter().map(|s| s.weight).sum();
            for (index, s) in active.iter().enumerate() {
                let bound = threshold(|u| {
                    let mut choice = u * total;
                    for v in active.iter().take(index + 1) {
                        choice -= v.weight;
                        if choice < 0.0 {
                            return true;
                        }
                    }
                    false
                });
                let [lo, hi] = words64(bound);
                c.states[band].push([lo, hi, s.lower as u32, s.upper as u32, u32::from(s.dry)]);
            }
        }
        if let Kind::AttachmentGrowth { growth } = &recipe.feature {
            let (air, directions) = growth.vine_program()?;
            let mut ops = Vec::new();
            c.predicate(air, &mut ops)?;
            let mut desc = vec![
                c.words.len() as u32,
                ops.len() as u32,
                directions.len() as u32,
            ];
            c.words.extend(ops.into_iter().flatten());
            for (state, p) in directions {
                let mut ops = Vec::new();
                c.predicate(p, &mut ops)?;
                desc.extend([state as u32, c.words.len() as u32, ops.len() as u32]);
                c.words.extend(ops.into_iter().flatten());
            }
            c.vines = Some(c.words.len() as u32);
            c.words.extend(desc);
        }
        Some(c)
    }
}
struct Batch {
    input: Vec<u32>,
    anchors: Vec<[u32; 4]>,
    role: u8,
    capacity: usize,
}
impl Batch {
    fn new(
        field: &Field,
        profile: &WorldProfile,
        request: ChunkRequest,
        recipe: u32,
        positions: &[(i32, i32)],
        capacity: usize,
    ) -> Option<Self> {
        if !profile.ordered_decorations
            || positions.is_empty()
            || positions.len() > 256
            || capacity > CAP
        {
            return None;
        }
        let c = Compiled::new(&profile.decorations[recipe as usize], request)?;
        let width = field.side * 16;
        let quart_y = request.min_y.div_euclid(4) * 4;
        let layers = (request.min_y + request.height as i32 - quart_y + 3) as usize / 4;
        let mut input = vec![0; HEADER + width * width * COL];
        input[..8].copy_from_slice(&[
            (field.origin_x * 16) as u32,
            (field.origin_z * 16) as u32,
            width as u32,
            request.min_y as u32,
            request.height,
            capacity as u32,
            positions.len() as u32,
            OVERLAY as u32,
        ]);
        input[8] = input.len() as u32;
        input.extend(profile.material_flags.iter().map(|v| *v as u32));
        input[9] = input.len() as u32;
        input.extend(profile.heightmap_masks.iter().map(|v| *v as u32));
        input[10] = input.len() as u32;
        input[11] = c.ops.len() as u32;
        input.extend(c.ops.iter().flatten());
        input[22] = u32::from(c.vines.is_some());
        input[23] = c.vines.unwrap_or(0);
        let role = if c.vines.is_some() { FEATURE } else { PLANT };
        if c.vines.is_some()
            && c.ops.iter().map(|o| o[0]).collect::<Vec<_>>() == [1, 1, 2, 10, 8, 7]
        {
            let a = c.ops[0][1] as usize;
            let b = c.ops[1][1] as usize;
            let outer = c.words[a + 1] as i32;
            let inner = c.words[b + 1] as i32;
            if c.words[a] == 0
                && c.words[b] == 0
                && outer > 0
                && inner > 0
                && (outer as i64) * (inner as i64) <= 16384
            {
                input[24] = outer as u32;
                input[25] = inner as u32;
                input[26] = c.ops[3][1];
                input[27] = c.ops[4][1];
                input[28] = c.ops[4][2];
                // Only this exact in-square program can use column-local live
                // state. Cross-column predicates must be invariant under every
                // possible air-to-vine write, as proved from the loaded data.
                input[29] = u32::from(
                    request.height % 2 == 0
                        && request.height <= 512
                        && column_independent(&profile.decorations[recipe as usize]),
                );
                input[30] = 128;
            }
        }
        input[12] = input.len() as u32;
        input.extend(c.words);
        for band in 0..2 {
            input[13 + band * 2] = input.len() as u32;
            input[14 + band * 2] = c.states[band].len() as u32;
            input.extend(c.states[band].iter().flatten());
        }
        input[17] = u32::from(profile.decoration_biome_3d);
        input[18] = layers as u32;
        for z in 0..width {
            for x in 0..width {
                let wx = field.origin_x * 16 + x as i32;
                let wz = field.origin_z * 16 + z as i32;
                let col = field.column(wx, wz)?;
                let at = HEADER + (z * width + x) * COL;
                let runs: Vec<_> = if let Some(s) = &field.substrate {
                    s.material_runs(wx, wz)?.to_vec()
                } else {
                    let mut runs = Vec::new();
                    let mut previous = u32::MAX;
                    for dy in (0..request.height).rev() {
                        let m =
                            field.material(profile, request, [wx, request.min_y + dy as i32, wz])?
                                as u32;
                        if m == previous {
                            *runs.last_mut().unwrap() = dy << 16 | m;
                        } else {
                            runs.push(dy << 16 | m);
                            previous = m;
                        }
                    }
                    runs
                };
                input[at] = input.len() as u32;
                input[at + 1] = runs.len() as u32;
                input.extend(runs);
                input[at + 2] = col.height as u32;
                input[at + 3] = u32::from(col.packed & (1 << 27) != 0);
                input[at + 4] = u32::from(
                    field
                        .substrate
                        .as_ref()
                        .is_some_and(|m| m.surface_carved(wx, wz)),
                );
                input[at + 5] =
                    u32::from(profile.biomes[col.biome()].decorations.contains(&recipe));
                for map in 0..6 {
                    input[at + 8 + map] = field.height(profile, request, wx, wz, map as u8)? as u32;
                }
            }
        }
        input[19] = input.len() as u32;
        input[20] = quart_y as u32;
        input[21] = (width / 4) as u32;
        for q in 0..layers {
            for z in (0..width).step_by(4) {
                for x in (0..width).step_by(4) {
                    let wx = field.origin_x * 16 + x as i32;
                    let wz = field.origin_z * 16 + z as i32;
                    let y = (quart_y + q as i32 * 4).max(request.min_y);
                    // NONE tells the shader to use each column's own fallback biome.
                    let b = field.substrate.as_ref().and_then(|m| m.biome(wx, y, wz));
                    input.push(b.map_or(u32::MAX, |b| {
                        u32::from(profile.biomes[b as usize].decorations.contains(&recipe))
                    }));
                }
            }
        }
        let r = &profile.decorations[recipe as usize];
        let anchors = positions
            .iter()
            .map(|&(x, z)| {
                let seed = request.seed
                    ^ r.placement_salt.unwrap_or(r.salt) as u64
                    ^ mix(x as u32 as u64)
                    ^ mix((z as u32 as u64) << 32);
                let [lo, hi] = words64(seed);
                [x as u32, z as u32, lo, hi]
            })
            .collect();
        Some(Self {
            input,
            anchors,
            role,
            capacity,
        })
    }
    fn output_words(&self) -> usize {
        self.anchors.len() * (STATUS + self.capacity * 5)
    }
    fn with_initial_overlays(mut self, overlays: &[placement::Overlay]) -> Self {
        // The handoff fixture covers fixed-height vine programs. Heightmapped
        // plants would also need prior overlay tops, not just material entries.
        assert!(self.input[22] != 0 && self.input[24] != 0);
        assert_eq!(self.anchors.len(), overlays.len());
        let descriptors = self.input.len();
        self.input[31] = descriptors as u32;
        self.input.resize(descriptors + overlays.len() * 2, 0);
        let width = self.input[2] as i32;
        for (index, overlay) in overlays.iter().enumerate() {
            let mut entries: Vec<_> = overlay
                .probe_entries()
                .filter_map(|(p, m)| {
                    let x = p[0] - self.input[0] as i32;
                    let z = p[2] - self.input[1] as i32;
                    let y = p[1] - self.input[3] as i32;
                    (x >= 0
                        && z >= 0
                        && y >= 0
                        && x < width
                        && z < width
                        && y < self.input[4] as i32)
                        .then_some([((y * width + z) * width + x + 1) as u32, m as u32])
                })
                .collect();
            entries.sort_unstable_by_key(|v| v[0]);
            self.input[descriptors + index * 2] = self.input.len() as u32;
            self.input[descriptors + index * 2 + 1] = entries.len() as u32;
            self.input.extend(entries.into_iter().flatten());
        }
        self
    }
}
struct Gpu {
    device: wgpu::Device,
    queue: wgpu::Queue,
    pipeline: wgpu::ComputePipeline,
    prepare: wgpu::ComputePipeline,
    columns: wgpu::ComputePipeline,
    gather: wgpu::ComputePipeline,
    layout: wgpu::BindGroupLayout,
    backend: String,
    queries: Option<wgpu::QuerySet>,
    device_ms: std::cell::Cell<Option<[f64; 3]>>,
}
impl Gpu {
    fn new() -> Self {
        let instance = wgpu::Instance::new(wgpu::InstanceDescriptor {
            backends: wgpu::Backends::METAL | wgpu::Backends::VULKAN | wgpu::Backends::DX12,
            ..wgpu::InstanceDescriptor::new_without_display_handle()
        });
        let adapter = pollster::block_on(instance.request_adapter(&wgpu::RequestAdapterOptions {
            power_preference: wgpu::PowerPreference::LowPower,
            compatible_surface: None,
            force_fallback_adapter: false,
            apply_limit_buckets: false,
        }))
        .unwrap();
        let info = adapter.get_info();
        let (device, queue) = pollster::block_on(adapter.request_device(&wgpu::DeviceDescriptor {
            label: Some("bounded complete plant-anchor probe"),
            required_features: adapter.features() & wgpu::Features::TIMESTAMP_QUERY,
            ..Default::default()
        }))
        .unwrap();
        let layout = device.create_bind_group_layout(&wgpu::BindGroupLayoutDescriptor {
            label: Some("anchor probe"),
            entries: &(0..6)
                .map(|binding| wgpu::BindGroupLayoutEntry {
                    binding,
                    visibility: wgpu::ShaderStages::COMPUTE,
                    ty: wgpu::BindingType::Buffer {
                        ty: wgpu::BufferBindingType::Storage {
                            read_only: binding < 2,
                        },
                        has_dynamic_offset: false,
                        min_binding_size: None,
                    },
                    count: None,
                })
                .collect::<Vec<_>>(),
        });
        let shader = device.create_shader_module(wgpu::ShaderModuleDescriptor {
            label: Some("whole plant anchor"),
            source: wgpu::ShaderSource::Wgsl(include_str!("anchor_probe.wgsl").into()),
        });
        let pl = device.create_pipeline_layout(&wgpu::PipelineLayoutDescriptor {
            label: Some("anchor probe"),
            bind_group_layouts: &[Some(&layout)],
            immediate_size: 0,
        });
        let pipeline = device.create_compute_pipeline(&wgpu::ComputePipelineDescriptor {
            label: Some("anchor probe"),
            layout: Some(&pl),
            module: &shader,
            entry_point: Some("main"),
            compilation_options: Default::default(),
            cache: None,
        });
        let prepare = device.create_compute_pipeline(&wgpu::ComputePipelineDescriptor {
            label: Some("parallel vine preparation"),
            layout: Some(&pl),
            module: &shader,
            entry_point: Some("prepare"),
            compilation_options: Default::default(),
            cache: None,
        });
        let columns = device.create_compute_pipeline(&wgpu::ComputePipelineDescriptor {
            label: Some("independent ordered vine columns"),
            layout: Some(&pl),
            module: &shader,
            entry_point: Some("columns"),
            compilation_options: Default::default(),
            cache: None,
        });
        let gather = device.create_compute_pipeline(&wgpu::ComputePipelineDescriptor {
            label: Some("parallel ordered anchor compaction"),
            layout: Some(&pl),
            module: &shader,
            entry_point: Some("gather"),
            compilation_options: Default::default(),
            cache: None,
        });
        let queries = device
            .features()
            .contains(wgpu::Features::TIMESTAMP_QUERY)
            .then(|| {
                device.create_query_set(&wgpu::QuerySetDescriptor {
                    label: Some("anchor stage timestamps"),
                    ty: wgpu::QueryType::Timestamp,
                    count: 6,
                })
            });
        Self {
            device,
            queue,
            pipeline,
            prepare,
            columns,
            gather,
            layout,
            backend: format!("{:?}: {}", info.backend, info.name),
            queries,
            device_ms: std::cell::Cell::new(None),
        }
    }
    fn run(&self, b: &Batch) -> Vec<Option<Vec<WorldBlock>>> {
        use wgpu::util::DeviceExt;
        let a = self
            .device
            .create_buffer_init(&wgpu::util::BufferInitDescriptor {
                label: Some("anchor programs/substrate"),
                contents: bytemuck::cast_slice(&b.input),
                usage: wgpu::BufferUsages::STORAGE,
            });
        let anchors = self
            .device
            .create_buffer_init(&wgpu::util::BufferInitDescriptor {
                label: Some("anchor seeds"),
                contents: bytemuck::cast_slice(&b.anchors),
                usage: wgpu::BufferUsages::STORAGE,
            });
        let buffer = |size, usage| {
            self.device.create_buffer(&wgpu::BufferDescriptor {
                label: Some("anchor scratch/output"),
                size,
                usage,
                mapped_at_creation: false,
            })
        };
        let outbytes = (b.output_words() * 4) as u64;
        let timestamp_bytes = if self.queries.is_some() { 48 } else { 0 };
        let column_mode = b.input[24] > 0 && b.input[29] != 0;
        let overlay = buffer(
            (b.anchors.len()
                * if column_mode {
                    256 * b.input[4] as usize * 4
                } else {
                    OVERLAY * 8
                }) as u64,
            wgpu::BufferUsages::STORAGE | wgpu::BufferUsages::COPY_DST,
        );
        let out = buffer(
            outbytes,
            wgpu::BufferUsages::STORAGE | wgpu::BufferUsages::COPY_SRC,
        );
        let read = buffer(
            outbytes + timestamp_bytes,
            wgpu::BufferUsages::COPY_DST | wgpu::BufferUsages::MAP_READ,
        );
        let candidates = buffer(
            (b.anchors.len() as u64 * b.input[24] as u64 * b.input[25] as u64 * 16).max(16),
            wgpu::BufferUsages::STORAGE,
        );
        let columns = buffer(
            if column_mode {
                (b.anchors.len() * (1 + 256 * (1 + b.input[30] as usize)) * 4) as u64
            } else {
                4
            },
            wgpu::BufferUsages::STORAGE | wgpu::BufferUsages::COPY_DST,
        );
        let bind = self.device.create_bind_group(&wgpu::BindGroupDescriptor {
            label: Some("anchor probe"),
            layout: &self.layout,
            entries: &[&a, &anchors, &overlay, &out, &candidates, &columns]
                .into_iter()
                .enumerate()
                .map(|(binding, b)| wgpu::BindGroupEntry {
                    binding: binding as u32,
                    resource: b.as_entire_binding(),
                })
                .collect::<Vec<_>>(),
        });
        let mut enc = self.device.create_command_encoder(&Default::default());
        let resolve = self.queries.as_ref().map(|_| {
            buffer(
                48,
                wgpu::BufferUsages::QUERY_RESOLVE | wgpu::BufferUsages::COPY_SRC,
            )
        });
        let writes = |pair: u32| {
            self.queries
                .as_ref()
                .map(|q| wgpu::ComputePassTimestampWrites {
                    query_set: q,
                    beginning_of_pass_write_index: Some(pair * 2),
                    end_of_pass_write_index: Some(pair * 2 + 1),
                })
        };
        enc.clear_buffer(&overlay, 0, None);
        enc.clear_buffer(&columns, 0, None);
        if b.input[24] > 0 {
            let mut pass = enc.begin_compute_pass(&wgpu::ComputePassDescriptor {
                label: Some("anchor prepare"),
                timestamp_writes: writes(0),
            });
            pass.set_pipeline(&self.prepare);
            pass.set_bind_group(0, &bind, &[]);
            pass.dispatch_workgroups(
                (b.input[24] * b.input[25]).div_ceil(64),
                b.anchors.len() as u32,
                1,
            );
        }
        if column_mode {
            let mut pass = enc.begin_compute_pass(&wgpu::ComputePassDescriptor {
                label: Some("anchor columns"),
                timestamp_writes: writes(1),
            });
            pass.set_pipeline(&self.columns);
            pass.set_bind_group(0, &bind, &[]);
            pass.dispatch_workgroups(4, b.anchors.len() as u32, 1);
        }
        {
            let mut pass = enc.begin_compute_pass(&wgpu::ComputePassDescriptor {
                label: Some("anchor gather / scalar"),
                timestamp_writes: writes(2),
            });
            pass.set_pipeline(if column_mode {
                &self.gather
            } else {
                &self.pipeline
            });
            pass.set_bind_group(0, &bind, &[]);
            pass.dispatch_workgroups(
                if column_mode {
                    b.anchors.len() as u32
                } else {
                    (b.anchors.len() as u32).div_ceil(32)
                },
                1,
                1,
            );
        }
        enc.copy_buffer_to_buffer(&out, 0, &read, 0, outbytes);
        if let (Some(q), Some(resolve)) = (&self.queries, &resolve) {
            enc.resolve_query_set(q, 0..6, resolve, 0);
            enc.copy_buffer_to_buffer(resolve, 0, &read, outbytes, 48);
        }
        let submission = self.queue.submit([enc.finish()]);
        let (tx, rx) = mpsc::channel();
        read.slice(..).map_async(wgpu::MapMode::Read, move |r| {
            let _ = tx.send(r);
        });
        self.device
            .poll(wgpu::PollType::Wait {
                submission_index: Some(submission),
                timeout: Some(Duration::from_secs(10)),
            })
            .unwrap();
        rx.recv_timeout(Duration::from_secs(10)).unwrap().unwrap();
        let bytes = read.slice(..).get_mapped_range().unwrap();
        self.device_ms.set(self.queries.as_ref().map(|_| {
            let values: Vec<_> = bytes[outbytes as usize..]
                .chunks_exact(8)
                .map(|v| u64::from_ne_bytes(v.try_into().unwrap()))
                .collect();
            std::array::from_fn(|i| {
                if (i == 0 && b.input[24] == 0) || (i == 1 && !column_mode) {
                    0.
                } else {
                    values[i * 2 + 1].saturating_sub(values[i * 2]) as f64
                        * self.queue.get_timestamp_period() as f64
                        / 1_000_000.
                }
            })
        }));
        let data: &[u32] = bytemuck::cast_slice(&bytes[..outbytes as usize]);
        let result = data
            .chunks_exact(STATUS + b.capacity * 5)
            .map(|r| {
                if r[1] != 0 {
                    return None;
                }
                assert!(r[0] as usize <= b.capacity);
                Some(
                    r[STATUS..STATUS + r[0] as usize * 5]
                        .chunks_exact(5)
                        .map(|v| WorldBlock {
                            root_order: 0,
                            tick: 0,
                            x: v[0] as i32,
                            y: v[1] as i32,
                            z: v[2] as i32,
                            material: v[3] as u16,
                            upper: v[4] as u16,
                            role: b.role,
                        })
                        .collect(),
                )
            })
            .collect();
        drop(bytes);
        read.unmap();
        result
    }
}
fn reference(
    field: &Field,
    profile: &WorldProfile,
    request: ChunkRequest,
    recipe: u32,
    positions: &[(i32, i32)],
) -> Vec<Vec<WorldBlock>> {
    reference_initial(field, profile, request, recipe, positions, Vec::new())
}
fn reference_initial(
    field: &Field,
    profile: &WorldProfile,
    request: ChunkRequest,
    recipe: u32,
    positions: &[(i32, i32)],
    initial: Vec<placement::Overlay>,
) -> Vec<Vec<WorldBlock>> {
    let initial = if initial.is_empty() {
        (0..positions.len())
            .map(|_| placement::Overlay::default())
            .collect()
    } else {
        initial
    };
    assert_eq!(initial.len(), positions.len());
    positions
        .par_iter()
        .zip(initial.into_par_iter())
        .map(|(&(x, z), overlay)| {
            let mut missing = Vec::new();
            let mut scratch = TreeScratch::default();
            let mut anchor = expand_anchor(
                field,
                profile,
                request,
                x,
                z,
                field.substrate.as_deref(),
                None,
                Some(&[recipe]),
                &mut missing,
                None,
                &mut scratch,
            );
            anchor.overlay = overlay;
            let samples = provider_noise::Samples::default();
            let noise = provider_noise::Context::new(&samples);
            let (blocks, _) = evaluate_anchor(
                anchor,
                field,
                profile,
                request,
                field.substrate.as_deref(),
                None,
                &noise,
                &mut missing,
                false,
                None,
                &mut scratch,
            );
            assert!(missing.is_empty() && noise.missing().is_empty());
            blocks
        })
        .collect()
}
fn signature(v: &[WorldBlock]) -> Vec<[i32; 6]> {
    v.iter()
        .map(|b| {
            [
                b.x,
                b.y,
                b.z,
                b.material as i32,
                b.upper as i32,
                b.role as i32,
            ]
        })
        .collect()
}

#[test]
#[ignore = "bounded actual-GPU whole-program trial"]
fn gpu_complete_plant_anchor_probe() {
    let file = std::env::var("RETINA_ANCHOR_PROFILE").expect("profile path");
    let mut source: serde_json::Value =
        serde_json::from_slice(&std::fs::read(file).unwrap()).unwrap();
    source["program_execution"] = "interpreter".into();
    let engine = crate::TerrainEngine::new().unwrap();
    let id = engine
        .register_profile(&serde_json::to_vec(&source).unwrap())
        .unwrap();
    let profile = engine.profile(id).unwrap().unwrap();
    let request = ChunkRequest {
        seed: 123456789,
        chunk_x: 0,
        chunk_z: 0,
        min_y: -64,
        height: 384,
        base_height: 64.,
        amplitude: 48.,
        frequency: 0.008,
        reserved: id,
    };
    let side = 4;
    let (field, _) = engine.terrain_field(request, side).unwrap();
    let gpu = Gpu::new();
    let positions: Vec<_> = (0..side as i32)
        .flat_map(|z| (0..side as i32).map(move |x| (x, z)))
        .collect();
    let pool = rayon::ThreadPoolBuilder::new()
        .num_threads(2)
        .build()
        .unwrap();
    let mut cases = Vec::new();
    let mut count = 0;
    for (recipe, r) in profile.decorations.iter().enumerate() {
        let Some(batch) = Batch::new(&field, &profile, request, recipe as u32, &positions, CAP)
        else {
            continue;
        };
        let before = Instant::now();
        let cpu = pool.install(|| reference(&field, &profile, request, recipe as u32, &positions));
        let cpu_ms = before.elapsed().as_secs_f64() * 1000.;
        let before = Instant::now();
        let actual = gpu.run(&batch);
        let gpu_ms = before.elapsed().as_secs_f64() * 1000.;
        for (i, (expected, got)) in cpu.iter().zip(&actual).enumerate() {
            assert_eq!(
                signature(expected),
                signature(got.as_ref().expect("unexpected overflow")),
                "{} anchor {i}",
                r.source
            );
        }
        let writes: usize = cpu.iter().map(Vec::len).sum();
        count += writes;
        cases.push(serde_json::json!({"recipe":recipe,"source":r.source,"anchors":positions.len(),"writes":writes,"cpu_ms":cpu_ms,"gpu_ms":gpu_ms,"upload_bytes":(batch.input.len()+batch.anchors.len()*4)*4,"readback_bytes":batch.output_words()*4}));
        if writes > 0 {
            let mut small = batch;
            small.capacity = 0;
            small.input[5] = 0;
            let overflow = gpu.run(&small);
            for (expected, got) in cpu.iter().zip(overflow) {
                if expected.is_empty() {
                    assert!(got.unwrap().is_empty());
                } else {
                    assert!(got.is_none());
                }
            }
        }
    }
    assert!(count > 0, "fixture must place actual registered features");
    println!(
        "ANCHOR_PROBE {}",
        serde_json::json!({"backend":gpu.backend,"cases":cases,"writes":count,"note":"Exploratory first-run timings; includes GPU allocation/upload/readback but not CPU batch preparation. Not a production performance claim."})
    );
}

#[test]
#[ignore = "bounded complete planner census, no GPU anchor offload"]
fn gpu_anchor_workload_census() {
    let file = std::env::var("RETINA_ANCHOR_PROFILE").expect("profile path");
    let mut source: serde_json::Value =
        serde_json::from_slice(&std::fs::read(file).unwrap()).unwrap();
    source["program_execution"] = "interpreter".into();
    let engine = crate::TerrainEngine::new().unwrap();
    let id = engine
        .register_profile(&serde_json::to_vec(&source).unwrap())
        .unwrap();
    let profile = engine.profile(id).unwrap().unwrap();
    let request = ChunkRequest {
        seed: 123456789,
        chunk_x: 0,
        chunk_z: 0,
        min_y: -64,
        height: 384,
        base_height: 64.,
        amplitude: 48.,
        frequency: 0.008,
        reserved: id,
    };
    let (field, mask) = engine.terrain_field(request, 32).unwrap();
    let pool = rayon::ThreadPoolBuilder::new()
        .num_threads(2)
        .build()
        .unwrap();
    let (plan, details) = pool
        .install(|| {
            engine.plan_decorations_detailed(&field, &profile, request, 32, mask.as_deref())
        })
        .unwrap();
    let rows:Vec<_>=details.worker.recipe_candidates.iter().enumerate().filter(|(_,v)|v[0]>0).map(|(id,v)|{
        let r=&profile.decorations[id];
        serde_json::json!({"recipe":id,"source":r.source,"kind":source["decorations"][id]["kind"],"evaluated":v[0],"positioned":v[1],"gpu_probe_supported":Compiled::new(r,request).is_some()})
    }).collect();
    let writes: usize = plan.iter().map(Vec::len).sum();
    assert_eq!(
        details.worker.evaluated_candidates,
        details
            .worker
            .recipe_candidates
            .iter()
            .map(|v| v[0])
            .sum::<u64>()
    );
    println!(
        "ANCHOR_CENSUS {}",
        serde_json::json!({"backend":engine.backend(),"seed":request.seed,"side":32,"writes":writes,"rows":rows,"phases":details})
    );
}

pub(super) fn vine_field(
    profile: &WorldProfile,
    recipe: u32,
    initial: u16,
    request: ChunkRequest,
    side: usize,
) -> Field<'static> {
    let biome = profile
        .biomes
        .iter()
        .position(|b| b.decorations.contains(&recipe))
        .unwrap();
    let span = side + 2;
    let width = span * 16;
    let mw = width + 2;
    let surface = mw + 30;
    let quart = surface / 4;
    let layers = (request.height as usize).div_ceil(4);
    let columns = width * width;
    let groups = columns.div_ceil(256);
    let exposure = (mw * mw * request.height as usize).div_ceil(32);
    let bio = exposure + (surface * surface).div_ceil(32);
    let base = bio + (quart * quart * layers).div_ceil(2);
    let payload = base + 4 + columns * 2 + groups * 2;
    let mut words = vec![0; payload];
    words[bio..base].fill(biome as u32 | ((biome as u32) << 16));
    words[base..base + 4].copy_from_slice(&[0x52554e53, width as u32, columns as u32, 0]);
    for z in 0..width {
        for x in 0..width {
            let i = z * width + x;
            let start = words.len() - payload;
            if i % 256 == 0 {
                words[base + 4 + columns * 2 + groups + i / 256] = start as u32;
            }
            words[base + 5 + i * 2] =
                (start as u32) - words[base + 4 + columns * 2 + groups + i / 256];
            let mut previous = u32::MAX;
            for dy in (0..request.height).rev() {
                let y = request.min_y + dy as i32;
                let material = if y < 64 || (x % 4 == 0 && y < 256) {
                    profile.stone
                } else if x % 4 == 1 && y == 160 {
                    initial
                } else {
                    0
                } as u32;
                if material == previous {
                    *words.last_mut().unwrap() = dy << 16 | material;
                } else {
                    words.push(dy << 16 | material);
                    previous = material;
                }
            }
            words[base + 4 + i * 2] = (words.len() - payload - start) as u32;
        }
    }
    words[base + 3] = (words.len() - payload) as u32;
    let mask = crate::geology::CaveMask {
        origin_x: (request.chunk_x - 1) * 16 - 1,
        origin_z: (request.chunk_z - 1) * 16 - 1,
        min_y: request.min_y,
        height: request.height,
        width: mw,
        air_only: true,
        words,
        columns: None,
    };
    mask.validate_material_runs().unwrap();
    Field {
        prior: None,
        origin_x: request.chunk_x - 1,
        origin_z: request.chunk_z - 1,
        side: span,
        columns: vec![
            Column {
                height: 64,
                materials: profile.stone as u32 | ((profile.stone as u32) << 16),
                packed: biome as u32 | 3 << 24
            };
            span * span * COLUMNS
        ],
        substrate: Some(std::sync::Arc::new(mask)),
    }
}

#[test]
#[ignore = "bounded exact seeded vine anchors with live sequential growth"]
fn gpu_vine_anchor_live_probe() {
    let file = std::env::var("RETINA_ANCHOR_PROFILE").expect("Terralith profile");
    let source: serde_json::Value = serde_json::from_slice(&std::fs::read(&file).unwrap()).unwrap();
    let profile = WorldProfile::parse(&std::fs::read(&file).unwrap()).unwrap();
    let recipe = profile
        .decorations
        .iter()
        .position(|r| r.source == "terralith:highlands/vines_spread")
        .unwrap() as u32;
    let initial = source["decorations"][recipe as usize]["initial"]
        .as_u64()
        .unwrap() as u16;
    let mut dependent = source["decorations"][recipe as usize].clone();
    dependent["support"][2]["allowed"]
        .as_array_mut()
        .unwrap()
        .insert(0, serde_json::json!(0));
    let dependent: Recipe = serde_json::from_value(dependent).unwrap();
    assert!(
        !column_independent(&dependent),
        "horizontal support changed by air-to-vine writes must use the scalar fallback"
    );
    let gpu = Gpu::new();
    let pool = rayon::ThreadPoolBuilder::new()
        .num_threads(2)
        .build()
        .unwrap();
    let mut cases = Vec::new();
    let mut cascades = 0;
    for (x, z, seed) in [(0, 0, 0), (-17, -1, 42), (-1874990, 1874990, u64::MAX)] {
        let request = ChunkRequest {
            seed,
            chunk_x: x,
            chunk_z: z,
            min_y: -64,
            height: 384,
            base_height: 64.,
            amplitude: 0.,
            frequency: 0.008,
            reserved: 0,
        };
        let field = vine_field(&profile, recipe, initial, request, 1);
        let positions = [(x, z)];
        let mut b = Batch::new(&field, &profile, request, recipe, &positions, CAP).unwrap();
        assert_eq!(b.input[24] * b.input[25], 8000);
        assert_eq!(b.input[29], 1, "fixture must use independent columns");
        let cpu = pool.install(|| reference(&field, &profile, request, recipe, &positions));
        assert!(!cpu[0].is_empty());
        cascades += cpu[0].iter().filter(|b| b.y < 159).count();
        let start = Instant::now();
        let parallel = gpu.run(&b);
        let parallel_ms = start.elapsed().as_secs_f64() * 1000.;
        assert_eq!(signature(&cpu[0]), signature(parallel[0].as_ref().unwrap()));
        let mut prior = placement::Overlay::default();
        for dz in 0..16 {
            prior.write(&profile, [x * 16 + 1, 160, z * 16 + dz], 0);
            prior.write(&profile, [x * 16 + 1, 100, z * 16 + dz], initial);
            for y in 80..116 {
                prior.write(&profile, [x * 16 + 4, y, z * 16 + dz], 0);
            }
        }
        let overlays = vec![prior];
        let before = Batch::new(&field, &profile, request, recipe, &positions, CAP)
            .unwrap()
            .with_initial_overlays(&overlays);
        let after_cpu = pool
            .install(|| reference_initial(&field, &profile, request, recipe, &positions, overlays));
        assert_ne!(
            signature(&cpu[0]),
            signature(&after_cpu[0]),
            "prior writes must affect this fixture"
        );
        let after_gpu = gpu.run(&before);
        assert_eq!(
            signature(&after_cpu[0]),
            signature(after_gpu[0].as_ref().unwrap()),
            "per-anchor prior overlay"
        );
        b.input[24] = 0;
        let start = Instant::now();
        let serial = gpu.run(&b);
        let serial_ms = start.elapsed().as_secs_f64() * 1000.;
        assert_eq!(signature(&cpu[0]), signature(serial[0].as_ref().unwrap()));
        b.input[24] = 200;
        b.input[5] = 0;
        b.capacity = 0;
        let failed = gpu.run(&b);
        assert!(failed[0].is_none());
        // The only accepted result after overflow is a complete CPU replay.
        let recovered = failed
            .into_iter()
            .enumerate()
            .map(|(i, b)| {
                b.unwrap_or_else(|| {
                    reference(&field, &profile, request, recipe, &positions[i..i + 1]).remove(0)
                })
            })
            .collect::<Vec<_>>();
        assert_eq!(signature(&cpu[0]), signature(&recovered[0]));
        cases.push(serde_json::json!({"origin":[x,z],"seed":seed,"writes":cpu[0].len(),"writes_with_prior_overlay":after_cpu[0].len(),"parallel_gpu_ms":parallel_ms,"scalar_gpu_ms":serial_ms}));
    }
    assert!(
        cascades > 0,
        "must exercise newly created vines enabling later placements"
    );
    println!(
        "VINE_LIVE_PROBE {}",
        serde_json::json!({"backend":gpu.backend,"cases":cases,"cascaded_writes":cascades})
    );
}

#[test]
#[ignore = "bounded repeated independent-column GPU/CPU comparison"]
fn gpu_vine_anchor_batch_probe() {
    let file = std::env::var("RETINA_ANCHOR_PROFILE").expect("Terralith profile");
    let source: serde_json::Value = serde_json::from_slice(&std::fs::read(&file).unwrap()).unwrap();
    let profile = WorldProfile::parse(&std::fs::read(&file).unwrap()).unwrap();
    let recipe = profile
        .decorations
        .iter()
        .position(|r| r.source == "terralith:highlands/vines_spread")
        .unwrap() as u32;
    let initial = source["decorations"][recipe as usize]["initial"]
        .as_u64()
        .unwrap() as u16;
    let gpu = Gpu::new();
    let pool = rayon::ThreadPoolBuilder::new()
        .num_threads(2)
        .build()
        .unwrap();
    let request = ChunkRequest {
        seed: 123456789,
        chunk_x: 0,
        chunk_z: 0,
        min_y: -64,
        height: 384,
        base_height: 64.,
        amplitude: 0.,
        frequency: 0.008,
        reserved: 0,
    };
    let mut cases = Vec::new();
    for side in [4, 8] {
        let field = vine_field(&profile, recipe, initial, request, side);
        let positions: Vec<_> = (0..side as i32)
            .flat_map(|z| (0..side as i32).map(move |x| (x, z)))
            .collect();
        let expected = pool.install(|| reference(&field, &profile, request, recipe, &positions));
        let signatures: Vec<_> = expected.iter().map(|v| signature(v)).collect();
        let writes: usize = expected.iter().map(Vec::len).sum();
        assert!(writes > 0);
        let mut rows = Vec::new();
        // First pair warms the pipelines and allocators. Then reverse the order
        // on every pair. Packing remains inside every GPU measurement.
        for iteration in 0..4 {
            for kind in if iteration % 2 == 0 {
                ["cpu", "gpu"]
            } else {
                ["gpu", "cpu"]
            } {
                let before = Instant::now();
                let (actual, packing_ms, upload_bytes, readback_bytes) = if kind == "cpu" {
                    (
                        pool.install(|| reference(&field, &profile, request, recipe, &positions)),
                        0.,
                        0,
                        0,
                    )
                } else {
                    let b = Batch::new(&field, &profile, request, recipe, &positions, CAP).unwrap();
                    assert_eq!(b.input[29], 1);
                    let packing_ms = before.elapsed().as_secs_f64() * 1000.;
                    let actual = gpu
                        .run(&b)
                        .into_iter()
                        .map(|v| v.expect("no overflow"))
                        .collect();
                    (
                        actual,
                        packing_ms,
                        (b.input.len() + b.anchors.len() * 4) * 4,
                        b.output_words() * 4,
                    )
                };
                let total_ms = before.elapsed().as_secs_f64() * 1000.;
                assert_eq!(
                    signatures,
                    actual.iter().map(|v| signature(v)).collect::<Vec<_>>()
                );
                if iteration > 0 {
                    rows.push(serde_json::json!({"path":kind,"total_ms":total_ms,"packing_ms":packing_ms,"upload_bytes":upload_bytes,"readback_bytes":readback_bytes+if kind=="gpu"&&gpu.queries.is_some(){48}else{0},"device_stage_ms":if kind=="gpu"{gpu.device_ms.get()}else{None}}));
                }
            }
        }
        // Deliberate queue overflow must reject the complete anchor result.
        let mut b = Batch::new(&field, &profile, request, recipe, &positions, CAP).unwrap();
        b.input[30] = 1;
        assert!(gpu.run(&b).iter().all(Option::is_none));
        cases.push(serde_json::json!({"side":side,"anchors":positions.len(),"attempts":positions.len()*8000,"writes":writes,"rows":rows}));
    }
    println!(
        "VINE_BATCH_PROBE {}",
        serde_json::json!({"backend":gpu.backend,"cases":cases,"note":"Complete independent registered recipe only, seeded synthetic substrate; not whole planner or lit-MCA throughput."})
    );
}
