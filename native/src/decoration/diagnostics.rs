//! Opt-in planner diagnostics. Wall phases are disjoint; worker counters overlap
//! their replay wall phase and tree/leaf counters are nested subsets of workers.
use std::time::Instant;

#[derive(Default, Debug, serde::Serialize)]
pub struct Planning {
    /// Source-root dependencies include nested terrain and provider sampling.
    /// Legacy replay phases below stay zero on this execution path.
    pub dependency_wall_nanos: u64,
    pub dependency_anchors: u64,
    pub dependency_column_tiles: u64,
    pub dependency_terrain_tiles: u64,
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
    pub combined_sample_batches: u64,
    pub peak_retained_bytes: u64,
    pub worker: Worker,
    pub(crate) gpu: super::vine_gpu::Stats,
}
#[derive(Default, Debug, serde::Serialize)]
pub struct Worker {
    pub expansion_nanos: u64,
    pub evaluation_nanos: u64,
    /// Nested subsets of evaluation_nanos, enabled only for detailed planning.
    pub candidate_queue_nanos: u64,
    pub placement_checks_nanos: u64,
    pub feature_nanos: u64,
    pub overlay_commit_nanos: u64,
    pub tree_nanos: u64,
    pub leaf_nanos: u64,
    pub queued_entries: u64,
    pub tree_attempts: u64,
    pub evaluated_candidates: u64,
    /// Per registered recipe: evaluated candidates, then accepted positions.
    /// Populated only by the opt-in detailed planner, never normal generation.
    pub recipe_candidates: Vec<[u64; 2]>,
    pub resumed_anchors: u64,
    pub resumed_candidates: u64,
    pub discarded_checkpoints: u64,
    /// Inputs are resolved at the start of evaluation, not a promise that a
    /// later missing provider will retain every preceding result.
    pub resolved_candidates: u64,
    pub speculative_candidates: u64,
    pub unsupported_cpu_candidates: u64,
    pub gpu_supported_cpu_candidates: u64,
    pub unresolved_gpu_candidates: u64,
    pub retained_limit_gpu_groups: u64,
    pub compacted_gpu_groups: u64,
    pub compacted_queue_bytes: u64,
    #[serde(skip)]
    pub(super) eligible: Option<super::vine_gpu::Programs>,
}
impl Worker {
    pub(super) fn add(&mut self, b: &Self) {
        self.expansion_nanos += b.expansion_nanos;
        self.evaluation_nanos += b.evaluation_nanos;
        self.candidate_queue_nanos += b.candidate_queue_nanos;
        self.placement_checks_nanos += b.placement_checks_nanos;
        self.feature_nanos += b.feature_nanos;
        self.overlay_commit_nanos += b.overlay_commit_nanos;
        self.tree_nanos += b.tree_nanos;
        self.leaf_nanos += b.leaf_nanos;
        self.queued_entries += b.queued_entries;
        self.tree_attempts += b.tree_attempts;
        self.evaluated_candidates += b.evaluated_candidates;
        if self.recipe_candidates.len() < b.recipe_candidates.len() {
            self.recipe_candidates
                .resize(b.recipe_candidates.len(), [0; 2]);
        }
        for (a, b) in self.recipe_candidates.iter_mut().zip(&b.recipe_candidates) {
            a[0] += b[0];
            a[1] += b[1];
        }
        self.resumed_anchors += b.resumed_anchors;
        self.resumed_candidates += b.resumed_candidates;
        self.discarded_checkpoints += b.discarded_checkpoints;
        self.resolved_candidates += b.resolved_candidates;
        self.speculative_candidates += b.speculative_candidates;
        self.unsupported_cpu_candidates += b.unsupported_cpu_candidates;
        self.gpu_supported_cpu_candidates += b.gpu_supported_cpu_candidates;
        self.unresolved_gpu_candidates += b.unresolved_gpu_candidates;
        self.retained_limit_gpu_groups += b.retained_limit_gpu_groups;
        self.compacted_gpu_groups += b.compacted_gpu_groups;
        self.compacted_queue_bytes += b.compacted_queue_bytes;
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

#[cfg(test)]
mod tests {
    use super::Worker;
    #[test]
    fn recipe_counters_merge_unequal_worker_coverage() {
        let mut a = Worker {
            recipe_candidates: vec![[3, 2]],
            evaluated_candidates: 3,
            ..Default::default()
        };
        let b = Worker {
            recipe_candidates: vec![[4, 1], [0, 0], [8, 3]],
            evaluated_candidates: 12,
            ..Default::default()
        };
        a.add(&b);
        a.add(&Worker::default());
        assert_eq!(a.recipe_candidates, [[7, 3], [0, 0], [8, 3]]);
        assert_eq!(
            a.evaluated_candidates,
            a.recipe_candidates.iter().map(|v| v[0]).sum::<u64>()
        );
    }
}
