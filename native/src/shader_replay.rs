//! Replay captured compiler/driver inputs without Minecraft, Naga or generation.
use std::path::Path;

#[cfg(any(all(windows, target_arch = "x86_64"), test))]
fn arguments(original: &[String], disable_optimization: bool) -> Vec<String> {
    if !disable_optimization { return original.to_vec(); }
    let mut args: Vec<_> = original.iter().filter(|arg|
        !matches!(arg.as_str(), "-O0" | "-O1" | "-O2" | "-O3" | "-Od" | "/O0" | "/O1" | "/O2" | "/O3" | "/Od"))
        .cloned().collect();
    args.push("-Od".into());
    args
}

/// Modes: driver, dxc, dxc-no-opt, no-opt-driver. Input is a case directory or case.json.
pub fn run(path: &Path, mode: &str) -> Result<(), String> {
    #[cfg(all(windows, target_arch = "x86_64"))]
    { windows::run(path, mode) }
    #[cfg(not(all(windows, target_arch = "x86_64")))]
    { let _ = (path, mode); Err("DX12 replay requires the Windows x64 probe".into()) }
}

#[cfg(all(windows, target_arch = "x86_64"))]
mod windows {
    use super::*;
    use crate::startup::{Stage, capture::{self, Manifest}};
    use std::{fs, time::Instant};
    use wgpu::hal::dx12::diagnostics;

    pub(super) fn run(path: &Path, mode: &str) -> Result<(), String> {
        let (compile, driver, no_opt) = match mode {
            "driver" => (false, true, false),
            "dxc" => (true, false, false),
            "dxc-no-opt" => (true, false, true),
            "no-opt-driver" => (true, true, true),
            _ => return Err(format!("Unknown replay mode {mode:?}")),
        };
        let directory = if path.extension().is_some_and(|e| e == "json") {
            path.parent().ok_or("case.json has no directory")?
        } else { path };
        let manifest = Manifest::read(directory)?;
        if manifest.schema != 1 { return Err(format!("Unsupported capture schema {}", manifest.schema)); }
        let output = directory.join(format!("replay-{mode}-{}-{}", capture::now(), std::process::id()));
        fs::create_dir_all(&output).map_err(|e| e.to_string())?;
        let mut report = serde_json::json!({"mode": mode, "pipeline_id": manifest.pipeline_id,
            "entry": manifest.entry, "captured_adapter": manifest.adapter,
            "state": "starting", "started_unix_ms": capture::now()});
        let save = |report: &serde_json::Value| -> Result<(), String> {
            capture::atomic_write(&output.join("report.json"), &serde_json::to_vec_pretty(report).map_err(|e| e.to_string())?)
        };
        save(&report)?;
        eprintln!("DX12 replay {mode}: {}; output={}", directory.display(), output.display());
        let result = (|| {
            let bytecode = if compile {
                let source = fs::read_to_string(directory.join("shader.hlsl")).map_err(|e| e.to_string())?;
                let original = manifest.arguments.as_ref().ok_or("Capture has no DXC arguments (possibly an in-memory shader cache hit); use driver replay or capture its first compilation")?;
                let args = arguments(original, no_opt);
                report["source_sha256"] = capture::sha256(source.as_bytes()).into();
                report["arguments"] = serde_json::json!(args);
                report["state"] = "dxc-running".into(); save(&report)?;
                let stage = Stage::new(format!("replay DXC entry={} optimization_disabled={no_opt}", manifest.entry));
                let start = Instant::now();
                let bytes = crate::startup::driver::observe(&stage, &manifest.entry, || diagnostics::compile_hlsl(&source, &args))?;
                report["dxc_seconds"] = start.elapsed().as_secs_f64().into();
                drop(stage);
                capture::atomic_write(&output.join("shader.dxil"), &bytes)?;
                bytes
            } else {
                fs::read(directory.join("shader.dxil")).map_err(|e| e.to_string())?
            };
            let hash = capture::sha256(&bytecode);
            report["dxil_bytes"] = bytecode.len().into();
            report["dxil_sha256"] = hash.clone().into();
            report["matches_captured_dxil"] = manifest.files.get("shader.dxil").is_some_and(|f| f.sha256 == hash).into();
            if driver {
                let root = fs::read(directory.join("root-signature.bin")).map_err(|e| e.to_string())?;
                report["root_signature_sha256"] = capture::sha256(&root).into();
                report["state"] = "device-creation".into(); save(&report)?;
                let instance = wgpu::Instance::new(wgpu::InstanceDescriptor {
                    backends: wgpu::Backends::DX12, ..wgpu::InstanceDescriptor::new_without_display_handle()
                });
                let adapter = pollster::block_on(instance.request_adapter(&wgpu::RequestAdapterOptions {
                    power_preference: wgpu::PowerPreference::HighPerformance, ..Default::default()
                })).map_err(|e| e.to_string())?;
                let info = adapter.get_info();
                report["replay_adapter"] = serde_json::json!({"name": info.name, "driver": info.driver, "vendor": info.vendor, "device": info.device});
                eprintln!("Replay adapter: {} driver={}; captured adapter: {} driver={}", info.name, info.driver, manifest.adapter.name, manifest.adapter.driver);
                let (device, _queue) = pollster::block_on(adapter.request_device(&wgpu::DeviceDescriptor::default())).map_err(|e| e.to_string())?;
                report["state"] = "driver-running".into(); save(&report)?;
                let stage = Stage::new(format!("replay DX12 root signature and compute pipeline entry={} dxil_bytes={}", manifest.entry, bytecode.len()));
                let start = Instant::now();
                // The guard owns access for the duration of the synchronous call.
                // Replay creates/drops a pipeline only; no shader is dispatched.
                let hal = unsafe { device.as_hal::<wgpu::hal::api::Dx12>() }.ok_or("DX12 device unavailable")?;
                crate::startup::driver::observe(&stage, &manifest.entry, || diagnostics::create_compute_pipeline(&hal, &bytecode, &root))?;
                report["driver_seconds"] = start.elapsed().as_secs_f64().into();
                drop(stage);
            }
            Ok::<_, String>(())
        })();
        report["state"] = if result.is_ok() { "complete" } else { "failed" }.into();
        if let Err(error) = &result { report["error"] = error.clone().into(); }
        save(&report)?;
        eprintln!("Replay report: {}", output.join("report.json").display());
        result
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    #[test]
    fn optimization_comparison_preserves_entry_target_and_other_arguments() {
        let args: Vec<String> = ["source name", "-E", "surface_columns", "-T", "cs_6_7", "-HV", "2018", "-O3", "-no-warnings", "-Ges"].into_iter().map(str::to_owned).collect();
        assert_eq!(arguments(&args, false), args);
        let changed = arguments(&args, true);
        assert_eq!(changed.last().unwrap(), "-Od");
        assert!(!changed.iter().any(|s| s == "-O3"));
        assert_eq!(&changed[..7], &args[..7]);
        assert!(changed.iter().any(|s| s == "-Ges"));
    }
}
