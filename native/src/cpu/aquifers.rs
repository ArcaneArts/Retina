//! Registered local aquifer geometry, fluids and pressure barriers. All field
//! programs and palettes come from the authoritative saved definition.
use super::{
    caves::Caves,
    noise::{mix, mix_hash},
    program::Context,
};
use crate::{ChunkRequest, profile::WorldProfile};
use std::collections::HashMap;
const DRY: i32 = -32512;
#[derive(Clone, Copy)]
struct Center {
    point: [i32; 3],
    status: [i32; 2],
}
pub struct Aquifers<'a> {
    profile: &'a WorldProfile,
    request: ChunkRequest,
    surface: HashMap<[i32; 2], i32>,
    centers: HashMap<[i32; 3], Center>,
    water_is_water: bool,
}
impl<'a> Aquifers<'a> {
    pub fn new(profile: &'a WorldProfile, request: ChunkRequest) -> Self {
        let material = &profile.materials[profile.water as usize];
        let water_is_water = material
            .as_str()
            .or_else(|| material.get("id").and_then(serde_json::Value::as_str))
            == Some("minecraft:water");
        Self {
            profile,
            request,
            surface: HashMap::new(),
            centers: HashMap::new(),
            water_is_water,
        }
    }
    fn program(&self) -> Result<&crate::program::AquiferProgram, String> {
        self.profile
            .registry_program
            .as_ref()
            .and_then(|r| r.aquifer.as_ref())
            .ok_or_else(|| "CPU aquifer requested without a loaded aquifer program".into())
    }
    pub fn global(&self, y: i32) -> [i32; 2] {
        if y < self.profile.geology.lava_level {
            [-54, self.profile.geology.lava as i32]
        } else {
            [self.profile.sea_level, self.profile.water as i32]
        }
    }
    fn at(status: [i32; 2], y: i32) -> u32 {
        if y < status[0] { status[1] as u32 } else { 0 }
    }
    fn surface(&mut self, point: [i32; 2], caves: &mut Caves<'a>) -> Result<i32, String> {
        let point = point.map(|v| v.div_euclid(4) * 4);
        if let Some(level) = self.surface.get(&point) {
            return Ok(*level);
        }
        let a = self.program()?;
        let program = a.program as usize + 4;
        let step = a.surface[1];
        let mut level = a.surface[0];
        let explicit = a.surface[2] != 0;
        let p = [point[0] as f32, 0.0, point[1] as f32];
        let value = caves.sampler().run(program, p, Context::default())?;
        if explicit {
            level = value[0].floor() as i32;
        } else {
            let mut y = (value[1].floor() as i32).div_euclid(step) * step;
            while y >= level {
                if caves
                    .sampler()
                    .run(program, [p[0], y as f32, p[2]], Context::default())?[0]
                    > 0.0
                {
                    level = y;
                    break;
                }
                y = match y.checked_sub(step) {
                    Some(y) => y,
                    None => break,
                };
            }
        }
        if self.surface.len() >= 32768 {
            self.surface.clear();
        }
        self.surface.insert(point, level);
        Ok(level)
    }
    fn status(&mut self, p: [i32; 3], caves: &mut Caves<'a>) -> Result<[i32; 2], String> {
        let global = self.global(p[1]);
        let mut lowest = i32::MAX;
        let mut under = false;
        const OFFSETS: [[i32; 2]; 13] = [
            [0, 0],
            [-2, -1],
            [-1, -1],
            [0, -1],
            [1, -1],
            [-3, 0],
            [-2, 0],
            [-1, 0],
            [1, 0],
            [-2, 1],
            [-1, 1],
            [0, 1],
            [1, 1],
        ];
        for (i, offset) in OFFSETS.into_iter().enumerate() {
            let surface = self.surface([p[0] + offset[0] * 16, p[2] + offset[1] * 16], caves)?;
            let adjusted = surface + 8;
            if i == 0 && p[1] - 12 > adjusted {
                return Ok(global);
            }
            let reaches = p[1] + 12 > adjusted;
            if reaches || i == 0 {
                let wet = self.global(adjusted);
                if Self::at(wet, adjusted) != 0 {
                    if i == 0 {
                        under = true;
                    }
                    if reaches {
                        return Ok(wet);
                    }
                }
            }
            lowest = lowest.min(surface);
        }
        let program = self.program()?.program as usize;
        let sampled = caves
            .sampler()
            .run(program, p.map(|v| v as f32), Context::default())?;
        let mut level = DRY;
        if sampled[1] <= 0.0 {
            let factor = if under {
                (1.0 - (lowest + 8 - p[1]) as f32 / 64.0).clamp(0.0, 1.0)
            } else {
                0.0
            };
            let flood = sampled[0].clamp(-1.0, 1.0);
            if flood > mix(0.8, -0.3, factor) {
                level = global[0];
            } else if flood > mix(0.4, -0.8, factor) {
                let cell = [
                    p[0].div_euclid(16),
                    p[1].div_euclid(40),
                    p[2].div_euclid(16),
                ];
                let spread =
                    caves
                        .sampler()
                        .run(program + 1, cell.map(|v| v as f32), Context::default())?[0]
                        * 10.0;
                level = lowest.min(cell[1] * 40 + 20 + (spread / 3.0).floor() as i32 * 3);
            }
        }
        let mut material = global[1];
        let lava = self.profile.geology.lava as i32;
        if level <= -10 && level != DRY && material != lava {
            let cell = [
                p[0].div_euclid(64),
                p[1].div_euclid(40),
                p[2].div_euclid(64),
            ];
            if caves
                .sampler()
                .run(program + 2, cell.map(|v| v as f32), Context::default())?[0]
                .abs()
                > 0.3
            {
                material = lava;
            }
        }
        Ok([level, material])
    }
    fn center(&mut self, cell: [i32; 3], caves: &mut Caves<'a>) -> Result<Center, String> {
        if let Some(center) = self.centers.get(&cell) {
            return Ok(*center);
        }
        // Addition binds before XOR in the shader's saved seed contract.
        let h = mix_hash(
            (cell[0] as u32).wrapping_mul(0x9e3779b9)
                ^ (cell[1] as u32).wrapping_mul(0x85ebca6b)
                ^ (cell[2] as u32).wrapping_mul(0xc2b2ae35)
                ^ self.request.seed as u32
                ^ mix_hash((self.request.seed >> 32) as u32).wrapping_add(91871),
        );
        let point = [
            cell[0] * 16 + (h % 10) as i32,
            cell[1] * 12 + (mix_hash(h.wrapping_add(1)) % 9) as i32,
            cell[2] * 16 + (mix_hash(h.wrapping_add(2)) % 10) as i32,
        ];
        let center = Center {
            point,
            status: self.status(point, caves)?,
        };
        if self.centers.len() >= 16384 {
            self.centers.clear();
        }
        self.centers.insert(cell, center);
        Ok(center)
    }
    fn pressure(&self, y: i32, noise: f32, a: [i32; 2], b: [i32; 2]) -> f32 {
        let type1 = Self::at(a, y);
        let type2 = Self::at(b, y);
        let lava = self.profile.geology.lava as u32;
        let water = self.profile.water as u32;
        if self.water_is_water
            && ((type1 == lava && type2 == water) || (type2 == lava && type1 == water))
        {
            return 2.0;
        }
        let difference = (a[0] - b[0]).abs();
        if difference == 0 {
            return 0.0;
        }
        let distance = y as f32 + 0.5 - (a[0] + b[0]) as f32 * 0.5;
        let edge = difference as f32 * 0.5 - distance.abs();
        let gradient = if distance > 0.0 {
            edge / if edge > 0.0 { 1.5 } else { 2.5 }
        } else {
            (edge + 3.0) / if edge + 3.0 > 0.0 { 3.0 } else { 10.0 }
        };
        2.0 * (gradient
            + if (-2.0..=2.0).contains(&gradient) {
                noise
            } else {
                0.0
            })
    }
    /// 0 air, 1 default fluid, 2 lava, 3 pressure barrier. Invoked only for
    /// cavity geometry; solid base rock never needs local aquifer sampling.
    pub fn substance(&mut self, p: [i32; 3], caves: &mut Caves<'a>) -> Result<u32, String> {
        let y = p[1];
        let global = self.global(y);
        let lava = self.profile.geology.lava as u32;
        if Self::at(global, y) == lava {
            return Ok(2);
        }
        if !self.program()?.enabled {
            return Ok(u32::from(Self::at(global, y) != 0));
        }
        let anchor = [
            (p[0] - 5).div_euclid(16),
            (y + 1).div_euclid(12),
            (p[2] - 5).div_euclid(16),
        ];
        let mut distance = [i32::MAX; 3];
        let mut closest = [Center {
            point: [0; 3],
            status: [0; 2],
        }; 3];
        for dx in 0..=1 {
            for dy in -1..=1 {
                for dz in 0..=1 {
                    let center =
                        self.center([anchor[0] + dx, anchor[1] + dy, anchor[2] + dz], caves)?;
                    let d = (0..3).map(|i| (center.point[i] - p[i]).pow(2)).sum::<i32>();
                    for i in 0..3 {
                        if distance[i] >= d {
                            for j in (i + 1..3).rev() {
                                distance[j] = distance[j - 1];
                                closest[j] = closest[j - 1];
                            }
                            distance[i] = d;
                            closest[i] = center;
                            break;
                        }
                    }
                }
            }
        }
        let status = closest.map(|c| c.status);
        let material = Self::at(status[0], y);
        let result = if material == lava {
            2
        } else {
            u32::from(material != 0)
        };
        let s12 = 1.0 - (distance[1] - distance[0]) as f32 / 25.0;
        if s12 <= 0.0 {
            return Ok(result);
        }
        if self.water_is_water
            && material == self.profile.water as u32
            && Self::at(self.global(y - 1), y - 1) == lava
        {
            return Ok(result);
        }
        let program = self.program()?.program as usize;
        let noise = caves
            .sampler()
            .lattice(program + 3, p.map(|v| v as f32), [4, 4, 4])?[0];
        let s13 = 1.0 - (distance[2] - distance[0]) as f32 / 25.0;
        let s23 = 1.0 - (distance[2] - distance[1]) as f32 / 25.0;
        let p12 = self.pressure(y, noise, status[0], status[1]);
        let p13 = if s13 > 0.0 {
            self.pressure(y, noise, status[0], status[2])
        } else {
            0.0
        };
        let p23 = if s23 > 0.0 {
            self.pressure(y, noise, status[1], status[2])
        } else {
            0.0
        };
        let mut density = -0.02f32;
        if p12 > 0.0 || p13 > 0.0 || p23 > 0.0 {
            let sampled = caves.values(p)?[0];
            density = density.min(caves.density(p, sampled)?);
        }
        if density + s12 * p12 > 0.0
            || s13 > 0.0 && density + s12 * s13 * p13 > 0.0
            || s23 > 0.0 && density + s12 * s23 * p23 > 0.0
        {
            return Ok(3);
        }
        Ok(result)
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::{
        cpu::fields::Fields,
        geology::ColumnHalo,
        profile::Column,
        world_definition::{WorldDefinition, catalog::WorldCatalog},
    };
    use std::sync::Arc;
    #[test]
    fn loaded_local_and_global_aquifers_repeat_with_valid_fluid_kinds() {
        let catalog = WorldCatalog::read(std::path::Path::new(concat!(
            env!("CARGO_MANIFEST_DIR"),
            "/../assets/world-definitions/26.3/vanilla-binary/retina-catalog.dat"
        )))
        .unwrap();
        let definition = WorldDefinition::from_gzip(
            catalog.materialize("normal", 123456789).unwrap().as_slice(),
        )
        .unwrap();
        for key in ["minecraft:overworld", "minecraft:the_nether"] {
            let dimension = definition.dimension(key).unwrap();
            let p = dimension.parameters();
            let profile = dimension.profile().unwrap();
            let request = ChunkRequest {
                seed: definition.seed() as u64,
                chunk_x: -1,
                chunk_z: -1,
                min_y: p.min_y,
                height: p.height as u32,
                base_height: p.base_height,
                amplitude: p.amplitude,
                frequency: p.frequency,
                reserved: 1,
            };
            let halo = ColumnHalo {
                origin_x: -1,
                origin_z: -1,
                side: 2,
                columns: Arc::new(vec![
                    Column {
                        height: if key.contains("nether") { 128 } else { 70 },
                        packed: 0,
                        materials: 0
                    };
                    1024
                ]),
            };
            for global in [false, true] {
                let mut profile = profile.as_ref().clone();
                if global {
                    profile
                        .registry_program
                        .as_mut()
                        .unwrap()
                        .aquifer
                        .as_mut()
                        .unwrap()
                        .enabled = false;
                }
                let fields = Fields::new(&profile).unwrap();
                let mut caves = Caves::new(&profile, &fields, request, &halo);
                let mut a = Aquifers::new(&profile, request);
                let mut b = Aquifers::new(
                    &profile,
                    ChunkRequest {
                        chunk_x: -33,
                        chunk_z: 32,
                        ..request
                    },
                );
                let points = [
                    [-15, -55, -15],
                    [-7, -24, -5],
                    [0, 0, 0],
                    [3, 24, 5],
                    [11, 51, 13],
                ];
                let values: Vec<_> = points
                    .iter()
                    .map(|&p| a.substance(p, &mut caves).unwrap())
                    .collect();
                for (&p, substance) in points.iter().zip(values).rev() {
                    assert!(substance <= 3);
                    assert_eq!(substance, b.substance(p, &mut caves).unwrap());
                    if global {
                        let status = a.global(p[1]);
                        let material = Aquifers::at(status, p[1]);
                        assert_eq!(
                            substance,
                            if material == profile.geology.lava as u32 {
                                2
                            } else {
                                u32::from(material != 0)
                            }
                        );
                    }
                }
                assert_eq!(
                    a.pressure(
                        0,
                        123.0,
                        [10, profile.water as i32],
                        [10, profile.water as i32]
                    ),
                    0.0
                );
                let boundary = profile.geology.lava_level;
                assert_eq!(a.global(boundary - 1)[1], profile.geology.lava as i32);
                assert_eq!(a.global(boundary)[1], profile.water as i32);
            }
        }
    }
}
