//! One caller-owned scope for ordered roots. The last lane publishes a root and
//! schedules its successor without joining another root on a pool worker.
use std::sync::{
    Arc, Mutex, RwLock,
    atomic::{AtomicBool, AtomicUsize, Ordering},
};

mod planner;
pub(super) use planner::evaluate;

trait Steps: Send + Sync {
    fn prepare(&mut self, root: usize) -> Result<usize, String>;
    fn run(&self, root: usize, index: usize) -> Result<(), String>;
    fn publish(&mut self, root: usize) -> Result<(), String>;
    fn settled(&mut self, _root: usize, _success: bool) {}
}

struct Driver<'a, S> {
    state: RwLock<&'a mut S>,
    roots: usize,
    workers: usize,
    outcome: Mutex<Option<Result<(), String>>>,
}

struct Batch {
    next: AtomicUsize,
    remaining: AtomicUsize,
    stopped: AtomicBool,
    failure: Mutex<Option<String>>,
}

fn run<S: Steps>(pool: &rayon::ThreadPool, state: &mut S, roots: usize) -> Result<(), String> {
    if roots == 0 {
        return Ok(());
    }
    let driver = Driver {
        state: RwLock::new(state),
        roots,
        workers: pool.current_num_threads(),
        outcome: Mutex::new(None),
    };
    pool.in_place_scope(|scope| {
        scope.spawn(|scope| advance(scope, &driver, 0));
    });
    // All lanes, publication, preparation and their captured owners have joined
    // before returning or propagating a panic. Nothing is detached from the job.
    driver
        .outcome
        .into_inner()
        .unwrap()
        .expect("joined root chain outcome")
}

/// Returns true only when a successful root has a successor. All callback read
/// guards are gone before this exclusive publication/observer boundary.
fn settle<S: Steps>(driver: &Driver<'_, S>, root: usize, evaluated: Result<(), String>) -> bool {
    let result = {
        let mut state = driver.state.write().unwrap();
        let result = evaluated.and_then(|()| state.publish(root));
        state.settled(root, result.is_ok());
        result
    };
    if result.is_err() || root + 1 == driver.roots {
        *driver.outcome.lock().unwrap() = Some(result);
        false
    } else {
        true
    }
}

fn advance<'scope, 'state: 'scope, S: Steps + 'scope>(
    scope: &rayon::Scope<'scope>,
    driver: &'scope Driver<'state, S>,
    mut root: usize,
) {
    loop {
        let prepared = driver.state.write().unwrap().prepare(root);
        let count = match prepared {
            Ok(count) => count,
            Err(error) => {
                settle(driver, root, Err(error));
                return;
            }
        };
        if count == 0 {
            if !settle(driver, root, Ok(())) {
                return;
            }
            // Empty roots advance iteratively rather than growing the stack.
            root += 1;
            continue;
        }
        let lanes = count.min(driver.workers);
        let batch = Arc::new(Batch {
            next: AtomicUsize::new(0),
            remaining: AtomicUsize::new(lanes),
            stopped: AtomicBool::new(false),
            failure: Mutex::new(None),
        });
        for _ in 0..lanes {
            let batch = batch.clone();
            scope.spawn(move |scope| {
                {
                    let state = driver.state.read().unwrap();
                    while !batch.stopped.load(Ordering::Relaxed) {
                        let index = batch.next.fetch_add(1, Ordering::Relaxed);
                        if index >= count {
                            break;
                        }
                        if let Err(error) = state.run(root, index) {
                            batch.failure.lock().unwrap().get_or_insert(error);
                            batch.stopped.store(true, Ordering::Relaxed);
                            break;
                        }
                    }
                }
                // A panicking callback never signals completion, so its root
                // cannot publish or schedule another root. The outer scope
                // still joins every live lane before propagating the panic.
                if batch.remaining.fetch_sub(1, Ordering::AcqRel) == 1 {
                    let evaluated = batch.failure.lock().unwrap().take().map_or(Ok(()), Err);
                    if settle(driver, root, evaluated) {
                        advance(scope, driver, root + 1);
                    }
                }
            });
        }
        return;
    }
}

#[cfg(test)]
mod tests;
