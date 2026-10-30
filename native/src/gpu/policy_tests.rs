//! Exercise the actual shared selectors, preload and specialized material path.
use super::*;

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
    shared.start_interpreter_preload();
    independent.start_interpreter_preload();
    assert_eq!(shared.preloaded_interpreters.len(), 2);
    assert!(independent.preloaded_interpreters.is_empty());
    for state in shared.preloaded_interpreters.values() {
        assert!(state.ready(true).unwrap().is_some(), "preload must compile, not silently fall back");
    }
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
}
