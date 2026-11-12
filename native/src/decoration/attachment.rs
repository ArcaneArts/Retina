//! Registered vines and multiface growth on the live GPU/feature substrate.
use super::*;
use placement::{Overlay, Predicate};

pub(super) const DIRECTIONS: [[i32; 3]; 6] = [
    [0, -1, 0],
    [0, 1, 0],
    [0, 0, -1],
    [0, 0, 1],
    [-1, 0, 0],
    [1, 0, 0],
];

#[derive(Clone, Deserialize)]
pub struct State {
    source: u16,
    faces: u8,
    added: [u16; 6],
}
#[derive(Clone, Deserialize)]
pub struct Recipe {
    vines: bool,
    initial: u16,
    wet_initial: u16,
    face_mask: u8,
    states: Vec<State>,
    directions: Vec<usize>,
    search_range: u32,
    spread_chance: f32,
    spread_order: Vec<u8>,
    sculk: bool,
    air: Predicate,
    water: Predicate,
    source_water: Predicate,
    placed_on: Predicate,
    spread_replaceable: Predicate,
    spread_support: Predicate,
    support: [Predicate; 6],
    sturdy: [Predicate; 6],
}
impl Recipe {
    pub(super) fn vine_program(&self) -> Option<(&Predicate, Vec<(u16, &Predicate)>)> {
        if !self.vines {
            return None;
        }
        let initial = self.state(self.initial)?;
        Some((
            &self.air,
            self.directions
                .iter()
                .map(|d| (initial.added[*d], &self.support[*d]))
                .collect(),
        ))
    }
    fn state(&self, material: u16) -> Option<&State> {
        self.states
            .binary_search_by_key(&material, |s| s.source)
            .ok()
            .map(|i| &self.states[i])
    }
    pub(super) fn is_sculk(&self) -> bool {
        self.sculk && !self.vines && self.face_mask == 63
    }
    pub(super) fn faces(&self, material: u16) -> Option<u8> {
        self.state(material).map(|s| s.faces)
    }
    pub(super) fn with_faces(&self, faces: u8, wet: bool) -> u16 {
        let initial = if wet { self.wet_initial } else { self.initial };
        (0..6)
            .filter(|&d| faces & (1 << d) != 0)
            .fold(initial, |id, d| self.state(id).unwrap().added[d])
    }
    pub(super) fn supports(
        &self,
        at: [i32; 3],
        face: usize,
        read: &impl Fn([i32; 3]) -> Option<u16>,
    ) -> bool {
        self.support[face].test_with(at, read) == Some(true)
    }
    pub(super) fn sturdy(
        &self,
        at: [i32; 3],
        face: usize,
        read: &impl Fn([i32; 3]) -> Option<u16>,
    ) -> bool {
        self.sturdy[face].test_with(at, read) == Some(true)
    }
    fn placed_state(
        &self,
        at: [i32; 3],
        face: usize,
        read: &impl Fn([i32; 3]) -> Option<u16>,
    ) -> Option<u16> {
        if self.face_mask & (1 << face) == 0 || !self.supports(at, face, read) {
            return None;
        }
        let existing = self.state(read(at)?);
        if existing.is_some_and(|s| s.faces & (1 << face) != 0) {
            return None;
        }
        let state = existing.or_else(|| {
            self.state(if self.source_water.test_with(at, read) == Some(true) {
                self.wet_initial
            } else {
                self.initial
            })
        })?;
        Some(state.added[face])
    }
    pub(super) fn spread_state(
        &self,
        at: [i32; 3],
        from: usize,
        toward: usize,
        order: &[u8],
        read: &impl Fn([i32; 3]) -> Option<u16>,
    ) -> Option<([i32; 3], u16)> {
        let test = |p: &Predicate, pos| p.test_with(pos, read) == Some(true);
        for &kind in order {
            let (pos, face) = match kind {
                0 => (at, toward),
                1 => (relative(at, toward), from),
                2 => (relative(relative(at, toward), from), toward ^ 1),
                _ => unreachable!(),
            };
            let replace = test(&self.air, pos)
                || read(pos).is_some_and(|s| self.state(s).is_some())
                || test(&self.water, pos) && test(&self.source_water, pos);
            if self.sculk {
                if !test(&self.spread_support, relative(pos, face))
                    || !replace && !test(&self.spread_replaceable, pos)
                    || kind == 2 && self.sturdy(relative(at, face ^ 1), face, read)
                {
                    continue;
                }
            } else if !replace {
                continue;
            }
            if let Some(state) = self.placed_state(pos, face, read) {
                return Some((pos, state));
            }
        }
        None
    }
    pub(super) fn validate(&self, palette: usize) -> bool {
        self.face_mask < 64
            && self.directions.iter().all(|d| *d < 6)
            && self
                .directions
                .iter()
                .enumerate()
                .all(|(i, d)| !self.directions[..i].contains(d))
            && self.state(self.initial).is_some()
            && self.state(self.wet_initial).is_some()
            && self.states.windows(2).all(|w| w[0].source < w[1].source)
            && self.states.iter().all(|s| {
                (s.source as usize) < palette
                    && s.faces & !self.face_mask == 0
                    && s.added.iter().all(|id| self.state(*id).is_some())
            })
            && (if self.vines {
                self.search_range == 0
            } else {
                (1..=64).contains(&self.search_range)
            })
            && self.spread_chance.is_finite()
            && (0.0..=1.0).contains(&self.spread_chance)
            && self.spread_order.iter().all(|s| *s < 3)
            && [
                &self.air,
                &self.water,
                &self.source_water,
                &self.placed_on,
                &self.spread_replaceable,
                &self.spread_support,
            ]
            .into_iter()
            .chain(&self.support)
            .chain(&self.sturdy)
            .all(|p| p.validate(palette))
    }
}
pub(super) fn relative(at: [i32; 3], direction: usize) -> [i32; 3] {
    std::array::from_fn(|axis| at[axis] + DIRECTIONS[direction][axis])
}
pub(super) fn shuffle<T>(values: &mut [T], rng: &mut Rng) {
    for n in (2..=values.len()).rev() {
        values.swap(n - 1, rng.below(n as i32) as usize);
    }
}
struct World<'a, R> {
    recipe: &'a Recipe,
    read: R,
    blocks: &'a mut Vec<WorldBlock>,
    start: usize,
    min_y: i32,
    max_y: i32,
}
impl<R: Fn([i32; 3]) -> Option<u16>> World<'_, R> {
    fn material(&self, at: [i32; 3]) -> Option<u16> {
        self.blocks[self.start..]
            .iter()
            .rev()
            .find(|b| [b.x, b.y, b.z] == at)
            .map(|b| b.material)
            .or_else(|| (self.read)(at))
    }
    fn test(&self, predicate: &Predicate, at: [i32; 3]) -> bool {
        predicate.test_with(at, &|p| self.material(p)) == Some(true)
    }
    fn put(&mut self, at: [i32; 3], material: u16) -> bool {
        if at[1] < self.min_y || at[1] >= self.max_y {
            return false;
        }
        self.blocks.push(WorldBlock {
            root_order: 0,
            tick: 0,
            x: at[0],
            y: at[1],
            z: at[2],
            material,
            upper: 0,
            role: FEATURE,
        });
        true
    }
    fn placed_state(&self, at: [i32; 3], face: usize) -> Option<u16> {
        self.recipe.placed_state(at, face, &|p| self.material(p))
    }
    fn spread(&mut self, at: [i32; 3], starting_face: usize, source: u16, rng: &mut Rng) {
        let source_faces = self.recipe.state(source).unwrap().faces;
        let mut directions = [0, 1, 2, 3, 4, 5];
        shuffle(&mut directions, rng);
        for d in directions {
            if d / 2 == starting_face / 2 || source_faces & (1 << d) != 0 {
                continue;
            }
            if let Some((pos, state)) =
                self.recipe
                    .spread_state(at, starting_face, d, &self.recipe.spread_order, &|p| {
                        self.material(p)
                    })
            {
                if self.put(pos, state) {
                    return;
                }
            }
        }
    }
    fn growth(&mut self, at: [i32; 3], directions: &[usize], rng: &mut Rng) -> bool {
        for d in directions {
            if !self.test(&self.recipe.placed_on, relative(at, *d)) {
                continue;
            }
            // The first allowed neighbor ends this attempt, even if its shape
            // cannot support that face. This is the loaded game's ordering.
            let Some(state) = self.placed_state(at, *d) else {
                return false;
            };
            self.put(at, state);
            if (rng.unit() as f32) < self.recipe.spread_chance {
                self.spread(at, *d, state, rng);
            }
            return true;
        }
        false
    }
    fn generate(&mut self, at: [i32; 3], rng: &mut Rng) -> bool {
        if self.recipe.vines {
            if !self.test(&self.recipe.air, at) {
                return false;
            }
            for d in &self.recipe.directions {
                if self.test(&self.recipe.support[*d], at) {
                    let state = self.recipe.state(self.recipe.initial).unwrap().added[*d];
                    self.put(at, state);
                    return true;
                }
            }
            return false;
        }
        if !self.test(&self.recipe.air, at) && !self.test(&self.recipe.water, at) {
            return false;
        }
        let mut directions = [0; 6];
        let directions = &mut directions[..self.recipe.directions.len()];
        directions.copy_from_slice(&self.recipe.directions);
        shuffle(directions, rng);
        if self.growth(at, directions, rng) {
            return true;
        }
        for d in directions.iter() {
            let mut placement = [0; 6];
            let mut count = 0;
            for p in &self.recipe.directions {
                if *p != (*d ^ 1) {
                    placement[count] = *p;
                    count += 1;
                }
            }
            let placement = &mut placement[..count];
            shuffle(placement, rng);
            // Minecraft 26.3 probes origin.relative(direction) on each search
            // iteration. A failed growth attempt neither writes nor draws RNG,
            // so repeated probes of this invocation's immutable substrate have
            // the same result. Keep the direction shuffle, then evaluate once.
            let pos = relative(at, *d);
            if !self.test(&self.recipe.air, pos)
                && !self.test(&self.recipe.water, pos)
                && !self
                    .material(pos)
                    .is_some_and(|s| self.recipe.state(s).is_some())
            {
                continue;
            }
            if self.growth(pos, placement, rng) {
                return true;
            }
        }
        false
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
) -> bool {
    let start = blocks.len();
    let read = |p| overlay.material(field, profile, request, p);
    World {
        recipe,
        read,
        blocks,
        start,
        min_y: request.min_y,
        max_y: request.min_y + request.height as i32,
    }
    .generate(at, rng)
}
