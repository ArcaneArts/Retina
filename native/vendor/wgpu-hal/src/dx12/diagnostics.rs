//! Retina's opt-in observation of the actual compiler/driver inputs.
//! The observer cannot change inputs and an observer panic cannot abort GPU work.
use std::sync::OnceLock;
use alloc::{string::String, vec::Vec};

/// Synchronous events; borrowed bytes are valid only during the callback.
#[derive(Debug)]
pub enum Event<'a> {
    /// Serialized bytes used to create this pipeline's root signature.
    ComputeStart { root_signature: &'a [u8] },
    /// Exact source and UTF-16 arguments passed to IDxcCompiler3::Compile.
    DxcInput { source: &'a str, arguments: &'a [String] },
    /// Exact bytecode passed to CreateComputePipelineState (including cache hits).
    ComputeInput { bytecode: &'a [u8] },
    /// The real driver call returned; None means success.
    ComputeEnd { error: Option<&'a str> },
}

static OBSERVER: OnceLock<for<'a> fn(Event<'a>)> = OnceLock::new();

/// Install once, before creating pipeline layouts. Returns false if already set.
pub fn set_observer(observer: for<'a> fn(Event<'a>)) -> bool {
    OBSERVER.set(observer).is_ok()
}

pub(super) fn enabled() -> bool { OBSERVER.get().is_some() }

pub(super) fn emit(event: Event<'_>) {
    if let Some(observer) = OBSERVER.get() {
        let _ = std::panic::catch_unwind(|| observer(event));
    }
}

/// Compile captured HLSL directly with the same bundled DXC, without Naga.
pub fn compile_hlsl(source: &str, arguments: &[String]) -> Result<Vec<u8>, String> {
    profiling::scope!("compile_dxc");
    super::shader_compilation::replay_dxc(source, arguments)
}

/// Call the D3D12 driver directly with captured bytecode and root signature.
/// No dispatch occurs and no shader code is executed.
pub fn create_compute_pipeline(device: &super::Device, bytecode: &[u8], root_signature: &[u8]) -> Result<(), String> {
    use windows::Win32::Graphics::Direct3D12::*;
    let signature: ID3D12RootSignature = {
        profiling::scope!("ID3D12Device::CreateRootSignature");
        unsafe { device.raw.CreateRootSignature(0, root_signature) }
    }.map_err(|error| format!("CreateRootSignature: {error}"))?;
    let descriptor = D3D12_COMPUTE_PIPELINE_STATE_DESC {
        pRootSignature: std::mem::ManuallyDrop::new(Some(signature)),
        CS: D3D12_SHADER_BYTECODE { pShaderBytecode: bytecode.as_ptr().cast(), BytecodeLength: bytecode.len() },
        ..Default::default()
    };
    let result: Result<ID3D12PipelineState, _> = {
        profiling::scope!("ID3D12Device::CreateComputePipelineState");
        unsafe { device.raw.CreateComputePipelineState(&descriptor) }
    };
    // Release the pipeline before its root signature. D3D12 descriptors use
    // ManuallyDrop because the C ABI does not own interfaces.
    let result = result.map(drop).map_err(|error| format!("CreateComputePipelineState: {error}"));
    let mut descriptor = descriptor;
    unsafe { std::mem::ManuallyDrop::drop(&mut descriptor.pRootSignature); }
    result
}
