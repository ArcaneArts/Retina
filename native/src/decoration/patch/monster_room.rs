//! Registered monster-room placement against the live feature overlay.
use super::{Predicate, Rng, World};
use crate::decoration::generation::FeatureEntity;
use serde::Deserialize;

type Pos = [i32; 3];
const SIDES: [Pos; 4] = [[0, 0, -1], [1, 0, 0], [0, 0, 1], [-1, 0, 0]];

#[derive(Clone, Deserialize)]
pub struct Recipe {
    cobble: u16,
    mossy: u16,
    air: u16,
    spawner: u16,
    chests: [u16; 4],
    empty: Predicate,
    solid: Predicate,
    solid_render: Predicate,
    replaceable: Predicate,
    chest_block: Predicate,
    spawner_block: Predicate,
}

fn offset([x, y, z]: Pos, [dx, dy, dz]: Pos) -> Pos {
    [x + dx, y + dy, z + dz]
}

impl Recipe {
    pub(in crate::decoration) fn validate(&self, palette: usize) -> bool {
        [self.cobble, self.mossy, self.air, self.spawner].into_iter().chain(self.chests)
            .all(|id| usize::from(id) < palette)
            && [&self.empty, &self.solid, &self.solid_render, &self.replaceable,
                &self.chest_block, &self.spawner_block].into_iter().all(|p| p.validate(palette))
    }

    pub(super) fn place(&self, world: &mut World, at: Pos, rng: &mut Rng) -> bool {
        let rx = 2 + rng.below(2);
        let rz = 2 + rng.below(2);
        let wall_x = rx + 1;
        let wall_z = rz + 1;
        let mut openings = 0;
        for x in -wall_x..=wall_x {
            for z in -wall_z..=wall_z {
                if !world.test(&self.solid, offset(at, [x, -1, z]))
                    || !world.test(&self.solid, offset(at, [x, 4, z]))
                {
                    return false;
                }
                if (x.abs() == wall_x || z.abs() == wall_z)
                    && world.test(&self.empty, offset(at, [x, 0, z]))
                    && world.test(&self.empty, offset(at, [x, 1, z]))
                {
                    openings += 1;
                }
            }
        }
        if !(1..=5).contains(&openings) {
            return false;
        }

        for x in -wall_x..=wall_x {
            for y in (-1..=3).rev() {
                for z in -wall_z..=wall_z {
                    let p = offset(at, [x, y, z]);
                    if x.abs() == wall_x || z.abs() == wall_z || y == -1 {
                        if !world.test(&self.solid, offset(p, [0, -1, 0])) {
                            world.write(p, self.air);
                        } else if world.test(&self.solid, p) && !world.test(&self.chest_block, p) {
                            let material = if y == -1 && rng.below(4) != 0 { self.mossy } else { self.cobble };
                            if world.test(&self.replaceable, p) {
                                world.write(p, material);
                            }
                        }
                    } else if !world.test(&self.chest_block, p)
                        && !world.test(&self.spawner_block, p)
                        && world.test(&self.replaceable, p)
                    {
                        world.write(p, self.air);
                    }
                }
            }
        }
        for _ in 0..2 {
            for _ in 0..3 {
                let p = offset(at, [rng.range([-rx, rx]), 0, rng.range([-rz, rz])]);
                if !world.test(&self.empty, p)
                    || SIDES.iter().filter(|&&side| world.test(&self.solid, offset(p, side))).count() != 1
                {
                    continue;
                }
                if world.test(&self.replaceable, p) {
                    let facing = self.chest_facing(world, p);
                    world.write_block_entity(p, self.chests[facing], FeatureEntity::DungeonChest);
                    rng.next(); // Keep the loot draw in the shared feature stream.
                }
                break;
            }
        }
        if world.test(&self.replaceable, at) {
            let entity = match rng.below(4) {
                0 => FeatureEntity::SkeletonSpawner,
                3 => FeatureEntity::SpiderSpawner,
                _ => FeatureEntity::ZombieSpawner,
            };
            world.write_block_entity(at, self.spawner, entity);
        }
        true
    }

    fn chest_facing(&self, world: &World, at: Pos) -> usize {
        let neighbors = SIDES.map(|side| offset(at, side));
        if neighbors.iter().any(|&p| world.test(&self.chest_block, p)) {
            return 0;
        }
        let full = neighbors.map(|p| world.test(&self.solid_render, p));
        if full.iter().filter(|&&solid| solid).count() == 1 {
            return (full.iter().position(|&solid| solid).unwrap() + 2) % 4;
        }
        let mut facing = if full[0] { 2 } else { 0 };
        if full[facing] {
            facing = (facing + 1) % 4;
        }
        if full[facing] {
            facing = (facing + 2) % 4;
        }
        facing
    }
}
