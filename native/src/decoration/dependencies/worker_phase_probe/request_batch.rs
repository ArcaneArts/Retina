//! Test-fixture callers, separate from the native assembly pool. Every request
//! is available at one batch origin; this models an executor queue, not Java.
use std::sync::atomic::{AtomicUsize, Ordering};
use std::time::Instant;

pub(super) struct Sample<T> {
    pub index: usize,
    pub queue_ns: u64,
    pub callback_ns: u64,
    pub completion_ns: u64,
    pub value: T,
}

pub(super) fn run<T: Send>(
    count: usize,
    callers: usize,
    reverse: bool,
    generate: impl Fn(usize) -> T + Sync,
) -> Vec<Sample<T>> {
    assert!(callers > 0, "request callers must be positive");
    let origin = Instant::now();
    let invoke = |slot| {
        let index = if reverse { count - 1 - slot } else { slot };
        let queue_ns = origin.elapsed().as_nanos() as u64;
        let value = generate(index);
        let completion_ns = origin.elapsed().as_nanos() as u64;
        Sample {
            index,
            queue_ns,
            callback_ns: completion_ns - queue_ns,
            completion_ns,
            value,
        }
    };
    // Keep the original serial fixture on its caller thread.
    if callers == 1 {
        return (0..count).map(invoke).collect();
    }
    let next = AtomicUsize::new(0);
    std::thread::scope(|scope| {
        let handles: Vec<_> = (0..callers.min(count))
            .map(|_| {
                let next = &next;
                let invoke = &invoke;
                scope.spawn(move || {
                    let mut results = Vec::new();
                    loop {
                        let slot = next.fetch_add(1, Ordering::Relaxed);
                        if slot >= count {
                            break;
                        }
                        results.push((slot, invoke(slot)));
                    }
                    results
                })
            })
            .collect();
        let mut results = Vec::with_capacity(count);
        let mut failure = None;
        for handle in handles {
            match handle.join() {
                Ok(values) => results.extend(values),
                Err(error) => {
                    if failure.is_none() {
                        failure = Some(error);
                    }
                }
            }
        }
        if let Some(error) = failure {
            std::panic::resume_unwind(error);
        }
        results.sort_unstable_by_key(|(slot, _)| *slot);
        results.into_iter().map(|(_, sample)| sample).collect()
    })
}

#[test]
fn scoped_request_batch_preserves_order_exactly_once_and_bounds_active_callers() {
    for callers in [1, 2, 4, 8] {
        for reverse in [false, true] {
            let seen: Vec<_> = (0..20).map(|_| AtomicUsize::new(0)).collect();
            let active = AtomicUsize::new(0);
            let peak = AtomicUsize::new(0);
            let entered = AtomicUsize::new(0);
            let first_wave = std::sync::Barrier::new(callers);
            let samples = run(20, callers, reverse, |index| {
                seen[index].fetch_add(1, Ordering::SeqCst);
                let now = active.fetch_add(1, Ordering::SeqCst) + 1;
                peak.fetch_max(now, Ordering::SeqCst);
                if entered.fetch_add(1, Ordering::SeqCst) < callers {
                    first_wave.wait();
                }
                active.fetch_sub(1, Ordering::SeqCst);
                index * 3
            });
            assert_eq!(active.load(Ordering::SeqCst), 0);
            assert_eq!(peak.load(Ordering::SeqCst), callers);
            assert!(seen.iter().all(|v| v.load(Ordering::SeqCst) == 1));
            assert_eq!(samples.len(), 20);
            for (slot, sample) in samples.iter().enumerate() {
                let expected = if reverse { 19 - slot } else { slot };
                assert_eq!(sample.index, expected);
                assert_eq!(sample.value, expected * 3);
                assert_eq!(sample.queue_ns + sample.callback_ns, sample.completion_ns);
            }
        }
    }
    let caller = std::thread::current().id();
    assert!(run(3, 1, false, |_| std::thread::current().id())
        .iter()
        .all(|v| v.value == caller));
    assert!(run(0, 8, false, |_| unreachable!()).is_empty());
    assert_eq!(
        run(2, 8, true, |i| i)
            .iter()
            .map(|v| v.value)
            .collect::<Vec<_>>(),
        vec![1, 0]
    );
}

#[test]
fn scoped_request_batch_joins_live_callbacks_before_propagating_failure() {
    struct Active<'a>(&'a AtomicUsize);
    impl Drop for Active<'_> {
        fn drop(&mut self) {
            self.0.fetch_sub(1, Ordering::SeqCst);
        }
    }
    let active = AtomicUsize::new(0);
    let finished = AtomicUsize::new(0);
    let first_wave = std::sync::Barrier::new(2);
    let result = std::panic::catch_unwind(|| {
        run(20, 2, false, |index| {
            active.fetch_add(1, Ordering::SeqCst);
            let _active = Active(&active);
            if index < 2 {
                first_wave.wait();
            }
            assert_ne!(index, 0, "controlled request failure");
            finished.fetch_add(1, Ordering::SeqCst);
        })
    });
    assert!(result.is_err());
    assert_eq!(active.load(Ordering::SeqCst), 0);
    assert_eq!(finished.load(Ordering::SeqCst), 19);
}
