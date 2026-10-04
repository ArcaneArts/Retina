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
                || b.lake_water_barrier as usize >= p.materials.len()
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
pub fn apply(
    r: ChunkRequest,
    p: &WorldProfile,
    mask: Option<&CaveMask>,
    blocks: &mut [u16],
) -> crate::decoration::pairs::Updates {
    let mut updates = crate::decoration::pairs::Updates::default();
    apply_inner(r, p, mask, blocks, true, &mut updates);
    updates
}
fn apply_inner(
    r: ChunkRequest,
    p: &WorldProfile,
    mask: Option<&CaveMask>,
    blocks: &mut [u16],
    bounded: bool,
    updates: &mut crate::decoration::pairs::Updates,
) {
    let Some(mask) = mask else {
        return;
    };
    if r.height < 8 || !p.biomes.iter().any(|b| b.cave_kind != 0) {
        return;
    }
    // Biomes are constant in each 4×4×4 quart. Check the actual GPU field before
    // reading the much larger strided block columns; this includes every possible
    // cave-floor start, even if a surface/ceiling biome differs from its floor.
    let mut eligible = [Some((6, r.height as usize)); 16];
    let first_quart = (r.min_y + 6).div_euclid(4);
    let last_quart = (r.min_y + r.height as i32 - 2).div_euclid(4);
    if bounded {
        for z in 0..4 {
            for x in 0..4 {
                let mut bounds = None;
                for q in first_quart..=last_quart {
                    if mask
                        .biome(r.chunk_x * 16 + x * 4, q * 4, r.chunk_z * 16 + z * 4)
                        .is_some_and(|id| {
                            let biome = &p.biomes[id as usize];
                            biome.cave_kind != 0 && biome.cave_features.floor != 0
                        })
                    {
                        let start = (q * 4 - r.min_y).max(6) as usize;
                        let end = (q * 4 + 4 - r.min_y) as usize;
                        bounds = Some((bounds.map_or(start, |(first, _)| first), end));
                    }
                }
                eligible[(z * 4 + x) as usize] = bounds;
            }
        }
    }
    if eligible.iter().all(Option::is_none) {
        return;
    }
    for z in 0..16 {
        for x in 0..16 {
            let Some((first, last)) = eligible[(z / 4 * 4 + x / 4) as usize] else {
                continue;
            };
            let wx = r.chunk_x * 16 + x;
            let wz = r.chunk_z * 16 + z;
            let column = (z * 16 + x) as usize;
            let mut layer = first;
            // Do not invent a floor when the first eligible quart intersects an
            // air span whose actual start was in an ineligible biome below it.
            if first > 6 && blocks[(first - 1) * COLUMNS + column] == 0 {
                while layer < r.height as usize && blocks[layer * COLUMNS + column] == 0 {
                    layer += 1;
                }
            }
            while layer < last && layer + 1 < r.height as usize {
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
                                let index = start * COLUMNS + column;
                                let material = choose(&f.plants, hash(h ^ 7151));
                                blocks[index] = material;
                                updates.record(p, index, material);
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

#[cfg(test)]
mod scan_tests {
    use super::*;
    #[test]
    fn quart_bounds_preserve_dense_dressing_across_air_spans_and_vertical_alignment() {
        let noise = serde_json::json!({"frequency":0.001,"amplitude":1,"modifiers":[1]});
        let biome = serde_json::json!({"id":"test:plain","climate":[0,0,0,0],"terrain":[0,0,0],"top":1,"filler":1,"underwater":1,"flags":0});
        let mut cave = biome.clone();
        cave["id"] = serde_json::json!("test:cave");
        cave["cave_kind"] = serde_json::json!(1);
        cave["cave_features"] = serde_json::json!({"floor":3,"clay":4,"plants":[5],"plant_chance":1,"replaceable":[false,true,false,true,true,false]});
        let profile = WorldProfile::parse(serde_json::json!({
            "biome_scale":128,"blend":0.55,"sea_level":63,"stone":1,"water":2,"bedrock":1,"deepslate":1,"snow":1,"ice":1,
            "materials":["minecraft:air","minecraft:stone","minecraft:water","minecraft:moss_block","minecraft:clay","minecraft:short_grass"],
            "biomes":[biome,cave],"noises":[noise,noise,noise,noise],"material_flags":[0,1,2,1,1,4],"heightmap_masks":[0,63,3,63,63,3]
        }).to_string().as_bytes()).unwrap();
        let mut dressed = 0;
        for min_y in [-65, -64, -63, -1, 0, 3] {
            for height in [4u32, 31, 64, 127] {
                let r = ChunkRequest {
                    seed: 42,
                    chunk_x: -3,
                    chunk_z: 5,
                    min_y,
                    height,
                    base_height: 64.0,
                    amplitude: 48.0,
                    frequency: 0.008,
                    reserved: 0,
                };
                let width = 18usize;
                let surface_width = width + 30;
                let layers = ((min_y + height as i32 - min_y.div_euclid(4) * 4 + 3) / 4) as usize;
                let offset = (width * width * height as usize).div_ceil(32)
                    + (surface_width * surface_width).div_ceil(32);
                let mut mask = CaveMask {
                    air_only: false,
                    origin_x: r.chunk_x * 16 - 1,
                    origin_z: r.chunk_z * 16 - 1,
                    min_y,
                    height,
                    width,
                    words: vec![
                        0;
                        offset
                            + (surface_width / 4 * surface_width / 4 * layers).div_ceil(2)
                    ],
                };
                for pattern in 0..5 {
                    for i in 0..surface_width / 4 * surface_width / 4 * layers {
                        let q = i / (surface_width / 4 * surface_width / 4);
                        let active = match pattern {
                            0 => false,
                            1 => true,
                            2 => q >= 3 && q <= layers / 2,
                            3 => q % 3 == 1,
                            _ => (i * 13 + q * 7) % 11 < 4,
                        };
                        let shift = (i % 2) * 16;
                        mask.words[offset + i / 2] = (mask.words[offset + i / 2]
                            & !(65535 << shift))
                            | (u32::from(active) << shift);
                    }
                    let original: Vec<u16> = (0..r.block_count())
                        .map(|i| {
                            let y = i / COLUMNS;
                            let c = i % COLUMNS;
                            // Long spans crossing the eligibility boundary, short closed spans,
                            // water endpoints, and open surface air all coexist.
                            if y == 0 || y % 23 == 0 {
                                1
                            } else if y % 29 == 0 {
                                2
                            } else if (y + c % 7) % 17 < 13 {
                                0
                            } else {
                                1
                            }
                        })
                        .collect();
                    let mut dense = original.clone();
                    let mut bounded = original.clone();
                    apply_inner(
                        r,
                        &profile,
                        Some(&mask),
                        &mut dense,
                        false,
                        &mut crate::decoration::pairs::Updates::default(),
                    );
                    apply(r, &profile, Some(&mask), &mut bounded);
                    assert_eq!(
                        bounded, dense,
                        "min_y={min_y}, height={height}, pattern={pattern}"
                    );
                    dressed += dense.iter().zip(&original).filter(|(a, b)| a != b).count();
                }
            }
        }
        assert!(
            dressed > 1000,
            "fixtures exercise actual floor and plant writes"
        );
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
