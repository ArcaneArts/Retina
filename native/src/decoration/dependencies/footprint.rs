//! Conservative horizontal footprints for placement programs whose random
//! positions do not read prior world state. Biome and vertical rejection are
//! deliberately ignored: the footprint must include every possible write.
use super::{Bounds, Root};
use super::super::*;

pub(super) fn eligible(profile: &WorldProfile, ids: &[u32]) -> bool {
    !ids.is_empty() && ids.iter().all(|&id| {
        let recipe = &profile.decorations[id as usize];
        recipe.reach.is_some_and(|reach| reach >= 0)
            && recipe.placement.as_ref().is_some_and(|program| program.iter().all(|op| {
                matches!(op, placement::Modifier::Count { .. } | placement::Modifier::InSquare
                    | placement::Modifier::HeightRange { .. } | placement::Modifier::Biome)
            }))
    })
}

pub(super) enum Footprint {
    Unbounded,
    Known { origin: [i64; 2], width: usize, rows: Box<[u64]> },
}
impl Footprint {
    pub(super) fn build(profile: &WorldProfile, root: &Root, request: ChunkRequest, ax: i32, az: i32) -> Self {
        if !root.footprint { return Self::Unbounded; }
        let Bounds { x, z } = root.bounds;
        let width = x[1] - x[0] + 1;
        let height = z[1] - z[0] + 1;
        // Limit only this optional index. Wide/custom or expensive programs keep
        // the original exact dependency evaluation without a footprint filter.
        if !(1..=64).contains(&width) || !(1..=64).contains(&height) { return Self::Unbounded; }
        let origin = [i64::from(ax) * 16 + x[0], i64::from(az) * 16 + z[0]];
        if [origin[0], origin[0] + width - 1, origin[1], origin[1] + height - 1]
            .iter().any(|&v| i32::try_from(v).is_err()) { return Self::Unbounded; }
        let mut rows = vec![0u64; height as usize];
        let request = ChunkRequest { chunk_x: ax, chunk_z: az, ..request };
        let mut work = 0;
        for &id in &root.ids {
            let recipe = &profile.decorations[id as usize];
            let salt = recipe.placement_salt.unwrap_or(recipe.salt) as u64;
            let rng = Rng::new(request.seed ^ salt ^ mix(ax as u32 as u64) ^ mix((az as u32 as u64) << 32));
            let mut mark = |at: [i64; 2]| {
                let reach = i64::from(recipe.reach.unwrap());
                let lo = [at[0] - reach - origin[0], at[1] - reach - origin[1]];
                let hi = [at[0] + reach - origin[0], at[1] + reach - origin[1]];
                if lo[0] < 0 || lo[1] < 0 || hi[0] >= width || hi[1] >= height { return false; }
                let span = (hi[0] - lo[0] + 1) as u32;
                let bits = if span == 64 { u64::MAX } else { ((1u64 << span) - 1) << lo[0] };
                for row in &mut rows[lo[1] as usize..=hi[1] as usize] { *row |= bits; }
                true
            };
            if !walk(recipe.placement.as_ref().unwrap(), 0, request, rng,
                [i64::from(ax) * 16, i64::from(az) * 16], &mut work, &mut mark) {
                return Self::Unbounded;
            }
        }
        Self::Known { origin, width: width as usize, rows: rows.into_boxed_slice() }
    }
    pub(super) fn contains(&self, x: i32, z: i32) -> bool {
        match self {
            Self::Unbounded => true,
            Self::Known { origin, width, rows } => {
                let x = i64::from(x) - origin[0]; let z = i64::from(z) - origin[1];
                x >= 0 && x < *width as i64 && z >= 0 && z < rows.len() as i64
                    && rows[z as usize] & (1u64 << x) != 0
            }
        }
    }
}
fn walk(program: &placement::Program, index: usize, request: ChunkRequest, mut rng: Rng,
        mut at: [i64; 2], work: &mut usize, mark: &mut impl FnMut([i64; 2]) -> bool) -> bool {
    *work += 1;
    if *work > 16_384 { return false; }
    if index == program.len() { return mark(at); }
    match &program[index] {
        placement::Modifier::Count { count } => {
            let count = count.sample(&mut rng).max(0) as usize;
            for _ in 0..count {
                if !walk(program, index + 1, request, Rng::new(rng.next()), at, work, mark) { return false; }
            }
            return true;
        }
        placement::Modifier::InSquare => { at[0] += i64::from(rng.below(16)); at[1] += i64::from(rng.below(16)); }
        placement::Modifier::HeightRange { height } => { height.sample(&mut rng, request); }
        placement::Modifier::Biome => {}
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
                ] {
                    let (mut profile, field, request) = super::super::super::replay_tests::fixture(seed, coordinate);
                    profile.decorations[0].placement = Some(serde_json::from_value(program).unwrap());
                    profile.decorations[0].reach = Some(reach);
                    let recipe = &profile.decorations[0];
                    let root = Root { definition: None, ids: vec![0], bounds: Bounds::recipe(recipe).unwrap(),
                        deferred_ore: None, footprint: eligible(&profile, &[0]) };
                    let mask = Footprint::build(&profile, &root, request, coordinate, coordinate);
                    assert!(matches!(mask, Footprint::Known { .. }));
                    let salt = recipe.placement_salt.unwrap_or(recipe.salt) as u64;
                    let rng = Rng::new(seed ^ salt ^ mix(coordinate as u32 as u64) ^ mix((coordinate as u32 as u64) << 32));
                    let mut entries = Vec::new(); let mut missing = Vec::new();
                    placement::expand(recipe.placement.as_ref().unwrap(), 0, 0,
                        [coordinate * 16, request.min_y, coordinate * 16], rng, &field, request,
                        None, Some(&mut entries), &mut missing);
                    let mut queue = placement::Queue::new(entries);
                    let overlay = placement::Overlay::default(); let mut cache = placement::EvaluationCache::default();
                    let mut expected = spatial::Set::default();
                    while let Some(candidate) = queue.next_checkpoint(&field, &profile, request,
                        &overlay, &mut cache, None, &mut missing, None, |_, _| false) {
                        if let Some(at) = placement::position(&candidate, recipe, &field, &profile, request, &overlay, &mut cache) {
                            for z in at[2] - reach..=at[2] + reach {
                                for x in at[0] - reach..=at[0] + reach { expected.insert((x, z)); }
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
        assert!(!eligible(&profile, &[0])); // Heightmap reads live prior state.
        for (count, reach) in [(100_000, 1), (1, 100)] {
            profile.decorations[0].placement = Some(serde_json::from_value(json!([
                {"type":"count","count":count},{"type":"in_square"}])).unwrap());
            profile.decorations[0].reach = Some(reach);
            let root = Root { definition: None, ids: vec![0], bounds: Bounds::recipe(&profile.decorations[0]).unwrap(),
                deferred_ore: None, footprint: eligible(&profile, &[0]) };
            let mask = Footprint::build(&profile, &root, request, 0, 0);
            assert!(matches!(mask, Footprint::Unbounded));
            assert!(mask.contains(-1000, 1000));
        }
    }
}
