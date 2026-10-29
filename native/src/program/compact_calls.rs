//! Bound static call-site multiplication before a driver inlines the interpreter.
//! The original WGSL remains the independent reference for output parity tests.
pub(super) fn function<'a>(source: &'a str, name: &str) -> &'a str {
    let start = source.find(&format!("fn {name}(")).expect("known sampling helper");
    let mut depth = 0;
    for (offset, ch) in source[start..].char_indices() {
        if ch == '{' { depth += 1; }
        if ch == '}' {
            depth -= 1;
            if depth == 0 { return &source[start..start + offset + 1]; }
        }
    }
    unreachable!("unterminated sampling helper")
}

pub(super) fn source(mut source: String, depth: usize) -> String {
    let reference = include_str!("../program.wgsl");
    let compact = include_str!("compact_calls.wgsl");
    for name in ["density_layer", "registered_density", "density_surface_height"] {
        source = source.replace(function(reference, name), function(compact, name));
    }
    for level in 0..depth {
        let name = format!("run_program_field_{level}");
        let original = function(&source, &name).to_owned();
        let body = format!(r#"fn {name}(field:u32,point:vec3<f32>,request:Request,context:vec4<f32>)->f32{{
let at=bytecode[12]+field*4u;let program=bytecode[at];
let size=vec3<f32>(f32(bytecode[at+1u]),f32(bytecode[at+2u]),f32(bytecode[at+1u]));
let lower=floor(point/size)*size;let alpha=(point-lower)/size;
let aligned=all(alpha==vec3<f32>(0.0));
var corners:array<f32,8>;
for(var corner=0u;corner<8u;corner++){{
    let upper=vec3<u32>(corner&1u,(corner>>1u)&1u,(corner>>2u)&1u);
    if any((upper!=vec3<u32>(0u)) & (alpha==vec3<f32>(0.0))){{continue;}}
    let point=select(lower+vec3<f32>(upper)*size,point,aligned);
    let cached=interpolation_cached_corner(field,point,request);
    if cached.y!=0.0{{corners[corner]=cached.x;}}
    else{{corners[corner]=run_program_level_{level}(program,point,request,context)[0];}}
    // Preserve the direct return on grid nodes, including signed zero/NaN.
    if aligned{{return corners[0];}}
}}
return interpolation_mix(corners,alpha);
}}"#);
        source = source.replace(&original, &body);
    }
    source
}

#[cfg(test)]
mod tests {
    use wgpu::naga::{self, Statement};
    fn calls(block: &naga::Block, visit: &mut impl FnMut(naga::Handle<naga::Function>)) {
        for statement in block {
            match statement {
                Statement::Call { function, .. } => visit(*function),
                Statement::Block(block) => calls(block, visit),
                Statement::If { accept, reject, .. } => { calls(accept, visit); calls(reject, visit); }
                Statement::Switch { cases, .. } => for case in cases { calls(&case.body, visit); },
                Statement::Loop { body, continuing, .. } => { calls(body, visit); calls(continuing, visit); }
                _ => {}
            }
        }
    }
    fn expanded(module: &naga::Module, body: &naga::Block) -> usize {
        let mut count = 0;
        calls(body, &mut |handle| {
            let f = &module.functions[handle];
            let interpreter = f.name.as_deref().is_some_and(|n| n == "run_program" || n.starts_with("run_program_level_"));
            count += usize::from(interpreter) + expanded(module, &f.body);
        });
        count
    }
    fn module(program: &str, cave: bool) -> naga::Module {
        let source = if cave {
            format!("{}\n{}\n{program}\n{}\n{}", include_str!("../caves.wgsl"), include_str!("../climate.wgsl"), include_str!("../aquifers.wgsl"), include_str!("../materials.wgsl"))
        } else {
            format!("{}\n{}\n{program}\n{}", include_str!("../simplex.wgsl"), include_str!("../climate.wgsl"), include_str!("../noise3.wgsl"))
        };
        let module = naga::front::wgsl::parse_str(&source).unwrap_or_else(|e| panic!("{}", e.emit_to_string(&source)));
        naga::valid::Validator::new(naga::valid::ValidationFlags::all(), naga::valid::Capabilities::all()).validate(&module).unwrap();
        module
    }
    #[test]
    fn shared_sampling_reduces_inlined_interpreter_copies_and_validates_all_variants() {
        for (capacity, depth, composition) in [(64, 0, false), (64, 1, false), (64, 2, false), (1024, 1, false), (64, 1, true)] {
            let reference = super::super::interpreter_reference_source(capacity, depth, composition);
            let actual = super::super::interpreter_source_density(capacity, depth, composition);
            for cave in [false, true] {
                let old = module(&reference, cave);
                let new = module(&actual, cave);
                for (old_entry, new_entry) in old.entry_points.iter().zip(&new.entry_points) {
                    assert_eq!(old_entry.name, new_entry.name);
                    let before = expanded(&old, &old_entry.function.body);
                    let after = expanded(&new, &new_entry.function.body);
                    assert!(after <= before, "{}: {before} -> {after}", new_entry.name);
                    if new_entry.name == "surface_columns" {
                        assert!(after * 3 < before, "surface call graph must shrink materially: {before} -> {after}");
                        eprintln!("INTERPRETER_CALL_GRAPH capacity={capacity} depth={depth} composition={composition} entry={} before={before} after={after}", new_entry.name);
                    }
                }
            }
        }
    }
}
