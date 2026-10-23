//! Enumerate the same descending floor transitions as the live scalar scan,
//! borrowing an exact prior column. No extra column owners or caches are kept.
use super::*;

pub(super) fn from_column(
    column: &ColumnState,
    profile: &WorldProfile,
    min_y: i32,
    height: usize,
    start: usize,
) -> (Vec<i32>, usize) {
    debug_assert!(height > 0 && start <= height);
    let empty = |m: u16| {
        profile.material_flags[m as usize] & 64 != 0
            || m == 0 || m == profile.water || m == profile.geology.lava
    };
    let bedrock = |m: u16| {
        profile.material_flags[m as usize] & 128 != 0 || m == profile.bedrock
    };
    let mut current = if start == height { 0 } else { column.material(start) };
    let mut cursor = start;
    let mut grounds = Vec::new();
    let runs = column.runs.slice();
    // Skip runs above the scan's starting point, including a run beginning
    // exactly there: the first comparison is with the voxel immediately below.
    let first = runs.partition_point(|run| (run >> 16) as usize >= start);
    let mut visits = 0;
    for &run in &runs[first..] {
        visits += 1;
        let below = run as u16;
        if empty(current) && !empty(below) && !bedrock(below) {
            grounds.push(min_y + cursor as i32);
        }
        current = below;
        cursor = (run >> 16) as usize;
    }
    (grounds, visits)
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn ground_transitions_match_scalar_at_every_start_and_run_owner() {
        let (mut profile, _, _) = super::super::super::replay_tests::fixture(42, 0);
        // Explicit fluid IDs work even without an empty flag. Both registered
        // bedrock and flagged substitutes are excluded as floors.
        profile.water = 7;
        profile.geology.lava = 4;
        profile.bedrock = 2;
        profile.material_flags.fill(0);
        profile.material_flags[8] = 64;
        profile.material_flags[3] = 128;
        for height in 1..=384 {
            for pattern in 0..4 {
                let min_y = [-512, -64, 0, 37][pattern];
                let blocks: Vec<u16> = (0..height).map(|y| match pattern {
                    0 => 0,
                    1 => 1,
                    2 => (y % 9) as u16,
                    _ => ((y / 17) % 9) as u16,
                }).collect();
                let owned = ColumnState::new(blocks.clone(), &profile, min_y);
                let mut words = vec![u32::MAX; 5];
                words.extend_from_slice(owned.runs.slice());
                let end = words.len();
                words.push(u32::MAX);
                let cached = ColumnState::from_runs(ColumnRuns::Cached {
                    runs: Arc::new(words.clone()), range: 5..end,
                }, height, &profile, min_y);
                let shared = ColumnState::from_runs(ColumnRuns::Shared {
                    mask: Arc::new(crate::geology::CaveMask {
                        origin_x: -32, origin_z: 16, min_y, height: height as u32,
                        width: 0, air_only: false, words, columns: None,
                    }), range: 5..end,
                }, height, &profile, min_y);
                for start in 0..=height {
                    let empty = |m: u16| {
                        profile.material_flags[m as usize] & 64 != 0 || m == 0
                            || m == profile.water || m == profile.geology.lava
                    };
                    let mut expected = Vec::new();
                    let mut current = blocks.get(start).copied().unwrap_or(0);
                    for y in (1..=start).rev() {
                        let below = blocks[y - 1];
                        if empty(current) && !empty(below) && below != profile.bedrock
                            && profile.material_flags[below as usize] & 128 == 0 {
                            expected.push(min_y + y as i32);
                        }
                        current = below;
                    }
                    for column in [&owned, &cached, &shared] {
                        let (actual, visits) = from_column(column, &profile, min_y, height, start);
                        assert_eq!(actual, expected, "height={height} start={start} pattern={pattern}");
                        assert!(visits <= start && visits <= column.runs.slice().len());
                    }
                }
            }
        }
    }
}
