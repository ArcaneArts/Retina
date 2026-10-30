//! Retina's scoped compute compilation policy and exact SPIR-V observer.
use core::cell::Cell;
use std::sync::OnceLock;

/// Optimization is optional for the startup interpreter, which is replaced by
/// an optimized, world-specific pipeline once background compilation finishes.
#[derive(Clone, Copy, Debug, Default, PartialEq, Eq)]
pub enum ComputeCompilation {
    /// Preserve the upstream optimized compilation path.
    #[default]
    Optimized,
    /// `None` retains normal optimization, matching other backends' defaults.
    Interpreter { fast_compile: Option<bool> },
}

std::thread_local! {
    static COMPILATION: Cell<ComputeCompilation> = const { Cell::new(ComputeCompilation::Optimized) };
}

/// Scope the policy to one synchronous pipeline creation, including unwinding.
pub fn with_compute_compilation<T>(policy: ComputeCompilation, operation: impl FnOnce() -> T) -> T {
    struct Restore(ComputeCompilation);
    impl Drop for Restore {
        fn drop(&mut self) {
            COMPILATION.set(self.0);
        }
    }
    let _restore = Restore(COMPILATION.replace(policy));
    operation()
}

/// Effective VkPipelineCreateFlags for this synchronous pipeline call.
pub fn compute_flags() -> u32 {
    let fast = match COMPILATION.get() {
        ComputeCompilation::Optimized => false,
        ComputeCompilation::Interpreter { fast_compile } => {
            fast_compile.unwrap_or(false)
        }
    };
    if fast { ash::vk::PipelineCreateFlags::DISABLE_OPTIMIZATION.as_raw() } else { 0 }
}

/// Inputs are borrowed from the actual HAL compilation path, never regenerated.
#[derive(Clone, Copy, Debug)]
pub enum Event<'a> {
    Spirv { words: &'a [u32], entry: &'a str },
    ComputeStart { flags: u32 },
    ComputeEnd { error: Option<&'a str> },
}
static OBSERVER: OnceLock<for<'a> fn(Event<'a>)> = OnceLock::new();

/// Install one observer before creating pipelines. Observer failure cannot
/// change compilation inputs or prevent the real Vulkan operation.
pub fn set_observer(observer: for<'a> fn(Event<'a>)) -> bool {
    OBSERVER.set(observer).is_ok()
}
pub(super) fn emit(event: Event<'_>) {
    if let Some(observer) = OBSERVER.get() {
        let _ = std::panic::catch_unwind(|| observer(event));
    }
}
