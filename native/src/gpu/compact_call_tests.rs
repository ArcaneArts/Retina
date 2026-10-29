//! Compare complete generation against the original interpreter on the same GPU.
use super::*;

#[test]
#[ignore = "requires a real GPU and RETINA_STARTUP_DEFINITION"]
fn compact_sampling_matches_reference_world_generation() {
    let definition = crate::world_definition::WorldDefinition::read(
        std::path::Path::new(&std::env::var("RETINA_STARTUP_DEFINITION").unwrap())).unwrap();
    let mut gpu = Gpu::new(std::sync::Arc::new(crate::pipeline::Metrics::default())).unwrap();
    gpu.specialized_terrain = true;
    gpu.interpreter_dispatch = false;
    let times = timings::Timings::default();
    // End terrain has its own generator, not a registry interpreter. Its native
    // fixture tests exercise that path; do not route it through this GPU oracle.
    let end = definition.dimension("minecraft:the_end").unwrap().profile().unwrap();
    assert!(end.end.is_some() && end.registry_program.is_none());
    for (index, dimension) in ["minecraft:overworld", "minecraft:the_nether"].into_iter().enumerate() {
        let mut profile = (**definition.dimension(dimension).unwrap().profile().unwrap()).clone();
        profile.program_execution = crate::specialize::Execution::Interpreter;
        let registry = profile.registry_program.as_ref().unwrap();
        let capacity = if registry.scratch_values() > crate::program::COMPACT_VALUES { 1024 } else { crate::program::COMPACT_VALUES };
        let depth = registry.interpolation_depth();
        let composition = registry.density_composition;
        let key = (capacity, depth, composition, crate::specialize::interpreter_reuse::Plan::new(registry, capacity, composition));
        let make = |source: String| std::sync::Arc::new(crate::specialize::compile_interpreter(
            &gpu.device, &gpu.layout, &gpu.cave_layout, &source, None, false).unwrap());
        let reference = make(crate::program::interpreter_reference_source(capacity, depth, composition));
        let compact = make(crate::program::interpreter_source_density(capacity, depth, composition));
        let id = index as u32 + 1;
        for (case, (x, z, min_y, height, side)) in [
            (-33, -33, -64, 384, 3),
            (255, -256, -80, 448, 3), // generation bounds clip both ends
            (-1_048_575, 1_048_575, -64, 384, 0), // large f32 coordinates
            (0, -1, 48, 17, 3), // non-aligned ceiling and thin interval
        ].into_iter().enumerate() {
            let mut request = GpuRequest::from(crate::ChunkRequest {
                seed: 123456789 + case as u64 * 7919, chunk_x: x, chunk_z: z,
                min_y, height, base_height: 64., amplitude: 48., frequency: 0.008, reserved: id,
            });
            request.tile_side = side;
            request.padding = u32::from(side != 0);
            let mut samples = Vec::new();
            for pipelines in [&reference, &compact] {
                if depth > 0 || composition {
                    gpu.interpolation_interpreters.insert(key, pipelines.clone());
                } else {
                    // Select the complete depth-zero bundle instead of base
                    // fallbacks, so both sides execute their own source code.
                    gpu.wide_profiles.insert(id);
                    gpu.wide_interpreter = Some(pipelines.clone());
                }
                let pending = gpu.submit(&[request], Some(&profile), &times).unwrap();
                samples.push(gpu.complete(pending, &times).unwrap());
            }
            eprintln!("COMPACT_PARITY dimension={dimension} case={case} columns={} materials={}", samples[0].columns.len(), samples[0].mask.is_some());
            super::material_continuation_tests::equal(&samples[0], &samples[1]);
        }
    }
}
