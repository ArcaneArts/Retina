//! Bounded stripes for optional diagnostic counters. A physical thread uses the
//! same stripe across profiles; collisions remain correct through atomic adds.
//! No generated state or metrics owner is retained in thread-local storage.
use super::Counter;
use std::mem::size_of;
use std::sync::atomic::{AtomicU64, AtomicUsize, Ordering};

pub(super) const SHARDS: usize = 64;
const COUNTERS: usize = Counter::Count as usize;

// Apple Silicon cache lines are 128 bytes; this also separates stripes on
// targets with smaller lines. Keep adjacent writers out of the same line.
#[repr(align(128))]
struct Shard([AtomicU64; COUNTERS]);
impl Default for Shard {
    fn default() -> Self { Self(std::array::from_fn(|_| AtomicU64::new(0))) }
}

thread_local! {
    static STRIPE: usize = {
        static NEXT: AtomicUsize = AtomicUsize::new(0);
        NEXT.fetch_add(1, Ordering::Relaxed)
    };
}

pub(super) struct Counters {
    shards: Box<[Shard; SHARDS]>,
}
impl Default for Counters {
    fn default() -> Self {
        // Construct one small stripe at a time, avoiding a large stack array.
        let values: Box<[_]> = (0..SHARDS).map(|_| Shard::default()).collect();
        Self { shards: values.try_into().unwrap_or_else(|_| unreachable!()) }
    }
}
impl Counters {
    #[inline]
    pub(super) fn add(&self, counter: Counter, value: u64) {
        STRIPE.with(|stripe| self.add_at(*stripe, counter, value));
    }
    #[inline]
    fn add_at(&self, stripe: usize, counter: Counter, value: u64) {
        self.shards[stripe & (SHARDS - 1)].0[counter as usize]
            .fetch_add(value, Ordering::Relaxed);
    }
    pub(super) fn load(&self, counter: usize) -> u64 {
        self.shards.iter().fold(0u64, |sum, stripe| {
            sum.wrapping_add(stripe.0[counter].load(Ordering::Relaxed))
        })
    }
    pub(super) const fn storage_bytes() -> usize { size_of::<Shard>() * SHARDS }
}

#[cfg(test)]
mod tests {
    use super::*;
    use std::mem::{align_of, size_of_val};
    use std::sync::{Arc, Barrier};

    trait AddCounter: Sync {
        fn add_counter(&self, counter: Counter, value: u64);
        fn read_counter(&self, counter: Counter) -> u64;
        fn snapshot_values(&self) -> Vec<u64>;
    }
    impl AddCounter for Counters {
        fn add_counter(&self, counter: Counter, value: u64) { self.add(counter, value); }
        fn read_counter(&self, counter: Counter) -> u64 { self.load(counter as usize) }
        fn snapshot_values(&self) -> Vec<u64> { (0..COUNTERS).map(|i| self.load(i)).collect() }
    }
    // Retain the original single-array operation for a same-process benchmark.
    struct Shared([AtomicU64; COUNTERS]);
    impl Default for Shared {
        fn default() -> Self { Self(std::array::from_fn(|_| AtomicU64::new(0))) }
    }
    impl AddCounter for Shared {
        fn add_counter(&self, counter: Counter, value: u64) {
            self.0[counter as usize].fetch_add(value, Ordering::Relaxed);
        }
        fn read_counter(&self, counter: Counter) -> u64 {
            self.0[counter as usize].load(Ordering::Relaxed)
        }
        fn snapshot_values(&self) -> Vec<u64> {
            self.0.iter().map(|v| v.load(Ordering::Relaxed)).collect()
        }
    }

    fn exercise<T: AddCounter>(counts: &T, threads: usize, updates: usize) -> (u128, u128) {
        let ready = Barrier::new(threads + 1);
        let start = Barrier::new(threads + 1);
        let done = Barrier::new(threads + 1);
        let elapsed = std::thread::scope(|scope| {
            for _ in 0..threads {
                let (ready, start, done) = (&ready, &start, &done);
                scope.spawn(move || {
                    ready.wait();
                    start.wait();
                    for i in 0..updates {
                        let counter = if i % 2 == 0 { Counter::column_requests }
                            else { Counter::anchor_ready_hits };
                        counts.add_counter(counter, 1);
                    }
                    done.wait();
                });
            }
            ready.wait();
            let began = std::time::Instant::now();
            start.wait();
            done.wait();
            began.elapsed().as_nanos()
        });
        // Read only after writers join. Snapshot/constructor work is outside
        // the measured interval, and both variants must keep identical totals.
        assert_eq!(counts.read_counter(Counter::column_requests), (threads * updates.div_ceil(2)) as u64);
        assert_eq!(counts.read_counter(Counter::anchor_ready_hits), (threads * (updates / 2)) as u64);
        let began = std::time::Instant::now();
        for _ in 0..100 { std::hint::black_box(counts.snapshot_values()); }
        let snapshots = began.elapsed().as_nanos();
        assert_eq!(counts.snapshot_values().iter().copied().sum::<u64>(), (threads * updates) as u64);
        (elapsed, snapshots)
    }

    #[test]
    #[ignore = "Counter microbenchmark requires RETINA_COUNTER_BENCH_OUTPUT"]
    fn counter_contention_inventory() {
        use serde_json::json;
        let output = std::path::PathBuf::from(std::env::var("RETINA_COUNTER_BENCH_OUTPUT").unwrap());
        let updates = 100_000;
        let mut cases = Vec::new();
        for threads in [1, 8, 16] {
            let mut rows = Vec::new();
            for iteration in 0..25 {
                let mut shared_ns = 0;
                let mut sharded_ns = 0;
                let mut shared_snapshot_ns = 0;
                let mut sharded_snapshot_ns = 0;
                for sharded in if iteration % 2 == 0 { [false, true] } else { [true, false] } {
                    if sharded {
                        (sharded_ns, sharded_snapshot_ns) = exercise(&Counters::default(), threads, updates);
                    } else {
                        (shared_ns, shared_snapshot_ns) = exercise(&Shared::default(), threads, updates);
                    }
                }
                rows.push(json!({"warmup":iteration<5,"iteration":iteration,
                    "first":if iteration%2==0 {"shared"} else {"sharded"},
                    "shared_ns":shared_ns,"sharded_ns":sharded_ns,
                    "snapshot_repeats":100,"shared_snapshot_ns":shared_snapshot_ns,
                    "sharded_snapshot_ns":sharded_snapshot_ns}));
            }
            cases.push(json!({"threads":threads,"updates_per_thread":updates,"rows":rows}));
            std::fs::write(&output, serde_json::to_vec_pretty(&json!({
                "scope":"Same-process counter-storage microbenchmark. Five warmups and twenty measured pairs per thread count, alternating first variant. Ready barriers precede update timing; start/done barriers are included. Construction, thread creation/join and final exact-count checks are outside update timing. Independent thread-local initialization can still affect early updates. Separately, snapshot_ns times100joined counter-array reads and Vec allocation/drop; it excludes JSON and candidate/terrain tracking. This measures diagnostic counter storage only, not complete generation or default throughput.",
                "shards":SHARDS,"counter_backing_bytes":Counters::storage_bytes(),"cases":cases,
            })).unwrap()).unwrap();
        }
    }

    #[test]
    fn updates_remain_exact_with_forced_stripe_collisions_and_separate_profiles() {
        let first = Arc::new(Counters::default());
        let second = Arc::new(Counters::default());
        std::thread::scope(|scope| {
            for thread in 0..16 {
                let (first, second) = (&first, &second);
                scope.spawn(move || {
                    for _ in 0..2_000 {
                        // Force all writers to the same stripe, including ids
                        // larger than the fixed table. Sharing must lose no adds.
                        first.add_at(thread * SHARDS, Counter::column_requests, 1);
                        second.add(Counter::column_requests, 3);
                        first.add(Counter::anchor_builds, 2);
                    }
                });
            }
        });
        assert_eq!(first.load(Counter::column_requests as usize), 32_000);
        assert_eq!(second.load(Counter::column_requests as usize), 96_000);
        assert_eq!(first.load(Counter::anchor_builds as usize), 64_000);
        assert_eq!(second.load(Counter::anchor_builds as usize), 0);
        assert_eq!(first.load(Counter::terrain_requests as usize), 0);
    }

    #[test]
    fn live_snapshot_bounds_and_joined_totals_preserve_all_updates() {
        let counts = Counters::default();
        let halfway = Barrier::new(9);
        let resume = Barrier::new(9);
        std::thread::scope(|scope| {
            for _ in 0..8 {
                let (counts, halfway, resume) = (&counts, &halfway, &resume);
                scope.spawn(move || {
                    for _ in 0..10_000 { counts.add(Counter::memo_requests, 1); }
                    halfway.wait();
                    resume.wait();
                    for _ in 0..10_000 { counts.add(Counter::memo_requests, 1); }
                });
            }
            halfway.wait();
            assert_eq!(counts.load(Counter::memo_requests as usize), 80_000);
            resume.wait();
            let mut previous = 80_000;
            for _ in 0..32 {
                let value = counts.load(Counter::memo_requests as usize);
                assert!((previous..=160_000).contains(&value));
                previous = value;
            }
        });
        assert_eq!(counts.load(Counter::memo_requests as usize), 160_000);
    }

    #[test]
    fn snapshots_keep_wrapping_u64_arithmetic_and_bounded_aligned_storage() {
        let counts = Counters::default();
        counts.add_at(0, Counter::column_requests, u64::MAX);
        counts.add_at(1, Counter::column_requests, 5);
        assert_eq!(counts.load(Counter::column_requests as usize), 4);
        counts.add_at(0, Counter::column_requests, 2);
        assert_eq!(counts.load(Counter::column_requests as usize), 6);
        assert_eq!(align_of::<Shard>(), 128);
        assert_eq!(size_of::<Shard>() % 128, 0);
        assert_eq!(Counters::storage_bytes(), size_of_val(&*counts.shards));
        assert!(SHARDS.is_power_of_two());
    }
}
