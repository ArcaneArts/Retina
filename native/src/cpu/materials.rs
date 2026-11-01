//! Native cavity planes, quart biomes and complete vertical material runs.
use super::{aquifers::Aquifers, caves::Caves, fields::Fields, noise::cell_hash, program::Context};
use crate::{
    ChunkRequest,
    geology::{CaveMask, ColumnHalo},
    profile::{Column, WorldProfile},
};
use rayon::prelude::*;
use std::sync::atomic::{AtomicU32, Ordering};
#[derive(Clone, Copy)]
struct Voxel {
    id: u16,
    solid: bool,
    cavity: bool,
}
struct Builder<'a> {
    profile: &'a WorldProfile,
    request: ChunkRequest,
    caves: Caves<'a>,
    aquifers: Aquifers<'a>,
}
impl<'a> Builder<'a> {
    fn new(
        profile: &'a WorldProfile,
        fields: &'a Fields,
        request: ChunkRequest,
        columns: &'a ColumnHalo,
    ) -> Self {
        Self {
            profile,
            request,
            caves: Caves::new(profile, fields, request, columns),
            aquifers: Aquifers::new(profile, request),
        }
    }
    fn with_biomes(mut self, grid: super::caves::biomes::Grid) -> Self {
        self.caves = self.caves.with_biomes(grid);
        self
    }
    fn voxel(&mut self, p: [i32; 3], c: Column) -> Result<Voxel, String> {
        let y = p[1];
        let [min, max] = self.caves.sampler().bounds();
        if y < min || y >= max {
            return Ok(Voxel {
                id: 0,
                solid: false,
                cavity: true,
            });
        }
        let lake = c.packed & (1 << 29) != 0;
        let icy = c.packed & (1 << 28) != 0;
        let waterline = if lake {
            c.height + ((c.packed >> 24) & 7) as i32
        } else {
            self.profile.sea_level
        };
        if y >= c.height {
            let id = if y >= waterline {
                0
            } else if lake && icy {
                self.profile.geology.lava
            } else if !lake && icy && y == waterline - 1 {
                self.profile.ice
            } else {
                self.profile.water
            };
            return Ok(Voxel {
                id,
                solid: false,
                cavity: false,
            });
        }
        let geometry = self.caves.carved(p)?;
        let aquifer = self
            .profile
            .registry_program
            .as_ref()
            .is_some_and(|r| r.aquifer.is_some());
        let fluid = if geometry && aquifer {
            self.aquifers.substance(p, &mut self.caves)?
        } else {
            0
        };
        let cavity = geometry && (!aquifer || fluid == 0);
        let carveable = self
            .profile
            .geology
            .carveable
            .get(self.profile.stone as usize)
            .copied()
            .unwrap_or(false);
        if geometry && carveable {
            let id = if aquifer {
                match fluid {
                    0 => 0,
                    1 => self.profile.water,
                    2 => self.profile.geology.lava,
                    3 => {
                        return Ok(Voxel {
                            id: self.profile.stone,
                            solid: true,
                            cavity: false,
                        });
                    }
                    _ => unreachable!(),
                }
            } else if y < self.profile.geology.lava_level {
                self.profile.geology.lava
            } else if self.profile.biomes[c.biome()].flags & 4 != 0 && y < waterline {
                self.profile.water
            } else {
                0
            };
            return Ok(Voxel {
                id,
                solid: false,
                cavity,
            });
        }
        if lake && y >= c.height - 3 {
            return Ok(Voxel {
                id: if y == c.height - 1 {
                    c.materials as u16
                } else {
                    (c.materials >> 16) as u16
                },
                solid: true,
                cavity,
            });
        }
        Ok(Voxel {
            id: self.profile.stone,
            solid: true,
            cavity,
        })
    }
    fn column(
        &mut self,
        x: i32,
        z: i32,
        c: Column,
        voxels: &mut Vec<Voxel>,
        runs: &mut Vec<u32>,
        below: &mut Vec<usize>,
    ) -> Result<(), String> {
        voxels.clear();
        for layer in 0..self.request.height {
            voxels.push(self.voxel([x, self.request.min_y + layer as i32, z], c)?);
        }
        let layered = self.profile.material_layers();
        let h = cell_hash(
            [x, z],
            (self.request.seed as u32).wrapping_add(1381),
            (self.request.seed >> 32) as u32,
        );
        let point = [x as f32, 0.0, z as f32];
        let (depth, secondary, band, slope) = if layered {
            let sampler = self.caves.sampler();
            let depth = (sampler.surface_noise(point, 0)? * 2.75
                + 3.0
                + (h & 65535) as f32 / 65535.0 * 0.25) as i32 as f32;
            let secondary = sampler.surface_noise(point, 1)?;
            let band = (sampler.surface_noise(point, 2)? * 4.0).round();
            let slope = ((self.caves.column(x + 1, z)?.height
                - self.caves.column(x - 1, z)?.height)
                .abs())
            .max((self.caves.column(x, z + 1)?.height - self.caves.column(x, z - 1)?.height).abs())
                as f32;
            (depth, secondary, band, slope)
        } else {
            (0.0, 0.0, 0.0, 0.0)
        };
        let preliminary = c.height as f32 - 1.0 + depth - 8.0;
        below.clear();
        below.resize(voxels.len(), 0);
        let mut bottom = 0;
        for (layer, voxel) in voxels.iter().enumerate() {
            if !voxel.solid {
                bottom = layer + 1;
            }
            below[layer] = bottom;
        }
        let mut above = 0;
        let mut water = i32::MIN as f32;
        let mut previous = None;
        for layer in (0..voxels.len()).rev() {
            let y = self.request.min_y + layer as i32;
            let voxel = voxels[layer];
            let mut material = voxel.id;
            if !voxel.solid {
                if material == 0 {
                    above = 0;
                    water = i32::MIN as f32;
                } else if water == i32::MIN as f32 {
                    water = (y + 1) as f32;
                }
            } else {
                above += 1;
                if layered {
                    let biome = if self
                        .profile
                        .registry_program
                        .as_ref()
                        .is_some_and(|r| r.enclosed())
                        || y < c.height - 12
                    {
                        self.caves.biome([x, y, z])?
                    } else {
                        c.packed & 65535
                    };
                    let selected = self.caves.sampler().run(
                        3 + biome as usize,
                        [x as f32, y as f32, z as f32],
                        Context {
                            surface: [above as f32, depth, slope, band],
                            detail: [
                                (layer - below[layer] + 1) as f32,
                                secondary,
                                water,
                                preliminary,
                            ],
                        },
                    )?[0];
                    if selected > 0.0 && c.packed & (1 << 29) == 0 {
                        let selected = (selected - 1.0) as u32;
                        if selected >= self.profile.materials.len() as u32 {
                            return Err(format!(
                                "CPU material program returned invalid palette ID {selected}"
                            ));
                        }
                        material = selected as u16;
                    }
                } else {
                    material = c.material(y, self.request.min_y, Some(self.profile));
                }
            }
            if previous != Some(material) {
                if let Some(previous) = previous {
                    runs.push(((layer as u32 + 1) << 16) | previous as u32);
                }
                previous = Some(material);
            }
        }
        runs.push(previous.unwrap_or(0) as u32);
        Ok(())
    }
}
/// Uses the existing mask/run layout. Prefix groups are zero because CPU offsets
/// are already absolute, exactly as in the shared End substrate implementation.
pub(crate) fn build(
    profile: &WorldProfile,
    fields: &Fields,
    request: ChunkRequest,
    side: u32,
    columns: &ColumnHalo,
    metrics: &crate::performance::Metrics,
) -> Result<CaveMask, String> {
    build_in_pool(
        profile,
        fields,
        request,
        side,
        columns,
        metrics,
        super::pools::terrain()?,
    )
}

/// All CPU tiles share the bounded terrain lane, independent of waiting feature
/// tasks. Per-worker samplers/scratch never contain GPU resources.
pub(super) fn build_in_pool(
    profile: &WorldProfile,
    fields: &Fields,
    request: ChunkRequest,
    side: u32,
    columns: &ColumnHalo,
    metrics: &crate::performance::Metrics,
    pool: &rayon::ThreadPool,
) -> Result<CaveMask, String> {
    let side = side as usize;
    let surface = side * 16;
    let run_width = surface - 32;
    let width = run_width + 2;
    let quart = surface / 4;
    let layers = (request.height as usize + request.min_y.rem_euclid(4) as usize).div_ceil(4);
    let air_words = (width * width * request.height as usize).div_ceil(32);
    let surface_words = (surface * surface).div_ceil(32);
    let biome_offset = air_words + surface_words;
    let base = biome_offset + (quart * quart * layers).div_ceil(2);
    let layered = profile.material_layers();
    let count = run_width * run_width;
    let groups = count.div_ceil(256);
    let mut words = vec![
        0u32;
        if layered {
            base + 4 + count * 2 + groups * 2
        } else {
            base
        }
    ];
    let ox = request.chunk_x * 16;
    let oz = request.chunk_z * 16;
    // Biome storage extends through the full horizontal halo, independently of
    // clipped terrain bounds. It is sampled on the same global quart lattice.
    let bottom = request.min_y.div_euclid(4) * 4;
    let biome_rows = pool.install(|| {
        (0..quart.div_ceil(4))
            .into_par_iter()
            .map_init(
                || Builder::new(profile, fields, request, columns),
                |b, strip| {
                    let start = strip * 4;
                    let rows = (quart - start).min(4);
                    let mut packed = vec![0u32; rows * quart * layers / 2];
                    for z in 0..rows {
                        for x in 0..quart {
                            for y in 0..layers {
                                let index = (y * rows + z) * quart + x;
                                let biome = b.caves.biome([
                                    ox + x as i32 * 4,
                                    bottom + y as i32 * 4,
                                    oz + (start + z) as i32 * 4,
                                ])?;
                                packed[index / 2] |= biome << ((index % 2) * 16);
                            }
                        }
                    }
                    Ok((start, rows, packed))
                },
            )
            .collect::<Result<Vec<_>, String>>()
    })?;
    for (start, rows, packed) in biome_rows {
        let len = rows * quart / 2;
        for y in 0..layers {
            let offset = biome_offset + (y * quart + start) * quart / 2;
            words[offset..offset + len].copy_from_slice(&packed[y * len..(y + 1) * len]);
        }
    }
    // The complete immutable biome plane is already authoritative output for
    // this profile/seed/tile. Share it with the later passes instead of sampling
    // those same quart coordinates again in each worker's separate cache.
    let started = metrics.start();
    let shared_biomes = super::caves::biomes::Grid::new(
        [ox, bottom, oz], quart, layers, &words[biome_offset..base],
    );
    metrics.elapsed(crate::performance::Counter::cpu_biome_grid_prepare_nanos, started);
    metrics.add(crate::performance::Counter::cpu_biome_grid_bytes, ((base - biome_offset) * 4) as u64);
    let mut b = Builder::new(profile, fields, request, columns).with_biomes(shared_biomes.clone());
    for z in 0..surface {
        for x in 0..surface {
            let c = columns
                .column_at(ox + x as i32, oz + z as i32)
                .ok_or("missing CPU surface column")?;
            if b.caves
                .carved([ox + x as i32, c.height - 1, oz + z as i32])?
            {
                let index = z * surface + x;
                words[air_words + index / 32] |= 1 << (index % 32);
            }
        }
    }
    let mut proof = b.caves.proof_stats();
    drop(b);
    if layered {
        words[base..base + 4].copy_from_slice(&[0x52554e53, run_width as u32, count as u32, 0]);
    }
    // Different strips can touch separate bits of the same boundary word.
    // Relaxed OR is sufficient: all workers join before publishing the mask.
    let air_bits = (0..air_words)
        .map(|_| AtomicU32::new(0))
        .collect::<Vec<_>>();
    let strips = pool.install(|| {
        (0..width.div_ceil(16))
            .into_par_iter()
            .map_init(
                || {
                    (
                        Builder::new(profile, fields, request, columns).with_biomes(shared_biomes.clone()),
                        Vec::with_capacity(request.height as usize),
                        Vec::with_capacity(request.height as usize),
                    )
                },
                |(b, voxels, below), strip| {
                    let start = strip * 16;
                    let end = (start + 16).min(width);
                    let before = b.caves.proof_stats();
                    let mut descriptors = Vec::new();
                    let mut runs = Vec::new();
                    for z in start..end {
                        for x in 0..width {
                            let wx = ox + 15 + x as i32;
                            let wz = oz + 15 + z as i32;
                            let c = columns
                                .column_at(wx, wz)
                                .ok_or("missing CPU material column")?;
                            let interior = x > 0 && z > 0 && x + 1 < width && z + 1 < width;
                            if layered && interior {
                                let offset = runs.len() as u32;
                                let before = runs.len();
                                b.column(wx, wz, c, voxels, &mut runs, below)?;
                                descriptors.push([(runs.len() - before) as u32, offset]);
                                for (y, voxel) in voxels.iter().enumerate() {
                                    if voxel.cavity {
                                        let bit = (y * width + z) * width + x;
                                        air_bits[bit / 32]
                                            .fetch_or(1 << (bit % 32), Ordering::Relaxed);
                                    }
                                }
                            } else {
                                for y in 0..request.height {
                                    let p = [wx, request.min_y + y as i32, wz];
                                    let carved = b.caves.carved(p)?;
                                    let air = if profile
                                        .registry_program
                                        .as_ref()
                                        .is_some_and(|r| r.aquifer.is_some())
                                    {
                                        carved
                                            && (p[1] < b.caves.sampler().bounds()[0]
                                                || p[1] >= b.caves.sampler().bounds()[1]
                                                || b.aquifers.substance(p, &mut b.caves)? == 0)
                                    } else {
                                        carved
                                    };
                                    if air {
                                        let bit = (y as usize * width + z) * width + x;
                                        air_bits[bit / 32]
                                            .fetch_or(1 << (bit % 32), Ordering::Relaxed);
                                    }
                                }
                            }
                        }
                    }
                    let after = b.caves.proof_stats();
                    Ok((
                        (start.max(1) - 1) * run_width,
                        descriptors,
                        runs,
                        super::caves::ProofStats {
                            air: after.air - before.air,
                            solid: after.solid - before.solid,
                            scalar: after.scalar - before.scalar,
                        },
                    ))
                },
            )
            .collect::<Result<Vec<_>, String>>()
    })?;
    for (word, value) in words[..air_words].iter_mut().zip(air_bits) {
        *word = value.into_inner();
    }
    let mut runs = Vec::new();
    // Indexed collection retains z-major order regardless of worker scheduling.
    for (first, descriptors, strip_runs, stats) in strips {
        let offset = runs.len() as u32;
        for (i, [len, start]) in descriptors.into_iter().enumerate() {
            words[base + 4 + (first + i) * 2] = len;
            words[base + 5 + (first + i) * 2] = start + offset;
        }
        runs.extend(strip_runs);
        proof.air += stats.air;
        proof.solid += stats.solid;
        proof.scalar += stats.scalar;
    }
    metrics.add(
        crate::performance::Counter::cpu_density_air_proofs,
        proof.air,
    );
    metrics.add(
        crate::performance::Counter::cpu_density_solid_proofs,
        proof.solid,
    );
    metrics.add(
        crate::performance::Counter::cpu_density_scalar_points,
        proof.scalar,
    );
    if layered {
        words[base + 3] = runs.len() as u32;
        words.extend(runs);
    }
    let mask = CaveMask {
        origin_x: ox + 15,
        origin_z: oz + 15,
        min_y: request.min_y,
        height: request.height,
        width,
        air_only: profile
            .registry_program
            .as_ref()
            .is_some_and(|r| r.aquifer.is_some()),
        words,
        columns: None,
    };
    if layered {
        mask.validate_material_runs()?;
    }
    Ok(mask)
}
