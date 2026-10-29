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
struct Input {
    table: wgpu::Buffer,
    points: Vec<[u32; 4]>,
    upload: u64,
}
pub(crate) struct BatchSamples {
    pub counts: Vec<u32>,
    pub providers: Vec<u32>,
    pub upload: u64,
    pub readback: u64,
    pub device_nanos: [Option<u64>; 2],
}
pub(crate) struct ResultSamples {
    pub values: Vec<u32>,
    pub upload: u64,
    pub readback: u64,
    pub device_nanos: Option<u64>,
}
impl Gpu {
    pub(crate) fn retire(&mut self, ids: &[u32]) {
        self.profiles.retain(|id, _| !ids.contains(id));
        self.provider_profiles.retain(|id, _| !ids.contains(id));
    }

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
        let shader = crate::startup::shader(&device, wgpu::ShaderModuleDescriptor {
            label: Some("Retina registered placement counts"),
            source: wgpu::ShaderSource::Wgsl(include_str!("../../decoration_counts.wgsl").into()),
        });
        let pipeline_layout = device.create_pipeline_layout(&wgpu::PipelineLayoutDescriptor {
            label: Some("Retina sparse feature counts"),
            bind_group_layouts: &[Some(&layout)],
            immediate_size: 0,
        });
        let pipeline = crate::startup::pipeline(&device, &wgpu::ComputePipelineDescriptor {
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
                    count: 4,
                }),
                resolve: device.create_buffer(&wgpu::BufferDescriptor {
                    label: Some("Retina feature timestamp resolve"),
                    size: 32,
                    usage: wgpu::BufferUsages::QUERY_RESOLVE | wgpu::BufferUsages::COPY_SRC,
                    mapped_at_creation: false,
                }),
                readback: device.create_buffer(&wgpu::BufferDescriptor {
                    label: Some("Retina feature timestamp readback"),
                    size: 32,
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
        let batch = self.run_batch(id, Some(noise), queries, &[], &[])?;
        Ok(ResultSamples {
            values: batch.counts,
            upload: batch.upload,
            readback: batch.readback,
            device_nanos: batch.device_nanos[0],
        })
    }

    fn prepare_counts(&mut self, id: u32, noise: &Noise, queries: &[Query]) -> Input {
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
        Input {
            table,
            points: input,
            upload,
        }
    }

    pub(crate) fn run_providers(
        &mut self,
        id: u32,
        programs: &[crate::decoration::provider_noise::Program],
        points: &[[i32; 4]],
    ) -> Result<ResultSamples, String> {
        let batch = self.run_batch(id, None, &[], programs, points)?;
        Ok(ResultSamples {
            values: batch.providers,
            upload: batch.upload,
            readback: batch.readback,
            device_nanos: batch.device_nanos[1],
        })
    }

    fn prepare_providers(
        &mut self,
        id: u32,
        programs: &[crate::decoration::provider_noise::Program],
        points: &[[i32; 4]],
    ) -> Input {
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
        Input {
            table,
            points: input,
            upload,
        }
    }

    /// Counts and provider samples in one replay round have no dependency on
    /// each other. Keep two dispatches/timings, but share submission and mapping.
    pub(crate) fn run_batch(
        &mut self,
        id: u32,
        noise: Option<&Noise>,
        queries: &[Query],
        programs: &[crate::decoration::provider_noise::Program],
        points: &[[i32; 4]],
    ) -> Result<BatchSamples, String> {
        if points
            .iter()
            .any(|p| p[3] < 0 || p[3] as usize >= programs.len())
        {
            return Err("invalid provider noise program ID".into());
        }
        let count_input = if queries.is_empty() {
            None
        } else {
            Some(self.prepare_counts(
                id,
                noise.ok_or("profile has no registered placement noise")?,
                queries,
            ))
        };
        let provider_input =
            (!points.is_empty()).then(|| self.prepare_providers(id, programs, points));
        let inputs = [count_input, provider_input];
        let dispatches = inputs.iter().filter(|i| i.is_some()).count();
        if dispatches == 0 {
            return Ok(BatchSamples {
                counts: Vec::new(),
                providers: Vec::new(),
                upload: 0,
                readback: 0,
                device_nanos: [None; 2],
            });
        }
        if inputs[1].is_some() && self.provider_pipeline.is_none() {
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
            self.provider_pipeline = Some(crate::startup::pipeline(&self.device,
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
        // Storage binding offsets must be aligned for BOTH word outputs and
        // four-word inputs. Padding is never exposed to the shader or caller.
        let provider_start = if points.is_empty() {
            queries.len()
        } else {
            let alignment =
                (self.device.limits().min_storage_buffer_offset_alignment as usize / 4).max(1);
            queries.len().div_ceil(alignment) * alignment
        };
        let starts = [0, provider_start];
        let active = provider_start + points.len();
        let mut upload = 0;
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
        let bytes = (active * 4) as u64;
        let mut encoder = self
            .device
            .create_command_encoder(&wgpu::CommandEncoderDescriptor {
                label: Some("Retina sparse decoration samples"),
            });
        let mut timing_index = 0;
        for (kind, input) in inputs.iter().enumerate() {
            let Some(input) = input else { continue };
            let start = starts[kind] as u64;
            let count = input.points.len();
            let binding = |buffer, stride| {
                wgpu::BindingResource::Buffer(wgpu::BufferBinding {
                    buffer,
                    offset: start * stride,
                    size: std::num::NonZeroU64::new(count as u64 * stride),
                })
            };
            let group = self.device.create_bind_group(&wgpu::BindGroupDescriptor {
                label: Some("Retina active sparse decoration batch"),
                layout: &self.layout,
                entries: &[
                    wgpu::BindGroupEntry {
                        binding: 0,
                        resource: input.table.as_entire_binding(),
                    },
                    wgpu::BindGroupEntry {
                        binding: 1,
                        resource: binding(&buffers.input, 16),
                    },
                    wgpu::BindGroupEntry {
                        binding: 2,
                        resource: binding(&buffers.output, 4),
                    },
                ],
            });
            self.queue.write_buffer(
                &buffers.input,
                start * 16,
                bytemuck::cast_slice(&input.points),
            );
            upload += input.upload + (count * 16) as u64;
            let mut pass = encoder.begin_compute_pass(&wgpu::ComputePassDescriptor {
                label: Some(if kind == 0 {
                    "Retina registered feature counts"
                } else {
                    "Retina registered provider noise"
                }),
                timestamp_writes: self.timestamps.as_ref().map(|t| {
                    wgpu::ComputePassTimestampWrites {
                        query_set: &t.queries,
                        beginning_of_pass_write_index: Some(timing_index),
                        end_of_pass_write_index: Some(timing_index + 1),
                    }
                }),
            });
            timing_index += 2;
            pass.set_pipeline(if kind == 0 {
                &self.pipeline
            } else {
                self.provider_pipeline.as_ref().unwrap()
            });
            pass.set_bind_group(0, &group, &[]);
            // Two-dimensional dispatch also supports large sparse custom-feature batches.
            pass.dispatch_workgroups(
                (count as u32).div_ceil(64).min(256),
                (count as u32).div_ceil(64 * 256),
                1,
            );
        }
        encoder.copy_buffer_to_buffer(&buffers.output, 0, &buffers.readback, 0, bytes);
        if let Some(t) = &self.timestamps {
            encoder.resolve_query_set(&t.queries, 0..timing_index, &t.resolve, 0);
            encoder.copy_buffer_to_buffer(&t.resolve, 0, &t.readback, 0, timing_index as u64 * 8);
        }
        let submission = self.queue.submit([encoder.finish()]);
        let (sender, receiver) = mpsc::channel();
        if let Some(t) = &self.timestamps {
            let sender = sender.clone();
            t.readback
                .slice(..timing_index as u64 * 8)
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
            let mut device_nanos = [None; 2];
            if let Some(t) = &self.timestamps {
                let mapped = t
                    .readback
                    .slice(..timing_index as u64 * 8)
                    .get_mapped_range()
                    .map_err(|e| e.to_string())?;
                let ticks = bytemuck::cast_slice::<u8, u64>(&mapped);
                let mut offset = 0;
                for (kind, input) in inputs.iter().enumerate() {
                    if input.is_some() {
                        device_nanos[kind] = Some(
                            (ticks[offset + 1].saturating_sub(ticks[offset]) as f64
                                * self.queue.get_timestamp_period() as f64)
                                as u64,
                        );
                        offset += 2;
                    }
                }
            }
            let values = bytemuck::cast_slice::<u8, u32>(&mapped);
            Ok(BatchSamples {
                counts: values[..queries.len()].to_vec(),
                providers: values[provider_start..].to_vec(),
                upload,
                readback: bytes
                    + if self.timestamps.is_some() {
                        timing_index as u64 * 8
                    } else {
                        0
                    },
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

#[cfg(test)]
impl Gpu {
    pub(crate) fn buffer_inventory(&self) -> crate::gpu_buffer_probe::Inventory {
        let mut out = crate::gpu_buffer_probe::Inventory::default();
        if let Some(v) = &self.buffers {
            for (name, buffer) in [("input", &v.input), ("output", &v.output),
                ("readback", &v.readback)] { out.add(name, buffer); }
        }
        for (id, v) in &self.profiles {
            if let Some(b) = &v.buffer { out.add(format!("profile/{id}"), b); }
        }
        for (id, v) in &self.provider_profiles { out.add(format!("provider/{id}"), v); }
        if let Some(v) = &self.timestamps {
            out.add("timestamp_resolve", &v.resolve); out.add("timestamp_readback", &v.readback);
        }
        out
    }
}
