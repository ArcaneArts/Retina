use super::{
    WorldDefinition,
    tests::{document, gzip},
    value,
};
use serde_json::json;

fn source() -> super::wire::Document {
    let mut document = document();
    let dimension = document.dimensions.get_mut("minecraft:overworld").unwrap();
    dimension.parameters.sea_level = dimension.parameters.min_y;
    dimension.profile = Some(
        value::encode(&json!({"terrain_kind":"procedural","sea_level":-64,
        "biomes":[{"id":"minecraft:plains"}]}))
        .unwrap(),
    );
    document
}

#[test]
fn procedural_rules_and_palettes_load_through_the_world_reader() {
    let document = source();
    let world = WorldDefinition::from_gzip(gzip(&document).as_slice()).unwrap();
    let dimension = world.dimension("minecraft:overworld").unwrap();
    assert!(dimension.profile().unwrap().terrain_kind == crate::profile::TerrainKind::Procedural);
    let request = world.request("minecraft:overworld", 7, -33, 19).unwrap();
    assert_eq!(
        (request.seed, request.min_y, request.height),
        (document.seed as u64, -64, 384)
    );
    let metadata = world.adapter_metadata();
    assert_eq!(
        metadata["dimensions"]["minecraft:overworld"]["profile_metadata"]["terrain_kind"],
        "procedural"
    );
}

#[test]
fn procedural_definitions_reject_rules_the_kernel_cannot_execute() {
    let mut document = source();
    let original = document.dimensions["minecraft:overworld"]
        .profile
        .clone()
        .unwrap();
    let mut mixed = value::decode(original.clone()).unwrap();
    mixed["decorations"] = json!([]);
    document
        .dimensions
        .get_mut("minecraft:overworld")
        .unwrap()
        .profile = Some(value::encode(&mixed).unwrap());
    assert!(
        WorldDefinition::from_gzip(gzip(&document).as_slice())
            .err()
            .unwrap()
            .contains("unknown field")
    );
    document
        .dimensions
        .get_mut("minecraft:overworld")
        .unwrap()
        .profile = Some(original);
    document.shared_types.materials[1].id = "minecraft:dirt".into();
    assert!(
        WorldDefinition::from_gzip(gzip(&document).as_slice())
            .err()
            .unwrap()
            .contains("air/stone")
    );
}

#[test]
fn legacy_format_defaults_storage_and_current_format_requires_it() {
    let mut document = source();
    for dimension in document.dimensions.values_mut() {
        dimension.parameters.storage = None;
    }
    assert!(
        WorldDefinition::from_gzip(gzip(&document).as_slice())
            .err()
            .unwrap()
            .contains("explicit dimension storage")
    );
    document.format_version = 1;
    let world = WorldDefinition::from_gzip(gzip(&document).as_slice()).unwrap();
    let params = world.dimension("minecraft:overworld").unwrap().parameters();
    assert_eq!(
        params.storage_bounds(),
        super::wire::Bounds {
            min_y: params.min_y,
            height: params.height
        }
    );
}
