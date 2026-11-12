//! Pointed dripstone states and cave-column searches shared by the formation recipes.
use super::{Predicate, Rng, World};
use serde::Deserialize;

mod cluster;
mod large;
mod provider;

type Pos = [i32; 3];
const HORIZONTAL: [Pos; 4] = [[0, 0, -1], [1, 0, 0], [0, 0, 1], [-1, 0, 0]];
const NEIGHBORS: [Pos; 6] = [
    [0, -1, 0],
    [0, 1, 0],
    [0, 0, -1],
    [0, 0, 1],
    [-1, 0, 0],
    [1, 0, 0],
];

#[derive(Clone, Deserialize)]
#[serde(tag = "shape", rename_all = "snake_case")]
pub enum Recipe {
    Pointed {
        spikes: Spikes,
        open: Predicate,
        taller: f32,
        spread: [f32; 3],
    },
    Cluster {
        spikes: Spikes,
        cluster: cluster::Recipe,
    },
    Large {
        large: large::Recipe,
    },
}

impl Recipe {
    pub(in crate::decoration) fn validate(&self, palette: usize) -> bool {
        match self {
            Self::Pointed {
                spikes,
                open,
                taller,
                spread,
            } => {
                spikes.validate(palette)
                    && open.validate(palette)
                    && spread
                        .iter()
                        .chain([taller])
                        .all(|p| (0.0..=1.0).contains(p))
            }
            Self::Cluster { spikes, cluster } => {
                spikes.validate(palette) && cluster.validate(palette)
            }
            Self::Large { large } => large.validate(palette),
        }
    }

    pub(super) fn place(&self, world: &mut World, at: Pos, rng: &mut Rng) -> bool {
        match self {
            Self::Pointed {
                spikes,
                open,
                taller,
                spread,
            } => pointed(world, at, rng, spikes, open, *taller, *spread),
            Self::Cluster { spikes, cluster } => cluster.place(world, at, rng, spikes),
            Self::Large { large } => large.place(world, at, rng),
        }
    }
}

#[derive(Clone, Deserialize)]
pub struct Spikes {
    block: u16,
    base: Predicate,
    replaceable: Predicate,
    water: Predicate,
    // Direction (down, up), thickness (base to tip), then dry/waterlogged.
    states: [[[u16; 2]; 5]; 2],
}

impl Spikes {
    fn validate(&self, palette: usize) -> bool {
        std::iter::once(self.block)
            .chain(self.states.iter().flatten().flatten().copied())
            .all(|id| id != 0 && usize::from(id) < palette)
            && [&self.base, &self.replaceable, &self.water]
                .into_iter()
                .all(|p| p.validate(palette))
    }

    fn replace(&self, world: &mut World, at: Pos) -> bool {
        if !world.test(&self.replaceable, at) {
            return false;
        }
        world.write(at, self.block);
        true
    }

    fn grow(&self, world: &mut World, at: Pos, direction: i32, length: i32, merge: bool) {
        if length <= 0 || !world.test(&self.base, offset(at, [0, -direction, 0])) {
            return;
        }
        let states = &self.states[usize::from(direction > 0)];
        for i in 0..length {
            let thickness = match length - i {
                1 => {
                    if merge {
                        4
                    } else {
                        3
                    }
                }
                2 => 2,
                _ if i == 0 => 0,
                _ => 1,
            };
            let pos = offset(at, [0, direction * i, 0]);
            world.write(
                pos,
                states[thickness][usize::from(world.test(&self.water, pos))],
            );
        }
    }
}

fn pointed(
    world: &mut World,
    at: Pos,
    rng: &mut Rng,
    spikes: &Spikes,
    open: &Predicate,
    taller: f32,
    spread: [f32; 3],
) -> bool {
    let ceiling = world.test(&spikes.base, offset(at, [0, 1, 0]));
    let floor = world.test(&spikes.base, offset(at, [0, -1, 0]));
    let direction = match (ceiling, floor) {
        (true, true) => {
            if rng.below(2) == 0 {
                1
            } else {
                -1
            }
        }
        (true, false) => -1,
        (false, true) => 1,
        (false, false) => return false,
    };
    let support = offset(at, [0, -direction, 0]);
    spikes.replace(world, support);
    for side in HORIZONTAL {
        let mut pos = offset(support, side);
        for (step, chance) in spread.into_iter().enumerate() {
            if rng.unit() > f64::from(chance) {
                break;
            }
            spikes.replace(world, pos);
            if step < 2 {
                pos = offset(pos, NEIGHBORS[rng.below(6) as usize]);
            }
        }
    }
    let length = 1 + i32::from(
        rng.unit() < f64::from(taller) && world.test(open, offset(at, [0, direction, 0])),
    );
    spikes.grow(world, at, direction, length, false);
    true
}

fn offset([x, y, z]: Pos, [dx, dy, dz]: Pos) -> Pos {
    [x + dx, y + dy, z + dz]
}

fn at_y([x, _, z]: Pos, y: i32) -> Pos {
    [x, y, z]
}

fn scan(
    world: &World,
    at: Pos,
    range: i32,
    open: &Predicate,
    boundary: &Predicate,
) -> Option<(Option<i32>, Option<i32>)> {
    if !world.test(open, at) {
        return None;
    }
    let edge = |direction| {
        let mut point = at;
        for _ in 1..range {
            if !world.test(open, point) {
                break;
            }
            point[1] += direction;
        }
        world.test(boundary, point).then_some(point[1])
    };
    Some((edge(-1), edge(1)))
}
