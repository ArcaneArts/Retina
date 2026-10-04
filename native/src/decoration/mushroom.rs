//! Registered huge mushrooms, including cap-face states and live clearance.
use super::*;
use blocks::Provider;
use placement::{Overlay, Predicate};

#[derive(Clone, Deserialize)]
pub struct Faces {
    pub source: u16,
    pub states: Vec<u16>,
}
#[derive(Clone, Deserialize)]
pub struct Recipe {
    pub red: bool,
    pub foliage_radius: i32,
    pub cap: Provider,
    pub stem: Provider,
    pub faces: Vec<Faces>,
    pub support: Predicate,
    pub clearance: Predicate,
    pub replaceable: Predicate,
}
impl Recipe {
    pub(super) fn validate(&self, palette: usize) -> bool {
        (0..=15).contains(&self.foliage_radius)
            && self.cap.validate(palette)
            && self.stem.validate(palette)
            && self.support.validate(palette)
            && self.clearance.validate(palette)
            && self.replaceable.validate(palette)
            && self
                .faces
                .iter()
                .all(|v| v.states.len() == 32 && v.states.iter().all(|id| (*id as usize) < palette))
            && self
                .cap
                .outputs()
                .iter()
                .all(|id| self.faces.iter().any(|v| v.source == *id))
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
    let mut height = rng.below(3) + 4;
    if rng.below(12) == 0 {
        height *= 2;
    }
    let test = |predicate: &Predicate, pos| {
        predicate.test(pos, field, profile, request, overlay) == Some(true)
    };
    if at[1] < request.min_y + 1
        || at[1] + height + 1 > request.min_y + request.height as i32 - 1
        || !test(&recipe.support, [at[0], at[1] - 1, at[2]])
    {
        return;
    }
    for dy in 0..=height {
        // The current Minecraft red feature passes (-1,-1) to its radius
        // helper and consequently checks only the trunk axis. Cap blocks still
        // have individual replaceability checks; do not widen that test here.
        let radius = if recipe.red || dy <= 3 {
            0
        } else {
            recipe.foliage_radius
        };
        for dx in -radius..=radius {
            for dz in -radius..=radius {
                if !test(&recipe.clearance, [at[0] + dx, at[1] + dy, at[2] + dz]) {
                    return;
                }
            }
        }
    }
    let mut put = |pos: [i32; 3], material| {
        if test(&recipe.replaceable, pos) {
            blocks.push(WorldBlock {
                x: pos[0],
                y: pos[1],
                z: pos[2],
                material,
                upper: 0,
                role: FEATURE,
            });
        }
    };
    let layers = if recipe.red {
        height - 3..=height
    } else {
        height..=height
    };
    for dy in layers {
        let radius = if recipe.red && dy == height {
            recipe.foliage_radius - 1
        } else {
            recipe.foliage_radius
        };
        for dx in -radius..=radius {
            for dz in -radius..=radius {
                let min_x = dx == -radius;
                let max_x = dx == radius;
                let min_z = dz == -radius;
                let max_z = dz == radius;
                let edge_x = min_x || max_x;
                let edge_z = min_z || max_z;
                if recipe.red && dy < height && edge_x == edge_z || !recipe.red && edge_x && edge_z
                {
                    continue;
                }
                let faces = if recipe.red {
                    let center = recipe.foliage_radius - 2;
                    [
                        dx < -center,
                        dx > center,
                        dz < -center,
                        dz > center,
                        dy >= height - 1,
                    ]
                } else {
                    [
                        min_x || edge_z && dx == 1 - radius,
                        max_x || edge_z && dx == radius - 1,
                        min_z || edge_x && dz == 1 - radius,
                        max_z || edge_x && dz == radius - 1,
                        false,
                    ]
                };
                let mask = faces
                    .iter()
                    .enumerate()
                    .fold(0, |mask, (i, on)| mask | ((*on as usize) << i));
                let source = recipe.cap.sample(rng);
                let states = &recipe
                    .faces
                    .iter()
                    .find(|v| v.source == source)
                    .unwrap()
                    .states;
                put([at[0] + dx, at[1] + dy, at[2] + dz], states[mask]);
            }
        }
    }
    for dy in 0..height {
        put([at[0], at[1] + dy, at[2]], recipe.stem.sample(rng));
    }
}
