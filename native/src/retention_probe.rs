//! Joined-region CPU ownership census. No production hooks or allocator guesses.
use crate::*;
use serde_json::{Value, json};
use std::{fs, path::PathBuf};

fn columns(cache: &ColumnCache) -> Value {
    json!({"entries":cache.entries.len(),"order":cache.order.len(),
        "capacity":cache.entries.capacity(),"limit":8192,
        "payload_bytes":cache.entries.len()*std::mem::size_of::<[Column; COLUMNS]>()})
}

fn census(engine: &TerrainEngine) -> Value {
    let caves = engine.caves.lock().unwrap();
    let mut masks = HashMap::<usize, (&Arc<geology::CaveMask>, usize)>::new();
    for mask in caves.entries.values() {
        masks
            .entry(Arc::as_ptr(mask) as usize)
            .or_insert((mask, 0))
            .1 += 1;
    }
    let mut halo_columns = HashMap::<usize, (&Arc<Vec<Column>>, usize)>::new();
    for (mask, _) in masks.values() {
        if let Some(halo) = &mask.columns {
            halo_columns
                .entry(Arc::as_ptr(&halo.columns) as usize)
                .or_insert((&halo.columns, 0))
                .1 += 1;
        }
    }
    let masks_report = json!({
        "cache_keys":caves.entries.len(),"order":caves.order.len(),"key_limit":2048,
        "unique_masks":masks.len(),"unique_halo_column_buffers":halo_columns.len(),
        "word_capacity_bytes":masks.values().map(|(v,_)| v.words.capacity()*4).sum::<usize>(),
        "halo_column_capacity_bytes":halo_columns.values().map(|(v,_)| v.capacity()*std::mem::size_of::<Column>()).sum::<usize>(),
        "external_mask_references":masks.values().map(|(v,known)| Arc::strong_count(v).saturating_sub(*known)).sum::<usize>(),
        "external_halo_references":halo_columns.values().map(|(v,known)| Arc::strong_count(v).saturating_sub(*known)).sum::<usize>(),
    });
    drop(halo_columns);
    drop(masks);
    drop(caves);
    json!({"columns":columns(&engine.cache.lock().unwrap()),
        "heights":columns(&engine.height_cache.lock().unwrap()),
        "queries":engine.query_cache.lock().unwrap().retention_probe(),
        "caves":masks_report,
        "versions":engine.dependency_versions.lock().unwrap().retention_probe(),
        "structures":engine.structures.lock().unwrap().retention_probe(),
        "registered_profiles":engine.profiles.read().unwrap().len(),
        "timing_profiles":engine.timings.lock().unwrap().len()})
}

fn validate(value: &Value) {
    for name in ["columns", "heights", "queries"] {
        let v = &value[name];
        assert!(v["entries"].as_u64().unwrap() <= v["limit"].as_u64().unwrap());
        assert_eq!(v["entries"], v["order"]);
    }
    let v = &value["caves"];
    assert!(v["cache_keys"].as_u64().unwrap() <= v["key_limit"].as_u64().unwrap());
    assert_eq!(v["cache_keys"], v["order"]);
    assert_eq!(v["external_mask_references"], 0);
    assert_eq!(v["external_halo_references"], 0);
    let v = &value["versions"];
    assert_eq!(v["tracked_bytes"], v["recounted_bytes"]);
    assert!(v["tracked_bytes"].as_u64().unwrap() <= v["byte_limit"].as_u64().unwrap());
    let entries = v["entries"].as_array().unwrap();
    assert!(entries.len() <= v["entry_limit"].as_u64().unwrap() as usize);
    for entry in entries {
        assert_eq!(entry["tracked_bytes"], entry["recounted_bytes"]);
        assert_eq!(entry["external_snapshot_references"], 0);
        assert_eq!(entry["external_interior_run_references"], 0);
        if !entry["exterior"].is_null() {
            assert_eq!(entry["exterior"]["external_run_references"], 0);
        }
    }
    let v = &value["structures"];
    let entries = v["entries"].as_u64().unwrap();
    assert!(entries <= v["entry_limit"].as_u64().unwrap());
    assert_eq!(v["entries"], v["order"]);
    assert!(v["metadata_entries"].as_u64().unwrap() <= v["metadata_limit"].as_u64().unwrap());
    assert_eq!(v["metadata_entries"], v["metadata_order"]);
    // The sweep precedes eviction of the just-inserted cell's predecessor,
    // leaving at most one additional dead weak entry at a sweep boundary.
    assert!(
        v["weak_entries"].as_u64().unwrap()
            <= entries + v["insertions_since_sweep"].as_u64().unwrap() + 1
    );
    assert_eq!(v["external_cell_references"], 0);
    assert_eq!(v["external_start_references"], 0);
}

#[test]
#[ignore = "requires GPU, RETINA_TERRAIN_REUSE_PROFILES and RETINA_RETENTION_OUTPUT"]
fn completed_region_retention_census() {
    let profiles = PathBuf::from(std::env::var("RETINA_TERRAIN_REUSE_PROFILES").unwrap());
    let output = PathBuf::from(std::env::var("RETINA_RETENTION_OUTPUT").unwrap());
    fs::create_dir(&output).unwrap();
    let engine = TerrainEngine::new().unwrap();
    let mut cases = Vec::new();
    for (name, file, seed) in [
        ("nether-main", "nether.json", 123456789),
        ("nether-second-seed", "nether.json", 42),
        ("nether-custom-bounds", "custom.json", 123456789),
        ("nether-direct-stem", "direct-stem.json", 42),
        ("overworld", "overworld.json", 123456789),
    ] {
        let bytes = fs::read(profiles.join(file)).unwrap();
        let profile: Value = serde_json::from_slice(&bytes).unwrap();
        let id = engine.register_profile(&bytes).unwrap();
        let bounds = &profile["generation_bounds"];
        cases.push((
            name,
            ChunkRequest {
                seed,
                chunk_x: 0,
                chunk_z: 0,
                reserved: id,
                min_y: bounds["storage_min_y"].as_i64().unwrap_or(-64) as i32,
                height: bounds["storage_height"].as_u64().unwrap_or(384) as u32,
                base_height: 64.,
                amplitude: 48.,
                frequency: 0.008,
            },
            profile["biomes"][0]["id"].as_str().unwrap().to_owned(),
        ));
    }
    let warm = output.join("warm");
    fs::create_dir(&warm).unwrap();
    for (case, request, biome) in &cases {
        for i in 0..5 {
            crate::region::generate_region(
                &engine,
                ChunkRequest {
                    chunk_x: (8 + i) * 32,
                    chunk_z: 8 * 32,
                    ..*request
                },
                &warm.join(format!("{case}-{i}.mca")),
                0,
                biome,
            )
            .unwrap();
        }
    }
    let mut records = Vec::new();
    for round in 0..3 {
        for (case, request, biome) in &cases {
            let destination = output.join(format!("{case}-round{round}"));
            fs::create_dir(&destination).unwrap();
            for step in 0..20 {
                let index = match round {
                    0 => step,
                    1 => 19 - step,
                    _ => (step * 7 + 3) % 20,
                };
                let request = ChunkRequest {
                    chunk_x: (-2 + index % 5) * 32,
                    chunk_z: (-2 + index / 5) * 32,
                    ..*request
                };
                let start = Instant::now();
                let report = crate::region::generate_region(
                    &engine,
                    request,
                    &destination.join(format!("{index}.mca")),
                    0,
                    biome,
                )
                .unwrap();
                let elapsed = start.elapsed().as_secs_f64() * 1000.;
                assert_eq!(report.generated, 1024);
                assert_eq!(report.preserved, 0);
                records.push(json!({"case":case,"round":round,"index":index,
                    "diagnostic_generation_ms":elapsed,"program":engine.pipeline.program_snapshot(request.reserved),
                    "retention":census(&engine)}));
            }
            fs::write(output.join("report.json"),serde_json::to_vec_pretty(&json!({
                "records":records,"complete":false,"timing_acceptance":false,
                "scope":"One native engine, all five corpus cases and three request orders. Cache ownership is inspected only after generation returns. CPU payload capacities exclude hash buckets, allocator overhead and GPU/driver memory. Counts do not equate to RSS; no runtime scheduling, eviction or cleanup policy is changed."
            })).unwrap()).unwrap();
            println!("RETENTION {case} round {round} complete");
        }
    }
    let release_versions = engine.dependency_versions.lock().unwrap().release_probe();
    let weak_masks: Vec<_> = engine
        .caves
        .lock()
        .unwrap()
        .entries
        .values()
        .map(Arc::downgrade)
        .collect();
    drop(engine);
    let released_versions = release_versions();
    let released_masks = json!({"observed_mask_references":weak_masks.len(),
        "live_mask_references":weak_masks.iter().filter(|v| v.strong_count()!=0).count()});
    let report_path = output.join("report.json");
    let mut report: Value = serde_json::from_slice(&fs::read(&report_path).unwrap()).unwrap();
    report["after_engine_drop"] = json!({"versions":released_versions,"caves":released_masks});
    fs::write(&report_path, serde_json::to_vec_pretty(&report).unwrap()).unwrap();
    // Validate only after the real corpus finishes; retain all observations if
    // an ownership expectation fails so a reader can investigate the actual owner.
    for record in &records {
        validate(&record["retention"]);
    }
    assert_eq!(released_versions["live_snapshots"], 0);
    assert_eq!(released_versions["live_interior_run_buffers"], 0);
    assert_eq!(released_masks["live_mask_references"], 0);
    report["complete"] = true.into();
    fs::write(&report_path, serde_json::to_vec_pretty(&report).unwrap()).unwrap();
}
