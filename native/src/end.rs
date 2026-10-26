//! Vanilla 26.3 End island field. Seed setup is shared with the existing Java LCG.
//! Keep f64 simplex arithmetic, f32 island arithmetic and truncating integer
//! division distinct: changing any of them changes islands at negative/far coordinates.
use crate::structures::Random;
mod blended;
pub use blended::{BlendedNoise, Parameters as BlendedParameters};
mod field;
pub use field::CandidateField;
mod density;
mod terrain;
pub use terrain::Profile;
pub use density::{DenseTile, VanillaDensity};

fn permutation(random: &mut Random) -> [u8; 256] {
    let mut permutation = std::array::from_fn(|i| i as u8);
    for i in 0..256 {
        let offset = random.int((256 - i) as u32) as usize;
        permutation.swap(i, i + offset);
    }
    permutation
}

pub struct IslandNoise {
    permutation: [u8; 256],
}

#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub enum Biome {
    Center,
    Highlands,
    Midlands,
    SmallIslands,
    Barrens,
}
impl Biome {
    pub fn id(self) -> &'static str {
        match self {
            Self::Center => "minecraft:the_end",
            Self::Highlands => "minecraft:end_highlands",
            Self::Midlands => "minecraft:end_midlands",
            Self::SmallIslands => "minecraft:small_end_islands",
            Self::Barrens => "minecraft:end_barrens",
        }
    }
}

impl IslandNoise {
    pub fn new(seed: u64) -> Self {
        let mut random = Random::new(seed);
        random.consume(17292);
        // SimplexNoise(random, true) still draws its three offsets even though
        // their scale is zero. Omitting the draws changes the permutation.
        for _ in 0..3 {
            random.double();
        }
        Self { permutation: permutation(&mut random) }
    }

    fn permute(&self, i: i32) -> usize {
        self.permutation[(i & 255) as usize] as usize
    }

    /// Seed-center noise uses Minecraft's f64 2D simplex, then narrows to f32.
    pub fn center_noise(&self, x: i32, z: i32) -> f32 {
        let x = x as f64;
        let z = z as f64;
        let root_three = 3.0f64.sqrt();
        let skew = 0.5 * (root_three - 1.0);
        let unskew = (3.0 - root_three) / 6.0;
        let s = (x + z) * skew;
        let i = (x + s).floor() as i32;
        let j = (z + s).floor() as i32;
        let t = (i + j) as f64 * unskew;
        let x0 = x - (i as f64 - t);
        let z0 = z - (j as f64 - t);
        let (i1, j1) = if x0 > z0 { (1, 0) } else { (0, 1) };
        let x1 = x0 - i1 as f64 + unskew;
        let z1 = z0 - j1 as f64 + unskew;
        let x2 = x0 - 1.0 + 2.0 * unskew;
        let z2 = z0 - 1.0 + 2.0 * unskew;
        let ii = i & 255;
        let jj = j & 255;
        let g0 = self.permute(ii + self.permute(jj) as i32) % 12;
        let g1 = self.permute(ii + i1 + self.permute(jj + j1) as i32) % 12;
        let g2 = self.permute(ii + 1 + self.permute(jj + 1) as i32) % 12;
        ((Self::corner(g0, x0, z0) + Self::corner(g1, x1, z1)
            + Self::corner(g2, x2, z2))
            * 70.0) as f32
    }

    fn corner(index: usize, x: f64, z: f64) -> f64 {
        const GRADIENT: [[i32; 2]; 12] = [
            [1, 1], [-1, 1], [1, -1], [-1, -1],
            [1, 0], [-1, 0], [1, 0], [-1, 0],
            [0, 1], [0, -1], [0, 1], [0, -1],
        ];
        let mut t = 0.5 - x * x - z * z;
        if t < 0.0 {
            0.0
        } else {
            t *= t;
            let gradient = GRADIENT[index];
            t * t * (gradient[0] as f64 * x + gradient[1] as f64 * z + 0.0)
        }
    }

    pub fn outer(&self, block_x: i32, block_z: i32) -> f32 {
        // These / and % operations deliberately truncate toward zero, as Java
        // EndIslandFunction does. Biome chunk coordinates separately use floor.
        let section_x = block_x / 8;
        let section_z = block_z / 8;
        let chunk_x = section_x / 2;
        let chunk_z = section_z / 2;
        let sub_x = section_x % 2;
        let sub_z = section_z % 2;
        let mut height = -100.0f32;
        for dx in -12..=12 {
            for dz in -12..=12 {
                let cx = i64::from(chunk_x + dx);
                let cz = i64::from(chunk_z + dz);
                if cx * cx + cz * cz > 4096 && self.center_noise(cx as i32, cz as i32) < -0.9 {
                    let size = ((cx as f32).abs() * 3439.0 + (cz as f32).abs() * 147.0) % 13.0 + 9.0;
                    let x = (sub_x - dx * 2) as f32;
                    let z = (sub_z - dz * 2) as f32;
                    let candidate = (100.0 - (x * x + z * z).sqrt() * size).clamp(-100.0, 80.0);
                    height = height.max(candidate);
                }
            }
        }
        (height - 8.0) / 128.0
    }

    pub fn sample(&self, block_x: i32, block_z: i32) -> f32 {
        let x = block_x as f32;
        let z = block_z as f32;
        let distance = (x * x + z * z).sqrt();
        let main = ((100.0 - distance).clamp(-100.0, 80.0) - 8.0) * 0.0078125;
        main.max(self.outer(block_x, block_z))
    }

    pub fn biome(&self, block_x: i32, block_z: i32) -> Biome {
        let cx = block_x.div_euclid(16);
        let cz = block_z.div_euclid(16);
        if i64::from(cx) * i64::from(cx) + i64::from(cz) * i64::from(cz) <= 4096 {
            return Biome::Center;
        }
        let value = self.sample(cx * 16 + 8, cz * 16 + 8);
        if value > 0.25 {
            Biome::Highlands
        } else if value >= -0.0625 {
            Biome::Midlands
        } else if value < -0.21875 {
            Biome::SmallIslands
        } else {
            Biome::Barrens
        }
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn central_field_and_biome_are_distinct_from_the_void() {
        let noise = IslandNoise::new(42);
        assert_eq!(noise.sample(0, 0), 0.5625);
        assert_eq!(noise.sample(512, 0), -0.84375);
        assert_eq!(noise.biome(512, 0), Biome::Center);
        assert_eq!(noise.biome(0, 0), Biome::Center);
        assert_eq!(noise.biome(1024, 0), Biome::Center);
        assert_eq!(noise.sample(-1, -1), noise.sample(1, 1));
    }

    #[test]
    fn permutation_is_seeded_and_complete() {
        let a = IslandNoise::new(42);
        let b = IslandNoise::new(43);
        assert_ne!(a.permutation, b.permutation);
        let mut permutation = a.permutation;
        permutation.sort_unstable();
        assert_eq!(permutation, std::array::from_fn(|i| i as u8));
        assert_eq!(a.permutation, IslandNoise::new(42).permutation);
    }

    #[test]
    fn vanilla_26_3_reference_bits_and_biomes() {
        let reference: serde_json::Value = serde_json::from_str(include_str!(
            "../tests/fixtures/end-islands-26.3.json"
        )).unwrap();
        for seed in reference["seeds"].as_array().unwrap() {
            let value = seed["seed"].as_i64().unwrap();
            let noise = IslandNoise::new(value as u64);
            for sample in seed["samples"].as_array().unwrap() {
                let x = sample["xz"][0].as_i64().unwrap() as i32;
                let z = sample["xz"][1].as_i64().unwrap() as i32;
                assert_eq!(noise.sample(x, z).to_bits(), sample["island_bits"].as_i64().unwrap() as u32,
                    "island seed={value} at {x},{z}");
                assert_eq!(noise.biome(x, z).id(), sample["biome"].as_str().unwrap(),
                    "biome seed={value} at {x},{z}");
                if let Some(expected) = sample["outer_bits"].as_i64() {
                    assert_eq!(noise.outer(x, z).to_bits(), expected as u32, "outer seed={value} at {x},{z}");
                }
                if let Some(expected) = sample["center_bits"].as_i64() {
                    assert_eq!(noise.center_noise(x / 16, z / 16).to_bits(), expected as u32,
                        "simplex seed={value} at {x},{z}");
                }
            }
        }
    }
}
