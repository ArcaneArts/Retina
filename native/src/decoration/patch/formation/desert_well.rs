//! A desert well writes its foundation, pool, rim, roof and buried loot in source order.
use super::{Pos, Predicate, Rng, World, offset};
use crate::decoration::WELL_ARCHAEOLOGY;
use serde::Deserialize;

const POOL: [Pos; 5] = [[0, 0, 0], [1, 0, 0], [0, 0, 1], [-1, 0, 0], [0, 0, -1]];

#[derive(Clone, Deserialize)]
pub struct Recipe {
    sand: u16,
    sandstone: u16,
    slab: u16,
    water: u16,
    suspicious_sand: u16,
    air: Predicate,
}

impl Recipe {
    pub(super) fn validate(&self, palette: usize) -> bool {
        [self.sand, self.sandstone, self.slab, self.water, self.suspicious_sand]
            .into_iter()
            .all(|id| usize::from(id) < palette)
            && self.air.validate(palette)
    }

    pub(super) fn place(&self, world: &mut World, mut at: Pos, rng: &mut Rng) -> bool {
        at[1] += 1;
        while at[1] > world.request.min_y + 2 && world.test(&self.air, at) {
            at[1] -= 1;
        }
        if world.material(at) != Some(self.sand) {
            return false;
        }
        for z in -2..=2 {
            for x in -2..=2 {
                if world.test(&self.air, offset(at, [x, -1, z]))
                    && world.test(&self.air, offset(at, [x, -2, z]))
                {
                    return false;
                }
            }
        }
        for z in -2i32..=2 {
            for x in -2i32..=2 {
                for y in -2..=0 {
                    world.write(offset(at, [x, y, z]), self.sandstone);
                }
                if x.abs() == 2 || z.abs() == 2 {
                    let rim = if x == 0 || z == 0 { self.slab } else { self.sandstone };
                    world.write(offset(at, [x, 1, z]), rim);
                }
            }
        }
        for [x, _, z] in POOL {
            world.write(offset(at, [x, 0, z]), self.water);
            world.write(offset(at, [x, -1, z]), self.sand);
        }
        for z in -1i32..=1 {
            for x in -1i32..=1 {
                let roof = if x == 0 && z == 0 { self.sandstone } else { self.slab };
                world.write(offset(at, [x, 4, z]), roof);
                if x.abs() == 1 && z.abs() == 1 {
                    for y in 1..=3 {
                        world.write(offset(at, [x, y, z]), self.sandstone);
                    }
                }
            }
        }
        for y in [-1, -2] {
            let [x, _, z] = POOL[rng.below(POOL.len() as i32) as usize];
            world.write_command(offset(at, [x, y, z]), self.suspicious_sand, WELL_ARCHAEOLOGY, 0);
        }
        true
    }
}
