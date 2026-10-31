//! Native evaluation of loaded placement-noise permutations and count rules.
use super::{Noise, Query};

impl Noise {
    pub(crate) fn sample(&self, point: [f32; 2]) -> f32 {
        let skew = (point[0] + point[1]) * 0.3660254037844386_f32;
        let cell = point.map(|v| (v + skew).floor() as i32);
        let unskew = cell[0].wrapping_add(cell[1]) as f32 * 0.21132486540518713_f32;
        let a = [
            point[0] - (cell[0] as f32 - unskew),
            point[1] - (cell[1] as f32 - unskew),
        ];
        let step = if a[0] > a[1] { [1, 0] } else { [0, 1] };
        let b = [
            a[0] - step[0] as f32 + 0.21132486540518713_f32,
            a[1] - step[1] as f32 + 0.21132486540518713_f32,
        ];
        let c = a.map(|v| v - 1.0 + 0.42264973081037426_f32);
        let permute = |i: i32| self.permutation[(i as u32 & 255) as usize];
        let gradient = |x: i32, z: i32| permute(x.wrapping_add(permute(z) as i32)) % 12;
        70.0 * (corner(gradient(cell[0], cell[1]), a)
            + corner(
                gradient(cell[0].wrapping_add(step[0]), cell[1].wrapping_add(step[1])),
                b,
            )
            + corner(
                gradient(cell[0].wrapping_add(1), cell[1].wrapping_add(1)),
                c,
            ))
    }
    pub(crate) fn count(&self, query: &Query) -> i32 {
        let w = query.rule.words;
        let divisor = f32::from_bits(w[4]);
        self.count_at(
            query.rule,
            [query.x as f32 / divisor, query.z as f32 / divisor],
        )
    }
    pub(crate) fn count_at(&self, rule: super::Rule, point: [f32; 2]) -> i32 {
        let w = rule.words;
        let noise = self.sample(point);
        let count = if w[0] == 0 {
            if noise < f32::from_bits(w[1]) {
                w[2] as i32
            } else {
                w[3] as i32
            }
        } else {
            ((noise + f32::from_bits(w[5])) * (w[6] as i32) as f32).ceil() as i32
        };
        count.max(0)
    }
}
fn corner(gradient: u32, p: [f32; 2]) -> f32 {
    const GRADIENTS: [[f32; 2]; 12] = [
        [1., 1.],
        [-1., 1.],
        [1., -1.],
        [-1., -1.],
        [1., 0.],
        [-1., 0.],
        [1., 0.],
        [-1., 0.],
        [0., 1.],
        [0., -1.],
        [0., 1.],
        [0., -1.],
    ];
    let g = GRADIENTS[gradient as usize];
    let t = (0.5 - (p[0] * p[0] + p[1] * p[1])).max(0.0);
    let square = t * t;
    square * square * (g[0] * p[0] + g[1] * p[1])
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::decoration::counts::Rule;
    #[test]
    fn rules_use_loaded_noise_and_nonnegative_counts() {
        let noise = Noise {
            permutation: (0..256).collect(),
        };
        assert_eq!(noise.sample([0.0; 2]), 0.0);
        let q = |rule| Query { x: 0, z: 0, rule };
        assert_eq!(noise.count(&q(Rule::threshold(0.1, 5, 17))), 5);
        assert_eq!(noise.count(&q(Rule::threshold(0.0, 5, 17))), 17);
        assert_eq!(noise.count(&q(Rule::based(10, 200.0, 0.3))), 3);
        assert_eq!(noise.count(&q(Rule::based(10, 200.0, -0.3))), 0);
        assert_ne!(
            noise.sample([-31.25, 15.75]),
            Noise {
                permutation: (0..256).rev().collect()
            }
            .sample([-31.25, 15.75])
        );
    }
}
