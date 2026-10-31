//! Registered Perlin stacks use native f64 coordinates, f32 gradients/weights.
//! This retains far-coordinate fractions without GPU integer-limb emulation.
use super::{Layer, Program};
use crate::cpu::noise::trilinear;

impl Program {
    pub(crate) fn sample(&self, point: [i32; 3]) -> f32 {
        let point = point.map(|v| {
            self.coordinate_scale
                .map_or(v as f64, |scale| (v as f32 * scale) as f64)
        });
        self.layers
            .iter()
            .map(|layer| layer.amplitude * sample_layer(layer, point))
            .sum()
    }
}
fn sample_layer(layer: &Layer, point: [f64; 3]) -> f32 {
    let scaled = std::array::from_fn::<_, 3, _>(|i| point[i] * layer.frequency + layer.offsets[i]);
    let cell = scaled.map(|v| v.floor().rem_euclid(256.0) as u32);
    let p = scaled.map(|v| (v - v.floor()) as f32);
    let permute = |v: u32| layer.permutation[(v & 255) as usize];
    const GRADIENTS: [[f32; 3]; 16] = [
        [1., 1., 0.],
        [-1., 1., 0.],
        [1., -1., 0.],
        [-1., -1., 0.],
        [1., 0., 1.],
        [-1., 0., 1.],
        [1., 0., -1.],
        [-1., 0., -1.],
        [0., 1., 1.],
        [0., -1., 1.],
        [0., 1., -1.],
        [0., -1., -1.],
        [1., 1., 0.],
        [0., -1., 1.],
        [-1., 1., 0.],
        [0., -1., -1.],
    ];
    let values = std::array::from_fn(|index| {
        let step = [
            index as u32 & 1,
            (index as u32 >> 1) & 1,
            (index as u32 >> 2) & 1,
        ];
        let hash =
            permute(permute(permute(cell[0] + step[0]) + cell[1] + step[1]) + cell[2] + step[2]);
        let g = GRADIENTS[(hash & 15) as usize];
        (g[0] * (p[0] - step[0] as f32) + g[1] * (p[1] - step[1] as f32))
            + g[2] * (p[2] - step[2] as f32)
    });
    trilinear(values, p.map(|v| v * v * v * (v * (v * 6.0 - 15.0) + 10.0)))
}

#[cfg(test)]
mod tests {
    use super::*;
    #[test]
    fn loaded_layers_are_periodic_and_preserve_far_fractional_phase() {
        let layer = Layer {
            frequency: 0.125,
            amplitude: 0.5,
            offsets: [0.0; 3],
            permutation: (0..256).collect(),
        };
        let program = Program {
            layers: vec![layer],
            coordinate_scale: None,
        };
        assert_eq!(program.sample([0, 0, 0]), 0.0);
        let a = program.sample([-13, 17, 29]);
        let b = program.sample([-13 + 2048, 17, 29]);
        assert_eq!(a, b);
        assert_eq!(a, program.sample([-13 + 2048 * 14000, 17, 29]));
        assert_ne!(a, program.sample([-12, 17, 29]));
        let slow = Program {
            coordinate_scale: Some(0.013),
            ..program.clone()
        };
        assert!(slow.sample([30_000_000, -64, -30_000_000]).is_finite());
        assert_ne!(slow.sample([-13, 17, 29]), a);
    }
}
