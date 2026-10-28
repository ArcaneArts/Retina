//! Explicit GPU lifecycle diagnostic; ordinary test/build runs do not launch it.
use super::{WorldDefinition, catalog::WorldCatalog, runtime::{WorldSession, ProgramExecution}};
use crate::{TerrainEngine, region::codec::Compression};
use std::{path::PathBuf, sync::{Arc, atomic::Ordering}};
use serde_json::json;

#[test]
#[ignore = "requires an idle GPU and RETINA_RETIREMENT_OUTPUT"]
fn closed_world_resource_inventory() {
    let output = PathBuf::from(std::env::var_os("RETINA_RETIREMENT_OUTPUT").expect("output directory"));
    std::fs::create_dir_all(&output).unwrap();
    let catalog = WorldCatalog::read("../assets/world-definitions/26.3/vanilla/retina-catalog.dat").unwrap();
    let bytes = catalog.materialize("bukkit-api", 42).unwrap();
    drop(catalog);
    let engine = Arc::new(TerrainEngine::new().unwrap());
    engine.pipeline.gpu_buffer_probe.enabled.store(true, Ordering::Release);
    let mut rows = Vec::new();
    let mut previous_id = 0;
    for cycle in 0..12 {
        let definition = Arc::new(WorldDefinition::from_gzip(bytes.as_slice()).unwrap());
        let session = WorldSession::bind_with_execution(engine.clone(), definition,
            Compression::Zlib, Some(ProgramExecution::Interpreter)).unwrap();
        let lease = session.dimension("minecraft:overworld").unwrap();
        let id = lease.profile_id();
        assert!(id > previous_id, "reopening must not alias a stale profile ID");
        previous_id = id;
        let request = lease.request(0, 0).unwrap();
        let before = engine.sample_columns(&[request]).unwrap();
        drop(session);
        assert!(engine.profile(id).unwrap().is_some(), "dimension lease owns the world");
        let after = engine.sample_columns(&[request]).unwrap();
        assert_eq!(bytemuck::cast_slice::<_, u8>(&before), bytemuck::cast_slice::<_, u8>(&after));
        drop(lease);
        assert!(engine.profile(id).is_err(), "closed world must reject a stale profile");
        assert_eq!(engine.profiles.read().unwrap().len(), 0, "closed registrations must retire");
        assert_eq!(engine.timings.lock().unwrap().len(), 0, "closed timings must retire");
        engine.timing_snapshot(id);
        engine.performance_snapshot(id);
        engine.pipeline.program_snapshot(id);
        assert_eq!(engine.timings.lock().unwrap().len(), 0, "diagnostics must not resurrect a profile");
        let graphics = crate::gpu_buffer_probe::census(&engine).unwrap();
        crate::gpu_buffer_probe::validate(&graphics);
        let rss = std::process::Command::new("ps").args(["-o", "rss=", "-p", &std::process::id().to_string()])
            .output().ok().and_then(|r| String::from_utf8(r.stdout).ok())
            .and_then(|r| r.trim().parse::<u64>().ok());
        rows.push(json!({"cycle":cycle,"closed_profile":id,
            "registered_slots":engine.profiles.read().unwrap().len(),
            "timing_profiles":engine.timings.lock().unwrap().len(),"rss_kib":rss,
            "graphics":graphics}));
        std::fs::write(output.join("inventory.json"), serde_json::to_vec_pretty(&json!({
            "backend":engine.backend(),"cycles":rows})).unwrap()).unwrap();
    }
    crate::gpu_buffer_probe::vm_snapshot(&output, "after-world-close");
}


#[test]
#[ignore = "requires an idle GPU"]
fn retirement_preserves_other_worlds_and_cleans_partial_binding() {
    let catalog = WorldCatalog::read("../assets/world-definitions/26.3/vanilla/retina-catalog.dat").unwrap();
    let definition = |seed| Arc::new(WorldDefinition::from_gzip(
        catalog.materialize("bukkit-api", seed).unwrap().as_slice()).unwrap());
    let first_definition = definition(42);
    let second_definition = definition(43);
    let engine = Arc::new(TerrainEngine::new().unwrap());
    let bind = |definition| WorldSession::bind_with_execution(engine.clone(), definition,
        Compression::Zlib, Some(ProgramExecution::Interpreter)).unwrap();
    let first = bind(first_definition.clone());
    let second = bind(second_definition);
    let survivor = second.dimension("minecraft:overworld").unwrap();
    let survivor_request = survivor.request(0, 0).unwrap();
    let expected = engine.sample_columns(&[survivor_request]).unwrap();
    let lease = first.dimension("minecraft:overworld").unwrap();
    let retired_id = lease.profile_id();
    let (started, start) = std::sync::mpsc::channel();
    let (release, released) = std::sync::mpsc::channel();
    let worker = std::thread::spawn(move || {
        started.send(()).unwrap();
        released.recv().unwrap();
        // The host has closed its owner while this admitted operation retains its lease.
        let request = lease.request(32, -33).unwrap();
        lease.engine().sample_columns(&[request]).unwrap()
    });
    start.recv().unwrap();
    drop(first);
    assert!(engine.profile(retired_id).unwrap().is_some());
    release.send(()).unwrap();
    assert_eq!(worker.join().unwrap().len(), 256);
    assert!(engine.profile(retired_id).is_err());
    assert_eq!(engine.profiles.read().unwrap().len(), 3);
    let actual = engine.sample_columns(&[survivor_request]).unwrap();
    assert_eq!(bytemuck::cast_slice::<_, u8>(&expected), bytemuck::cast_slice::<_, u8>(&actual));
    drop(survivor);
    drop(second);
    assert_eq!(engine.profiles.read().unwrap().len(), 0);
    // Keeping the decoded definition alive must not retain session registrations.
    assert!(first_definition.dimension("minecraft:overworld").is_ok());
    engine.profiles.write().unwrap().leave_one_handle_for_failure_test();
    let result = WorldSession::bind_with_execution(engine.clone(), first_definition,
        Compression::Zlib, Some(ProgramExecution::Interpreter));
    assert!(matches!(result, Err(ref error) if error.contains("handle space exhausted")));
    assert_eq!(engine.profiles.read().unwrap().len(), 0, "first registration must roll back when the second fails");
    assert!(engine.profile(u32::MAX).is_err());
}


#[test]
#[ignore = "requires an idle GPU and RETINA_RETIREMENT_OUTPUT"]
fn complete_chunks_survive_world_reopening() {
    let output = PathBuf::from(std::env::var_os("RETINA_RETIREMENT_OUTPUT").expect("output directory"));
    std::fs::create_dir_all(&output).unwrap();
    let catalog = WorldCatalog::read("../assets/world-definitions/26.3/vanilla/retina-catalog.dat").unwrap();
    let definition = Arc::new(WorldDefinition::from_gzip(catalog.materialize("bukkit-api", 42).unwrap().as_slice()).unwrap());
    drop(catalog);
    let engine = Arc::new(TerrainEngine::new().unwrap());
    engine.pipeline.gpu_buffer_probe.enabled.store(true, Ordering::Release);
    let mut reference = std::collections::BTreeMap::new();
    let mut records = Vec::new();
    for (cycle, mode) in [ProgramExecution::Interpreter, ProgramExecution::Auto, ProgramExecution::Specialized].into_iter().enumerate() {
        let session = WorldSession::bind_with_execution(engine.clone(), definition.clone(), Compression::Zlib, Some(mode)).unwrap();
        for name in ["overworld", "the_nether", "the_end"] {
            let lease = session.dimension(&format!("minecraft:{name}")).unwrap();
            for (x, z) in [(0, 0), (33, -33)] {
                let start = std::time::Instant::now();
                let nbt = lease.generate_chunk(x, z).unwrap();
                let key = format!("{name}-{x}-{z}.nbt");
                let elapsed = start.elapsed().as_secs_f64();
                std::fs::write(output.join(format!("{cycle}-{key}")), &nbt).unwrap();
                if let Some(expected) = reference.get(&key) {
                    assert_eq!(&nbt, expected, "complete chunk changed after reopening: {key}, cycle {cycle}");
                } else { reference.insert(key.clone(), nbt); }
                records.push(json!({"cycle":cycle,"chunk":key,"elapsed_seconds":elapsed}));
            }
        }
        let before = crate::gpu_buffer_probe::census(&engine).unwrap();
        drop(session);
        let after = crate::gpu_buffer_probe::census(&engine).unwrap();
        crate::gpu_buffer_probe::validate(&after);
        for (name, count) in after["terrain"]["world_owners"].as_object().unwrap() {
            assert_eq!(count, 0, "retained {name}");
        }
        for name in ["shared_compiler", "shared_interpreters"] {
            assert!(after["terrain"][name]["warm"].as_u64().unwrap() <= 8);
        }
        for name in ["terrain", "lighting", "features"] {
            let inventory = if name == "terrain" { &after[name]["inventory"] } else { &after[name] };
            for buffer in inventory["buffers"].as_array().unwrap() {
                let key = buffer["name"].as_str().unwrap();
                assert!(!key.starts_with("provider/") && !key.starts_with("cave_profile/")
                    && (!key.starts_with("profile/") || key.starts_with("profile/0/")), "retained {name}/{key}");
            }
        }
        assert_eq!(engine.profiles.read().unwrap().len(), 0);
        assert_eq!(engine.timings.lock().unwrap().len(), 0);
        assert!(engine.cache.lock().unwrap().entries.is_empty());
        assert!(engine.height_cache.lock().unwrap().entries.is_empty());
        assert!(engine.caves.lock().unwrap().entries.is_empty());
        std::fs::write(output.join(format!("resources-{cycle}.json")), serde_json::to_vec_pretty(&json!({"before":before,"after":after})).unwrap()).unwrap();
        std::fs::write(output.join("chunks.json"), serde_json::to_vec_pretty(&records).unwrap()).unwrap();
    }
}

#[test]
#[ignore = "requires an idle GPU and RETINA_RETIREMENT_OUTPUT"]
fn complete_regions_release_auxiliary_resources() {
    let output = PathBuf::from(std::env::var_os("RETINA_RETIREMENT_OUTPUT").expect("output directory"));
    std::fs::create_dir_all(&output).unwrap();
    let catalog = WorldCatalog::read("../assets/world-definitions/26.3/vanilla/retina-catalog.dat").unwrap();
    let definition = Arc::new(WorldDefinition::from_gzip(catalog.materialize("bukkit-api", 42).unwrap().as_slice()).unwrap());
    drop(catalog);
    let engine = Arc::new(TerrainEngine::new().unwrap());
    engine.pipeline.gpu_buffer_probe.enabled.store(true, Ordering::Release);
    for cycle in 0..3 {
        let session = WorldSession::bind_with_execution(engine.clone(), definition.clone(), Compression::Zlib, Some(ProgramExecution::Auto)).unwrap();
        for name in ["overworld", "the_nether", "the_end"] {
            let lease = session.dimension(&format!("minecraft:{name}")).unwrap();
            let report = lease.generate_region(0, 0, &output.join(format!("{cycle}-{name}.mca"))).unwrap();
            assert_eq!(report.generated, 1024);
            eprintln!("retirement region cycle {cycle}: {name} completed");
        }
        let before = crate::gpu_buffer_probe::census(&engine).unwrap();
        assert!(before["lighting"]["buffers"].as_array().unwrap().iter().any(|v| v["name"].as_str().unwrap().starts_with("profile/")), "must exercise resident lighting");
        drop(session);
        let after = crate::gpu_buffer_probe::census(&engine).unwrap();
        crate::gpu_buffer_probe::validate(&after);
        for kind in ["lighting", "features"] {
            for buffer in after[kind]["buffers"].as_array().unwrap() {
                let name = buffer["name"].as_str().unwrap();
                assert!(!name.starts_with("profile/") && !name.starts_with("provider/"), "retained {kind}/{name}");
            }
        }
        assert_eq!(engine.profiles.read().unwrap().len(), 0);
        assert_eq!(engine.timings.lock().unwrap().len(), 0);
        assert!(engine.dependency_versions.lock().unwrap().retention_probe()["entries"].as_array().unwrap().is_empty());
        assert_eq!(engine.structures.lock().unwrap().retention_probe()["entries"], 0);
        std::fs::write(output.join(format!("resources-{cycle}.json")), serde_json::to_vec_pretty(&json!({"before":before,"after":after})).unwrap()).unwrap();
    }
    crate::gpu_buffer_probe::vm_snapshot(&output, "after-regions-close");
}
