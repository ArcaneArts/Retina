//! Complete fixed-height vine programs. Eligibility is proved from registered
//! operations/material predicates; unsupported or overflowing jobs replay on CPU.
use super::{
    placement::{IntConfig, IntProvider, Modifier, Overlay, Predicate},
    *,
};
use std::sync::Arc;
mod device;
pub(crate) use device::{Gpu, Stats};
const HEADER: usize = 32;
const COLUMN: usize = 8;
pub(super) const CAP: usize = 256;
pub(super) const BATCH: usize = 64;
pub(super) const QUEUE: usize = 128;
pub(crate) type Programs = Arc<Vec<Option<Program>>>;

#[derive(Clone, Debug)]
pub(crate) struct Program {
    words: Vec<u32>,
    outer: u32,
    inner: u32,
    predicate: [u32; 2],
    feature: u32,
    salt: u64,
}
impl Program {
    pub(super) fn attempts(&self) -> u64 {
        self.outer as u64 * self.inner as u64
    }
    fn new(recipe: &Recipe, request: ChunkRequest) -> Option<Self> {
        let Kind::AttachmentGrowth { growth } = &recipe.feature else {
            return None;
        };
        let (air, directions) = growth.vine_program()?;
        let Predicate::Material {
            offset: [0, 0, 0],
            allowed: air_states,
        } = air
        else {
            return None;
        };
        let outputs: Vec<_> = directions.iter().map(|v| v.0).collect();
        if outputs.iter().any(|m| air_states.binary_search(m).is_ok()) {
            return None;
        }
        let ops = recipe.placement.as_ref()?;
        let [
            Modifier::Count { count: outer },
            Modifier::Count { count: inner },
            Modifier::InSquare,
            Modifier::HeightRange { height },
            Modifier::BlockPredicateFilter { predicate },
            Modifier::Biome,
        ] = &ops[..]
        else {
            return None;
        };
        fn constant(v: &IntProvider) -> Option<u32> {
            match v {
                IntProvider::Value(n) | IntProvider::Config(IntConfig::Constant { value: n })
                    if *n > 0 =>
                {
                    Some(*n as u32)
                }
                _ => None,
            }
        }
        let (outer, inner) = (constant(outer)?, constant(inner)?);
        if !(1024..=16384).contains(&(outer as u64 * inner as u64)) {
            return None;
        }
        // Only writes in this column may change the truth of a predicate.
        fn independent(p: &Predicate, air: &[u16], outputs: &[u16]) -> bool {
            match p {
                Predicate::Material { offset, allowed } if offset[0] != 0 || offset[2] != 0 => {
                    let mut v = air
                        .iter()
                        .chain(outputs)
                        .map(|m| allowed.binary_search(m).is_ok());
                    let first = v.next();
                    v.all(|b| Some(b) == first)
                }
                Predicate::AllOf { predicates } | Predicate::AnyOf { predicates } => {
                    predicates.iter().all(|p| independent(p, air, outputs))
                }
                Predicate::Not { predicate } => independent(predicate, air, outputs),
                _ => true,
            }
        }
        if !independent(predicate, air_states, &outputs)
            || directions
                .iter()
                .any(|(_, p)| !independent(p, air_states, &outputs))
        {
            return None;
        }
        use super::placement_height::{Anchor, HeightConfig, HeightProvider};
        let resolve = |a: &Anchor| match a {
            Anchor::Absolute(n) => *n,
            Anchor::AboveBottom(n) => request.min_y.saturating_add(*n),
            Anchor::BelowTop(n) => (request.min_y + request.height as i32 - 1).saturating_sub(*n),
        };
        let (lo, hi) = match height {
            HeightProvider::Constant(a)
            | HeightProvider::Config(HeightConfig::Constant { value: a }) => {
                let y = resolve(a);
                (y, y)
            }
            HeightProvider::Config(HeightConfig::Uniform {
                min_inclusive: a,
                max_inclusive: b,
            }) => (resolve(a), resolve(b)),
            _ => return None,
        };
        if lo < i32::MIN + 32
            || lo > i32::MAX - 32
            || hi > i32::MAX - 32
            || hi as i64 - lo as i64 > 8192
        {
            return None;
        }
        let mut p = Self {
            words: vec![u32::from(hi > lo), lo as u32, hi as u32, 0],
            outer,
            inner,
            predicate: [0; 2],
            feature: 0,
            salt: recipe.placement_salt.unwrap_or(recipe.salt) as u64,
        };
        p.predicate = p.compile(predicate, &outputs)?;
        let a = p.compile(air, &outputs)?;
        let mut feature = vec![a[0], a[1], directions.len() as u32];
        for (state, predicate) in directions {
            let pred = p.compile(predicate, &outputs)?;
            feature.extend([state as u32, pred[0], pred[1]]);
        }
        p.feature = p.words.len() as u32;
        p.words.extend(feature);
        Some(p)
    }
    fn compile(&mut self, p: &Predicate, outputs: &[u16]) -> Option<[u32; 2]> {
        fn visit(
            p: &Predicate,
            words: &mut Vec<u32>,
            ops: &mut Vec<[u32; 8]>,
            outputs: &[u16],
        ) -> Option<()> {
            match p {
                Predicate::InsideWorldBounds { .. } => return None,
                Predicate::True => ops.push([0; 8]),
                Predicate::Material { offset, allowed } => {
                    if offset.iter().any(|v| v.unsigned_abs() > 32) {
                        return None;
                    }
                    let at = words.len() as u32;
                    words.extend(allowed.iter().map(|m| *m as u32));
                    ops.push([
                        1,
                        offset[0] as u32,
                        offset[1] as u32,
                        offset[2] as u32,
                        at,
                        allowed.len() as u32,
                        u32::from(outputs.iter().any(|m| allowed.binary_search(m).is_ok())),
                        u32::from(outputs.iter().any(|m| allowed.binary_search(m).is_err())),
                    ]);
                }
                Predicate::Not { predicate } => {
                    visit(predicate, words, ops, outputs)?;
                    ops.push([2, 0, 0, 0, 0, 0, 0, 0]);
                }
                Predicate::AllOf { predicates } | Predicate::AnyOf { predicates } => {
                    let all = matches!(p, Predicate::AllOf { .. });
                    ops.push([if all { 0 } else { 5 }, 0, 0, 0, 0, 0, 0, 0]);
                    for p in predicates {
                        visit(p, words, ops, outputs)?;
                        ops.push([if all { 3 } else { 4 }, 0, 0, 0, 0, 0, 0, 0]);
                    }
                }
            }
            (ops.len() <= 64).then_some(())
        }
        let mut ops = Vec::new();
        visit(p, &mut self.words, &mut ops, outputs)?;
        let result = [self.words.len() as u32, ops.len() as u32];
        self.words.extend(ops.into_iter().flatten());
        Some(result)
    }
}

pub(crate) fn compile(profile: &WorldProfile, request: ChunkRequest) -> Programs {
    let mut salts = std::collections::HashMap::new();
    for r in &profile.decorations {
        if r.placement.is_some() {
            *salts
                .entry(r.placement_salt.unwrap_or(r.salt))
                .or_insert(0usize) += 1;
        }
    }
    Arc::new(
        profile
            .decorations
            .iter()
            .map(|r| {
                if !profile.ordered_decorations
                    || request.height == 0
                    || request.height > 512
                    || request.height % 2 != 0
                    || salts.get(&r.placement_salt.unwrap_or(r.salt)) != Some(&1)
                {
                    None
                } else {
                    Program::new(r, request)
                }
            })
            .collect(),
    )
}

pub(super) struct Input {
    words: Vec<u32>,
    height: u32,
    attempts: u32,
}
impl Input {
    fn new(
        field: &Field,
        profile: &WorldProfile,
        request: ChunkRequest,
        recipe: u32,
        program: &Program,
        mask: Option<&crate::geology::CaveMask>,
    ) -> Option<Self> {
        let width = field.side.checked_mul(16)?;
        if width == 0
            || width
                .checked_mul(width)?
                .checked_mul(request.height as usize)?
                > u32::MAX as usize
        {
            return None;
        }
        for chunk in [field.origin_x, field.origin_z] {
            let start = chunk as i64 * 16;
            let end = start + width as i64 + 32;
            if start < i32::MIN as i64 + 32 || end > i32::MAX as i64 {
                return None;
            }
        }
        let mut words = vec![0; HEADER + width * width * COLUMN];
        words[0] = (field.origin_x * 16) as u32;
        words[1] = (field.origin_z * 16) as u32;
        words[2] = width as u32;
        words[3] = request.min_y as u32;
        words[4] = request.height;
        words[5] = CAP as u32;
        words[12] = words.len() as u32;
        words.extend(&program.words);
        words[17] = u32::from(profile.decoration_biome_3d);
        words[23] = program.feature;
        words[24] = program.outer;
        words[25] = program.inner;
        words[26] = 0;
        words[27] = program.predicate[0];
        words[28] = program.predicate[1];
        words[30] = QUEUE as u32;
        let substrate = field.substrate.as_ref()?;
        for z in 0..width {
            for x in 0..width {
                let wx = field.origin_x * 16 + x as i32;
                let wz = field.origin_z * 16 + z as i32;
                let col = field.column(wx, wz)?;
                let runs = substrate.material_runs(wx, wz)?;
                let at = HEADER + (z * width + x) * COLUMN;
                words[at] = words.len() as u32;
                words[at + 1] = runs.len() as u32;
                words.extend_from_slice(runs);
                words[at + 2] = col.height as u32;
                words[at + 4] = u32::from(mask.is_some_and(|m| m.surface_carved(wx, wz)));
                words[at + 5] =
                    u32::from(profile.biomes[col.biome()].decorations.contains(&recipe));
            }
        }
        let qy = request.min_y.div_euclid(4) * 4;
        let layers = ((request.min_y + request.height as i32 - qy + 3) / 4) as usize;
        words[19] = words.len() as u32;
        words[20] = qy as u32;
        words[21] = (width / 4) as u32;
        for y in 0..layers {
            for z in (0..width).step_by(4) {
                for x in (0..width).step_by(4) {
                    let wx = field.origin_x * 16 + x as i32;
                    let wz = field.origin_z * 16 + z as i32;
                    words.push(
                        substrate
                            .biome(wx, (qy + y as i32 * 4).max(request.min_y), wz)
                            .map_or(u32::MAX, |b| {
                                u32::from(profile.biomes[b as usize].decorations.contains(&recipe))
                            }),
                    );
                }
            }
        }
        Some(Self {
            words,
            height: request.height,
            attempts: program.attempts() as u32,
        })
    }
    fn anchors(
        &self,
        request: ChunkRequest,
        program: &Program,
        jobs: &[(i32, i32, &Overlay)],
    ) -> Vec<u32> {
        let mut words = vec![0; 4 + jobs.len() * 8];
        words[0] = jobs.len() as u32;
        let width = self.words[2] as i64;
        for (i, (x, z, overlay)) in jobs.iter().enumerate() {
            let seed =
                request.seed ^ program.salt ^ mix(*x as u32 as u64) ^ mix((*z as u32 as u64) << 32);
            let mut entries: Vec<_> = overlay
                .gpu_entries()
                .filter_map(|(p, m)| {
                    let x = p[0] as i64 - self.words[0] as i32 as i64;
                    let z = p[2] as i64 - self.words[1] as i32 as i64;
                    let y = p[1] as i64 - request.min_y as i64;
                    (x >= 0
                        && z >= 0
                        && x < width
                        && z < width
                        && y >= 0
                        && y < request.height as i64)
                        .then_some([((y * width + z) * width + x + 1) as u32, m as u32])
                })
                .collect();
            entries.sort_unstable_by_key(|v| v[0]);
            let offset = words.len() as u32;
            let at = 4 + i * 8;
            words[at..at + 6].copy_from_slice(&[
                *x as u32,
                *z as u32,
                seed as u32,
                (seed >> 32) as u32,
                offset,
                entries.len() as u32,
            ]);
            words.extend(entries.into_iter().flatten());
        }
        words
    }
}

impl Replay<'_> {
    pub(crate) fn run_vines(
        &mut self,
        targets: &[(usize, u32)],
        gpu: &std::sync::Mutex<Gpu>,
    ) -> Stats {
        let mut groups = std::collections::BTreeMap::<u32, Vec<usize>>::new();
        for &(index, recipe) in targets {
            groups.entry(recipe).or_default().push(index);
        }
        let mut total = Stats::default();
        for (recipe, indices) in groups {
            let program = self
                .gpu
                .as_ref()
                .and_then(|v| v[recipe as usize].as_ref())
                .unwrap();
            // Packing the substrate can outweigh a sparse program. Compare the
            // actual candidate count with the field footprint before offloading;
            // smaller batches keep the exact same CPU continuation.
            let candidates = indices.len() as u64 * program.attempts();
            let columns = self.field.side as u64 * self.field.side as u64 * 256;
            let result = if candidates >= columns * 2 {
                let packing = std::time::Instant::now();
                let input = Input::new(
                    self.field,
                    self.profile,
                    self.request,
                    recipe,
                    program,
                    self.mask,
                );
                total.substrate_pack_nanos += packing.elapsed().as_nanos() as u64;
                let jobs: Vec<_> = indices
                    .iter()
                    .map(|&i| {
                        (
                            self.field.origin_x + (i % self.field.side) as i32,
                            self.field.origin_z + (i / self.field.side) as i32,
                            &self.pending[i].as_ref().unwrap().0.overlay,
                        )
                    })
                    .collect();
                if let Some(input) = input {
                    let wait = std::time::Instant::now();
                    let locked = gpu.lock();
                    total.lock_wait_nanos += wait.elapsed().as_nanos() as u64;
                    match locked {
                        Ok(mut gpu) => Some(gpu.run(&input, self.request, program, &jobs)),
                        Err(_) => {
                            total.device_failure_anchors += indices.len() as u64;
                            None
                        }
                    }
                } else {
                    total.unresolved_substrate_anchors += indices.len() as u64;
                    None
                }
            } else {
                total.sparse_anchors += indices.len() as u64;
                None
            };
            let outputs = if let Some((output, stats)) = result {
                total.add(stats);
                output
            } else {
                total.fallbacks += indices.len() as u64;
                (0..indices.len()).map(|_| None).collect()
            };
            for (index, output) in indices.into_iter().zip(outputs) {
                let (mut state, old_bytes) = self.pending[index].take().unwrap();
                self.retained_bytes.fetch_sub(old_bytes, Ordering::Relaxed);
                state.gpu_after = Some(recipe);
                state.gpu_pause = None;
                if let Some(mut blocks) = output {
                    if self.profile.decoration_source_order {
                        for block in &mut blocks {
                            block.root_order = self.profile.decorations[recipe as usize].root_order;
                        }
                    }
                    state.candidates.remove_group(recipe);
                    state
                        .evaluation_cache
                        .retain(|(group, _, _), _| *group != recipe);
                    state
                        .overlay
                        .commit(&blocks, 0, self.field, self.profile, self.request);
                    state.completed += program.attempts();
                    state.blocks.extend(blocks);
                }
                let bytes = state.retained_bytes();
                if let Ok(previous) =
                    self.retained_bytes
                        .fetch_update(Ordering::Relaxed, Ordering::Relaxed, |used| {
                            used.checked_add(bytes)
                                .filter(|n| *n <= self.retained_limit)
                        })
                {
                    self.peak_retained_bytes
                        .fetch_max(previous + bytes, Ordering::Relaxed);
                    self.pending[index] = Some((state, bytes));
                } else {
                    // A deterministic full CPU replay avoids retaining an oversized
                    // successful GPU prefix or repeatedly submitting the same group.
                    self.gpu_disabled[index] = true;
                    total.post_gpu_retained_limit_anchors += 1;
                }
            }
        }
        total
    }
}

#[cfg(test)]
mod tests;
