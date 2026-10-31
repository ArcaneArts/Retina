//! Bounded, nonblocking source queue. Required preloads precede optional work.
use std::collections::VecDeque;
use std::sync::{Arc, Condvar, Mutex};

pub(super) trait Work {
    fn required(&self) -> bool;
    fn live(&self) -> bool;
    fn bytes(&self) -> usize;
    fn rejected(self, reason: &str);
}
struct State<T> { jobs: VecDeque<T>, closed: bool }
struct Shared<T> { state: Mutex<State<T>>, changed: Condvar, count: usize, bytes: usize }
pub(super) struct Sender<T: Work>(Arc<Shared<T>>);
pub(super) struct Receiver<T: Work>(Arc<Shared<T>>);
#[derive(Clone, Copy)]
pub(super) enum Lane { Any, Required, #[cfg(test)] Optional }
pub(super) fn channel<T: Work>(count: usize, bytes: usize) -> (Sender<T>, Receiver<T>) {
    let shared = Arc::new(Shared { state: Mutex::new(State { jobs: VecDeque::new(), closed: false }), changed: Condvar::new(), count, bytes });
    (Sender(shared.clone()), Receiver(shared))
}
impl<T: Work> Sender<T> {
    pub fn send(&self, job: T) -> Result<(), String> {
        let mut state = self.0.state.lock().unwrap_or_else(|e| e.into_inner());
        state.jobs.retain(Work::live);
        // A single oversized valid graph is allowed to make progress. Only its
        // concurrent queue peers are backpressured; no graph-size preflight gate.
        let full = state.jobs.len() >= self.0.count || !state.jobs.is_empty()
            && state.jobs.iter().map(Work::bytes).sum::<usize>().saturating_add(job.bytes()) > self.0.bytes;
        if state.closed || full {
            let reason = if state.closed { "GPU shader compiler stopped" } else { "GPU shader source queue full; retaining interpreter" };
            drop(state); job.rejected(reason); return Err(reason.into());
        }
        state.jobs.push_back(job); self.0.changed.notify_all(); Ok(())
    }
}
impl<T: Work> Receiver<T> {
    pub fn recv(&self, lane: Lane) -> Option<T> {
        let mut state = self.0.state.lock().unwrap_or_else(|e| e.into_inner());
        loop {
            if state.closed { return None; }
            state.jobs.retain(Work::live);
            let index = match lane {
                Lane::Any => state.jobs.iter().position(Work::required).or_else(|| (!state.jobs.is_empty()).then_some(0)),
                Lane::Required => state.jobs.iter().position(Work::required),
                #[cfg(test)]
                Lane::Optional => state.jobs.iter().position(|job| !job.required()),
            };
            if let Some(index) = index {
                return state.jobs.remove(index);
            }
            state = self.0.changed.wait(state).unwrap_or_else(|e| e.into_inner());
        }
    }
}
fn close<T: Work>(shared: &Shared<T>) {
    let jobs = {
        let mut state = shared.state.lock().unwrap_or_else(|e| e.into_inner());
        state.closed = true; shared.changed.notify_all(); std::mem::take(&mut state.jobs)
    };
    for job in jobs { job.rejected("GPU shader compiler stopped"); }
}
impl<T: Work> Drop for Sender<T> { fn drop(&mut self) { close(&self.0); } }
impl<T: Work> Drop for Receiver<T> { fn drop(&mut self) { close(&self.0); } }

impl Work for super::Job {
    fn required(&self) -> bool { self.interpreter.is_some() && self.policy != crate::startup::InterpreterPolicy::Optimized }
    fn live(&self) -> bool { self.state.strong_count() != 0 }
    fn bytes(&self) -> usize { self.program.len() }
    fn rejected(self, reason: &str) {
        for state in [self.state.upgrade(), self.promotion].into_iter().flatten() {
            *state.result.lock().unwrap_or_else(|e| e.into_inner()) = Some(Err(reason.into()));
            state.progress.status.store(3, std::sync::atomic::Ordering::Release);
            state.done.notify_all();
        }
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use std::sync::atomic::{AtomicBool, Ordering};
    struct Item { id: usize, required: bool, bytes: usize, live: Arc<AtomicBool>, rejected: Arc<Mutex<Vec<usize>>> }
    impl Work for Item {
        fn required(&self) -> bool { self.required }
        fn live(&self) -> bool { self.live.load(Ordering::Relaxed) }
        fn bytes(&self) -> usize { self.bytes }
        fn rejected(self, _: &str) { self.rejected.lock().unwrap().push(self.id); }
    }
    #[test]
    fn bounded_priority_retirement_oversized_progress_and_shutdown() {
        let rejected = Arc::new(Mutex::new(Vec::new()));
        let make = |id, required, bytes| Item { id, required, bytes, live: Arc::new(AtomicBool::new(true)), rejected: rejected.clone() };
        let (send, receive) = channel(3, 10);
        send.send(make(1, false, 3)).unwrap();
        send.send(make(2, true, 3)).unwrap();
        assert_eq!(receive.recv(Lane::Required).unwrap().id, 2);
        let stale = make(3, false, 2); let owner = stale.live.clone();
        send.send(stale).unwrap(); owner.store(false, Ordering::Relaxed);
        send.send(make(4, false, 7)).unwrap();
        assert!(send.send(make(5, false, 1)).is_err());
        assert_eq!(receive.recv(Lane::Any).unwrap().id, 1);
        assert_eq!(receive.recv(Lane::Optional).unwrap().id, 4);
        send.send(make(6, true, 100)).unwrap();
        assert_eq!(receive.recv(Lane::Any).unwrap().id, 6);
        send.send(make(7, false, 1)).unwrap(); drop(send);
        assert!(receive.recv(Lane::Any).is_none());
        assert_eq!(*rejected.lock().unwrap(), [5, 7]);
    }
}
