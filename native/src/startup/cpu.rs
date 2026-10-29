//! Query thread CPU counters without suspending or unwinding another thread.
#[cfg(not(windows))]
pub(super) struct Probe;
#[cfg(not(windows))]
impl Probe {
    pub fn current() -> Self { Self }
    pub fn report(&self) -> String { String::new() }
}

#[cfg(windows)]
mod windows {
    use std::os::windows::io::{AsRawHandle, FromRawHandle, OwnedHandle};
    use windows_sys::Win32::{Foundation::FILETIME, System::Threading::{
        GetCurrentThreadId, GetThreadTimes, OpenThread, THREAD_QUERY_LIMITED_INFORMATION,
    }};
    pub(crate) struct Probe {
        thread: Option<(OwnedHandle, u64)>,
        id: u32,
    }
    fn ticks(thread: &OwnedHandle) -> Option<u64> {
        let mut created = FILETIME::default();
        let mut exited = FILETIME::default();
        let mut kernel = FILETIME::default();
        let mut user = FILETIME::default();
        // Query-only handle; the target keeps running throughout this call.
        if unsafe { GetThreadTimes(thread.as_raw_handle(), &mut created, &mut exited, &mut kernel, &mut user) } == 0 {
            return None;
        }
        let value = |time: FILETIME| (u64::from(time.dwHighDateTime) << 32) | u64::from(time.dwLowDateTime);
        Some(value(kernel).saturating_add(value(user)))
    }
    impl Probe {
        pub fn current() -> Self {
            let id = unsafe { GetCurrentThreadId() };
            let raw = unsafe { OpenThread(THREAD_QUERY_LIMITED_INFORMATION, 0, id) };
            let thread = if raw.is_null() { None } else {
                let handle = unsafe { OwnedHandle::from_raw_handle(raw) };
                ticks(&handle).map(|start| (handle, start))
            };
            Self { thread, id }
        }
        pub fn report(&self) -> String {
            match self.thread.as_ref().and_then(|(handle, start)| ticks(handle).map(|now| now.saturating_sub(*start))) {
                Some(ticks) => format!(" thread_id={} thread_cpu_ms={:.1}", self.id, ticks as f64 / 10_000.0),
                None => format!(" thread_id={} thread_cpu_ms=unavailable", self.id),
            }
        }
    }
}
#[cfg(windows)]
pub(super) use windows::Probe;
