//! Conservative horizontal footprints for placement programs whose random
//! horizontal positions do not read prior world state. Live filters and vertical
//! moves are deliberately ignored: the footprint includes every possible write.
use super::super::*;
use super::{Bounds, Root};

pub(super) fn eligible(profile: &WorldProfile, ids: &[u32]) -> bool {
    // A shared placement salt shares live EvaluationCache entries between
    // recipes, including their full positions. Custom programs can reuse a
    // prefix with different draws; independent masks cannot prove that union.
    let mut salts = spatial::Set::default();
    !ids.is_empty()
        && ids.iter().all(|&id| {
            let recipe = &profile.decorations[id as usize];
            salts.insert(recipe.placement_salt.unwrap_or(recipe.salt))
                && recipe.reach.is_some_and(|reach| reach >= 0)
                && recipe.placement.as_ref().is_some_and(|program| {
                    program.len() <= 64
                        && program.iter().all(|op| {
                            matches!(
                                op,
                                placement::Modifier::Count { .. }
                                    | placement::Modifier::InSquare
                                    | placement::Modifier::HeightRange { .. }
                                    | placement::Modifier::Biome
                                    | placement::Modifier::RarityFilter { .. }
                                    | placement::Modifier::RandomChance { .. }
                                    | placement::Modifier::Select { .. }
                                    | placement::Modifier::Offset { .. }
                                    | placement::Modifier::Heightmap { .. }
                                    | placement::Modifier::BlockPredicateFilter { .. }
                                    | placement::Modifier::SurfaceWaterDepthFilter { .. }
                                    | placement::Modifier::SurfaceRelativeThresholdFilter { .. }
                                    | placement::Modifier::EnvironmentScan { .. }
                            )
                        })
                })
        })
}

pub(super) enum Footprint {
    Unbounded,
    Known {
        origin: [i64; 2],
        width: usize,
        rows: Box<[u64]>,
    },
}
impl Footprint {
    pub(super) fn build(
        profile: &WorldProfile,
        root: &Root,
        request: ChunkRequest,
        ax: i32,
        az: i32,
    ) -> Self {
        if !root.footprint {
            return Self::Unbounded;
        }
        let Bounds { x, z } = root.bounds;
        let width = x[1] - x[0] + 1;
        let height = z[1] - z[0] + 1;
        // Limit only this optional index. Wide/custom or expensive programs keep
        // the original exact dependency evaluation without a footprint filter.
        if !(1..=64).contains(&width) || !(1..=64).contains(&height) {
            return Self::Unbounded;
        }
        let origin = [i64::from(ax) * 16 + x[0], i64::from(az) * 16 + z[0]];
        if [
            origin[0],
            origin[0] + width - 1,
            origin[1],
            origin[1] + height - 1,
        ]
        .iter()
        .any(|&v| i32::try_from(v).is_err())
        {
            return Self::Unbounded;
        }
        let mut rows = vec![0u64; height as usize];
        let request = ChunkRequest {
            chunk_x: ax,
            chunk_z: az,
            ..request
        };
        let mut work = 0;
        for &id in &root.ids {
            let recipe = &profile.decorations[id as usize];
            let salt = recipe.placement_salt.unwrap_or(recipe.salt) as u64;
            let rng = Rng::new(
                request.seed ^ salt ^ mix(ax as u32 as u64) ^ mix((az as u32 as u64) << 32),
            );
            let mut mark = |at: [i64; 2]| {
                let reach = i64::from(recipe.reach.unwrap());
                let lo = [at[0] - reach - origin[0], at[1] - reach - origin[1]];
                let hi = [at[0] + reach - origin[0], at[1] + reach - origin[1]];
                if lo[0] < 0 || lo[1] < 0 || hi[0] >= width || hi[1] >= height {
                    return false;
                }
                let span = (hi[0] - lo[0] + 1) as u32;
                let bits = if span == 64 {
                    u64::MAX
                } else {
                    ((1u64 << span) - 1) << lo[0]
                };
                for row in &mut rows[lo[1] as usize..=hi[1] as usize] {
                    *row |= bits;
                }
                true
            };
            if !walk(
                recipe.placement.as_ref().unwrap(),
                0,
                request,
                rng,
                [i64::from(ax) * 16, i64::from(az) * 16],
                &mut work,
                &mut mark,
            ) {
                return Self::Unbounded;
            }
        }
        Self::Known {
            origin,
            width: width as usize,
            rows: rows.into_boxed_slice(),
        }
    }
    pub(super) fn contains(&self, x: i32, z: i32) -> bool {
        match self {
            Self::Unbounded => true,
            Self::Known {
                origin,
                width,
                rows,
            } => {
                let x = i64::from(x) - origin[0];
                let z = i64::from(z) - origin[1];
                x >= 0
                    && x < *width as i64
                    && z >= 0
                    && z < rows.len() as i64
                    && rows[z as usize] & (1u64 << x) != 0
            }
        }
    }
}
fn walk(
    program: &placement::Program,
    index: usize,
    request: ChunkRequest,
    mut rng: Rng,
    mut at: [i64; 2],
    work: &mut usize,
    mark: &mut impl FnMut([i64; 2]) -> bool,
) -> bool {
    *work += 1;
    if *work > 16_384 {
        return false;
    }
    if index == program.len() {
        return mark(at);
    }
    match &program[index] {
        placement::Modifier::Count { count } => {
            let count = count.sample(&mut rng).max(0) as usize;
            for _ in 0..count {
                if !walk(
                    program,
                    index + 1,
                    request,
                    Rng::new(rng.next()),
                    at,
                    work,
                    mark,
                ) {
                    return false;
                }
            }
            return true;
        }
        placement::Modifier::InSquare => {
            at[0] += i64::from(rng.below(16));
            at[1] += i64::from(rng.below(16));
        }
        placement::Modifier::HeightRange { height } => {
            height.sample(&mut rng, request);
        }
        placement::Modifier::Offset { x, y, z } => {
            at[0] += i64::from(x.sample(&mut rng));
            y.sample(&mut rng); // The vertical draw affects the following Z draw.
            at[1] += i64::from(z.sample(&mut rng));
        }
        placement::Modifier::RarityFilter { chance } => {
            if rng.unit() >= 1.0 / *chance as f64 {
                return true;
            }
        }
        placement::Modifier::RandomChance { chance } => {
            if (rng.unit() as f32) >= *chance {
                return true;
            }
        }
        placement::Modifier::Select { min, max } => {
            let value = rng.unit();
            if value < *min || value >= *max {
                return true;
            }
        }
        // These operations never consume RNG or move horizontally. Their live
        // rejection/height cannot create a candidate outside this superset.
        placement::Modifier::Biome
        | placement::Modifier::Heightmap { .. }
        | placement::Modifier::BlockPredicateFilter { .. }
        | placement::Modifier::SurfaceWaterDepthFilter { .. }
        | placement::Modifier::SurfaceRelativeThresholdFilter { .. }
        | placement::Modifier::EnvironmentScan { .. } => {}
        _ => return false,
    }
    walk(program, index + 1, request, rng, at, work, mark)
}

#[cfg(test)]
mod tests {
    use super::*;
    use serde_json::json;
    #[test]
    fn pure_footprints_match_real_placement_streams_and_negative_coordinates() {
        for (seed, coordinate) in [(42, -3), (71, 2), (123456789, -1)] {
            for reach in [0, 3] {
                for program in [
                    json!([{"type":"count","count":{"type":"uniform","min_inclusive":2,"max_inclusive":7}},
                        {"type":"in_square"},{"type":"height_range","height":{"type":"uniform",
                        "min_inclusive":{"absolute":8},"max_inclusive":{"absolute":90}}},{"type":"in_square"},{"type":"biome"}]),
                    json!([{"type":"height_range","height":{"type":"uniform","min_inclusive":{"absolute":1},
                        "max_inclusive":{"absolute":99}}},{"type":"count","count":2},{"type":"in_square"},
                        {"type":"count","count":3},{"type":"in_square"}]),
                    json!([{"type":"count","count":0},{"type":"in_square"}]),
                    json!([{"type":"count","count":24},{"type":"rarity_filter","chance":2},
                        {"type":"in_square"},{"type":"heightmap","map":1},
                        {"type":"offset","x":{"type":"uniform","min_inclusive":-2,"max_inclusive":2},
                            "y":{"type":"uniform","min_inclusive":-3,"max_inclusive":3},
                            "z":{"type":"uniform","min_inclusive":-2,"max_inclusive":2}},
                        {"type":"random_chance","chance":0.75},{"type":"select","min":0.1,"max":0.9},
                        {"type":"block_predicate_filter","predicate":{"type":"true"}},
                        {"type":"surface_water_depth_filter","max_water_depth":100},
                        {"type":"surface_relative_threshold_filter","map":1},
                        {"type":"environment_scan","direction":-1,"max_steps":5,
                            "target_condition":{"type":"true"}},{"type":"biome"}]),
                    json!([{"type":"rarity_filter","chance":2},{"type":"count","count":3},
                        {"type":"heightmap","map":1},{"type":"in_square"},{"type":"count","count":2},
                        {"type":"offset","x":-2,"y":2,"z":3},{"type":"in_square"}]),
                ] {
                    let (mut profile, field, request) =
                        super::super::super::replay_tests::fixture(seed, coordinate);
                    profile.decorations[0].placement =
                        Some(serde_json::from_value(program).unwrap());
                    profile.decorations[0].reach = Some(reach);
                    let recipe = &profile.decorations[0];
                    let root = Root {
                        definition: None,
                        ids: vec![0],
                        bounds: Bounds::recipe(recipe).unwrap(),
                        deferred_ore: None,
                        footprint: eligible(&profile, &[0]),
                    };
                    let mask = Footprint::build(&profile, &root, request, coordinate, coordinate);
                    assert!(matches!(mask, Footprint::Known { .. }));
                    let salt = recipe.placement_salt.unwrap_or(recipe.salt) as u64;
                    let rng = Rng::new(
                        seed ^ salt
                            ^ mix(coordinate as u32 as u64)
                            ^ mix((coordinate as u32 as u64) << 32),
                    );
                    let mut entries = Vec::new();
                    let mut missing = Vec::new();
                    placement::expand(
                        recipe.placement.as_ref().unwrap(),
                        0,
                        0,
                        [coordinate * 16, request.min_y, coordinate * 16],
                        rng,
                        &field,
                        request,
                        None,
                        Some(&mut entries),
                        &mut missing,
                    );
                    let mut queue = placement::Queue::new(entries);
                    let overlay = placement::Overlay::default();
                    let mut cache = placement::EvaluationCache::default();
                    let mut expected = spatial::Set::default();
                    while let Some(candidate) = queue.next_checkpoint(
                        &field,
                        &profile,
                        request,
                        &overlay,
                        &mut cache,
                        None,
                        &mut missing,
                        None,
                        |_, _| false,
                    ) {
                        if let Some(at) = placement::position(
                            &candidate, recipe, &field, &profile, request, &overlay, &mut cache,
                        ) {
                            for z in at[2] - reach..=at[2] + reach {
                                for x in at[0] - reach..=at[0] + reach {
                                    expected.insert((x, z));
                                }
                            }
                        }
                    }
                    assert!(missing.is_empty());
                    for z in coordinate * 16 - 8..coordinate * 16 + 40 {
                        for x in coordinate * 16 - 8..coordinate * 16 + 40 {
                            assert_eq!(mask.contains(x, z), expected.contains(&(x, z)));
                        }
                    }
                }
            }
        }
    }
    #[test]
    fn unsupported_wide_and_expensive_footprints_leave_all_writers_eligible() {
        let (mut profile, _, request) = super::super::super::replay_tests::fixture(42, 0);
        assert!(!eligible(&profile, &[0])); // Missing exported feature reach.
        for (count, reach) in [(100_000, 1), (1, 100)] {
            profile.decorations[0].placement = Some(
                serde_json::from_value(json!([
                {"type":"count","count":count},{"type":"in_square"}]))
                .unwrap(),
            );
            profile.decorations[0].reach = Some(reach);
            let root = Root {
                definition: None,
                ids: vec![0],
                bounds: Bounds::recipe(&profile.decorations[0]).unwrap(),
                deferred_ore: None,
                footprint: eligible(&profile, &[0]),
            };
            let mask = Footprint::build(&profile, &root, request, 0, 0);
            assert!(matches!(mask, Footprint::Unbounded));
            assert!(mask.contains(-1000, 1000));
        }
        for program in [
            json!([{"type":"count_on_every_layer","count":1}]),
            json!([{"type":"noise_based_count","noise_to_count_ratio":1,"noise_factor":30,"noise_offset":1}]),
            json!([{"type":"cuboid","xz_size":1,"y_size":1}]),
            json!(vec![json!({"type":"biome"}); 65]),
        ] {
            profile.decorations[0].placement = Some(serde_json::from_value(program).unwrap());
            profile.decorations[0].reach = Some(1);
            assert!(!eligible(&profile, &[0]));
            let root = Root {
                definition: None,
                ids: vec![0],
                bounds: Bounds::recipe(&profile.decorations[0]).unwrap(),
                deferred_ore: None,
                footprint: false,
            };
            assert!(matches!(
                Footprint::build(&profile, &root, request, 0, 0),
                Footprint::Unbounded
            ));
        }
    }

    #[test]
    fn shared_live_placement_groups_keep_original_evaluation() {
        let (mut profile, _, _) = super::super::super::replay_tests::fixture(42, 0);
        for (id, x) in [(0, -2), (1, 2)] {
            profile.decorations[id].placement = Some(
                serde_json::from_value(json!([
                    {"type":"offset","x":x},{"type":"heightmap","map":1},
                    {"type":"count","count":2},{"type":"in_square"}
                ]))
                .unwrap(),
            );
            profile.decorations[id].reach = Some(3);
            profile.decorations[id].placement_salt = Some(71);
            assert!(eligible(&profile, &[id as u32]));
        }
        assert!(!eligible(&profile, &[0, 1]));
        profile.decorations[1].placement_salt = Some(42);
        assert!(eligible(&profile, &[0, 1]));
    }
}
