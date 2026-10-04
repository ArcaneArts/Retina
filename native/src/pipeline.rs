//! Device timeline diagnostics. Gaps include readback copies and unmeasured GPU
//! commands as well as idle time; they must not be presented as pure GPU idle.
use std::collections::HashMap;
use std::sync::{
    Arc, Mutex,
    atomic::{AtomicU64, Ordering},
};

/// Separate versioned diagnostics keep the original timeline ABI unchanged.
#[repr(C)]
#[derive(Clone, Copy, Default, Debug, serde::Serialize)]
pub struct ProgramSnapshot {
    pub version: u32,
    pub status: u32,
    pub compile_nanos: u64,
    pub source_bytes: u64,
    pub nodes: u32,
    pub emitted: u32,
    pub graphs: u32,
    pub horizontal_fields: u32,
    pub cache_hits: u64,
    pub upload_bytes: u64,
    pub readback_bytes: u64,
}
#[derive(Default)]
struct ProfileCounters {
    shader: Mutex<Option<Arc<crate::specialize::Progress>>>,
    upload: AtomicU64,
    readback: AtomicU64,
}

#[repr(C)]
#[derive(Clone, Copy, Default, Debug, serde::Serialize)]
pub struct Snapshot {
    pub version: u32,
    pub peak_in_flight: u32,
    pub completed: u64,
    pub device_span_nanos: u64,
    pub device_gap_nanos: u64,
    pub unavailable_timestamp_pairs: u64,
}
#[derive(Default)]
pub(crate) struct Metrics {
    peak: AtomicU64,
    completed: AtomicU64,
    spans: AtomicU64,
    gaps: AtomicU64,
    unavailable: AtomicU64,
    profiles: Mutex<HashMap<u32, Arc<ProfileCounters>>>,
}
impl Metrics {
    fn profile(&self, id: u32) -> Arc<ProfileCounters> {
        self.profiles
            .lock()
            .unwrap_or_else(|e| e.into_inner())
            .entry(id)
            .or_default()
            .clone()
    }
    pub fn shader(&self, id: u32, state: Arc<crate::specialize::Progress>) {
        *self
            .profile(id)
            .shader
            .lock()
            .unwrap_or_else(|e| e.into_inner()) = Some(state);
    }
    pub fn transfer(&self, id: u32, upload: u64, readback: u64) {
        let p = self.profile(id);
        p.upload.fetch_add(upload, Ordering::Relaxed);
        p.readback.fetch_add(readback, Ordering::Relaxed);
    }
    pub fn program_snapshot(&self, id: u32) -> ProgramSnapshot {
        let p = self.profile(id);
        let mut snapshot = p
            .shader
            .lock()
            .unwrap_or_else(|e| e.into_inner())
            .as_ref()
            .map_or(
                ProgramSnapshot {
                    version: 1,
                    ..Default::default()
                },
                |s| s.snapshot(),
            );
        snapshot.upload_bytes = p.upload.load(Ordering::Relaxed);
        snapshot.readback_bytes = p.readback.load(Ordering::Relaxed);
        snapshot
    }
    pub fn in_flight(&self, count: usize) {
        self.peak.fetch_max(count as u64, Ordering::Relaxed);
    }
    pub fn completed(&self) {
        self.completed.fetch_add(1, Ordering::Relaxed);
    }
    pub fn unavailable(&self) {
        self.unavailable.fetch_add(1, Ordering::Relaxed);
    }
    pub fn device(&self, span: u64, gap: u64) {
        self.spans.fetch_add(span, Ordering::Relaxed);
        self.gaps.fetch_add(gap, Ordering::Relaxed);
    }
    pub fn snapshot(&self) -> Snapshot {
        Snapshot {
            version: 1,
            peak_in_flight: self.peak.load(Ordering::Relaxed) as u32,
            completed: self.completed.load(Ordering::Relaxed),
            device_span_nanos: self.spans.load(Ordering::Relaxed),
            device_gap_nanos: self.gaps.load(Ordering::Relaxed),
            unavailable_timestamp_pairs: self.unavailable.load(Ordering::Relaxed),
        }
    }
}
