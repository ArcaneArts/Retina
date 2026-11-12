//! Registry-defined root systems place a child feature above an open ceiling.
use super::{Placed, Predicate, Provider, Rng, Simple, World};
use crate::{
    ChunkRequest,
    decoration::{Field, Kind, TreeScratch, placement::Overlay, tree},
    profile::WorldProfile,
};
use serde::Deserialize;

type Pos = [i32; 3];

#[derive(Clone, Deserialize)]
pub struct Recipe {
    tree: Placed,
    column_height: i32,
    clearance: i32,
    water_depth: i32,
    radius: i32,
    attempts: u16,
    hanging_radius: i32,
    hanging_span: i32,
    hanging_attempts: u16,
    allowed: Predicate,
    air: Predicate,
    water: Predicate,
    ground: Predicate,
    replaceable: Predicate,
    root: Provider,
    hanging: Simple,
}

impl Recipe {
    pub(in crate::decoration) fn validate(&self, palette: usize) -> bool {
        (1..=4096).contains(&self.column_height)
            && [self.clearance, self.water_depth, self.radius, self.hanging_radius]
                .into_iter().all(|v| (1..=64).contains(&v))
            && (0..=16).contains(&self.hanging_span)
            && [self.attempts, self.hanging_attempts].into_iter().all(|v| (1..=256).contains(&v))
            && [&self.allowed, &self.air, &self.water, &self.ground, &self.replaceable]
                .into_iter().all(|p| p.validate(palette))
            && self.tree.validate(palette) && self.root.validate(palette) && self.hanging.validate(palette)
    }

    pub(super) fn place(&self, world: &mut World, at: Pos, rng: &mut Rng) -> bool {
        if !world.test(&self.air, at) {
            return false;
        }
        let [x, y, z] = at;
        let top = world.request.min_y + world.request.height as i32;
        for tree_y in (y + 1)..=(y + self.column_height).min(top - 1) {
            let above = [x, tree_y, z];
            if !world.test(&self.allowed, above) || !(1..=self.clearance).all(|dy| {
                let p = [x, tree_y + dy, z];
                world.test(&self.air, p) || (dy < self.water_depth && world.test(&self.water, p))
            }) {
                continue;
            }
            if !world.test(&self.ground, [x, tree_y - 1, z]) {
                break;
            }
            if world.placed(&self.tree, above, rng, 0) {
                self.roots(world, at, tree_y, rng);
                break;
            }
        }
        // An air origin succeeds even if none of its tree candidates succeed.
        true
    }

    fn roots(&self, world: &mut World, [x, y, z]: Pos, tree_y: i32, rng: &mut Rng) {
        let spread = |rng: &mut Rng, radius| rng.below(radius) - rng.below(radius);
        for root_y in y..tree_y - 1 {
            for _ in 0..self.attempts {
                let p = [x + spread(rng, self.radius), root_y, z + spread(rng, self.radius)];
                if world.test(&self.replaceable, p) {
                    if let Some(id) = self.root.sample_optional(rng, p, &|p| world.material(p), world.noise) {
                        world.write(p, id);
                    }
                }
            }
        }
        for _ in 0..self.hanging_attempts {
            let p = [
                x + spread(rng, self.hanging_radius),
                y + spread(rng, self.hanging_span),
                z + spread(rng, self.hanging_radius),
            ];
            if world.test(&self.air, p) {
                world.simple(&self.hanging, p, rng);
            }
        }
    }
}

#[derive(Clone, Deserialize)]
pub struct Tree {
    recipe: Box<crate::decoration::Recipe>,
    clearance: Clearance,
    pub(in crate::decoration) grow: Predicate,
    clear: Predicate,
    pub(in crate::decoration) dirt: Predicate,
    pub(in crate::decoration) force_dirt: bool,
}

#[derive(Clone, Deserialize)]
struct Clearance {
    layers: u8,
    limit: i32,
    #[serde(default)]
    upper_limit: i32,
    lower_size: i32,
    #[serde(default)]
    middle_size: i32,
    upper_size: i32,
    min_clipped_height: Option<i32>,
}

impl Tree {
    pub(super) fn validate(&self, palette: usize) -> bool {
        matches!(&self.recipe.feature, Kind::Tree { root: 0, decorators, .. } if decorators.is_empty())
            && self.recipe.validate(palette).is_ok()
            && [&self.grow, &self.clear, &self.dirt].into_iter().all(|p| p.validate(palette))
            && matches!(self.clearance.layers, 2 | 3)
            && [self.clearance.lower_size, self.clearance.middle_size, self.clearance.upper_size]
                .into_iter().all(|r| (0..=16).contains(&r))
            && [self.clearance.limit, self.clearance.upper_limit]
                .into_iter().all(|r| (0..=81).contains(&r))
    }

    pub(super) fn place(&self, world: &mut World, [x, y, z]: Pos, rng: &mut Rng) -> bool {
        let mut writes = Vec::new();
        tree(world.field, world.profile, world.request, &self.recipe, x, z, rng,
            &mut writes, None, Some((y, world.overlay)), Some(self), None, &mut TreeScratch::default());
        let success = !writes.is_empty();
        for b in writes {
            world.write([b.x, b.y, b.z], b.material);
        }
        success
    }

    pub(in crate::decoration) fn height(&self, [x, y, z]: Pos, height: i32, field: &Field,
                                        profile: &WorldProfile, request: ChunkRequest, overlay: &Overlay) -> Option<i32> {
        if y <= request.min_y || y + height + 1 > request.min_y + request.height as i32 {
            return None;
        }
        let size = &self.clearance;
        for dy in 0..=height + 1 {
            let radius = if dy < size.limit {
                size.lower_size
            } else if size.layers == 2 || dy >= height - size.upper_limit {
                size.upper_size
            } else {
                size.middle_size
            };
            for dx in -radius..=radius {
                for dz in -radius..=radius {
                    if self.clear.test([x + dx, y + dy, z + dz], field, profile, request, overlay) != Some(true) {
                        let clipped = dy - 2;
                        return size.min_clipped_height.filter(|&min| clipped >= min && clipped > 0).map(|_| clipped);
                    }
                }
            }
        }
        Some(height)
    }
}
