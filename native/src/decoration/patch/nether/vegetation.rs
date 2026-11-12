//! Spread patches, vine columns and hanging pillars replay against live blocks.
use super::*;

fn in_height(w: &World, y: i32) -> bool {
    y >= w.request.min_y && y < w.request.min_y + w.request.height as i32
}

fn difference(rng: &mut Rng, x: i32, up: i32, down: i32) -> [i32; 3] {
    [
        rng.below(x) - rng.below(x),
        rng.below(up) - rng.below(down),
        rng.below(x) - rng.below(x),
    ]
}

#[derive(Clone, Deserialize)]
pub struct SpreadPatch {
    tries: i32,
    xz_spread: i32,
    y_spread: i32,
    origin: Option<Predicate>,
    child: Placed,
}

impl SpreadPatch {
    pub(super) fn validate(&self, palette: usize) -> bool {
        (1..=4096).contains(&self.tries)
            && (0..=15).contains(&self.xz_spread)
            && (0..=256).contains(&self.y_spread)
            && self.origin.as_ref().is_none_or(|p| p.validate(palette))
            && self.child.validate(palette)
    }

    pub(super) fn place(&self, w: &mut World, at: [i32; 3], rng: &mut Rng) -> bool {
        if self.origin.as_ref().is_some_and(|p| !w.test(p, at)) {
            return false;
        }
        let mut placed = false;
        for _ in 0..self.tries {
            let p = add(
                at,
                difference(
                    rng,
                    self.xz_spread + 1,
                    self.y_spread + 1,
                    self.y_spread + 1,
                ),
            );
            placed |= w.placed(&self.child, p, rng, 0);
        }
        placed
    }
}

#[derive(Clone, Deserialize)]
struct VineColumn {
    air: Predicate,
    support: Predicate,
    plant: u16,
    tips: [u16; 9],
}

impl VineColumn {
    fn validate(&self, palette: usize) -> bool {
        self.air.validate(palette)
            && self.support.validate(palette)
            && [self.plant]
                .iter()
                .chain(&self.tips)
                .all(|&id| (id as usize) < palette)
    }

    fn place(&self, w: &mut World, mut at: [i32; 3], dir: i32, max_height: i32, rng: &mut Rng) {
        let mut height = 1 + if max_height == 1 {
            0
        } else {
            rng.below(max_height)
        };
        if rng.below(6) == 0 {
            height *= 2;
        }
        if rng.below(5) == 0 {
            height = 1;
        }
        // Downward vines include the source's zero-based extra iteration.
        let steps = height + i32::from(dir < 0);
        for i in 1..=steps {
            if w.test(&self.air, at) {
                if i == steps || !w.test(&self.air, add(at, [0, dir, 0])) {
                    w.write(at, self.tips[rng.below(9) as usize]);
                    break;
                }
                w.write(at, self.plant);
            }
            at[1] += dir;
        }
    }
}

#[derive(Clone, Deserialize)]
pub struct WeepingVines {
    #[serde(flatten)]
    column: VineColumn,
    wart: u16,
}

impl WeepingVines {
    pub(super) fn validate(&self, palette: usize) -> bool {
        self.column.validate(palette) && (self.wart as usize) < palette
    }

    pub(super) fn place(&self, w: &mut World, at: [i32; 3], rng: &mut Rng) -> bool {
        let air = &self.column.air;
        let support = &self.column.support;
        if !w.test(air, at) || !w.test(support, add(at, [0, 1, 0])) {
            return false;
        }
        w.write(at, self.wart);
        for _ in 0..200 {
            let p = add(at, difference(rng, 6, 2, 5));
            if !w.test(air, p) {
                continue;
            }
            let mut neighbors = DIRECTIONS.iter().filter(|&&d| w.test(support, add(p, d)));
            if neighbors.next().is_some() && neighbors.next().is_none() {
                w.write(p, self.wart);
            }
        }
        for _ in 0..100 {
            let p = add(at, difference(rng, 8, 2, 7));
            if w.test(air, p) && w.test(support, add(p, [0, 1, 0])) {
                self.column.place(w, p, -1, 8, rng);
            }
        }
        true
    }
}

#[derive(Clone, Deserialize)]
pub struct TwistingVines {
    #[serde(flatten)]
    column: VineColumn,
    spread_width: i32,
    spread_height: i32,
    max_height: i32,
}

impl TwistingVines {
    pub(super) fn validate(&self, palette: usize) -> bool {
        self.column.validate(palette)
            && (1..=15).contains(&self.spread_width)
            && (1..=256).contains(&self.spread_height)
            && (1..=256).contains(&self.max_height)
    }

    pub(super) fn place(&self, w: &mut World, at: [i32; 3], rng: &mut Rng) -> bool {
        let air = &self.column.air;
        let support = &self.column.support;
        if !w.test(air, at) || !w.test(support, add(at, [0, -1, 0])) {
            return false;
        }
        let width = self.spread_width;
        let height = self.spread_height;
        for _ in 0..width * width {
            let mut p = add(
                at,
                [
                    rng.below(2 * width + 1) - width,
                    rng.below(2 * height + 1) - height,
                    rng.below(2 * width + 1) - width,
                ],
            );
            p[1] -= 1;
            while in_height(w, p[1]) && w.test(air, p) {
                p[1] -= 1;
            }
            if in_height(w, p[1]) && w.test(support, p) {
                p[1] += 1;
                if w.test(air, p) {
                    self.column.place(w, p, 1, self.max_height, rng);
                }
            }
        }
        true
    }
}

#[derive(Clone, Deserialize)]
pub struct BasaltPillar {
    air: Predicate,
    state: u16,
}

impl BasaltPillar {
    pub(super) fn validate(&self, palette: usize) -> bool {
        self.air.validate(palette) && (self.state as usize) < palette
    }

    pub(super) fn place(&self, w: &mut World, mut at: [i32; 3], rng: &mut Rng) -> bool {
        const SIDES: [[i32; 3]; 4] = [[0, 0, -1], [0, 0, 1], [-1, 0, 0], [1, 0, 0]];
        if !w.test(&self.air, at) || w.test(&self.air, add(at, [0, 1, 0])) {
            return false;
        }
        let mut hanging = [true; 4];
        while in_height(w, at[1]) && w.test(&self.air, at) {
            w.write(at, self.state);
            for (side, active) in SIDES.into_iter().zip(&mut hanging) {
                *active = *active && rng.below(10) != 0;
                if *active {
                    w.write(add(at, side), self.state);
                }
            }
            at[1] -= 1;
        }
        if !in_height(w, at[1]) {
            return true;
        }
        for side in SIDES {
            if rng.below(2) == 0 {
                w.write(add(at, add(side, [0, 1, 0])), self.state);
            }
        }
        for x in -3i32..=3 {
            for z in -3i32..=3 {
                if rng.below(10) >= 10 - x.abs() * z.abs() {
                    continue;
                }
                let mut p = add(at, [x, 0, z]);
                for _ in 0..3 {
                    if !w.test(&self.air, add(p, [0, -1, 0])) {
                        break;
                    }
                    p[1] -= 1;
                }
                if !w.test(&self.air, add(p, [0, -1, 0])) {
                    w.write(p, self.state);
                }
            }
        }
        true
    }
}
