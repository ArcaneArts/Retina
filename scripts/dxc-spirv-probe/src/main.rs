//! Compiler capability probe only. No GPU, window or platform device is opened.
//! Uses the exact pinned static DXC package used by the Windows native library.
use mach_dxcompiler_rs::cbindings::*;
use std::{ffi::CString, ptr};

struct Compiler(MachDxcCompiler);
impl Drop for Compiler { fn drop(&mut self) { unsafe { machDxcDeinit(self.0); } } }
struct ResultHandle(MachDxcCompileResult);
impl Drop for ResultHandle { fn drop(&mut self) { unsafe { machDxcCompileResultDeinit(self.0); } } }
const SOURCE: &str = r#"
RWStructuredBuffer<float> output : register(u0);
[numthreads(64,1,1)] void main(uint3 id:SV_DispatchThreadID) { output[id.x]=float(id.x)*0.25f; }
"#;
impl Compiler {
    fn compile(&self, spirv: bool) -> serde_json::Value {
        let mut args = vec!["-E", "main", "-T", "cs_6_0"];
        if spirv { args.extend(["-spirv", "-fspv-target-env=vulkan1.1"]); }
        let args = args.iter().map(|s| CString::new(*s).unwrap()).collect::<Vec<_>>();
        let pointers = args.iter().map(|s| s.as_ptr()).collect::<Vec<_>>();
        let mut options = MachDxcCompileOptions { code: SOURCE.as_ptr().cast(), code_len: SOURCE.len(),
            args: pointers.as_ptr(), args_len: pointers.len(), include_callbacks: ptr::null_mut() };
        let result = ResultHandle(unsafe { machDxcCompile(self.0, &mut options) });
        assert!(!result.0.is_null(), "DXC returned a null result");
        let error = unsafe { machDxcCompileResultGetError(result.0) };
        let diagnostic = if error.is_null() { None } else {
            let size = unsafe { machDxcCompileErrorGetStringLength(error) };
            let bytes = unsafe { std::slice::from_raw_parts(machDxcCompileErrorGetString(error).cast::<u8>(), size) };
            let message = String::from_utf8_lossy(bytes).into_owned();
            unsafe { machDxcCompileErrorDeinit(error); }
            Some(message)
        };
        let object = unsafe { machDxcCompileResultGetObject(result.0) };
        let (bytes, magic) = if object.is_null() { (0, Vec::new()) } else {
            let size = unsafe { machDxcCompileObjectGetBytesLength(object) };
            let bytes = unsafe { std::slice::from_raw_parts(machDxcCompileObjectGetBytes(object).cast::<u8>(), size) };
            let magic = bytes.iter().take(4).copied().collect();
            unsafe { machDxcCompileObjectDeinit(object); }
            (size, magic)
        };
        serde_json::json!({"target": if spirv {"spirv"} else {"dxil"}, "object_bytes": bytes,
            "object_magic": magic, "diagnostic": diagnostic,
            "arguments": args.iter().map(|s| s.to_str().unwrap()).collect::<Vec<_>>()})
    }
}
fn main() {
    let compiler = Compiler(unsafe { machDxcInit() });
    assert!(!compiler.0.is_null(), "DXC initialization failed");
    let result = serde_json::json!({"schema_version":1,
        "compiler":"mach-dxcompiler-rs 0.1.7+2026.10.26-141dd7f.1",
        "source": SOURCE, "gpu_opened":false, "results":[compiler.compile(false),compiler.compile(true)]});
    println!("{}",serde_json::to_string_pretty(&result).unwrap());
}
