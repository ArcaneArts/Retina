//! Configurable detailed diagnostics with bounded preparation progress. Logging never gates GPU operations.
mod cpu;
mod progress;
mod batch;
pub(crate) use batch::PipelineBatch;
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
    progress: progress::Progress,
}
type Shared = (Mutex<State>, Condvar);
static STATE: LazyLock<Arc<Shared>> =
    LazyLock::new(|| Arc::new((Mutex::new(State::default()), Condvar::new())));

static DEBUG: std::sync::atomic::AtomicBool = std::sync::atomic::AtomicBool::new(false);
#[derive(Clone, Copy, Debug, Default, Eq, Hash, PartialEq)]
pub(crate) enum InterpreterPolicy { #[default] Inherited, Fast, Optimized }
thread_local! { static INTERPRETER_POLICY: std::cell::Cell<InterpreterPolicy> = const { std::cell::Cell::new(InterpreterPolicy::Inherited) }; }
pub(crate) fn with_interpreter_policy<T>(policy: InterpreterPolicy, operation: impl FnOnce() -> T) -> T {
    struct Restore(InterpreterPolicy);
    impl Drop for Restore {
        fn drop(&mut self) { INTERPRETER_POLICY.set(self.0); }
    }
    let _restore = Restore(INTERPRETER_POLICY.replace(policy));
    operation()
}
pub(crate) fn interpreter_policy() -> InterpreterPolicy { INTERPRETER_POLICY.get() }
pub(crate) fn debug_enabled() -> bool {
    DEBUG.load(std::sync::atomic::Ordering::Relaxed)
}
#[unsafe(no_mangle)]
pub extern "C" fn retina_configure_debug(enabled: i32) {
    DEBUG.store(enabled != 0, std::sync::atomic::Ordering::Relaxed);
}
pub(crate) fn message(message: impl std::fmt::Display) {
    if debug_enabled() { info(message); }
}
pub(crate) fn info(message: impl std::fmt::Display) {
    // A closed launcher pipe must not panic or change generation behavior.
    let mut stderr = std::io::stderr().lock();
    let _ = writeln!(stderr, "[Retina startup] {message}");
    let _ = stderr.flush();
}

pub(crate) struct Stage {
    id: u64,
    label: String,
    started: Instant,
    pipeline: bool,
}
impl Stage {
    pub(crate) fn new(label: impl Into<String>) -> Self {
        Self::begin(label.into(), false)
    }
    fn begin(label: String, pipeline: bool) -> Self {
        let started = Instant::now();
        let thread = std::thread::current();
        let label = format!("{} [thread={}]", label, thread.name().unwrap_or("host"));
        let reserved = pipeline && batch::claim();
        let mut state = STATE.0.lock().unwrap_or_else(|e| e.into_inner());
        if pipeline { state.progress.begin(reserved, Instant::now()); }
        state.next += 1;
        let id = state.next;
        state.active.insert(id, (label.clone(), started, cpu::Probe::current()));
        let launch = !state.monitoring;
        state.monitoring = true;
        let progress = state.progress.report(Instant::now());
        let active = state.active.len();
        drop(state);
        message(format_args!("BEGIN #{id} {label} | {progress} stages_active={active}"));
        if launch {
            let shared = Arc::clone(&STATE);
            if let Err(error) = std::thread::Builder::new()
                .name("retina-startup-log".into())
                .spawn(move || monitor(shared, Duration::from_secs(10), info))
            {
                STATE.0.lock().unwrap_or_else(|e| e.into_inner()).monitoring = false;
                info(format_args!("Progress reporter unavailable: {error}"));
            }
        }
        Self { id, label, started, pipeline }
    }
}
impl Drop for Stage {
    fn drop(&mut self) {
        let mut state = STATE.0.lock().unwrap_or_else(|e| e.into_inner());
        let cpu = state.active.remove(&self.id).map(|(_, _, cpu)| cpu);
        let unwound = std::thread::panicking();
        if self.pipeline { state.progress.finish(unwound, Instant::now()); }
        if state.active.is_empty() {
            STATE.1.notify_all();
        }
        let progress = state.progress.report(Instant::now());
        let active = state.active.len();
        drop(state);
        let outcome = if unwound {
            "UNWIND"
        } else {
            "END"
        };
        message(format_args!(
            "{outcome} #{} {} elapsed={:.3}s{} | {progress} stages_active={active}",
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
            let progress = state.progress.report(Instant::now());
            let active = state.active.len();
            let lines: Vec<_> = state
                .active
                .iter()
                .map(|(id, (label, began, cpu))| {
                    format!(
                        "WAIT #{id} {label} elapsed={:.1}s{} (operation still running) | {progress} stages_active={active}",
                        began.elapsed().as_secs_f64(), cpu.report()
                    )
                })
                .collect();
            drop(state);
            for line in report_lines(debug_enabled() || cfg!(test), lines, &progress, active) {
                emit(line);
            }
            state = shared.0.lock().unwrap_or_else(|e| e.into_inner());
        }
    }
}

fn report_lines(debug: bool, details: Vec<String>, progress: &str, active: usize) -> Vec<String> {
    if debug { details } else {
        vec![format!("Preparing generation: {progress}; active operations={active}")]
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
    compute_pipeline(device, descriptor, false)
}

pub(crate) fn interpreter_pipeline(
    device: &wgpu::Device,
    descriptor: &wgpu::ComputePipelineDescriptor<'_>,
) -> wgpu::ComputePipeline {
    compute_pipeline(device, descriptor, true)
}

fn compute_pipeline(
    device: &wgpu::Device,
    descriptor: &wgpu::ComputePipelineDescriptor<'_>,
    interpreter: bool,
) -> wgpu::ComputePipeline {
    crate::compile_budget::with_driver_slot(|| compute_pipeline_admitted(device, descriptor, interpreter))
}

fn compute_pipeline_admitted(device: &wgpu::Device, descriptor: &wgpu::ComputePipelineDescriptor<'_>, interpreter: bool) -> wgpu::ComputePipeline {
    #[cfg(any(not(target_vendor = "apple"), test, feature = "vulkan-portability"))]
    use wgpu::hal::vulkan::diagnostics::{with_compute_compilation, ComputeCompilation};
    #[cfg(any(not(target_vendor = "apple"), test, feature = "vulkan-portability"))]
    let policy = if interpreter {
        ComputeCompilation::Interpreter { fast_compile: match INTERPRETER_POLICY.get() {
            InterpreterPolicy::Fast => Some(true),
            InterpreterPolicy::Optimized => Some(false),
            InterpreterPolicy::Inherited => match std::env::var("RETINA_VULKAN_FAST_COMPILE").as_deref() {
            Ok("0") => Some(false),
            Ok("1") => Some(true),
            _ => None,
        } } }
    } else { ComputeCompilation::Optimized };
    let stage = Stage::begin(format!(
        "compute pipeline {} entry={} interpreter={interpreter}",
        descriptor.label.unwrap_or("unnamed"),
        descriptor.entry_point.unwrap_or("auto")
    ), true);
    driver::observe(&stage, descriptor.entry_point.unwrap_or("auto"), || {
        #[cfg(any(not(target_vendor = "apple"), test, feature = "vulkan-portability"))]
        {
            wgpu::hal::vulkan::diagnostics::take_feedback();
            let result = with_compute_compilation(policy, || device.create_compute_pipeline(descriptor));
            if let Some(f) = wgpu::hal::vulkan::diagnostics::take_feedback() {
                message(format_args!("Vulkan pipeline feedback pipeline_id=#{} entry={} supported={} valid={} cache_supplied={} cache_hit={} driver_ms={:.3} policy={policy:?}",
                    stage.id, descriptor.entry_point.unwrap_or("auto"), f.supported, f.valid,
                    f.cache_supplied, if f.valid { if f.cache_hit { "yes" } else { "no" } } else { "unknown" },
                    f.duration_ns as f64 / 1e6));
            }
            result
        }
        #[cfg(all(target_vendor = "apple", not(test), not(feature = "vulkan-portability")))]
        { device.create_compute_pipeline(descriptor) }
    })
}

#[cfg(test)]
mod tests {
    use super::*;
    #[test]
    fn policy_scope_restores_after_nested_calls_and_unwind() {
        assert_eq!(INTERPRETER_POLICY.get(), InterpreterPolicy::Inherited);
        let _ = std::panic::catch_unwind(|| with_interpreter_policy(InterpreterPolicy::Fast, || {
            assert_eq!(INTERPRETER_POLICY.get(), InterpreterPolicy::Fast);
            with_interpreter_policy(InterpreterPolicy::Optimized, || assert_eq!(INTERPRETER_POLICY.get(), InterpreterPolicy::Optimized));
            assert_eq!(INTERPRETER_POLICY.get(), InterpreterPolicy::Fast);
            panic!("scope unwind");
        }));
        assert_eq!(INTERPRETER_POLICY.get(), InterpreterPolicy::Inherited);
    }
    #[test]
    fn quiet_progress_collapses_details_and_debug_preserves_them() {
        let details = vec!["WAIT driver phase".into(), "WAIT pipeline".into()];
        let quiet = report_lines(false, details.clone(), "pipelines=1/2 finished", 2);
        assert_eq!(quiet, vec!["Preparing generation: pipelines=1/2 finished; active operations=2"]);
        assert_eq!(report_lines(true, details.clone(), "unused", 2), details);
    }
    #[test]
    fn vulkan_policy_is_opt_in_and_scoped_to_interpreter_and_thread() {
        use wgpu::hal::vulkan::diagnostics::{compute_flags, with_compute_compilation, ComputeCompilation::*};
        assert_eq!(compute_flags(), 0);
        with_compute_compilation(Interpreter { fast_compile: None }, || {
            assert_eq!(compute_flags(), 0);
        });
        with_compute_compilation(Interpreter { fast_compile: Some(true) }, || {
            assert_eq!(compute_flags(), 1);
            with_compute_compilation(Optimized, || assert_eq!(compute_flags(), 0));
            assert_eq!(compute_flags(), 1);
            std::thread::spawn(|| assert_eq!(compute_flags(), 0)).join().unwrap();
            with_compute_compilation(Interpreter { fast_compile: Some(false) }, || assert_eq!(compute_flags(), 0));
            assert_eq!(compute_flags(), 1);
        });
        let _ = std::panic::catch_unwind(|| with_compute_compilation(Interpreter { fast_compile: Some(true) }, || panic!("scope test")));
        assert_eq!(compute_flags(), 0);
    }
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
        assert!(line.contains("pipelines=0/0 finished") && line.contains("stages_active=1"));
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
