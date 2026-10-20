//! Registered height distributions and build-relative anchors. These are random
//! placement draws, not CPU copies of the GPU terrain/noise programs.
use super::Rng;
use crate::ChunkRequest;
use serde::Deserialize;

/// Preserve each caller's existing random stream while sharing provider semantics.
pub(crate) trait HeightRandom {
    fn below(&mut self, bound: i32) -> i32;
    fn weighted(&mut self, total: u64) -> u64;
}
impl HeightRandom for Rng {
    fn below(&mut self, bound: i32) -> i32 {
        Rng::below(self, bound)
    }
    fn weighted(&mut self, total: u64) -> u64 {
        self.next() % total
    }
}

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
    #[serde(alias = "minecraft:constant")]
    Constant { value: Anchor },
    #[serde(alias = "minecraft:uniform")]
    Uniform {
        min_inclusive: Anchor,
        max_inclusive: Anchor,
    },
    #[serde(alias = "minecraft:biased_to_bottom")]
    BiasedToBottom {
        min_inclusive: Anchor,
        max_inclusive: Anchor,
        #[serde(default = "one")]
        inner: i32,
    },
    #[serde(alias = "minecraft:very_biased_to_bottom")]
    VeryBiasedToBottom {
        min_inclusive: Anchor,
        max_inclusive: Anchor,
        #[serde(default = "one")]
        inner: i32,
    },
    #[serde(alias = "minecraft:trapezoid")]
    Trapezoid {
        min_inclusive: Anchor,
        max_inclusive: Anchor,
        #[serde(default)]
        plateau: i32,
    },
    #[serde(alias = "minecraft:weighted_list")]
    WeightedList { distribution: Vec<WeightedHeight> },
}
#[derive(Clone, Deserialize)]
pub struct WeightedHeight {
    pub data: HeightProvider,
    pub weight: u32,
}
fn between(rng: &mut impl HeightRandom, a: i32, b: i32) -> i32 {
    // Mth.nextInt retains the minimum for empty/equal ranges.
    if a >= b { a } else { a + rng.below(b - a + 1) }
}
impl HeightProvider {
    pub(crate) fn validate(&self) -> bool {
        match self {
            Self::Config(
                HeightConfig::BiasedToBottom { inner, .. }
                | HeightConfig::VeryBiasedToBottom { inner, .. },
            ) => *inner > 0,
            Self::Config(HeightConfig::WeightedList { distribution }) => {
                !distribution.is_empty()
                    && distribution.iter().any(|v| v.weight > 0)
                    && distribution.iter().map(|v| v.weight as u64).sum::<u64>() <= i32::MAX as u64
                    && distribution.iter().all(|v| v.data.validate())
            }
            _ => true,
        }
    }
    pub(crate) fn sample(&self, rng: &mut impl HeightRandom, request: ChunkRequest) -> i32 {
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
                let mut choice = rng.weighted(total);
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
            Self::Config(HeightConfig::Uniform { .. }) => {
                if lo > hi {
                    lo
                } else {
                    lo + rng.below(hi - lo + 1)
                }
            }
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
                    return lo + rng.below(hi - lo + 1);
                }
                let low = (span - plateau) / 2;
                lo + rng.below(span - low + 1) + rng.below(low + 1)
            }
            _ => unreachable!(),
        }
    }
}
