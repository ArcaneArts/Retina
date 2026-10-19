//! Ignored, bounded experiment. Nothing in this module is linked into normal builds.
use super::*;
use std::{
    sync::mpsc,
    time::{Duration, Instant},
};

type Pos = (i32, i32, i32);
type Leaves = Vec<(Pos, u8)>;
const EMPTY: u32 = u32::MAX;
const LEAF_CELL: u32 = EMPTY - 1;
struct Canopy {
    wood: Vec<Pos>,
    leaves: Vec<Pos>,
    expected: Leaves,
    recipe: usize,
    seed: u64,
    full: Vec<WorldBlock>,
}

fn cpu(input: &Canopy, scratch: &mut TreeScratch) -> Leaves {
    let TreeScratch {
        occupancy,
        frontier,
        seen,
        ..
    } = scratch;
    occupancy.reset(&input.leaves);
    frontier.clear();
    seen.clear();
    for &p in &input.wood {
        if seen.insert(p) {
            occupancy.mark_log(p);
            frontier.push_back((p, 0u8));
        }
    }
    let mut out = Vec::new();
    while let Some((p, d)) = frontier.pop_front() {
        if d >= 6 {
            continue;
        }
        for (x, y, z) in [
            (1, 0, 0),
            (-1, 0, 0),
            (0, 1, 0),
            (0, -1, 0),
            (0, 0, 1),
            (0, 0, -1),
        ] {
            let q = (p.0 + x, p.1 + y, p.2 + z);
            if occupancy.take_leaf(q) {
                out.push((q, d + 1));
                frontier.push_back((q, d + 1));
            }
        }
    }
    scratch.recycle();
    out
}

struct Grid {
    minimum: [i32; 3],
    size: [usize; 3],
    offset: usize,
}
impl Grid {
    fn index(&self, p: Pos) -> Option<usize> {
        let p = [p.0, p.1, p.2];
        let d: [usize; 3] =
            std::array::from_fn(|i| (p[i] as i64 - self.minimum[i] as i64) as usize);
        if (0..3).any(|i| d[i] >= self.size[i]) {
            return None;
        }
        Some(self.offset + (d[1] * self.size[2] + d[2]) * self.size[0] + d[0])
    }
    fn position(&self, i: usize) -> Pos {
        (
            self.minimum[0] + (i % self.size[0]) as i32,
            self.minimum[1] + (i / (self.size[0] * self.size[2])) as i32,
            self.minimum[2] + ((i / self.size[0]) % self.size[2]) as i32,
        )
    }
    fn len(&self) -> usize {
        self.size.iter().product()
    }
}
struct Batch {
    grids: Vec<Grid>,
    descriptors: Vec<[u32; 4]>,
    words: Vec<u32>,
    widest: usize,
}
impl Batch {
    fn prepare(inputs: &[Canopy]) -> Self {
        assert!(!inputs.is_empty() && inputs.len() <= 256);
        let mut batch = Self {
            grids: Vec::new(),
            descriptors: Vec::new(),
            words: Vec::new(),
            widest: 0,
        };
        for input in inputs {
            assert!(!input.leaves.is_empty() && input.wood.len() <= 4096);
            let mut lo = [i32::MAX; 3];
            let mut hi = [i32::MIN; 3];
            for &(x, y, z) in &input.leaves {
                for (i, v) in [x, y, z].into_iter().enumerate() {
                    lo[i] = lo[i].min(v);
                    hi[i] = hi[i].max(v);
                }
            }
            // Only adjacent logs can seed a leaf. Keep original root ordinals
            // even for farther logs, so cropping cannot change BFS tie order.
            let grid = Grid {
                minimum: lo.map(|v| v.checked_sub(1).unwrap()),
                size: std::array::from_fn(|i| (hi[i] as i64 - lo[i] as i64 + 3) as usize),
                offset: batch.words.len(),
            };
            let len = grid
                .size
                .iter()
                .try_fold(1usize, |n, s| n.checked_mul(*s))
                .unwrap();
            assert!(
                len <= 1_048_576 && batch.words.len() + len <= 4_194_304,
                "probe storage budget exceeded"
            );
            batch.words.resize(batch.words.len() + len, EMPTY);
            for &p in &input.leaves {
                batch.words[grid.index(p).unwrap()] = LEAF_CELL;
            }
            for (i, &p) in input.wood.iter().enumerate() {
                if let Some(at) = grid.index(p) {
                    batch.words[at] = batch.words[at].min(i as u32);
                }
            }
            batch.widest = batch.widest.max(len);
            batch.descriptors.push([
                grid.offset as u32,
                grid.size[0] as u32,
                grid.size[1] as u32,
                grid.size[2] as u32,
            ]);
            batch.grids.push(grid);
        }
        batch
    }
    fn decode(&self, words: &[u32]) -> Vec<Leaves> {
        self.grids
            .iter()
            .map(|grid| {
                let mut reached: Vec<_> = words[grid.offset..grid.offset + grid.len()]
                    .iter()
                    .copied()
                    .enumerate()
                    .filter(|&(_, key)| (1..=6).contains(&(key >> 28)))
                    .collect();
                reached.sort_unstable_by_key(|&(_, key)| key);
                reached
                    .into_iter()
                    .map(|(i, key)| (grid.position(i), (key >> 28) as u8))
                    .collect()
            })
            .collect()
    }
}
struct Buffers {
    shape: wgpu::Buffer,
    a: wgpu::Buffer,
    b: wgpu::Buffer,
    readback: wgpu::Buffer,
    desc: wgpu::Buffer,
    capacity: usize,
}
struct Stamp {
    queries: wgpu::QuerySet,
    resolve: wgpu::Buffer,
    readback: wgpu::Buffer,
}
struct Gpu {
    device: wgpu::Device,
    queue: wgpu::Queue,
    pipeline: wgpu::ComputePipeline,
    local_pipeline: wgpu::ComputePipeline,
    large_pipeline: wgpu::ComputePipeline,
    layout: wgpu::BindGroupLayout,
    buffers: Option<Buffers>,
    backend: String,
    stamp: Option<Stamp>,
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
            label: Some("Retina bounded leaf probe"),
            required_features: adapter.features() & wgpu::Features::TIMESTAMP_QUERY,
            ..Default::default()
        }))
        .unwrap();
        let layout = device.create_bind_group_layout(&wgpu::BindGroupLayoutDescriptor {
            label: Some("Retina leaf probe"),
            entries: &(0..4)
                .map(|binding| wgpu::BindGroupLayoutEntry {
                    binding,
                    visibility: wgpu::ShaderStages::COMPUTE,
                    ty: wgpu::BindingType::Buffer {
                        ty: wgpu::BufferBindingType::Storage {
                            read_only: binding != 2,
                        },
                        has_dynamic_offset: false,
                        min_binding_size: None,
                    },
                    count: None,
                })
                .collect::<Vec<_>>(),
        });
        let shader = device.create_shader_module(wgpu::ShaderModuleDescriptor {
            label: Some("Retina leaf probe"),
            source: wgpu::ShaderSource::Wgsl(include_str!("leaf_probe.wgsl").into()),
        });
        let pipeline_layout = device.create_pipeline_layout(&wgpu::PipelineLayoutDescriptor {
            label: Some("Retina leaf probe"),
            bind_group_layouts: &[Some(&layout)],
            immediate_size: 0,
        });
        let pipeline = device.create_compute_pipeline(&wgpu::ComputePipelineDescriptor {
            label: Some("Retina leaf probe"),
            layout: Some(&pipeline_layout),
            module: &shader,
            entry_point: Some("propagate"),
            compilation_options: Default::default(),
            cache: None,
        });
        let make_pipeline = |entry| {
            device.create_compute_pipeline(&wgpu::ComputePipelineDescriptor {
                label: Some(entry),
                layout: Some(&pipeline_layout),
                module: &shader,
                entry_point: Some(entry),
                compilation_options: Default::default(),
                cache: None,
            })
        };
        let local_pipeline = make_pipeline("propagate_local");
        let large_pipeline = make_pipeline("propagate_large");
        let stamp = device
            .features()
            .contains(wgpu::Features::TIMESTAMP_QUERY)
            .then(|| Stamp {
                queries: device.create_query_set(&wgpu::QuerySetDescriptor {
                    label: Some("leaf timestamps"),
                    ty: wgpu::QueryType::Timestamp,
                    count: 2,
                }),
                resolve: device.create_buffer(&wgpu::BufferDescriptor {
                    label: Some("leaf timestamp resolve"),
                    size: 16,
                    usage: wgpu::BufferUsages::QUERY_RESOLVE | wgpu::BufferUsages::COPY_SRC,
                    mapped_at_creation: false,
                }),
                readback: device.create_buffer(&wgpu::BufferDescriptor {
                    label: Some("leaf timestamp readback"),
                    size: 16,
                    usage: wgpu::BufferUsages::MAP_READ | wgpu::BufferUsages::COPY_DST,
                    mapped_at_creation: false,
                }),
            });
        Self {
            device,
            queue,
            pipeline,
            local_pipeline,
            large_pipeline,
            layout,
            stamp,
            buffers: None,
            backend: format!("{:?}: {}", info.backend, info.name),
        }
    }
    fn run(&mut self, batch: &Batch, local: bool) -> (Vec<u32>, Option<f64>) {
        if self
            .buffers
            .as_ref()
            .is_none_or(|b| b.capacity < batch.words.len())
        {
            let capacity = batch.words.len();
            let make = |label, size, usage| {
                self.device.create_buffer(&wgpu::BufferDescriptor {
                    label: Some(label),
                    size,
                    usage,
                    mapped_at_creation: false,
                })
            };
            let storage = wgpu::BufferUsages::STORAGE
                | wgpu::BufferUsages::COPY_SRC
                | wgpu::BufferUsages::COPY_DST;
            self.buffers = Some(Buffers {
                shape: make("leaf shape", (capacity * 4) as u64, storage),
                a: make("leaf rank A", (capacity * 4) as u64, storage),
                b: make("leaf rank B", (capacity * 4) as u64, storage),
                readback: make(
                    "leaf readback",
                    (capacity * 4) as u64,
                    wgpu::BufferUsages::COPY_DST | wgpu::BufferUsages::MAP_READ,
                ),
                desc: make("leaf trees", 256 * 16, storage),
                capacity,
            });
        }
        let b = self.buffers.as_ref().unwrap();
        let bytes = (batch.words.len() * 4) as u64;
        self.queue
            .write_buffer(&b.shape, 0, bytemuck::cast_slice(&batch.words));
        self.queue
            .write_buffer(&b.desc, 0, bytemuck::cast_slice(&batch.descriptors));
        let binding = |buffer, size| {
            wgpu::BindingResource::Buffer(wgpu::BufferBinding {
                buffer,
                offset: 0,
                size: std::num::NonZeroU64::new(size),
            })
        };
        let groups: [_; 2] = std::array::from_fn(|swap| {
            self.device.create_bind_group(&wgpu::BindGroupDescriptor {
                label: Some("leaf active batch"),
                layout: &self.layout,
                entries: &[
                    wgpu::BindGroupEntry {
                        binding: 0,
                        resource: binding(&b.shape, bytes),
                    },
                    wgpu::BindGroupEntry {
                        binding: 1,
                        resource: binding(if swap == 0 { &b.a } else { &b.b }, bytes),
                    },
                    wgpu::BindGroupEntry {
                        binding: 2,
                        resource: binding(if swap == 0 { &b.b } else { &b.a }, bytes),
                    },
                    wgpu::BindGroupEntry {
                        binding: 3,
                        resource: binding(&b.desc, (batch.descriptors.len() * 16) as u64),
                    },
                ],
            })
        });
        let mut encoder = self
            .device
            .create_command_encoder(&wgpu::CommandEncoderDescriptor {
                label: Some("leaf six ordered relaxations"),
            });
        encoder.copy_buffer_to_buffer(&b.shape, 0, &b.a, 0, bytes);
        let global = !local || batch.grids.iter().any(|g| g.len() > 4096);
        for step in 0..if global { 6 } else { 0 } {
            let mut pass = encoder.begin_compute_pass(&wgpu::ComputePassDescriptor {
                label: Some("leaf rank relaxation"),
                timestamp_writes: self
                    .stamp
                    .as_ref()
                    .filter(|_| step == 0 || (step == 5 && !local))
                    .map(|stamp| wgpu::ComputePassTimestampWrites {
                        query_set: &stamp.queries,
                        beginning_of_pass_write_index: (step == 0).then_some(0),
                        end_of_pass_write_index: (step == 5 && !local).then_some(1),
                    }),
            });
            pass.set_pipeline(if local {
                &self.large_pipeline
            } else {
                &self.pipeline
            });
            pass.set_bind_group(0, &groups[step % 2], &[]);
            pass.dispatch_workgroups(
                (batch.widest as u32).div_ceil(64),
                batch.grids.len() as u32,
                1,
            );
        }
        if local {
            let mut pass = encoder.begin_compute_pass(&wgpu::ComputePassDescriptor {
                label: Some("leaf workgroup relaxation"),
                timestamp_writes: self.stamp.as_ref().map(|stamp| {
                    wgpu::ComputePassTimestampWrites {
                        query_set: &stamp.queries,
                        beginning_of_pass_write_index: (!global).then_some(0),
                        end_of_pass_write_index: Some(1),
                    }
                }),
            });
            pass.set_pipeline(&self.local_pipeline);
            pass.set_bind_group(0, &groups[1], &[]);
            pass.dispatch_workgroups(batch.grids.len() as u32, 1, 1);
        }
        encoder.copy_buffer_to_buffer(&b.a, 0, &b.readback, 0, bytes);
        if let Some(s) = &self.stamp {
            encoder.resolve_query_set(&s.queries, 0..2, &s.resolve, 0);
            encoder.copy_buffer_to_buffer(&s.resolve, 0, &s.readback, 0, 16);
        }
        let submission = self.queue.submit([encoder.finish()]);
        let (tx, rx) = mpsc::channel();
        if let Some(s) = &self.stamp {
            let tx = tx.clone();
            s.readback
                .slice(..)
                .map_async(wgpu::MapMode::Read, move |r| {
                    let _ = tx.send(r);
                });
        }
        b.readback
            .slice(..bytes)
            .map_async(wgpu::MapMode::Read, move |r| {
                let _ = tx.send(r);
            });
        self.device
            .poll(wgpu::PollType::Wait {
                submission_index: Some(submission),
                timeout: Some(Duration::from_secs(5)),
            })
            .unwrap();
        for _ in 0..1 + usize::from(self.stamp.is_some()) {
            rx.recv_timeout(Duration::from_secs(5)).unwrap().unwrap();
        }
        let result = {
            let mapped = b.readback.slice(..bytes).get_mapped_range().unwrap();
            bytemuck::cast_slice::<u8, u32>(&mapped).to_vec()
        };
        b.readback.unmap();
        let device_ms = self.stamp.as_ref().and_then(|s| {
            let ms = {
                let mapped = s.readback.slice(..).get_mapped_range().unwrap();
                let ticks = bytemuck::cast_slice::<u8, u64>(&mapped);
                ticks[1].saturating_sub(ticks[0]) as f64 * self.queue.get_timestamp_period() as f64
                    / 1e6
            };
            s.readback.unmap();
            (ms > 0.).then_some(ms)
        });
        (result, device_ms)
    }
}

fn fixtures(
    profile: &WorldProfile,
    n: usize,
) -> (Vec<Canopy>, serde_json::Value, Field<'static>, ChunkRequest) {
    let biome = profile
        .biomes
        .iter()
        .position(|b| b.id == "minecraft:forest")
        .unwrap();
    let soil = &profile.biomes[biome];
    let field = Field {
        prior: None,
        substrate: None,
        origin_x: -35,
        origin_z: -35,
        side: 8,
        columns: vec![
            Column {
                height: 64,
                materials: soil.top | (soil.filler << 16),
                packed: biome as u32
            };
            64 * COLUMNS
        ],
    };
    let request = ChunkRequest {
        seed: 123456789,
        chunk_x: -33,
        chunk_z: -33,
        min_y: -64,
        height: 384,
        base_height: 64.,
        amplitude: 0.,
        frequency: 0.008,
        reserved: 0,
    };
    let shapes = [
        ("straight", "blob"),
        ("fancy", "fancy"),
        ("mega_jungle", "jungle"),
        ("dark_oak", "dark_oak"),
        ("giant", "mega_pine"),
        ("forking", "acacia"),
    ];
    let recipes:Vec<_>=shapes.iter().map(|&(t,f)|profile.decorations.iter().find(|r|matches!(&r.feature,Kind::Tree{trunk_shape,foliage_shape,root,soil,..} if trunk_shape==&format!("{t}_trunk_placer") && foliage_shape==&format!("{f}_foliage_placer") && (*root==0 || *soil!=0))).unwrap()).collect();
    let mut inputs = Vec::new();
    let mut scratch = TreeScratch::default();
    let mut worker = diagnostics::Worker::default();
    let mut sources = Vec::new();
    for i in 0..n * 4 {
        if inputs.len() == n {
            break;
        }
        let recipe = recipes[i % recipes.len()];
        let mut blocks = Vec::new();
        tree(
            &field,
            profile,
            request,
            recipe,
            -520,
            -520,
            &mut Rng::new(12345 + i as u64 * 53),
            &mut blocks,
            None,
            None,
            Some(&mut worker),
            &mut scratch,
        );
        let actual: Vec<_> = blocks
            .iter()
            .filter(|b| b.role == LEAF)
            .map(|b| ((b.x, b.y, b.z), b.material))
            .collect();
        if actual.is_empty() {
            continue;
        }
        assert!(scratch.occupancy.sparse.is_none() && !scratch.occupancy.cells.is_empty());
        // The completed grid retains every potential leaf plus marked trunk cells.
        // Adding a trunk cell to potential leaves is harmless: roots exclude it
        // before both CPU and GPU traversals. This avoids production test hooks.
        let grid = Grid {
            minimum: scratch.occupancy.minimum,
            size: scratch.occupancy.sizes,
            offset: 0,
        };
        let leaves = scratch
            .occupancy
            .cells
            .iter()
            .enumerate()
            .filter(|&(_, v)| *v != 0)
            .map(|(i, _)| grid.position(i))
            .collect();
        let wood = blocks
            .iter()
            .take_while(|b| b.role == LOG)
            .map(|b| (b.x, b.y, b.z))
            .collect();
        let mut input = Canopy {
            wood,
            leaves,
            expected: Vec::new(),
            recipe: profile
                .decorations
                .iter()
                .position(|r| std::ptr::eq(r, recipe))
                .unwrap(),
            seed: 12345 + i as u64 * 53,
            full: blocks,
        };
        input.expected = cpu(&input, &mut TreeScratch::default());
        assert_eq!(
            input.expected.iter().map(|p| p.0).collect::<Vec<_>>(),
            actual.iter().map(|p| p.0).collect::<Vec<_>>()
        );
        let Kind::Tree { leaves, .. } = &recipe.feature else {
            unreachable!()
        };
        assert!(
            leaves.iter().any(|leaf| input
                .expected
                .iter()
                .zip(&actual)
                .all(|(&(_, d), &(_, m))| leaf.distances[d as usize - 1] == m)),
            "distance materials differ from production"
        );
        sources.push(recipe.source.clone());
        inputs.push(input);
    }
    assert_eq!(inputs.len(), n);
    (
        inputs,
        serde_json::json!({"sources":sources.iter().fold(std::collections::BTreeMap::<&str,usize>::new(),|mut m,k|{*m.entry(k).or_default()+=1;m}),"worker_cpu_geometry_and_leaf":worker}),
        field,
        request,
    )
}

fn check_order_cases(gpu: &mut Gpu) {
    let mut cases = Vec::new();
    for origin in [
        (-30_000_000, -50, 30_000_000),
        (0, 0, 0),
        (30_000_000, 256, -30_000_000),
    ] {
        let at = |x, y, z| (origin.0 + x, origin.1 + y, origin.2 + z);
        let mut leaves = Vec::new();
        for y in 0..4 {
            for z in -3..=3 {
                for x in -3..=3 {
                    if (x + y + z) % 3 != 0 {
                        leaves.push(at(x, y, z));
                    }
                }
            }
        }
        leaves.extend((1..=8).map(|x| at(x, 0, 0)));
        leaves.push(at(6, 9, 6)); // disconnected, still fits the local workgroup
        leaves.extend_from_within(..12);
        let mut c = Canopy {
            wood: vec![at(1, 0, 0), at(-1, 0, 0), at(1, 0, 0), at(0, 0, 0)],
            leaves,
            expected: Vec::new(),
            recipe: 0,
            seed: 0,
            full: Vec::new(),
        };
        c.expected = cpu(&c, &mut TreeScratch::default());
        cases.push(c);
        let small = cases.last().unwrap();
        let mut large = Canopy {
            wood: small.wood.clone(),
            leaves: small.leaves.clone(),
            expected: Vec::new(),
            recipe: 0,
            seed: 0,
            full: Vec::new(),
        };
        large.leaves.push(at(15, 9, 15)); // exercises the large-grid fallback
        large.expected = cpu(&large, &mut TreeScratch::default());
        cases.push(large);
    }
    let batch = Batch::prepare(&cases);
    assert!(batch.grids.iter().any(|g| g.len() <= 4096));
    assert!(batch.grids.iter().any(|g| g.len() > 4096));
    for local in [false, true] {
        let (words, _) = gpu.run(&batch, local);
        for (actual, case) in batch.decode(&words).iter().zip(&cases) {
            assert_eq!(actual, &case.expected);
        }
    }
}

#[test]
#[ignore = "actual GPU ordered leaf checks; use a 30s process-group deadline"]
fn ordered_gpu_leaf_edge_cases() {
    check_order_cases(&mut Gpu::new());
}
fn full_cpu(
    inputs: &[Canopy],
    profile: &WorldProfile,
    field: &Field,
    request: ChunkRequest,
) -> Vec<Vec<WorldBlock>> {
    inputs
        .par_iter()
        .map_init(TreeScratch::default, |scratch, c| {
            let mut blocks = Vec::new();
            tree(
                field,
                profile,
                request,
                &profile.decorations[c.recipe],
                -520,
                -520,
                &mut Rng::new(c.seed),
                &mut blocks,
                None,
                None,
                None,
                scratch,
            );
            blocks
        })
        .collect()
}
fn commands(blocks: &[WorldBlock]) -> impl Iterator<Item = (i32, i32, i32, u16, u16, u8)> + '_ {
    blocks
        .iter()
        .map(|b| (b.x, b.y, b.z, b.material, b.upper, b.role))
}

#[test]
#[ignore = "actual GPU leaf evaluation; RETINA_LEAF_PROFILE required, two workers, use 30s deadline"]
fn ordered_gpu_leaf_evaluation() {
    let path = std::env::var("RETINA_LEAF_PROFILE").unwrap();
    let profile = WorldProfile::parse(&std::fs::read(&path).unwrap()).unwrap();
    let (inputs, metadata, field, request) = fixtures(&profile, 256);
    let workers = rayon::ThreadPoolBuilder::new()
        .num_threads(2)
        .build()
        .unwrap();
    let mut gpu = Gpu::new();
    check_order_cases(&mut gpu);
    let local = std::env::var("RETINA_LEAF_LOCAL").as_deref() == Ok("1");
    for n in [16, 64, 256] {
        let inputs = &inputs[..n];
        let mut cpu_ms = Vec::new();
        let mut gpu_ms = Vec::new();
        let mut prepare_ms = Vec::new();
        let mut upload_wait_readback_ms = Vec::new();
        let mut decode_ms = Vec::new();
        let mut voxels = 0;
        let mut device_ms = Vec::new();
        let mut full_tree_ms = Vec::new();
        for pair in 0..9 {
            for step in 0..3 {
                let mode = (pair + step) % 3;
                let gpu_path = mode == 0;
                let begin = Instant::now();
                if mode == 2 {
                    let full = workers.install(|| full_cpu(inputs, &profile, &field, request));
                    if pair >= 2 {
                        full_tree_ms.push(begin.elapsed().as_secs_f64() * 1000.);
                    }
                    for (a, c) in full.iter().zip(inputs) {
                        assert!(commands(a).eq(commands(&c.full)));
                    }
                    continue;
                }
                let result = if gpu_path {
                    let batch = Batch::prepare(inputs);
                    let prepared = Instant::now();
                    let (data, device) = gpu.run(&batch, local);
                    let readback = Instant::now();
                    let result = batch.decode(&data);
                    let end = Instant::now();
                    voxels = batch.words.len();
                    if pair >= 2 {
                        gpu_ms.push((end - begin).as_secs_f64() * 1000.);
                        device_ms.push(device);
                        prepare_ms.push((prepared - begin).as_secs_f64() * 1000.);
                        upload_wait_readback_ms.push((readback - prepared).as_secs_f64() * 1000.);
                        decode_ms.push((end - readback).as_secs_f64() * 1000.);
                    }
                    result
                } else {
                    let result = workers.install(|| {
                        inputs
                            .par_iter()
                            .map_init(TreeScratch::default, |scratch, input| cpu(input, scratch))
                            .collect::<Vec<_>>()
                    });
                    if pair >= 2 {
                        cpu_ms.push(begin.elapsed().as_secs_f64() * 1000.);
                    }
                    result
                };
                for (actual, input) in result.iter().zip(inputs) {
                    assert_eq!(actual, &input.expected, "ordered leaf output differs");
                }
            }
        }
        println!(
            "{}",
            serde_json::json!({"backend":gpu.backend,"local_workgroups":local,"profile":path,"trees":n,"warmups":2,"pairs":7,"workers":2,"voxels":voxels,"device_buffer_bytes":voxels*16+4096,"cpu_ms":cpu_ms,"full_cpu_tree_ms":full_tree_ms,"gpu_total_ms":gpu_ms,"gpu_device_ms":device_ms,"gpu_prepare_ms":prepare_ms,"gpu_upload_wait_readback_ms":upload_wait_readback_ms,"gpu_order_decode_ms":decode_ms,"outputs_equal":true,"fixture":if n==256{Some(&metadata)}else{None}})
        );
    }
}
