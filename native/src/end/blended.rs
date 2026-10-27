//! Seeded 26.3 BlendedNoise, including its deprecated smeared Perlin stacks.
//! Point and volume paths deliberately retain Minecraft's different multiply
//! and gradient-summation orders. Neither path uses fused multiply-add.
use crate::structures::Random;
use serde::{Deserialize, Serialize};
#[path="blended_column.rs"]
mod column;

// Every gradient has two unit components. Local X/Z are in [0,1];
// smeared Y is in [-1.1e-7,1]. A nonzero floor requires fudge_y
// <=1/(1-epsilon); its epsilon can overshoot by at most 1.1e-7.
// Dot magnitudes are below 2.000001. Rounded fade weights differ from
// [0,1] by less than 1e-5; three lerps remain far inside magnitude 4.
// Lower/upper stack amplitudes sum to <1; 0.01 also covers their 16
// rounded products/adds and the final clamped-alpha lerp. Main noise
// only selects alpha, so it cannot enlarge this conservative bound.
pub(super) const VOLUME_BOUND: f32 = 4.01;
const LAYER_BOUND: f64 = 4.0;

#[derive(Clone, Copy, Debug, Deserialize, Serialize)]
pub struct Parameters {
    pub xz_scale: f64,
    pub y_scale: f64,
    pub xz_factor: f64,
    pub y_factor: f64,
    pub smear_scale_multiplier: f64,
}
impl Default for Parameters {
    fn default() -> Self {
        Self { xz_scale: 0.25, y_scale: 0.25, xz_factor: 80.0, y_factor: 160.0, smear_scale_multiplier: 4.0 }
    }
}

#[derive(Serialize)]
pub struct BlendedNoise {
    parameters: Parameters,
    lower: Vec<Layer>,
    upper: Vec<Layer>,
    main: Vec<Layer>,
}

#[derive(Serialize)]
struct Layer {
    #[serde(serialize_with = "serialize_permutation")]
    permutation: [u8; 256],
    offsets: [f64; 3],
    frequency: f64,
    amplitude: f32,
    fudge_y: f64,
}

fn serialize_permutation<S: serde::Serializer>(value: &[u8; 256], serializer: S) -> Result<S::Ok, S::Error> {
    value.as_slice().serialize(serializer)
}

impl BlendedNoise {
    pub fn new(seed: u64, parameters: Parameters) -> Result<Self, String> {
        if [parameters.xz_scale, parameters.y_scale, parameters.xz_factor, parameters.y_factor]
            .iter().any(|v| !v.is_finite() || !(0.001..=1000.0).contains(v))
            || !parameters.smear_scale_multiplier.is_finite()
            || !(1.0..=8.0).contains(&parameters.smear_scale_multiplier) {
            return Err("End blended-noise parameters exceed the registered Minecraft codec ranges".into());
        }
        let mut random = Random::new(seed);
        let smear_y = (684.412 * parameters.y_scale) * parameters.smear_scale_multiplier;
        let lower = Self::stack(&mut random, 16, smear_y, 0.99998474f32 as f64);
        let upper = Self::stack(&mut random, 16, smear_y, 0.99998474f32 as f64);
        let main = Self::stack(&mut random, 8, smear_y / parameters.y_factor, 12.75);
        Ok(Self { parameters, lower, upper, main })
    }

    fn stack(random: &mut Random, count: usize, smear_y: f64, mut amplitude: f64) -> Vec<Layer> {
        amplitude /= ((1u32 << count) - 1) as f64;
        let mut frequency = 1.0;
        let mut layers = Vec::with_capacity(count);
        for _ in 0..count {
            // 26.3 BitRandomSource.nextDouble widens a float result (l2f,
            // fmul, f2d). Retain that rounding for End noise without changing
            // the existing structure RNG's higher-precision double method.
            let offsets = std::array::from_fn(|_| (random.double() as f32) as f64 * 256.0);
            let permutation = super::permutation(random);
            layers.push(Layer { permutation, offsets, frequency, amplitude: amplitude as f32,
                fudge_y: smear_y * frequency });
            frequency /= 2.0;
            amplitude *= 2.0;
        }
        layers
    }

    pub fn sample(&self, x: i32, y: i32, z: i32) -> f32 {
        self.value(x, y, z, false)
    }

    /// A point evaluated with the operation order of vanilla's bulk volume path.
    pub fn volume_point(&self, x: i32, y: i32, z: i32) -> f32 {
        self.value(x, y, z, true)
    }

    /// Bound the complete volume result using only the four largest-amplitude
    /// layers of each limit stack. Unknown layers and alpha remain intervals;
    /// this result is never substituted for an exact density value.
    #[cfg(test)]
    pub(super) fn volume_bounds(&self, x: i32, y: i32, z: i32) -> [f32; 2] {
        let p = self.parameters;
        let bounds = |layers: &[Layer]| {
            let split = layers.len()-4;
            let known: f64 = layers[split..].iter().map(|layer|
                f64::from(Self::layer_value(layer,x,y,z,684.412*p.xz_scale,684.412*p.y_scale,true))).sum();
            let unknown: f64 = layers[..split].iter().map(|layer|f64::from(layer.amplitude.abs())*LAYER_BOUND).sum();
            // Reserve much more than all source-order f32 accumulation error,
            // including the final blend and unknown rounded products.
            [known-unknown-0.001,known+unknown+0.001]
        };
        let a=bounds(&self.lower);let b=bounds(&self.upper);
        [(a[0].min(b[0]) as f32).next_down(),(a[1].max(b[1]) as f32).next_up()]
    }

    fn value(&self, x: i32, y: i32, z: i32, volume: bool) -> f32 {
        let p = self.parameters;
        let xz_scale = 684.412 * p.xz_scale;
        let y_scale = 684.412 * p.y_scale;
        let alpha = (Self::sample_stack(&self.main, x, y, z, xz_scale / p.xz_factor,
            y_scale / p.y_factor, volume) + 0.5).clamp(0.0, 1.0);
        if alpha == 0.0 {
            return Self::sample_stack(&self.lower, x, y, z, xz_scale, y_scale, volume);
        }
        if alpha == 1.0 {
            return Self::sample_stack(&self.upper, x, y, z, xz_scale, y_scale, volume);
        }
        let lower = Self::sample_stack(&self.lower, x, y, z, xz_scale, y_scale, volume);
        let upper = Self::sample_stack(&self.upper, x, y, z, xz_scale, y_scale, volume);
        lerp(alpha, lower, upper)
    }

    fn sample_stack(layers: &[Layer], x: i32, y: i32, z: i32, xz_scale: f64, y_scale: f64, volume: bool) -> f32 {
        let mut value = 0.0f32;
        for layer in layers {
            value += Self::layer_value(layer,x,y,z,xz_scale,y_scale,volume);
        }
        value
    }

    fn layer_value(layer: &Layer,x:i32,y:i32,z:i32,xz_scale:f64,y_scale:f64,volume:bool)->f32 {
        let point = if volume {
                [x as f64 * (xz_scale * layer.frequency), y as f64 * (y_scale * layer.frequency),
                    z as f64 * (xz_scale * layer.frequency)]
            } else {
                [(x as f64 * xz_scale) * layer.frequency, (y as f64 * y_scale) * layer.frequency,
                    (z as f64 * xz_scale) * layer.frequency]
            };
        layer.amplitude * layer.sample(point, volume)
    }
}

const GRADIENTS: [[f32; 3]; 16] = [
    [1.,1.,0.],[-1.,1.,0.],[1.,-1.,0.],[-1.,-1.,0.],
    [1.,0.,1.],[-1.,0.,1.],[1.,0.,-1.],[-1.,0.,-1.],
    [0.,1.,1.],[0.,-1.,1.],[0.,1.,-1.],[0.,-1.,-1.],
    [1.,1.,0.],[0.,-1.,1.],[-1.,1.,0.],[0.,-1.,-1.],
];

fn lerp(t: f32, a: f32, b: f32) -> f32 { a + t * (b - a) }
fn smoothstep(t: f32) -> f32 { t * t * t * (t * (t * 6.0 - 15.0) + 10.0) }
fn wrap(x: f64) -> f64 {
    let half = f64::from_bits(16777216.0f64.to_bits() - 1);
    if x >= -half && x < half { x }
    else { x - (x / 33554432.0 + 0.5).floor() * 33554432.0 }
}

impl Layer {
    fn permute(&self, i: i32) -> i32 { self.permutation[(i & 255) as usize] as i32 }

    fn sample(&self, point: [f64; 3], volume: bool) -> f32 {
        let phase: [f64; 3] = std::array::from_fn(|axis| wrap(point[axis]) + self.offsets[axis]);
        let cell: [i32; 3] = phase.map(|v| v.floor() as i32);
        let relative: [f64; 3] = std::array::from_fn(|axis| phase[axis] - cell[axis] as f64);
        let limit = if point[1] >= 0.0 && point[1] < relative[1] { point[1] } else { relative[1] };
        let fudge = (limit / self.fudge_y + 1.0e-7f32 as f64).floor() * self.fudge_y;
        let local = [relative[0] as f32, (relative[1] - fudge) as f32, relative[2] as f32];
        let alpha = [smoothstep(local[0]), smoothstep(relative[1] as f32), smoothstep(local[2])];
        let dots: [f32; 8] = std::array::from_fn(|corner| {
            let dx = (corner & 1) as i32;
            let dy = ((corner >> 1) & 1) as i32;
            let dz = ((corner >> 2) & 1) as i32;
            let x = self.permute(cell[0] + dx);
            let xy = self.permute(x + cell[1] + dy);
            let gradient = GRADIENTS[(self.permute(xy + cell[2] + dz) & 15) as usize];
            let p = [local[0] - dx as f32, local[1] - dy as f32, local[2] - dz as f32];
            if volume {
                (gradient[0] * p[0] + gradient[2] * p[2]) + gradient[1] * p[1]
            } else {
                (gradient[0] * p[0] + gradient[1] * p[1]) + gradient[2] * p[2]
            }
        });
        lerp(alpha[2],
            lerp(alpha[1], lerp(alpha[0], dots[0], dots[1]), lerp(alpha[0], dots[2], dots[3])),
            lerp(alpha[1], lerp(alpha[0], dots[4], dots[5]), lerp(alpha[0], dots[6], dots[7])))
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn vanilla_26_3_uses_float_rounded_random_offsets() {
        let noise = BlendedNoise::new(0, Parameters::default()).unwrap();
        assert_eq!(noise.lower[0].offsets, [187.12774658203125, 61.57732391357422, 163.17886352539062]);
    }

    #[test]
    fn vanilla_blended_scalar_and_bulk_bits() {
        let source: serde_json::Value = serde_json::from_str(include_str!("../../tests/fixtures/end-blended-26.3.json")).unwrap();
        for case in source["cases"].as_array().unwrap() {
            let seed = case["seed"].as_i64().unwrap();
            let parameters = serde_json::from_value(case["parameters"].clone()).unwrap();
            let noise = BlendedNoise::new(seed as u64, parameters).unwrap();
            for point in case["points"].as_array().unwrap() {
                let x = point["xyz"][0].as_i64().unwrap() as i32;
                let y = point["xyz"][1].as_i64().unwrap() as i32;
                let z = point["xyz"][2].as_i64().unwrap() as i32;
                if let Some(expected) = point["scalar_bits"].as_i64() {
                    assert_eq!(noise.sample(x, y, z).to_bits(), expected as u32, "scalar seed={seed} at {x},{y},{z}");
                }
                assert_eq!(noise.volume_point(x, y, z).to_bits(), point["volume_bits"].as_i64().unwrap() as u32,
                    "bulk seed={seed} at {x},{y},{z}");
                let value=f32::from_bits(point["volume_bits"].as_i64().unwrap() as u32);
                let [low,high]=noise.volume_bounds(x,y,z);
                assert!(low<=value && value<=high,"source value outside bound seed={seed} at {x},{y},{z}: {low}..{high} versus {value}");
                assert!(value.abs()<=VOLUME_BOUND);
                if (0..=128).contains(&y) && y%4==0 {
                    let flags=1u64<<(y/4);
                    assert_eq!(noise.volume_column(x,z,flags)[(y/4) as usize].to_bits(),value.to_bits());
                }
            }
        }
    }

    #[test]
    fn noise_bounds_cover_codec_extremes_and_wrapped_coordinates() {
        for seed in [0,42,u64::MAX] {
            for parameters in [Parameters::default(),
                Parameters{xz_scale:0.001,y_scale:1000.0,xz_factor:0.001,y_factor:1000.0,smear_scale_multiplier:1.0},
                Parameters{xz_scale:1000.0,y_scale:0.001,xz_factor:1000.0,y_factor:0.001,smear_scale_multiplier:8.0},
                Parameters{y_scale:32.0*1.00000005/(684.412*4.0),..Parameters::default()}] {
                let noise=BlendedNoise::new(seed,parameters).unwrap();
                for (x,z) in [(0,0),(-1,1),(16777217,-16777211),(i32::MIN+8,i32::MAX-8)] {
                    for y in [-65,-1,0,1,4,32,56,128,312] {
                        let value=noise.volume_point(x,y,z);let [low,high]=noise.volume_bounds(x,y,z);
                        assert!(low<=value && value<=high,"{seed} {x},{y},{z}: {low}..{high} versus {value}");
                        assert!(value.abs()<=VOLUME_BOUND);
                    }
                }
            }
        }
    }
}
