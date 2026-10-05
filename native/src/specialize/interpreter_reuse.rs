//! Reuse the loaded compact interpreter only for entrypoints whose complete
//! WGSL call graph cannot reach a registered interpolation instruction.
use super::RegistryProgram;
use std::collections::HashSet;
use std::sync::LazyLock;
use wgpu::naga::{self, BinaryOperator, Expression, Function, Handle, Literal, Statement};

const WORLD: [&str; 10] = [
    "main",
    "biome_sites",
    "biome_queries",
    "lake_candidates",
    "lake_density",
    "lake_nodes",
    "climate_nodes",
    "height_nodes",
    "density_nodes",
    "surface_columns",
];
const CAVE: [&str; 10] = [
    "underground_queries",
    "cave_nodes",
    "cave_exterior",
    "cave_mask",
    "material_counts",
    "material_emit",
    "aquifer_mask",
    "aquifer_surface",
    "aquifer_centers",
    "aquifer_barrier",
];

#[derive(Clone, Copy, Debug, Default, Eq, Hash, PartialEq)]
pub(crate) struct Plan(u32);

#[derive(Clone, Copy, Debug, Eq, PartialEq)]
enum Root {
    One(u32),
    Materials,
    Aquifer(u32),
    Composition,
    All,
}

static DEPENDENCIES: LazyLock<Option<Vec<Vec<Root>>>> = LazyLock::new(|| {
    // These are the actual base interpreter sources, not a second hand list of
    // entrypoint dependencies. Parsing failure keeps the complete interpreter;
    // it never prevents the real GPU compiler or generation from running.
    let program = crate::program::interpreter_source(crate::program::COMPACT_VALUES);
    let world = format!(
        "{}\n{}\n{program}\n{}",
        include_str!("../simplex.wgsl"),
        include_str!("../climate.wgsl"),
        include_str!("../noise3.wgsl")
    );
    let cave = format!(
        "{}\n{}\n{program}\n{}\n{}",
        include_str!("../caves.wgsl"),
        include_str!("../climate.wgsl"),
        include_str!("../aquifers.wgsl"),
        include_str!("../materials.wgsl")
    );
    let world = naga::front::wgsl::parse_str(&world).ok()?;
    let cave = naga::front::wgsl::parse_str(&cave).ok()?;
    WORLD
        .iter()
        .map(|e| dependencies(&world, e))
        .chain(CAVE.iter().map(|e| dependencies(&cave, e)))
        .collect()
});

impl Plan {
    pub fn new(program: &RegistryProgram, capacity: usize, composition: bool) -> Self {
        if capacity > crate::program::COMPACT_VALUES {
            // Loaded base pipelines have 64 slots. Wide profiles retain the
            // full wide bundle even if a particular graph would fit in 64.
            return Self::default();
        }
        let Some(dependencies) = DEPENDENCIES.as_ref() else {
            return Self::default();
        };
        let interpolated = |id: usize| {
            program
                .programs
                .get(id)
                .is_none_or(|p| p.nodes.iter().any(|n| n.op == 28))
        };
        let any = || {
            program
                .all_programs()
                .any(|p| p.nodes.iter().any(|n| n.op == 28))
        };
        let material_end = program
            .aquifer
            .as_ref()
            .filter(|a| a.enabled)
            .map_or(program.programs.len(), |a| a.program as usize);
        let mut reuse = 0;
        for (entry, roots) in dependencies.iter().enumerate() {
            let needs_depth = roots.iter().any(|root| match *root {
                Root::One(id) => interpolated(id as usize),
                Root::Materials => (3..material_end).any(interpolated),
                Root::Aquifer(slot) => program
                    .aquifer
                    .as_ref()
                    .filter(|a| a.enabled)
                    .is_none_or(|a| interpolated(a.program as usize + slot as usize)),
                Root::All => any(),
                Root::Composition => composition,
            });
            if !needs_depth {
                reuse |= 1 << entry;
            }
        }
        Self(reuse)
    }
    pub fn world_reuses(self, entry: &str) -> bool {
        WORLD
            .iter()
            .position(|e| *e == entry)
            .is_some_and(|i| self.0 & (1 << i) != 0)
    }
    pub fn cave_reuses(self, entry: &str) -> bool {
        CAVE.iter()
            .position(|e| *e == entry)
            .is_some_and(|i| self.0 & (1 << (WORLD.len() + i)) != 0)
    }
}

fn dependencies(module: &naga::Module, entry: &str) -> Option<Vec<Root>> {
    let function = &module
        .entry_points
        .iter()
        .find(|e| e.name == entry)?
        .function;
    let mut roots = Vec::new();
    walk(
        module,
        function,
        &function.body,
        &mut HashSet::new(),
        &mut roots,
    );
    Some(roots)
}

fn walk(
    module: &naga::Module,
    owner: &Function,
    block: &naga::Block,
    seen: &mut HashSet<Handle<Function>>,
    roots: &mut Vec<Root>,
) {
    for statement in block {
        match statement {
            Statement::Block(block) => walk(module, owner, block, seen, roots),
            Statement::If { accept, reject, .. } => {
                walk(module, owner, accept, seen, roots);
                walk(module, owner, reject, seen, roots);
            }
            Statement::Switch { cases, .. } => {
                for case in cases {
                    walk(module, owner, &case.body, seen, roots);
                }
            }
            Statement::Loop {
                body, continuing, ..
            } => {
                walk(module, owner, body, seen, roots);
                walk(module, owner, continuing, seen, roots);
            }
            Statement::Call {
                function,
                arguments,
                ..
            } => {
                let callee = &module.functions[*function];
                if callee.name.as_deref() == Some("density_composed") {
                    // This switch also controls scratch strides/offsets. Base
                    // pipelines compile it to false, so layout alone can make
                    // an otherwise non-interpolated entry require a new bundle.
                    if !roots.contains(&Root::Composition) {
                        roots.push(Root::Composition);
                    }
                } else if matches!(
                    callee.name.as_deref(),
                    Some("run_program" | "run_density_bounds")
                ) {
                    let root = arguments
                        .first()
                        .map_or(Root::All, |arg| argument(module, owner, *arg));
                    if !roots.contains(&root) {
                        roots.push(root);
                    }
                } else if seen.insert(*function) {
                    walk(module, callee, &callee.body, seen, roots);
                }
            }
            _ => {}
        }
    }
}

fn argument(module: &naga::Module, f: &Function, h: Handle<Expression>) -> Root {
    match f.expressions[h] {
        Expression::Literal(Literal::U32(value)) => Root::One(value),
        Expression::Binary {
            op: BinaryOperator::Add,
            left,
            right,
        } => {
            let (a, b) = (argument(module, f, left), argument(module, f, right));
            match (a, b) {
                (Root::One(a), Root::One(b)) => a.checked_add(b).map_or(Root::All, Root::One),
                (Root::Aquifer(a), Root::One(b)) | (Root::One(b), Root::Aquifer(a)) => {
                    a.checked_add(b).map_or(Root::All, Root::Aquifer)
                }
                (Root::One(3), _) if biome(f, right) => Root::Materials,
                (_, Root::One(3)) if biome(f, left) => Root::Materials,
                _ => Root::All,
            }
        }
        Expression::Load { pointer } => argument(module, f, pointer),
        Expression::AccessIndex { base, index: 1 } => {
            let Expression::AccessIndex { base, index: 0 } = f.expressions[base] else {
                return Root::All;
            };
            let Expression::AccessIndex { base, index } = f.expressions[base] else {
                return Root::All;
            };
            let Expression::GlobalVariable(global) = f.expressions[base] else {
                return Root::All;
            };
            let global = &module.global_variables[global];
            let naga::TypeInner::Struct { ref members, .. } = module.types[global.ty].inner else {
                return Root::All;
            };
            if global.name.as_deref() == Some("caves")
                && members
                    .get(index as usize)
                    .is_some_and(|m| m.name.as_deref() == Some("aquifer"))
            {
                Root::Aquifer(0)
            } else {
                Root::All
            }
        }
        _ => Root::All,
    }
}

fn biome(f: &Function, h: Handle<Expression>) -> bool {
    if f.named_expressions
        .get(&h)
        .is_some_and(|name| name == "biome")
    {
        return true;
    }
    match f.expressions[h] {
        Expression::Load { pointer } => biome(f, pointer),
        Expression::LocalVariable(v) => f.local_variables[v].name.as_deref() == Some("biome"),
        _ => false,
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::program::{AquiferProgram, Instruction, Program};
    fn profile() -> RegistryProgram {
        RegistryProgram {
            programs: (0..9)
                .map(|_| Program {
                    nodes: vec![Instruction {
                        op: 0,
                        a: 0,
                        b: 0,
                        c: 0,
                        p: [0.0; 4],
                    }],
                    roots: vec![0],
                })
                .collect(),
            interpolations: vec![],
            noises: vec![],
            points: vec![],
            surface: [-64, 8, 0],
            terrain_cell: [4, 8],
            density_composition: false,
            surface_noises: [0; 3],
            material_layers: true,
            material_halo: false,
            aquifer: Some(AquiferProgram {
                enabled: true,
                program: 4,
                surface: [-64, 8, 0],
            }),
        }
    }
    #[test]
    fn shader_call_graphs_keep_interpolation_out_of_unrelated_pipelines() {
        let dependencies = DEPENDENCIES
            .as_ref()
            .expect("actual WGSL must parse for reuse");
        assert_eq!(dependencies.len(), WORLD.len() + CAVE.len());
        assert!(
            !dependencies.iter().flatten().any(|r| *r == Root::All),
            "unresolved shader dispatch: {dependencies:?}"
        );
        let mut p = profile();
        p.programs[1].nodes[0].op = 28;
        p.programs[2].nodes[0].op = 28;
        let plan = Plan::new(&p, 64, false);
        let composed = Plan::new(&p, 64, true);
        assert!(!composed.world_reuses("main"));
        assert!(!composed.world_reuses("lake_candidates"));
        assert!(composed.world_reuses("climate_nodes"));
        assert!(composed.cave_reuses("material_emit"));
        for entry in [
            "main",
            "biome_sites",
            "biome_queries",
            "lake_candidates",
            "climate_nodes",
        ] {
            assert!(plan.world_reuses(entry), "{entry}: {dependencies:?}");
        }
        for entry in [
            "underground_queries",
            "material_counts",
            "material_emit",
            "aquifer_surface",
            "aquifer_centers",
            "aquifer_barrier",
        ] {
            assert!(plan.cave_reuses(entry), "{entry}: {dependencies:?}");
        }
        for entry in [
            "height_nodes",
            "density_nodes",
            "surface_columns",
            "lake_density",
            "lake_nodes",
            "interpolation_nodes_1",
        ] {
            assert!(!plan.world_reuses(entry), "{entry}");
        }
        for entry in ["cave_nodes", "cave_exterior", "cave_mask", "aquifer_mask"] {
            assert!(!plan.cave_reuses(entry), "{entry}");
        }
        p.programs[0].nodes[0].op = 28;
        let climate = Plan::new(&p, 64, false);
        assert_ne!(plan, climate);
        assert!(!climate.world_reuses("biome_sites"));
        assert!(!climate.cave_reuses("underground_queries"));
        p.programs[3].nodes[0].op = 28;
        let materials = Plan::new(&p, 64, false);
        assert!(!materials.world_reuses("main"));
        assert!(!materials.cave_reuses("material_emit"));
        p.programs[8].nodes[0].op = 28;
        let aquifer = Plan::new(&p, 64, false);
        assert!(!aquifer.cave_reuses("aquifer_surface"));
        assert!(aquifer.cave_reuses("aquifer_centers"));
        assert_eq!(Plan::new(&p, 1024, false), Plan::default());
    }
    #[test]
    fn unknown_graph_ids_and_new_entrypoints_keep_the_complete_interpreter() {
        let module = naga::front::wgsl::parse_str("fn run_program(id:u32)->u32{return id;} @compute @workgroup_size(1) fn main(){let result=run_program(3u+7u);}").unwrap();
        assert_eq!(dependencies(&module, "main"), Some(vec![Root::One(10)]));
        assert!(!Plan::default().world_reuses("main"));
        assert!(!Plan(u32::MAX).world_reuses("new_stage"));
        assert!(!Plan(u32::MAX).cave_reuses("new_stage"));
    }
}
