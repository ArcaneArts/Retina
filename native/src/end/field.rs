//! Shared exact candidate-center table. The simplex predicate and center sizes
//! remain CPU f64/f32 operations; nearby terrain and biome samples reuse them.
use super::{Biome, IslandNoise};

struct Center { x: i32, z: i32, size: f32 }

pub struct CandidateField {
    bounds: [i32; 4],
    origin: [i32; 2],
    width: usize,
    height: usize,
    sizes: Vec<f32>,
    active: Vec<Center>,
}
impl IslandNoise {
    /// Inclusive block-coordinate bounds. Each candidate center needed anywhere
    /// in the rectangle is tested once, rather than once per queried position.
    pub fn candidate_field(&self, min_x: i32, min_z: i32, max_x: i32, max_z: i32)
        -> Result<CandidateField, String> {
        if min_x > max_x || min_z > max_z { return Err("inverted End field bounds".into()); }
        let origin = [min_x / 8 / 2 - 12, min_z / 8 / 2 - 12];
        let width = (i64::from(max_x / 8 / 2) + 12 - i64::from(origin[0]) + 1) as usize;
        let height = (i64::from(max_z / 8 / 2) + 12 - i64::from(origin[1]) + 1) as usize;
        let count = width.checked_mul(height).ok_or("End candidate table size overflow")?;
        let mut sizes = Vec::new();
        sizes.try_reserve_exact(count).map_err(|e| format!("cannot allocate End candidates: {e}"))?;
        let mut active = Vec::new();
        for z in 0..height { for x in 0..width {
            let cx = origin[0] + x as i32; let cz = origin[1] + z as i32;
            let eligible = i64::from(cx) * i64::from(cx) + i64::from(cz) * i64::from(cz) > 4096
                && self.center_noise(cx, cz) < -0.9;
            let size = if eligible {
                ((cx as f32).abs() * 3439.0 + (cz as f32).abs() * 147.0) % 13.0 + 9.0
            } else { 0.0 };
            sizes.push(size);
            if eligible { active.push(Center { x: cx, z: cz, size }); }
        }}
        Ok(CandidateField { bounds: [min_x, min_z, max_x, max_z], origin, width, height, sizes, active })
    }
}
impl CandidateField {
    pub fn origin_chunk(&self) -> [i32; 2] { self.origin }
    pub fn active_centers(&self) -> usize { self.active.len() }
    pub fn width(&self) -> usize { self.width }
    pub fn height(&self) -> usize { self.height }
    /// Zero denotes a rejected candidate; valid sizes are in [9,22).
    pub fn sizes(&self) -> &[f32] { &self.sizes }
    fn contains(&self, x: i32, z: i32) -> bool {
        (self.bounds[0]..=self.bounds[2]).contains(&x) && (self.bounds[1]..=self.bounds[3]).contains(&z)
    }
    pub fn outer(&self, x: i32, z: i32) -> Option<f32> {
        if !self.contains(x, z) { return None; }
        let sx = x / 8; let sz = z / 8;
        let cx = sx / 2; let cz = sz / 2;
        let sub_x = sx % 2; let sub_z = sz % 2;
        let mut height = -100.0f32;
        // Rejected centers can never contribute. Max over the remaining finite
        // values is order-independent; each contribution keeps vanilla arithmetic.
        for center in &self.active {
            let dx = center.x - cx; let dz = center.z - cz;
            if dx.abs() > 12 || dz.abs() > 12 { continue; }
            let px = (sub_x - dx * 2) as f32; let pz = (sub_z - dz * 2) as f32;
            let value = (100.0 - (px * px + pz * pz).sqrt() * center.size).clamp(-100.0, 80.0);
            height = height.max(value);
        }
        Some((height - 8.0) / 128.0)
    }
    pub fn sample(&self, x: i32, z: i32) -> Option<f32> {
        let outer = self.outer(x, z)?;
        let fx = x as f32; let fz = z as f32;
        let distance = (fx * fx + fz * fz).sqrt();
        let main = ((100.0 - distance).clamp(-100.0, 80.0) - 8.0) * 0.0078125;
        Some(main.max(outer))
    }
    pub fn biome(&self, x: i32, z: i32) -> Option<Biome> {
        if !self.contains(x, z) { return None; }
        let cx = x.div_euclid(16); let cz = z.div_euclid(16);
        if i64::from(cx) * i64::from(cx) + i64::from(cz) * i64::from(cz) <= 4096 { return Some(Biome::Center); }
        let value = self.sample(cx * 16 + 8, cz * 16 + 8)?;
        Some(if value > 0.25 { Biome::Highlands } else if value >= -0.0625 { Biome::Midlands }
            else if value < -0.21875 { Biome::SmallIslands } else { Biome::Barrens })
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    #[test]
    fn shared_candidates_keep_signed_arithmetic_and_all_reference_bits() {
        let reference: serde_json::Value = serde_json::from_str(include_str!("../../tests/fixtures/end-islands-26.3.json")).unwrap();
        for group in reference["seeds"].as_array().unwrap() {
            let noise = IslandNoise::new(group["seed"].as_i64().unwrap() as u64);
            for p in group["samples"].as_array().unwrap() {
                let x = p["xz"][0].as_i64().unwrap() as i32;
                let z = p["xz"][1].as_i64().unwrap() as i32;
                let field = noise.candidate_field(x - 8, z - 8, x + 8, z + 8).unwrap();
                assert_eq!(field.sample(x, z).unwrap().to_bits(), noise.sample(x, z).to_bits());
                assert_eq!(field.outer(x, z).unwrap().to_bits(), noise.outer(x, z).to_bits());
                // The cell center must also be inside the table for a biome probe.
                let field = noise.candidate_field(x.div_euclid(16)*16, z.div_euclid(16)*16,
                    x.div_euclid(16)*16+15, z.div_euclid(16)*16+15).unwrap();
                assert_eq!(field.biome(x, z), Some(noise.biome(x, z)));
            }
        }
    }
    #[test]
    fn shared_rectangle_tests_centers_once_and_refuses_uncovered_samples() {
        let noise = IslandNoise::new(42);
        let field = noise.candidate_field(-16, -16, 512, 512).unwrap();
        assert_eq!(field.sizes().len(), 58*58);
        for z in (-16..=512).step_by(8) { for x in (-16..=512).step_by(8) {
            assert_eq!(field.sample(x, z).unwrap().to_bits(), noise.sample(x, z).to_bits(), "{x},{z}");
        }}
        assert!(field.sample(-17, 0).is_none());
        assert!(field.sample(0, 513).is_none());
        assert!(noise.candidate_field(1, 0, 0, 1).is_err());
    }
}
