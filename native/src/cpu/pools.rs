//! CPU terrain producers must remain runnable while dependency workers wait for
//! their shared terrain cells. Two global lanes share one bounded thread budget.
use std::sync::OnceLock;

#[derive(Clone, Copy, Debug)]
pub(crate) struct Budget {
    pub roots: usize,
    pub terrain: usize,
}
impl Budget {
    fn new(requested: usize) -> Self {
        // One thread per lane guarantees producer progress even on small hosts.
        let total = requested.clamp(2, 16);
        // Terrain dominates CPU generation. A small dependency lane can feed
        // multiple independent terrain jobs without occupying their workers.
        let roots = (total / 4).max(1);
        Self {
            roots,
            terrain: total - roots,
        }
    }
}
static BUDGET: OnceLock<Budget> = OnceLock::new();
static ROOTS: OnceLock<Result<rayon::ThreadPool, String>> = OnceLock::new();
static TERRAIN: OnceLock<Result<rayon::ThreadPool, String>> = OnceLock::new();
fn budget() -> Budget {
    *BUDGET.get_or_init(|| {
        let requested = std::env::var("RETINA_REGION_THREADS")
            .ok()
            .and_then(|s| s.parse::<usize>().ok())
            .filter(|&n| n > 0)
            .unwrap_or_else(|| std::thread::available_parallelism().map_or(2, usize::from));
        let b = Budget::new(requested);
        crate::startup::info(format_args!(
            "CPU workers: total={} dependency/assembly={} terrain={}",
            b.roots + b.terrain,
            b.roots,
            b.terrain
        ));
        b
    })
}
fn pool(count: usize, lane: &'static str) -> Result<rayon::ThreadPool, String> {
    rayon::ThreadPoolBuilder::new()
        .num_threads(count)
        .thread_name(move |i| format!("retina-cpu-{lane}-{i}"))
        .build()
        .map_err(|e| format!("CPU {lane} worker initialization failed: {e}"))
}
pub(crate) fn roots() -> Result<&'static rayon::ThreadPool, String> {
    ROOTS
        .get_or_init(|| pool(budget().roots, "assembly"))
        .as_ref()
        .map_err(Clone::clone)
}
pub(crate) fn terrain() -> Result<&'static rayon::ThreadPool, String> {
    TERRAIN
        .get_or_init(|| pool(budget().terrain, "terrain"))
        .as_ref()
        .map_err(Clone::clone)
}

#[cfg(test)]
mod tests {
    use super::*;
    use rayon::prelude::*;
    #[test]
    fn cpu_lanes_share_one_bounded_budget() {
        for requested in 0..=64 {
            let b = Budget::new(requested);
            assert_eq!(b.roots + b.terrain, requested.clamp(2, 16));
            assert!(b.roots >= 1 && b.terrain >= 1);
            assert!(b.roots <= b.terrain && b.roots <= 4);
        }
    }
    #[test]
    fn terrain_makes_progress_while_every_root_worker_is_waiting() {
        let roots = pool(2, "test-assembly").unwrap();
        let terrain = pool(2, "test-terrain").unwrap();
        let gate = std::sync::Arc::new(std::sync::Barrier::new(3));
        let (release, receiver) = std::sync::mpsc::channel();
        let receiver = std::sync::Arc::new(std::sync::Mutex::new(receiver));
        let (done, finished) = std::sync::mpsc::channel();
        for _ in 0..2 {
            let (gate, receiver, done) = (gate.clone(), receiver.clone(), done.clone());
            roots.spawn(move || {
                gate.wait();
                let _ = receiver.lock().unwrap().recv();
                let _ = done.send(());
            });
        }
        gate.wait();
        let sum: usize = terrain.install(|| {
            (0..64usize)
                .into_par_iter()
                .map(|i| {
                    assert!(
                        std::thread::current()
                            .name()
                            .unwrap()
                            .starts_with("retina-cpu-test-terrain-")
                    );
                    i
                })
                .sum()
        });
        // Release waiting roots before any assertion can unwind their owners.
        release.send(()).unwrap();
        release.send(()).unwrap();
        assert_eq!(sum, 2016);
        for _ in 0..2 {
            finished
                .recv_timeout(std::time::Duration::from_secs(5))
                .unwrap();
        }
    }
}
