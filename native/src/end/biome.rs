//! Seeded block-to-quart biome zoom used by vanilla placement biome filters.
use sha2::{Digest, Sha256};
pub struct BiomeZoom { seed: u64 }
fn next(value: u64, salt: u64) -> u64 {
    value.wrapping_mul(value.wrapping_mul(6364136223846793005).wrapping_add(1442695040888963407)).wrapping_add(salt)
}
fn fiddle(value: u64) -> f64 { ((((value as i64) >> 24).rem_euclid(1024)) as f64 / 1024.0 - 0.5) * 0.9 }
impl BiomeZoom {
    pub fn new(seed: u64) -> Self {
        let hash = Sha256::digest(seed.to_le_bytes());
        Self { seed: u64::from_le_bytes(hash[..8].try_into().unwrap()) }
    }
    pub fn quart(&self, at: [i32; 3]) -> [i32; 3] {
        let shifted = at.map(|v| v.wrapping_sub(2));
        let parent = shifted.map(|v| v >> 2);
        let fraction = shifted.map(|v| (v & 3) as f64 / 4.0);
        let mut best = f64::INFINITY; let mut selected = 0;
        for i in 0..8 {
            let corner: [i32;3] = std::array::from_fn(|axis| parent[axis] + i32::from(i & (4 >> axis) != 0));
            let distance: [f64;3] = std::array::from_fn(|axis| fraction[axis] - f64::from(i & (4 >> axis) != 0));
            let mut value = self.seed;
            for axis in [0,1,2,0,1,2] { value = next(value, corner[axis] as i64 as u64); }
            let x = fiddle(value); value = next(value,self.seed);
            let y = fiddle(value); value = next(value,self.seed);
            let z = fiddle(value);
            let dx = distance[0]+x; let dy = distance[1]+y; let dz = distance[2]+z;
            let d = dz*dz + dy*dy + dx*dx;
            if best > d { best = d; selected = i; }
        }
        std::array::from_fn(|axis| parent[axis] + i32::from(selected & (4 >> axis) != 0))
    }
}
