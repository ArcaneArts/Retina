//! Small complete planning/application comparison. No region writing or lighting.
use super::*;
use crate::{ChunkRequest, TerrainEngine, decoration};
use rayon::prelude::*;

#[test]
#[ignore = "actual GPU ore A/B; RETINA_ORE_PROFILE required; use 30s process-group deadline"]
fn seeded_ore_planning_comparison() {
    let path = std::env::var("RETINA_ORE_PROFILE").unwrap();
    let engine = TerrainEngine::new().unwrap();
    let reserved = engine
        .register_reference_profile(&std::fs::read(&path).unwrap())
        .unwrap();
    let profile = engine.profile(reserved).unwrap().unwrap();
    let workers = rayon::ThreadPoolBuilder::new()
        .num_threads(2)
        .build()
        .unwrap();
    for (side, cx, cz) in [(4usize, 0, 0), (8, -33, -33)] {
        let request = ChunkRequest {
            seed: 123456789,
            chunk_x: cx,
            chunk_z: cz,
            min_y: -64,
            height: 384,
            base_height: 64.,
            amplitude: 48.,
            frequency: 0.008,
            reserved,
        };
        let (field, mask) = engine.terrain_field(request, side as u32).unwrap();
        let chunk = |target: usize| ChunkRequest {
            chunk_x: cx + (target % side) as i32,
            chunk_z: cz + (target / side) as i32,
            ..request
        };
        let base: Vec<Vec<u16>> = workers.install(|| {
            (0..side * side)
                .into_par_iter()
                .map(|target| {
                    let r = chunk(target);
                    let mut blocks = vec![0; r.block_count()];
                    decoration::assemble_carved(
                        r,
                        field.chunk(r.chunk_x, r.chunk_z),
                        Some(&profile),
                        mask.as_deref(),
                        &mut blocks,
                    );
                    blocks
                })
                .collect()
        });
        let mut samples = [Vec::new(), Vec::new()];
        for pair in 0..9 {
            let mut outputs = [None, None];
            for step in 0..2 {
                let mode = (pair + step) % 2;
                let mut blocks = base.clone();
                let start = Instant::now();
                let batch = workers.install(|| {
                    if mode == 0 {
                        Batch::prepare_compact(&field, &profile, request, side, mask.as_deref())
                    } else {
                        Batch::prepare_seeded(&field, &profile, request, side, mask.as_deref())
                    }
                });
                let prepared = Instant::now();
                let mut gpu = engine.gpu().unwrap().ore_gpu.lock().unwrap();
                let acquired = Instant::now();
                let result = gpu.run(&batch).unwrap();
                let retained_gpu_bytes = gpu
                    .buffers
                    .as_ref()
                    .map_or(0, |b| b.sizes[0] + b.sizes[1] + b.sizes[2] * 2)
                    + if gpu.seeded.is_some() { 65 * 64 * 8 } else { 0 };
                drop(gpu);
                let readback = Instant::now();
                workers.install(|| {
                    blocks
                        .par_iter_mut()
                        .enumerate()
                        .for_each(|(target, blocks)| {
                            batch.apply_compact(
                                &result.words,
                                &field,
                                &profile,
                                mask.as_deref(),
                                target,
                                blocks,
                            );
                        })
                });
                let done = Instant::now();
                let (repairs, repair_bytes) = batch.repair_statistics();
                assert!(!batch.descriptors.is_empty(), "empty ore fixture");
                if mode == 1 {
                    assert_eq!(batch.descriptors.len(), batch.seeds.len());
                    for d in &batch.descriptors {
                        let at = d.dimensions[3] as usize;
                        let words = &result.words[at..at + 8];
                        assert!(
                            (1..=d.source[1])
                                .contains(&(words[3].count_ones() + words[7].count_ones()))
                        );
                        for k in 0..3 {
                            assert!(words[k] as i32 >= d.minimum[k]);
                            assert!(words[4 + k] > 0);
                            assert!(
                                (words[k] as i32 as i64) + words[4 + k] as i64
                                    <= d.minimum[k] as i64 + d.dimensions[k] as i64
                            );
                        }
                    }
                    if pair == 0 {
                        batch.check_seeded_headers(&result.words, &profile);
                    }
                }
                if pair >= 2 {
                    samples[mode].push(serde_json::json!({
                        "total_ms":(done-start).as_secs_f64()*1000.,
                        "preparation":batch.preparation,
                        "prepare_ms":(prepared-start).as_secs_f64()*1000.,
                        "lock_wait_ms":(acquired-prepared).as_secs_f64()*1000.,
                        "host":result.host,
                        "gpu_host_ms":(readback-acquired).as_secs_f64()*1000.,
                        "apply_ms":(done-readback).as_secs_f64()*1000.,
                        "device_nanos":result.device_nanos,
                        "veins":batch.descriptors.len(),"cpu_spheres":batch.spheres.len(),
                        "upload_bytes":batch.descriptors.len()*48+batch.spheres.len()*16+batch.seeds.len()*48,
                        "readback_bytes":result.readback_bytes,"retained_gpu_bytes":retained_gpu_bytes,
                        "repair_veins":repairs,"repair_capacity_bytes":repair_bytes
                    }));
                }
                outputs[mode] = Some(blocks);
            }
            assert_eq!(
                outputs[0], outputs[1],
                "seed preparation changed final ore blocks at {cx},{cz}"
            );
            if pair == 0 {
                let plan = engine
                    .plan_ores(&field, &profile, request, side, mask.as_deref(), None)
                    .unwrap();
                let crate::geology::RegionPlan::Gpu { batch, .. } = &plan else {
                    panic!("normal region path did not use GPU ores");
                };
                assert!(
                    !batch.seeds.is_empty(),
                    "normal region path did not use seeds"
                );
                let mut normal = base.clone();
                workers.install(|| {
                    normal
                        .par_iter_mut()
                        .enumerate()
                        .for_each(|(target, blocks)| {
                            plan.apply(request, &field, &profile, mask.as_deref(), target, blocks);
                        })
                });
                assert_eq!(normal, outputs[0].as_ref().unwrap().as_slice());
            }
        }
        println!(
            "{}",
            serde_json::json!({"profile":path,"backend":engine.backend(),"side":side,"chunk_x":cx,"chunk_z":cz,"height":384,"workers":2,"warmups":2,"pairs":7,"outputs_equal":true,"baseline":samples[0],"seeded":samples[1]})
        );
    }
}
