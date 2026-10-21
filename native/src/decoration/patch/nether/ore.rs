//! Registered vein/scattered geometry and ordered replacement rules on the live feature overlay.
use super::*;

#[derive(Clone, Deserialize)]
pub struct Ore {
    targets: Vec<Target>,
    size: i32,
    discard: f32,
    scattered: bool,
    heightmap: u8,
    air: Vec<u16>,
}

#[derive(Clone, Deserialize)]
struct Target {
    rule: Rule,
    state: u16,
}

#[derive(Clone, Deserialize)]
#[serde(tag = "kind", rename_all = "snake_case")]
enum Rule {
    AlwaysTrue,
    Material {
        allowed: Vec<u16>,
        chance: Option<f32>,
    },
    HeightMatch {
        min: i32,
        max: i32,
    },
    AllOf {
        rules: Vec<Rule>,
    },
    AnyOf {
        rules: Vec<Rule>,
    },
    Not {
        rule: Box<Rule>,
    },
}
impl Rule {
    fn validate(&self, palette: usize) -> bool {
        match self {
            Self::AlwaysTrue | Self::HeightMatch { .. } => true,
            Self::Material { allowed, chance } => {
                allowed.iter().all(|&id| (id as usize) < palette)
                    && chance.is_none_or(f32::is_finite)
            }
            Self::AllOf { rules } | Self::AnyOf { rules } => {
                rules.iter().all(|r| r.validate(palette))
            }
            Self::Not { rule } => rule.validate(palette),
        }
    }
    fn test(&self, state: u16, y: i32, rng: &mut Rng) -> bool {
        match self {
            Self::AlwaysTrue => true,
            Self::Material { allowed, chance } => {
                allowed.contains(&state) && chance.is_none_or(|p| (rng.unit() as f32) < p)
            }
            Self::HeightMatch { min, max } => y >= *min && y <= *max,
            Self::AllOf { rules } => rules.iter().all(|r| r.test(state, y, rng)),
            Self::AnyOf { rules } => rules.iter().any(|r| r.test(state, y, rng)),
            Self::Not { rule } => !rule.test(state, y, rng),
        }
    }
}

/// The terrain check sits between these two draw stages in actual replay.
struct Vein {
    size: i32,
    from: [f64; 3],
    to: [f64; 3],
    start: [i32; 3],
    width: i32,
    height: i32,
}
impl Vein {
    fn new(size: i32, at: [i32; 3], rng: &mut Rng) -> Self {
        let angle = (rng.unit() as f32) * std::f32::consts::PI;
        let spread = size as f32 / 8.0;
        let radius = ((size as f32 / 16.0 * 2.0 + 1.0) / 2.0).ceil() as i32;
        let dx = (angle as f64).sin() * spread as f64;
        let dz = (angle as f64).cos() * spread as f64;
        let from = [
            at[0] as f64 + dx,
            (at[1] + rng.below(3) - 2) as f64,
            at[2] as f64 + dz,
        ];
        let to = [
            at[0] as f64 - dx,
            (at[1] + rng.below(3) - 2) as f64,
            at[2] as f64 - dz,
        ];
        let start = [
            at[0] - spread.ceil() as i32 - radius,
            at[1] - 2 - radius,
            at[2] - spread.ceil() as i32 - radius,
        ];
        let width = 2 * (spread.ceil() as i32 + radius);
        let height = 2 * (2 + radius);
        Self {
            size,
            from,
            to,
            start,
            width,
            height,
        }
    }
    fn spheres<'a>(&'a self, rng: &'a mut Rng) -> impl Iterator<Item = ([f64; 3], f64)> + 'a {
        (0..self.size).map(move |i| {
            let step = i as f32 / self.size as f32;
            let center = std::array::from_fn::<_, 3, _>(|axis| {
                self.from[axis] + step as f64 * (self.to[axis] - self.from[axis])
            });
            let size = rng.unit() * self.size as f64 / 16.0;
            let radius =
                (((minecraft_sin(std::f32::consts::PI * step) + 1.0) as f64) * size + 1.0) / 2.0;
            (center, radius)
        })
    }
    fn bounds(&self, center: [f64; 3], radius: f64) -> ([i32; 3], [i32; 3]) {
        let min = std::array::from_fn::<_, 3, _>(|axis| {
            ((center[axis] - radius).floor() as i32).max(self.start[axis])
        });
        let max = std::array::from_fn::<_, 3, _>(|axis| {
            ((center[axis] + radius).floor() as i32).max(min[axis])
        });
        (min, max)
    }
}

impl Ore {
    pub(super) fn deferred_replacement(&self, profile: &WorldProfile) -> Option<(u16, u16)> {
        let [target] = self.targets.as_slice() else {
            return None;
        };
        let Rule::Material {
            allowed,
            chance: None,
        } = &target.rule
        else {
            return None;
        };
        let [required] = allowed.as_slice() else {
            return None;
        };
        // Nonzero discard and probabilistic rules consume material-dependent
        // RNG draws or inspect neighboring air; those keep eager replacement.
        (self.discard == 0.0
            && profile.heightmap_masks[*required as usize]
                == profile.heightmap_masks[target.state as usize])
            .then_some((*required, target.state))
    }
    /// Conservative X/Z rectangles. Veins share replay geometry; scattered
    /// attempts stay within the radius implied by their sampled count even when
    /// replacements consume extra RNG. Live rejection can only remove writes.
    pub(super) fn ore_footprint(
        &self,
        at: [i32; 3],
        rng: &mut Rng,
        mark: &mut impl FnMut([i32; 2], [i32; 2]) -> bool,
    ) -> Option<bool> {
        if at.iter().any(|&v| v < i32::MIN + 64 || v > i32::MAX - 64) {
            return None;
        }
        if self.scattered {
            let count = self.scatter_count(rng);
            return Some(
                count == 0 || {
                    let reach = scatter_distance(count - 1) as i32;
                    mark(
                        [at[0] - reach, at[2] - reach],
                        [at[0] + reach, at[2] + reach],
                    )
                },
            );
        }
        let vein = Vein::new(self.size, at, rng);
        for (center, radius) in vein.spheres(rng) {
            let (min, max) = vein.bounds(center, radius);
            if !mark([min[0], min[2]], [max[0], max[2]]) {
                return Some(false);
            }
        }
        Some(true)
    }
    pub(super) fn validate(&self, palette: usize) -> bool {
        (0..=64).contains(&self.size)
            && (0.0..=1.0).contains(&self.discard)
            && self.heightmap < 6
            && self.air.iter().all(|&id| (id as usize) < palette)
            && self
                .targets
                .iter()
                .all(|t| (t.state as usize) < palette && t.rule.validate(palette))
    }

    fn scatter_count(&self, rng: &mut Rng) -> i32 {
        rng.below(self.size + 1)
    }

    pub(super) fn place(&self, w: &mut World, at: [i32; 3], rng: &mut Rng) -> bool {
        if self.scattered {
            let count = self.scatter_count(rng);
            for i in 0..count {
                let distance = scatter_distance(i);
                let mut target = at;
                for coordinate in &mut target {
                    let offset = ((rng.unit() as f32) - (rng.unit() as f32)) * distance;
                    // Java Math.round(float), including negative half values.
                    *coordinate += round_offset(offset);
                }
                self.replace(w, target, rng);
            }
            true
        } else {
            self.vein(w, at, rng)
        }
    }

    fn replace(&self, w: &mut World, at: [i32; 3], rng: &mut Rng) -> bool {
        if let Some((required, material)) = w.deferred_ore {
            return w.write_deferred_ore(at, required, material);
        }
        let Some(state) = w.material(at) else {
            return false;
        };
        for target in &self.targets {
            if !target.rule.test(state, at[1], rng) {
                continue;
            }
            let skip_air =
                self.discard <= 0.0 || self.discard < 1.0 && (rng.unit() as f32) >= self.discard;
            if !skip_air
                && DIRECTIONS.iter().any(|&d| {
                    w.material(add(at, d))
                        .is_some_and(|id| self.air.contains(&id))
                })
            {
                continue;
            }
            w.write(at, target.state);
            return true;
        }
        false
    }

    fn vein(&self, w: &mut World, at: [i32; 3], rng: &mut Rng) -> bool {
        let vein = Vein::new(self.size, at, rng);
        let (start, width, height) = (vein.start, vein.width, vein.height);
        let reaches_terrain = (start[0]..=start[0] + width).any(|x| {
            (start[2]..=start[2] + width).any(|z| {
                w.overlay
                    .height(w.field, w.profile, w.request, x, z, self.heightmap)
                    .is_some_and(|h| start[1] <= h)
            })
        });
        if !reaches_terrain {
            return false;
        }

        let mut spheres: Vec<_> = vein.spheres(rng).collect();
        for i in 0..spheres.len() {
            if spheres[i].1 <= 0.0 {
                continue;
            }
            for j in i + 1..spheres.len() {
                if spheres[j].1 <= 0.0 {
                    continue;
                }
                let d =
                    std::array::from_fn::<_, 3, _>(|axis| spheres[i].0[axis] - spheres[j].0[axis]);
                let dr = spheres[i].1 - spheres[j].1;
                if dr * dr > d[0] * d[0] + d[1] * d[1] + d[2] * d[2] {
                    if dr > 0.0 {
                        spheres[j].1 = -1.0;
                    } else {
                        spheres[i].1 = -1.0;
                    }
                }
            }
        }
        let mut tested = vec![0u64; ((width * height * width) as usize).div_ceil(64)];
        let mut placed = false;
        for (center, radius) in spheres {
            if radius < 0.0 {
                continue;
            }
            let (min, max) = vein.bounds(center, radius);
            for x in min[0]..=max[0] {
                let xd = (x as f64 + 0.5 - center[0]) / radius;
                if xd * xd >= 1.0 {
                    continue;
                }
                for y in min[1]..=max[1] {
                    let yd = (y as f64 + 0.5 - center[1]) / radius;
                    if xd * xd + yd * yd >= 1.0 {
                        continue;
                    }
                    for z in min[2]..=max[2] {
                        let zd = (z as f64 + 0.5 - center[2]) / radius;
                        if xd * xd + yd * yd + zd * zd >= 1.0
                            || y < w.request.min_y
                            || y >= w.request.min_y + w.request.height as i32
                        {
                            continue;
                        }
                        let index = (x - start[0]
                            + (y - start[1]) * width
                            + (z - start[2]) * width * height)
                            as usize;
                        let word = index / 64;
                        if word >= tested.len() {
                            tested.resize(word + 1, 0);
                        }
                        let bit = 1u64 << (index % 64);
                        if tested[word] & bit != 0 {
                            continue;
                        }
                        tested[word] |= bit;
                        placed |= self.replace(w, [x, y, z], rng);
                    }
                }
            }
        }
        placed
    }
}

// The difference of two sampled floats lies in [-1, 1], so Java rounding
// cannot move any coordinate farther than this integer distance.
fn scatter_distance(index: i32) -> f32 {
    index.min(7) as f32
}

/// Mth.sin's float-indexed table; the angle itself must round before lookup.
fn minecraft_sin(angle: f32) -> f32 {
    let index = ((angle * 10_430.378) as i32 & 65_535) as f64;
    (index * std::f64::consts::PI * 2.0 / 65_536.0).sin() as f32
}

fn round_offset(offset: f32) -> i32 {
    (offset as f64 + 0.5).floor() as i32
}

#[cfg(test)]
mod tests {
    use super::round_offset;

    #[test]
    fn deferred_replacement_excludes_rng_air_and_height_changes() {
        let (mut profile, _, _) = crate::decoration::replay_tests::fixture(42, 0);
        let value = serde_json::json!({"targets":[{"rule":{"kind":"material","allowed":[1]},"state":4}],
            "size":33,"discard":0.0,"scattered":false,"heightmap":2,"air":[0]});
        let ore: super::Ore = serde_json::from_value(value.clone()).unwrap();
        assert_eq!(ore.deferred_replacement(&profile), Some((1, 4)));
        for chance in [0.0, 0.5, 1.0] {
            let mut value = value.clone();
            value["targets"][0]["rule"]["chance"] = chance.into();
            let ore: super::Ore = serde_json::from_value(value).unwrap();
            assert_eq!(ore.deferred_replacement(&profile), None);
        }
        let mut changed = value.clone();
        changed["discard"] = 1.0.into();
        assert_eq!(
            serde_json::from_value::<super::Ore>(changed)
                .unwrap()
                .deferred_replacement(&profile),
            None
        );
        let mut changed = value.clone();
        changed["targets"][0]["rule"]["allowed"] = serde_json::json!([1, 2]);
        assert_eq!(
            serde_json::from_value::<super::Ore>(changed)
                .unwrap()
                .deferred_replacement(&profile),
            None
        );
        profile.heightmap_masks[4] ^= 1;
        assert_eq!(ore.deferred_replacement(&profile), None);
    }
    #[test]
    fn deferred_ore_preserves_geometry_rng_and_overlapping_anchor_results() {
        use crate::decoration::{
            DEFERRED_ORE, FEATURE, Rng, placement::Overlay, provider_noise, replay_tests,
        };
        use std::collections::BTreeMap;
        let (profile, field, request) = replay_tests::fixture(42, 0);
        for scattered in [false, true] {
            let ore: super::Ore = serde_json::from_value(serde_json::json!({
                "targets":[{"rule":{"kind":"material","allowed":[1]},"state":4}],
                "size":33,"discard":0.0,"scattered":scattered,"heightmap":2,"air":[0]}))
            .unwrap();
            let run = |deferred: bool| {
                let mut all = Vec::new();
                let mut random_states = Vec::new();
                for anchor in [0, 1] {
                    let mut overlay = Overlay::default();
                    let mut blocks = Vec::new();
                    let samples = provider_noise::Samples::default();
                    let noise = provider_noise::Context::new(&samples);
                    let mut world = super::World {
                        field: &field,
                        profile: &profile,
                        request,
                        overlay: &mut overlay,
                        blocks: &mut blocks,
                        noise: &noise,
                        deferred_ore: deferred.then_some((1, 4)),
                    };
                    for (index, y) in [30, 30, 63, 70, 30].into_iter().enumerate() {
                        let mut rng = Rng::new(42 + index as u64);
                        // The return value is intentionally ignored by a proven
                        // standalone root; selectors must retain eager behavior.
                        ore.place(&mut world, [15 + anchor, y, 8], &mut rng);
                        random_states.push(rng.next());
                    }
                    all.extend(blocks);
                }
                let mut accepted = BTreeMap::new();
                for block in all {
                    let at = [block.x, block.y, block.z];
                    let previous = accepted
                        .get(&at)
                        .copied()
                        .unwrap_or_else(|| field.material(&profile, request, at).unwrap());
                    if block.role == FEATURE
                        || block.role == DEFERRED_ORE && previous == block.upper
                    {
                        accepted.insert(at, block.material);
                    }
                }
                (accepted, random_states)
            };
            let eager = run(false);
            assert!(!eager.0.is_empty());
            assert_eq!(run(true), eager);
        }
    }
    #[test]
    fn rejected_veins_keep_header_only_random_consumption() {
        use crate::decoration::{Rng, placement::Overlay, provider_noise, replay_tests};
        let (profile, field, request) = replay_tests::fixture(42, 0);
        for size in [0, 1, 33, 64] {
            let ore: super::Ore = serde_json::from_value(serde_json::json!({
                "targets":[{"rule":{"kind":"material","allowed":[1],"chance":0.5},"state":4}],
                "size":size,"discard":0.5,"scattered":false,"heightmap":2,"air":[0]}))
            .unwrap();
            let mut overlay = Overlay::default();
            let mut blocks = Vec::new();
            let samples = provider_noise::Samples::default();
            let noise = provider_noise::Context::new(&samples);
            let mut world = super::World {
                field: &field,
                profile: &profile,
                request,
                overlay: &mut overlay,
                blocks: &mut blocks,
                noise: &noise,
                deferred_ore: None,
            };
            let mut rng = Rng::new(71);
            let mut expected = Rng::new(71);
            expected.unit();
            expected.below(3);
            expected.below(3);
            assert!(!ore.place(&mut world, [8, 120, 8], &mut rng));
            assert_eq!(rng.next(), expected.next());
            assert!(blocks.is_empty());
        }
    }
    #[test]
    fn coordinate_edge_footprints_keep_exported_reach() {
        for (scattered, at) in [(false, [i32::MIN, 30, 0]), (false, [0, i32::MAX, 0])] {
            let ore: super::Ore = serde_json::from_value(serde_json::json!({
                "targets":[{"rule":{"kind":"always_true"},"state":4}],"size":33,
                "discard":0.0,"scattered":scattered,"heightmap":2,"air":[0]}))
            .unwrap();
            let mut rng = super::Rng::new(42);
            let before = rng.0;
            assert_eq!(
                ore.ore_footprint(at, &mut rng, &mut |_, _| panic!("fallback must not mark")),
                None
            );
            assert_eq!(rng.0, before);
        }
    }
    #[test]
    fn scattered_offsets_keep_java_float_half_boundaries() {
        for (value, expected) in [
            (0.5f32.next_down(), 0),
            (0.5, 1),
            (0.5f32.next_up(), 1),
            ((-0.5f32).next_down(), -1),
            (-0.5, 0),
            ((-0.5f32).next_up(), 0),
            (6.5f32.next_down(), 6),
            (6.5, 7),
            (-6.5, -6),
        ] {
            assert_eq!(round_offset(value), expected, "{value:?}");
        }
    }
}
