//! Find reusable X/Z subgraphs without approximating their sampled values.
use crate::program::RegistryProgram;
use std::collections::{BTreeSet, HashMap};

#[derive(Hash, PartialEq, Eq)]
struct Key {
    op: u32,
    p: [u32; 4],
    args: [u32; 3],
    knots: Vec<[u32; 3]>,
}

pub(crate) struct Plan {
    pub slots: Vec<Vec<Option<usize>>>,
    pub owners: Vec<(usize, usize)>,
}

impl Plan {
    pub fn new(registry: &RegistryProgram) -> Self {
        Self::for_programs(registry, |pid| pid < 3 || pid >= registry.programs.len())
    }

    /// The same dependency/coordinate analysis can scope a cache to a single
    /// vertical search, without enlarging the resident world column atlas.
    pub fn for_programs(registry: &RegistryProgram, include: impl Fn(usize) -> bool) -> Self {
        let mut keys = HashMap::<Key, usize>::new();
        let mut owners = Vec::new();
        let mut axes = Vec::<u8>::new();
        let mut costs = Vec::<u32>::new();
        let mut programs = Vec::<Vec<usize>>::new();
        let mut selected = BTreeSet::new();
        for (pid, program) in registry.all_programs().enumerate() {
            if !include(pid) {
                programs.push(vec![]);
                continue;
            }
            let mut ids = Vec::<usize>::new();
            for (i, n) in program.nodes.iter().enumerate() {
                let deps = n.dependencies(&registry.points);
                let mut key = Key {
                    op: n.op,
                    p: n.p.map(f32::to_bits),
                    args: [n.a, n.b, n.c],
                    knots: Vec::new(),
                };
                match n.op {
                    1 | 23 | 24 | 27 | 28 | 31 => {
                        key.args = [
                            ids[n.a as usize] as u32,
                            ids[n.b as usize] as u32,
                            ids[n.c as usize] as u32,
                        ]
                    }
                    4..=10 | 40 | 41 => {
                        key.args[0] = ids[n.a as usize] as u32;
                        key.args[1] = ids[n.b as usize] as u32;
                    }
                    11..=22 | 30 | 43 | 44 | 53 => key.args[0] = ids[n.a as usize] as u32,
                    25 => {
                        key.args[0] = ids[n.a as usize] as u32;
                        key.args[1] = 0; // Offset is not part of spline semantics.
                        key.knots = registry.points[n.b as usize..(n.b + n.c) as usize]
                            .iter()
                            .map(|p| [p[0].to_bits(), p[1].to_bits(), ids[p[2] as usize] as u32])
                            .collect();
                    }
                    _ => {}
                }
                let id = if let Some(&id) = keys.get(&key) {
                    id
                } else {
                    let inherited = deps.iter().fold(0, |a, &d| a | axes[ids[d as usize]]);
                    let direct = match n.op {
                        0 => 0,
                        1 => u8::from(n.p[1] != 0.0) * 5 | u8::from(n.p[2] != 0.0) * 2,
                        2 => {
                            if matches!(n.b, 1 | 2) {
                                5
                            } else {
                                7
                            }
                        }
                        3 => match n.a {
                            0 => 1,
                            1 => 2,
                            2 => 4,
                            _ => 7,
                        },
                        4..=25 | 27 | 30 | 31 | 40 | 41 | 43 | 44 => 0,
                        29 => 1 << n.a,
                        26 => {
                            if n.p[1] == 0.0 {
                                5
                            } else {
                                7
                            }
                        }
                        // Context-dependent material operations cannot be column cached.
                        _ => 7,
                    };
                    let cost = deps.iter().fold(
                        if matches!(n.op, 1 | 2 | 26 | 27 | 31) {
                            8u32
                        } else {
                            1
                        },
                        |c, &d| c.saturating_add(costs[ids[d as usize]]),
                    );
                    let id = owners.len();
                    owners.push((pid, i));
                    axes.push(inherited | direct);
                    costs.push(cost);
                    keys.insert(key, id);
                    id
                };
                ids.push(id);
            }
            let mut live = vec![false; program.nodes.len()];
            let mut pending = program.roots.clone();
            pending.push(0); // Missing root slots use node zero in resident bytecode.
            while let Some(i) = pending.pop() {
                if std::mem::replace(&mut live[i as usize], true) {
                    continue;
                }
                pending.extend(program.nodes[i as usize].dependencies(&registry.points));
            }
            let eligible = |id: usize| axes[id] != 0 && axes[id] & 2 == 0 && costs[id] >= 8;
            for (i, n) in program.nodes.iter().enumerate().filter(|(i, _)| live[*i]) {
                if axes[ids[i]] & 2 != 0 {
                    for dep in n.dependencies(&registry.points) {
                        if eligible(ids[dep as usize]) {
                            selected.insert(ids[dep as usize]);
                        }
                    }
                }
            }
            for &root in &program.roots {
                if eligible(ids[root as usize]) {
                    selected.insert(ids[root as usize]);
                }
            }
            programs.push(ids);
        }
        let field_ids = selected.into_iter().collect::<Vec<_>>();
        let field_slots = field_ids
            .iter()
            .enumerate()
            .map(|(slot, &id)| (id, slot))
            .collect::<HashMap<_, _>>();
        Self {
            slots: registry
                .all_programs()
                .enumerate()
                .map(
                    |(pid, p)| match programs.get(pid).filter(|ids| !ids.is_empty()) {
                        Some(ids) => ids.iter().map(|id| field_slots.get(id).copied()).collect(),
                        None => vec![None; p.nodes.len()],
                    },
                )
                .collect(),
            owners: field_ids.iter().map(|&id| owners[id]).collect(),
        }
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::program::{Instruction, Program};
    fn n(op: u32, a: u32, b: u32, c: u32, p: [f32; 4]) -> Instruction {
        Instruction { op, a, b, c, p }
    }
    #[test]
    fn shared_horizontal_fields_exclude_y_and_dead_noise() {
        let nodes = vec![
            n(0, 0, 0, 0, [0.0; 4]),
            n(2, 0, 1, 0, [0.0; 4]),
            n(3, 1, 0, 0, [0.0, 10.0, 0.0, 1.0]),
            n(4, 1, 2, 0, [0.0; 4]),
            n(2, 1, 0, 0, [0.0; 4]),
        ];
        let a = Program {
            nodes,
            roots: vec![3],
        };
        let mut b = a.clone();
        b.nodes.insert(0, n(0, 0, 0, 0, [17.0; 4]));
        b.nodes[4].a += 1;
        b.nodes[4].b += 1;
        b.roots = vec![4];
        let r = RegistryProgram {
            interpolations: vec![],
            programs: vec![a.clone(), b, a],
            noises: vec![],
            points: vec![],
            surface: [0, 8, 0],
            terrain_cell: [4, 8],
            density_composition: false,
            surface_noises: [0; 3],
            material_layers: false,
            material_halo: false,
            aquifer: None,
        };
        let plan = Plan::new(&r);
        assert_eq!(plan.owners, vec![(0, 1)]);
        assert_eq!(plan.slots[0][1], Some(0));
        assert_eq!(plan.slots[1][2], Some(0));
        assert_eq!(plan.slots[0][2], None);
        assert_eq!(plan.slots[0][4], None);

        let mut nested = r;
        let zero = Program {
            nodes: vec![n(0, 0, 0, 0, [0.0; 4])],
            roots: vec![0],
        };
        let input = nested.programs[0].clone();
        nested.programs = vec![zero.clone(), zero.clone(), zero, input.clone()];
        nested.interpolations = vec![
            crate::program::Interpolation {
                input: input.clone(),
                cell: [4, 8],
            },
            crate::program::Interpolation {
                input,
                cell: [7, 5],
            },
        ];
        let child_plan = Plan::new(&nested);
        assert_eq!(child_plan.owners, vec![(4, 1)]);
        assert_eq!(child_plan.slots[3], vec![None; 5]);
        assert_eq!(child_plan.slots[4][1], Some(0));
        assert_eq!(child_plan.slots[5][1], Some(0));
        let source = crate::specialize::source(&nested).unwrap();
        assert!(source.contains("store_columns_4(point,r,id.x)"));
        let child = source
            .split("fn interpolation_graph_0(")
            .nth(1)
            .unwrap()
            .split("fn interpolation_field_0(")
            .next()
            .unwrap();
        assert!(child.contains("column_field_0(point,request)"));
    }
    #[test]
    fn scoped_noise_caches_only_when_y_is_replaced() {
        let graph = Program {
            nodes: vec![
                n(0, 0, 0, 0, [0.0; 4]),
                n(29, 0, 0, 0, [0.0; 4]),
                n(29, 1, 0, 0, [0.0; 4]),
                n(29, 2, 0, 0, [0.0; 4]),
                n(0, 0, 0, 0, [64.0, 0.0, 0.0, 0.0]),
                n(27, 1, 4, 3, [0.0, 1.0, 0.0, 0.0]),
                n(27, 1, 2, 3, [0.0, 1.0, 0.0, 0.0]),
                n(4, 5, 6, 0, [0.0; 4]),
            ],
            roots: vec![7],
        };
        let registry = RegistryProgram {
            interpolations: vec![],
            programs: vec![graph.clone(), graph.clone(), graph],
            noises: vec![],
            points: vec![],
            surface: [0, 8, 0],
            terrain_cell: [4, 8],
            density_composition: false,
            surface_noises: [0; 3],
            material_layers: false,
            material_halo: false,
            aquifer: None,
        };
        let plan = Plan::new(&registry);
        assert_eq!(plan.owners, vec![(0, 5)]);
        for slots in &plan.slots {
            assert_eq!(slots[5], Some(0));
            assert_eq!(slots[6], None);
        }
    }
    #[test]
    fn spline_offsets_share_but_derivatives_do_not() {
        let a = Program {
            nodes: vec![
                n(0, 0, 0, 0, [0.0; 4]),
                n(3, 0, 0, 0, [0.0, 10.0, 0.0, 1.0]),
                n(1, 0, 0, 0, [0.0, 1.0, 0.0, 0.0]),
                n(25, 1, 0, 2, [0.0; 4]),
                n(3, 1, 0, 0, [0.0, 10.0, 0.0, 1.0]),
                n(4, 3, 4, 0, [0.0; 4]),
            ],
            roots: vec![5],
        };
        let mut b = a.clone();
        b.nodes[3].b = 2;
        let mut c = a.clone();
        c.nodes[3].b = 4;
        let r = RegistryProgram {
            interpolations: vec![],
            programs: vec![a, b, c],
            noises: vec![],
            points: vec![
                [0.0, 1.0, 0.0, 0.0],
                [10.0, -0.5, 2.0, 0.0],
                [0.0, 1.0, 0.0, 0.0],
                [10.0, -0.5, 2.0, 0.0],
                [0.0, 2.0, 0.0, 0.0],
                [10.0, -0.5, 2.0, 0.0],
            ],
            surface: [0, 8, 0],
            terrain_cell: [4, 8],
            density_composition: false,
            surface_noises: [0; 3],
            material_layers: false,
            material_halo: false,
            aquifer: None,
        };
        let p = Plan::new(&r);
        assert_eq!(p.owners, vec![(0, 3), (2, 3)]);
        assert_eq!(p.slots[1][3], Some(0));
        assert_eq!(p.slots[2][3], Some(1));
    }
}
