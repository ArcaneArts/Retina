//! GPU ownership stays separate from the engine's saved-world and CPU state.
//! Constructing an engine without these resources performs no graphics work.
use crate::{GpuCommand, decoration, geology, gpu_worker, lighting, pipeline};
use std::sync::{Arc, Mutex, mpsc};

#[derive(Debug)]
pub(crate) enum InitError {
    Unavailable(String),
    Failure(String),
}
impl From<String> for InitError {
    fn from(value: String) -> Self {
        Self::Failure(value)
    }
}
impl From<&str> for InitError {
    fn from(value: &str) -> Self {
        Self::Failure(value.into())
    }
}
impl From<InitError> for String {
    fn from(value: InitError) -> String {
        match value {
            InitError::Unavailable(s) | InitError::Failure(s) => s,
        }
    }
}

pub(crate) struct GpuResources {
    pub backend: String,
    pub sender: mpsc::SyncSender<GpuCommand>,
    pub ore_gpu: Mutex<geology::raster_gpu::Gpu>,
    pub feature_gpu: Mutex<decoration::counts::gpu::Gpu>,
    pub light_gpu: Mutex<lighting::Gpu>,
    pub vine_gpu: Mutex<decoration::vine_gpu::Gpu>,
}
impl GpuResources {
    pub fn start(depth: usize, metrics: Arc<pipeline::Metrics>) -> Result<Option<Self>, String> {
        if crate::gpu_backend::selected()?.is_empty() {
            crate::startup::info(
                "CPU backend selected: no graphics API requested by configuration/override",
            );
            return Ok(None);
        }
        let (sender, receiver) = mpsc::sync_channel::<GpuCommand>(256);
        let (ready_sender, ready_receiver) = mpsc::sync_channel(1);
        std::thread::Builder::new()
            .name("retina-gpu".into())
            .spawn(move || gpu_worker::run(receiver, ready_sender, metrics, depth))
            .map_err(|error| error.to_string())?;
        let (backend, ores, counts) = {
            let _wait = crate::startup::Stage::new("waiting for GPU worker readiness");
            match ready_receiver.recv().map_err(|error| error.to_string())? {
                Ok(ready) => ready,
                Err(InitError::Unavailable(reason)) => {
                    crate::startup::info(format_args!("CPU backend selected: {reason}"));
                    return Ok(None);
                }
                Err(InitError::Failure(error)) => return Err(error),
            }
        };
        crate::startup::info(format_args!("GPU worker ready: {backend}"));
        let (device, queue) = ores.shared_device();
        let vines = decoration::vine_gpu::Gpu::new(device.clone(), queue.clone(), &backend);
        let light = lighting::Gpu::new(device, queue, &backend);
        Ok(Some(Self {
            sender,
            backend,
            ore_gpu: Mutex::new(ores),
            feature_gpu: Mutex::new(counts),
            vine_gpu: Mutex::new(vines),
            light_gpu: Mutex::new(light),
        }))
    }
}

#[cfg(test)]
mod tests {
    #[test]
    fn cpu_engine_state_needs_no_graphics_resources_or_worker() {
        let engine = crate::TerrainEngine::from_compute(None, Default::default());
        assert!(engine.compute.is_none());
        assert_eq!(engine.backend(), "CPU: native");
        assert_eq!(engine.pipeline_snapshot().completed, 0);
        engine.timings(81);
        engine.retire_world_profiles(vec![81]);
        assert!(engine.timings.lock().unwrap().is_empty());
        assert!(engine.gpu().is_err());
    }
}

/// Backend-independent terrain result. Device buffers never cross this boundary.
pub(crate) struct Sample {
    pub columns: Vec<crate::profile::Column>,
    pub mask: Option<crate::geology::CaveMask>,
    pub timings: crate::timings::Snapshot,
    #[cfg(test)]
    pub host_phases: crate::gpu::host_phase_probe::Phases,
}

#[cfg(test)]
mod selection_tests {
    #[test]
    fn backend_selection_child() {
        let Ok(case) = std::env::var("RETINA_BACKEND_CHILD_CASE") else {
            return;
        };
        crate::startup::retina_configure_debug(1);
        match case.as_str() {
            "disabled" => assert_eq!(crate::gpu_backend::retina_configure_gpu_backend(0x100), 0),
            "unavailable" => {
                // Attempt an API not implemented by this native target. The
                // production decision still comes from actual adapter creation.
                #[cfg(any(target_os = "macos", target_os = "linux"))]
                let code = 2;
                #[cfg(target_os = "windows")]
                let code = 3;
                assert_eq!(crate::gpu_backend::retina_configure_gpu_backend(code), 0);
            }
            "cpu" | "invalid" => {}
            _ => panic!("unknown child case"),
        }
        if case == "invalid" {
            let error = crate::TerrainEngine::new()
                .err()
                .expect("invalid backend must remain an error");
            assert!(error.contains("unknown RETINA_GPU_BACKEND"), "{error}");
            return;
        }
        let engine = crate::TerrainEngine::new().unwrap();
        assert!(engine.compute.is_none());
        assert_eq!(engine.backend(), "CPU: native");
        let request = crate::ChunkRequest {
            seed: 123456789,
            chunk_x: -1,
            chunk_z: 1,
            min_y: -64,
            height: 384,
            base_height: 64.0,
            amplitude: 48.0,
            frequency: 0.008,
            reserved: 0,
        };
        let columns = engine.sample_columns(&[request]).unwrap();
        assert_eq!(columns.len(), 256);
        assert!(columns.iter().all(|c| c.height > -64 && c.height <= 320));
        assert_eq!(engine.pipeline_snapshot().completed, 0);
    }
    #[test]
    fn native_selection_uses_cpu_for_disabled_or_unavailable_and_rejects_invalid_configuration() {
        for case in ["disabled", "cpu", "unavailable", "invalid"] {
            let mut command = std::process::Command::new(std::env::current_exe().unwrap());
            command
                .args([
                    "--exact",
                    "compute::selection_tests::backend_selection_child",
                    "--nocapture",
                ])
                .env("RETINA_BACKEND_CHILD_CASE", case)
                .env_remove("RETINA_GPU_BACKEND");
            if case == "cpu" {
                command.env("RETINA_GPU_BACKEND", "cpu");
            }
            if case == "invalid" {
                command.env("RETINA_GPU_BACKEND", "invalid");
            }
            let output = command.output().unwrap();
            assert!(
                output.status.success(),
                "{case}: {}",
                String::from_utf8_lossy(&output.stderr)
            );
            let stderr = String::from_utf8_lossy(&output.stderr);
            if case == "disabled" || case == "cpu" {
                assert!(!stderr.contains("GPU instance creation"), "{stderr}");
            }
            if case == "unavailable" {
                assert!(
                    stderr.contains("GPU hardware adapter enumeration"),
                    "{stderr}"
                );
                assert!(stderr.contains("CPU backend selected"), "{stderr}");
            }
            if case == "invalid" {
                assert!(!stderr.contains("CPU backend selected"), "{stderr}");
            }
            eprintln!("Native backend selection {case}: {}", output.status);
        }
    }
}
