use super::{WorldDefinition, value, wire::*};
use crate::profile::WorldProfile;
use fastnbt::{IntArray, Value as Nbt};
use flate2::{Compression, write::GzEncoder};
use serde_json::{Value, json};
use std::{collections::BTreeMap, io::Write};

fn profile(biome: &str) -> Value {
    let noise = json!({"frequency":0.001,"amplitude":1,"modifiers":[1]});
    json!({
        "biome_scale":128,"blend":0.55,"sea_level":63,
        "stone":1,"water":1,"bedrock":1,"deepslate":1,"snow":1,"ice":1,
        "biomes":[{"id":biome,"climate":[0,0,0,0],"terrain":[0,0,0],
            "top":1,"filler":1,"underwater":1,"flags":0}],
        "noises":[noise,noise,noise,noise],"material_flags":[0,1],"heightmap_masks":[0,63],
        "flat":{"base_layers":[1,1,0],"deferred_layers":[],"air_materials":[0]}
    })
}

pub(super) fn document() -> Document {
    let dimensions = [
        ("minecraft:overworld", "minecraft:plains", 1, 0),
        ("minecraft:the_nether", "minecraft:nether_wastes", 2, 1),
    ]
    .into_iter()
    .map(|(id, biome, material, biome_id)| {
        (
            id.into(),
            Dimension {
                parameters: Parameters {
                    generator: "flat".into(),
                    storage: Some(Bounds { min_y:-64,height:384 }),
                    min_y: -64,
                    height: 384,
                    generator_depth: 384,
                    sea_level: 63,
                    spawn_height: 64,
                    skylight: true,
                    ceiling: false,
                    logical_height: 384,
                    coordinate_scale: 1.0,
                    base_height: 64.0,
                    amplitude: 48.0,
                    frequency: 0.008,
                },
                material_palette: IntArray::new(vec![0, material]),
                biome_palette: IntArray::new(vec![biome_id]),
                profile: Some(value::encode(&profile(biome)).unwrap()),
                unsupported_reason: None,
            },
        )
    })
    .collect();
    Document {
        format_version: FORMAT_VERSION,
        generation_version: GENERATION_VERSION,
        minecraft_version: "26.3".into(),
        data_version: 5000,
        exporter: "Retina codec test".into(),
        seed: i64::MIN + 17,
        generate_structures: true,
        bonus_chest: false,
        datapacks: vec![Datapack {
            id: "vanilla".into(),
            digest: None,
        }],
        shared_types: SharedTypes {
            materials: ["minecraft:air", "minecraft:stone", "minecraft:netherrack"]
                .into_iter()
                .map(|id| BlockState {
                    id: id.into(),
                    properties: BTreeMap::new(),
                })
                .collect(),
            biomes: vec!["minecraft:plains".into(), "minecraft:nether_wastes".into()],
        },
        dimensions,
    }
}

pub(super) fn gzip(document: &Document) -> Vec<u8> {
    let mut encoder = GzEncoder::new(Vec::new(), Compression::best());
    encoder
        .write_all(&fastnbt::to_bytes(document).unwrap())
        .unwrap();
    encoder.finish().unwrap()
}

fn load(document: &Document) -> WorldDefinition {
    WorldDefinition::from_gzip(gzip(document).as_slice()).unwrap()
}

fn error(document: &Document) -> String {
    WorldDefinition::from_gzip(gzip(document).as_slice())
        .err()
        .expect("invalid definition accepted")
}

#[test]
fn shared_world_file_preserves_dimension_palettes_seed_and_native_profiles() {
    let doc = document();
    let bytes = gzip(&doc);
    let world = WorldDefinition::from_gzip(bytes.as_slice()).unwrap();
    assert_eq!(world.seed(), i64::MIN + 17);
    assert_eq!(world.data_version(), doc.data_version);
    assert_eq!(world.dimensions().len(), 2);
    assert!(world.source().generate_structures);
    assert!(!world.source().bonus_chest);
    for (id, source) in &doc.dimensions {
        let loaded = world.dimension(id).unwrap().profile().unwrap();
        let mut previous = value::decode(source.profile.clone().unwrap()).unwrap();
        previous["materials"] = Value::Array(
            source
                .material_palette
                .iter()
                .map(|&id| Value::String(doc.shared_types.materials[id as usize].id.clone()))
                .collect(),
        );
        let previous = WorldProfile::parse(&serde_json::to_vec(&previous).unwrap()).unwrap();
        assert_eq!(loaded.gpu_bytes(), previous.gpu_bytes());
        assert_eq!(loaded.climate_gpu_bytes(), previous.climate_gpu_bytes());
        assert_eq!(loaded.material_nbt, previous.material_nbt);
        assert_eq!(
            loaded.materials[1]["id"],
            if id == "minecraft:overworld" {
                "minecraft:stone"
            } else {
                "minecraft:netherrack"
            }
        );
        let request = world.request(id, 9, -33, 64).unwrap();
        assert_eq!(request.seed, world.seed() as u64);
        assert_eq!(
            (
                request.chunk_x,
                request.chunk_z,
                request.min_y,
                request.height
            ),
            (-33, 64, -64, 384)
        );
        let mut blocks = vec![0; request.block_count()];
        loaded.flat.as_ref().unwrap().assemble(&mut blocks);
        assert!(blocks[..512].iter().all(|&id| id == 1));
        assert!(blocks[512..].iter().all(|&id| id == 0));
    }
    let reopened = WorldDefinition::from_gzip(bytes.as_slice()).unwrap();
    assert_eq!(world.fingerprint(), reopened.fingerprint());
    assert_eq!(
        world
            .dimension("minecraft:overworld")
            .unwrap()
            .profile()
            .unwrap()
            .encoded,
        reopened
            .dimension("minecraft:overworld")
            .unwrap()
            .profile()
            .unwrap()
            .encoded
    );
    assert_ne!(
        world
            .dimension("minecraft:overworld")
            .unwrap()
            .profile()
            .unwrap()
            .encoded,
        world
            .dimension("minecraft:the_nether")
            .unwrap()
            .profile()
            .unwrap()
            .encoded
    );
}

#[test]
fn structural_codec_preserves_mixed_nullable_numeric_and_unicode_data() {
    let original = json!({
        "bytes":[0,127,128,255],"ints":[i32::MIN,0,i32::MAX],
        "longs":[i64::MIN,9007199254740993_i64,i64::MAX],
        "double":[-0.0,f64::MIN_POSITIVE,1.0/3.0],
        "bool":[true,false],"strings":["\u{0}你好🦊","v"],
        "mixed":[null,true,-1,9007199254740993_i64,"marker",{"v":null},[1,"two"]],
        "empty":[],"compound":{},"nullable":[null,{"field":false},null]
    });
    let wire = value::encode(&original).unwrap();
    let bytes = fastnbt::to_bytes(&wire).unwrap();
    let decoded = value::decode(fastnbt::from_bytes(&bytes).unwrap()).unwrap();
    assert_eq!(decoded, original);
    assert_eq!(
        decoded["double"][0].as_f64().unwrap().to_bits(),
        (-0.0_f64).to_bits()
    );
    let Nbt::Compound(fields) = wire else {
        panic!()
    };
    assert!(matches!(fields["bytes"], Nbt::ByteArray(_)));
    assert!(matches!(fields["ints"], Nbt::IntArray(_)));
    assert!(matches!(fields["longs"], Nbt::LongArray(_)));
}

#[test]
fn typed_template_nbt_survives_native_validation_and_compilation() {
    let tag = json!([10,{
        "name":[8,"marker 🦊\u{0}"],"seed":[4,i64::MAX],
        "bytes":[7,[-128,127]],"ints":[11,[i32::MIN,i32::MAX]],
        "longs":[12,[i64::MIN,i64::MAX]],"float":[5,1.125],"double":[6,1.0/3.0],
        "list":[9,[8,[[8,"first"],[8,"second"]]]]
    }]);
    let mut doc = document();
    let source = doc.dimensions.get_mut("minecraft:overworld").unwrap();
    let mut data = value::decode(source.profile.take().unwrap()).unwrap();
    data["structures"] = json!({"templates":[{
        "id":"test:marker","size":[1,1,1],"ground":0,"palettes":[[[1,1,1,1]]],
        "blocks":[0,0,0,0],"tags":{"0":tag},"joints":[],"entities":[]
    }],"definitions":[],"pools":{},"sets":[]});
    source.profile = Some(value::encode(&data).unwrap());
    let world = load(&doc);
    let actual = &world
        .dimension("minecraft:overworld")
        .unwrap()
        .profile()
        .unwrap()
        .structures
        .templates[0]
        .tags[&0];
    assert_eq!(actual, &tag);
    assert_eq!(crate::nbt::root(actual), crate::nbt::root(&tag));
}

#[test]
fn optional_end_profiles_load_without_altering_end_execution() {
    let mut doc = document();
    let source = doc.dimensions.get_mut("minecraft:overworld").unwrap();
    let mut data = value::decode(source.profile.take().unwrap()).unwrap();
    data.as_object_mut().unwrap().remove("flat");
    data["end"] = json!({"material":1,"biomes":[0,0,0,0,0]});
    source.profile = Some(value::encode(&data).unwrap());
    source.parameters.generator = "end".into();
    let world = load(&doc);
    let loaded = world
        .dimension("minecraft:overworld")
        .unwrap()
        .profile()
        .unwrap();
    assert_eq!(loaded.end.as_ref().unwrap().material, 1);
    assert_eq!(loaded.end.as_ref().unwrap().biomes, [0; 5]);
    assert!(loaded.flat.is_none());
}

#[test]
fn unsupported_dimensions_are_explicit_and_do_not_disable_other_dimensions() {
    let mut doc = document();
    let mut unsupported = doc.dimensions["minecraft:overworld"].clone();
    unsupported.profile = None;
    unsupported.unsupported_reason = Some("custom generator requires executable mod code".into());
    doc.dimensions.insert("test:custom".into(), unsupported);
    let world = load(&doc);
    assert!(
        world
            .dimension("minecraft:overworld")
            .unwrap()
            .profile()
            .is_ok()
    );
    assert!(
        world
            .dimension("test:custom")
            .unwrap()
            .profile()
            .err()
            .unwrap()
            .contains("custom generator")
    );
    assert!(world.request("test:custom", 1, 0, 0).is_err());
    assert!(world.request("test:missing", 1, 0, 0).is_err());
    assert!(world.request("minecraft:overworld", 0, 0, 0).is_err());
}

#[test]
fn loader_reports_actual_format_reference_and_generation_errors() {
    let mut doc = document();
    doc.format_version += 1;
    assert!(error(&doc).contains("unsupported world definition format"));
    let mut doc = document();
    doc.generation_version += 1;
    assert!(error(&doc).contains("unsupported generation semantics"));
    let mut doc = document();
    doc.dimensions
        .get_mut("minecraft:overworld")
        .unwrap()
        .material_palette = IntArray::new(vec![0, 99]);
    assert!(error(&doc).contains("dimension minecraft:overworld: unknown shared material 99"));
    let mut doc = document();
    doc.dimensions
        .get_mut("minecraft:overworld")
        .unwrap()
        .biome_palette = IntArray::new(vec![1]);
    assert!(error(&doc).contains("biome palette differs"));
    let mut doc = document();
    doc.dimensions
        .get_mut("minecraft:overworld")
        .unwrap()
        .parameters
        .sea_level = 9;
    assert!(error(&doc).contains("sea level differs"));
    let mut doc = document();
    doc.dimensions
        .get_mut("minecraft:overworld")
        .unwrap()
        .parameters
        .height = -1;
    assert!(error(&doc).contains("negative dimension height"));
    let mut doc = document();
    doc.dimensions
        .get_mut("minecraft:overworld")
        .unwrap()
        .unsupported_reason = Some("ambiguous".into());
    assert!(error(&doc).contains("either a profile or an unsupported reason"));
}

#[test]
fn malformed_profile_values_and_runtime_settings_are_rejected_during_decode() {
    assert!(value::decode(Nbt::Byte(2)).unwrap_err().contains("boolean"));
    assert!(
        value::decode(Nbt::Double(f64::NAN))
            .unwrap_err()
            .contains("non-finite")
    );
    assert!(value::decode(Nbt::List(vec![Nbt::Compound(Default::default())])).is_err());
    let mut doc = document();
    let source = doc.dimensions.get_mut("minecraft:overworld").unwrap();
    let mut data = value::decode(source.profile.take().unwrap()).unwrap();
    data["mca_compression"] = "zlib".into();
    source.profile = Some(value::encode(&data).unwrap());
    assert!(error(&doc).contains("runtime compression"));
}

#[test]
fn read_uses_the_published_file_and_preserves_it_after_errors() {
    use std::sync::atomic::{AtomicU64, Ordering};
    static NEXT: AtomicU64 = AtomicU64::new(0);
    let dir = std::env::temp_dir().join(format!(
        "retina-definition-{}-{}",
        std::process::id(),
        NEXT.fetch_add(1, Ordering::Relaxed)
    ));
    std::fs::create_dir(&dir).unwrap();
    let path = dir.join("retina.dat");
    assert!(
        WorldDefinition::read(&path)
            .err()
            .unwrap()
            .contains("retina.dat")
    );
    let bytes = gzip(&document());
    std::fs::write(&path, &bytes).unwrap();
    assert_eq!(WorldDefinition::read(&path).unwrap().dimensions().len(), 2);
    let truncated = &bytes[..bytes.len() - 8];
    std::fs::write(&path, truncated).unwrap();
    assert!(WorldDefinition::read(&path).err().unwrap().contains("gzip"));
    assert_eq!(std::fs::read(&path).unwrap(), truncated);
    std::fs::remove_dir_all(dir).unwrap();
}

#[test]
fn world_profiles_retire_after_the_last_owner_and_active_job_release_them() {
    use super::residency::Profiles;
    use crate::region::codec::Compression;
    use std::sync::Arc;
    let world = load(&document());
    let profile = world
        .dimension("minecraft:overworld")
        .unwrap()
        .profile()
        .unwrap()
        .clone();
    let lifetime = Arc::downgrade(&profile);
    let mut profiles = Profiles::default();
    let id = profiles
        .world(&profile, Compression::Lz4, world.data_version(), world.dimension("minecraft:overworld").unwrap().parameters().storage_bounds())
        .unwrap();
    assert_eq!(profiles.compression(id).unwrap(), Compression::Lz4);
    assert_eq!(
        profiles.data_version(id).unwrap(),
        Some(world.data_version())
    );
    assert_eq!(
        profile.mca_compression,
        Compression::Zlib,
        "execution settings do not mutate the saved profile"
    );
    let active_job = profiles.get(id).unwrap().unwrap();
    drop(profile);
    drop(world);
    assert!(
        profiles.get(id).unwrap().is_some(),
        "an active job retains its data"
    );
    drop(active_job);
    assert!(lifetime.upgrade().is_none());
    assert!(profiles.get(id).err().unwrap().contains("closed"));
    let replacement = load(&document());
    let next = profiles
        .world(
            replacement
                .dimension("minecraft:overworld")
                .unwrap()
                .profile()
                .unwrap(),
            Compression::Zlib,
            replacement.data_version(),
            replacement.dimension("minecraft:overworld").unwrap().parameters().storage_bounds(),
        )
        .unwrap();
    assert_ne!(
        id, next,
        "retired IDs cannot alias cached data for a reopened world"
    );
    assert!(profiles.get(id).is_err());
}

#[test]
#[ignore = "Generate Java fixtures with worldDefinitionCompileTest, then set RETINA_WORLD_DEFINITION_FIXTURES"]
fn java_compiled_definitions_preserve_complete_profiles() {
    let root = std::path::PathBuf::from(
        std::env::var("RETINA_WORLD_DEFINITION_FIXTURES")
            .expect("RETINA_WORLD_DEFINITION_FIXTURES must name the Java fixture directory"),
    );
    for case in ["vanilla", "flat", "datapack", "legacy", "procedural", "cropped", "cropped_registry"] {
        let directory = root.join(case);
        let start = std::time::Instant::now();
        let world = WorldDefinition::read(directory.join("retina.dat")).unwrap();
        let load_nanos = start.elapsed().as_nanos();
        let mut supported = 0;
        for (id, dimension) in world.dimensions() {
            if dimension.unsupported_reason().is_some() {
                continue;
            }
            let baseline_path =
                directory.join(format!("expected-{}.json", id.replace([':', '/'], "_")));
            let bytes = std::fs::read(baseline_path).unwrap();
            let actual = dimension.profile().unwrap();
            if actual.terrain_kind == crate::profile::TerrainKind::Procedural {
                let raw = world.source().dimensions[id].profile.clone().unwrap();
                assert_eq!(value::decode(raw).unwrap(), exact_json(&bytes));
                assert_eq!(actual.biomes.len(), 1);
                assert_eq!(actual.materials.len(), 2);
                supported += 1;
                continue;
            }
            let baseline = WorldProfile::parse(&bytes).unwrap();
            assert_eq!(
                actual.gpu_bytes(),
                baseline.gpu_bytes(),
                "{case}/{id}: GPU tables"
            );
            assert_eq!(
                actual.climate_gpu_bytes(),
                baseline.climate_gpu_bytes(),
                "{case}/{id}: climate"
            );
            assert_eq!(
                actual.material_nbt, baseline.material_nbt,
                "{case}/{id}: material NBT"
            );
            let mut expected = exact_json(&bytes);
            expected.as_object_mut().unwrap().remove("materials");
            expected.as_object_mut().unwrap().remove("mca_compression");
            let raw = world.source().dimensions[id].profile.clone().unwrap();
            let actual_data = value::decode(raw).unwrap();
            let metadata=world.adapter_metadata();
            let limitations=&metadata["dimensions"][id]["limitations"];
            for (field,report) in [("decoration_omissions","decorations"),("geology_omissions","geology"),
                                   ("simple_current_omissions","simple_block_survival")] {
                assert_eq!(&limitations[report],&expected[field],"{case}/{id}: saved {report} report");
            }
            assert_eq!(limitations["structures"],expected["structures"]["omissions"]);
            assert_eq!(limitations["approximations"],expected["registry_program"]["approximations"]);
            assert!(
                actual_data == expected,
                "{case}/{id}: complete structural data differs at {}",
                first_difference(&actual_data, &expected, "profile").unwrap()
            );
            if let (Some(actual), Some(baseline)) = (&actual.flat, &baseline.flat) {
                let request = world.request(id, 1, -33, 32).unwrap();
                let mut from_file = vec![0; request.block_count()];
                let mut from_registry = vec![0; request.block_count()];
                actual.assemble(&mut from_file);
                baseline.assemble(&mut from_registry);
                assert_eq!(from_file, from_registry, "{case}/{id}: flat terrain");
            }
            supported += 1;
        }
        assert!(
            supported >= 2,
            "each fixture must exercise multiple supported dimensions"
        );
        if case == "datapack" {
            assert!(
                world
                    .source()
                    .shared_types
                    .biomes
                    .iter()
                    .any(|id| id == "definition_test:empty")
            );
            assert_eq!(world.seed(), 9007199254740993);
        }
        let gpu_table_parity = (!matches!(case,"procedural"|"cropped")).then_some(true);
        let receipt = json!({"supported_dimensions":supported,"load_nanos":load_nanos,
            "gpu_table_parity":gpu_table_parity,"complete_data_parity":true,"gpu_initialized":false});
        std::fs::write(directory.join("rust-validation.json"), receipt.to_string()).unwrap();
        println!("world definition {case}: {receipt}");
    }
    let mut bytes = Vec::new();
    std::io::Read::read_to_end(
        &mut flate2::read::GzDecoder::new(
            std::fs::File::open(root.join("codec/retina.dat")).unwrap(),
        ),
        &mut bytes,
    )
    .unwrap();
    let mut root_tag: std::collections::HashMap<String, Nbt> = fastnbt::from_bytes(&bytes).unwrap();
    let actual = value::decode(root_tag.remove("values").unwrap()).unwrap();
    let expected = exact_json(&std::fs::read(root.join("codec/expected.json")).unwrap());
    assert_eq!(actual, expected, "Java primitive and mixed-list fidelity");
    assert_eq!(
        actual["floating"][0].as_f64().unwrap().to_bits(),
        (-0.0_f64).to_bits()
    );
}

// The legacy parser is still tested above for GPU/material parity. For an
// independent exact-data comparison, preserve number lexemes and use Rust's
// correctly-rounded f64 parser. serde_json's default fast decimal conversion
// can differ by one ULP from the Java values preserved in the binary file.
fn exact_json(bytes: &[u8]) -> Value {
    fn decode(raw: &serde_json::value::RawValue) -> Value {
        let text = raw.get().trim();
        match text.as_bytes()[0] {
            b'{' => {
                let fields: BTreeMap<String, &serde_json::value::RawValue> =
                    serde_json::from_str(text).unwrap();
                Value::Object(fields.into_iter().map(|(k, v)| (k, decode(v))).collect())
            }
            b'[' => {
                let values: Vec<&serde_json::value::RawValue> = serde_json::from_str(text).unwrap();
                Value::Array(values.into_iter().map(decode).collect())
            }
            b'-' | b'0'..=b'9' if text.contains(['.', 'e', 'E']) => {
                Value::Number(serde_json::Number::from_f64(text.parse().unwrap()).unwrap())
            }
            _ => serde_json::from_str(text).unwrap(),
        }
    }
    decode(serde_json::from_slice(bytes).unwrap())
}

fn first_difference(actual: &Value, expected: &Value, path: &str) -> Option<String> {
    if actual == expected {
        return None;
    }
    match (actual, expected) {
        (Value::Object(a), Value::Object(b)) if a.keys().eq(b.keys()) => a
            .iter()
            .find_map(|(key, a)| first_difference(a, &b[key], &format!("{path}.{key}"))),
        (Value::Array(a), Value::Array(b)) if a.len() == b.len() => a
            .iter()
            .zip(b)
            .enumerate()
            .find_map(|(i, (a, b))| first_difference(a, b, &format!("{path}[{i}]"))),
        _ => Some(path.into()),
    }
}

#[test]
fn exporter_reports_survive_file_loading_and_older_absence_stays_unknown() {
    let mut document=document();
    let world=WorldDefinition::from_gzip(gzip(&document).as_slice()).unwrap();
    assert_eq!(world.adapter_metadata()["dimensions"]["minecraft:overworld"]["limitations"],json!({}));
    let saved=document.dimensions.get_mut("minecraft:overworld").unwrap();
    let mut profile=value::decode(saved.profile.take().unwrap()).unwrap();
    profile["decoration_omissions"]=json!(["tree_decorator:beehive"]);
    profile["geology_omissions"]=json!([]);
    profile["simple_current_omissions"]=json!(["example:contextual_block"]);
    profile["structures"]=json!({"definitions":[],"templates":[],"pools":{},"sets":[],"omissions":["placement:minecraft:strongholds"]});
    saved.profile=Some(value::encode(&profile).unwrap());
    let world=WorldDefinition::from_gzip(gzip(&document).as_slice()).unwrap();
    assert_eq!(world.adapter_metadata()["dimensions"]["minecraft:overworld"]["limitations"],json!({
        "decorations":["tree_decorator:beehive"],"geology":[],"simple_block_survival":["example:contextual_block"],
        "structures":["placement:minecraft:strongholds"]}));
}
