//! Globally aligned CPU cave fields and underground biome selection. The native
//! executor consumes the same loaded programs/noise/carver recipes as the GPU.
use super::{
    fields::{Fields, Sampler},
    noise::*,
    program::Context,
};
use crate::{
    ChunkRequest,
    geology::ColumnHalo,
    profile::{Column, WorldProfile},
};
use std::collections::HashMap;
mod carvers;
#[derive(Clone, Copy, Default)]
pub(crate) struct ProofStats {
    pub air: u64,
    pub solid: u64,
    pub scalar: u64,
}
pub struct Caves<'a> {
    profile: &'a WorldProfile,
    request: ChunkRequest,
    columns: &'a ColumnHalo,
    sampler: Sampler<'a>,
    nodes: HashMap<[i32; 3], [f32; 4]>,
    exterior: HashMap<[i32; 2], [f32; 2]>,
    biomes: HashMap<[i32; 3], u32>,
    proof_stats: ProofStats,
}
impl<'a> Caves<'a> {
    pub fn new(
        profile: &'a WorldProfile,
        fields: &'a Fields,
        request: ChunkRequest,
        columns: &'a ColumnHalo,
    ) -> Self {
        Self {
            profile,
            request,
            columns,
            sampler: fields.sampler(profile, request),
            nodes: HashMap::new(),
            exterior: HashMap::new(),
            biomes: HashMap::new(),
            proof_stats: ProofStats::default(),
        }
    }
    fn enclosed(&self) -> bool {
        self.profile
            .registry_program
            .as_ref()
            .is_some_and(|p| p.enclosed())
    }
    fn registered_caves(&self) -> bool {
        self.profile.registry_program.is_some() && self.profile.geology.caves_enabled(self.profile)
    }
    fn seed(&self, salt: u32) -> u32 {
        (self.request.seed as u32 ^ mix_hash((self.request.seed >> 32) as u32)).wrapping_add(salt)
    }
    pub fn sampler(&mut self) -> &mut Sampler<'a> {
        &mut self.sampler
    }
    pub fn column(&self, x: i32, z: i32) -> Result<Column, String> {
        self.columns
            .column_at(x, z)
            .ok_or_else(|| format!("CPU cave sample {x},{z} outside column halo"))
    }
    pub fn biome_at(&mut self, p: [i32; 3], column: Column) -> Result<u32, String> {
        biome_at(self.profile, self.request, &mut self.sampler, p, column)
    }
    pub fn biome(&mut self, p: [i32; 3]) -> Result<u32, String> {
        let p = p.map(|v| v.div_euclid(4) * 4);
        if let Some(value) = self.biomes.get(&p) {
            return Ok(*value);
        }
        let column = self.column(p[0], p[2])?;
        let biome = self.biome_at(p, column)?;
        if self.biomes.len() >= 32768 {
            self.biomes.clear();
        }
        self.biomes.insert(p, biome);
        Ok(biome)
    }
    fn noise(&self, p: [f32; 3], channel: usize) -> f32 {
        let Some(n) = self.profile.geology.cave_noises.get(channel) else {
            return 0.0;
        };
        let (mut frequency, mut persistence, mut total, mut weights) = (n.frequency, 1.0, 0.0, 0.0);
        for (i, &modifier) in n.modifiers.iter().enumerate() {
            if frequency > 0.125 {
                break;
            }
            let weight = modifier * persistence;
            if weight > 0.0 {
                total += noise3(
                    p.map(|v| v * frequency),
                    self.seed(channel as u32 * 7919 + i as u32 * 1013),
                ) * weight;
                weights += weight;
            }
            frequency *= 2.0;
            persistence *= 0.5;
        }
        total / weights.max(0.0001) * n.amplitude
    }
    pub fn node(&mut self, p: [i32; 3]) -> Result<[f32; 4], String> {
        if let Some(value) = self.nodes.get(&p) {
            return Ok(*value);
        }
        let point = p.map(|v| v as f32);
        let registered = self.registered_caves();
        let chambers = if registered {
            if self.profile.registry_program.as_ref().unwrap().surface[2] == 0 {
                self.sampler.density(1, point)?
            } else {
                self.sampler.run(2, point, Context::default())?[0]
            }
        } else {
            self.noise([point[0], point[1] * 1.7, point[2]], 0)
                - self.noise([point[0], point[1] * 3.0, point[2]], 3).abs() * 0.12
                - (self.noise([point[0], point[1] * 0.25, point[2]], 5) - 0.48).max(0.0) * 1.5
        };
        let rough = self.noise(point, 4);
        let tunnel = [point[0] * 1.6, point[1], point[2] * 1.6];
        let enclosed = self.enclosed();
        let mut a = if enclosed {
            1000.0
        } else {
            self.noise(tunnel, 1) + rough * 0.06
        };
        let b = if enclosed {
            0.0
        } else {
            self.noise(tunnel, 2) - rough * 0.06
        };
        let mut ribbon = 1000.0f32;
        let min = [self.columns.origin_x * 16, self.columns.origin_z * 16];
        let max = min.map(|v| v + self.columns.side as i32 * 16 - 1);
        let x = p[0].clamp(min[0], max[0]);
        let z = p[2].clamp(min[1], max[1]);
        let column = self.column(x, z)?;
        let biome = if enclosed {
            self.biome_at([x, p[1], z], column)?
        } else {
            column.packed & 65535
        };
        for c in &self.profile.biomes[biome as usize].carvers {
            if enclosed {
                a = a.min(carvers::tunnel(point, self.seed(0), c).min(carvers::room(
                    point,
                    self.seed(0),
                    c,
                )));
            }
            if c.kind == 1 && c.probability > 0.0 {
                ribbon = ribbon.min(carvers::ravine(point, self.seed(0), c));
            }
        }
        let value = [chambers, a, b, ribbon];
        if value.iter().any(|v| !v.is_finite()) {
            return Err("non-finite CPU cave node".into());
        }
        if self.nodes.len() >= 65536 {
            self.nodes.clear();
        }
        self.nodes.insert(p, value);
        Ok(value)
    }
    pub fn values(&mut self, p: [i32; 3]) -> Result<[f32; 4], String> {
        let lower = p.map(|v| v.div_euclid(4) * 4);
        let alpha = std::array::from_fn::<_, 3, _>(|i| (p[i] - lower[i]) as f32 * 0.25);
        let mut values = [[0.0; 4]; 8];
        for (i, value) in values.iter_mut().enumerate() {
            let offset = [i & 1, (i >> 1) & 1, (i >> 2) & 1];
            if (0..3).any(|axis| offset[axis] != 0 && alpha[axis] == 0.0) {
                continue;
            }
            *value = self.node(std::array::from_fn(|axis| {
                lower[axis] + offset[axis] as i32 * 4
            }))?;
        }
        Ok(std::array::from_fn(|channel| {
            trilinear(values.map(|v| v[channel]), alpha)
        }))
    }
    pub fn density(&mut self, p: [i32; 3], sampled: f32) -> Result<f32, String> {
        if self.registered_caves() {
            let r = self.profile.registry_program.as_ref().unwrap();
            if r.surface[2] == 0
                && (r.density_composition || r.terrain_cell.iter().any(|v| v % 4 != 0))
            {
                return self.sampler.density(1, p.map(|v| v as f32));
            }
        }
        Ok(sampled)
    }
    fn exterior(&mut self, x: i32, z: i32, column: Column) -> Result<[f32; 2], String> {
        if let Some(value) = self.exterior.get(&[x, z]) {
            return Ok(*value);
        }
        let value = if self.enclosed() {
            [self.sampler.bounds()[1] as f32, 1.0]
        } else {
            let mut roof = column.height;
            if self.registered_caves()
                && self.profile.registry_program.as_ref().unwrap().surface[2] == 0
            {
                roof = column.height - 1;
                while roof > self.request.min_y + 5 {
                    let sampled = self.values([x, roof, z])?[0];
                    if self.density([x, roof, z], sampled)? >= 0.0 {
                        break;
                    }
                    roof -= 1;
                }
            }
            let aperture = noise3([x as f32 * 0.012, 0.0, z as f32 * 0.012], self.seed(43117))
                + noise3([x as f32 * 0.026, 0.0, z as f32 * 0.026], self.seed(53731)) * 0.25;
            [roof as f32, smoothstep(0.18, 0.45, aperture)]
        };
        if self.exterior.len() >= 32768 {
            self.exterior.clear();
        }
        self.exterior.insert([x, z], value);
        Ok(value)
    }
    pub(crate) fn proof_stats(&self) -> ProofStats {
        self.proof_stats
    }
    pub fn carved(&mut self, p: [i32; 3]) -> Result<bool, String> {
        let [x, y, z] = p;
        let [min, max] = self.sampler.bounds();
        if y < min || y >= max {
            return Ok(true);
        }
        if !self.profile.geology.caves_enabled(self.profile) {
            return Ok(false);
        }
        let column = self.column(x, z)?;
        let enclosed = self.enclosed();
        if y >= column.height || (!enclosed && y < self.request.min_y + 5) {
            return Ok(false);
        }
        if column.packed & (1 << 29) != 0 && y >= column.height - 4 {
            return Ok(false);
        }
        let [exterior, entrance] = self.exterior(x, z, column)?;
        let roof = (1.0 - entrance) * (1.0 - smoothstep(0.0, 24.0, (column.height - 1 - y) as f32));
        let mut values = None;
        let mut carved = false;
        if self.registered_caves() && (y as f32) < exterior {
            let r = self.profile.registry_program.as_ref().unwrap();
            let threshold = -roof * 0.20;
            let bounds = if r.surface[2] == 0 {
                self.sampler.density_certificate(1, p)
            } else {
                super::program::bounds::Bounds::UNKNOWN
            };
            if bounds.hi < threshold {
                self.proof_stats.air += 1;
                carved = true;
            } else if bounds.lo >= threshold {
                self.proof_stats.solid += 1;
            } else {
                self.proof_stats.scalar += 1;
                let sampled = if r.surface[2] == 0
                    && (r.density_composition || r.terrain_cell.iter().any(|s| s % 4 != 0))
                {
                    0.0
                } else {
                    let v = self.values(p)?;
                    values = Some(v);
                    v[0]
                };
                carved = self.density(p, sampled)? < threshold;
            }
        }
        if carved {
            return Ok(true);
        }
        if enclosed && (y <= min || y >= self.sampler.generation_top() - 7) {
            return Ok(false);
        }
        let values = match values {
            Some(v) => v,
            None => self.values(p)?,
        };
        if enclosed {
            return Ok(values[1] < 0.0 || values[3] < 1.0);
        }
        let biome = self.biome(p)?;
        for c in &self.profile.biomes[biome as usize].carvers {
            if c.count <= 0.0 || c.probability <= 0.0 {
                continue;
            }
            let margin = (c.thickness * c.vertical * 4.0).max(4.0);
            let lower = c.height.min as f32 - margin;
            let upper = c.height.max as f32 + margin;
            if c.kind == 0 && ((y as f32) < lower || (y as f32) > upper) {
                continue;
            }
            let fade = ((y as f32 - lower).min(upper - y as f32) * 0.125).clamp(0.0, 1.0);
            if c.kind == 0 {
                let strength = (c.probability * c.count).clamp(0.0, 2.0);
                let threshold = 0.43 - strength * 0.085 - c.room * 0.025 + roof * 0.35;
                let width = (0.042 + c.thickness * 0.028)
                    * c.horizontal
                    * strength.max(0.001).sqrt()
                    * (1.0 - roof * 0.92);
                let floor = ((c.floor + 1.0) * 0.03).clamp(0.0, 0.06);
                carved = carved
                    || (!self.registered_caves() && values[0] > threshold + (1.0 - fade) * 0.5)
                    || values[1].abs().max(values[2].abs() * c.vertical) < width * fade - floor;
            } else if c.kind == 1 {
                carved = carved || values[3] < 1.0 - roof * 0.25;
            } else {
                return Err(format!("unsupported CPU carver kind {}", c.kind));
            }
        }
        Ok(carved)
    }
}

#[cfg(test)]
mod tests;

pub(crate) fn biome_at(
    profile: &WorldProfile,
    request: ChunkRequest,
    sampler: &mut Sampler<'_>,
    p: [i32; 3],
    column: Column,
) -> Result<u32, String> {
    let seed = |salt: u32| {
        (request.seed as u32 ^ mix_hash((request.seed >> 32) as u32)).wrapping_add(salt)
    };
    let surface = column.packed & 65535;
    let [x, y, z] = p;
    if profile
        .registry_program
        .as_ref()
        .is_some_and(|r| r.enclosed())
    {
        return sampler.underground_biome(p.map(|v| v as f32), surface);
    }
    if y >= column.height - 12 {
        return Ok(surface);
    }
    if profile.registry_program.is_some() && sampler.has_underground() {
        return sampler.underground_biome(p.map(|v| v as f32), surface);
    }
    let p = [x as f32, y as f32, z as f32];
    let humidity =
        (0.5 + noise3([p[0] * 0.004, p[1] * 0.006, p[2] * 0.004], seed(2297))).clamp(-1.0, 1.0);
    let continental =
        (0.5 + noise3([p[0] * 0.003, p[1] * 0.004, p[2] * 0.003], seed(3571))).clamp(-1.0, 1.0);
    let erosion = noise3([p[0] * 0.002, p[1] * 0.005, p[2] * 0.002], seed(5573));
    let depth = (0.2
        + 0.7 * (column.height - y) as f32 / ((column.height - request.min_y) as f32).max(1.0))
    .clamp(0.2, 0.9);
    let (mut selected, mut best) = (surface, 1e20);
    for (i, b) in profile.biomes.iter().enumerate() {
        if b.cave_kind == 0 {
            continue;
        }
        if b.cave_kind == 3 {
            if y < -20 && erosion < b.climate[3] + 0.12 {
                return Ok(i as u32);
            }
            continue;
        }
        if depth < b.cave_depth[0] || depth > b.cave_depth[1] {
            continue;
        }
        if b.cave_kind == 4 {
            let temperature = noise3([p[0] * 0.003, p[1] * 0.004, p[2] * 0.003], seed(7919));
            let actual = [temperature, humidity, continental, erosion];
            let error = (0..4)
                .map(|j| (actual[j] - b.climate[j]).powi(2) * [2.0, 1.5, 1.0, 0.5][j])
                .sum::<f32>();
            if error < best {
                best = error;
                selected = i as u32;
            }
            continue;
        }
        let channel = if b.cave_kind == 1 { 1 } else { 2 };
        let value = if channel == 1 { humidity } else { continental };
        if value < b.climate[channel] - 0.15 {
            continue;
        }
        let error = (value - b.climate[channel]).abs();
        if error < best {
            best = error;
            selected = i as u32;
        }
    }
    Ok(selected)
}
