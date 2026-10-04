//! Registered placement modifiers with shared selector budgets and a local live
//! heightmap. Each anchor owns its overlay, preserving parallel MCA/chunk parity.
use super::spatial::Map;
use super::*;

#[derive(Clone, Deserialize)]
#[serde(untagged)]
pub enum IntProvider {
    Value(i32),
    Config(IntConfig),
}
impl Default for IntProvider {
    fn default() -> Self {
        Self::Value(0)
    }
}
#[derive(Clone, Deserialize)]
#[serde(tag = "type", rename_all = "snake_case")]
pub enum IntConfig {
    Constant {
        value: i32,
    },
    Uniform {
        #[serde(alias = "min")]
        min_inclusive: i32,
        #[serde(alias = "max")]
        max_inclusive: i32,
    },
    BiasedToBottom {
        min_inclusive: i32,
        max_inclusive: i32,
    },
    Trapezoid {
        min: i32,
        max: i32,
        plateau: i32,
    },
    Clamped {
        source: Box<IntProvider>,
        min_inclusive: i32,
        max_inclusive: i32,
    },
    ClampedNormal {
        mean: f64,
        deviation: f64,
        min_inclusive: i32,
        max_inclusive: i32,
    },
    WeightedList {
        distribution: Vec<WeightedInt>,
    },
}
#[derive(Clone, Deserialize)]
pub struct WeightedInt {
    data: IntProvider,
    weight: u32,
}
impl IntProvider {
    fn sample(&self, rng: &mut Rng) -> i32 {
        match self {
            Self::Value(v) | Self::Config(IntConfig::Constant { value: v }) => *v,
            Self::Config(IntConfig::Uniform {
                min_inclusive: a,
                max_inclusive: b,
            }) => *a + rng.below(b - a + 1),
            Self::Config(IntConfig::BiasedToBottom {
                min_inclusive: a,
                max_inclusive: b,
            }) => {
                let span = rng.below(b - a + 1) + 1;
                *a + rng.below(span)
            }
            Self::Config(IntConfig::Trapezoid { min, max, plateau }) => {
                let range = max - min;
                if *plateau >= range {
                    *min + rng.below(range + 1)
                } else {
                    let low = (range - plateau) / 2;
                    *min + rng.below(low + 1) + rng.below(range - low + 1)
                }
            }
            Self::Config(IntConfig::Clamped {
                source,
                min_inclusive: a,
                max_inclusive: b,
            }) => source.sample(rng).clamp(*a, *b),
            Self::Config(IntConfig::ClampedNormal {
                mean,
                deviation,
                min_inclusive: a,
                max_inclusive: b,
            }) => {
                let gaussian = (-2. * rng.unit().max(f64::MIN_POSITIVE).ln()).sqrt()
                    * (std::f64::consts::TAU * rng.unit()).cos();
                (mean + gaussian * deviation).clamp(*a as f64, *b as f64) as i32
            }
            Self::Config(IntConfig::WeightedList { distribution }) => {
                let total: u64 = distribution.iter().map(|v| v.weight as u64).sum();
                let mut n = rng.next() % total;
                for v in distribution {
                    if n < v.weight as u64 {
                        return v.data.sample(rng);
                    }
                    n -= v.weight as u64;
                }
                unreachable!()
            }
        }
    }
    fn bounds(&self) -> Result<(i32, i32), String> {
        let range = match self {
            Self::Value(v) | Self::Config(IntConfig::Constant { value: v }) => (*v, *v),
            Self::Config(IntConfig::Uniform {
                min_inclusive: a,
                max_inclusive: b,
            })
            | Self::Config(IntConfig::BiasedToBottom {
                min_inclusive: a,
                max_inclusive: b,
            }) => (*a, *b),
            Self::Config(IntConfig::Trapezoid { min, max, plateau }) => {
                if *plateau < 0 {
                    return Err("negative placement plateau".into());
                }
                (*min, *max)
            }
            Self::Config(IntConfig::Clamped {
                source,
                min_inclusive: a,
                max_inclusive: b,
            }) => {
                source.bounds()?;
                (*a, *b)
            }
            Self::Config(IntConfig::ClampedNormal {
                mean,
                deviation,
                min_inclusive: a,
                max_inclusive: b,
            }) => {
                if !mean.is_finite() || !deviation.is_finite() || *deviation < 0. {
                    return Err("invalid placement normal distribution".into());
                }
                (*a, *b)
            }
            Self::Config(IntConfig::WeightedList { distribution }) => {
                if distribution.is_empty() || distribution.iter().all(|v| v.weight == 0) {
                    return Err("empty placement distribution".into());
                }
                let ranges = distribution
                    .iter()
                    .filter(|v| v.weight > 0)
                    .map(|v| v.data.bounds())
                    .collect::<Result<Vec<_>, _>>()?;
                (
                    ranges.iter().map(|v| v.0).min().unwrap(),
                    ranges.iter().map(|v| v.1).max().unwrap(),
                )
            }
        };
        if range.0 > range.1 || range.0 < -4096 || range.1 > 4096 {
            return Err("invalid decoration integer range".into());
        }
        Ok(range)
    }
}
#[derive(Clone, Deserialize)]
#[serde(tag = "type", rename_all = "snake_case")]
pub enum Predicate {
    Material { offset: [i32; 3], allowed: Vec<u16> },
    AllOf { predicates: Vec<Predicate> },
    AnyOf { predicates: Vec<Predicate> },
    Not { predicate: Box<Predicate> },
    True,
}
impl Predicate {
    fn validate(&self, palette: usize) -> bool {
        match self {
            Self::Material { offset, allowed } => {
                offset.iter().all(|v| (-17..=17).contains(v))
                    && allowed.iter().all(|v| (*v as usize) < palette)
                    && allowed.windows(2).all(|v| v[0] < v[1])
            }
            Self::AllOf { predicates } | Self::AnyOf { predicates } => {
                predicates.iter().all(|p| p.validate(palette))
            }
            Self::Not { predicate } => predicate.validate(palette),
            Self::True => true,
        }
    }
    fn test(
        &self,
        at: [i32; 3],
        field: &Field,
        profile: &WorldProfile,
        request: ChunkRequest,
        overlay: &Overlay,
    ) -> Option<bool> {
        match self {
            Self::Material { offset, allowed } => overlay
                .material(
                    field,
                    profile,
                    request,
                    std::array::from_fn(|i| at[i] + offset[i]),
                )
                .map(|m| allowed.binary_search(&m).is_ok()),
            Self::AllOf { predicates } | Self::AnyOf { predicates } => {
                let all = matches!(self, Self::AllOf { .. });
                let mut unknown = false;
                for predicate in predicates {
                    match predicate.test(at, field, profile, request, overlay) {
                        Some(value) if value != all => return Some(value),
                        None => unknown = true,
                        _ => {}
                    }
                }
                (!unknown).then_some(all)
            }
            Self::Not { predicate } => predicate
                .test(at, field, profile, request, overlay)
                .map(|v| !v),
            Self::True => Some(true),
        }
    }
}
#[derive(Clone, Deserialize)]
#[serde(tag = "type", rename_all = "snake_case")]
pub enum Modifier {
    Count {
        count: IntProvider,
    },
    NoiseThresholdCount {
        noise_level: f64,
        below_noise: i32,
        above_noise: i32,
    },
    NoiseBasedCount {
        noise_to_count_ratio: i32,
        noise_factor: f64,
        #[serde(default)]
        noise_offset: f64,
    },
    RarityFilter {
        chance: u32,
    },
    InSquare,
    Heightmap {
        map: u8,
    },
    Offset {
        #[serde(default)]
        x: IntProvider,
        #[serde(default)]
        y: IntProvider,
        #[serde(default)]
        z: IntProvider,
    },
    BlockPredicateFilter {
        predicate: Predicate,
    },
    SurfaceWaterDepthFilter {
        max_water_depth: i32,
    },
    Biome,
    Select {
        min: f64,
        max: f64,
    },
}
impl Modifier {
    pub(crate) fn noise_rule(&self) -> Option<counts::Rule> {
        match self {
            Self::NoiseThresholdCount {
                noise_level,
                below_noise,
                above_noise,
            } => Some(counts::Rule::threshold(
                *noise_level,
                *below_noise,
                *above_noise,
            )),
            Self::NoiseBasedCount {
                noise_to_count_ratio,
                noise_factor,
                noise_offset,
            } => Some(counts::Rule::based(
                *noise_to_count_ratio,
                *noise_factor,
                *noise_offset,
            )),
            _ => None,
        }
    }
    pub(crate) fn requires_registered_noise(&self) -> bool {
        matches!(self, Self::NoiseBasedCount { .. })
            || matches!(self, Self::NoiseThresholdCount { noise_level, .. } if (*noise_level + 0.8).abs() >= 0.00001)
    }
}
pub(super) fn validate(program: &[Modifier], palette: usize) -> Result<(), String> {
    for op in program {
        let valid = match op {
            Modifier::Count { count } => {
                let (a, b) = count.bounds()?;
                a >= 0 && b <= 4096
            }
            Modifier::Offset { x, y, z } => [x, y, z].into_iter().all(|p| p.bounds().is_ok()),
            Modifier::NoiseThresholdCount {
                noise_level,
                below_noise,
                above_noise,
            } => {
                noise_level.is_finite()
                    && (*noise_level as f32).is_finite()
                    && *below_noise >= 0
                    && *above_noise >= 0
            }
            Modifier::NoiseBasedCount {
                noise_factor,
                noise_offset,
                ..
            } => {
                noise_factor.is_finite()
                    && (*noise_factor as f32).is_finite()
                    && *noise_factor as f32 != 0.0
                    && noise_offset.is_finite()
                    && (*noise_offset as f32).is_finite()
            }
            Modifier::RarityFilter { chance } => *chance > 0,
            Modifier::Heightmap { map } => *map < 6,
            Modifier::BlockPredicateFilter { predicate } => predicate.validate(palette),
            Modifier::Select { min, max } => {
                min.is_finite() && max.is_finite() && *min >= 0. && *max <= 1.00000001 && min <= max
            }
            _ => true,
        };
        if !valid {
            return Err("invalid decoration placement modifier".into());
        }
    }
    Ok(())
}
#[derive(Clone)]
enum Action {
    Shift([i32; 3]),
    Modifier { index: usize, parents: usize },
}
pub(super) struct Candidate {
    pub recipe: u32,
    pub group: u32,
    pub order: Vec<usize>,
    pub seed: u64,
    origin: [i32; 3],
    actions: Vec<Action>,
}
pub(super) fn expand(
    program: &[Modifier],
    recipe: u32,
    group: u32,
    origin: [i32; 3],
    rng: Rng,
    field: &Field,
    counts: Option<&counts::Counts>,
    output: Option<&mut Vec<Candidate>>,
    queries: &mut Vec<counts::Query>,
) {
    struct Walker<'a> {
        program: &'a [Modifier],
        recipe: u32,
        group: u32,
        origin: [i32; 3],
        field: &'a Field,
        counts: Option<&'a counts::Counts>,
        output: Option<&'a mut Vec<Candidate>>,
        queries: &'a mut Vec<counts::Query>,
    }
    impl Walker<'_> {
        fn step(
            &mut self,
            index: usize,
            at: [i32; 3],
            mut rng: Rng,
            actions: &mut Vec<Action>,
            order: &mut Vec<usize>,
        ) {
            if self.output.is_none()
                && !self.program[index..]
                    .iter()
                    .any(|op| op.noise_rule().is_some())
            {
                return;
            }
            if index == self.program.len() {
                if let Some(output) = self.output.as_mut() {
                    output.push(Candidate {
                        recipe: self.recipe,
                        group: self.group,
                        order: order.clone(),
                        seed: rng.0,
                        origin: self.origin,
                        actions: actions.clone(),
                    });
                }
                return;
            }
            let mut point = at;
            let before = actions.len();
            match &self.program[index] {
                Modifier::Count { count } => {
                    for i in 0..count.sample(&mut rng).max(0) as usize {
                        let child = Rng::new(rng.next());
                        order.push(i);
                        self.step(index + 1, at, child, actions, order);
                        order.pop();
                    }
                    return;
                }
                Modifier::NoiseThresholdCount { .. } | Modifier::NoiseBasedCount { .. } => {
                    let rule = self.program[index].noise_rule().unwrap();
                    let count = if let Some(counts) = self.counts {
                        let Some(count) = counts.get(rule, at[0], at[2]) else {
                            self.queries.push(counts::Query {
                                x: at[0],
                                z: at[2],
                                rule,
                            });
                            return;
                        };
                        count
                    } else if let Modifier::NoiseThresholdCount {
                        below_noise,
                        above_noise,
                        ..
                    } = self.program[index]
                    {
                        // Saved profiles without the new permutation keep their old bit-field rules.
                        let Some(column) = self.field.column(at[0], at[2]) else {
                            return;
                        };
                        if column.packed & (1 << 27) != 0 {
                            below_noise
                        } else {
                            above_noise
                        }
                    } else {
                        panic!("noise-based placement requires a prepared GPU count batch");
                    };
                    for i in 0..count.max(0) as usize {
                        let child = Rng::new(rng.next());
                        order.push(i);
                        self.step(index + 1, at, child, actions, order);
                        order.pop();
                    }
                    return;
                }
                Modifier::RarityFilter { chance } => {
                    if rng.unit() >= 1.0 / *chance as f64 {
                        return;
                    }
                }
                Modifier::Select { min, max } => {
                    let n = rng.unit();
                    if n < *min || n >= *max {
                        return;
                    }
                }
                Modifier::InSquare => {
                    let offset = [rng.below(16), 0, rng.below(16)];
                    point = std::array::from_fn(|i| at[i] + offset[i]);
                    actions.push(Action::Shift(offset));
                }
                Modifier::Offset { x, y, z } => {
                    let offset = [x.sample(&mut rng), y.sample(&mut rng), z.sample(&mut rng)];
                    point = std::array::from_fn(|i| at[i] + offset[i]);
                    actions.push(Action::Shift(offset));
                }
                _ => actions.push(Action::Modifier {
                    index,
                    parents: order.len(),
                }),
            }
            self.step(index + 1, point, rng, actions, order);
            actions.truncate(before);
        }
    }
    Walker {
        program,
        recipe,
        group,
        origin,
        field,
        counts,
        output,
        queries,
    }
    .step(0, origin, rng, &mut Vec::new(), &mut Vec::new());
}
pub(super) type EvaluationCache = Map<(u32, usize, Vec<usize>), Option<[i32; 3]>>;

pub(super) fn position(
    candidate: &Candidate,
    recipe: &Recipe,
    field: &Field,
    profile: &WorldProfile,
    request: ChunkRequest,
    overlay: &Overlay,
    cache: &mut EvaluationCache,
) -> Option<[i32; 3]> {
    let mut at = candidate.origin;
    let program = recipe.placement.as_ref()?;
    for action in &candidate.actions {
        match action {
            Action::Shift(offset) => at = std::array::from_fn(|i| at[i] + offset[i]),
            Action::Modifier { index, parents } => {
                // Modifiers before a nested count execute once for the parent,
                // before any of its children place blocks. Later children inherit
                // that position/filter outcome even as the live canopy changes.
                let key = (*parents < candidate.order.len()).then(|| {
                    (
                        candidate.group,
                        *index,
                        candidate.order[..*parents].to_vec(),
                    )
                });
                if let Some(value) = key.as_ref().and_then(|key| cache.get(key)) {
                    at = (*value)?;
                    continue;
                }
                let evaluated = (|| {
                    match &program[*index] {
                        Modifier::Heightmap { map } => {
                            at[1] = overlay.height(field, profile, request, at[0], at[2], *map)?;
                        }
                        Modifier::BlockPredicateFilter { predicate } => {
                            if predicate.test(at, field, profile, request, overlay) != Some(true) {
                                return None;
                            }
                        }
                        Modifier::Biome => {
                            if !profile.biomes[field.column(at[0], at[2])?.biome()]
                                .decorations
                                .contains(&candidate.recipe)
                            {
                                return None;
                            }
                        }
                        Modifier::SurfaceWaterDepthFilter { max_water_depth } => {
                            // Ordinals exported by the game: OCEAN_FLOOR=3, WORLD_SURFACE=1.
                            if overlay.height(field, profile, request, at[0], at[2], 1)?
                                - overlay.height(field, profile, request, at[0], at[2], 3)?
                                > *max_water_depth
                            {
                                return None;
                            }
                        }
                        _ => unreachable!(),
                    }
                    Some(at)
                })();
                if let Some(key) = key {
                    cache.insert(key, evaluated);
                }
                at = evaluated?;
            }
        }
    }
    (at[1] >= request.min_y && at[1] < request.min_y + request.height as i32).then_some(at)
}
#[derive(Default)]
pub(super) struct Overlay {
    blocks: Map<[i32; 3], u16>,
    tops: Map<(i32, i32), [i32; 6]>,
}
impl Overlay {
    pub(super) fn material(
        &self,
        field: &Field,
        profile: &WorldProfile,
        request: ChunkRequest,
        at: [i32; 3],
    ) -> Option<u16> {
        self.blocks.get(&at).copied().or_else(|| {
            field
                .column(at[0], at[2])
                .map(|c| c.material(at[1], request.min_y, Some(profile)))
        })
    }
    fn height(
        &self,
        field: &Field,
        profile: &WorldProfile,
        request: ChunkRequest,
        x: i32,
        z: i32,
        map: u8,
    ) -> Option<i32> {
        let column = field.column(x, z)?;
        let mask = 1u8 << map;
        let mut y = column.surface_height(Some(profile));
        while y > request.min_y
            && profile.heightmap_masks
                [column.material(y - 1, request.min_y, Some(profile)) as usize]
                & mask
                == 0
        {
            y -= 1;
        }
        if let Some(tops) = self.tops.get(&(x, z)) {
            y = y.max(tops[map as usize].saturating_add(1));
        }
        Some(y)
    }
    fn write(&mut self, profile: &WorldProfile, at: [i32; 3], material: u16) {
        self.blocks.insert(at, material);
        let masks = profile.heightmap_masks[material as usize];
        let tops = self.tops.entry((at[0], at[2])).or_insert([i32::MIN; 6]);
        for (map, top) in tops.iter_mut().enumerate() {
            if masks & (1 << map) != 0 {
                *top = (*top).max(at[1]);
            }
        }
    }
    pub(super) fn commit(
        &mut self,
        blocks: &[WorldBlock],
        start: usize,
        field: &Field,
        profile: &WorldProfile,
        request: ChunkRequest,
    ) {
        for role in [SOIL, LOG, LEAF, COCOA, VINE, PLANT] {
            for b in blocks[start..].iter().filter(|b| b.role == role) {
                if b.y < request.min_y || b.y >= request.min_y + request.height as i32 {
                    continue;
                }
                let at = [b.x, b.y, b.z];
                let Some(previous) = self.material(field, profile, request, at) else {
                    continue;
                };
                let flags = profile.material_flags[previous as usize];
                let replaces = match role {
                    SOIL => flags & 1 != 0 || previous == profile.snow,
                    LOG => previous == 0 || flags & (4 | 16) != 0,
                    _ => previous == 0,
                };
                if !replaces {
                    continue;
                }
                if b.upper != 0 && role == PLANT {
                    let upper = [b.x, b.y + 1, b.z];
                    if b.y + 1 >= request.min_y + request.height as i32
                        || self.material(field, profile, request, upper) != Some(0)
                    {
                        continue;
                    }
                    self.write(profile, upper, b.upper);
                }
                self.write(profile, at, b.material);
            }
        }
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use serde_json::json;

    fn fixture() -> (WorldProfile, Field, ChunkRequest, Recipe) {
        let mut profile: WorldProfile = serde_json::from_value(json!({
            "biome_scale":256,"blend":0.55,"sea_level":63,
            "stone":1,"water":7,"bedrock":1,"deepslate":1,"snow":1,"ice":7,
            "materials":["air","stone","grass","dirt","log","leaves","plant","water"],
            "biomes":[{"id":"test:forest","climate":[0,0,0,0],"terrain":[0,0,0],
                "top":2,"filler":3,"underwater":3,"flags":0,"decorations":[0]}],
            "noises":[],"material_flags":[0,0,1,1,8,4,16,0],
            "heightmap_masks":[0,63,63,63,63,31,3,19]
        }))
        .unwrap();
        let field = Field {
            origin_x: -1,
            origin_z: -1,
            side: 3,
            columns: vec![
                Column {
                    height: 64,
                    materials: 2 | (3 << 16),
                    packed: 3 << 24
                };
                9 * COLUMNS
            ],
        };
        let request = ChunkRequest {
            seed: 42,
            chunk_x: 0,
            chunk_z: 0,
            min_y: 0,
            height: 128,
            base_height: 64.,
            amplitude: 0.,
            frequency: 0.008,
            reserved: 0,
        };
        let recipe: Recipe=serde_json::from_value(json!({
            "source":"test:forest","salt":0,"placement_salt":0,"density":155,
            "low_density":155,"noise_count":false,"rarity":1,"tries":1,"spread":[0,0,0],
            "placement":[{"type":"count","count":155},{"type":"in_square"},
                {"type":"heightmap","map":1},{"type":"block_predicate_filter",
                "predicate":{"type":"material","offset":[0,-1,0],"allowed":[2,3]}},{"type":"biome"}],
            "kind":"tree","trunk_shape":"straight_trunk_placer","foliage_shape":"blob_foliage_placer",
            "height":[5,0,0],"radius":[3,3],"offset":[0,0],"foliage_height":[3,3],"trunk_height":[0,0],
            "logs":[{"axes":[4,4,4],"weight":1}],"leaves":[{"distances":[5,5,5,5,5,5],"weight":1}],
            "soil":3,"root":0,"root_offset":[0,0]
        })).unwrap();
        profile.decorations.push(recipe.clone());
        (profile, field, request, recipe)
    }
    fn candidates(program: &[Modifier], recipe: u32, field: &Field, seed: u64) -> Vec<Candidate> {
        let mut out = Vec::new();
        expand(
            program,
            recipe,
            0,
            [0, 0, 0],
            Rng::new(seed),
            field,
            None,
            Some(&mut out),
            &mut Vec::new(),
        );
        out
    }
    fn ops(value: serde_json::Value) -> Vec<Modifier> {
        serde_json::from_value(value).unwrap()
    }

    #[test]
    fn selectors_share_the_exact_parent_budget_including_nested_counts() {
        let (_, field, _, _) = fixture();
        for seed in 0..64 {
            let prefix = json!([{"type":"count","count":37},{"type":"in_square"},
                {"type":"count","count":{"type":"uniform","min_inclusive":2,"max_inclusive":4}}]);
            let baseline = candidates(&ops(prefix.clone()), 0, &field, seed);
            let mut selected = Vec::new();
            for (recipe, (min, max)) in [(0., 0.2), (0.2, 0.7), (0.7, 1.)].into_iter().enumerate() {
                let mut branch = prefix.as_array().unwrap().clone();
                branch.push(json!({"type":"select","min":min,"max":max}));
                selected.extend(candidates(&ops(json!(branch)), recipe as u32, &field, seed));
            }
            let mut expected: Vec<_> = baseline.into_iter().map(|c| c.order).collect();
            let mut actual: Vec<_> = selected.into_iter().map(|c| c.order).collect();
            expected.sort();
            actual.sort();
            assert_eq!(actual, expected, "one branch per parent attempt");
        }
    }

    #[test]
    fn count_zero_and_rarity_keep_modifier_order() {
        let (_, field, _, mut recipe) = fixture();
        recipe.placement = Some(ops(
            json!([{"type":"heightmap","map":1},{"type":"count","count":0}]),
        ));
        recipe.tries = 0.;
        recipe.validate(8).unwrap();
        assert!(candidates(recipe.placement.as_ref().unwrap(), 0, &field, 42).is_empty());
        let before = ops(json!([{"type":"rarity_filter","chance":2},{"type":"count","count":80}]));
        let after = ops(json!([{"type":"count","count":80},{"type":"rarity_filter","chance":2}]));
        for seed in 0..64 {
            assert!([0, 80].contains(&candidates(&before, 0, &field, seed).len()));
        }
        assert!((1..80).contains(&candidates(&after, 0, &field, 42).len()));
    }

    #[test]
    fn sparse_noise_queries_preserve_nested_count_streams_and_selector_budgets() {
        let (_, field, _, _) = fixture();
        let program = ops(json!([
            {"type":"count","count":7},{"type":"in_square"},
            {"type":"noise_threshold_count","noise_level":0.02,"below_noise":2,"above_noise":3},
            {"type":"in_square"},
            {"type":"noise_based_count","noise_to_count_ratio":-8,"noise_factor":30,"noise_offset":-0.5},
            {"type":"heightmap","map":1}
        ]));
        validate(&program, 8).unwrap();
        let run = |program: &[Modifier], counts: Option<&counts::Counts>, output: bool| {
            let mut found = Vec::new();
            let mut queries = Vec::new();
            expand(
                program,
                0,
                0,
                [0, 0, 0],
                Rng::new(847),
                &field,
                counts,
                output.then_some(&mut found),
                &mut queries,
            );
            (found, queries)
        };
        let mut counts = counts::Counts::default();
        let (_, first) = run(&program, Some(&counts), false);
        assert_eq!(first.len(), 7);
        assert!(
            first
                .iter()
                .all(|q| q.rule == program[2].noise_rule().unwrap())
        );
        counts.extend(first.into_iter().map(|q| (q, 2)));
        let (_, second) = run(&program, Some(&counts), false);
        assert_eq!(second.len(), 14);
        assert!(
            second
                .iter()
                .all(|q| q.rule == program[4].noise_rule().unwrap())
        );
        counts.extend(second.into_iter().map(|q| (q, 3)));
        assert!(run(&program, Some(&counts), false).1.is_empty());
        let (actual, missing) = run(&program, Some(&counts), true);
        assert!(missing.is_empty());
        let mut baseline = program.clone();
        baseline[2] = Modifier::Count {
            count: IntProvider::Value(2),
        };
        baseline[4] = Modifier::Count {
            count: IntProvider::Value(3),
        };
        let (expected, _) = run(&baseline, None, true);
        assert_eq!(actual.len(), 42);
        assert_eq!(
            actual
                .iter()
                .map(|c| (&c.order, c.seed))
                .collect::<Vec<_>>(),
            expected
                .iter()
                .map(|c| (&c.order, c.seed))
                .collect::<Vec<_>>()
        );
        // One selected branch per parent, after both spatial counts.
        let mut selected = Vec::new();
        for (id, (min, max)) in [(0., 0.25), (0.25, 0.8), (0.8, 1.)].into_iter().enumerate() {
            let mut branch = program.clone();
            branch.push(Modifier::Select { min, max });
            let mut missing = Vec::new();
            expand(
                &branch,
                id as u32,
                0,
                [0, 0, 0],
                Rng::new(847),
                &field,
                Some(&counts),
                Some(&mut selected),
                &mut missing,
            );
            assert!(missing.is_empty());
        }
        let mut expected: Vec<_> = actual.into_iter().map(|c| c.order).collect();
        let mut actual: Vec<_> = selected.into_iter().map(|c| c.order).collect();
        expected.sort();
        actual.sort();
        assert_eq!(actual, expected);
    }

    #[test]
    fn live_heightmaps_and_soil_filters_reject_canopy_without_density_caps() {
        let (profile, field, request, recipe) = fixture();
        let program = recipe.placement.as_ref().unwrap();
        assert_eq!(candidates(program, 0, &field, 42).len(), 155);
        let modern = super::super::anchors(&field, &profile, request, 0, 0, None);
        let mut legacy_profile = profile.clone();
        legacy_profile.decorations[0].placement = None;
        let legacy = super::super::anchors(&field, &legacy_profile, request, 0, 0, None);
        let roots =
            |blocks: &[WorldBlock]| blocks.iter().filter(|b| b.role == LOG && b.y == 64).count();
        assert!(
            roots(&modern) > 0 && roots(&modern) < roots(&legacy) / 2,
            "live canopy must reject requested attempts: modern={}, legacy={}",
            roots(&modern),
            roots(&legacy)
        );
        let mut overlay = Overlay::default();
        overlay.write(&profile, [8, 69, 8], 5);
        assert_eq!(overlay.height(&field, &profile, request, 8, 8, 1), Some(70));
        assert_eq!(overlay.height(&field, &profile, request, 8, 8, 5), Some(64));
        let soil = Predicate::Material {
            offset: [0, -1, 0],
            allowed: vec![2, 3],
        };
        assert_eq!(
            soil.test([8, 70, 8], &field, &profile, request, &overlay),
            Some(false)
        );
        assert_eq!(
            soil.test([8, 64, 8], &field, &profile, request, &overlay),
            Some(true)
        );
        let outside = Predicate::Not {
            predicate: Box::new(soil),
        };
        assert_eq!(
            outside.test([1000, 64, 8], &field, &profile, request, &overlay),
            None
        );
        let any = Predicate::AnyOf {
            predicates: vec![outside.clone(), Predicate::True],
        };
        assert_eq!(
            any.test([1000, 64, 8], &field, &profile, request, &overlay),
            Some(true)
        );
        let all = Predicate::AllOf {
            predicates: vec![
                outside,
                Predicate::Not {
                    predicate: Box::new(Predicate::True),
                },
            ],
        };
        assert_eq!(
            all.test([1000, 64, 8], &field, &profile, request, &overlay),
            Some(false)
        );
    }

    #[test]
    fn registered_offsets_and_water_depth_use_the_selected_heightmap() {
        let (profile, mut field, request, mut recipe) = fixture();
        recipe.placement = Some(ops(json!([{"type":"offset","x":8,"z":8},
            {"type":"heightmap","map":1},{"type":"offset","y":1},
            {"type":"block_predicate_filter","predicate":{"type":"material","offset":[0,-2,0],"allowed":[2]}}])));
        let c = candidates(recipe.placement.as_ref().unwrap(), 0, &field, 42)
            .pop()
            .unwrap();
        assert_eq!(
            position(
                &c,
                &recipe,
                &field,
                &profile,
                request,
                &Overlay::default(),
                &mut EvaluationCache::default()
            ),
            Some([8, 65, 8])
        );
        field.columns.fill(Column {
            height: 60,
            materials: 2 | (3 << 16),
            packed: 3 << 24,
        });
        for (depth, expected) in [(1, None), (3, Some([8, 63, 8]))] {
            recipe.placement = Some(ops(json!([{"type":"offset","x":8,"z":8},
                {"type":"heightmap","map":1},{"type":"surface_water_depth_filter","max_water_depth":depth}])));
            let c = candidates(recipe.placement.as_ref().unwrap(), 0, &field, 42)
                .pop()
                .unwrap();
            assert_eq!(
                position(
                    &c,
                    &recipe,
                    &field,
                    &profile,
                    request,
                    &Overlay::default(),
                    &mut EvaluationCache::default()
                ),
                expected
            );
        }
    }

    #[test]
    fn nested_attempts_inherit_parent_height_while_outer_attempts_resample_canopy() {
        let (profile, field, request, mut recipe) = fixture();
        for (program, expected) in [
            (
                json!([{"type":"heightmap","map":1},{"type":"count","count":2}]),
                64,
            ),
            (
                json!([{"type":"count","count":2},{"type":"heightmap","map":1}]),
                70,
            ),
        ] {
            recipe.placement = Some(ops(program));
            let candidates = candidates(recipe.placement.as_ref().unwrap(), 0, &field, 42);
            let mut overlay = Overlay::default();
            let mut cache = EvaluationCache::default();
            assert_eq!(
                position(
                    &candidates[0],
                    &recipe,
                    &field,
                    &profile,
                    request,
                    &overlay,
                    &mut cache
                ),
                Some([0, 64, 0])
            );
            overlay.write(&profile, [0, 69, 0], 5);
            assert_eq!(
                position(
                    &candidates[1],
                    &recipe,
                    &field,
                    &profile,
                    request,
                    &overlay,
                    &mut cache
                ),
                Some([0, expected, 0])
            );
        }
        let normal: IntProvider = serde_json::from_value(json!({"type":"clamped_normal","mean":4.9,"deviation":0,"min_inclusive":0,"max_inclusive":10})).unwrap();
        assert_eq!(
            normal.sample(&mut Rng::new(42)),
            4,
            "Minecraft truncates normal integer samples rather than rounding"
        );
    }
}
