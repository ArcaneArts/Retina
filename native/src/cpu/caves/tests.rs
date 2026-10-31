use super::*;
use crate::{
    geology::Carver,
    world_definition::{WorldDefinition, catalog::WorldCatalog},
};
use std::sync::Arc;
fn definition() -> WorldDefinition {
    let c = WorldCatalog::read(std::path::Path::new(concat!(
        env!("CARGO_MANIFEST_DIR"),
        "/../assets/world-definitions/26.3/vanilla-binary/retina-catalog.dat"
    )))
    .unwrap();
    WorldDefinition::from_gzip(c.materialize("normal", 123456789).unwrap().as_slice()).unwrap()
}
fn carver(kind: u32, probability: f32) -> Carver {
    serde_json::from_value(serde_json::json!({"id":"test:carver","kind":kind,"probability":probability,"height":{"min":-30,"max":80,"triangle":true,"plateau":5},"count":2.0,"thickness":3.0,"horizontal":2.0,"vertical":1.5,"floor":-0.7,"room":2.0,"thickness_range":[2.0,5.0],"horizontal_range":[1.0,2.0],"distance_range":[0.5,1.0],"vertical_range":[1.0,2.0],"rotation_range":[-0.2,0.2],"width_smoothness":3.0,"vertical_default":1.0,"vertical_center":0.5})).unwrap()
}
#[test]
fn zero_probability_and_finite_carver_settings_remain_active_contracts() {
    let c = carver(0, 0.0);
    for point in [
        [-8192.0, -64.0, 512.0],
        [0.0, 0.0, 0.0],
        [17.0, 63.0, -31.0],
        [30000000.0, 32.0, -30000000.0],
    ] {
        assert_eq!(carvers::tunnel(point, 0xffffffff, &c), 1000.0);
        assert_eq!(carvers::room(point, 0xffffffff, &c), 1000.0);
        assert_eq!(carvers::ravine(point, 0xffffffff, &carver(1, 0.0)), 1000.0);
        assert!(carvers::tunnel(point, 0xffffffff, &carver(0, 1.0)).is_finite());
        assert!(carvers::room(point, 0xffffffff, &carver(0, 1.0)).is_finite());
        assert!(carvers::ravine(point, 0xffffffff, &carver(1, 1.0)).is_finite());
    }
    let mut tunnels = 0;
    let mut rooms = 0;
    let mut ravines = 0;
    let c = carver(0, 1.0);
    let r = carver(1, 1.0);
    for x in (0..128).step_by(4) {
        for z in (0..128).step_by(4) {
            for y in (-32..80).step_by(4) {
                let p = [x as f32, y as f32, z as f32];
                tunnels += usize::from(carvers::tunnel(p, 12345, &c) < 0.0);
                rooms += usize::from(carvers::room(p, 12345, &c) < 0.0);
                ravines += usize::from(carvers::ravine(p, 12345, &r) < 1.0);
            }
        }
    }
    assert!(
        tunnels > 0 && rooms > 0 && ravines > 0,
        "{tunnels}/{rooms}/{ravines}"
    );
}
#[test]
fn actual_registered_cave_fields_repeat_across_order_and_source_bounds() {
    let d = definition();
    for key in ["minecraft:overworld", "minecraft:the_nether"] {
        let dimension = d.dimension(key).unwrap();
        let p = dimension.parameters();
        let profile = dimension.profile().unwrap();
        let r = ChunkRequest {
            seed: d.seed() as u64,
            chunk_x: -1,
            chunk_z: -1,
            min_y: p.min_y,
            height: p.height as u32,
            base_height: p.base_height,
            amplitude: p.amplitude,
            frequency: p.frequency,
            reserved: 1,
        };
        for legacy in [false, true] {
            let mut loaded = profile.as_ref().clone();
            if legacy {
                loaded.registry_program = None;
            }
            let profile = &loaded;
            let f = Fields::new(profile).unwrap();
            let halo = ColumnHalo {
                origin_x: -1,
                origin_z: -1,
                side: 2,
                columns: Arc::new(vec![
                    Column {
                        height: if key.contains("nether") { 128 } else { 70 },
                        packed: 0,
                        materials: 0
                    };
                    1024
                ]),
            };
            let mut a = Caves::new(profile, &f, r, &halo);
            let mut b = Caves::new(
                profile,
                &f,
                ChunkRequest {
                    chunk_x: -33,
                    chunk_z: 32,
                    ..r
                },
                &halo,
            );
            let points = [
                [-16, -32, -16],
                [-4, -4, -4],
                [0, 24, 0],
                [11, 51, 13],
                [15, 64, 15],
            ];
            let values: Vec<_> = points
                .iter()
                .map(|&p| {
                    (
                        a.values(p).unwrap(),
                        a.biome(p).unwrap(),
                        a.carved(p).unwrap(),
                    )
                })
                .collect();
            for (p, (values, biome, carved)) in points.iter().zip(values).rev() {
                assert!(values.iter().all(|v| v.is_finite()));
                assert_eq!(
                    values.map(f32::to_bits),
                    b.values(*p).unwrap().map(f32::to_bits)
                );
                assert_eq!(biome, b.biome(*p).unwrap());
                assert!((biome as usize) < profile.biomes.len());
                assert_eq!(carved, b.carved(*p).unwrap());
            }
            let bounds = a.sampler.bounds();
            assert!(a.carved([0, bounds[0] - 1, 0]).unwrap());
            assert!(a.carved([0, bounds[1], 0]).unwrap());
        }
    }
}

fn function(source: &str, name: &str) -> String {
    let start = source.find(&format!("fn {name}(")).unwrap();
    let mut depth = 0;
    for (offset, c) in source[start..].char_indices() {
        if c == '{' {
            depth += 1;
        }
        if c == '}' {
            depth -= 1;
            if depth == 0 {
                return source[start..start + offset + 1].into();
            }
        }
    }
    panic!("unterminated {name}")
}
#[test]
#[ignore = "requires a real hardware GPU; loaded carver numerical comparison"]
fn loaded_cpu_carvers_match_actual_shader_kernels() {
    use wgpu::util::DeviceExt;
    let gpu = crate::gpu::Gpu::new(Default::default()).unwrap();
    let (device, queue) = gpu.ore_planner().shared_device();
    let mut source = String::from(
        "struct Carver {range:vec4<f32>,shape:vec4<f32>,detail:vec4<f32>,variation:vec4<f32>,length:vec4<f32>,axis:vec4<f32>}\nstruct Input {point:vec4<f32>,settings:Carver}\n@group(0) @binding(0) var<storage,read> inputs:array<Input>;\n@group(0) @binding(1) var<storage,read_write> output:array<vec4<f32>>;\n@compute @workgroup_size(64) fn parity(@builtin(global_invocation_id) id:vec3<u32>) {let i=inputs[id.x];let seed=bitcast<u32>(i.point.w);output[id.x]=vec4<f32>(enclosed_tunnel(i.point.xyz,seed,i.settings),enclosed_room(i.point.xyz,seed,i.settings),ravine_distance(i.point.xyz,seed,i.settings),0.0);}\n",
    );
    for name in [
        "hash",
        "corner",
        "noise3",
        "enclosed_tunnel",
        "enclosed_room",
        "ravine_distance",
    ] {
        source.push_str(&function(include_str!("../../caves.wgsl"), name));
        source.push('\n');
    }
    let mut inputs = Vec::new();
    let mut expected = Vec::new();
    let mut room_coordinate_errors = Vec::new();
    for i in 0..256 {
        let point = if i < 192 {
            [
                (i % 16) as f32 * 8.0 - 64.0,
                ((i * 17) % 96) as f32 - 32.0,
                (i / 16) as f32 * 8.0 - 64.0,
            ]
        } else {
            [
                30000000.0 + (i % 16) as f32 * 8.0,
                ((i * 17) % 96) as f32 - 32.0,
                -30000000.0 + (i / 16) as f32 * 8.0,
            ]
        };
        let c = carver((i % 3 == 0) as u32, if i % 7 == 0 { 0.0 } else { 1.0 });
        let seed = 0x9abcdef0u32;
        // Reassociation may move a world-border room center by a representable
        // coordinate step. Bound the two rounding operations in (cell+jitter)*size,
        // then propagate that position interval through squared normalized distance.
        let spacing = |v: f32| {
            let v = v.abs();
            f32::from_bits(v.to_bits() + 1) - v
        };
        let size = (17.0 * c.horizontal).max(32.0);
        let center_error =
            |v: f32| spacing((v / size).abs() + 2.0) * size + spacing(v.abs() + 2.0 * size);
        let normalized_error = ((center_error(point[0]) / (2.5 * c.horizontal)).powi(2)
            + (center_error(point[2]) / (2.5 * c.horizontal)).powi(2))
        .sqrt();
        room_coordinate_errors.push(normalized_error);
        inputs.extend(point);
        inputs.push(f32::from_bits(seed));
        inputs.extend(c.parameters());
        expected.push([
            carvers::tunnel(point, seed, &c),
            carvers::room(point, seed, &c),
            carvers::ravine(point, seed, &c),
        ]);
    }
    let module = device.create_shader_module(wgpu::ShaderModuleDescriptor {
        label: Some("CPU carver parity"),
        source: wgpu::ShaderSource::Wgsl(source.into()),
    });
    let pipeline = device.create_compute_pipeline(&wgpu::ComputePipelineDescriptor {
        label: None,
        layout: None,
        module: &module,
        entry_point: Some("parity"),
        compilation_options: Default::default(),
        cache: None,
    });
    let input = device.create_buffer_init(&wgpu::util::BufferInitDescriptor {
        label: None,
        contents: bytemuck::cast_slice(&inputs),
        usage: wgpu::BufferUsages::STORAGE,
    });
    let output = device.create_buffer(&wgpu::BufferDescriptor {
        label: None,
        size: 4096,
        usage: wgpu::BufferUsages::STORAGE | wgpu::BufferUsages::COPY_SRC,
        mapped_at_creation: false,
    });
    let read = device.create_buffer(&wgpu::BufferDescriptor {
        label: None,
        size: 4096,
        usage: wgpu::BufferUsages::MAP_READ | wgpu::BufferUsages::COPY_DST,
        mapped_at_creation: false,
    });
    let bindings = device.create_bind_group(&wgpu::BindGroupDescriptor {
        label: None,
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
        ],
    });
    let mut encoder = device.create_command_encoder(&Default::default());
    {
        let mut pass = encoder.begin_compute_pass(&Default::default());
        pass.set_pipeline(&pipeline);
        pass.set_bind_group(0, &bindings, &[]);
        pass.dispatch_workgroups(4, 1, 1);
    }
    encoder.copy_buffer_to_buffer(&output, 0, &read, 0, 4096);
    queue.submit([encoder.finish()]);
    let (send, receive) = std::sync::mpsc::channel();
    read.slice(..)
        .map_async(wgpu::MapMode::Read, move |r| send.send(r).unwrap());
    device
        .poll(wgpu::PollType::Wait {
            submission_index: None,
            timeout: Some(std::time::Duration::from_secs(30)),
        })
        .unwrap();
    receive.recv().unwrap().unwrap();
    let bytes = read.slice(..).get_mapped_range().unwrap();
    let actual: &[f32] = bytemuck::cast_slice(&bytes);
    let mut maximum = [0.0f32; 3];
    for (i, values) in expected.iter().enumerate() {
        for channel in 0..3 {
            let a = actual[i * 4 + channel];
            let b = values[channel];
            let error = (a - b).abs();
            maximum[channel] = maximum[channel].max(error);
            let mut tolerance = 0.0001 + 0.001 * a.abs().max(b.abs());
            if i >= 192 && channel == 1 {
                let delta = room_coordinate_errors[i];
                tolerance += 2.0 * (a.max(b).max(0.0) + 1.0).sqrt() * delta + delta * delta;
            }
            assert!(
                error <= tolerance,
                "carver {i}/{channel}: GPU {a} CPU {b} error {error}, bound {tolerance}"
            );
        }
    }
    eprintln!("CPU/GPU 256 loaded carver fixtures; maximum absolute errors {maximum:?}");
}
