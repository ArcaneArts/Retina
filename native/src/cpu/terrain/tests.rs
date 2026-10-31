use super::*;
use crate::{
    TerrainEngine,
    world_definition::{WorldDefinition, catalog::WorldCatalog, runtime::WorldSession},
};
fn definition() -> Arc<WorldDefinition> {
    let c = WorldCatalog::read(std::path::Path::new(concat!(
        env!("CARGO_MANIFEST_DIR"),
        "/../assets/world-definitions/26.3/vanilla-binary/retina-catalog.dat"
    )))
    .unwrap();
    Arc::new(
        WorldDefinition::from_gzip(c.materialize("normal", 123456789).unwrap().as_slice()).unwrap(),
    )
}
#[test]
fn cpu_engine_queries_share_owned_preparation_and_retire_without_graphics() {
    let engine = Arc::new(TerrainEngine::from_compute(None, Default::default()));
    let world = WorldSession::bind(engine.clone(), definition(), Default::default()).unwrap();
    for key in [
        "minecraft:overworld",
        "minecraft:the_nether",
        "minecraft:the_end",
    ] {
        let dim = world.dimension(key).unwrap();
        let r = dim.request(-1, 1).unwrap();
        let columns = engine.sample_columns(&[r]).unwrap();
        assert_eq!(columns.len(), 256);
        let heights = engine.sample_heights(r).unwrap();
        assert_eq!(
            heights.to_vec(),
            columns.iter().map(|c| c.height).collect::<Vec<_>>()
        );
        let point = Point {
            x: -8,
            y: 31,
            z: 24,
        };
        let h = engine
            .query_points(r, &[point, point], crate::queries::Kind::Height)
            .unwrap();
        assert_eq!(h[0].height, h[1].height);
        let b = engine
            .query_points(r, &[point], crate::queries::Kind::Biome)
            .unwrap();
        assert!(
            b[0].biome()
                < definition()
                    .dimension(key)
                    .unwrap()
                    .profile()
                    .unwrap()
                    .biomes
                    .len()
        );
        assert!(engine.compute.is_none());
    }
    assert_eq!(engine.cpu_fields.lock().unwrap().len(), 2);
    drop(world);
    assert!(engine.cpu_fields.lock().unwrap().is_empty());
}
#[test]
fn dense_columns_preserve_serial_order_across_workers_and_program_modes() {
    let d = definition();
    let one = rayon::ThreadPoolBuilder::new()
        .num_threads(1)
        .build()
        .unwrap();
    let four = rayon::ThreadPoolBuilder::new()
        .num_threads(4)
        .build()
        .unwrap();
    for key in ["minecraft:overworld", "minecraft:the_nether"] {
        for legacy in [false, true] {
            let mut profile = d
                .dimension(key)
                .unwrap()
                .profile()
                .unwrap()
                .as_ref()
                .clone();
            if legacy {
                profile.registry_program = None;
            }
            let fields = Fields::new(&profile).unwrap();
            let r = d.request(key, 1, -2, -1).unwrap();
            for mode in [0, 3] {
                let mut serial = Columns::new(&profile, &fields, r);
                let mut reference = Vec::new();
                for cz in 0..3 {
                    for cx in 0..3 {
                        for z in 0..16 {
                            for x in 0..16 {
                                let (x, z) = ((r.chunk_x + cx) * 16 + x, (r.chunk_z + cz) * 16 + z);
                                reference.push(
                                    if mode == 3 {
                                        serial.probe(x, z, true)
                                    } else {
                                        serial.column(x, z, true)
                                    }
                                    .unwrap(),
                                );
                            }
                        }
                    }
                }
                for pool in [&one, &four] {
                    let actual =
                        dense_columns(Some(&profile), Some(&fields), r, 3, mode, pool).unwrap();
                    assert_eq!(
                        bytemuck::cast_slice::<Column, u8>(&reference),
                        bytemuck::cast_slice::<Column, u8>(&actual),
                        "{key} legacy={legacy} mode={mode}"
                    );
                }
            }
        }
    }
    let r = d.request("minecraft:overworld", 1, -1, 0).unwrap();
    let a = dense_columns(None, None, r, 3, 0, &one).unwrap();
    let b = dense_columns(None, None, r, 3, 0, &four).unwrap();
    assert_eq!(
        bytemuck::cast_slice::<Column, u8>(&a),
        bytemuck::cast_slice::<Column, u8>(&b)
    );
}
#[test]
fn cpu_material_masks_repeat_exactly_across_one_and_four_workers() {
    let d = definition();
    let one = rayon::ThreadPoolBuilder::new()
        .num_threads(1)
        .build()
        .unwrap();
    let four = rayon::ThreadPoolBuilder::new()
        .num_threads(4)
        .build()
        .unwrap();
    for key in ["minecraft:overworld", "minecraft:the_nether"] {
        for legacy in [false, true] {
            let mut profile = d
                .dimension(key)
                .unwrap()
                .profile()
                .unwrap()
                .as_ref()
                .clone();
            if legacy {
                profile.registry_program = None;
            }
            let fields = Fields::new(&profile).unwrap();
            let r = d.request(key, 1, -2, -2).unwrap();
            let metrics = timings::Timings::default();
            let columns = sample(
                Some(&profile),
                Some(&fields),
                &[r],
                3,
                0,
                0,
                &[],
                &[],
                &metrics,
            )
            .unwrap()
            .columns;
            let halo = ColumnHalo {
                origin_x: r.chunk_x,
                origin_z: r.chunk_z,
                side: 3,
                columns: Arc::new(columns),
            };
            let a = crate::cpu::materials::build_in_pool(
                &profile,
                &fields,
                r,
                3,
                &halo,
                &metrics.performance,
                &one,
            )
            .unwrap();
            let b = crate::cpu::materials::build_in_pool(
                &profile,
                &fields,
                r,
                3,
                &halo,
                &metrics.performance,
                &four,
            )
            .unwrap();
            assert_eq!(
                a.words, b.words,
                "{key} legacy={legacy}: worker scheduling changed packed output"
            );
            if !legacy {
                a.validate_material_runs().unwrap();
            }
        }
    }
}
#[test]
fn cpu_complete_material_tile_has_valid_storage_and_global_overlap() {
    let d = definition();
    for key in ["minecraft:overworld", "minecraft:the_nether"] {
        let dim = d.dimension(key).unwrap();
        let profile = dim.profile().unwrap();
        let fields = Fields::new(profile).unwrap();
        let r = d.request(key, 1, -1, -1).unwrap();
        let times = timings::Timings::default();
        let start = std::time::Instant::now();
        let a = sample(
            Some(profile),
            Some(&fields),
            &[r],
            3,
            1,
            0,
            &[],
            &[],
            &times,
        )
        .unwrap();
        let m = a.mask.unwrap();
        m.validate_material_runs().unwrap();
        let b = sample(
            Some(profile),
            Some(&fields),
            &[ChunkRequest {
                chunk_x: -2,
                chunk_z: -2,
                ..r
            }],
            4,
            2,
            0,
            &[],
            &[],
            &times,
        )
        .unwrap()
        .mask
        .unwrap();
        for z in 0..16 {
            for x in 0..16 {
                for y in r.min_y..r.min_y + r.height as i32 {
                    let material = m.material_at(x, y, z).unwrap();
                    assert!((material as usize) < profile.materials.len());
                    assert_eq!(
                        Some(material),
                        b.material_at(x, y, z),
                        "CPU overlap {key} {x},{y},{z}"
                    );
                    assert_eq!(m.carved(x, y, z), b.carved(x, y, z));
                    assert_eq!(m.biome(x, y, z), b.biome(x, y, z));
                }
            }
        }
        eprintln!(
            "CPU full material {key}: one- and four-chunk core overlap validated in {:?}; {} mask words",
            start.elapsed(),
            m.words.len()
        );
    }
}
#[test]
#[ignore = "requires a real hardware GPU; complete material/cavity comparison"]
fn cpu_materials_and_cavities_match_real_hardware_near_origin() {
    let d = definition();
    let mut gpu = crate::gpu::Gpu::new(Default::default()).unwrap();
    for (i, key) in ["minecraft:overworld", "minecraft:the_nether"]
        .into_iter()
        .enumerate()
    {
        let dim = d.dimension(key).unwrap();
        let profile = dim.profile().unwrap();
        let fields = Fields::new(profile).unwrap();
        let r = d.request(key, 1 + i as u32, -1, -1).unwrap();
        let times = timings::Timings::default();
        let a = sample(
            Some(profile),
            Some(&fields),
            &[r],
            3,
            1,
            0,
            &[],
            &[],
            &times,
        )
        .unwrap();
        let mut request = crate::GpuRequest::from(r);
        request.tile_side = 3;
        request.padding = 1;
        let pending = gpu.submit(&[request], Some(profile), &times).unwrap();
        let b = gpu.complete(pending, &times).unwrap();
        let a = a.mask.unwrap();
        let b = b.mask.unwrap();
        let (mut material, mut air, mut biome, mut total) = (0, 0, 0, 0);
        let mut transitions = std::collections::BTreeMap::new();
        for z in 0..16 {
            for x in 0..16 {
                for y in r.min_y..r.min_y + r.height as i32 {
                    let one = a.material_at(x, y, z).unwrap();
                    let two = b.material_at(x, y, z).unwrap();
                    if one != two {
                        material += 1;
                        *transitions.entry((one, two)).or_insert(0) += 1;
                    }
                    air += usize::from(a.carved(x, y, z) != b.carved(x, y, z));
                    biome += usize::from(a.biome(x, y, z) != b.biome(x, y, z));
                    total += 1;
                }
            }
        }
        eprintln!(
            "CPU/GPU {key} {total} voxels: materials {material}, cavity bits {air}, biome samples {biome}; transitions {transitions:?}"
        );
        assert!(
            material as f64 / total as f64 <= 0.02,
            "systematic material disagreement {key}"
        );
        assert!(
            air as f64 / total as f64 <= 0.02,
            "systematic cavity disagreement {key}"
        );
        assert!(
            biome as f64 / total as f64 <= 0.02,
            "systematic biome disagreement {key}"
        );
    }
}

#[test]
#[ignore = "bounded full CPU generation acceptance; no client, graphics resources or world files"]
fn full_cpu_chunks_complete_features_and_nbt_in_all_dimensions() {
    let engine = Arc::new(TerrainEngine::from_compute(None, Default::default()));
    let definition = definition();
    let version = definition.data_version();
    let world = WorldSession::bind(engine.clone(), definition, Default::default()).unwrap();
    for (key, x, z) in [
        ("minecraft:overworld", 0, 0),
        ("minecraft:the_nether", 0, 0),
        ("minecraft:the_end", 0, 0),
        ("minecraft:the_end", 128, 128),
    ] {
        let dim = world.dimension(key).unwrap();
        let started = std::time::Instant::now();
        let bytes = dim.generate_chunk(x, z).unwrap();
        let root: std::collections::HashMap<String, fastnbt::Value> =
            fastnbt::from_bytes(&bytes).unwrap();
        assert_eq!(root["DataVersion"], fastnbt::Value::Int(version));
        assert_eq!(root["xPos"], fastnbt::Value::Int(x));
        assert_eq!(root["zPos"], fastnbt::Value::Int(z));
        assert_eq!(
            root["Status"],
            fastnbt::Value::String("minecraft:features".into())
        );
        assert!(root.contains_key("sections"));
        assert!(root.contains_key("Heightmaps"));
        assert!(engine.compute.is_none());
        eprintln!(
            "Full CPU {key} {x},{z}: {} NBT bytes in {:?}",
            bytes.len(),
            started.elapsed()
        );
    }
    drop(world);
    assert!(engine.cpu_fields.lock().unwrap().is_empty());
}
