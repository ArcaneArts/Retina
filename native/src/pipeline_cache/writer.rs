//! Bounded latest-snapshot writer. Disk I/O never holds the queue mutex.
use std::{path::PathBuf, sync::{Arc, Condvar, Mutex, atomic::{AtomicBool, Ordering}}, thread::JoinHandle, time::Instant};
#[derive(Default)]
struct State { next: u64, finished: u64, pending: Option<(u64, Vec<u8>)>, stop: bool }
struct Shared { state: Mutex<State>, changed: Condvar, failed: AtomicBool }
pub(super) struct Writer { shared: Arc<Shared>, thread: Option<JoinHandle<()>> }
impl Writer {
    pub fn new(path: PathBuf) -> std::io::Result<Self> {
        Self::spawn(path.display().to_string(), move |data| super::write(&path, data).map_err(|e| std::io::Error::other(format!("{}: {e}", path.display()))))
    }
    fn spawn(label: String, save: impl Fn(&[u8]) -> std::io::Result<()> + Send + 'static) -> std::io::Result<Self> {
        let shared = Arc::new(Shared { state: Mutex::new(State::default()), changed: Condvar::new(), failed: AtomicBool::new(false) });
        let worker = shared.clone();
        let thread = std::thread::Builder::new().name("retina-cache-writer".into()).spawn(move || {
            loop {
                let mut state = worker.state.lock().unwrap_or_else(|e| e.into_inner());
                while state.pending.is_none() && !state.stop {
                    state = worker.changed.wait(state).unwrap_or_else(|e| e.into_inner());
                }
                let Some((generation, data)) = state.pending.take() else { break; };
                drop(state);
                let started = Instant::now();
                let result = std::panic::catch_unwind(std::panic::AssertUnwindSafe(|| save(&data)))
                    .unwrap_or_else(|_| Err(std::io::Error::other("cache writer panicked")));
                match result {
                    Ok(()) => {
                        worker.failed.store(false, Ordering::Relaxed);
                        crate::startup::message(format_args!("Pipeline cache persisted path={label} generation={generation} bytes={} persist_ms={:.3}", data.len(), started.elapsed().as_secs_f64()*1000.0));
                    }
                    Err(error) => if !worker.failed.swap(true, Ordering::Relaxed) {
                        crate::startup::info(format_args!("Pipeline cache could not be saved: {error}; generation continues"));
                    },
                }
                let mut state = worker.state.lock().unwrap_or_else(|e| e.into_inner());
                state.finished = generation;
                worker.changed.notify_all();
            }
        })?;
        Ok(Self { shared, thread: Some(thread) })
    }
    pub fn enqueue(&self, bytes: Vec<u8>) {
        let mut state = self.shared.state.lock().unwrap_or_else(|e| e.into_inner());
        state.next += 1;
        state.pending = Some((state.next, bytes));
        self.shared.changed.notify_one();
    }
    pub fn failed(&self) -> bool { self.shared.failed.load(Ordering::Relaxed) }
    pub fn flush(&self) {
        let mut state = self.shared.state.lock().unwrap_or_else(|e| e.into_inner());
        let target = state.next;
        while state.finished < target {
            state = self.shared.changed.wait(state).unwrap_or_else(|e| e.into_inner());
        }
    }
}
impl Drop for Writer {
    fn drop(&mut self) {
        {
            let mut state = self.shared.state.lock().unwrap_or_else(|e| e.into_inner());
            state.stop = true;
            self.shared.changed.notify_one();
        }
        if let Some(thread) = self.thread.take() { let _ = thread.join(); }
    }
}
#[cfg(test)]
mod tests {
    use super::*;
    use std::sync::{Barrier, atomic::AtomicUsize};
    #[test]
    fn blocked_write_coalesces_pending_updates_flushes_latest_and_retries_failure() {
        let entered = Arc::new(Barrier::new(2)); let release = Arc::new(Barrier::new(2));
        let calls = Arc::new(AtomicUsize::new(0)); let values = Arc::new(Mutex::new(Vec::new()));
        let (a,b,c,v) = (entered.clone(),release.clone(),calls.clone(),values.clone());
        let writer = Writer::spawn("test".into(), move |data| {
            if c.fetch_add(1, Ordering::Relaxed) == 0 { a.wait(); b.wait(); return Err(std::io::Error::other("transient")); }
            v.lock().unwrap().push(data[0]); Ok(())
        }).unwrap();
        writer.enqueue(vec![0]); entered.wait();
        for value in 1..=100 { writer.enqueue(vec![value]); }
        assert_eq!(writer.shared.state.lock().unwrap().pending.as_ref().unwrap().1, [100]);
        release.wait(); writer.flush();
        assert_eq!(calls.load(Ordering::Relaxed), 2);
        assert_eq!(*values.lock().unwrap(), [100]); assert!(!writer.failed());
        writer.enqueue(vec![101]); drop(writer);
        assert_eq!(*values.lock().unwrap(), [100, 101]);
    }
    #[test]
    fn concurrent_producers_do_not_lose_the_final_snapshot() {
        let values = Arc::new(Mutex::new(Vec::new())); let output = values.clone();
        let writer = Writer::spawn("test".into(), move |data| { output.lock().unwrap().push(data[0]); Ok(()) }).unwrap();
        std::thread::scope(|scope| { for _ in 0..4 { let writer=&writer; scope.spawn(move || { for _ in 0..20 { writer.enqueue(vec![1]); } }); } });
        writer.enqueue(vec![42]); writer.flush();
        assert_eq!(values.lock().unwrap().last(), Some(&42));
    }
}
