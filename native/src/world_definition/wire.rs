//! Version 2 on-disk schema, with legacy version 1 storage defaults. All integers are signed NBT numbers; world seeds
//! retain their complete two's-complement 64-bit representation.
use fastnbt::{IntArray, Value};
use serde::{Deserialize, Serialize};
use std::collections::BTreeMap;

pub const FORMAT_VERSION: i32 = 2;
pub const GENERATION_VERSION: i32 = 1;

#[derive(Clone, Debug, Deserialize, Serialize)]
pub struct Document {
    pub format_version: i32,
    pub generation_version: i32,
    pub minecraft_version: String,
    pub data_version: i32,
    pub exporter: String,
    pub seed: i64,
    pub generate_structures: bool,
    pub bonus_chest: bool,
    pub datapacks: Vec<Datapack>,
    pub shared_types: SharedTypes,
    pub dimensions: BTreeMap<String, Dimension>,
}

#[derive(Clone, Debug, Deserialize, Serialize)]
pub struct Datapack {
    pub id: String,
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub digest: Option<String>,
}

#[derive(Clone, Debug, Deserialize, Serialize)]
pub struct SharedTypes {
    pub materials: Vec<BlockState>,
    pub biomes: Vec<String>,
}

#[derive(Clone, Debug, Deserialize, Serialize)]
pub struct BlockState {
    pub id: String,
    pub properties: BTreeMap<String, String>,
}

#[derive(Clone, Debug, Deserialize, Serialize)]
pub struct Dimension {
    pub parameters: Parameters,
    /// Local native material index -> shared material index. Never server IDs.
    pub material_palette: IntArray,
    /// Local native biome index -> shared biome index.
    pub biome_palette: IntArray,
    /// A structural NBT compound, using the value encoding in value.rs.
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub profile: Option<Value>,
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub unsupported_reason: Option<String>,
}

#[derive(Clone, Debug, Deserialize, Serialize)]
pub struct Parameters {
    pub generator: String,
    pub min_y: i32,
    pub height: i32,
    /// Dimension storage may extend beyond a legacy generator's native range.
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub storage: Option<Bounds>,
    pub generator_depth: i32,
    pub sea_level: i32,
    pub spawn_height: i32,
    pub skylight: bool,
    pub ceiling: bool,
    pub logical_height: i32,
    pub coordinate_scale: f64,
    /// Legacy procedural parameters remain explicit until their callers retire.
    pub base_height: f32,
    pub amplitude: f32,
    pub frequency: f32,
}

#[derive(Clone, Copy, Debug, Deserialize, Serialize, PartialEq, Eq)]
pub struct Bounds {
    pub min_y: i32,
    pub height: i32,
}

impl Parameters {
    pub fn storage_bounds(&self) -> Bounds {
        self.storage.unwrap_or(Bounds {
            min_y: self.min_y,
            height: self.height,
        })
    }
}
