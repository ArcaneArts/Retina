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
        }
    }
    pub(super) fn validate(&self, palette: usize) -> bool {
        match self {
            Self::State { material } => (*material as usize) < palette,
            Self::Weighted { entries } => {
                !entries.is_empty()
                    && entries.iter().any(|v| v.weight > 0)
                    && entries.iter().all(|v| v.provider.validate(palette))
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
        }
    }
    pub(super) fn sample(&self, rng: &mut Rng) -> u16 {
        match self {
            Self::State { material } => *material,
            Self::Weighted { entries } => {
                let mut n = rng.next() % entries.iter().map(|v| v.weight as u64).sum::<u64>();
                for v in entries {
                    if n < v.weight as u64 {
                        return v.provider.sample(rng);
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
                let source = source.sample(rng);
                let variant = variants.iter().find(|v| v.source == source).unwrap();
                variant.states[(values.sample(rng) - variant.minimum) as usize]
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
        Kind::HugeMushroom { mushroom } => {
            super::mushroom::place(mushroom, at, rng, field, profile, request, overlay, blocks)
        }
        Kind::BlockColumn { column } => {
            let mut heights: Vec<i32> =
                column.layers.iter().map(|l| l.height.sample(rng)).collect();
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
            for (layer, height) in column.layers.iter().zip(heights) {
                for _ in 0..height {
                    write(blocks, pos, layer.provider.sample(rng));
                    pos = std::array::from_fn(|i| pos[i] + column.direction[i]);
                }
            }
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
