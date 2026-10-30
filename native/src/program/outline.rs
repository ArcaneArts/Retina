//! Give the bytecode interpreter a callable ABI that DXIL can retain.
//!
//! DXC's `noinline` cannot retain our array returns / struct and vector
//! parameters. A per-invocation mailbox lets the large body use a void ABI;
//! the original signature becomes a small wrapper. Each interpolation depth
//! has its own mailbox, so a nested call cannot overwrite its caller's inputs.
//! Register arrays remain local and retain their original zero initialization.
use super::compact_calls::function;

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
    let params: Vec<_> = args.split(',').map(|arg| arg.split_once(':').unwrap()).collect();
    let input = format!("retina_input_{name}");
    let output = format!("retina_output_{name}");
    let mut replacement = format!(
        "struct RetinaArgs_{name}{{{args},}}\nvar<private> {input}:RetinaArgs_{name};\nvar<private> {output}:{};\nfn retina_noinline_{name}(){{\n",
        result.trim()
    );
    for (param, _) in &params {
        replacement.push_str(&format!("let {param}={input}.{param};\n"));
    }
    // These are controlled, generated interpreter bodies (no comments or
    // strings containing `return`). Keep every early return and expression.
    let mut rest = &body[..body.len()-1];
    while let Some((before, after)) = rest.split_once("return ") {
        let (value, after) = after.split_once(';').unwrap();
        replacement.push_str(before);
        replacement.push_str(&format!("{{{output}={value};return;}}"));
        rest = after;
    }
    replacement.push_str(rest);
    replacement.push_str(&format!("}}\n{signature}{{\n{input}=RetinaArgs_{name}("));
    replacement.push_str(&params.iter().map(|(param, _)| *param).collect::<Vec<_>>().join(","));
    replacement.push_str(&format!(");\nretina_noinline_{name}();\nreturn {output};\n}}"));
    source = source.replace(original, &replacement);
    source
}

#[cfg(test)]
mod tests {
    use wgpu::naga::{self, AddressSpace};

    #[test]
    fn outlined_bodies_have_void_abi_and_distinct_private_mailboxes() {
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
                    assert!(body.arguments.is_empty() && body.result.is_none());
                    assert!(body.local_variables.iter().any(|(_, v)| v.name.as_deref() == Some("values")));
                }
                let mailboxes: Vec<_> = module.global_variables.iter().filter(|(_, v)| v.name.as_deref().is_some_and(|n| n.starts_with("retina_input_") || n.starts_with("retina_output_"))).collect();
                assert_eq!(mailboxes.len(), 2 * (depth + 2));
                assert!(mailboxes.iter().all(|(_, v)| v.space == AddressSpace::Private));
            }
        }
    }
}
