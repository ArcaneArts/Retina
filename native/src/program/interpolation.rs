//! Registered trilinear operators. Child graphs are ordered before callers;
//! generated evaluator levels are acyclic and require no CPU spatial samples.
use super::Program;
use serde::Deserialize;
use std::fmt::Write;

#[derive(Clone, Deserialize)]
pub struct Field {
    pub input: Program,
    pub cell: [u32; 2],
}

pub(super) fn depths(fields: &[Field]) -> Vec<usize> {
    let mut result = Vec::with_capacity(fields.len());
    for field in fields {
        let child = field
            .input
            .nodes
            .iter()
            .filter(|n| n.op == 28)
            .map(|n| result[n.p[0] as usize])
            .max()
            .unwrap_or(0);
        result.push(child + 1);
    }
    result
}

/// Skip duplicate corners on aligned axes, and bypass interpolation altogether
/// on grid nodes. Negative coordinates use floor, never truncation or clamping.
fn sample_body(call: &str, field: &str) -> String {
    format!(
        "let at=bytecode[12]+{field}*4u;let program=bytecode[at];\n\
         let size=vec3<f32>(f32(bytecode[at+1u]),f32(bytecode[at+2u]),f32(bytecode[at+1u]));\n\
         let lower=floor(point/size)*size;let alpha=(point-lower)/size;\n\
         if all(alpha==vec3<f32>(0.0)){{return {call}[0];}}\n\
         var corners:array<f32,8>;\n\
         for(var corner=0u;corner<8u;corner++){{\n\
             let upper=vec3<u32>(corner&1u,(corner>>1u)&1u,(corner>>2u)&1u);\n\
             if any((upper!=vec3<u32>(0u)) & (alpha==vec3<f32>(0.0))){{continue;}}\n\
             let point=lower+vec3<f32>(upper)*size;corners[corner]={call}[0];\n\
         }}\nreturn interpolation_mix(corners,alpha);\n"
    )
}

pub(super) fn interpreter(body: &str, depth: usize, prefix: &str) -> String {
    let mut result = format!(
        "fn {prefix}_field_none(field:u32,point:vec3<f32>,request:Request,context:vec4<f32>)->f32{{return 0.0;}}\n"
    );
    for level in 0..=depth {
        let name = if level == depth {
            prefix.to_owned()
        } else {
            format!("{prefix}_level_{level}")
        };
        let call = if level == 0 {
            format!("{prefix}_field_none")
        } else {
            format!("{prefix}_field_{}", level - 1)
        };
        result.push_str(
            &body
                .replace("fn run_program(", &format!("fn {name}("))
                .replace("INTERPOLATION_CALL", &call),
        );
        if level < depth {
            let call = format!("{name}(program,point,request,context)");
            writeln!(result,"fn {prefix}_field_{level}(field:u32,point:vec3<f32>,request:Request,context:vec4<f32>)->f32{{\n{}}}",sample_body(&call,"field")).unwrap();
        }
    }
    result
}

pub(crate) fn specialized(field: usize) -> String {
    let call = format!("interpolation_graph_{field}(point,request,context,program)");
    format!(
        "fn interpolation_field_{field}(point:vec3<f32>,request:Request,context:vec4<f32>)->f32{{\n{}}}\n",
        sample_body(&call, &format!("{field}u"))
    )
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::program::{Instruction, RegistryProgram};
    fn input(field: Option<usize>) -> Program {
        let mut nodes = vec![Instruction {
            op: 0,
            a: 0,
            b: 0,
            c: 0,
            p: [0.0; 4],
        }];
        for axis in 0..3 {
            nodes.push(Instruction {
                op: 29,
                a: axis,
                b: 0,
                c: 0,
                p: [0.0; 4],
            });
        }
        if let Some(field) = field {
            nodes.push(Instruction {
                op: 28,
                a: 1,
                b: 2,
                c: 3,
                p: [field as f32, 0.0, 0.0, 0.0],
            });
        }
        Program {
            roots: vec![(nodes.len() - 1) as u32],
            nodes,
        }
    }
    fn registry() -> RegistryProgram {
        serde_json::from_value(serde_json::json!({
            "programs":[{"nodes":[{"op":0,"a":0,"b":0,"c":0,"p":[0,0,0,0]}],"roots":[0]}],
            "noises":[{"frequency":1,"amplitude":1,"salt":0,"coefficients":[1]}],"points":[],"surface":[-64,8,0]
        })).unwrap()
    }
    #[test]
    fn field_dependencies_are_topological_and_coordinate_operands_stay_live() {
        let mut r = registry();
        r.programs = vec![input(Some(2)), input(None), input(None)];
        r.interpolations = vec![
            Field {
                input: input(None),
                cell: [4, 8],
            },
            Field {
                input: input(Some(0)),
                cell: [7, 5],
            },
            Field {
                input: input(Some(1)),
                cell: [5, 3],
            },
        ];
        r.validate(0).unwrap();
        assert_eq!(r.interpolation_depth(), 3);
        assert_eq!(r.programs[0].nodes[4].dependencies(&[]), vec![1, 2, 3]);
        let scratch = r.scratch_values();
        assert!(scratch >= 4);
        r.interpolations[0].input = input(Some(0));
        assert!(r.validate(0).unwrap_err().contains("earlier subgraphs"));
        r.interpolations[0].input = input(None);
        r.interpolations[2].cell[1] = 0;
        assert!(r.validate(0).unwrap_err().contains("interpolation field"));
        r.interpolations[2].cell[1] = 3;
        r.programs[0].nodes[4].p[0] = 0.5;
        assert!(r.validate(0).unwrap_err().contains("earlier subgraphs"));
    }
}
