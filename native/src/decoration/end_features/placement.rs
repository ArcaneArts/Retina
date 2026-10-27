//! Shared vanilla End placement stream with live reads between feature leaves.
use super::super::placement::{IntConfig, IntProvider, Modifier, Overlay};
use super::super::*;
use crate::end::{BiomeZoom, DecorationRandom};
fn supported_int(value: &IntProvider) -> bool {
    match value {
        IntProvider::Value(_)
        | IntProvider::Config(IntConfig::Constant { .. } | IntConfig::Uniform { .. }) => true,
        IntProvider::Config(IntConfig::WeightedList { distribution }) => {
            let total: u64 = distribution.iter().map(|v| u64::from(v.weight)).sum();
            total > 0
                && total <= i32::MAX as u64
                && distribution.iter().all(|v| supported_int(&v.data))
        }
        _ => false,
    }
}
fn integer(value: &IntProvider, rng: &mut DecorationRandom) -> i32 {
    match value {
        IntProvider::Value(v) | IntProvider::Config(IntConfig::Constant { value: v }) => *v,
        IntProvider::Config(IntConfig::Uniform {
            min_inclusive,
            max_inclusive,
        }) => min_inclusive + rng.below(max_inclusive - min_inclusive + 1),
        IntProvider::Config(IntConfig::WeightedList { distribution }) => {
            let total: u64 = distribution.iter().map(|v| u64::from(v.weight)).sum();
            let mut n = rng.below(total as i32) as u32;
            for entry in distribution {
                if n < entry.weight {
                    return integer(&entry.data, rng);
                }
                n -= entry.weight;
            }
            unreachable!()
        }
        _ => unreachable!("validated End integer provider"),
    }
}
fn anchor(value: &placement_height::Anchor, r: ChunkRequest) -> i32 {
    let min = r.min_y.max(0);
    let height = r.height.min(128) as i32;
    match value {
        placement_height::Anchor::Absolute(v) => *v,
        placement_height::Anchor::AboveBottom(v) => min + v,
        placement_height::Anchor::BelowTop(v) => min + height - 1 - v,
    }
}
fn height_supported(height: &placement_height::HeightProvider) -> bool {
    matches!(
        height,
        placement_height::HeightProvider::Constant(_)
            | placement_height::HeightProvider::Config(
                placement_height::HeightConfig::Constant { .. }
                    | placement_height::HeightConfig::Uniform { .. }
            )
    )
}
fn sample_height(
    height: &placement_height::HeightProvider,
    random: &mut DecorationRandom,
    request: ChunkRequest,
) -> i32 {
    use placement_height::{HeightConfig as C, HeightProvider as H};
    match height {
        H::Constant(a) | H::Config(C::Constant { value: a }) => anchor(a, request),
        H::Config(C::Uniform {
            min_inclusive,
            max_inclusive,
        }) => {
            let min = anchor(min_inclusive, request);
            let max = anchor(max_inclusive, request);
            if min > max {
                min
            } else {
                min + random.below(max - min + 1)
            }
        }
        _ => unreachable!("validated End height provider"),
    }
}

pub(super) fn validate(program: &[Modifier], biomes: &[u16], palette: usize) -> bool {
    !biomes.is_empty()
        && placement::validate(program, palette).is_ok()
        && program.iter().all(|op| match op {
            Modifier::Count { count } => supported_int(count),
            Modifier::Offset { x, y, z } => {
                supported_int(x) && supported_int(y) && supported_int(z)
            }
            Modifier::Heightmap { .. }
            | Modifier::RarityFilter { .. }
            | Modifier::InSquare
            | Modifier::Biome
            | Modifier::FixedPlacement { .. } => true,
            Modifier::HeightRange { height } => height_supported(height),
            _ => false,
        })
}

pub(super) fn walk(
    program: &[Modifier],
    biomes: &[u16],
    index: u32,
    step: u32,
    origin: [i32; 3],
    field: &Field,
    profile: &WorldProfile,
    request: ChunkRequest,
    overlay: &Overlay,
    output: &mut Vec<WorldBlock>,
    mut emit: impl FnMut([i32; 3], &mut DecorationRandom, &mut Overlay, &mut Vec<WorldBlock>),
) {
    let mut live = overlay.snapshot();
    let mut random = DecorationRandom::for_feature(request.seed, origin[0], origin[2], index, step);
    let zoom = BiomeZoom::new(request.seed);
    let mut stack = vec![(origin, 0usize)];
    let mut modified = Vec::new();
    while let Some((at, index)) = stack.pop() {
        let Some(op) = program.get(index) else {
            emit(at, &mut random, &mut live, output);
            continue;
        };
        modified.clear();
        match op {
            Modifier::Count { count } => {
                for _ in 0..integer(count, &mut random) {
                    modified.push(at);
                }
            }
            Modifier::RarityFilter { chance } => {
                if random.float() < 1.0f32 / *chance as f32 {
                    modified.push(at);
                }
            }
            Modifier::InSquare => {
                modified.push([at[0] + random.below(16), at[1], at[2] + random.below(16)])
            }
            Modifier::Offset { x, y, z } => modified.push([
                at[0] + integer(x, &mut random),
                at[1] + integer(y, &mut random),
                at[2] + integer(z, &mut random),
            ]),
            Modifier::Heightmap { map } => {
                if let Some(y) = live.height(field, profile, request, at[0], at[2], *map) {
                    if y > request.min_y {
                        modified.push([at[0], y, at[2]]);
                    }
                }
            }
            Modifier::HeightRange { height } => {
                modified.push([at[0], sample_height(height, &mut random, request), at[2]])
            }
            Modifier::FixedPlacement { positions } => {
                modified.extend(positions.iter().copied().filter(|p| {
                    p[0].div_euclid(16) == at[0].div_euclid(16)
                        && p[2].div_euclid(16) == at[2].div_euclid(16)
                }))
            }
            Modifier::Biome => {
                let quart = zoom.quart(at);
                let point = quart.map(|v| v * 4);
                if field
                    .biome(profile, point)
                    .is_some_and(|b| biomes.contains(&(b as u16)))
                {
                    modified.push(at);
                }
            }
            _ => unreachable!("validated End placement modifier"),
        }
        // FeaturePlacer invokes terminal features in emission order; other
        // modifiers push children backwards for depth-first shared RNG order.
        if index + 1 == program.len() {
            for &at in &modified {
                emit(at, &mut random, &mut live, output);
            }
        } else {
            stack.extend(modified.iter().rev().map(|&p| (p, index + 1)));
        }
    }
}

/// Exact supported programs whose terminal points stay in the anchor chunk.
pub(super) fn owned(program: &[Modifier]) -> bool {
    let mut low = [0i64; 2];
    let mut high = [0i64; 2];
    for op in program {
        match op {
            Modifier::InSquare => {
                high[0] += 15;
                high[1] += 15;
            }
            Modifier::FixedPlacement { .. } => {
                if low.iter().any(|&v| v < 0) || high.iter().any(|&v| v > 15) {
                    return false;
                }
                low = [0; 2];
                high = [15; 2];
            }
            Modifier::Offset { x, z, .. } => {
                for (i, value) in [x, z].into_iter().enumerate() {
                    let Ok((a, b)) = value.bounds() else {
                        return false;
                    };
                    low[i] += i64::from(a);
                    high[i] += i64::from(b);
                }
            }
            _ => {}
        }
    }
    low.iter().all(|&v| v >= 0) && high.iter().all(|&v| v <= 15)
}

#[cfg(test)]
mod tests {
    use super::*;
    #[test]
    fn metadata_owner_proof_covers_offset_and_fixed_position_programs() {
        let zero = || IntProvider::Value(0);
        let offset = |x, z| Modifier::Offset {
            x: IntProvider::Value(x),
            y: zero(),
            z: IntProvider::Value(z),
        };
        assert!(owned(&[Modifier::InSquare, offset(0, 0)]));
        assert!(!owned(&[Modifier::InSquare, offset(1, 0)]));
        assert!(!owned(&[offset(-1, 0)]));
        assert!(owned(&[offset(1, 0), offset(-1, 0)]));
        assert!(!owned(&[Modifier::InSquare, Modifier::InSquare]));
        assert!(owned(&[
            Modifier::FixedPlacement {
                positions: vec![[-1, 134, 511]]
            },
            offset(0, 0)
        ]));
        assert!(!owned(&[
            offset(16, 0),
            Modifier::FixedPlacement { positions: vec![] }
        ]));
    }
}
