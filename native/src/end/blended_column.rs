//! Exact bulk volume columns. Share horizontal work and gradient lookups while
//! retaining each point's source multiply, summation, and interpolation order.
use super::*;

impl Layer {
    fn volume_column(
        &self,
        x: i32,
        z: i32,
        xz_scale: f64,
        y_scale: f64,
        mut flags: u64,
    ) -> [f32; 33] {
        let phase_x = wrap(x as f64 * (xz_scale * self.frequency)) + self.offsets[0];
        let phase_z = wrap(z as f64 * (xz_scale * self.frequency)) + self.offsets[2];
        let cell_x = phase_x.floor() as i32;
        let cell_z = phase_z.floor() as i32;
        let local_x = (phase_x - cell_x as f64) as f32;
        let local_z = (phase_z - cell_z as f64) as f32;
        let ax = smoothstep(local_x);
        let az = smoothstep(local_z);
        let perm_x = [self.permute(cell_x), self.permute(cell_x + 1)];
        let factor = y_scale * self.frequency;
        let mut cached_y = None;
        let mut horizontal = [0.0; 8];
        let mut gradient_y = [0.0; 8];
        let mut result = [0.0; 33];
        while flags != 0 {
            let cy = flags.trailing_zeros() as usize;
            flags &= flags - 1;
            let point_y = (cy as i32 * 4) as f64 * factor;
            let phase_y = wrap(point_y) + self.offsets[1];
            let cell_y = phase_y.floor() as i32;
            let relative_y = phase_y - cell_y as f64;
            let limit = if point_y >= 0.0 && point_y < relative_y {
                point_y
            } else {
                relative_y
            };
            let fudge = (limit / self.fudge_y + 1.0e-7f32 as f64).floor() * self.fudge_y;
            let local_y = (relative_y - fudge) as f32;
            if cached_y != Some(cell_y) {
                for corner in 0..8 {
                    let dx = corner & 1;
                    let dy = (corner >> 1) & 1;
                    let dz = (corner >> 2) & 1;
                    let xy = self.permute(perm_x[dx] + cell_y + dy as i32);
                    let gradient = GRADIENTS[(self.permute(xy + cell_z + dz as i32) & 15) as usize];
                    horizontal[corner] =
                        gradient[0] * (local_x - dx as f32) + gradient[2] * (local_z - dz as f32);
                    gradient_y[corner] = gradient[1];
                }
                cached_y = Some(cell_y);
            }
            let dots: [f32; 8] = std::array::from_fn(|corner| {
                horizontal[corner] + gradient_y[corner] * (local_y - ((corner >> 1) & 1) as f32)
            });
            let ay = smoothstep(relative_y as f32);
            result[cy] = self.amplitude
                * lerp(
                    az,
                    lerp(ay, lerp(ax, dots[0], dots[1]), lerp(ax, dots[2], dots[3])),
                    lerp(ay, lerp(ax, dots[4], dots[5]), lerp(ax, dots[6], dots[7])),
                );
        }
        result
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    #[test]
    fn batched_volume_values_and_bounds_retain_source_order_and_wrapping() {
        for parameters in [
            Parameters::default(),
            Parameters {
                xz_scale: 0.001,
                y_scale: 1000.0,
                xz_factor: 0.001,
                y_factor: 1000.0,
                smear_scale_multiplier: 1.0,
            },
            Parameters {
                xz_scale: 1000.0,
                y_scale: 0.001,
                xz_factor: 1000.0,
                y_factor: 0.001,
                smear_scale_multiplier: 8.0,
            },
        ] {
            for seed in [0, 42, u64::MAX] {
                let noise = BlendedNoise::new(seed, parameters).unwrap();
                for (x, z) in [
                    (0, 0),
                    (-9, 17),
                    (16777217, -16777211),
                    (i32::MIN + 8, i32::MAX - 8),
                ] {
                    for flags in [(1u64 << 33) - 1, 0x155555555, 1u64 << 32, 0] {
                        let actual = noise.volume_column(x, z, flags);
                        let bounds = noise.volume_column_bounds(x, z, flags);
                        let mut remaining = flags;
                        while remaining != 0 {
                            let cy = remaining.trailing_zeros() as usize;
                            remaining &= remaining - 1;
                            assert_eq!(
                                actual[cy].to_bits(),
                                noise.volume_point(x, cy as i32 * 4, z).to_bits(),
                                "seed={seed} at {x},{cy},{z}"
                            );
                            assert_eq!(
                                bounds[cy].map(f32::to_bits),
                                noise.volume_bounds(x, cy as i32 * 4, z).map(f32::to_bits)
                            );
                        }
                    }
                }
            }
        }
    }
}

impl BlendedNoise {
    fn stack_column(
        layers: &[Layer],
        x: i32,
        z: i32,
        xz_scale: f64,
        y_scale: f64,
        flags: u64,
    ) -> [f32; 33] {
        if flags == 0 {
            return [0.0; 33];
        }
        let mut result = [0.0; 33];
        for layer in layers {
            let values = layer.volume_column(x, z, xz_scale, y_scale, flags);
            let mut remaining = flags;
            while remaining != 0 {
                let cy = remaining.trailing_zeros() as usize;
                remaining &= remaining - 1;
                result[cy] += values[cy];
            }
        }
        result
    }

    pub(in crate::end) fn volume_column(&self, x: i32, z: i32, flags: u64) -> [f32; 33] {
        if flags == 0 {
            return [0.0; 33];
        }
        let p = self.parameters;
        let xz = 684.412 * p.xz_scale;
        let y = 684.412 * p.y_scale;
        let alpha = Self::stack_column(&self.main, x, z, xz / p.xz_factor, y / p.y_factor, flags);
        let mut lower_flags = 0;
        let mut upper_flags = 0;
        let mut remaining = flags;
        while remaining != 0 {
            let cy = remaining.trailing_zeros() as usize;
            remaining &= remaining - 1;
            let a = (alpha[cy] + 0.5).clamp(0.0, 1.0);
            if a != 1.0 {
                lower_flags |= 1u64 << cy;
            }
            if a != 0.0 {
                upper_flags |= 1u64 << cy;
            }
        }
        let lower = Self::stack_column(&self.lower, x, z, xz, y, lower_flags);
        let upper = Self::stack_column(&self.upper, x, z, xz, y, upper_flags);
        let mut result = [0.0; 33];
        let mut remaining = flags;
        while remaining != 0 {
            let cy = remaining.trailing_zeros() as usize;
            remaining &= remaining - 1;
            let a = (alpha[cy] + 0.5).clamp(0.0, 1.0);
            result[cy] = if a == 0.0 {
                lower[cy]
            } else if a == 1.0 {
                upper[cy]
            } else {
                lerp(a, lower[cy], upper[cy])
            };
        }
        result
    }

    pub(in crate::end) fn volume_column_bounds(
        &self,
        x: i32,
        z: i32,
        flags: u64,
    ) -> [[f32; 2]; 33] {
        let p = self.parameters;
        let xz = 684.412 * p.xz_scale;
        let y = 684.412 * p.y_scale;
        let stack = |layers: &[Layer]| {
            let split = layers.len() - 4;
            let mut known = [0.0f64; 33];
            for layer in &layers[split..] {
                let values = layer.volume_column(x, z, xz, y, flags);
                let mut remaining = flags;
                while remaining != 0 {
                    let cy = remaining.trailing_zeros() as usize;
                    remaining &= remaining - 1;
                    known[cy] += f64::from(values[cy]);
                }
            }
            let unknown: f64 = layers[..split]
                .iter()
                .map(|l| f64::from(l.amplitude.abs()) * LAYER_BOUND)
                .sum();
            (known, unknown)
        };
        let (lower, lower_unknown) = stack(&self.lower);
        let (upper, upper_unknown) = stack(&self.upper);
        let mut result = [[0.0; 2]; 33];
        let mut remaining = flags;
        while remaining != 0 {
            let cy = remaining.trailing_zeros() as usize;
            remaining &= remaining - 1;
            result[cy] = [
                ((lower[cy] - lower_unknown).min(upper[cy] - upper_unknown) - 0.001) as f32,
                ((lower[cy] + lower_unknown).max(upper[cy] + upper_unknown) + 0.001) as f32,
            ];
            result[cy][0] = result[cy][0].next_down();
            result[cy][1] = result[cy][1].next_up();
        }
        result
    }
}
