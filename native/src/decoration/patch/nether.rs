//! Nether block primitives replay against the same live overlay as nested placement.
use super::*;
use placement::IntProvider;
mod fungus;
mod ore;

#[derive(Clone, Deserialize)]
#[serde(tag = "kind", rename_all = "snake_case")]
pub enum Feature {
    Ore {
        #[serde(flatten)]
        ore: ore::Ore,
    },
    Spring {
        state: u16,
        tick: u16,
        valid_blocks: Predicate,
        air: Predicate,
        requires_block_below: bool,
        rock_count: i32,
        hole_count: i32,
    },
    HugeFungus {
        #[serde(flatten)]
        fungus: fungus::Fungus,
    },
    NeighborSpread {
        provider: Provider,
        neighbors: Predicate,
        can_replace: Predicate,
        attempts: IntProvider,
        xz_offset: IntProvider,
        y_offset: IntProvider,
    },
    Pillar {
        provider: Provider,
        can_replace: Predicate,
        direction: i32,
        chance_to_continue: f32,
        cap: Option<Placed>,
    },
    ProjectedSquare {
        provider: Provider,
        project_through: Predicate,
        size: IntProvider,
        max_projection_height: i32,
    },
    ColumnCluster {
        provider: Provider,
        continue_through: Predicate,
        can_replace: Predicate,
        cannot_place_on: Predicate,
        air: Predicate,
        cluster_reach: IntProvider,
        column_count: IntProvider,
        column_reach: IntProvider,
        height: IntProvider,
    },
    ReplaceBlobs {
        target: Predicate,
        state: u16,
        radius: IntProvider,
    },
    Delta {
        contents: u16,
        rim: u16,
        blocked: Predicate,
        air: Predicate,
        size: IntProvider,
        rim_size: IntProvider,
    },
}
fn bounded(p: &IntProvider, min: i32, max: i32) -> bool {
    p.bounds().is_ok_and(|(a, b)| a >= min && b <= max)
}
impl Feature {
    pub(crate) fn deferred_replacement(&self, profile: &WorldProfile) -> Option<(u16, u16)> {
        match self {
            Self::Ore { ore } => ore.deferred_replacement(profile),
            _ => None,
        }
    }
    pub(crate) fn ore_footprint(
        &self, at: [i32; 3], rng: &mut Rng,
        mark: &mut impl FnMut([i32; 2], [i32; 2]) -> bool,
    ) -> Option<bool> {
        match self {
            Self::Ore { ore } => ore.ore_footprint(at, rng, mark),
            _ => None,
        }
    }
    pub(crate) fn validate(&self, palette: usize) -> bool {
        match self {
            Self::Ore { ore } => ore.validate(palette),
            Self::Spring {
                state,
                tick,
                valid_blocks,
                air,
                ..
            } => {
                (*state as usize) < palette
                    && *tick > 0
                    && valid_blocks.validate(palette)
                    && air.validate(palette)
            }
            Self::HugeFungus { fungus } => fungus.validate(palette),
            Self::NeighborSpread {
                provider,
                neighbors,
                can_replace,
                attempts,
                xz_offset,
                y_offset,
            } => {
                provider.validate(palette)
                    && neighbors.validate(palette)
                    && can_replace.validate(palette)
                    && bounded(attempts, 1, 3000)
                    && bounded(xz_offset, -16, 16)
                    && bounded(y_offset, -16, 16)
            }
            Self::Pillar {
                provider,
                can_replace,
                direction,
                chance_to_continue,
                cap,
            } => {
                provider.validate(palette)
                    && can_replace.validate(palette)
                    && matches!(direction, -1 | 1)
                    && (0.0..=1.0).contains(chance_to_continue)
                    && cap.as_ref().is_none_or(|c| c.validate(palette))
            }
            Self::ProjectedSquare {
                provider,
                project_through,
                size,
                max_projection_height,
            } => {
                provider.validate(palette)
                    && project_through.validate(palette)
                    && bounded(size, 1, 16)
                    && *max_projection_height >= 0
            }
            Self::ColumnCluster {
                provider,
                continue_through,
                can_replace,
                cannot_place_on,
                air,
                cluster_reach,
                column_count,
                column_reach,
                height,
            } => {
                provider.validate(palette)
                    && [continue_through, can_replace, cannot_place_on, air]
                        .iter()
                        .all(|p| p.validate(palette))
                    && bounded(cluster_reach, 0, 13)
                    && bounded(column_count, 1, 150)
                    && bounded(column_reach, 0, 3)
                    && bounded(height, 1, 10)
            }
            Self::ReplaceBlobs {
                target,
                state,
                radius,
            } => target.validate(palette) && (*state as usize) < palette && bounded(radius, 0, 12),
            Self::Delta {
                contents,
                rim,
                blocked,
                air,
                size,
                rim_size,
            } => {
                (*contents as usize) < palette
                    && (*rim as usize) < palette
                    && blocked.validate(palette)
                    && air.validate(palette)
                    && bounded(size, 0, 16)
                    && bounded(rim_size, 0, 16)
            }
        }
    }

    pub(super) fn place(&self, w: &mut World, at: [i32; 3], rng: &mut Rng) -> bool {
        match self {
            Self::Ore { ore } => ore.place(w, at, rng),
            Self::Spring {
                state,
                tick,
                valid_blocks,
                air,
                requires_block_below,
                rock_count,
                hole_count,
            } => {
                if !w.test(valid_blocks, add(at, [0, 1, 0]))
                    || *requires_block_below && !w.test(valid_blocks, add(at, [0, -1, 0]))
                    || !w.test(air, at) && !w.test(valid_blocks, at)
                {
                    return false;
                }
                let neighbors = [[0, -1, 0], [-1, 0, 0], [1, 0, 0], [0, 0, -1], [0, 0, 1]];
                let rocks = neighbors
                    .iter()
                    .filter(|&&d| w.test(valid_blocks, add(at, d)))
                    .count() as i32;
                let holes = neighbors
                    .iter()
                    .filter(|&&d| w.test(air, add(at, d)))
                    .count() as i32;
                if rocks != *rock_count || holes != *hole_count {
                    return false;
                }
                w.write_tick(at, *state, *tick);
                true
            }
            Self::HugeFungus { fungus } => fungus.place(w, at, rng),
            Self::NeighborSpread {
                provider,
                neighbors,
                can_replace,
                attempts,
                xz_offset,
                y_offset,
            } => {
                put(w, provider, at, rng);
                let attempts = attempts.sample(rng);
                for _ in 0..attempts {
                    let p = [
                        at[0] + xz_offset.sample(rng),
                        at[1] + y_offset.sample(rng),
                        at[2] + xz_offset.sample(rng),
                    ];
                    if !w.test(can_replace, p) {
                        continue;
                    }
                    let mut count = 0;
                    for d in DIRECTIONS {
                        if w.test(neighbors, add(p, d)) {
                            count += 1;
                        }
                        if count > 1 {
                            break;
                        }
                    }
                    if count == 1 {
                        put(w, provider, p, rng);
                    }
                }
                true
            }
            Self::Pillar {
                provider,
                can_replace,
                direction,
                chance_to_continue,
                cap,
            } => {
                let mut p = at;
                // Minecraft consumes the continuation draw before testing build height.
                while w.test(can_replace, p)
                    && (rng.unit() as f32) < *chance_to_continue
                    && inside(w, p[1])
                {
                    put(w, provider, p, rng);
                    p[1] += direction;
                }
                p[1] -= direction;
                if let Some(cap) = cap {
                    w.placed(cap, p, rng, 0);
                }
                true
            }
            Self::ProjectedSquare {
                provider,
                project_through,
                size,
                max_projection_height,
            } => {
                let size = size.sample(rng);
                let bound = size * size + 1;
                for dx in -size..=size {
                    for dz in -size..=size {
                        if rng.below(bound) >= bound - dx.abs() * dz.abs() {
                            continue;
                        }
                        let mut p = add(at, [dx, 0, dz]);
                        let mut drop = *max_projection_height;
                        while w.test(project_through, add(p, [0, -1, 0])) {
                            p[1] -= 1;
                            drop -= 1;
                            if drop <= 0 {
                                break;
                            }
                        }
                        put_optional(w, provider, p, rng);
                    }
                }
                true
            }
            Self::ColumnCluster {
                provider,
                continue_through,
                can_replace,
                cannot_place_on,
                air,
                cluster_reach,
                column_count,
                column_reach,
                height,
            } => {
                let can_place = |w: &World, p| {
                    w.test(can_replace, p)
                        && !w.test(air, add(p, [0, -1, 0]))
                        && !w.test(cannot_place_on, add(p, [0, -1, 0]))
                };
                if !can_place(w, at) {
                    return false;
                }
                let height = height.sample(rng);
                let reach = height.min(cluster_reach.sample(rng));
                let count = column_count.sample(rng);
                let mut any = false;
                for _ in 0..count {
                    let p = add(
                        at,
                        [
                            rng.below(reach * 2 + 1) - reach,
                            rng.below(1),
                            rng.below(reach * 2 + 1) - reach,
                        ],
                    );
                    let blocks_y = height - (p[0] - at[0]).abs() - (p[2] - at[2]).abs();
                    if blocks_y < 0 {
                        continue;
                    }
                    let radius = column_reach.sample(rng);
                    // BlockPos.betweenClosed visits X before Z.
                    for z in -radius..=radius {
                        for x in -radius..=radius {
                            let mut cursor = add(p, [x, 0, z]);
                            let distance = x.abs() + z.abs();
                            let mut limit = distance;
                            let mut found = false;
                            if w.test(can_replace, cursor) {
                                while cursor[1] > w.request.min_y + 1 && limit > 0 {
                                    limit -= 1;
                                    if can_place(w, cursor) {
                                        found = true;
                                        break;
                                    }
                                    cursor[1] -= 1;
                                }
                            } else {
                                while cursor[1] < w.request.min_y + w.request.height as i32
                                    && limit > 0
                                {
                                    limit -= 1;
                                    if w.test(cannot_place_on, cursor) {
                                        break;
                                    }
                                    if w.test(air, cursor) {
                                        found = true;
                                        break;
                                    }
                                    cursor[1] += 1;
                                }
                            }
                            if !found {
                                continue;
                            }
                            for _ in 0..=blocks_y - distance / 2 {
                                if w.test(can_replace, cursor) {
                                    put(w, provider, cursor, rng);
                                    any = true;
                                } else if !w.test(continue_through, cursor) {
                                    break;
                                }
                                cursor[1] += 1;
                            }
                        }
                    }
                }
                any
            }
            Self::ReplaceBlobs {
                target,
                state,
                radius,
            } => {
                let mut center = at;
                center[1] = center[1].clamp(
                    w.request.min_y + 1,
                    w.request.min_y + w.request.height as i32 - 1,
                );
                while center[1] > w.request.min_y + 1 && !w.test(target, center) {
                    center[1] -= 1;
                }
                if center[1] <= w.request.min_y + 1 {
                    return false;
                }
                let radii = [radius.sample(rng), radius.sample(rng), radius.sample(rng)];
                let mut any = false;
                manhattan(radii, *radii.iter().max().unwrap(), |offset| {
                    let p = add(center, offset);
                    if w.test(target, p) {
                        w.write(p, *state);
                        any = true;
                    }
                });
                any
            }
            Self::Delta {
                contents,
                rim,
                blocked,
                air,
                size,
                rim_size,
            } => {
                let spawn = rng.unit() < 0.9;
                let rx = if spawn { rim_size.sample(rng) } else { 0 };
                let rz = if spawn { rim_size.sample(rng) } else { 0 };
                let radii = [size.sample(rng), 0, size.sample(rng)];
                let has_rim = spawn && rx != 0 && rz != 0;
                let mut any = false;
                let clear = |w: &World, p| {
                    !w.test(blocked, p)
                        && DIRECTIONS
                            .iter()
                            .all(|d| w.test(air, add(p, *d)) == (*d == [0, 1, 0]))
                };
                manhattan(radii, radii[0].max(radii[2]), |offset| {
                    let p = add(at, offset);
                    if !clear(w, p) {
                        return;
                    }
                    if has_rim {
                        w.write(p, *rim);
                        any = true;
                    }
                    let p = add(p, [rx, 0, rz]);
                    if clear(w, p) {
                        w.write(p, *contents);
                        any = true;
                    }
                });
                any
            }
        }
    }
}
const DIRECTIONS: [[i32; 3]; 6] = [
    [0, -1, 0],
    [0, 1, 0],
    [0, 0, -1],
    [0, 0, 1],
    [-1, 0, 0],
    [1, 0, 0],
];
fn add(a: [i32; 3], b: [i32; 3]) -> [i32; 3] {
    std::array::from_fn(|i| a[i] + b[i])
}
fn inside(w: &World, y: i32) -> bool {
    y >= w.request.min_y && y < w.request.min_y + w.request.height as i32
}
fn put(w: &mut World, p: &Provider, at: [i32; 3], rng: &mut Rng) {
    let material = p.sample(rng, at, &|at| w.material(at), w.noise);
    w.write(at, material);
}
fn put_optional(w: &mut World, p: &Provider, at: [i32; 3], rng: &mut Rng) {
    if let Some(material) = p.sample_optional(rng, at, &|at| w.material(at), w.noise) {
        w.write(at, material);
    }
}
fn manhattan(reach: [i32; 3], max: i32, mut visit: impl FnMut([i32; 3])) {
    for depth in 0..=max {
        for x in -reach[0].min(depth)..=reach[0].min(depth) {
            let y_max = reach[1].min(depth - x.abs());
            for y in -y_max..=y_max {
                let z = depth - x.abs() - y.abs();
                if z > reach[2] {
                    continue;
                }
                visit([x, y, z]);
                if z != 0 {
                    visit([x, y, -z]);
                }
            }
        }
    }
}
