//! Counts real compute-pipeline calls, not their nested translation/driver stages.
use std::{
    collections::VecDeque,
    time::{Duration, Instant},
};

const WINDOW: Duration = Duration::from_secs(10);

#[derive(Default)]
pub(super) struct Progress {
    discovered: u64,
    finished: u64,
    abandoned: u64,
    queued: u64,
    active: u64,
    first: Option<Instant>,
    last: Option<Instant>,
    recent: VecDeque<Instant>,
}

impl Progress {
    pub fn discover(&mut self, count: u64, now: Instant) {
        if count > 0 {
            self.first.get_or_insert(now);
            self.discovered += count;
            self.queued += count;
        }
    }

    pub fn begin(&mut self, reserved: bool, now: Instant) {
        // Unplanned/lazy calls are discovered as they arrive. An incomplete
        // inventory must never prevent the actual pipeline call from running.
        if !reserved {
            self.discover(1, now);
        }
        self.queued -= 1;
        self.active += 1;
    }

    pub fn finish(&mut self, unwound: bool, now: Instant) {
        self.active -= 1;
        if unwound {
            self.abandoned += 1;
        } else {
            self.finished += 1;
            self.last = Some(now);
            self.prune(now);
            self.recent.push_back(now);
        }
    }

    pub fn cancel(&mut self, count: u64) {
        self.queued -= count;
        self.abandoned += count;
    }

    fn prune(&mut self, now: Instant) {
        while self
            .recent
            .front()
            .is_some_and(|at| now.duration_since(*at) >= WINDOW)
        {
            self.recent.pop_front();
        }
    }

    pub fn report(&mut self, now: Instant) -> String {
        self.prune(now);
        let elapsed = self
            .first
            .map_or(0.0, |at| now.duration_since(at).as_secs_f64());
        let window = elapsed.min(WINDOW.as_secs_f64());
        let rate = if window > 0.0 {
            self.recent.len() as f64 / window
        } else {
            0.0
        };
        let percent = if self.discovered > 0 {
            100.0 * self.finished as f64 / self.discovered as f64
        } else {
            0.0
        };
        let last = self.last.map_or_else(
            || "none".into(),
            |at| format!("{:.1}s", now.duration_since(at).as_secs_f64()),
        );
        format!(
            "pipelines={}/{} finished ({percent:.1}% of discovered) remaining={} queued={} active={} abandoned={} recent_rate={rate:.2}/s window={window:.1}s last_finished={last}",
            self.finished,
            self.discovered,
            self.queued + self.active,
            self.queued,
            self.active,
            self.abandoned,
        )
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn discoveries_completions_and_stalls_have_honest_counts_and_rates() {
        let now = Instant::now();
        let mut progress = Progress::default();
        assert!(
            progress
                .report(now)
                .contains("pipelines=0/0 finished (0.0%")
        );
        progress.discover(4, now);
        progress.begin(true, now);
        progress.finish(false, now + Duration::from_secs(1));
        progress.begin(true, now + Duration::from_secs(1));
        let report = progress.report(now + Duration::from_secs(2));
        assert!(report.contains("pipelines=1/4 finished (25.0% of discovered) remaining=3 queued=2 active=1 abandoned=0"), "{report}");
        assert!(
            report.contains("recent_rate=0.50/s window=2.0s last_finished=1.0s"),
            "{report}"
        );
        progress.discover(2, now + Duration::from_secs(3));
        let report = progress.report(now + Duration::from_secs(12));
        assert!(
            report.contains(
                "pipelines=1/6 finished (16.7% of discovered) remaining=5 queued=4 active=1"
            ),
            "{report}"
        );
        assert!(
            report.contains("recent_rate=0.00/s window=10.0s last_finished=11.0s"),
            "{report}"
        );
        // Aborted work stops contributing to remaining, without claiming success.
        progress.finish(true, now + Duration::from_secs(12));
        progress.cancel(4);
        assert!(
            progress
                .report(now + Duration::from_secs(12))
                .contains("remaining=0 queued=0 active=0 abandoned=5")
        );
        progress.begin(false, now + Duration::from_secs(13));
        progress.finish(false, now + Duration::from_secs(14));
        assert!(
            progress
                .report(now + Duration::from_secs(14))
                .contains("pipelines=2/7 finished")
        );
    }

    #[test]
    fn simultaneous_batches_share_counts_without_losing_completions() {
        use std::sync::{Arc, Barrier, Mutex};
        let progress = Arc::new(Mutex::new(Progress::default()));
        let barrier = Arc::new(Barrier::new(3));
        let workers: Vec<_> = (0..2)
            .map(|_| {
                let progress = progress.clone();
                let barrier = barrier.clone();
                std::thread::spawn(move || {
                    let mut state = progress.lock().unwrap();
                    state.discover(3, Instant::now());
                    state.begin(true, Instant::now());
                    drop(state);
                    barrier.wait();
                    barrier.wait();
                    let mut state = progress.lock().unwrap();
                    state.finish(false, Instant::now());
                    state.cancel(2);
                })
            })
            .collect();
        barrier.wait();
        assert!(
            progress
                .lock()
                .unwrap()
                .report(Instant::now())
                .contains("remaining=6 queued=4 active=2")
        );
        barrier.wait();
        for worker in workers {
            worker.join().unwrap();
        }
        assert!(
            progress
                .lock()
                .unwrap()
                .report(Instant::now())
                .contains("remaining=0 queued=0 active=0 abandoned=4")
        );
    }
}
