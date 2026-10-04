//! Registered height distributions and build-relative anchors. These are random
//! placement draws, not CPU copies of the GPU terrain/noise programs.
use super::Rng;
use crate::ChunkRequest;
use serde::Deserialize;

#[derive(Clone, Deserialize)]
#[serde(rename_all = "snake_case")]
pub enum Anchor {
    Absolute(i32),
    AboveBottom(i32),
    BelowTop(i32),
}
impl Anchor {
    fn resolve(&self, request: ChunkRequest) -> i32 {
        match self {
            Self::Absolute(v) => *v,
            Self::AboveBottom(v) => request.min_y.saturating_add(*v),
            Self::BelowTop(v) => (request.min_y + request.height as i32 - 1).saturating_sub(*v),
        }
    }
}
#[derive(Clone, Deserialize)]
#[serde(untagged)]
pub enum HeightProvider {
    Constant(Anchor),
    Config(HeightConfig),
}
fn one() -> i32 {
    1
}
#[derive(Clone, Deserialize)]
#[serde(tag = "type", rename_all = "snake_case")]
pub enum HeightConfig {
    Constant {
        value: Anchor,
    },
    Uniform {
        min_inclusive: Anchor,
        max_inclusive: Anchor,
    },
    BiasedToBottom {
        min_inclusive: Anchor,
        max_inclusive: Anchor,
        #[serde(default = "one")]
        inner: i32,
    },
    VeryBiasedToBottom {
        min_inclusive: Anchor,
        max_inclusive: Anchor,
        #[serde(default = "one")]
        inner: i32,
    },
    Trapezoid {
        min_inclusive: Anchor,
        max_inclusive: Anchor,
        #[serde(default)]
        plateau: i32,
    },
    WeightedList {
        distribution: Vec<WeightedHeight>,
    },
}
#[derive(Clone, Deserialize)]
pub struct WeightedHeight {
    pub data: HeightProvider,
    pub weight: u32,
}
fn between(rng: &mut Rng, a: i32, b: i32) -> i32 {
    // Mth.nextInt retains the minimum for empty/equal ranges.
    if a >= b { a } else { a + rng.below(b - a + 1) }
}
impl HeightProvider {
    pub(super) fn validate(&self) -> bool {
        match self {
            Self::Config(
                HeightConfig::BiasedToBottom { inner, .. }
                | HeightConfig::VeryBiasedToBottom { inner, .. },
            ) => *inner > 0,
            Self::Config(HeightConfig::WeightedList { distribution }) => {
                !distribution.is_empty()
                    && distribution.iter().any(|v| v.weight > 0)
                    && distribution.iter().all(|v| v.data.validate())
            }
            _ => true,
        }
    }
    pub(super) fn sample(&self, rng: &mut Rng, request: ChunkRequest) -> i32 {
        let (lo, hi) = match self {
            Self::Constant(v) | Self::Config(HeightConfig::Constant { value: v }) => {
                return v.resolve(request);
            }
            Self::Config(
                HeightConfig::Uniform {
                    min_inclusive,
                    max_inclusive,
                }
                | HeightConfig::BiasedToBottom {
                    min_inclusive,
                    max_inclusive,
                    ..
                }
                | HeightConfig::VeryBiasedToBottom {
                    min_inclusive,
                    max_inclusive,
                    ..
                }
                | HeightConfig::Trapezoid {
                    min_inclusive,
                    max_inclusive,
                    ..
                },
            ) => (
                min_inclusive.resolve(request),
                max_inclusive.resolve(request),
            ),
            Self::Config(HeightConfig::WeightedList { distribution }) => {
                let total: u64 = distribution.iter().map(|v| v.weight as u64).sum();
                let mut choice = rng.next() % total;
                for v in distribution {
                    if choice < v.weight as u64 {
                        return v.data.sample(rng, request);
                    }
                    choice -= v.weight as u64;
                }
                unreachable!();
            }
        };
        match self {
            Self::Config(HeightConfig::Uniform { .. }) => between(rng, lo, hi),
            Self::Config(HeightConfig::BiasedToBottom { inner, .. }) => {
                if hi as i64 - lo as i64 - *inner as i64 + 1 <= 0 {
                    return lo;
                }
                let limit = rng.below(hi - lo - inner + 1);
                lo + rng.below(limit + inner)
            }
            Self::Config(HeightConfig::VeryBiasedToBottom { inner, .. }) => {
                if hi as i64 - lo as i64 - *inner as i64 + 1 <= 0 {
                    return lo;
                }
                let upper = between(rng, lo + inner, hi);
                let biased = between(rng, lo, upper - 1);
                between(rng, lo, biased - 1 + inner)
            }
            Self::Config(HeightConfig::Trapezoid { plateau, .. }) => {
                if lo > hi {
                    return lo;
                }
                let span = hi - lo;
                if *plateau >= span {
                    return between(rng, lo, hi);
                }
                let low = (span - plateau) / 2;
                lo + between(rng, 0, span - low) + between(rng, 0, low)
            }
            _ => unreachable!(),
        }
    }
}
