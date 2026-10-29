//! Observe existing wgpu-hal profiler scopes, without patching the driver path.
use super::Stage;
use std::sync::LazyLock;
use tracing::{Dispatch, Subscriber, span::{Attributes, Id}};
use tracing_subscriber::{Layer, layer::Context, registry::LookupSpan, prelude::*};

fn relevant(name: &str) -> bool {
    matches!(name, "naga::spv::write_vec" | "naga::back::hlsl::write"
        | "compile_fxc" | "Fxc::D3DCompile" | "compile_dxc"
        | "vkCreateComputePipelines" | "ID3D12Device::CreateComputePipelineState")
}
struct DriverStages;
impl<S: Subscriber + for<'a> LookupSpan<'a>> Layer<S> for DriverStages {
    fn on_new_span(&self, attrs: &Attributes<'_>, id: &Id, ctx: Context<'_, S>) {
        if let Some(span) = ctx.span(id) {
            span.extensions_mut().insert(Stage::new(format!("driver phase {}", attrs.metadata().name())));
        }
    }
}
static DISPATCH: LazyLock<Dispatch> = LazyLock::new(|| Dispatch::new(
    tracing_subscriber::registry().with(DriverStages.with_filter(
        tracing_subscriber::filter::filter_fn(|metadata| relevant(metadata.name()))
    ))
));

pub(super) fn observe<T>(operation: impl FnOnce() -> T) -> T {
    // Thread-local only while inside the real synchronous pipeline operation.
    // Do not install/replace the host's global tracing subscriber.
    tracing::dispatcher::with_default(&DISPATCH, operation)
}

#[cfg(test)]
mod tests {
    use super::*;
    #[test]
    fn driver_spans_are_tracked_and_released_including_unwind() {
        observe(|| {
            let span = tracing::info_span!("Fxc::D3DCompile");
            let entered = span.enter();
            assert!(super::super::STATE.0.lock().unwrap().active.values()
                .any(|(label, _, _)| label.contains("driver phase Fxc::D3DCompile")));
            drop(entered);
        });
        let _ = std::panic::catch_unwind(|| observe(|| {
            let _span = tracing::info_span!("vkCreateComputePipelines").entered();
            panic!("diagnostic unwind");
        }));
        assert!(!super::super::STATE.0.lock().unwrap().active.values()
            .any(|(label, _, _)| label.contains("driver phase")));
        assert!(!relevant("unrelated frame event"));
    }
}
