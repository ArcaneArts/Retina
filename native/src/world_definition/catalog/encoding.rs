//! Catalog container versions. V2 decodes only the selected compressed variant;
//! materialized world definitions retain their existing format and authority.
use super::*;
use fastnbt::ByteArray;

#[derive(Deserialize)]
#[serde(deny_unknown_fields)]
struct Container {
    catalog_version: i32,
    materialization_version: i32,
    #[serde(default)]
    variants: Option<BTreeMap<String, Variant>>,
    #[serde(default)]
    compressed_variants: Option<BTreeMap<String, ByteArray>>,
}

pub(super) fn read(path: &Path, selected: Option<&str>) -> Result<WorldCatalog, String> {
    let mut raw = Vec::new();
    GzDecoder::new(File::open(path).map_err(|e| format!("{}: {e}", path.display()))?)
        .read_to_end(&mut raw)
        .map_err(|e| format!("{}: catalog gzip: {e}", path.display()))?;
    let container: Container = fastnbt::from_bytes(&raw)
        .map_err(|e| format!("{}: catalog NBT: {e}", path.display()))?;
    drop(raw);
    if container.materialization_version != 1 {
        return Err(format!("unsupported materialization version {}", container.materialization_version));
    }
    let variants = match (container.catalog_version, container.variants, container.compressed_variants) {
        (1, Some(mut variants), None) => {
            if let Some(name) = selected {
                let variant = variants.remove(name).ok_or_else(|| format!("catalog has no variant {name}"))?;
                BTreeMap::from([(name.to_owned(), variant)])
            } else { variants }
        }
        (2, None, Some(mut variants)) => {
            if let Some(name) = selected {
                let bytes = variants.remove(name).ok_or_else(|| format!("catalog has no variant {name}"))?;
                // Release unused compressed payloads before expanding the selected one.
                drop(variants);
                BTreeMap::from([(name.to_owned(), decode(name, bytes)?)])
            } else {
                variants.into_iter().map(|(name, bytes)| {
                    let variant = decode(&name, bytes)?;
                    Ok((name, variant))
                }).collect::<Result<_, String>>()?
            }
        }
        (version, _, _) => return Err(format!("unsupported or inconsistent catalog container {version}")),
    };
    // The decoded model uses the original materialization schema. Container
    // compression is independent of world-definition and seed-recipe versions.
    Ok(WorldCatalog { catalog_version: 1, materialization_version: 1, variants })
}

fn decode(name: &str, bytes: ByteArray) -> Result<Variant, String> {
    let mut raw = Vec::new();
    GzDecoder::new(bytemuck::cast_slice::<i8, u8>(&bytes))
        .read_to_end(&mut raw)
        .map_err(|e| format!("catalog variant {name} gzip: {e}"))?;
    fastnbt::from_bytes(&raw).map_err(|e| format!("catalog variant {name} NBT: {e}"))
}
