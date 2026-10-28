//! Small real-GPU fixtures: stage scheduling, snapshot isolation and buffer reuse.
use super::*;

#[path = "material_retention_pool_tests.rs"]
mod retention_pool;

pub(super) fn fixture() -> WorldProfile {
    let bytes = std::fs::read(std::env::var("RETINA_MATERIAL_PROFILE").unwrap()).unwrap();
    let mut value: serde_json::Value = serde_json::from_slice(&bytes).unwrap();
    value["program_execution"] = "interpreter".into();
    let profile = WorldProfile::parse(&serde_json::to_vec(&value).unwrap()).unwrap();
    assert!(profile.registry_program.as_ref().unwrap().material_layers);
    profile
}
pub(super) fn request(profile: u32, x: i32, side: u32, height: u32) -> GpuRequest {
    let mut r = GpuRequest::from(crate::ChunkRequest {
        seed: 123456789,
        chunk_x: x,
        chunk_z: -33,
        min_y: -64,
        height,
        base_height: 48.,
        amplitude: 24.,
        frequency: 0.008,
        reserved: profile,
    });
    r.tile_side = side;
    r.padding = side - 2;
    r
}
pub(super) fn equal(a: &GpuSample, b: &GpuSample) {
    assert_eq!(
        bytemuck::cast_slice::<Column, u8>(&a.columns),
        bytemuck::cast_slice::<Column, u8>(&b.columns)
    );
    assert_eq!(
        a.mask.as_ref().map(|m| &m.words),
        b.mask.as_ref().map(|m| &m.words)
    );
    assert_eq!(a.timings.gpu_jobs, b.timings.gpu_jobs);
    assert_eq!(a.timings.gpu_columns, b.timings.gpu_columns);
}
pub(super) fn continuation(gpu: &mut Gpu, pending: PendingSample, times: &timings::Timings) -> PendingSample {
    match gpu.advance(pending, times).unwrap() {
        Progress::Pending(p) => {
            assert!(matches!(p.stage, PendingStage::Materials(_)));
            p
        }
        Progress::Complete(_) => panic!("material emission must yield before completion"),
    }
}

#[test]
#[ignore = "requires RETINA_MATERIAL_PROFILE and a real GPU"]
fn material_continuations_preserve_interleaved_output() {
    let profile = fixture();
    let mut other = profile.clone();
    other.sea_level -= 17;
    let metrics = std::sync::Arc::new(crate::pipeline::Metrics::default());
    let mut gpu = Gpu::new(metrics.clone()).unwrap();
    let times = timings::Timings::default();
    // Distinct profile IDs, dimensions and coordinates exercise snapshot isolation
    // when later submissions replace the owner's shared request/density buffers.
    let a = request(1, -33, 3, 128);
    let b = request(2, 4095, 5, 192);
    let selected = |r: GpuRequest| if r.profile == 2 { &other } else { &profile };
    let mut expected = Vec::new();
    for r in [a, b] {
        let p = gpu.submit(&[r], Some(selected(r)), &times).unwrap();
        let sample = gpu.complete(p, &times).unwrap();
        sample
            .mask
            .as_ref()
            .unwrap()
            .validate_material_runs()
            .unwrap();
        expected.push(sample);
    }
    let before = metrics.snapshot().completed;
    let first = gpu.submit(&[a], Some(&profile), &times).unwrap();
    let second = gpu.submit(&[b], Some(&other), &times).unwrap();
    let first = continuation(&mut gpu, first, &times);
    assert_eq!(metrics.snapshot().completed, before);
    assert_eq!(gpu.readback_pool.len(), 1);
    let second = continuation(&mut gpu, second, &times);
    assert_eq!(metrics.snapshot().completed, before);
    assert_eq!(gpu.readback_pool.len(), 2);
    // Finish in reverse order as an extra guard against accidentally sharing an
    // output or timestamp buffer. Normal worker completion follows queue order.
    equal(&expected[1], &gpu.complete(second, &times).unwrap());
    equal(&expected[0], &gpu.complete(first, &times).unwrap());
    assert_eq!(metrics.snapshot().completed, before + 2);
    assert_eq!(gpu.material_pool.len(), 2);

    // Small -> large -> small requests reuse/grow slots with logical copy sizes.
    for r in [b, a, b, a] {
        let p = gpu.submit(&[r], Some(selected(r)), &times).unwrap();
        let result = gpu.complete(p, &times).unwrap();
        equal(&expected[usize::from(r.profile == 2)], &result);
        assert!(gpu.readback_pool.len() <= 2 && gpu.material_pool.len() <= 2);
    }

    // A plain height request can finish while another job awaits its second pass.
    let p = gpu.submit(&[a], Some(&profile), &times).unwrap();
    let p = continuation(&mut gpu, p, &times);
    let mut probe = a;
    probe.tile_side = 0;
    probe.padding = (1 << 31) | (1 << 29);
    let q = gpu.submit(&[probe], Some(&profile), &times).unwrap();
    assert!(matches!(
        gpu.advance(q, &times).unwrap(),
        Progress::Complete(_)
    ));
    equal(&expected[0], &gpu.complete(p, &times).unwrap());

    // Cancelling either phase must release its maps, without pooling a slot that
    // is still in flight. Subsequent generation must remain usable.
    let p = gpu.submit(&[a], Some(&profile), &times).unwrap();
    drop(p);
    let p = gpu.submit(&[b], Some(&other), &times).unwrap();
    drop(continuation(&mut gpu, p, &times));
    let p = gpu.submit(&[a], Some(&profile), &times).unwrap();
    equal(&expected[0], &gpu.complete(p, &times).unwrap());

    // A failed result is not recycled or counted as a completed job.
    let before = metrics.snapshot().completed;
    let p = gpu.submit(&[a], Some(&profile), &times).unwrap();
    let mut p = continuation(&mut gpu, p, &times);
    if let PendingStage::Materials(m) = &mut p.stage {
        m.mask = None;
    }
    assert!(gpu.complete(p, &times).is_err());
    assert_eq!(metrics.snapshot().completed, before);
    let p = gpu.submit(&[a], Some(&profile), &times).unwrap();
    equal(&expected[0], &gpu.complete(p, &times).unwrap());

    // Adapters without timestamps use the same continuation and reuse path.
    gpu.timestamp_support = false;
    gpu.readback_pool.clear();
    gpu.material_pool.clear();
    let p = gpu.submit(&[a], Some(&profile), &times).unwrap();
    let result = gpu.complete(p, &times).unwrap();
    equal(&expected[0], &result);
    assert_eq!(result.timings.flags, 0);
}

#[test]
#[ignore = "bounded material-only A/B; requires RETINA_MATERIAL_PROFILE and a real GPU"]
fn material_continuation_replay() {
    let profile = fixture();
    let mut gpu = Gpu::new(std::sync::Arc::new(crate::pipeline::Metrics::default())).unwrap();
    let times = timings::Timings::default();
    let requests = [request(1, -33, 8, 192), request(1, 4095, 8, 192)];
    let mut run = |overlap| {
        let start = Instant::now();
        let a = gpu.submit(&[requests[0]], Some(&profile), &times).unwrap();
        let b = gpu.submit(&[requests[1]], Some(&profile), &times).unwrap();
        let (a, b) = if overlap {
            let a = continuation(&mut gpu, a, &times);
            let b = continuation(&mut gpu, b, &times);
            (
                gpu.complete(a, &times).unwrap(),
                gpu.complete(b, &times).unwrap(),
            )
        } else {
            (
                gpu.complete(a, &times).unwrap(),
                gpu.complete(b, &times).unwrap(),
            )
        };
        (start.elapsed().as_secs_f64() * 1000., [a, b])
    };
    let (_, baseline) = run(false);
    let (_, candidate) = run(true);
    for (a, b) in baseline.iter().zip(&candidate) {
        equal(a, b);
    }
    let mut serial = Vec::new();
    let mut overlap = Vec::new();
    for i in 0..5 {
        for mode in [i % 2 == 0, i % 2 != 0] {
            let (ms, samples) = run(mode);
            for (a, b) in baseline.iter().zip(&samples) {
                equal(a, b);
            }
            if mode {
                overlap.push(ms);
            } else {
                serial.push(ms);
            }
        }
    }
    println!(
        "{}",
        serde_json::json!({"fixture":"two 8x8 tiles, height 192", "serial_ms":serial, "continuation_ms":overlap, "exact_outputs":true,
        "retained_emission_bytes":gpu.material_pool.iter().map(|s| s.storage.size()+s.readback.size()).sum::<u64>()})
    );
}

#[test]
#[ignore = "requires RETINA_MATERIAL_PROFILE and a real GPU"]
fn material_runs_outside_generation_bounds_are_single_air_runs() {
    let profile = fixture();
    let generation = profile.registry_program.as_ref().unwrap()
        .generation.as_ref().unwrap();
    let mut gpu = Gpu::new(std::sync::Arc::new(crate::pipeline::Metrics::default())).unwrap();
    let times = timings::Timings::default();
    for budget in [0, 1, 32] {
        gpu.material_retained_runs = budget;
        for height in [1, 32] {
            for min_y in [
                generation.min_y - height,
                generation.min_y + generation.height,
            ] {
                let mut r = request(1, -33, 3, height as u32);
                r.min_y = min_y;
                r.max_y = min_y + height;
                let pending = gpu.submit(&[r], Some(&profile), &times).unwrap();
                let result = gpu.complete(pending, &times).unwrap();
                let mask = result.mask.as_ref().unwrap();
                mask.validate_material_runs().unwrap();
                for z in 0..mask.width - 2 {
                    for x in 0..mask.width - 2 {
                        assert_eq!(mask.material_runs(mask.origin_x + 1 + x as i32,
                            mask.origin_z + 1 + z as i32).unwrap(), &[0],
                            "air outside generation bounds: budget={budget}, min_y={min_y}, height={height}");
                    }
                }
            }
        }
    }
}

#[test]
#[ignore = "requires RETINA_MATERIAL_PROFILE and a real GPU"]
fn retained_material_runs_preserve_fit_overflow_and_interleaved_ownership() {
    let profile = fixture();
    let mut other = profile.clone();
    other.sea_level -= 17;
    let mut gpu = Gpu::new(std::sync::Arc::new(crate::pipeline::Metrics::default())).unwrap();
    let times = timings::Timings::default();
    let a = request(1, -33, 5, 256);
    let b = request(2, 4095, 8, 192);
    let mut single = request(1, 17, 3, 1);
    single.min_y = 40;
    single.max_y = 41;
    let inputs = [a, b, single];
    let selected = |r: GpuRequest| if r.profile == 2 { &other } else { &profile };
    // Budget zero runs the complete original evaluator twice. Normal native
    // libraries have no runtime switch and retain at most 32 runs per column.
    gpu.material_retained_runs = 0;
    let expected: Vec<_> = inputs
        .iter()
        .map(|r| {
            let pending = gpu.submit(&[*r], Some(selected(*r)), &times).unwrap();
            gpu.complete(pending, &times).unwrap()
        })
        .collect();
    let lengths = |sample: &GpuSample| {
        let mask = sample.mask.as_ref().unwrap();
        let width = mask.width - 2;
        (0..width)
            .flat_map(|z| {
                (0..width).map(move |x| {
                    mask.material_runs(mask.origin_x + 1 + x as i32, mask.origin_z + 1 + z as i32)
                        .unwrap()
                        .len()
                })
            })
            .collect::<Vec<_>>()
    };
    assert!(
        lengths(&expected[0]).iter().any(|&n| n > 1),
        "exercise overflow"
    );
    assert!(
        lengths(&expected[2]).iter().all(|&n| n == 1),
        "exercise exact fit"
    );
    for (budget, index) in [
        (1, 0),
        (32, 1),
        (1, 2),
        (0, 1),
        (32, 0),
        (1, 1),
        (32, 2),
        (0, 0),
    ] {
        gpu.material_retained_runs = budget;
        let r = inputs[index];
        let pending = gpu.submit(&[r], Some(selected(r)), &times).unwrap();
        let result = gpu.complete(pending, &times).unwrap();
        equal(&expected[index], &result);
        result
            .mask
            .as_ref()
            .unwrap()
            .validate_material_runs()
            .unwrap();
    }
    // Keep differently sized scratch planes and exact-fit/overflow budgets in
    // flight together, then finish in reverse order. No shared scratch may leak.
    let mut pending = Vec::new();
    for i in 0..8 {
        let index = i % 3;
        gpu.material_retained_runs = if i % 2 == 0 { 1 } else { 32 };
        let r = inputs[index];
        pending.push((index, gpu.submit(&[r], Some(selected(r)), &times).unwrap()));
    }
    for (index, p) in pending.into_iter().rev() {
        equal(&expected[index], &gpu.complete(p, &times).unwrap());
    }
    gpu.material_retained_runs = 32;
    drop(gpu.submit(&[b], Some(&other), &times).unwrap());
    let p = gpu.submit(&[a], Some(&profile), &times).unwrap();
    drop(continuation(&mut gpu, p, &times));
    let p = gpu.submit(&[b], Some(&other), &times).unwrap();
    let mut p = continuation(&mut gpu, p, &times);
    if let PendingStage::Materials(m) = &mut p.stage {
        m.mask = None;
    }
    assert!(gpu.complete(p, &times).is_err());
    gpu.timestamp_support = false;
    gpu.readback_pool.clear();
    gpu.material_pool.clear();
    let p = gpu.submit(&[a], Some(&profile), &times).unwrap();
    let result = gpu.complete(p, &times).unwrap();
    equal(&expected[0], &result);
    assert_eq!(result.timings.flags, 0);
    assert!(gpu.readback_pool.len() <= 2 && gpu.material_pool.len() <= 2);
    println!(
        "retained runs: exact fit, overflow, reverse completion, cancellation, failure and untimed reuse passed"
    );
}

#[test]
#[ignore = "bounded material retention A/B; requires RETINA_MATERIAL_PROFILE and a real GPU"]
fn retained_material_runs_replay() {
    let profile = fixture();
    let mut gpu = Gpu::new(std::sync::Arc::new(crate::pipeline::Metrics::default())).unwrap();
    let times = timings::Timings::default();
    let inputs = [request(1, -33, 22, 256), request(1, 4095, 22, 256)];
    let mut run = |budget, index| {
        gpu.material_retained_runs = budget;
        let start = Instant::now();
        let p = gpu
            .submit(&[inputs[index]], Some(&profile), &times)
            .unwrap();
        let sample = gpu.complete(p, &times).unwrap();
        (start.elapsed().as_secs_f64() * 1000., sample)
    };
    let expected = [run(0, 0).1, run(0, 1).1];
    for i in 0..5 {
        for budget in [0, 32] {
            equal(&expected[i % 2], &run(budget, i % 2).1);
        }
    }
    let mut measurements = Vec::new();
    for i in 0..20 {
        for budget in if i % 2 == 0 { [0, 32] } else { [32, 0] } {
            let (ms, sample) = run(budget, i % 2);
            equal(&expected[i % 2], &sample);
            let mask = sample.mask.as_ref().unwrap();
            let width = mask.width - 2;
            let mut histogram = std::collections::BTreeMap::<usize, u64>::new();
            for z in 0..width {
                for x in 0..width {
                    *histogram
                        .entry(
                            mask.material_runs(
                                mask.origin_x + 1 + x as i32,
                                mask.origin_z + 1 + z as i32,
                            )
                            .unwrap()
                            .len(),
                        )
                        .or_default() += 1;
                }
            }
            measurements.push(serde_json::json!({"pair":i,"retained_runs":budget,"wall_ms":ms,
                "material_device_ms":sample.timings.nanos[timings::MATERIALS] as f64/1e6,
                "run_count_histogram":histogram,"retained_storage_bytes":width*width*budget as usize*4,
                "retained_snapshot_copy_bytes":0}));
        }
    }
    println!(
        "{}",
        serde_json::json!({"measurements":measurements,"exact":true,
        "scope":"Five warmups and twenty pairs of raw 22x22 tiles; all GPU terrain stages, no decoration/assembly/NBT. Interpreter fixture. Counts/histograms occur after timing. This is not complete-region speedup evidence."})
    );
}

#[test]
fn material_retention_respects_actual_binding_limit_without_rejecting_generation() {
    assert_eq!(material_retention(0, 32, 0), (0, 0));
    assert_eq!(material_retention(100, 32, 1599), (0, 0));
    assert_eq!(material_retention(100, 32, 1600), (4, 1600));
    assert_eq!(material_retention(100, 32, 12799), (28, 11200));
    assert_eq!(material_retention(100, 32, 12800), (32, 12800));
    assert_eq!(material_retention(100, 1, 1600), (1, 1600));
    assert_eq!(material_retention(100, 0, u64::MAX), (0, 0));
    assert_eq!(
        material_retention(576 * 576, 32, u64::MAX),
        (32, 42_467_328)
    );
}

#[test]
#[ignore = "requires RETINA_MATERIAL_PROFILE and a real GPU"]
fn retained_material_runs_preserve_invalid_program_failures() {
    let profile = fixture();
    let mut gpu = Gpu::new(std::sync::Arc::new(crate::pipeline::Metrics::default())).unwrap();
    let times = timings::Timings::default();
    let valid_request = request(1, -33, 3, 128);
    gpu.material_retained_runs = 0;
    let pending = gpu
        .submit(&[valid_request], Some(&profile), &times)
        .unwrap();
    let expected = gpu.complete(pending, &times).unwrap();
    for (index, value) in [1_073_741_824.0, 2_147_483_648.0].into_iter().enumerate() {
        let mut invalid = profile.clone();
        let registry = invalid.registry_program.as_mut().unwrap();
        for program in &mut registry.programs[3..3 + invalid.biomes.len()] {
            program.nodes = vec![crate::program::Instruction {
                op: 0,
                a: 0,
                b: 0,
                c: 0,
                p: [value, 0.0, 0.0, 0.0],
            }];
            program.roots = vec![0];
        }
        // Registration accepts finite constants; generation must preserve its
        // existing invalid-run error, even when encoded words would lose a bit.
        registry.validate(invalid.biomes.len()).unwrap();
        let invalid_request = request(2 + index as u32, -33, 3, 128);
        for budget in [0, 1, 32] {
            gpu.material_retained_runs = budget;
            let pending = gpu
                .submit(&[invalid_request], Some(&invalid), &times)
                .unwrap();
            match gpu.complete(pending, &times) {
                Err(error) => assert_eq!(error, "unordered GPU material runs"),
                Ok(_) => panic!("retention budget {budget} accepted an invalid material program"),
            }
            // Failed snapshots must not poison the next valid job or its pools.
            let pending = gpu
                .submit(&[valid_request], Some(&profile), &times)
                .unwrap();
            equal(&expected, &gpu.complete(pending, &times).unwrap());
        }
    }
}

#[test]
#[ignore = "requires RETINA_MATERIAL_PROFILE and a real GPU"]
fn retained_material_counts_keep_cave_scratch_independent() {
    let profile = fixture();
    let mut gpu = Gpu::new(std::sync::Arc::new(crate::pipeline::Metrics::default())).unwrap();
    let times = timings::Timings::default();
    // The widest supported request with a short vertical interval makes run
    // storage much larger than the cave lattice. Counting must not grow or use
    // that shared lattice as an intermediate retained-run buffer.
    let mut input = request(1, -33, 38, 1);
    input.min_y = 40;
    input.max_y = 41;
    gpu.material_retained_runs = 0;
    let pending = gpu.submit(&[input], Some(&profile), &times).unwrap();
    let expected = gpu.complete(pending, &times).unwrap();
    let original_nodes = gpu.cave_buffers.as_ref().unwrap().nodes.size();
    gpu.material_retained_runs = 32;
    let pending = gpu.submit(&[input], Some(&profile), &times).unwrap();
    let PendingStage::Terrain(terrain) = &pending.stage else {
        panic!("expected initial terrain phase");
    };
    let retained = terrain
        .materials
        .as_ref()
        .unwrap()
        .retained
        .as_ref()
        .unwrap();
    assert_eq!(retained.size(), 42_467_328);
    assert_eq!(retained.usage(), wgpu::BufferUsages::STORAGE);
    assert!(original_nodes < retained.size());
    assert_eq!(
        gpu.cave_buffers.as_ref().unwrap().nodes.size(),
        original_nodes
    );
    println!(
        "C78_SCRATCH {}",
        serde_json::json!({
            "without_retention_cave_bytes":original_nodes,
            "with_retention_cave_bytes":gpu.cave_buffers.as_ref().unwrap().nodes.size(),
            "private_retention_bytes":retained.size(),
            "retention_usage":"STORAGE only",
        })
    );
    equal(&expected, &gpu.complete(pending, &times).unwrap());
}

#[test]
#[ignore = "requires an actual GPU; validates retained run word encoding"]
fn retained_material_words_are_exact_on_real_gpu() {
    let gpu = Gpu::new(std::sync::Arc::new(crate::pipeline::Metrics::default())).unwrap();
    let columns = 257usize;
    let runs = 32usize;
    let mut words: Vec<u32> = (0..columns * runs)
        .map(|i| (i as u32).wrapping_mul(0x9e3779b9) & 0x0fffffff)
        .collect();
    words[..8].copy_from_slice(&[
        0, 1, 65535, 65536, 0x007fffff, 0x00800000, 0x0ffffffe, 0x0fffffff,
    ]);
    let input = gpu
        .device
        .create_buffer_init(&wgpu::util::BufferInitDescriptor {
            label: None,
            contents: bytemuck::cast_slice(&words),
            usage: wgpu::BufferUsages::STORAGE,
        });
    let size = (words.len() * 4) as u64;
    let storage = |usage| {
        gpu.device.create_buffer(&wgpu::BufferDescriptor {
            label: None,
            size,
            usage,
            mapped_at_creation: false,
        })
    };
    let nodes = storage(wgpu::BufferUsages::STORAGE);
    let output = storage(wgpu::BufferUsages::STORAGE | wgpu::BufferUsages::COPY_SRC);
    let readback = gpu.readback_buffer("retained words", size);
    // Execute the production encoding functions, not a host reimplementation.
    let materials = include_str!("../materials.wgsl");
    let helpers = &materials[materials.find("fn material_retain(").unwrap()
        ..materials.find("fn material_carved(").unwrap()];
    let source = format!(
        "@group(0) @binding(0) var<storage,read_write> nodes:array<vec4<f32>>;\n\
        @group(0) @binding(1) var<storage,read> words:array<u32>;\n\
        @group(0) @binding(2) var<storage,read_write> output:array<u32>;\n{helpers}\n\
        @compute @workgroup_size(64) fn save(@builtin(global_invocation_id) id:vec3<u32>) {{\
            if id.x>={columns}u {{return;}}\
            for(var run=0u;run<{runs}u;run++) {{material_retain(id.x,run,{columns}u,words[run*{columns}u+id.x]);}}\
        }}\n\
        @compute @workgroup_size(64) fn restore(@builtin(global_invocation_id) id:vec3<u32>) {{\
            if id.x>={columns}u {{return;}}\
            for(var run=0u;run<{runs}u;run++) {{output[run*{columns}u+id.x]=material_retained(id.x,run,{columns}u);}}\
        }}"
    );
    let module = gpu
        .device
        .create_shader_module(wgpu::ShaderModuleDescriptor {
            label: None,
            source: wgpu::ShaderSource::Wgsl(source.into()),
        });
    let layout = gpu
        .device
        .create_bind_group_layout(&wgpu::BindGroupLayoutDescriptor {
            label: None,
            entries: &(0..3)
                .map(|binding| wgpu::BindGroupLayoutEntry {
                    binding,
                    visibility: wgpu::ShaderStages::COMPUTE,
                    ty: wgpu::BindingType::Buffer {
                        ty: wgpu::BufferBindingType::Storage {
                            read_only: binding == 1,
                        },
                        has_dynamic_offset: false,
                        min_binding_size: None,
                    },
                    count: None,
                })
                .collect::<Vec<_>>(),
        });
    let pipeline_layout = gpu
        .device
        .create_pipeline_layout(&wgpu::PipelineLayoutDescriptor {
            label: None,
            bind_group_layouts: &[Some(&layout)],
            immediate_size: 0,
        });
    let group = gpu.device.create_bind_group(&wgpu::BindGroupDescriptor {
        label: None,
        layout: &layout,
        entries: &[&nodes, &input, &output]
            .into_iter()
            .enumerate()
            .map(|(binding, buffer)| wgpu::BindGroupEntry {
                binding: binding as u32,
                resource: buffer.as_entire_binding(),
            })
            .collect::<Vec<_>>(),
    });
    let mut encoder = gpu
        .device
        .create_command_encoder(&wgpu::CommandEncoderDescriptor::default());
    for entry in ["save", "restore"] {
        let pipeline = gpu
            .device
            .create_compute_pipeline(&wgpu::ComputePipelineDescriptor {
                label: None,
                layout: Some(&pipeline_layout),
                module: &module,
                entry_point: Some(entry),
                compilation_options: Default::default(),
                cache: None,
            });
        let mut pass = encoder.begin_compute_pass(&wgpu::ComputePassDescriptor::default());
        pass.set_pipeline(&pipeline);
        pass.set_bind_group(0, &group, &[]);
        pass.dispatch_workgroups((columns as u32).div_ceil(64), 1, 1);
    }
    encoder.copy_buffer_to_buffer(&output, 0, &readback, 0, size);
    let submission = gpu.queue.submit([encoder.finish()]);
    let mut mapping = Mapping::new(&readback, size);
    gpu.device
        .poll(wgpu::PollType::Wait {
            submission_index: Some(submission),
            timeout: Some(Duration::from_secs(30)),
        })
        .unwrap();
    mapping.check().unwrap();
    let mapped = readback.slice(..size).get_mapped_range().unwrap();
    assert_eq!(bytemuck::cast_slice::<u8, u32>(&mapped), words);
}
