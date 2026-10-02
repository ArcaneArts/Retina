//! Registry-derived subsurface generation. GPU masks are shared, Rust ore planning is parallel.
use crate::{
    COLUMNS, ChunkRequest,
    decoration::Field,
    profile::{NoiseProfile, WorldProfile},
};
use rayon::prelude::*;
use serde::Deserialize;

#[derive(Clone, Default, Deserialize)]
pub struct HeightRange {
    pub min: i32,
    pub max: i32,
    pub triangle: bool,
    pub plateau: i32,
}
#[derive(Clone, Deserialize)]
pub struct ReplacementBand {
    pub min: i32,
    pub max: i32,
    pub materials: Vec<u8>,
}
#[derive(Clone, Deserialize)]
pub struct OreRecipe {
    pub id: String,
    pub size: u32,
    pub discard: f32,
    pub scattered: bool,
    pub count_min: u32,
    pub count_max: u32,
    pub rarity: u32,
    pub height: HeightRange,
    pub replacement_bands: Vec<ReplacementBand>,
}
#[derive(Clone, Deserialize)]
pub struct Carver {
    pub id: String,
    pub kind: u32,
    pub probability: f32,
    pub height: HeightRange,
    pub count: f32,
    pub thickness: f32,
    pub horizontal: f32,
    pub vertical: f32,
    pub floor: f32,
    pub room: f32,
}
#[derive(Clone, Default, Deserialize)]
pub struct GeologyProfile {
    #[serde(default)]
    pub ores: Vec<OreRecipe>,
    #[serde(default)]
    pub cave_noises: Vec<NoiseProfile>,
    #[serde(default)]
    pub carveable: Vec<bool>,
    #[serde(default)]
    pub lava: u8,
    #[serde(default)]
    pub lava_level: i32,
    #[serde(default)]
    pub geology_min_y: i32,
    #[serde(default)]
    pub geology_height: u32,
}
impl GeologyProfile {
    pub fn validate(&self, profile: &WorldProfile) -> Result<(), String> {
        let palette = profile.materials.len();
        if self.ores.is_empty() && self.cave_noises.is_empty() {
            return Ok(());
        }
        if self.carveable.len() != palette
            || self.lava as usize >= palette
            || self.cave_noises.len() != 6
            || self.geology_height == 0
            || self.geology_height > 4096
        {
            return Err("invalid geology dimensions".into());
        }
        for ore in &self.ores {
            if ore.id.is_empty()
                || ore.size > 64
                || ore.count_min > ore.count_max
                || ore.count_max > 1024
                || ore.rarity == 0
                || !(0.0..=1.0).contains(&ore.discard)
                || ore.height.min > ore.height.max
                || ore.height.plateau < 0
                || ore.replacement_bands.is_empty()
                || ore.replacement_bands.iter().any(|b| {
                    b.min > b.max
                        || b.materials.len() != palette
                        || b.materials.iter().any(|v| *v as usize >= palette)
                })
            {
                return Err(format!("invalid ore recipe {}", ore.id));
            }
        }
        for biome in &profile.biomes {
            if biome.ores.iter().any(|v| *v as usize >= self.ores.len()) || biome.carvers.len() > 4
            {
                return Err("invalid biome geology references".into());
            }
            for c in &biome.carvers {
                if c.kind > 1
                    || c.height.min > c.height.max
                    || !(0.0..=1.0).contains(&c.probability)
                    || [
                        c.count,
                        c.thickness,
                        c.horizontal,
                        c.vertical,
                        c.floor,
                        c.room,
                    ]
                    .iter()
                    .any(|v| !v.is_finite())
                    || c.thickness < 0.0
                    || c.count < 0.0
                    || c.horizontal <= 0.0
                    || c.vertical <= 0.0
                {
                    return Err(format!("invalid carver {}", c.id));
                }
            }
        }
        Ok(())
    }
    pub fn caves_enabled(&self, profile: &WorldProfile) -> bool {
        !self.cave_noises.is_empty()
            && profile
                .biomes
                .iter()
                .any(|b| b.carvers.iter().any(|c| c.probability > 0.0))
    }
    pub fn gpu_bytes(&self, profile: &WorldProfile) -> Vec<u8> {
        let mut bytes = bytemuck::cast_slice(&[profile.biomes.len() as u32, 0, 0, 0]).to_vec();
        for noise in &self.cave_noises {
            let mut data = vec![0u8; 144];
            data[..4].copy_from_slice(&noise.frequency.to_le_bytes());
            data[4..8].copy_from_slice(&noise.amplitude.to_le_bytes());
            data[8..12].copy_from_slice(&(noise.modifiers.len() as u32).to_le_bytes());
            for (i, value) in noise.modifiers.iter().enumerate() {
                data[16 + i * 4..20 + i * 4].copy_from_slice(&value.to_le_bytes());
            }
            bytes.extend(data);
        }
        for biome in &profile.biomes {
            for i in 0..4 {
                let floats = if let Some(c) = biome.carvers.get(i) {
                    [
                        c.height.min as f32,
                        c.height.max as f32,
                        c.probability,
                        c.kind as f32,
                        c.thickness,
                        c.horizontal,
                        c.vertical,
                        c.count,
                        c.floor,
                        c.room,
                        0.0,
                        0.0,
                    ]
                } else {
                    [0.0; 12]
                };
                bytes.extend_from_slice(bytemuck::cast_slice(&floats));
            }
            bytes.extend_from_slice(bytemuck::cast_slice(&biome.climate));
            bytes.extend_from_slice(bytemuck::cast_slice(&[
                biome.cave_depth[0],
                biome.cave_depth[1],
                biome.cave_kind as f32,
                biome.flags as f32,
            ]));
        }
        bytes
    }
}

/// One bit per voxel plus a one-block horizontal halo for ore exposure checks.
/// Coordinates and lattice align globally; a region and independent chunk get the same mask.
pub struct CaveMask {
    pub origin_x: i32,
    pub origin_z: i32,
    pub min_y: i32,
    pub height: u32,
    pub width: usize,
    pub words: Vec<u32>,
}
impl CaveMask {
    /// Packed GPU quart biome IDs, including the complete horizontal decoration/ore halo.
    pub fn biome(&self, x: i32, y: i32, z: i32) -> Option<u8> {
        let surface_width = self.width + 30;
        let width = surface_width / 4;
        let x = x - (self.origin_x - 15);
        let z = z - (self.origin_z - 15);
        let y = y - self.min_y.div_euclid(4) * 4;
        let layers =
            ((self.min_y + self.height as i32 - self.min_y.div_euclid(4) * 4 + 3) / 4) as usize;
        if x < 0
            || z < 0
            || y < 0
            || x as usize >= surface_width
            || z as usize >= surface_width
            || y as usize / 4 >= layers
        {
            return None;
        }
        let offset = (self.width * self.width * self.height as usize).div_ceil(32)
            + (surface_width * surface_width).div_ceil(32);
        let index = (y as usize / 4 * width + z as usize / 4) * width + x as usize / 4;
        Some(((self.words[offset + index / 4] >> ((index % 4) * 8)) & 255) as u8)
    }
    pub fn surface_carved(&self, x: i32, z: i32) -> bool {
        let x = x - (self.origin_x - 15);
        let z = z - (self.origin_z - 15);
        let width = self.width + 30;
        if x < 0 || z < 0 || x as usize >= width || z as usize >= width {
            return false;
        }
        let offset = (self.width * self.width * self.height as usize).div_ceil(32);
        let index = z as usize * width + x as usize;
        self.words[offset + index / 32] & (1 << (index % 32)) != 0
    }
    pub fn carved(&self, x: i32, y: i32, z: i32) -> bool {
        let x = x - self.origin_x;
        let z = z - self.origin_z;
        let y = y - self.min_y;
        if x < 0
            || z < 0
            || y < 0
            || x as usize >= self.width
            || z as usize >= self.width
            || y as u32 >= self.height
        {
            return false;
        }
        let index = (y as usize * self.width + z as usize) * self.width + x as usize;
        self.words[index / 32] & (1 << (index % 32)) != 0
    }
}

#[derive(Clone, Copy)]
pub struct OrePlacement {
    pub index: u32,
    pub recipe: u32,
    pub random: u32,
}
#[derive(Clone, Copy)]
struct OreBlock {
    x: i32,
    y: i32,
    z: i32,
    recipe: u32,
    random: u32,
}
struct Random(u64);
impl Random {
    fn next(&mut self) -> u32 {
        self.0 = self.0.wrapping_add(0x9e3779b97f4a7c15);
        let mut z = self.0;
        z = (z ^ (z >> 30)).wrapping_mul(0xbf58476d1ce4e5b9);
        z = (z ^ (z >> 27)).wrapping_mul(0x94d049bb133111eb);
        (z ^ (z >> 31)) as u32
    }
    fn unit(&mut self) -> f32 {
        (self.next() >> 8) as f32 / 16777216.0
    }
    fn int(&mut self, min: i32, max: i32) -> i32 {
        min + (self.next() as u64 % (max as i64 - min as i64 + 1) as u64) as i32
    }
    fn height(&mut self, h: &HeightRange) -> i32 {
        let range = h.max - h.min;
        if h.triangle && h.plateau < range {
            let a = (range - h.plateau) / 2;
            h.min + self.int(0, a) + self.int(0, range - a)
        } else {
            self.int(h.min, h.max)
        }
    }
}
fn seed(request: ChunkRequest, x: i32, z: i32, recipe: u32) -> u64 {
    request.seed
        ^ (x as u64).wrapping_mul(0x632be59bd9b4e019)
        ^ (z as u64).wrapping_mul(0x9e3779b97f4a7c15)
        ^ (recipe as u64).wrapping_mul(0x94d049bb133111eb)
}

/// Indexed parallel planning preserves anchor order, including overlapping replacement features.
pub fn plan(
    field: &Field,
    profile: &WorldProfile,
    request: ChunkRequest,
    side: usize,
    mask: Option<&CaveMask>,
) -> Vec<Vec<OrePlacement>> {
    let generate = |index: usize| {
        let cx = field.origin_x + (index % field.side) as i32;
        let cz = field.origin_z + (index / field.side) as i32;
        let mut blocks = Vec::new();
        for (id, recipe) in profile.geology.ores.iter().enumerate() {
            let mut random = Random(seed(request, cx, cz, id as u32));
            if random.next() % recipe.rarity != 0 {
                continue;
            }
            let tries = random.int(recipe.count_min as i32, recipe.count_max as i32);
            for _ in 0..tries {
                let x = cx * 16 + random.int(0, 15);
                let z = cz * 16 + random.int(0, 15);
                let y = random.height(&recipe.height);
                let Some(column) = field.column(x, z) else {
                    continue;
                };
                let biome = mask
                    .and_then(|m| m.biome(x, y, z))
                    .map_or(column.biome(), |b| b as usize);
                if !profile.biomes[biome].ores.contains(&(id as u32)) {
                    continue;
                }
                vein(recipe, id as u32, x, y, z, &mut random, &mut blocks);
            }
        }
        blocks
    };
    let anchors: Vec<_> = if side > 1 {
        (0..field.side * field.side)
            .into_par_iter()
            .map(generate)
            .collect()
    } else {
        (0..field.side * field.side).map(generate).collect()
    };
    let mut targets = vec![Vec::new(); side * side];
    for b in anchors.into_iter().flatten() {
        let cx = b.x.div_euclid(16) - request.chunk_x;
        let cz = b.z.div_euclid(16) - request.chunk_z;
        let y = b.y - request.min_y;
        if cx < 0
            || cz < 0
            || cx as usize >= side
            || cz as usize >= side
            || y < 0
            || y >= request.height as i32
        {
            continue;
        }
        targets[cz as usize * side + cx as usize].push(OrePlacement {
            index: y as u32 * 256 + b.z.rem_euclid(16) as u32 * 16 + b.x.rem_euclid(16) as u32,
            recipe: b.recipe,
            random: b.random,
        });
    }
    targets
}
fn vein(
    recipe: &OreRecipe,
    id: u32,
    x: i32,
    y: i32,
    z: i32,
    random: &mut Random,
    out: &mut Vec<OreBlock>,
) {
    let mut emit = |x, y, z, random: &mut Random| {
        out.push(OreBlock {
            x,
            y,
            z,
            recipe: id,
            random: random.next(),
        })
    };
    if recipe.scattered {
        let tries = random.int(0, recipe.size as i32);
        for i in 0..tries {
            let spread = i.min(7) as f32;
            let dx = ((random.unit() - random.unit()) * spread).round() as i32;
            let dy = ((random.unit() - random.unit()) * spread).round() as i32;
            let dz = ((random.unit() - random.unit()) * spread).round() as i32;
            emit(x + dx, y + dy, z + dz, random);
        }
        return;
    }
    let angle = random.unit() * std::f32::consts::PI;
    let dx = angle.sin() * recipe.size as f32 / 8.0;
    let dz = angle.cos() * recipe.size as f32 / 8.0;
    let a = [
        x as f32 + dx,
        y as f32 + random.int(-2, 0) as f32,
        z as f32 + dz,
    ];
    let b = [
        x as f32 - dx,
        y as f32 + random.int(-2, 0) as f32,
        z as f32 - dz,
    ];
    // Vanilla's containment pruning avoids testing the interiors of hidden ellipsoids.
    let mut spheres: Vec<[f32; 4]> = (0..recipe.size)
        .map(|i| {
            let t = i as f32 / recipe.size as f32;
            [
                a[0] + (b[0] - a[0]) * t,
                a[1] + (b[1] - a[1]) * t,
                a[2] + (b[2] - a[2]) * t,
                ((std::f32::consts::PI * t).sin() + 1.0) * random.unit() * recipe.size as f32
                    / 32.0
                    + 0.5,
            ]
        })
        .collect();
    for i in 0..spheres.len() {
        if spheres[i][3] < 0.0 {
            continue;
        }
        for j in i + 1..spheres.len() {
            if spheres[j][3] < 0.0 {
                continue;
            }
            let dr = spheres[i][3] - spheres[j][3];
            let distance: f32 = (0..3)
                .map(|k| (spheres[i][k] - spheres[j][k]).powi(2))
                .sum();
            if dr * dr > distance {
                if dr > 0.0 {
                    spheres[j][3] = -1.0;
                } else {
                    spheres[i][3] = -1.0;
                    break;
                }
            }
        }
    }
    let reach =
        (recipe.size as f32 / 8.0).ceil() as i32 + (recipe.size as f32 / 16.0 + 0.5).ceil() as i32;
    let width = (reach * 2 + 3) as usize;
    let bottom = [x - reach - 1, y - reach - 3, z - reach - 1];
    let mut tested = vec![0u64; (width * width * (width + 4)).div_ceil(64)];
    for sphere in spheres.iter().filter(|s| s[3] > 0.0) {
        let radius = sphere[3];
        let inverse = 1.0 / radius;
        for yy in (sphere[1] - radius).floor() as i32..=(sphere[1] + radius).floor() as i32 {
            let dy2 = ((yy as f32 + 0.5 - sphere[1]) * inverse).powi(2);
            if dy2 >= 1.0 {
                continue;
            }
            for zz in (sphere[2] - radius).floor() as i32..=(sphere[2] + radius).floor() as i32 {
                let yz2 = dy2 + ((zz as f32 + 0.5 - sphere[2]) * inverse).powi(2);
                if yz2 >= 1.0 {
                    continue;
                }
                for xx in (sphere[0] - radius).floor() as i32..=(sphere[0] + radius).floor() as i32
                {
                    if yz2 + ((xx as f32 + 0.5 - sphere[0]) * inverse).powi(2) >= 1.0 {
                        continue;
                    }
                    let index = (((yy - bottom[1]) as usize * width + (zz - bottom[2]) as usize)
                        * width)
                        + (xx - bottom[0]) as usize;
                    let bit = 1u64 << (index % 64);
                    if tested[index / 64] & bit == 0 {
                        tested[index / 64] |= bit;
                        emit(xx, yy, zz, random);
                    }
                }
            }
        }
    }
}

fn cave_material(profile: &WorldProfile, column: crate::profile::Column, y: i32) -> u8 {
    if y < profile.geology.lava_level {
        profile.geology.lava
    } else if profile.biomes[column.biome()].flags & 4 != 0 && y < profile.sea_level {
        profile.water
    } else {
        0
    }
}
/// Called on each CPU worker after base assembly; no noise evaluation or interpolation in Rust.
pub fn apply(
    request: ChunkRequest,
    field: &Field,
    profile: &WorldProfile,
    mask: Option<&CaveMask>,
    ores: &[OrePlacement],
    blocks: &mut [u8],
) {
    if let Some(mask) = mask {
        for (layer, row) in blocks.chunks_exact_mut(COLUMNS).enumerate() {
            let y = request.min_y + layer as i32;
            for (i, block) in row.iter_mut().enumerate() {
                let x = request.chunk_x * 16 + (i % 16) as i32;
                let z = request.chunk_z * 16 + (i / 16) as i32;
                if profile.geology.carveable[*block as usize] && mask.carved(x, y, z) {
                    *block = cave_material(profile, field.column(x, z).unwrap(), y);
                }
            }
        }
    }
    for ore in ores {
        let index = ore.index as usize;
        let recipe = &profile.geology.ores[ore.recipe as usize];
        let y = request.min_y + (index / COLUMNS) as i32;
        let replacement = recipe
            .replacement_bands
            .iter()
            .find(|b| b.min <= y && y <= b.max)
            .map_or(0, |b| b.materials[blocks[index] as usize]);
        if replacement == 0 {
            continue;
        }
        if recipe.discard > 0.0 && (ore.random >> 8) as f32 / 16777216.0 < recipe.discard {
            let x = request.chunk_x * 16 + (index % 16) as i32;
            let z = request.chunk_z * 16 + ((index % 256) / 16) as i32;
            let y = request.min_y + (index / 256) as i32;
            let exposed = [
                (1, 0, 0),
                (-1, 0, 0),
                (0, 1, 0),
                (0, -1, 0),
                (0, 0, 1),
                (0, 0, -1),
            ]
            .iter()
            .any(|&(dx, dy, dz)| {
                let (nx, ny, nz) = (x + dx, y + dy, z + dz);
                let Some(c) = field.column(nx, nz) else {
                    return true;
                };
                let mut material = c.material(ny, request.min_y, Some(profile));
                if mask.is_some_and(|m| m.carved(nx, ny, nz))
                    && profile.geology.carveable[material as usize]
                {
                    material = cave_material(profile, c, ny);
                }
                material == 0
            });
            if exposed {
                continue;
            }
        }
        blocks[index] = replacement;
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    #[test]
    fn ore_ellipsoids_are_unique_and_fit_the_neighbor_chunk_halo() {
        for size in 1..=64 {
            let recipe = OreRecipe {
                id: "test:ore".into(),
                size,
                discard: 0.0,
                scattered: false,
                count_min: 1,
                count_max: 1,
                rarity: 1,
                height: Default::default(),
                replacement_bands: vec![],
            };
            for seed in 0..32 {
                let mut blocks = Vec::new();
                vein(&recipe, 0, -1, -32, -1, &mut Random(seed), &mut blocks);
                let positions: std::collections::HashSet<_> =
                    blocks.iter().map(|b| (b.x, b.y, b.z)).collect();
                assert_eq!(
                    positions.len(),
                    blocks.len(),
                    "ellipsoid overlap duplicated a voxel"
                );
                assert!(
                    blocks.iter().all(|b| (b.x + 1).abs() <= 16
                        && (b.z + 1).abs() <= 16
                        && (b.y + 32).abs() <= 16),
                    "vein extends beyond the shared halo"
                );
            }
        }
    }
    #[test]
    fn ore_height_distributions_keep_their_registered_triangle_and_plateau() {
        let range = HeightRange {
            min: -64,
            max: 64,
            triangle: true,
            plateau: 0,
        };
        let mut random = Random(42);
        let mut center: i32 = 0;
        let mut edge = 0;
        for _ in 0..10000 {
            let y = random.height(&range);
            assert!((-64..=64).contains(&y));
            if y.abs() < 16 {
                center += 1;
            }
            if y.abs() > 48 {
                edge += 1;
            }
        }
        assert!(center > edge * 3, "triangle lost its central peak");
        let uniform = HeightRange {
            plateau: 128,
            ..range
        };
        let mut center: i32 = 0;
        let mut edge = 0;
        for _ in 0..10000 {
            let y = random.height(&uniform);
            if y.abs() < 16 {
                center += 1;
            }
            if y.abs() > 48 {
                edge += 1;
            }
        }
        assert!(
            (center - edge).abs() < 500,
            "wide plateau should be uniform"
        );
    }
}
