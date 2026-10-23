use super::*;

fn oracle(blocks: &[u16], profile: &WorldProfile, min_y: i32) -> [i32; 6] {
    std::array::from_fn(|map| {
        blocks.iter().rposition(|&m| profile.heightmap_masks[m as usize] & (1 << map) != 0)
            .map_or(min_y, |y| min_y + y as i32 + 1)
    })
}

#[test]
fn direct_heights_match_voxel_oracle_for_all_masks_and_valid_extremes() {
    let (mut profile, _, _) = crate::decoration::replay_tests::fixture(42, 0);
    for mask in 0..64u8 {
        profile.heightmap_masks[0] = 0;
        profile.heightmap_masks[1] = mask | 192;
        profile.heightmap_masks[2] = (mask ^ 63) | 64;
        for height in [0, 1, 16, 96, 128, 256, 384, 4096] {
            for min_y in [i32::MIN, -64, 0, 48, i32::MAX - height as i32] {
                for pattern in 0..5 {
                    let mut random = 17u32;
                    let blocks: Vec<u16> = (0..height).map(|y| match pattern {
                        0 => 0,
                        1 => 1,
                        2 => (y % 3) as u16,
                        3 => ((y / 17) % 3) as u16,
                        _ => { random = random.wrapping_mul(1664525).wrapping_add(1013904223); (random % 3) as u16 }
                    }).collect();
                    let expected = oracle(&blocks, &profile, min_y);
                    let column = ColumnState::new(blocks.clone());
                    assert_eq!(column.expand(height), blocks);
                    for _ in 0..2 {
                        for map in 0..6 { assert_eq!(column.height(&profile, min_y, height, map), expected[map as usize]); }
                    }
                }
            }
        }
    }
}

#[test]
fn direct_heights_concurrent_repeated_reads_keep_values_and_release_owners() {
    let (profile, _, _) = crate::decoration::replay_tests::fixture(42, 0);
    let blocks: Vec<u16> = (0..4096).map(|y| ((y / 5) % profile.materials.len()) as u16).collect();
    let expected = oracle(&blocks, &profile, -64);
    let column = Arc::new(ColumnState::new(blocks));
    let weak = Arc::downgrade(&column);
    let barrier = std::sync::Barrier::new(8);
    std::thread::scope(|scope| {
        for _ in 0..8 {
            let (column, barrier, profile) = (column.clone(), &barrier, &profile);
            scope.spawn(move || {
                barrier.wait();
                for _ in 0..100 { assert_eq!(column.heights(profile, -64, 4096), expected); }
            });
        }
    });
    drop(column); assert!(weak.upgrade().is_none());
}

#[test]
fn direct_heights_never_reuse_a_different_profile_or_geometry_result() {
    let (mut a, _, _) = crate::decoration::replay_tests::fixture(42, 0);
    let (mut b, _, _) = crate::decoration::replay_tests::fixture(42, 0);
    a.heightmap_masks[1] = 5; b.heightmap_masks[1] = 42;
    let blocks = vec![1; 16];
    let column = ColumnState::new(blocks.clone());
    for _ in 0..3 {
        assert_eq!(column.heights(&a, i32::MIN, 16), oracle(&blocks, &a, i32::MIN));
        assert_eq!(column.heights(&b, 16, 16), oracle(&blocks, &b, 16));
        assert_eq!(column.heights(&a, i32::MAX - 16, 16), oracle(&blocks, &a, i32::MAX - 16));
    }
}

#[test]
fn direct_heights_invalid_maps_preserve_array_index_failure() {
    let (profile, _, _) = crate::decoration::replay_tests::fixture(42, 0);
    for blocks in [Vec::new(), vec![0; 16], vec![1; 16]] {
        let height = blocks.len(); let column = ColumnState::new(blocks);
        for map in [6, 7, 8, 63, 255] {
            assert!(std::panic::catch_unwind(|| column.height(&profile, 0, height, map)).is_err());
        }
    }
}

#[test]
fn direct_heights_normal_layout_model() {
    // These field sets exclude cfg(test) tokens. Payload layout is not RSS.
    #[allow(dead_code)] struct Eager { runs: ColumnRuns, heights: [i32; 6] }
    #[allow(dead_code)] struct Direct { runs: ColumnRuns }
    #[allow(dead_code)] struct SavedEager { range: std::ops::Range<usize>, heights: [i32; 6] }
    println!("direct height normal field layouts: eager={} direct={} old_saved={} range={}",
        std::mem::size_of::<Eager>(), std::mem::size_of::<Direct>(),
        std::mem::size_of::<SavedEager>(), std::mem::size_of::<std::ops::Range<usize>>());
}
