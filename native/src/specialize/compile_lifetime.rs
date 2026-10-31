//! Cooperative retirement between real compiler calls. One strong State owner
//! belongs to the active worker; remaining owners are profiles or reuse leases.
use super::*;
thread_local! { static OWNER: std::cell::RefCell<Option<Weak<State>>> = const { std::cell::RefCell::new(None) }; }
pub(super) fn owner() -> Option<Weak<State>> { OWNER.with(|owner| owner.borrow().clone()) }
pub(super) fn with_owner<T>(owner: Option<Weak<State>>, operation: impl FnOnce() -> T) -> T {
    struct Restore(Option<Weak<State>>);
    impl Drop for Restore { fn drop(&mut self) { OWNER.with(|owner| *owner.borrow_mut() = self.0.take()); } }
    let _restore = Restore(OWNER.with(|current| current.replace(owner)));
    operation()
}
pub(super) fn check() -> Result<(), String> {
    if OWNER.with(|owner| owner.borrow().as_ref().is_some_and(|owner| owner.strong_count() <= 1)) {
        Err("GPU compilation retired after its last profile owner closed".into())
    } else { Ok(()) }
}

#[cfg(test)]
mod tests {
    use super::*;
    #[test]
    fn retirement_waits_for_all_shared_owners_and_context_restores() {
        let worker = Arc::new(State::default()); let profile = worker.clone();
        with_owner(Some(Arc::downgrade(&worker)), || {
            check().unwrap(); let other_profile = profile.clone(); drop(profile);
            check().unwrap(); drop(other_profile); assert!(check().is_err());
        });
        check().unwrap();
    }
}
