//! Underwater feature recipes evaluated against the ordered decoration overlay.
use super::{Predicate, Rng, World};
use crate::decoration::placement::IntProvider;
use serde::Deserialize;

mod coral;

const OCEAN_FLOOR: u8 = 3;

#[derive(Clone, Deserialize)]
pub struct Recipe {
    water: Predicate,
    #[serde(flatten)]
    growth: Growth,
}

#[derive(Clone, Deserialize)]
#[serde(tag = "growth", rename_all = "snake_case")]
enum Growth {
    Coral {
        coral: coral::Recipe,
    },
    Kelp {
        body: u16,
        tips: [u16; 4],
        support: Predicate,
        below_is_not_tip: Predicate,
    },
    Seagrass {
        tall_probability: f64,
        short: u16,
        tall: [u16; 2],
        support: Predicate,
        full_water: Predicate,
    },
    SeaPickle {
        count: IntProvider,
        pickles: [u16; 4],
        support: Predicate,
    },
    UnderwaterMagma {
        material: u16,
        floor_search_range: i32,
        radius: i32,
        probability: f64,
        solid: Predicate,
    },
}

impl Recipe {
    pub(in crate::decoration) fn validate(&self, palette: usize) -> bool {
        let id = |m| m != 0 && (m as usize) < palette;
        let chance = |p| (0.0..=1.0).contains(&p);
        self.water.validate(palette)
            && match &self.growth {
                Growth::Coral { coral } => coral.validate(palette),
                Growth::Kelp {
                    body,
                    tips,
                    support,
                    below_is_not_tip,
                } => {
                    id(*body)
                        && tips.iter().copied().all(id)
                        && support.validate(palette)
                        && below_is_not_tip.validate(palette)
                }
                Growth::Seagrass {
                    tall_probability,
                    short,
                    tall,
                    support,
                    full_water,
                } => {
                    chance(*tall_probability)
                        && id(*short)
                        && tall.iter().copied().all(id)
                        && support.validate(palette)
                        && full_water.validate(palette)
                }
                Growth::SeaPickle {
                    count,
                    pickles,
                    support,
                } => {
                    count
                        .bounds()
                        .is_ok_and(|(min, max)| min >= 0 && max <= 256)
                        && pickles.iter().copied().all(id)
                        && support.validate(palette)
                }
                Growth::UnderwaterMagma {
                    material,
                    floor_search_range,
                    radius,
                    probability,
                    solid,
                } => {
                    id(*material)
                        && (0..=512).contains(floor_search_range)
                        && (0..=64).contains(radius)
                        && chance(*probability)
                        && solid.validate(palette)
                }
            }
    }

    fn ocean_floor(&self, world: &World, at: [i32; 3]) -> Option<[i32; 3]> {
        let [x, _, z] = at;
        let y =
            world
                .overlay
                .height(world.field, world.profile, world.request, x, z, OCEAN_FLOOR)?;
        let at = [x, y, z];
        world.test(&self.water, at).then_some(at)
    }

    pub(super) fn place(&self, world: &mut World, at: [i32; 3], rng: &mut Rng) -> bool {
        match &self.growth {
            Growth::Coral { coral } => coral.place(world, at, rng, &self.water),
            Growth::Kelp {
                body,
                tips,
                support,
                below_is_not_tip,
            } => {
                let Some(mut at) = self.ocean_floor(world, at) else {
                    return false;
                };
                let height = 1 + rng.below(10);
                for layer in 0..=height {
                    let above = [at[0], at[1] + 1, at[2]];
                    if world.test(&self.water, at)
                        && world.test(&self.water, above)
                        && world.test(support, at)
                    {
                        if layer == height {
                            world.write(at, tips[rng.below(4) as usize]);
                            return true;
                        }
                        world.write(at, *body);
                        at = above;
                    } else {
                        let last = [at[0], at[1] - 1, at[2]];
                        if layer > 0
                            && world.test(support, last)
                            && world.test(below_is_not_tip, last)
                        {
                            world.write(last, tips[rng.below(4) as usize]);
                            return true;
                        }
                        return false;
                    }
                }
                false
            }
            Growth::Seagrass {
                tall_probability,
                short,
                tall,
                support,
                full_water,
            } => {
                let at = [at[0] + rng.triangle(7), at[1], at[2] + rng.triangle(7)];
                let Some(at) = self.ocean_floor(world, at) else {
                    return false;
                };
                let is_tall = rng.unit() < *tall_probability;
                if !world.test(support, at) || is_tall && !world.test(full_water, at) {
                    return false;
                }
                if is_tall {
                    let above = [at[0], at[1] + 1, at[2]];
                    if world.test(&self.water, above) {
                        world.write(at, tall[0]);
                        world.write(above, tall[1]);
                    }
                } else {
                    world.write(at, *short);
                }
                true
            }
            Growth::SeaPickle {
                count,
                pickles,
                support,
            } => {
                let mut placed = false;
                for _ in 0..count.sample(rng) {
                    let at = [at[0] + rng.triangle(7), at[1], at[2] + rng.triangle(7)];
                    let floor = self.ocean_floor(world, at);
                    let material = pickles[rng.below(4) as usize];
                    if let Some(at) = floor
                        && world.test(support, at)
                    {
                        world.write(at, material);
                        placed = true;
                    }
                }
                placed
            }
            Growth::UnderwaterMagma {
                material,
                floor_search_range,
                radius,
                probability,
                solid,
            } => {
                if !world.test(&self.water, at) {
                    return false;
                }
                let [x, y, z] = at;
                let Some(floor) = (1..*floor_search_range).map(|dy| y - dy).find(|&y| {
                    self.water.test(
                        [x, y, z],
                        world.field,
                        world.profile,
                        world.request,
                        world.overlay,
                    ) == Some(false)
                }) else {
                    return false;
                };
                let mut placed = false;
                for z in z - radius..=z + radius {
                    for y in floor - radius..=floor + radius {
                        for x in x - radius..=x + radius {
                            if rng.unit() < *probability
                                && [
                                    [x, y, z],
                                    [x, y - 1, z],
                                    [x - 1, y, z],
                                    [x + 1, y, z],
                                    [x, y, z - 1],
                                    [x, y, z + 1],
                                ]
                                .into_iter()
                                .all(|at| world.test(solid, at))
                            {
                                world.write([x, y, z], *material);
                                placed = true;
                            }
                        }
                    }
                }
                placed
            }
        }
    }
}
