//! CPU counterparts of simplex.wgsl and noise3.wgsl. Hashes use explicit wrapping
//! arithmetic, including negative coordinates and both halves of the world seed.
#[inline]
pub fn mix_hash(mut value: u32) -> u32 {
    value = (value ^ (value >> 16)).wrapping_mul(0x7feb352d);
    value = (value ^ (value >> 15)).wrapping_mul(0x846ca68b);
    value ^ (value >> 16)
}
#[inline]
pub fn cell_hash(cell: [i32; 2], low: u32, high: u32) -> u32 {
    mix_hash(
        (cell[0] as u32).wrapping_mul(0x9e3779b9)
            ^ (cell[1] as u32).wrapping_mul(0x85ebca6b)
            ^ low
            ^ mix_hash(high),
    )
}
#[inline]
pub fn mix(a: f32, b: f32, t: f32) -> f32 {
    a + (b - a) * t
}
#[inline]
pub fn smoothstep(low: f32, high: f32, value: f32) -> f32 {
    let t = ((value - low) / (high - low)).clamp(0.0, 1.0);
    t * t * (3.0 - 2.0 * t)
}
fn corner(cell: [i32; 3], offset: [f32; 3], seed: u32) -> f32 {
    let h = mix_hash(
        (cell[0] as u32).wrapping_mul(0x9e3779b9)
            ^ (cell[1] as u32).wrapping_mul(0x85ebca6b)
            ^ (cell[2] as u32).wrapping_mul(0xc2b2ae35)
            ^ seed,
    ) & 15;
    let a = if h < 8 { offset[0] } else { offset[1] };
    let b = if h < 4 {
        offset[1]
    } else if h == 12 || h == 14 {
        offset[0]
    } else {
        offset[2]
    };
    (if h & 1 != 0 { -a } else { a }) + (if h & 2 != 0 { -b } else { b })
}
pub fn noise3(point: [f32; 3], seed: u32) -> f32 {
    let cell = point.map(|v| v.floor() as i32);
    let d = std::array::from_fn::<_, 3, _>(|i| point[i] - cell[i] as f32);
    let t = d.map(|v| v * v * v * (v * (v * 6.0 - 15.0) + 10.0));
    let mut corners = [0.0; 8];
    for (i, value) in corners.iter_mut().enumerate() {
        let step = [(i & 1) as i32, ((i >> 1) & 1) as i32, ((i >> 2) & 1) as i32];
        *value = corner(
            std::array::from_fn(|j| cell[j].wrapping_add(step[j])),
            std::array::from_fn(|j| d[j] - step[j] as f32),
            seed,
        );
    }
    trilinear(corners, t) * 1.6
}
pub fn trilinear(c: [f32; 8], t: [f32; 3]) -> f32 {
    mix(
        mix(mix(c[0], c[1], t[0]), mix(c[2], c[3], t[0]), t[1]),
        mix(mix(c[4], c[5], t[0]), mix(c[6], c[7], t[0]), t[1]),
        t[2],
    )
}
fn contribution(cell: [i32; 2], d: [f32; 2], low: u32, high: u32) -> f32 {
    let t = (0.5 - d[0] * d[0] - d[1] * d[1]).max(0.0);
    let t2 = t * t;
    let gradient = match cell_hash(cell, low, high) & 7 {
        0 => [1.0, 1.0],
        1 => [-1.0, 1.0],
        2 => [1.0, -1.0],
        3 => [-1.0, -1.0],
        4 => [1.0, 0.0],
        5 => [-1.0, 0.0],
        6 => [0.0, 1.0],
        _ => [0.0, -1.0],
    };
    t2 * t2 * (gradient[0] * d[0] + gradient[1] * d[1])
}
pub fn simplex(point: [f32; 2], low: u32, high: u32) -> f32 {
    let skew = (point[0] + point[1]) * 0.3660254037844386_f32;
    let cell = point.map(|v| (v + skew).floor() as i32);
    let unskew = cell[0].wrapping_add(cell[1]) as f32 * 0.2113248654051871_f32;
    let d0 = std::array::from_fn::<_, 2, _>(|i| point[i] - (cell[i] as f32 - unskew));
    let step = if d0[0] > d0[1] { [1, 0] } else { [0, 1] };
    let d1 = std::array::from_fn(|i| d0[i] - step[i] as f32 + 0.2113248654051871_f32);
    let d2 = d0.map(|v| v - 1.0 + 0.4226497308103742_f32);
    70.0 * (contribution(cell, d0, low, high)
        + contribution(
            std::array::from_fn(|i| cell[i].wrapping_add(step[i])),
            d1,
            low,
            high,
        )
        + contribution(cell.map(|v| v.wrapping_add(1)), d2, low, high))
}
pub fn fbm(mut point: [f32; 2], low: u32, high: u32) -> f32 {
    let (mut sum, mut weights, mut weight) = (0.0, 0.0, 1.0);
    for octave in 0..4 {
        sum += simplex(point, low.wrapping_add(octave * 1013), high) * weight;
        weights += weight;
        point = point.map(|v| v * 2.0);
        weight *= 0.5;
    }
    sum / weights
}

#[cfg(test)]
mod tests {
    use super::*;
    #[test]
    fn integer_lattice_and_signed_seed_are_repeatable() {
        for point in [
            [0.0, 0.0, 0.0],
            [-1.0, -256.0, 41.0],
            [1234.0, 13.0, -567.0],
        ] {
            assert_eq!(noise3(point, u32::MAX), 0.0);
        }
        assert_eq!(mix_hash(0), 0);
        assert_eq!(mix_hash(1), 0x688990c0);
        assert_ne!(cell_hash([-3, 7], 1, 0), cell_hash([-3, 7], 1, 1));
        let p = [-17.25, 6.75, 11.125];
        let a = noise3(p, 813);
        assert!(a.is_finite());
        assert_eq!(a.to_bits(), noise3(p, 813).to_bits());
    }
}
