//! Explicit offline attribution. Disabled observation hooks take no capture
//! allocation, timestamp or capture lock; construction only checks the flag.
//! Spans may nest and overlap; host totals are not elapsed-time shares.
use super::super::counts::{BatchSamples, Query};
use crate::ChunkRequest;
use serde::Serialize;
use std::{
    collections::HashSet,
    fs::{self, OpenOptions},
    io::{BufWriter, Write},
    path::PathBuf,
    sync::{
        Mutex,
        atomic::{AtomicU64, Ordering},
    },
    time::{Instant, SystemTime, UNIX_EPOCH},
};

pub(super) const POLL: usize = 0;
pub(super) const TERRAIN: usize = 1;
pub(super) const COLUMN: usize = 2;
pub(super) const BUILD: usize = 3;
const SAMPLE: usize = 4;
const NAMES: [&str; 5] = [
    "replay_poll_inclusive",
    "terrain_field_inclusive",
    "column_cell_inclusive",
    "column_build_inclusive",
    "raw_sampling",
];
static NEXT: AtomicU64 = AtomicU64::new(1);
static NEXT_THREAD: AtomicU64 = AtomicU64::new(1);
thread_local! { static THREAD: u64 = NEXT_THREAD.fetch_add(1, Ordering::Relaxed); }

#[derive(Clone, Copy)]
struct Limits {
    events: usize,
    keys: usize,
}
#[derive(Default, Serialize)]
struct Total {
    calls: u64,
    aborted: u64,
    host_ns: u64,
    dropped_events: u64,
}
#[derive(Serialize)]
struct Event {
    thread: u64,
    root_or_prefix: Option<usize>,
    x: i32,
    z: i32,
    start_ns: u64,
    end_ns: u64,
    complete: bool,
    count_queries: usize,
    provider_queries: usize,
    backend: Option<&'static str>,
    gpu_lock_ns: Option<u64>,
    gpu_lock_ok: Option<bool>,
    sampling_host_ns: Option<u64>,
    device_ns: [Option<u64>; 2],
    upload_bytes: u64,
    readback_bytes: u64,
}
#[derive(Default)]
struct State {
    totals: [Total; 5],
    events: [Vec<Event>; 5],
    counts: HashSet<Query>,
    providers: HashSet<[i32; 4]>,
    count_queries: u64,
    provider_queries: u64,
    count_duplicates: u64,
    provider_duplicates: u64,
    count_untracked: u64,
    provider_untracked: u64,
    views: Vec<ViewUsage>,
}
const ROOT_SLOTS: usize = 512;
#[derive(Default, Serialize)]
pub(super) struct ViewUsage {
    pub views: u64,
    pub allocated_views: u64,
    pub requested_slot_bytes: u64,
    pub filled_slots: u64,
    pub fallback_entries: u64,
    pub fallback_unavailable: u64,
    pub occupancy: [u64; 8],
    pub max_filled: u64,
    pub max_fallback: u64,
    pub material_mask_bytes: u64,
}
fn root_slot(root: usize) -> usize {
    root.min(ROOT_SLOTS - 1)
}
fn occupancy_bin(filled: usize) -> usize {
    match filled {
        0 => 0,
        1 => 1,
        2..=4 => 2,
        5..=16 => 3,
        17..=64 => 4,
        65..=256 => 5,
        257..=1024 => 6,
        _ => 7,
    }
}
pub(super) struct Capture {
    output: Option<PathBuf>,
    metadata: serde_json::Value,
    limits: Limits,
    start: Instant,
    state: Mutex<State>,
    detailed: bool,
    identities: Box<[[AtomicU64; 4]]>,
}
pub(super) struct Span<'a> {
    capture: &'a Capture,
    kind: usize,
    event: Option<Event>,
    stage: Option<Instant>,
}
fn ns(duration: std::time::Duration) -> u64 {
    duration.as_nanos().min(u64::MAX as u128) as u64
}

fn observe<T: Eq + std::hash::Hash + Copy>(
    set: &mut HashSet<T>,
    keys: &[T],
    limit: usize,
) -> (u64, u64) {
    let (mut duplicates, mut untracked) = (0, 0);
    for key in keys {
        if set.contains(key) {
            duplicates += 1;
        } else if set.len() < limit {
            set.insert(*key);
        } else {
            untracked += 1;
        }
    }
    (duplicates, untracked)
}

impl Capture {
    pub(super) fn from_env<'a>(
        request: ChunkRequest,
        side: usize,
        profile: &crate::WorldProfile,
        roots: impl Iterator<Item = &'a super::Root>,
    ) -> Option<Box<Self>> {
        let directory = PathBuf::from(std::env::var_os("RETINA_DEPENDENCY_TRACE_DIR")?);
        let mut capture = Self::in_directory(directory, request, side)?;
        let mut inventory = Vec::new();
        let mut count: usize = 0;
        for (index, root) in roots.enumerate() {
            count += 1;
            if index < ROOT_SLOTS - 1 {
                inventory.push(serde_json::json!({
                    "index": index, "definition": root.definition, "city_shapes": root.city_shapes,
                    "uniform_blob_material": root.uniform_blob,
                    "recipe_count": root.ids.len(),
                    "recipes": root.ids.iter().take(16).map(|&id| profile.decorations[id as usize].source.chars().take(256).collect::<String>()).collect::<Vec<_>>(),
                }));
            }
        }
        capture.metadata["root_count"] = count.into();
        capture.metadata["roots"] = inventory.into();
        capture.metadata["root_inventory_truncated"] = count.saturating_sub(ROOT_SLOTS - 1).into();
        Some(capture)
    }
    fn in_directory(directory: PathBuf, request: ChunkRequest, side: usize) -> Option<Box<Self>> {
        if let Err(error) = fs::create_dir_all(&directory) {
            eprintln!("Retina dependency trace unavailable: {error}");
            return None;
        }
        let millis = SystemTime::now()
            .duration_since(UNIX_EPOCH)
            .map(|t| t.as_millis())
            .unwrap_or(0);
        let output = directory.join(format!(
            "dependency-{}-{millis}-{}.json",
            std::process::id(),
            NEXT.fetch_add(1, Ordering::Relaxed)
        ));
        Some(Box::new(Self {
            output: Some(output),
            metadata: serde_json::json!({
                "profile": request.reserved, "seed_bits": request.seed,
                "chunk_origin": [request.chunk_x, request.chunk_z], "side": side,
                "min_y": request.min_y, "height": request.height,
            }),
            limits: Limits {
                events: 768,
                keys: 65_536,
            },
            start: Instant::now(),
            state: Mutex::new(State::default()),
            detailed: std::env::var_os("RETINA_DEPENDENCY_TRACE_MODE")
                .is_some_and(|v| v == "detailed"),
            identities: (0..ROOT_SLOTS)
                .map(|_| std::array::from_fn(|_| AtomicU64::new(0)))
                .collect(),
        }))
    }
    pub(super) fn span(&self, kind: usize, root: Option<usize>, x: i32, z: i32) -> Span<'_> {
        if !self.detailed && kind != POLL && kind != SAMPLE {
            return Span {
                capture: self,
                kind,
                event: None,
                stage: None,
            };
        }
        Span {
            capture: self,
            kind,
            stage: Some(Instant::now()),
            event: Some(Event {
                thread: THREAD.with(|thread| *thread),
                root_or_prefix: root,
                x,
                z,
                start_ns: ns(self.start.elapsed()),
                end_ns: 0,
                complete: false,
                count_queries: 0,
                provider_queries: 0,
                backend: None,
                gpu_lock_ns: None,
                gpu_lock_ok: None,
                sampling_host_ns: None,
                device_ns: [None; 2],
                upload_bytes: 0,
                readback_bytes: 0,
            }),
        }
    }
    pub(super) fn sampling(
        &self,
        root: usize,
        x: i32,
        z: i32,
        counts: &[Query],
        providers: &[[i32; 4]],
    ) -> Span<'_> {
        if let Ok(mut state) = self.state.lock() {
            state.count_queries = state.count_queries.saturating_add(counts.len() as u64);
            state.provider_queries = state
                .provider_queries
                .saturating_add(providers.len() as u64);
            let (d, u) = observe(&mut state.counts, counts, self.limits.keys);
            state.count_duplicates = state.count_duplicates.saturating_add(d);
            state.count_untracked = state.count_untracked.saturating_add(u);
            let (d, u) = observe(&mut state.providers, providers, self.limits.keys);
            state.provider_duplicates = state.provider_duplicates.saturating_add(d);
            state.provider_untracked = state.provider_untracked.saturating_add(u);
        }
        // Key bookkeeping precedes this span and is not GPU lock/sample time.
        let mut span = self.span(SAMPLE, Some(root), x, z);
        span.event.as_mut().unwrap().count_queries = counts.len();
        span.event.as_mut().unwrap().provider_queries = providers.len();
        span
    }
    pub(super) fn identity(&self, root: usize, limited: bool, unchanged: bool) {
        self.identities[root_slot(root)][usize::from(limited) * 2 + usize::from(unchanged)]
            .fetch_add(1, Ordering::Relaxed);
    }
    pub(super) fn view(
        &self,
        root: usize,
        allocated: bool,
        slot_bytes: usize,
        filled: usize,
        fallback: Option<usize>,
        masks: usize,
    ) {
        if let Ok(mut state) = self.state.lock() {
            let index = root_slot(root);
            let len = state.views.len().max(index + 1);
            state.views.resize_with(len, ViewUsage::default);
            let view = &mut state.views[index];
            view.views += 1;
            view.allocated_views += u64::from(allocated);
            view.requested_slot_bytes += slot_bytes as u64;
            view.filled_slots += filled as u64;
            view.occupancy[occupancy_bin(filled)] += 1;
            view.max_filled = view.max_filled.max(filled as u64);
            view.fallback_entries += fallback.unwrap_or(0) as u64;
            view.max_fallback = view.max_fallback.max(fallback.unwrap_or(0) as u64);
            view.fallback_unavailable += u64::from(fallback.is_none());
            view.material_mask_bytes += masks as u64;
        }
    }
    fn snapshot(&self, state: &State) -> serde_json::Value {
        serde_json::json!({
            "schema": "retina.dependency-sampling-trace", "schema_version": 2,
            "metadata": self.metadata, "capture_closed": true,
            "dependency_or_generation_success": "not inferred from capture lifetime",
            "limits": {"events_per_kind": self.limits.events, "keys_per_kind": self.limits.keys},
            "kinds": NAMES, "totals": state.totals, "events": state.events,
            "mode": if self.detailed { "detailed" } else { "summary" },
            "observed_kinds": [true, self.detailed, self.detailed, self.detailed, true],
            "views_by_root": state.views,
            "view_occupancy_bins": ["0", "1", "2-4", "5-16", "17-64", "65-256", "257-1024", ">1024"],
            "prefix_outcomes_by_applied_root": self.identities.iter().map(|row| row.each_ref().map(|v| v.load(Ordering::Relaxed))).collect::<Vec<_>>(),
            "prefix_outcome_columns": ["unlimited_changed", "unlimited_identity", "limited_changed", "limited_identity"],
            "root_overflow_bucket": ROOT_SLOTS - 1,
            "queries": {"counts": state.count_queries, "providers": state.provider_queries,
                "recorded_unique_counts": state.counts.len(), "recorded_unique_providers": state.providers.len(),
                "count_duplicates": state.count_duplicates, "provider_duplicates": state.provider_duplicates,
                "untracked_count_keys": state.count_untracked, "untracked_provider_keys": state.provider_untracked},
            "scope": ["Inclusive/nested/concurrent host spans, not additive elapsed shares or GPU utilization",
                "Unobserved kinds have zero counters: zero is not evidence of no work; detailed adds per-column clocks and locks",
                "View occupancy observed at view drop; slot bytes are cumulative requested payload, not peak RSS or allocator cost",
                "Prefix outcomes count successful reconstructions only; identities use existing result tests without retaining extra Arc owners",
                "Root indices at or above overflow bucket are merged; arrays index the root metadata inventory",
                "Root inventory caps at 511 roots, 16 recipe names per root and 256 Unicode characters per name; recipe_count is untruncated",
                "Terrain/column spans include recursive work or waits; a column build may be nested in its cell span",
                "Sampling host time excludes GPU lock wait and ends before replay-cache extension; total raw span includes extension",
                "Device timestamps have their own clock and can be unavailable",
                "Coordinates: poll/sampling are anchor chunks, columns are blocks, terrain is chunks",
                "Thread numbers are instrumentation-local process identities, not OS thread IDs",
                "Raw keys belong to this planner/profile only; duplicate requests do not prove safe reuse of derived edits",
                "Not a complete trace of ore/vine/GPU work or all planning paths"],
        })
    }
}
impl Span<'_> {
    pub(super) fn complete(&mut self) {
        if let Some(event) = &mut self.event {
            event.complete = true;
        }
    }
    pub(super) fn gpu_lock_begin(&mut self) {
        self.event.as_mut().unwrap().backend = Some("gpu");
        self.stage = Some(Instant::now());
    }
    pub(super) fn gpu_lock_end(&mut self, ok: bool) {
        let event = self.event.as_mut().unwrap();
        event.gpu_lock_ns = self.stage.map(|stage| ns(stage.elapsed()));
        event.gpu_lock_ok = Some(ok);
        self.stage = Some(Instant::now());
    }
    pub(super) fn cpu_begin(&mut self) {
        self.event.as_mut().unwrap().backend = Some("cpu");
        self.stage = Some(Instant::now());
    }
    pub(super) fn sampled(&mut self, result: &BatchSamples) {
        let event = self.event.as_mut().unwrap();
        event.sampling_host_ns = self.stage.map(|stage| ns(stage.elapsed()));
        event.device_ns = result.device_nanos;
        event.upload_bytes = result.upload;
        event.readback_bytes = result.readback;
        event.complete = true;
    }
}
impl Drop for Span<'_> {
    fn drop(&mut self) {
        let Some(mut event) = self.event.take() else {
            return;
        };
        event.end_ns = ns(self.capture.start.elapsed());
        if let Ok(mut state) = self.capture.state.lock() {
            let total = &mut state.totals[self.kind];
            total.calls = total.calls.saturating_add(1);
            total.aborted = total.aborted.saturating_add(u64::from(!event.complete));
            total.host_ns = total
                .host_ns
                .saturating_add(event.end_ns.saturating_sub(event.start_ns));
            if state.events[self.kind].len() < self.capture.limits.events {
                state.events[self.kind].push(event);
            } else {
                state.totals[self.kind].dropped_events =
                    state.totals[self.kind].dropped_events.saturating_add(1);
            }
        }
    }
}
impl Drop for Capture {
    fn drop(&mut self) {
        let Some(output) = &self.output else {
            return;
        };
        let body = match self.state.lock() {
            Ok(state) => self.snapshot(&state),
            Err(_) => {
                eprintln!("Retina dependency trace unavailable: capture lock poisoned");
                return;
            }
        };
        let result = (|| -> Result<(), Box<dyn std::error::Error>> {
            let mut file = BufWriter::new(
                OpenOptions::new()
                    .write(true)
                    .create_new(true)
                    .open(output)?,
            );
            serde_json::to_writer(&mut file, &body)?;
            file.flush()?;
            Ok(())
        })();
        if let Err(error) = result {
            eprintln!("Retina dependency trace could not be written: {error}");
        }
    }
}

#[cfg(test)]
mod tests {
    use super::super::super::counts::Rule;
    use super::*;
    fn capture() -> Capture {
        Capture {
            output: None,
            metadata: serde_json::Value::Null,
            limits: Limits { events: 2, keys: 2 },
            start: Instant::now(),
            state: Mutex::new(State::default()),
            detailed: true,
            identities: (0..ROOT_SLOTS)
                .map(|_| std::array::from_fn(|_| AtomicU64::new(0)))
                .collect(),
        }
    }
    #[test]
    fn complete_keys_bounds_and_aborts_are_observed() {
        let trace = capture();
        let q = Query {
            x: 7,
            z: 9,
            rule: Rule::based(1, 2., 0.),
        };
        let q2 = Query {
            rule: Rule::based(2, 2., 0.),
            ..q
        };
        for index in 0..4 {
            let mut call = trace.sampling(index, 0, 0, &[q, q2], &[[1, index as i32, 3, 4]]);
            if index != 3 {
                call.complete();
            }
        }
        let s = trace.state.lock().unwrap();
        assert_eq!(s.counts.len(), 2);
        assert_eq!(s.count_duplicates, 6);
        assert_eq!(s.provider_untracked, 2);
        assert_eq!(s.providers.len(), 2);
        assert_eq!(s.totals[SAMPLE].calls, 4);
        assert_eq!(s.totals[SAMPLE].aborted, 1);
        assert_eq!(s.totals[SAMPLE].dropped_events, 2);
        assert_eq!(s.events[SAMPLE].len(), 2);
    }
    #[test]
    fn provider_y_and_program_identity_and_missing_timestamps_remain_explicit() {
        let mut trace = capture();
        trace.limits.keys = 8;
        {
            let mut call = trace.sampling(
                0,
                1,
                2,
                &[],
                &[[1, 2, 3, 4], [1, 3, 3, 4], [1, 2, 3, 5], [1, 2, 3, 4]],
            );
            call.cpu_begin();
            call.sampled(&BatchSamples {
                counts: vec![],
                providers: vec![],
                upload: 0,
                readback: 0,
                device_nanos: [None; 2],
            });
        }
        let s = trace.state.lock().unwrap();
        assert_eq!(s.providers.len(), 3);
        assert_eq!(s.provider_duplicates, 1);
        let event = &s.events[SAMPLE][0];
        assert!(event.complete);
        assert_eq!(event.gpu_lock_ns, None);
        assert_eq!(event.device_ns, [None; 2]);
        assert!(event.sampling_host_ns.is_some());
    }
    #[test]
    fn event_budgets_are_separate_for_each_kind() {
        let trace = capture();
        for _ in 0..5 {
            trace.span(COLUMN, None, 0, 0).complete();
        }
        trace.span(POLL, Some(4), 2, 3).complete();
        let s = trace.state.lock().unwrap();
        assert_eq!(s.events[POLL].len(), 1);
        assert_eq!(s.totals[COLUMN].dropped_events, 3);
        assert_eq!(s.totals[POLL].aborted, 0);
    }
    #[test]
    fn summary_exposes_unobserved_kinds_and_bounds_root_aggregation() {
        let mut trace = capture();
        trace.detailed = false;
        trace.span(COLUMN, Some(7), 1, 2).complete();
        trace.span(POLL, Some(7), 1, 2).complete();
        trace.identity(3, false, true);
        trace.identity(3, true, false);
        trace.identity(usize::MAX, true, true);
        for filled in [0, 1, 2, 4, 5, 16, 17, 64, 65, 256, 257, 1024, 1025] {
            trace.view(3, filled != 0, 1024, filled, Some(2), 8);
        }
        trace.view(usize::MAX, false, 0, 0, None, 0);
        let state = trace.state.lock().unwrap();
        let json = trace.snapshot(&state);
        assert_eq!(
            json["observed_kinds"],
            serde_json::json!([true, false, false, false, true])
        );
        assert_eq!(state.totals[COLUMN].calls, 0);
        assert_eq!(state.totals[POLL].calls, 1);
        assert_eq!(state.views.len(), ROOT_SLOTS);
        assert_eq!(state.views[3].occupancy, [1, 1, 2, 2, 2, 2, 2, 1]);
        assert_eq!(state.views[ROOT_SLOTS - 1].fallback_unavailable, 1);
        assert_eq!(
            json["prefix_outcomes_by_applied_root"][3],
            serde_json::json!([0, 1, 1, 0])
        );
        assert_eq!(
            json["prefix_outcomes_by_applied_root"][ROOT_SLOTS - 1],
            serde_json::json!([0, 0, 0, 1])
        );
    }
    #[test]
    fn failed_writer_never_truncates_an_existing_file_or_panics() {
        let path = std::env::temp_dir().join(format!(
            "retina-trace-owned-{}-{}",
            std::process::id(),
            NEXT.fetch_add(1, Ordering::Relaxed)
        ));
        fs::write(&path, b"owned evidence").unwrap();
        let mut trace = capture();
        trace.output = Some(path.clone());
        drop(trace);
        assert_eq!(fs::read(&path).unwrap(), b"owned evidence");
        fs::remove_file(path).unwrap();
    }
    #[test]
    fn invalid_output_directory_disables_only_the_optional_capture() {
        let path = std::env::temp_dir().join(format!(
            "retina-trace-file-{}-{}",
            std::process::id(),
            NEXT.fetch_add(1, Ordering::Relaxed)
        ));
        fs::write(&path, b"existing file").unwrap();
        let request = ChunkRequest {
            seed: 1,
            chunk_x: 0,
            chunk_z: 0,
            min_y: 0,
            height: 16,
            base_height: 8.,
            amplitude: 0.,
            frequency: 0.01,
            reserved: 0,
        };
        assert!(Capture::in_directory(path.clone(), request, 1).is_none());
        assert_eq!(fs::read(&path).unwrap(), b"existing file");
        fs::remove_file(path).unwrap();
    }
}
