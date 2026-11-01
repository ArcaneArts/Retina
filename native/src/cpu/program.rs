//! Prepared execution of the same loaded DAGs used by the GPU. Reachability and
//! constant folding happen once; scratch frames and spatial caches belong to a
//! bounded sampling job, never the global engine or saved world definition.
pub(crate) mod bounds;
mod statistics;
mod material_zero;
mod cell;
use super::noise::*;
use crate::program::{Instruction, Program, RegistryProgram, opcode::Opcode};
use crate::spatial_hash::SamplingMap as Map;
use statistics::{Event, Event::*, Statistics};
use std::{collections::HashMap, sync::Arc};

#[derive(Clone, Copy, Default)]
pub struct Context {
    /// stone depth, surface depth, slope, terracotta offset
    pub surface: [f32; 4],
    /// ceiling depth, secondary noise, fluid height, preliminary floor
    pub detail: [f32; 4],
}
struct Step {
    index: usize,
    opcode: Opcode,
    constant: Option<f32>,
    dependencies: Box<[u32]>,
}
struct Plan {
    steps: Vec<Step>,
    boundable: bool,
    indices: Vec<usize>,
}
pub struct Prepared {
    registry: Arc<RegistryProgram>,
    plans: Vec<Plan>,
    columns: crate::column_program::Plan,
    material_zero: Vec<Option<f32>>,
    material_zero_minimum: f32,
}
impl Prepared {
    pub fn new(registry: Arc<RegistryProgram>) -> Result<Self, String> {
        let extra = if registry.aquifer.as_ref().is_some_and(|a| a.enabled) {
            5
        } else {
            0
        };
        let biomes = registry
            .programs
            .len()
            .checked_sub(3 + extra)
            .ok_or("CPU registry has fewer than three base programs")?;
        registry.validate(biomes)?;
        let mut plans = Vec::new();
        let mut material_zero = vec![None; biomes];
        for (pid, program) in registry.all_programs().enumerate() {
            let mut live = vec![false; program.nodes.len()];
            let mut pending = program.roots.clone();
            pending.push(0);
            while let Some(index) = pending.pop() {
                if std::mem::replace(&mut live[index as usize], true) {
                    continue;
                }
                pending.extend(program.nodes[index as usize].dependencies(&registry.points));
            }
            let mut constants = vec![None; program.nodes.len()];
            let mut steps = Vec::new();
            for (index, node) in program.nodes.iter().enumerate().filter(|(i, _)| live[*i]) {
                let opcode = Opcode::from_code(node.op)?;
                let pure = matches!(
                    opcode,
                    Opcode::Constant
                        | Opcode::Add
                        | Opcode::Subtract
                        | Opcode::Multiply
                        | Opcode::Divide
                        | Opcode::Minimum
                        | Opcode::Maximum
                        | Opcode::Power
                        | Opcode::Absolute
                        | Opcode::Square
                        | Opcode::Cube
                        | Opcode::HalfNegative
                        | Opcode::QuarterNegative
                        | Opcode::Squeeze
                        | Opcode::Reciprocal
                        | Opcode::Negate
                        | Opcode::SquareRoot
                        | Opcode::Logarithm
                        | Opcode::Sign
                        | Opcode::Clamp
                        | Opcode::RangeChoice
                        | Opcode::Lerp
                        | Opcode::And
                        | Opcode::Or
                        | Opcode::Not
                        | Opcode::InRange
                        | Opcode::ValueGradient
                );
                let deps = node.dependencies(&registry.points);
                let constant = if pure && deps.iter().all(|&i| constants[i as usize].is_some()) {
                    let values = deps
                        .iter()
                        .map(|&i| (i, constants[i as usize].unwrap()))
                        .collect::<HashMap<_, _>>();
                    Some(scalar(opcode, node, |i| {
                        values.get(&i).copied().unwrap_or(0.0)
                    }))
                } else {
                    None
                };
                constants[index] = constant;
                steps.push(Step {
                    index,
                    opcode,
                    constant,
                    dependencies: deps.into_boxed_slice(),
                });
            }
            let mut possible = vec![false; program.nodes.len()];
            for step in &steps {
                possible[step.index] = step.constant.is_some_and(f32::is_finite)
                    || bounds::possible(step.opcode, &program.nodes[step.index], &possible);
            }
            let boundable =
                (0..6).all(|i| possible[program.roots.get(i).copied().unwrap_or(0) as usize]);
            let mut indices = vec![usize::MAX; program.nodes.len()];
            for (position, step) in steps.iter().enumerate() {
                indices[step.index] = position;
            }
            let plan = Plan { steps, boundable, indices };
            if registry.material_layers && pid >= 3 && pid < 3 + biomes {
                material_zero[pid - 3] = material_zero::prepare(program, &plan);
            }
            plans.push(plan);
        }
        let columns = crate::column_program::Plan::new(&registry);
        let material_zero_minimum = material_zero.iter().flatten().copied()
            .fold(f32::INFINITY, f32::min);
        Ok(Self { registry, plans, columns, material_zero, material_zero_minimum })
    }
    pub fn evaluator<'a>(&'a self, seed: u64, sea_level: i32, bands: &'a [u16]) -> Evaluator<'a> {
        Evaluator {
            prepared: self,
            seed,
            sea_level,
            bands,
            frames: Vec::new(),
            corners: Map::default(),
            plain_corners: Map::default(),
            cells: None,
            horizontal: Map::default(),
            bounds_scratch: Vec::new(),
            statistics: None,
            #[cfg(test)]
            eager: false,
        }
    }
}
#[derive(Hash, PartialEq, Eq)]
struct PlainCorner {
    field: usize,
    point: [u32; 3],
}
#[derive(Hash, PartialEq, Eq)]
struct Corner {
    field: usize,
    point: [u32; 3],
    surface: [u32; 4],
    detail: [u32; 4],
}
#[derive(Default)]
struct Frame {
    values: Vec<f32>,
    stamps: Vec<u32>,
    epoch: u32,
    work: Vec<u32>,
}
impl Frame {
    fn begin(&mut self, size: usize) {
        self.values.resize(size, 0.0);
        self.stamps.resize(size, 0);
        self.epoch = self.epoch.wrapping_add(1);
        if self.epoch == 0 {
            self.stamps.fill(0);
            self.epoch = 1;
        }
        self.work.clear();
    }
    fn missing(&self, node: u32) -> bool {
        self.stamps[node as usize] != self.epoch
    }
    fn store(&mut self, node: usize, value: f32) {
        self.values[node] = value;
        self.stamps[node] = self.epoch;
        self.work.pop();
    }
}
impl Step {
    /// Select dependencies using the scalar operation's actual branch rules.
    /// Programs are validated DAGs; the explicit stack also handles deep graphs.
    fn pending(&self, node: &Instruction, frame: &Frame, points: &[[f32; 4]]) -> Option<u32> {
        use Opcode::*;
        if matches!(self.opcode, RangeChoice | And | Or | Spline) {
            if frame.missing(node.a) {
                return Some(node.a);
            }
            let a = frame.values[node.a as usize];
            match self.opcode {
                RangeChoice => {
                    let branch = if a >= node.p[0] && a < node.p[1] {
                        node.b
                    } else {
                        node.c
                    };
                    return frame.missing(branch).then_some(branch);
                }
                And if a == 0.0 => return None,
                Or if a != 0.0 => return None,
                And | Or => return frame.missing(node.b).then_some(node.b),
                Spline => {
                    let knots = &points[node.b as usize..(node.b + node.c) as usize];
                    let mut j = 0;
                    for (i, knot) in knots.iter().enumerate().skip(1) {
                        if a >= knot[0] {
                            j = i;
                        }
                    }
                    let left = knots[j];
                    if frame.missing(left[2] as u32) {
                        return Some(left[2] as u32);
                    }
                    if !(a < left[0] || j + 1 == knots.len()) {
                        let right = knots[j + 1][2] as u32;
                        return frame.missing(right).then_some(right);
                    }
                    return None;
                }
                _ => unreachable!(),
            }
        }
        self.dependencies
            .iter()
            .copied()
            .find(|&node| frame.missing(node))
    }
}
pub struct Evaluator<'a> {
    prepared: &'a Prepared,
    seed: u64,
    sea_level: i32,
    bands: &'a [u16],
    frames: Vec<Frame>,
    corners: Map<Corner, f32>,
    plain_corners: Map<PlainCorner, f32>,
    cells: Option<Box<cell::Cells>>,
    horizontal: Map<(u32, u32, usize), f32>,
    bounds_scratch: Vec<bounds::Bounds>,
    statistics: Option<Box<Statistics>>,
    /// Reference scheduling runs all reachable nodes in original forward order.
    #[cfg(test)]
    eager: bool,
}
impl Evaluator<'_> {
    #[inline]
    pub(crate) fn material_is_zero(&self, program: usize, y: f32, preliminary: f32) -> bool {
        y < preliminary && y >= self.prepared.material_zero_minimum
            && program.checked_sub(3).and_then(|i| self.prepared.material_zero.get(i))
                .copied().flatten().is_some_and(|lower| y >= lower)
    }
    pub(crate) fn with_timings(mut self, timings: Option<Arc<crate::timings::Timings>>) -> Self {
        self.statistics = timings.map(|t| Box::new(Statistics::new(t)));
        self
    }
    #[inline]
    fn record<const TRACK: bool>(&self, event: Event, amount: u64) {
        if TRACK && let Some(statistics) = &self.statistics {
            statistics.add(event, amount);
        }
    }
    #[inline]
    fn sample_noise<const TRACK: bool>(&self, point: [f32; 3], seed: u32) -> f32 {
        self.record::<TRACK>(cpu_vm_noise3_calls, 1);
        noise3(point, seed)
    }
    pub fn run(
        &mut self,
        program: usize,
        point: [f32; 3],
        context: Context,
    ) -> Result<[f32; 6], String> {
        if program >= self.prepared.registry.programs.len() {
            return Err(format!("unknown CPU program {program}"));
        }
        Ok(if self.statistics.is_some() {
            self.evaluate::<true>(program, point, context, 0)
        } else {
            self.evaluate::<false>(program, point, context, 0)
        })
    }
    pub fn noise(&self, point: [f32; 3], index: usize) -> f32 {
        if self.statistics.is_some() {
            self.noise_profile::<true>(point, index)
        } else {
            self.noise_profile::<false>(point, index)
        }
    }
    fn noise_profile<const TRACK: bool>(&self, point: [f32; 3], index: usize) -> f32 {
        self.record::<TRACK>(cpu_vm_noise_profiles, 1);
        let n = &self.prepared.registry.noises[index];
        let seed = self.seed as u32 ^ mix_hash((self.seed >> 32) as u32) ^ n.salt as u32;
        let point = [
            point[0] * n.horizontal_scale,
            point[1],
            point[2] * n.horizontal_scale,
        ];
        let (mut frequency, mut sum) = (n.frequency, 0.0);
        for (i, &weight) in n.coefficients.iter().enumerate() {
            if weight != 0.0 {
                sum += (self.sample_noise::<TRACK>(
                    point.map(|v| v * frequency),
                    seed.wrapping_add(i as u32 * 1013),
                ) + self.sample_noise::<TRACK>(
                    point.map(|v| v * (frequency * 1.0181268882175227_f32)),
                    (seed ^ 0xa511e9b3).wrapping_add(i as u32 * 1013),
                )) * weight;
            }
            frequency *= 2.0;
        }
        sum * n.amplitude * 0.625
    }
    fn blended<const TRACK: bool>(&self, point: [f32; 3], p: [f32; 4]) -> f32 {
        self.record::<TRACK>(cpu_vm_blended_profiles, 1);
        let limit = [
            point[0] * 684.412 * p[0] / 32768.0,
            point[1] * 684.412 * p[1] / 32768.0,
            point[2] * 684.412 * p[0] / 32768.0,
        ];
        let main = [
            point[0] * 684.412 * p[0] / p[2] / 128.0,
            point[1] * 684.412 * p[1] / p[3] / 128.0,
            point[2] * 684.412 * p[0] / p[2] / 128.0,
        ];
        let seed = self.seed as u32 ^ mix_hash((self.seed >> 32) as u32);
        let (mut lower, mut upper, mut blend, mut weights, mut weight) = (0.0, 0.0, 0.0, 0.0, 1.0);
        for octave in 0..8 {
            let scale = (1u32 << octave) as f32;
            lower += self
                .sample_noise::<TRACK>(limit.map(|v| v * scale), seed.wrapping_add(octave * 1013))
                * weight;
            upper += self.sample_noise::<TRACK>(
                limit.map(|v| v * scale),
                seed.wrapping_add(7919 + octave * 1013),
            ) * weight;
            blend += self.sample_noise::<TRACK>(
                main.map(|v| v * scale),
                seed.wrapping_add(15838 + octave * 1013),
            ) * weight;
            weights += weight;
            weight *= 0.5;
        }
        mix(lower, upper, (blend / weights * 0.5 + 0.5).clamp(0.0, 1.0)) / weights
    }
    fn interpolate<const TRACK: bool>(
        &mut self,
        field: usize,
        point: [f32; 3],
        context: Context,
        depth: usize,
    ) -> f32 {
        self.record::<TRACK>(cpu_vm_interpolations, 1);
        let f = &self.prepared.registry.interpolations[field];
        let size = [f.cell[0] as f32, f.cell[1] as f32, f.cell[0] as f32];
        let lower = std::array::from_fn::<_, 3, _>(|i| (point[i] / size[i]).floor() * size[i]);
        let alpha = std::array::from_fn::<_, 3, _>(|i| (point[i] - lower[i]) / size[i]);
        let plain = context.surface.map(f32::to_bits) == [0; 4]
            && context.detail.map(f32::to_bits) == [0; 4];
        // The eager reference keeps the preceding corner-map-only path.
        #[cfg(test)]
        let plain = plain && !self.eager;
        if plain { return self.interpolate_cell::<TRACK>(field, lower, size, alpha, context, depth); }
        let mut corners = [0.0; 8];
        for (index, value) in corners.iter_mut().enumerate() {
            let upper = [index & 1, (index >> 1) & 1, (index >> 2) & 1];
            if (0..3).any(|i| upper[i] != 0 && alpha[i] == 0.0) {
                continue;
            }
            let p = std::array::from_fn(|i| lower[i] + upper[i] as f32 * size[i]);
            *value = self.corner_inner::<TRACK>(field, p, context, depth);
            if alpha == [0.0; 3] {
                return *value;
            }
        }
        trilinear(corners, alpha)
    }
    fn corner(&mut self, field: usize, point: [f32; 3], context: Context, depth: usize) -> f32 {
        if self.statistics.is_some() {
            self.corner_inner::<true>(field, point, context, depth)
        } else {
            self.corner_inner::<false>(field, point, context, depth)
        }
    }
    fn corner_inner<const TRACK: bool>(
        &mut self,
        field: usize,
        point: [f32; 3],
        context: Context,
        depth: usize,
    ) -> f32 {
        self.record::<TRACK>(cpu_vm_corner_lookups, 1);
        let spatial = PlainCorner {
            field,
            point: point.map(f32::to_bits),
        };
        let surface = context.surface.map(f32::to_bits);
        let detail = context.detail.map(f32::to_bits);
        // Most density/interpolation samples use the exact default context.
        // Keep those keys small; non-default contexts retain every original bit,
        // including negative zero. Both maps share the original entry budget.
        let contextual = (surface != [0; 4] || detail != [0; 4]).then(|| Corner {
            field,
            point: spatial.point,
            surface,
            detail,
        });
        if contextual.is_none() {
            self.record::<TRACK>(cpu_vm_plain_corner_lookups, 1);
        }
        let cached = if let Some(key) = &contextual {
            self.corners.get(key)
        } else {
            self.plain_corners.get(&spatial)
        };
        if let Some(value) = cached {
            self.record::<TRACK>(cpu_vm_corner_hits, 1);
            if contextual.is_none() {
                self.record::<TRACK>(cpu_vm_plain_corner_hits, 1);
            }
            return *value;
        }
        let value = self.evaluate::<TRACK>(
            self.prepared.registry.programs.len() + field,
            point,
            context,
            depth,
        )[0];
        if self.corners.len() + self.plain_corners.len() >= 32768 {
            self.record::<TRACK>(cpu_vm_corner_clears, 1);
            self.corners.clear();
            self.plain_corners.clear();
        }
        if let Some(key) = contextual {
            self.corners.insert(key, value);
        } else {
            self.plain_corners.insert(spatial, value);
        }
        value
    }
    fn evaluate<const TRACK: bool>(
        &mut self,
        pid: usize,
        point: [f32; 3],
        context: Context,
        depth: usize,
    ) -> [f32; 6] {
        self.record::<TRACK>(cpu_vm_calls, 1);
        if TRACK {
            let root_count = self.prepared.registry.programs.len();
            let extra = if self
                .prepared
                .registry
                .aquifer
                .as_ref()
                .is_some_and(|a| a.enabled)
            {
                5
            } else {
                0
            };
            let scope = if pid < 3 {
                cpu_vm_terrain_calls
            } else if pid < root_count - extra {
                cpu_vm_material_calls
            } else if pid < root_count {
                cpu_vm_auxiliary_calls
            } else {
                cpu_vm_field_calls
            };
            self.record::<TRACK>(scope, 1);
        }
        if self.frames.len() <= depth {
            self.frames.resize_with(depth + 1, Frame::default);
        }
        let mut frame = std::mem::take(&mut self.frames[depth]);
        let registry = &self.prepared.registry;
        let program: &Program = if pid < registry.programs.len() {
            &registry.programs[pid]
        } else {
            &registry.interpolations[pid - registry.programs.len()].input
        };
        let plan = &self.prepared.plans[pid];
        frame.begin(program.nodes.len());
        #[cfg(test)]
        let eager = self.eager;
        #[cfg(not(test))]
        let eager = false;
        if eager {
            frame
                .work
                .extend(plan.steps.iter().rev().map(|step| step.index as u32));
        } else {
            frame.work.extend(
                (0..6)
                    .rev()
                    .map(|i| program.roots.get(i).copied().unwrap_or(0)),
            );
        }
        while let Some(&index) = frame.work.last() {
            self.record::<TRACK>(cpu_vm_node_visits, 1);
            if !frame.missing(index) {
                self.record::<TRACK>(cpu_vm_ready_hits, 1);
                frame.work.pop();
                continue;
            }
            let index = index as usize;
            let step = &plan.steps[plan.indices[index]];
            let n = &program.nodes[index];
            let a = n.a as usize;
            let b = n.b as usize;
            let c = n.c as usize;
            let p = n.p;
            if let Some(value) = step.constant {
                self.record::<TRACK>(cpu_vm_constant_hits, 1);
                frame.store(index, value);
                continue;
            }
            let cache = self.prepared.columns.slots[pid][index]
                .map(|slot| (point[0].to_bits(), point[2].to_bits(), slot));
            if cache.is_some() {
                self.record::<TRACK>(cpu_vm_horizontal_lookups, 1);
            }
            if let Some(value) = cache.and_then(|key| self.horizontal.get(&key)) {
                self.record::<TRACK>(cpu_vm_horizontal_hits, 1);
                frame.store(index, *value);
                continue;
            }
            if !eager && let Some(dependency) = step.pending(n, &frame, &registry.points) {
                frame.work.push(dependency);
                continue;
            }
            self.record::<TRACK>(cpu_vm_computed_nodes, 1);
            let values = &mut frame.values;
            use Opcode::*;
            let value = if let Some(value) = step.constant {
                value
            } else {
                match step.opcode {
                    Constant | Add | Subtract | Multiply | Divide | Minimum | Maximum | Power
                    | Absolute | Square | Cube | HalfNegative | QuarterNegative | Squeeze
                    | Reciprocal | Negate | SquareRoot | Logarithm | Sign | Clamp | RangeChoice
                    | Lerp | And | Or | Not | InRange | ValueGradient => {
                        scalar(step.opcode, n, |i| values[i as usize])
                    }
                    Noise => self.noise_profile::<TRACK>(
                        [
                            point[0] * p[1] + values[a],
                            point[1] * p[2] + values[b],
                            point[2] * p[1] + values[c],
                        ],
                        p[0] as usize,
                    ),
                    Shift => {
                        let q = match b {
                            1 => [point[0], 0.0, point[2]],
                            2 => [point[2], point[0], 0.0],
                            _ => point,
                        };
                        self.noise_profile::<TRACK>(q.map(|v| v * 0.25), a) * 4.0
                    }
                    AxisGradient => gradient(point[a], b, p),
                    Spline => {
                        let x = values[a];
                        let knots = &registry.points[b..b + c];
                        let mut j = 0;
                        for (i, knot) in knots.iter().enumerate().skip(1) {
                            if x >= knot[0] {
                                j = i;
                            }
                        }
                        let left = knots[j];
                        let lv = values[left[2] as usize];
                        if x < left[0] || j == c - 1 {
                            lv + (x - left[0]) * left[1]
                        } else {
                            let right = knots[j + 1];
                            let rv = values[right[2] as usize];
                            let span = right[0] - left[0];
                            let t = (x - left[0]) / span;
                            let delta = rv - lv;
                            mix(lv, rv, t)
                                + t * (1.0 - t)
                                    * mix(left[1] * span - delta, -right[1] * span + delta, t)
                        }
                    }
                    OldBlended => self.blended::<TRACK>(point, p),
                    ShiftedOldBlended => {
                        self.blended::<TRACK>([values[a], values[b], values[c]], p)
                    }
                    ShiftedNoise => {
                        self.noise_profile::<TRACK>(
                            [values[a], values[b], values[c]],
                            p[0] as usize,
                        ) * p[1]
                    }
                    Interpolate => self.interpolate::<TRACK>(
                        p[0] as usize,
                        [values[a], values[b], values[c]],
                        context,
                        depth + 1,
                    ),
                    Coordinate => point[a],
                    Terracotta => {
                        if self.bands.is_empty() {
                            0.0
                        } else {
                            self.bands[(point[1].round_ties_even() as i32
                                + context.surface[3] as i32)
                                .rem_euclid(self.bands.len() as i32)
                                as usize] as f32
                                + 1.0
                        }
                    }
                    StoneDepth => {
                        let [stone, surface, _, _] = context.surface;
                        truth(if registry.material_layers {
                            let depth = if a == 1 { stone } else { context.detail[0] };
                            let secondary = ((context.detail[1] + 1.0) * 0.5 * p[1]) as i32 as f32;
                            depth <= 1.0 + p[0] + if b == 1 { surface } else { 0.0 } + secondary
                        } else {
                            a == 1
                                && stone
                                    <= 1.0 + p[0] + if b == 1 { surface } else { 0.0 } + p[1] * 0.5
                        })
                    }
                    Water => {
                        let y = point[1] + if a == 1 { context.surface[0] } else { 0.0 };
                        truth(if registry.material_layers {
                            context.detail[2] == i32::MIN as f32
                                || y >= context.detail[2] + p[0] + context.surface[1] * p[1]
                        } else {
                            point[1] + context.surface[0] >= self.sea_level as f32
                                || y >= self.sea_level as f32 + p[0] + context.surface[1] * p[1]
                        })
                    }
                    VerticalAnchor => truth(
                        point[1] + if a == 1 { context.surface[0] } else { 0.0 }
                            >= p[0] + context.surface[1] * p[1],
                    ),
                    VerticalChance => {
                        let h = cell_hash(
                            [point[0] as i32, point[2] as i32],
                            (self.seed as u32).wrapping_add(pid as u32 * 7919),
                            (self.seed >> 32) as u32,
                        );
                        truth(
                            (h & 65535) as f32 / 65535.0
                                < ((p[1] - point[1]) / (p[1] - p[0]).max(1.0)).clamp(0.0, 1.0),
                        )
                    }
                    Steep => truth(context.surface[2] > 3.0),
                    Hole => truth(context.surface[1] <= 0.0),
                    Temperature => {
                        let mut temperature = p[0];
                        let line = self.sea_level as f32 + 17.0;
                        if point[1] > line {
                            temperature -= (self.sample_noise::<TRACK>(
                                [point[0] * 0.125, 0.0, point[2] * 0.125],
                                1234,
                            ) * 8.0
                                + point[1]
                                - line)
                                * 0.00125;
                        }
                        truth(temperature < 0.15)
                    }
                    NoiseCount => ((self
                        .sample_noise::<TRACK>([point[0] / p[0], 0.0, point[2] / p[0]], 2345)
                        * 0.625
                        + p[1])
                        * p[2])
                        .ceil(),
                    Coverage => {
                        let surface = point[1] + context.surface[0];
                        let (mut low, mut high) = if c & 2 != 0 {
                            (surface, surface)
                        } else {
                            (p[0], p[1])
                        };
                        let range = (high - low + 1.0).max(1.0);
                        if c & 1 != 0 {
                            low = low.max(surface);
                            high = high.min(self.sea_level as f32 - 1.0);
                        }
                        let eligible =
                            (high.min(point[1] + p[3]) - low.max(point[1] - p[3]) + 1.0).max(0.0);
                        if values[a] > 0.0 && eligible > 0.0 {
                            let area = (std::f32::consts::PI * p[2] * p[2]).max(1.0);
                            let coverage = 1.0
                                - (-values[a] * area * (eligible / range).min(1.0) / 256.0).exp();
                            let divisor = (p[2] * 2.0).max(2.0);
                            let field = self.sample_noise::<TRACK>(
                                [point[0] / divisor, 0.0, point[2] / divisor],
                                self.seed as u32 ^ mix_hash((self.seed >> 32) as u32) ^ n.b,
                            ) * 0.625;
                            truth(smoothstep(-0.5, 0.5, field) < coverage)
                        } else {
                            0.0
                        }
                    }
                    PreliminarySurface => {
                        truth(!registry.material_layers || point[1] >= context.detail[3])
                    }
                }
            };
            frame.store(index, value);
            if let Some(key) = cache {
                if self.horizontal.len() >= 32768 {
                    self.record::<TRACK>(cpu_vm_horizontal_clears, 1);
                    self.horizontal.clear();
                }
                self.horizontal.insert(key, value);
            }
        }
        let result = std::array::from_fn(|i| {
            frame.values[program.roots.get(i).copied().unwrap_or(0) as usize]
        });
        self.frames[depth] = frame;
        result
    }
}
fn truth(value: bool) -> f32 {
    if value { 1.0 } else { 0.0 }
}
fn gradient(value: f32, mode: usize, p: [f32; 4]) -> f32 {
    let mut t = (value - p[0]) / (p[1] - p[0]);
    t = match mode {
        1 => t - t.floor(),
        2 => 1.0 - ((t * 0.5 - (t * 0.5).floor()) * 2.0 - 1.0).abs(),
        _ => t.clamp(0.0, 1.0),
    };
    mix(p[2], p[3], t)
}
fn scalar(op: Opcode, n: &Instruction, v: impl Fn(u32) -> f32) -> f32 {
    use Opcode::*;
    let safe = |x: f32| if x.abs() < 0.000001 { 0.000001 } else { x };
    match op {
        Constant => n.p[0],
        Add => v(n.a) + v(n.b),
        Subtract => v(n.a) - v(n.b),
        Multiply => v(n.a) * v(n.b),
        Divide => v(n.a) / safe(v(n.b)),
        Minimum => v(n.a).min(v(n.b)),
        Maximum => v(n.a).max(v(n.b)),
        Power => v(n.a).max(0.0).powf(v(n.b)),
        Absolute => v(n.a).abs(),
        Square => v(n.a) * v(n.a),
        Cube => v(n.a) * v(n.a) * v(n.a),
        HalfNegative => {
            if v(n.a) > 0.0 {
                v(n.a)
            } else {
                v(n.a) * 0.5
            }
        }
        QuarterNegative => {
            if v(n.a) > 0.0 {
                v(n.a)
            } else {
                v(n.a) * 0.25
            }
        }
        Squeeze => {
            let x = v(n.a).clamp(-1.0, 1.0);
            x * 0.5 - x * x * x / 24.0
        }
        Reciprocal => 1.0 / safe(v(n.a)),
        Negate => -v(n.a),
        SquareRoot => v(n.a).max(0.0).sqrt(),
        Logarithm => v(n.a).max(0.000001).ln(),
        Sign => {
            if v(n.a) == 0.0 {
                0.0
            } else {
                v(n.a).signum()
            }
        }
        Clamp => v(n.a).clamp(n.p[0], n.p[1]),
        RangeChoice => {
            if v(n.a) >= n.p[0] && v(n.a) < n.p[1] {
                v(n.b)
            } else {
                v(n.c)
            }
        }
        Lerp => mix(v(n.b), v(n.c), v(n.a)),
        And => {
            if v(n.a) != 0.0 {
                v(n.b)
            } else {
                0.0
            }
        }
        Or => {
            if v(n.a) != 0.0 {
                v(n.a)
            } else {
                v(n.b)
            }
        }
        Not => truth(v(n.a) == 0.0),
        InRange => truth(v(n.a) >= n.p[0] && v(n.a) <= n.p[1]),
        ValueGradient => gradient(v(n.a), n.b as usize, n.p),
        _ => unreachable!("non-scalar opcode {op:?}"),
    }
}

#[cfg(test)]
mod tests;
