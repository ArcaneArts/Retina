//! Thread-local reservations keep concurrent/nested compilation inventories apart.
use super::{STATE, message};
use std::{
    cell::{Cell, RefCell},
    rc::Rc,
    time::Instant,
};

thread_local! {
    static CURRENT: RefCell<Option<Rc<Cell<u64>>>> = const { RefCell::new(None) };
}

pub(crate) struct PipelineBatch {
    pending: Rc<Cell<u64>>,
    previous: Option<Rc<Cell<u64>>>,
}

impl PipelineBatch {
    pub(crate) fn new(label: &str, count: usize) -> Self {
        let pending = Rc::new(Cell::new(count as u64));
        let previous = CURRENT.with(|current| current.replace(Some(pending.clone())));
        let mut state = STATE.0.lock().unwrap_or_else(|e| e.into_inner());
        state.progress.discover(count as u64, Instant::now());
        let progress = state.progress.report(Instant::now());
        drop(state);
        if count > 0 {
            message(format_args!(
                "DISCOVER pipeline batch={label} added={count} | {progress}"
            ));
        }
        Self { pending, previous }
    }
}

pub(super) fn claim() -> bool {
    CURRENT.with_borrow(|current| {
        let Some(pending) = current else {
            return false;
        };
        if pending.get() == 0 {
            return false;
        }
        pending.set(pending.get() - 1);
        true
    })
}

impl Drop for PipelineBatch {
    fn drop(&mut self) {
        CURRENT.with(|current| *current.borrow_mut() = self.previous.take());
        let count = self.pending.get();
        if count > 0 {
            let mut state = STATE.0.lock().unwrap_or_else(|e| e.into_inner());
            state.progress.cancel(count);
            let progress = state.progress.report(Instant::now());
            drop(state);
            message(format_args!(
                "ABANDON unstarted pipeline jobs={count} | {progress}"
            ));
        }
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn reservations_are_thread_local_nested_and_restored_on_unwind() {
        let outer = PipelineBatch::new("outer", 2);
        let pipeline = super::super::Stage::begin("test pipeline".into(), true);
        super::super::driver::observe(&pipeline, "test_entry", || {
            let _driver = tracing::info_span!("vkCreateComputePipelines").entered();
            assert_eq!(
                outer.pending.get(),
                1,
                "nested driver phases must not consume pipeline jobs"
            );
        });
        drop(pipeline);
        std::thread::spawn(|| assert!(!claim())).join().unwrap();
        let _ = std::panic::catch_unwind(|| {
            let inner = PipelineBatch::new("inner", 2);
            let _pipeline = super::super::Stage::begin("unwinding pipeline".into(), true);
            assert_eq!(inner.pending.get(), 1);
            panic!("scope test");
        });
        assert!(
            CURRENT.with_borrow(|current| Rc::ptr_eq(current.as_ref().unwrap(), &outer.pending))
        );
        drop(super::super::Stage::begin(
            "remaining pipeline".into(),
            true,
        ));
        assert_eq!(outer.pending.get(), 0);
        // Additional calls execute and are discovered even if the inventory was incomplete.
        drop(super::super::Stage::begin(
            "unplanned pipeline".into(),
            true,
        ));
        drop(outer);
        assert!(!claim());
    }
}
