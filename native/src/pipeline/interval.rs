//! Opt-in bounded observations. Host sequence marks classify submissions; GPU
//! timestamps have a separate clock and never establish a host idle percentage.
use serde::Serialize;
use std::{
    collections::BTreeMap,
    sync::{
        Arc, Mutex,
        atomic::{AtomicBool, AtomicU64, Ordering},
    },
    time::Instant,
};

const CAPACITY: usize = 4096;

#[derive(Default)]
pub(crate) struct Recorder {
    enabled: AtomicBool,
    next_window: AtomicU64,
    capture: Mutex<Option<Arc<Capture>>>,
}
struct Capture {
    origin: Instant,
    state: Mutex<State>,
}
#[derive(Default)]
struct State {
    sequence: u64,
    events: BTreeMap<u64, Event>,
    dropped: u64,
    active: usize,
    window: Option<Window>,
}
struct Window {
    id: u64,
    sequence: u64,
    start_ns: u64,
    dropped: u64,
    active_at_start: usize,
    peak: usize,
}
#[derive(Clone, Serialize)]
struct Event {
    submission_id: u64,
    kind: &'static str,
    profile: u32,
    submitted_host_ns: u64,
    completed_sequence: Option<u64>,
    completed_host_ns: Option<u64>,
    aborted: bool,
    invalid_timestamp_pairs: u32,
    passes: Vec<Pass>,
}
#[derive(Clone, Serialize)]
struct Pass {
    stage: &'static str,
    start_tick: u64,
    end_tick: u64,
    timestamp_period_ns: f64,
}
pub(crate) struct Submission {
    capture: Arc<Capture>,
    id: u64,
    passes: Vec<Pass>,
    invalid: u32,
    finished: bool,
}
impl Recorder {
    pub(crate) fn configure(&self, enabled: bool) {
        let mut slot = self.capture.lock().unwrap_or_else(|e| e.into_inner());
        // Re-enabling does not invalidate a live window. Disable releases the
        // recorder's ownership; outstanding tickets finish in their old capture.
        if enabled && slot.is_none() {
            *slot = Some(Arc::new(Capture {
                origin: Instant::now(),
                state: Mutex::new(State::default()),
            }));
        } else if !enabled {
            *slot = None;
        }
        self.enabled.store(enabled, Ordering::Release);
    }
    fn capture(&self) -> Result<Arc<Capture>, String> {
        self.capture
            .lock()
            .unwrap_or_else(|e| e.into_inner())
            .clone()
            .ok_or_else(|| "GPU interval capture is disabled".into())
    }
    pub(crate) fn submit(&self, kind: &'static str, profile: u32) -> Option<Submission> {
        // The normal generation path takes no lock and allocates nothing.
        if !self.enabled.load(Ordering::Relaxed) {
            return None;
        }
        let capture = self.capture().ok()?;
        let mut state = capture.state.lock().unwrap_or_else(|e| e.into_inner());
        if state.events.len() == CAPACITY {
            // Never evict a live submission: its future completion must retain
            // its identity, including when it crosses a window boundary.
            if let Some(id) = state
                .events
                .iter()
                .find(|(_, e)| e.completed_sequence.is_some())
                .map(|(&id, _)| id)
            {
                state.events.remove(&id);
            } else {
                state.dropped += 1;
                return None;
            }
            state.dropped += 1;
        }
        state.sequence += 1;
        let id = state.sequence;
        state.events.insert(
            id,
            Event {
                submission_id: id,
                kind,
                profile,
                submitted_host_ns: capture.nanos(),
                completed_sequence: None,
                completed_host_ns: None,
                aborted: false,
                invalid_timestamp_pairs: 0,
                passes: Vec::new(),
            },
        );
        state.active += 1;
        let active = state.active;
        if let Some(window) = &mut state.window {
            window.peak = window.peak.max(active);
        }
        drop(state);
        Some(Submission {
            capture,
            id,
            passes: Vec::new(),
            invalid: 0,
            finished: false,
        })
    }
    pub(crate) fn begin(&self) -> Result<u64, String> {
        let capture = self.capture()?;
        let mut state = capture.state.lock().unwrap_or_else(|e| e.into_inner());
        if state.window.is_some() {
            return Err("GPU interval window is already open".into());
        }
        state.sequence += 1;
        let sequence = state.sequence;
        let id = self.next_window.fetch_add(1, Ordering::Relaxed) + 1;
        state.window = Some(Window {
            id,
            sequence,
            start_ns: capture.nanos(),
            dropped: state.dropped,
            active_at_start: state.active,
            peak: state.active,
        });
        Ok(id)
    }
    pub(crate) fn end(&self, id: u64) -> Result<serde_json::Value, String> {
        let capture = self.capture()?;
        let mut state = capture.state.lock().unwrap_or_else(|e| e.into_inner());
        if state.window.as_ref().is_none_or(|w| w.id != id) {
            return Err("unknown GPU interval window".into());
        }
        let window = state.window.take().unwrap();
        state.sequence += 1;
        let end_id = state.sequence;
        let end_ns = capture.nanos();
        let events: Vec<_> = state
            .events
            .values()
            .filter(|e| {
                e.completed_sequence
                    .is_none_or(|completed| completed > window.sequence)
            })
            .cloned()
            .collect();
        let dropped = state.dropped - window.dropped;
        let active_at_end = state.active;
        drop(state);
        let mut contained = 0;
        let mut incoming = 0;
        let mut outgoing = 0;
        let mut aborted = 0;
        let mut invalid = 0u64;
        let mut untimestamped = 0;
        let mut spans = Vec::new();
        let mut rows = Vec::new();
        for event in events {
            let submitted_inside = event.submission_id > window.sequence;
            let ended_inside = event.completed_sequence.is_some();
            let completed_inside = ended_inside && !event.aborted;
            if event.aborted {
                aborted += 1;
            }
            invalid += event.invalid_timestamp_pairs as u64;
            let fully_contained = submitted_inside && completed_inside && !event.aborted;
            if fully_contained {
                contained += 1;
                if event.passes.is_empty() {
                    untimestamped += 1;
                }
                for pass in &event.passes {
                    spans.push((
                        (pass.start_tick as f64 * pass.timestamp_period_ns) as u64,
                        (pass.end_tick as f64 * pass.timestamp_period_ns) as u64,
                    ));
                }
            }
            if !submitted_inside && completed_inside {
                incoming += 1;
            }
            if submitted_inside && !ended_inside {
                outgoing += 1;
            }
            rows.push(
                serde_json::json!({"submission":event,"submitted_inside":submitted_inside,
                "ended_inside":ended_inside,"completed_inside":completed_inside,"fully_contained":fully_contained}),
            );
        }
        let sum: u128 = spans.iter().map(|(s, e)| (e - s) as u128).sum();
        let union = union_ns(&mut spans);
        Ok(serde_json::json!({
            "schema_version":1,"capture_capacity":CAPACITY,"window_id":id,"start_sequence":window.sequence,"end_sequence":end_id,
            "start_host_ns":window.start_ns,"end_host_ns":end_ns,"host_wall_ns":end_ns-window.start_ns,
            "tracked_active_at_start":window.active_at_start,"tracked_active_at_end":active_at_end,
            "interval_peak_tracked_submissions":window.peak,"fully_contained_completed":contained,
            "incoming_completed":incoming,"outgoing_pending":outgoing,"aborted_observations":aborted,
            "invalid_timestamp_pairs":invalid,"dropped_records_during_window":dropped,
            "fully_contained_without_valid_timestamps":untimestamped,
            "fully_contained_pass_sum_ns":sum.min(u64::MAX as u128) as u64,
            "fully_contained_pass_sum_saturated":sum > u64::MAX as u128,
            "fully_contained_pass_union_ns":union as u64,
            "events":rows,
            "scope":"Opt-in terrain, material-emission and lighting submissions on the shared device. Enable before warmup to observe incoming work. Work submitted before capture is enabled, ore/feature/vine jobs, copies, driver compilation and untimestamped work are not covered. Host sequence marks classify whole submissions; device timestamps use a separate clock. Boundary-crossing and aborted submissions are excluded from pass aggregates. Pending work is not awaited. Union removes overlapping pass timestamps, not host time. Neither gaps, pass sums nor unions are an idle/utilization percentage. Zero passes can mean unavailable timestamps. Dropped records make coverage incomplete. Peaks count tracked submissions, not hardware occupancy."
        }))
    }
}
impl Capture {
    fn nanos(&self) -> u64 {
        self.origin.elapsed().as_nanos().min(u64::MAX as u128) as u64
    }
}
impl Submission {
    pub(crate) fn pass(&mut self, stage: &'static str, start: u64, end: u64, period: f64) {
        if start == 0 || end < start || !period.is_finite() || period <= 0.0 {
            self.invalid += 1;
        } else {
            self.passes.push(Pass {
                stage,
                start_tick: start,
                end_tick: end,
                timestamp_period_ns: period,
            });
        }
    }
    pub(crate) fn finish(&mut self) {
        self.complete(false);
    }
    fn complete(&mut self, aborted: bool) {
        if self.finished {
            return;
        }
        self.finished = true;
        let mut state = self.capture.state.lock().unwrap_or_else(|e| e.into_inner());
        state.sequence += 1;
        let sequence = state.sequence;
        if let Some(event) = state.events.get_mut(&self.id) {
            event.completed_sequence = Some(sequence);
            event.completed_host_ns = Some(self.capture.nanos());
            event.aborted = aborted;
            event.invalid_timestamp_pairs = self.invalid;
            event.passes = std::mem::take(&mut self.passes);
            state.active -= 1;
        }
    }
}
impl Drop for Submission {
    fn drop(&mut self) {
        self.complete(true);
    }
}
fn union_ns(spans: &mut [(u64, u64)]) -> u128 {
    spans.sort_unstable();
    let mut total = 0u128;
    let mut covered = 0;
    for &(start, end) in spans.iter() {
        total += end.saturating_sub(start.max(covered)) as u128;
        covered = covered.max(end);
    }
    total
}

#[cfg(test)]
mod tests {
    use super::*;
    #[test]
    fn warmup_boundaries_overlap_missing_timestamps_and_interval_peaks() {
        let recorder = Recorder::default();
        assert!(recorder.submit("terrain", 1).is_none());
        recorder.configure(true);
        let mut warm = recorder.submit("terrain", 1).unwrap();
        warm.pass("columns", 10, 90, 1.0);
        warm.finish();
        let mut incoming = recorder.submit("terrain", 1).unwrap();
        let id = recorder.begin().unwrap();
        let mut terrain = recorder.submit("terrain", 1).unwrap();
        let mut light = recorder.submit("lighting", 1).unwrap();
        terrain.pass("columns", 100, 200, 1.0);
        light.pass("light_sky", 150, 250, 1.0);
        terrain.pass("unavailable", 0, 10, 1.0);
        terrain.pass("reversed", 250, 200, 1.0);
        incoming.pass("columns", 1, 5000, 1.0);
        incoming.finish();
        terrain.finish();
        light.finish();
        let outgoing = recorder.submit("materials", 1).unwrap();
        let result = recorder.end(id).unwrap();
        assert_eq!(result["fully_contained_completed"], 2);
        assert_eq!(result["incoming_completed"], 1);
        assert_eq!(result["outgoing_pending"], 1);
        assert_eq!(result["tracked_active_at_start"], 1);
        assert_eq!(result["tracked_active_at_end"], 1);
        assert_eq!(result["interval_peak_tracked_submissions"], 3);
        assert_eq!(result["fully_contained_pass_sum_ns"], 200);
        assert_eq!(result["fully_contained_pass_union_ns"], 150);
        assert_eq!(result["invalid_timestamp_pairs"], 2);
        assert_eq!(result["events"].as_array().unwrap().len(), 4);
        drop(outgoing);
        let id = recorder.begin().unwrap();
        let empty = recorder.end(id).unwrap();
        assert_eq!(empty["interval_peak_tracked_submissions"], 0);
        assert_eq!(empty["fully_contained_pass_union_ns"], 0);
    }
    #[test]
    fn aborts_wrong_handles_and_disable_do_not_poison_generation() {
        let recorder = Recorder::default();
        assert!(recorder.begin().is_err());
        recorder.configure(true);
        let id = recorder.begin().unwrap();
        assert!(recorder.begin().is_err());
        let job = recorder.submit("materials", 2).unwrap();
        drop(job);
        assert!(recorder.end(id + 1).is_err());
        let result = recorder.end(id).unwrap();
        assert_eq!(result["aborted_observations"], 1);
        assert_eq!(result["fully_contained_completed"], 0);
        assert_eq!(result["outgoing_pending"], 0);
        assert_eq!(result["events"][0]["completed_inside"], false);
        assert_eq!(result["events"][0]["ended_inside"], true);
        let old = recorder.submit("lighting", 2).unwrap();
        recorder.configure(false);
        assert!(recorder.submit("terrain", 2).is_none());
        recorder.configure(true);
        drop(old);
        let id = recorder.begin().unwrap();
        let result = recorder.end(id).unwrap();
        assert_eq!(result["tracked_active_at_start"], 0);
    }
    #[test]
    fn capture_is_bounded_and_lost_coverage_is_explicit() {
        let recorder = Recorder::default();
        recorder.configure(true);
        let id = recorder.begin().unwrap();
        let pending: Vec<_> = (0..CAPACITY)
            .map(|_| recorder.submit("lighting", 1).unwrap())
            .collect();
        assert!(recorder.submit("terrain", 1).is_none());
        drop(pending);
        recorder.submit("materials", 1).unwrap().finish();
        let result = recorder.end(id).unwrap();
        assert_eq!(result["events"].as_array().unwrap().len(), CAPACITY);
        assert_eq!(result["dropped_records_during_window"], 2);
    }
    #[test]
    fn stale_window_identity_cannot_close_a_new_capture() {
        let recorder = Recorder::default();
        recorder.configure(true);
        let old = recorder.begin().unwrap();
        recorder.configure(false);
        recorder.configure(true);
        let current = recorder.begin().unwrap();
        assert_ne!(old, current);
        assert!(recorder.end(old).is_err());
        assert!(recorder.end(current).is_ok());
    }
    #[test]
    fn concurrent_submitters_keep_unique_identities_and_observe_the_same_window() {
        let recorder = Recorder::default();
        recorder.configure(true);
        let id = recorder.begin().unwrap();
        std::thread::scope(|scope| {
            for _ in 0..8 {
                scope.spawn(|| {
                    for _ in 0..128 {
                        recorder.submit("lighting", 1).unwrap().finish();
                    }
                });
            }
        });
        let result = recorder.end(id).unwrap();
        assert_eq!(result["fully_contained_completed"], 1024);
        assert_eq!(result["fully_contained_without_valid_timestamps"], 1024);
        assert_eq!(result["tracked_active_at_end"], 0);
        assert_eq!(result["dropped_records_during_window"], 0);
    }
    #[test]
    fn large_timestamp_sums_are_explicitly_saturated_instead_of_panicking() {
        let recorder = Recorder::default();
        recorder.configure(true);
        let id = recorder.begin().unwrap();
        let mut job = recorder.submit("terrain", 1).unwrap();
        job.pass("columns", 1, u64::MAX, 1.0);
        job.pass("height", 1, u64::MAX, 1.0);
        job.finish();
        let result = recorder.end(id).unwrap();
        assert_eq!(result["fully_contained_pass_sum_ns"], u64::MAX);
        assert_eq!(result["fully_contained_pass_sum_saturated"], true);
    }
}
