//! GPU ownership stays separate from the engine's saved-world and CPU state.
//! Constructing an engine without these resources performs no graphics work.
use crate::{GpuCommand, decoration, geology, gpu_worker, lighting, pipeline};
use std::sync::{Arc, Mutex, mpsc};

pub(crate) struct GpuResources {
    pub backend: String,
    pub sender: mpsc::SyncSender<GpuCommand>,
    pub ore_gpu: Mutex<geology::raster_gpu::Gpu>,
    pub feature_gpu: Mutex<decoration::counts::gpu::Gpu>,
    pub light_gpu: Mutex<lighting::Gpu>,
    pub vine_gpu: Mutex<decoration::vine_gpu::Gpu>,
}
impl GpuResources {
    pub fn start(depth: usize, metrics: Arc<pipeline::Metrics>) -> Result<Self, String> {
        let (sender, receiver) = mpsc::sync_channel::<GpuCommand>(256);
        let (ready_sender, ready_receiver) = mpsc::sync_channel(1);
        std::thread::Builder::new()
            .name("retina-gpu".into())
            .spawn(move || gpu_worker::run(receiver, ready_sender, metrics, depth))
            .map_err(|error| error.to_string())?;
        let (backend, ores, counts) = {
            let _wait = crate::startup::Stage::new("waiting for GPU worker readiness");
            ready_receiver.recv().map_err(|error| error.to_string())??
        };
        crate::startup::info(format_args!("GPU worker ready: {backend}"));
        let (device, queue) = ores.shared_device();
        let vines = decoration::vine_gpu::Gpu::new(device.clone(), queue.clone(), &backend);
        let light = lighting::Gpu::new(device, queue, &backend);
        Ok(Self {
            sender,
            backend,
            ore_gpu: Mutex::new(ores),
            feature_gpu: Mutex::new(counts),
            vine_gpu: Mutex::new(vines),
            light_gpu: Mutex::new(light),
        })
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
