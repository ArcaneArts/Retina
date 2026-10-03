//! Resident registry tables and parallel cave dressing; spatial decisions come from the GPU.
use crate::{COLUMNS, ChunkRequest, geology::CaveMask, profile::WorldProfile};
use serde::Deserialize;

#[derive(Clone, Default, Deserialize)]
#[serde(default)]
pub struct TerrainFeatures {
    pub snow_layer: u16,
    pub bands: Vec<u16>,
    pub band_frequency: f32,
    pub band_amplitude: f32,
}
#[derive(Clone, Default, Deserialize)]
#[serde(default)]
pub struct CaveFeatures {
    pub replaceable: Vec<bool>,
    pub floor: u16,
    pub clay: u16,
    pub blossom: u16,
    pub plants: Vec<u16>,
    pub vine_bodies: Vec<u16>,
    pub vine_tips: Vec<u16>,
    pub drip_up: Vec<u16>,
    pub drip_down: Vec<u16>,
    pub drip_min: u32,
    pub drip_max: u32,
    pub density: f32,
    pub plant_chance: f32,
    pub vine_max: u32,
}
impl TerrainFeatures {
    pub fn validate(&self, p: &WorldProfile) -> Result<(), String> {
        if self.snow_layer as usize >= p.materials.len()
            || self.bands.len() > 256
            || self.bands.iter().any(|m| *m as usize >= p.materials.len())
            || !self.band_frequency.is_finite()
            || self.band_frequency < 0.0
            || !self.band_amplitude.is_finite()
            || self.band_amplitude < 0.0
        {
            return Err("invalid badlands bands".into());
        }
        for b in &p.biomes {
            let f = &b.cave_features;
            if b.cave_kind > 4
                || b.lakes
                    .iter()
                    .any(|v| !v.is_finite() || !(0.0..=1.0).contains(v))
                || b.cave_depth.iter().any(|v| !v.is_finite())
                || b.lake_barrier as usize >= p.materials.len()
                || [f.floor, f.clay, f.blossom]
                    .iter()
                    .chain(&f.plants)
                    .chain(&f.vine_bodies)
                    .chain(&f.vine_tips)
                    .chain(&f.drip_up)
                    .chain(&f.drip_down)
                    .any(|m| *m as usize >= p.materials.len())
                || (!f.drip_up.is_empty() && (f.drip_up.len() != 4 || f.drip_down.len() != 4))
                || (!f.replaceable.is_empty() && f.replaceable.len() != p.materials.len())
                || f.drip_max > 128
                || f.drip_min > f.drip_max
                || f.vine_max > 128
                || !f.density.is_finite()
                || !(0.0..=1.0).contains(&f.density)
                || !f.plant_chance.is_finite()
                || !(0.0..=1.0).contains(&f.plant_chance)
            {
                return Err(format!(
                    "invalid terrain features for {} (kind {}, lakes {:?}, drip {}..{})",
                    b.id, b.cave_kind, b.lakes, f.drip_min, f.drip_max
                ));
            }
        }
        Ok(())
    }
}
fn hash(mut h: u32) -> u32 {
    h = (h ^ (h >> 16)).wrapping_mul(0x7feb352d);
    h = (h ^ (h >> 15)).wrapping_mul(0x846ca68b);
    h ^ (h >> 16)
}
fn random(r: ChunkRequest, x: i32, y: i32, z: i32, salt: u32) -> u32 {
    hash(
        (x as u32).wrapping_mul(0x9e3779b9)
            ^ (y as u32).wrapping_mul(0x85ebca6b)
            ^ (z as u32).wrapping_mul(0xc2b2ae35)
            ^ r.seed as u32
            ^ hash((r.seed >> 32) as u32)
            ^ salt,
    )
}
fn choose(values: &[u16], h: u32) -> u16 {
    values[(h as usize) % values.len()]
}

/// Each task owns one chunk. Vertical features stay in their supporting column,
/// so scheduling and region boundaries cannot clip them or change their random seed.
pub fn apply(r: ChunkRequest, p: &WorldProfile, mask: Option<&CaveMask>, blocks: &mut [u16]) {
    let Some(mask) = mask else {
        return;
    };
    if !p.biomes.iter().any(|b| b.cave_kind != 0) {
        return;
    }
    for z in 0..16 {
        for x in 0..16 {
            let wx = r.chunk_x * 16 + x;
            let wz = r.chunk_z * 16 + z;
            let column = (z * 16 + x) as usize;
            let mut layer = 6usize;
            while layer + 1 < r.height as usize {
                if blocks[layer * COLUMNS + column] != 0 {
                    layer += 1;
                    continue;
                }
                let start = layer;
                while layer < r.height as usize && blocks[layer * COLUMNS + column] == 0 {
                    layer += 1;
                }
                let end = layer;
                // A roof is required. Surface air, water and lava are never dressed as cave floors.
                if end >= r.height as usize || end - start < 2 {
                    continue;
                }
                let y = r.min_y + start as i32;
                let Some(id) = mask.biome(wx, y, wz) else {
                    continue;
                };
                let biome = &p.biomes[id as usize];
                let f = &biome.cave_features;
                if biome.cave_kind == 0 || f.floor == 0 {
                    continue;
                }
                let below = (start - 1) * COLUMNS + column;
                let above = end * COLUMNS + column;
                let floor_ok = f
                    .replaceable
                    .get(blocks[below] as usize)
                    .copied()
                    .unwrap_or(false);
                let h = random(r, wx, y, wz, 4139);
                if floor_ok {
                    blocks[below] = f.floor;
                    match biome.cave_kind {
                        1 => {
                            if f.clay != 0 && hash(h) % 19 == 0 {
                                blocks[below] = f.clay;
                            }
                            if !f.plants.is_empty() && (h >> 8) as f32 / 16777216.0 < f.plant_chance
                            {
                                blocks[start * COLUMNS + column] =
                                    choose(&f.plants, hash(h ^ 7151));
                            }
                        }
                        2 => {
                            if (h >> 8) as f32 / 16777216.0 < f.density * 0.18
                                && !f.drip_up.is_empty()
                            {
                                let length = (f.drip_min + hash(h) % (f.drip_max - f.drip_min + 1))
                                    .min((end - start - 1) as u32)
                                    as usize;
                                for i in 0..length {
                                    let remaining = length - i - 1;
                                    let shape = remaining.min(3);
                                    blocks[(start + i) * COLUMNS + column] = f.drip_up[shape];
                                }
                            }
                        }
                        _ => {}
                    }
                }
                // Use the roof's biome too: a vertical biome transition can split floor/ceiling styles.
                let Some(id) = mask.biome(wx, r.min_y + end as i32 - 1, wz) else {
                    continue;
                };
                let roof = &p.biomes[id as usize];
                let f = &roof.cave_features;
                if !f
                    .replaceable
                    .get(blocks[above] as usize)
                    .copied()
                    .unwrap_or(false)
                {
                    continue;
                }
                let h = random(r, wx, r.min_y + end as i32, wz, 9283);
                if roof.cave_kind == 1 && f.floor != 0 {
                    blocks[above] = f.floor;
                    if !f.vine_bodies.is_empty() && !f.vine_tips.is_empty() && h % 11 == 0 {
                        let length = (1 + hash(h) % f.vine_max.max(1)).min((end - start - 1) as u32)
                            as usize;
                        for i in 0..length {
                            let index = (end - i - 1) * COLUMNS + column;
                            if blocks[index] != 0 {
                                break;
                            }
                            blocks[index] = if i + 1 == length {
                                choose(&f.vine_tips, hash(h ^ i as u32))
                            } else {
                                choose(&f.vine_bodies, hash(h ^ i as u32))
                            };
                        }
                    } else if f.blossom != 0 && h % 101 == 0 {
                        blocks[(end - 1) * COLUMNS + column] = f.blossom;
                    }
                } else if roof.cave_kind == 2 && f.floor != 0 {
                    blocks[above] = f.floor;
                    if (h >> 8) as f32 / 16777216.0 < f.density * 0.18 && !f.drip_down.is_empty() {
                        let length = (f.drip_min + hash(h) % (f.drip_max - f.drip_min + 1))
                            .min((end - start - 1) as u32)
                            as usize;
                        for i in 0..length {
                            let index = (end - i - 1) * COLUMNS + column;
                            if blocks[index] != 0 {
                                break;
                            }
                            blocks[index] = f.drip_down[(length - i - 1).min(3)];
                        }
                    }
                }
            }
        }
    }
}

/// Registered snow/freezing feature, applied after vegetation. Temperature flags come from GPU columns.
pub fn snow(
    request: ChunkRequest,
    p: &WorldProfile,
    columns: &[crate::profile::Column],
    blocks: &mut [u16],
) {
    if p.terrain_features.snow_layer == 0 {
        return;
    }
    for (index, column) in columns.iter().enumerate() {
        if !p.biomes[column.biome()].snow_surface
            || column.packed & (1 << 28) == 0
            || column.packed & (1 << 29) != 0
        {
            continue;
        }
        for layer in (0..request.height as usize - 1).rev() {
            let at = layer * COLUMNS + index;
            let material = blocks[at] as usize;
            if p.heightmap_masks[material] & (1 << 4) == 0 {
                continue;
            }
            if blocks[at + COLUMNS] == 0 && blocks[at] != p.water && blocks[at] != p.geology.lava {
                blocks[at + COLUMNS] = p.terrain_features.snow_layer;
            }
            break;
        }
    }
}
