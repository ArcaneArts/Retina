//! Coral formations and attachments replayed against the live underwater overlay.
use super::{Predicate, Rng, World};
use serde::Deserialize;

const DIRECTIONS: [[i32; 3]; 4] = [[0, 0, -1], [1, 0, 0], [0, 0, 1], [-1, 0, 0]];
const UP: [i32; 3] = [0, 1, 0];
type Position = [i32; 3];

#[derive(Clone, Copy, Deserialize)]
#[serde(rename_all = "snake_case")]
enum Shape {
    Tree,
    Claw,
    Mushroom,
}

#[derive(Clone, Deserialize)]
pub struct Recipe {
    shape: Shape,
    blocks: Vec<u16>,
    tops: Vec<u16>,
    wall_fans: [Vec<u16>; 4],
    pickles: [u16; 4],
    replaceable: Predicate,
}

impl Recipe {
    pub(super) fn validate(&self, palette: usize) -> bool {
        self.replaceable.validate(palette)
            && self
                .blocks
                .iter()
                .chain(&self.tops)
                .chain(self.wall_fans.iter().flatten())
                .chain(&self.pickles)
                .all(|&id| (id as usize) < palette)
    }

    pub(super) fn place(
        &self,
        world: &mut World,
        at: Position,
        rng: &mut Rng,
        water: &Predicate,
    ) -> bool {
        let Some(material) = choose(&self.blocks, rng) else {
            return false;
        };
        let mut place = |at, rng: &mut Rng| {
            let above = offset(at, UP);
            if !world.test(&self.replaceable, at) || !world.test(water, above) {
                return false;
            }
            world.write(at, material);
            if rng.unit() < 0.25 {
                if let Some(top) = choose(&self.tops, rng) {
                    world.write(above, top);
                }
            } else if rng.unit() < 0.05 {
                world.write(above, self.pickles[rng.below(4) as usize]);
            }
            for (index, direction) in DIRECTIONS.into_iter().enumerate() {
                let side = offset(at, direction);
                if rng.unit() < 0.2
                    && world.test(water, side)
                    && let Some(fan) = choose(&self.wall_fans[index], rng)
                {
                    world.write(side, fan);
                }
            }
            true
        };
        match self.shape {
            Shape::Tree => tree(at, rng, &mut place),
            Shape::Claw => claw(at, rng, &mut place),
            Shape::Mushroom => mushroom(at, rng, &mut place),
        }
    }
}

fn offset(at: Position, direction: [i32; 3]) -> Position {
    std::array::from_fn(|axis| at[axis] + direction[axis])
}

fn choose(materials: &[u16], rng: &mut Rng) -> Option<u16> {
    (!materials.is_empty()).then(|| materials[rng.below(materials.len() as i32) as usize])
}

fn shuffle(directions: &mut [usize], rng: &mut Rng) {
    for i in (1..directions.len()).rev() {
        directions.swap(i, rng.below((i + 1) as i32) as usize);
    }
}

fn tree(
    mut at: Position,
    rng: &mut Rng,
    place: &mut impl FnMut(Position, &mut Rng) -> bool,
) -> bool {
    for _ in 0..1 + rng.below(3) {
        if !place(at, rng) {
            return true;
        }
        at = offset(at, UP);
    }
    let branches = 2 + rng.below(3) as usize;
    let mut directions = [0, 1, 2, 3];
    shuffle(&mut directions, rng);
    for &dir in &directions[..branches] {
        let mut point = offset(at, DIRECTIONS[dir]);
        let mut rise = 0;
        for step in 0..2 + rng.below(5) {
            if !place(point, rng) {
                break;
            }
            rise += 1;
            point = offset(point, UP);
            if step == 0 || rise >= 2 && rng.unit() < 0.25 {
                point = offset(point, DIRECTIONS[dir]);
                rise = 0;
            }
        }
    }
    true
}

fn claw(at: Position, rng: &mut Rng, place: &mut impl FnMut(Position, &mut Rng) -> bool) -> bool {
    if !place(at, rng) {
        return false;
    }
    let primary = rng.below(4) as usize;
    let branches = 2 + rng.below(2) as usize;
    let mut directions = [primary, (primary + 1) % 4, (primary + 3) % 4];
    shuffle(&mut directions, rng);
    for &dir in &directions[..branches] {
        let mut point = offset(at, DIRECTIONS[dir]);
        let stem_length = 1 + rng.below(2);
        let (stem, length) = if dir == primary {
            (DIRECTIONS[dir], 2 + rng.below(3))
        } else {
            point = offset(point, UP);
            (
                [DIRECTIONS[dir], UP][rng.below(2) as usize],
                3 + rng.below(3),
            )
        };
        for _ in 0..stem_length {
            if !place(point, rng) {
                break;
            }
            point = offset(point, stem);
        }
        point = offset(offset(point, stem.map(|n| -n)), UP);
        for _ in 0..length {
            point = offset(point, DIRECTIONS[primary]);
            if !place(point, rng) {
                break;
            }
            if rng.unit() < 0.25 {
                point = offset(point, UP);
            }
        }
    }
    true
}

fn mushroom(
    at: Position,
    rng: &mut Rng,
    place: &mut impl FnMut(Position, &mut Rng) -> bool,
) -> bool {
    let height = 3 + rng.below(3);
    let width = 3 + rng.below(3);
    let depth = 3 + rng.below(3);
    let drop = 1 + rng.below(3);
    for x in 0..=width {
        for y in 0..=height {
            for z in 0..=depth {
                let faces = u8::from(x == 0 || x == width)
                    + u8::from(y == 0 || y == height)
                    + u8::from(z == 0 || z == depth);
                if faces == 1 && rng.unit() >= 0.1 {
                    place(offset(at, [x, y - drop, z]), rng);
                }
            }
        }
    }
    true
}
