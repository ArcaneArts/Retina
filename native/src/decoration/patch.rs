//! Registered surface patches with ordered, shared-stream nested feature replay.
//! The resident GPU substrate supplies spatial terrain; Rust handles local writes.
use super::*;
use blocks::{ColumnRecipe, Provider};
use placement::{Modifier, Overlay, Predicate};

#[derive(Clone, Deserialize)]
pub struct SameBlock {
    pub source: u16,
    pub predicate: Predicate,
}
#[derive(Clone, Deserialize)]
pub struct SimpleState {
    pub source: u16,
    pub lower: [u16; 2],
    pub upper: [u16; 2],
    pub survival: Predicate,
    pub upper_allowed: Predicate,
}
#[derive(Clone, Deserialize)]
pub struct Simple {
    pub provider: Provider,
    pub states: Vec<SimpleState>,
    pub water: Predicate,
}
impl Simple {
    pub(super) fn validate(&self, palette: usize) -> bool {
        self.provider.validate(palette)
            && self.water.validate(palette)
            && self
                .provider
                .outputs()
                .iter()
                .all(|id| self.states.iter().any(|s| s.source == *id))
            && self.states.iter().all(|s| {
                (s.source as usize) < palette
                    && s.lower
                        .iter()
                        .chain(&s.upper)
                        .all(|id| (*id as usize) < palette)
                    && s.survival.validate(palette)
                    && s.upper_allowed.validate(palette)
            })
    }
}
#[derive(Clone, Deserialize)]
pub struct Recipe {
    pub ground: Provider,
    pub same_blocks: Vec<SameBlock>,
    pub replaceable: Predicate,
    pub air: Predicate,
    pub sturdy: Predicate,
    pub direction: i32,
    pub depth: placement::IntProvider,
    pub extra_bottom_block_chance: f32,
    pub vertical_range: u32,
    pub vegetation_chance: f32,
    pub xz_radius: placement::IntProvider,
    pub extra_edge_column_chance: f32,
    pub vegetation: Placed,
    pub water_pool: bool,
    pub enclosed: Vec<Predicate>,
    pub waterlogged_states: Vec<[u16; 2]>,
}
#[derive(Clone, Deserialize)]
pub struct Placed {
    pub placement: Vec<Modifier>,
    pub feature: Box<Feature>,
}
#[derive(Clone, Deserialize)]
#[serde(tag = "kind", rename_all = "snake_case")]
pub enum Feature {
    SimpleBlock {
        #[serde(flatten)]
        simple: Simple,
    },
    BlockColumn {
        #[serde(flatten)]
        column: ColumnRecipe,
    },
    VegetationPatch {
        #[serde(flatten)]
        patch: Recipe,
    },
    RandomSelector {
        options: Vec<OptionFeature>,
        default: Placed,
    },
    SimpleSelector {
        features: Vec<Placed>,
    },
    BooleanSelector {
        when_true: Placed,
        when_false: Placed,
    },
    WeightedSelector {
        entries: Vec<WeightedFeature>,
    },
}
#[derive(Clone, Deserialize)]
pub struct OptionFeature {
    pub chance: f32,
    pub placed: Placed,
}
#[derive(Clone, Deserialize)]
pub struct WeightedFeature {
    pub weight: u32,
    pub placed: Placed,
}
impl Placed {
    fn validate(&self, palette: usize) -> bool {
        placement::validate(&self.placement, palette).is_ok()
            && self.placement.iter().all(|m| {
                !matches!(m, Modifier::Biome | Modifier::Select { .. }) && m.noise_rule().is_none()
            })
            && match self.feature.as_ref() {
                Feature::SimpleBlock { simple } => simple.validate(palette),
                Feature::BlockColumn { column } => column.validate(palette),
                Feature::VegetationPatch { patch } => patch.validate(palette),
                Feature::RandomSelector { options, default } => {
                    default.validate(palette)
                        && options
                            .iter()
                            .all(|v| (0.0..=1.0).contains(&v.chance) && v.placed.validate(palette))
                }
                Feature::SimpleSelector { features } => {
                    !features.is_empty() && features.iter().all(|v| v.validate(palette))
                }
                Feature::BooleanSelector {
                    when_true,
                    when_false,
                } => when_true.validate(palette) && when_false.validate(palette),
                Feature::WeightedSelector { entries } => {
                    !entries.is_empty()
                        && entries.iter().any(|v| v.weight > 0)
                        && entries.iter().all(|v| v.placed.validate(palette))
                }
            }
    }
}
impl Recipe {
    pub(super) fn validate(&self, palette: usize) -> bool {
        let chance = |v: f32| v.is_finite() && (0.0..=1.0).contains(&v);
        self.ground.validate(palette)
            && self
                .ground
                .outputs()
                .iter()
                .all(|id| self.same_blocks.iter().any(|v| v.source == *id))
            && self
                .same_blocks
                .iter()
                .all(|v| (v.source as usize) < palette && v.predicate.validate(palette))
            && self.replaceable.validate(palette)
            && self.air.validate(palette)
            && self.sturdy.validate(palette)
            && matches!(self.direction, -1 | 1)
            && self.depth.bounds().is_ok_and(|(a, b)| a >= 1 && b <= 128)
            && self
                .xz_radius
                .bounds()
                .is_ok_and(|(a, b)| a >= 0 && b <= 14)
            && (1..=256).contains(&self.vertical_range)
            && chance(self.extra_bottom_block_chance)
            && chance(self.vegetation_chance)
            && chance(self.extra_edge_column_chance)
            && self.vegetation.validate(palette)
            && (!self.water_pool || self.enclosed.len() == 5)
            && self.enclosed.iter().all(|p| p.validate(palette))
            && self
                .waterlogged_states
                .iter()
                .flatten()
                .all(|id| (*id as usize) < palette)
    }
}
struct World<'a> {
    field: &'a Field,
    profile: &'a WorldProfile,
    request: ChunkRequest,
    overlay: &'a mut Overlay,
    blocks: &'a mut Vec<WorldBlock>,
    noise: &'a provider_noise::Context<'a>,
}
impl World<'_> {
    fn test(&self, p: &Predicate, at: [i32; 3]) -> bool {
        p.test(at, self.field, self.profile, self.request, self.overlay) == Some(true)
    }
    fn material(&self, at: [i32; 3]) -> Option<u16> {
        self.overlay
            .material(self.field, self.profile, self.request, at)
    }
    fn write(&mut self, at: [i32; 3], material: u16) {
        if at[1] < self.request.min_y
            || at[1] >= self.request.min_y + self.request.height as i32
            || self.field.column(at[0], at[2]).is_none()
        {
            return;
        }
        self.blocks.push(WorldBlock {
            x: at[0],
            y: at[1],
            z: at[2],
            material,
            upper: 0,
            role: FEATURE,
        });
        self.overlay.write(self.profile, at, material);
    }
    fn simple(&mut self, simple: &Simple, at: [i32; 3], rng: &mut Rng) -> bool {
        let Some(id) = simple
            .provider
            .sample_optional(rng, at, &|p| self.material(p), self.noise)
        else {
            return false;
        };
        let state = simple.states.iter().find(|s| s.source == id).unwrap();
        if !self.test(&state.survival, at) {
            return false;
        }
        let above = [at[0], at[1] + 1, at[2]];
        let paired = state.upper[0] != 0;
        if paired && !self.test(&state.upper_allowed, above) {
            return false;
        }
        let lower = state.lower[usize::from(self.test(&simple.water, at))];
        let upper = state.upper[usize::from(self.test(&simple.water, above))];
        self.write(at, lower);
        if paired {
            self.write(above, upper);
        }
        true
    }
    fn feature(&mut self, feature: &Feature, at: [i32; 3], rng: &mut Rng) -> bool {
        match feature {
            Feature::SimpleBlock { simple } => self.simple(simple, at, rng),
            Feature::BlockColumn { column } => {
                let start = self.blocks.len();
                let result = blocks::place_column(
                    column,
                    at,
                    rng,
                    self.field,
                    self.profile,
                    self.request,
                    self.overlay,
                    self.blocks,
                    self.noise,
                );
                self.overlay
                    .commit(self.blocks, start, self.field, self.profile, self.request);
                result
            }
            Feature::VegetationPatch { patch } => self.patch(patch, at, rng),
            Feature::RandomSelector { options, default } => {
                for v in options {
                    if (rng.unit() as f32) < v.chance {
                        return self.placed(&v.placed, at, rng, 0);
                    }
                }
                self.placed(default, at, rng, 0)
            }
            Feature::SimpleSelector { features } => self.placed(
                &features[rng.below(features.len() as i32) as usize],
                at,
                rng,
                0,
            ),
            Feature::BooleanSelector {
                when_true,
                when_false,
            } => self.placed(
                if rng.unit() < 0.5 {
                    when_true
                } else {
                    when_false
                },
                at,
                rng,
                0,
            ),
            Feature::WeightedSelector { entries } => {
                let mut n = rng.next() % entries.iter().map(|v| v.weight as u64).sum::<u64>();
                for v in entries {
                    if n < v.weight as u64 {
                        return self.placed(&v.placed, at, rng, 0);
                    }
                    n -= v.weight as u64;
                }
                unreachable!()
            }
        }
    }
    fn placed(&mut self, placed: &Placed, mut at: [i32; 3], rng: &mut Rng, index: usize) -> bool {
        let Some(op) = placed.placement.get(index) else {
            return self.feature(&placed.feature, at, rng);
        };
        match op {
            Modifier::Count { count } => {
                let n = count.sample(rng);
                let mut any = false;
                for _ in 0..n {
                    any |= self.placed(placed, at, rng, index + 1);
                }
                return any;
            }
            Modifier::Cuboid {
                xz_size,
                y_size,
                include_edges,
                include_interior,
            } => {
                let mut any = false;
                for offset in placement::cuboid_offsets(
                    xz_size,
                    y_size,
                    *include_edges,
                    *include_interior,
                    rng,
                ) {
                    any |= self.placed(
                        placed,
                        std::array::from_fn(|axis| at[axis] + offset[axis]),
                        rng,
                        index + 1,
                    );
                }
                return any;
            }
            Modifier::CountOnEveryLayer { count } => {
                let mut layer = 0;
                let mut any = false;
                loop {
                    let mut found = false;
                    let mut i = 0;
                    while i < count.sample(rng) {
                        let x = at[0] + rng.below(16);
                        let z = at[2] + rng.below(16);
                        if let Some(y) = self.overlay.ground_layer(
                            self.field,
                            self.profile,
                            self.request,
                            x,
                            z,
                            layer,
                        ) {
                            found = true;
                            any |= self.placed(placed, [x, y, z], rng, index + 1);
                        }
                        i += 1;
                    }
                    if !found {
                        return any;
                    }
                    layer += 1;
                }
            }
            Modifier::RandomChance { chance } => {
                if (rng.unit() as f32) >= *chance {
                    return false;
                }
            }
            Modifier::RarityFilter { chance } => {
                if (rng.unit() as f32) >= 1.0 / (*chance as f32) {
                    return false;
                }
            }
            Modifier::InSquare => {
                at[0] += rng.below(16);
                at[2] += rng.below(16);
            }
            Modifier::Offset { x, y, z } => {
                at[0] += x.sample(rng);
                at[1] += y.sample(rng);
                at[2] += z.sample(rng);
            }
            Modifier::HeightRange { height } => at[1] = height.sample(rng, self.request),
            Modifier::Heightmap { map } => {
                let Some(y) =
                    self.overlay
                        .height(self.field, self.profile, self.request, at[0], at[2], *map)
                else {
                    return false;
                };
                if y <= self.request.min_y {
                    return false;
                }
                at[1] = y;
            }
            Modifier::BlockPredicateFilter { predicate } => {
                if !self.test(predicate, at) {
                    return false;
                }
            }
            Modifier::EnvironmentScan {
                direction,
                max_steps,
                target_condition,
                allowed_search_condition,
            } => {
                let Some(p) = placement::environment_scan(
                    at,
                    *direction,
                    *max_steps,
                    target_condition,
                    allowed_search_condition,
                    self.field,
                    self.profile,
                    self.request,
                    self.overlay,
                ) else {
                    return false;
                };
                at = p;
            }
            Modifier::SurfaceWaterDepthFilter { max_water_depth } => {
                let surface =
                    self.overlay
                        .height(self.field, self.profile, self.request, at[0], at[2], 1);
                let floor =
                    self.overlay
                        .height(self.field, self.profile, self.request, at[0], at[2], 3);
                if !surface
                    .zip(floor)
                    .is_some_and(|(s, f)| s - f <= *max_water_depth)
                {
                    return false;
                }
            }
            Modifier::SurfaceRelativeThresholdFilter {
                map,
                min_inclusive,
                max_inclusive,
            } => {
                if !placement::surface_relative(
                    at,
                    *map,
                    *min_inclusive,
                    *max_inclusive,
                    self.field,
                    self.profile,
                    self.request,
                    self.overlay,
                ) {
                    return false;
                }
            }
            _ => unreachable!("unsupported nested modifier rejected at export/validation"),
        }
        self.placed(placed, at, rng, index + 1)
    }
    fn patch(&mut self, patch: &Recipe, origin: [i32; 3], rng: &mut Rng) -> bool {
        let rx = patch.xz_radius.sample(rng) + 1;
        let rz = patch.xz_radius.sample(rng) + 1;
        let mut surface = Vec::new();
        for dx in -rx..=rx {
            for dz in -rz..=rz {
                let ex = dx.abs() == rx;
                let ez = dz.abs() == rz;
                if ex && ez
                    || (ex || ez)
                        && (patch.extra_edge_column_chance == 0.0
                            || (rng.unit() as f32) > patch.extra_edge_column_chance)
                {
                    continue;
                }
                let mut at = [origin[0] + dx, origin[1], origin[2] + dz];
                let mut steps = 0;
                while self.test(&patch.air, at) && steps < patch.vertical_range {
                    at[1] += patch.direction;
                    steps += 1;
                }
                steps = 0;
                while !self.test(&patch.air, at) && steps < patch.vertical_range {
                    at[1] -= patch.direction;
                    steps += 1;
                }
                let ground = [at[0], at[1] + patch.direction, at[2]];
                if !self.test(&patch.air, at) || !self.test(&patch.sturdy, ground) {
                    continue;
                }
                let depth = patch.depth.sample(rng)
                    + i32::from(
                        patch.extra_bottom_block_chance > 0.0
                            && (rng.unit() as f32) < patch.extra_bottom_block_chance,
                    );
                let mut pos = ground;
                let mut placed = true;
                for i in 0..depth {
                    let Some(material) =
                        patch
                            .ground
                            .sample_optional(rng, pos, &|p| self.material(p), self.noise)
                    else {
                        // getState's absent fallback retains the existing block;
                        // Minecraft's same-block check then leaves the cursor here.
                        continue;
                    };
                    let same = patch
                        .same_blocks
                        .iter()
                        .find(|v| v.source == material)
                        .unwrap();
                    if !self.test(&same.predicate, pos) {
                        if !self.test(&patch.replaceable, pos) {
                            placed = i != 0;
                            break;
                        }
                        self.write(pos, material);
                        pos[1] += patch.direction;
                    }
                }
                if placed {
                    surface.push(ground);
                }
            }
        }
        java_set_order(&mut surface);
        if patch.water_pool {
            surface.retain(|at| patch.enclosed.iter().all(|p| self.test(p, *at)));
            // The second HashSet is populated in the first set's iteration order.
            java_set_order(&mut surface);
            for &at in &surface {
                self.write(at, self.profile.water);
            }
        }
        for &ground in &surface {
            if patch.vegetation_chance > 0.0 && (rng.unit() as f32) < patch.vegetation_chance {
                let at = [
                    ground[0],
                    ground[1]
                        + if patch.water_pool {
                            0
                        } else {
                            -patch.direction
                        },
                    ground[2],
                ];
                if self.placed(&patch.vegetation, at, rng, 0) && patch.water_pool {
                    if let Some(id) = self.material(ground) {
                        if let Some(pair) = patch.waterlogged_states.iter().find(|v| v[0] == id) {
                            self.write(ground, pair[1]);
                        }
                    }
                }
            }
        }
        !surface.is_empty()
    }
}
// HashMap preserves insertion order inside ordinary bins, including resize
// splits. Sorting stably by the final bucket reproduces that order. Pathological
// tree bins use Java object identity; here ties remain reproducible by insertion.
fn java_set_order(positions: &mut [[i32; 3]]) {
    let mut capacity = 16usize;
    while positions.len() > capacity * 3 / 4 {
        capacity *= 2;
    }
    positions.sort_by_key(|p| {
        let hash = p[1]
            .wrapping_add(p[2].wrapping_mul(31))
            .wrapping_mul(31)
            .wrapping_add(p[0]) as u32;
        (hash ^ (hash >> 16)) as usize & (capacity - 1)
    });
}
pub(super) fn place(
    patch: &Recipe,
    at: [i32; 3],
    rng: &mut Rng,
    field: &Field,
    profile: &WorldProfile,
    request: ChunkRequest,
    overlay: &mut Overlay,
    blocks: &mut Vec<WorldBlock>,
    noise: &provider_noise::Context,
) -> bool {
    World {
        field,
        profile,
        request,
        overlay,
        blocks,
        noise,
    }
    .patch(patch, at, rng)
}
pub(super) fn place_simple(
    simple: &Simple,
    at: [i32; 3],
    rng: &mut Rng,
    field: &Field,
    profile: &WorldProfile,
    request: ChunkRequest,
    overlay: &mut Overlay,
    blocks: &mut Vec<WorldBlock>,
    noise: &provider_noise::Context,
) -> bool {
    World {
        field,
        profile,
        request,
        overlay,
        blocks,
        noise,
    }
    .simple(simple, at, rng)
}
