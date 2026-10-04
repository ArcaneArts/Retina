//! Per-profile counters. Worker time is additive across Rayon threads; GPU device
//! timestamps overlap queue/host phases. Region reports separately scale worker costs to wall shares.
use std::sync::atomic::{AtomicU64, Ordering};
use std::time::Instant;

pub const STAGES: usize = 24;
pub const QUEUE: usize = 0;
pub const ENCODE: usize = 1;
pub const WAIT_COPY: usize = 2;
pub const HEIGHT: usize = 3;
pub const SITES: usize = 4;
pub const COLUMNS: usize = 5;
pub const CAVE_DENSITY: usize = 6;
pub const CAVE_MASK: usize = 7;
pub const STRUCTURE_PLAN: usize = 8;
pub const VEGETATION_PLAN: usize = 9;
pub const ORE_PLAN: usize = 10;
pub const ASSEMBLY: usize = 11;
pub const GEOLOGY: usize = 12;
pub const CAVE_FEATURES: usize = 13;
pub const VEGETATION: usize = 14;
pub const STRUCTURES: usize = 15;
pub const SNOW: usize = 16;
pub const NBT: usize = 17;
pub const COMPRESS: usize = 18;
pub const IO: usize = 19;
pub const MATERIALS: usize = 20;
pub const AQUIFER_FIELDS: usize = 21;
pub const AQUIFER_MASK: usize = 22;
pub const FEATURE_COUNTS: usize = 23;

#[repr(C)]
#[derive(Default, Clone, Copy, Debug)]
pub struct Snapshot {
    pub version: u32,
    pub flags: u32,
    pub chunks: u64,
    pub gpu_columns: u64,
    pub gpu_jobs: u64,
    pub nanos: [u64; STAGES],
}
pub struct Timings {
    chunks: AtomicU64,
    gpu_columns: AtomicU64,
    gpu_jobs: AtomicU64,
    flags: AtomicU64,
    nanos: [AtomicU64; STAGES],
}
impl Default for Timings {
    fn default() -> Self {
        Self {
            chunks: AtomicU64::new(0),
            gpu_columns: AtomicU64::new(0),
            gpu_jobs: AtomicU64::new(0),
            flags: AtomicU64::new(0),
            nanos: std::array::from_fn(|_| AtomicU64::new(0)),
        }
    }
}
impl Timings {
    pub fn device(&self, stage: usize, nanos: u64) {
        self.add(stage, nanos);
        self.flags.fetch_or(1, Ordering::Relaxed);
    }
    pub fn add(&self, stage: usize, nanos: u64) {
        self.nanos[stage].fetch_add(nanos, Ordering::Relaxed);
    }
    pub fn time<T>(&self, stage: usize, f: impl FnOnce() -> T) -> T {
        let _guard = self.span(stage);
        f()
    }
    pub fn span(&self, stage: usize) -> Guard<'_> {
        Guard {
            timings: self,
            stage,
            start: Instant::now(),
        }
    }
    pub fn chunks(&self, count: u64) {
        self.chunks.fetch_add(count, Ordering::Relaxed);
    }
    pub fn gpu(&self, columns: u64, measured: bool) {
        self.gpu_jobs.fetch_add(1, Ordering::Relaxed);
        self.gpu_columns.fetch_add(columns, Ordering::Relaxed);
        if measured {
            self.flags.fetch_or(1, Ordering::Relaxed);
        }
    }
    pub fn snapshot(&self) -> Snapshot {
        Snapshot {
            version: 4,
            flags: self.flags.load(Ordering::Relaxed) as u32,
            chunks: self.chunks.load(Ordering::Relaxed),
            gpu_columns: self.gpu_columns.load(Ordering::Relaxed),
            gpu_jobs: self.gpu_jobs.load(Ordering::Relaxed),
            nanos: std::array::from_fn(|i| self.nanos[i].load(Ordering::Relaxed)),
        }
    }
}
pub struct Guard<'a> {
    timings: &'a Timings,
    stage: usize,
    start: Instant,
}
impl Drop for Guard<'_> {
    fn drop(&mut self) {
        self.timings
            .add(self.stage, self.start.elapsed().as_nanos() as u64);
    }
}
