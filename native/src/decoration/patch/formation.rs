//! Local rock and ice formations placed into the live decoration overlay.
use super::{Predicate, Rng, World};
use serde::Deserialize;

mod iceberg;

type Pos = [i32; 3];
const HORIZONTAL: [Pos; 4] = [[0, 0, -1], [0, 0, 1], [-1, 0, 0], [1, 0, 0]];
const ADJACENT: [Pos; 6] = [
    [0, -1, 0],
    [0, 1, 0],
    HORIZONTAL[0],
    HORIZONTAL[1],
    HORIZONTAL[2],
    HORIZONTAL[3],
];

#[derive(Clone, Deserialize)]
#[serde(tag = "shape", rename_all = "snake_case")]
pub enum Recipe {
    BlockBlob {
        block: u16,
        ground: Predicate,
    },
    BlueIce {
        block: u16,
        water: Predicate,
        packed_ice: Predicate,
        replaceable: Predicate,
    },
    IceSpike {
        block: u16,
        air: Predicate,
        snow: Predicate,
        replaceable: Predicate,
    },
    Iceberg {
        iceberg: iceberg::Recipe,
    },
}

impl Recipe {
    pub(in crate::decoration) fn validate(&self, palette: usize) -> bool {
        let valid = |id: u16| usize::from(id) < palette;
        match self {
            Self::BlockBlob { block, ground } => valid(*block) && ground.validate(palette),
            Self::BlueIce {
                block,
                water,
                packed_ice,
                replaceable,
            } => {
                valid(*block)
                    && water.validate(palette)
                    && packed_ice.validate(palette)
                    && replaceable.validate(palette)
            }
            Self::IceSpike {
                block,
                air,
                snow,
                replaceable,
            } => {
                valid(*block)
                    && air.validate(palette)
                    && snow.validate(palette)
                    && replaceable.validate(palette)
            }
            Self::Iceberg { iceberg } => iceberg.validate(palette),
        }
    }

    pub(super) fn place(&self, world: &mut World, at: Pos, rng: &mut Rng) -> bool {
        match self {
            Self::BlockBlob { block, ground } => blob(world, at, rng, *block, ground),
            Self::BlueIce {
                block,
                water,
                packed_ice,
                replaceable,
            } => blue_ice(world, at, rng, *block, water, packed_ice, replaceable),
            Self::IceSpike {
                block,
                air,
                snow,
                replaceable,
            } => spike(world, at, rng, *block, air, snow, replaceable),
            Self::Iceberg { iceberg } => iceberg.place(world, at, rng),
        }
    }
}

fn offset([x, y, z]: Pos, [dx, dy, dz]: Pos) -> Pos {
    [x + dx, y + dy, z + dz]
}

fn blob(world: &mut World, mut at: Pos, rng: &mut Rng, block: u16, ground: &Predicate) -> bool {
    let min = world.request.min_y + 3;
    while at[1] > min && !world.test(ground, offset(at, [0, -1, 0])) {
        at[1] -= 1;
    }
    if at[1] <= min {
        return false;
    }
    for _ in 0..3 {
        let [rx, ry, rz] = [rng.below(2), rng.below(2), rng.below(2)];
        let radius = (rx + ry + rz) as f32 * 0.333 + 0.5;
        for dz in -rz..=rz {
            for dy in -ry..=ry {
                for dx in -rx..=rx {
                    if (dx * dx + dy * dy + dz * dz) as f32 <= radius * radius {
                        world.write(offset(at, [dx, dy, dz]), block);
                    }
                }
            }
        }
        at = offset(at, [rng.below(2) - 1, -rng.below(2), rng.below(2) - 1]);
    }
    true
}

fn blue_ice(
    world: &mut World,
    at: Pos,
    rng: &mut Rng,
    block: u16,
    water: &Predicate,
    packed_ice: &Predicate,
    replaceable: &Predicate,
) -> bool {
    if at[1] >= world.profile.sea_level
        || !(world.test(water, at) || world.test(water, offset(at, ADJACENT[0])))
        || !ADJACENT[1..]
            .iter()
            .any(|&side| world.test(packed_ice, offset(at, side)))
    {
        return false;
    }
    world.write(at, block);
    for _ in 0..200 {
        let dy = rng.below(5) - rng.below(6);
        let range = if dy < 2 { 3 + dy / 2 } else { 3 };
        if range < 1 {
            continue;
        }
        let pos = offset(at, [rng.triangle(range - 1), dy, rng.triangle(range - 1)]);
        if world.test(replaceable, pos)
            && ADJACENT
                .iter()
                .any(|&side| world.material(offset(pos, side)) == Some(block))
        {
            world.write(pos, block);
        }
    }
    true
}

fn spike(
    world: &mut World,
    mut at: Pos,
    rng: &mut Rng,
    block: u16,
    air: &Predicate,
    snow: &Predicate,
    replaceable: &Predicate,
) -> bool {
    while at[1] > world.request.min_y + 2 && world.test(air, at) {
        at[1] -= 1;
    }
    if !world.test(snow, at) {
        return false;
    }
    at[1] += rng.below(4);
    let height = 7 + rng.below(4);
    let width = height / 4 + rng.below(2);
    if width > 1 && rng.below(60) == 0 {
        at[1] += 10 + rng.below(30);
    }

    for y in 0..height {
        let radius = (1.0 - y as f32 / height as f32) * width as f32;
        let extent = radius.ceil() as i32;
        for x in -extent..=extent {
            for z in -extent..=extent {
                let distance = (x.abs() as f32 - 0.25).powi(2) + (z.abs() as f32 - 0.25).powi(2);
                let edge = x.abs() == extent || z.abs() == extent;
                if (x != 0 || z != 0) && distance > radius * radius || edge && rng.unit() > 0.75 {
                    continue;
                }
                let above = offset(at, [x, y, z]);
                if world.test(replaceable, above) {
                    world.write(above, block);
                }
                if y != 0 && extent > 1 {
                    let below = offset(at, [x, -y, z]);
                    if world.test(replaceable, below) {
                        world.write(below, block);
                    }
                }
            }
        }
    }

    let root = (width - 1).clamp(0, 1);
    for x in -root..=root {
        for z in -root..=root {
            let mut pos = offset(at, [x, -1, z]);
            let mut segment = if x.abs() == 1 && z.abs() == 1 {
                rng.below(5)
            } else {
                50
            };
            while pos[1] > 50
                && (world.test(replaceable, pos) || world.material(pos) == Some(block))
            {
                world.write(pos, block);
                pos[1] -= 1;
                segment -= 1;
                if segment <= 0 {
                    pos[1] -= 1 + rng.below(5);
                    segment = rng.below(5);
                }
            }
        }
    }
    true
}
