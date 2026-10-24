use super::*;
use super::tests::{column, key};

fn session() -> Arc<Session> {
    let mut session = Session::default();
    session.cross_stage = true;
    Arc::new(session)
}
fn stage(before: usize) -> Key {
    Key { before, ..key() }
}

#[test]
fn cross_stage_census_captures_demand_time_without_pinning_or_late_publication() {
    let session = session();
    let earlier = column(&[1]);
    let later = column(&[2]);
    session.begin_with_layout(stage(4), 0, true, true).unwrap().complete(&earlier);
    session.begin(stage(12), 1, false).unwrap().complete(&later);
    let mut demand = session.begin(stage(8), 2, false).unwrap();
    demand.source(Source::Prefix);
    let late = column(&[1]);
    session.begin(stage(6), 3, false).unwrap().complete(&late);
    assert_eq!(Arc::strong_count(&earlier), 1);
    assert!(Arc::try_unwrap(earlier).is_ok());
    demand.complete(&column(&[1]));
    let report = session.report();
    // Inspect only the demand group: the late publisher is itself observed.
    let counts = &report["groups"].as_array().unwrap().iter()
        .find(|g| g["before"] == 8 && g["interior"] == false).unwrap()["counts"];
    assert_eq!(counts["cross_stage_compared_snapshots"], 2);
    assert_eq!(counts["cross_stage_equal_earlier"], 1);
    assert_eq!(counts["cross_stage_equal_later"], 0);
    assert_eq!(counts["cross_stage_equal_compact"], 1);
    assert_eq!(counts["cross_stage_equal_tile"], 0);
    assert_eq!(counts["prefix_cross_stage_equal_without_exact_other_live"], 1);
    drop(later);
    drop(late);
    assert_eq!(session.report()["pending_builds"], 0);
    assert_eq!(session.report()["live_handles_at_wave_end"], 0);
}

#[test]
fn cross_stage_census_excludes_same_job_dead_values_and_isolates_contexts() {
    for changed in 0..12 {
        let session = session();
        let value = column(&[1]);
        session.begin(stage(4), 0, false).unwrap().complete(&value);
        let mut other = stage(8);
        match changed {
            0 => other.engine += 1,
            1 => other.profile += 1,
            2 => other.roots += 1,
            3 => other.request.seed += 1,
            4 => other.request.x += 1,
            5 => other.request.z += 1,
            6 => other.request.min_y += 1,
            7 => other.request.height += 1,
            8 => other.request.profile += 1,
            9 => other.request.frequency += 1,
            10 => other.request.base += 1,
            _ => other.request.amplitude += 1,
        }
        session.begin(other, 1, false).unwrap().complete(&column(&[1]));
        session.begin(stage(9), 0, false).unwrap().complete(&column(&[1]));
        drop(value);
        session.begin(stage(10), 2, false).unwrap().complete(&column(&[1]));
        assert_eq!(session.report()["counts"]["cross_stage_compared_snapshots"], 0);
    }
}

#[test]
fn cross_stage_census_accepts_equal_voxels_and_separates_exact_key_availability() {
    let session = session();
    let value = column(&[(4 << 16) | 1, 1]);
    let exact = column(&[1]);
    let compact = column(&[1]);
    session.begin(stage(12), 0, false).unwrap().complete(&value);
    session.begin_with_layout(stage(12), 3, true, true).unwrap().complete(&compact);
    session.begin(stage(8), 1, false).unwrap().complete(&exact);
    session.begin(stage(8), 2, false).unwrap().complete(&column(&[1]));
    let report = session.report();
    let counts = &report["counts"];
    assert_eq!(counts["cross_stage_equal_later"], 2);
    assert_eq!(counts["cross_stage_equal_any"], 2);
    assert_eq!(counts["cross_stage_equal_without_exact_other_live"], 1);
    assert_eq!(counts["cross_stage_equal_tile"], 2);
    assert_eq!(counts["cross_stage_equal_compact"], 2);
    assert_eq!(counts["cross_stage_compared_snapshots"], 2);
    assert_eq!(counts["voxel_mismatches"], 0);
    assert_eq!(Arc::strong_count(&value), 1);
    assert_eq!(Arc::strong_count(&exact), 1);
}

#[test]
fn cross_stage_census_sampling_and_caps_are_explicit() {
    let mut session = Session::new(1, 0);
    session.cross_stage = true;
    for x in -256..256 {
        for before in [0, 1, 8, 41] {
            assert_eq!(session.selected(before, x, 17), selected(0, x, 17));
        }
    }
    let session = Arc::new(session);
    let value = column(&[1]);
    session.begin(stage(4), 0, false).unwrap().complete(&value);
    assert!(session.begin(stage(8), 1, false).is_none());
    let report = session.report();
    assert_eq!(report["keys"], 1);
    assert_eq!(report["snapshot_logical_bytes"], 0);
    assert_eq!(report["counts"]["skipped_keys"], 1);
    assert_eq!(report["counts"]["skipped_snapshots"], 1);
    assert_eq!(report["counts"]["cross_stage_compared_snapshots"], 0);
}

#[test]
fn cross_stage_census_parallel_publication_leaves_no_live_owners_or_pending_builds() {
    let session = session();
    let barrier = std::sync::Barrier::new(9);
    std::thread::scope(|scope| {
        for before in 0..8 {
            let session = &session;
            let barrier = &barrier;
            scope.spawn(move || {
                let value = column(&[1]);
                session.begin(stage(before), before as u64, false).unwrap().complete(&value);
                barrier.wait();
                barrier.wait();
                assert_eq!(Arc::strong_count(&value), 1);
            });
        }
        barrier.wait();
        let demand = session.begin(stage(9), 100, false).unwrap();
        assert_eq!(demand.candidates.len(), 8);
        demand.complete(&column(&[1]));
        barrier.wait();
    });
    assert_eq!(session.report()["pending_builds"], 0);
    assert_eq!(session.report()["live_handles_at_wave_end"], 0);
}

#[test]
fn cross_stage_census_abandoned_build_releases_captured_snapshots() {
    let session = session();
    let value = column(&[1]);
    session.begin(stage(4), 0, false).unwrap().complete(&value);
    let demand = session.begin(stage(8), 1, false).unwrap();
    assert_eq!(demand.candidates.len(), 1);
    let snapshot = Arc::downgrade(&demand.candidates[0].snapshot);
    assert_eq!(Arc::strong_count(&value), 1);
    drop(value);
    drop(demand);
    assert_eq!(session.report()["pending_builds"], 0);
    assert_eq!(session.report()["live_handles_at_wave_end"], 0);
    assert_eq!(session.report()["counts"]["abandoned_or_failed"], 1);
    drop(session);
    assert_eq!(snapshot.strong_count(), 0);
}
