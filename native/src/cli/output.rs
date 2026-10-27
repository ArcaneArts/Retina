use fs2::FileExt;
use std::{
    fs::{self, File, OpenOptions},
    path::{Path, PathBuf},
};

/// An OS advisory lock, held for the complete run. A stale filename is harmless;
/// process exit/crash releases the real lock without an unlink race.
pub fn lock(root: &Path) -> Result<File, String> {
    fs::create_dir_all(root)
        .map_err(|error| format!("create output {}: {error}", root.display()))?;
    let path = root.join(".retina-generation.lock");
    let file = OpenOptions::new()
        .create(true)
        .truncate(false)
        .read(true)
        .write(true)
        .open(&path)
        .map_err(|error| format!("open output lock {}: {error}", path.display()))?;
    file.try_lock_exclusive()
        .map_err(|error| format!("lock output {}: {error}", root.display()))?;
    Ok(file)
}

pub fn region_directory(root: &Path, dimension: &str) -> Result<PathBuf, String> {
    Ok(match dimension {
        "minecraft:overworld" => root.join("region"),
        "minecraft:the_nether" => root.join("DIM-1/region"),
        "minecraft:the_end" => root.join("DIM1/region"),
        _ => {
            let (namespace, path) = dimension
                .split_once(':')
                .ok_or("dimension must be namespaced")?;
            // Minecraft identifier characters can include dots. An output path
            // must additionally prohibit directory traversal and absolute paths.
            let valid = !namespace.is_empty()
                && namespace
                    .bytes()
                    .all(|c| c.is_ascii_lowercase() || c.is_ascii_digit() || b"_.-".contains(&c))
                && !matches!(namespace, "." | "..")
                && !path.is_empty()
                && path.split('/').all(|part| {
                    !part.is_empty()
                        && !matches!(part, "." | "..")
                        && part.bytes().all(|c| {
                            c.is_ascii_lowercase() || c.is_ascii_digit() || b"_.-".contains(&c)
                        })
                });
            if !valid {
                return Err(format!(
                    "dimension {dimension} cannot form a safe output path"
                ));
            }
            root.join("dimensions")
                .join(namespace)
                .join(path)
                .join("region")
        }
    })
}

#[cfg(test)]
mod tests {
    use super::*;
    #[test]
    fn dimension_layout_and_traversal() {
        let root = Path::new("out");
        assert_eq!(
            region_directory(root, "minecraft:overworld").unwrap(),
            root.join("region")
        );
        assert_eq!(
            region_directory(root, "minecraft:the_nether").unwrap(),
            root.join("DIM-1/region")
        );
        assert_eq!(
            region_directory(root, "minecraft:the_end").unwrap(),
            root.join("DIM1/region")
        );
        assert_eq!(
            region_directory(root, "pack:caves/lower").unwrap(),
            root.join("dimensions/pack/caves/lower/region")
        );
        for key in [
            "x:../outside",
            "..:outside",
            "x:/absolute",
            "x:a//b",
            "x:a/./b",
            "x:a\\b",
            "x:a:b",
        ] {
            assert!(region_directory(root, key).is_err(), "{key}");
        }
    }
    #[test]
    fn real_lock_conflict_and_release() {
        let path = std::env::temp_dir().join(format!("retina-cli-lock-{}", std::process::id()));
        let first = lock(&path).unwrap();
        assert!(lock(&path).is_err());
        drop(first);
        drop(lock(&path).unwrap()); // The remaining filename must not block a new run.
        fs::remove_file(path.join(".retina-generation.lock")).unwrap();
        fs::remove_dir(path).unwrap();
    }
}
