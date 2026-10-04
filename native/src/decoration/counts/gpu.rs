use super::*;
use std::{collections::HashMap, sync::mpsc, time::Duration};
use wgpu::util::DeviceExt;

pub(crate) struct Gpu {
    device: wgpu::Device,
    queue: wgpu::Queue,
    pipeline: wgpu::ComputePipeline,
    provider_pipeline: Option<wgpu::ComputePipeline>,
    provider_profiles: HashMap<u32, wgpu::Buffer>,
    layout: wgpu::BindGroupLayout,
    profiles: HashMap<u32, Resident>,
    buffers: Option<Buffers>,
    timestamps: Option<Timestamps>,
}
struct Timestamps {
    queries: wgpu::QuerySet,
    resolve: wgpu::Buffer,
    readback: wgpu::Buffer,
}
struct Resident {
    rules: Map<Rule, u32>,
    words: Vec<u32>,
    buffer: Option<wgpu::Buffer>,
}
struct Buffers {
    input: wgpu::Buffer,
    output: wgpu::Buffer,
    readback: wgpu::Buffer,
    capacity: usize,
}
pub(crate) struct ResultSamples {
    pub values: Vec<u32>,
    pub upload: u64,
    pub readback: u64,
    pub device_nanos: Option<u64>,
}
impl Gpu {
    pub(crate) fn new(device: wgpu::Device, queue: wgpu::Queue) -> Self {
        let layout = device.create_bind_group_layout(&wgpu::BindGroupLayoutDescriptor {
            label: Some("Retina sparse feature counts"),
            entries: &(0..3)
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
            label: Some("Retina registered placement counts"),
            source: wgpu::ShaderSource::Wgsl(include_str!("../../decoration_counts.wgsl").into()),
        });
        let pipeline_layout = device.create_pipeline_layout(&wgpu::PipelineLayoutDescriptor {
            label: Some("Retina sparse feature counts"),
            bind_group_layouts: &[Some(&layout)],
            immediate_size: 0,
        });
        let pipeline = device.create_compute_pipeline(&wgpu::ComputePipelineDescriptor {
            label: Some("Retina sparse feature counts"),
            layout: Some(&pipeline_layout),
            module: &shader,
            entry_point: Some("counts"),
            compilation_options: Default::default(),
            cache: None,
        });
        let timestamps = device
            .features()
            .contains(wgpu::Features::TIMESTAMP_QUERY)
            .then(|| Timestamps {
                queries: device.create_query_set(&wgpu::QuerySetDescriptor {
                    label: Some("Retina feature count timestamps"),
                    ty: wgpu::QueryType::Timestamp,
                    count: 2,
                }),
                resolve: device.create_buffer(&wgpu::BufferDescriptor {
                    label: Some("Retina feature timestamp resolve"),
                    size: 16,
                    usage: wgpu::BufferUsages::QUERY_RESOLVE | wgpu::BufferUsages::COPY_SRC,
                    mapped_at_creation: false,
                }),
                readback: device.create_buffer(&wgpu::BufferDescriptor {
                    label: Some("Retina feature timestamp readback"),
                    size: 16,
                    usage: wgpu::BufferUsages::MAP_READ | wgpu::BufferUsages::COPY_DST,
                    mapped_at_creation: false,
                }),
            });
        Self {
            device,
            queue,
            pipeline,
            provider_pipeline: None,
            provider_profiles: HashMap::new(),
            layout,
            profiles: HashMap::new(),
            buffers: None,
            timestamps,
        }
    }

    pub(crate) fn run(
        &mut self,
        id: u32,
        noise: &Noise,
        queries: &[Query],
    ) -> Result<ResultSamples, String> {
        if queries.is_empty() {
            return Ok(ResultSamples {
                values: Vec::new(),
                upload: 0,
                readback: 0,
                device_nanos: None,
            });
        }
        let mut upload = 0;
        let resident = self.profiles.entry(id).or_insert_with(|| Resident {
            rules: Map::default(),
            words: noise.permutation.clone(),
            buffer: None,
        });
        let previous = resident.words.len();
        let input: Vec<[u32; 4]> = queries
            .iter()
            .map(|q| {
                let next = resident.rules.len() as u32;
                let rule = *resident.rules.entry(q.rule).or_insert_with(|| {
                    resident.words.extend_from_slice(&q.rule.words);
                    next
                });
                [q.x as u32, q.z as u32, rule, 0]
            })
            .collect();
        if resident.words.len() != previous {
            resident.buffer = Some(self.device.create_buffer_init(
                &wgpu::util::BufferInitDescriptor {
                    label: Some("Retina resident placement count rules"),
                    contents: bytemuck::cast_slice(&resident.words),
                    usage: wgpu::BufferUsages::STORAGE,
                },
            ));
            // Includes the permutation; repeated regions upload no static data.
            upload += (resident.words.len() * 4) as u64;
        }
        let table = resident.buffer.as_ref().unwrap().clone();
        self.run_words(&table, &input, upload, false)
    }

    pub(crate) fn run_providers(
        &mut self,
        id: u32,
        programs: &[crate::decoration::provider_noise::Program],
        points: &[[i32; 4]],
    ) -> Result<ResultSamples, String> {
        if points.is_empty() {
            return Ok(ResultSamples {
                values: Vec::new(),
                upload: 0,
                readback: 0,
                device_nanos: None,
            });
        }
        if points
            .iter()
            .any(|p| p[3] < 0 || p[3] as usize >= programs.len())
        {
            return Err("invalid provider noise program ID".into());
        }
        let mut upload = 0;
        let table = self
            .provider_profiles
            .entry(id)
            .or_insert_with(|| {
                let words = crate::decoration::provider_noise::encode(programs);
                upload = (words.len() * 4) as u64;
                self.device
                    .create_buffer_init(&wgpu::util::BufferInitDescriptor {
                        label: Some("Retina resident provider noise stacks"),
                        contents: bytemuck::cast_slice(&words),
                        usage: wgpu::BufferUsages::STORAGE,
                    })
            })
            .clone();
        let input: Vec<[u32; 4]> = points.iter().map(|p| p.map(|v| v as u32)).collect();
        self.run_words(&table, &input, upload, true)
    }

    fn run_words(
        &mut self,
        table: &wgpu::Buffer,
        input: &[[u32; 4]],
        mut upload: u64,
        providers: bool,
    ) -> Result<ResultSamples, String> {
        if providers && self.provider_pipeline.is_none() {
            let shader = self
                .device
                .create_shader_module(wgpu::ShaderModuleDescriptor {
                    label: Some("Retina registered provider noise"),
                    source: wgpu::ShaderSource::Wgsl(
                        include_str!("../../provider_noise.wgsl").into(),
                    ),
                });
            let layout = self
                .device
                .create_pipeline_layout(&wgpu::PipelineLayoutDescriptor {
                    label: Some("Retina sparse provider noise"),
                    bind_group_layouts: &[Some(&self.layout)],
                    immediate_size: 0,
                });
            self.provider_pipeline = Some(self.device.create_compute_pipeline(
                &wgpu::ComputePipelineDescriptor {
                    label: Some("Retina sparse provider noise"),
                    layout: Some(&layout),
                    module: &shader,
                    entry_point: Some("provider_noise"),
                    compilation_options: Default::default(),
                    cache: None,
                },
            ));
        }
        let active = input.len();
        if self.buffers.as_ref().is_none_or(|b| b.capacity < active) {
            let capacity = active.max(self.buffers.as_ref().map_or(1, |b| b.capacity));
            let buffer = |label, size, usage| {
                self.device.create_buffer(&wgpu::BufferDescriptor {
                    label: Some(label),
                    size,
                    usage,
                    mapped_at_creation: false,
                })
            };
            self.buffers = Some(Buffers {
                input: buffer(
                    "Retina feature points",
                    (capacity * 16) as u64,
                    wgpu::BufferUsages::STORAGE | wgpu::BufferUsages::COPY_DST,
                ),
                output: buffer(
                    "Retina feature counts",
                    (capacity * 4) as u64,
                    wgpu::BufferUsages::STORAGE | wgpu::BufferUsages::COPY_SRC,
                ),
                readback: buffer(
                    "Retina feature count readback",
                    (capacity * 4) as u64,
                    wgpu::BufferUsages::COPY_DST | wgpu::BufferUsages::MAP_READ,
                ),
                capacity,
            });
        }
        let buffers = self.buffers.as_ref().unwrap();
        let binding = |buffer, size| {
            wgpu::BindingResource::Buffer(wgpu::BufferBinding {
                buffer,
                offset: 0,
                size: std::num::NonZeroU64::new(size),
            })
        };
        let bytes = (active * 4) as u64;
        let group = self.device.create_bind_group(&wgpu::BindGroupDescriptor {
            label: Some("Retina active feature count batch"),
            layout: &self.layout,
            entries: &[
                wgpu::BindGroupEntry {
                    binding: 0,
                    resource: table.as_entire_binding(),
                },
                wgpu::BindGroupEntry {
                    binding: 1,
                    resource: binding(&buffers.input, bytes * 4),
                },
                wgpu::BindGroupEntry {
                    binding: 2,
                    resource: binding(&buffers.output, bytes),
                },
            ],
        });
        self.queue
            .write_buffer(&buffers.input, 0, bytemuck::cast_slice(input));
        upload += bytes * 4;
        let mut encoder = self
            .device
            .create_command_encoder(&wgpu::CommandEncoderDescriptor {
                label: Some("Retina registered feature counts"),
            });
        {
            let mut pass = encoder.begin_compute_pass(&wgpu::ComputePassDescriptor {
                label: Some("Retina registered feature counts"),
                timestamp_writes: self.timestamps.as_ref().map(|t| {
                    wgpu::ComputePassTimestampWrites {
                        query_set: &t.queries,
                        beginning_of_pass_write_index: Some(0),
                        end_of_pass_write_index: Some(1),
                    }
                }),
            });
            pass.set_pipeline(if providers {
                self.provider_pipeline.as_ref().unwrap()
            } else {
                &self.pipeline
            });
            pass.set_bind_group(0, &group, &[]);
            // Two-dimensional dispatch also supports large sparse custom-feature batches.
            pass.dispatch_workgroups(
                (active as u32).div_ceil(64).min(256),
                (active as u32).div_ceil(64 * 256),
                1,
            );
        }
        encoder.copy_buffer_to_buffer(&buffers.output, 0, &buffers.readback, 0, bytes);
        if let Some(t) = &self.timestamps {
            encoder.resolve_query_set(&t.queries, 0..2, &t.resolve, 0);
            encoder.copy_buffer_to_buffer(&t.resolve, 0, &t.readback, 0, 16);
        }
        let submission = self.queue.submit([encoder.finish()]);
        let (sender, receiver) = mpsc::channel();
        if let Some(t) = &self.timestamps {
            let sender = sender.clone();
            t.readback
                .slice(..)
                .map_async(wgpu::MapMode::Read, move |r| {
                    let _ = sender.send(r);
                });
        }
        buffers
            .readback
            .slice(..bytes)
            .map_async(wgpu::MapMode::Read, move |r| {
                let _ = sender.send(r);
            });
        let result = (|| {
            self.device
                .poll(wgpu::PollType::Wait {
                    submission_index: Some(submission),
                    timeout: Some(Duration::from_secs(30)),
                })
                .map_err(|e| format!("feature count GPU poll failed: {e}"))?;
            for _ in 0..1 + usize::from(self.timestamps.is_some()) {
                receiver
                    .recv_timeout(Duration::from_secs(30))
                    .map_err(|e| format!("feature count GPU mapping timed out: {e}"))?
                    .map_err(|e| format!("feature count GPU mapping failed: {e}"))?;
            }
            let mapped = buffers
                .readback
                .slice(..bytes)
                .get_mapped_range()
                .map_err(|e| e.to_string())?;
            let device_nanos = if let Some(t) = &self.timestamps {
                let mapped = t
                    .readback
                    .slice(..)
                    .get_mapped_range()
                    .map_err(|e| e.to_string())?;
                let ticks = bytemuck::cast_slice::<u8, u64>(&mapped);
                Some(
                    (ticks[1].saturating_sub(ticks[0]) as f64
                        * self.queue.get_timestamp_period() as f64) as u64,
                )
            } else {
                None
            };
            Ok(ResultSamples {
                values: bytemuck::cast_slice::<u8, u32>(&mapped).to_vec(),
                upload,
                readback: bytes + if self.timestamps.is_some() { 16 } else { 0 },
                device_nanos,
            })
        })();
        buffers.readback.unmap();
        if let Some(t) = &self.timestamps {
            t.readback.unmap();
        }
        result
    }
}
