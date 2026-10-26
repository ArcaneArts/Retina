//! Dense reference for the loaded vanilla End graph, before feature/structure writes.
//! The scalar interpolator and bulk column use their respective Minecraft operation
//! orders. This reference is not an adapter for arbitrary custom density graphs.
use super::{BlendedNoise, BlendedParameters, IslandNoise};

pub struct VanillaDensity {
    islands: IslandNoise,
    noise: BlendedNoise,
}

/// Dense terrain lattice for a rectangle of block columns. Only the shared
/// 8×4×8 noise corners are stored; no occupancy shortcut is applied. Columns
/// retain the exact bulk interpolator's operation order, including Y stepping.
pub struct DenseTile {
    origin: [i32; 2],
    size: [u32; 2],
    corner_origin: [i32; 2],
    corners_x: usize,
    corners: Vec<[f32; 33]>,
    islands: super::CandidateField,
}

impl DenseTile {
    pub fn biome(&self, x: i32, z: i32) -> Option<super::Biome> { self.islands.biome(x, z) }
    /// Returns the full 128-block base-terrain density range, or None outside
    /// the requested rectangle. Dimension storage above this range remains air.
    pub fn column_densities(&self, x: i32, z: i32) -> Option<[f32; 128]> {
        let local_x = i64::from(x) - i64::from(self.origin[0]);
        let local_z = i64::from(z) - i64::from(self.origin[1]);
        if local_x < 0 || local_z < 0 || local_x >= i64::from(self.size[0])
            || local_z >= i64::from(self.size[1]) {
            return None;
        }
        let dx = x.rem_euclid(8);
        let dz = z.rem_euclid(8);
        let cx = ((i64::from(x) - i64::from(dx) - i64::from(self.corner_origin[0])) / 8) as usize;
        let cz = ((i64::from(z) - i64::from(dz) - i64::from(self.corner_origin[1])) / 8) as usize;
        let index = cz * self.corners_x + cx;
        let a = &self.corners[index];
        let b = &self.corners[index + 1];
        let c = &self.corners[index + self.corners_x];
        let d = &self.corners[index + self.corners_x + 1];
        let ax = dx as f32 * 0.125;
        let az = dz as f32 * 0.125;
        let mut values = [0.0; 128];
        for cell in 0..32 {
            let bottom = lerp(ax, lerp(az, a[cell], c[cell]), lerp(az, b[cell], d[cell]));
            let top = lerp(ax, lerp(az, a[cell + 1], c[cell + 1]), lerp(az, b[cell + 1], d[cell + 1]));
            let step = (top - bottom) * 0.25;
            let mut value = bottom + step * 0.0;
            for dy in 0..4 {
                values[cell * 4 + dy] = squeeze(value) + 0.0;
                value += step;
            }
        }
        Some(values)
    }

    pub fn column(&self, x: i32, z: i32) -> Option<[bool; 256]> {
        let densities = self.column_densities(x, z)?;
        Some(std::array::from_fn(|y| y < 128 && densities[y] > 0.0))
    }

    pub fn lattice_points(&self) -> usize { self.corners.len() * 33 }
    pub fn lattice_bytes(&self) -> usize { self.corners.len() * std::mem::size_of::<[f32; 33]>() }
}

fn lerp(t: f32, a: f32, b: f32) -> f32 { a + t * (b - a) }
fn select_lerp(t: f32, a: f32, b: f32) -> f32 {
    if t == 0.0 { a } else if t == 1.0 { b } else { lerp(t, a, b) }
}
fn squeeze(value: f32) -> f32 {
    let x = value.clamp(-1.0, 1.0);
    x / 2.0 - x * x * x / 24.0
}

impl VanillaDensity {
    pub fn new(seed: u64) -> Self {
        Self { islands: IslandNoise::new(seed), noise: BlendedNoise::new(seed, BlendedParameters::default()).unwrap() }
    }

    /// Construct each required island value once and each 3D noise corner once.
    /// Callers can interpolate columns in parallel without locks or new noise
    /// evaluation. The rectangle may be unaligned or cross chunk/region edges.
    pub fn tile(&self, x: i32, z: i32, width: u32, depth: u32) -> Result<DenseTile, String> {
        fn axis(origin: i32, length: u32) -> Result<(i32, usize), String> {
            if length == 0 { return Err("End tile dimensions must be nonzero".into()); }
            let first = i64::from(origin).div_euclid(8) * 8;
            let last = (i64::from(origin) + i64::from(length) - 1).div_euclid(8) * 8 + 8;
            i32::try_from(last).map_err(|_| "End tile noise corner exceeds coordinate range")?;
            Ok((first as i32, ((last - first) / 8 + 1) as usize))
        }
        let (corner_x, corners_x) = axis(x, width)?;
        let (corner_z, corners_z) = axis(z, depth)?;
        let count = corners_x.checked_mul(corners_z).ok_or("End tile lattice size overflow")?;
        let mut corners = Vec::new();
        corners.try_reserve_exact(count).map_err(|e| format!("cannot allocate End tile lattice: {e}"))?;
        let islands = self.islands.candidate_field(corner_x, corner_z,
            (i64::from(corner_x) + (corners_x as i64 - 1) * 8) as i32,
            (i64::from(corner_z) + (corners_z as i64 - 1) * 8) as i32)?;
        for cz in 0..corners_z {
            let block_z = (i64::from(corner_z) + cz as i64 * 8) as i32;
            for cx in 0..corners_x {
                let block_x = (i64::from(corner_x) + cx as i64 * 8) as i32;
                let island = islands.sample(block_x, block_z).unwrap();
                corners.push(std::array::from_fn(|cy| self.raw(block_x, cy as i32 * 4, block_z, island, true)));
            }
        }
        Ok(DenseTile { origin: [x, z], size: [width, depth], corner_origin: [corner_x, corner_z], corners_x, corners, islands })
    }

    fn raw(&self, x: i32, y: i32, z: i32, island: f32, volume: bool) -> f32 {
        let bottom = (y.clamp(4, 32) - 4) as f32 * (1.0f32 / 28.0);
        if bottom == 0.0 { return -0.234375 * 0.64; }
        let top = 1.0 + (y.clamp(56, 312) - 56) as f32 * (-1.0f32 / 256.0);
        let shape = if top == 0.0 { -23.4375 } else {
            let noise = if volume { self.noise.volume_point(x, y, z) } else { self.noise.sample(x, y, z) };
            select_lerp(top, -23.4375, island + noise)
        };
        select_lerp(bottom, -0.234375, shape) * 0.64
    }

    fn island_corners(&self, x: i32, z: i32) -> [f32; 4] {
        std::array::from_fn(|i| self.islands.sample(x.wrapping_add((i as i32 & 1) * 8),
            z.wrapping_add(((i as i32 >> 1) & 1) * 8)))
    }

    fn row(&self, x: i32, y: i32, z: i32, islands: &[f32; 4]) -> [f32; 4] {
        std::array::from_fn(|i| self.raw(x.wrapping_add((i as i32 & 1) * 8), y,
            z.wrapping_add(((i as i32 >> 1) & 1) * 8), islands[i], true))
    }

    pub fn sample(&self, x: i32, y: i32, z: i32) -> f32 {
        let dx = x.rem_euclid(8);
        let dy = y.rem_euclid(4);
        let dz = z.rem_euclid(8);
        if dx == 0 && dy == 0 && dz == 0 {
            return squeeze(self.raw(x, y, z, self.islands.sample(x, z), false)) + 0.0;
        }
        let bx = x - dx;
        let by = y - dy;
        let bz = z - dz;
        let islands = self.island_corners(bx, bz);
        let a = self.row(bx, by, bz, &islands);
        let b = self.row(bx, by.wrapping_add(4), bz, &islands);
        let ax = dx as f32 / 8.0;
        let ay = dy as f32 / 4.0;
        let az = dz as f32 / 8.0;
        let value = lerp(az,
            lerp(ay, lerp(ax, a[0], a[1]), lerp(ax, b[0], b[1])),
            lerp(ay, lerp(ax, a[2], a[3]), lerp(ax, b[2], b[3])));
        squeeze(value) + 0.0
    }

    /// Actual bulk interpolator: Z, then X, then an accumulated Y step per cell.
    /// Returns the 128-block noise range; dimension storage above it remains air.
    pub fn column_densities(&self, x: i32, z: i32) -> [f32; 128] {
        let dx = x.rem_euclid(8);
        let dz = z.rem_euclid(8);
        let bx = x - dx;
        let bz = z - dz;
        let ax = dx as f32 * 0.125;
        let az = dz as f32 * 0.125;
        let islands = self.island_corners(bx, bz);
        let mut lower = self.row(bx, 0, bz, &islands);
        let mut values = [0.0; 128];
        for cell in 0..32 {
            let upper = self.row(bx, cell as i32 * 4 + 4, bz, &islands);
            let bottom = lerp(ax, lerp(az, lower[0], lower[2]), lerp(az, lower[1], lower[3]));
            let top = lerp(ax, lerp(az, upper[0], upper[2]), lerp(az, upper[1], upper[3]));
            let step = (top - bottom) * 0.25;
            let mut value = bottom + step * 0.0;
            for dy in 0..4 {
                values[cell * 4 + dy] = squeeze(value) + 0.0;
                value += step;
            }
            lower = upper;
        }
        values
    }

    pub fn column(&self, x: i32, z: i32) -> [bool; 256] {
        let densities = self.column_densities(x, z);
        std::array::from_fn(|y| y < 128 && densities[y] > 0.0)
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn vanilla_graph_bits_and_bulk_columns() {
        let reference: serde_json::Value = serde_json::from_str(include_str!("../../tests/fixtures/end-density-26.3.json")).unwrap();
        for group in reference["seeds"].as_array().unwrap() {
            let seed = group["seed"].as_i64().unwrap();
            let noise = VanillaDensity::new(seed as u64);
            for sample in group["samples"].as_array().unwrap() {
                let x = sample["xz"][0].as_i64().unwrap() as i32;
                let z = sample["xz"][1].as_i64().unwrap() as i32;
                for value in sample["density_y_bits"].as_array().unwrap() {
                    let y = value[0].as_i64().unwrap() as i32;
                    assert_eq!(noise.sample(x, y, z).to_bits(), value[1].as_i64().unwrap() as u32,
                        "density seed={seed} at {x},{y},{z}");
                }
            }
            for sample in group["columns"].as_array().unwrap() {
                let x = sample["xz"][0].as_i64().unwrap() as i32;
                let z = sample["xz"][1].as_i64().unwrap() as i32;
                let actual = noise.column(x, z);
                let tile = noise.tile(x.div_euclid(16) * 16, z.div_euclid(16) * 16, 16, 16).unwrap();
                assert_eq!(tile.column(x, z).unwrap(), actual, "tiled column seed={seed} at {x},{z}");
                for (y, solid) in actual.into_iter().enumerate() {
                    let expected = sample["solid_y"].as_array().unwrap().iter().any(|v| v.as_u64() == Some(y as u64));
                    assert_eq!(solid, expected, "column seed={seed} at {x},{y},{z}");
                }
            }
        }
    }

    #[test]
    fn dense_lattice_matches_columns_across_unaligned_and_far_edges() {
        for (seed, x, z) in [(42u64, -9, -17), (123456789, 4091, -529),
            (u64::MAX, -16777217, 16777211)] {
            let noise = VanillaDensity::new(seed);
            let tile = noise.tile(x, z, 19, 11).unwrap();
            for dz in 0..11 {
                for dx in 0..19 {
                    let actual = tile.column_densities(x + dx, z + dz).unwrap().map(f32::to_bits);
                    let expected = noise.column_densities(x + dx, z + dz).map(f32::to_bits);
                    assert_eq!(actual, expected, "seed={seed} at {},{}", x + dx, z + dz);
                }
            }
            assert!(tile.column_densities(x - 1, z).is_none());
            assert!(tile.column_densities(x + 19, z).is_none());
            assert!(tile.column_densities(x, z - 1).is_none());
            assert!(tile.column_densities(x, z + 11).is_none());
        }
    }

    #[test]
    fn dense_lattice_shares_corners_and_checks_its_real_bounds() {
        let noise = VanillaDensity::new(42);
        let tile = noise.tile(-8, 8, 16, 16).unwrap();
        assert_eq!(tile.lattice_points(), 3 * 3 * 33);
        assert_eq!(tile.lattice_bytes(), 3 * 3 * 33 * 4);
        assert!(noise.tile(0, 0, 0, 16).is_err());
        assert!(noise.tile(0, 0, 16, 0).is_err());
        assert!(noise.tile(i32::MAX, 0, 1, 1).is_err());
        assert!(noise.tile(0, i32::MAX - 7, 1, 8).is_err());
        assert!(noise.tile(i32::MAX - 15, 0, u32::MAX, 1).is_err());
    }
}
