//! Registered lakes and the void preset's starting platform.
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
    #[serde(default = "centered_offset")]
    horizontal_offset: i32,
    #[serde(default)]
    pub(crate) freeze_water: bool,
    non_air: Option<Predicate>,
    #[serde(default)]
    air_tick: u16,
    #[serde(default)]
    mark_above: bool,
}

fn centered_offset() -> i32 {
    -8
}

impl Lake {
    pub(super) fn validate(&self, palette: usize) -> bool {
        self.fluid.validate(palette)
            && self.barrier.validate(palette)
            && (self.air as usize) < palette
            && (-8..=0).contains(&self.horizontal_offset)
            && (!self.mark_above || self.non_air.is_some())
            && self.non_air.as_ref().is_none_or(|p| p.validate(palette))
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
) -> bool {
    if at[1] <= request.min_y + 4 || at[1] + 3 >= request.min_y + request.height as i32 {
        return false;
    }
    let origin = [
        at[0] + recipe.horizontal_offset,
        at[1] - 4,
        at[2] + recipe.horizontal_offset,
    ];
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
                    return false;
                };
                if (y >= 4 && recipe.liquid.test_with(pos, &read) != Some(false))
                    || (y < 4
                        && recipe.solid.test_with(pos, &read) != Some(true)
                        && material != fluid)
                    || recipe.can_place_feature.test_with(pos, &read) != Some(true)
                {
                    return false;
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
                        root_order: 0,
                        tick: if y >= 4 { recipe.air_tick } else { 0 },
                        x: pos[0],
                        y: pos[1],
                        z: pos[2],
                        material,
                        upper: 0,
                        role: FEATURE,
                    });
                    if y >= 4 && recipe.mark_above {
                        mark_above(
                            pos,
                            request,
                            recipe.non_air.as_ref().unwrap(),
                            &|p| placed.get(&p).copied().or_else(|| read(p)),
                            output,
                        );
                    }
                }
            }
        }
    }
    let barrier = recipe.barrier.sample(
        rng,
        origin,
        &|p| placed.get(&p).copied().or_else(|| read(p)),
        noise,
    );
    let barrier_start = output.len();
    let has_barrier = recipe.non_air.as_ref().map_or(barrier != 0, |predicate| {
        predicate.test_with(origin, &|_| Some(barrier)) == Some(true)
    });
    if has_barrier {
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
                    let live = |p| placed.get(&p).copied().or_else(|| read(p));
                    if recipe.solid.test_with(pos, &live) == Some(true)
                        && recipe.can_replace_with_barrier.test_with(pos, &live) == Some(true)
                    {
                        blocks::write(output, pos, barrier);
                        if recipe.mark_above {
                            placed.insert(pos, barrier);
                            mark_above(
                                pos,
                                request,
                                recipe.non_air.as_ref().unwrap(),
                                &|p| placed.get(&p).copied().or_else(|| read(p)),
                                output,
                            );
                        }
                    }
                }
            }
        }
    }
    if let Some(climate) = profile
        .terrain_features
        .surface_freeze
        .as_ref()
        .filter(|climate| recipe.freeze_water && climate.water_fluid(fluid))
    {
        let y = origin[1] + 4;
        for block in output[barrier_start..]
            .iter()
            .filter(|block| block.y == y && block.role == FEATURE)
        {
            placed.insert([block.x, block.y, block.z], block.material);
        }
        let live = |p| placed.get(&p).copied().or_else(|| read(p));
        for x in 0..16 {
            for z in 0..16 {
                let pos = [origin[0] + x, y, origin[2] + z];
                if let Some(ice) =
                    live(pos).and_then(|state| climate.lake_ice(profile, field, pos, state))
                    && recipe.can_replace_with_air_or_fluid.test_with(pos, &live) == Some(true)
                {
                    blocks::write(output, pos, ice);
                }
            }
        }
    }
    true
}

fn mark_above(
    [x, y, z]: [i32; 3],
    request: ChunkRequest,
    non_air: &Predicate,
    read: &impl Fn([i32; 3]) -> Option<u16>,
    output: &mut Vec<WorldBlock>,
) {
    for y in y + 1..=y + 2 {
        if y >= request.min_y + request.height as i32
            || non_air.test_with([x, y, z], read) != Some(true)
        {
            break;
        }
        output.push(WorldBlock {
            root_order: 0,
            x,
            y,
            z,
            material: 0,
            upper: 0,
            role: POST_PROCESS,
            tick: 0,
        });
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
                    root_order: 0,
                    tick: 0,
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
