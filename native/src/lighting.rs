//! Final-block GPU lighting. The outer MCA ring is input only, never marked lit.
use serde::Deserialize;
use std::{
    collections::HashMap,
    sync::mpsc,
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
pub(crate) struct Gpu {
    device: wgpu::Device,
    queue: wgpu::Queue,
    pipelines: Option<Pipelines>,
    timing: Option<Timing>,
    profiles: HashMap<u32, (wgpu::Buffer, wgpu::Buffer)>,
    buffers: Option<Buffers>,
}
struct Pipelines {
    layout: wgpu::BindGroupLayout,
    init: wgpu::ComputePipeline,
    spread: wgpu::ComputePipeline,
    compact: wgpu::ComputePipeline,
    args: wgpu::ComputePipeline,
    sparse: wgpu::ComputePipeline,
    pack: wgpu::ComputePipeline,
}
struct Timing {
    resolve: wgpu::Buffer,
    readback: wgpu::Buffer,
}
struct Buffers {
    params: wgpu::Buffer,
    blocks: wgpu::Buffer,
    a: wgpu::Buffer,
    b: wgpu::Buffer,
    output: wgpu::Buffer,
    readback: wgpu::Buffer,
    work: wgpu::Buffer,
    active: wgpu::Buffer,
    indirect: wgpu::Buffer,
    capacities: [u64; 4],
}
pub(crate) struct ResultLight {
    pub bytes: Vec<u8>,
    pub device_nanos: Option<[u64; 3]>,
    pub upload: u64,
}
impl Gpu {
    pub(crate) fn new(device: wgpu::Device, queue: wgpu::Queue) -> Self {
        Self {
            device,
            queue,
            pipelines: None,
            timing: None,
            profiles: HashMap::new(),
            buffers: None,
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
            layout,
        });
        // Runs are serialized and both readbacks are unmapped before returning.
        self.timing = self
            .device
            .features()
            .contains(wgpu::Features::TIMESTAMP_QUERY)
            .then(|| Timing {
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
            });
    }
    /// Chunk-major material input; crop/core are measured in chunks. No CPU propagation.
    pub(crate) fn run(
        &mut self,
        handle: u32,
        profile: &Profile,
        chunks: u32,
        height: u32,
        crop: u32,
        core: u32,
        blocks: &[u16],
    ) -> Result<ResultLight, String> {
        if chunks == 0
            || height == 0
            || core == 0
            || crop + core > chunks
            || blocks.len() != chunks as usize * chunks as usize * height as usize * 256
        {
            return Err("invalid GPU lighting volume".into());
        }
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
        let bricks = (chunks * 2).pow(2) * height.div_ceil(8);
        let sizes = [
            (blocks.len() * 2) as u64,
            blocks.len() as u64,
            core as u64 * core as u64 * height as u64 * 256,
            bricks as u64 * 4,
        ];
        if self
            .buffers
            .as_ref()
            .is_none_or(|b| (0..4).any(|i| b.capacities[i] < sizes[i]))
        {
            let capacities = std::array::from_fn(|i| {
                sizes[i].max(self.buffers.as_ref().map_or(4, |b| b.capacities[i]))
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
                    32,
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
                readback: buffer(
                    "Retina light readback",
                    capacities[2],
                    wgpu::BufferUsages::COPY_DST | wgpu::BufferUsages::MAP_READ,
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
        }
        let buffers = self.buffers.as_ref().unwrap();
        let pipelines = self.pipelines.as_ref().unwrap();
        let resident = &self.profiles[&handle];
        let bind = |read: &wgpu::Buffer, write: &wgpu::Buffer| {
            let entries = [
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
            self.device.create_bind_group(&wgpu::BindGroupDescriptor {
                label: Some("Retina light volume"),
                layout: &pipelines.layout,
                entries: &entries
                    .iter()
                    .enumerate()
                    .map(|(i, b)| wgpu::BindGroupEntry {
                        binding: i as u32,
                        resource: b.as_entire_binding(),
                    })
                    .collect::<Vec<_>>(),
            })
        };
        let ab = bind(&buffers.a, &buffers.b);
        let ba = bind(&buffers.b, &buffers.a);
        let params = [
            chunks * 16,
            height,
            chunks,
            crop,
            core,
            profile.sky as u32 | if sparse { 2 } else { 0 },
            profile.faces.div_ceil(32),
            (blocks.len() / 4) as u32,
        ];
        self.queue
            .write_buffer(&buffers.params, 0, bytemuck::cast_slice(&params));
        self.queue
            .write_buffer(&buffers.blocks, 0, bytemuck::cast_slice(blocks));
        upload += sizes[0] + 32;
        let timestamp = self.timing.is_some();
        let queries = timestamp.then(|| {
            self.device.create_query_set(&wgpu::QuerySetDescriptor {
                label: Some("Retina light timing"),
                ty: wgpu::QueryType::Timestamp,
                count: 6,
            })
        });
        let times = self.timing.as_ref().map(|t| (&t.resolve, &t.readback));
        let mut encoder = self
            .device
            .create_command_encoder(&wgpu::CommandEncoderDescriptor {
                label: Some("Retina final lighting"),
            });
        let writes = |pair: u32| {
            queries.as_ref().map(|q| wgpu::ComputePassTimestampWrites {
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
            pass.set_bind_group(0, &ab, &[]);
            pass.dispatch_workgroups((chunks * chunks * 64).div_ceil(64), 1, 1);
        }
        if sparse {
            let mut pass = encoder.begin_compute_pass(&wgpu::ComputePassDescriptor {
                label: Some("Retina compact active light bricks"),
                timestamp_writes: queries.as_ref().map(|q| wgpu::ComputePassTimestampWrites {
                    query_set: q,
                    beginning_of_pass_write_index: Some(2),
                    end_of_pass_write_index: None,
                }),
            });
            pass.set_pipeline(&pipelines.compact);
            pass.set_bind_group(0, &ab, &[]);
            pass.dispatch_workgroups(bricks.div_ceil(64), 1, 1);
        }
        if sparse {
            let mut pass = encoder.begin_compute_pass(&wgpu::ComputePassDescriptor {
                label: Some("Retina light indirect arguments"),
                timestamp_writes: None,
            });
            pass.set_pipeline(&pipelines.args);
            pass.set_bind_group(0, &ab, &[]);
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
                timestamp_writes: queries.as_ref().and_then(|q| {
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
            pass.set_bind_group(0, if iteration % 2 == 0 { &ba } else { &ab }, &[]);
            if sparse {
                pass.dispatch_workgroups_indirect(&buffers.indirect, 0);
            } else {
                pass.dispatch_workgroups(256, params[7].div_ceil(16384), 1);
            }
        }
        {
            let mut pass = encoder.begin_compute_pass(&wgpu::ComputePassDescriptor {
                label: Some("Retina pack interior light"),
                timestamp_writes: writes(2),
            });
            pass.set_pipeline(&pipelines.pack);
            pass.set_bind_group(0, &ba, &[]);
            pass.dispatch_workgroups(256, (sizes[2] as u32 / 4).div_ceil(16384), 1);
        }
        encoder.copy_buffer_to_buffer(&buffers.output, 0, &buffers.readback, 0, sizes[2]);
        if let (Some(q), Some((resolve, readback))) = (&queries, &times) {
            encoder.resolve_query_set(q, 0..6, resolve, 0);
            encoder.copy_buffer_to_buffer(resolve, 0, readback, 0, 48);
        }
        let submission = self.queue.submit([encoder.finish()]);
        let (send, recv) = mpsc::channel();
        buffers
            .readback
            .slice(..sizes[2])
            .map_async(wgpu::MapMode::Read, {
                let send = send.clone();
                move |r| {
                    let _ = send.send(r);
                }
            });
        if let Some((_, t)) = &times {
            t.slice(..).map_async(wgpu::MapMode::Read, move |r| {
                let _ = send.send(r);
            });
        }
        let result = (|| {
            self.device
                .poll(wgpu::PollType::Wait {
                    submission_index: Some(submission),
                    timeout: Some(Duration::from_secs(60)),
                })
                .map_err(|e| format!("lighting GPU poll: {e}"))?;
            for _ in 0..1 + usize::from(timestamp) {
                recv.recv_timeout(Duration::from_secs(60))
                    .map_err(|e| e.to_string())?
                    .map_err(|e| e.to_string())?;
            }
            let bytes = buffers
                .readback
                .slice(..sizes[2])
                .get_mapped_range()
                .map_err(|e| e.to_string())?
                .to_vec();
            let device_nanos = times.as_ref().map(|(_, t)| {
                let data = t.slice(..).get_mapped_range().unwrap();
                let ticks: &[u64] = bytemuck::cast_slice(&data);
                std::array::from_fn(|i| {
                    (ticks[i * 2 + 1].saturating_sub(ticks[i * 2]) as f64
                        * self.queue.get_timestamp_period() as f64) as u64
                })
            });
            Ok(ResultLight {
                bytes,
                device_nanos,
                upload,
            })
        })();
        buffers.readback.unmap();
        if let Some((_, t)) = &times {
            t.unmap();
        }
        result
    }
}

/// Reuses only the 32x32 region's final blocks; a one-chunk ring guarantees the core.
pub(crate) fn region(
    engine: &crate::TerrainEngine,
    request: crate::ChunkRequest,
    profile: &Profile,
    chunks: &[Vec<u16>],
    job: &crate::timings::Timings,
) -> Result<Vec<Option<Vec<u8>>>, String> {
    let start = Instant::now();
    let mut gpu = engine
        .light_gpu
        .lock()
        .map_err(|_| "lighting GPU lock poisoned")?;
    let mut output = vec![None; 1024];
    let core = [10usize, 6, 5, 3, 2, 1]
        .into_iter()
        .find(|&core| {
            (core + 2).pow(2) as u64 * (request.height as u64 + 32) * 512
                <= gpu.device.limits().max_storage_buffer_binding_size as u64
        })
        .unwrap_or(1);
    let count = (request.height as usize + 32) * 256;
    let mut input = Vec::with_capacity((core + 2).pow(2) * count);
    for cz in (1..31).step_by(core) {
        for cx in (1..31).step_by(core) {
            input.clear();
            for z in cz - 1..cz + core + 1 {
                for x in cx - 1..cx + core + 1 {
                    input.resize(input.len() + 4096, 0);
                    input.extend_from_slice(&chunks[z * 32 + x]);
                    input.resize(input.len() + 4096, 0);
                }
            }
            let result = gpu.run(
                request.reserved,
                profile,
                (core + 2) as u32,
                request.height + 32,
                1,
                core as u32,
                &input,
            )?;
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
            for z in 0..core {
                for x in 0..core {
                    let i = (z * core + x) * count;
                    output[(cz + z) * 32 + cx + x] = Some(result.bytes[i..i + count].to_vec());
                }
            }
        }
    }
    let elapsed = start.elapsed().as_nanos() as u64;
    engine
        .timings(request.reserved)
        .add(crate::timings::LIGHT_HOST, elapsed);
    job.add(crate::timings::LIGHT_HOST, elapsed);
    Ok(output)
}
