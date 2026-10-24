use super::*;
use std::sync::{Barrier, mpsc};
use std::time::Duration;

struct Callbacks<P, R, F> {
    prepare: P,
    run: R,
    publish: F,
}
impl<P, R, F> Steps for Callbacks<P, R, F>
where
    P: FnMut(usize) -> Result<usize, String> + Send + Sync,
    R: Fn(usize, usize) -> Result<(), String> + Send + Sync,
    F: FnMut(usize) -> Result<(), String> + Send + Sync,
{
    fn prepare(&mut self, root: usize) -> Result<usize, String> {
        (self.prepare)(root)
    }
    fn run(&self, root: usize, index: usize) -> Result<(), String> {
        (self.run)(root, index)
    }
    fn publish(&mut self, root: usize) -> Result<(), String> {
        (self.publish)(root)
    }
}

#[test]
fn concurrent_chains_preserve_roots_and_execute_once_with_bounded_workers() {
    for workers in [1, 2, 4] {
        let pool = rayon::ThreadPoolBuilder::new()
            .num_threads(workers)
            .build()
            .unwrap();
        let counts = [0, 1, 0, 3, 127, 0, 2];
        let active = AtomicUsize::new(0);
        let peak = AtomicUsize::new(0);
        let entered = AtomicUsize::new(0);
        let first_wave = Barrier::new(workers);
        std::thread::scope(|scope| {
            for _ in 0..8 {
                let (pool, counts, active, peak, entered, first_wave) =
                    (&pool, &counts, &active, &peak, &entered, &first_wave);
                scope.spawn(move || {
                    let published = AtomicUsize::new(0);
                    let own_active = AtomicUsize::new(0);
                    let seen: Vec<Vec<_>> = counts
                        .iter()
                        .map(|&count| (0..count).map(|_| AtomicUsize::new(0)).collect())
                        .collect();
                    let mut prepared = Vec::new();
                    let mut state = Callbacks {
                        prepare: |root: usize| {
                            assert!(pool.current_thread_index().is_some());
                            assert_eq!(published.load(Ordering::SeqCst), root);
                            assert_eq!(own_active.load(Ordering::SeqCst), 0);
                            prepared.push(root);
                            Ok(counts[root])
                        },
                        run: |root: usize, index: usize| {
                            assert!(pool.current_thread_index().is_some());
                            assert_eq!(published.load(Ordering::SeqCst), root);
                            own_active.fetch_add(1, Ordering::SeqCst);
                            let now = active.fetch_add(1, Ordering::SeqCst) + 1;
                            peak.fetch_max(now, Ordering::SeqCst);
                            if entered.fetch_add(1, Ordering::SeqCst) < workers {
                                first_wave.wait();
                            }
                            seen[root][index].fetch_add(1, Ordering::SeqCst);
                            active.fetch_sub(1, Ordering::SeqCst);
                            own_active.fetch_sub(1, Ordering::SeqCst);
                            Ok(())
                        },
                        publish: |root: usize| {
                            assert!(pool.current_thread_index().is_some());
                            assert_eq!(own_active.load(Ordering::SeqCst), 0);
                            assert!(seen[root].iter().all(|n| n.load(Ordering::SeqCst) == 1));
                            assert_eq!(published.fetch_add(1, Ordering::SeqCst), root);
                            Ok(())
                        },
                    };
                    assert!(pool.current_thread_index().is_none());
                    run(pool, &mut state, counts.len()).unwrap();
                    assert_eq!(published.load(Ordering::SeqCst), counts.len());
                    assert_eq!(prepared, (0..counts.len()).collect::<Vec<_>>());
                    assert!(seen.iter().flatten().all(|n| n.load(Ordering::SeqCst) == 1));
                });
            }
        });
        assert_eq!(active.load(Ordering::SeqCst), 0);
        assert_eq!(peak.load(Ordering::SeqCst), workers);
    }
}

#[test]
fn continuation_and_caller_return_do_not_join_unrelated_work() {
    let pool = Arc::new(
        rayon::ThreadPoolBuilder::new()
            .num_threads(1)
            .build()
            .unwrap(),
    );
    let (entered, ready) = mpsc::channel();
    let (release, released) = mpsc::channel();
    let released = Mutex::new(released);
    let (next_done, next_completed) = mpsc::channel();
    let (done, completed) = mpsc::channel();
    let caller = {
        let pool = pool.clone();
        std::thread::spawn(move || {
            let mut state = Callbacks {
                prepare: |_| Ok(1),
                run: |root, _| {
                    if root == 0 {
                        entered.send(()).unwrap();
                        released.lock().unwrap().recv().unwrap();
                    } else {
                        next_done.send(()).unwrap();
                    }
                    Ok(())
                },
                publish: |_| Ok(()),
            };
            done.send(run(&pool, &mut state, 2)).unwrap();
        })
    };
    ready.recv_timeout(Duration::from_secs(10)).unwrap();
    let (other_entered, other_ready) = mpsc::channel();
    let (other_release, other_released) = mpsc::channel();
    pool.spawn(move || {
        other_entered.send(()).unwrap();
        other_released.recv().unwrap();
    });
    release.send(()).unwrap();
    let next = next_completed.recv_timeout(Duration::from_secs(10));
    let other = other_ready.recv_timeout(Duration::from_secs(10));
    let result = completed.recv_timeout(Duration::from_secs(10));
    // Release the unrelated worker even if a result is wrong, so a failing test
    // cannot leave the process stranded in a blocked Rayon job.
    other_release.send(()).unwrap();
    caller.join().unwrap();
    next.unwrap();
    other.unwrap();
    assert_eq!(result.unwrap(), Ok(()));
}

#[test]
fn later_root_failure_or_panic_joins_live_lanes_and_never_advances() {
    for panic in [false, true] {
        let pool = Arc::new(
            rayon::ThreadPoolBuilder::new()
                .num_threads(2)
                .build()
                .unwrap(),
        );
        let active = Arc::new(AtomicUsize::new(0));
        let prepared = Arc::new(Mutex::new(Vec::new()));
        let published = Arc::new(Mutex::new(Vec::new()));
        let (entered, ready) = mpsc::channel();
        let (release, released) = mpsc::channel();
        let released = Mutex::new(released);
        let first_wave = Barrier::new(2);
        let (done, completed) = mpsc::channel();
        let caller = {
            let (pool, active, prepared, published) = (
                pool.clone(),
                active.clone(),
                prepared.clone(),
                published.clone(),
            );
            std::thread::spawn(move || {
                struct Active<'a>(&'a AtomicUsize);
                impl Drop for Active<'_> {
                    fn drop(&mut self) {
                        self.0.fetch_sub(1, Ordering::SeqCst);
                    }
                }
                let mut state = Callbacks {
                    prepare: |root| {
                        prepared.lock().unwrap().push(root);
                        Ok(if root == 1 { 2 } else { 1 })
                    },
                    run: |root, index| {
                        active.fetch_add(1, Ordering::SeqCst);
                        let _active = Active(&active);
                        if root == 1 {
                            entered.send(index).unwrap();
                            first_wave.wait();
                            if index == 0 {
                                assert!(!panic, "controlled root callback panic");
                                return Err("original callback failure".into());
                            }
                            released.lock().unwrap().recv().unwrap();
                        }
                        Ok(())
                    },
                    publish: |root| {
                        published.lock().unwrap().push(root);
                        Ok(())
                    },
                };
                done.send(std::panic::catch_unwind(std::panic::AssertUnwindSafe(
                    || run(&pool, &mut state, 3),
                )))
                .unwrap();
            })
        };
        ready.recv_timeout(Duration::from_secs(10)).unwrap();
        ready.recv_timeout(Duration::from_secs(10)).unwrap();
        let premature = completed.try_recv();
        let active_before_release = active.load(Ordering::SeqCst);
        release.send(()).unwrap();
        let result = completed.recv_timeout(Duration::from_secs(10)).unwrap();
        caller.join().unwrap();
        assert!(matches!(premature, Err(mpsc::TryRecvError::Empty)));
        assert!(active_before_release >= 1);
        assert_eq!(active.load(Ordering::SeqCst), 0);
        assert_eq!(*prepared.lock().unwrap(), vec![0, 1]);
        assert_eq!(*published.lock().unwrap(), vec![0]);
        if panic {
            assert!(result.is_err());
        } else {
            assert_eq!(result.unwrap(), Err("original callback failure".into()));
        }
        pool.install(|| assert!(pool.current_thread_index().is_some()));
    }
}

#[test]
fn preparation_and_publication_failures_stop_at_the_original_root() {
    let pool = rayon::ThreadPoolBuilder::new()
        .num_threads(2)
        .build()
        .unwrap();
    for inside in [false, true] {
        for count in [0, 1, 127] {
            for fail_preparation in [false, true] {
                for panic in [false, true] {
                    let mut prepared = Vec::new();
                    let mut published = Vec::new();
                    let calls = AtomicUsize::new(0);
                    let mut state = Callbacks {
                        prepare: |root| {
                            prepared.push(root);
                            if fail_preparation && root == 1 {
                                assert!(!panic, "controlled preparation panic");
                                return Err("original preparation failure".into());
                            }
                            Ok(count)
                        },
                        run: |_, _| {
                            calls.fetch_add(1, Ordering::Relaxed);
                            Ok(())
                        },
                        publish: |root| {
                            if !fail_preparation && root == 1 {
                                assert!(!panic, "controlled publication panic");
                                return Err("original publication failure".into());
                            }
                            published.push(root);
                            Ok(())
                        },
                    };
                    let mut execute = || run(&pool, &mut state, 3);
                    let result = std::panic::catch_unwind(std::panic::AssertUnwindSafe(|| {
                        if inside {
                            pool.install(execute)
                        } else {
                            execute()
                        }
                    }));
                    assert_eq!(prepared, vec![0, 1]);
                    assert_eq!(published, vec![0]);
                    assert_eq!(
                        calls.load(Ordering::Relaxed),
                        count * if fail_preparation { 1 } else { 2 }
                    );
                    if panic {
                        assert!(result.is_err());
                    } else {
                        assert_eq!(
                            result.unwrap(),
                            Err(if fail_preparation {
                                "original preparation failure"
                            } else {
                                "original publication failure"
                            }
                            .into())
                        );
                    }
                }
            }
        }
    }
}

#[test]
fn empty_chains_and_long_empty_root_sequences_are_bounded() {
    let pool = rayon::ThreadPoolBuilder::new()
        .num_threads(1)
        .build()
        .unwrap();
    for roots in [0, 50_000] {
        let mut prepared = 0;
        let mut published = 0;
        let mut state = Callbacks {
            prepare: |root| {
                assert_eq!(prepared, root);
                prepared += 1;
                Ok(0)
            },
            run: |_, _| panic!("empty root callback"),
            publish: |root| {
                assert_eq!(published, root);
                published += 1;
                Ok(())
            },
        };
        run(&pool, &mut state, roots).unwrap();
        assert_eq!(prepared, roots);
        assert_eq!(published, roots);
    }
}

#[test]
fn settlement_records_success_and_each_error_once_without_masking_failure() {
    struct State {
        fail: &'static str,
        settled: Vec<(usize, bool)>,
    }
    impl Steps for State {
        fn prepare(&mut self, root: usize) -> Result<usize, String> {
            if root == 1 && self.fail == "prepare" {
                Err("prepare".into())
            } else {
                Ok(1)
            }
        }
        fn run(&self, root: usize, _: usize) -> Result<(), String> {
            if root == 1 && self.fail == "run" {
                Err("run".into())
            } else {
                Ok(())
            }
        }
        fn publish(&mut self, root: usize) -> Result<(), String> {
            if root == 1 && self.fail == "publish" {
                Err("publish".into())
            } else {
                Ok(())
            }
        }
        fn settled(&mut self, root: usize, success: bool) {
            self.settled.push((root, success));
        }
    }
    let pool = rayon::ThreadPoolBuilder::new()
        .num_threads(2)
        .build()
        .unwrap();
    for fail in ["", "prepare", "run", "publish"] {
        let mut state = State {
            fail,
            settled: Vec::new(),
        };
        let result = run(&pool, &mut state, 3);
        if fail.is_empty() {
            assert_eq!(result, Ok(()));
            assert_eq!(state.settled, vec![(0, true), (1, true), (2, true)]);
        } else {
            assert_eq!(result, Err(fail.into()));
            assert_eq!(state.settled, vec![(0, true), (1, false)]);
        }
    }
}
