//! Minecraft 26.3 WorldgenRandom backed by Xoroshiro128++.
//! Keep this separate from existing Overworld/Nether decoration streams.
#[derive(Clone)]
pub struct DecorationRandom { low: u64, high: u64 }
fn mix(mut value: u64) -> u64 {
    value = (value ^ (value >> 30)).wrapping_mul(0xbf58476d1ce4e5b9);
    value = (value ^ (value >> 27)).wrapping_mul(0x94d049bb133111eb);
    value ^ (value >> 31)
}
impl DecorationRandom {
    pub fn new(seed: u64) -> Self {
        let low = seed ^ 0x6a09e667f3bcc909;
        let mut result = Self { low: mix(low), high: mix(low.wrapping_add(0x9e3779b97f4a7c15)) };
        if result.low | result.high == 0 { result.low = 0x9e3779b97f4a7c15; result.high = 0x6a09e667f3bcc909; }
        result
    }
    fn next_bits(&mut self, bits: u32) -> u32 {
        let low = self.low; let high = self.high;
        let result = low.wrapping_add(high).rotate_left(17).wrapping_add(low);
        let high = high ^ low;
        self.low = low.rotate_left(49) ^ high ^ high.wrapping_shl(21);
        self.high = high.rotate_left(28);
        (result >> (64 - bits)) as u32
    }
    pub fn long(&mut self) -> u64 {
        let high = self.next_bits(32) as i32 as i64;
        let low = self.next_bits(32) as i32 as i64;
        ((high as u64) << 32).wrapping_add(low as u64)
    }
    pub fn below(&mut self, bound: i32) -> i32 {
        assert!(bound > 0);
        if bound & (bound - 1) == 0 { return ((i64::from(bound) * i64::from(self.next_bits(31))) >> 31) as i32; }
        loop {
            let sample = self.next_bits(31) as i32;
            let modulo = sample % bound;
            if sample.wrapping_sub(modulo).wrapping_add(bound - 1) >= 0 { return modulo; }
        }
    }
    pub fn float(&mut self) -> f32 { self.next_bits(24) as f32 * (1.0 / 16777216.0) }
    pub fn double(&mut self) -> f64 {
        let combined = ((u64::from(self.next_bits(26))) << 27) + u64::from(self.next_bits(27));
        ((combined as f32) * (1.0f32 / 9007199254740992.0)) as f64
    }
    pub fn for_feature(seed: u64, block_x: i32, block_z: i32, index: u32, step: u32) -> Self {
        let mut random = Self::new(seed);
        let xs = random.long() | 1; let zs = random.long() | 1;
        let decoration = (block_x as i64 as u64).wrapping_mul(xs)
            .wrapping_add((block_z as i64 as u64).wrapping_mul(zs)) ^ seed;
        Self::new(decoration.wrapping_add(u64::from(index)).wrapping_add(u64::from(step) * 10000))
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    #[test]
    fn world_seeded_wrapper_draws_and_biome_zoom_match_minecraft() {
        let source: serde_json::Value=serde_json::from_str(include_str!("../../tests/fixtures/end-decoration-random-26.3.json")).unwrap();
        for row in source["streams"].as_array().unwrap() {
            let seed=row["seed"].as_i64().unwrap() as u64;
            let mut random=DecorationRandom::for_feature(seed,row["origin"][0].as_i64().unwrap() as i32,
                row["origin"][1].as_i64().unwrap() as i32,row["feature"][0].as_u64().unwrap() as u32,row["feature"][1].as_u64().unwrap() as u32);
            for draw in row["integers"].as_array().unwrap() {
                assert_eq!(random.below(draw["bound"].as_i64().unwrap() as i32),draw["value"].as_i64().unwrap() as i32,"seed={seed}");
            }
            for draw in row["tail"].as_array().unwrap() {
                assert_eq!(random.float().to_bits(),draw["float_bits"].as_i64().unwrap() as u32);
                assert_eq!(random.double().to_bits(),draw["double_bits"].as_i64().unwrap() as u64);
                assert_eq!(random.long(),draw["long"].as_i64().unwrap() as u64);
            }
        }
        for row in source["biome_zoom"].as_array().unwrap() {
            let point=std::array::from_fn(|i|row["position"][i].as_i64().unwrap() as i32);
            let expected=std::array::from_fn(|i|row["quart"][i].as_i64().unwrap() as i32);
            assert_eq!(crate::end::BiomeZoom::new(row["seed"].as_i64().unwrap() as u64).quart(point),expected);
        }
    }
}
