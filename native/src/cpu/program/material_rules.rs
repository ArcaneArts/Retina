//! Flatten small material rules into conditional branches with constant leaves.
//! Other graphs retain the general evaluator and its dependency/cache behavior.
use super::{Context, Evaluator, Opcode};
use crate::program::{Instruction, Program};

#[derive(Clone, Copy)]
enum Predicate {
    StoneDepth,
    VerticalAnchor,
    VerticalChance,
    Hole,
    Steep,
    PreliminarySurface,
    NoiseRange,
}

enum Branch {
    Material(f32),
    Test {
        node: u32,
        predicate: Predicate,
        yes: usize,
        no: usize,
    },
}

pub(super) struct Plan {
    branches: Vec<Branch>,
    root: usize,
    output: [bool; 6],
    implicit: f32,
}

impl Plan {
    pub(super) fn new(program: &Program, constants: &[Option<f32>]) -> Option<Self> {
        let root = program.roots.first().copied().unwrap_or(0);
        let implicit = constants[0].filter(|v| v.is_finite())?;
        let roots = std::array::from_fn::<_, 6, _>(|i| program.roots.get(i).copied().unwrap_or(0));
        if roots.iter().any(|&id| id != root && id != 0) {
            return None;
        }
        let mut branches = Vec::new();
        let mut compiler = Compiler {
            program,
            constants,
            branches: &mut branches,
        };
        let root_branch = compiler.value(root, 0)?;
        Some(Self {
            branches,
            root: root_branch,
            output: roots.map(|id| id == root),
            implicit,
        })
    }

    pub(super) fn evaluate<const TRACK: bool>(
        &self,
        evaluator: &Evaluator<'_>,
        pid: usize,
        point: [f32; 3],
        context: Context,
    ) -> ([f32; 6], u64) {
        let program = &evaluator.prepared.registry.programs[pid];
        let mut cursor = self.root;
        let mut visits = 0;
        let result = loop {
            if TRACK {
                visits += 1;
            }
            match self.branches[cursor] {
                Branch::Material(value) => break value,
                Branch::Test {
                    node,
                    predicate,
                    yes,
                    no,
                } => {
                    let node = &program.nodes[node as usize];
                    let value = match predicate {
                        Predicate::StoneDepth => {
                            stone_depth(node.a == 1, node.b == 1, node.p, context)
                        }
                        Predicate::VerticalAnchor => {
                            vertical_anchor(node.a == 1, node.p, point, context)
                        }
                        Predicate::VerticalChance => {
                            vertical_chance(evaluator.seed, pid, node.p, point)
                        }
                        Predicate::Hole => context.surface[1] <= 0.0,
                        Predicate::Steep => context.surface[2] > 3.0,
                        Predicate::PreliminarySurface => point[1] >= context.detail[3],
                        Predicate::NoiseRange => {
                            let noise = &program.nodes[node.a as usize];
                            let p = noise.p;
                            // Keep the original operations, including addition
                            // of a signed zero input before noise evaluation.
                            let input = program.nodes[0].p[0];
                            let value = evaluator.noise_profile::<TRACK>(
                                [
                                    point[0] * p[1] + input,
                                    point[1] * p[2] + input,
                                    point[2] * p[1] + input,
                                ],
                                p[0] as usize,
                            );
                            value >= node.p[0] && value <= node.p[1]
                        }
                    };
                    cursor = if value { yes } else { no };
                }
            }
        };
        (
            self.output
                .map(|selected| if selected { result } else { self.implicit }),
            visits,
        )
    }
}

struct Compiler<'a> {
    program: &'a Program,
    constants: &'a [Option<f32>],
    branches: &'a mut Vec<Branch>,
}

impl Compiler<'_> {
    fn push(&mut self, branch: Branch) -> Option<usize> {
        // The branch path is optional. Keep preparation bounded when a DAG's
        // shared expressions expand into many different continuations.
        if self.branches.len() == 512 {
            return None;
        }
        let index = self.branches.len();
        self.branches.push(branch);
        Some(index)
    }

    fn value(&mut self, id: u32, depth: usize) -> Option<usize> {
        if depth == 128 {
            return None;
        }
        let node = &self.program.nodes[id as usize];
        let opcode = Opcode::from_code(node.op).ok()?;
        if let Some(value) = self.constants[id as usize] {
            return if value.is_finite() {
                self.push(Branch::Material(value))
            } else {
                None
            };
        }
        match opcode {
            Opcode::Or => {
                let no = self.value(node.b, depth + 1)?;
                self.nonzero(node.a, no, depth + 1)
            }
            Opcode::And => {
                let yes = self.value(node.b, depth + 1)?;
                let no = self.push(Branch::Material(0.0))?;
                self.condition(node.a, yes, no, depth + 1)
            }
            _ => None,
        }
    }

    /// An OR returns the first nonzero value. A zero on its left instead
    /// continues to the right, including through a zero-valued AND result.
    fn nonzero(&mut self, id: u32, no: usize, depth: usize) -> Option<usize> {
        if depth == 128 {
            return None;
        }
        if let Some(value) = self.constants[id as usize] {
            return if !value.is_finite() {
                None
            } else if value == 0.0 {
                Some(no)
            } else {
                self.push(Branch::Material(value))
            };
        }
        let node = &self.program.nodes[id as usize];
        match Opcode::from_code(node.op).ok()? {
            Opcode::Or => {
                let no = self.nonzero(node.b, no, depth + 1)?;
                self.nonzero(node.a, no, depth + 1)
            }
            Opcode::And => {
                let yes = self.nonzero(node.b, no, depth + 1)?;
                self.condition(node.a, yes, no, depth + 1)
            }
            _ => {
                let yes = self.push(Branch::Material(1.0))?;
                self.condition(id, yes, no, depth + 1)
            }
        }
    }

    fn condition(&mut self, id: u32, yes: usize, no: usize, depth: usize) -> Option<usize> {
        if depth == 128 {
            return None;
        }
        if let Some(value) = self.constants[id as usize] {
            return Some(if value != 0.0 { yes } else { no });
        }
        let node: &Instruction = &self.program.nodes[id as usize];
        let predicate = match Opcode::from_code(node.op).ok()? {
            Opcode::And => {
                let right = self.condition(node.b, yes, no, depth + 1)?;
                return self.condition(node.a, right, no, depth + 1);
            }
            Opcode::Or => {
                let right = self.condition(node.b, yes, no, depth + 1)?;
                return self.condition(node.a, yes, right, depth + 1);
            }
            Opcode::Not => return self.condition(node.a, no, yes, depth + 1),
            Opcode::StoneDepth => Predicate::StoneDepth,
            Opcode::VerticalAnchor => Predicate::VerticalAnchor,
            Opcode::VerticalChance => Predicate::VerticalChance,
            Opcode::Hole => Predicate::Hole,
            Opcode::Steep => Predicate::Steep,
            Opcode::PreliminarySurface => Predicate::PreliminarySurface,
            Opcode::InRange
                if self.constants[0].is_some_and(|v| {
                    self.program.nodes[0].op == Opcode::Constant as u32
                        && v.to_bits() == self.program.nodes[0].p[0].to_bits()
                }) =>
            {
                let input = &self.program.nodes[node.a as usize];
                if input.op != Opcode::Noise as u32 || [input.a, input.b, input.c] != [0; 3] {
                    return None;
                }
                Predicate::NoiseRange
            }
            _ => return None,
        };
        self.push(Branch::Test {
            node: id,
            predicate,
            yes,
            no,
        })
    }
}

pub(super) fn stone_depth(floor: bool, surface_depth: bool, p: [f32; 4], context: Context) -> bool {
    let [stone, surface, _, _] = context.surface;
    let depth = if floor { stone } else { context.detail[0] };
    let secondary = ((context.detail[1] + 1.0) * 0.5 * p[1]) as i32 as f32;
    depth <= 1.0 + p[0] + if surface_depth { surface } else { 0.0 } + secondary
}

pub(super) fn vertical_anchor(depth: bool, p: [f32; 4], point: [f32; 3], context: Context) -> bool {
    point[1] + if depth { context.surface[0] } else { 0.0 } >= p[0] + context.surface[1] * p[1]
}

pub(super) fn vertical_chance(seed: u64, pid: usize, p: [f32; 4], point: [f32; 3]) -> bool {
    let h = super::cell_hash(
        [point[0] as i32, point[2] as i32],
        (seed as u32).wrapping_add(pid as u32 * 7919),
        (seed >> 32) as u32,
    );
    (h & 65535) as f32 / 65535.0 < ((p[1] - point[1]) / (p[1] - p[0]).max(1.0)).clamp(0.0, 1.0)
}
