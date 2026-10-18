//! Opt-in planner diagnostics. Wall phases are disjoint; worker counters overlap
//! their replay wall phase and tree/leaf counters are nested subsets of workers.
use std::time::Instant;

#[derive(Default, Debug, serde::Serialize)]
pub struct Planning {
    pub anchors_wall_nanos: u64,
    pub count_expansion_wall_nanos: u64,
    pub sampling_wall_nanos: u64,
    pub replay_wall_nanos: u64,
    pub distribution_wall_nanos: u64,
    pub rounds: u64,
    pub anchor_attempts: u64,
    pub repeated_anchor_attempts: u64,
    pub noise_queries: u64,
    pub count_queries: u64,
    pub worker: Worker,
}
#[derive(Default, Debug, serde::Serialize)]
pub struct Worker {
    pub expansion_nanos: u64,
    pub evaluation_nanos: u64,
    pub tree_nanos: u64,
    pub leaf_nanos: u64,
    pub candidates: u64,
    pub tree_attempts: u64,
}
impl Worker {
    pub(super) fn add(&mut self, b: &Self) {
        self.expansion_nanos += b.expansion_nanos;
        self.evaluation_nanos += b.evaluation_nanos;
        self.tree_nanos += b.tree_nanos;
        self.leaf_nanos += b.leaf_nanos;
        self.candidates += b.candidates;
        self.tree_attempts += b.tree_attempts;
    }
}
pub(crate) fn start(enabled: bool) -> Option<Instant> {
    enabled.then(Instant::now)
}
pub(crate) fn record(start: Option<Instant>, into: Option<&mut u64>) {
    if let (Some(start), Some(into)) = (start, into) {
        *into += start.elapsed().as_nanos() as u64;
    }
}
pub(super) struct Clock<'a> {
    into: &'a mut u64,
    start: Instant,
}
pub(super) fn span(into: Option<&mut u64>) -> Option<Clock<'_>> {
    into.map(|into| Clock {
        into,
        start: Instant::now(),
    })
}
impl Drop for Clock<'_> {
    fn drop(&mut self) {
        *self.into += self.start.elapsed().as_nanos() as u64;
    }
}
