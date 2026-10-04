//! Ore mask submissions share the terrain device/queue, with reusable buffers.
use super::raster::Batch;
use std::{
    sync::mpsc,
    time::{Duration, Instant},
};

pub(crate) struct Gpu {
    device: wgpu::Device,
    queue: wgpu::Queue,
    pipeline: wgpu::ComputePipeline,
    layout: wgpu::BindGroupLayout,
    buffers: Option<Buffers>,
    timestamps: Option<Timestamps>,
}
struct Timestamps {
    queries: wgpu::QuerySet,
    resolve: wgpu::Buffer,
    readback: wgpu::Buffer,
}
pub(crate) struct ResultMasks {
    pub words: Vec<u32>,
    pub device_nanos: Option<u64>,
    pub readback_bytes: u64,
}
struct Buffers {
    descriptors: wgpu::Buffer,
    spheres: wgpu::Buffer,
    output: wgpu::Buffer,
    readback: wgpu::Buffer,
    sizes: [u64; 3],
}
impl Gpu {
    pub(crate) fn new(device: wgpu::Device, queue: wgpu::Queue) -> Self {
        let layout = device.create_bind_group_layout(&wgpu::BindGroupLayoutDescriptor {
            label: Some("Retina ore masks"),
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
            label: Some("Retina guarded ore masks"),
            source: wgpu::ShaderSource::Wgsl(include_str!("../ore.wgsl").into()),
        });
        let pipeline_layout = device.create_pipeline_layout(&wgpu::PipelineLayoutDescriptor {
            label: Some("Retina ore masks"),
            bind_group_layouts: &[Some(&layout)],
            immediate_size: 0,
        });
        let pipeline = device.create_compute_pipeline(&wgpu::ComputePipelineDescriptor {
            label: Some("Retina ore masks"),
            layout: Some(&pipeline_layout),
            module: &shader,
            entry_point: Some("guarded"),
            compilation_options: Default::default(),
            cache: None,
        });
        let timestamps = device
            .features()
            .contains(wgpu::Features::TIMESTAMP_QUERY)
            .then(|| {
                let buffer = |label, usage| {
                    device.create_buffer(&wgpu::BufferDescriptor {
                        label: Some(label),
                        size: 16,
                        usage,
                        mapped_at_creation: false,
                    })
                };
                Timestamps {
                    queries: device.create_query_set(&wgpu::QuerySetDescriptor {
                        label: Some("Retina ore mask timestamps"),
                        ty: wgpu::QueryType::Timestamp,
                        count: 2,
                    }),
                    resolve: buffer(
                        "Retina ore timestamp resolve",
                        wgpu::BufferUsages::QUERY_RESOLVE | wgpu::BufferUsages::COPY_SRC,
                    ),
                    readback: buffer(
                        "Retina ore timestamp readback",
                        wgpu::BufferUsages::MAP_READ | wgpu::BufferUsages::COPY_DST,
                    ),
                }
            });
        Self {
            device,
            queue,
            pipeline,
            layout,
            buffers: None,
            timestamps,
        }
    }
    pub(crate) fn run(&mut self, batch: &Batch) -> Result<ResultMasks, String> {
        if batch.descriptors.is_empty() {
            return Ok(ResultMasks {
                words: Vec::new(),
                device_nanos: None,
                readback_bytes: 0,
            });
        }
        let start = Instant::now();
        let sizes = [
            (batch.descriptors.len() * 48) as u64,
            (batch.spheres.len() * 16) as u64,
            (batch.words * 4) as u64,
        ];
        if self
            .buffers
            .as_ref()
            .is_none_or(|b| (0..3).any(|i| b.sizes[i] < sizes[i]))
        {
            let capacities = std::array::from_fn(|i| {
                sizes[i].max(self.buffers.as_ref().map_or(16, |b| b.sizes[i]))
            });
            let buffer = |label, size, usage| {
                self.device.create_buffer(&wgpu::BufferDescriptor {
                    label: Some(label),
                    size,
                    usage,
                    mapped_at_creation: false,
                })
            };
            let input = wgpu::BufferUsages::STORAGE | wgpu::BufferUsages::COPY_DST;
            let descriptors = buffer("Retina ore veins", capacities[0], input);
            let spheres = buffer("Retina ore spheres", capacities[1], input);
            let output = buffer(
                "Retina ore masks",
                capacities[2],
                wgpu::BufferUsages::STORAGE | wgpu::BufferUsages::COPY_SRC,
            );
            let readback = buffer(
                "Retina ore mask readback",
                capacities[2],
                wgpu::BufferUsages::COPY_DST | wgpu::BufferUsages::MAP_READ,
            );
            self.buffers = Some(Buffers {
                descriptors,
                spheres,
                output,
                readback,
                sizes: capacities,
            });
        }
        let buffers = self.buffers.as_ref().unwrap();
        // Shader arrayLength must see this job's active lengths, never the
        // previous larger region's retained buffer capacity/stale descriptors.
        let binding = |buffer, size| {
            wgpu::BindingResource::Buffer(wgpu::BufferBinding {
                buffer,
                offset: 0,
                size: std::num::NonZeroU64::new(size),
            })
        };
        let bindings = self.device.create_bind_group(&wgpu::BindGroupDescriptor {
            label: Some("Retina ore masks"),
            layout: &self.layout,
            entries: &[
                wgpu::BindGroupEntry {
                    binding: 0,
                    resource: binding(&buffers.descriptors, sizes[0]),
                },
                wgpu::BindGroupEntry {
                    binding: 1,
                    resource: binding(&buffers.spheres, sizes[1]),
                },
                wgpu::BindGroupEntry {
                    binding: 2,
                    resource: binding(&buffers.output, sizes[2]),
                },
            ],
        });
        self.queue.write_buffer(
            &buffers.descriptors,
            0,
            bytemuck::cast_slice(&batch.descriptors),
        );
        self.queue
            .write_buffer(&buffers.spheres, 0, bytemuck::cast_slice(&batch.spheres));
        let mut encoder = self
            .device
            .create_command_encoder(&wgpu::CommandEncoderDescriptor {
                label: Some("Retina ore masks"),
            });
        {
            let mut pass = encoder.begin_compute_pass(&wgpu::ComputePassDescriptor {
                label: Some("Retina ore masks"),
                timestamp_writes: self.timestamps.as_ref().map(|t| {
                    wgpu::ComputePassTimestampWrites {
                        query_set: &t.queries,
                        beginning_of_pass_write_index: Some(0),
                        end_of_pass_write_index: Some(1),
                    }
                }),
            });
            pass.set_pipeline(&self.pipeline);
            pass.set_bind_group(0, &bindings, &[]);
            // Allocation may retain extra capacity: dispatch only active descriptors.
            pass.dispatch_workgroups(256, (batch.descriptors.len() as u32).div_ceil(256), 1);
        }
        encoder.copy_buffer_to_buffer(&buffers.output, 0, &buffers.readback, 0, sizes[2]);
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
                .map_async(wgpu::MapMode::Read, move |result| {
                    let _ = sender.send(result);
                });
        }
        buffers
            .readback
            .slice(..sizes[2])
            .map_async(wgpu::MapMode::Read, move |result| {
                let _ = sender.send(result);
            });
        let result = (|| {
            self.device
                .poll(wgpu::PollType::Wait {
                    submission_index: Some(submission),
                    timeout: Some(Duration::from_secs(30)),
                })
                .map_err(|e| format!("ore GPU poll failed: {e}"))?;
            for _ in 0..1 + usize::from(self.timestamps.is_some()) {
                receiver
                    .recv_timeout(Duration::from_secs(30))
                    .map_err(|e| format!("ore GPU mapping timed out: {e}"))?
                    .map_err(|e| format!("ore GPU mapping failed: {e}"))?;
            }
            let mapped = buffers
                .readback
                .slice(..sizes[2])
                .get_mapped_range()
                .map_err(|e| e.to_string())?;
            let device_nanos = if let Some(t) = &self.timestamps {
                let range = t
                    .readback
                    .slice(..)
                    .get_mapped_range()
                    .map_err(|e| e.to_string())?;
                let ticks: &[u64] = bytemuck::cast_slice(&range);
                let nanos = (ticks[1].saturating_sub(ticks[0]) as f64
                    * self.queue.get_timestamp_period() as f64) as u64;
                // Reject stale/reversed pairs; a device pass cannot outlast the
                // entire host submission/readback interval containing it.
                (ticks[0] > 0 && ticks[1] >= ticks[0] && nanos <= start.elapsed().as_nanos() as u64)
                    .then_some(nanos)
            } else {
                None
            };
            Ok(ResultMasks {
                words: bytemuck::cast_slice::<u8, u32>(&mapped).to_vec(),
                device_nanos,
                readback_bytes: sizes[2] + if self.timestamps.is_some() { 16 } else { 0 },
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
mod tests {
    use super::*;
    use crate::{
        COLUMNS, ChunkRequest,
        decoration::Field,
        geology::*,
        profile::{Column, WorldProfile},
    };
    use serde_json::json;

    #[test]
    #[ignore = "requires a real compute device; run explicitly with --ignored"]
    fn real_gpu_compact_ores_preserve_hosts_exposure_edges_and_reused_buffers() {
        let terrain = crate::gpu::Gpu::new(Default::default()).unwrap();
        let mut gpu = terrain.ore_planner();
        let mut profile:WorldProfile=serde_json::from_value(json!({
            "biome_scale":256,"blend":0.55,"sea_level":32,"stone":1,"water":4,
            "bedrock":1,"deepslate":1,"snow":0,"ice":4,
            "materials":["minecraft:air","minecraft:stone","minecraft:gold_ore","minecraft:iron_ore","minecraft:water"],
            "biomes":[{"id":"test:biome","climate":[0,0,0,0],"terrain":[0,1,1],"top":1,"filler":1,"underwater":1,"flags":0}],
            "noises":[],"material_flags":[0,1,1,1,0],"heightmap_masks":[0,63,63,63,0]
        })).unwrap();
        profile.geology.ores = (0..4)
            .map(|id| OreRecipe {
                id: format!("test:{id}"),
                size: if id == 0 { 64 } else { 16 },
                discard: if id >= 2 { 0.4 } else { 0. },
                scattered: id == 3,
                count_min: 12,
                count_max: 12,
                rarity: 1,
                height: HeightRange {
                    min: -40,
                    max: 80,
                    triangle: true,
                    plateau: 10,
                },
                replacement_bands: vec![ReplacementBand {
                    min: -64,
                    max: 127,
                    materials: match id {
                        0 => vec![0, 2, 0, 0, 0],
                        1 => vec![3, 0, 3, 0, 0],
                        _ => vec![0, 3, 0, 0, 0],
                    },
                }],
            })
            .collect();
        profile.ore_membership = vec![vec![true; 4]];
        let p = &profile;
        let mut checked = 0;
        // Alternating large/small batches catches stale retained buffer entries.
        for &(cx, cz, side) in &[
            (-2, -2, 3),
            (1, 1, 2),
            (-1_874_995, 1_874_995, 3),
            (7, -9, 2),
        ] {
            let request = ChunkRequest {
                seed: 0x1234_5678_9abc_def0,
                chunk_x: cx,
                chunk_z: cz,
                min_y: -64,
                height: 192,
                base_height: 40.,
                amplitude: 0.,
                frequency: 0.008,
                reserved: 0,
            };
            let field = Field {
                origin_x: cx - 1,
                origin_z: cz - 1,
                side: side + 2,
                columns: (0..(side + 2) * (side + 2) * COLUMNS)
                    .map(|i| Column {
                        height: 20 + (i % 16) as i32,
                        packed: 3 << 24,
                        materials: 1 | (1 << 16),
                    })
                    .collect(),
            };
            let expected = plan(&field, &profile, request, side, None);
            let batch = Batch::prepare_compact(&field, &profile, request, side, None);
            let result = gpu.run(&batch).unwrap();
            assert_eq!(result.words.len(), batch.words);
            assert_eq!(
                result.readback_bytes,
                (batch.words * 4) as u64 + if gpu.timestamps.is_some() { 16 } else { 0 }
            );
            let words = result.words;
            for target in 0..side * side {
                let r = ChunkRequest {
                    chunk_x: cx + (target % side) as i32,
                    chunk_z: cz + (target / side) as i32,
                    ..request
                };
                let columns = field.chunk(r.chunk_x, r.chunk_z);
                let mut reference: Vec<_> = (0..r.height)
                    .flat_map(|i| {
                        columns
                            .iter()
                            .map(move |c| c.material(r.min_y + i as i32, r.min_y, Some(p)))
                    })
                    .collect();
                let mut actual = reference.clone();
                apply_ores(r, &field, &profile, None, &expected[target], &mut reference);
                batch.apply_compact(&words, &field, &profile, None, target, &mut actual);
                let differences: Vec<_> = actual
                    .iter()
                    .zip(&reference)
                    .enumerate()
                    .filter(|(_, (a, b))| a != b)
                    .take(16)
                    .map(|(i, (a, b))| (i, *a, *b))
                    .collect();
                assert!(
                    differences.is_empty(),
                    "host/exposure replay changed at {cx},{cz}, slot {target}: {differences:?}"
                );
                checked += 1;
            }
            // Force every candidate to be resolved on the CPU. Deliberately set
            // GPU occupancy wrong too: both sides of a float boundary must repair.
            let mut uncertain = vec![u32::MAX; batch.words];
            for target in 0..side * side {
                let r = ChunkRequest {
                    chunk_x: cx + (target % side) as i32,
                    chunk_z: cz + (target / side) as i32,
                    ..request
                };
                let columns = field.chunk(r.chunk_x, r.chunk_z);
                let mut reference: Vec<_> = (0..r.height)
                    .flat_map(|i| {
                        columns
                            .iter()
                            .map(move |c| c.material(r.min_y + i as i32, r.min_y, Some(p)))
                    })
                    .collect();
                let mut actual = reference.clone();
                apply_ores(r, &field, &profile, None, &expected[target], &mut reference);
                for mask in uncertain.chunks_exact_mut(2) {
                    mask[0] = 0;
                }
                batch.apply_compact(&uncertain, &field, &profile, None, target, &mut actual);
                let differences: Vec<_> = actual
                    .iter()
                    .zip(&reference)
                    .enumerate()
                    .filter(|(_, (a, b))| a != b)
                    .take(16)
                    .map(|(i, (a, b))| (i, *a, *b))
                    .collect();
                assert!(
                    differences.is_empty(),
                    "uncertainty resolution changed geometry at {cx},{cz}, slot {target}: {differences:?}"
                );
            }
        }
        assert_eq!(checked, 26);
    }
}
