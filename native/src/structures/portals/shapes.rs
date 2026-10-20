//! Explicit StructureTemplate neighbor updates over its complete placement footprint.
use super::*;
use std::collections::HashSet;
const DIRS: [[i32; 3]; 6] = [
    [0, 0, -1],
    [1, 0, 0],
    [0, 0, 1],
    [-1, 0, 0],
    [0, -1, 0],
    [0, 1, 0],
];

#[derive(Clone, Deserialize)]
pub(super) struct Profile {
    pub states: Vec<State>,
    pub variants: Vec<Vec<u16>>,
}
#[derive(Clone, Deserialize)]
pub(super) struct State {
    kind: u8,
    #[serde(default)]
    facing: usize,
    #[serde(default)]
    half: u8,
    #[serde(default)]
    connections: u8,
    #[serde(default)]
    family: usize,
    wall_connect: u8,
    bars_connect: u8,
    wall_cover: u8,
    pub waterlogged: bool,
    pub wet: u16,
    pub water: bool,
    pub source_fluid: bool,
    pub entity: bool,
    pub container: bool,
    pub tick_fluid: String,
    pub tick_block: String,
    pub bubble_support: bool,
}
impl Profile {
    pub(super) fn valid(&self, materials: usize) -> bool {
        self.states.len() == materials
            && self
                .variants
                .iter()
                .flatten()
                .all(|&v| (v as usize) < materials)
            && self.states.iter().all(|s| {
                s.kind <= 3
                    && s.facing < 4
                    && (s.wet as usize) < materials
                    && (s.kind == 0
                        || self.variants.get(s.family).is_some_and(|v| {
                            v.len()
                                == match s.kind {
                                    1 => 5,
                                    2 => 16,
                                    _ => 162,
                                }
                        }))
            })
    }
}
impl World<'_> {
    fn tick(&mut self, pos: [i32; 3], fluid: &str) {
        self.tick_kind(pos, fluid, false);
    }
    fn tick_kind(&mut self, pos: [i32; 3], kind: &str, block: bool) {
        if self.tick_seen.insert((pos, kind.to_owned(), block)) {
            self.ticks.push((pos, kind.to_owned(), block));
        }
    }
    pub(super) fn wet(&mut self, pos: [i32; 3], old: u16) {
        let table = &self.profile.structures.portals.as_ref().unwrap().shapes;
        let current = self.get(pos);
        let state = &table.states[current as usize];
        if !state.source_fluid && table.states[old as usize].water && state.wet != current {
            let wet = state.wet;
            let tag = self.writes.get(&pos).and_then(|v| v.1.clone());
            self.set(pos, wet, tag);
            self.tick(pos, "minecraft:water");
        }
    }
    pub(super) fn fill(&mut self, positions: &mut Vec<[i32; 3]>, locked: &HashSet<[i32; 3]>) {
        loop {
            let before = positions.len();
            positions.retain(|&pos| {
                let table = &self.profile.structures.portals.as_ref().unwrap().shapes;
                let mut fluid = self.get(pos);
                for direction in [5, 0, 1, 2, 3] {
                    if table.states[fluid as usize].source_fluid {
                        break;
                    }
                    let neighbor = add(pos, DIRS[direction]);
                    let id = self.get(neighbor);
                    if !locked.contains(&neighbor) && table.states[id as usize].source_fluid {
                        fluid = id;
                    }
                }
                if table.states[fluid as usize].source_fluid {
                    self.wet(pos, fluid);
                    false
                } else {
                    true
                }
            });
            if positions.len() == before || positions.is_empty() {
                break;
            }
        }
    }
    fn update(&mut self, pos: [i32; 3], direction: usize) -> u16 {
        let id = self.get(pos);
        let profile = self.profile;
        let table = &profile.structures.portals.as_ref().unwrap().shapes;
        let state = &table.states[id as usize];
        let neighbor = &table.states[self.get(add(pos, DIRS[direction])) as usize];
        if state.waterlogged
            || !state.tick_fluid.is_empty() && (state.source_fluid || neighbor.source_fluid)
        {
            self.tick(pos, &state.tick_fluid);
        }
        if direction == 4 && !state.tick_block.is_empty() && neighbor.bubble_support {
            self.tick_kind(pos, &state.tick_block, true);
        }
        let variant = match state.kind {
            1 if direction < 4 => {
                let mut result = 0;
                let facing = state.facing;
                for (which, dir) in [(0, facing), (1, (facing + 2) % 4)] {
                    let other = &table.states[self.get(add(pos, DIRS[dir])) as usize];
                    if other.kind == 1 && other.half == state.half && other.facing % 2 != facing % 2
                    {
                        let test = if which == 0 {
                            (other.facing + 2) % 4
                        } else {
                            other.facing
                        };
                        let third = &table.states[self.get(add(pos, DIRS[test])) as usize];
                        if third.kind != 1 || third.facing != facing || third.half != state.half {
                            let left = other.facing == (facing + 3) % 4;
                            result = if which == 0 {
                                if left { 3 } else { 4 }
                            } else if left {
                                1
                            } else {
                                2
                            };
                            break;
                        }
                    }
                }
                result
            }
            2 if direction < 4 => {
                ((state.connections & !(1 << direction))
                    | (neighbor.bars_connect & (1 << direction))) as usize
            }
            3 if direction != 4 => {
                let connections = if direction < 4 {
                    (state.connections & !(1 << direction))
                        | (neighbor.wall_connect & (1 << direction))
                } else {
                    state.connections
                };
                let top = &table.states[self.get(add(pos, [0, 1, 0])) as usize];
                let sides: [u8; 4] = std::array::from_fn(|i| {
                    if connections & (1 << i) == 0 {
                        0
                    } else if top.wall_cover & (1 << i) != 0 {
                        2
                    } else {
                        1
                    }
                });
                let none = sides.map(|v| v == 0);
                let corner = connections == 0 || none[0] != none[2] || none[1] != none[3];
                let tall = sides[0] == 2 && sides[2] == 2 || sides[1] == 2 && sides[3] == 2;
                let post = top.wall_cover & 32 != 0 || corner || !tall && top.wall_cover & 16 != 0;
                sides[0] as usize
                    + 3 * sides[1] as usize
                    + 9 * sides[2] as usize
                    + 27 * sides[3] as usize
                    + usize::from(post) * 81
            }
            _ => return id,
        };
        table.variants[state.family][variant]
    }
    fn update_at(&mut self, pos: [i32; 3], direction: usize) {
        let id = self.get(pos);
        let result = self.update(pos, direction);
        if result != id {
            let tag = self.writes.get(&pos).and_then(|v| v.1.clone());
            self.set(pos, result, tag);
        }
    }
    pub(super) fn shapes(&mut self, placed: &[[i32; 3]]) {
        if placed.is_empty() {
            return;
        }
        let occupied: HashSet<_> = placed.iter().copied().collect();
        let min: [i32; 3] = std::array::from_fn(|i| placed.iter().map(|p| p[i]).min().unwrap());
        let max: [i32; 3] = std::array::from_fn(|i| placed.iter().map(|p| p[i]).max().unwrap());
        // DiscreteVoxelShape.forAllFaces: Z, then Y, then X axes; each scan
        // emits negative entry faces and positive exit faces in source order.
        for (axes, negative, positive) in [([0, 1, 2], 0, 2), ([2, 0, 1], 4, 5), ([1, 2, 0], 3, 1)]
        {
            for a in min[axes[0]]..=max[axes[0]] {
                for b in min[axes[1]]..=max[axes[1]] {
                    let mut previous = false;
                    for c in min[axes[2]]..=max[axes[2]] + 1 {
                        let mut pos = [0; 3];
                        pos[axes[0]] = a;
                        pos[axes[1]] = b;
                        pos[axes[2]] = c;
                        let full = occupied.contains(&pos);
                        let face = if !previous && full {
                            Some(negative)
                        } else if previous && !full {
                            pos[axes[2]] -= 1;
                            Some(positive)
                        } else {
                            None
                        };
                        if let Some(direction) = face {
                            self.update_at(pos, direction);
                            self.update_at(
                                add(pos, DIRS[direction]),
                                [2, 3, 0, 1, 5, 4][direction],
                            );
                        }
                        previous = full;
                    }
                }
            }
        }
        for &pos in placed {
            for direction in [3, 1, 0, 2, 4, 5] {
                self.update_at(pos, direction);
            }
        }
    }
}
