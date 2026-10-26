//! Dense reference for the loaded vanilla End graph, before feature/structure writes.
//! The scalar interpolator and bulk column use their respective Minecraft operation
//! orders. This reference is not an adapter for arbitrary custom density graphs.
use super::{BlendedNoise, BlendedParameters, IslandNoise};

pub struct VanillaDensity {
    islands: IslandNoise,
    noise: BlendedNoise,
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
                for (y, solid) in actual.into_iter().enumerate() {
                    let expected = sample["solid_y"].as_array().unwrap().iter().any(|v| v.as_u64() == Some(y as u64));
                    assert_eq!(solid, expected, "column seed={seed} at {x},{y},{z}");
                }
            }
        }
    }
}
