use super::*;

#[test]
#[ignore = "requires Metal, RETINA_INCREMENTAL_SPECIALIZATION=1 and RETINA_STARTUP_DEFINITION"]
fn specialization_cohorts_match_interpreter_while_compilation_is_paused() {
    assert_eq!(std::env::var("RETINA_INCREMENTAL_SPECIALIZATION").as_deref(), Ok("1"));
    crate::startup::retina_configure_debug(1);
    let definition = crate::world_definition::WorldDefinition::read(std::env::var("RETINA_STARTUP_DEFINITION").unwrap()).unwrap();
    let mut gpu = Gpu::new(Default::default()).unwrap();
    let mut reference = Gpu::new(Default::default()).unwrap();
    assert!(gpu.backend.starts_with("Metal:"));
    let times = timings::Timings::default();
    for (index, dimension) in ["minecraft:overworld", "minecraft:the_nether"].into_iter().enumerate() {
        let id = index as u32 + 1;
        let mut profile = (**definition.dimension(dimension).unwrap().profile().unwrap()).clone();
        profile.program_execution = crate::specialize::Execution::Interpreter;
        let mut request = GpuRequest::from(crate::ChunkRequest {
            seed: 123456789, chunk_x: -33, chunk_z: -33, min_y: -80, height: 448,
            base_height: 64., amplitude: 48., frequency: 0.008, reserved: id,
        });
        // Match dispatch_full: one border chunk on each side, with the material
        // interior covering a full MCA region in the Overworld fixture.
        request.padding = if index == 0 { 32 } else { 1 };
        request.tile_side = request.padding + 2;
        let baseline = reference.submit(&[request], Some(&profile), &times).unwrap();
        let baseline = reference.complete(baseline, &times).unwrap();
        profile.program_execution = crate::specialize::Execution::Auto;
        let fields = crate::specialize::incremental::pause_at(1);
        let first = gpu.submit(&[request], Some(&profile), &times).unwrap();
        let first = gpu.complete(first, &times).unwrap();
        super::material_continuation_tests::equal(&baseline, &first);
        assert_eq!(fields.reached.recv_timeout(Duration::from_secs(120)).unwrap(), 1);
        let state = gpu.specialized[&id].clone();
        let partial = state.ready(false).unwrap().unwrap();
        assert!(!partial.complete);
        let overlay = partial.overlay(Some(&gpu.interpreter_profiles[&id]));
        assert_eq!(overlay.world_kind("horizontal_nodes"), "specialized");
        assert_ne!(overlay.cave_kind("material_counts"), "specialized");
        let pending = gpu.submit(&[request], Some(&profile), &times).unwrap();
        let sample = gpu.complete(pending, &times).unwrap();
        super::material_continuation_tests::equal(&baseline, &sample);
        assert_eq!(state.progress.group_snapshot().ready_groups, 1);
        let caves = crate::specialize::incremental::pause_at(2);
        fields.release(); drop(fields);
        assert_eq!(caves.reached.recv_timeout(Duration::from_secs(120)).unwrap(), 2);
        let partial_caves = state.ready(false).unwrap().unwrap();
        assert!(!partial_caves.complete);
        assert_eq!(partial_caves.cave_kind("material_counts"), "specialized");
        let pending = gpu.submit(&[request], Some(&profile), &times).unwrap();
        let sample = gpu.complete(pending, &times).unwrap();
        super::material_continuation_tests::equal(&baseline, &sample);
        assert_eq!(state.progress.group_snapshot().ready_groups, 2);
        caves.release(); drop(caves);
        assert!(state.ready(true).unwrap().unwrap().complete);
        let pending = gpu.submit(&[request], Some(&profile), &times).unwrap();
        let sample = gpu.complete(pending, &times).unwrap();
        super::material_continuation_tests::equal(&baseline, &sample);
        assert_eq!(state.progress.group_snapshot().ready_groups, 3);
        assert!(!partial.complete && !partial_caves.complete);
        eprintln!("INCREMENTAL_PARITY dimension={dimension} phases=fields,caves,complete columns={} materials=true", sample.columns.len());
        gpu.retire(&[id]); reference.retire(&[id]);
    }
}
