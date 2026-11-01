//! Job-local CPU field sampling. GPU encoding and CPU lookup share the same
//! climate index, while both program executors consume the saved registry DAG.
use super::{
    noise::trilinear,
    program::{Context, Evaluator, Prepared},
};
use crate::spatial_hash::SamplingMap;
use crate::{ChunkRequest, profile::WorldProfile};
use std::sync::Arc;

pub struct Fields {
    program: Option<Prepared>,
    sampling_timings: Option<Arc<crate::timings::Timings>>,
    pub(crate) climate: crate::climate::Index,
    unplaced: Vec<usize>,
}
impl Fields {
    pub fn new(profile: &WorldProfile) -> Result<Self, String> {
        let program = profile
            .registry_program
            .as_ref()
            .map(|r| Prepared::new(Arc::new(r.clone())))
            .transpose()?;
        Ok(Self {
            program,
            sampling_timings: None,
            climate: crate::climate::Index::new(profile),
            unplaced: profile
                .biomes
                .iter()
                .enumerate()
                .filter(|(i, b)| {
                    b.flags & (16 | 64) == 0
                        && !profile
                            .climate_targets
                            .iter()
                            .any(|target| target.biome == *i as u32)
                })
                .map(|(i, _)| i)
                .collect(),
        })
    }
    pub(crate) fn with_timings(mut self, timings: Arc<crate::timings::Timings>) -> Self {
        if crate::performance::enabled() {
            self.sampling_timings = Some(timings);
        }
        self
    }
    pub fn sampler<'a>(&'a self, profile: &'a WorldProfile, request: ChunkRequest) -> Sampler<'a> {
        Sampler {
            fields: self,
            profile,
            request,
            evaluator: self.program.as_ref().map(|p| {
                p.evaluator(
                    request.seed,
                    profile.sea_level,
                    &profile.terrain_features.bands,
                )
                .with_timings(self.sampling_timings.clone())
            }),
            grid: SamplingMap::default(),
            heights: SamplingMap::default(),
            density_bounds: SamplingMap::default(),
            climate_hints: [None; 2],
        }
    }
}
pub struct Sampler<'a> {
    fields: &'a Fields,
    profile: &'a WorldProfile,
    request: ChunkRequest,
    evaluator: Option<Evaluator<'a>>,
    grid: SamplingMap<(usize, [u32; 3]), [f32; 6]>,
    heights: SamplingMap<[u32; 2], f32>,
    density_bounds: SamplingMap<(usize, [u32; 3]), super::program::bounds::Bounds>,
    climate_hints: [Option<usize>; 2],
}
impl Sampler<'_> {
    #[inline]
    pub(crate) fn material_is_zero(&self, program: usize, y: f32, preliminary: f32) -> bool {
        self.evaluator.as_ref().is_some_and(|e| e.material_is_zero(program, y, preliminary))
    }
    pub fn run(
        &mut self,
        program: usize,
        point: [f32; 3],
        context: Context,
    ) -> Result<[f32; 6], String> {
        let result = self
            .evaluator
            .as_mut()
            .ok_or("CPU sample has no registered generation program")?
            .run(program, point, context)?;
        if result.iter().any(|v| !v.is_finite()) {
            return Err(format!(
                "nonfinite CPU program {program} result at {point:?}"
            ));
        }
        Ok(result)
    }
    fn vertex(&mut self, program: usize, point: [f32; 3]) -> Result<[f32; 6], String> {
        let key = (program, point.map(f32::to_bits));
        if let Some(value) = self.grid.get(&key) {
            return Ok(*value);
        }
        let value = self.run(program, point, Context::default())?;
        if self.grid.len() >= 32768 {
            self.grid.clear();
        }
        self.grid.insert(key, value);
        Ok(value)
    }
    fn interpolated(
        &mut self,
        program: usize,
        point: [f32; 3],
        cell: [u32; 3],
        height: bool,
    ) -> Result<[f32; 6], String> {
        let size = cell.map(|v| v as f32);
        let lower = std::array::from_fn::<_, 3, _>(|i| (point[i] / size[i]).floor() * size[i]);
        let alpha = std::array::from_fn::<_, 3, _>(|i| (point[i] - lower[i]) / size[i]);
        let mut corners = [[0.0; 6]; 8];
        for (index, values) in corners.iter_mut().enumerate() {
            let offset = [index & 1, (index >> 1) & 1, (index >> 2) & 1];
            if (0..3).any(|i| offset[i] != 0 && alpha[i] == 0.0) {
                continue;
            }
            *values = self.vertex(
                program,
                std::array::from_fn(|i| lower[i] + offset[i] as f32 * size[i]),
            )?;
            if height {
                values[0] = (values[0] + 1.0).clamp(
                    self.request.min_y as f32 + 1.0,
                    (self.request.min_y + self.request.height as i32) as f32,
                );
            }
            if alpha == [0.0; 3] {
                return Ok(*values);
            }
        }
        Ok(std::array::from_fn(|channel| {
            trilinear(corners.map(|v| v[channel]), alpha)
        }))
    }
    pub fn lattice(
        &mut self,
        program: usize,
        point: [f32; 3],
        cell: [u32; 3],
    ) -> Result<[f32; 6], String> {
        self.interpolated(program, point, cell, false)
    }
    pub fn climate(&mut self, point: [f32; 3], surface: bool) -> Result<[f32; 6], String> {
        if surface {
            self.interpolated(0, [point[0], 0.0, point[2]], [4, 1, 4], false)
        } else {
            self.run(0, point, Context::default())
        }
    }
    /// Registered final-field interpolation remains distinct from nested
    /// interpolation operators already encoded in the graph itself.
    pub fn density(&mut self, program: usize, point: [f32; 3]) -> Result<f32, String> {
        let registry = self
            .profile
            .registry_program
            .as_ref()
            .ok_or("CPU sample has no registered density program")?;
        if registry.density_composition {
            return Ok(self.run(program, point, Context::default())?[0]);
        }
        let cell = [
            registry.terrain_cell[0],
            registry.terrain_cell[1],
            registry.terrain_cell[0],
        ];
        Ok(self.interpolated(program, point, cell, false)?[0])
    }
    pub(crate) fn density_certificate(
        &mut self,
        program: usize,
        point: [i32; 3],
    ) -> super::program::bounds::Bounds {
        self.density_certificate_at(program, point.map(|v| v as f32))
    }
    fn density_certificate_at(
        &mut self,
        program: usize,
        point: [f32; 3],
    ) -> super::program::bounds::Bounds {
        use super::program::bounds::Bounds;
        let Some(registry) = self.profile.registry_program.as_ref() else {
            return Bounds::UNKNOWN;
        };
        let step = [
            registry.terrain_cell[0] as f32,
            registry.terrain_cell[1] as f32,
            registry.terrain_cell[0] as f32,
        ];
        if registry.density_composition && !self.evaluator.as_ref().unwrap().can_bound(program) {
            return Bounds::UNKNOWN;
        }
        let lo =
            std::array::from_fn::<_, 3, _>(|i| (point[i] / step[i]).floor() * step[i]);
        let key = (program, lo.map(f32::to_bits));
        let hi = std::array::from_fn(|i| lo[i] + step[i]);
        // Rounding at very large coordinates or unusual cell sizes must never
        // certify a box that does not enclose the point being skipped.
        if (0..3).any(|i| !point[i].is_finite() || lo[i] > point[i] || hi[i] < point[i]) {
            return Bounds::UNKNOWN;
        }
        if let Some(bounds) = self.density_bounds.get(&key) {
            return *bounds;
        }
        if !registry.density_composition
            && lo.into_iter().chain(hi).any(|v: f32| v.abs() > 8388608.0)
        {
            return Bounds::UNKNOWN;
        }
        let bounds = if registry.density_composition {
            self.evaluator.as_mut().unwrap().bounds(program, lo, hi)
        } else {
            let mut bounds = Bounds {
                lo: f32::INFINITY,
                hi: f32::NEG_INFINITY,
            };
            for index in 0..8 {
                let offset = [index & 1, (index >> 1) & 1, (index >> 2) & 1];
                let p = std::array::from_fn(|i| lo[i] + offset[i] as f32 * step[i]);
                let Ok(value) = self.vertex(program, p) else {
                    return Bounds::UNKNOWN;
                };
                bounds.lo = bounds.lo.min(value[0]);
                bounds.hi = bounds.hi.max(value[0]);
            }
            bounds.widen()
        };
        if self.density_bounds.len() >= 32768 {
            self.density_bounds.clear();
        }
        self.density_bounds.insert(key, bounds);
        bounds
    }
    pub fn bounds(&self) -> [i32; 2] {
        let min = self.request.min_y;
        let max = min + self.request.height as i32;
        self.profile
            .registry_program
            .as_ref()
            .and_then(|p| p.generation.as_ref())
            .map_or([min, max], |g| {
                [
                    min.max(g.terrain_min_y),
                    max.min(g.terrain_min_y + g.terrain_height),
                ]
            })
    }
    pub fn generation_top(&self) -> i32 {
        let max = self.request.min_y + self.request.height as i32;
        self.profile
            .registry_program
            .as_ref()
            .and_then(|r| r.generation.as_ref())
            .map_or(max, |g| max.min(g.min_y + g.height))
    }
    pub fn surface_height(&mut self, x: i32, z: i32) -> Result<f32, String> {
        self.surface_height_at([x as f32, z as f32])
    }
    pub fn surface_height_at(&mut self, [x, z]: [f32; 2]) -> Result<f32, String> {
        if let Some(value) = self.heights.get(&[x.to_bits(), z.to_bits()]) {
            return Ok(*value);
        }
        let registry = self
            .profile
            .registry_program
            .as_ref()
            .ok_or("CPU sample has no registered surface program")?;
        let [min, max] = self.bounds();
        let empty = (self.request.min_y + i32::from(registry.generation.is_none())) as f32;
        let height = if max <= min {
            empty
        } else if registry.surface[2] != 0 {
            self.interpolated(1, [x as f32, 0.0, z as f32], [4, 1, 4], true)?[0]
        } else if !registry.density_composition
            && min == self.request.min_y
            && max == self.request.min_y + self.request.height as i32
        {
            // Preserve the existing final-field crossing calculation. Blending
            // independently found corner heights would manufacture planar shelves.
            let step = registry.terrain_cell[1] as i32;
            let bottom = min.div_euclid(step) * step;
            let top =
                max.div_euclid(step) * step + if max.rem_euclid(step) != 0 { step } else { 0 };
            let mut upper = self.density(1, [x as f32, max as f32, z as f32])?;
            let mut upper_y = max as f32;
            let mut found = if upper > 0.0 { Some(max as f32) } else { None };
            if found.is_none() {
                for layer in (0..(top - bottom) / step).rev() {
                    let y = bottom + layer * step;
                    let lower = self.density(1, [x as f32, y as f32, z as f32])?;
                    if lower > 0.0 {
                        let crossing =
                            y as f32 + (upper_y - y as f32) * lower / (lower - upper).max(0.000001);
                        found = Some(
                            (crossing + 1.0).clamp(self.request.min_y as f32 + 1.0, max as f32),
                        );
                        break;
                    }
                    upper = lower;
                    upper_y = y as f32;
                }
            }
            found.unwrap_or(empty)
        } else {
            self.scanned_surface_height([x, z], [min, max], empty, registry.density_composition)?
        };
        if self.heights.len() >= 32768 {
            self.heights.clear();
        }
        self.heights.insert([x.to_bits(), z.to_bits()], height);
        Ok(height)
    }
    fn scanned_surface_height(
        &mut self,
        [x, z]: [f32; 2],
        [min, max]: [i32; 2],
        empty: f32,
        certificates: bool,
    ) -> Result<f32, String> {
        let registry = self.profile.registry_program.as_ref().unwrap();
        let step = i64::from(registry.terrain_cell[1]);
        let certificates = certificates && self.evaluator.as_ref().unwrap().can_bound(1)
            && x.abs() <= 8388608.0 && z.abs() <= 8388608.0
            && min >= -8388608 && max <= 8388608;
        let (mut sampled, mut skipped, mut intervals) = (0, 0, 0);
        let mut height = empty;
        let mut top = max;
        'height: while top > min {
            let bottom = if certificates {
                ((i64::from(top) - 1).div_euclid(step) * step).max(i64::from(min)) as i32
            } else {
                min
            };
            if certificates {
                intervals += 1;
                let lo = (((top - 1) as f32) / step as f32).floor() * step as f32;
                let encloses = lo <= bottom as f32 && lo + step as f32 >= (top - 1) as f32;
                let bounds = self.density_certificate_at(1, [x, (top - 1) as f32, z]);
                if encloses && bounds.finite() && bounds.hi <= 0.0 {
                    skipped += (top - bottom) as u64;
                    top = bottom;
                    continue;
                }
            }
            // Visit uncertain cells in the original descending integer order.
            // Bounds never choose a crossing or hide islands/overhangs.
            for y in (bottom..top).rev() {
                sampled += 1;
                if self.density(1, [x, y as f32, z])? > 0.0 {
                    height = (y + 1) as f32;
                    break 'height;
                }
            }
            top = bottom;
        }
        if let Some(timings) = &self.fields.sampling_timings {
            use crate::performance::Counter::*;
            timings.performance.add(cpu_height_scalar_points, sampled);
            timings.performance.add(cpu_height_air_points_skipped, skipped);
            timings.performance.add(cpu_height_interval_queries, intervals);
        }
        Ok(height)
    }
    pub fn surface_biome(
        &mut self,
        x: i32,
        z: i32,
        initial: u32,
        exclude_shores: bool,
    ) -> Result<u32, String> {
        let actual = self.climate([x as f32, 0.0, z as f32], true)?;
        Ok(self.registered_biome(actual, initial, exclude_shores))
    }
    pub fn registered_biome(&mut self, actual: [f32; 6], initial: u32, exclude_shores: bool) -> u32 {
        if self
            .profile
            .registry_program
            .as_ref()
            .is_some_and(|r| r.enclosed())
        {
            return self.climate_search(actual, true, initial, 1e20, false);
        }
        if !self.fields.climate.has_surface() {
            return initial;
        }
        let (mut biome, mut best) = (initial, 1e20);
        for &index in &self.fields.unplaced {
            let b = &self.profile.biomes[index];
            let fitness = (0..4)
                .map(|i| (actual[i] - b.climate[i]).powi(2))
                .sum::<f32>()
                - 0.03;
            if fitness < best {
                best = fitness;
                biome = index as u32;
            }
        }
        self.climate_search(actual, false, biome, best, exclude_shores)
    }
    pub fn has_underground(&self) -> bool {
        self.fields.climate.has_underground()
    }
    pub fn underground_biome(&mut self, point: [f32; 3], surface: u32) -> Result<u32, String> {
        if !self.fields.climate.has_underground() {
            return Ok(surface);
        }
        let actual = self.climate(point, false)?;
        Ok(self.climate_search(actual, true, surface, 1e20, false))
    }
    fn climate_search(&mut self, actual: [f32; 6], underground: bool,
        initial: u32, fitness: f32, exclude_shores: bool) -> u32 {
        let slot = usize::from(underground);
        let (biome, hint) = self.fields.climate.search_with_hint(actual, false, underground,
            false, initial, fitness, exclude_shores, self.climate_hints[slot]);
        self.climate_hints[slot] = hint;
        biome
    }
    pub fn coastal_biome(&mut self, x: i32, z: i32, initial: u32) -> Result<u32, String> {
        let actual = self.climate([x as f32, 0.0, z as f32], true)?;
        Ok(self.registered_biome(self.fields.climate.coastal(actual), initial, false))
    }
    pub fn slope(&mut self, x: i32, z: i32, layered: bool) -> Result<f32, String> {
        let radius = if layered { 1 } else { 4 };
        let east = self.surface_height(x + radius, z)?;
        let west = self.surface_height(x - radius, z)?;
        let south = self.surface_height(x, z + radius)?;
        let north = self.surface_height(x, z - radius)?;
        Ok(if layered {
            (east.floor() - west.floor())
                .abs()
                .max((south.floor() - north.floor()).abs())
        } else {
            (east - west).abs() + (south - north).abs()
        })
    }
    pub fn surface_noise(&self, point: [f32; 3], slot: usize) -> Result<f32, String> {
        let registry = self
            .profile
            .registry_program
            .as_ref()
            .ok_or("CPU sample has no registered surface noise")?;
        Ok(self
            .evaluator
            .as_ref()
            .unwrap()
            .noise(point, registry.surface_noises[slot] as usize))
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::world_definition::{WorldDefinition, catalog::WorldCatalog};
    #[test]
    fn certified_height_matches_scalar_scan_across_dimensions_and_clipped_heights() {
        let catalog = WorldCatalog::read(std::path::Path::new(concat!(
            env!("CARGO_MANIFEST_DIR"),
            "/../assets/world-definitions/26.3/vanilla-binary/retina-catalog.dat"
        ))).unwrap();
        for seed in [123456789, -726381] {
            let d = WorldDefinition::from_gzip(catalog.materialize("normal", seed).unwrap().as_slice()).unwrap();
            // End uses its dedicated terrain kernel, not registered height scans.
            for key in ["minecraft:overworld", "minecraft:the_nether"] {
                let profile = d.dimension(key).unwrap().profile().unwrap();
                let request = d.request(key, 1, 0, 0).unwrap();
                let fields = Fields::new(profile).unwrap();
                let mut accelerated = fields.sampler(profile, request);
                let mut reference = fields.sampler(profile, request);
                for point in [[0.0, 0.0], [-17.25, 31.5], [-512.0, 511.0],
                    [8388608.0, -8388608.0], [16777216.0, -16777216.0]] {
                    for range in [accelerated.bounds(), [-1536, 1536], [-31, 71], [71, 72], [0, 0]] {
                        let a = accelerated.scanned_surface_height(point, range, -1536.0, true).unwrap();
                        let b = reference.scanned_surface_height(point, range, -1536.0, false).unwrap();
                        assert_eq!(a.to_bits(), b.to_bits(), "{seed} {key} {point:?} {range:?}");
                    }
                }
            }
        }
    }
    #[test]
    fn certified_height_retains_thin_islands_zero_density_and_invalid_roots() {
        use crate::program::{Instruction, Program};
        let catalog = WorldCatalog::read(std::path::Path::new(concat!(
            env!("CARGO_MANIFEST_DIR"),
            "/../assets/world-definitions/26.3/vanilla-binary/retina-catalog.dat"
        ))).unwrap();
        let d = WorldDefinition::from_gzip(catalog.materialize("normal", 1).unwrap().as_slice()).unwrap();
        for (outside, inside, expected) in [(-1.0, 1.0, 72.0), (-1.0, 0.0, -64.0),
            (-0.0, 1e-30, 72.0), (1.0, 1.0, 320.0)] {
            let mut profile = d.dimension("minecraft:overworld").unwrap().profile().unwrap().as_ref().clone();
            let registry = profile.registry_program.as_mut().unwrap();
            registry.programs[1] = Program { nodes: vec![
                Instruction {op:0,a:0,b:0,c:0,p:[outside;4]},
                Instruction {op:29,a:1,b:0,c:0,p:[0.0;4]},
                Instruction {op:0,a:0,b:0,c:0,p:[inside;4]},
                Instruction {op:23,a:1,b:2,c:0,p:[71.0,72.0,0.0,0.0]},
            ], roots: vec![3,0,0,0,0,0] };
            for cell in [[4,8], [3,7]] {
                profile.registry_program.as_mut().unwrap().terrain_cell = cell;
                let fields = Fields::new(&profile).unwrap();
                let request = d.request("minecraft:overworld", 1, 0, 0).unwrap();
                let mut a = fields.sampler(&profile, request);
                let mut b = fields.sampler(&profile, request);
                let one = a.scanned_surface_height([-17.25, 31.5], [-64,320], -64.0, true).unwrap();
                let two = b.scanned_surface_height([-17.25, 31.5], [-64,320], -64.0, false).unwrap();
                assert_eq!(one.to_bits(), two.to_bits());
                assert_eq!(one, expected);
            }
            // An invalid returned root must still report the scalar error even
            // when root zero's density would otherwise certify the cell as air.
            let registry = profile.registry_program.as_mut().unwrap();
            registry.programs[1].nodes.push(Instruction {op:0,a:0,b:0,c:0,p:[f32::MAX;4]});
            registry.programs[1].nodes.push(Instruction {op:12,a:4,b:0,c:0,p:[0.0;4]});
            registry.programs[1].roots[1] = 5;
            if outside < 0.0 {
                let fields = Fields::new(&profile).unwrap();
                let request = d.request("minecraft:overworld", 1, 0, 0).unwrap();
                let mut a = fields.sampler(&profile, request);
                let mut b = fields.sampler(&profile, request);
                assert_eq!(a.scanned_surface_height([0.0,0.0], [100,120], 0.0, true).unwrap_err(),
                    b.scanned_surface_height([0.0,0.0], [100,120], 0.0, false).unwrap_err());
            }
        }
    }
    #[test]
    fn actual_density_certificates_enclose_every_block_in_sampled_cells() {
        let catalog = WorldCatalog::read(std::path::Path::new(concat!(
            env!("CARGO_MANIFEST_DIR"),
            "/../assets/world-definitions/26.3/vanilla-binary/retina-catalog.dat"
        )))
        .unwrap();
        let d = WorldDefinition::from_gzip(
            catalog.materialize("normal", 123456789).unwrap().as_slice(),
        )
        .unwrap();
        let mut certified = 0;
        for key in ["minecraft:overworld", "minecraft:the_nether"] {
            let dimension = d.dimension(key).unwrap();
            let profile = dimension.profile().unwrap();
            let r = d.request(key, 1, 0, 0).unwrap();
            let f = Fields::new(profile).unwrap();
            let mut sampler = f.sampler(profile, r);
            let cell = profile.registry_program.as_ref().unwrap().terrain_cell;
            for point in [
                [-17, -31, 19],
                [0, 0, 0],
                [11, 32, -23],
                [31, 70, 31],
                [8, 120, 8],
            ] {
                let bounds = sampler.density_certificate(1, point);
                if !bounds.finite() {
                    continue;
                }
                certified += 1;
                let step = [cell[0] as i32, cell[1] as i32, cell[0] as i32];
                let lo = std::array::from_fn::<_, 3, _>(|i| point[i].div_euclid(step[i]) * step[i]);
                for y in 0..step[1] {
                    for z in 0..step[2] {
                        for x in 0..step[0] {
                            let p = [(lo[0] + x) as f32, (lo[1] + y) as f32, (lo[2] + z) as f32];
                            let value = sampler.density(1, p).unwrap();
                            assert!(
                                value >= bounds.lo && value <= bounds.hi,
                                "{key} {p:?} value {value} outside {bounds:?}"
                            );
                        }
                    }
                }
            }
        }
        assert!(
            certified > 0,
            "actual vanilla density graph should yield useful conservative certificates"
        );
    }
    #[test]
    fn actual_vanilla_field_samples_are_finite_repeatable_and_globally_aligned() {
        let path = std::path::Path::new(concat!(
            env!("CARGO_MANIFEST_DIR"),
            "/../assets/world-definitions/26.3/vanilla-binary/retina-catalog.dat"
        ));
        let catalog = WorldCatalog::read(path).unwrap();
        let definition = WorldDefinition::from_gzip(
            catalog.materialize("normal", 123456789).unwrap().as_slice(),
        )
        .unwrap();
        for key in ["minecraft:overworld", "minecraft:the_nether"] {
            let dimension = definition.dimension(key).unwrap();
            let profile = dimension.profile().unwrap();
            let p = dimension.parameters();
            let request = ChunkRequest {
                seed: definition.seed() as u64,
                chunk_x: 0,
                chunk_z: 0,
                min_y: p.min_y,
                height: p.height as u32,
                base_height: p.base_height,
                amplitude: p.amplitude,
                frequency: p.frequency,
                reserved: 0,
            };
            let fields = Fields::new(profile).unwrap();
            let mut a = fields.sampler(profile, request);
            let mut b = fields.sampler(
                profile,
                ChunkRequest {
                    chunk_x: -33,
                    chunk_z: 32,
                    ..request
                },
            );
            for point in [
                [-17.0, 63.0, 31.0],
                [0.0, 0.0, 0.0],
                [16777216.0, -31.0, -16777216.0],
            ] {
                let one = a.climate(point, false).unwrap();
                let two = b.climate(point, false).unwrap();
                assert_eq!(one.map(f32::to_bits), two.map(f32::to_bits));
                let density = a.density(1, point).unwrap();
                assert!(density.is_finite());
                assert_eq!(density.to_bits(), b.density(1, point).unwrap().to_bits());
                let biome = a.underground_biome(point, 0).unwrap();
                assert!((biome as usize) < profile.biomes.len());
                let height = a.surface_height(point[0] as i32, point[2] as i32).unwrap();
                assert!(height >= p.min_y as f32 && height <= (p.min_y + p.height as i32) as f32);
                assert_eq!(
                    height,
                    b.surface_height(point[0] as i32, point[2] as i32).unwrap()
                );
            }
        }
    }
}
