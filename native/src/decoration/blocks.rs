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
    CopyProperties {
        source: Box<Provider>,
        variants: Vec<CopiedStates>,
    },
    RandomBlock {
        states: Vec<u16>,
    },
    Noise {
        program: u32,
        states: Vec<u16>,
    },
    DualNoise {
        fast: u32,
        slow: u32,
        variety: [u32; 2],
        states: Vec<u16>,
    },
    NoiseThreshold {
        program: u32,
        threshold: f32,
        high_chance: f32,
        default_state: u16,
        low_states: Vec<u16>,
        high_states: Vec<u16>,
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
pub struct CopiedStates {
    pub source: u16,
    pub states: Vec<u16>,
    pub replacements: Vec<[u16; 2]>,
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
    #[serde(default)]
    pub passthrough: bool,
}
impl Provider {
    /// Whether getState may derive its block from the live substrate. Property
    /// transforms preserve that block identity; optional rules still fall
    /// through instead of substituting a current state themselves.
    pub(super) fn reads_current(&self) -> bool {
        self.nullable()
            || match self {
                Self::RandomizedInt { source, .. }
                | Self::Rotated { source, .. }
                | Self::CopyProperties { source, .. } => source.reads_current(),
                Self::Weighted { entries } => entries.iter().any(|v| v.provider.reads_current()),
                Self::RuleBased { fallback, rules } => {
                    rules.iter().any(|r| r.provider.optional_reads_current())
                        || fallback
                            .as_ref()
                            .is_some_and(|p| p.optional_reads_current())
                }
                _ => false,
            }
    }
    fn optional_reads_current(&self) -> bool {
        match self {
            Self::RandomizedInt { source, .. }
            | Self::Rotated { source, .. }
            | Self::CopyProperties { source, .. } => source.reads_current(),
            Self::Weighted { entries } => entries.iter().any(|v| v.provider.reads_current()),
            Self::RuleBased { fallback, rules } => {
                rules.iter().any(|r| r.provider.optional_reads_current())
                    || fallback
                        .as_ref()
                        .is_some_and(|p| p.optional_reads_current())
            }
            _ => false,
        }
    }
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
            Self::CopyProperties { variants, .. } => variants
                .iter()
                .flat_map(|v| v.states.iter().copied())
                .collect(),
            Self::RandomBlock { states }
            | Self::Noise { states, .. }
            | Self::DualNoise { states, .. } => states.clone(),
            Self::NoiseThreshold {
                default_state,
                low_states,
                high_states,
                ..
            } => std::iter::once(*default_state)
                .chain(low_states.iter().copied())
                .chain(high_states.iter().copied())
                .collect(),
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
                source.validate(palette)
                    && source.outputs().iter().all(|id| {
                        source.reads_current()
                            || variants.iter().any(|v| {
                                v.source == *id
                                    && (v.passthrough
                                        || (lo >= v.minimum
                                            && (hi as i64)
                                                < v.minimum as i64 + v.states.len() as i64))
                            })
                    })
                    && variants.iter().all(|v| {
                        (v.source as usize) < palette
                            && !v.states.is_empty()
                            && (!v.passthrough || v.states == [v.source])
                            && v.states.iter().all(|id| (*id as usize) < palette)
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
                source.validate(palette)
                    && direction.is_none_or(|d| d < 6)
                    && source.outputs().iter().all(|id| {
                        source.reads_current() || variants.iter().any(|v| v.source == *id)
                    })
                    && variants
                        .iter()
                        .all(|v| v.states.iter().all(|id| (*id as usize) < palette))
            }
            Self::RandomBlock { states } => states.iter().all(|id| (*id as usize) < palette),
            Self::CopyProperties { source, variants } => {
                source.validate(palette)
                    && source.outputs().iter().all(|id| {
                        source.reads_current() || variants.iter().any(|v| v.source == *id)
                    })
                    && variants.iter().all(|v| {
                        (v.source as usize) < palette
                            && v.states.contains(&v.source)
                            && v.states.iter().all(|id| (*id as usize) < palette)
                            && v.replacements.windows(2).all(|w| w[0][0] < w[1][0])
                            && v.replacements.iter().all(|pair| {
                                (pair[0] as usize) < palette && v.states.contains(&pair[1])
                            })
                    })
            }
            Self::Noise { states, .. } => {
                !states.is_empty() && states.iter().all(|id| (*id as usize) < palette)
            }
            Self::DualNoise {
                variety, states, ..
            } => {
                (1..=64).contains(&variety[0])
                    && variety[0] <= variety[1]
                    && variety[1] <= 64
                    && !states.is_empty()
                    && states.iter().all(|id| (*id as usize) < palette)
            }
            Self::NoiseThreshold {
                threshold,
                high_chance,
                default_state,
                low_states,
                high_states,
                ..
            } => {
                threshold.is_finite()
                    && (-1.0..=1.0).contains(threshold)
                    && high_chance.is_finite()
                    && (0.0..=1.0).contains(high_chance)
                    && !low_states.is_empty()
                    && !high_states.is_empty()
                    && std::iter::once(default_state)
                        .chain(low_states)
                        .chain(high_states)
                        .all(|id| (*id as usize) < palette)
            }
        }
    }
    /// Providers normally use getState, whose nullable rule/random-block result
    /// keeps the current block. SimpleBlockFeature instead uses getOptionalState.
    pub(super) fn sample(
        &self,
        rng: &mut Rng,
        at: [i32; 3],
        material: &impl Fn([i32; 3]) -> Option<u16>,
        noise: &provider_noise::Context,
    ) -> u16 {
        self.sample_optional(rng, at, material, noise)
            .unwrap_or_else(|| material(at).unwrap_or(0))
    }
    pub(super) fn sample_optional(
        &self,
        rng: &mut Rng,
        at: [i32; 3],
        material: &impl Fn([i32; 3]) -> Option<u16>,
        noise: &provider_noise::Context,
    ) -> Option<u16> {
        match self {
            Self::State { material } => Some(*material),
            Self::Weighted { entries } => {
                let mut n = rng.next() % entries.iter().map(|v| v.weight as u64).sum::<u64>();
                for v in entries {
                    if n < v.weight as u64 {
                        return Some(v.provider.sample(rng, at, material, noise));
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
                let state = source.sample(rng, at, material, noise);
                let Some(variant) = variants.iter().find(|v| v.source == state) else {
                    assert!(
                        source.reads_current(),
                        "missing registered integer transform"
                    );
                    return Some(state);
                };
                // Minecraft returns the sampled state without a value draw
                // when the named property is absent or is not an integer.
                Some(if variant.passthrough {
                    state
                } else {
                    variant.states[(values.sample(rng) - variant.minimum) as usize]
                })
            }
            Self::RuleBased { fallback, rules } => {
                for rule in rules {
                    if rule.predicate.test_with(at, material) == Some(true) {
                        if let Some(state) = rule.provider.sample_optional(rng, at, material, noise)
                        {
                            return Some(state);
                        }
                    }
                }
                fallback
                    .as_ref()
                    .and_then(|p| p.sample_optional(rng, at, material, noise))
            }
            Self::Rotated {
                source,
                direction,
                variants,
            } => {
                // Direction.getRandom runs before the nested provider.
                let direction = direction.unwrap_or_else(|| rng.below(6) as usize);
                let state = source.sample(rng, at, material, noise);
                Some(variants.iter().find(|v| v.source == state).map_or_else(
                    || {
                        assert!(
                            source.reads_current(),
                            "missing registered rotation transform"
                        );
                        state
                    },
                    |v| v.states[direction],
                ))
            }
            Self::RandomBlock { states } => {
                (!states.is_empty()).then(|| states[rng.below(states.len() as i32) as usize])
            }
            Self::CopyProperties { source, variants } => {
                let state = source.sample(rng, at, material, noise);
                let current = material(at).unwrap_or(0);
                let Some(variant) = variants.iter().find(|v| v.source == state) else {
                    // An undeclared source comes from the current block,
                    // possibly with changed properties. Copying all its own
                    // compatible properties back restores the current state.
                    assert!(source.reads_current(), "missing registered property copy");
                    return Some(current);
                };
                let variants = &variant.replacements;
                Some(
                    variants
                        .binary_search_by_key(&current, |v| v[0])
                        .map_or(state, |i| variants[i][1]),
                )
            }
            Self::Noise { program, states } => {
                Some(states[noise_index(noise.get(*program, at), states.len())])
            }
            Self::DualNoise {
                fast,
                slow,
                variety,
                states,
            } => {
                let local = (((noise.get(*slow, at) as f64 + 1.0) / 2.0).clamp(0.0, 1.0)
                    * (variety[1] + 1 - variety[0]) as f64
                    + variety[0] as f64) as usize;
                let selected = noise_index(noise.get(*fast, at), local) as i32;
                // Other possible-state samples have no side effects or draws.
                // Evaluate only the slow sample the fast selector actually uses.
                let pos = [
                    at[0].wrapping_add(selected * 54545),
                    at[1],
                    at[2].wrapping_add(selected * 34234),
                ];
                Some(states[noise_index(noise.get(*slow, pos), states.len())])
            }
            Self::NoiseThreshold {
                program,
                threshold,
                high_chance,
                default_state,
                low_states,
                high_states,
            } => Some(if noise.get(*program, at) < *threshold {
                low_states[rng.below(low_states.len() as i32) as usize]
            } else if (rng.unit() as f32) < *high_chance {
                high_states[rng.below(high_states.len() as i32) as usize]
            } else {
                *default_state
            }),
        }
    }
}
fn noise_index(value: f32, count: usize) -> usize {
    (((1.0 + value) / 2.0).clamp(0.0, 0.9999) * count as f32) as usize
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
    noise: &provider_noise::Context,
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
            let material = layer.provider.sample(
                rng,
                pos,
                &|p| {
                    if p[1] < request.min_y || p[1] >= request.min_y + request.height as i32 {
                        return Some(0);
                    }
                    blocks[start..]
                        .iter()
                        .rev()
                        .find(|b| [b.x, b.y, b.z] == p)
                        .map(|b| b.material)
                        .or_else(|| overlay.material(field, profile, request, p))
                },
                noise,
            );
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
    noise: &provider_noise::Context,
) {
    let test = |p: &Predicate, at| p.test(at, field, profile, request, overlay) == Some(true);
    match kind {
        Kind::AttachmentGrowth { growth } => {
            super::attachment::place(growth, at, rng, field, profile, request, overlay, blocks);
        }
        Kind::FallenTree { fallen } => super::fallen::place(
            fallen, at, rng, field, profile, request, overlay, blocks, noise,
        ),
        Kind::HugeMushroom { mushroom } => super::mushroom::place(
            mushroom, at, rng, field, profile, request, overlay, blocks, noise,
        ),
        Kind::BlockColumn { column } => {
            place_column(
                column, at, rng, field, profile, request, overlay, blocks, noise,
            );
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
    fn missing_integer_properties_preserve_source_and_random_stream() {
        let samples = provider_noise::Samples::default();
        let noise = provider_noise::Context::new(&samples);
        let provider: Provider = serde_json::from_str(
            r#"{
            "type":"randomized_int","source":{"type":"random_block","states":[1,2]},
            "values":{"type":"uniform","min_inclusive":1,"max_inclusive":3},
            "variants":[
                {"source":1,"minimum":0,"states":[1],"passthrough":true},
                {"source":2,"minimum":1,"states":[3,4,5]}
            ]}"#,
        )
        .unwrap();
        assert!(provider.validate(6));
        let mut passed = 0;
        let mut sampled = 0;
        for seed in 0..64 {
            let mut rng = Rng::new(seed);
            let mut reference = Rng::new(seed);
            let source = reference.below(2) + 1;
            let expected = if source == 1 {
                passed += 1;
                1
            } else {
                sampled += 1;
                3 + reference.below(3) as u16
            };
            assert_eq!(
                provider.sample(&mut rng, [-17, 11, -33], &|_| Some(0), &noise),
                expected
            );
            assert_eq!(rng.next(), reference.next());
        }
        assert!(passed > 0 && sampled > 0);
        let Provider::RandomizedInt {
            mut variants,
            source,
            values,
        } = provider
        else {
            unreachable!()
        };
        variants[0].states.push(2);
        assert!(
            !Provider::RandomizedInt {
                source,
                values,
                variants
            }
            .validate(6)
        );
    }

    #[test]
    fn property_copies_read_live_position_without_extra_random_draws() {
        let samples = provider_noise::Samples::default();
        let noise = provider_noise::Context::new(&samples);
        let provider: Provider = serde_json::from_str(
            r#"{
            "type":"copy_properties","source":{"type":"random_block","states":[1,2]},
            "variants":[
                {"source":1,"states":[1,3,4],"replacements":[[5,3],[7,4]]},
                {"source":2,"states":[2,6,8],"replacements":[[5,6],[7,8]]}
            ]}"#,
        )
        .unwrap();
        assert!(provider.validate(9));
        for seed in 0..64 {
            for current in [0, 5, 7] {
                let at = [-17, 11, -33];
                let mut rng = Rng::new(seed);
                let mut reference = Rng::new(seed);
                let choice = reference.below(2);
                let expected = match current {
                    5 => [3, 6][choice as usize],
                    7 => [4, 8][choice as usize],
                    _ => choice as u16 + 1,
                };
                assert_eq!(
                    provider.sample(
                        &mut rng,
                        at,
                        &|p| {
                            assert_eq!(p, at);
                            Some(current)
                        },
                        &noise
                    ),
                    expected
                );
                assert_eq!(rng.next(), reference.next());
            }
        }
        let Provider::CopyProperties {
            mut variants,
            source,
        } = provider
        else {
            unreachable!()
        };
        variants[0].replacements.reverse();
        assert!(!Provider::CopyProperties { variants, source }.validate(9));
    }

    #[test]
    fn rules_continue_after_nullable_matches_and_preserve_draws() {
        let samples = provider_noise::Samples::default();
        let noise = provider_noise::Context::new(&samples);
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
                provider.sample_optional(
                    &mut rng,
                    [-17, 8, -33],
                    &|p| Some(if p[1] == 7 { 7 } else { 0 }),
                    &noise
                ),
                Some(chosen)
            );
            assert_eq!(rng.next(), expected.next());
            let mut rng = Rng::new(seed);
            let mut expected = Rng::new(seed);
            assert_eq!(
                provider.sample_optional(&mut rng, [0, 0, 0], &|_| Some(4), &noise),
                None
            );
            assert_eq!(
                provider.sample(&mut rng, [0, 0, 0], &|_| Some(4), &noise),
                4
            );
            assert_eq!(rng.next(), expected.next());
        }
    }

    #[test]
    fn rotations_draw_direction_before_nested_state_and_fixed_direction_draws_nothing() {
        let samples = provider_noise::Samples::default();
        let noise = provider_noise::Context::new(&samples);
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
                    provider.sample(&mut rng, [0, 0, 0], &|_| None, &noise),
                    3 + source as u16 * 6 + d as u16
                );
                assert_eq!(rng.next(), expected.next());
            }
        }
    }
    #[test]
    fn nullable_current_transforms_preserve_identity_and_draw_order() {
        let samples = provider_noise::Samples::default();
        let noise = provider_noise::Context::new(&samples);
        let empty = Provider::RandomBlock { states: vec![] };
        for current in [0, 1, 7] {
            for seed in 0..64 {
                let mut rng = Rng::new(seed);
                let mut reference = Rng::new(seed);
                assert_eq!(
                    empty.sample_optional(&mut rng, [0; 3], &|_| Some(current), &noise),
                    None
                );
                assert_eq!(
                    empty.sample(&mut rng, [0; 3], &|_| Some(current), &noise),
                    current
                );
                assert_eq!(rng.next(), reference.next());
                let rotation = Provider::Rotated {
                    source: Box::new(empty.clone()),
                    direction: None,
                    variants: vec![Rotations {
                        source: 1,
                        states: [2, 3, 4, 5, 6, 7],
                    }],
                };
                let mut rng = Rng::new(seed);
                let mut reference = Rng::new(seed);
                let direction = reference.below(6) as usize;
                assert!(rotation.validate(8));
                let result = rotation.sample(&mut rng, [0; 3], &|_| Some(current), &noise);
                assert_eq!(
                    result,
                    if current == 1 {
                        2 + direction as u16
                    } else {
                        current
                    }
                );
                assert_eq!(rng.next(), reference.next());
                let copied = Provider::CopyProperties {
                    source: Box::new(rotation),
                    variants: vec![],
                };
                let mut rng = Rng::new(seed);
                let mut reference = Rng::new(seed);
                reference.below(6);
                assert!(copied.validate(8));
                assert_eq!(
                    copied.sample(&mut rng, [0; 3], &|_| Some(current), &noise),
                    current
                );
                assert_eq!(rng.next(), reference.next());
                let integer = Provider::RandomizedInt {
                    source: Box::new(empty.clone()),
                    values: IntProvider::Config(placement::IntConfig::Uniform {
                        min_inclusive: 1,
                        max_inclusive: 3,
                    }),
                    variants: vec![IntStates {
                        source: 1,
                        minimum: 1,
                        states: vec![2, 3, 4],
                        passthrough: false,
                    }],
                };
                let mut rng = Rng::new(seed);
                let mut reference = Rng::new(seed);
                assert!(integer.validate(8));
                let expected = if current == 1 {
                    2 + reference.below(3) as u16
                } else {
                    current
                };
                assert_eq!(
                    integer.sample(&mut rng, [0; 3], &|_| Some(current), &noise),
                    expected
                );
                assert_eq!(rng.next(), reference.next());
            }
        }
    }
}
