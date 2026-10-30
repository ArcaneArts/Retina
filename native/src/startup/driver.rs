//! Observe existing wgpu-hal profiler scopes, without patching the driver path.
use super::Stage;
use std::sync::LazyLock;
use std::cell::RefCell;
use tracing::{Dispatch, Subscriber, span::{Attributes, Id}};
use tracing_subscriber::{Layer, layer::Context, registry::LookupSpan, prelude::*};

fn relevant(name: &str) -> bool {
    matches!(name, "naga::spv::write_vec" | "naga::back::hlsl::write"
        | "compile_fxc" | "Fxc::D3DCompile" | "compile_dxc"
        | "vkCreateComputePipelines" | "ID3D12Device::CreateComputePipelineState"
        | "ID3D12Device::CreateRootSignature")
}
struct DriverStages;
thread_local! {
    static PIPELINE: RefCell<Option<(u64, String)>> = const { RefCell::new(None) };
}
struct PipelineContext(Option<(u64, String)>);
impl Drop for PipelineContext {
    fn drop(&mut self) {
        PIPELINE.with(|context| *context.borrow_mut() = self.0.take());
    }
}
impl<S: Subscriber + for<'a> LookupSpan<'a>> Layer<S> for DriverStages {
    fn on_new_span(&self, attrs: &Attributes<'_>, id: &Id, ctx: Context<'_, S>) {
        if let Some(span) = ctx.span(id) {
            let pipeline = PIPELINE.with_borrow(|context| context.as_ref()
                .map(|(id, label)| format!(" pipeline_id=#{id} entry={label}")).unwrap_or_default());
            span.extensions_mut().insert(Stage::new(format!("driver phase {}{pipeline}", attrs.metadata().name())));
        }
    }
}
static DISPATCH: LazyLock<Dispatch> = LazyLock::new(|| Dispatch::new(
    tracing_subscriber::registry().with(DriverStages.with_filter(
        tracing_subscriber::filter::filter_fn(|metadata| relevant(metadata.name()))
    ))
));

#[cfg(all(windows, target_arch = "x86_64"))]
pub(super) fn pipeline_context() -> Option<(u64, String)> {
    PIPELINE.with_borrow(Clone::clone)
}

pub(crate) fn observe<T>(stage: &Stage, entry: &str, operation: impl FnOnce() -> T) -> T {
    let _context = PipelineContext(PIPELINE.with(|context|
        context.replace(Some((stage.id, entry.to_owned())))));
    // Thread-local only while inside the real synchronous pipeline operation.
    // Do not install/replace the host's global tracing subscriber.
    tracing::dispatcher::with_default(&DISPATCH, operation)
}

#[cfg(test)]
mod tests {
    use super::*;
    #[test]
    fn driver_spans_are_tracked_and_released_including_unwind() {
        let pipeline = Stage::new("test pipeline");
        observe(&pipeline, "test_entry", || {
            let span = tracing::info_span!("Fxc::D3DCompile");
            let entered = span.enter();
            assert!(super::super::STATE.0.lock().unwrap().active.values()
                .any(|(label, _, _)| label.contains("driver phase Fxc::D3DCompile")
                    && label.contains(&format!("pipeline_id=#{} entry=test_entry", pipeline.id))));
            drop(entered);
        });
        let _ = std::panic::catch_unwind(|| observe(&pipeline, "unwind_entry", || {
            let _span = tracing::info_span!("vkCreateComputePipelines").entered();
            panic!("diagnostic unwind");
        }));
        let context = format!("pipeline_id=#{} ", pipeline.id);
        assert!(!super::super::STATE.0.lock().unwrap().active.values()
            .any(|(label, _, _)| label.contains("driver phase") && label.contains(&context)));
        assert!(!relevant("unrelated frame event"));
        assert!(PIPELINE.with_borrow(|context| context.is_none()));
    }
}
