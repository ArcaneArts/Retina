//! Opt-in seed/geometry investigation, excluded from normal library builds.
use super::*;
use std::{sync::mpsc, time::Duration};

#[repr(C)]
#[derive(Clone, Copy, bytemuck::Pod, bytemuck::Zeroable)]
struct Input {
    origin_size: [i32; 4],
    seed: [u32; 4],
    a: [f32; 4],
    delta: [f32; 4],
}
#[repr(C)]
#[derive(Clone, Copy, bytemuck::Pod, bytemuck::Zeroable)]
struct Sphere {
    geometry: [f32; 4],
    random: [u32; 4],
}

#[test]
#[ignore = "actual GPU seed/geometry probe; use a 30-second process-group deadline"]
fn seed_geometry_probe() {
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
    let (device, queue) =
        pollster::block_on(adapter.request_device(&wgpu::DeviceDescriptor::default())).unwrap();
    let mut inputs = Vec::new();
    let assisted = std::env::var("RETINA_ORE_ASSISTED").as_deref() == Ok("1");
    for origin in [
        [0, -64, 0],
        [-519, -31, 1023],
        [-524_288, 68, 524_288],
        [4_194_303, 128, -4_194_305],
        [-30_000_000, 384, 30_000_000],
    ] {
        for seed in [
            0,
            1,
            u64::MAX,
            0x8123_4567_89ab_cdef,
            123456789,
            0x9e3779b97f4a7c15,
            0x8000000000000000,
            0xffffffff,
        ] {
            for size in 1..=64 {
                let mut random = Random(seed);
                let angle = random.unit() * std::f32::consts::PI;
                let dx = angle.sin() * size as f32 / 8.;
                let dz = angle.cos() * size as f32 / 8.;
                let a = [
                    origin[0] as f32 + dx,
                    origin[1] as f32 + random.int(-2, 0) as f32,
                    origin[2] as f32 + dz,
                    0.,
                ];
                let b = [
                    origin[0] as f32 - dx,
                    origin[1] as f32 + random.int(-2, 0) as f32,
                    origin[2] as f32 - dz,
                    0.,
                ];
                inputs.push(Input {
                    origin_size: [origin[0], origin[1], origin[2], size],
                    seed: [seed as u32, (seed >> 32) as u32, u32::from(assisted), 0],
                    a,
                    delta: std::array::from_fn(|k| b[k] - a[k]),
                });
            }
        }
    }
    let geometry_inputs = inputs.len();
    let special = [
        0.,
        -0.,
        1.,
        -1.,
        0.5,
        -0.5,
        f32::from_bits(1f32.to_bits() + 1),
        f32::from_bits(1f32.to_bits() - 1),
        2f32.powi(-24),
        -2f32.powi(-24),
        2f32.powi(-30),
        2f32.powi(30),
    ];
    let mut pairs: Vec<_> = special
        .iter()
        .flat_map(|&a| special.iter().map(move |&b| (a, b)))
        .collect();
    let mut random = Random(0x8149524bac18ee);
    for _ in 0..2048 {
        let mut next = || {
            let bits = random.next();
            let exponent = random.int(97, 157) as u32;
            f32::from_bits((bits & 0x807fffff) | (exponent << 23))
        };
        pairs.push((next(), next()));
    }
    for &(a, b) in &pairs {
        inputs.push(Input {
            origin_size: [0; 4],
            seed: [0, 0, 0, 1],
            a: [a, 0., 0., 0.],
            delta: [b, 0., 0., 0.],
        });
    }
    let make = |label, size, usage| {
        device.create_buffer(&wgpu::BufferDescriptor {
            label: Some(label),
            size,
            usage,
            mapped_at_creation: false,
        })
    };
    let input = make(
        "ore seeds",
        (inputs.len() * std::mem::size_of::<Input>()) as u64,
        wgpu::BufferUsages::STORAGE | wgpu::BufferUsages::COPY_DST,
    );
    let bytes = (inputs.len() * 64 * 32) as u64;
    let output = make(
        "ore geometry probe",
        bytes,
        wgpu::BufferUsages::STORAGE | wgpu::BufferUsages::COPY_SRC,
    );
    let readback = make(
        "ore probe readback",
        bytes,
        wgpu::BufferUsages::COPY_DST | wgpu::BufferUsages::MAP_READ,
    );
    let table = super::seeded::coefficients();
    let coefficients = make(
        "ore recipe coefficients",
        (table.len() * 8) as u64,
        wgpu::BufferUsages::STORAGE | wgpu::BufferUsages::COPY_DST,
    );
    queue.write_buffer(&coefficients, 0, bytemuck::cast_slice(&table));
    let source = if let Ok(path) = std::env::var("RETINA_ORE_SHADER_DIR") {
        let root = std::path::Path::new(&path);
        std::fs::read_to_string(root.join("float_bits.wgsl")).unwrap()
            + &std::fs::read_to_string(root.join("seed_math.wgsl")).unwrap()
            + &std::fs::read_to_string(root.join("seed_probe.wgsl")).unwrap()
    } else {
        concat!(
            include_str!("float_bits.wgsl"),
            "\n",
            include_str!("seed_math.wgsl"),
            "\n",
            include_str!("seed_probe.wgsl")
        )
        .to_owned()
    };
    let shader = device.create_shader_module(wgpu::ShaderModuleDescriptor {
        label: Some("ore seed preparation probe"),
        source: wgpu::ShaderSource::Wgsl(source.into()),
    });
    let pipeline = device.create_compute_pipeline(&wgpu::ComputePipelineDescriptor {
        label: Some("ore seed preparation probe"),
        layout: None,
        module: &shader,
        entry_point: Some("prepare"),
        compilation_options: Default::default(),
        cache: None,
    });
    let bindings = device.create_bind_group(&wgpu::BindGroupDescriptor {
        label: Some("ore seed probe"),
        layout: &pipeline.get_bind_group_layout(0),
        entries: &[
            wgpu::BindGroupEntry {
                binding: 0,
                resource: input.as_entire_binding(),
            },
            wgpu::BindGroupEntry {
                binding: 1,
                resource: output.as_entire_binding(),
            },
            wgpu::BindGroupEntry {
                binding: 2,
                resource: coefficients.as_entire_binding(),
            },
        ],
    });
    queue.write_buffer(&input, 0, bytemuck::cast_slice(&inputs));
    let mut encoder = device.create_command_encoder(&wgpu::CommandEncoderDescriptor::default());
    {
        let mut pass = encoder.begin_compute_pass(&wgpu::ComputePassDescriptor::default());
        pass.set_pipeline(&pipeline);
        pass.set_bind_group(0, &bindings, &[]);
        pass.dispatch_workgroups(inputs.len() as u32, 1, 1);
    }
    encoder.copy_buffer_to_buffer(&output, 0, &readback, 0, bytes);
    let submission = queue.submit([encoder.finish()]);
    let (tx, rx) = mpsc::channel();
    readback.slice(..).map_async(wgpu::MapMode::Read, move |r| {
        let _ = tx.send(r);
    });
    device
        .poll(wgpu::PollType::Wait {
            submission_index: Some(submission),
            timeout: Some(Duration::from_secs(5)),
        })
        .unwrap();
    rx.recv_timeout(Duration::from_secs(5)).unwrap().unwrap();
    let words = readback.slice(..).get_mapped_range().unwrap();
    let results: &[Sphere] = bytemuck::cast_slice(&words);
    let mut checked = 0;
    let mut differing_centers = 0;
    let mut differing_radii = 0;
    let mut differing_pruning = 0;
    let mut maximum_center_delta = 0f32;
    let mut maximum_radius_delta = 0f32;
    let mut examples = Vec::new();
    for (index, input) in inputs.iter().enumerate() {
        if input.seed[3] == 1 {
            let a = input.a[0];
            let b = input.delta[0];
            let expected = [a + b, a * b, a - b, a * (-b)].map(f32::to_bits);
            assert_eq!(
                results[index * 64].geometry.map(f32::to_bits),
                expected,
                "arithmetic differs for {a:?}/{b:?}"
            );
            continue;
        }
        let [x, y, z, size] = input.origin_size;
        let seed = u64::from(input.seed[0]) | (u64::from(input.seed[1]) << 32);
        let recipe = OreRecipe {
            id: "probe".into(),
            size: size as u32,
            discard: 0.,
            scattered: false,
            count_min: 1,
            count_max: 1,
            rarity: 1,
            height: HeightRange::default(),
            replacement_bands: Vec::new(),
        };
        let mut reference = Vec::new();
        vein_spheres(&recipe, x, y, z, &mut Random(seed), &mut reference);
        let mut random = Random(seed);
        random.next();
        let y0 = random.next();
        let y1 = random.next();
        for (i, expected) in reference.iter().enumerate() {
            let actual = results[index * 64 + i];
            let next = random.next();
            assert_eq!(actual.random[0], next, "seed stream differs at {index}/{i}");
            assert_eq!((actual.random[2], actual.random[3]), (y0, y1));
            assert!(actual.geometry.iter().all(|v| v.is_finite()));
            let t = i as f32 / size as f32;
            let radius = ((std::f32::consts::PI * t).sin() + 1.)
                * ((next >> 8) as f32 / 16777216.)
                * size as f32
                / 32.
                + 0.5;
            let raw_radius = f32::from_bits(actual.random[1]);
            differing_radii += usize::from(raw_radius.to_bits() != radius.to_bits());
            maximum_radius_delta = maximum_radius_delta.max((raw_radius - radius).abs());
            let center_differs =
                (0..3).any(|k| actual.geometry[k].to_bits() != expected[k].to_bits());
            differing_centers += usize::from(center_differs);
            for k in 0..3 {
                maximum_center_delta =
                    maximum_center_delta.max((actual.geometry[k] - expected[k]).abs());
            }
            let pruning_differs = (actual.geometry[3] > 0.) != (expected[3] > 0.);
            differing_pruning += usize::from(pruning_differs);
            if pruning_differs && examples.len() < 8 {
                examples.push(serde_json::json!({"input":input.origin_size,"seed":seed,"sphere":i,"cpu":expected,"gpu":actual.geometry}));
            }
            checked += 1;
        }
    }
    println!(
        "{}",
        serde_json::json!({"adapter":adapter.get_info().name,"assisted":assisted,"inputs":geometry_inputs,"arithmetic_pairs":pairs.len(),"spheres":checked,"random_streams_match":true,"different_centers":differing_centers,"different_radii":differing_radii,"different_pruning":differing_pruning,"maximum_center_delta":maximum_center_delta,"maximum_radius_delta":maximum_radius_delta,"pruning_examples":examples,"note":"Geometry diagnostics only; no mask equivalence or speed claim."})
    );
    if assisted {
        assert_eq!(
            (differing_centers, differing_radii, differing_pruning),
            (0, 0, 0)
        );
    }
    drop(words);
    readback.unmap();
}
