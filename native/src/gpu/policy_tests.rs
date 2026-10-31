//! Exercise the actual shared selectors, preload and specialized material path.
use super::*;

#[test]
#[ignore = "requires RETINA_INTERPRETER_COMPILE_THREADS=2 and a real GPU/definition"]
fn parallel_required_interpreter_matches_sequential_generation() {
    assert_eq!(std::env::var("RETINA_INTERPRETER_COMPILE_THREADS").as_deref(), Ok("2"));
    crate::startup::retina_configure_debug(1);
    let definition = crate::world_definition::WorldDefinition::read(std::env::var("RETINA_STARTUP_DEFINITION").unwrap()).unwrap();
    let mut parallel = Gpu::new(Default::default()).unwrap();
    let mut sequential = Gpu::new(Default::default()).unwrap();
    sequential.interpreter_preload = false;
    sequential.compiler.sequential_interpreter_for_test();
    let times = timings::Timings::default();
    for (i, dimension) in ["minecraft:overworld", "minecraft:the_nether"].into_iter().enumerate() {
        let mut profile = (**definition.dimension(dimension).unwrap().profile().unwrap()).clone();
        profile.program_execution = crate::specialize::Execution::Interpreter;
        let terrain_only = std::env::var("RETINA_TEST_TERRAIN_ONLY").as_deref() == Ok("1");
        if terrain_only { profile.registry_program.as_mut().unwrap().material_layers = false; }
        let id = i as u32 + 1;
        let mut request = GpuRequest::from(crate::ChunkRequest {
            seed: 123456789, chunk_x: -33, chunk_z: -33, min_y: -80, height: 448,
            base_height: 64., amplitude: 48., frequency: 0.008, reserved: id,
        });
        request.tile_side = 3; request.padding = 1;
        let a = parallel.submit(&[request], Some(&profile), &times).unwrap();
        let b = sequential.submit(&[request], Some(&profile), &times).unwrap();
        let a = parallel.complete(a, &times).unwrap();
        let b = sequential.complete(b, &times).unwrap();
        super::material_continuation_tests::equal(&a, &b);
        eprintln!("PARALLEL_COMPILER_PARITY dimension={dimension} columns={} materials={}", a.columns.len(), !terrain_only);
        parallel.retire(&[id]); sequential.retire(&[id]);
    }
}

#[test]
#[ignore = "requires an explicitly selected Vulkan runtime"]
fn storage_noise_profile_biome_pipeline_creates_on_vulkan() {
    assert_eq!(std::env::var("RETINA_GPU_BACKEND").as_deref(), Ok("vulkan"));
    let gpu = Gpu::new(Default::default()).unwrap();
    assert!(gpu.backend.starts_with("Vulkan:"));
    let scope = gpu.device.push_error_scope(wgpu::ErrorFilter::Validation);
    let first = gpu.sites_pipeline.get();
    assert!(std::ptr::eq(first, gpu.sites_pipeline.get()));
    assert!(pollster::block_on(scope.pop()).is_none());
    gpu.health.check().unwrap();
}

#[test]
#[ignore = "requires a real GPU with immediates and RETINA_STARTUP_DEFINITION"]
fn common_policy_shared_dispatch_matches_independent_generation() {
    let definition = crate::world_definition::WorldDefinition::read(
        std::path::Path::new(&std::env::var("RETINA_STARTUP_DEFINITION").unwrap())).unwrap();
    let mut shared = Gpu::new_with_policy(std::sync::Arc::new(crate::pipeline::Metrics::default()), |features, size|
        Policy::resolve(features, size, |_| None)).unwrap();
    let mut independent = Gpu::new_with_policy(std::sync::Arc::new(crate::pipeline::Metrics::default()), |features, size|
        Policy::resolve(features, size, |key|
            matches!(key, "RETINA_INTERPRETER_DISPATCH" | "RETINA_MATERIAL_DISPATCH").then(|| "0".into()))).unwrap();
    assert!(shared.device.features().contains(wgpu::Features::IMMEDIATES), "test needs a real immediate-capable adapter");
    assert!(shared.preloaded_interpreters.is_empty(), "no speculative bundles before a profile exists");
    assert!(independent.preloaded_interpreters.is_empty());
    let times = timings::Timings::default();
    for (dimension_index, dimension) in ["minecraft:overworld", "minecraft:the_nether"].into_iter().enumerate() {
        for (mode_index, mode) in [crate::specialize::Execution::Interpreter, crate::specialize::Execution::Specialized].into_iter().enumerate() {
            let mut profile = (**definition.dimension(dimension).unwrap().profile().unwrap()).clone();
            profile.program_execution = mode;
            let id = (dimension_index * 2 + mode_index + 1) as u32;
            for (case, (x, z, min_y, height)) in [(-33, -33, -64, 384), (255, -256, -80, 448)].into_iter().enumerate() {
                let mut request = GpuRequest::from(crate::ChunkRequest {
                    seed: 123456789 + case as u64 * 7919, chunk_x: x, chunk_z: z,
                    min_y, height, base_height: 64., amplitude: 48., frequency: 0.008, reserved: id,
                });
                request.tile_side = 3;
                request.padding = 1;
                let a = shared.submit(&[request], Some(&profile), &times).unwrap();
                let a = shared.complete(a, &times).unwrap();
                assert_eq!(shared.preloaded_interpreters.len(), 1, "unused legacy bundle must not compile");
                assert!(independent.preloaded_interpreters.is_empty());
                for state in shared.preloaded_interpreters.values() {
                    assert!(state.ready(true).unwrap().is_some(), "required preload must compile, not silently fall back");
                }
                let b = independent.submit(&[request], Some(&profile), &times).unwrap();
                let b = independent.complete(b, &times).unwrap();
                super::material_continuation_tests::equal(&a, &b);
                assert!(a.mask.is_some());
                assert!(shared.interpreter_profiles[&id].world_selected("surface_columns").dispatch.is_some());
                assert!(independent.interpreter_profiles[&id].world_selected("surface_columns").dispatch.is_none());
                if mode == crate::specialize::Execution::Specialized {
                    let a = shared.specialized[&id].ready(true).unwrap().unwrap();
                    let b = independent.specialized[&id].ready(true).unwrap().unwrap();
                    assert!(a.cave_or("material_counts", &shared.material_counts_pipeline).dispatch.is_some());
                    assert!(b.cave_or("material_counts", &independent.material_counts_pipeline).dispatch.is_none());
                }
                eprintln!("SHARED_POLICY_PARITY dimension={dimension} specialized={} case={case} columns={} material_words={}",
                    mode == crate::specialize::Execution::Specialized, a.columns.len(), a.mask.as_ref().unwrap().words.len());
            }
        }
    }
    // A legacy definition requests its own layout only when it is actually used.
    let mut legacy = (**definition.dimension("minecraft:overworld").unwrap().profile().unwrap()).clone();
    legacy.program_execution = crate::specialize::Execution::Interpreter;
    legacy.registry_program.as_mut().unwrap().density_composition = false;
    let mut request = GpuRequest::from(crate::ChunkRequest {
        seed: 123456789, chunk_x: -33, chunk_z: -33, min_y: -64, height: 384,
        base_height: 64., amplitude: 48., frequency: 0.008, reserved: 99,
    });
    request.tile_side = 3; request.padding = 1;
    let a = shared.submit(&[request], Some(&legacy), &times).unwrap();
    let a = shared.complete(a, &times).unwrap();
    let b = independent.submit(&[request], Some(&legacy), &times).unwrap();
    let b = independent.complete(b, &times).unwrap();
    super::material_continuation_tests::equal(&a, &b);
    assert_eq!(shared.preloaded_interpreters.len(), 2, "legacy compiled when requested");
    assert!(shared.preloaded_interpreters[&false].ready(true).unwrap().is_some());
    eprintln!("SHARED_POLICY_PARITY legacy=true columns={}", a.columns.len());

}
