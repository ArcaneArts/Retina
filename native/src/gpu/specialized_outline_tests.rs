//! Compare production outlined specialization with its original generated code.
use super::*;

#[test]
#[ignore = "requires a real GPU and RETINA_STARTUP_DEFINITION"]
fn outlined_specialization_matches_original_generation() {
    let definition = crate::world_definition::WorldDefinition::read(
        std::path::Path::new(&std::env::var("RETINA_STARTUP_DEFINITION").unwrap())).unwrap();
    let mut actual = Gpu::new_with_policy(Default::default(), |features, size|
        Policy::resolve(features, size, |_| None)).unwrap();
    let mut reference = Gpu::new_with_policy(Default::default(), |features, size|
        Policy::resolve(features, size, |_| None)).unwrap();
    reference.compiler.reference_specialization = true;
    let times = timings::Timings::default();
    for (dimension_index, dimension) in ["minecraft:overworld", "minecraft:the_nether"].into_iter().enumerate() {
        let mut profile = (**definition.dimension(dimension).unwrap().profile().unwrap()).clone();
        profile.program_execution = crate::specialize::Execution::Specialized;
        for terrain in [false, true] {
            actual.specialized_terrain = terrain;
            reference.specialized_terrain = terrain;
            let id = (1 + dimension_index * 2 + usize::from(terrain)) as u32;
            for (case, (x, z, min_y, height)) in [(-33, -33, -64, 384), (255, -256, -80, 448), (1_048_575, -1_048_575, 48, 17)].into_iter().enumerate() {
                let mut request = GpuRequest::from(crate::ChunkRequest {
                    seed: 123456789 + case as u64 * 7919, chunk_x: x, chunk_z: z,
                    min_y, height, base_height: 64., amplitude: 48., frequency: 0.008, reserved: id,
                });
                request.tile_side = 3;
                request.padding = 1;
                // First submission compiles. The second measures warm generation
                // only, with alternate order to limit consistent ordering bias.
                for repeat in 0..2 {
                    let mut samples = Vec::new();
                    for (outlined, gpu) in if repeat == 0 { [(true, &mut actual), (false, &mut reference)] } else { [(false, &mut reference), (true, &mut actual)] } {
                        let start = Instant::now();
                        let pending = gpu.submit(&[request], Some(&profile), &times).unwrap();
                        let sample = gpu.complete(pending, &times).unwrap();
                        if repeat == 1 { eprintln!("OUTLINE_TIMING dimension={dimension} terrain={terrain} case={case} outlined={outlined} ms={:.3}",start.elapsed().as_secs_f64()*1000.); }
                        samples.push(sample);
                        assert!(gpu.specialized[&id].ready(true).unwrap().is_some());
                    }
                    super::material_continuation_tests::equal(&samples[0], &samples[1]);
                }
                eprintln!("OUTLINE_PARITY dimension={dimension} terrain={terrain} case={case} columns=2304");
            }
        }
    }
}
