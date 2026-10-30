//! Keep large shader bodies callable without a module-private mailbox.
//! DXIL requires scalar parameters/results for retained functions. Flatten
//! vectors/Request into scalar inputs and return through distinct local f32
//! output pointers. Locals preserve invocation and nested-call isolation on
//! every backend, without adding global state or changing register arrays.
use super::compact_calls::function;

/// Apply after cached-field rewrites and constant-call selection. Only the
/// generated graph bodies are outlined; scalar arithmetic stays inline.
pub(crate) fn specialized(mut source: String) -> String {
    let names: Vec<String> = source.split("fn ").skip(1)
        .filter_map(|tail| tail.split_once('(').map(|(name, _)| name))
        .filter(|name| ["graph_", "interpolation_graph_", "raw_column_"].iter().any(|prefix|
            name.strip_prefix(prefix).is_some_and(|suffix| !suffix.is_empty() && suffix.bytes().all(|b| b.is_ascii_digit()))))
        .map(str::to_owned).collect();
    for name in names { source = outline(source, &name); }
    source
}

pub(super) fn source(mut source: String, depth: usize) -> String {
    for level in 0..=depth {
        let name = if level == depth { "run_program".to_owned() } else { format!("run_program_level_{level}") };
        source = outline(source, &name);
    }
    outline(source, "run_density_bounds")
}

fn outline(mut source: String, name: &str) -> String {
    let original = function(&source, name);
    let (signature, body) = original.split_once('{').unwrap();
    let (args, result) = signature.split_once('(').unwrap().1.split_once(")->").unwrap();
    let result = result.trim();
    let count = match result {
        "f32" => 1,
        "vec2<f32>" => 2,
        "array<f32,6>" => 6,
        _ => panic!("unexpected generated function result: {result}"),
    };
    // Request is shared by world/cave kernels. Read its declaration rather
    // than maintaining another independently ordered list of fields.
    let request = include_str!("../simplex.wgsl").split_once("struct Request {").unwrap().1.split_once('}').unwrap().0;
    let request: Vec<_> = request.split(',').filter_map(|field| field.trim().split_once(':'))
        .map(|(field, ty)| (field.trim(), ty.trim())).collect();
    let mut params = Vec::new();
    let mut call = Vec::new();
    let mut locals = String::new();
    for arg in args.split(',') {
        let (param, ty) = arg.split_once(':').unwrap();
        let param = param.trim(); let ty = ty.trim();
        let fields: Vec<_> = if ty == "Request" { request.clone() }
            else if ty == "vec3<f32>" { vec![("x", "f32"), ("y", "f32"), ("z", "f32")] }
            else if ty == "vec4<f32>" { vec![("x", "f32"), ("y", "f32"), ("z", "f32"), ("w", "f32")] }
            else { vec![] };
        if fields.is_empty() {
            params.push(arg.to_owned()); call.push(param.to_owned());
        } else {
            let mut values = Vec::new();
            for (field, ty) in fields {
                let scalar = format!("{param}_{field}");
                params.push(format!("{scalar}:{ty}"));
                call.push(format!("{param}.{field}")); values.push(scalar);
            }
            locals.push_str(&format!("let {param}={ty}({});\n",values.join(",")));
        }
    }
    let mut wrapper = String::new();
    let mut results = Vec::new();
    for i in 0..count {
        params.push(format!("retina_out_{i}:ptr<function,f32>"));
        let local = format!("retina_result_{i}");
        wrapper.push_str(&format!("var {local}:f32;\n"));
        call.push(format!("&{local}")); results.push(local);
    }
    let mut replacement = format!("fn retina_noinline_{name}({}){{\n{locals}",params.join(","));
    // Controlled generated bodies contain no comments/strings with `return `.
    let mut rest = &body[..body.len()-1];
    while let Some((before, after)) = rest.split_once("return ") {
        let (value, after) = after.split_once(';').unwrap();
        replacement.push_str(before);
        replacement.push_str(&format!("{{let retina_result={value};"));
        for i in 0..count {
            let value = if count == 1 { "retina_result".to_owned() } else { format!("retina_result[{i}]") };
            replacement.push_str(&format!("*retina_out_{i}={value};"));
        }
        replacement.push_str("return;}"); rest = after;
    }
    replacement.push_str(rest);
    let value = if count == 1 { results[0].clone() } else { format!("{result}({})",results.join(",")) };
    replacement.push_str(&format!("}}\n{signature}{{\n{wrapper}retina_noinline_{name}({});\nreturn {value};\n}}",call.join(",")));
    source = source.replace(original, &replacement);
    source
}

#[cfg(test)]
mod tests {
    use wgpu::naga::{self, AddressSpace, Scalar, ScalarKind, TypeInner};

    #[test]
    fn specialized_graphs_keep_nested_calls_and_early_returns() {
        let original = "fn graph_round(value:f32)->f32{return value;}\nfn raw_column_0(point:vec3<f32>)->f32 {if point.y<0.0{return -0.0;}return point.x;}\nfn interpolation_graph_0(point:vec3<f32>)->array<f32,6>{return array<f32,6>(raw_column_0(point),0.0,0.0,0.0,0.0,0.0);}\nfn graph_0(point:vec3<f32>)->array<f32,6>{return interpolation_graph_0(point);}";
        let source = super::specialized(original.into());
        assert_eq!(source.matches("fn retina_noinline_").count(), 3);
        assert!(source.contains("fn graph_round(value:f32)->f32{return value;}"));
        assert!(source.contains("let retina_result=-0.0;*retina_out_0=retina_result;return;"));
        let module = naga::front::wgsl::parse_str(&source).unwrap();
        naga::valid::Validator::new(naga::valid::ValidationFlags::all(), naga::valid::Capabilities::all()).validate(&module).unwrap();
    }

    #[test]
    fn outlined_bodies_have_scalar_abi_and_no_private_mailboxes() {
        for (capacity, depth, composition) in [(64, 0, false), (64, 1, false), (64, 2, false), (1024, 2, true)] {
            let program = super::super::interpreter_source_density(capacity, depth, composition);
            for (prefix, suffix) in [
                (include_str!("../simplex.wgsl"), include_str!("../noise3.wgsl").to_owned()),
                (include_str!("../caves.wgsl"), format!("{}\n{}", include_str!("../aquifers.wgsl"), include_str!("../materials.wgsl"))),
            ] {
                let source = format!("{prefix}\n{}\n{program}\n{suffix}", include_str!("../climate.wgsl"));
                let module = naga::front::wgsl::parse_str(&source).unwrap_or_else(|e| panic!("{}", e.emit_to_string(&source)));
                naga::valid::Validator::new(naga::valid::ValidationFlags::all(), naga::valid::Capabilities::all()).validate(&module).unwrap();
                let bodies: Vec<_> = module.functions.iter().filter(|(_, f)| f.name.as_deref().is_some_and(|n| n.starts_with("retina_noinline_"))).collect();
                assert_eq!(bodies.len(), depth + 2);
                for (_, body) in bodies {
                    assert!(body.result.is_none());
                    for arg in &body.arguments {
                        assert!(matches!(module.types[arg.ty].inner, TypeInner::Scalar(_)) || matches!(module.types[arg.ty].inner, TypeInner::Pointer { base, space: AddressSpace::Function } if module.types[base].inner == TypeInner::Scalar(Scalar { kind: ScalarKind::Float, width: 4 })));
                    }
                    assert!(body.local_variables.iter().any(|(_, v)| v.name.as_deref() == Some("values")));
                }
                let mailboxes: Vec<_> = module.global_variables.iter().filter(|(_, v)| v.name.as_deref().is_some_and(|n| n.starts_with("retina_input_") || n.starts_with("retina_output_"))).collect();
                assert!(mailboxes.is_empty());
            }
        }
    }
}
