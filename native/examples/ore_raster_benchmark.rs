//! End-to-end compact GPU ore raster experiment, NOT a production ore layout.
//! ore_raster_benchmark <profile.json> [iterations] [seed] [chunk_x] [chunk_z] [bits|ordered|compact]
//! Includes descriptor preparation, upload, fence/readback, decode and merging.
//! Production replacement/exposure rules remain on the CPU in both proposals.
use retina_worldgen::{
    ChunkRequest, TerrainEngine,
    decoration::{self, Field},
    geology::{self, OrePlacement, raster_benchmark::Batch},
    profile::WorldProfile,
};
use std::{
    sync::mpsc,
    time::{Duration, Instant},
};

struct RasterGpu {
    device: wgpu::Device,
    queue: wgpu::Queue,
    pipeline: wgpu::ComputePipeline,
    bindings: wgpu::BindGroup,
    descriptors: wgpu::Buffer,
    spheres: wgpu::Buffer,
    output: wgpu::Buffer,
    readback: wgpu::Buffer,
    queries: Option<(wgpu::QuerySet, wgpu::Buffer, wgpu::Buffer)>,
    backend: String,
}
impl RasterGpu {
    fn new(batch: &Batch) -> Result<Self, Box<dyn std::error::Error>> {
        let instance = wgpu::Instance::new(wgpu::InstanceDescriptor {
            backends: wgpu::Backends::METAL | wgpu::Backends::VULKAN | wgpu::Backends::DX12,
            ..wgpu::InstanceDescriptor::new_without_display_handle()
        });
        let adapter = pollster::block_on(instance.request_adapter(&wgpu::RequestAdapterOptions {
            power_preference: wgpu::PowerPreference::HighPerformance,
            compatible_surface: None,
            force_fallback_adapter: false,
            apply_limit_buckets: false,
        }))?;
        let info = adapter.get_info();
        let backend = format!("{:?}: {}", info.backend, info.name);
        let timestamp = adapter.features().contains(wgpu::Features::TIMESTAMP_QUERY);
        let (device, queue) =
            pollster::block_on(adapter.request_device(&wgpu::DeviceDescriptor {
                label: Some("Retina ore experiment"),
                required_features: adapter.features() & wgpu::Features::TIMESTAMP_QUERY,
                required_limits: wgpu::Limits {
                    max_storage_buffer_binding_size:
                        adapter.limits().max_storage_buffer_binding_size,
                    max_buffer_size: adapter.limits().max_buffer_size,
                    ..Default::default()
                },
                ..Default::default()
            }))?;
        let buffer = |label: &str, size: u64, usage| {
            device.create_buffer(&wgpu::BufferDescriptor {
                label: Some(label),
                size: size.max(16),
                usage,
                mapped_at_creation: false,
            })
        };
        let descriptors = buffer(
            "veins",
            (batch.descriptors.len() * 48) as u64,
            wgpu::BufferUsages::STORAGE | wgpu::BufferUsages::COPY_DST,
        );
        let spheres = buffer(
            "spheres",
            (batch.spheres.len() * 16) as u64,
            wgpu::BufferUsages::STORAGE | wgpu::BufferUsages::COPY_DST,
        );
        let output = buffer(
            "ore bits",
            (batch.words * 4) as u64,
            wgpu::BufferUsages::STORAGE | wgpu::BufferUsages::COPY_SRC,
        );
        let readback = buffer(
            "ore readback",
            (batch.words * 4) as u64,
            wgpu::BufferUsages::MAP_READ | wgpu::BufferUsages::COPY_DST,
        );
        let layout = device.create_bind_group_layout(&wgpu::BindGroupLayoutDescriptor {
            label: None,
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
        let pipeline_layout = device.create_pipeline_layout(&wgpu::PipelineLayoutDescriptor {
            label: None,
            bind_group_layouts: &[Some(&layout)],
            immediate_size: 0,
        });
        let shader = device.create_shader_module(wgpu::ShaderModuleDescriptor {
            label: Some("compact ore raster"),
            source: wgpu::ShaderSource::Wgsl(include_str!("../src/ore.wgsl").into()),
        });
        let pipeline = device.create_compute_pipeline(&wgpu::ComputePipelineDescriptor {
            label: None,
            layout: Some(&pipeline_layout),
            module: &shader,
            entry_point: Some(if batch.guarded {
                "guarded"
            } else if batch.ordered {
                "ordered"
            } else {
                "main"
            }),
            compilation_options: Default::default(),
            cache: None,
        });
        let bindings = device.create_bind_group(&wgpu::BindGroupDescriptor {
            label: None,
            layout: &layout,
            entries: &[
                wgpu::BindGroupEntry {
                    binding: 0,
                    resource: descriptors.as_entire_binding(),
                },
                wgpu::BindGroupEntry {
                    binding: 1,
                    resource: spheres.as_entire_binding(),
                },
                wgpu::BindGroupEntry {
                    binding: 2,
                    resource: output.as_entire_binding(),
                },
            ],
        });
        let queries = timestamp.then(|| {
            (
                device.create_query_set(&wgpu::QuerySetDescriptor {
                    label: None,
                    ty: wgpu::QueryType::Timestamp,
                    count: 2,
                }),
                buffer(
                    "ore time resolve",
                    16,
                    wgpu::BufferUsages::QUERY_RESOLVE | wgpu::BufferUsages::COPY_SRC,
                ),
                buffer(
                    "ore time readback",
                    16,
                    wgpu::BufferUsages::MAP_READ | wgpu::BufferUsages::COPY_DST,
                ),
            )
        });
        Ok(Self {
            device,
            queue,
            pipeline,
            bindings,
            descriptors,
            spheres,
            output,
            readback,
            queries,
            backend,
        })
    }
    fn run(&self, batch: &Batch) -> Result<(Vec<u32>, Option<f64>), Box<dyn std::error::Error>> {
        let begin = Instant::now();
        self.queue.write_buffer(
            &self.descriptors,
            0,
            bytemuck::cast_slice(&batch.descriptors),
        );
        self.queue
            .write_buffer(&self.spheres, 0, bytemuck::cast_slice(&batch.spheres));
        let mut encoder = self
            .device
            .create_command_encoder(&wgpu::CommandEncoderDescriptor { label: None });
        {
            let timestamp_writes =
                self.queries
                    .as_ref()
                    .map(|q| wgpu::ComputePassTimestampWrites {
                        query_set: &q.0,
                        beginning_of_pass_write_index: Some(0),
                        end_of_pass_write_index: Some(1),
                    });
            let mut pass = encoder.begin_compute_pass(&wgpu::ComputePassDescriptor {
                label: Some("ore raster"),
                timestamp_writes,
            });
            pass.set_pipeline(&self.pipeline);
            pass.set_bind_group(0, &self.bindings, &[]);
            pass.dispatch_workgroups(256, (batch.descriptors.len() as u32).div_ceil(256), 1);
        }
        encoder.copy_buffer_to_buffer(&self.output, 0, &self.readback, 0, (batch.words * 4) as u64);
        if let Some(q) = &self.queries {
            encoder.resolve_query_set(&q.0, 0..2, &q.1, 0);
            encoder.copy_buffer_to_buffer(&q.1, 0, &q.2, 0, 16);
        }
        let submission = self.queue.submit([encoder.finish()]);
        let (sender, receiver) = mpsc::channel();
        self.readback
            .slice(..)
            .map_async(wgpu::MapMode::Read, move |r| {
                let _ = sender.send(r);
            });
        let time_receiver = self.queries.as_ref().map(|q| {
            let (sender, receiver) = mpsc::channel();
            q.2.slice(..).map_async(wgpu::MapMode::Read, move |r| {
                let _ = sender.send(r);
            });
            receiver
        });
        self.device.poll(wgpu::PollType::Wait {
            submission_index: Some(submission),
            timeout: Some(Duration::from_secs(30)),
        })?;
        receiver.recv_timeout(Duration::from_secs(30))??;
        let words = bytemuck::cast_slice(
            &self
                .readback
                .slice(..(batch.words * 4) as u64)
                .get_mapped_range()?,
        )
        .to_vec();
        self.readback.unmap();
        let device_ms = if let (Some(q), Some(receiver)) = (&self.queries, time_receiver) {
            receiver.recv_timeout(Duration::from_secs(30))??;
            let range = q.2.slice(..).get_mapped_range()?;
            let values: &[u64] = bytemuck::cast_slice(&range);
            let result = (values[0] > 0 && values[1] >= values[0]).then(|| {
                (values[1] - values[0]) as f64 * self.queue.get_timestamp_period() as f64 / 1e6
            });
            drop(range);
            q.2.unmap();
            result
        } else {
            None
        };
        // Some Metal query pairs are stale. A pass cannot outlast the complete
        // upload/fence/readback interval containing it; exclude those pairs.
        let device_ms = device_ms.filter(|ms| *ms <= begin.elapsed().as_secs_f64() * 1000. + 0.1);
        Ok((words, device_ms))
    }
}
fn geometry_signature(plan: &[Vec<OrePlacement>]) -> (usize, u64) {
    let mut hash = 0xcbf29ce484222325u64;
    let mut count = 0;
    for chunk in plan {
        let mut pairs: Vec<_> = chunk.iter().map(|p| (p.recipe, p.index)).collect();
        pairs.sort_unstable();
        count += pairs.len();
        for (recipe, index) in pairs {
            hash = (hash ^ recipe as u64).wrapping_mul(0x100000001b3);
            hash = (hash ^ index as u64).wrapping_mul(0x100000001b3);
        }
        hash = (hash ^ chunk.len() as u64).wrapping_mul(0x100000001b3);
    }
    (count, hash)
}
fn median(samples: &[f64]) -> f64 {
    let mut sorted = samples.to_vec();
    sorted.sort_by(f64::total_cmp);
    sorted[sorted.len() / 2]
}
fn ordered_signature(plan: &[Vec<OrePlacement>]) -> u64 {
    let mut hash = 0xcbf29ce484222325u64;
    for chunk in plan {
        hash = (hash ^ chunk.len() as u64).wrapping_mul(0x100000001b3);
        for p in chunk {
            for v in [p.index, p.recipe, p.random] {
                hash = (hash ^ v as u64).wrapping_mul(0x100000001b3);
            }
        }
    }
    hash
}

fn compact_benchmark(
    engine: &TerrainEngine,
    workers: &rayon::ThreadPool,
    field: &Field,
    mask: Option<&geology::CaveMask>,
    profile: &WorldProfile,
    request: ChunkRequest,
    iterations: usize,
) -> Result<(), Box<dyn std::error::Error>> {
    use rayon::prelude::*;
    let chunk_request = |target: usize| ChunkRequest {
        chunk_x: request.chunk_x + (target % 32) as i32,
        chunk_z: request.chunk_z + (target / 32) as i32,
        ..request
    };
    let base: Vec<Vec<u16>> = workers.install(|| {
        (0..1024)
            .into_par_iter()
            .map(|target| {
                let r = chunk_request(target);
                let mut blocks = vec![0; r.block_count()];
                decoration::assemble_carved(
                    r,
                    field.chunk(r.chunk_x, r.chunk_z),
                    Some(profile),
                    mask,
                    &mut blocks,
                );
                blocks
            })
            .collect()
    });
    let batch = workers.install(|| Batch::prepare_compact(field, profile, request, 32, mask));
    let gpu = RasterGpu::new(&batch)?;
    let signature = |blocks: &[Vec<u16>]| {
        workers.install(|| {
            blocks
                .par_iter()
                .map(|chunk| {
                    chunk.iter().fold(0xcbf29ce484222325u64, |h, v| {
                        (h ^ *v as u64).wrapping_mul(0x100000001b3)
                    })
                })
                .collect::<Vec<_>>()
        })
    };
    let mut cpu_times = Vec::new();
    let mut prep_times = Vec::new();
    let mut dispatch_times = Vec::new();
    let mut apply_times = Vec::new();
    let mut total_times = Vec::new();
    let mut device_times = Vec::new();
    let mut expected = None;
    for iteration in 0..iterations + 5 {
        let cpu = || {
            let mut blocks = base.clone(); // identical base copy excluded from both timers
            let start = Instant::now();
            let plan = workers.install(|| geology::plan(field, profile, request, 32, mask));
            workers.install(|| {
                blocks
                    .par_iter_mut()
                    .enumerate()
                    .for_each(|(target, blocks)| {
                        geology::apply_ores(
                            chunk_request(target),
                            field,
                            profile,
                            mask,
                            &plan[target],
                            blocks,
                        );
                    })
            });
            let elapsed = start.elapsed().as_secs_f64() * 1000.;
            (elapsed, signature(&blocks))
        };
        let compact = || -> Result<_, Box<dyn std::error::Error>> {
            let mut blocks = base.clone();
            let start = Instant::now();
            let batch =
                workers.install(|| Batch::prepare_compact(field, profile, request, 32, mask));
            let prepared = Instant::now();
            let (bits, device) = gpu.run(&batch)?;
            let readback = Instant::now();
            workers.install(|| {
                blocks
                    .par_iter_mut()
                    .enumerate()
                    .for_each(|(target, blocks)| {
                        batch.apply_compact(&bits, field, profile, mask, target, blocks);
                    })
            });
            let finished = Instant::now();
            let times = [
                (prepared - start).as_secs_f64() * 1000.,
                (readback - prepared).as_secs_f64() * 1000.,
                (finished - readback).as_secs_f64() * 1000.,
                (finished - start).as_secs_f64() * 1000.,
            ];
            Ok((times, device, signature(&blocks)))
        };
        let (cpu, (times, device, gpu_hash)) = if iteration % 2 == 0 {
            (cpu(), compact()?)
        } else {
            let candidate = compact()?;
            (cpu(), candidate)
        };
        assert_eq!(
            cpu.1, gpu_hash,
            "compact GPU changed final carved/ore block data"
        );
        if let Some(ref expected) = expected {
            assert_eq!(expected, &gpu_hash, "repeat changed block data");
        } else {
            expected = Some(gpu_hash);
        }
        if iteration >= 5 {
            cpu_times.push(cpu.0);
            prep_times.push(times[0]);
            dispatch_times.push(times[1]);
            apply_times.push(times[2]);
            total_times.push(times[3]);
            if let Some(device) = device {
                device_times.push(device);
            }
        }
    }
    println!(
        "{}",
        serde_json::json!({"backend":engine.backend(),"format":"direct_union_masks",
        "seed":request.seed,"chunk_x":request.chunk_x,"chunk_z":request.chunk_z,
        "workers":16,"warmups":5,"iterations":iterations,"identical_block_chunks":1024*(iterations+5),
        "veins":batch.descriptors.len(),"spheres":batch.spheres.len(),
        "upload_bytes":batch.descriptors.len()*48+batch.spheres.len()*16,"readback_bytes":batch.words*4,
        "median_ms":{"cpu_plan_apply":median(&cpu_times),"prepare":median(&prep_times),
            "upload_dispatch_readback":median(&dispatch_times),"direct_apply":median(&apply_times),
            "gpu_total":median(&total_times),"gpu_device":if device_times.is_empty(){None}else{Some(median(&device_times))}},
        "samples_ms":{"cpu_plan_apply":cpu_times,"prepare":prep_times,"upload_dispatch_readback":dispatch_times,
            "direct_apply":apply_times,"gpu_total":total_times,"gpu_device":device_times}})
    );
    Ok(())
}
fn main() -> Result<(), Box<dyn std::error::Error>> {
    let mut args = std::env::args().skip(1);
    let source = std::fs::read(args.next().ok_or("profile path required")?)?;
    let iterations: usize = args.next().map(|s| s.parse()).transpose()?.unwrap_or(7);
    let seed: u64 = args
        .next()
        .map(|s| s.parse())
        .transpose()?
        .unwrap_or(123456789);
    let chunk_x = args.next().map(|s| s.parse()).transpose()?.unwrap_or(0);
    let chunk_z = args.next().map(|s| s.parse()).transpose()?.unwrap_or(0);
    let format = args.next();
    let ordered = match format.as_deref() {
        None | Some("bits") => false,
        Some("ordered") => true,
        Some("compact") => false,
        Some(_) => return Err("format must be bits, ordered or compact".into()),
    };
    if iterations == 0 {
        return Err("iterations must be positive".into());
    }
    let engine = TerrainEngine::new()?;
    let reserved = engine.register_profile(&source)?;
    let profile = engine.profile(reserved)?.ok_or("missing profile")?;
    let request = ChunkRequest {
        seed,
        chunk_x,
        chunk_z,
        min_y: -64,
        height: 384,
        base_height: 64.,
        amplitude: 48.,
        frequency: 0.008,
        reserved,
    };
    let (field, mask) = engine.terrain_field(request, 32)?;
    let workers = rayon::ThreadPoolBuilder::new().num_threads(16).build()?;
    if format.as_deref() == Some("compact") {
        return compact_benchmark(
            &engine,
            &workers,
            &field,
            mask.as_deref(),
            &profile,
            request,
            iterations,
        );
    }
    let batch =
        workers.install(|| Batch::prepare(&field, &profile, request, 32, mask.as_deref(), ordered));
    if batch.descriptors.is_empty() {
        return Err("no regular veins on this field".into());
    }
    let gpu = RasterGpu::new(&batch)?;
    // Validation is outside the timed measurements.
    let reference = workers.install(|| batch.reference(&profile));
    let (expected, _) = gpu.run(&batch)?;
    let differing_bits: usize = reference
        .iter()
        .zip(&expected)
        .map(|(a, b)| (a ^ b).count_ones() as usize)
        .sum();
    let differing_voxels: usize = if ordered {
        reference
            .iter()
            .zip(&expected)
            .map(|(a, b)| {
                (0..4)
                    .filter(|i| ((a >> (i * 8)) & 255) != ((b >> (i * 8)) & 255))
                    .count()
            })
            .sum()
    } else {
        differing_bits
    };
    let position_differences: usize = if ordered {
        reference
            .iter()
            .zip(&expected)
            .map(|(a, b)| {
                (0..4)
                    .filter(|i| (((a >> (i * 8)) & 255) != 0) != (((b >> (i * 8)) & 255) != 0))
                    .count()
            })
            .sum()
    } else {
        differing_bits
    };
    let cpu_plan =
        workers.install(|| geology::plan(&field, &profile, request, 32, mask.as_deref()));
    let cpu_geometry = geometry_signature(&cpu_plan);
    let cpu_order = ordered_signature(&cpu_plan);
    drop(cpu_plan);
    let diagnostic_plan = workers.install(|| batch.decode(&expected));
    let gpu_geometry = geometry_signature(&diagnostic_plan);
    let gpu_order = ordered_signature(&diagnostic_plan);
    if ordered && differing_bits == 0 {
        assert_eq!(
            cpu_order, gpu_order,
            "ordered output lost production replay/exposure priority"
        );
    }
    drop(diagnostic_plan);
    drop(reference);
    let mut cpu_ms = Vec::new();
    let mut prep_ms = Vec::new();
    let mut dispatch_ms = Vec::new();
    let mut decode_ms = Vec::new();
    let mut total_ms = Vec::new();
    let mut device_ms = Vec::new();
    for iteration in 0..iterations + 3 {
        let cpu = || {
            let begin = Instant::now();
            let plan =
                workers.install(|| geology::plan(&field, &profile, request, 32, mask.as_deref()));
            let ms = begin.elapsed().as_secs_f64() * 1000.;
            std::hint::black_box(&plan);
            drop(plan);
            ms
        };
        let run = || -> Result<_, Box<dyn std::error::Error>> {
            let begin = Instant::now();
            let batch = workers.install(|| {
                Batch::prepare(&field, &profile, request, 32, mask.as_deref(), ordered)
            });
            let prepared = Instant::now();
            let (bits, device) = gpu.run(&batch)?;
            let readback = Instant::now();
            let plan = workers.install(|| batch.decode(&bits));
            let finished = Instant::now();
            let times = [
                (prepared - begin).as_secs_f64() * 1000.,
                (readback - prepared).as_secs_f64() * 1000.,
                (finished - readback).as_secs_f64() * 1000.,
                (finished - begin).as_secs_f64() * 1000.,
            ];
            assert_eq!(expected, bits, "repeated GPU raster output changed");
            std::hint::black_box(&plan);
            drop(plan);
            Ok((times, device))
        };
        let (cpu_time, (times, device)) = if iteration % 2 == 0 {
            (cpu(), run()?)
        } else {
            let result = run()?;
            (cpu(), result)
        };
        if iteration >= 3 {
            cpu_ms.push(cpu_time);
            prep_ms.push(times[0]);
            dispatch_ms.push(times[1]);
            decode_ms.push(times[2]);
            total_ms.push(times[3]);
            if let Some(ms) = device {
                device_ms.push(ms);
            }
        }
    }
    println!(
        "{}",
        serde_json::json!({
            "backend":gpu.backend,"seed":seed,"chunk_x":chunk_x,"chunk_z":chunk_z,"workers":16,"warmups":3,"iterations":iterations,
            "veins":batch.descriptors.len(),"spheres":batch.spheres.len(),"upload_bytes":batch.descriptors.len()*48+batch.spheres.len()*16,"readback_bytes":batch.words*4,
            "cpu_placements":cpu_geometry.0,"gpu_placements":gpu_geometry.0,"cpu_geometry_signature":format!("{:016x}",cpu_geometry.1),"gpu_geometry_signature":format!("{:016x}",gpu_geometry.1),
            "format":if ordered {"first_sphere_bytes"}else{"union_bits"},"raster_differing_bits":differing_bits,"raster_differing_voxels":differing_voxels,"position_differences":position_differences,"exposure_random_parity":cpu_order==gpu_order,
            "cpu_ordered_signature":format!("{cpu_order:016x}"),"gpu_ordered_signature":format!("{gpu_order:016x}"),"unavailable_device_samples":iterations-device_ms.len(),
            "median_ms":{"cpu_plan":median(&cpu_ms),"prepare":median(&prep_ms),"upload_dispatch_readback":median(&dispatch_ms),"decode_merge":median(&decode_ms),"gpu_total":median(&total_ms),"gpu_device":if device_ms.is_empty(){None}else{Some(median(&device_ms))}},
            "samples_ms":{"cpu_plan":cpu_ms,"prepare":prep_ms,"upload_dispatch_readback":dispatch_ms,"decode_merge":decode_ms,"gpu_total":total_ms,"gpu_device":device_ms}
        })
    );
    Ok(())
}
