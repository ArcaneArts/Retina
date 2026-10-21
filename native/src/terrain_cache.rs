//! Reconstruct a field only from an already-cached tile with identical geometry.
use crate::decoration::Field;
use crate::geology::CaveMask;
use crate::{ChunkRequest, COLUMNS};
use std::sync::Arc;

/// The caller must obtain `mask` through the complete request CacheKey. Exact
/// geometry also preserves the substrate's fallback column/material coverage.
/// This applies only to patch-halo tiles, whose producer retains all GPU columns.
pub(crate) fn exact_patch_field(
    request: ChunkRequest,
    side: u32,
    mask: &Arc<CaveMask>,
) -> Option<Field<'static>> {
    if !(2..=32).contains(&side) {
        return None;
    }
    let origin_x = request.chunk_x.checked_sub(3)?;
    let origin_z = request.chunk_z.checked_sub(3)?;
    let tile_side = side as usize + 6;
    let halo = mask.columns.as_ref()?;
    if halo.origin_x != origin_x
        || halo.origin_z != origin_z
        || halo.side != tile_side
        || halo.columns.len() != tile_side * tile_side * COLUMNS
        || mask.min_y != request.min_y
        || mask.height != request.height
        || i64::from(mask.origin_x) != i64::from(origin_x) * 16 + 15
        || i64::from(mask.origin_z) != i64::from(origin_z) * 16 + 15
        || mask.width != (tile_side - 2) * 16 + 2
    {
        return None;
    }
    // Same ring-1 crop as the fresh patch-halo producer. Keep the existing
    // immutable mask; do not reinsert it or alter cache retention/eviction.
    let field_side = side as usize + 2;
    let mut columns = Vec::with_capacity(field_side * field_side * COLUMNS);
    for z in 2..2 + field_side {
        let start = (z * tile_side + 2) * COLUMNS;
        columns.extend_from_slice(&halo.columns[start..start + field_side * COLUMNS]);
    }
    Some(Field {
        origin_x: request.chunk_x - 1,
        origin_z: request.chunk_z - 1,
        side: field_side,
        columns,
        substrate: Some(mask.clone()),
        prior: None,
    })
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::geology::ColumnHalo;
    use crate::{CacheKey, CaveCache, Column, TerrainEngine};

    fn request(x: i32, z: i32) -> ChunkRequest {
        ChunkRequest {
            seed: 42,
            chunk_x: x,
            chunk_z: z,
            min_y: 0,
            height: 256,
            base_height: 64.,
            amplitude: 48.,
            frequency: 0.008,
            reserved: 1,
        }
    }
    fn mask(request: ChunkRequest, side: usize) -> Arc<CaveMask> {
        let tile_side = side + 6;
        Arc::new(CaveMask {
            origin_x: (request.chunk_x - 3) * 16 + 15,
            origin_z: (request.chunk_z - 3) * 16 + 15,
            min_y: request.min_y,
            height: request.height,
            width: (tile_side - 2) * 16 + 2,
            air_only: true,
            words: Vec::new(),
            columns: Some(ColumnHalo {
                origin_x: request.chunk_x - 3,
                origin_z: request.chunk_z - 3,
                side: tile_side,
                columns: Arc::new(
                    (0..tile_side * tile_side * COLUMNS)
                        .map(|i| Column {
                            height: i as i32,
                            packed: i as u32,
                            materials: 0,
                        })
                        .collect(),
                ),
            }),
        })
    }
    #[test]
    fn exact_geometry_preserves_crop_guard_extent_and_lifetime() {
        for (x, z) in [(-33, 17), (0, -16), (32, 48)] {
            for side in [2, 7, 16, 32] {
                let r = request(x, z);
                let mask = mask(r, side);
                let weak = Arc::downgrade(&mask);
                let field = exact_patch_field(r, side as u32, &mask).unwrap();
                assert_eq!(
                    (field.origin_x, field.origin_z, field.side),
                    (x - 1, z - 1, side + 2)
                );
                assert!(field.prior.is_none());
                for dz in 0..side + 2 {
                    for dx in 0..side + 2 {
                        let bx = (x - 1 + dx as i32) * 16;
                        let bz = (z - 1 + dz as i32) * 16;
                        assert_eq!(
                            field.column(bx, bz),
                            mask.columns.as_ref().unwrap().column_at(bx, bz)
                        );
                    }
                }
                assert_eq!(field.column((x - 4) * 16, z * 16), None);
                assert_eq!(field.column((x + side as i32 + 3) * 16, z * 16), None);
                assert!(Arc::ptr_eq(field.substrate.as_ref().unwrap(), &mask));
                drop(mask);
                assert!(weak.upgrade().is_some());
                drop(field);
                assert!(weak.upgrade().is_none());
            }
        }
    }
    #[test]
    fn mismatched_geometry_and_full_world_keys_do_not_reuse() {
        let r = request(-16, 32);
        let mask = mask(r, 16);
        for (changed, side) in [
            (r, 8),
            (r, 32),
            (ChunkRequest { chunk_x: -15, ..r }, 16),
            (ChunkRequest { chunk_z: 31, ..r }, 16),
            (ChunkRequest { min_y: -64, ..r }, 16),
            (ChunkRequest { height: 128, ..r }, 16),
            (
                ChunkRequest {
                    chunk_x: i32::MIN,
                    ..r
                },
                16,
            ),
        ] {
            assert!(exact_patch_field(changed, side, &mask).is_none());
        }
        let mut cache = CaveCache::default();
        cache.insert(r, 16, mask.clone());
        for changed in [
            ChunkRequest { seed: 43, ..r },
            ChunkRequest { reserved: 2, ..r },
            ChunkRequest { min_y: -64, ..r },
            ChunkRequest { height: 128, ..r },
            ChunkRequest {
                base_height: 65.,
                ..r
            },
            ChunkRequest {
                amplitude: 47.,
                ..r
            },
            ChunkRequest {
                frequency: 0.009,
                ..r
            },
        ] {
            assert!(!cache.entries.contains_key(&CacheKey::from(changed)));
        }
        assert_eq!(cache.entries.len(), 256);
        let field =
            exact_patch_field(r, 16, cache.entries.get(&CacheKey::from(r)).unwrap()).unwrap();
        assert!(Arc::ptr_eq(field.substrate.as_ref().unwrap(), &mask));
        assert_eq!(cache.entries.len(), 256);
    }
    #[test]
    #[ignore = "requires a GPU and RETINA_TERRAIN_REUSE_PROFILES with exported profiles"]
    fn actual_exact_cached_tiles_match_fresh_gpu_fields() {
        let directory =
            std::path::PathBuf::from(std::env::var("RETINA_TERRAIN_REUSE_PROFILES").unwrap());
        let engine = TerrainEngine::new().unwrap();
        for name in ["nether", "custom", "direct-stem", "overworld"] {
            let bytes = std::fs::read(directory.join(format!("{name}.json"))).unwrap();
            let source: serde_json::Value = serde_json::from_slice(&bytes).unwrap();
            let id = engine.register_profile(&bytes).unwrap();
            let profile = engine.profile(id).unwrap().unwrap();
            let eligible = profile.decoration_patch_halo
                && profile
                    .registry_program
                    .as_ref()
                    .is_some_and(|p| p.material_halo);
            let bounds = &source["generation_bounds"];
            for (seed, x, z) in [(123456789, -33, 17), (42, 0, -16)] {
                let r = ChunkRequest {
                    seed,
                    reserved: id,
                    min_y: bounds["storage_min_y"].as_i64().unwrap_or(-64) as i32,
                    height: bounds["storage_height"].as_u64().unwrap_or(384) as u32,
                    ..request(x, z)
                };
                for side in [2, 7, 16, 32] {
                    *engine.caves.lock().unwrap() = CaveCache::default();
                    let (fresh, first_mask) = engine.terrain_field(r, side).unwrap();
                    let first_mask = first_mask.unwrap();
                    let (reused, reused_mask) = engine.terrain_field(r, side).unwrap();
                    let reused_mask = reused_mask.unwrap();
                    assert_eq!(
                        Arc::ptr_eq(&first_mask, &reused_mask),
                        eligible,
                        "{name} side={side} must reuse only a retained patch-halo tile"
                    );
                    assert_eq!(fresh.columns, reused.columns);
                    assert_eq!(fresh.side, reused.side);
                    let end = side as i32 * 16;
                    for (dx, dz) in [
                        (-49, -49),
                        (-48, -48),
                        (-32, -32),
                        (-16, -16),
                        (0, 0),
                        (end - 1, end - 1),
                        (end + 15, end + 15),
                        (end + 31, end + 31),
                        (end + 47, end + 47),
                        (end + 48, end + 48),
                    ] {
                        let (bx, bz) = (x * 16 + dx, z * 16 + dz);
                        assert_eq!(fresh.column(bx, bz), reused.column(bx, bz));
                        for map in 0..6 {
                            assert_eq!(
                                fresh.height(&profile, r, bx, bz, map),
                                reused.height(&profile, r, bx, bz, map)
                            );
                        }
                        for by in r.min_y - 1..=r.min_y + r.height as i32 {
                            let at = [bx, by, bz];
                            assert_eq!(
                                fresh.material(&profile, r, at),
                                reused.material(&profile, r, at)
                            );
                            assert_eq!(fresh.biome(&profile, at), reused.biome(&profile, at));
                        }
                    }
                    if side == 16 && eligible {
                        std::thread::scope(|scope| {
                            let handles: Vec<_> = (0..8)
                                .map(|_| scope.spawn(|| engine.terrain_field(r, side).unwrap()))
                                .collect();
                            for handle in handles {
                                let (field, mask) = handle.join().unwrap();
                                assert_eq!(field.columns, fresh.columns);
                                assert!(Arc::ptr_eq(&mask.unwrap(), &first_mask));
                            }
                        });
                    }
                    let different_side = if side == 32 { 16 } else { side + 1 };
                    let (_, different) = engine.terrain_field(r, different_side).unwrap();
                    assert!(!Arc::ptr_eq(&different.unwrap(), &first_mask));
                    let changed = ChunkRequest {
                        seed: seed + 1,
                        ..r
                    };
                    let (_, different_seed) = engine.terrain_field(changed, side).unwrap();
                    assert!(!Arc::ptr_eq(&different_seed.unwrap(), &first_mask));
                    *engine.caves.lock().unwrap() = CaveCache::default();
                    assert_eq!(fresh.columns, reused.columns);
                }
            }
            println!("exact cached terrain parity: {name}");
        }
    }
}
