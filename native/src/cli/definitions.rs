//! Definition-only commands reuse the authoritative catalog/reader APIs. No
//! TerrainEngine or graphics initialization is involved.
use super::args::Init;
use retina_worldgen::world_definition::{WorldDefinition, catalog::WorldCatalog};
use std::path::Path;

pub fn initialize(options: Init) -> Result<serde_json::Value, String> {
    let catalog = WorldCatalog::read_selected(&options.catalog, &options.variant)?;
    let (path, definition) =
        catalog.publish_open_owned(&options.variant, options.seed, &options.world)?;
    eprintln!(
        "Created {} from catalog variant {}",
        path.display(),
        options.variant
    );
    Ok(definition.adapter_metadata())
}

pub fn inspect(path: &Path) -> Result<serde_json::Value, String> {
    Ok(WorldDefinition::read(path)?.adapter_metadata())
}

#[cfg(test)]
mod tests {
    use super::*;
    use std::sync::atomic::{AtomicU64, Ordering};
    struct Scratch(std::path::PathBuf);
    impl Drop for Scratch {
        fn drop(&mut self) {
            let _ = std::fs::remove_dir_all(&self.0);
        }
    }
    #[test]
    fn init_inspect_reuse_real_catalog_and_never_replace_authoritative_data() {
        static NEXT: AtomicU64 = AtomicU64::new(0);
        let root = Scratch(std::env::temp_dir().join(format!(
            "retina-cli-definition-{}-{}",
            std::process::id(),
            NEXT.fetch_add(1, Ordering::Relaxed)
        )));
        let catalog = std::path::PathBuf::from(concat!(
            env!("CARGO_MANIFEST_DIR"),
            "/../assets/world-definitions/26.3/vanilla-binary/retina-catalog.dat"
        ));
        let options = |seed| Init {
            catalog: catalog.clone(),
            world: root.0.clone(),
            seed,
            variant: "normal-no-structures-bonus-chest".into(),
        };
        let first = initialize(options(i64::MIN + 41)).unwrap();
        assert_eq!(first["seed"], i64::MIN + 41);
        assert_eq!(first["generate_structures"], false);
        assert_eq!(first["bonus_chest"], true);
        assert_eq!(first["dimensions"].as_object().unwrap().len(), 3);
        let path = root.0.join("retina.dat");
        let bytes = std::fs::read(&path).unwrap();
        assert_eq!(first, inspect(&path).unwrap());
        assert!(initialize(options(999)).is_err());
        assert_eq!(bytes, std::fs::read(&path).unwrap());
        std::fs::write(&path, b"invalid existing definition").unwrap();
        assert!(initialize(options(999)).is_err());
        assert_eq!(std::fs::read(path).unwrap(), b"invalid existing definition");
        assert!(inspect(&root.0.join("missing.dat")).is_err());
    }
}
