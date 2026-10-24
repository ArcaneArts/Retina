use super::*;
use super::tests::{column, key};
fn session() -> Arc<Session> {
    let mut s = Session::default();
    s.cross_stage = true;
    s.identity_intervals = true;
    Arc::new(s)
}
fn stage(before: usize) -> Key { Key { before, ..key() } }

#[test]
fn identity_intervals_require_complete_demand_time_coverage() {
    let s = session(); let value = column(&[1]);
    s.begin(stage(1), 0, false).unwrap().complete(&value);
    s.identity(stage(2), false); s.identity(stage(4), false);
    let hole = s.begin(stage(4), 1, false).unwrap();
    assert!(!hole.candidates.iter().any(|v| v.certified));
    s.identity(stage(3), false);
    // Publishing the missing link cannot retroactively certify the demand.
    assert!(!hole.candidates.iter().any(|v| v.certified));
    hole.complete(&value);
    let ready = s.begin(stage(4), 2, false).unwrap();
    assert!(ready.candidates.iter().any(|v| v.before == 1 && v.certified));
    ready.complete(&value);
    assert_eq!(s.report()["counts"]["identity_certified_mismatches"], 0);
    assert_eq!(Arc::strong_count(&value), 1);
}
#[test]
fn identity_intervals_do_not_certify_later_sources_or_other_contexts() {
    let s = session(); let value = column(&[1]);
    s.begin(stage(4), 0, false).unwrap().complete(&value);
    for before in 1..=5 { s.identity(stage(before), false); }
    let earlier = s.begin(stage(2), 1, false).unwrap();
    assert!(!earlier.candidates.iter().any(|v| v.certified)); earlier.complete(&value);
    let other = Key { request: crate::CacheKey { seed: key().request.seed+1, ..key().request }, ..stage(5) };
    let isolated = s.begin(other, 2, false).unwrap();
    assert!(!isolated.candidates.iter().any(|v| v.certified)); isolated.complete(&value);
}
#[test]
fn identity_intervals_report_bad_certificates_and_bound_metadata() {
    let s = session(); let value = column(&[1]);
    s.begin(stage(1), 0, false).unwrap().complete(&value);
    s.identity(stage(2), false);
    s.begin(stage(2), 1, false).unwrap().complete(&column(&[2]));
    assert_eq!(s.report()["counts"]["identity_certified_mismatches"], 1);
    let mut capped = Session::new(1, 0); capped.identity_intervals = true;
    capped.identity(stage(1), false); capped.identity(stage(2), false);
    assert_eq!(capped.report()["counts"]["identity_skipped_keys"], 1);
    assert_eq!(capped.shards[stage(1).column().shard()].lock().unwrap().identities.len(), 1);
}

#[test]
fn identity_intervals_exclude_limited_changed_and_unsampled_prefixes() {
    let s = session();
    let job = Job { session: Some(s.clone()), id: 0, key: key(), origin: [0, 0], width: 512 };
    assert!(s.selected(1, 0, 0));
    job.identity(1, 0, 0, true, true);
    job.identity(1, 0, 0, false, false);
    job.identity(0, 0, 0, false, true);
    let x = (1..256).find(|x| !s.selected(1, *x, 0)).unwrap();
    job.identity(1, x, 0, false, true);
    assert_eq!(s.report()["counts"]["identity_publications"], 0);
    job.identity(1, 0, 0, false, true);
    assert_eq!(s.report()["counts"]["identity_publications"], 1);
}
