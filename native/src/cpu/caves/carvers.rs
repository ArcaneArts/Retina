//! CPU numerical kernels for the loaded carver recipes. Parameters are the same
//! Carver values encoded for the GPU, with wrapping seed/hash arithmetic.
use super::super::noise::{mix, mix_hash, noise3};
use crate::geology::Carver;
fn hash_cell(cell: [i32; 2], seed: u32, salt: u32) -> u32 {
    mix_hash(
        (cell[0] as u32).wrapping_mul(0x9e3779b9)
            ^ (cell[1] as u32).wrapping_mul(0x85ebca6b)
            ^ seed
            ^ salt,
    )
}
pub(super) fn ravine(point: [f32; 3], seed: u32, c: &Carver) -> f32 {
    let cell = [
        (point[0] / 128.0).floor() as i32,
        (point[2] / 128.0).floor() as i32,
    ];
    let probability = 1.0 - (1.0 - c.probability.clamp(0.0, 1.0)).powf(64.0);
    let mut distance = 1000.0f32;
    for dz in -1..=1 {
        for dx in -1..=1 {
            let at = [cell[0] + dx, cell[1] + dz];
            let h = hash_cell(at, seed, 17863);
            if (h & 65535) as f32 / 65535.0 >= probability {
                continue;
            }
            let random = [
                ((h >> 16) & 255) as f32 / 255.0,
                ((mix_hash(h) >> 8) & 255) as f32 / 255.0,
                (mix_hash(h.wrapping_add(1)) & 255) as f32 / 255.0,
            ];
            let thickness = if c.thickness_range[1] > 0.0 {
                mix(c.thickness_range[0], c.thickness_range[1], random[0])
            } else {
                c.thickness
            };
            let horizontal = if c.horizontal_range[1] > 0.0 {
                mix(c.horizontal_range[0], c.horizontal_range[1], random[1])
            } else {
                c.horizontal
            };
            let factor = if c.distance_range[1] > 0.0 {
                mix(c.distance_range[0], c.distance_range[1], random[2])
            } else {
                c.room
            };
            let half_length = (112.0 * factor * 0.5).max(1.0);
            let center = [
                (at[0] as f32 + 0.25 + random[1] * 0.5) * 128.0,
                (at[1] as f32 + 0.25 + random[2] * 0.5) * 128.0,
            ];
            let height_random = (mix_hash(h.wrapping_add(3)) & 65535) as f32 / 65535.0;
            let mut center_y = mix(c.height.min as f32, c.height.max as f32, height_random);
            if c.height.triangle {
                let span = c.height.max as f32 - c.height.min as f32;
                let slope = (span - (c.height.plateau as f32).clamp(0.0, span)) * 0.5;
                center_y = c.height.min as f32
                    + height_random * slope
                    + (mix_hash(h.wrapping_add(4)) & 65535) as f32 / 65535.0 * (span - slope);
            }
            let y_scale = if c.vertical_range[1] > 0.0 {
                mix(c.vertical_range[0], c.vertical_range[1], random[2])
            } else {
                c.vertical
            };
            let has_factor = c.vertical_default > 0.0 || c.vertical_center > 0.0;
            let maximum_factor = if has_factor {
                (c.vertical_default + c.vertical_center).max(0.2)
            } else {
                1.0
            };
            let maximum_y = (1.5 + thickness) * y_scale * maximum_factor * 1.25 + 12.0;
            if (point[1] - center_y).abs() > maximum_y {
                continue;
            }
            let angle = ((h >> 8) & 65535) as f32 / 65535.0 * 6.2831853;
            let delta = [point[0] - center[0], point[2] - center[1]];
            let along = delta[0] * angle.cos() + delta[1] * angle.sin();
            let side = delta[0] * -angle.sin() + delta[1] * angle.cos();
            let t = along / half_length;
            if t.abs() >= 1.0
                || side.abs() > half_length * 0.18 + (1.5 + thickness) * horizontal * 1.5 + 6.0
            {
                continue;
            }
            let phase = (h & 31) as f32;
            let bend = (t * 2.2 + phase).sin() * half_length * 0.10
                + noise3([along * 0.035, phase, 0.0], seed.wrapping_add(19391)) * 4.0;
            let rotation = mix(c.rotation_range[0], c.rotation_range[1], height_random);
            let height_bend =
                (t * 2.7 + phase).sin() * 3.0 + rotation.sin() * (t * 1.8).sin() * 8.0;
            let radius = 1.5 + thickness * (1.0 - t * t).max(0.0).sqrt();
            let edge = noise3(
                [
                    along * 0.06,
                    point[1] / (4.0 * c.width_smoothness.max(1.0)),
                    phase,
                ],
                seed.wrapping_add(24107),
            );
            let width = (radius * horizontal * (0.82 + edge * 0.18)).max(0.5);
            let vertical_factor = if has_factor {
                (c.vertical_default + c.vertical_center * (1.0 - t.abs())).max(0.2)
            } else {
                1.0
            };
            let vertical_radius =
                (radius * y_scale * vertical_factor * (0.85 + edge * 0.10)).max(2.0);
            let wall = noise3(point.map(|v| v * 0.045), seed.wrapping_add(31337)) * 0.6;
            let horizontal_distance = (side - bend + wall) / width;
            let vertical_distance = (point[1] - center_y - height_bend) / vertical_radius;
            distance = distance.min(
                horizontal_distance * horizontal_distance
                    + vertical_distance * vertical_distance
                    + t * t,
            );
        }
    }
    distance
}
pub(super) fn tunnel(point: [f32; 3], seed: u32, c: &Carver) -> f32 {
    if c.kind != 0 || c.probability <= 0.0 || c.count <= 0.0 || c.start_vertical <= 0.0 {
        return 1000.0;
    }
    let margin = (c.thickness * c.vertical * 4.0).max(4.0);
    let fade = ((point[1] - c.height.min as f32 + margin)
        .min(c.height.max as f32 + margin - point[1])
        * 0.125)
        .clamp(0.0, 1.0);
    let strength = (c.probability * c.count).clamp(0.0, 2.0);
    let width = (0.042 + c.thickness * 0.028) * c.horizontal * strength.sqrt();
    let floor = ((c.floor + 1.0) * 0.03).clamp(0.0, 0.06);
    let p = [
        point[0] * 0.025,
        point[1] * 0.025 / c.start_vertical,
        point[2] * 0.025,
    ];
    let a = noise3(p, seed.wrapping_add(7919));
    let b = noise3(p, seed.wrapping_add(15838));
    a.abs().max(b.abs() * c.vertical) - width * fade + floor
}
pub(super) fn room(point: [f32; 3], seed: u32, c: &Carver) -> f32 {
    if c.kind != 0 || c.probability <= 0.0 || c.count <= 0.0 || c.room <= 0.0 {
        return 1000.0;
    }
    let size = (17.0 * c.horizontal).max(32.0);
    let cell = [
        (point[0] / size).floor() as i32,
        (point[2] / size).floor() as i32,
    ];
    let probability =
        1.0 - (1.0 - (c.probability * c.count * 0.25).clamp(0.0, 1.0)).powf(size * size / 256.0);
    let mut distance = 1000.0f32;
    for dz in -1..=1 {
        for dx in -1..=1 {
            let at = [cell[0] + dx, cell[1] + dz];
            let h = hash_cell(at, seed, 61843);
            if (h & 65535) as f32 / 65536.0 >= probability {
                continue;
            }
            let random = [1, 2, 3].map(|i| (mix_hash(h.wrapping_add(i)) & 65535) as f32 / 65535.0);
            let center = [
                (at[0] as f32 + random[0]) * size,
                mix(c.height.min as f32, c.height.max as f32, random[1]),
                (at[1] as f32 + random[2]) * size,
            ];
            let radius = 2.5 + (mix_hash(h.wrapping_add(4)) & 65535) as f32 / 65535.0 * 6.0;
            let scale = [
                radius * c.horizontal,
                radius * c.room,
                radius * c.horizontal,
            ];
            let p = std::array::from_fn::<_, 3, _>(|i| (point[i] - center[i]) / scale[i]);
            distance =
                distance.min((p.iter().map(|v| v * v).sum::<f32>() - 1.0).max(c.floor - p[1]));
        }
    }
    distance
}
