//! Registered flat-world lakes and the void preset's starting platform.
use super::*;
use blocks::Provider;
use placement::{Overlay, Predicate};

#[derive(Clone, Deserialize)]
pub struct Lake {
    fluid: Provider,
    barrier: Provider,
    can_place_feature: Predicate,
    can_replace_with_air_or_fluid: Predicate,
    can_replace_with_barrier: Predicate,
    solid: Predicate,
    liquid: Predicate,
    air: u16,
}

impl Lake {
    pub(super) fn validate(&self, palette: usize) -> bool {
        self.fluid.validate(palette)
            && self.barrier.validate(palette)
            && (self.air as usize) < palette
            && [
                &self.can_place_feature,
                &self.can_replace_with_air_or_fluid,
                &self.can_replace_with_barrier,
                &self.solid,
                &self.liquid,
            ]
            .iter()
            .all(|p| p.validate(palette))
    }
}

fn index(x: usize, y: usize, z: usize) -> usize {
    (x * 16 + z) * 8 + y
}

fn boundary(grid: &[bool; 2048], x: usize, y: usize, z: usize) -> bool {
    !grid[index(x, y, z)]
        && ((x < 15 && grid[index(x + 1, y, z)])
            || (x > 0 && grid[index(x - 1, y, z)])
            || (z < 15 && grid[index(x, y, z + 1)])
            || (z > 0 && grid[index(x, y, z - 1)])
            || (y < 7 && grid[index(x, y + 1, z)])
            || (y > 0 && grid[index(x, y - 1, z)]))
}

/// Minecraft's ellipsoid geometry and boundary predicates, using Retina's seeded replay stream.
#[allow(clippy::too_many_arguments)]
pub(super) fn lake(
    recipe: &Lake,
    at: [i32; 3],
    rng: &mut Rng,
    field: &Field,
    profile: &WorldProfile,
    request: ChunkRequest,
    overlay: &Overlay,
    output: &mut Vec<WorldBlock>,
    noise: &provider_noise::Context,
) {
    if at[1] <= request.min_y + 4 || at[1] + 3 >= request.min_y + request.height as i32 {
        return;
    }
    let origin = [at[0] - 8, at[1] - 4, at[2] - 8];
    let mut grid = [false; 2048];
    let spots = rng.below(4) + 4;
    for _ in 0..spots {
        let xr = rng.unit() * 6.0 + 3.0;
        let yr = rng.unit() * 4.0 + 2.0;
        let zr = rng.unit() * 6.0 + 3.0;
        let xp = rng.unit() * (16.0 - xr - 2.0) + 1.0 + xr / 2.0;
        let yp = rng.unit() * (8.0 - yr - 4.0) + 2.0 + yr / 2.0;
        let zp = rng.unit() * (16.0 - zr - 2.0) + 1.0 + zr / 2.0;
        for x in 1..15 {
            for z in 1..15 {
                for y in 1..7 {
                    let dx = (x as f64 - xp) / (xr / 2.0);
                    let dy = (y as f64 - yp) / (yr / 2.0);
                    let dz = (z as f64 - zp) / (zr / 2.0);
                    if dx * dx + dy * dy + dz * dz < 1.0 {
                        grid[index(x, y, z)] = true;
                    }
                }
            }
        }
    }
    let read = |p| overlay.material(field, profile, request, p);
    let fluid = recipe.fluid.sample(rng, origin, &read, noise);
    for x in 0..16 {
        for z in 0..16 {
            for y in 0..8 {
                if !boundary(&grid, x, y, z) {
                    continue;
                }
                let pos = [
                    origin[0] + x as i32,
                    origin[1] + y as i32,
                    origin[2] + z as i32,
                ];
                let Some(material) = read(pos) else {
                    return;
                };
                if (y >= 4 && recipe.liquid.test_with(pos, &read) != Some(false))
                    || (y < 4
                        && recipe.solid.test_with(pos, &read) != Some(true)
                        && material != fluid)
                    || recipe.can_place_feature.test_with(pos, &read) != Some(true)
                {
                    return;
                }
            }
        }
    }
    let mut placed = std::collections::HashMap::new();
    for x in 0..16 {
        for z in 0..16 {
            for y in 0..8 {
                if !grid[index(x, y, z)] {
                    continue;
                }
                let pos = [
                    origin[0] + x as i32,
                    origin[1] + y as i32,
                    origin[2] + z as i32,
                ];
                if recipe.can_replace_with_air_or_fluid.test_with(pos, &read) == Some(true) {
                    let material = if y >= 4 { recipe.air } else { fluid };
                    placed.insert(pos, material);
                    output.push(WorldBlock {
                        x: pos[0],
                        y: pos[1],
                        z: pos[2],
                        material,
                        upper: 0,
                        role: FEATURE,
                    });
                }
            }
        }
    }
    let read = |p| placed.get(&p).copied().or_else(|| read(p));
    let barrier = recipe.barrier.sample(rng, origin, &read, noise);
    if barrier == 0 {
        return;
    }
    for x in 0..16 {
        for z in 0..16 {
            for y in 0..8 {
                if !boundary(&grid, x, y, z) || (y >= 4 && rng.below(2) == 0) {
                    continue;
                }
                let pos = [
                    origin[0] + x as i32,
                    origin[1] + y as i32,
                    origin[2] + z as i32,
                ];
                if recipe.solid.test_with(pos, &read) == Some(true)
                    && recipe.can_replace_with_barrier.test_with(pos, &read) == Some(true)
                {
                    output.push(WorldBlock {
                        x: pos[0],
                        y: pos[1],
                        z: pos[2],
                        material: barrier,
                        upper: 0,
                        role: FEATURE,
                    });
                }
            }
        }
    }
}

pub(super) fn platform(
    materials: [u16; 2],
    at: [i32; 3],
    request: ChunkRequest,
    output: &mut Vec<WorldBlock>,
) {
    let cx = at[0].div_euclid(16);
    let cz = at[2].div_euclid(16);
    if cx.abs() > 1 || cz.abs() > 1 {
        return;
    }
    let y = at[1] + 3;
    if y < request.min_y || y >= request.min_y + request.height as i32 {
        return;
    }
    for z in cz * 16..cz * 16 + 16 {
        for x in cx * 16..cx * 16 + 16 {
            if (x - 8).abs().max((z - 8).abs()) <= 16 {
                let material = materials[usize::from(x == 8 && z == 8)];
                output.push(WorldBlock {
                    x,
                    y,
                    z,
                    material,
                    upper: 0,
                    role: FEATURE,
                });
            }
        }
    }
}
