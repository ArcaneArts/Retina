//! Persistent, seed-bound generation input for every dimension in one world.
//!
//! This module loads data only: it never creates a GPU device, reads datapacks,
//! or calls Minecraft. Both host adapters and the headless runner use it.
mod adapter;
mod ffi;
pub(crate) mod residency;
pub mod runtime;
mod value;
pub mod wire;

use crate::{ChunkRequest, profile::WorldProfile};
use flate2::read::GzDecoder;
use sha2::{Digest, Sha256};
use std::{collections::BTreeMap, fs::File, io::Read, path::Path, sync::Arc};

pub struct WorldDefinition {
    document: wire::Document,
    dimensions: BTreeMap<String, Dimension>,
    fingerprint: [u8; 32],
}

pub struct Dimension {
    parameters: wire::Parameters,
    profile: Option<Arc<WorldProfile>>,
    unsupported_reason: Option<String>,
}

impl WorldDefinition {
    pub fn read(path: impl AsRef<Path>) -> Result<Self, String> {
        let path = path.as_ref();
        let file = File::open(path).map_err(|e| format!("{}: {e}", path.display()))?;
        Self::from_gzip(file).map_err(|e| format!("{}: {e}", path.display()))
    }

    /// Absence is the only condition that permits first-time compilation.
    /// Permission failures and malformed existing files remain actual errors.
    pub fn read_if_present(path: impl AsRef<Path>) -> Result<Option<Self>, String> {
        let path = path.as_ref();
        let file = match File::open(path) {
            Ok(file) => file,
            Err(error) if error.kind() == std::io::ErrorKind::NotFound => return Ok(None),
            Err(error) => return Err(format!("{}: {error}", path.display())),
        };
        Self::from_gzip(file)
            .map(Some)
            .map_err(|error| format!("{}: {error}", path.display()))
    }

    pub fn from_gzip(reader: impl Read) -> Result<Self, String> {
        let mut bytes = Vec::new();
        GzDecoder::new(reader)
            .read_to_end(&mut bytes)
            .map_err(|e| format!("world definition gzip: {e}"))?;
        let document: wire::Document =
            fastnbt::from_bytes(&bytes).map_err(|e| format!("world definition NBT: {e}"))?;
        Self::from_document(document, Sha256::digest(&bytes).into())
    }

    fn from_document(document: wire::Document, fingerprint: [u8; 32]) -> Result<Self, String> {
        if !(1..=wire::FORMAT_VERSION).contains(&document.format_version) {
            return Err(format!(
                "unsupported world definition format {} (reader supports 1 through {})",
                document.format_version,
                wire::FORMAT_VERSION
            ));
        }
        if document.generation_version != wire::GENERATION_VERSION {
            return Err(format!(
                "unsupported generation semantics {} (engine supports {})",
                document.generation_version,
                wire::GENERATION_VERSION
            ));
        }
        if document.data_version <= 0
            || document.minecraft_version.is_empty()
            || document.exporter.is_empty()
            || document.dimensions.is_empty()
        {
            return Err(
                "world definition requires source versions and dimension definitions".into(),
            );
        }
        for state in &document.shared_types.materials {
            if !identifier(&state.id)
                || state
                    .properties
                    .iter()
                    .any(|(k, v)| k.is_empty() || v.is_empty())
            {
                return Err(format!("invalid shared block state {}", state.id));
            }
        }
        if document
            .shared_types
            .biomes
            .iter()
            .any(|id| !identifier(id))
        {
            return Err("invalid shared biome identifier".into());
        }
        let mut dimensions = BTreeMap::new();
        for (key, source) in &document.dimensions {
            if !identifier(key) {
                return Err(format!("invalid dimension identifier {key}"));
            }
            let mut identity = Sha256::new();
            identity.update(fingerprint);
            identity.update(key.as_bytes());
            let dimension = Dimension::load(&document, source, identity.finalize().to_vec())
                .map_err(|e| format!("dimension {key}: {e}"))?;
            dimensions.insert(key.clone(), dimension);
        }
        Ok(Self {
            document,
            dimensions,
            fingerprint,
        })
    }

    pub fn seed(&self) -> i64 {
        self.document.seed
    }
    pub fn data_version(&self) -> i32 {
        self.document.data_version
    }
    pub fn source(&self) -> &wire::Document {
        &self.document
    }
    pub fn fingerprint(&self) -> &[u8; 32] {
        &self.fingerprint
    }
    pub fn dimensions(&self) -> &BTreeMap<String, Dimension> {
        &self.dimensions
    }
    pub fn dimension(&self, key: &str) -> Result<&Dimension, String> {
        self.dimensions
            .get(key)
            .ok_or_else(|| format!("world definition has no dimension {key}"))
    }

    /// All generation settings originate here; a host supplies only coordinates
    /// and the engine's resident profile handle after registering this profile.
    pub fn request(
        &self,
        dimension: &str,
        profile: u32,
        x: i32,
        z: i32,
    ) -> Result<ChunkRequest, String> {
        let dimension = self.dimension(dimension)?;
        dimension.profile()?;
        if profile == 0 {
            return Err("saved definitions require a resident profile handle".into());
        }
        let p = dimension.parameters();
        let request = ChunkRequest {
            seed: self.seed() as u64,
            chunk_x: x,
            chunk_z: z,
            min_y: p.min_y,
            height: p.height as u32,
            base_height: p.base_height,
            amplitude: p.amplitude,
            frequency: p.frequency,
            reserved: profile,
        };
        request.validate()?;
        Ok(request)
    }
}

impl Dimension {
    fn load(
        world: &wire::Document,
        dimension: &wire::Dimension,
        identity: Vec<u8>,
    ) -> Result<Self, String> {
        let profile = match (&dimension.profile, &dimension.unsupported_reason) {
            (None, Some(reason)) if !reason.is_empty() => None,
            (Some(profile), None) => Some(Arc::new(load_profile(
                world,
                dimension,
                profile.clone(),
                identity,
            )?)),
            _ => return Err("dimension requires either a profile or an unsupported reason".into()),
        };
        if profile.is_some() {
            let p = &dimension.parameters;
            if world.format_version >= 2 && p.storage.is_none() {
                return Err("format 2 requires explicit dimension storage bounds".into());
            }
            let height = u32::try_from(p.height).map_err(|_| "negative dimension height")?;
            ChunkRequest {
                seed: world.seed as u64,
                chunk_x: 0,
                chunk_z: 0,
                min_y: p.min_y,
                height,
                base_height: p.base_height,
                amplitude: p.amplitude,
                frequency: p.frequency,
                reserved: 1,
            }
            .validate()?;
            let storage = p.storage_bounds();
            ChunkRequest { min_y:storage.min_y,
                height:u32::try_from(storage.height).map_err(|_| "negative storage height")?,
                seed:0,chunk_x:0,chunk_z:0,base_height:0.,amplitude:0.,frequency:1.,reserved:1 }.validate()?;
            if storage.min_y % 16 != 0 || storage.height % 16 != 0
                || storage.min_y / 16 < -128 || (storage.min_y + storage.height - 1) / 16 > 127 {
                return Err("storage bounds require aligned MCA sections with signed-byte Y".into());
            }
            if p.generator.is_empty()
                || p.generator_depth <= 0
                || p.logical_height <= 0
                || !p.coordinate_scale.is_finite()
                || p.coordinate_scale <= 0.0
            {
                return Err("invalid dimension parameters".into());
            }
        }
        Ok(Self {
            parameters: dimension.parameters.clone(),
            profile,
            unsupported_reason: dimension.unsupported_reason.clone(),
        })
    }

    pub fn parameters(&self) -> &wire::Parameters {
        &self.parameters
    }
    pub fn unsupported_reason(&self) -> Option<&str> {
        self.unsupported_reason.as_deref()
    }
    pub fn profile(&self) -> Result<&Arc<WorldProfile>, String> {
        self.profile.as_ref().ok_or_else(|| {
            format!(
                "unsupported generation: {}",
                self.unsupported_reason
                    .as_deref()
                    .unwrap_or("missing profile")
            )
        })
    }
}

fn load_profile(
    world: &wire::Document,
    dimension: &wire::Dimension,
    raw: fastnbt::Value,
    identity: Vec<u8>,
) -> Result<WorldProfile, String> {
    let mut data = value::decode(raw)?;
    let object = data
        .as_object_mut()
        .ok_or("profile must be an NBT compound")?;
    if object.contains_key("materials") || object.contains_key("mca_compression") {
        return Err(
            "profile must use the shared material palette and omit runtime compression".into(),
        );
    }
    if world.format_version >= 2 && object.contains_key("program_execution") {
        return Err("program execution is a binding option, not a saved generation rule".into());
    }
    let materials = dimension
        .material_palette
        .iter()
        .map(|&id| {
            usize::try_from(id)
                .ok()
                .and_then(|id| world.shared_types.materials.get(id))
                .map(value::material)
                .ok_or_else(|| format!("unknown shared material {id}"))
        })
        .collect::<Result<Vec<_>, _>>()?;
    object.insert("materials".into(), materials.into());
    let biomes = object
        .get("biomes")
        .and_then(serde_json::Value::as_array)
        .ok_or("profile requires biome definitions")?;
    if biomes.len() != dimension.biome_palette.len() {
        return Err("biome palette must cover all local biome definitions".into());
    }
    for (&id, biome) in dimension.biome_palette.iter().zip(biomes) {
        let expected = usize::try_from(id)
            .ok()
            .and_then(|id| world.shared_types.biomes.get(id))
            .ok_or_else(|| format!("unknown shared biome {id}"))?;
        if biome.get("id").and_then(serde_json::Value::as_str) != Some(expected.as_str()) {
            return Err(format!(
                "biome palette differs from profile at shared biome {id}"
            ));
        }
    }
    let profile = WorldProfile::from_definition(data, identity)?;
    if profile.sea_level != dimension.parameters.sea_level {
        return Err("profile sea level differs from dimension parameters".into());
    }
    Ok(profile)
}

fn identifier(id: &str) -> bool {
    let Some((namespace, path)) = id.split_once(':') else {
        return false;
    };
    !namespace.is_empty()
        && !path.is_empty()
        && namespace
            .bytes()
            .all(|c| c.is_ascii_lowercase() || c.is_ascii_digit() || b"_.-".contains(&c))
        && path
            .bytes()
            .all(|c| c.is_ascii_lowercase() || c.is_ascii_digit() || b"/_.-".contains(&c))
}

#[cfg(test)]
mod tests;
#[cfg(test)]
mod procedural_tests;
#[cfg(test)]
mod embedded_nbt_tests;
