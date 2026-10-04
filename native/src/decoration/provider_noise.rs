//! Resident, registry-initialized Perlin stacks for spatial block providers.
//! Only integer positions and program IDs cross the sparse query boundary.
use serde::Deserialize;

#[derive(Clone, Deserialize)]
pub struct Program {
    pub layers: Vec<Layer>,
}
#[derive(Clone, Deserialize)]
pub struct Layer {
    pub frequency: f64,
    pub amplitude: f32,
    pub offsets: [f64; 3],
    pub permutation: Vec<u32>,
}
impl Program {
    pub(crate) fn validate(&self) -> Result<(), String> {
        if self.layers.len() > 64 {
            return Err("provider noise has more than 64 Perlin layers".into());
        }
        for layer in &self.layers {
            let mut sorted = layer.permutation.clone();
            sorted.sort_unstable();
            if !layer.frequency.is_finite()
                || !layer.amplitude.is_finite()
                || layer.offsets.iter().any(|v| !v.is_finite())
                || sorted != (0..256).collect::<Vec<_>>()
            {
                return Err("invalid provider noise layer or permutation".into());
            }
        }
        Ok(())
    }
}

// Perlin is periodic over 256 lattice cells. Keep 40 fractional bits before
// multiplying by a signed integer coordinate, rather than first rounding a
// far block position to f32. The shader performs the same product in u32 limbs.
fn fixed_phase(value: f64) -> [u32; 2] {
    let phase = (value.rem_euclid(256.0) * (1u64 << 40) as f64).round() as u64 & ((1u64 << 48) - 1);
    [phase as u32, (phase >> 32) as u32]
}
pub(crate) fn encode(programs: &[Program]) -> Vec<u32> {
    let mut words = vec![0; 1 + programs.len() * 2];
    words[0] = programs.len() as u32;
    for (i, program) in programs.iter().enumerate() {
        words[1 + i * 2] = words.len() as u32;
        words[2 + i * 2] = program.layers.len() as u32;
        for layer in &program.layers {
            words.extend_from_slice(&fixed_phase(layer.frequency));
            words.extend_from_slice(&[layer.amplitude.to_bits(), 0]);
            for offset in layer.offsets {
                words.extend_from_slice(&fixed_phase(offset));
            }
            words.extend_from_slice(&layer.permutation);
            words.extend_from_slice(&[0, 0]);
        }
    }
    words
}

#[cfg(test)]
mod tests {
    use super::*;
    #[test]
    fn signed_phase_products_retain_far_coordinate_fractions() {
        for frequency in [0.0001, 0.008 * 1.0181268882175227, 0.0625, 1.0, 257.125] {
            let [lo, hi] = fixed_phase(frequency);
            for point in [-30_000_000i32, -16_777_217, -257, -1, 0, 1, 257, 30_000_000] {
                let coefficient = lo as u64 | (hi as u64) << 32;
                let expected =
                    ((point as i64 as u64).wrapping_mul(coefficient)) & ((1u64 << 48) - 1);
                let a = point as u32;
                let a0 = a & 65535;
                let a1 = a >> 16;
                let b0 = lo & 65535;
                let b1 = lo >> 16;
                let t0 = a0 * b0;
                let t1 = a1 * b0;
                let t2 = a0 * b1;
                let carry = ((t0 >> 16) + (t1 & 65535) + (t2 & 65535)) >> 16;
                let low = t0.wrapping_add(t1.wrapping_add(t2) << 16);
                let high = (a1 * b1)
                    .wrapping_add(t1 >> 16)
                    .wrapping_add(t2 >> 16)
                    .wrapping_add(carry)
                    .wrapping_add(a.wrapping_mul(hi))
                    .wrapping_sub(if point < 0 { lo } else { 0 })
                    & 65535;
                assert_eq!(low as u64 | (high as u64) << 32, expected);
                let phase = expected as f64 / (1u64 << 40) as f64;
                let reference = (point as f64 * frequency).rem_euclid(256.0);
                let error = (phase - reference).abs();
                assert!(error.min(256.0 - error) < 0.00002);
            }
        }
    }
}
