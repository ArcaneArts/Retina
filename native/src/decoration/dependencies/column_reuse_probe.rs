//! Test-only sampled full-column overlap census. Original evaluation always
//! supplies generation. Copied runs/heights and never-upgraded weak references
//! observe availability without keeping a column, planner or terrain alive.
use super::*;
use serde::Serialize;
use serde_json::{Value, json};
use std::hash::{Hash, Hasher};
use std::sync::{Weak, atomic::AtomicBool};

const SHARDS: usize = 64;
const MAX_KEYS: usize = 524_288;
const MAX_COPY_BYTES: usize = 128 * 1024 * 1024;
const SAMPLE_DIVISOR: u32 = 64;
static ENABLED: AtomicBool = AtomicBool::new(false);
static ACTIVE: Mutex<Option<Arc<Session>>> = Mutex::new(None);

#[derive(Clone, Copy, Hash, PartialEq, Eq)]
struct Key {
    engine: usize,
    profile: usize,
    request: crate::CacheKey,
    roots: usize,
    before: usize,
}
impl Key {
    fn column(self) -> Self {
        Self { before: 0, ..self }
    }
    fn shard(&self) -> usize {
        let mut hash = super::super::spatial::SpatialHasher::default();
        self.hash(&mut hash);
        (hash.finish() >> 32) as usize % SHARDS
    }
}
// Sample coordinates/root, not demand order. Every occurrence of a selected
// exact key is observed. Counts are sampled counts, never scaled speedups.
fn selected(before: usize, x: i32, z: i32) -> bool {
    let mut h = (x as u32).wrapping_mul(0x9e3779b1)
        ^ (z as u32).rotate_left(16)
        ^ (before as u32).wrapping_mul(0x85ebca6b);
    h ^= h >> 16;
    h = h.wrapping_mul(0x7feb352d);
    h ^= h >> 15;
    h & (SAMPLE_DIVISOR - 1) == 0
}

macro_rules! counts {
    ($($name:ident),+ $(,)?) => {
        #[derive(Default, Serialize)]
        struct Counts { $( $name: u64, )+ }
        impl Counts {
            fn add(&mut self, other: &Self) { $( self.$name += other.$name; )+ }
        }
    }
}
counts! {
    sampled_initializers, successes, abandoned_or_failed,
    other_live_at_demand, same_live_at_demand,
    other_pending_at_demand, same_pending_at_demand, historical_dead_at_demand,
    compared_columns, compared_live_other, compared_run_words,
    run_encoding_differences, voxel_mismatches, height_mismatches, invalid_runs,
    skipped_keys, skipped_snapshots,
    prefix_successes, substrate_successes, archive_successes, unclassified_successes,
    live_other_prefix, live_other_substrate, live_other_archive,
    other_live_compact_only, other_live_tile_only, other_live_compact_and_tile,
    prefix_live_compact_only, prefix_live_tile_only, prefix_live_compact_and_tile,
    cross_stage_live_earlier, cross_stage_live_later,
    cross_stage_compared_snapshots, cross_stage_equal_earlier, cross_stage_equal_later,
    cross_stage_equal_any, cross_stage_equal_without_exact_other_live,
    cross_stage_equal_compact, cross_stage_equal_tile,
    prefix_cross_stage_equal_any, prefix_cross_stage_equal_without_exact_other_live,
}
#[derive(Clone, Copy)]
pub(super) enum Source {
    Prefix,
    Substrate,
    Archive,
}
struct Snapshot {
    runs: Vec<u32>,
    heights: [i32; 6],
}
impl Snapshot {
    fn valid(runs: &[u32], height: usize) -> bool {
        !runs.is_empty()
            && runs.last().unwrap() >> 16 == 0
            && runs[0] >> 16 < height as u32
            && runs.windows(2).all(|pair| pair[0] >> 16 > pair[1] >> 16)
    }
    fn material(runs: &[u32], y: usize) -> u16 {
        runs[runs.partition_point(|run| run >> 16 > y as u32)] as u16
    }
    fn same_voxels(&self, runs: &[u32], height: usize) -> bool {
        self.runs == runs
            || (0..height).all(|y| Self::material(&self.runs, y) == Self::material(runs, y))
    }
}
#[derive(Default)]
struct History {
    pending: Vec<(u64, u64)>,
    published: Vec<(u64, Weak<ColumnState>, bool)>,
    snapshot: Option<Arc<Snapshot>>,
}
#[derive(Default)]
struct Shard {
    entries: Map<Key, History>,
    // One stage entry per admitted exact key. No ColumnState owner is retained.
    stages: Map<Key, Vec<usize>>,
    counts: BTreeMap<(usize, bool), Counts>,
    next: u64,
    copy_bytes: usize,
}
struct Session {
    shards: [Mutex<Shard>; SHARDS],
    jobs: Mutex<Vec<Value>>,
    keys_per_shard: usize,
    bytes_per_shard: usize,
    cross_stage: bool,
}
impl Default for Session {
    fn default() -> Self {
        Self::new(MAX_KEYS / SHARDS, MAX_COPY_BYTES / SHARDS)
    }
}
impl Session {
    fn new(keys_per_shard: usize, bytes_per_shard: usize) -> Self {
        Self {
            shards: std::array::from_fn(|_| Mutex::new(Shard::default())),
            jobs: Mutex::new(Vec::new()),
            keys_per_shard,
            bytes_per_shard,
            cross_stage: false,
        }
    }
    fn selected(&self, before: usize, x: i32, z: i32) -> bool {
        selected(if self.cross_stage { 0 } else { before }, x, z)
    }
    fn begin(self: &Arc<Self>, key: Key, job: u64, interior: bool) -> Option<Build> {
        self.begin_with_layout(key, job, interior, false)
    }
    fn begin_with_layout(self: &Arc<Self>, key: Key, job: u64, interior: bool, compact: bool) -> Option<Build> {
        let shard = if self.cross_stage { key.column().shard() } else { key.shard() };
        let mut state = self.shards[shard].lock().unwrap();
        let group = (key.before, interior);
        if state.entries.len() >= self.keys_per_shard && !state.entries.contains_key(&key) {
            state.counts.entry(group).or_default().skipped_keys += 1;
            return None;
        }
        // Capture availability before the original build starts. Keep only
        // copied diagnostic snapshots, never upgrade the observed weak owner.
        let mut candidates = Vec::new();
        if self.cross_stage {
            for &before in state.stages.get(&key.column()).into_iter().flatten() {
                if before == key.before { continue; }
                let previous = &state.entries[&Key { before, ..key }];
                let Some(snapshot) = &previous.snapshot else { continue; };
                let mut compact = false;
                let mut tile = false;
                for (owner, weak, is_compact) in &previous.published {
                    if *owner == job || weak.strong_count() == 0 { continue; }
                    if *is_compact { compact = true; } else { tile = true; }
                }
                if compact || tile {
                    candidates.push(Candidate { before, snapshot: snapshot.clone(), compact, tile });
                }
            }
            if !state.entries.contains_key(&key) {
                state.stages.entry(key.column()).or_default().push(key.before);
            }
        }
        state.next += 1;
        let token = state.next;
        let history = state.entries.entry(key).or_default();
        let mut live_compact = false;
        let mut live_tile = false;
        let mut live_same = false;
        // Observe weak owner counts only; never upgrade or extend lifetimes.
        for (owner, weak, is_compact) in &history.published {
            if weak.strong_count() == 0 { continue; }
            if *owner == job { live_same = true; }
            else if *is_compact { live_compact = true; }
            else { live_tile = true; }
        }
        let live_other = live_compact || live_tile;
        let dead = !live_other && !live_same && !history.published.is_empty();
        let pending_other = history.pending.iter().any(|(owner, _)| *owner != job);
        let pending_same = history.pending.iter().any(|(owner, _)| *owner == job);
        history.pending.push((job, token));
        let c = state.counts.entry(group).or_default();
        c.sampled_initializers += 1;
        c.other_live_at_demand += live_other as u64;
        c.other_live_compact_only += (live_compact && !live_tile) as u64;
        c.other_live_tile_only += (live_tile && !live_compact) as u64;
        c.other_live_compact_and_tile += (live_compact && live_tile) as u64;
        c.same_live_at_demand += live_same as u64;
        c.historical_dead_at_demand += dead as u64;
        c.other_pending_at_demand += pending_other as u64;
        c.same_pending_at_demand += pending_same as u64;
        c.cross_stage_live_earlier += candidates.iter().any(|v| v.before < key.before) as u64;
        c.cross_stage_live_later += candidates.iter().any(|v| v.before > key.before) as u64;
        Some(Build {
            session: self.clone(),
            key,
            shard,
            job,
            token,
            group,
            live_other,
            live_compact,
            live_tile,
            compact,
            candidates,
            source: None,
            finished: false,
        })
    }
    fn report(&self) -> Value {
        let mut total = Counts::default();
        let mut groups = BTreeMap::<(usize, bool), Counts>::new();
        let (mut keys, mut bytes, mut pending, mut weak, mut live) = (0, 0, 0, 0, 0);
        for shard in &self.shards {
            let state = shard.lock().unwrap();
            keys += state.entries.len();
            bytes += state.copy_bytes;
            for history in state.entries.values() {
                pending += history.pending.len();
                weak += history.published.len();
                live += history
                    .published
                    .iter()
                    .filter(|(_, w, _)| w.strong_count() != 0)
                    .count();
            }
            for (&group, counts) in &state.counts {
                total.add(counts);
                groups.entry(group).or_default().add(counts);
            }
        }
        let groups: Vec<_> = groups.into_iter().map(|((before, interior), counts)| {
            json!({"before":before,"interior":interior,"counts":counts})
        }).collect();
        json!({"counts":total,"groups":groups,"jobs":*self.jobs.lock().unwrap(),
            "keys":keys,"snapshot_logical_bytes":bytes,"pending_builds":pending,
            "published_weak_handles":weak,"live_handles_at_wave_end":live,
            "cross_stage_mode":self.cross_stage,
            "sample_divisor":SAMPLE_DIVISOR,"max_keys":self.keys_per_shard*SHARDS,
            "max_snapshot_logical_bytes":self.bytes_per_shard*SHARDS})
    }
}
pub(super) struct Job {
    session: Option<Arc<Session>>,
    id: u64,
    key: Key,
    origin: [i64; 2],
    width: i64,
}
impl Job {
    pub(super) fn evaluate(
        &self,
        before: usize,
        x: i32,
        z: i32,
        profile: &WorldProfile,
        compact: bool,
        build: impl FnOnce(Option<&mut Build>) -> Result<Arc<ColumnState>, String>,
    ) -> Result<Arc<ColumnState>, String> {
        let mut observation = self.begin(before, x, z, compact);
        let result = build(observation.as_mut());
        if let (Some(observation), Ok(value)) = (observation, &result) {
            observation.complete_with_heights(value, observed_heights(
                value, profile, self.key.request.min_y, self.key.request.height as usize,
            ));
        }
        result
    }
    pub(super) fn new(
        engine: &TerrainEngine,
        profile: &WorldProfile,
        request: ChunkRequest,
        side: usize,
        roots: usize,
    ) -> Self {
        let session = ENABLED
            .load(Ordering::Acquire)
            .then(|| ACTIVE.lock().unwrap().clone())
            .flatten();
        let mut job = Self {
            session,
            id: 0,
            key: Key {
                engine: engine as *const _ as usize,
                profile: profile as *const _ as usize,
                request: request.into(),
                roots,
                before: 0,
            },
            origin: [
                i64::from(request.chunk_x) * 16,
                i64::from(request.chunk_z) * 16,
            ],
            width: side as i64 * 16,
        };
        if let Some(session) = &job.session {
            let mut jobs = session.jobs.lock().unwrap();
            job.id = jobs.len() as u64;
            jobs.push(
                json!({"id":job.id,"seed":request.seed,"profile":request.reserved,
                "chunk_x":request.chunk_x,"chunk_z":request.chunk_z,"min_y":request.min_y,
                "height":request.height,"side":side,"roots":roots}),
            );
        }
        job
    }
    pub(super) fn begin(&self, before: usize, x: i32, z: i32, compact: bool) -> Option<Build> {
        let session = self.session.as_ref()?;
        if !session.selected(before, x, z) {
            return None;
        }
        let dx = i64::from(x) - self.origin[0];
        let dz = i64::from(z) - self.origin[1];
        let key = Key {
            before,
            request: crate::CacheKey {
                x,
                z,
                ..self.key.request
            },
            ..self.key
        };
        session.begin_with_layout(
            key,
            self.id,
            dx >= 0 && dz >= 0 && dx < self.width && dz < self.width,
            compact,
        )
    }
}
// Reconstruct diagnostic height snapshots directly from immutable runs. Calling
// ColumnState::height would charge these observer reads to the height-use probe.
// This helper runs only for selected successful observations, never generation.
fn observed_heights(column: &ColumnState, profile: &WorldProfile, min_y: i32, height: usize) -> [i32; 6] {
    std::array::from_fn(|map| {
        let mut top = min_y + height as i32;
        for &run in column.runs.slice() {
            if profile.heightmap_masks[run as u16 as usize] & (1 << map) != 0 {
                return top;
            }
            top = min_y + (run >> 16) as i32;
        }
        min_y
    })
}
struct Candidate {
    before: usize,
    snapshot: Arc<Snapshot>,
    compact: bool,
    tile: bool,
}
pub(super) struct Build {
    session: Arc<Session>,
    key: Key,
    shard: usize,
    job: u64,
    token: u64,
    group: (usize, bool),
    live_other: bool,
    live_compact: bool,
    live_tile: bool,
    compact: bool,
    candidates: Vec<Candidate>,
    source: Option<Source>,
    finished: bool,
}
impl Build {
    pub(super) fn source(&mut self, source: Source) {
        self.source = Some(source);
    }
    fn complete_with_heights(mut self, column: &Arc<ColumnState>, heights: [i32; 6]) {
        let runs = column.runs.slice();
        let height = self.key.request.height as usize;
        let valid = Snapshot::valid(runs, height);
        let (mut earlier, mut later, mut compact, mut tile) = (false, false, false, false);
        if valid {
            for candidate in &self.candidates {
                if candidate.snapshot.heights == heights && candidate.snapshot.same_voxels(runs, height) {
                    earlier |= candidate.before < self.key.before;
                    later |= candidate.before > self.key.before;
                    compact |= candidate.compact;
                    tile |= candidate.tile;
                }
            }
        }
        let equal = earlier || later;
        let bytes = std::mem::size_of_val(runs) + std::mem::size_of_val(&heights);
        let mut state = self.session.shards[self.shard].lock().unwrap();
        let room = bytes
            <= self
                .session
                .bytes_per_shard
                .saturating_sub(state.copy_bytes);
        let history = state.entries.get_mut(&self.key).unwrap();
        history.pending.retain(|&(_, token)| token != self.token);
        let (compared, encoding_diff, voxels_diff, heights_diff, stored, skipped) =
            if let Some(previous) = &history.snapshot {
                (
                    true,
                    previous.runs != runs,
                    !valid || !previous.same_voxels(runs, height),
                    previous.heights != heights,
                    false,
                    false,
                )
            } else if room && valid {
                history.snapshot = Some(Arc::new(Snapshot {
                    runs: runs.to_vec(),
                    heights,
                }));
                (false, false, false, false, true, false)
            } else {
                (false, false, false, false, false, true)
            };
        history.published.push((self.job, Arc::downgrade(column), self.compact));
        if stored {
            state.copy_bytes += bytes;
        }
        let c = state.counts.entry(self.group).or_default();
        c.successes += 1;
        c.cross_stage_compared_snapshots += if valid { self.candidates.len() as u64 } else { 0 };
        c.cross_stage_equal_earlier += earlier as u64;
        c.cross_stage_equal_later += later as u64;
        c.cross_stage_equal_any += equal as u64;
        c.cross_stage_equal_without_exact_other_live += (equal && !self.live_other) as u64;
        c.cross_stage_equal_compact += compact as u64;
        c.cross_stage_equal_tile += tile as u64;
        match self.source {
            Some(Source::Prefix) => {
                c.prefix_successes += 1;
                c.prefix_cross_stage_equal_any += equal as u64;
                c.prefix_cross_stage_equal_without_exact_other_live += (equal && !self.live_other) as u64;
                c.live_other_prefix += self.live_other as u64;
                c.prefix_live_compact_only += (self.live_compact && !self.live_tile) as u64;
                c.prefix_live_tile_only += (self.live_tile && !self.live_compact) as u64;
                c.prefix_live_compact_and_tile += (self.live_compact && self.live_tile) as u64;
            }
            Some(Source::Substrate) => {
                c.substrate_successes += 1;
                c.live_other_substrate += self.live_other as u64;
            }
            Some(Source::Archive) => {
                c.archive_successes += 1;
                c.live_other_archive += self.live_other as u64;
            }
            None => c.unclassified_successes += 1,
        }
        c.compared_columns += compared as u64;
        c.compared_live_other += (compared && self.live_other) as u64;
        c.compared_run_words += if compared { runs.len() as u64 } else { 0 };
        c.run_encoding_differences += encoding_diff as u64;
        c.voxel_mismatches += voxels_diff as u64;
        c.height_mismatches += heights_diff as u64;
        c.invalid_runs += (!valid) as u64;
        c.skipped_snapshots += skipped as u64;
        self.finished = true;
    }
}
impl Drop for Build {
    fn drop(&mut self) {
        if !self.finished {
            let mut state = self.session.shards[self.shard]
                .lock()
                .unwrap_or_else(|e| e.into_inner());
            state
                .entries
                .get_mut(&self.key)
                .unwrap()
                .pending
                .retain(|&(_, token)| token != self.token);
            state
                .counts
                .entry(self.group)
                .or_default()
                .abandoned_or_failed += 1;
        }
    }
}
struct Installed(Arc<Session>);
impl Installed {
    fn start() -> Self {
        let mut active = ACTIVE.lock().unwrap();
        assert!(active.is_none());
        let mut session = Session::default();
        session.cross_stage = std::env::var("RETINA_COLUMN_REUSE_CROSS_STAGE").as_deref() == Ok("1");
        let session = Arc::new(session);
        *active = Some(session.clone());
        ENABLED.store(true, Ordering::Release);
        Self(session)
    }
}
impl Drop for Installed {
    fn drop(&mut self) {
        ENABLED.store(false, Ordering::Release);
        *ACTIVE.lock().unwrap_or_else(|e| e.into_inner()) = None;
    }
}

#[cfg(test)]
mod tests;
#[cfg(test)]
mod cross_stage_tests;
