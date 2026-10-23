use super::*;

fn key() -> Key {
    Key {
        engine: 11,
        profile: 23,
        roots: 41,
        before: 8,
        request: crate::CacheKey {
            seed: 42,
            x: -17,
            z: 32,
            min_y: 0,
            height: 8,
            base: 1,
            amplitude: 2,
            frequency: 3,
            profile: 4,
        },
    }
}
fn column(runs: &[u32]) -> Arc<ColumnState> {
    let (profile, _, _) = crate::decoration::replay_tests::fixture(42, 0);
    Arc::new(ColumnState::from_runs(
        ColumnRuns::Owned(runs.into()),
        8,
        &profile,
        0,
    ))
}

#[test]
fn column_census_separates_same_other_pending_live_dead_and_failure_without_pinning() {
    let session = Arc::new(Session::default());
    let a = session.begin(key(), 0, false).unwrap();
    let b = session.begin(key(), 1, false).unwrap();
    let value = column(&[1]);
    a.complete(&value);
    assert_eq!(Arc::strong_count(&value), 1);
    let same = session.begin(key(), 0, false).unwrap();
    let other = session.begin(key(), 2, false).unwrap();
    b.complete(&column(&[1]));
    same.complete(&column(&[1]));
    other.complete(&column(&[1]));
    assert_eq!(Arc::strong_count(&value), 1);
    assert!(Arc::try_unwrap(value).is_ok());
    session
        .begin(key(), 3, false)
        .unwrap()
        .complete(&column(&[1]));
    drop(session.begin(key(), 4, false));
    let r = session.report();
    let c = &r["counts"];
    assert_eq!(c["sampled_initializers"], 6);
    assert_eq!(c["successes"], 5);
    assert_eq!(c["abandoned_or_failed"], 1);
    assert_eq!(c["same_live_at_demand"], 1);
    assert_eq!(c["other_live_at_demand"], 1);
    assert_eq!(c["other_pending_at_demand"], 3);
    assert_eq!(c["historical_dead_at_demand"], 2);
    assert_eq!(c["compared_columns"], 4);
    assert_eq!(c["compared_live_other"], 1);
    assert_eq!(c["voxel_mismatches"], 0);
    assert_eq!(r["pending_builds"], 0);
    assert_eq!(r["live_handles_at_wave_end"], 0);
}

#[test]
fn column_census_compares_every_voxel_and_height_without_requiring_identical_encoding() {
    let session = Arc::new(Session::default());
    session
        .begin(key(), 0, true)
        .unwrap()
        .complete(&column(&[1]));
    session
        .begin(key(), 1, true)
        .unwrap()
        .complete(&column(&[(4 << 16) | 1, 1]));
    let different = column(&[(4 << 16) | 2, 1]);
    session.begin(key(), 2, true).unwrap().complete(&different);
    let mut wrong_height = column(&[1]);
    Arc::get_mut(&mut wrong_height).unwrap().heights[5] += 1;
    session
        .begin(key(), 3, true)
        .unwrap()
        .complete(&wrong_height);
    let r = session.report();
    let c = &r["counts"];
    assert_eq!(c["compared_columns"], 3);
    assert_eq!(c["run_encoding_differences"], 2);
    assert_eq!(c["voxel_mismatches"], 1);
    assert!(c["height_mismatches"].as_u64().unwrap() >= 1);
    for runs in [
        vec![],
        vec![1 << 16 | 1],
        vec![8 << 16 | 1, 1],
        vec![1, 4 << 16 | 1],
    ] {
        assert!(!Snapshot::valid(&runs, 8));
    }
}

#[test]
fn column_census_keys_sampling_and_limits_preserve_scope_and_report_omissions() {
    let k = key();
    let r = k.request;
    for request in [
        crate::CacheKey {
            seed: r.seed + 1,
            ..r
        },
        crate::CacheKey { x: r.x + 1, ..r },
        crate::CacheKey { z: r.z + 1, ..r },
        crate::CacheKey { min_y: 1, ..r },
        crate::CacheKey { height: 9, ..r },
        crate::CacheKey { base: 2, ..r },
        crate::CacheKey { amplitude: 3, ..r },
        crate::CacheKey { frequency: 4, ..r },
        crate::CacheKey { profile: 5, ..r },
    ] {
        assert!(Key { request, ..k } != k);
    }
    for different in [
        Key { engine: 12, ..k },
        Key { profile: 24, ..k },
        Key { roots: 42, ..k },
        Key { before: 9, ..k },
    ] {
        assert!(different != k);
    }
    let mut chosen = 0;
    for before in 0..42 {
        for x in -128..128 {
            for z in -16..16 {
                let a = selected(before, x, z);
                assert_eq!(a, selected(before, x, z));
                chosen += a as usize;
            }
        }
    }
    // Broad sanity bounds, not a promised exact sample weight.
    assert!((4000..7000).contains(&chosen));
    for x in [i32::MIN, -1, 0, i32::MAX] {
        let _ = selected(usize::MAX, x, x);
    }
    let no_keys = Arc::new(Session::new(0, 1024));
    assert!(no_keys.begin(k, 0, true).is_none());
    assert_eq!(no_keys.report()["counts"]["skipped_keys"], 1);
    let no_copies = Arc::new(Session::new(1, 0));
    no_copies.begin(k, 0, true).unwrap().complete(&column(&[1]));
    assert_eq!(no_copies.report()["counts"]["skipped_snapshots"], 1);
    assert_eq!(no_copies.report()["snapshot_logical_bytes"], 0);
}

#[test]
fn column_census_parallel_same_key_publication_balances_without_owning_values() {
    let session = Arc::new(Session::default());
    let barrier = std::sync::Barrier::new(8);
    std::thread::scope(|scope| {
        for job in 0..8 {
            let session = &session;
            let barrier = &barrier;
            scope.spawn(move || {
                let b = session.begin(key(), job, false).unwrap();
                barrier.wait();
                let value = column(&[1]);
                b.complete(&value);
                assert_eq!(Arc::strong_count(&value), 1);
                barrier.wait();
            });
        }
    });
    let r = session.report();
    assert_eq!(r["counts"]["sampled_initializers"], 8);
    assert_eq!(r["counts"]["compared_columns"], 7);
    assert_eq!(r["counts"]["other_pending_at_demand"], 7);
    assert_eq!(r["counts"]["voxel_mismatches"], 0);
    assert_eq!(r["pending_builds"], 0);
    assert_eq!(r["live_handles_at_wave_end"], 0);
}

#[test]
fn column_census_separates_existing_archive_hits_from_actual_prefix_and_substrate_paths() {
    let session = Arc::new(Session::default());
    let value = column(&[1]);
    session.begin(key(), 0, true).unwrap().complete(&value);
    for (job, source) in [
        (1, Source::Archive),
        (2, Source::Prefix),
        (3, Source::Substrate),
    ] {
        let mut b = session.begin(key(), job, false).unwrap();
        b.source(source);
        b.complete(&column(&[1]));
    }
    let r = session.report();
    let c = &r["counts"];
    for name in [
        "prefix_successes",
        "substrate_successes",
        "archive_successes",
        "unclassified_successes",
        "live_other_prefix",
        "live_other_substrate",
        "live_other_archive",
    ] {
        assert_eq!(c[name], 1, "{name}");
    }
    assert_eq!(c["other_live_at_demand"], 3);
    assert_eq!(Arc::strong_count(&value), 1);
}

#[test]
#[ignore = "GPU inventory requires RETINA_TERRAIN_REUSE_PROFILES and RETINA_COLUMN_REUSE_OUTPUT"]
fn exact_live_column_reuse_inventory() {
    use std::{
        fs,
        path::PathBuf,
        time::{Duration, Instant},
    };
    let profiles = PathBuf::from(std::env::var("RETINA_TERRAIN_REUSE_PROFILES").unwrap());
    let output = PathBuf::from(std::env::var("RETINA_COLUMN_REUSE_OUTPUT").unwrap());
    fs::create_dir(&output).unwrap();
    let only_case = std::env::var("RETINA_COLUMN_REUSE_CASE").ok();
    let only_callers = std::env::var("RETINA_COLUMN_REUSE_CALLERS")
        .ok()
        .map(|v| v.parse::<usize>().unwrap());
    let mut cases = Vec::new();
    for (name, file, seed) in [
        ("nether-main", "nether.json", 123456789),
        ("nether-second-seed", "nether.json", 42),
        ("nether-custom-bounds", "custom.json", 123456789),
        ("nether-direct-stem", "direct-stem.json", 42),
        ("overworld", "overworld.json", 123456789),
    ] {
        if only_case.as_ref().is_some_and(|v| v != name) {
            continue;
        }
        let bytes = fs::read(profiles.join(file)).unwrap();
        let profile: Value = serde_json::from_slice(&bytes).unwrap();
        for callers in [1, 2, 4, 8] {
            if only_callers.is_some_and(|v| v != callers) {
                continue;
            }
            // Fresh engine and five warmups per level: no preceding mode's
            // completed-region cache can become this mode's starting state.
            let engine = TerrainEngine::new().unwrap();
            let id = engine.register_profile(&bytes).unwrap();
            let bounds = &profile["generation_bounds"];
            let request = ChunkRequest {
                seed,
                chunk_x: 0,
                chunk_z: 0,
                min_y: bounds["storage_min_y"].as_i64().unwrap_or(-64) as i32,
                height: bounds["storage_height"].as_u64().unwrap_or(384) as u32,
                base_height: 64.,
                amplitude: 48.,
                frequency: 0.008,
                reserved: id,
            };
            let biome = profile["biomes"][0]["id"].as_str().unwrap();
            let directory = output.join(format!("{name}-callers{callers}"));
            fs::create_dir(&directory).unwrap();
            let generate = |label: &str, x: i32, z: i32| {
                let before = engine.pipeline.program_snapshot(id);
                let started = Instant::now();
                let result = crate::region::generate_region(
                    &engine,
                    ChunkRequest {
                        chunk_x: x * 32,
                        chunk_z: z * 32,
                        ..request
                    },
                    &directory.join(format!("{label}.mca")),
                    0,
                    biome,
                )
                .unwrap();
                assert_eq!(result.generated, 1024);
                assert_eq!(result.preserved, 0);
                json!({"name":label,"x":x,"z":z,"instrumented_ms":started.elapsed().as_secs_f64()*1000.,
                    "program_before":before,"program_after":engine.pipeline.program_snapshot(id)})
            };
            let warmups: Vec<_> = (0..5)
                .map(|i| generate(&format!("warm{i}"), 8 + i, 8))
                .collect();
            let readiness_started = Instant::now();
            while engine.pipeline.program_snapshot(id).status != 4 {
                assert!(
                    readiness_started.elapsed() < Duration::from_secs(180),
                    "actual program snapshot: {:?}",
                    engine.pipeline.program_snapshot(id)
                );
                std::thread::sleep(Duration::from_millis(25));
            }
            let readiness_wait_ms = readiness_started.elapsed().as_secs_f64() * 1000.;
            let mut waves = Vec::new();
            // Serial pairs expose dead predecessor results. All modes use the
            // same reverse coordinate order; concurrent waves join before reset.
            let width = callers.max(2);
            for start in (0..20usize).step_by(width) {
                let installed = Installed::start();
                let ids: Vec<_> = (start..(start + width).min(20)).map(|i| 19 - i).collect();
                let rows = if callers == 1 {
                    ids.iter()
                        .map(|&i| {
                            generate(&i.to_string(), -2 + (i % 5) as i32, -2 + (i / 5) as i32)
                        })
                        .collect::<Vec<_>>()
                } else {
                    let barrier = std::sync::Barrier::new(ids.len());
                    std::thread::scope(|scope| {
                        let handles: Vec<_> = ids
                            .iter()
                            .map(|&i| {
                                let barrier = &barrier;
                                let generate = &generate;
                                scope.spawn(move || {
                                    barrier.wait();
                                    generate(
                                        &i.to_string(),
                                        -2 + (i % 5) as i32,
                                        -2 + (i / 5) as i32,
                                    )
                                })
                            })
                            .collect();
                        handles
                            .into_iter()
                            .map(|h| h.join().unwrap())
                            .collect::<Vec<_>>()
                    })
                };
                let inventory = installed.0.report();
                let record = json!({"rows":rows,"inventory":inventory});
                fs::write(
                    directory.join(format!("wave-{start}.json")),
                    serde_json::to_vec_pretty(&record).unwrap(),
                )
                .unwrap();
                assert_eq!(inventory["pending_builds"], 0);
                assert_eq!(inventory["live_handles_at_wave_end"], 0);
                for field in [
                    "abandoned_or_failed",
                    "voxel_mismatches",
                    "height_mismatches",
                    "invalid_runs",
                    "unclassified_successes",
                ] {
                    assert_eq!(
                        inventory["counts"][field], 0,
                        "{name} callers={callers} {field}"
                    );
                }
                let c = &inventory["counts"];
                assert_eq!(
                    c["successes"].as_u64().unwrap(),
                    c["prefix_successes"].as_u64().unwrap()
                        + c["substrate_successes"].as_u64().unwrap()
                        + c["archive_successes"].as_u64().unwrap()
                );
                for row in &rows {
                    assert_eq!(row["program_before"]["status"], 4);
                    assert_eq!(row["program_after"]["status"], 4);
                }
                drop(installed);
                waves.push(record);
            }
            drop(engine);
            cases.push(
                json!({"case":name,"profile":file,"seed":seed,"callers":callers,
                "warmups":warmups,"readiness_wait_ms":readiness_wait_ms,"waves":waves}),
            );
            fs::write(output.join("report.json"),serde_json::to_vec_pretty(&json!({"timing_acceptance":false,
                "scope":"Test-only deterministic 1/64 sampling of exact complete column initializer keys. Engine/profile object identities, full CacheKey settings, root count, before index and block X/Z isolate contexts. Limited within-root prefixes are not separately admitted. Original evaluation always supplies generation. Weak references never upgrade or pin values; strong_count is sampled under an observer shard lock and is not a guarantee a future upgrade would succeed. Complete copied material runs and six heights compare every successful repeated key; equivalent noncanonical run encodings are separately reported. No errors, waits, values or RNG are shared. Evidence resets each <=8-region joined wave, with serial pairs. Copies and keys have explicit per-shard caps; skipped evidence is reported, not extrapolated. Logical copy bytes exclude allocation/map/weak-control-block overhead. Diagnostic locks, copies and weak references perturb scheduling and memory; observed opportunities are not normal hit rates, unique saved subtrees, allocations or speedup estimates. Fresh engine and five warmups per case/request count, twenty measured full lit compressed published regions, fixed reverse order and actual shader readiness. No timing acceptance.","cases":cases})).unwrap()).unwrap();
            println!("live column inventory {name} callers={callers}: twenty regions complete");
        }
    }
    assert!(
        !cases.is_empty(),
        "case/caller selection matched no workload"
    );
}
