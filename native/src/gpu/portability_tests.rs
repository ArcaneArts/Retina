//! Real Vulkan smoke coverage, including the optional Apple portability build.
use super::*;
#[test]
#[ignore = "requires an explicitly selected Vulkan runtime"]
fn vulkan_portability_smoke() {
    assert_eq!(std::env::var("RETINA_GPU_BACKEND").as_deref(), Ok("vulkan"));
    crate::startup::retina_configure_debug(1);
    let mut gpu = Gpu::new(Default::default()).unwrap();
    assert!(gpu.backend.starts_with("Vulkan:"), "actual backend={}", gpu.backend);
    let timings = timings::Timings::default();
    for x in [0, -3] {
        let request = GpuRequest::from(crate::ChunkRequest {
            seed: 123456789, chunk_x: x, chunk_z: -3, min_y: -64, height: 384,
            base_height: 64.0, amplitude: 48.0, frequency: 0.008, reserved: 0,
        });
        let pending = gpu.submit(&[request], None, &timings).unwrap();
        let actual = gpu.complete(pending, &timings).unwrap();
        assert_eq!(actual.columns.len(), 256);
        assert!(actual.columns.iter().all(|c| (-64..=320).contains(&c.height)));
        let pending = gpu.submit(&[request], None, &timings).unwrap();
        let repeat = gpu.complete(pending, &timings).unwrap();
        assert_eq!(bytemuck::cast_slice::<Column,u8>(&actual.columns),
            bytemuck::cast_slice::<Column,u8>(&repeat.columns));
    }
    crate::pipeline_cache::flush_all();
    eprintln!("VULKAN_SMOKE actual_backend={} deterministic_queries=4", gpu.backend);
}
