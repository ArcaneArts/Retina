//! Selectable processing of the actual Naga module after override substitution,
//! before Vulkan module creation. Metal/DX12 do not enter this path.
use std::sync::LazyLock;

#[derive(Clone, Copy, Debug, Eq, PartialEq)]
enum Mode { Naga, Tools }
fn parse(value: &str) -> Option<Mode> {
    match value { "naga" => Some(Mode::Naga), "tools" => Some(Mode::Tools), _ => None }
}
static MODE: LazyLock<Mode> = LazyLock::new(|| {
    let value = std::env::var("RETINA_VULKAN_SPIRV").unwrap_or_else(|_| "naga".into());
    parse(&value).unwrap_or_else(|| {
        crate::startup::info(format_args!("Unknown Vulkan SPIR-V mode {value:?}; keeping Naga")); Mode::Naga
    })
});
pub(crate) fn identity() -> &'static str {
    match *MODE {
        Mode::Naga => "naga",
        Mode::Tools if cfg!(feature = "spirv-tools") => "spirv-tools-0.13.3-safe-v1",
        Mode::Tools => "spirv-tools-unavailable-v1",
    }
}
pub(crate) fn install(info: &wgpu::AdapterInfo) {
    if info.backend != wgpu::Backend::Vulkan { return; }
    crate::startup::message(format_args!("Vulkan compiler input: {}", identity()));
    #[cfg(any(not(target_vendor = "apple"), test, feature = "vulkan-portability"))]
    {
        static INSTALLED: LazyLock<bool> = LazyLock::new(|| wgpu::hal::vulkan::diagnostics::set_spirv_transform(transform));
        if !*INSTALLED { crate::startup::info("Vulkan compiler input hook already owned; retaining the installed path"); }
    }
}

#[cfg(any(not(target_vendor = "apple"), test, feature = "vulkan-portability"))]
fn transform(words: &[u32], entry: &str) -> Option<Vec<u32>> {
    if *MODE == Mode::Naga { return None; }
    let _stage = crate::startup::Stage::new(format!("SPIRV-Tools processing entry={entry}"));
    let start = std::time::Instant::now();
    let result = optimize(words);
    if crate::startup::debug_enabled() {
        use sha2::{Digest, Sha256};
        let output = result.as_deref().unwrap_or(words);
        crate::startup::message(format_args!("Compiler input {}", serde_json::json!({
            "schema_version": 1, "entry": entry, "policy": identity(), "applied": result.is_ok(),
            "processing_ms": start.elapsed().as_secs_f64()*1000.0,
            "input": stats(words), "output": stats(output),
            "input_sha256": format!("{:x}", Sha256::digest(bytemuck::cast_slice::<u32,u8>(words))),
            "output_sha256": format!("{:x}", Sha256::digest(bytemuck::cast_slice::<u32,u8>(output))),
            "fallback_reason": result.as_ref().err(),
            "scope": "Compiler input preparation only; driver creation and GPU execution are measured separately."
        })));
    }
    match result {
        Ok(words) => Some(words),
        Err(error) => {
            use std::sync::atomic::{AtomicBool, Ordering};
            static REPORTED: AtomicBool = AtomicBool::new(false);
            if !REPORTED.swap(true, Ordering::Relaxed) {
                crate::startup::info(format_args!("SPIRV-Tools processing unavailable: {error}; compiling the original Naga module"));
            }
            None
        }
    }
}

#[cfg(feature = "spirv-tools")]
#[cfg(any(not(target_vendor = "apple"), test, feature = "vulkan-portability"))]
fn optimize(words: &[u32]) -> Result<Vec<u32>, String> {
    use spirv_tools::{opt::{Optimizer, Passes}, val::Validator};
    let mut optimizer = spirv_tools::opt::create(Some(spirv_tools::TargetEnv::Universal_1_6));
    // No generic preset, inlining, loop expansion, floating algebra, precision
    // relaxation, interface removal or bounds-check removal. Overrides have
    // already been resolved by wgpu before this hook receives the module.
    for pass in [Passes::FreezeSpecConstantValue, Passes::FoldSpecConstantOpAndComposite,
        Passes::CombineAccessChains, Passes::LocalAccessChainConvert,
        Passes::LocalSingleBlockLoadStoreElim, Passes::LocalSingleStoreElim,
        Passes::DeadBranchElim, Passes::BlockMerge, Passes::EliminateDeadFunctions,
        Passes::EliminateDeadConstant, Passes::CompactIds] {
        optimizer.register_pass(pass);
    }
    let mut messages = Vec::new();
    let binary = optimizer.optimize(words, &mut |message: spirv_tools::error::Message| {
        if messages.len() < 8 { messages.push(message.message); }
    }, Some(spirv_tools::opt::Options {
        preserve_bindings: true, validator_options: Some(Default::default()), ..Default::default()
    })).map_err(|error| format!("{error}: {}", messages.join("; ")))?;
    let output = binary.as_words();
    spirv_tools::val::create(Some(spirv_tools::TargetEnv::Universal_1_6))
        .validate(output, None).map_err(|error| format!("processed module: {error}"))?;
    Ok(output.to_vec())
}
#[cfg(not(feature = "spirv-tools"))]
#[cfg(any(not(target_vendor = "apple"), test, feature = "vulkan-portability"))]
fn optimize(_: &[u32]) -> Result<Vec<u32>, String> { Err("native build excludes the spirv-tools feature".into()) }

#[cfg(any(not(target_vendor = "apple"), test, feature = "vulkan-portability"))]
#[derive(Default, Debug, serde::Serialize)]
struct Stats { words: usize, functions: u32, no_inline_functions: u32, calls: u32, float_arithmetic: u32 }
#[cfg(any(not(target_vendor = "apple"), test, feature = "vulkan-portability"))]
fn stats(words: &[u32]) -> Stats {
    let mut stats = Stats { words: words.len(), ..Default::default() };
    let mut at = 5;
    while let Some(&header) = words.get(at) {
        let count = (header >> 16) as usize;
        if count == 0 || at + count > words.len() { break; }
        match header & 0xffff {
            54 => { stats.functions += 1; if count > 3 && words[at+3] & 2 != 0 { stats.no_inline_functions += 1; } }
            57 => stats.calls += 1,
            129 | 131 | 133 | 136 | 140 | 141 => stats.float_arithmetic += 1,
            _ => (),
        }
        at += count;
    }
    stats
}

#[cfg(test)]
mod tests {
    use super::*;
    #[test]
    fn selection_is_explicit_and_invalid_input_is_recoverable() {
        assert_eq!(parse("naga"), Some(Mode::Naga)); assert_eq!(parse("tools"), Some(Mode::Tools));
        assert_eq!(parse("arbitrary-preset"), None);
        assert!(optimize(&[0, 1, 2]).is_err());
        assert_eq!(stats(&[0; 5]).functions, 0);
    }

    #[test]
    #[cfg(feature = "spirv-tools")]
    fn curated_processing_retains_bindings_calls_float_ops_and_noinline() {
        use wgpu::naga;
        let module = naga::front::wgsl::parse_str(r#"
@group(0) @binding(3) var<storage,read> input:array<f32>;
@group(0) @binding(7) var<storage,read_write> output:array<f32>;
fn retained(value:f32)->f32 { var copy=value; return copy*copy+1.0; }
@compute @workgroup_size(64) fn main(@builtin(global_invocation_id) id:vec3<u32>) {
    if id.x<arrayLength(&input) && id.x<arrayLength(&output) { output[id.x]=retained(input[id.x]); }
}
"#).unwrap();
        let info = naga::valid::Validator::new(naga::valid::ValidationFlags::all(), naga::valid::Capabilities::all()).validate(&module).unwrap();
        let mut input = naga::back::spv::write_vec(&module, &info, &naga::back::spv::Options {
            flags: naga::back::spv::WriterFlags::DEBUG, ..Default::default()
        }, Some(&naga::back::spv::PipelineOptions { shader_stage: naga::ShaderStage::Compute, entry_point: "main".into() })).unwrap();
        fn instructions(words: &[u32]) -> Vec<(usize, u32)> {
            let mut result=Vec::new(); let mut at=5;
            while at<words.len() { let count=(words[at]>>16) as usize; assert!(count>0); result.push((at,words[at]&0xffff)); at+=count; }
            result
        }
        let ids=instructions(&input).into_iter().filter(|(_,op)| *op==5).filter_map(|(at,_)| {
            let end=at+(input[at]>>16) as usize;
            let name=String::from_utf8_lossy(bytemuck::cast_slice::<u32,u8>(&input[at+2..end]));
            name.contains("retained").then_some(input[at+1])
        }).collect::<Vec<_>>();
        assert_eq!(ids.len(),1);
        for (at,op) in instructions(&input) {
            if op==54 && ids.contains(&input[at+2]) { input[at+3]=(input[at+3]&!1)|2; }
        }
        let output=optimize(&input).unwrap(); let before=stats(&input); let after=stats(&output);
        assert_eq!(before.no_inline_functions,1); assert_eq!(after.no_inline_functions,1);
        assert_eq!(before.calls,after.calls); assert_eq!(before.float_arithmetic,after.float_arithmetic);
        fn bindings(words:&[u32])->Vec<(u32,u32)> {
            let mut groups=std::collections::BTreeMap::<u32,(Option<u32>,Option<u32>)>::new();
            for (at,op) in instructions(words) {
                if op==71 { match words[at+2] { 33=>groups.entry(words[at+1]).or_default().1=Some(words[at+3]),
                    34=>groups.entry(words[at+1]).or_default().0=Some(words[at+3]), _=>() } }
            }
            let mut values=groups.into_values().map(|(group,binding)| (group.unwrap(),binding.unwrap())).collect::<Vec<_>>();
            values.sort_unstable(); values
        }
        assert_eq!(bindings(&input),[(0,3),(0,7)]); assert_eq!(bindings(&input),bindings(&output));
        // Both dynamic array bounds remain in the processed program.
        assert!(instructions(&output).iter().filter(|(_,op)| *op==68).count()>=2);
    }
}
