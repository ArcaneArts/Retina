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
