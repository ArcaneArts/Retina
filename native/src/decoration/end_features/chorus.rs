//! Source-ordered ChorusFlowerBlock.generatePlant with a live write overlay.
use super::super::placement::{Modifier, Overlay};
use super::super::*;
use crate::end::DecorationRandom;

#[derive(Clone, Deserialize)]
pub struct Recipe {
    pub plants: Vec<u16>,  // down, up, north, east, south, west bit order
    pub flowers: Vec<u16>, // all ages, including terminal age 5
    pub supports: Vec<u16>,
    pub air: Vec<u16>,
    pub step: u32,
    pub index: u32,
    pub biomes: Vec<u16>,
    pub placement: Vec<Modifier>,
}
const DIRECTIONS: [[i32; 3]; 6] = [
    [0, -1, 0],
    [0, 1, 0],
    [0, 0, -1],
    [1, 0, 0],
    [0, 0, 1],
    [-1, 0, 0],
];
fn offset(a: [i32; 3], b: [i32; 3]) -> [i32; 3] {
    [a[0] + b[0], a[1] + b[1], a[2] + b[2]]
}
impl Recipe {
    pub(in crate::decoration) fn validate(&self, palette: usize) -> bool {
        self.plants.len() == 64
            && self.flowers.len() == 6
            && !self.supports.is_empty()
            && !self.air.is_empty()
            && self
                .plants
                .iter()
                .chain(&self.flowers)
                .chain(&self.supports)
                .chain(&self.air)
                .all(|&id| (id as usize) < palette)
            && self.plants.iter().chain(&self.flowers).all(|&id| id != 0)
            && super::placement::validate(&self.placement, &self.biomes, palette)
    }
    pub(in crate::decoration) fn place(
        &self,
        origin: [i32; 3],
        field: &Field,
        profile: &WorldProfile,
        request: ChunkRequest,
        overlay: &Overlay,
        output: &mut Vec<WorldBlock>,
    ) {
        super::placement::walk(
            &self.placement,
            &self.biomes,
            self.index,
            self.step,
            origin,
            field,
            profile,
            request,
            overlay,
            output,
            |at, random, live, output| {
                let mut tree = Tree {
                    recipe: self,
                    field,
                    profile,
                    request,
                    live,
                    output,
                };
                if tree.empty(at)
                    && tree
                        .read(offset(at, DIRECTIONS[0]))
                        .is_some_and(|id| self.supports.contains(&id))
                {
                    tree.plant(at);
                    tree.grow(at, at, 0, random);
                }
            },
        );
    }
}
struct Tree<'a> {
    recipe: &'a Recipe,
    field: &'a Field<'a>,
    profile: &'a WorldProfile,
    request: ChunkRequest,
    live: &'a mut Overlay,
    output: &'a mut Vec<WorldBlock>,
}
impl Tree<'_> {
    fn read(&self, at: [i32; 3]) -> Option<u16> {
        self.live
            .material(self.field, self.profile, self.request, at)
    }
    fn empty(&self, at: [i32; 3]) -> bool {
        self.read(at)
            .is_some_and(|id| self.recipe.air.contains(&id))
    }
    fn set(&mut self, at: [i32; 3], material: u16) {
        if at[1] < self.request.min_y || at[1] >= self.request.min_y + self.request.height as i32 {
            return;
        }
        self.live.write(self.profile, at, material);
        self.output.push(WorldBlock {
            root_order: 0,
            tick: 0,
            x: at[0],
            y: at[1],
            z: at[2],
            material,
            upper: 0,
            role: FEATURE,
        });
    }
    fn plant(&mut self, at: [i32; 3]) {
        let mut bits = 0;
        for (i, &d) in DIRECTIONS.iter().enumerate() {
            if self.read(offset(at, d)).is_some_and(|id| {
                self.recipe.plants.contains(&id)
                    || self.recipe.flowers.contains(&id)
                    || (i == 0 && self.recipe.supports.contains(&id))
            }) {
                bits |= 1 << i;
            }
        }
        self.set(at, self.recipe.plants[bits]);
    }
    fn neighbors_empty(&self, at: [i32; 3], except: Option<usize>) -> bool {
        (2..6).all(|d| Some(d) == except || self.empty(offset(at, DIRECTIONS[d])))
    }
    fn grow(
        &mut self,
        current: [i32; 3],
        start: [i32; 3],
        depth: u8,
        random: &mut DecorationRandom,
    ) {
        let height = random.below(4) + 1 + i32::from(depth == 0);
        for i in 0..height {
            let target = offset(current, [0, i + 1, 0]);
            if !self.neighbors_empty(target, None) {
                return;
            }
            self.plant(target);
            self.plant(offset(target, DIRECTIONS[0]));
        }
        let mut placed = false;
        if depth < 4 {
            let stems = random.below(4) + i32::from(depth == 0);
            for _ in 0..stems {
                let d = random.below(4) as usize + 2;
                let opposite = 2 + (d - 2 + 2) % 4;
                let target = offset(offset(current, [0, height, 0]), DIRECTIONS[d]);
                if (target[0] - start[0]).abs() < 8
                    && (target[2] - start[2]).abs() < 8
                    && self.empty(target)
                    && self.empty(offset(target, DIRECTIONS[0]))
                    && self.neighbors_empty(target, Some(opposite))
                {
                    placed = true;
                    self.plant(target);
                    self.plant(offset(target, DIRECTIONS[opposite]));
                    self.grow(target, start, depth + 1, random);
                }
            }
        }
        if !placed {
            self.set(offset(current, [0, height, 0]), self.recipe.flowers[5]);
        }
    }
}
