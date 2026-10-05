//! Resident bytecode for registered density functions and surface predicates.
use serde::Deserialize;
pub(crate) mod composition;
pub(crate) mod interpolation;
pub(crate) mod lake_sparse;
pub(crate) use interpolation::Field as Interpolation;
#[derive(Clone, Deserialize)]
pub struct Instruction {
    pub op: u32,
    pub a: u32,
    pub b: u32,
    pub c: u32,
    pub p: [f32; 4],
}
impl Instruction {
    pub(crate) fn dependencies(&self, points: &[[f32; 4]]) -> Vec<u32> {
        let mut result = match self.op {
            1 | 23 | 24 | 27 | 28 | 31 => vec![self.a, self.b, self.c],
            4..=10 | 40 | 41 => vec![self.a, self.b],
            11..=22 | 25 | 30 | 43 | 44 | 53 => vec![self.a],
            _ => vec![],
        };
        if self.op == 25 {
            result.extend(
                points[self.b as usize..(self.b + self.c) as usize]
                    .iter()
                    .map(|p| p[2] as u32),
            );
        }
        result
    }
}
#[derive(Clone, Deserialize)]
pub struct Program {
    pub nodes: Vec<Instruction>,
    pub roots: Vec<u32>,
}
pub(crate) const COMPACT_VALUES: usize = 64;

/// Eager interpreter values stay live through their last input read, and roots
/// stay live through the final output copy. Original instruction indices remain
/// unchanged for specialized graphs and the shared spline point table.
struct Registers {
    slots: Vec<u32>,
    capacity: usize,
}
impl Program {
    fn registers(&self, points: &[[f32; 4]]) -> Registers {
        let count = self.nodes.len();
        let mut last: Vec<usize> = (0..count).collect();
        for (i, node) in self.nodes.iter().enumerate() {
            for child in node.dependencies(points) {
                last[child as usize] = i;
            }
        }
        for &root in &self.roots {
            last[root as usize] = count;
        }
        // Unspecified outputs read node zero, rather than a literal zero.
        if self.roots.len() < 6 {
            last[0] = count;
        }
        let mut release = vec![Vec::new(); count + 1];
        let mut available = std::collections::BTreeSet::new();
        let mut result = Registers {
            slots: Vec::with_capacity(count),
            capacity: 0,
        };
        for (i, &end) in last.iter().enumerate() {
            available.extend(release[i].drain(..));
            let slot = available.pop_first().unwrap_or_else(|| {
                let slot = result.capacity as u32;
                result.capacity += 1;
                slot
            });
            result.slots.push(slot);
            // Do not overwrite operands while evaluating the instruction body.
            release[(end + 1).min(count)].push(slot);
        }
        result
    }
}

/// Keep the verbatim interpreter as a reference for specialization and parity
/// tests. Only this executable variant translates accesses to resident slots.
pub(crate) fn interpreter_source(capacity: usize) -> String {
    interpreter_source_depth(capacity, 0)
}
pub(crate) fn interpreter_source_depth(capacity: usize, depth: usize) -> String {
    interpreter_source_density(capacity, depth, false)
}
pub(crate) fn interpreter_source_density(
    capacity: usize,
    depth: usize,
    composition: bool,
) -> String {
    let source = include_str!("program.wgsl");
    let start = source.find("fn run_program(").unwrap();
    let end = source.find("fn density_floor_div(").unwrap();
    let body = interpreter_body(capacity, depth, "run_program", true);
    density_composition_source(
        &format!(
            "{}{}{}{}{}",
            &source[..start],
            body,
            interpolation::prepass(depth, false, 0),
            density_bounds_source(capacity),
            &source[end..]
        ),
        composition,
    )
}
pub(crate) fn density_composition_source(source: &str, enabled: bool) -> String {
    if enabled {
        source.to_owned()
    } else {
        // Remove the experimental branch from legacy kernels at compilation,
        // so unused point evaluators cannot inflate register pressure.
        source.replace(
            "fn density_composed(r:Request)->bool {return (r.padding&(1u<<26u))!=0u;}",
            "fn density_composed(r:Request)->bool {return false;}",
        )
    }
}
pub(crate) fn density_bounds_source(capacity: usize) -> String {
    include_str!("density_bounds.wgsl").replace("BOUND_VALUES", &capacity.to_string())
}
pub(crate) fn interpreter_body(
    capacity: usize,
    depth: usize,
    prefix: &str,
    compact: bool,
) -> String {
    let source = include_str!("program.wgsl");
    let start = source.find("fn run_program(").unwrap();
    let end = source.find("fn density_floor_div(").unwrap();
    let body = &source[start..end];
    if !compact {
        return interpolation::interpreter(body, depth, prefix);
    }
    let mut mapped = String::new();
    let mut at = 0;
    while let Some(index) = body[at..].find("values[") {
        let open = at + index + "values[".len();
        mapped.push_str(&body[at..open]);
        let mut depth = 1;
        let mut close = open;
        for (i, ch) in body[open..].char_indices() {
            match ch {
                '[' => depth += 1,
                ']' => depth -= 1,
                _ => {}
            }
            if depth == 0 {
                close = open + i;
                break;
            }
        }
        assert_eq!(depth, 0, "unclosed interpreter value access");
        mapped.push_str("bytecode[registers+");
        mapped.push_str(&body[open..close]);
        mapped.push_str("]]");
        at = close + 1;
    }
    mapped.push_str(&body[at..]);
    mapped = mapped.replace(
        "var values:array<f32,1024>;",
        &format!("let registers=offset+count*8u;var values:array<f32,{capacity}>;"),
    );
    interpolation::interpreter(&mapped, depth, prefix)
}
#[derive(Clone, Deserialize)]
pub struct Noise {
    #[serde(default = "unit_scale")]
    pub horizontal_scale: f32,
    pub frequency: f32,
    pub amplitude: f32,
    pub salt: i32,
    pub coefficients: Vec<f32>,
}
#[derive(Clone, Deserialize)]
pub struct RegistryProgram {
    pub programs: Vec<Program>,
    #[serde(default)]
    pub interpolations: Vec<Interpolation>,
    pub noises: Vec<Noise>,
    pub points: Vec<[f32; 4]>,
    pub surface: [i32; 3],
    #[serde(default = "default_terrain_cell")]
    pub terrain_cell: [u32; 2],
    /// Compose registered interpolation fields at actual block positions.
    /// Profiles without this option retain their original final-field lattice.
    #[serde(default)]
    pub density_composition: bool,
    #[serde(default)]
    pub surface_noises: [u32; 3],
    #[serde(default)]
    pub material_layers: bool,
    /// Complete base-block substrate for the existing one-chunk decoration halo.
    /// Older saved profiles retain their representative-column placement checks.
    #[serde(default)]
    pub material_halo: bool,
    #[serde(default)]
    pub aquifer: Option<AquiferProgram>,
}
#[derive(Clone, Deserialize)]
pub struct AquiferProgram {
    pub enabled: bool,
    #[serde(default)]
    pub program: u32,
    #[serde(default = "default_aquifer_surface")]
    pub surface: [i32; 3],
}
fn default_aquifer_surface() -> [i32; 3] {
    [0, 1, 1]
}
fn unit_scale() -> f32 {
    1.0
}
fn default_terrain_cell() -> [u32; 2] {
    [4, 8]
}
impl RegistryProgram {
    pub(crate) fn all_programs(&self) -> impl Iterator<Item = &Program> {
        self.programs
            .iter()
            .chain(self.interpolations.iter().map(|f| &f.input))
    }
    pub(crate) fn interpolation_depth(&self) -> usize {
        interpolation::depths(&self.interpolations)
            .into_iter()
            .max()
            .unwrap_or(0)
    }
    pub(crate) fn scratch_values(&self) -> usize {
        self.all_programs()
            .map(|program| program.registers(&self.points).capacity)
            .max()
            .unwrap_or(0)
    }
    pub fn validate(&self, biomes: usize) -> Result<(), String> {
        if self.material_halo && !self.material_layers {
            return Err("decoration substrate requires GPU material layers".into());
        }
        let extra = if self.aquifer.as_ref().is_some_and(|a| a.enabled) {
            5
        } else {
            0
        };
        if let Some(a) = &self.aquifer {
            if !self.material_layers
                || a.enabled
                    && (a.program as usize != biomes + 3
                        || a.surface[1] <= 0
                        || !(0..=1).contains(&a.surface[2]))
            {
                return Err("invalid registered aquifer program".into());
            }
        }
        if self
            .surface_noises
            .iter()
            .any(|id| *id as usize >= self.noises.len())
            || self.points.iter().flatten().any(|x| !x.is_finite())
            || self.programs.len() != biomes + 3 + extra
            || self.surface[1] <= 0
            || self.surface[2] < 0
            || self.surface[2] > 1
            || self
                .terrain_cell
                .iter()
                .any(|s| *s == 0 || *s > i32::MAX as u32)
        {
            return Err("invalid registry GPU program dimensions".into());
        }
        for (pid, program) in self.all_programs().enumerate() {
            if program.nodes.is_empty()
                || program.nodes.len() > 1024
                || program.roots.len() > 6
                || program
                    .roots
                    .iter()
                    .any(|x| *x as usize >= program.nodes.len())
            {
                return Err("invalid registry GPU program roots".into());
            }
            for (i, n) in program.nodes.iter().enumerate() {
                if n.p.iter().any(|x| !x.is_finite()) {
                    return Err("nonfinite GPU instruction".into());
                }
                let deps: Vec<u32> = match n.op {
                    1 | 27 | 28 | 31 => vec![n.a, n.b, n.c],
                    4..=10 | 41 => vec![n.a, n.b],
                    11..=22 | 30 | 43 | 44 => vec![n.a],
                    23 | 24 => vec![n.a, n.b, n.c],
                    25 => vec![n.a],
                    40 => vec![n.a, n.b],
                    53 => vec![n.a],
                    _ => vec![],
                };
                if deps.iter().any(|x| *x as usize >= i) {
                    return Err("GPU instruction must reference earlier values".into());
                }
                if matches!(n.op, 1 | 27) && (n.p[0] as usize >= self.noises.len())
                    || n.op == 2 && n.a as usize >= self.noises.len()
                {
                    return Err("invalid GPU noise reference".into());
                }
                if n.op == 25
                    && (n.c == 0
                        || n.b as usize + n.c as usize > self.points.len()
                        || self.points[n.b as usize..(n.b + n.c) as usize]
                            .iter()
                            .any(|p| p[2] < 0.0 || p[2] as usize >= i))
                {
                    return Err("invalid GPU spline points".into());
                }
                if n.op == 29 && n.a > 2 {
                    return Err("invalid GPU coordinate axis".into());
                }
                if n.op == 28 {
                    let field = n.p[0] as usize;
                    let limit = if pid < self.programs.len() {
                        self.interpolations.len()
                    } else {
                        pid - self.programs.len()
                    };
                    if n.p[0] < 0.0 || n.p[0] != field as f32 || field >= limit {
                        return Err("interpolation fields must reference earlier subgraphs".into());
                    }
                }
                if !matches!(n.op,0..=31|40..=54) {
                    return Err("unknown GPU opcode".into());
                }
            }
        }
        if self.interpolations.iter().any(|f| {
            f.input.roots.len() != 1 || f.cell.iter().any(|s| *s == 0 || *s > i32::MAX as u32)
        }) {
            return Err("invalid registered interpolation field".into());
        }
        for n in &self.noises {
            if n.coefficients.is_empty()
                || n.coefficients.len() > 32
                || !n.horizontal_scale.is_finite()
                || n.horizontal_scale <= 0.0
                || !n.frequency.is_finite()
                || n.frequency <= 0.0
                || !n.amplitude.is_finite()
                || n.coefficients.iter().any(|x| !x.is_finite())
            {
                return Err("invalid GPU program noise".into());
            }
        }
        Ok(())
    }
    pub fn bytes(profile: Option<&crate::profile::WorldProfile>) -> Vec<u8> {
        let Some(p) = profile.and_then(|p| p.registry_program.as_ref()) else {
            return vec![0; 32];
        };
        let mut words = vec![0u32; 16 + p.all_programs().count() * 8];
        words[0] = p.all_programs().count() as u32;
        words[8] = profile.unwrap().sea_level as u32;
        words[9..12].copy_from_slice(&p.surface_noises);
        words[3] = p.surface[0] as u32;
        words[4] = p.surface[1] as u32;
        if p.material_layers {
            words[4] |= 1 << 31;
        }
        words[5] = p.surface[2] as u32;
        for (i, program) in p.all_programs().enumerate() {
            words[16 + i * 8] = words.len() as u32;
            words[17 + i * 8] = program.nodes.len() as u32;
            for (j, root) in program.roots.iter().enumerate() {
                words[18 + i * 8 + j] = *root;
            }
            for n in &program.nodes {
                words.extend([n.op, n.a, n.b, n.c]);
                words.extend(n.p.map(f32::to_bits));
            }
            words.extend(program.registers(&p.points).slots);
        }
        words[12] = words.len() as u32;
        words[13] = p.interpolations.len() as u32;
        words[14] = p.interpolation_depth() as u32;
        words[15] = p.programs.len() as u32;
        let depths = interpolation::depths(&p.interpolations);
        for (i, field) in p.interpolations.iter().enumerate() {
            words.extend([
                p.programs.len() as u32 + i as u32,
                field.cell[0],
                field.cell[1],
                depths[i] as u32,
            ]);
        }
        words[1] = words.len() as u32;
        for n in &p.noises {
            words.extend([
                n.frequency.to_bits(),
                n.amplitude.to_bits(),
                n.coefficients.len() as u32,
                n.salt as u32,
            ]);
            let mut mods = [0f32; 32];
            mods[..n.coefficients.len()].copy_from_slice(&n.coefficients);
            words.extend(mods.map(f32::to_bits));
            words.push(n.horizontal_scale.to_bits());
        }
        words[2] = words.len() as u32;
        for v in &p.points {
            words.extend(v.map(f32::to_bits));
        }
        words[6] = words.len() as u32;
        if let Some(profile) = profile {
            words[7] = profile.terrain_features.bands.len() as u32;
            words.extend(profile.terrain_features.bands.iter().map(|x| *x as u32));
        }
        bytemuck::cast_slice(&words).to_vec()
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    fn n(op: u32, a: u32, b: u32, c: u32) -> Instruction {
        Instruction {
            op,
            a,
            b,
            c,
            p: [0.0; 4],
        }
    }
    fn verify(program: &Program, points: &[[f32; 4]]) -> usize {
        let registers = program.registers(points);
        let mut owners = vec![usize::MAX; registers.capacity];
        for (i, node) in program.nodes.iter().enumerate() {
            for child in node.dependencies(points) {
                assert_eq!(
                    owners[registers.slots[child as usize] as usize], child as usize,
                    "overwritten input at instruction {i}"
                );
            }
            owners[registers.slots[i] as usize] = i;
        }
        for root in (0..6).map(|i| program.roots.get(i).copied().unwrap_or(0)) {
            assert_eq!(
                owners[registers.slots[root as usize] as usize],
                root as usize
            );
        }
        registers.capacity
    }
    #[test]
    fn implicit_root_zero_and_spline_children_remain_live() {
        let points = [[-1.0, 0.0, 1.0, 0.0], [1.0, 0.0, 3.0, 0.0]];
        let program = Program {
            nodes: vec![
                n(0, 0, 0, 0),
                n(0, 0, 0, 0),
                n(4, 1, 1, 0),
                n(0, 0, 0, 0),
                n(0, 0, 0, 0),
                n(25, 2, 0, 2),
                n(4, 5, 5, 0),
            ],
            roots: vec![6],
        };
        assert_eq!(verify(&program, &points), 5);
        let registers = program.registers(&points);
        assert_eq!(registers.slots[0], 0);
        assert_ne!(registers.slots[1], registers.slots[4]);
    }
    #[test]
    fn long_chain_reuses_scratch_without_a_graph_length_limit() {
        let mut nodes = vec![n(0, 0, 0, 0)];
        nodes.extend((1..1024).map(|i| n(4, i - 1, i - 1, 0)));
        let mut program = Program {
            nodes,
            roots: vec![1023],
        };
        assert_eq!(verify(&program, &[]), 3);
        program.roots = vec![1023; 6];
        assert_eq!(verify(&program, &[]), 2);
    }
    #[test]
    fn wide_programs_keep_all_their_inputs() {
        let mut nodes = vec![n(0, 0, 0, 0); 512];
        nodes.extend((0..512).map(|i| n(4, i, i, 0)));
        let program = Program {
            nodes,
            roots: vec![1023; 6],
        };
        assert_eq!(verify(&program, &[]), 513);
        assert!(program.registers(&[]).capacity > COMPACT_VALUES);
    }
    #[test]
    fn mapped_source_handles_nested_root_and_spline_addresses() {
        let source = interpreter_source(COMPACT_VALUES);
        assert!(source.contains("var values:array<f32,64>"));
        assert!(source.contains("values[bytecode[registers+a]]"));
        assert!(source.contains("values[bytecode[registers+i]]=result"));
        assert!(source.contains("values[bytecode[registers+bytecode[descriptor+2u+i]]]"));
        assert!(
            source.contains("values[bytecode[registers+u32(bitcast<f32>(bytecode[left+2u]))]]")
        );
        assert!(!source.contains("values[a]"));
        assert!(interpreter_source(1024).contains("var values:array<f32,1024>"));
    }
}
