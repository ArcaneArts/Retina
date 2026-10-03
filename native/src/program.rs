//! Resident bytecode for registered density functions and surface predicates.
use serde::Deserialize;
#[derive(Clone, Deserialize)]
pub struct Instruction {
    pub op: u32,
    pub a: u32,
    pub b: u32,
    pub c: u32,
    pub p: [f32; 4],
}
#[derive(Clone, Deserialize)]
pub struct Program {
    pub nodes: Vec<Instruction>,
    pub roots: Vec<u32>,
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
    pub noises: Vec<Noise>,
    pub points: Vec<[f32; 4]>,
    pub surface: [i32; 3],
    #[serde(default = "default_terrain_cell")]
    pub terrain_cell: [u32; 2],
    #[serde(default)]
    pub surface_noises: [u32; 3],
}
fn unit_scale() -> f32 {
    1.0
}
fn default_terrain_cell() -> [u32; 2] {
    [4, 8]
}
impl RegistryProgram {
    pub fn validate(&self, biomes: usize) -> Result<(), String> {
        if self
            .surface_noises
            .iter()
            .any(|id| *id as usize >= self.noises.len())
            || self.points.iter().flatten().any(|x| !x.is_finite())
            || self.programs.len() != biomes + 3
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
        for program in &self.programs {
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
                    1 => vec![n.a, n.b, n.c],
                    4..=10 | 41 => vec![n.a, n.b],
                    11..=22 | 43 | 44 => vec![n.a],
                    23 | 24 => vec![n.a, n.b, n.c],
                    25 => vec![n.a],
                    40 => vec![n.a, n.b],
                    _ => vec![],
                };
                if deps.iter().any(|x| *x as usize >= i) {
                    return Err("GPU instruction must reference earlier values".into());
                }
                if n.op == 1 && (n.p[0] as usize >= self.noises.len())
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
                if !matches!(n.op,0..=26|40..=51) {
                    return Err("unknown GPU opcode".into());
                }
            }
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
        let mut words = vec![0u32; 12 + p.programs.len() * 8];
        words[0] = p.programs.len() as u32;
        words[8] = profile.unwrap().sea_level as u32;
        words[9..12].copy_from_slice(&p.surface_noises);
        words[3] = p.surface[0] as u32;
        words[4] = p.surface[1] as u32;
        words[5] = p.surface[2] as u32;
        for (i, program) in p.programs.iter().enumerate() {
            words[12 + i * 8] = words.len() as u32;
            words[13 + i * 8] = program.nodes.len() as u32;
            for (j, root) in program.roots.iter().enumerate() {
                words[14 + i * 8 + j] = *root;
            }
            for n in &program.nodes {
                words.extend([n.op, n.a, n.b, n.c]);
                words.extend(n.p.map(f32::to_bits));
            }
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
