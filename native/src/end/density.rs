//! Dense reference for the loaded vanilla End graph, before feature/structure writes.
//! The scalar interpolator and bulk column use their respective Minecraft operation
//! orders. This reference is not an adapter for arbitrary custom density graphs.
use super::{BlendedNoise, BlendedParameters, IslandNoise};
#[path="sparse.rs"]
mod sparse;
pub(crate) use sparse::SparseTile;

pub struct VanillaDensity {
    islands: IslandNoise,
    noise: BlendedNoise,
}

/// Dense terrain lattice for a rectangle of block columns. Only the shared
/// 8×4×8 noise corners are stored. Exact density queries retain the dense
/// evaluator; solidity queries can use conservative signs of complete cells.
pub struct DenseTile {
    origin: [i32; 2],
    size: [u32; 2],
    corner_origin: [i32; 2],
    corners_x: usize,
    corners: Vec<[f32; 33]>,
    occupancy: Vec<[Occupancy; 32]>,
    mask_nanos: u64,
    islands: super::CandidateField,
}

#[derive(Clone, Copy, Debug, PartialEq, Eq)]
enum Occupancy { Empty, Solid, Uncertain }

/// Interpolation uses two convex lerps followed by at most three accumulated
/// quarter steps. Each rounded add/multiply contributes at most a few ulps of
/// the largest corner. 64 epsilon is deliberately larger than that complete
/// error budget, including subnormals. Near-zero and nonfinite inputs remain
/// uncertain. This bounds the actual eight corners, not sampled block points.
fn classify(corners: [f32; 8]) -> Occupancy {
    if corners.iter().any(|v| !v.is_finite()) { return Occupancy::Uncertain; }
    let min = corners.into_iter().fold(f32::INFINITY, f32::min);
    let max = corners.into_iter().fold(f32::NEG_INFINITY, f32::max);
    classify_range(min,max)
}
fn classify_range(min:f32,max:f32)->Occupancy {
    if !min.is_finite() || !max.is_finite() { return Occupancy::Uncertain; }
    let magnitude = min.abs().max(max.abs());
    if magnitude > f32::MAX / 4.0 { return Occupancy::Uncertain; }
    let margin = 64.0 * f32::EPSILON * magnitude.max(f32::MIN_POSITIVE);
    if max < -margin { Occupancy::Empty }
    else if min > margin { Occupancy::Solid }
    else { Occupancy::Uncertain }
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
        let bits = self.column_bits(x, z)?;
        Some(std::array::from_fn(|y| y < 128 && bits & (1u128 << y) != 0))
    }

    /// Compact base-terrain column. Features and structures are deliberately
    /// outside this mask and still run through ordered decoration normally.
    pub(crate) fn column_bits(&self, x: i32, z: i32) -> Option<u128> {
        let lx = i64::from(x) - i64::from(self.origin[0]);
        let lz = i64::from(z) - i64::from(self.origin[1]);
        if lx < 0 || lz < 0 || lx >= i64::from(self.size[0]) || lz >= i64::from(self.size[1]) { return None; }
        let dx = x.rem_euclid(8); let dz = z.rem_euclid(8);
        let cx = ((i64::from(x) - i64::from(dx) - i64::from(self.corner_origin[0])) / 8) as usize;
        let cz = ((i64::from(z) - i64::from(dz) - i64::from(self.corner_origin[1])) / 8) as usize;
        let index = cz * self.corners_x + cx;
        let mask = &self.occupancy[cz * (self.corners_x - 1) + cx];
        let [a,b,c,d] = [&self.corners[index], &self.corners[index+1],
            &self.corners[index+self.corners_x], &self.corners[index+self.corners_x+1]];
        let ax = dx as f32 * 0.125; let az = dz as f32 * 0.125;
        let mut bits = 0u128;
        for cell in 0..32 {
            match mask[cell] {
                Occupancy::Empty => {},
                Occupancy::Solid => bits |= 15u128 << (cell * 4),
                Occupancy::Uncertain => {
                    let bottom = lerp(ax, lerp(az,a[cell],c[cell]), lerp(az,b[cell],d[cell]));
                    let top = lerp(ax, lerp(az,a[cell+1],c[cell+1]), lerp(az,b[cell+1],d[cell+1]));
                    let step = (top - bottom) * 0.25;
                    let mut value = bottom + step * 0.0;
                    for dy in 0..4 {
                        if squeeze(value) + 0.0 > 0.0 { bits |= 1u128 << (cell*4+dy); }
                        value += step;
                    }
                },
            }
        }
        Some(bits)
    }

    pub(crate) fn occupancy_counts(&self) -> [u64; 3] {
        let mut counts = [0;3];
        for cells in &self.occupancy { for cell in cells {
            counts[match cell { Occupancy::Empty=>0,Occupancy::Solid=>1,Occupancy::Uncertain=>2 }] += 1;
        }}
        counts
    }

    pub(crate) fn mask_nanos(&self) -> u64 { self.mask_nanos }
    pub(crate) fn mask_bytes(&self) -> usize { self.occupancy.len()*std::mem::size_of::<[Occupancy;32]>() }

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

// At lattice heights 0..128 both lerp factors are nonnegative. Every operation
// is monotone in noise, so the same source-order expression propagates bounds.
fn vertical(y:i32,island:f32,noise:impl FnOnce()->f32)->f32 {
    let bottom = (y.clamp(4,32)-4) as f32 * (1.0f32/28.0);
    if bottom == 0.0 { return -0.234375*0.64; }
    let top=1.0+(y.clamp(56,312)-56) as f32 * (-1.0f32/256.0);
    let shape=if top==0.0 {-23.4375}else{select_lerp(top,-23.4375,island+noise())};
    select_lerp(bottom,-0.234375,shape)*0.64
}

impl VanillaDensity {
    pub fn new(seed: u64) -> Self {
        Self { islands: IslandNoise::new(seed), noise: BlendedNoise::new(seed, BlendedParameters::default()).unwrap() }
    }

    /// Construct each required island value once and each 3D noise corner once.
    /// Callers can interpolate columns in parallel without locks or new noise
    /// evaluation. The rectangle may be unaligned or cross chunk/region edges.
    pub fn tile(&self, x: i32, z: i32, width: u32, depth: u32) -> Result<DenseTile, String> {
        let (mut tile,corners_z)=self.tile_layout(x,z,width,depth)?;
        let corners_x=tile.corners_x;
        for cz in 0..corners_z {
            let block_z = (i64::from(tile.corner_origin[1]) + cz as i64 * 8) as i32;
            for cx in 0..corners_x {
                let block_x = (i64::from(tile.corner_origin[0]) + cx as i64 * 8) as i32;
                let island = tile.islands.sample(block_x, block_z).unwrap();
                tile.corners.push(std::array::from_fn(|cy| self.raw(block_x, cy as i32 * 4, block_z, island, true)));
            }
        }
        let mask_start = crate::performance::enabled().then(std::time::Instant::now);
        tile.occupancy = (0..(corners_z-1)*(corners_x-1)).map(|i| {
            let at = i / (corners_x-1) * corners_x + i % (corners_x-1);
            std::array::from_fn(|cy| classify([
                tile.corners[at][cy],tile.corners[at+1][cy],tile.corners[at+corners_x][cy],tile.corners[at+corners_x+1][cy],
                tile.corners[at][cy+1],tile.corners[at+1][cy+1],tile.corners[at+corners_x][cy+1],tile.corners[at+corners_x+1][cy+1]]))
        }).collect();
        tile.mask_nanos=mask_start.map_or(0,|t|t.elapsed().as_nanos() as u64);
        Ok(tile)
    }

    pub(crate) fn sparse_tile(&self,x:i32,z:i32,width:u32,depth:u32)->Result<SparseTile,String> {
        SparseTile::new(self,x,z,width,depth)
    }

    fn tile_layout(&self,x:i32,z:i32,width:u32,depth:u32)->Result<(DenseTile,usize),String> {
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
        Ok((DenseTile { origin:[x,z],size:[width,depth],corner_origin:[corner_x,corner_z],corners_x,
            corners,occupancy:Vec::new(),mask_nanos:0,islands },corners_z))
    }

    fn raw(&self, x: i32, y: i32, z: i32, island: f32, volume: bool) -> f32 {
        vertical(y,island,||if volume {self.noise.volume_point(x,y,z)}else{self.noise.sample(x,y,z)})
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
                    let solid = tile.column(x+dx,z+dz).unwrap();
                    assert_eq!(solid,std::array::from_fn(|y|y<128 && f32::from_bits(actual[y])>0.0),
                        "sparse solidity seed={seed} at {},{}",x+dx,z+dz);
                }
            }
            assert!(tile.column_densities(x - 1, z).is_none());
            assert!(tile.column_densities(x + 19, z).is_none());
            assert!(tile.column_densities(x, z - 1).is_none());
            assert!(tile.column_densities(x, z + 11).is_none());
        }
    }

    #[test]
    fn conservative_cell_signs_include_the_interpolator_rounding_budget() {
        assert_eq!(classify([0.0;8]),Occupancy::Uncertain);
        assert_eq!(classify([f32::NAN;8]),Occupancy::Uncertain);
        assert_eq!(classify([f32::INFINITY;8]),Occupancy::Uncertain);
        assert_eq!(classify([-1.0,-1.0e-9,-1.0,-1.0,-1.0,-1.0,-1.0,-1.0]),Occupancy::Uncertain);
        for scale in [f32::MIN_POSITIVE,1.0e-30,0.001,1.0,1.0e30] {
            for sign in [-1.0,1.0] {
                let corners = std::array::from_fn(|i|sign*scale*(i as f32+1.0));
                let mask = classify(corners);
                assert_eq!(mask,if sign<0.0 {Occupancy::Empty}else{Occupancy::Solid});
                for z in 0..8 { for x in 0..8 {
                    let ax=x as f32*0.125;let az=z as f32*0.125;
                    let bottom=lerp(ax,lerp(az,corners[0],corners[2]),lerp(az,corners[1],corners[3]));
                    let top=lerp(ax,lerp(az,corners[4],corners[6]),lerp(az,corners[5],corners[7]));
                    let step=(top-bottom)*0.25;let mut value=bottom+step*0.0;
                    for _ in 0..4 { assert_eq!(squeeze(value)+0.0>0.0,mask==Occupancy::Solid);value+=step; }
                }}
            }
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
