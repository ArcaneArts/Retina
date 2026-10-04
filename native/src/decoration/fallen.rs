//! Loaded fallen logs and decorators, with an inexpensive feature-local overlay.
use super::*;
use blocks::Provider;
use placement::{IntProvider, Overlay, Predicate};

const HORIZONTAL: [[i32; 3]; 4] = [[0, 0, -1], [1, 0, 0], [0, 0, 1], [-1, 0, 0]];
#[derive(Clone, Deserialize)]
pub struct Axes {
    pub source: u16,
    pub states: [u16; 2],
}
#[derive(Clone, Deserialize)]
#[serde(tag = "type", rename_all = "snake_case")]
pub enum Decorator {
    AttachedToLogs {
        probability: f32,
        provider: Provider,
        directions: Vec<[i32; 3]>,
    },
    TrunkVine {
        states: [u16; 4],
    },
    ShelfMushroom {
        probability: f32,
        states: [[u16; 2]; 4],
    },
}
#[derive(Clone, Deserialize)]
pub struct Recipe {
    pub trunk: Provider,
    pub log_length: IntProvider,
    pub axes: Vec<Axes>,
    pub stump_decorators: Vec<Decorator>,
    pub log_decorators: Vec<Decorator>,
    pub clearance: Predicate,
    pub sturdy: Predicate,
    pub air: Predicate,
    pub replaceable: Predicate,
    pub water: Predicate,
    pub shelf: Predicate,
}
impl Recipe {
    pub(super) fn validate(&self, palette: usize) -> bool {
        let valid = |id: &u16| (*id as usize) < palette;
        let probability = |v: f32| v.is_finite() && (0.0..=1.0).contains(&v);
        self.trunk.validate(palette)
            && !self.trunk.nullable()
            && self
                .log_length
                .bounds()
                .is_ok_and(|(a, b)| a >= 0 && b <= 15)
            && self.axes.iter().all(|a| a.states.iter().all(valid))
            && self
                .trunk
                .outputs()
                .iter()
                .all(|id| self.axes.iter().any(|a| a.source == *id))
            && [
                &self.clearance,
                &self.sturdy,
                &self.air,
                &self.replaceable,
                &self.water,
                &self.shelf,
            ]
            .iter()
            .all(|p| p.validate(palette))
            && self
                .stump_decorators
                .iter()
                .chain(&self.log_decorators)
                .all(|d| match d {
                    Decorator::AttachedToLogs {
                        probability: p,
                        provider,
                        directions,
                    } => {
                        probability(*p)
                            && provider.validate(palette)
                            && !directions.is_empty()
                            && directions
                                .iter()
                                .all(|v| v.iter().map(|x| (*x as i64).abs()).sum::<i64>() == 1)
                    }
                    Decorator::TrunkVine { states } => states.iter().all(valid),
                    Decorator::ShelfMushroom {
                        probability: p,
                        states,
                    } => probability(*p) && states.iter().flatten().all(valid),
                })
    }
}
fn shift(at: [i32; 3], direction: [i32; 3], n: i32) -> [i32; 3] {
    std::array::from_fn(|i| at[i] + direction[i] * n)
}

struct Context<'a> {
    field: &'a Field,
    profile: &'a WorldProfile,
    request: ChunkRequest,
    overlay: &'a Overlay,
    blocks: &'a mut Vec<WorldBlock>,
    start: usize,
}
impl Context<'_> {
    fn material(&self, at: [i32; 3]) -> Option<u16> {
        if at[1] < self.request.min_y || at[1] >= self.request.min_y + self.request.height as i32 {
            return Some(0);
        }
        self.blocks[self.start..]
            .iter()
            .rev()
            .find(|b| [b.x, b.y, b.z] == at)
            .map(|b| b.material)
            .or_else(|| {
                self.overlay
                    .material(self.field, self.profile, self.request, at)
            })
    }
    fn test(&self, p: &Predicate, at: [i32; 3]) -> bool {
        p.test_with(at, &|pos| self.material(pos)) == Some(true)
    }
    fn put(&mut self, at: [i32; 3], material: u16) {
        self.blocks.push(WorldBlock {
            x: at[0],
            y: at[1],
            z: at[2],
            material,
            upper: 0,
            role: FEATURE,
        });
    }
    fn decorate(
        &mut self,
        recipe: &Recipe,
        decorators: &[Decorator],
        logs: &[[i32; 3]],
        rng: &mut Rng,
    ) {
        for decorator in decorators {
            match decorator {
                Decorator::AttachedToLogs {
                    probability,
                    provider,
                    directions,
                } => {
                    let mut shuffled = logs.to_vec();
                    for i in (2..=shuffled.len()).rev() {
                        let j = rng.below(i as i32) as usize;
                        shuffled.swap(i - 1, j);
                    }
                    for at in shuffled {
                        let direction = directions[rng.below(directions.len() as i32) as usize];
                        let pos = shift(at, direction, 1);
                        if (rng.unit() as f32) <= *probability && self.test(&recipe.air, pos) {
                            let material = provider.sample(rng, pos, &|p| self.material(p));
                            self.put(pos, material);
                        }
                    }
                }
                Decorator::TrunkVine { states } => {
                    for at in logs {
                        for (i, direction) in
                            [HORIZONTAL[3], HORIZONTAL[1], HORIZONTAL[0], HORIZONTAL[2]]
                                .into_iter()
                                .enumerate()
                        {
                            if rng.below(3) > 0 {
                                let pos = shift(*at, direction, 1);
                                if self.test(&recipe.air, pos) {
                                    self.put(pos, states[i]);
                                }
                            }
                        }
                    }
                }
                Decorator::ShelfMushroom {
                    probability,
                    states,
                } => {
                    if (rng.unit() as f32) >= *probability || logs.is_empty() {
                        continue;
                    }
                    // Fallen-tree contexts contain either one stump or a level log.
                    let directions = if logs[0][0] != logs[logs.len() - 1][0] {
                        [0, 2]
                    } else {
                        [1, 3]
                    };
                    for at in logs {
                        for direction in directions {
                            if (rng.unit() as f32) > 0.25 {
                                continue;
                            }
                            let pos = shift(*at, HORIZONTAL[direction], 1);
                            if !self.test(&recipe.replaceable, pos)
                                || self.test(&recipe.water, pos)
                                || HORIZONTAL
                                    .iter()
                                    .any(|d| self.test(&recipe.water, shift(pos, *d, 1)))
                                || [pos, *at].iter().any(|p| {
                                    HORIZONTAL
                                        .iter()
                                        .any(|d| self.test(&recipe.shelf, shift(*p, *d, 1)))
                                })
                            {
                                continue;
                            }
                            self.put(pos, states[direction][rng.below(2) as usize]);
                        }
                    }
                }
            }
        }
    }
}

pub(super) fn place(
    recipe: &Recipe,
    at: [i32; 3],
    rng: &mut Rng,
    field: &Field,
    profile: &WorldProfile,
    request: ChunkRequest,
    overlay: &Overlay,
    blocks: &mut Vec<WorldBlock>,
) {
    let start = blocks.len();
    let mut context = Context {
        field,
        profile,
        request,
        overlay,
        blocks,
        start,
    };
    let stump = recipe.trunk.sample(rng, at, &|p| context.material(p));
    context.put(at, stump);
    context.decorate(recipe, &recipe.stump_decorators, &[at], rng);
    let direction = HORIZONTAL[rng.below(4) as usize];
    let length = recipe.log_length.sample(rng) - 2;
    let mut pos = shift(at, direction, 2 + rng.below(2));
    pos[1] += 1;
    for _ in 0..6 {
        if context.test(&recipe.clearance, pos) && context.test(&recipe.sturdy, pos) {
            break;
        }
        pos[1] -= 1;
    }
    let mut gap = 0;
    for i in 0..length {
        let p = shift(pos, direction, i);
        if !context.test(&recipe.clearance, p) {
            return;
        }
        if !context.test(&recipe.sturdy, p) {
            gap += 1;
            if gap > 2 {
                return;
            }
        } else {
            gap = 0;
        }
    }
    let mut logs = Vec::new();
    for i in 0..length {
        let p = shift(pos, direction, i);
        let source = recipe.trunk.sample(rng, p, &|p| context.material(p));
        let axes = recipe.axes.iter().find(|v| v.source == source).unwrap();
        context.put(p, axes.states[usize::from(direction[2] != 0)]);
        logs.push(p);
    }
    // Minecraft's decorator context sorts by Y after collecting a Java HashSet.
    // Equal-Y logs retain the bucket order, including insertion-order collisions.
    logs.sort_by_key(|p| {
        let h = p[1]
            .wrapping_add(p[2].wrapping_mul(31))
            .wrapping_mul(31)
            .wrapping_add(p[0]) as u32;
        (h ^ (h >> 16)) & if length > 12 { 31 } else { 15 }
    });
    context.decorate(recipe, &recipe.log_decorators, &logs, rng);
}
