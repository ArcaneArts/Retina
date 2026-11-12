//! Optional registered tree placers. Profiles without these programs retain their original geometry.
use super::placement::{IntProvider, Overlay, Predicate};
use super::{FEATURE, Field, PLANT, Rng, WorldBlock};
use crate::tree_shapes::{Crown, Wood};
use crate::{ChunkRequest, profile::WorldProfile};
use serde::Deserialize;

#[derive(Clone, Deserialize)]
pub struct Geometry {
    pub(super) trunk: Option<Trunk>,
    pub(super) foliage: Option<Foliage>,
    pub(super) roots: Option<Roots>,
}

#[derive(Clone, Deserialize)]
#[serde(tag = "kind", rename_all = "snake_case")]
pub(super) enum Trunk {
    Bending {
        length: IntProvider,
        leaves_from: i32,
    },
    Cherry {
        branch_count: IntProvider,
        length: IntProvider,
        start: [i32; 2],
        end: IntProvider,
    },
    UpwardsBranching {
        steps: IntProvider,
        extra_length: IntProvider,
        chance: f64,
        can_grow_through: Predicate,
    },
}

#[derive(Clone, Deserialize)]
#[serde(tag = "kind", rename_all = "snake_case")]
pub(super) enum Foliage {
    Cherry {
        radius: IntProvider,
        offset: IntProvider,
        height: IntProvider,
        corner_hole: f64,
        bottom_hole: f64,
        hanging: f64,
        extension: f64,
    },
    RandomSpread {
        radius: IntProvider,
        height: IntProvider,
        attempts: u16,
    },
}

#[derive(Clone, Deserialize)]
pub(super) struct Roots {
    pub trunk_offset: IntProvider,
    pub width: i32,
    pub length: i32,
    pub skew: f64,
    pub grow_through: Predicate,
    pub muddy_in: Predicate,
    pub water: Predicate,
    pub wet: u16,
    pub muddy: u16,
    above: Option<AboveRoot>,
}

#[derive(Clone, Deserialize)]
struct AboveRoot {
    chance: f64,
    material: u16,
}

impl Geometry {
    pub fn validate(&self, palette: usize) -> bool {
        self.trunk.as_ref().is_none_or(|trunk| match trunk {
            Trunk::Bending { length, leaves_from } => in_range(length, 1, 64) && *leaves_from > 0,
            Trunk::Cherry {
                branch_count,
                length,
                start,
                end,
            } => {
                in_range(branch_count, 1, 3)
                    && in_range(length, 2, 16)
                    && (-16..0).contains(&start[0])
                    && (start[0] + 1..=0).contains(&start[1])
                    && in_range(end, -16, 16)
            }
            Trunk::UpwardsBranching {
                steps,
                extra_length,
                chance,
                can_grow_through,
            } => {
                in_range(steps, 1, 16)
                    && in_range(extra_length, 0, 16)
                    && probability(*chance)
                    && can_grow_through.validate(palette)
            }
        }) && self.foliage.as_ref().is_none_or(|foliage| match foliage {
            Foliage::Cherry {
                radius,
                offset,
                height,
                corner_hole,
                bottom_hole,
                hanging,
                extension,
            } => {
                in_range(radius, 0, 16)
                    && in_range(offset, -16, 16)
                    && in_range(height, 4, 16)
                    && [corner_hole, bottom_hole, hanging, extension]
                        .into_iter()
                        .all(|p| probability(*p))
            }
            Foliage::RandomSpread {
                radius,
                height,
                attempts,
            } => in_range(radius, 1, 16) && in_range(height, 1, 512) && *attempts <= 256,
        }) && self
            .roots
            .as_ref()
            .is_none_or(|roots| roots.validate(palette))
    }
}

fn in_range(provider: &IntProvider, min: i32, max: i32) -> bool {
    provider.bounds().is_ok_and(|(a, b)| a >= min && b <= max)
}

fn probability(value: f64) -> bool {
    (0.0..=1.0).contains(&value)
}

const HORIZONTAL: [(i32, i32); 4] = [(1, 0), (-1, 0), (0, 1), (0, -1)];

fn crown(x: i32, y: i32, z: i32) -> Crown {
    Crown {
        pos: (x, y, z),
        radius_offset: 0,
        width: 1,
    }
}

impl Trunk {
    pub fn place(
        &self,
        x: i32,
        y: i32,
        z: i32,
        height: i32,
        rng: &mut Rng,
    ) -> (Vec<Wood>, Vec<Crown>) {
        let mut wood = Vec::new();
        let mut crowns = Vec::new();
        match self {
            Self::Bending { length, leaves_from } => {
                let (dx, dz) = [(0, -1), (1, 0), (0, 1), (-1, 0)][rng.below(4) as usize];
                let (mut x, mut z) = (x, z);
                for dy in 0..height {
                    if dy + 1 >= height - 1 + rng.below(2) {
                        x += dx;
                        z += dz;
                    }
                    wood.push((x, y + dy, z, 0));
                    if dy >= *leaves_from {
                        crowns.push(crown(x, y + dy, z));
                    }
                }
                for _ in 0..=length.sample(rng) {
                    wood.push((x, y + height, z, 0));
                    crowns.push(crown(x, y + height, z));
                    x += dx;
                    z += dz;
                }
            }
            Self::Cherry {
                branch_count,
                length,
                start,
                end,
            } => {
                let first = (height - 1 + rng.range(*start)).max(0);
                let mut second = (height - 1 + rng.range([start[0], start[1] - 1])).max(0);
                second += i32::from(second >= first);
                let count = branch_count.sample(rng);
                let trunk_height = match count {
                    3 => height,
                    2 => first.max(second) + 1,
                    _ => first + 1,
                };
                wood.extend((0..trunk_height).map(|dy| (x, y + dy, z, 0)));
                if count == 3 {
                    crowns.push(crown(x, y + trunk_height, z));
                }
                let direction = rng.below(4) as usize;
                for (index, from) in [first, second]
                    .into_iter()
                    .take(count.min(2) as usize)
                    .enumerate()
                {
                    let (dx, dz) = HORIZONTAL[direction ^ index];
                    let axis = if dx == 0 { 2 } else { 1 };
                    let end_y = y + height - 1 + end.sample(rng);
                    let mut at = (x, y + from, z);
                    let extended = from == trunk_height - 1 || end_y < at.1;
                    let mut remaining = length.sample(rng) + i32::from(extended);
                    for _ in 0..=i32::from(extended) {
                        at.0 += dx;
                        at.2 += dz;
                        remaining -= 1;
                        wood.push((at.0, at.1, at.2, axis));
                    }
                    while remaining > 0 || at.1 != end_y {
                        let vertical = (end_y - at.1).abs();
                        if rng.unit() * f64::from(remaining + vertical) < f64::from(vertical) {
                            at.1 += (end_y - at.1).signum();
                            wood.push((at.0, at.1, at.2, 0));
                        } else {
                            at.0 += dx;
                            at.2 += dz;
                            remaining -= 1;
                            wood.push((at.0, at.1, at.2, axis));
                        }
                    }
                    crowns.push(crown(at.0, end_y + 1, at.2));
                }
            }
            Self::UpwardsBranching {
                steps,
                extra_length,
                chance,
                ..
            } => {
                for dy in 0..height {
                    wood.push((x, y + dy, z, 0));
                    if dy == height - 1 || rng.unit() >= *chance {
                        continue;
                    }
                    let (dx, dz) = HORIZONTAL[rng.below(4) as usize];
                    let start = (extra_length.sample(rng) - extra_length.sample(rng) - 1).max(0);
                    let mut at = (x, y + dy, z);
                    for climb in
                        (start..(start + steps.sample(rng)).min(height)).filter(|dy| *dy > 0)
                    {
                        at = (at.0 + dx, y + dy + climb, at.2 + dz);
                        wood.push((at.0, at.1, at.2, 0));
                        crowns.push(crown(at.0, at.1, at.2));
                    }
                    if at.1 > y + dy {
                        crowns.push(crown(at.0, at.1 + 1, at.2));
                        crowns.push(crown(at.0, at.1 - 1, at.2));
                    }
                }
                crowns.push(crown(x, y + height, z));
            }
        }
        (wood, crowns)
    }
}

impl Foliage {
    pub fn visit(&self, crowns: &[Crown], rng: &mut Rng, mut leaf: impl FnMut((i32, i32, i32))) {
        match self {
            Self::Cherry {
                radius,
                offset,
                height,
                corner_hole,
                bottom_hole,
                hanging,
                extension,
            } => {
                let radius = radius.sample(rng);
                let height = height.sample(rng);
                for crown in crowns {
                    let (x, y, z) = crown.pos;
                    let y = y + offset.sample(rng);
                    let radius = radius + crown.radius_offset - 1;
                    let rows = [(height - 3, radius - 2), (height - 4, radius - 1)]
                        .into_iter()
                        .chain((0..=height - 5).rev().map(|dy| (dy, radius)))
                        .chain([(-1, radius), (-2, radius - 1)]);
                    for (dy, r) in rows {
                        for dx in -r..r + crown.width {
                            for dz in -r..r + crown.width {
                                let ax = dx.abs().min((dx - crown.width + 1).abs());
                                let az = dz.abs().min((dz - crown.width + 1).abs());
                                let edge = ax == r || az == r;
                                if dy == -1 && edge && rng.unit() < *bottom_hole {
                                    continue;
                                }
                                let corner = ax == r && az == r;
                                if (r > 2 && corner)
                                    || ((corner || r > 2 && ax + az > 2 * r - 2)
                                        && rng.unit() < *corner_hole)
                                {
                                    continue;
                                }
                                let at = (x + dx, y + dy, z + dz);
                                leaf(at);
                                if dy < 0 && edge && rng.unit() < *hanging {
                                    leaf((at.0, at.1 - 1, at.2));
                                    if rng.unit() < *extension {
                                        leaf((at.0, at.1 - 2, at.2));
                                    }
                                }
                            }
                        }
                    }
                }
            }
            Self::RandomSpread {
                radius,
                height,
                attempts,
            } => {
                let radius = radius.sample(rng) - 1;
                let height = height.sample(rng) - 1;
                for crown in crowns {
                    let (x, y, z) = crown.pos;
                    for _ in 0..*attempts {
                        leaf((
                            x + rng.triangle(radius),
                            y + rng.triangle(height),
                            z + rng.triangle(radius),
                        ));
                    }
                }
            }
        }
    }
}

impl Roots {
    fn validate(&self, palette: usize) -> bool {
        in_range(&self.trunk_offset, 0, 16)
            && (1..=12).contains(&self.width)
            && (1..=64).contains(&self.length)
            && probability(self.skew)
            && [self.wet, self.muddy]
                .into_iter()
                .all(|id| (id as usize) < palette)
            && [&self.grow_through, &self.muddy_in, &self.water]
                .into_iter()
                .all(|p| p.validate(palette))
            && self
                .above
                .as_ref()
                .is_none_or(|a| probability(a.chance) && (a.material as usize) < palette)
    }

    pub fn positions(
        &self,
        origin: [i32; 3],
        ground_y: i32,
        field: &Field,
        profile: &WorldProfile,
        request: ChunkRequest,
        overlay: &Overlay,
        rng: &mut Rng,
    ) -> Option<Vec<[i32; 3]>> {
        let test = |predicate: &Predicate, at| {
            predicate.test(at, field, profile, request, overlay) == Some(true)
        };
        let grow = |at: [i32; 3]| {
            (request.min_y..request.min_y + request.height as i32).contains(&at[1])
                && (test(&self.grow_through, at) || test(&self.muddy_in, at))
        };
        let [x, y, z] = origin;
        if (ground_y..y).any(|root_y| !grow([x, root_y, z])) {
            return None;
        }

        let mut positions = vec![[x, y - 1, z]];
        let mut branch = Vec::with_capacity(self.length as usize);
        for (dx, dz) in HORIZONTAL {
            branch.clear();
            let start = [x + dx, y, z + dz];
            if !self.branch(origin, start, [dx, dz], 0, rng, &grow, &mut branch) {
                return None;
            }
            positions.extend_from_slice(&branch);
            positions.push(start);
        }
        positions.retain(|&at| grow(at));
        Some(positions)
    }

    fn branch(
        &self,
        origin: [i32; 3],
        at: [i32; 3],
        direction: [i32; 2],
        depth: i32,
        rng: &mut Rng,
        grow: &impl Fn([i32; 3]) -> bool,
        positions: &mut Vec<[i32; 3]>,
    ) -> bool {
        if depth == self.length || positions.len() > self.length as usize {
            return false;
        }
        let [x, y, z] = at;
        let [dx, dz] = direction;
        let below = [x, y - 1, z];
        let side = [x + dx, y, z + dz];
        let distance = at
            .into_iter()
            .zip(origin)
            .map(|(a, b)| (a - b).abs())
            .sum::<i32>();
        let mut next = [below, below];
        let count = if distance > self.width {
            1
        } else if distance > self.width - 3 {
            if rng.unit() < self.skew {
                next[1] = [side[0], y - 1, side[2]];
                2
            } else {
                1
            }
        } else {
            if rng.unit() >= self.skew && rng.below(2) != 0 {
                next[0] = side;
            }
            1
        };
        for next in next.into_iter().take(count).filter(|&next| grow(next)) {
            positions.push(next);
            if !self.branch(origin, next, direction, depth + 1, rng, grow, positions) {
                return false;
            }
        }
        true
    }

    pub fn place(
        &self,
        material: u16,
        positions: Vec<[i32; 3]>,
        field: &Field,
        profile: &WorldProfile,
        request: ChunkRequest,
        overlay: &Overlay,
        rng: &mut Rng,
        blocks: &mut Vec<WorldBlock>,
    ) {
        let test = |predicate: &Predicate, at| {
            predicate.test(at, field, profile, request, overlay) == Some(true)
        };
        for [x, y, z] in positions {
            let muddy = test(&self.muddy_in, [x, y, z]);
            let material = if muddy {
                self.muddy
            } else if test(&self.water, [x, y, z]) {
                self.wet
            } else {
                material
            };
            blocks.push(WorldBlock {
                x,
                y,
                z,
                material,
                role: FEATURE,
                root_order: 0,
                tick: 0,
                upper: 0,
            });
            if let Some(above) = &self.above {
                if !muddy
                    && y + 1 < request.min_y + request.height as i32
                    && rng.unit() < above.chance
                    && overlay.material(field, profile, request, [x, y + 1, z]) == Some(0)
                {
                    blocks.push(WorldBlock {
                        x,
                        y: y + 1,
                        z,
                        material: above.material,
                        role: PLANT,
                        root_order: 0,
                        tick: 0,
                        upper: 0,
                    });
                }
            }
        }
    }
}
