//! Demand-driven source-root dependencies. Each root reads the accepted result
//! of preceding roots; its anchor-local attempts keep their existing live overlay.
mod footprint;
use super::spatial::Map;
use super::*;
use crate::TerrainEngine;
use crate::performance::{Counter as Perf, Metrics};
use std::borrow::Cow;
use std::collections::BTreeMap;
use std::hash::{Hash, Hasher};
use std::sync::{Arc, Mutex, OnceLock, atomic::{AtomicU64, Ordering}};

/// Job-owned cells keep one initializer per key. Partition only their lookup
/// locks; generation and recursive dependency waits happen after releasing them.
struct ShardedMap<K, V> {
    shards: [Mutex<Map<K, V>>; 64],
}
impl<K, V> Default for ShardedMap<K, V> {
    fn default() -> Self {
        Self {
            shards: std::array::from_fn(|_| Mutex::new(Map::default())),
        }
    }
}
impl<K: Hash + Eq, V> ShardedMap<K, V> {
    fn shard(&self, key: &K) -> &Mutex<Map<K, V>> {
        let mut hash = super::spatial::SpatialHasher::default();
        key.hash(&mut hash);
        &self.shards[(hash.finish() >> 32) as usize % self.shards.len()]
    }
    fn take(&mut self) -> Result<Map<K, V>, ()> {
        let mut all = Map::default();
        for shard in &mut self.shards {
            all.extend(std::mem::take(shard.get_mut().map_err(|_| ())?));
        }
        Ok(all)
    }
    fn len(&self) -> usize {
        self.shards
            .iter()
            .map(|shard| shard.lock().unwrap().len())
            .sum()
    }
    fn into_entries(self) -> impl Iterator<Item = (K, V)> {
        self.shards
            .into_iter()
            .flat_map(|shard| shard.into_inner().unwrap_or_else(|e| e.into_inner()))
    }
}
type Memo<K, V> = ShardedMap<K, Arc<OnceLock<Result<Arc<V>, String>>>>;
fn memo<K: Eq + std::hash::Hash, V>(
    cache: &Memo<K, V>,
    key: K,
    metrics: &Metrics,
    build: impl FnOnce() -> Result<V, String>,
) -> Result<Arc<V>, String> {
    memo_arc(cache, key, metrics, || build().map(Arc::new))
}
fn memo_arc<K: Eq + std::hash::Hash, V>(
    cache: &Memo<K, V>,
    key: K,
    metrics: &Metrics,
    build: impl FnOnce() -> Result<Arc<V>, String>,
) -> Result<Arc<V>, String> {
    metrics.add(Perf::memo_requests, 1);
    let lock_started = metrics.start();
    let slot = cache
        .shard(&key)
        .lock()
        .map_err(|_| "decoration dependency cache poisoned")?
        .entry(key)
        .or_insert_with(|| Arc::new(OnceLock::new()))
        .clone();
    metrics.elapsed(Perf::memo_lock_nanos, lock_started);
    if slot.get().is_some() {
        metrics.add(Perf::memo_ready_hits, 1);
    }
    let joined = metrics.start();
    let mut built = false;
    let result = slot
        .get_or_init(|| {
            built = true;
            metrics.add(Perf::memo_builds, 1);
            build()
        })
        .clone();
    // Exclude initializer execution: an outer OnceLock can itself wait for
    // nested dependencies or GPU results, which must not count as joining it.
    if !built {
        metrics.elapsed(Perf::memo_join_nanos, joined);
    }
    result
}
#[derive(Clone, Copy)]
struct Bounds {
    x: [i64; 2],
    z: [i64; 2],
}
impl Bounds {
    fn recipe(recipe: &Recipe) -> Result<Self, String> {
        let mut result = Self {
            x: [0, 0],
            z: [0, 0],
        };
        let mut add = |x: [i64; 2], z: [i64; 2]| {
            for i in 0..2 {
                result.x[i] += x[i];
                result.z[i] += z[i];
            }
        };
        if let Some(program) = &recipe.placement {
            for op in program.iter() {
                match op {
                    placement::Modifier::InSquare
                    | placement::Modifier::CountOnEveryLayer { .. } => add([0, 15], [0, 15]),
                    placement::Modifier::Offset { x, z, .. } => {
                        let (xl, xh) = x.bounds()?;
                        let (zl, zh) = z.bounds()?;
                        add([xl as i64, xh as i64], [zl as i64, zh as i64]);
                    }
                    placement::Modifier::Cuboid { xz_size, .. } => {
                        let (_, radius) = xz_size.bounds()?;
                        let radius = radius as i64;
                        add([-radius, radius], [-radius, radius]);
                    }
                    _ => {}
                }
            }
        } else {
            add([0, 15], [0, 15]);
        }
        let reach = i64::from(recipe.reach.ok_or_else(|| {
            format!(
                "source-ordered feature has no exported footprint: {}",
                recipe.source
            )
        })?);
        if reach < 0 {
            return Err(format!("negative feature footprint: {}", recipe.source));
        }
        add([-reach, reach], [-reach, reach]);
        Ok(result)
    }
    fn union(&mut self, other: Self) {
        self.x = [self.x[0].min(other.x[0]), self.x[1].max(other.x[1])];
        self.z = [self.z[0].min(other.z[0]), self.z[1].max(other.z[1])];
    }
    fn writers(self, x: i32, z: i32) -> Result<impl Iterator<Item = (i32, i32)>, String> {
        self.writer_range([x, z], [x, z])
    }
    fn writers_area(self, min: [i32; 2], max: [i32; 2]) -> Result<Vec<(i32, i32)>, String> {
        Ok(self.writer_range(min, max)?.collect())
    }
    fn writer_range(
        self,
        min: [i32; 2],
        max: [i32; 2],
    ) -> Result<impl Iterator<Item = (i32, i32)>, String> {
        let ceil = |v: i64| -(-v).div_euclid(16);
        let x = [
            ceil(min[0] as i64 - self.x[1]),
            (max[0] as i64 - self.x[0]).div_euclid(16),
        ];
        let z = [
            ceil(min[1] as i64 - self.z[1]),
            (max[1] as i64 - self.z[0]).div_euclid(16),
        ];
        let (xs, zs) = if x[0] > x[1] || z[0] > z[1] {
            (1..=0, 1..=0)
        } else {
            // Validate in the same order as the first failing original pair.
            let x0 = i32::try_from(x[0]).map_err(|_| "dependency anchor X overflows")?;
            let z0 = i32::try_from(z[0]).map_err(|_| "dependency anchor Z overflows")?;
            let x1 = i32::try_from(x[1]).map_err(|_| "dependency anchor X overflows")?;
            let z1 = i32::try_from(z[1]).map_err(|_| "dependency anchor Z overflows")?;
            (x0..=x1, z0..=z1)
        };
        // Prior-column reads need this order but not an owned coordinate list.
        Ok(zs.flat_map(move |az| xs.clone().map(move |ax| (ax, az))))
    }
}
struct Root {
    definition: Option<usize>,
    ids: Vec<u32>,
    bounds: Bounds,
    deferred_ore: Option<(u16, u16)>,
    footprint: bool,
}

fn deferred_ore_pair(profile: &WorldProfile, ids: &[u32]) -> Option<(u16, u16)> {
    if !profile.ordered_decorations {
        return None;
    }
    let eligible = |&id: &u32| {
        let recipe = &profile.decorations[id as usize];
        let program = recipe.placement.as_ref()?;
        if !program.iter().all(|op| {
            matches!(
                op,
                placement::Modifier::Count { .. }
                    | placement::Modifier::InSquare
                    | placement::Modifier::HeightRange { .. }
                    | placement::Modifier::Biome
            )
        }) {
            return None;
        }
        let Kind::NetherFeature { nether } = &recipe.feature else {
            return None;
        };
        nether.deferred_replacement(profile)
    };
    let pair = eligible(ids.first()?)?;
    // Each anchor originally sees the prior root plus its own writes. Uniform
    // replacement makes cross-anchor overlap idempotent when applied in order.
    ids.iter()
        .all(|id| eligible(id) == Some(pair))
        .then_some(pair)
}
struct Output {
    metadata: Option<crate::structures::ChunkData>,
    writes: Vec<WorldBlock>,
    columns: Map<(i32, i32), Vec<usize>>,
}
/// Immutable commands from all possible writers of the requested volume.
/// Outputs remain in source anchor order. Each column stores one anchor ID per
/// group and borrows its existing command index list, preserving within-root
/// support reads without duplicating one pair of indices per command.
struct CompletedRoot {
    anchors: Vec<((i32, i32), Arc<Output>)>,
    columns: Map<usize, Vec<usize>>,
}
impl CompletedRoot {
    fn writes(&self, column: usize, x: i32, z: i32)
        -> impl Iterator<Item = ((i32, i32, usize), WorldBlock)> + '_
    {
        self.columns.get(&column).into_iter().flatten().flat_map(move |&anchor| {
            let ((ax, az), output) = &self.anchors[anchor];
            output.columns.get(&(x, z)).into_iter().flatten().map(move |&index| {
                ((*az, *ax, index), output.writes[index])
            })
        })
    }
}
// A failure is rare, but its inline String enlarged every column slot. Keep
// the error behind one pointer without adding allocations to successful builds.
struct ColumnFailure(String);
type ColumnCell = OnceLock<Result<Arc<ColumnState>, Box<ColumnFailure>>>;
struct ColumnVersion {
    root: usize,
    state: ColumnCell,
}
struct Versions {
    origin: [i32; 2],
    width: usize,
    changed: Vec<Vec<ColumnVersion>>,
    base: Vec<ColumnCell>,
}
impl Versions {
    fn new(request: ChunkRequest, side: usize) -> Self {
        let width = side * 16;
        Self {
            origin: [request.chunk_x * 16, request.chunk_z * 16],
            width,
            changed: (0..width * width).map(|_| Vec::new()).collect(),
            base: (0..width * width).map(|_| OnceLock::new()).collect(),
        }
    }
    fn index(&self, x: i32, z: i32) -> Option<usize> {
        let x = i64::from(x) - i64::from(self.origin[0]);
        let z = i64::from(z) - i64::from(self.origin[1]);
        (x >= 0 && z >= 0 && x < self.width as i64 && z < self.width as i64)
            .then(|| z as usize * self.width + x as usize)
    }
    fn before(&self, column: usize, before: usize) -> (usize, &ColumnCell) {
        let versions = &self.changed[column];
        let count = versions.partition_point(|version| version.root < before);
        match count.checked_sub(1) {
            Some(last) => (versions[last].root + 1, &versions[last].state),
            None => (0, &self.base[column]),
        }
    }
}

enum ColumnRuns {
    Owned(Box<[u32]>),
    Shared {
        mask: Arc<crate::geology::CaveMask>,
        range: std::ops::Range<usize>,
    },
}
impl ColumnRuns {
    fn slice(&self) -> &[u32] {
        match self {
            Self::Owned(runs) => runs,
            Self::Shared { mask, range } => &mask.words[range.clone()],
        }
    }
}
struct ColumnState {
    runs: ColumnRuns,
    heights: [i32; 6],
}
impl ColumnState {
    fn new(blocks: Vec<u16>, profile: &WorldProfile, min_y: i32) -> Self {
        let mut runs = Vec::new();
        let mut start = 0;
        for y in 1..=blocks.len() {
            if y == blocks.len() || blocks[y] != blocks[start] {
                runs.push((start as u32) << 16 | u32::from(blocks[start]));
                start = y;
            }
        }
        runs.reverse();
        Self::from_runs(
            ColumnRuns::Owned(runs.into_boxed_slice()),
            blocks.len(),
            profile,
            min_y,
        )
    }
    fn from_runs(runs: ColumnRuns, height: usize, profile: &WorldProfile, min_y: i32) -> Self {
        let mut heights = [min_y; 6];
        let mut top = min_y + height as i32;
        for &run in runs.slice() {
            for (map, height) in heights.iter_mut().enumerate() {
                if *height == min_y
                    && profile.heightmap_masks[run as u16 as usize] & (1 << map) != 0
                {
                    *height = top;
                }
            }
            top = min_y + (run >> 16) as i32;
        }
        Self { runs, heights }
    }
    fn material(&self, y: usize) -> u16 {
        let runs = self.runs.slice();
        runs[runs.partition_point(|run| run >> 16 > y as u32)] as u16
    }
    fn expand(&self, height: usize) -> Vec<u16> {
        let mut blocks = vec![0; height];
        let mut top = height;
        for &run in self.runs.slice() {
            let start = (run >> 16) as usize;
            blocks[start..top].fill(run as u16);
            top = start;
        }
        blocks
    }
}
struct ColumnTile(Vec<ColumnCell>);
impl Default for ColumnTile {
    fn default() -> Self {
        Self((0..COLUMNS).map(|_| OnceLock::new()).collect())
    }
}
struct Planner<'a> {
    engine: &'a TerrainEngine,
    profile: &'a WorldProfile,
    request: ChunkRequest,
    roots: Vec<Root>,
    terrain: crate::terrain_job::TerrainJob<'a>,
    anchors: Memo<(usize, i32, i32), Output>,
    ready: Map<(usize, i32, i32), Arc<Output>>,
    footprints: Memo<(usize, i32, i32), footprint::Footprint>,
    ready_footprints: Map<(usize, i32, i32), Arc<footprint::Footprint>>,
    completed: Vec<CompletedRoot>,
    versions: Versions,
    anchor_ids: Memo<(i32, i32), Vec<u32>>,
    starts: Memo<(i32, i32), Vec<Arc<crate::structures::Start>>>,
    discovery: crate::structures::Discovery,
    job: Option<&'a crate::timings::Timings>,
    metrics: Arc<crate::timings::Timings>,
    retain_prefix: bool,
    columns: ShardedMap<(usize, i32, i32), Arc<ColumnTile>>,
    ready_columns: Map<(usize, i32, i32), Arc<ColumnTile>>,
    error: Mutex<Option<String>>,
}
const VIEW_COLUMN_SLOTS: usize = 32;
struct ViewColumn {
    key: (i32, i32),
    column: Arc<ColumnState>,
}
struct ViewColumns {
    // The first column in a slot stays immutable for the view's lifetime.
    // Exact keys distinguish collisions, which retain the unbounded map path.
    slots: [OnceLock<ViewColumn>; VIEW_COLUMN_SLOTS],
    values: Mutex<Map<(i32, i32), Arc<ColumnState>>>,
    counts: Option<[AtomicU64; 3]>,
}
impl Default for ViewColumns {
    fn default() -> Self {
        Self {
            slots: std::array::from_fn(|_| OnceLock::new()),
            values: Mutex::new(Map::default()),
            counts: crate::performance::enabled().then(|| std::array::from_fn(|_| AtomicU64::new(0))),
        }
    }
}
impl ViewColumns {
    fn index(key: (i32, i32)) -> usize {
        let mut hash = super::spatial::SpatialHasher::default();
        key.hash(&mut hash);
        (hash.finish() >> 32) as usize % VIEW_COLUMN_SLOTS
    }
    fn count(&self, index: usize) {
        if let Some(counts) = &self.counts { counts[index].fetch_add(1, Ordering::Relaxed); }
    }
    fn read<T>(
        &self,
        x: i32,
        z: i32,
        load: impl FnOnce() -> Result<Arc<ColumnState>, String>,
        read: impl FnOnce(&ColumnState) -> T,
    ) -> Result<T, String> {
        let key = (x, z);
        let cell = &self.slots[Self::index(key)];
        if let Some(slot) = cell.get() {
            if slot.key == key {
                self.count(0);
                self.count(2);
                return Ok(read(&slot.column));
            }
            let values = self.values.lock().map_err(|_| "anchor view poisoned")?;
            if let Some(column) = values.get(&key) {
                self.count(0);
                return Ok(read(column));
            }
        }
        self.count(1);
        // Do not hold a slot initializer or map lock across recursive prior
        // evaluation. Concurrent misses may load the same immutable column,
        // just as on the original map path.
        let column = load()?;
        let value = read(&column);
        if let Err(slot) = cell.set(ViewColumn { key, column }) {
            if cell.get().unwrap().key != key {
                self.values.lock().map_err(|_| "anchor view poisoned")?
                    .insert(key, slot.column);
            }
        }
        Ok(value)
    }
}
struct View<'a, 'b> {
    planner: &'a Planner<'b>,
    before: usize,
    base: Arc<Field<'static>>,
    columns: ViewColumns,
}
impl Drop for View<'_, '_> {
    fn drop(&mut self) {
        if let Some(counts) = &self.columns.counts {
            // Publish per-view totals once. Disabled diagnostics add no atomics
            // to scalar reads; enabled counters are local to this view.
            for (counter, count) in [Perf::view_column_cached_reads,
                Perf::view_column_loads, Perf::view_column_slot_hits].into_iter().zip(counts) {
                self.planner.metrics.performance.add(counter, count.load(Ordering::Relaxed));
            }
        }
    }
}
impl View<'_, '_> {
    fn terrain(&self, x: i32, z: i32) -> Result<Arc<Field<'static>>, String> {
        if x.div_euclid(256) == (self.base.origin_x + 1).div_euclid(16)
            && z.div_euclid(256) == (self.base.origin_z + 1).div_euclid(16)
        {
            Ok(self.base.clone())
        } else {
            self.planner.terrain(x.div_euclid(16), z.div_euclid(16))
        }
    }
    fn read_column<T>(
        &self,
        x: i32,
        z: i32,
        read: impl FnOnce(&ColumnState) -> T,
    ) -> Result<T, String> {
        self.columns.read(x, z, || self.planner.column(self.before, x, z), read)
    }
}
impl PriorWorld for View<'_, '_> {
    fn deferred_ore(&self) -> Option<(u16, u16)> {
        self.planner.roots[self.before].deferred_ore
    }
    fn column(&self, x: i32, z: i32) -> Option<Column> {
        match self.terrain(x, z) {
            Ok(field) => field.column(x, z),
            Err(error) => {
                self.planner.fail(error);
                None
            }
        }
    }
    fn biome(&self, at: [i32; 3]) -> Option<usize> {
        match self.terrain(at[0], at[2]) {
            Ok(field) => field.biome(self.planner.profile, at),
            Err(error) => {
                self.planner.fail(error);
                None
            }
        }
    }
    fn material(&self, at: [i32; 3]) -> Option<u16> {
        if at[1] < self.planner.request.min_y
            || at[1] >= self.planner.request.min_y + self.planner.request.height as i32
        {
            return Some(0);
        }
        match self.read_column(at[0], at[2], |column| {
            column.material((at[1] - self.planner.request.min_y) as usize)
        }) {
            Ok(material) => Some(material),
            Err(error) => {
                self.planner.fail(error);
                None
            }
        }
    }
    fn height(&self, x: i32, z: i32, map: u8) -> Option<i32> {
        let mut before = self.before;
        while before > 0 && self.planner.roots[before - 1].deferred_ore.is_some() {
            before -= 1;
        }
        // These roots preserve every heightmap bit. Keep material queries on
        // the original prefix; an earlier column must not enter that view cache.
        let height = if before == self.before {
            self.read_column(x, z, |column| column.heights[map as usize])
        } else {
            self.planner.metrics.performance.add(
                Perf::height_roots_skipped, (self.before - before) as u64);
            self.planner.column(before, x, z).map(|column| column.heights[map as usize])
        };
        match height {
            Ok(height) => Some(height),
            Err(error) => {
                self.planner.fail(error);
                None
            }
        }
    }
}
impl Planner<'_> {
    /// Called only after the root's parallel target evaluation has joined.
    /// Recursive boundary work is synchronous, so every remaining cell is done.
    fn complete(&mut self, root: usize, targets: &[(i32, i32)]) -> Result<(), String> {
        let pending = self
            .anchors
            .take()
            .map_err(|_| "dependency cache poisoned")?;
        for (key, cell) in pending {
            let cell = Arc::try_unwrap(cell)
                .map_err(|_| "dependency cell still in use at root completion")?;
            let output = cell
                .into_inner()
                .ok_or("unfinished dependency at root completion")??;
            self.ready.insert(key, output);
        }
        for (key, cell) in self.footprints.take().map_err(|_| "footprint cache poisoned")? {
            let cell = Arc::try_unwrap(cell).map_err(|_| "footprint still in use at root completion")?;
            let footprint = cell.into_inner().ok_or("unfinished footprint at root completion")??;
            self.ready_footprints.insert(key, footprint);
        }
        // Existing tile slots are OnceLocks; the map itself becomes immutable
        // between root barriers, including slots first needed by later roots.
        self.ready_columns.extend(
            self.columns
                .take()
                .map_err(|_| "column dependency cache poisoned")?,
        );
        let mut completed = CompletedRoot {
            anchors: Vec::with_capacity(targets.len()),
            columns: Map::default(),
        };
        let mut command_count = 0;
        for &(ax, az) in targets {
            let output = self
                .ready
                .get(&(root, ax, az))
                .ok_or("missing completed root target")?;
            let anchor = completed.anchors.len();
            for (&(x, z), indices) in &output.columns {
                if let Some(column) = self.versions.index(x, z) {
                    completed
                        .columns
                        .entry(column)
                        .or_default()
                        .push(anchor);
                    command_count += indices.len() as u64;
                }
            }
            completed.anchors.push(((ax, az), output.clone()));
        }
        for &column in completed.columns.keys() {
            self.versions.changed[column].push(ColumnVersion {
                root,
                state: OnceLock::new(),
            });
        }
        self.metrics.performance.add(
            Perf::completed_column_commands,
            command_count,
        );
        self.metrics.performance.add(
            Perf::completed_column_groups,
            completed.columns.values().map(|v| v.len() as u64).sum(),
        );
        self.completed.push(completed);
        Ok(())
    }
    fn fail(&self, error: String) {
        self.error.lock().unwrap().get_or_insert(error);
    }
    fn check(&self) -> Result<(), String> {
        self.error.lock().unwrap().clone().map_or(Ok(()), Err)
    }
    fn terrain(&self, x: i32, z: i32) -> Result<Arc<Field<'static>>, String> {
        self.terrain.field(x, z)
    }

    fn anchor(&self, root: usize, x: i32, z: i32) -> Result<Cow<'_, Arc<Output>>, String> {
        // The completed map is immutable until the next joined root barrier.
        // Only pending results need an owning reference for their caller.
        if let Some(output) = self.ready.get(&(root, x, z)) {
            self.metrics.performance.add(Perf::anchor_ready_hits, 1);
            return Ok(Cow::Borrowed(output));
        }
        memo(
            &self.anchors,
            (root, x, z),
            &self.metrics.performance,
            || {
                self.metrics.performance.add(Perf::anchor_builds, 1);
                if let Some(definition) = self.roots[root].definition {
                    return self.structure(root, definition, x, z);
                }
                let base = self.terrain(x, z)?;
                let available = memo(&self.anchor_ids, (x, z), &self.metrics.performance, || {
                    Ok(anchor_ids(&base, self.profile, x, z))
                })?;
                let ids = [available
                    .iter()
                    .copied()
                    .filter(|id| self.roots[root].ids.contains(id))
                    .collect::<Vec<_>>()];
                if ids[0].is_empty() {
                    return Ok(Output {
                        metadata: None,
                        writes: Vec::new(),
                        columns: Map::default(),
                    });
                }
                let view = View {
                    planner: self,
                    before: root,
                    base: base.clone(),
                    columns: ViewColumns::default(),
                };
                let field = Field {
                    origin_x: x,
                    origin_z: z,
                    side: 1,
                    columns: base.chunk(x, z).to_vec(),
                    substrate: base.substrate.clone(),
                    prior: Some(&view),
                };
                let request = ChunkRequest {
                    chunk_x: x,
                    chunk_z: z,
                    ..self.request
                };
                let mut replay =
                    Replay::new(&field, self.profile, request, x, z, 1, None, Some(&ids))
                        .with_prefix_resume(self.retain_prefix);
                let mut samples = provider_noise::Samples::default();
                let mut counts = counts::Counts::default();
                loop {
                    let queries = replay.poll(
                        &samples,
                        self.profile.decoration_noise.as_ref().map(|_| &counts),
                    );
                    self.check()?;
                    if queries.is_empty() {
                        break;
                    }
                    // Geometry is native; spatial count/provider evaluation still uses
                    // the resident GPU programs. No Java generation callbacks exist.
                    let sampled = self
                        .engine
                        .feature_gpu
                        .lock()
                        .map_err(|_| "feature GPU lock poisoned")?
                        .run_batch(
                            request.reserved,
                            self.profile.decoration_noise.as_ref(),
                            &queries.counts,
                            &self.profile.decoration_provider_noises,
                            &queries.noise,
                        )?;
                    self.engine.pipeline.transfer(
                        request.reserved,
                        sampled.upload,
                        sampled.readback,
                    );
                    for (slot, nanos) in [
                        crate::timings::FEATURE_COUNTS,
                        crate::timings::PROVIDER_NOISE,
                    ]
                    .into_iter()
                    .zip(sampled.device_nanos)
                    {
                        if let Some(nanos) = nanos {
                            self.engine.timings(request.reserved).device(slot, nanos);
                            if let Some(job) = self.job {
                                job.device(slot, nanos);
                            }
                        }
                    }
                    counts.extend(
                        queries
                            .counts
                            .into_iter()
                            .zip(sampled.counts.into_iter().map(|v| v as i32)),
                    );
                    samples.extend(
                        queries
                            .noise
                            .into_iter()
                            .zip(sampled.providers.into_iter().map(f32::from_bits)),
                    );
                }
                let mut all = replay.finish_world();
                let writes = std::mem::take(&mut all[0]);
                let mut columns: Map<_, Vec<_>> = Map::default();
                let mut deferred = 0;
                for (index, block) in writes.iter().enumerate() {
                    deferred += u64::from(block.role == DEFERRED_ORE);
                    columns.entry((block.x, block.z)).or_default().push(index);
                }
                self.metrics
                    .performance
                    .add(Perf::deferred_ore_candidates, deferred);
                Ok(Output {
                    writes,
                    columns,
                    metadata: None,
                })
            },
        )
        .map(Cow::Owned)
    }
    fn column_writer(&self, root: usize, ax: i32, az: i32, x: i32, z: i32)
        -> Result<Option<Cow<'_, Arc<Output>>>, String>
    {
        let key = (root, ax, az);
        if let Some(output) = self.ready.get(&key) {
            self.metrics.performance.add(Perf::anchor_ready_hits, 1);
            return Ok(Some(Cow::Borrowed(output)));
        }
        if self.roots[root].footprint {
            let footprint = if let Some(footprint) = self.ready_footprints.get(&key) {
                Cow::Borrowed(footprint)
            } else {
                Cow::Owned(memo(&self.footprints, key, &self.metrics.performance, || {
                    self.metrics.performance.add(Perf::footprint_builds, 1);
                    Ok(footprint::Footprint::build(self.profile, &self.roots[root], self.request, ax, az))
                })?)
            };
            if !footprint.contains(x, z) {
                self.metrics.performance.add(Perf::footprint_pruned_writers, 1);
                return Ok(None);
            }
        }
        self.anchor(root, ax, az).map(Some)
    }
    fn structure(&self, root: usize, definition: usize, x: i32, z: i32) -> Result<Output, String> {
        let request = ChunkRequest {
            chunk_x: x,
            chunk_z: z,
            ..self.request
        };
        let starts = memo(&self.starts, (x, z), &self.metrics.performance, || {
            self.discovery.plans(self.engine, request, 1, &self.terrain)
        })?;
        let starts: Vec<_> = starts
            .iter()
            .filter(|s| s.definition == definition && s.affects(request))
            .cloned()
            .collect();
        if starts.is_empty() {
            return Ok(Output {
                writes: Vec::new(),
                columns: Map::default(),
                metadata: None,
            });
        }
        let base = self.terrain(x, z)?;
        let view = View {
            planner: self,
            before: root,
            base: base.clone(),
            columns: ViewColumns::default(),
        };
        crate::structures::prepare(self.engine, request, self.profile, &starts, Some(&view))?;
        let mut blocks = vec![0; request.block_count()];
        for dz in 0..16 {
            for dx in 0..16 {
                let column = self.column(root, x * 16 + dx as i32, z * 16 + dz as i32)?;
                for y in 0..request.height as usize {
                    blocks[y * 256 + dz * 16 + dx] = column.material(y);
                }
            }
        }
        let mut metadata = crate::structures::apply_tracked(
            request,
            self.profile,
            &starts,
            base.chunk(x, z),
            &mut blocks,
        );
        let mut columns: Map<_, Vec<_>> = Map::default();
        let writes = std::mem::take(&mut metadata.writes)
            .into_iter()
            .enumerate()
            .map(|(slot, (index, material))| {
                let bx = x * 16 + (index % 16) as i32;
                let bz = z * 16 + (index % 256 / 16) as i32;
                columns.entry((bx, bz)).or_default().push(slot);
                WorldBlock {
                    root_order: root as u32,
                    x: bx,
                    y: request.min_y + (index / 256) as i32,
                    z: bz,
                    material,
                    role: FEATURE,
                    upper: 0,
                    tick: 0,
                }
            })
            .collect();
        self.check()?;
        Ok(Output {
            writes,
            columns,
            metadata: Some(metadata),
        })
    }
    fn column(&self, before: usize, x: i32, z: i32) -> Result<Arc<ColumnState>, String> {
        let metrics = &self.metrics.performance;
        metrics.add(Perf::column_requests, 1);
        // The completed interior has all possible writers. An absent command is
        // an identity transformation, so share the preceding changed version.
        // Boundary columns keep their full demand-driven dependency closure.
        if before <= self.completed.len() {
            if let Some(column) = self.versions.index(x, z) {
                let (version, slot) = self.versions.before(column, before);
                metrics.add(Perf::column_versions_skipped, (before - version) as u64);
                metrics.add(Perf::completed_column_reads, 1);
                return self.column_cell(version, x, z, slot);
            }
        }
        let key = (before, x.div_euclid(16), z.div_euclid(16));
        let index = z.rem_euclid(16) as usize * 16 + x.rem_euclid(16) as usize;
        if let Some(tile) = self.ready_columns.get(&key) {
            metrics.add(Perf::completed_boundary_reads, 1);
            return self.column_cell(before, x, z, &tile.0[index]);
        }
        let lock_started = metrics.start();
        let tile = self
            .columns
            .shard(&key)
            .lock()
            .map_err(|_| "column dependency cache poisoned")?
            .entry(key)
            .or_insert_with(|| {
                metrics.add(Perf::column_tiles, 1);
                Arc::new(ColumnTile::default())
            })
            .clone();
        metrics.elapsed(Perf::column_lock_nanos, lock_started);
        self.column_cell(before, x, z, &tile.0[index])
    }
    fn column_cell(
        &self,
        before: usize,
        x: i32,
        z: i32,
        slot: &ColumnCell,
    ) -> Result<Arc<ColumnState>, String> {
        let metrics = &self.metrics.performance;
        let joined = metrics.start();
        let mut built = false;
        let result = slot.get_or_init(|| {
            built = true;
            metrics.add(Perf::column_builds, 1);
            self.build_column(before, x, z)
                .map_err(|error| Box::new(ColumnFailure(error)))
        });
        if !built {
            metrics.elapsed(Perf::column_join_nanos, joined);
        }
        result
            .as_ref()
            .map(Arc::clone)
            .map_err(|error| error.0.clone())
    }
    fn build_column(&self, before: usize, x: i32, z: i32) -> Result<Arc<ColumnState>, String> {
        let metrics = &self.metrics.performance;
        if before != 0 {
            return self.prefix(before, x, z, None);
        }
        let field = self.terrain(x.div_euclid(16), z.div_euclid(16))?;
        if let Some((mask, range)) = field
            .substrate
            .as_ref()
            .and_then(|mask| mask.material_run_range(x, z).map(|range| (mask, range)))
        {
            metrics.add(Perf::shared_material_columns, 1);
            return Ok(Arc::new(ColumnState::from_runs(
                ColumnRuns::Shared {
                    mask: mask.clone(),
                    range,
                },
                self.request.height as usize,
                self.profile,
                self.request.min_y,
            )));
        }
        let blocks = (self.request.min_y..self.request.min_y + self.request.height as i32)
            .map(|y| {
                field
                    .material(self.profile, self.request, [x, y, z])
                    .ok_or_else(|| format!("missing GPU substrate at {x},{y},{z}"))
            })
            .collect::<Result<Vec<_>, _>>()?;
        Ok(Arc::new(ColumnState::new(
            blocks,
            self.profile,
            self.request.min_y,
        )))
    }
    fn prefix(
        &self,
        before: usize,
        x: i32,
        z: i32,
        limit: Option<(i32, i32, usize)>,
    ) -> Result<Arc<ColumnState>, String> {
        self.metrics.performance.add(Perf::prefix_evaluations, 1);
        let prior = self.column(before - 1, x, z)?;
        let mut blocks: Option<Vec<u16>> = None;
        if let Some((root, column)) = self
            .completed
            .get(before - 1)
            .zip(self.versions.index(x, z))
        {
            for (key, block) in root.writes(column, x, z) {
                if limit.is_some_and(|limit| key >= limit) {
                    continue;
                }
                self.apply_column_write(
                    before,
                    x,
                    z,
                    key,
                    block,
                    &prior,
                    &mut blocks,
                )?;
            }
        } else {
            for (ax, az) in self.roots[before - 1].bounds.writers(x, z)? {
                if limit.is_some_and(|key| (az, ax) > (key.0, key.1)) {
                    continue;
                }
                let Some(output) = self.column_writer(before - 1, ax, az, x, z)? else {
                    continue;
                };
                for &index in output.columns.get(&(x, z)).into_iter().flatten() {
                    let key = (az, ax, index);
                    if limit.is_some_and(|limit| key >= limit) {
                        continue;
                    }
                    self.apply_column_write(
                        before,
                        x,
                        z,
                        key,
                        output.writes[index],
                        &prior,
                        &mut blocks,
                    )?;
                }
            }
        }
        if let Some(blocks) = blocks {
            let result = ColumnState::new(blocks, self.profile, self.request.min_y);
            if result.runs.slice() == prior.runs.slice() {
                Ok(prior)
            } else {
                Ok(Arc::new(result))
            }
        } else {
            Ok(prior)
        }
    }
    fn apply_column_write(
        &self,
        before: usize,
        x: i32,
        z: i32,
        key: (i32, i32, usize),
        b: WorldBlock,
        prior: &ColumnState,
        blocks: &mut Option<Vec<u16>>,
    ) -> Result<(), String> {
        let height = self.request.height as usize;
        let y = b.y - self.request.min_y;
        if y < 0 || y >= height as i32 {
            return Ok(());
        }
        let y = y as usize;
        let previous = blocks.as_ref().map_or_else(|| prior.material(y), |b| b[y]);
        let flags = self.profile.material_flags[previous as usize];
        let replace = match b.role {
            FEATURE => true,
            DEFERRED_ORE => previous == b.upper,
            SOIL => flags & 1 != 0 || previous == self.profile.snow,
            LOG => previous == 0 || flags & (4 | 16) != 0,
            _ => previous == 0,
        };
        if !replace {
            return Ok(());
        }
        if b.role == VINE || b.role == COCOA {
            let (dx, dz) = SIDES[b.upper as usize];
            let nx = x - dx;
            let nz = z - dz;
            let supported =
                if nx.div_euclid(16) != x.div_euclid(16) || nz.div_euclid(16) != z.div_euclid(16) {
                    true
                } else {
                    let neighbor = self.prefix(before, nx, nz, Some(key))?;
                    self.profile.material_flags[neighbor.material(y) as usize]
                        & if b.role == COCOA { 32 } else { 4 | 8 }
                        != 0
                };
            let hanging = b.role == VINE
                && y + 1 < height
                && blocks
                    .as_ref()
                    .map_or_else(|| prior.material(y + 1), |b| b[y + 1])
                    == b.material;
            if !supported && !hanging {
                return Ok(());
            }
        }
        if b.role == PLANT && b.upper != 0 {
            if y + 1 >= height
                || blocks
                    .as_ref()
                    .map_or_else(|| prior.material(y + 1), |b| b[y + 1])
                    != 0
            {
                return Ok(());
            }
            blocks.get_or_insert_with(|| prior.expand(height))[y + 1] = b.upper;
        }
        if previous != b.material {
            blocks.get_or_insert_with(|| prior.expand(height))[y] = b.material;
        }
        Ok(())
    }
}

pub(crate) fn generate(
    engine: &TerrainEngine,
    profile: &WorldProfile,
    request: ChunkRequest,
    side: usize,
    job: Option<&crate::timings::Timings>,
    mut details: Option<&mut diagnostics::Planning>,
    retain_prefix: bool,
    initial: Option<&Field<'_>>,
    pool: Option<&rayon::ThreadPool>,
) -> Result<super::generation::Generation, String> {
    let started = std::time::Instant::now();
    let metrics = engine.timings(request.reserved);
    let evaluation_started = metrics.performance.start();
    let diagnostic = std::env::var("RETINA_DEPENDENCY_DIAGNOSTICS").as_deref() == Ok("1");
    let mut groups = BTreeMap::<(u32, u8, u32), Root>::new();
    for (id, recipe) in profile.decorations.iter().enumerate() {
        if !profile
            .biomes
            .iter()
            .any(|b| b.decorations.contains(&(id as u32)))
        {
            continue;
        }
        let bounds = Bounds::recipe(recipe)?;
        let root = groups
            .entry((recipe.generation_step, 1, recipe.root_order))
            .or_insert(Root {
                definition: None,
                ids: Vec::new(),
                bounds,
                deferred_ore: None,
                footprint: false,
            });
        root.ids.push(id as u32);
        root.bounds.union(bounds);
    }
    for (id, definition) in profile.structures.definitions.iter().enumerate() {
        if definition.unimplemented
            || !profile
                .structures
                .sets
                .iter()
                .any(|s| s.entries.iter().any(|e| e.definition == id))
        {
            continue;
        }
        groups.insert(
            (crate::structures::step(definition)?, 0, id as u32),
            Root {
                ids: Vec::new(),
                definition: Some(id),
                deferred_ore: None,
                footprint: false,
                bounds: Bounds {
                    x: [0, 15],
                    z: [0, 15],
                },
            },
        );
    }
    for root in groups.values_mut() {
        root.deferred_ore = deferred_ore_pair(profile, &root.ids);
        root.footprint = footprint::eligible(profile, &root.ids);
    }
    metrics.performance.add(
        Perf::deferred_ore_roots,
        groups
            .values()
            .filter(|root| root.deferred_ore.is_some())
            .count() as u64,
    );
    let mut planner = Planner {
        engine,
        profile,
        request,
        roots: groups.into_values().collect(),
        terrain: crate::terrain_job::TerrainJob::new(engine, profile, request, initial),
        anchors: ShardedMap::default(),
        ready: Map::default(),
        footprints: Memo::default(),
        ready_footprints: Map::default(),
        completed: Vec::new(),
        versions: Versions::new(request, side),
        anchor_ids: ShardedMap::default(),
        starts: ShardedMap::default(),
        discovery: crate::structures::Discovery::new(&profile.structures),
        job,
        metrics: metrics.clone(),
        retain_prefix,
        columns: ShardedMap::default(),
        ready_columns: Map::default(),
        error: Mutex::new(None),
    };
    let mut targets = Vec::new();
    // Find all writers of the requested volume, including anchors beyond the old
    // fixed halo when a supported placement chain reaches farther.
    for root in 0..planner.roots.len() {
        // Return to the request's caller after each root. A waiting worker may
        // steal another root, but cannot inherit that request's remaining loop.
        let evaluated = dispatch_phase(pool, &metrics.performance, || -> Result<(), String> {
            let anchors = planner.roots[root].bounds.writers_area(
                [request.chunk_x * 16, request.chunk_z * 16],
                [
                    (request.chunk_x + side as i32) * 16 - 1,
                    (request.chunk_z + side as i32) * 16 - 1,
                ],
            )?;
            anchors
                .par_iter()
                .try_for_each(|&(ax, az)| planner.anchor(root, ax, az).map(|_| ()))?;
            planner.complete(root, &anchors)?;
            for (ax, az) in anchors {
                targets.push((root, ax, az));
            }
            Ok(())
        });
        if let Err(error) = evaluated {
            return dispatch_phase(pool, &metrics.performance, || {
                drop(planner);
                Err(error)
            });
        }
        if diagnostic {
            eprintln!(
                "Retina dependency root {root}/{}: anchors={}, column_tiles={}, tiles={}, elapsed_ms={:.1}, request={},{}",
                planner.roots.len(),
                planner.ready.len(),
                planner.columns.len() + planner.ready_columns.len(),
                planner.terrain.len(),
                started.elapsed().as_secs_f64() * 1000.,
                request.chunk_x,
                request.chunk_z
            );
        }
    }
    metrics
        .performance
        .elapsed(Perf::dependency_evaluation_nanos, evaluation_started);
    if let Some(details) = details.as_mut() {
        details.dependency_wall_nanos = started.elapsed().as_nanos() as u64;
        details.dependency_anchors = planner.ready.len() as u64;
        details.dependency_column_tiles =
            (planner.columns.len() + planner.ready_columns.len()) as u64;
        details.dependency_terrain_tiles = planner.terrain.len() as u64;
    }
    // Final planning, distribution and all retirement stay inside the bounded
    // pool and join before generation returns. No cleanup escapes the interval.
    dispatch_phase(pool, &metrics.performance, || {
        planner.check()?;
        let structure_started = metrics.performance.start();
        let structure_detail = diagnostics::start(details.is_some());
        let starts = planner
            .discovery
            .plans(engine, request, side as i32, &planner.terrain)?;
        metrics
            .performance
            .elapsed(Perf::final_structure_planning_nanos, structure_started);
        diagnostics::record(
            structure_detail,
            details.as_mut().map(|d| &mut d.final_structure_wall_nanos),
        );
        // Release immutable command references before consuming the outputs. This
        // cleanup is inside the measured region and contributes to teardown time.
        let release_started = metrics.performance.start();
        let release_detail = diagnostics::start(details.is_some());
        retire(
            std::mem::take(&mut planner.completed),
            &metrics.performance,
            Perf::teardown_commands_nanos,
        );
        retire(
            std::mem::take(&mut planner.versions.changed),
            &metrics.performance,
            Perf::teardown_versions_nanos,
        );
        metrics
            .performance
            .elapsed(Perf::teardown_nanos, release_started);
        diagnostics::record(
            release_detail,
            details.as_mut().map(|d| &mut d.teardown_wall_nanos),
        );
        let merge_started = diagnostics::start(details.is_some());
        let distribution_started = metrics.performance.start();
        let mut cache = std::mem::take(&mut planner.ready);
        let mut result = super::generation::Generation::new(request, side, profile, &starts);

        for key in targets {
            let output = cache
                .remove(&key)
                .ok_or("missing completed dependency anchor")?;
            let output = Arc::try_unwrap(output).map_err(|_| "dependency output still in use")?;
            for block in output.writes {
                result.push(request, side, block);
            }
            if let Some(metadata) = output.metadata {
                result.event(request, side, key.1, key.2, metadata);
            }
        }
        metrics
            .performance
            .elapsed(Perf::distribution_nanos, distribution_started);
        diagnostics::record(
            merge_started,
            details.as_mut().map(|d| &mut d.distribution_wall_nanos),
        );
        let teardown_started = metrics.performance.start();
        let teardown_detail = diagnostics::start(details.is_some());
        retire(cache, &metrics.performance, Perf::teardown_outputs_nanos);
        // Preserve normal field destruction order while measuring each group.
        let Planner {
            roots,
            terrain,
            anchors,
            ready,
            footprints,
            ready_footprints,
            completed,
            versions,
            anchor_ids,
            starts,
            discovery,
            columns,
            ready_columns,
            error,
            metrics: planner_metrics,
            ..
        } = planner;
        retire(roots, &metrics.performance, Perf::teardown_other_nanos);
        retire(terrain, &metrics.performance, Perf::teardown_terrain_nanos);
        retire(
            (anchors, ready, completed, footprints, ready_footprints),
            &metrics.performance,
            Perf::teardown_other_nanos,
        );
        retire(
            versions,
            &metrics.performance,
            Perf::teardown_versions_nanos,
        );
        retire(
            (anchor_ids, starts, discovery, planner_metrics),
            &metrics.performance,
            Perf::teardown_other_nanos,
        );
        let boundary_started = metrics.performance.start();
        // A column's identity versions retain the same state. Release those
        // references together instead of walking unrelated hash-map buckets.
        let mut boundary = ready_columns.into_iter().collect::<Vec<_>>();
        boundary.extend(columns.into_entries());
        boundary.sort_unstable_by_key(|((root, x, z), _)| (*z, *x, *root));
        drop(boundary);
        metrics
            .performance
            .elapsed(Perf::teardown_boundary_columns_nanos, boundary_started);
        retire(error, &metrics.performance, Perf::teardown_other_nanos);
        metrics
            .performance
            .elapsed(Perf::teardown_nanos, teardown_started);
        diagnostics::record(teardown_detail, details.map(|d| &mut d.teardown_wall_nanos));
        Ok(result)
    })
}

fn dispatch_phase<T: Send>(
    pool: Option<&rayon::ThreadPool>,
    metrics: &Metrics,
    run: impl FnOnce() -> T + Send,
) -> T {
    match pool {
        Some(pool) => {
            let queued = metrics.start();
            pool.install(|| {
                metrics.elapsed(Perf::dependency_pool_wait_nanos, queued);
                run()
            })
        }
        None => run(),
    }
}

fn retire<T>(value: T, metrics: &Metrics, counter: Perf) {
    let started = metrics.start();
    drop(value);
    metrics.elapsed(counter, started);
}

pub(crate) fn plan(
    engine: &TerrainEngine,
    profile: &WorldProfile,
    request: ChunkRequest,
    side: usize,
    job: Option<&crate::timings::Timings>,
    details: Option<&mut diagnostics::Planning>,
    retain_prefix: bool,
    initial: Option<&Field<'_>>,
) -> Result<Vec<Vec<Placement>>, String> {
    Ok(generate(
        engine,
        profile,
        request,
        side,
        job,
        details,
        retain_prefix,
        initial,
        None,
    )?
    .placements)
}

#[cfg(test)]
mod tests {
    use super::*;
    fn cached_column(material: u16) -> Arc<ColumnState> {
        Arc::new(ColumnState { runs: ColumnRuns::Owned(vec![u32::from(material)].into_boxed_slice()),
            heights: [i32::from(material); 6] })
    }
    #[test]
    fn view_slots_preserve_colliding_and_extreme_coordinate_keys() {
        let cache = ViewColumns::default();
        let keys: Vec<_> = (0..257).map(|i| (i - 128, 16 - i))
            .chain([(i32::MIN, i32::MAX), (i32::MAX, i32::MIN)]).collect();
        for (index, &(x, z)) in keys.iter().enumerate() {
            let material = index as u16 + 1;
            assert_eq!(cache.read(x, z, || Ok(cached_column(material)), |c| c.material(0)).unwrap(), material);
        }
        assert!(cache.values.lock().unwrap().len() > 0);
        for (index, &(x, z)) in keys.iter().enumerate().rev() {
            assert_eq!(cache.read(x, z, || panic!("cached column was lost"), |c| c.material(0)).unwrap(), index as u16 + 1);
        }
    }
    #[test]
    fn view_slots_allow_recursive_collision_loads_and_propagate_errors() {
        let cache = ViewColumns::default();
        let key = (0, 0);
        let collision = (1..1000).map(|x| (x, 0))
            .find(|&other| ViewColumns::index(other) == ViewColumns::index(key)).unwrap();
        let value = cache.read(key.0, key.1, || {
            assert_eq!(cache.read(collision.0, collision.1, || Ok(cached_column(2)), |c| c.material(0)).unwrap(), 2);
            Ok(cached_column(1))
        }, |c| c.material(0)).unwrap();
        assert_eq!(value, 1);
        for (key, material) in [(key, 1), (collision, 2)] {
            assert_eq!(cache.read(key.0, key.1, || panic!("recursive result lost"), |c| c.material(0)).unwrap(), material);
        }
        assert_eq!(cache.read(-1, -1, || Err("original load failure".into()), |c| c.material(0)), Err("original load failure".into()));
        assert_eq!(cache.read(-1, -1, || Ok(cached_column(3)), |c| c.material(0)).unwrap(), 3);
        let _ = std::panic::catch_unwind(|| {
            let _guard = cache.values.lock().unwrap();
            panic!("poison fallback map");
        });
        assert_eq!(cache.read(key.0, key.1, || panic!("must preserve fallback error"), |c| c.material(0)), Err("anchor view poisoned".into()));
    }
    #[test]
    fn view_slots_handle_concurrent_misses_and_account_for_all_reads() {
        let cache = ViewColumns { counts: Some(std::array::from_fn(|_| AtomicU64::new(0))), ..ViewColumns::default() };
        (0..4096).into_par_iter().for_each(|i| {
            let x: i32 = i % 257 - 128;
            let material = x.rem_euclid(127) as u16 + 1;
            assert_eq!(cache.read(x, -1, || Ok(cached_column(material)), |c| (c.material(0), c.heights[0])).unwrap(), (material, i32::from(material)));
        });
        for x in -128..=128 {
            assert_eq!(cache.read(x, -1, || panic!("concurrent value lost"), |c| c.material(0)).unwrap(), x.rem_euclid(127) as u16 + 1);
        }
        let counts = cache.counts.as_ref().unwrap();
        assert_eq!(counts[0].load(Ordering::Relaxed) + counts[1].load(Ordering::Relaxed), 4096 + 257);
        assert!(counts[2].load(Ordering::Relaxed) > 0);
    }
    #[test]
    fn view_slots_own_columns_only_until_their_independent_view_drops() {
        let first = ViewColumns::default();
        let second = ViewColumns::default();
        let column = cached_column(7);
        let weak = Arc::downgrade(&column);
        assert_eq!(first.read(-16, 32, || Ok(column), |c| c.material(0)).unwrap(), 7);
        assert_eq!(second.read(-16, 32, || Ok(cached_column(8)), |c| c.material(0)).unwrap(), 8);
        assert!(weak.upgrade().is_some());
        drop(first);
        assert!(weak.upgrade().is_none());
        assert_eq!(second.read(-16, 32, || panic!("independent view lost its state"), |c| c.material(0)).unwrap(), 8);
    }
    #[test]
    fn root_dispatch_does_not_inherit_remaining_phases_of_stolen_request() {
        use std::sync::atomic::{AtomicUsize, Ordering};
        let observed_before_resuming = |phased: bool| {
            let pool = Arc::new(rayon::ThreadPoolBuilder::new().num_threads(1).build().unwrap());
            let roots = Arc::new(AtomicUsize::new(0));
            let (started, ready) = std::sync::mpsc::channel();
            let waiting = {
                let pool = pool.clone();
                let roots = roots.clone();
                std::thread::spawn(move || pool.install(|| {
                    started.send(()).unwrap();
                    let deadline = std::time::Instant::now() + std::time::Duration::from_secs(10);
                    while roots.load(Ordering::SeqCst) == 0 {
                        assert!(std::time::Instant::now() < deadline, "queued phase never ran");
                        if rayon::yield_now() == Some(rayon::Yield::Idle) {
                            std::thread::yield_now();
                        }
                    }
                    roots.load(Ordering::SeqCst)
                }))
            };
            ready.recv_timeout(std::time::Duration::from_secs(10)).unwrap();
            let metrics = Metrics::default();
            if phased {
                for _ in 0..3 {
                    dispatch_phase(Some(&pool), &metrics, || {
                        roots.fetch_add(1, Ordering::SeqCst);
                    });
                }
            } else {
                dispatch_phase(Some(&pool), &metrics, || {
                    for _ in 0..3 {
                        roots.fetch_add(1, Ordering::SeqCst);
                    }
                });
            }
            let observed = waiting.join().unwrap();
            assert_eq!(roots.load(Ordering::SeqCst), 3);
            observed
        };
        // A cooperative wait executes a stolen task before it can resume. The
        // old whole-request task includes all later roots; phase dispatch does not.
        assert_eq!(observed_before_resuming(false), 3);
        assert_eq!(observed_before_resuming(true), 1);
    }
    #[test]
    fn sharded_memo_retains_one_initializer_and_failure_per_key() {
        use std::sync::atomic::{AtomicUsize, Ordering};
        let mut cache = Memo::default();
        let metrics = Metrics::default();
        let calls = AtomicUsize::new(0);
        (0..4096).into_par_iter().for_each(|i| {
            let key = (i % 64) - 32;
            let result = memo(&cache, key, &metrics, || {
                calls.fetch_add(1, Ordering::Relaxed);
                if key == 0 {
                    Err("original failure".to_owned())
                } else {
                    Ok(key)
                }
            });
            if key == 0 {
                assert_eq!(result.unwrap_err(), "original failure");
            } else {
                assert_eq!(*result.unwrap(), key);
            }
        });
        assert_eq!(calls.load(Ordering::Relaxed), 64);
        assert_eq!(cache.len(), 64);
        let completed = cache.take().unwrap();
        assert_eq!(cache.len(), 0);
        assert_eq!(completed.len(), 64);
        assert_eq!(
            completed[&0].get().unwrap().as_ref().unwrap_err(),
            "original failure"
        );
        for (key, cell) in completed {
            assert_eq!(Arc::strong_count(&cell), 1);
            if key != 0 {
                assert_eq!(**cell.get().unwrap().as_ref().unwrap(), key);
            }
        }
    }
    #[test]
    fn sharded_lookup_poison_is_an_error_and_cleanup_retains_entries() {
        let cache = Memo::default();
        let metrics = Metrics::default();
        memo(&cache, -19, &metrics, || Ok(7)).unwrap();
        std::thread::scope(|scope| {
            assert!(
                scope
                    .spawn(|| {
                        let _guard = cache.shard(&-19).lock().unwrap();
                        panic!("poison this shard");
                    })
                    .join()
                    .is_err()
            );
        });
        assert_eq!(
            memo(&cache, -19, &metrics, || Ok(9)).unwrap_err(),
            "decoration dependency cache poisoned"
        );
        assert_eq!(cache.into_entries().count(), 1);
    }
    #[test]
    fn inverse_writer_ranges_preserve_empty_and_overflow_results() {
        let outside = -((i32::MAX as i64 + 1) * 16);
        let empty = Bounds {
            x: [outside; 2],
            z: [1; 2],
        };
        assert_eq!(empty.writers(0, 0).unwrap().count(), 0);
        let invalid = Bounds {
            x: [outside; 2],
            z: [0; 2],
        };
        assert_eq!(
            invalid.writers(0, 0).err().as_deref(),
            Some("dependency anchor X overflows")
        );
        let invalid = Bounds {
            x: [0; 2],
            z: [outside; 2],
        };
        assert_eq!(
            invalid.writers(0, 0).err().as_deref(),
            Some("dependency anchor Z overflows")
        );
        let point = Bounds {
            x: [0; 2],
            z: [0; 2],
        };
        assert_eq!(
            point
                .writers(i32::MIN, i32::MIN)
                .unwrap()
                .collect::<Vec<_>>(),
            vec![(i32::MIN / 16, i32::MIN / 16)]
        );
    }
    #[test]
    fn grouped_columns_keep_anchor_and_global_command_order_for_prefix_limits() {
        let block = |x, material| WorldBlock {
            root_order: 0, x, y: 8, z: -16, material, upper: 0, role: FEATURE, tick: 0,
        };
        let root = CompletedRoot {
            anchors: vec![
                ((-2, -1), Arc::new(Output {
                    metadata: None,
                    writes: vec![block(-32, 1), block(-31, 2), block(-32, 3)],
                    columns: [((-32, -16), vec![0, 2]), ((-31, -16), vec![1])].into_iter().collect(),
                })),
                ((-1, -1), Arc::new(Output {
                    metadata: None, writes: vec![block(-32, 4)],
                    columns: [((-32, -16), vec![0])].into_iter().collect(),
                })),
            ],
            columns: [(0, vec![0, 1]), (1, vec![0])].into_iter().collect(),
        };
        assert_eq!(root.writes(0, -32, -16).map(|(key, b)| (key, b.material)).collect::<Vec<_>>(),
            vec![((-1, -2, 0), 1), ((-1, -2, 2), 3), ((-1, -1, 0), 4)]);
        let limit = (-1, -2, 2);
        assert_eq!(root.writes(0, -32, -16).filter(|(key, _)| *key < limit)
            .map(|(_, b)| b.material).collect::<Vec<_>>(), vec![1]);
        assert_eq!(root.writes(1, -31, -16).filter(|(key, _)| *key < limit)
            .map(|(_, b)| b.material).collect::<Vec<_>>(), vec![2]);
        assert_eq!(root.writes(2, -30, -16).count(), 0);
    }
    #[test]
    fn completed_versions_preserve_root_boundaries_and_negative_volume_edges() {
        let (_, _, mut request) = super::super::replay_tests::fixture(42, 0);
        request.chunk_x = -2;
        request.chunk_z = -1;
        let mut versions = Versions::new(request, 2);
        let first = versions.index(-32, -16).unwrap();
        let last = versions.index(-1, 15).unwrap();
        assert_eq!(first, 0);
        assert_eq!(last, 32 * 32 - 1);
        for (x, z) in [(-33, -16), (0, -16), (-32, -17), (-32, 16)] {
            assert_eq!(versions.index(x, z), None);
        }
        versions.changed[first] = [0, 3, 8]
            .into_iter()
            .map(|root| ColumnVersion {
                root,
                state: OnceLock::new(),
            })
            .collect();
        for (before, expected) in [(0, 0), (1, 1), (3, 1), (4, 4), (8, 4), (9, 9)] {
            assert_eq!(versions.before(first, before).0, expected);
            assert_eq!(versions.before(last, before).0, 0);
        }
    }
    #[test]
    fn deferred_ore_requires_uniform_roots_and_state_independent_placement() {
        let (mut profile, _, _) = super::super::replay_tests::fixture(42, 0);
        let mut recipe = profile.decorations[0].clone();
        recipe.feature = serde_json::from_value(serde_json::json!({
            "kind":"nether_feature", "nether":{"kind":"ore", "targets":[{
                "rule":{"kind":"material","allowed":[1]},"state":4}],
                "size":33,"discard":0.0,"scattered":false,"heightmap":2,"air":[0]}
        }))
        .unwrap();
        recipe.placement = Some(serde_json::from_value(serde_json::json!([
            {"type":"count","count":2},{"type":"in_square"},
            {"type":"height_range","height":{"type":"uniform", "min_inclusive":{"absolute":5},"max_inclusive":{"absolute":41}}},
            {"type":"biome"}
        ])).unwrap());
        profile.decorations = vec![recipe.clone(), recipe];
        assert_eq!(deferred_ore_pair(&profile, &[]), None);
        assert_eq!(deferred_ore_pair(&profile, &[0, 1]), Some((1, 4)));
        profile.decorations[1].placement = Some(
            serde_json::from_value(serde_json::json!([
            {"type":"heightmap","map":1}]))
            .unwrap(),
        );
        assert_eq!(deferred_ore_pair(&profile, &[0, 1]), None);
        profile.decorations[1] = profile.decorations[0].clone();
        if let Kind::NetherFeature { nether } = &mut profile.decorations[1].feature {
            *nether = serde_json::from_value(serde_json::json!({"kind":"ore", "targets":[{
                "rule":{"kind":"material","allowed":[1]},"state":2}],
                "size":33,"discard":0.0,"scattered":false,"heightmap":2,"air":[0]}))
            .unwrap();
        }
        assert_eq!(deferred_ore_pair(&profile, &[0, 1]), None);
        assert_eq!(deferred_ore_pair(&profile, &[0]), Some((1, 4)));
        profile.ordered_decorations = false;
        assert_eq!(deferred_ore_pair(&profile, &[0]), None);
    }
    #[test]
    fn encoded_columns_preserve_every_voxel_and_heightmap() {
        let (profile, _, _) = super::super::replay_tests::fixture(42, 0);
        for min_y in [-64, 0, 48] {
            for height in [1, 16, 128, 256, 4096] {
                for pattern in 0..4 {
                    let blocks: Vec<u16> = (0..height)
                        .map(|y| match pattern {
                            0 => 0,
                            1 => 1,
                            2 => (y % profile.materials.len()) as u16,
                            _ => ((y / 17) % profile.materials.len()) as u16,
                        })
                        .collect();
                    let encoded = ColumnState::new(blocks.clone(), &profile, min_y);
                    assert_eq!(encoded.expand(height), blocks);
                    for (y, &block) in blocks.iter().enumerate() {
                        assert_eq!(encoded.material(y), block);
                    }
                    for map in 0..6 {
                        let expected = blocks
                            .iter()
                            .rposition(|&m| profile.heightmap_masks[m as usize] & (1 << map) != 0)
                            .map_or(min_y, |y| min_y + y as i32 + 1);
                        assert_eq!(encoded.heights[map], expected);
                    }
                }
            }
        }
    }
    #[test]
    fn shared_material_runs_keep_the_owner_and_preserve_column_values() {
        let (profile, _, _) = super::super::replay_tests::fixture(42, 0);
        let blocks: Vec<u16> = (0..256)
            .map(|y| ((y / 17) % profile.materials.len()) as u16)
            .collect();
        let owned = ColumnState::new(blocks.clone(), &profile, -64);
        let mut words = vec![u32::MAX; 7];
        words.extend_from_slice(owned.runs.slice());
        let end = words.len();
        words.push(u32::MAX);
        let mask = Arc::new(crate::geology::CaveMask {
            origin_x: -32,
            origin_z: -32,
            min_y: -64,
            height: 256,
            width: 0,
            air_only: false,
            words,
            columns: None,
        });
        let shared = ColumnState::from_runs(
            ColumnRuns::Shared {
                mask: mask.clone(),
                range: 7..end,
            },
            256,
            &profile,
            -64,
        );
        drop(mask);
        assert_eq!(shared.expand(256), blocks);
        assert_eq!(shared.heights, owned.heights);
        for (y, block) in blocks.into_iter().enumerate() {
            assert_eq!(shared.material(y), block);
        }
    }
    #[test]
    fn area_writers_match_union_of_individual_columns() {
        let bounds = Bounds {
            x: [-7, 22],
            z: [31, 48],
        };
        for (min, max) in [
            ([-33, -17], [15, 31]),
            ([0, 0], [31, 31]),
            ([-1, -1], [-1, -1]),
        ] {
            let mut expected = std::collections::BTreeSet::new();
            for z in min[1]..=max[1] {
                for x in min[0]..=max[0] {
                    for (ax, az) in bounds.writers(x, z).unwrap() {
                        expected.insert((az, ax));
                    }
                }
            }
            assert_eq!(
                bounds.writers_area(min, max).unwrap(),
                expected
                    .into_iter()
                    .map(|(z, x)| (x, z))
                    .collect::<Vec<_>>()
            );
        }
    }
    #[test]
    fn writer_inverse_matches_world_coordinate_footprints() {
        for bounds in [
            Bounds {
                x: [-7, 22],
                z: [-3, 18],
            },
            Bounds {
                x: [15, 15],
                z: [-1, -1],
            },
            Bounds {
                x: [-64, 93],
                z: [0, 30],
            },
        ] {
            for x in -40..=40 {
                for z in -40..=40 {
                    let expected: Vec<_> = (-10..=10)
                        .flat_map(|az| {
                            (-10..=10).filter_map(move |ax| {
                                let dx = i64::from(x - ax * 16);
                                let dz = i64::from(z - az * 16);
                                (dx >= bounds.x[0]
                                    && dx <= bounds.x[1]
                                    && dz >= bounds.z[0]
                                    && dz <= bounds.z[1])
                                    .then_some((ax, az))
                            })
                        })
                        .collect();
                    assert_eq!(bounds.writers(x, z).unwrap().collect::<Vec<_>>(), expected);
                }
            }
        }
    }
}
