//! Final-block GPU lighting. The outer MCA ring is input only, never marked lit.
mod output;
mod palettes;
pub(crate) use palettes::Section as PaletteSection;
mod transport;
pub(crate) use output::{Channel, ChunkLight};
use serde::Deserialize;
use std::{
    collections::{HashMap, VecDeque},
    time::{Duration, Instant},
};
use wgpu::util::DeviceExt;

#[derive(Clone, Deserialize)]
pub struct Profile {
    pub sky: bool,
    pub faces: u32,
    pub states: Vec<[u32; 8]>,
    pub blocked: Vec<u32>,
}
impl Profile {
    pub fn validate(&self, materials: usize) -> Result<(), String> {
        let stride = (self.faces as usize).div_ceil(32);
        if self.faces == 0
            || self.states.len() != materials
            || self.blocked.len() != self.faces as usize * stride
            || self
                .states
                .iter()
                .any(|s| s[0] > 255 || s[1..7].iter().any(|&f| f >= self.faces))
        {
            return Err("invalid registered lighting palette/face coverage".into());
        }
        Ok(())
    }
}
fn compact_transport(selected: Option<&str>, backend: &str) -> bool {
    match selected {
        Some("dense") => false,
        Some("compact") => true,
        _ => backend.starts_with("Metal:"),
    }
}

fn section_palettes_enabled(selected: Option<&str>, backend: &str) -> bool {
    match selected {
        Some("0") => false,
        Some("1") => true,
        _ => backend.starts_with("Metal:"),
    }
}

pub(crate) struct Gpu {
    device: wgpu::Device,
    queue: wgpu::Queue,
    pipelines: Option<Pipelines>,
    readbacks: Vec<Readback>,
    profiles: HashMap<u32, (wgpu::Buffer, wgpu::Buffer)>,
    bind_groups: HashMap<u32, (wgpu::BindGroup, wgpu::BindGroup)>,
    buffers: Option<Buffers>,
    compact_input: bool,
    compact_output: bool,
    section_palettes: bool,
}
struct Pipelines {
    layout: wgpu::BindGroupLayout,
    init: wgpu::ComputePipeline,
    spread: wgpu::ComputePipeline,
    compact: wgpu::ComputePipeline,
    args: wgpu::ComputePipeline,
    sparse: wgpu::ComputePipeline,
    pack: wgpu::ComputePipeline,
    palettes: wgpu::ComputePipeline,
}
struct Timing {
    queries: wgpu::QuerySet,
    resolve: wgpu::Buffer,
    readback: wgpu::Buffer,
}
struct Buffers {
    params: wgpu::Buffer,
    blocks: wgpu::Buffer,
    a: wgpu::Buffer,
    b: wgpu::Buffer,
    output: wgpu::Buffer,
    work: wgpu::Buffer,
    active: wgpu::Buffer,
    indirect: wgpu::Buffer,
    capacities: [u64; 4],
}
struct Readback {
    buffer: wgpu::Buffer,
    timing: Option<Timing>,
}
struct Pending {
    slot: Readback,
    submission: wgpu::SubmissionIndex,
    mapping: crate::gpu::Mapping,
    timing_map: Option<crate::gpu::Mapping>,
    size: u64,
    upload: u64,
    layout: Option<output::Layout>,
    palettes: Option<(usize, palettes::Layout)>,
}
impl Pending {
    fn finish(
        &mut self,
        device: &wgpu::Device,
        queue: &wgpu::Queue,
    ) -> Result<ResultLight, String> {
        device
            .poll(wgpu::PollType::Wait {
                submission_index: Some(self.submission.clone()),
                timeout: Some(Duration::from_secs(60)),
            })
            .map_err(|e| format!("lighting GPU poll: {e}"))?;
        self.mapping.check()?;
        if let Some(mapping) = &mut self.timing_map {
            mapping.check()?;
        }
        let bytes = self
            .slot
            .buffer
            .slice(..self.size)
            .get_mapped_range()
            .map_err(|e| e.to_string())?
            .to_vec();
        let device_nanos = if let Some(t) = &self.slot.timing {
            let data = t
                .readback
                .slice(..)
                .get_mapped_range()
                .map_err(|e| e.to_string())?;
            let ticks: &[u64] = bytemuck::cast_slice(&data);
            Some(std::array::from_fn(|i| {
                (ticks[i * 2 + 1].saturating_sub(ticks[i * 2]) as f64
                    * queue.get_timestamp_period() as f64) as u64
            }))
        } else {
            None
        };
        Ok(ResultLight {
            bytes,
            device_nanos,
            upload: self.upload,
            layout: self.layout.take(),
            palettes: self.palettes.take(),
        })
    }
}
pub(crate) enum OutputFormat {
    Voxels,
    Sections,
}
pub(crate) struct ResultLight {
    pub bytes: Vec<u8>,
    pub device_nanos: Option<[u64; 3]>,
    pub upload: u64,
    layout: Option<output::Layout>,
    palettes: Option<(usize, palettes::Layout)>,
}
impl Gpu {
    pub(crate) fn retire(&mut self, ids: &[u32]) {
        self.profiles.retain(|id, _| !ids.contains(id));
        self.bind_groups.retain(|id, _| !ids.contains(id));
    }

    pub(crate) fn new(device: wgpu::Device, queue: wgpu::Queue, backend: &str) -> Self {
        Self {
            device,
            queue,
            pipelines: None,
            readbacks: Vec::new(),
            profiles: HashMap::new(),
            bind_groups: HashMap::new(),
            buffers: None,
            compact_output: compact_transport(
                std::env::var("RETINA_LIGHTING_OUTPUT").ok().as_deref(),
                backend,
            ),
            compact_input: compact_transport(
                std::env::var("RETINA_LIGHTING_INPUT").ok().as_deref(),
                backend,
            ),
            section_palettes: section_palettes_enabled(
                std::env::var("RETINA_GPU_PALETTES").ok().as_deref(),
                backend,
            ),
        }
    }
    fn prepare(&mut self) {
        if self.pipelines.is_some() {
            return;
        }
        let layout = self
            .device
            .create_bind_group_layout(&wgpu::BindGroupLayoutDescriptor {
                label: Some("Retina final lighting"),
                entries: &(0..9)
                    .map(|binding| wgpu::BindGroupLayoutEntry {
                        binding,
                        visibility: wgpu::ShaderStages::COMPUTE,
                        ty: wgpu::BindingType::Buffer {
                            ty: if binding == 0 {
                                wgpu::BufferBindingType::Uniform
                            } else {
                                wgpu::BufferBindingType::Storage {
                                    read_only: binding < 4,
                                }
                            },
                            has_dynamic_offset: false,
                            min_binding_size: None,
                        },
                        count: None,
                    })
                    .collect::<Vec<_>>(),
            });
        let module = self
            .device
            .create_shader_module(wgpu::ShaderModuleDescriptor {
                label: Some("Retina packed GPU lighting"),
                source: wgpu::ShaderSource::Wgsl(include_str!("lighting.wgsl").into()),
            });
        let pipeline_layout = self
            .device
            .create_pipeline_layout(&wgpu::PipelineLayoutDescriptor {
                label: Some("Retina final lighting"),
                bind_group_layouts: &[Some(&layout)],
                immediate_size: 0,
            });
        let pipeline = |entry| {
            self.device
                .create_compute_pipeline(&wgpu::ComputePipelineDescriptor {
                    label: Some(entry),
                    layout: Some(&pipeline_layout),
                    module: &module,
                    entry_point: Some(entry),
                    compilation_options: Default::default(),
                    cache: None,
                })
        };
        self.pipelines = Some(Pipelines {
            init: pipeline("initialize"),
            spread: pipeline("spread"),
            compact: pipeline("compact"),
            args: pipeline("dispatch_args"),
            sparse: pipeline("spread_sparse"),
            pack: pipeline("pack"),
            palettes: pipeline("section_palettes"),
            layout,
        });
    }
    fn readback(&mut self, size: u64, maximum: u64) -> Readback {
        if let Some(slot) = self.readbacks.pop().filter(|s| s.buffer.size() >= size) {
            return slot;
        }
        Readback {
            buffer: self.device.create_buffer(&wgpu::BufferDescriptor {
                label: Some("Retina light readback slot"),
                size: size.next_power_of_two().min(maximum).max(size),
                usage: wgpu::BufferUsages::COPY_DST | wgpu::BufferUsages::MAP_READ,
                mapped_at_creation: false,
            }),
            timing: self
                .device
                .features()
                .contains(wgpu::Features::TIMESTAMP_QUERY)
                .then(|| Timing {
                    queries: self.device.create_query_set(&wgpu::QuerySetDescriptor {
                        label: Some("Retina light timing"),
                        ty: wgpu::QueryType::Timestamp,
                        count: 6,
                    }),
                    resolve: self.device.create_buffer(&wgpu::BufferDescriptor {
                        label: Some("Retina light resolve"),
                        size: 48,
                        usage: wgpu::BufferUsages::QUERY_RESOLVE | wgpu::BufferUsages::COPY_SRC,
                        mapped_at_creation: false,
                    }),
                    readback: self.device.create_buffer(&wgpu::BufferDescriptor {
                        label: Some("Retina light times"),
                        size: 48,
                        usage: wgpu::BufferUsages::MAP_READ | wgpu::BufferUsages::COPY_DST,
                        mapped_at_creation: false,
                    }),
                }),
        }
    }
    pub(crate) fn run(
        &mut self,
        handle: u32,
        profile: &Profile,
        chunks: u32,
        height: u32,
        crop: u32,
        core: u32,
        blocks: &[u16],
        format: OutputFormat,
    ) -> Result<ResultLight, String> {
        let pending = self.submit(handle, profile, chunks, height, crop, core, blocks, format)?;
        self.complete(pending)
    }
    /// Unpadded chunk-major materials; the GPU supplies the two air sections.
    /// Crop/core are measured in chunks. No CPU propagation.
    fn submit(
        &mut self,
        handle: u32,
        profile: &Profile,
        chunks: u32,
        height: u32,
        crop: u32,
        core: u32,
        blocks: &[u16],
        format: OutputFormat,
    ) -> Result<Pending, String> {
        if chunks == 0
            || height == 0
            || core == 0
            || crop + core > chunks
            || blocks.len() != chunks as usize * chunks as usize * height as usize * 256
        {
            return Err("invalid GPU lighting volume".into());
        }
        if self.compact_input && height % 16 == 0 {
            let descriptors = transport::classify(blocks);
            let mut words = Vec::new();
            transport::pack(
                &mut words,
                blocks
                    .chunks_exact(height as usize * 256)
                    .zip(descriptors.chunks_exact(height as usize / 16)),
                height,
            );
            self.submit_payload(
                handle,
                profile,
                chunks,
                height,
                crop,
                core,
                bytemuck::cast_slice(&words),
                true,
                format,
                None,
                None,
            )
        } else {
            self.submit_payload(
                handle,
                profile,
                chunks,
                height,
                crop,
                core,
                bytemuck::cast_slice(blocks),
                false,
                format,
                None,
                None,
            )
        }
    }
    fn submit_payload(
        &mut self,
        handle: u32,
        profile: &Profile,
        chunks: u32,
        height: u32,
        crop: u32,
        core: u32,
        blocks: &[u8],
        compact: bool,
        format: OutputFormat,
        layout: Option<output::Layout>,
        palettes: Option<palettes::Layout>,
    ) -> Result<Pending, String> {
        self.prepare();
        let mut upload = 0;
        if !self.profiles.contains_key(&handle) {
            let buffer = |label, contents| {
                self.device
                    .create_buffer_init(&wgpu::util::BufferInitDescriptor {
                        label: Some(label),
                        contents,
                        usage: wgpu::BufferUsages::STORAGE,
                    })
            };
            let states = bytemuck::cast_slice(&profile.states);
            let blocked = bytemuck::cast_slice(&profile.blocked);
            upload += (states.len() + blocked.len()) as u64;
            self.profiles.insert(
                handle,
                (
                    buffer("Retina light states", states),
                    buffer("Retina light faces", blocked),
                ),
            );
        }
        // Dense reference mode uses the same attenuation/face rules, for diagnostics.
        let sparse = std::env::var("RETINA_LIGHTING_DENSE").as_deref() != Ok("1");
        let light_height = height + 32;
        let voxels = chunks as u64 * chunks as u64 * light_height as u64 * 256;
        let bricks = (chunks * 2).pow(2) * light_height.div_ceil(8);
        let dense_output = core as u64 * core as u64 * light_height as u64 * 256;
        let layout_bytes: &[u8] = layout
            .as_ref()
            .map_or(&[], |v| bytemuck::cast_slice(&v.source_channels));
        let palette_sources: &[u8] = palettes
            .as_ref()
            .map_or(&[], |v| bytemuck::cast_slice(&v.sources));
        let light_bytes = layout.as_ref().map_or(dense_output, |v| v.bytes as u64);
        let palette_bytes = palettes.as_ref().map_or(0, |v| v.bytes() as u64);
        let output_maximum = dense_output + palette_bytes;
        let sizes = [
            (blocks.len() + layout_bytes.len() + palette_sources.len()) as u64,
            voxels,
            light_bytes + palette_bytes,
            bricks as u64 * 4,
        ];
        // Compact input/output sizes vary between tiles. Grow them independently
        // while the lighting frontiers fit. Geometric capacity avoids repeated
        // reallocations; output is bounded by dense light plus palette records.
        let input_capacity = sizes[0]
            .next_power_of_two()
            .min(self.device.limits().max_storage_buffer_binding_size as u64)
            .max(sizes[0]);
        let output_capacity = sizes[2].next_power_of_two().min(output_maximum).max(sizes[2]);
        if let Some(buffers) = &mut self.buffers {
            if buffers.capacities[1] >= sizes[1] && buffers.capacities[3] >= sizes[3] {
                for (index, capacity, buffer, label, usage) in [
                    (
                        0,
                        input_capacity,
                        &mut buffers.blocks,
                        "Retina final light blocks",
                        wgpu::BufferUsages::STORAGE | wgpu::BufferUsages::COPY_DST,
                    ),
                    (
                        2,
                        output_capacity,
                        &mut buffers.output,
                        "Retina packed light",
                        wgpu::BufferUsages::STORAGE | wgpu::BufferUsages::COPY_SRC,
                    ),
                ] {
                    if buffers.capacities[index] < sizes[index] {
                        *buffer = self.device.create_buffer(&wgpu::BufferDescriptor {
                            label: Some(label),
                            size: capacity,
                            usage,
                            mapped_at_creation: false,
                        });
                        buffers.capacities[index] = capacity;
                        self.bind_groups.clear();
                    }
                }
            }
        }
        if self
            .buffers
            .as_ref()
            .is_none_or(|b| (0..4).any(|i| b.capacities[i] < sizes[i]))
        {
            let capacities = std::array::from_fn(|i| {
                (match i {
                    0 => input_capacity,
                    2 => output_capacity,
                    _ => sizes[i],
                })
                .max(self.buffers.as_ref().map_or(4, |b| b.capacities[i]))
            });
            let buffer = |label, size, usage| {
                self.device.create_buffer(&wgpu::BufferDescriptor {
                    label: Some(label),
                    size,
                    usage,
                    mapped_at_creation: false,
                })
            };
            self.buffers = Some(Buffers {
                params: buffer(
                    "Retina light params",
                    48,
                    wgpu::BufferUsages::UNIFORM | wgpu::BufferUsages::COPY_DST,
                ),
                blocks: buffer(
                    "Retina final light blocks",
                    capacities[0],
                    wgpu::BufferUsages::STORAGE | wgpu::BufferUsages::COPY_DST,
                ),
                a: buffer(
                    "Retina light frontier A",
                    capacities[1],
                    wgpu::BufferUsages::STORAGE,
                ),
                b: buffer(
                    "Retina light frontier B",
                    capacities[1],
                    wgpu::BufferUsages::STORAGE,
                ),
                output: buffer(
                    "Retina packed light",
                    capacities[2],
                    wgpu::BufferUsages::STORAGE | wgpu::BufferUsages::COPY_SRC,
                ),
                work: buffer(
                    "Retina light brick classification / indirect dispatch",
                    capacities[3] + 16,
                    wgpu::BufferUsages::STORAGE
                        | wgpu::BufferUsages::COPY_DST
                        | wgpu::BufferUsages::COPY_SRC,
                ),
                active: buffer(
                    "Retina active light bricks",
                    capacities[3],
                    wgpu::BufferUsages::STORAGE,
                ),
                indirect: buffer(
                    "Retina light dispatch args",
                    12,
                    wgpu::BufferUsages::COPY_DST | wgpu::BufferUsages::INDIRECT,
                ),
                capacities,
            });
            // Bind groups retain their buffers, so growing any shared allocation
            // invalidates every profile's cached pair.
            self.bind_groups.clear();
        }
        let slot = self.readback(sizes[2], output_maximum);
        let buffers = self.buffers.as_ref().unwrap();
        let pipelines = self.pipelines.as_ref().unwrap();
        let bind_groups = self.bind_groups.entry(handle).or_insert_with(|| {
            let resident = &self.profiles[&handle];
            let bind = |read: &wgpu::Buffer, write: &wgpu::Buffer| {
                let buffers = [
                    &buffers.params,
                    &buffers.blocks,
                    &resident.0,
                    &resident.1,
                    read,
                    write,
                    &buffers.output,
                    &buffers.work,
                    &buffers.active,
                ];
                let entries: [wgpu::BindGroupEntry<'_>; 9] =
                    std::array::from_fn(|i| wgpu::BindGroupEntry {
                        binding: i as u32,
                        resource: buffers[i].as_entire_binding(),
                    });
                self.device.create_bind_group(&wgpu::BindGroupDescriptor {
                    label: Some("Retina light volume"),
                    layout: &pipelines.layout,
                    entries: &entries,
                })
            };
            (bind(&buffers.a, &buffers.b), bind(&buffers.b, &buffers.a))
        });
        let (ab, ba) = (&bind_groups.0, &bind_groups.1);
        let params = [
            chunks * 16,
            light_height,
            chunks,
            crop,
            core,
            profile.sky as u32
                | if layout.is_some() { 16 } else { 0 }
                | if compact { 8 } else { 0 }
                | if sparse { 2 } else { 0 }
                | if matches!(format, OutputFormat::Sections) {
                    4
                } else {
                    0
                },
            profile.faces.div_ceil(32),
            (voxels / 4) as u32,
            (blocks.len() / 4) as u32,
            layout
                .as_ref()
                .map_or(0, |v| v.source_channels.len() as u32),
            ((blocks.len() + layout_bytes.len()) / 4) as u32,
            (light_bytes / 4) as u32,
        ];
        self.queue
            .write_buffer(&buffers.params, 0, bytemuck::cast_slice(&params));
        self.queue.write_buffer(&buffers.blocks, 0, blocks);
        if !layout_bytes.is_empty() {
            self.queue
                .write_buffer(&buffers.blocks, blocks.len() as u64, layout_bytes);
        }
        if !palette_sources.is_empty() {
            self.queue.write_buffer(
                &buffers.blocks,
                (blocks.len() + layout_bytes.len()) as u64,
                palette_sources,
            );
        }
        upload += sizes[0] + 48;
        let queries = slot.timing.as_ref().map(|t| &t.queries);
        let times = slot.timing.as_ref().map(|t| (&t.resolve, &t.readback));
        let mut encoder = self
            .device
            .create_command_encoder(&wgpu::CommandEncoderDescriptor {
                label: Some("Retina final lighting"),
            });
        let writes = |pair: u32| {
            queries.map(|q| wgpu::ComputePassTimestampWrites {
                query_set: q,
                beginning_of_pass_write_index: Some(pair * 2),
                end_of_pass_write_index: Some(pair * 2 + 1),
            })
        };
        if sparse {
            encoder.clear_buffer(&buffers.work, 0, None);
        }
        {
            let mut pass = encoder.begin_compute_pass(&wgpu::ComputePassDescriptor {
                label: Some("Retina direct sky / emissions"),
                timestamp_writes: writes(0),
            });
            pass.set_pipeline(&pipelines.init);
            pass.set_bind_group(0, ab, &[]);
            pass.dispatch_workgroups((chunks * chunks * 64).div_ceil(64), 1, 1);
        }
        if sparse {
            let mut pass = encoder.begin_compute_pass(&wgpu::ComputePassDescriptor {
                label: Some("Retina compact active light bricks"),
                timestamp_writes: queries.map(|q| wgpu::ComputePassTimestampWrites {
                    query_set: q,
                    beginning_of_pass_write_index: Some(2),
                    end_of_pass_write_index: None,
                }),
            });
            pass.set_pipeline(&pipelines.compact);
            pass.set_bind_group(0, ab, &[]);
            pass.dispatch_workgroups(bricks.div_ceil(64), 1, 1);
        }
        if sparse {
            let mut pass = encoder.begin_compute_pass(&wgpu::ComputePassDescriptor {
                label: Some("Retina light indirect arguments"),
                timestamp_writes: None,
            });
            pass.set_pipeline(&pipelines.args);
            pass.set_bind_group(0, ab, &[]);
            pass.dispatch_workgroups(1, 1, 1);
        }
        if sparse {
            // A storage-writable binding cannot also be used as indirect arguments.
            encoder.copy_buffer_to_buffer(&buffers.work, 0, &buffers.indirect, 0, 12);
        }
        // 15 -> 1 takes fourteen edges; all source columns were initialized together.
        // Separate passes provide storage visibility between ping-pong frontiers.
        for iteration in 0..14 {
            let mut pass = encoder.begin_compute_pass(&wgpu::ComputePassDescriptor {
                label: Some("Retina bounded light propagation"),
                timestamp_writes: queries.and_then(|q| {
                    if iteration == 0 && !sparse || iteration == 13 {
                        Some(wgpu::ComputePassTimestampWrites {
                            query_set: q,
                            beginning_of_pass_write_index: (iteration == 0 && !sparse).then_some(2),
                            end_of_pass_write_index: (iteration == 13).then_some(3),
                        })
                    } else {
                        None
                    }
                }),
            });
            pass.set_pipeline(if sparse {
                &pipelines.sparse
            } else {
                &pipelines.spread
            });
            pass.set_bind_group(0, if iteration % 2 == 0 { ba } else { ab }, &[]);
            if sparse {
                pass.dispatch_workgroups_indirect(&buffers.indirect, 0);
            } else {
                pass.dispatch_workgroups(256, params[7].div_ceil(16384), 1);
            }
        }
        {
            let mut pass = encoder.begin_compute_pass(&wgpu::ComputePassDescriptor {
                label: Some("Retina pack interior light"),
                timestamp_writes: if palette_bytes > 0 {
                    queries.map(|q| wgpu::ComputePassTimestampWrites {
                        query_set: q,
                        beginning_of_pass_write_index: Some(4),
                        end_of_pass_write_index: None,
                    })
                } else {
                    writes(2)
                },
            });
            pass.set_pipeline(&pipelines.pack);
            pass.set_bind_group(0, ba, &[]);
            pass.dispatch_workgroups(256, (light_bytes as u32 / 4).div_ceil(16384), 1);
        }
        if let Some(layout) = &palettes {
            if !layout.sources.is_empty() {
                let mut pass = encoder.begin_compute_pass(&wgpu::ComputePassDescriptor {
                    label: Some("Retina final section palettes"),
                    timestamp_writes: queries.map(|q| wgpu::ComputePassTimestampWrites {
                        query_set: q,
                        beginning_of_pass_write_index: None,
                        end_of_pass_write_index: Some(5),
                    }),
                });
                pass.set_pipeline(&pipelines.palettes);
                pass.set_bind_group(0, ba, &[]);
                pass.dispatch_workgroups(layout.sources.len() as u32, 1, 1);
            }
        }
        encoder.copy_buffer_to_buffer(&buffers.output, 0, &slot.buffer, 0, sizes[2]);
        if let (Some(q), Some((resolve, readback))) = (queries, &times) {
            encoder.resolve_query_set(q, 0..6, resolve, 0);
            encoder.copy_buffer_to_buffer(resolve, 0, readback, 0, 48);
        }
        let submission = self.queue.submit([encoder.finish()]);
        let mapping = crate::gpu::Mapping::new(&slot.buffer, sizes[2]);
        let timing_map = slot
            .timing
            .as_ref()
            .map(|t| crate::gpu::Mapping::new(&t.readback, 48));
        Ok(Pending {
            slot,
            submission,
            mapping,
            timing_map,
            size: sizes[2],
            upload,
            layout,
            palettes: palettes.map(|layout| (light_bytes as usize, layout)),
        })
    }
    fn complete(&mut self, mut pending: Pending) -> Result<ResultLight, String> {
        let result = pending.finish(&self.device, &self.queue);
        // Always unmap, including map/poll failures. An abandoned pending slot
        // also unmaps on drop, so failure never poisons the next region.
        drop(pending.mapping);
        drop(pending.timing_map);
        if result.is_ok() && self.readbacks.len() < 2 {
            self.readbacks.push(pending.slot);
        }
        result
    }
}

/// Chunk indices and section-packed bytes share one tile allocation.
/// Consumers can encode a completed tile while later tiles execute on the GPU.
pub(crate) struct Tile {
    pub slots: Vec<usize>,
    pub bytes: Vec<u8>,
    pub chunk_bytes: usize,
    layout: Option<output::Layout>,
    palettes: Option<(usize, palettes::Layout)>,
}
impl Tile {
    pub(crate) fn chunk(&self, index: usize) -> ChunkLight<'_> {
        let mut result = if let Some(layout) = &self.layout {
            let count = self.chunk_bytes / 2048;
            ChunkLight::new(
                &self.bytes,
                Some(&layout.descriptors[index * count..(index + 1) * count]),
            )
        } else {
            ChunkLight::new(
                &self.bytes[index * self.chunk_bytes..(index + 1) * self.chunk_bytes],
                None,
            )
        };
        if let Some((offset, layout)) = &self.palettes {
            let sections = self.chunk_bytes / 4096 - 2;
            result.palettes = Some(palettes::ChunkPalettes {
                bytes: &self.bytes[*offset..],
                descriptors: &layout.descriptors[index * sections..(index + 1) * sections],
            });
        }
        result
    }
}

/// Reuses only the 32x32 region's final blocks; a one-chunk ring guarantees the core.
/// At most two readbacks per region are in flight; scratch buffers remain shared on the ordered queue.
pub(crate) fn region<B: crate::material_blocks::Read>(
    engine: &crate::TerrainEngine,
    request: crate::ChunkRequest,
    profile: &Profile,
    chunks: &[B],
    job: &crate::timings::Timings,
    mut consume: impl FnMut(Tile) -> Result<(), String>,
) -> Result<(), String> {
    let start = Instant::now();
    let mut consuming = Duration::ZERO;
    let gpu = engine
        .light_gpu
        .lock()
        .map_err(|_| "lighting GPU lock poisoned")?;
    let core = [10usize, 6, 5, 3, 2, 1]
        .into_iter()
        .find(|&core| {
            (core + 2).pow(2) as u64 * (request.height as u64 + 32) * 512
                <= gpu.device.limits().max_storage_buffer_binding_size as u64
        })
        .unwrap_or(1);
    let device = gpu.device.clone();
    let queue = gpu.queue.clone();
    let compact = gpu.compact_input && request.height % 16 == 0;
    let compact_output = gpu.compact_output && request.height % 16 == 0;
    let gpu_palettes = gpu.section_palettes;
    drop(gpu);
    // Classify final sections once per region, including the input-only ring.
    // Overlapping tiles reuse these facts instead of rescanning their halos.
    let descriptors: Vec<_> = if compact || compact_output || gpu_palettes {
        chunks.iter().map(|b| transport::classify(b)).collect()
    } else {
        Vec::new()
    };
    let constants = compact_output.then(|| output::Constants::new(profile, &descriptors, 32));
    let mut packed = Vec::new();
    let count = (request.height as usize + 32) * 256;
    let mut input = Vec::new();
    if !compact {
        input.reserve((core + 2).pow(2) * request.block_count());
    }
    let mut tiles = (1..31)
        .step_by(core)
        .flat_map(|cz| (1..31).step_by(core).map(move |cx| (cx, cz)));
    let mut flights = VecDeque::new();
    loop {
        // Submit ahead before CPU encoding; GPU scratch reuse is safe because
        // write_buffer + submit preserve queue order, and each flight owns its readback.
        while flights.len() < 2 {
            let Some((cx, cz)) = tiles.next() else { break };
            let indices: Vec<_> = (cz - 1..cz + core + 1)
                .flat_map(|z| (cx - 1..cx + core + 1).map(move |x| z * 32 + x))
                .collect();
            let payload = if compact {
                transport::pack(
                    &mut packed,
                    indices
                        .iter()
                        .map(|&i| (&chunks[i], &descriptors[i][..])),
                    request.height,
                );
                bytemuck::cast_slice(&packed)
            } else {
                input.clear();
                for i in indices {
                    chunks[i].extend_dense(&mut input);
                }
                bytemuck::cast_slice(&input)
            };
            if crate::performance::enabled() {
                engine.timings(request.reserved).performance.add(
                    crate::performance::Counter::lighting_material_upload_bytes, payload.len() as u64);
            }
            let layout = constants.as_ref().map(|c| {
                c.tile((0..core).flat_map(|z| (0..core).map(move |x| (cz + z) * 32 + cx + x)))
            });
            let palette_layout = gpu_palettes.then(|| {
                let materials: Vec<_> = (cz - 1..cz + core + 1)
                    .flat_map(|z| (cx - 1..cx + core + 1).map(move |x| z * 32 + x))
                    .map(|i| descriptors[i].clone())
                    .collect();
                palettes::Layout::new(&materials, core + 2, 1, core)
            });
            let pending = engine
                .light_gpu
                .lock()
                .map_err(|_| "lighting GPU lock poisoned")?
                .submit_payload(
                    request.reserved,
                    profile,
                    (core + 2) as u32,
                    request.height,
                    1,
                    core as u32,
                    payload,
                    compact,
                    OutputFormat::Sections,
                    layout,
                    palette_layout,
                )?;
            flights.push_back((cx, cz, pending));
        }
        let Some((cx, cz, mut pending)) = flights.pop_front() else {
            break;
        };
        let result = pending.finish(&device, &queue)?;
        drop(pending.mapping);
        drop(pending.timing_map);
        {
            let mut gpu = engine
                .light_gpu
                .lock()
                .map_err(|_| "lighting GPU lock poisoned")?;
            if gpu.readbacks.len() < 2 {
                gpu.readbacks.push(pending.slot);
            }
        }
        engine
            .pipeline
            .transfer(request.reserved, result.upload, result.bytes.len() as u64);
        if let Some(nanos) = result.device_nanos {
            for (i, value) in nanos.into_iter().enumerate() {
                engine
                    .timings(request.reserved)
                    .device(crate::timings::LIGHT_SKY + i, value);
                job.device(crate::timings::LIGHT_SKY + i, value);
            }
        }
        let slots = (0..core)
            .flat_map(|z| (0..core).map(move |x| (cz + z) * 32 + cx + x))
            .collect();
        let consuming_start = Instant::now();
        consume(Tile {
            slots,
            bytes: result.bytes,
            chunk_bytes: count,
            layout: result.layout,
            palettes: result.palettes,
        })?;
        consuming += consuming_start.elapsed();
    }
    // Encoding is accounted by its worker phase, not twice as lighting host time.
    let elapsed = start.elapsed().saturating_sub(consuming).as_nanos() as u64;
    engine
        .timings(request.reserved)
        .add(crate::timings::LIGHT_HOST, elapsed);
    job.add(crate::timings::LIGHT_HOST, elapsed);
    Ok(())
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn compact_transport_defaults_use_the_backend_with_adapter_name() {
        for selected in [None, Some("unknown")] {
            assert!(compact_transport(selected, "Metal: Apple M4 Max"));
            assert!(!compact_transport(selected, "Vulkan: GPU"));
            assert!(!compact_transport(selected, "Dx12: GPU"));
        }
        assert!(!compact_transport(Some("dense"), "Metal: Apple M4 Max"));
        assert!(compact_transport(Some("compact"), "Vulkan: GPU"));
    }

    #[test]
    fn palette_defaults_and_cpu_override_follow_measured_backend() {
        for selected in [None, Some("unknown")] {
            assert!(section_palettes_enabled(selected, "Metal: Apple M4 Max"));
            assert!(!section_palettes_enabled(selected, "Vulkan: GPU"));
            assert!(!section_palettes_enabled(selected, "Dx12: GPU"));
        }
        assert!(!section_palettes_enabled(Some("0"), "Metal: Apple M4 Max"));
        assert!(section_palettes_enabled(Some("1"), "Vulkan: GPU"));
    }

    pub(super) fn fixture_profile(sky: bool) -> Profile {
        let mut states = vec![[0; 8]; 4];
        states[1][0] = 15; // opaque
        states[2][0] = 240; // emitter
        states[3][0] = 1; // attenuating transparent material
        Profile {
            sky,
            faces: 1,
            states,
            blocked: vec![0],
        }
    }
    fn fixture_blocks(chunks: usize, height: usize, salt: usize) -> Vec<u16> {
        (0..chunks * chunks * height * 256)
            .map(|i| {
                let y = (i / 256) % height;
                if y < height / 3 && (i * 17 + salt) % 19 > 2 {
                    1
                } else if (i * 13 + salt) % 977 == 0 {
                    2
                } else if y == height / 2 && (i + salt) % 7 == 0 {
                    3
                } else {
                    0
                }
            })
            .collect()
    }
    #[test]
    #[ignore = "requires a supported GPU"]
    fn pipeline_slots_survive_growth_interleaving_and_abandonment() {
        let engine = crate::TerrainEngine::new().unwrap();
        let mut gpu = engine.light_gpu.lock().unwrap();
        gpu.compact_input = false;
        let profiles = [fixture_profile(true), fixture_profile(false)];
        let small = fixture_blocks(3, 32, 3);
        let large = fixture_blocks(4, 64, 7);
        let expected_a = gpu
            .run(1, &profiles[0], 3, 32, 1, 1, &small, OutputFormat::Sections)
            .unwrap()
            .bytes;
        let expected_b = gpu
            .run(2, &profiles[1], 4, 64, 0, 4, &large, OutputFormat::Sections)
            .unwrap()
            .bytes;
        gpu.buffers = None;
        gpu.bind_groups.clear();
        gpu.readbacks.clear();
        gpu.compact_input = true;
        let a = gpu
            .submit(1, &profiles[0], 3, 32, 1, 1, &small, OutputFormat::Sections)
            .unwrap();
        gpu.compact_input = false;
        let b = gpu
            .submit(2, &profiles[1], 4, 64, 0, 4, &large, OutputFormat::Sections)
            .unwrap();
        assert_eq!(gpu.complete(b).unwrap().bytes, expected_b);
        assert_eq!(gpu.complete(a).unwrap().bytes, expected_a);
        gpu.compact_input = true;
        let abandoned = gpu
            .submit(1, &profiles[0], 3, 32, 1, 1, &small, OutputFormat::Sections)
            .unwrap();
        drop(abandoned);
        assert_eq!(
            gpu.run(2, &profiles[1], 4, 64, 0, 4, &large, OutputFormat::Sections)
                .unwrap()
                .bytes,
            expected_b
        );
        assert!(gpu.readbacks.len() <= 2);
    }
    #[test]
    #[ignore = "requires a supported GPU"]
    fn streamed_region_matches_sequential_tiles_and_recovers_from_consumer_error() {
        let engine = crate::TerrainEngine::new().unwrap();
        let profile = fixture_profile(true);
        let request = crate::ChunkRequest {
            seed: 1,
            chunk_x: -32,
            chunk_z: 32,
            min_y: -16,
            height: 32,
            base_height: 0.,
            amplitude: 0.,
            frequency: 0.01,
            reserved: 1,
        };
        let chunks: Vec<_> = (0..1024).map(|i| fixture_blocks(1, 32, i)).collect();
        let mut expected = vec![None; 1024];
        {
            let mut gpu = engine.light_gpu.lock().unwrap();
            gpu.compact_input = false;
            for cz in (1..31).step_by(10) {
                for cx in (1..31).step_by(10) {
                    let mut input = Vec::new();
                    for z in cz - 1..cz + 11 {
                        for x in cx - 1..cx + 11 {
                            input.extend_from_slice(&chunks[z * 32 + x]);
                        }
                    }
                    let result = gpu
                        .run(1, &profile, 12, 32, 1, 10, &input, OutputFormat::Sections)
                        .unwrap();
                    for z in 0..10 {
                        for x in 0..10 {
                            let index = (z * 10 + x) * 64 * 256;
                            expected[(cz + z) * 32 + cx + x] =
                                Some(result.bytes[index..index + 64 * 256].to_vec());
                        }
                    }
                }
            }
        }
        let job = crate::timings::Timings::default();
        engine.light_gpu.lock().unwrap().compact_input = true;
        engine.light_gpu.lock().unwrap().compact_output = true;
        assert!(
            region(&engine, request, &profile, &chunks, &job, |_| Err(
                "consumer failure".into()
            ))
            .is_err()
        );
        let mut seen = vec![false; 1024];
        region(&engine, request, &profile, &chunks, &job, |tile| {
            assert!(
                engine.light_gpu.try_lock().is_ok(),
                "consumer must not hold GPU mutex"
            );
            for (index, &slot) in tile.slots.iter().enumerate() {
                assert!(!seen[slot]);
                seen[slot] = true;
                assert_eq!(
                    &tile.chunk(index).expanded(tile.chunk_bytes / 4096),
                    expected[slot].as_ref().unwrap()
                );
            }
            Ok(())
        })
        .unwrap();
        assert_eq!(seen.iter().filter(|&&v| v).count(), 900);
        for i in 0..1024 {
            assert_eq!(seen[i], expected[i].is_some());
        }
    }
    #[test]
    #[ignore = "bounded exact constant-light output and descriptor ownership check"]
    fn compact_output_preserves_every_channel_and_pending_layout() {
        let engine = crate::TerrainEngine::new().unwrap();
        let mut gpu = engine.light_gpu.lock().unwrap();
        let mut pending = Vec::new();
        for (case, (side, height, crop, core)) in [
            (1usize, 16u32, 0usize, 1usize),
            (3, 64, 1, 1),
            (4, 80, 1, 2),
        ]
        .into_iter()
        .enumerate()
        {
            for sky in [false, true] {
                for pattern in 0..8 {
                    let mut profile = fixture_profile(sky);
                    profile.faces = 2;
                    profile.blocked = vec![2, 1];
                    profile.states.push([239, 0, 0, 0, 0, 0, 0, 0]);
                    profile.states.push([0, 1, 0, 1, 0, 1, 0, 0]);
                    if pattern == 4 {
                        profile.blocked[0] |= 1;
                    }
                    if pattern == 5 {
                        profile.states[0][0] = 240; // Registered air can emit.
                    } else if pattern == 6 {
                        profile.states[0][0] = 2; // Or attenuate direct sky.
                    }
                    let handle = 20 + (case * 16 + usize::from(sky) * 8 + pattern) as u32;
                    let mut blocks = vec![0u16; side * side * height as usize * 256];
                    for (i, material) in blocks.iter_mut().enumerate() {
                        let y = (i / 256) % height as usize;
                        *material = match pattern {
                            0 | 4 | 5 | 6 => 0,
                            7 => u16::from(i == height as usize * 256 + 4095) * 2,
                            1 => 1,
                            2 => 4,
                            _ if y < 16 => 1,
                            _ if y < 32 => ((i * 17 + i / 16) % 6) as u16,
                            _ => 0,
                        };
                    }
                    let expected = gpu
                        .run(
                            handle,
                            &profile,
                            side as u32,
                            height,
                            crop as u32,
                            core as u32,
                            &blocks,
                            OutputFormat::Sections,
                        )
                        .unwrap()
                        .bytes;
                    let metadata: Vec<_> = blocks
                        .chunks_exact(height as usize * 256)
                        .map(transport::classify)
                        .collect();
                    let constants = output::Constants::new(&profile, &metadata, side);
                    let layout =
                        constants
                            .tile((0..core).flat_map(|z| {
                                (0..core).map(move |x| (crop + z) * side + crop + x)
                            }));
                    assert!(layout.bytes <= expected.len());
                    if pattern == 0 {
                        assert_eq!(layout.bytes, 4, "empty volume is fully constant");
                    }
                    let flight = gpu
                        .submit_payload(
                            handle,
                            &profile,
                            side as u32,
                            height,
                            crop as u32,
                            core as u32,
                            bytemuck::cast_slice(&blocks),
                            false,
                            OutputFormat::Sections,
                            Some(layout),
                            None,
                        )
                        .unwrap();
                    pending.push((flight, expected, core, (height as usize + 32) * 256));
                    // Reverse completion while following submissions reuse the shared
                    // frontiers/output, and sizes/profiles change between batches.
                    if pending.len() == 2 {
                        while let Some((flight, expected, core, chunk_bytes)) = pending.pop() {
                            let result = gpu.complete(flight).unwrap();
                            let tile = Tile {
                                slots: Vec::new(),
                                bytes: result.bytes,
                                chunk_bytes,
                                layout: result.layout,
                                palettes: result.palettes,
                            };
                            let actual: Vec<_> = (0..core * core)
                                .flat_map(|i| tile.chunk(i).expanded(chunk_bytes / 4096))
                                .collect();
                            assert_eq!(actual, expected, "case={case} sky={sky} pattern={pattern}");
                        }
                    }
                }
            }
        }
        assert!(pending.is_empty());
    }
    #[test]
    #[ignore = "bounded lossless compact-material GPU comparison"]
    fn compact_materials_preserve_light_with_faces_padding_and_uniform_sections() {
        let engine = crate::TerrainEngine::new().unwrap();
        let mut gpu = engine.light_gpu.lock().unwrap();
        for sky in [false, true] {
            let mut profile = fixture_profile(sky);
            profile.faces = 2;
            profile.blocked = vec![2, 1];
            profile.states.push([0, 1, 0, 1, 0, 1, 0, 0]);
            let handle = 10 + u32::from(sky);
            for (chunks, height, crop, core, uniform) in [
                (1, 16, 0, 1, true),
                (3, 64, 1, 1, false),
                (2, 32, 0, 2, true),
                (4, 80, 1, 2, false),
            ] {
                let blocks: Vec<u16> = (0..chunks * chunks * height * 256)
                    .map(|i| {
                        let section = (i / 4096) % (height / 16);
                        if uniform || section % 3 != 2 {
                            (section % 5) as u16
                        } else {
                            ((i * 17 + i / 16) % 5) as u16
                        }
                    })
                    .collect();
                gpu.compact_input = false;
                let expected = gpu
                    .run(
                        handle,
                        &profile,
                        chunks,
                        height,
                        crop,
                        core,
                        &blocks,
                        OutputFormat::Sections,
                    )
                    .unwrap();
                gpu.compact_input = true;
                let actual = gpu
                    .run(
                        handle,
                        &profile,
                        chunks,
                        height,
                        crop,
                        core,
                        &blocks,
                        OutputFormat::Sections,
                    )
                    .unwrap();
                assert_eq!(
                    actual.bytes, expected.bytes,
                    "sky={sky} chunks={chunks} height={height} crop={crop}"
                );
                assert!(actual.upload < expected.upload);
            }
        }
    }
    #[test]
    #[ignore = "bounded GPU replay timing; not a world-generation benchmark"]
    fn pipeline_replay_timing() {
        let engine = crate::TerrainEngine::new().unwrap();
        let mut gpu = engine.light_gpu.lock().unwrap();
        let (profiles, inputs, height): (Vec<Profile>, Vec<Vec<u16>>, u32) = if let Ok(directory) =
            std::env::var("RETINA_LIGHTING_FIXTURES")
        {
            let mut profiles = Vec::new();
            let mut inputs = Vec::new();
            for name in ["region-0.json", "region-10.json", "region-20.json"] {
                let directory = std::path::Path::new(&directory);
                let fixture: serde_json::Value =
                    serde_json::from_slice(&std::fs::read(directory.join(name)).unwrap()).unwrap();
                assert_eq!(fixture["side"], 12);
                assert_eq!(fixture["height"], 384);
                profiles
                    .push(serde_json::from_value(fixture["profile"]["lighting"].clone()).unwrap());
                let raw =
                    std::fs::read(directory.join(fixture["blocks"].as_str().unwrap())).unwrap();
                inputs.push(
                    raw.chunks_exact(2)
                        .map(|p| u16::from_le_bytes([p[0], p[1]]))
                        .collect(),
                );
            }
            (profiles, inputs, 384)
        } else {
            (
                (0..3).map(|_| fixture_profile(true)).collect(),
                (0..3).map(|salt| fixture_blocks(12, 128, salt)).collect(),
                128,
            )
        };
        let mut expected: Vec<Vec<u8>> = Vec::new();
        let mut samples = [Vec::new(), Vec::new()];
        for iteration in 0..6 {
            for depth in if iteration % 2 == 0 { [1, 2] } else { [2, 1] } {
                let start = Instant::now();
                let mut pending = VecDeque::new();
                let mut next = 0;
                let mut outputs = Vec::new();
                loop {
                    while pending.len() < depth && next < inputs.len() {
                        pending.push_back(
                            gpu.submit(
                                next as u32 + 1,
                                &profiles[next],
                                12,
                                height,
                                1,
                                10,
                                &inputs[next],
                                OutputFormat::Sections,
                            )
                            .unwrap(),
                        );
                        next += 1;
                    }
                    let Some(p) = pending.pop_front() else { break };
                    outputs.push(gpu.complete(p).unwrap().bytes);
                }
                let elapsed = start.elapsed().as_secs_f64() * 1000.;
                if expected.is_empty() {
                    expected = outputs;
                } else {
                    assert_eq!(outputs, expected);
                }
                if iteration > 0 {
                    samples[depth - 1].push(elapsed);
                }
            }
        }
        println!(
            "lighting tile replay milliseconds: sequential={:?}, depth_two={:?}; all bytes equal",
            samples[0], samples[1]
        );
    }
    #[test]
    #[ignore = "requires a supported GPU"]
    fn section_packing_matches_voxels_with_crop_padding_and_reuse() {
        let engine = crate::TerrainEngine::new().unwrap();
        let mut gpu = engine.light_gpu.lock().unwrap();
        for sky in [true, false] {
            let mut states = vec![[0; 8]; 4];
            states[1][0] = 15;
            states[2][0] = 240;
            states[3][0] = 1;
            let profile = Profile {
                sky,
                faces: 1,
                states,
                blocked: vec![0],
            };
            let handle = 1 + u32::from(sky);
            for (chunks, height, crop, core, empty) in [
                (1, 16, 0, 1, true),
                (2, 32, 0, 2, false),
                (3, 48, 1, 1, false),
                (3, 16, 0, 3, false),
            ] {
                let blocks: Vec<u16> = (0..chunks * chunks * height * 256)
                    .map(|i| {
                        if empty {
                            0
                        } else {
                            match (i * 17 + i / 16) % 103 {
                                0..=2 => 2,
                                3..=44 => 1,
                                45..=60 => 3,
                                _ => 0,
                            }
                        }
                    })
                    .collect();
                let voxels = gpu
                    .run(
                        handle,
                        &profile,
                        chunks,
                        height,
                        crop,
                        core,
                        &blocks,
                        OutputFormat::Voxels,
                    )
                    .unwrap();
                let packed = gpu
                    .run(
                        handle,
                        &profile,
                        chunks,
                        height,
                        crop,
                        core,
                        &blocks,
                        OutputFormat::Sections,
                    )
                    .unwrap();
                let expected: Vec<u8> = voxels
                    .bytes
                    .chunks_exact(4096)
                    .flat_map(|section| {
                        [0, 4].into_iter().flat_map(move |shift| {
                            section.chunks_exact(2).map(move |pair| {
                                ((pair[0] >> shift) & 15) | (((pair[1] >> shift) & 15) << 4)
                            })
                        })
                    })
                    .collect();
                assert_eq!(
                    packed.bytes, expected,
                    "sky={sky}, chunks={chunks}, height={height}, crop={crop}"
                );
            }
        }
    }
}

#[cfg(test)]
mod palette_tests;

#[cfg(test)]
impl Gpu {
    pub(crate) fn buffer_inventory(&self) -> crate::gpu_buffer_probe::Inventory {
        let mut out = crate::gpu_buffer_probe::Inventory::default();
        if let Some(v) = &self.buffers {
            for (name, buffer) in [("params", &v.params), ("blocks", &v.blocks), ("a", &v.a),
                ("b", &v.b), ("output", &v.output), ("work", &v.work),
                ("active", &v.active), ("indirect", &v.indirect)] { out.add(name, buffer); }
        }
        for (id, (a, b)) in &self.profiles {
            out.add(format!("profile/{id}/states"), a); out.add(format!("profile/{id}/blocked"), b);
        }
        for (i, v) in self.readbacks.iter().enumerate() {
            out.add(format!("readback/{i}"), &v.buffer);
            if let Some(t) = &v.timing {
                out.add(format!("timestamp/{i}/resolve"), &t.resolve);
                out.add(format!("timestamp/{i}/readback"), &t.readback);
            }
        }
        out
    }
}
