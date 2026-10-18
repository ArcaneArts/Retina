//! Compact, seed-driven geometry inputs. Endpoint trigonometry stays on the CPU;
//! parallel sphere construction and ordered containment pruning run on the GPU.
use super::*;

#[repr(C)]
#[derive(Clone, Copy, bytemuck::Pod, bytemuck::Zeroable)]
pub(crate) struct Seed {
    pub random_size: [u32; 4],
    pub a: [f32; 4],
    pub delta: [f32; 4],
}
impl Seed {
    pub(crate) fn new(origin: [i32; 3], size: u32, seed: u64) -> Self {
        let [x, y, z] = origin;
        let mut random = Random(seed);
        let angle = random.unit() * std::f32::consts::PI;
        let dx = angle.sin() * size as f32 / 8.;
        let dz = angle.cos() * size as f32 / 8.;
        let a = [
            x as f32 + dx,
            y as f32 + random.int(-2, 0) as f32,
            z as f32 + dz,
            0.,
        ];
        let b = [
            x as f32 - dx,
            y as f32 + random.int(-2, 0) as f32,
            z as f32 - dz,
            0.,
        ];
        Self {
            random_size: [seed as u32, (seed >> 32) as u32, size, 0],
            a,
            delta: std::array::from_fn(|i| b[i] - a[i]),
        }
    }
    pub(crate) fn bounds(&self) -> ([i32; 3], [u32; 3]) {
        let size = self.random_size[2];
        let t = (size - 1) as f32 / size as f32;
        // Each interpolated center is monotone in t, including f32 rounding.
        // The maximum possible radius is size/16 + 0.5. Evaluating the two
        // extreme centers bounds every original CPU sphere loop box exactly
        // conservatively, including coarse world-coordinate rounding.
        let last: [f32; 3] = std::array::from_fn(|i| self.a[i] + self.delta[i] * t);
        let radius = size as f32 / 16. + 0.5;
        let minimum = std::array::from_fn(|i| (self.a[i].min(last[i]) - radius).floor() as i32);
        let maximum: [i32; 3] =
            std::array::from_fn(|i| (self.a[i].max(last[i]) + radius).floor() as i32);
        (
            minimum,
            std::array::from_fn(|i| (maximum[i] - minimum[i] + 1) as u32),
        )
    }
}
pub(crate) fn coefficients() -> Vec<[u32; 2]> {
    (0..65)
        .flat_map(|size| {
            (0..64).map(move |i| {
                if size == 0 || i >= size {
                    return [0; 2];
                }
                let t = i as f32 / size as f32;
                [
                    t.to_bits(),
                    ((std::f32::consts::PI * t).sin() + 1.).to_bits(),
                ]
            })
        })
        .collect()
}

#[cfg(test)]
mod tests {
    use super::*;
    #[test]
    fn conservative_seed_boxes_contain_cpu_pruned_spheres() {
        let mut random = Random(91823612);
        for origin in [
            [0, -64, 0],
            [-519, 64, 1023],
            [4194303, -100, 4194305],
            [-30_000_000, 256, 30_000_000],
        ] {
            for size in 1..=64 {
                let recipe = OreRecipe {
                    id: "test".into(),
                    size,
                    discard: 0.,
                    scattered: false,
                    count_min: 1,
                    count_max: 1,
                    rarity: 1,
                    height: HeightRange::default(),
                    replacement_bands: vec![],
                };
                for _ in 0..8 {
                    let seed = (u64::from(random.next()) << 32) | u64::from(random.next());
                    let input = Seed::new(origin, size, seed);
                    let (low, sizes) = input.bounds();
                    let mut spheres = Vec::new();
                    vein_spheres(
                        &recipe,
                        origin[0],
                        origin[1],
                        origin[2],
                        &mut Random(seed),
                        &mut spheres,
                    );
                    for sphere in spheres.iter().filter(|s| s[3] > 0.) {
                        for k in 0..3 {
                            assert!((sphere[k] - sphere[3]).floor() as i32 >= low[k]);
                            assert!(
                                ((sphere[k] + sphere[3]).floor() as i32 as i64)
                                    < low[k] as i64 + sizes[k] as i64
                            );
                        }
                    }
                }
            }
        }
    }
}
