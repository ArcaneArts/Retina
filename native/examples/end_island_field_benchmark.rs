//! Bounded actual-device CPU/GPU comparison. No regions are generated.
//! Supply an independent island-reference.json to validate source values as well.
use retina_worldgen::end::{CandidateField, IslandNoise};
use serde_json::{Value, json};
use std::{error::Error, sync::mpsc, time::{Duration, Instant}};
use wgpu::util::DeviceExt;

struct Gpu {
    device: wgpu::Device,
    queue: wgpu::Queue,
    pipeline: wgpu::ComputePipeline,
    backend: String,
}
impl Gpu {
    fn new() -> Result<Self, Box<dyn Error>> {
        let instance = wgpu::Instance::new(wgpu::InstanceDescriptor {
            backends: wgpu::Backends::METAL | wgpu::Backends::VULKAN | wgpu::Backends::DX12,
            ..wgpu::InstanceDescriptor::new_without_display_handle()
        });
        let adapter = pollster::block_on(instance.request_adapter(&wgpu::RequestAdapterOptions {
            power_preference: wgpu::PowerPreference::HighPerformance,
            compatible_surface: None, force_fallback_adapter: false, apply_limit_buckets: false,
        }))?;
        let info = adapter.get_info();
        let (device, queue) = pollster::block_on(adapter.request_device(&wgpu::DeviceDescriptor {
            label: Some("Retina End shared field experiment"), ..Default::default()
        }))?;
        let shader = device.create_shader_module(wgpu::ShaderModuleDescriptor {
            label: Some("End island contribution experiment"),
            source: wgpu::ShaderSource::Wgsl(include_str!("end_island_field.wgsl").into()),
        });
        let pipeline = device.create_compute_pipeline(&wgpu::ComputePipelineDescriptor {
            label: Some("End island contribution experiment"), layout: None,
            module: &shader, entry_point: Some("main"), compilation_options: Default::default(), cache: None,
        });
        Ok(Self { device, queue, pipeline, backend: format!("{:?}: {}", info.backend, info.name) })
    }
    /// Includes buffer allocation, table/point upload, dispatch, fence/readback,
    /// decoding and dropping per-batch resources. No resident-table advantage is
    /// assumed: each generation neighborhood has a different candidate table.
    fn run(&self, field: &CandidateField, points: &[[i32; 2]]) -> Result<Vec<[f32; 2]>, Box<dyn Error>> {
        let origin = field.origin_chunk();
        let params = [origin[0], origin[1], field.width() as i32, points.len() as i32];
        let input = |label: &str, bytes: &[u8], usage| self.device.create_buffer_init(&wgpu::util::BufferInitDescriptor {
            label: Some(label), contents: bytes, usage,
        });
        let table = input("End center sizes", bytemuck::cast_slice(field.sizes()), wgpu::BufferUsages::STORAGE);
        let positions = input("End field points", bytemuck::cast_slice(points), wgpu::BufferUsages::STORAGE);
        let params = input("End field bounds", bytemuck::cast_slice(&params), wgpu::BufferUsages::UNIFORM);
        let bytes = points.len() as u64 * 8;
        let output = self.device.create_buffer(&wgpu::BufferDescriptor {
            label: Some("End field output"), size: bytes,
            usage: wgpu::BufferUsages::STORAGE | wgpu::BufferUsages::COPY_SRC, mapped_at_creation: false,
        });
        let readback = self.device.create_buffer(&wgpu::BufferDescriptor {
            label: Some("End field readback"), size: bytes,
            usage: wgpu::BufferUsages::COPY_DST | wgpu::BufferUsages::MAP_READ, mapped_at_creation: false,
        });
        let group = self.device.create_bind_group(&wgpu::BindGroupDescriptor {
            label: Some("End field batch"), layout: &self.pipeline.get_bind_group_layout(0),
            entries: &[wgpu::BindGroupEntry { binding: 0, resource: params.as_entire_binding() },
                wgpu::BindGroupEntry { binding: 1, resource: table.as_entire_binding() },
                wgpu::BindGroupEntry { binding: 2, resource: positions.as_entire_binding() },
                wgpu::BindGroupEntry { binding: 3, resource: output.as_entire_binding() }],
        });
        let mut encoder = self.device.create_command_encoder(&wgpu::CommandEncoderDescriptor { label: None });
        {
            let mut pass = encoder.begin_compute_pass(&wgpu::ComputePassDescriptor { label: None, timestamp_writes: None });
            pass.set_pipeline(&self.pipeline); pass.set_bind_group(0, &group, &[]);
            pass.dispatch_workgroups((points.len() as u32).div_ceil(64), 1, 1);
        }
        encoder.copy_buffer_to_buffer(&output, 0, &readback, 0, bytes);
        let submission = self.queue.submit([encoder.finish()]);
        let (sender, receiver) = mpsc::channel();
        readback.slice(..).map_async(wgpu::MapMode::Read, move |r| { let _ = sender.send(r); });
        self.device.poll(wgpu::PollType::Wait { submission_index: Some(submission), timeout: Some(Duration::from_secs(30)) })?;
        receiver.recv_timeout(Duration::from_secs(30))??;
        let result = bytemuck::cast_slice(&readback.slice(..).get_mapped_range()?).to_vec();
        readback.unmap();
        Ok(result)
    }
}
fn compare(cpu: &[[f32; 2]], gpu: &[[f32; 2]]) -> Value {
    let mut mismatch = [0usize; 2]; let mut max_abs = [0.0f32; 2]; let mut biome_mismatch = 0;
    let classify = |v: f32| if v > 0.25 {0} else if v >= -0.0625 {1} else if v < -0.21875 {2} else {3};
    for (a,b) in cpu.iter().zip(gpu) {
        for i in 0..2 { mismatch[i] += usize::from(a[i].to_bits() != b[i].to_bits()); max_abs[i] = max_abs[i].max((a[i]-b[i]).abs()); }
        biome_mismatch += usize::from(classify(a[1]) != classify(b[1]));
    }
    json!({"points":cpu.len(), "outer_bit_mismatches":mismatch[0], "combined_bit_mismatches":mismatch[1],
        "maximum_absolute_error":max_abs, "outer_biome_threshold_mismatches":biome_mismatch})
}
fn cpu(field: &CandidateField, points: &[[i32; 2]]) -> Vec<[f32; 2]> {
    points.iter().map(|&[x,z]| {
        let outer = field.outer(x,z).unwrap();
        let fx = x as f32; let fz = z as f32;
        let main = ((100.0 - (fx*fx+fz*fz).sqrt()).clamp(-100.0,80.0)-8.0)*0.0078125;
        [outer, main.max(outer)]
    }).collect()
}
fn main() -> Result<(), Box<dyn Error>> {
    let startup = Instant::now(); let gpu = Gpu::new()?; let startup_ms = startup.elapsed().as_secs_f64()*1000.;
    let mut source_points = 0; let mut source_gpu_mismatches = 0;
    let mut source_outer_mismatches = 0; let mut mismatch_examples = Vec::new();
    if let Some(path) = std::env::args().nth(1) {
        let source: Value = serde_json::from_slice(&std::fs::read(path)?)?;
        for group in source["seeds"].as_array().ok_or("reference seeds")? {
            let noise = IslandNoise::new(group["seed"].as_i64().ok_or("reference seed")? as u64);
            for p in group["samples"].as_array().ok_or("reference samples")? {
                let x = p["xz"][0].as_i64().unwrap() as i32; let z = p["xz"][1].as_i64().unwrap() as i32;
                let field = noise.candidate_field(x,z,x,z)?;
                let actual = field.sample(x,z).unwrap();
                let expected = p["island_bits"].as_i64().unwrap() as u32;
                if actual.to_bits() != expected { return Err(format!("CPU field reference mismatch {x},{z}").into()); }
                let output = gpu.run(&field, &[[x,z]])?;
                let mismatch = output[0][1].to_bits() != expected;
                source_gpu_mismatches += usize::from(mismatch);
                if let Some(outer) = p["outer_bits"].as_i64() {
                    if field.outer(x,z).unwrap().to_bits() != outer as u32 { return Err("CPU outer field reference mismatch".into()); }
                    source_outer_mismatches += usize::from(output[0][0].to_bits() != outer as u32);
                }
                if mismatch && mismatch_examples.len() < 8 {
                    mismatch_examples.push(json!({"seed":group["seed"],"xz":[x,z],
                        "reference_bits":expected,"gpu_bits":output[0][1].to_bits()}));
                }
                source_points += 1;
            }
        }
    }
    let mut cases = Vec::new();
    for (name,x,z,side) in [("central",-256,-256,65), ("outer",4096,-528,77),
        ("negative",-4608,-4096,77), ("far",-16777217,29999400,77)] {
        let noise = IslandNoise::new(123456789);
        let points: Vec<_> = (0..side).flat_map(|dz| (0..side).map(move |dx| [x+dx*8,z+dz*8])).collect();
        let build = Instant::now(); let field = noise.candidate_field(x,z,x+(side-1)*8,z+(side-1)*8)?;
        let build_ms = build.elapsed().as_secs_f64()*1000.;
        let expected = cpu(&field,&points);
        for (i,&[px,pz]) in points.iter().enumerate() {
            if expected[i][1].to_bits() != noise.sample(px,pz).to_bits() { return Err("CPU batch differs from scalar".into()); }
        }
        let warm = gpu.run(&field,&points)?;
        let parity = compare(&expected,&warm);
        let mut cpu_ms = Vec::new(); let mut gpu_ms = Vec::new();
        for trial in 0..8 {
            let time_cpu = || { let begin = Instant::now(); std::hint::black_box(cpu(&field,&points)); begin.elapsed().as_secs_f64()*1000. };
            let time_gpu = || -> Result<f64,Box<dyn Error>> { let begin = Instant::now(); std::hint::black_box(gpu.run(&field,&points)?); Ok(begin.elapsed().as_secs_f64()*1000.) };
            if trial % 2 == 0 { cpu_ms.push(time_cpu()); gpu_ms.push(time_gpu()?); }
            else { gpu_ms.push(time_gpu()?); cpu_ms.push(time_cpu()); }
        }
        cases.push(json!({"name":name,"origin":[x,z],"points":points.len(),"candidate_centers":field.sizes().len(),"accepted_centers":field.active_centers(),
            "candidate_build_ms":build_ms,"candidate_bytes":field.sizes().len()*4,"active_center_bytes":field.active_centers()*12,
            "upload_bytes":16+field.sizes().len()*4+points.len()*8,"readback_bytes":points.len()*8,
            "parity":parity,"cpu_sample_ms":cpu_ms,"gpu_upload_dispatch_readback_ms":gpu_ms}));
    }
    println!("{}", json!({"schema":"retina.end.island-field-cpu-gpu.v1","backend":gpu.backend,"cold_gpu_startup_ms":startup_ms,
        "reference_points":source_points,"reference_cpu_exact":true,"reference_gpu_bit_mismatches":source_gpu_mismatches,"reference_outer_gpu_bit_mismatches":source_outer_mismatches,
        "reference_mismatch_examples":mismatch_examples,
        "cases":cases,"scope":"shared candidate construction separately recorded; CPU/GPU contribution evaluation, GPU allocations/uploads/fence/readback/cleanup included; no full regions generated"}));
    Ok(())
}
