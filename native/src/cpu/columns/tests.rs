use super::*;
use crate::world_definition::{WorldDefinition, catalog::WorldCatalog};
fn definition() -> WorldDefinition {
    let catalog = WorldCatalog::read(std::path::Path::new(concat!(
        env!("CARGO_MANIFEST_DIR"),
        "/../assets/world-definitions/26.3/vanilla-binary/retina-catalog.dat"
    )))
    .unwrap();
    WorldDefinition::from_gzip(catalog.materialize("normal", 123456789).unwrap().as_slice())
        .unwrap()
}
fn request(d: &crate::world_definition::Dimension, seed: u64) -> ChunkRequest {
    let p = d.parameters();
    ChunkRequest {
        seed,
        chunk_x: -1,
        chunk_z: 1,
        min_y: p.min_y,
        height: p.height as u32,
        base_height: p.base_height,
        amplitude: p.amplitude,
        frequency: p.frequency,
        reserved: 1,
    }
}
#[test]
fn vanilla_and_legacy_columns_are_valid_repeatable_across_request_origins() {
    let d = definition();
    for key in ["minecraft:overworld", "minecraft:the_nether"] {
        let dimension = d.dimension(key).unwrap();
        let request = request(dimension, d.seed() as u64);
        let profile = dimension.profile().unwrap();
        for legacy in [false, true] {
            let mut profile = profile.as_ref().clone();
            if legacy {
                profile.registry_program = None;
            }
            let fields = Fields::new(&profile).unwrap();
            let mut a = Columns::new(&profile, &fields, request);
            let mut b = Columns::new(
                &profile,
                &fields,
                ChunkRequest {
                    chunk_x: -33,
                    chunk_z: 32,
                    ..request
                },
            );
            for [x, z] in [[-17, 31], [0, 0], [16677777, -16677777]] {
                let one = a.column(x, z, true).unwrap();
                let two = b.column(x, z, true).unwrap();
                assert_eq!(bytemuck::bytes_of(&one), bytemuck::bytes_of(&two));
                assert!(
                    one.height >= request.min_y
                        && one.height <= request.min_y + request.height as i32,
                    "{key} {}",
                    one.height
                );
                assert!((one.packed & 65535) < profile.biomes.len() as u32);
                assert!((one.materials & 65535) < profile.materials.len() as u32);
                assert!((one.materials >> 16) < profile.materials.len() as u32);
            }
        }
    }
}
#[test]
#[ignore = "requires a real hardware GPU; bounded column comparison"]
fn cpu_columns_match_hardware_generation_rules() {
    let d = definition();
    let mut gpu =
        crate::gpu::Gpu::new(std::sync::Arc::new(crate::pipeline::Metrics::default())).unwrap();
    for (index, key) in ["minecraft:overworld", "minecraft:the_nether"]
        .into_iter()
        .enumerate()
    {
        let dimension = d.dimension(key).unwrap();
        let request = ChunkRequest {
            reserved: 1 + index as u32,
            ..request(dimension, d.seed() as u64)
        };
        let profile = dimension.profile().unwrap();
        let fields = Fields::new(profile).unwrap();
        let mut cpu = Columns::new(profile, &fields, request);
        let timing = crate::timings::Timings::default();
        let pending = gpu
            .submit(&[crate::GpuRequest::from(request)], Some(profile), &timing)
            .unwrap();
        let sample = gpu.complete(pending, &timing).unwrap();
        let mut max_height = 0;
        let mut sum_height = 0;
        let mut biomes = 0;
        let mut materials = 0;
        for i in (0..256).step_by(17) {
            let x = request.chunk_x * 16 + (i % 16) as i32;
            let z = request.chunk_z * 16 + (i / 16) as i32;
            let a = cpu.column(x, z, true).unwrap();
            let b = sample.columns[i];
            let error = (a.height - b.height).abs();
            max_height = max_height.max(error);
            sum_height += error;
            biomes += usize::from(a.packed & 65535 != b.packed & 65535);
            materials += usize::from(a.materials != b.materials);
            eprintln!(
                "column {key} {x},{z}: CPU {}/{}/{} GPU {}/{}/{}",
                a.height,
                a.packed & 65535,
                a.materials,
                b.height,
                b.packed & 65535,
                b.materials
            );
        }
        eprintln!(
            "CPU/GPU {key}: 16 columns max height {max_height}, sum {sum_height}, biome differences {biomes}, material differences {materials}"
        );
        assert!(
            max_height <= 8,
            "systematic CPU/GPU terrain disagreement {key}: {max_height}"
        );
        assert!(
            biomes <= 2,
            "systematic CPU/GPU biome disagreement {key}: {biomes}"
        );
    }
}

#[test]
fn loaded_lake_barriers_fluids_snow_and_bands_keep_packed_contract() {
    let d = definition();
    let dimension = d.dimension("minecraft:overworld").unwrap();
    let mut r = request(dimension, d.seed() as u64);
    r.base_height = 180.0;
    r.amplitude = 0.0;
    let mut p = dimension.profile().unwrap().as_ref().clone();
    p.registry_program = None;
    p.climate_targets.clear();
    p.biomes.truncate(1);
    p.biomes[0].terrain = [0.0, 0.0, 1.0];
    p.biomes[0].flags = 0;
    p.biomes[0].lakes = [1.0, 1.0];
    p.biomes[0].lake_barrier = p.stone;
    let h = cell_hash(
        [0, 0],
        (r.seed as u32).wrapping_add(8647),
        (r.seed >> 32) as u32,
    );
    let x = ((0.25 + (h & 255) as f32 / 510.0) * 128.0).round() as i32;
    let z = ((0.25 + ((h >> 8) & 255) as f32 / 510.0) * 128.0).round() as i32;
    let f = Fields::new(&p).unwrap();
    let mut c = Columns::new(&p, &f, r);
    let dry = c.column(x, z, false).unwrap();
    let wet = c.column(x, z, true).unwrap();
    assert_eq!(dry.packed & (1 << 29), 0);
    assert_ne!(wet.packed & (1 << 29), 0);
    assert_ne!(wet.packed & (1 << 28), 0);
    assert!(wet.height < dry.height);
    assert_eq!(wet.materials, p.stone as u32 * 0x10001);
    p.biomes[0].lakes = [0.0, 0.0];
    p.biomes[0].flags = 1;
    let f = Fields::new(&p).unwrap();
    let snow = Columns::new(&p, &f, r).column(x, z, true).unwrap();
    assert_eq!(snow.materials & 65535, p.snow as u32);
    assert_ne!(snow.packed & (1 << 28), 0);
    p.biomes[0].flags = 8;
    p.terrain_features.band_frequency = 0.01;
    let f = Fields::new(&p).unwrap();
    let bands = Columns::new(&p, &f, r).column(x, z, true).unwrap();
    let offset = (bands.packed >> 16) as u8 as i8;
    assert!((-127..=127).contains(&offset));
    assert_eq!(bands.packed & (1 << 29), 0);
}
