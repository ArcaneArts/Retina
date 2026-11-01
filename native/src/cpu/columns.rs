//! Coherent CPU biome sites, terrain columns, lake geometry and representative
//! surface rules. Complete material columns/cavity masks are assembled separately.
use super::{
    fields::{Fields, Sampler},
    noise::*,
    program::Context,
};
use crate::{
    ChunkRequest,
    profile::{Column, NoiseProfile, WorldProfile},
};
use std::collections::HashMap;
#[derive(Clone, Copy)]
struct Site {
    point: [f32; 2],
    biome: u32,
    climate: [f32; 4],
}
#[derive(Clone, Copy)]
struct Sample {
    height: i32,
    biome: u32,
}
#[derive(Clone, Copy)]
struct Lake {
    radius: f32,
    level: i32,
    biome: u32,
    eligible: bool,
    lava: bool,
}
pub struct Columns<'a> {
    profile: &'a WorldProfile,
    request: ChunkRequest,
    fields: &'a Fields,
    sampler: Sampler<'a>,
    targeted: Vec<bool>,
    sites: HashMap<[i32; 2], Site>,
    lakes: HashMap<[i32; 2], Lake>,
    climate_hint: Option<usize>,
}
impl<'a> Columns<'a> {
    pub fn new(profile: &'a WorldProfile, fields: &'a Fields, request: ChunkRequest) -> Self {
        let targeted = (0..profile.biomes.len())
            .map(|i| profile.climate_targets.iter().any(|t| t.biome == i as u32))
            .collect();
        Self {
            profile,
            request,
            fields,
            sampler: fields.sampler(profile, request),
            targeted,
            sites: HashMap::new(),
            lakes: HashMap::new(),
            climate_hint: None,
        }
    }
    fn seed(&self, salt: u32) -> u32 {
        (self.request.seed as u32).wrapping_add(salt)
    }
    fn high(&self) -> u32 {
        (self.request.seed >> 32) as u32
    }
    fn enclosed(&self) -> bool {
        self.profile
            .registry_program
            .as_ref()
            .is_some_and(|r| r.enclosed())
    }
    fn stack(&self, point: [f32; 2], noise: &NoiseProfile, salt: u32) -> f32 {
        let (mut frequency, mut persistence, mut sum, mut weights) =
            (noise.frequency, 1.0, 0.0, 0.0);
        for (octave, &modifier) in noise.modifiers.iter().enumerate() {
            let weight = modifier * persistence;
            if weight > 0.0 {
                sum += simplex(
                    point.map(|v| v * frequency),
                    self.seed(salt + octave as u32 * 1013),
                    self.high(),
                ) * weight;
                weights += weight;
            }
            frequency *= 2.0;
            persistence *= 0.5;
        }
        (sum / weights.max(0.0001) * noise.amplitude * 1.6).clamp(-1.0, 1.0)
    }
    fn site(&mut self, cell: [i32; 2]) -> Result<Site, String> {
        if let Some(site) = self.sites.get(&cell) {
            return Ok(*site);
        }
        let h = cell_hash(cell, self.seed(0), self.high());
        let scale = self.profile.biome_scale;
        let point = [
            (cell[0] as f32 + 0.2 + (h & 65535) as f32 / 65535.0 * 0.6) * scale,
            (cell[1] as f32 + 0.2 + (h >> 16) as f32 / 65535.0 * 0.6) * scale,
        ];
        let registered = self.profile.registry_program.is_some();
        let actual = if registered {
            self.sampler
                .run(0, [point[0], 0.0, point[1]], Context::default())?
        } else {
            let mut actual = [0.0; 6];
            for channel in 0..4 {
                actual[channel] =
                    self.stack(point, &self.profile.noises[channel], channel as u32 * 7919);
            }
            if let Some(noise) = &self.profile.weirdness_noise {
                actual[4] = self.stack(point, noise, 31676);
            }
            actual
        };
        let (mut best, mut biome) = (1e20, 0);
        for (index, b) in self.profile.biomes.iter().enumerate() {
            if b.flags & 16 != 0
                || self.targeted[index]
                || self.fields.climate.has_surface() && b.flags & 64 != 0
            {
                continue;
            }
            let mut fitness = (0..4)
                .map(|i| (actual[i] - b.climate[i]).powi(2) * [2.5, 1.5, 2.0, 0.5][i])
                .sum::<f32>();
            if self.fields.climate.has_surface() {
                fitness -= 0.12;
            }
            if !registered && (b.flags & 4 != 0) != (actual[2] < -0.25) {
                fitness += 8.0;
            }
            if fitness < best {
                best = fitness;
                biome = index as u32;
            }
        }
        if self.fields.climate.has_surface() {
            let (selected, hint) = self.fields.climate.search_with_hint(actual, true, false,
                !registered, biome, best, false, self.climate_hint);
            biome = selected;
            self.climate_hint = hint;
        }
        let site = Site {
            point,
            biome,
            climate: [actual[0], actual[1], actual[2], actual[3]],
        };
        if self.sites.len() >= 4096 {
            self.sites.clear();
        }
        self.sites.insert(cell, site);
        Ok(site)
    }
    fn base(&mut self, point: [f32; 2]) -> Result<Sample, String> {
        if self.enclosed() {
            let actual = self
                .sampler
                .run(0, [point[0], 0.0, point[1]], Context::default())?;
            return Ok(Sample {
                height: self.request.min_y,
                biome: self.sampler.registered_biome(actual, 0, false),
            });
        }
        let scale = self.profile.biome_scale;
        let warp = [
            simplex(
                point.map(|v| v / (scale * 2.0)),
                self.seed(407),
                self.high(),
            ) * scale
                * 0.18,
            simplex(
                point.map(|v| v / (scale * 2.0)),
                self.seed(911),
                self.high(),
            ) * scale
                * 0.18,
        ];
        let ribbon_point = std::array::from_fn(|i| (point[i] + warp[i]) / (scale * 0.22));
        let ribbon = [
            simplex(ribbon_point, self.seed(1487), self.high()) * scale * 0.055,
            simplex(ribbon_point, self.seed(2371), self.high()) * scale * 0.055,
        ];
        let wisp_point =
            std::array::from_fn(|i| (point[i] + warp[i] + ribbon[i]) / (scale * 0.075));
        let wisps = [
            simplex(wisp_point, self.seed(3253), self.high()) * scale * 0.02,
            simplex(wisp_point, self.seed(4721), self.high()) * scale * 0.02,
        ];
        let warped = std::array::from_fn::<_, 2, _>(|i| point[i] + warp[i] + ribbon[i] + wisps[i]);
        let cell = warped.map(|v| (v / scale).floor() as i32);
        let (mut nearest, mut biome, mut total, mut terrain, mut climate) =
            (1e20, 0, 0.0, [0.0; 3], [0.0; 4]);
        for dz in -2..=2 {
            for dx in -2..=2 {
                let site = self.site([cell[0] + dx, cell[1] + dz])?;
                let delta = [
                    (warped[0] - site.point[0]) / scale,
                    (warped[1] - site.point[1]) / scale,
                ];
                let distance = delta[0] * delta[0] + delta[1] * delta[1];
                if distance < nearest {
                    nearest = distance;
                    biome = site.biome;
                }
                let taper = (1.0 - distance / (1.75 * 1.75)).max(0.0);
                let weight =
                    (-distance / (self.profile.blend * self.profile.blend)).exp() * taper * taper;
                for i in 0..3 {
                    terrain[i] += self.profile.biomes[site.biome as usize].terrain[i] * weight;
                }
                for i in 0..4 {
                    climate[i] += site.climate[i] * weight;
                }
                total += weight;
            }
        }
        for value in &mut terrain {
            *value /= total;
        }
        for value in &mut climate {
            *value /= total;
        }
        let broad = fbm(
            point.map(|v| v * self.request.frequency * 0.6),
            self.seed(0),
            self.high(),
        );
        let detailed = fbm(
            point.map(|v| v * self.request.frequency),
            self.seed(0),
            self.high(),
        );
        let relief = mix(broad, detailed, ((terrain[2] - 0.6) / 0.4).clamp(0.0, 1.0));
        let height = (self.request.base_height
            + terrain[0]
            + climate[2] * 12.0
            + self.request.amplitude
                * terrain[1]
                * relief
                * (1.0 - climate[3] * 0.35).clamp(0.6, 1.4))
        .floor() as i32;
        if self.profile.registry_program.is_some() {
            let actual = self.sampler.climate([point[0], 0.0, point[1]], true)?;
            biome = self.sampler.registered_biome(actual, biome, false);
        }
        Ok(Sample {
            height: height.clamp(
                self.request.min_y + 1,
                self.request.min_y + self.request.height as i32,
            ),
            biome,
        })
    }
    fn raw_height(&mut self, point: [f32; 2]) -> Result<f32, String> {
        if self.profile.registry_program.is_some() {
            self.sampler.surface_height_at(point)
        } else {
            Ok(self.base(point)?.height as f32)
        }
    }
    fn physical(&mut self, x: i32, z: i32) -> Result<Sample, String> {
        let mut sample = self.base([x as f32, z as f32])?;
        if self.profile.registry_program.is_some() {
            sample.height = self.raw_height([x as f32, z as f32])?.floor() as i32;
        }
        if self.profile.registry_program.as_ref().is_some_and(|r| {
            !r.enclosed()
                && r.surface[2] == 0
                && self
                    .profile
                    .climate_targets
                    .iter()
                    .any(|t| self.profile.biomes[t.biome as usize].flags & 64 != 0)
        }) {
            let sea = self.profile.sea_level;
            let mut coastal = false;
            if (sea - 2..=sea + 3).contains(&sample.height) {
                for radius in [2, 4, 6] {
                    for (dx, dz) in [
                        (1, 0),
                        (-1, 0),
                        (0, 1),
                        (0, -1),
                        (1, 1),
                        (-1, 1),
                        (1, -1),
                        (-1, -1),
                    ] {
                        let step = if dx != 0 && dz != 0 {
                            (radius as f32 * 0.70710678).round() as i32
                        } else {
                            radius
                        };
                        let height =
                            self.raw_height([(x + dx * step) as f32, (z + dz * step) as f32])?;
                        coastal = if sample.height < sea {
                            height >= sea as f32
                        } else {
                            height < sea as f32
                        };
                        if coastal {
                            break;
                        }
                    }
                    if coastal {
                        break;
                    }
                }
            }
            if coastal {
                sample.biome = self.sampler.coastal_biome(x, z, sample.biome)?;
            } else if self.profile.biomes[sample.biome as usize].flags & 64 != 0 {
                sample.biome = self.sampler.surface_biome(x, z, sample.biome, true)?;
            }
        }
        Ok(sample)
    }
    fn lake(&mut self, cell: [i32; 2]) -> Result<Lake, String> {
        if let Some(lake) = self.lakes.get(&cell) {
            return Ok(*lake);
        }
        let h = cell_hash(cell, self.seed(8647), self.high());
        let point = [
            (cell[0] as f32 + 0.25 + (h & 255) as f32 / 510.0) * 128.0,
            (cell[1] as f32 + 0.25 + ((h >> 8) & 255) as f32 / 510.0) * 128.0,
        ];
        let center = self.base(point)?;
        let options = self.profile.biomes[center.biome as usize].lakes;
        let chance = ((h >> 16) & 65535) as f32 / 65535.0;
        let lava = chance < options[1];
        let radius = (24.0 + (h & 7) as f32) * if lava { 0.25 } else { 1.0 };
        let eligible = lava || chance < options[0];
        let mut level = self.request.min_y;
        if eligible {
            let mut floor = self.raw_height(point)?;
            for offset in [
                [radius + 5.0, 0.0],
                [-radius - 5.0, 0.0],
                [0.0, (radius + 5.0) / 0.78],
                [0.0, -(radius + 5.0) / 0.78],
            ] {
                floor = floor.min(self.raw_height([point[0] + offset[0], point[1] + offset[1]])?);
            }
            level = floor.floor() as i32 - 2;
        }
        let lake = Lake {
            radius,
            level,
            biome: center.biome,
            eligible,
            lava,
        };
        self.lakes.insert(cell, lake);
        Ok(lake)
    }
    pub fn column(&mut self, x: i32, z: i32, include_lakes: bool) -> Result<Column, String> {
        self.column_impl(x, z, include_lakes, true)
    }
    pub fn probe(&mut self, x: i32, z: i32, include_lakes: bool) -> Result<Column, String> {
        self.column_impl(x, z, include_lakes, false)
    }
    pub fn underground_biome(&mut self, point: [i32; 3], column: Column) -> Result<u32, String> {
        super::caves::biome_at(self.profile, self.request, &mut self.sampler, point, column)
    }
    fn column_impl(
        &mut self,
        x: i32,
        z: i32,
        include_lakes: bool,
        materials: bool,
    ) -> Result<Column, String> {
        let Sample { mut height, biome } = self.physical(x, z)?;
        let b = &self.profile.biomes[biome as usize];
        let point = [x as f32, z as f32];
        let h = cell_hash([x, z], self.seed(1381), self.high());
        let registered = self.profile.registry_program.is_some();
        let mut top = 0u32;
        let mut filler = 0u32;
        let mut flags = 0u32;
        let sea = self.profile.sea_level;
        if height < sea {
            top = 1;
            filler = 1;
        } else if b.flags & 1 != 0 {
            top = 2;
        } else if b.flags & 2 != 0 && height > sea + 65 {
            top = 3;
            filler = 3;
        }
        let mut frozen = b.flags & 1 != 0;
        if registered {
            let mut temperature = b.temperature;
            if height > sea + 17 {
                temperature -= (noise3([point[0] * 0.125, 0.0, point[1] * 0.125], 1234) * 8.0
                    + (height - sea - 17) as f32)
                    * 0.00125;
            }
            frozen = b.flags & 32 != 0 && temperature < 0.15;
        }
        if frozen && !self.enclosed() {
            flags |= 1 << 28;
        }
        if simplex(point.map(|v| v * 0.005), 2345, 0) < -0.8 {
            flags |= 1 << 27;
        }
        let mut depth = (3.5
            + simplex(
                point.map(|v| v * self.request.frequency * 3.0),
                self.seed(559),
                self.high(),
            ) * 2.0)
            .floor()
            .clamp(2.0, 6.0) as u32;
        if registered {
            depth = ((self.sampler.surface_noise([point[0], 0.0, point[1]], 0)? * 2.75
                + 3.0
                + (h & 65535) as f32 / 65535.0 * 0.25) as i32)
                .clamp(0, 7) as u32;
        }
        if include_lakes && !self.enclosed() {
            let cell = [x.div_euclid(128), z.div_euclid(128)];
            let hash = cell_hash(cell, self.seed(8647), self.high());
            let center = [
                (cell[0] as f32 + 0.25 + (hash & 255) as f32 / 510.0) * 128.0,
                (cell[1] as f32 + 0.25 + ((hash >> 8) & 255) as f32 / 510.0) * 128.0,
            ];
            let delta = [point[0] - center[0], (point[1] - center[1]) * 0.78];
            let distance = (delta[0] * delta[0] + delta[1] * delta[1]).sqrt();
            let warp = simplex(point.map(|v| v * 0.055), self.seed(9913), self.high()) * 0.09;
            let basin = distance / (24.0 + (hash & 7) as f32) + warp;
            if basin < 1.35 {
                let lake = self.lake(cell)?;
                let basin = distance / lake.radius + warp;
                if lake.eligible
                    && basin < 1.35
                    && b.flags & (2 | 4 | 8 | 16) == 0
                    && lake.level > sea + 3
                    && (height - lake.level).abs() <= 18
                {
                    if basin < 1.0 {
                        let drop = ((1.0 - basin * basin) * 6.0).ceil().clamp(1.0, 7.0) as u32;
                        height = (lake.level - drop as i32).clamp(
                            self.request.min_y + 6,
                            self.request.min_y + self.request.height as i32 - 1,
                        );
                        depth = drop;
                        flags &= !(1 << 28);
                        flags |= 1 << 29;
                        filler = lake.biome;
                        top = if lake.lava {
                            flags |= 1 << 28;
                            4
                        } else {
                            1
                        };
                    } else {
                        height = mix(
                            lake.level as f32,
                            height as f32,
                            smoothstep(1.0, 1.35, basin),
                        )
                        .round() as i32;
                    }
                }
            }
        }
        if !materials {
            return Ok(Column {
                height,
                packed: biome | (depth << 24) | flags,
                materials: 0,
            });
        }
        if b.flags & 8 != 0 && self.profile.terrain_features.band_frequency > 0.0 {
            let offset = if registered {
                self.sampler.surface_noise([point[0], 0.0, point[1]], 2)? * 4.0
            } else {
                simplex(
                    point.map(|v| v * self.profile.terrain_features.band_frequency),
                    self.seed(6833),
                    self.high(),
                ) * 4.0
                    * self.profile.terrain_features.band_amplitude
            };
            filler = (offset.round() as i32).clamp(-127, 127) as u8 as u32;
        }
        let mut top_id = match top {
            1 => b.underwater,
            2 => self.profile.snow as u32,
            3 => self.profile.stone as u32,
            _ => b.top,
        };
        let mut filler_id = match filler {
            1 => b.underwater,
            3 => self.profile.stone as u32,
            _ => b.filler,
        };
        if registered && flags & (1 << 29) == 0 {
            let layered = self
                .profile
                .registry_program
                .as_ref()
                .unwrap()
                .material_layers;
            let slope = self.sampler.slope(x, z, layered)?;
            let mut material_depth = depth as f32;
            let mut detail = [0.0; 4];
            if layered {
                material_depth = (self.sampler.surface_noise([point[0], 0.0, point[1]], 0)? * 2.75
                    + 3.0
                    + (h & 65535) as f32 / 65535.0 * 0.25) as i32
                    as f32;
                detail = [
                    (height - self.request.min_y) as f32,
                    self.sampler.surface_noise([point[0], 0.0, point[1]], 1)?,
                    if height < sea {
                        sea as f32
                    } else {
                        i32::MIN as f32
                    },
                    height as f32 - 1.0 + material_depth - 8.0,
                ];
            }
            let band = filler as u8 as i8 as f32;
            let selected = self.sampler.run(
                3 + biome as usize,
                [point[0], height as f32 - 1.0, point[1]],
                Context {
                    surface: [1.0, material_depth, slope, band],
                    detail,
                },
            )?[0];
            let below = self.sampler.run(
                3 + biome as usize,
                [point[0], height as f32 - 3.0, point[1]],
                Context {
                    surface: [3.0, material_depth, slope, band],
                    detail,
                },
            )?[0];
            if selected > 0.0 {
                top_id = (selected - 1.0) as u32;
            }
            if below > 0.0 {
                filler_id = (below - 1.0) as u32;
            }
        }
        if flags & (1 << 29) != 0 {
            let lake_biome = &self.profile.biomes[filler as usize];
            let barrier = if flags & (1 << 28) != 0 {
                lake_biome.lake_barrier
            } else {
                lake_biome.lake_water_barrier
            };
            if barrier != 0 {
                top_id = barrier as u32;
                filler_id = barrier as u32;
            }
        }
        if top_id >= self.profile.materials.len() as u32
            || filler_id >= self.profile.materials.len() as u32
        {
            return Err("CPU surface program returned an out-of-range material".into());
        }
        flags |= ((h >> 16) & 3) << 30;
        Ok(Column {
            height,
            packed: biome | ((filler & 255) << 16) | (depth << 24) | flags,
            materials: top_id | (filler_id << 16),
        })
    }
}

#[cfg(test)]
mod tests;
