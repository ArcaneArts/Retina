//! Ordered registered block columns, bamboo and aquatic simple blocks.
use super::*;
use placement::{IntProvider, Overlay, Predicate};

#[derive(Clone, Deserialize)]
#[serde(tag = "type", rename_all = "snake_case")]
pub enum Provider {
    State {
        material: u16,
    },
    Weighted {
        entries: Vec<WeightedProvider>,
    },
    RandomizedInt {
        source: Box<Provider>,
        values: IntProvider,
        variants: Vec<IntStates>,
    },
    RuleBased {
        #[serde(default)]
        fallback: Option<Box<Provider>>,
        rules: Vec<StateRule>,
    },
    Rotated {
        source: Box<Provider>,
        #[serde(default)]
        direction: Option<usize>,
        variants: Vec<Rotations>,
    },
    RandomBlock {
        states: Vec<u16>,
    },
}
#[derive(Clone, Deserialize)]
pub struct StateRule {
    pub predicate: Predicate,
    pub provider: Provider,
}
#[derive(Clone, Deserialize)]
pub struct Rotations {
    pub source: u16,
    pub states: [u16; 6],
}
#[derive(Clone, Deserialize)]
pub struct WeightedProvider {
    pub weight: u32,
    pub provider: Provider,
}
#[derive(Clone, Deserialize)]
pub struct IntStates {
    pub source: u16,
    pub minimum: i32,
    pub states: Vec<u16>,
}
impl Provider {
    pub(super) fn nullable(&self) -> bool {
        match self {
            Self::RuleBased { fallback, .. } => fallback.as_ref().is_none_or(|p| p.nullable()),
            Self::RandomBlock { states } => states.is_empty(),
            _ => false,
        }
    }
    pub(super) fn outputs(&self) -> Vec<u16> {
        match self {
            Self::State { material } => vec![*material],
            Self::Weighted { entries } => {
                entries.iter().flat_map(|v| v.provider.outputs()).collect()
            }
            Self::RandomizedInt { variants, .. } => variants
                .iter()
                .flat_map(|v| v.states.iter().copied())
                .collect(),
            Self::RuleBased { fallback, rules } => rules
                .iter()
                .flat_map(|r| r.provider.outputs())
                .chain(fallback.iter().flat_map(|p| p.outputs()))
                .collect(),
            Self::Rotated { variants, .. } => variants.iter().flat_map(|v| v.states).collect(),
            Self::RandomBlock { states } => states.clone(),
        }
    }
    pub(super) fn validate(&self, palette: usize) -> bool {
        match self {
            Self::State { material } => (*material as usize) < palette,
            Self::Weighted { entries } => {
                !entries.is_empty()
                    && entries.iter().any(|v| v.weight > 0)
                    && entries
                        .iter()
                        .all(|v| !v.provider.nullable() && v.provider.validate(palette))
            }
            Self::RandomizedInt {
                source,
                values,
                variants,
            } => {
                let Ok((lo, hi)) = values.bounds() else {
                    return false;
                };
                !source.nullable()
                    && source.validate(palette)
                    && source.outputs().iter().all(|id| {
                        variants.iter().any(|v| {
                            v.source == *id
                                && lo >= v.minimum
                                && (hi as i64) < v.minimum as i64 + v.states.len() as i64
                        })
                    })
                    && variants.iter().all(|v| {
                        !v.states.is_empty() && v.states.iter().all(|id| (*id as usize) < palette)
                    })
            }
            Self::RuleBased { fallback, rules } => {
                fallback.as_ref().is_none_or(|p| p.validate(palette))
                    && rules
                        .iter()
                        .all(|r| r.predicate.validate(palette) && r.provider.validate(palette))
            }
            Self::Rotated {
                source,
                direction,
                variants,
            } => {
                !source.nullable()
                    && source.validate(palette)
                    && direction.is_none_or(|d| d < 6)
                    && source
                        .outputs()
                        .iter()
                        .all(|id| variants.iter().any(|v| v.source == *id))
                    && variants
                        .iter()
                        .all(|v| v.states.iter().all(|id| (*id as usize) < palette))
            }
            Self::RandomBlock { states } => states.iter().all(|id| (*id as usize) < palette),
        }
    }
    /// Providers normally use getState, whose nullable rule/random-block result
    /// keeps the current block. SimpleBlockFeature instead uses getOptionalState.
    pub(super) fn sample(
        &self,
        rng: &mut Rng,
        at: [i32; 3],
        material: &impl Fn([i32; 3]) -> Option<u16>,
    ) -> u16 {
        self.sample_optional(rng, at, material)
            .unwrap_or_else(|| material(at).unwrap_or(0))
    }
    pub(super) fn sample_optional(
        &self,
        rng: &mut Rng,
        at: [i32; 3],
        material: &impl Fn([i32; 3]) -> Option<u16>,
    ) -> Option<u16> {
        match self {
            Self::State { material } => Some(*material),
            Self::Weighted { entries } => {
                let mut n = rng.next() % entries.iter().map(|v| v.weight as u64).sum::<u64>();
                for v in entries {
                    if n < v.weight as u64 {
                        return Some(v.provider.sample(rng, at, material));
                    }
                    n -= v.weight as u64;
                }
                unreachable!()
            }
            Self::RandomizedInt {
                source,
                values,
                variants,
            } => {
                let source = source.sample(rng, at, material);
                let variant = variants.iter().find(|v| v.source == source).unwrap();
                Some(variant.states[(values.sample(rng) - variant.minimum) as usize])
            }
            Self::RuleBased { fallback, rules } => {
                for rule in rules {
                    if rule.predicate.test_with(at, material) == Some(true) {
                        if let Some(state) = rule.provider.sample_optional(rng, at, material) {
                            return Some(state);
                        }
                    }
                }
                fallback
                    .as_ref()
                    .and_then(|p| p.sample_optional(rng, at, material))
            }
            Self::Rotated {
                source,
                direction,
                variants,
            } => {
                // Direction.getRandom runs before the nested provider.
                let direction = direction.unwrap_or_else(|| rng.below(6) as usize);
                let source = source.sample(rng, at, material);
                Some(variants.iter().find(|v| v.source == source).unwrap().states[direction])
            }
            Self::RandomBlock { states } => {
                (!states.is_empty()).then(|| states[rng.below(states.len() as i32) as usize])
            }
        }
    }
}
#[derive(Clone, Deserialize)]
pub struct Layer {
    pub height: IntProvider,
    pub provider: Provider,
}
#[derive(Clone, Deserialize)]
pub struct ColumnRecipe {
    pub layers: Vec<Layer>,
    pub direction: [i32; 3],
    pub allowed: Predicate,
    pub prioritize_tip: bool,
}
impl ColumnRecipe {
    pub(super) fn validate(&self, palette: usize) -> bool {
        let maximum = if self.direction[1] == 0 { 15 } else { 4096 };
        self.direction
            .iter()
            .map(|v| (*v as i64).abs())
            .sum::<i64>()
            == 1
            && self.allowed.validate(palette)
            && !self.layers.is_empty()
            && self.layers.len() <= 128
            && self.layers.iter().all(|l| {
                l.provider.validate(palette) && l.height.bounds().is_ok_and(|(a, _)| a >= 0)
            })
            && self
                .layers
                .iter()
                .map(|l| l.height.bounds().map_or(i64::MAX / 128, |v| v.1 as i64))
                .sum::<i64>()
                <= maximum
    }
}
#[derive(Clone, Deserialize)]
pub struct BambooRecipe {
    pub probability: f64,
    pub trunk: u16,
    pub crown: [u16; 3],
    pub podzol: u16,
    pub survival: Predicate,
    pub podzol_allowed: Predicate,
}
impl BambooRecipe {
    pub(super) fn validate(&self, palette: usize) -> bool {
        self.probability.is_finite()
            && (0.0..=1.0).contains(&self.probability)
            && [self.trunk, self.podzol]
                .iter()
                .chain(self.crown.iter())
                .all(|id| (*id as usize) < palette)
            && self.survival.validate(palette)
            && self.podzol_allowed.validate(palette)
    }
}
#[derive(Clone, Deserialize)]
pub struct AquaticState {
    pub lower: u16,
    pub upper: u16,
    pub weight: f64,
    pub band: u8,
    pub survival: Predicate,
    pub upper_allowed: Predicate,
}
pub(super) fn aquatic_valid(states: &[AquaticState], palette: usize) -> bool {
    !states.is_empty()
        && states.iter().all(|s| {
            (s.lower as usize) < palette
                && (s.upper as usize) < palette
                && s.weight.is_finite()
                && s.weight > 0.
                && s.band <= 2
                && s.survival.validate(palette)
                && s.upper_allowed.validate(palette)
        })
}
fn write(blocks: &mut Vec<WorldBlock>, at: [i32; 3], material: u16) {
    blocks.push(WorldBlock {
        x: at[0],
        y: at[1],
        z: at[2],
        material,
        upper: 0,
        role: FEATURE,
    });
}
pub(super) fn place_column(
    column: &ColumnRecipe,
    at: [i32; 3],
    rng: &mut Rng,
    field: &Field,
    profile: &WorldProfile,
    request: ChunkRequest,
    overlay: &Overlay,
    blocks: &mut Vec<WorldBlock>,
) -> bool {
    let test = |p: &Predicate, at| p.test(at, field, profile, request, overlay) == Some(true);
    let mut heights: Vec<i32> = column.layers.iter().map(|l| l.height.sample(rng)).collect();
    let total: i32 = heights.iter().sum();
    for y in 0..total {
        // Minecraft checks the next position, not the origin.
        let next = std::array::from_fn(|i| at[i] + column.direction[i] * (y + 1));
        if !test(&column.allowed, next) {
            let mut remove = total - y;
            let indices: Vec<_> = if column.prioritize_tip {
                (0..heights.len()).collect()
            } else {
                (0..heights.len()).rev().collect()
            };
            for i in indices {
                let n = heights[i].min(remove);
                heights[i] -= n;
                remove -= n;
                if remove == 0 {
                    break;
                }
            }
            break;
        }
    }
    let mut pos = at;
    let start = blocks.len();
    for (layer, height) in column.layers.iter().zip(heights) {
        for _ in 0..height {
            let material = layer.provider.sample(rng, pos, &|p| {
                if p[1] < request.min_y || p[1] >= request.min_y + request.height as i32 {
                    return Some(0);
                }
                blocks[start..]
                    .iter()
                    .rev()
                    .find(|b| [b.x, b.y, b.z] == p)
                    .map(|b| b.material)
                    .or_else(|| overlay.material(field, profile, request, p))
            });
            write(blocks, pos, material);
            pos = std::array::from_fn(|i| pos[i] + column.direction[i]);
        }
    }
    total != 0
}
pub(super) fn place(
    kind: &Kind,
    at: [i32; 3],
    rng: &mut Rng,
    field: &Field,
    profile: &WorldProfile,
    request: ChunkRequest,
    overlay: &Overlay,
    blocks: &mut Vec<WorldBlock>,
) {
    let test = |p: &Predicate, at| p.test(at, field, profile, request, overlay) == Some(true);
    match kind {
        Kind::FallenTree { fallen } => {
            super::fallen::place(fallen, at, rng, field, profile, request, overlay, blocks)
        }
        Kind::HugeMushroom { mushroom } => {
            super::mushroom::place(mushroom, at, rng, field, profile, request, overlay, blocks)
        }
        Kind::BlockColumn { column } => {
            place_column(column, at, rng, field, profile, request, overlay, blocks);
        }
        Kind::Bamboo { bamboo } => {
            if overlay.material(field, profile, request, at) != Some(0)
                || !test(&bamboo.survival, at)
            {
                return;
            }
            let height = 5 + rng.below(12);
            if rng.unit() < bamboo.probability {
                let radius = 1 + rng.below(4);
                for x in -radius..=radius {
                    for z in -radius..=radius {
                        if x * x + z * z > radius * radius {
                            continue;
                        }
                        let Some(y) =
                            overlay.height(field, profile, request, at[0] + x, at[2] + z, 1)
                        else {
                            continue;
                        };
                        let pos = [at[0] + x, y - 1, at[2] + z];
                        if test(&bamboo.podzol_allowed, pos) {
                            write(blocks, pos, bamboo.podzol);
                        }
                    }
                }
            }
            let mut count = 0;
            while count < height
                && overlay.material(field, profile, request, [at[0], at[1] + count, at[2]])
                    == Some(0)
            {
                write(blocks, [at[0], at[1] + count, at[2]], bamboo.trunk);
                count += 1;
            }
            if count >= 3 {
                for (dy, material) in bamboo.crown.iter().enumerate() {
                    write(blocks, [at[0], at[1] + count - dy as i32, at[2]], *material);
                }
            }
        }
        Kind::Aquatic { states } => {
            let low = field.column(at[0], at[2]).unwrap().packed & (1 << 27) != 0;
            if let Some(s) = weighted(
                states
                    .iter()
                    .filter(|s| s.band == 0 || s.band == if low { 1 } else { 2 }),
                |s| s.weight,
                rng,
            ) {
                if !test(&s.survival, at)
                    || (s.upper != 0 && !test(&s.upper_allowed, [at[0], at[1] + 1, at[2]]))
                {
                    return;
                }
                write(blocks, at, s.lower);
                if s.upper != 0 {
                    write(blocks, [at[0], at[1] + 1, at[2]], s.upper);
                }
            }
        }
        _ => unreachable!(),
    }
}

#[cfg(test)]
mod provider_tests {
    use super::*;

    #[test]
    fn rules_continue_after_nullable_matches_and_preserve_draws() {
        let provider: Provider = serde_json::from_str(r#"{"type":"rule_based","rules":[
            {"predicate":{"type":"true"},"provider":{"type":"random_block","states":[]}},
            {"predicate":{"type":"material","offset":[0,-1,0],"allowed":[7]},"provider":{"type":"random_block","states":[2,3]}}
        ]}"#).unwrap();
        assert!(provider.validate(8));
        for seed in 0..64 {
            let mut rng = Rng::new(seed);
            let mut expected = Rng::new(seed);
            let chosen = [2, 3][expected.below(2) as usize];
            assert_eq!(
                provider.sample_optional(&mut rng, [-17, 8, -33], &|p| Some(if p[1] == 7 {
                    7
                } else {
                    0
                })),
                Some(chosen)
            );
            assert_eq!(rng.next(), expected.next());
            let mut rng = Rng::new(seed);
            let mut expected = Rng::new(seed);
            assert_eq!(
                provider.sample_optional(&mut rng, [0, 0, 0], &|_| Some(4)),
                None
            );
            assert_eq!(provider.sample(&mut rng, [0, 0, 0], &|_| Some(4)), 4);
            assert_eq!(rng.next(), expected.next());
        }
    }

    #[test]
    fn rotations_draw_direction_before_nested_state_and_fixed_direction_draws_nothing() {
        for direction in [None, Some(4)] {
            let provider = Provider::Rotated {
                source: Box::new(Provider::RandomBlock { states: vec![1, 2] }),
                direction,
                variants: vec![
                    Rotations {
                        source: 1,
                        states: [3, 4, 5, 6, 7, 8],
                    },
                    Rotations {
                        source: 2,
                        states: [9, 10, 11, 12, 13, 14],
                    },
                ],
            };
            assert!(provider.validate(15));
            for seed in 0..64 {
                let mut rng = Rng::new(seed);
                let mut expected = Rng::new(seed);
                let d = direction.unwrap_or_else(|| expected.below(6) as usize);
                let source = expected.below(2);
                assert_eq!(
                    provider.sample(&mut rng, [0, 0, 0], &|_| None),
                    3 + source as u16 * 6 + d as u16
                );
                assert_eq!(rng.next(), expected.next());
            }
        }
    }
}
