//! Test-only opportunity counts. A completed root already evaluated its target
//! rectangle's inverse-footprint writers. Count boundary prefixes whose whole
//! writer rectangle is a subset, without starting any generation or indexing
//! additional outputs. This proves writer coverage, not a speedup or identity.
use super::{Buffer, Op};
use crate::decoration::dependencies::{Bounds, Planner};

#[derive(Debug, PartialEq, Eq)]
enum Coverage {
    Unrepresentable,
    Empty,
    Covered(u64),
    Uncovered,
}

fn range(min: i128, max: i128, extent: [i64; 2]) -> Option<[i128; 2]> {
    let lo = -(-(min - i128::from(extent[1]))).div_euclid(16);
    let hi = (max - i128::from(extent[0])).div_euclid(16);
    if lo <= hi && (lo < i128::from(i32::MIN) || hi > i128::from(i32::MAX)) {
        None
    } else {
        Some([lo, hi])
    }
}

fn classify(bounds: Bounds, origin: [i32; 2], width: usize, x: i32, z: i32) -> Coverage {
    if width == 0 { return Coverage::Unrepresentable; }
    let axes = [bounds.x, bounds.z];
    let point = [x, z];
    let mut query = [[0; 2]; 2];
    for axis in 0..2 {
        let Some(at) = range(i128::from(point[axis]), i128::from(point[axis]), axes[axis]) else {
            return Coverage::Unrepresentable;
        };
        query[axis] = at;
    }
    // writer_range returns no coordinates if either axis is empty.
    if query.iter().any(|r| r[0] > r[1]) { return Coverage::Empty; }
    for axis in 0..2 {
        let min = i128::from(origin[axis]);
        let Some(target) = range(min, min + width as i128 - 1, axes[axis]) else {
            return Coverage::Unrepresentable;
        };
        if query[axis][0] < target[0] || query[axis][1] > target[1] {
            return Coverage::Uncovered;
        }
    }
    let count = (query[0][1] - query[0][0] + 1) * (query[1][1] - query[1][0] + 1);
    match u64::try_from(count) {
        Ok(count) => Coverage::Covered(count),
        Err(_) => Coverage::Unrepresentable,
    }
}

pub(super) fn observe(
    work: &mut Buffer, planner: &Planner<'_>, before: usize, x: i32, z: i32, limited: bool,
) {
    if planner.versions.index(x, z).is_some() { return; }
    let root = before.checked_sub(1);
    work.add(Op::BoundaryTargetPrefix, root, 1);
    if limited { work.add(Op::BoundaryTargetLimited, root, 1); }
    let Some(root) = root.filter(|&root| root < planner.completed.len()) else {
        work.add(Op::BoundaryTargetUnfinished, root, 1);
        return;
    };
    let status = classify(planner.roots[root].bounds, planner.versions.origin,
        planner.versions.width, x, z);
    let op = match status {
        Coverage::Unrepresentable => Op::BoundaryTargetUnrepresentable,
        Coverage::Empty => Op::BoundaryTargetEmpty,
        Coverage::Covered(writers) => {
            work.add(Op::BoundaryTargetWriterPositions, Some(root), writers);
            Op::BoundaryTargetCovered
        }
        Coverage::Uncovered => Op::BoundaryTargetUncovered,
    };
    work.add(op, Some(root), 1);
}

#[test]
fn rectangle_proof_matches_original_writer_enumeration_across_boundaries() {
    use std::collections::BTreeSet;
    for origin in [[-65, -17], [-16, 16], [0, 0], [31, -32]] {
        for width in [1, 16, 32] {
            for (x, z) in [([0, 0], [0, 0]), ([-1, 16], [-9, 7]),
                ([-16, 31], [17, 49]), ([-47, 15], [-2, 33]), ([17, 4], [0, 0])] {
                let bounds = Bounds { x, z };
                let max = [origin[0] + width as i32 - 1, origin[1] + width as i32 - 1];
                let target: BTreeSet<_> = bounds.writer_range(origin, max).unwrap().collect();
                for dz in (-33..width as i32 + 33).step_by(3) {
                    for dx in (-33..width as i32 + 33).step_by(3) {
                        let (x, z) = (origin[0] + dx, origin[1] + dz);
                        let query: Vec<_> = bounds.writers(x, z).unwrap().collect();
                        let expected = if query.is_empty() { Coverage::Empty }
                            else if query.iter().all(|at| target.contains(at)) {
                                Coverage::Covered(query.len() as u64)
                            } else { Coverage::Uncovered };
                        assert_eq!(classify(bounds, origin, width, x, z), expected,
                            "origin={origin:?} width={width} point={x},{z}");
                    }
                }
            }
        }
    }
}

#[test]
fn optional_geometry_proof_rejects_unrepresentable_writers_without_wrapping() {
    let bounds = Bounds { x: [i64::MIN, i64::MAX], z: [0, 15] };
    assert_eq!(classify(bounds, [0, 0], 512, 0, 0), Coverage::Unrepresentable);
    let bounds = Bounds { x: [-1, 16], z: [-1, 16] };
    assert_eq!(classify(bounds, [i32::MIN, i32::MIN], 0, i32::MIN, i32::MIN), Coverage::Unrepresentable);
    // Coordinates near signed world edges remain ordinary representable
    // anchor coordinates; use wide arithmetic rather than overflowing a bound.
    assert!(matches!(classify(bounds, [i32::MAX - 15, i32::MAX - 15], 16,
        i32::MAX, i32::MAX), Coverage::Covered(_)));
}
