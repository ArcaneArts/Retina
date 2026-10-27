//! End city placement uses the chunk's source decoration stream, not planning RNG.
use crate::structures::{self, Start};
use crate::{ChunkRequest, end::DecorationRandom, nbt, profile::WorldProfile};
use serde_json::{Value, json};
use std::{
    collections::{BTreeMap, HashMap},
    sync::Arc,
};

fn packed(chunk: [i32; 2]) -> u64 {
    u64::from(chunk[0] as u32) | (u64::from(chunk[1] as u32) << 32)
}
fn hash(key: u64) -> u64 {
    let h = key.wrapping_mul(0x9e3779b97f4a7c15);
    let h = h ^ (h >> 32);
    h ^ (h >> 16)
}
fn insert<'a>(table: &mut [Option<&'a Arc<Start>>], start: &'a Arc<Start>) {
    let mask = table.len() - 1;
    let mut i = hash(packed(start.chunk)) as usize & mask;
    while table[i].is_some() {
        i = (i + 1) & mask;
    }
    table[i] = Some(start);
}
/// LongOpenHashSet insertion and reverse-table iteration used by source
/// StructureManager references. Rehash order matters for colliding keys.
pub(super) fn reference_order(mut starts: Vec<&Arc<Start>>) -> Vec<&Arc<Start>> {
    starts.sort_by_key(|s| (s.chunk[0], s.chunk[1]));
    starts.dedup_by_key(|s| s.chunk);
    let mut table = vec![None; 32];
    let mut zero = None;
    let mut size = 0;
    for start in starts {
        if packed(start.chunk) == 0 {
            zero = Some(start);
        } else {
            insert(&mut table, start);
        }
        size += 1;
        if size > table.len() * 3 / 4 {
            let old = table;
            table = vec![None; old.len() * 2];
            for start in old.into_iter().rev().flatten() {
                insert(&mut table, start);
            }
        }
    }
    zero.into_iter()
        .chain(table.into_iter().rev().flatten())
        .collect()
}
fn referenced(start: &Start, request: ChunkRequest, p: &structures::Profile) -> bool {
    if start.pieces.is_empty()
        || (i64::from(start.chunk[0]) - i64::from(request.chunk_x)).abs() > 8
        || (i64::from(start.chunk[1]) - i64::from(request.chunk_z)).abs() > 8
    {
        return false;
    }
    let margin = structures::reference_margin(&p.definitions[start.definition]);
    let x = request.chunk_x * 16;
    let z = request.chunk_z * 16;
    start.pieces.iter().map(|p| p.bounds[0]).min().unwrap() - margin <= x + 15
        && start.pieces.iter().map(|p| p.bounds[3]).max().unwrap() + margin >= x
        && start.pieces.iter().map(|p| p.bounds[2]).min().unwrap() - margin <= z + 15
        && start.pieces.iter().map(|p| p.bounds[5]).max().unwrap() + margin >= z
}
pub(in crate::structures) fn ordered<'a>(
    plans: &'a [Arc<Start>],
    request: ChunkRequest,
    profile: &WorldProfile,
) -> impl Iterator<Item = &'a Arc<Start>> {
    if profile.end.is_none() {
        return plans.iter().chain(Vec::new());
    }
    let mut groups = BTreeMap::<usize, Vec<&Arc<Start>>>::new();
    for start in plans {
        groups.entry(start.definition).or_default().push(start);
    }
    let ordered: Vec<_> = groups
        .into_iter()
        .flat_map(|(definition, starts)| {
            if profile.structures.definitions[definition].kind == "end_city" {
                reference_order(
                    starts
                        .into_iter()
                        .filter(|s| referenced(s, request, &profile.structures))
                        .collect(),
                )
            } else {
                starts
            }
        })
        .collect();
    plans[..0].iter().chain(ordered)
}

#[derive(Default)]
pub(in crate::structures) struct Placement {
    randoms: HashMap<usize, DecorationRandom>,
    materials: HashMap<[i32; 3], u16>,
    density: Option<crate::end::VanillaDensity>,
    base_columns: HashMap<[i32; 2], [bool; 256]>,
}
impl Placement {
    #[allow(clippy::too_many_arguments)]
    pub(in crate::structures) fn apply(
        &mut self,
        start: &Start,
        request: ChunkRequest,
        profile: &WorldProfile,
        mut output: Option<&mut [u16]>,
        block_entities: &mut HashMap<[i32; 3], Value>,
        entities: &mut Vec<Value>,
        writes: &mut Vec<(usize, u16)>,
        track: bool,
        updates: &mut crate::decoration::pairs::Updates,
    ) -> (usize, usize) {
        let p = &profile.structures;
        let kit = p.end_cities.as_ref().unwrap();
        let d = &p.definitions[start.definition];
        let random = self.randoms.entry(start.definition).or_insert_with(|| {
            DecorationRandom::for_feature(
                request.seed,
                request.chunk_x * 16,
                request.chunk_z * 16,
                d.config["retina_decoration_index"].as_u64().unwrap() as u32,
                d.config["retina_decoration_step"].as_u64().unwrap() as u32,
            )
        });
        let bounds = [
            request.chunk_x * 16,
            request.min_y + 1,
            request.chunk_z * 16,
            request.chunk_x * 16 + 15,
            request.min_y + request.height as i32 - 1,
            request.chunk_z * 16 + 15,
        ];
        let chunk = [request.chunk_x, request.chunk_z];
        let index = start.index(p);
        let mut placed = 0;
        let mut count = 0;
        for &piece_id in index.pieces.get(&chunk).map_or(&[][..], Vec::as_slice) {
            let piece = &start.pieces[piece_id];
            if !structures::intersects(piece.bounds, bounds) {
                continue;
            }
            count += 1;
            let part = &piece.element.parts[0];
            let template = &p.templates[part.template];
            let name = template.id.strip_prefix("minecraft:end_city/").unwrap();
            let entry = &kit.templates[name];
            let rotated = p.rotated(part.template, piece.palette, piece.rotation);
            let subset = index
                .blocks
                .get(&(chunk, piece_id, 0))
                .map_or(&[][..], Vec::as_slice);
            for &ordinal in subset {
                let block = &rotated.blocks[ordinal];
                let pos = structures::add(piece.pos, block.pos);
                if !structures::inside(bounds, pos)
                    || pos[1] >= request.min_y + request.height as i32
                {
                    continue;
                }
                if p.names[block.original as usize] == "minecraft:structure_block"
                    || part.ignore_air && block.original == 0
                {
                    continue;
                }
                let material = template.transform.as_ref().unwrap().materials
                    [block.original as usize][piece.rotation];
                let at = (pos[1] - request.min_y) as usize * 256
                    + (pos[2] - bounds[2]) as usize * 16
                    + (pos[0] - bounds[0]) as usize;
                let previous = self.materials.insert(pos, material);
                if let Some(output) = output.as_deref_mut() {
                    output[at] = material;
                    updates.record(profile, at, material);
                }
                if track {
                    writes.push((at, material));
                }
                placed += 1;
                let source_tag = entry.tags[piece.palette].get(&ordinal);
                if source_tag.is_some()
                    || previous.is_none_or(|previous| {
                        p.names[previous as usize] != p.names[material as usize]
                    })
                {
                    block_entities.remove(&pos);
                }
                if let Some(prototype) = source_tag.or_else(|| kit.block_entities.get(&material)) {
                    let mut tag = prototype.clone();
                    if source_tag.is_some() && kit.containers.contains(&material) {
                        let seed = random.long() as i64;
                        // placeInWorld draws even when no table is present;
                        // the container save codec omits that unused seed.
                        if nbt::field(&tag, "LootTable").is_some() {
                            nbt::put(&mut tag, "LootTableSeed", nbt::long(seed));
                        }
                    }
                    for axis in 0..3 {
                        nbt::put(&mut tag, ["x", "y", "z"][axis], nbt::int(pos[axis]));
                    }
                    block_entities.entry(pos).or_insert(tag);
                }
            }
            let mut repaired: Vec<_> = self
                .materials
                .iter()
                .filter_map(|(&pos, &material)| {
                    let updated = kit.stair_shape(material, |direction| {
                        self.materials.get(&structures::add(pos, structures::DIRECTIONS[direction])).copied()
                    })?;
                    (updated != material).then_some((pos, updated))
                })
                .collect();
            repaired.sort_by_key(|(pos, _)| *pos);
            for (pos, material) in repaired {
                self.materials.insert(pos, material);
                let at = (pos[1] - request.min_y) as usize * 256
                    + (pos[2] - bounds[2]) as usize * 16
                    + (pos[0] - bounds[0]) as usize;
                if let Some(output) = output.as_deref_mut() {
                    output[at] = material;
                }
                if track {
                    writes.push((at, material));
                }
                placed += 1;
            }
            let attachments: Vec<_> = self
                .materials
                .iter()
                .filter_map(|(&pos, &material)| {
                    kit.attachments.get(&material).map(|a| (pos, a.clone()))
                })
                .collect();
            for (pos, attachment) in attachments {
                let support = structures::add(pos, structures::DIRECTIONS[attachment.facing ^ 1]);
                let material = if let Some(&material) = self.materials.get(&support) {
                    material
                } else if support[1] < request.min_y
                    || support[1] >= request.min_y + request.height as i32
                {
                    0
                } else if structures::inside(bounds, support) && output.is_some() {
                    let at = (support[1] - request.min_y) as usize * 256
                        + (support[2] - bounds[2]) as usize * 16
                        + (support[0] - bounds[0]) as usize;
                    output.as_deref().unwrap()[at]
                } else {
                    let density = self
                        .density
                        .get_or_insert_with(|| crate::end::VanillaDensity::new(request.seed));
                    let column = self
                        .base_columns
                        .entry([support[0], support[2]])
                        .or_insert_with(|| density.column(support[0], support[2]));
                    if (0..256).contains(&support[1]) && column[support[1] as usize] {
                        profile.end.as_ref().unwrap().material
                    } else {
                        0
                    }
                };
                let supported = if attachment.solid {
                    kit.solid.contains(&material)
                } else {
                    kit.faces
                        .get(&material)
                        .is_some_and(|mask| mask & (1 << attachment.facing) != 0)
                };
                if !supported {
                    self.materials.insert(pos, 0);
                    block_entities.remove(&pos);
                    let at = (pos[1] - request.min_y) as usize * 256
                        + (pos[2] - bounds[2]) as usize * 16
                        + (pos[0] - bounds[0]) as usize;
                    if let Some(output) = output.as_deref_mut() {
                        output[at] = 0;
                    }
                    if track {
                        writes.push((at, 0));
                    }
                    placed += 1;
                }
            }
            for (&ordinal, tag) in &entry.markers {
                let block = &rotated.blocks[ordinal];
                let pos = structures::add(piece.pos, block.pos);
                if !structures::inside(bounds, pos)
                    || p.names[block.original as usize] != "minecraft:structure_block"
                    || nbt::field(tag, "mode").and_then(|v| v[1].as_str()) != Some("DATA")
                {
                    continue;
                }
                let marker = nbt::field(tag, "metadata")
                    .and_then(|v| v[1].as_str())
                    .unwrap_or("");
                if marker.starts_with("Chest") {
                    let chest = [pos[0], pos[1] - 1, pos[2]];
                    if structures::inside(bounds, chest)
                        && self
                            .materials
                            .get(&chest)
                            .is_some_and(|m| kit.containers.contains(m))
                    {
                        if let Some(tag) = block_entities.get_mut(&chest) {
                            nbt::put(
                                tag,
                                "LootTable",
                                nbt::string("minecraft:chests/end_city_treasure"),
                            );
                            nbt::put(tag, "LootTableSeed", nbt::long(random.long() as i64));
                            tag[1].as_object_mut().unwrap().remove("Items");
                        }
                    }
                } else if marker.starts_with("Sentry") || marker.starts_with("Elytra") {
                    if !(-30_000_000..30_000_000).contains(&pos[0])
                        || !(-30_000_000..30_000_000).contains(&pos[2])
                        || !(-20_000_000..20_000_000).contains(&pos[1])
                    {
                        continue;
                    }
                    let identity = request.seed
                        ^ structures::rand64(packed(start.chunk))
                        ^ structures::rand64(start.definition as u64)
                        ^ structures::rand64(piece_id as u64)
                        ^ structures::rand64(ordinal as u64).rotate_left(13);
                    entities.push(entity(
                        marker.starts_with("Sentry"),
                        pos,
                        piece.rotation,
                        identity,
                    ));
                }
            }
        }
        (count, placed)
    }
}
fn entity(shulker: bool, pos: [i32; 3], rotation: usize, key: u64) -> Value {
    let a = structures::rand64(key ^ 0x656e645f63697479);
    let b = structures::rand64(a);
    let mut tag = nbt::compound();
    nbt::put(
        &mut tag,
        "id",
        nbt::string(if shulker {
            "minecraft:shulker"
        } else {
            "minecraft:item_frame"
        }),
    );
    nbt::put(
        &mut tag,
        "UUID",
        json!([11, [a as i32, (a >> 32) as i32, b as i32, (b >> 32) as i32]]),
    );
    nbt::put(&mut tag, "Motion", nbt::list(6, vec![json!([6, 0.0]); 3]));
    for (name, value) in [
        ("Invulnerable", json!([1, 0])),
        ("OnGround", json!([1, 0])),
        ("Fire", json!([2, 0])),
        ("Air", json!([2, 300])),
        ("fall_distance", json!([6, 0.0])),
        ("PortalCooldown", nbt::int(0)),
    ] {
        nbt::put(&mut tag, name, value);
    }
    let mut point = [pos[0] as f64 + 0.5, pos[1] as f64, pos[2] as f64 + 0.5];
    let yaw = if shulker {
        // LivingEntity's constructor yaw is runtime randomness, independent of
        // worldgen. Use a stable spawn identity stream without drawing loot RNG.
        ((a >> 40) as f32 * (1.0 / 16777216.0)) * std::f32::consts::TAU
    } else {
        rotation as f32 * 90.0
    };
    if shulker {
        for (name, value) in [
            ("Health", json!([5, 30.0])),
            ("HurtTime", json!([2, 0])),
            ("DeathTime", json!([2, 0])),
            ("AbsorptionAmount", json!([5, 0.0])),
            ("current_impulse_context_reset_grace_time", nbt::int(0)),
            ("FallFlying", json!([1, 0])),
            ("CanPickUpLoot", json!([1, 0])),
            ("PersistenceRequired", json!([1, 0])),
            ("LeftHanded", json!([1, 0])),
            ("AttachFace", json!([1, 0])),
            ("Peek", json!([1, 0])),
            ("Color", json!([1, 16])),
            ("attributes", nbt::list(0, vec![])),
        ] {
            nbt::put(&mut tag, name, value);
        }
        let mut brain = nbt::compound();
        nbt::put(&mut brain, "memories", nbt::compound());
        nbt::put(&mut tag, "Brain", brain);
    } else {
        point[1] += 0.5;
        point[0] -= [0.0, -1.0, 0.0, 1.0][rotation] * 0.46875;
        point[2] -= [1.0, 0.0, -1.0, 0.0][rotation] * 0.46875;
        nbt::put(&mut tag, "Facing", json!([1, ([3, 4, 2, 5][rotation])]));
        nbt::put(&mut tag, "block_pos", json!([11, pos]));
        for (name, value) in [
            ("ItemRotation", json!([1, 0])),
            ("ItemDropChance", json!([5, 1.0])),
            ("Invisible", json!([1, 0])),
            ("Fixed", json!([1, 0])),
        ] {
            nbt::put(&mut tag, name, value);
        }
        let mut item = nbt::compound();
        nbt::put(&mut item, "id", nbt::string("minecraft:elytra"));
        nbt::put(&mut item, "count", nbt::int(1));
        nbt::put(&mut tag, "Item", item);
    }
    nbt::put(
        &mut tag,
        "Pos",
        nbt::list(6, point.into_iter().map(|v| json!([6, v])).collect()),
    );
    nbt::put(
        &mut tag,
        "Rotation",
        nbt::list(5, vec![json!([5, yaw]), json!([5, 0.0])]),
    );
    tag
}

#[cfg(test)]
mod tests {
    use super::*;
    #[test]
    fn reference_iteration_matches_actual_fastutil_including_zero_and_rehashes() {
        let source: Value = serde_json::from_str(include_str!(
            "../../../tests/fixtures/end-city-reference-order-26.3.json"
        ))
        .unwrap();
        let mut keys = 0;
        for case in source["cases"].as_array().unwrap() {
            let starts: Vec<_> = case["chunks"]
                .as_array()
                .unwrap()
                .iter()
                .map(|at| {
                    Arc::new(structures::empty_start(
                        ChunkRequest {
                            seed: 0,
                            chunk_x: at[0].as_i64().unwrap() as i32,
                            chunk_z: at[1].as_i64().unwrap() as i32,
                            min_y: 0,
                            height: 256,
                            base_height: 64.,
                            amplitude: 48.,
                            frequency: 0.008,
                            reserved: 0,
                        },
                        0,
                        0,
                    ))
                })
                .collect();
            let actual: Vec<_> = reference_order(starts.iter().collect())
                .iter()
                .map(|s| packed(s.chunk) as i64)
                .collect();
            assert_eq!(json!(actual), case["keys"]);
            keys += actual.len();
        }
        assert_eq!(keys, 456);
    }
}
