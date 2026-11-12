use super::Rng;
use serde::Deserialize;

#[derive(Clone, Deserialize)]
#[serde(untagged)]
pub enum FloatProvider {
    Value(f32),
    Config(FloatConfig),
}

#[derive(Clone, Deserialize)]
#[serde(tag = "type", rename_all = "snake_case")]
pub enum FloatConfig {
    Constant {
        value: f32,
    },
    Uniform {
        min_inclusive: f32,
        max_exclusive: f32,
    },
    ClampedNormal {
        mean: f32,
        deviation: f32,
        min: f32,
        max: f32,
    },
    Trapezoid {
        min: f32,
        max: f32,
        plateau: f32,
    },
}

impl FloatProvider {
    pub(super) fn valid(&self, low: f32, high: f32) -> bool {
        let (min, max) = match *self {
            Self::Value(v) | Self::Config(FloatConfig::Constant { value: v }) => (v, v),
            Self::Config(FloatConfig::Uniform {
                min_inclusive,
                max_exclusive,
            }) => (min_inclusive, max_exclusive),
            Self::Config(FloatConfig::ClampedNormal {
                mean,
                deviation,
                min,
                max,
            }) => {
                if !mean.is_finite() || !deviation.is_finite() {
                    return false;
                }
                (min, max)
            }
            Self::Config(FloatConfig::Trapezoid { min, max, plateau }) => {
                if !plateau.is_finite() || plateau > max - min {
                    return false;
                }
                (min, max)
            }
        };
        low <= min && min <= max && max <= high
    }

    pub(super) fn sample(&self, rng: &mut Rng) -> f32 {
        match *self {
            Self::Value(v) | Self::Config(FloatConfig::Constant { value: v }) => v,
            Self::Config(FloatConfig::Uniform {
                min_inclusive,
                max_exclusive,
            }) => min_inclusive + (max_exclusive - min_inclusive) * rng.unit() as f32,
            Self::Config(FloatConfig::ClampedNormal {
                mean,
                deviation,
                min,
                max,
            }) => normal(rng, mean, deviation, min, max),
            Self::Config(FloatConfig::Trapezoid { min, max, plateau }) => {
                let slope = (max - min - plateau) / 2.0;
                min + (max - min - slope) * rng.unit() as f32 + slope * rng.unit() as f32
            }
        }
    }
}

pub(super) fn normal(rng: &mut Rng, mean: f32, deviation: f32, min: f32, max: f32) -> f32 {
    let standard = (-2.0 * rng.unit().max(f64::MIN_POSITIVE).ln()).sqrt()
        * (std::f64::consts::TAU * rng.unit()).cos();
    (mean + standard as f32 * deviation).clamp(min, max)
}
