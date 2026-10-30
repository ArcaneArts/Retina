//! Host-side compiler probe. This compiles shader code, not a GPU pipeline.
use std::{ffi::CString, path::Path, time::Instant};
use mach_dxcompiler_rs::cbindings::*;
#[path = "../../../vendor/wgpu-hal/src/auxil/retina_functions.rs"]
mod boundaries;

fn main() {
    let args: Vec<_> = std::env::args().collect();
    let path = Path::new(args.get(1).expect("usage: compiler-audit shader.wgsl entry"));
    let entry = args.get(2).expect("missing compute entry point");
    let source = std::fs::read_to_string(path).unwrap();
    let module = naga::front::wgsl::parse_str(&source).unwrap_or_else(|e| panic!("{}", e.emit_to_string(&source)));
    let info = naga::valid::Validator::new(naga::valid::ValidationFlags::all(), naga::valid::Capabilities::all()).validate(&module).unwrap();
    // Binding numbers are synthetic: this probe does not create a root
    // signature or GPU pipeline. Compiler / language versions match Retina.
    let options = naga::back::hlsl::Options {
        shader_model: naga::back::hlsl::ShaderModel::V6_7,
        immediates_target: Some(naga::back::hlsl::BindTarget { space: 0, register: 10, ..Default::default() }),
        ..Default::default()
    };
    let pipeline = naga::back::hlsl::PipelineOptions { entry_point: Some((naga::ShaderStage::Compute, entry.clone())) };
    let mut hlsl = String::new();
    let reflection = naga::back::hlsl::Writer::new(&mut hlsl, &options, &pipeline).write(&module, &info, None).unwrap();
    let compiled_entry = reflection.entry_point_names[0].as_ref().unwrap();
    let outlined_hlsl = boundaries::hlsl(&mut hlsl);
    std::fs::write(path.with_extension("hlsl"), &hlsl).unwrap();
    let mut options = naga::back::spv::Options::default();
    if boundaries::has_bodies(&module) { options.flags.insert(naga::back::spv::WriterFlags::DEBUG); }
    let mut spv = naga::back::spv::write_vec(&module, &info, &options, Some(&naga::back::spv::PipelineOptions { shader_stage: naga::ShaderStage::Compute, entry_point: entry.clone() })).unwrap();
    let outlined_spirv = boundaries::spirv(&mut spv);
    // HLSL may retain definitions that SPIR-V drops as unreachable from this
    // entry. Report each backend's actual count without rejecting valid code.
    std::fs::write(path.with_extension("spv"), spv.iter().flat_map(|x| x.to_le_bytes()).collect::<Vec<_>>()).unwrap();
    let strings: Vec<_> = ["-E", compiled_entry, "-T", "cs_6_7", "-HV", "2018", "-no-warnings", "-Ges"].iter().map(|x| CString::new(*x).unwrap()).collect();
    let raw: Vec<_> = strings.iter().map(|s| s.as_ptr()).collect();
    let start = Instant::now();
    unsafe {
        let compiler = machDxcInit();
        assert!(!compiler.is_null());
        let mut options = MachDxcCompileOptions { code: hlsl.as_ptr().cast(), code_len: hlsl.len(), args: raw.as_ptr(), args_len: raw.len(), include_callbacks: std::ptr::null_mut() };
        let result = machDxcCompile(compiler, &mut options);
        let error = machDxcCompileResultGetError(result);
        if !error.is_null() { panic!("{}", std::ffi::CStr::from_ptr(machDxcCompileErrorGetString(error)).to_string_lossy()); }
        let object = machDxcCompileResultGetObject(result);
        assert!(!object.is_null());
        let bytes = std::slice::from_raw_parts(machDxcCompileObjectGetBytes(object).cast::<u8>(), machDxcCompileObjectGetBytesLength(object));
        std::fs::write(path.with_extension("dxil"), bytes).unwrap();
        println!("AUDIT path={} source_bytes={} hlsl_bytes={} spv_words={} dxil_bytes={} outlined_hlsl={} outlined_spirv={} dxc_seconds={:.3}", path.display(), source.len(), hlsl.len(), spv.len(), bytes.len(), outlined_hlsl, outlined_spirv, start.elapsed().as_secs_f64());
        machDxcCompileObjectDeinit(object);
        machDxcCompileResultDeinit(result);
        machDxcDeinit(compiler);
    }
}
