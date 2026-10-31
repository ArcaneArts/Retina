use super::gpu::*;
use super::*;
use crate::decoration::provider_noise::{Layer, Program};
use std::{sync::Mutex, time::Instant};

fn gpu(timestamps: bool) -> Gpu {
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
    let features = if timestamps {
        adapter.features() & wgpu::Features::TIMESTAMP_QUERY
    } else {
        wgpu::Features::empty()
    };
    let (device, queue) = pollster::block_on(adapter.request_device(&wgpu::DeviceDescriptor {
        label: Some("Retina bounded sparse sample check"),
        required_features: features,
        ..Default::default()
    }))
    .unwrap();
    Gpu::new(device, queue)
}

fn fixture(
    shift: u32,
    counts: usize,
    providers: usize,
) -> (Noise, Vec<Query>, Vec<Program>, Vec<[i32; 4]>) {
    let permutation = (0..256).map(|v| (v * 73 + shift) % 256).collect::<Vec<_>>();
    let noise = Noise {
        permutation: permutation.clone(),
    };
    let programs = vec![
        Program {
            coordinate_scale: None,
            layers: vec![
                Layer {
                    frequency: 0.013,
                    amplitude: 0.6,
                    offsets: [123.1, -65.4, 0.25],
                    permutation: permutation.clone(),
                },
                Layer {
                    frequency: 0.071,
                    amplitude: -0.3,
                    offsets: [7.0, 9.0, -11.0],
                    permutation,
                },
            ],
        },
        Program {
            coordinate_scale: Some(0.013),
            layers: vec![Layer {
                frequency: 0.0125,
                amplitude: 0.7,
                offsets: [-11.1, 2.2, 3.3],
                permutation: (0..256).rev().collect(),
            }],
        },
    ];
    let queries = (0..counts)
        .map(|i| Query {
            x: (i as i32 * 1999) - 30000000,
            z: 30000000 - i as i32 * 137,
            rule: match i % 3 {
                0 => Rule::threshold(-0.3, 5, 17),
                1 => Rule::based(120, 61.0, 0.3),
                _ => Rule::threshold(0.2, 0, 21),
            },
        })
        .collect();
    let points = (0..providers)
        .map(|i| {
            [
                30000000 - i as i32 * 29,
                (i % 384) as i32 - 64,
                -30000000 + i as i32 * 73,
                (i % 2) as i32,
            ]
        })
        .collect();
    (noise, queries, programs, points)
}

fn replay_fixture(
    gpu: &mut Gpu,
    id: u32,
    origin: i32,
    combined: bool,
) -> (Vec<Vec<crate::decoration::Placement>>, usize) {
    use crate::decoration::{Replay, provider_noise::Samples};
    let (mut profile, field, request) = crate::decoration::replay_tests::fixture(123456789, origin);
    let (noise, _, mut programs, _) = fixture(id, 0, 0);
    programs.push(programs[0].clone());
    profile.decoration_provider_noises = programs;
    let mut counts = Counts::default();
    let mut samples = Samples::default();
    let mut replay = Replay::new(&field, &profile, request, origin, origin, 2, None, None);
    let mut mixed = 0;
    for _ in 0..16 {
        let queries = replay.poll(&samples, Some(&counts));
        if queries.is_empty() {
            return (replay.finish(), mixed);
        }
        mixed += usize::from(!queries.counts.is_empty() && !queries.noise.is_empty());
        let (c, p) = if combined {
            let result = gpu
                .run_batch(
                    id,
                    Some(&noise),
                    &queries.counts,
                    &profile.decoration_provider_noises,
                    &queries.noise,
                )
                .unwrap();
            (result.counts, result.providers)
        } else {
            let c = gpu.run(id, &noise, &queries.counts).unwrap().values;
            let p = gpu
                .run_providers(id, &profile.decoration_provider_noises, &queries.noise)
                .unwrap()
                .values;
            (c, p)
        };
        counts.extend(
            queries
                .counts
                .into_iter()
                .zip(c.into_iter().map(|v| v as i32)),
        );
        samples.extend(
            queries
                .noise
                .into_iter()
                .zip(p.into_iter().map(f32::from_bits)),
        );
    }
    panic!("actual-GPU decoration replay failed to converge");
}

#[test]
#[ignore = "actual GPU, bounded sparse counts/provider correctness; use 30s process deadline"]
fn combined_samples_match_separate_dispatches() {
    for timestamps in [false, true] {
        let mut gpu = gpu(timestamps);
        let mut references = Vec::new();
        // Odd output lengths, binding alignment gaps, resized/reused buffers,
        // and >256 workgroups exercise the second dispatch dimension.
        for (id, n, m) in [
            (1, 1, 1),
            (2, 65, 16385),
            (3, 129, 3),
            (4, 0, 67),
            (5, 67, 0),
            (6, 0, 0),
        ] {
            let (noise, queries, programs, points) = fixture(id * 7, n, m);
            let counts = gpu.run(id, &noise, &queries).unwrap();
            let providers = gpu.run_providers(id, &programs, &points).unwrap();
            let batch = gpu
                .run_batch(id, Some(&noise), &queries, &programs, &points)
                .unwrap();
            assert_eq!(counts.values, batch.counts);
            assert_eq!(providers.values, batch.providers);
            assert_eq!(
                batch.upload,
                ((n + m) * 16) as u64,
                "resident rules must not re-upload"
            );
            assert_eq!(
                batch.device_nanos.map(|v| v.is_some()),
                [
                    counts.device_nanos.is_some(),
                    providers.device_nanos.is_some()
                ]
            );
            assert!(batch.readback >= ((n + m) * 4) as u64);
            assert!(batch.readback <= ((n + m) * 4 + 256 + 32) as u64);
            if n + m == 0 {
                assert_eq!((batch.upload, batch.readback), (0, 0));
            }
            references.push((
                id,
                noise,
                queries,
                programs,
                points,
                batch.counts,
                batch.providers,
            ));
        }
        let (id, noise, queries, programs, points, expected_counts, expected_providers) =
            &references[0];
        for invalid in [-1, programs.len() as i32] {
            assert!(
                gpu.run_batch(*id, Some(noise), queries, programs, &[[0, 0, 0, invalid]])
                    .is_err()
            );
        }
        assert!(gpu.run_batch(*id, None, queries, programs, points).is_err());
        let after_error = gpu
            .run_batch(*id, Some(noise), queries, programs, points)
            .unwrap();
        assert_eq!(&after_error.counts, expected_counts);
        assert_eq!(&after_error.providers, expected_providers);
        let workers = rayon::ThreadPoolBuilder::new()
            .num_threads(2)
            .build()
            .unwrap();
        for (id, origin) in [(101, 0), (102, -33), (103, 700000)] {
            let separate = workers.install(|| replay_fixture(&mut gpu, id, origin, false));
            let combined = workers.install(|| replay_fixture(&mut gpu, id, origin, true));
            assert!(
                combined.1 > 0,
                "fixture must exercise a mixed count/provider round"
            );
            assert!(!combined.0.iter().all(Vec::is_empty));
            assert_eq!(
                separate, combined,
                "every ordered placement must match at {origin}"
            );
        }
        let shared = Mutex::new(gpu);
        std::thread::scope(|scope| {
            for lane in 0..2 {
                let shared = &shared;
                let references = &references;
                scope.spawn(move || {
                    for step in 0..6 {
                        let (id, noise, queries, programs, points, counts, providers) =
                            &references[(step + lane) % references.len()];
                        let batch = shared
                            .lock()
                            .unwrap()
                            .run_batch(*id, Some(noise), queries, programs, points)
                            .unwrap();
                        assert_eq!(&batch.counts, counts);
                        assert_eq!(&batch.providers, providers);
                    }
                });
            }
        });
    }
}

#[test]
#[ignore = "actual GPU, 1 warmup + 7 alternating sparse sample pairs; use 30s process deadline"]
fn combined_samples_benchmark() {
    let mut gpu = gpu(true);
    for (id, n, m) in [(11, 64, 128), (12, 1024, 4096), (13, 67, 0), (14, 0, 4096)] {
        let (noise, queries, programs, points) = fixture(id, n, m);
        let mut separate_ms = Vec::new();
        let mut combined_ms = Vec::new();
        let mut transfer = None;
        for pair in 0..8 {
            let mut reference = None;
            let mut result = None;
            for step in 0..2 {
                let combined = (step + pair) % 2 == 1;
                let start = Instant::now();
                if combined {
                    let batch = gpu
                        .run_batch(id, Some(&noise), &queries, &programs, &points)
                        .unwrap();
                    let ms = start.elapsed().as_secs_f64() * 1000.0;
                    if pair > 0 {
                        combined_ms.push(ms);
                    }
                    transfer = Some((batch.upload, batch.readback, batch.device_nanos));
                    result = Some((batch.counts, batch.providers));
                } else {
                    let counts = gpu.run(id, &noise, &queries).unwrap();
                    let providers = gpu.run_providers(id, &programs, &points).unwrap();
                    let ms = start.elapsed().as_secs_f64() * 1000.0;
                    if pair > 0 {
                        separate_ms.push(ms);
                    }
                    reference = Some((counts.values, providers.values));
                }
            }
            assert_eq!(reference, result);
        }
        println!(
            "{}",
            serde_json::json!({"counts": n, "providers": m, "separate_ms": separate_ms, "combined_ms": combined_ms, "last_transfer_and_device_nanos": transfer, "all_outputs_equal": true})
        );
    }
}


#[test]
#[ignore = "bounded CPU/GPU numerical comparison; requires a real GPU"]
fn native_cpu_feature_samples_match_the_loaded_gpu_rules() {
    let mut gpu=gpu(false);
    let mut max_error=0.0f32;
    for shift in [1,25,219] {
        let (noise,mut queries,programs,points)=fixture(shift,256,256);
        for far in [false,true] {
            if !far {for (i,q) in queries.iter_mut().enumerate(){q.x=i as i32*37-4096;q.z=4096-i as i32*19;}}
            else {queries=fixture(shift,256,0).1;}
            let cpu=crate::cpu::features::sample(Some(&noise),&queries,&programs,&points).unwrap();
            let actual=gpu.run_batch(shift,Some(&noise),&queries,&programs,&points).unwrap();
            for ((&cpu,&actual),q) in cpu.counts.iter().zip(&actual.counts).zip(&queries) {
                if cpu==actual {continue;}
                // Backend division/reassociation may move a far f32 coordinate
                // by a representable step. At the world border this is spatially
                // meaningful; compare the loaded rule over that numerical interval,
                // rather than permitting arbitrary differences in feature counts.
                let divisor=f32::from_bits(q.rule.words[4]);
                let p=[q.x as f32/divisor,q.z as f32/divisor];
                let mut low=i32::MAX;let mut high=i32::MIN;
                for dx in -2..=2 {for dz in -2..=2 {
                    let at=[f32::from_bits(p[0].to_bits().wrapping_add_signed(dx)),
                            f32::from_bits(p[1].to_bits().wrapping_add_signed(dz))];
                    let count=noise.count_at(q.rule,at);low=low.min(count);high=high.max(count);
                }}
                // A ceil boundary can differ by one after final f32 arithmetic.
                let rounding=i32::from(q.rule.words[0]==1);
                assert!((low-rounding..=high+rounding).contains(&(actual as i32)),
                    "count outside numerical coordinate interval: CPU={cpu} GPU={actual} range={low}..={high} far={far} query={q:?}");
            }
            let differing=cpu.counts.iter().zip(&actual.counts).filter(|(a,b)|a!=b).count();
            let max_count_error=cpu.counts.iter().zip(&actual.counts).map(|(a,b)|a.abs_diff(*b)).max().unwrap();
            let totals=(cpu.counts.iter().map(|&v|v as u64).sum::<u64>(),actual.counts.iter().map(|&v|v as u64).sum::<u64>());
            eprintln!("CPU_COUNT_COMPARISON permutation={shift} far={far} differing={differing} max_error={max_count_error} totals={totals:?}");
            for (cpu,gpu) in cpu.providers.into_iter().zip(actual.providers) {
                let (cpu,gpu)=(f32::from_bits(cpu),f32::from_bits(gpu));
                let error=(cpu-gpu).abs();max_error=max_error.max(error);
                assert!(error<0.0001,"provider CPU {cpu} vs GPU {gpu}, permutation {shift}");
            }
        }
    }
    eprintln!("CPU_FEATURE_SAMPLES_PASS permutations=3 counts=1536 providers=1536 max_provider_error={max_error}");
}
