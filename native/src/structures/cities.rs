//! Minecraft 26.3 End city plans. This stream belongs to the structure context,
//! independently of random-spread candidate or weighted set selection streams.
mod assembly;
mod resident;
pub(super) mod integration;
pub(super) mod placement;
pub use resident::{Resident, TemplateRef};
use super::{add, intersects, rotate, Random};
use crate::nbt;
use serde::{Deserialize, Serialize};
use serde_json::{json, Value};
use std::collections::BTreeMap;

pub const NAMES: [&str; 19] = [
    "base_floor",
    "base_roof",
    "bridge_end",
    "bridge_gentle_stairs",
    "bridge_piece",
    "bridge_steep_stairs",
    "fat_tower_base",
    "fat_tower_middle",
    "fat_tower_top",
    "second_floor_1",
    "second_floor_2",
    "second_roof",
    "ship",
    "third_floor_1",
    "third_floor_2",
    "third_roof",
    "tower_base",
    "tower_piece",
    "tower_top",
];
#[derive(Clone, Deserialize)]
pub struct Catalog {
    pub sizes: BTreeMap<String, [i32; 3]>,
}
impl Catalog {
    pub fn validate(&self) -> Result<(), String> {
        for name in NAMES {
            if !self
                .sizes
                .get(name)
                .is_some_and(|s| s.iter().all(|&v| v > 0 && v <= 512))
            {
                return Err(format!("missing or empty End city template: {name}"));
            }
        }
        Ok(())
    }
    /// Conservative horizontal reach from the exact start position. Bounds
    /// follow source offsets, four bridge segments, and the eight-level depth
    /// limit; they do not truncate a generated plan to an arbitrary halo.
    pub fn horizontal_reach(&self) -> i32 {
        let extent = self
            .sizes
            .values()
            .map(|s| s[0].max(s[2]) - 1)
            .max()
            .unwrap();
        // House, tower, bridge, fat tower. A rejected recursive call still
        // leaves its caller's bridge-start piece, covered by max(extent,...).
        let mut reach = [[0i32; 4]; 10];
        for d in (1..=8).rev() {
            let next = reach[d + 1];
            reach[d][0] = 13 + extent.max(next[1]);
            reach[d][1] = (5 + extent).max(10 + extent.max(next[2])).max(4 + next[3]);
            reach[d][2] = (106 + extent).max(36 + next[0]).max(40 + extent);
            reach[d][3] = (5 + extent).max(15 + extent.max(next[2]));
        }
        3 + extent.max(reach[1][1])
    }
    fn bounds(&self, name: &str, pos: [i32; 3], rotation: usize) -> [i32; 6] {
        let size = self.sizes[name];
        let a = pos;
        let b = add(pos, rotate(size.map(|v| v - 1), rotation));
        [
            a[0].min(b[0]),
            a[1].min(b[1]),
            a[2].min(b[2]),
            a[0].max(b[0]),
            a[1].max(b[1]),
            a[2].max(b[2]),
        ]
    }
}
#[derive(Clone, Debug, PartialEq, Eq, Serialize)]
pub struct Piece {
    pub template: String,
    pub pos: [i32; 3],
    pub rotation: usize,
    pub overwrite: bool,
    pub depth: i32,
    pub bounds: [i32; 6],
}
impl Piece {
    /// Actual EndCityPiece/TemplateStructurePiece fields, retaining signed GD.
    pub fn saved_tag(&self) -> Value {
        let mut tag = nbt::compound();
        nbt::put(&mut tag, "id", nbt::string("minecraft:ecp"));
        nbt::put(&mut tag, "BB", json!([11, self.bounds]));
        nbt::put(&mut tag, "GD", nbt::int(self.depth));
        nbt::put(&mut tag, "O", nbt::int(2));
        nbt::put(&mut tag, "OW", json!([1, u8::from(self.overwrite)]));
        nbt::put(
            &mut tag,
            "Rot",
            nbt::string(
                [
                    "NONE",
                    "CLOCKWISE_90",
                    "CLOCKWISE_180",
                    "COUNTERCLOCKWISE_90",
                ][self.rotation],
            ),
        );
        for (key, n) in ["TPX", "TPY", "TPZ"].into_iter().zip(self.pos) {
            nbt::put(&mut tag, key, nbt::int(n));
        }
        nbt::put(&mut tag, "Template", nbt::string(&self.template));
        tag
    }
}
#[derive(Clone, Debug, PartialEq, Eq, Serialize)]
pub struct Plan {
    pub origin: [i32; 3],
    pub rotation: usize,
    pub pieces: Vec<Piece>,
    /// Diagnostic peek after assembly; never a marker or loot seed.
    pub next_long: i64,
}

/// Vanilla GenerationContext.makeRandom / setLargeFeatureSeed. The incoming
/// world seed is never replaced by the generic structure selection hash.
pub(super) fn context_random(seed: u64, chunk: [i32; 2]) -> Random {
    let mut random = Random::new(seed);
    let x = random.long();
    let z = random.long();
    Random::new(
        (chunk[0] as i64 as u64).wrapping_mul(x) ^ (chunk[1] as i64 as u64).wrapping_mul(z) ^ seed,
    )
}
/// End/fixed biome sources are vertically constant, so both source biome checks
/// use this exact horizontal column. Heights are first occupied, not first free.
pub fn plan(
    seed: u64,
    chunk: [i32; 2],
    catalog: &Catalog,
    mut valid_biome: impl FnMut(i32, i32) -> bool,
    mut first_occupied: impl FnMut(i32, i32) -> i32,
) -> Option<Plan> {
    let x = chunk[0] * 16 + 7;
    let z = chunk[1] * 16 + 7;
    if !valid_biome(x, z) {
        return None;
    }
    let mut random = context_random(seed, chunk);
    let rotation = random.int(4) as usize;
    let dx = if rotation == 1 || rotation == 2 {
        -5
    } else {
        5
    };
    let dz = if rotation == 2 || rotation == 3 {
        -5
    } else {
        5
    };
    let y = [
        first_occupied(x, z),
        first_occupied(x, z + dz),
        first_occupied(x + dx, z),
        first_occupied(x + dx, z + dz),
    ]
    .into_iter()
    .min()
    .unwrap();
    if y < 60 || !valid_biome(x, z) {
        return None;
    }
    let origin = [x, y, z];
    let pieces = assembly::assemble(catalog, origin, rotation, &mut random);
    Some(Plan {
        origin,
        rotation,
        pieces,
        next_long: random.clone().long() as i64,
    })
}

#[cfg(test)]
mod tests {
    use super::*;
    #[test]
    fn whole_world_seeded_city_plans_match_actual_source() {
        let source: Value = serde_json::from_str(include_str!(
            "../../tests/fixtures/end-city-plans-26.3.json"
        ))
        .unwrap();
        let catalog: Catalog = serde_json::from_value(source["catalog"].clone()).unwrap();
        catalog.validate().unwrap();
        let mut valid = 0;
        let mut height_rejected = 0;
        let mut ships = 0;
        let mut rotations = [0; 4];
        for case in source["cases"].as_array().unwrap() {
            let seed = case["seed"].as_i64().unwrap();
            let chunk = std::array::from_fn(|i| case["chunk"][i].as_i64().unwrap() as i32);
            assert_eq!(
                super::super::candidate(seed as u64, chunk[0], chunk[1], &source["placement"]),
                chunk
            );
            let biome = case["column_biome_eligible"].as_bool().unwrap();
            let actual = plan(
                seed as u64,
                chunk,
                &catalog,
                |_, _| biome,
                |x, z| {
                    let row = case["corner_heights"]
                        .as_array()
                        .unwrap()
                        .iter()
                        .find(|v| {
                            v[0].as_i64() == Some(i64::from(x))
                                && v[1].as_i64() == Some(i64::from(z))
                        })
                        .expect("source rotated corner query");
                    row[2].as_i64().unwrap() as i32
                },
            );
            assert_eq!(
                actual.is_some(),
                case["valid"].as_bool().unwrap(),
                "seed {seed}, chunk {chunk:?}"
            );
            if let Some(actual) = actual {
                assert_eq!(json!(actual.origin), case["start"]);
                assert_eq!(actual.next_long, case["next_long"].as_i64().unwrap());
                let tags: Vec<_> = actual.pieces.iter().map(Piece::saved_tag).collect();
                assert_eq!(json!(tags), case["pieces"], "seed {seed}, chunk {chunk:?}");
                let ship = actual.pieces.iter().any(|p| p.template == "ship");
                assert_eq!(ship, case["ship"].as_bool().unwrap());
                let reach = catalog.horizontal_reach();
                assert!(actual.pieces.iter().all(|p| [0, 2]
                    .into_iter()
                    .all(|i| p.bounds[i] >= actual.origin[i] - reach
                        && p.bounds[i + 3] <= actual.origin[i] + reach)));
                valid += 1;
                ships += usize::from(ship);
                rotations[actual.rotation] += 1;
            } else if biome {
                height_rejected += 1;
            }
        }
        assert_eq!(valid, 21);
        assert_eq!(ships, 11);
        assert_eq!(height_rejected, 16);
        assert!(rotations.iter().all(|&v| v > 0));
    }
}
