//! Always-on, bounded startup diagnostics. Logging never gates GPU operations.
mod cpu;
pub(crate) mod capture;
pub(crate) mod driver;
use std::{
    collections::BTreeMap,
    io::Write,
    sync::{Arc, Condvar, LazyLock, Mutex},
    time::{Duration, Instant},
};

#[derive(Default)]
struct State {
    next: u64,
    active: BTreeMap<u64, (String, Instant, cpu::Probe)>,
    monitoring: bool,
}
type Shared = (Mutex<State>, Condvar);
static STATE: LazyLock<Arc<Shared>> =
    LazyLock::new(|| Arc::new((Mutex::new(State::default()), Condvar::new())));

pub(crate) fn message(message: impl std::fmt::Display) {
    // A closed launcher pipe must not panic or change generation behavior.
    let mut stderr = std::io::stderr().lock();
    let _ = writeln!(stderr, "[Retina startup] {message}");
    let _ = stderr.flush();
}

pub(crate) struct Stage {
    id: u64,
    label: String,
    started: Instant,
}
impl Stage {
    pub(crate) fn new(label: impl Into<String>) -> Self {
        let label = label.into();
        let started = Instant::now();
        let thread = std::thread::current();
        let label = format!("{} [thread={}]", label, thread.name().unwrap_or("host"));
        let mut state = STATE.0.lock().unwrap_or_else(|e| e.into_inner());
        state.next += 1;
        let id = state.next;
        state.active.insert(id, (label.clone(), started, cpu::Probe::current()));
        let launch = !state.monitoring;
        state.monitoring = true;
        drop(state);
        message(format_args!("BEGIN #{id} {label}"));
        if launch {
            let shared = Arc::clone(&STATE);
            if let Err(error) = std::thread::Builder::new()
                .name("retina-startup-log".into())
                .spawn(move || monitor(shared, Duration::from_secs(10), message))
            {
                STATE.0.lock().unwrap_or_else(|e| e.into_inner()).monitoring = false;
                message(format_args!("Progress reporter unavailable: {error}"));
            }
        }
        Self { id, label, started }
    }
}
impl Drop for Stage {
    fn drop(&mut self) {
        let mut state = STATE.0.lock().unwrap_or_else(|e| e.into_inner());
        let cpu = state.active.remove(&self.id).map(|(_, _, cpu)| cpu);
        if state.active.is_empty() {
            STATE.1.notify_all();
        }
        drop(state);
        let outcome = if std::thread::panicking() {
            "UNWIND"
        } else {
            "END"
        };
        message(format_args!(
            "{outcome} #{} {} elapsed={:.3}s{}",
            self.id,
            self.label,
            self.started.elapsed().as_secs_f64(),
            cpu.map(|cpu| cpu.report()).unwrap_or_default()
        ));
    }
}
fn monitor(shared: Arc<Shared>, interval: Duration, mut emit: impl FnMut(String)) {
    let mut state = shared.0.lock().unwrap_or_else(|e| e.into_inner());
    loop {
        if state.active.is_empty() {
            state.monitoring = false;
            return;
        }
        let (next, timeout) = shared
            .1
            .wait_timeout(state, interval)
            .unwrap_or_else(|e| e.into_inner());
        state = next;
        if timeout.timed_out() {
            let lines: Vec<_> = state
                .active
                .iter()
                .map(|(id, (label, began, cpu))| {
                    format!(
                        "WAIT #{id} {label} elapsed={:.1}s{} (operation still running)",
                        began.elapsed().as_secs_f64(), cpu.report()
                    )
                })
                .collect();
            drop(state);
            for line in lines {
                emit(line);
            }
            state = shared.0.lock().unwrap_or_else(|e| e.into_inner());
        }
    }
}

pub(crate) fn shader(
    device: &wgpu::Device,
    descriptor: wgpu::ShaderModuleDescriptor<'_>,
) -> wgpu::ShaderModule {
    let _stage = Stage::new(format!(
        "shader module {}",
        descriptor.label.unwrap_or("unnamed")
    ));
    device.create_shader_module(descriptor)
}
pub(crate) fn pipeline(
    device: &wgpu::Device,
    descriptor: &wgpu::ComputePipelineDescriptor<'_>,
) -> wgpu::ComputePipeline {
    let stage = Stage::new(format!(
        "compute pipeline {} entry={}",
        descriptor.label.unwrap_or("unnamed"),
        descriptor.entry_point.unwrap_or("auto")
    ));
    driver::observe(&stage, descriptor.entry_point.unwrap_or("auto"), || device.create_compute_pipeline(descriptor))
}

#[cfg(test)]
mod tests {
    use super::*;
    #[test]
    fn stalled_operation_reports_and_monitor_exits_after_completion() {
        let shared = Arc::new((Mutex::new(State::default()), Condvar::new()));
        shared
            .0
            .lock()
            .unwrap()
            .active
            .insert(7, ("blocked driver call".into(), Instant::now(), cpu::Probe::current()));
        let (send, receive) = std::sync::mpsc::channel();
        let worker_state = shared.clone();
        let worker = std::thread::spawn(move || {
            monitor(worker_state, Duration::from_millis(5), |line| {
                let _ = send.send(line);
            })
        });
        let line = receive.recv_timeout(Duration::from_secs(5)).unwrap();
        assert!(line.contains("WAIT #7 blocked driver call") && line.contains("elapsed="));
        shared.0.lock().unwrap().active.clear();
        shared.1.notify_all();
        worker.join().unwrap();
        assert!(!shared.0.lock().unwrap().monitoring);
    }
    #[test]
    fn nested_stages_release_state_even_during_unwind() {
        let outer = Stage::new("test outer");
        let id = outer.id;
        let _ = std::panic::catch_unwind(|| {
            let _inner = Stage::new("test inner");
            panic!("probe");
        });
        assert!(STATE.0.lock().unwrap().active.contains_key(&id));
        drop(outer);
        assert!(!STATE.0.lock().unwrap().active.contains_key(&id));
    }
}
