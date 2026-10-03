//! Device timeline diagnostics. Gaps include readback copies and unmeasured GPU
//! commands as well as idle time; they must not be presented as pure GPU idle.
use std::sync::atomic::{AtomicU64, Ordering};

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
}
impl Metrics {
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
