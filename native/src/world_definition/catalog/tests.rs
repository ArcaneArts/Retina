use super::*;
use crate::world_definition::{tests::document, value};
use serde_json::json;

fn catalog() -> WorldCatalog {
    let mut doc = document();
    let mut profile = value::decode(
        doc.dimensions["minecraft:overworld"]
            .profile
            .clone()
            .unwrap(),
    )
    .unwrap();
    profile["terrain_features"] = json!({"bands":[], "snow_layer":1});
    let dimension = doc.dimensions.get_mut("minecraft:overworld").unwrap();
    let mut materials = Vec::new();
    for name in BAND_NAMES {
        let shared = doc.shared_types.materials.len() as i32;
        doc.shared_types
            .materials
            .push(crate::world_definition::wire::BlockState {
                id: name.into(),
                properties: BTreeMap::new(),
            });
        materials.push(dimension.material_palette.len() as i32);
        let mut palette = dimension.material_palette.to_vec();
        palette.push(shared);
        dimension.material_palette = IntArray::new(palette);
    }
    profile["material_flags"]
        .as_array_mut()
        .unwrap()
        .extend(std::iter::repeat_n(json!(1), 7));
    profile["heightmap_masks"]
        .as_array_mut()
        .unwrap()
        .extend(std::iter::repeat_n(json!(63), 7));
    dimension.profile = Some(value::encode(&profile).unwrap());
    let mut template: Value = fastnbt::from_bytes(&fastnbt::to_bytes(&doc).unwrap()).unwrap();
    let root = compound_mut(&mut template).unwrap();
    root.remove("seed");
    let dims = compound_mut(root.get_mut("dimensions").unwrap()).unwrap();
    let dimension = compound_mut(dims.get_mut("minecraft:overworld").unwrap()).unwrap();
    let profile = compound_mut(dimension.get_mut("profile").unwrap()).unwrap();
    compound_mut(profile.get_mut("terrain_features").unwrap())
        .unwrap()
        .remove("bands");
    WorldCatalog {
        catalog_version: 1,
        materialization_version: 1,
        variants: BTreeMap::from([(
            "normal".into(),
            Variant {
                template,
                seed_derivations: BTreeMap::from([(
                    "minecraft:overworld".into(),
                    BandsRecipe {
                        algorithm: "retina:badlands_bands/1".into(),
                        materials: IntArray::new(materials),
                    },
                )]),
            },
        )]),
    }
}

fn scratch() -> PathBuf {
    static NEXT: AtomicU64 = AtomicU64::new(0);
    let root = std::env::temp_dir().join(format!(
        "retina-catalog-test-{}-{}",
        std::process::id(),
        NEXT.fetch_add(1, Ordering::Relaxed)
    ));
    fs::create_dir_all(&root).unwrap();
    root
}

#[test]
fn materializes_exact_seed_and_deterministic_binary_without_mutating_catalog() {
    let source = catalog();
    let before = fastnbt::to_bytes(&source).unwrap();
    let mut previous = None;
    for seed in [0, -1, i64::MIN + 17, i64::MAX - 11, 123456789] {
        let bytes = source.materialize("normal", seed).unwrap();
        assert_eq!(bytes, source.materialize("normal", seed).unwrap());
        let reloaded: WorldCatalog = fastnbt::from_bytes(&before).unwrap();
        assert_eq!(bytes, reloaded.materialize("normal", seed).unwrap());
        let world = WorldDefinition::from_gzip(bytes.as_slice()).unwrap();
        assert_eq!(world.seed(), seed);
        let bands =
            world.diagnostic_profile("minecraft:overworld").unwrap()["terrain_features"]["bands"]
                .clone();
        assert_eq!(bands.as_array().unwrap().len(), 192);
        if let Some(previous) = previous {
            assert_ne!(previous, bands);
        }
        previous = Some(bands);
    }
    assert_eq!(before, fastnbt::to_bytes(&source).unwrap());
}

#[test]
fn validates_real_catalog_fields_and_preserves_existing_destination() {
    let mut source = catalog();
    assert!(
        source
            .materialize("missing", 0)
            .unwrap_err()
            .contains("no variant")
    );
    source.catalog_version = 99;
    assert!(
        source
            .materialize("normal", 0)
            .unwrap_err()
            .contains("versions")
    );
    source.catalog_version = 1;
    source
        .variants
        .get_mut("normal")
        .unwrap()
        .seed_derivations
        .get_mut("minecraft:overworld")
        .unwrap()
        .algorithm = "unknown".into();
    assert!(
        source
            .materialize("normal", 0)
            .unwrap_err()
            .contains("unsupported seed derivation")
    );
    let source = catalog();
    let root = scratch();
    fs::write(root.join("retina.dat"), b"damaged existing world").unwrap();
    assert!(source.publish("normal", 5, &root).is_err());
    assert_eq!(
        fs::read(root.join("retina.dat")).unwrap(),
        b"damaged existing world"
    );
    assert_eq!(fs::read_dir(&root).unwrap().count(), 1);
    fs::remove_file(root.join("retina.dat")).unwrap();
    let file = source.publish("normal", i64::MIN + 17, &root).unwrap();
    assert_eq!(WorldDefinition::read(file).unwrap().seed(), i64::MIN + 17);
    fs::remove_dir_all(root).unwrap();
}

#[test]
#[ignore = "requires RETINA_CATALOG_FIXTURES generated by :common:vanillaCatalogFixtures"]
fn java_catalog_matches_all_direct_exports() {
    let root = PathBuf::from(
        std::env::var_os("RETINA_CATALOG_FIXTURES").expect("RETINA_CATALOG_FIXTURES"),
    );
    let catalog = WorldCatalog::read(root.join("retina-catalog.dat")).unwrap();
    let references: serde_json::Value =
        serde_json::from_slice(&fs::read(root.join("references.json")).unwrap()).unwrap();
    for reference in references.as_array().unwrap() {
        let variant = reference["variant"].as_str().unwrap();
        let seed = reference["seed"].as_i64().unwrap();
        let actual =
            WorldDefinition::from_gzip(catalog.materialize(variant, seed).unwrap().as_slice())
                .unwrap();
        let expected =
            WorldDefinition::read(root.join(reference["definition"].as_str().unwrap())).unwrap();
        assert!(
            actual.adapter_metadata() == expected.adapter_metadata(),
            "metadata differs: {variant}/{seed}"
        );
        for key in expected.dimensions().keys() {
            assert!(
                actual.diagnostic_profile(key).unwrap()
                    == expected.diagnostic_profile(key).unwrap(),
                "complete profile differs: {variant}/{seed}/{key}"
            );
            assert!(
                actual
                    .dimension(key)
                    .unwrap()
                    .profile()
                    .unwrap()
                    .gpu_bytes()
                    == expected
                        .dimension(key)
                        .unwrap()
                        .profile()
                        .unwrap()
                        .gpu_bytes(),
                "GPU tables differ: {variant}/{seed}/{key}"
            );
        }
        println!(
            "CATALOG_DIRECT_PASS {variant}/{seed}: {} dimensions",
            actual.dimensions().len()
        );
    }
}

/// Compare packed section palettes semantically, then compare every other NBT
/// field unchanged. Different file-local material IDs cannot masquerade as a
/// regression or hide one behind a raw palette-order difference.
fn canonical_chunk(bytes: &[u8]) -> Value {
    use sha2::{Digest, Sha256};
    let mut root: Value = fastnbt::from_bytes(bytes).unwrap();
    let root = compound_mut(&mut root).unwrap();
    if let Some(Value::List(sections)) = root.get_mut("sections") {
        for section in sections {
            let section = compound_mut(section).unwrap();
            let Some(states) = section.get("block_states") else {
                continue;
            };
            let states = compound(states).unwrap();
            let Value::List(palette) = states.get("palette").unwrap() else {
                panic!("block palette")
            };
            let palette: Vec<Vec<u8>> = palette
                .iter()
                .map(|state| fastnbt::to_bytes(&Canonical(state)).unwrap())
                .collect();
            let bits = (usize::BITS - (palette.len() - 1).leading_zeros()).max(4) as usize;
            let per = 64 / bits;
            let mask = (1u64 << bits) - 1;
            let mut hash = Sha256::new();
            for index in 0..4096 {
                let id = if palette.len() == 1 {
                    0
                } else {
                    let Value::LongArray(data) = states.get("data").unwrap() else {
                        panic!("packed blocks")
                    };
                    ((data[index / per] as u64 >> ((index % per) * bits)) & mask) as usize
                };
                hash.update((palette[id].len() as u64).to_le_bytes());
                hash.update(&palette[id]);
            }
            section.insert(
                "block_states".into(),
                Value::ByteArray(fastnbt::ByteArray::new(
                    hash.finalize().iter().map(|&v| v as i8).collect(),
                )),
            );
        }
    }
    Value::Compound(root.clone())
}

#[test]
#[ignore = "requires GPU, RETINA_CATALOG_FIXTURES and RETINA_CATALOG_BASELINE retained direct exports"]
fn materialized_catalog_preserves_previous_generation() {
    use crate::{
        TerrainEngine,
        region::codec::Compression,
        world_definition::runtime::{ProgramExecution, WorldSession},
    };
    use std::sync::Arc;
    let root = PathBuf::from(
        std::env::var_os("RETINA_CATALOG_FIXTURES").expect("RETINA_CATALOG_FIXTURES"),
    );
    let baseline = PathBuf::from(
        std::env::var_os("RETINA_CATALOG_BASELINE").expect("RETINA_CATALOG_BASELINE"),
    );
    let catalog = WorldCatalog::read(root.join("retina-catalog.dat")).unwrap();
    let references: serde_json::Value =
        serde_json::from_slice(&fs::read(root.join("references.json")).unwrap()).unwrap();
    let engine = Arc::new(TerrainEngine::new().unwrap());
    let mut count = 0;
    for reference in references.as_array().unwrap() {
        let variant = reference["variant"].as_str().unwrap();
        let seed = reference["seed"].as_i64().unwrap();
        let actual = WorldSession::bind_with_execution(
            engine.clone(),
            Arc::new(
                WorldDefinition::from_gzip(catalog.materialize(variant, seed).unwrap().as_slice())
                    .unwrap(),
            ),
            Compression::Zlib,
            Some(ProgramExecution::Interpreter),
        )
        .unwrap();
        let direct = WorldSession::bind_with_execution(
            engine.clone(),
            Arc::new(
                WorldDefinition::read(root.join(reference["definition"].as_str().unwrap()))
                    .unwrap(),
            ),
            Compression::Zlib,
            Some(ProgramExecution::Interpreter),
        )
        .unwrap();
        let old = WorldSession::bind_with_execution(
            engine.clone(),
            Arc::new(
                WorldDefinition::read(baseline.join(reference["definition"].as_str().unwrap()))
                    .unwrap(),
            ),
            Compression::Zlib,
            Some(ProgramExecution::Interpreter),
        )
        .unwrap();
        for key in [
            "minecraft:overworld",
            "minecraft:the_nether",
            "minecraft:the_end",
        ] {
            for (x, z) in [(0, 0), (-1, -1), (32, -33)] {
                let actual =
                    canonical_chunk(&actual.dimension(key).unwrap().generate_chunk(x, z).unwrap());
                let direct =
                    canonical_chunk(&direct.dimension(key).unwrap().generate_chunk(x, z).unwrap());
                let old =
                    canonical_chunk(&old.dimension(key).unwrap().generate_chunk(x, z).unwrap());
                assert!(
                    actual == direct,
                    "materialized/direct chunk differs: {variant}/{seed}/{key}/{x},{z}"
                );
                assert!(
                    actual == old,
                    "palette reservation changed generation: {variant}/{seed}/{key}/{x},{z}"
                );
                count += 1;
            }
        }
    }
    println!("CATALOG_GPU_PASS {count} complete semantic chunks: catalog/direct/previous export");
}

#[test]
fn independently_compressed_variants_preserve_materialized_bytes() {
    let source = catalog();
    let root = scratch();
    let mut packed = BTreeMap::new();
    for (name, variant) in &source.variants {
        let mut gzip = GzEncoder::new(Vec::new(), Compression::best());
        gzip.write_all(&fastnbt::to_bytes(variant).unwrap()).unwrap();
        packed.insert(name.clone(), Value::ByteArray(fastnbt::ByteArray::new(
            gzip.finish().unwrap().into_iter().map(|b| b as i8).collect(),
        )));
    }
    // A corrupt unselected payload must not be decompressed. Selecting it or
    // asking for every variant must still report the real decoding failure.
    packed.insert("broken".into(), Value::ByteArray(fastnbt::ByteArray::new(vec![1, 2, 3])));
    let container = Value::Compound(HashMap::from([
        ("catalog_version".into(), Value::Int(2)),
        ("materialization_version".into(), Value::Int(1)),
        ("compressed_variants".into(), Value::Compound(packed.into_iter().collect())),
    ]));
    let path = root.join("catalog.dat");
    let mut gzip = GzEncoder::new(Vec::new(), Compression::best());
    gzip.write_all(&fastnbt::to_bytes(&container).unwrap()).unwrap();
    fs::write(&path, gzip.finish().unwrap()).unwrap();
    let selected = WorldCatalog::read_selected(&path, "normal").unwrap();
    assert_eq!(selected.variants.len(), 1);
    for seed in [0, -1, i64::MIN, i64::MAX, 123456789] {
        assert_eq!(source.materialize("normal", seed).unwrap(), selected.materialize("normal", seed).unwrap());
    }
    assert!(WorldCatalog::read_selected(&path, "broken").err().unwrap().contains("gzip"));
    assert!(WorldCatalog::read_selected(&path, "missing").err().unwrap().contains("no variant"));
    assert!(WorldCatalog::read(&path).is_err());
    fs::remove_dir_all(root).unwrap();
}

#[test]
fn legacy_container_selection_remains_compatible() {
    let source = catalog();
    let root = scratch();
    let path = root.join("catalog.dat");
    let mut gzip = GzEncoder::new(Vec::new(), Compression::best());
    gzip.write_all(&fastnbt::to_bytes(&source).unwrap()).unwrap();
    fs::write(&path, gzip.finish().unwrap()).unwrap();
    let selected = WorldCatalog::read_selected(&path, "normal").unwrap();
    assert_eq!(source.materialize("normal", -1).unwrap(), selected.materialize("normal", -1).unwrap());
    fs::remove_dir_all(root).unwrap();
}

#[test]
#[ignore = "requires RETINA_CATALOG_V1 and RETINA_CATALOG_V2"]
fn repacked_catalog_preserves_every_variant_and_seed() {
    let old = WorldCatalog::read(std::env::var("RETINA_CATALOG_V1").unwrap()).unwrap();
    let new_path = std::env::var("RETINA_CATALOG_V2").unwrap();
    let all = WorldCatalog::read(&new_path).unwrap();
    assert_eq!(old.variants.keys().collect::<Vec<_>>(), all.variants.keys().collect::<Vec<_>>());
    drop(all);
    for name in old.variants.keys() {
        let selected = WorldCatalog::read_selected(&new_path, name).unwrap();
        assert_eq!(selected.variants.len(), 1);
        for seed in [0, -1, i64::MIN, i64::MAX, 123456789] {
            let expected = old.materialize(name, seed).unwrap();
            let actual = selected.materialize(name, seed).unwrap();
            assert_eq!(expected, actual, "changed complete definition: {name}/{seed}");
            println!("CATALOG_CONTAINER_EXACT {name}/{seed} {} bytes", actual.len());
        }
    }
}
