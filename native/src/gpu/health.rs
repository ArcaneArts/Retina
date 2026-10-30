//! Preserve an actual device failure and stop reusing its invalid resources.
use std::sync::{Arc, Mutex, OnceLock, atomic::{AtomicU64, Ordering}};

#[derive(Default)]
pub(super) struct Health {
    error: OnceLock<String>,
    last_submission: Mutex<Option<String>>,
    submissions: AtomicU64,
}
impl Health {
    pub fn install(device: &wgpu::Device, backend: &str) -> Arc<Self> {
        let health = Arc::new(Self::default());
        let callback = health.clone();
        let backend = backend.to_owned();
        device.set_device_lost_callback(move |reason, message| {
            let last = callback.last_submission.lock().unwrap_or_else(|e| e.into_inner()).clone();
            let error = format!("GPU device lost: backend={backend} reason={reason:?} message={message}; last_submission={}", last.as_deref().unwrap_or("none"));
            // Normal device destruction also calls this callback. Keep its
            // state terminal without reporting normal teardown as a crash.
            callback.fail(error, reason != wgpu::DeviceLostReason::Destroyed);
        });
        health
    }
    pub fn check(&self) -> Result<(), String> {
        self.error.get().map_or(Ok(()), |error| Err(error.clone()))
    }
    pub fn fail(&self, error: String, log: bool) -> String {
        if self.error.set(error).is_ok() && log {
            crate::startup::message(self.error.get().unwrap());
        }
        self.error.get().unwrap().clone()
    }
    pub fn panic(&self, operation: &str, payload: &(dyn std::any::Any + Send)) -> String {
        let cause = payload.downcast_ref::<String>().map(String::as_str)
            .or_else(|| payload.downcast_ref::<&str>().copied()).unwrap_or("non-string panic");
        self.fail(format!("GPU {operation} panicked: {cause}"), true)
    }
    pub fn submission(&self, details: String) {
        let id = self.submissions.fetch_add(1, Ordering::Relaxed) + 1;
        let details = format!("#{id} {details}");
        *self.last_submission.lock().unwrap_or_else(|e| e.into_inner()) = Some(details.clone());
        if id <= 8 { crate::startup::message(format_args!("GPU submission {details}")); }
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    #[test]
    fn original_device_failure_survives_later_mapping_and_poll_errors() {
        let health = Health::default();
        assert!(health.check().is_ok());
        let original = health.fail("Vulkan device lost: driver reset".into(), false);
        assert_eq!(health.panic("poll", &"Parent device is lost"), original);
        assert_eq!(health.panic("mapping", &"invalid buffer"), original);
        assert_eq!(health.check(), Err(original));
    }
    #[test]
    fn panic_payload_is_preserved_instead_of_a_generic_generation_error() {
        let health = Health::default();
        assert_eq!(health.panic("completion", &String::from("Parent device is lost")), "GPU completion panicked: Parent device is lost");
    }

    #[test]
    #[ignore = "requires a real GPU"]
    fn destroyed_device_returns_one_failure_before_reusing_resources() {
        let gpu = super::super::Gpu::new(Default::default()).unwrap();
        gpu.device.destroy();
        let first = gpu.poll(wgpu::PollType::Poll).unwrap_err();
        assert_eq!(gpu.poll(wgpu::PollType::Poll).unwrap_err(), first);
        assert_eq!(gpu.failure(), Some(first));
    }
}
