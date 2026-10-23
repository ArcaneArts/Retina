//! Compare reused quart biomes with actual sparse queries and the original
//! single-fossil builder, including its floor search and later random draws.
use super::*;
use crate::queries::{Kind, Point};
use std::path::PathBuf;

fn fixture(engine: &TerrainEngine, name: &str) -> (u32, Arc<WorldProfile>, i32, u32) {
    let directory = PathBuf::from(std::env::var("RETINA_TERRAIN_REUSE_PROFILES").unwrap());
    let mut source: Value =
        serde_json::from_slice(&std::fs::read(directory.join(format!("{name}.json"))).unwrap())
            .unwrap();
    source["program_execution"] = "interpreter".into();
    let min_y = source["generation_bounds"]["storage_min_y"]
        .as_i64()
        .unwrap_or(-64) as i32;
    let height = source["generation_bounds"]["storage_height"]
        .as_u64()
        .unwrap_or(384) as u32;
    let id = engine
        .register_profile(&serde_json::to_vec(&source).unwrap())
        .unwrap();
    (id, engine.profile(id).unwrap().unwrap(), min_y, height)
}

fn request(id: u32, seed: u64, x: i32, z: i32, min_y: i32, height: u32) -> ChunkRequest {
    ChunkRequest {
        seed,
        chunk_x: x,
        chunk_z: z,
        min_y,
        height,
        base_height: 64.,
        amplitude: 48.,
        frequency: 0.008,
        reserved: id,
    }
}

fn report(name: &str, value: Value) {
    if let Ok(path) = std::env::var("RETINA_FOSSIL_BIOME_REPORTS") {
        std::fs::write(
            PathBuf::from(path).join(format!("{name}.json")),
            serde_json::to_vec_pretty(&value).unwrap(),
        )
        .unwrap();
    }
    println!("{value}");
}

#[test]
#[ignore = "requires RETINA_TERRAIN_REUSE_PROFILES and a real GPU"]
fn retained_fossil_biomes_match_actual_sparse_queries() {
    let engine = TerrainEngine::new().unwrap();
    let mut observations = Vec::new();
    for name in ["nether", "custom", "direct-stem", "overworld"] {
        let (id, _, min_y, height) = fixture(&engine, name);
        for (seed, x, z) in [(123456789, -33, 17), (42, 16, -49)] {
            let r = request(id, seed, x, z, min_y, height);
            let (_, mask) = engine.terrain_field(r, 16).unwrap();
            let mask = mask.unwrap();
            let mut points = Vec::new();
            // Non-quart-aligned X/Z, negative boundaries, both target edges and
            // guard cells, with the exact Y clamping used by sparse queries.
            for (dx, dz) in [
                (-32, -32),
                (-17, 3),
                (-1, -1),
                (0, 0),
                (1, 3),
                (3, 4),
                (4, 15),
                (15, 16),
                (16, 17),
                (127, 129),
                (255, 255),
                (256, 257),
                (271, 271),
                (287, -31),
            ] {
                for y in [
                    min_y - 9,
                    min_y - 1,
                    min_y,
                    min_y + 1,
                    min_y + 3,
                    min_y + 4,
                    min_y + height as i32 / 2,
                    min_y + height as i32 - 1,
                    min_y + height as i32,
                    min_y + height as i32 + 9,
                ] {
                    points.push(Point {
                        x: x * 16 + dx,
                        y,
                        z: z * 16 + dz,
                    });
                }
            }
            // Enumerate every quart Y at several horizontally distant columns.
            for (dx, dz) in [(0, 0), (7, 15), (127, 192), (255, 255)] {
                for y in (min_y..min_y + height as i32).step_by(4) {
                    points.push(Point {
                        x: x * 16 + dx,
                        y,
                        z: z * 16 + dz,
                    });
                }
            }
            let sparse = engine.query_points(r, &points, Kind::Biome).unwrap();
            for (point, expected) in points.iter().zip(&sparse) {
                assert_eq!(
                    retained_biome(r, &mask, [point.x, point.y, point.z]),
                    Some(expected.biome() as u16),
                    "{name}, seed={seed}, {point:?}"
                );
            }
            let outside = Point {
                x: mask.origin_x - 15 + (mask.width + 30) as i32,
                y: min_y,
                z: z * 16,
            };
            assert_eq!(
                retained_biome(r, &mask, [outside.x, outside.y, outside.z]),
                None
            );
            assert_eq!(
                engine
                    .query_points(r, &[outside], Kind::Biome)
                    .unwrap()
                    .len(),
                1
            );
            for bad in [
                ChunkRequest {
                    min_y: min_y + 1,
                    ..r
                },
                ChunkRequest {
                    height: height - 1,
                    ..r
                },
            ] {
                assert_eq!(retained_biome(bad, &mask, [x * 16, min_y, z * 16]), None);
                assert_eq!(
                    engine.query_points(bad, &[], Kind::Biome).err().as_deref(),
                    Some("point queries require quart-aligned vertical bounds")
                );
            }
            observations.push(json!({"profile":name,"seed":seed,"min_y":min_y,
                "height":height,"compared_points":points.len(),"outside_fallback":true,
                "invalid_empty_batch_preserves_error":true}));
        }
    }
    report(
        "point-parity",
        json!({"exact":true,"cases":observations,
        "scope":"Actual interpreter GPU tiles and sparse biome queries; complete-region corpus separately validates normal specialization and placement."}),
    );
}

#[test]
#[ignore = "requires RETINA_TERRAIN_REUSE_PROFILES and a real GPU"]
fn retained_fossil_biomes_preserve_floor_rng_and_start_metadata() {
    let engine = TerrainEngine::new().unwrap();
    let (id, profile, min_y, height) = fixture(&engine, "nether");
    let definition = profile
        .structures
        .definitions
        .iter()
        .position(|d| d.kind == "nether_fossil" && !d.unimplemented)
        .unwrap();
    let mut cases = Vec::new();
    let mut total_starts = 0;
    for (seed, x, z) in [(123456789, -64, -64), (42, 32, 32)] {
        let inputs: Vec<_> = (0..128)
            .map(|i| {
                let r = request(id, seed, x + (i % 16) * 2, z + (i / 16) * 4, min_y, height);
                (
                    r,
                    definition,
                    Random::new(seed.wrapping_add(i as u64 * 12345)),
                )
            })
            .collect();
        let prepared = prepare_batch(&engine, &profile, &inputs, None).unwrap();
        let mut positions = 0;
        let mut starts = 0;
        for (i, ((r, definition, random), p)) in inputs.iter().zip(&prepared).enumerate() {
            let expected_random = begin(*r, &profile, *definition, random.clone()).unwrap().0;
            assert_eq!(p.random.0, expected_random.0);
            let selected_seed = seed.wrapping_add(i as u64);
            let original = build(
                &engine,
                *r,
                &profile,
                *definition,
                selected_seed,
                random.clone(),
            )
            .unwrap();
            let reused = finish(*r, &profile, *definition, selected_seed, p);
            positions += usize::from(p.position.is_some());
            starts += usize::from(!reused.pieces.is_empty());
            assert_eq!(
                crate::structures::start_data(*r, &profile, &[Arc::new(original)]),
                crate::structures::start_data(*r, &profile, &[Arc::new(reused)])
            );
        }
        assert!(positions > 0, "exercise actual fossil floor positions");
        total_starts += starts;
        cases.push(json!({"seed":seed,"candidates":inputs.len(),
            "floor_positions":positions,"nonempty_starts":starts}));
    }
    assert!(
        total_starts > 0,
        "exercise accepted biome, template and rotation draws"
    );
    report(
        "start-parity",
        json!({"exact":true,"cases":cases,
        "scope":"Prepared batch against untouched single-fossil build path; exact RNG after begin and generated start metadata, not a replacement for complete-NBT comparisons."}),
    );
}
