//! Complete world-seeded city plans against actual Minecraft structure contexts.
use retina_worldgen::{
    end::{IslandNoise, VanillaDensity},
    structures::{
        candidate,
        cities::{self, Catalog},
    },
};
use serde_json::{json, Value};
use std::{collections::HashMap, error::Error, fs};
fn main() -> Result<(), Box<dyn Error>> {
    let path = std::env::args()
        .nth(1)
        .ok_or("expected city reference.json")?;
    let source: Value = serde_json::from_slice(&fs::read(&path)?)?;
    let catalog: Catalog = serde_json::from_value(source["catalog"].clone())?;
    catalog.validate()?;
    let allowed = source["valid_biomes"]
        .as_array()
        .ok_or("missing valid biomes")?;
    let mut worlds = HashMap::new();
    let mut count = 0;
    let mut valid = 0;
    let mut pieces = 0;
    let mut ships = 0;
    let mut rotations = [0usize; 4];
    let mut height_rejections = 0;
    for case in source["cases"].as_array().ok_or("missing source cases")? {
        let seed = case["seed"].as_i64().unwrap();
        let chunk = std::array::from_fn(|i| case["chunk"][i].as_i64().unwrap() as i32);
        let at = candidate(seed as u64, chunk[0], chunk[1], &source["placement"]);
        assert_eq!(at, chunk, "source random-spread candidate seed={seed}");
        let (islands, density) = worlds.entry(seed).or_insert_with(|| {
            (
                IslandNoise::new(seed as u64),
                VanillaDensity::new(seed as u64),
            )
        });
        let biome = allowed
            .iter()
            .any(|v| v.as_str() == Some(islands.biome(chunk[0] * 16 + 7, chunk[1] * 16 + 7).id()));
        assert_eq!(
            biome,
            case["column_biome_eligible"].as_bool().unwrap(),
            "column biome seed={seed} chunk={chunk:?}"
        );
        let tile = density.tile(chunk[0] * 16 + 2, chunk[1] * 16 + 2, 11, 11)?;
        if let Some(heights) = case["corner_heights"].as_array() {
            for point in heights {
                let x = point[0].as_i64().unwrap() as i32;
                let z = point[1].as_i64().unwrap() as i32;
                let height = tile
                    .column(x, z)
                    .unwrap()
                    .iter()
                    .rposition(|&b| b)
                    .map_or(-1, |y| y as i32);
                assert_eq!(
                    i64::from(height),
                    point[2].as_i64().unwrap(),
                    "source rotated corner height seed={seed} at {x},{z}"
                );
            }
        }
        let plan = cities::plan(
            seed as u64,
            chunk,
            &catalog,
            |x, z| {
                allowed
                    .iter()
                    .any(|v| v.as_str() == Some(islands.biome(x, z).id()))
            },
            |x, z| {
                tile.column(x, z)
                    .unwrap()
                    .iter()
                    .rposition(|&b| b)
                    .map_or(-1, |y| y as i32)
            },
        );
        assert_eq!(
            plan.is_some(),
            case["valid"].as_bool().unwrap(),
            "generation eligibility seed={seed} chunk={chunk:?}"
        );
        if let Some(plan) = plan {
            assert_eq!(
                json!(plan.origin),
                case["start"],
                "source start seed={seed} chunk={chunk:?}"
            );
            let reach = catalog.horizontal_reach();
            assert!(
                plan.pieces.iter().all(|p| (0..3)
                    .filter(|&i| i != 1)
                    .all(|i| p.bounds[i] >= plan.origin[i] - reach
                        && p.bounds[i + 3] <= plan.origin[i] + reach)),
                "derived discovery bound contains full plan"
            );
            let expected = case["pieces"].as_array().unwrap();
            assert_eq!(
                plan.pieces.len(),
                expected.len(),
                "piece count seed={seed} chunk={chunk:?}"
            );
            for (i, (piece, tag)) in plan.pieces.iter().zip(expected).enumerate() {
                assert_eq!(
                    piece.saved_tag(),
                    *tag,
                    "piece {i} seed={seed} chunk={chunk:?}"
                );
            }
            assert_eq!(
                plan.next_long,
                case["next_long"].as_i64().unwrap(),
                "source RNG continuation seed={seed} chunk={chunk:?}"
            );
            let ship = plan.pieces.iter().any(|p| p.template == "ship");
            assert_eq!(ship, case["ship"].as_bool().unwrap());
            rotations[plan.rotation] += 1;
            ships += usize::from(ship);
            valid += 1;
            pieces += plan.pieces.len();
        } else if biome {
            height_rejections += 1;
        }
        count += 1;
    }
    assert!(valid > 0 && ships > 0 && ships < valid && height_rejections > 0);
    assert!(
        rotations.iter().all(|&n| n > 0),
        "all rotations covered: {rotations:?}"
    );
    println!(
        "{}",
        json!({"schema":"retina.end.city-plan-comparison.v1","source":path,"world_seeded_candidates_exact":count,"generation_eligibility_exact":count,"valid_cities_exact":valid,"cities_with_ships":ships,"height_rejections_exact":height_rejections,"pieces_and_full_saved_tags_exact":pieces,"rng_continuations_exact":valid,"rotation_counts":rotations,"full_regions_generated":0,"conservative_origin_reach":catalog.horizontal_reach(),"production_activated":false})
    );
    Ok(())
}
