//! Optional two-call driver budget shared by required and background compilation.
//! An executing driver call cannot be cancelled. Required waiters take the next
//! available slot; background work can occupy at most one slot.
use std::cell::Cell;
use std::sync::{Condvar, LazyLock, Mutex};

#[derive(Clone, Copy, Debug, Default, Eq, PartialEq)]
pub(crate) enum Priority { #[default] Required, Optional }
thread_local! { static PRIORITY: Cell<Priority> = const { Cell::new(Priority::Required) }; }
pub(crate) fn priority() -> Priority { PRIORITY.get() }
pub(crate) fn with_priority<T>(value: Priority, operation: impl FnOnce() -> T) -> T {
    struct Restore(Priority);
    impl Drop for Restore { fn drop(&mut self) { PRIORITY.set(self.0); } }
    let _restore = Restore(PRIORITY.replace(value));
    operation()
}

pub(crate) fn parallel_enabled() -> bool {
    static ENABLED: LazyLock<bool> = LazyLock::new(||
        std::env::var("RETINA_INTERPRETER_COMPILE_THREADS").as_deref() == Ok("2"));
    *ENABLED
}

#[derive(Default)]
struct State { active: usize, optional: usize, required_waiters: usize }
#[derive(Default)]
struct Budget { state: Mutex<State>, changed: Condvar }
struct Permit<'a> { budget: &'a Budget, optional: bool }
impl Budget {
    fn acquire(&self, priority: Priority) -> Permit<'_> {
        let mut state = self.state.lock().unwrap_or_else(|e| e.into_inner());
        let optional = priority == Priority::Optional;
        if !optional { state.required_waiters += 1; self.changed.notify_all(); }
        while state.active == 2 || optional && (state.optional == 1 || state.required_waiters > 0) {
            state = self.changed.wait(state).unwrap_or_else(|e| e.into_inner());
        }
        if !optional { state.required_waiters -= 1; }
        state.active += 1;
        state.optional += usize::from(optional);
        Permit { budget: self, optional }
    }
}
impl Drop for Permit<'_> {
    fn drop(&mut self) {
        let mut state = self.budget.state.lock().unwrap_or_else(|e| e.into_inner());
        state.active -= 1; state.optional -= usize::from(self.optional);
        self.budget.changed.notify_all();
    }
}

pub(crate) fn with_driver_slot<T>(operation: impl FnOnce() -> T) -> T {
    if !parallel_enabled() { return operation(); }
    static BUDGET: LazyLock<Budget> = LazyLock::new(Budget::default);
    let start = std::time::Instant::now();
    let _stage = crate::startup::Stage::new(format!("compiler admission priority={:?}", priority()));
    let _permit = BUDGET.acquire(priority());
    crate::startup::message(format_args!("Compiler admission priority={:?} wait_ms={:.3}", priority(), start.elapsed().as_secs_f64()*1000.0));
    operation()
}

#[cfg(test)]
mod tests {
    use super::*;
    use std::sync::{Arc, mpsc};
    use std::time::{Duration, Instant};
    #[test]
    fn required_waiter_precedes_optional_and_unwind_releases_slots() {
        let budget = Arc::new(Budget::default());
        let first = budget.acquire(Priority::Required);
        let second = budget.acquire(Priority::Required);
        let (sent, received) = mpsc::channel();
        let required_budget = budget.clone(); let required_sent = sent.clone();
        let required = std::thread::spawn(move || {
            let _permit = required_budget.acquire(Priority::Required);
            required_sent.send("required").unwrap();
        });
        let deadline = Instant::now() + Duration::from_secs(5);
        while budget.state.lock().unwrap().required_waiters != 1 {
            assert!(Instant::now() < deadline); std::thread::yield_now();
        }
        let optional_budget = budget.clone();
        let optional = std::thread::spawn(move || {
            let _permit = optional_budget.acquire(Priority::Optional);
            sent.send("optional").unwrap();
        });
        drop(first);
        assert_eq!(received.recv_timeout(Duration::from_secs(5)).unwrap(), "required");
        drop(second);
        assert_eq!(received.recv_timeout(Duration::from_secs(5)).unwrap(), "optional");
        required.join().unwrap(); optional.join().unwrap();
        let _ = std::panic::catch_unwind(|| { let _permit = budget.acquire(Priority::Optional); panic!("unwind"); });
        let state = budget.state.lock().unwrap();
        assert_eq!((state.active, state.optional, state.required_waiters), (0, 0, 0));
    }
    #[test]
    fn priority_is_scoped_and_new_workers_default_to_required() {
        with_priority(Priority::Optional, || {
            assert_eq!(priority(), Priority::Optional);
            assert_eq!(std::thread::spawn(priority).join().unwrap(), Priority::Required);
        });
        assert_eq!(priority(), Priority::Required);
    }
}
