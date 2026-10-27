//! Saved form of the original fixed-biome air/stone terrain kernel. Its complete
//! rules are the generation-semantics version plus saved request parameters.
use super::{TerrainKind, WorldProfile};
use serde::Deserialize;
use serde_json::{Value, json};

#[derive(Deserialize)]
#[serde(deny_unknown_fields)]
struct Definition {
    terrain_kind: TerrainKind,
    sea_level: i32,
    biomes: Vec<Biome>,
    materials: Vec<Value>,
    // Informational source identity, also present in registry profiles.
    #[serde(default)]
    dimension: Option<String>,
}
#[derive(Deserialize)]
#[serde(deny_unknown_fields)]
struct Biome {
    id: String,
}

pub(super) fn decode(value: Value) -> Result<WorldProfile, String> {
    let input: Definition =
        serde_json::from_value(value).map_err(|error| format!("procedural definition: {error}"))?;
    if input.terrain_kind != TerrainKind::Procedural || input.biomes.len() != 1 {
        return Err("procedural terrain requires exactly one fixed biome".into());
    }
    if input.materials
        != json!([{"id":"minecraft:air"},{"id":"minecraft:stone"}])
            .as_array()
            .unwrap()
            .as_slice()
    {
        return Err("procedural terrain requires the air/stone material palette".into());
    }
    let _ = input.dimension;
    // These fields provide ordinary adapter palettes and ownership. They are
    // never dispatched as a registry program: the engine selects its existing
    // procedural kernel for this explicit loaded terrain kind.
    let noise = json!({"frequency":0.001,"amplitude":0,"modifiers":[0]});
    serde_json::from_value(json!({
        "terrain_kind":"procedural","biome_scale":128,"blend":0.55,"sea_level":input.sea_level,
        "stone":1,"water":0,"bedrock":1,"deepslate":1,"snow":1,"ice":0,
        "materials":input.materials,"material_flags":[0,1],"heightmap_masks":[0,63],
        "biomes":[{"id":input.biomes[0].id,"climate":[0,0,0,0],"terrain":[0,0,0],
            "top":1,"filler":1,"underwater":1,"flags":0}],
        "noises":[noise,noise,noise,noise]
    }))
    .map_err(|error| format!("procedural profile: {error}"))
}
