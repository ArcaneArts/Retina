//! One debug summary per region-sized GPU job, including mixed pipeline paths.
use crate::{GpuRequest, timings};
use std::{cell::RefCell, collections::BTreeMap, sync::atomic::{AtomicU64, Ordering}, time::{Duration, Instant}};
static NEXT: AtomicU64 = AtomicU64::new(1);
pub(super) struct Trace(RefCell<Option<Run>>);
pub(super) struct Run {
    id: u64, profile: u32, x: i32, z: i32, side: u32, started: Instant,
    paths: BTreeMap<String, &'static str>,
}
impl Trace {
    pub fn new(profile: u32, request: GpuRequest) -> Self {
        Self(RefCell::new((crate::startup::debug_enabled() && request.tile_side >= 32).then(|| Run {
            id: NEXT.fetch_add(1, Ordering::Relaxed), profile, x: request.origin_x, z: request.origin_z,
            side: request.tile_side, started: Instant::now(), paths: BTreeMap::new(),
        })))
    }
    pub fn record(&self, entry: &str, path: &'static str) {
        if let Some(run) = self.0.borrow_mut().as_mut() { run.paths.insert(entry.into(), path); }
    }
    pub fn finish(self) -> Option<Run> { self.0.into_inner() }
}
impl Run {
    pub fn submitted(&self, backend: &str, prepare: Duration) {
        let paths = self.paths.iter().map(|(entry, path)| format!("{entry}:{path}")).collect::<Vec<_>>().join(",");
        crate::startup::message(format_args!("Region GPU selected job={} backend={backend} profile={} origin={},{} tile_side={} prepare_ms={:.3} paths=[{paths}]",
            self.id, self.profile, self.x, self.z, self.side, prepare.as_secs_f64()*1000.0));
    }
    pub fn completed(&self, backend: &str, timing: &timings::Snapshot) {
        crate::startup::message(format_args!("Region GPU completed job={} backend={backend} profile={} origin={},{} tile_side={} elapsed_ms={:.3} gpu_columns={} wait_readback_ms={:.3}",
            self.id, self.profile, self.x, self.z, self.side, self.started.elapsed().as_secs_f64()*1000.0,
            timing.gpu_columns, timing.nanos[timings::WAIT_COPY] as f64/1e6));
    }
}
#[cfg(test)]
mod tests {
    use super::*;
    #[test]
    fn trace_keeps_mixed_paths_and_request_identity() {
        let trace = Trace(RefCell::new(Some(Run { id: 1, profile: 7, x: -512, z: 1024, side: 36,
            started: Instant::now(), paths: BTreeMap::new() })));
        trace.record("height_nodes", "interpreter"); trace.record("material_counts", "specialized");
        trace.record("biome_sites", "fallback");
        let run = trace.finish().unwrap();
        assert_eq!((run.profile, run.x, run.z, run.side), (7, -512, 1024, 36));
        assert_eq!(run.paths.values().copied().collect::<Vec<_>>(), ["fallback", "interpreter", "specialized"]);
        let disabled = Trace(RefCell::new(None)); disabled.record("unused", "fallback");
        assert!(disabled.finish().is_none());
    }
}
