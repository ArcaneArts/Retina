//! Reuse the specialized X/Z atlas in the compact GPU interpreter. The resident
//! schedule retains original instruction IDs, spline indices and register slots.
use super::{Program, RegistryProgram};

pub(crate) const FLAG: u32 = 1 << 24;

fn schedule(program: &Program, points: &[[f32; 4]], slots: &[Option<usize>]) -> Vec<u32> {
    if !slots.iter().any(Option::is_some) {
        return Vec::new();
    }
    let mut live = vec![false; program.nodes.len()];
    let mut pending = program.roots.clone();
    pending.push(0); // Missing outputs read the original node zero.
    while let Some(node) = pending.pop() {
        if std::mem::replace(&mut live[node as usize], true) {
            continue;
        }
        if slots[node as usize].is_none() {
            pending.extend(program.nodes[node as usize].dependencies(points));
        }
    }
    live.iter()
        .enumerate()
        .filter(|(_, used)| **used)
        .flat_map(|(node, _)| [node as u32, slots[node].map_or(0, |s| s as u32 + 1)])
        .collect()
}

/// Located directly after the terracotta bands, whose existing header provides
/// both address and length. Original descriptors and program count stay intact.
pub(super) fn append(registry: &RegistryProgram, words: &mut Vec<u32>) {
    let plan = crate::column_program::Plan::new(registry);
    let start = words.len();
    words.push(plan.owners.len() as u32);
    words.resize(start + 1 + plan.slots.len() * 2, 0);
    for (pid, program) in registry.all_programs().enumerate() {
        let entries = schedule(program, &registry.points, &plan.slots[pid]);
        if !entries.is_empty() {
            words[start + 1 + pid * 2] = words.len() as u32;
            words[start + 2 + pid * 2] = entries.len() as u32 / 2;
            words.extend(entries);
        }
    }
}

pub(super) fn interpreter_body(body: &str) -> String {
    body.replace(
        "for(var i=0u;i<count;i++) {",
        "let column=interpreter_column_index(point,request);var schedule=0u;var evaluations=count;\n\
         if column!=0xffffffffu{let row=bytecode[6]+bytecode[7]+1u+program*2u;schedule=bytecode[row];if schedule!=0u{evaluations=bytecode[row+1u];}}\n\
         for(var evaluation=0u;evaluation<evaluations;evaluation++){\n\
         var i=evaluation;var column_slot=0u;if schedule!=0u{i=bytecode[schedule+evaluation*2u];column_slot=bytecode[schedule+evaluation*2u+1u];}",
    )
    .replace("switch op {", "if column_slot!=0u{result=surface_nodes[column+column_slot-1u];}else{switch op {")
    .replace("values[i]=result;", "}\nvalues[i]=result;")
}

pub(super) const SOURCE: &str = r#"
fn interpreter_column_index(point:vec3<f32>,r:Request)->u32{
    if (r.padding&16777216u)==0u || r.density_side==0u{return 0xffffffffu;}
    let fields=bytecode[bytecode[6]+bytecode[7]];if fields==0u{return 0xffffffffu;}
    let origin=density_origin(r).xz;
    let local=(point.xz-vec2<f32>(origin))/f32(r.density_step_xz);let cell=vec2<i32>(local);
    if any(local!=vec2<f32>(cell)) || any(cell<vec2<i32>(0)) || any(cell>=vec2<i32>(i32(r.density_side))){return 0xffffffffu;}
    if any(point.xz!=vec2<f32>(origin+cell*i32(r.density_step_xz))){return 0xffffffffu;}
    let offset=lake_probe_offset(r)+lake_side(r)*lake_side(r)*20u*density_layers(r);
    return offset+(u32(cell.y)*r.density_side+u32(cell.x))*fields;
}
"#;

#[cfg(test)]
mod tests {
    use super::*;
    use crate::program::Instruction;

    #[test]
    fn schedule_cuts_cached_dependencies_but_keeps_spline_children_and_implicit_outputs() {
        let n = |op, a, b, c| Instruction {
            op,
            a,
            b,
            c,
            p: [0.0; 4],
        };
        let program = Program {
            nodes: vec![
                n(0, 0, 0, 0),
                n(2, 0, 1, 0),
                n(4, 1, 0, 0),
                n(3, 1, 0, 0),
                n(25, 3, 0, 2),
                n(4, 2, 4, 0),
                n(2, 0, 1, 0),
            ],
            roots: vec![5],
        };
        let points = [[-1.0, 0.0, 2.0, 0.0], [1.0, 0.0, 3.0, 0.0]];
        let slots = [None, None, Some(2), None, None, None, None];
        assert_eq!(
            schedule(&program, &points, &slots),
            vec![0, 0, 2, 3, 3, 0, 4, 0, 5, 0]
        );
        assert!(schedule(&program, &points, &[None; 7]).is_empty());
        let registers = program.registers(&points);
        let mut owners = vec![usize::MAX; registers.capacity];
        for entry in schedule(&program, &points, &slots).chunks_exact(2) {
            let i = entry[0] as usize;
            if entry[1] == 0 {
                for child in program.nodes[i].dependencies(&points) {
                    assert_eq!(
                        owners[registers.slots[child as usize] as usize],
                        child as usize
                    );
                }
            }
            owners[registers.slots[i] as usize] = i;
        }
        for &root in &[0, 5] {
            assert_eq!(owners[registers.slots[root] as usize], root);
        }
    }
}
