//! Logical buffer ownership at joined checkpoints, not a residency estimate.
use crate::TerrainEngine;
use serde_json::{Value, json};
use std::sync::{
    Mutex,
    atomic::{AtomicBool, Ordering},
};

#[derive(Default)]
pub(crate) struct Probe {
    pub enabled: AtomicBool,
    pub stopped: AtomicBool,
    pub latest: Mutex<Option<Value>>,
}
#[derive(Default)]
pub(crate) struct Inventory {
    buffers: Vec<Value>,
    bytes: u64,
}
impl Inventory {
    pub fn add(&mut self, name: impl AsRef<str>, buffer: &wgpu::Buffer) {
        self.bytes += buffer.size();
        self.buffers
            .push(json!({"name":name.as_ref(),"bytes":buffer.size(),
            "usage_bits":buffer.usage().bits()}));
    }
    pub fn value(self) -> Value {
        json!({"buffers":self.buffers,"buffer_count":self.buffers.len(),"bytes":self.bytes})
    }
}
pub(crate) fn census(engine: &TerrainEngine) -> Option<Value> {
    if !engine
        .pipeline
        .gpu_buffer_probe
        .enabled
        .load(Ordering::Acquire)
    {
        return None;
    }
    // Callers have joined. The terrain owner published before its final reply;
    // these helper mutexes cover their complete synchronous submissions.
    Some(json!({
        "terrain":engine.pipeline.gpu_buffer_probe.latest.lock().unwrap().clone(),
        "completed_now":engine.pipeline.snapshot().completed,
        "lighting":engine.light_gpu.lock().unwrap().buffer_inventory().value(),
        "ores":engine.ore_gpu.lock().unwrap().buffer_inventory().value(),
        "features":engine.feature_gpu.lock().unwrap().buffer_inventory().value(),
        "vines":engine.vine_gpu.lock().unwrap().buffer_inventory().value(),
    }))
}
pub(crate) fn validate(value: &Value) {
    assert_eq!(value["terrain"]["flights"], 0);
    assert!(value["terrain"]["readback_slots"].as_u64().unwrap() <= 2);
    assert!(value["terrain"]["material_slots"].as_u64().unwrap() <= 2);
    assert_eq!(value["terrain"]["completed"], value["completed_now"]);
    for name in ["terrain", "lighting", "ores", "features", "vines"] {
        let inventory = if name == "terrain" {
            &value[name]["inventory"]
        } else {
            &value[name]
        };
        let buffers = inventory["buffers"].as_array().unwrap();
        assert_eq!(
            inventory["buffer_count"].as_u64().unwrap(),
            buffers.len() as u64
        );
        assert_eq!(
            inventory["bytes"].as_u64().unwrap(),
            buffers
                .iter()
                .map(|b| b["bytes"].as_u64().unwrap())
                .sum::<u64>()
        );
        let names: std::collections::HashSet<_> = buffers
            .iter()
            .map(|b| b["name"].as_str().unwrap())
            .collect();
        assert_eq!(names.len(), buffers.len());
    }
}
pub(crate) fn vm_snapshot(output: &std::path::Path, label: &str) {
    #[cfg(target_os = "macos")]
    {
        // An actual query of this process at a completed-generation checkpoint.
        // Failure is retained as diagnostic data, never a generation gate.
        let result = std::process::Command::new("/usr/bin/vmmap")
            .args(["-summary", &std::process::id().to_string()])
            .output();
        let record = match result {
            Ok(v) => {
                std::fs::write(output.join(format!("{label}-vmmap.txt")), &v.stdout).unwrap();
                json!({"exit_code":v.status.code(),"stderr":String::from_utf8_lossy(&v.stderr)})
            }
            Err(error) => json!({"error":error.to_string()}),
        };
        std::fs::write(
            output.join(format!("{label}-vmmap-status.json")),
            serde_json::to_vec_pretty(&record).unwrap(),
        )
        .unwrap();
    }
    #[cfg(not(target_os = "macos"))]
    let _ = (output, label);
}
