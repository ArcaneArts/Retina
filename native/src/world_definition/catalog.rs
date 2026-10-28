//! Reusable registry catalogs materialize ordinary seed-bound files. Generation
//! still opens the published file through WorldDefinition, never through a catalog.
use super::WorldDefinition;
use fastnbt::{IntArray, Value};
use flate2::read::GzDecoder;
#[cfg(test)]
use flate2::{Compression, write::GzEncoder};
use serde::{
    Deserialize, Serialize,
    ser::{SerializeMap, SerializeSeq},
};
use std::{
    collections::{BTreeMap, HashMap},
    fs::{self, File, OpenOptions},
    io::{Read, Write},
    path::{Path, PathBuf},
    sync::atomic::{AtomicU64, Ordering},
};

#[derive(Clone, Deserialize, Serialize)]
#[serde(deny_unknown_fields)]
pub struct WorldCatalog {
    pub catalog_version: i32,
    pub materialization_version: i32,
    pub variants: BTreeMap<String, Variant>,
}

#[derive(Clone, Deserialize, Serialize)]
#[serde(deny_unknown_fields)]
pub struct Variant {
    pub template: Value,
    pub seed_derivations: BTreeMap<String, BandsRecipe>,
}

#[derive(Clone, Deserialize, Serialize)]
#[serde(deny_unknown_fields)]
pub struct BandsRecipe {
    pub algorithm: String,
    pub materials: IntArray,
}

type Compound = HashMap<String, Value>;
const BAND_NAMES: [&str; 7] = [
    "minecraft:terracotta",
    "minecraft:orange_terracotta",
    "minecraft:yellow_terracotta",
    "minecraft:brown_terracotta",
    "minecraft:red_terracotta",
    "minecraft:white_terracotta",
    "minecraft:light_gray_terracotta",
];

fn compound(value: &Value) -> Result<&Compound, String> {
    match value {
        Value::Compound(fields) => Ok(fields),
        _ => Err("catalog expected NBT compound".into()),
    }
}
fn compound_mut(value: &mut Value) -> Result<&mut Compound, String> {
    match value {
        Value::Compound(fields) => Ok(fields),
        _ => Err("catalog expected NBT compound".into()),
    }
}
fn required<'a>(fields: &'a Compound, name: &str) -> Result<&'a Value, String> {
    fields
        .get(name)
        .ok_or_else(|| format!("catalog is missing {name}"))
}
fn required_mut<'a>(fields: &'a mut Compound, name: &str) -> Result<&'a mut Value, String> {
    fields
        .get_mut(name)
        .ok_or_else(|| format!("catalog is missing {name}"))
}

impl WorldCatalog {
    pub fn read(path: impl AsRef<Path>) -> Result<Self, String> {
        encoding::read(path.as_ref(), None)
    }

    /// Runtime selection avoids decoding unrelated V2 variant payloads.
    pub fn read_selected(path: impl AsRef<Path>, variant: &str) -> Result<Self, String> {
        encoding::read(path.as_ref(), Some(variant))
    }

    /// Returns complete validated gzip NBT. The caller still publishes/reopens it.
    pub fn materialize(&self, variant: &str, seed: i64) -> Result<Vec<u8>, String> {
        self.validate_versions()?;
        let selected = self
            .variants
            .get(variant)
            .ok_or_else(|| format!("catalog has no variant {variant}"))?
            .clone();
        Self::materialize_selected(selected, seed)
    }

    /// Consume a runtime selection so its potentially large template is not copied.
    pub fn materialize_owned(mut self, variant: &str, seed: i64) -> Result<Vec<u8>, String> {
        self.validate_versions()?;
        let selected = self
            .variants
            .remove(variant)
            .ok_or_else(|| format!("catalog has no variant {variant}"))?;
        drop(self);
        Self::materialize_selected(selected, seed)
    }

    fn validate_versions(&self) -> Result<(), String> {
        if self.catalog_version != 1 || self.materialization_version != 1 {
            return Err(format!(
                "unsupported catalog/materialization versions {}/{} (supported 1/1)",
                self.catalog_version, self.materialization_version
            ));
        }
        Ok(())
    }

    fn materialize_selected(selected: Variant, seed: i64) -> Result<Vec<u8>, String> {
        let mut template = selected.template;
        let root = compound_mut(&mut template)?;
        if root.contains_key("seed") {
            return Err("catalog template contains a captured world seed".into());
        }
        root.insert("seed".into(), Value::Long(seed));
        let shared = compound(required(root, "shared_types")?)?;
        let Value::List(states) = required(shared, "materials")? else {
            return Err("catalog materials must be a list".into());
        };
        let states = states.clone();
        let dimensions = compound_mut(required_mut(root, "dimensions")?)?;
        for (key, recipe) in &selected.seed_derivations {
            if recipe.algorithm != "retina:badlands_bands/1" {
                return Err(format!(
                    "unsupported seed derivation {} in {key}",
                    recipe.algorithm
                ));
            }
            if recipe.materials.len() != BAND_NAMES.len() {
                return Err(format!("badlands recipe needs seven materials in {key}"));
            }
            let dimension = compound_mut(required_mut(dimensions, key)?)?;
            let Value::IntArray(palette) = required(dimension, "material_palette")? else {
                return Err("catalog palette must be an int array".into());
            };
            for (&local, expected) in recipe.materials.iter().zip(BAND_NAMES) {
                let shared = usize::try_from(local)
                    .ok()
                    .and_then(|id| palette.get(id))
                    .and_then(|&id| usize::try_from(id).ok())
                    .and_then(|id| states.get(id))
                    .ok_or_else(|| format!("unknown catalog band material {local} in {key}"))?;
                let state = compound(shared)?;
                if required(state, "id")? != &Value::String(expected.into())
                    || !compound(required(state, "properties")?)?.is_empty()
                {
                    return Err(format!(
                        "catalog band material {local} must resolve to {expected} in {key}"
                    ));
                }
            }
            let profile = compound_mut(required_mut(dimension, "profile")?)?;
            let terrain = compound_mut(required_mut(profile, "terrain_features")?)?;
            if terrain.contains_key("bands") {
                return Err(format!("catalog contains captured bands in {key}"));
            }
            terrain.insert(
                "bands".into(),
                Value::IntArray(IntArray::new(bands(seed, &recipe.materials))),
            );
        }
        for (key, dimension) in dimensions.iter() {
            let dimension = compound(dimension)?;
            if let Some(Value::Compound(profile)) = dimension.get("profile") {
                if let Some(Value::Compound(terrain)) = profile.get("terrain_features") {
                    if !terrain.contains_key("bands") {
                        return Err(format!("catalog lacks band derivation in {key}"));
                    }
                }
            }
        }
        let raw = fastnbt::to_bytes(&Canonical(&template))
            .map_err(|e| format!("materialized NBT: {e}"))?;
        drop(template);
        let mut compressor = libdeflater::Compressor::new(libdeflater::CompressionLvl::default());
        let mut bytes = vec![0; compressor.gzip_compress_bound(raw.len())];
        let size = compressor
            .gzip_compress(&raw, &mut bytes)
            .map_err(|e| e.to_string())?;
        bytes.truncate(size);
        drop(raw);
        drop(compressor);
        // Validate the actual bytes with the sole production world loader.
        WorldDefinition::from_gzip(bytes.as_slice())?;
        Ok(bytes)
    }

    /// Atomic no-replace publication in the destination filesystem. A present
    /// destination, including a malformed one, fails the actual hard-link operation.
    pub fn publish(
        &self,
        variant: &str,
        seed: i64,
        world_root: impl AsRef<Path>,
    ) -> Result<PathBuf, String> {
        let bytes = self.materialize(variant, seed)?;
        Self::publish_bytes(bytes, world_root.as_ref())
    }

    pub fn publish_owned(
        self,
        variant: &str,
        seed: i64,
        world_root: impl AsRef<Path>,
    ) -> Result<PathBuf, String> {
        let bytes = self.materialize_owned(variant, seed)?;
        Self::publish_bytes(bytes, world_root.as_ref())
    }

    fn publish_bytes(bytes: Vec<u8>, root: &Path) -> Result<PathBuf, String> {
        fs::create_dir_all(root).map_err(|e| format!("{}: {e}", root.display()))?;
        let destination = root.join("retina.dat");
        static NEXT: AtomicU64 = AtomicU64::new(0);
        let (temporary, mut file) = loop {
            let path = root.join(format!(
                "retina.dat.{}.{}.tmp",
                std::process::id(),
                NEXT.fetch_add(1, Ordering::Relaxed)
            ));
            match OpenOptions::new().write(true).create_new(true).open(&path) {
                Ok(file) => break (path, file),
                Err(error) if error.kind() == std::io::ErrorKind::AlreadyExists => continue,
                Err(error) => return Err(format!("{}: {error}", path.display())),
            }
        };
        let result = (|| {
            file.write_all(&bytes).map_err(|e| e.to_string())?;
            file.sync_all().map_err(|e| e.to_string())?;
            drop(file);
            fs::hard_link(&temporary, &destination)
                .map_err(|e| format!("{}: {e}", destination.display()))?;
            Ok(destination)
        })();
        let _ = fs::remove_file(temporary);
        result
    }
}

/// Same existing 48-bit Java RNG as native structure placement. Fixed provider
/// seeds and captured permutations are intentionally untouched.
fn bands(seed: i64, materials: &[i32]) -> Vec<i32> {
    let mut random = crate::structures::Random::new(seed as u64 ^ 0x6a09e667f3bcc909);
    let mut out = vec![materials[0]; 192];
    let mut i = 0;
    while i < out.len() {
        i += random.int(5) as usize + 1;
        if i < out.len() {
            out[i] = materials[1];
        }
        i += 1;
    }
    for color in 0..3 {
        let count = random.int(10) + 6;
        for _ in 0..count {
            let width = if color == 1 { 2 } else { 1 } + random.int(3) as usize;
            let start = random.int(out.len() as u32) as usize;
            for value in out.iter_mut().skip(start).take(width) {
                *value = materials[color + 2];
            }
        }
    }
    let count = random.int(7) + 9;
    let mut start = 0;
    for _ in 0..count {
        if start >= out.len() {
            break;
        }
        out[start] = materials[5];
        if start > 1 && random.int(2) != 0 {
            out[start - 1] = materials[6];
        }
        if start + 1 < out.len() && random.int(2) != 0 {
            out[start + 1] = materials[6];
        }
        start += random.int(16) as usize + 4;
    }
    out
}

/// Stable compound-key order without changing any NBT tag widths or list kinds.
struct Canonical<'a>(&'a Value);
impl Serialize for Canonical<'_> {
    fn serialize<S: serde::Serializer>(&self, serializer: S) -> Result<S::Ok, S::Error> {
        match self.0 {
            Value::Compound(fields) => {
                let mut map = serializer.serialize_map(Some(fields.len()))?;
                for (key, value) in fields.iter().collect::<BTreeMap<_, _>>() {
                    map.serialize_entry(key, &Canonical(value))?;
                }
                map.end()
            }
            Value::List(values) => {
                let mut list = serializer.serialize_seq(Some(values.len()))?;
                for value in values {
                    list.serialize_element(&Canonical(value))?;
                }
                list.end()
            }
            value => value.serialize(serializer),
        }
    }
}

#[cfg(test)]
mod tests;

#[cfg(all(test, unix))]
mod loading_probe;

mod encoding;
