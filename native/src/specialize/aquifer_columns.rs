//! Invocation-local X/Z values for the registered preliminary-surface search.
//! No scratch allocation, dispatch, resident atlas coverage or readback is needed.
use super::*;

pub(super) const MARKER: &str = "fn aquifer_column_values(";

pub(super) fn source(
    registry: &RegistryProgram,
    bodies: &HashMap<u32, &'static str>,
) -> Result<String, String> {
    let Some(aquifer) = registry
        .aquifer
        .as_ref()
        .filter(|a| a.enabled && a.surface[2] == 0)
    else {
        // An explicit height has no repeated Y search to amortize a prepass.
        return Ok(String::new());
    };
    let pid = aquifer.program as usize + 4;
    let plan = crate::column_program::Plan::for_programs(registry, |p| p == pid);
    if plan.owners.is_empty() {
        return Ok(String::new());
    }
    let program = &registry.programs[pid];
    let mut source = String::from("struct AquiferColumnValues {\n");
    for field in 0..plan.owners.len() {
        writeln!(source, "v{field}:f32,").unwrap();
    }
    source.push_str("}\nfn aquifer_column_values(point:vec3<f32>,request:Request)->AquiferColumnValues {let context=vec4<f32>(0.0);\n");
    writeln!(source, "let program={pid}u;").unwrap();
    let emitter = Emitter::new(program, &registry.points, bodies, None);
    let mut memo = vec![false; program.nodes.len()];
    for &(_, node) in &plan.owners {
        emitter.emit(node, &mut memo, &mut source)?;
    }
    writeln!(
        source,
        "return AquiferColumnValues({});}}",
        plan.owners
            .iter()
            .map(|(_, node)| format!("v{node}"))
            .collect::<Vec<_>>()
            .join(",")
    )
    .unwrap();
    let mut body = graph_columns(program, &registry.points, bodies, Some(&plan.slots[pid]))?;
    for field in 0..plan.owners.len() {
        body = body.replace(
            &format!("column_field_{field}(point,request)"),
            &format!("columns.v{field}"),
        );
    }
    writeln!(source,"fn run_aquifer_column(point:vec3<f32>,request:Request,context:vec4<f32>,columns:AquiferColumnValues)->array<f32,6>{{let program={pid}u;\n{body}}}").unwrap();
    Ok(source)
}

/// Only the two evaluations inside this entrypoint share X/Z and request state.
/// Scalar struct members stay local to that invocation's descending search.
pub(super) fn kernel(source: &str) -> String {
    if !source.contains(MARKER) {
        return source.to_owned();
    }
    source
        .replace(
            "let value=run_aquifer_4(p,r,vec4<f32>(0.0));",
            "let fields=aquifer_column_values(p,r);let value=run_aquifer_column(p,r,vec4<f32>(0.0),fields);",
        )
        .replace(
            "run_aquifer_4(vec3<f32>(p.x,f32(y),p.z),r,vec4<f32>(0.0))[0]",
            "run_aquifer_column(vec3<f32>(p.x,f32(y),p.z),r,vec4<f32>(0.0),fields)[0]",
        )
}

#[cfg(test)]
mod tests {
    use super::*;
    fn node(op: u32, a: u32, b: u32, c: u32, p: [f32; 4]) -> Instruction {
        Instruction { op, a, b, c, p }
    }
    fn registry() -> RegistryProgram {
        let zero = Program {
            nodes: vec![node(0, 0, 0, 0, [0.0; 4])],
            roots: vec![0],
        };
        let mut programs = vec![zero; 9];
        programs[8] = Program {
            nodes: vec![
                node(0, 0, 0, 0, [0.0; 4]),
                node(29, 0, 0, 0, [0.0; 4]),
                node(29, 1, 0, 0, [0.0; 4]),
                node(29, 2, 0, 0, [0.0; 4]),
                node(0, 0, 0, 0, [64.0, 0.0, 0.0, 0.0]),
                node(27, 1, 4, 3, [0.0, 1.0, 0.0, 0.0]), // Explicitly replaced Y.
                node(27, 1, 2, 3, [0.0, 1.0, 0.0, 0.0]), // Genuine 3D field.
                node(4, 5, 6, 0, [0.0; 4]),
                node(4, 7, 2, 0, [0.0; 4]),
                node(45, 0, 0, 0, [0.0; 4]), // Material context is not X/Z pure.
                node(4, 8, 9, 0, [0.0; 4]),
                node(4, 5, 4, 0, [0.0; 4]),
            ],
            roots: vec![10, 11],
        };
        RegistryProgram {
            programs,
            interpolations: vec![],
            noises: vec![],
            points: vec![],
            surface: [-64, 8, 0],
            terrain_cell: [4, 8],
            density_composition: false,
            surface_noises: [0; 3],
            material_layers: true,
            material_halo: false,
            aquifer: Some(crate::program::AquiferProgram {
                enabled: true,
                program: 4,
                surface: [-64, 8, 0],
            }),
        }
    }
    #[test]
    fn local_values_preserve_y_context_implicit_roots_and_search_scope() {
        let registry = registry();
        let plan = crate::column_program::Plan::for_programs(&registry, |p| p == 8);
        assert_eq!(plan.owners, vec![(8, 5), (8, 11)]);
        assert_eq!(plan.slots[8][6], None);
        assert_eq!(plan.slots[8][9], None);
        let source = source(&registry, &opcode_bodies().unwrap()).unwrap();
        let prepass = source.split("fn run_aquifer_column(").next().unwrap();
        assert!(!prepass.contains("var v6:f32"));
        assert!(!prepass.contains("var v9:f32"));
        assert!(source.contains("let v5=columns.v0;"));
        assert!(source.contains("let v11=columns.v1;"));
        assert!(source.contains("var v6:f32"));
        assert!(source.contains("var v9:f32"));
        assert!(source.ends_with("return array<f32,6>(v10,v11,v0,v0,v0,v0);\n}\n"));
        let raw = include_str!("../aquifers.wgsl");
        assert_eq!(kernel(raw), raw);
        let specialized =
            super::super::static_calls(&format!("{source}\nfn run_aquifer_4(\n{raw}"));
        assert!(specialized.contains("let fields=aquifer_column_values(p,r);"));
        assert!(
            specialized.contains(
                "run_aquifer_column(vec3<f32>(p.x,f32(y),p.z),r,vec4<f32>(0.0),fields)[0]"
            )
        );
        assert!(!specialized.contains("run_aquifer_4(p,r,vec4<f32>(0.0))"));
    }
    #[test]
    fn explicit_height_disabled_and_constant_searches_keep_original_graphs() {
        let mut registry = registry();
        let bodies = opcode_bodies().unwrap();
        registry.aquifer.as_mut().unwrap().surface[2] = 1;
        assert!(source(&registry, &bodies).unwrap().is_empty());
        registry.aquifer.as_mut().unwrap().surface[2] = 0;
        registry.aquifer.as_mut().unwrap().enabled = false;
        assert!(source(&registry, &bodies).unwrap().is_empty());
        registry.aquifer.as_mut().unwrap().enabled = true;
        registry.programs[8] = registry.programs[0].clone();
        assert!(source(&registry, &bodies).unwrap().is_empty());
    }
}
