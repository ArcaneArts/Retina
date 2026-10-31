//! Real pipeline-policy and publication parity; no Minecraft client required.
use super::*;

#[test]
#[ignore = "requires explicit Vulkan or Metal, RETINA_VULKAN_TIERED_INTERPRETER=1 and RETINA_STARTUP_DEFINITION"]
fn tiered_interpreter_promotes_without_changing_inflight_generation() {
    let backend = std::env::var("RETINA_GPU_BACKEND").unwrap();
    assert!(matches!(backend.as_str(), "vulkan" | "metal"));
    assert_eq!(std::env::var("RETINA_VULKAN_TIERED_INTERPRETER").as_deref(), Ok("1"));
    crate::startup::retina_configure_debug(1);
    let definition = crate::world_definition::WorldDefinition::read(
        std::env::var("RETINA_STARTUP_DEFINITION").unwrap()).unwrap();
    let mut gpu = Gpu::new(Default::default()).unwrap();
    if backend == "metal" { gpu.compiler.exercise_tiered_lifecycle_on_metal(); }
    assert!(gpu.backend.starts_with(if backend == "vulkan" { "Vulkan:" } else { "Metal:" }));
    let times = timings::Timings::default();
    for (i, dimension) in ["minecraft:overworld", "minecraft:the_nether"].into_iter().enumerate() {
        let mut profile = (**definition.dimension(dimension).unwrap().profile().unwrap()).clone();
        profile.program_execution = crate::specialize::Execution::Interpreter;
        let terrain_only = std::env::var("RETINA_TEST_TERRAIN_ONLY").as_deref() == Ok("1");
        if terrain_only { profile.registry_program.as_mut().unwrap().material_layers = false; }
        let program = profile.registry_program.as_ref().unwrap();
        let key = (program.interpreter_capacity(), program.interpolation_depth(), program.density_composition,
            crate::specialize::interpreter_reuse::Plan::new(program, program.interpreter_capacity(), program.density_composition));
        let id = i as u32 + 1;
        let mut request = GpuRequest::from(crate::ChunkRequest {
            seed: 123456789, chunk_x: -33, chunk_z: -33, min_y: -80, height: 448,
            base_height: 64., amplitude: 48., frequency: 0.008, reserved: id,
        });
        request.tile_side = 3; request.padding = 1;
        let pending = gpu.submit(&[request], Some(&profile), &times).unwrap();
        let _first = gpu.complete(pending, &times).unwrap();
        let observed = gpu.interpreter_profiles[&id].clone();
        let initial = if observed.kind == "interpreter-fast" { observed } else {
            // A warm optimized preload may win before the first submission.
            // Only use its initial counterpart if this actual profile is
            // compatible; another dimension can require an on-demand bundle.
            assert_eq!(observed.kind, "interpreter-optimized");
            assert!(crate::specialize::interpreter_reuse::Plan::density_preload(program.density_composition).supports(key.3));
            gpu.preloaded_interpreters[&program.density_composition].ready(true).unwrap().unwrap()
        };
        assert_eq!(initial.kind, "interpreter-fast");
        let progress = initial.promotion_progress().unwrap();
        let started = Instant::now();
        while progress.snapshot().status == 1 && started.elapsed() < Duration::from_secs(180) {
            std::thread::sleep(Duration::from_millis(10));
        }
        assert_eq!(progress.snapshot().status, 2, "optimized interpreter must actually complete");
        let optimized = initial.promoted().unwrap();
        assert_eq!(optimized.kind, "interpreter-optimized");
        // Select the initial set explicitly even if the driver cache made the
        // optimized job finish before the first submission observed it.
        gpu.interpolation_interpreters.insert(key, std::sync::Arc::new(initial.initial_only().with_reuse(key.3)));
        let fast_pending = gpu.submit(&[request], Some(&profile), &times).unwrap();
        assert_eq!(gpu.interpreter_profiles[&id].kind, "interpreter-fast");
        gpu.interpolation_interpreters.insert(key, std::sync::Arc::new(initial.with_reuse(key.3)));
        let optimized_pending = gpu.submit(&[request], Some(&profile), &times).unwrap();
        assert_eq!(gpu.interpreter_profiles[&id].kind, "interpreter-optimized");
        let fast = gpu.complete(fast_pending, &times).unwrap();
        let optimized_sample = gpu.complete(optimized_pending, &times).unwrap();
        super::material_continuation_tests::equal(&fast, &optimized_sample);
        assert_eq!(initial.kind, "interpreter-fast");
        gpu.retire(&[id]);
        assert!(!gpu.interpreter_profiles.contains_key(&id));
        eprintln!("INTERPRETER_PROMOTION dimension={dimension} columns={} materials_enabled={} inflight_parity=true", fast.columns.len(), !terrain_only);
    }
}
