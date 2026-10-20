//! Demand-driven source-root dependencies. Each root reads the accepted result
//! of preceding roots; its anchor-local attempts keep their existing live overlay.
use super::spatial::Map;
use super::*;
use crate::TerrainEngine;
use crate::performance::{Counter as Perf, Metrics};
use std::collections::BTreeMap;
use std::sync::{Arc, Mutex, OnceLock};

type Memo<K, V> = Mutex<Map<K, Arc<OnceLock<Result<Arc<V>, String>>>>>;
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
    fn writers(self, x: i32, z: i32) -> Result<Vec<(i32, i32)>, String> {
        self.writers_area([x, z], [x, z])
    }
    fn writers_area(self, min: [i32; 2], max: [i32; 2]) -> Result<Vec<(i32, i32)>, String> {
        let ceil = |v: i64| -(-v).div_euclid(16);
        let mut result = Vec::new();
        for az in ceil(min[1] as i64 - self.z[1])..=(max[1] as i64 - self.z[0]).div_euclid(16) {
            for ax in ceil(min[0] as i64 - self.x[1])..=(max[0] as i64 - self.x[0]).div_euclid(16) {
                result.push((
                    i32::try_from(ax).map_err(|_| "dependency anchor X overflows")?,
                    i32::try_from(az).map_err(|_| "dependency anchor Z overflows")?,
                ));
            }
        }
        Ok(result)
    }
}
struct Root {
    definition: Option<usize>,
    ids: Vec<u32>,
    bounds: Bounds,
}
struct Output {
    metadata: Option<crate::structures::ChunkData>,
    writes: Vec<WorldBlock>,
    columns: Map<(i32, i32), Vec<usize>>,
}
/// Immutable commands from all possible writers of the requested volume.
/// Outputs remain in source anchor order; command indices preserve within-root
/// support reads such as vines and cocoa.
struct CompletedRoot {
    anchors: Vec<((i32, i32), Arc<Output>)>,
    columns: Map<usize, Vec<(usize, usize)>>,
}
type ColumnCell = OnceLock<Result<Arc<ColumnState>, String>>;
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

struct ColumnState {
    runs: Box<[u32]>,
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
        Self::from_runs(runs.into_boxed_slice(), blocks.len(), profile, min_y)
    }
    fn from_runs(runs: Box<[u32]>, height: usize, profile: &WorldProfile, min_y: i32) -> Self {
        let mut heights = [min_y; 6];
        let mut top = min_y + height as i32;
        for &run in &runs {
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
        self.runs[self.runs.partition_point(|run| run >> 16 > y as u32)] as u16
    }
    fn expand(&self, height: usize) -> Vec<u16> {
        let mut blocks = vec![0; height];
        let mut top = height;
        for &run in &self.runs {
            let start = (run >> 16) as usize;
            blocks[start..top].fill(run as u16);
            top = start;
        }
        blocks
    }
}
struct ColumnTile(Vec<OnceLock<Result<Arc<ColumnState>, String>>>);
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
    completed: Vec<CompletedRoot>,
    versions: Versions,
    anchor_ids: Memo<(i32, i32), Vec<u32>>,
    starts: Memo<(i32, i32), Vec<Arc<crate::structures::Start>>>,
    discovery: crate::structures::Discovery,
    job: Option<&'a crate::timings::Timings>,
    metrics: Arc<crate::timings::Timings>,
    retain_prefix: bool,
    columns: Mutex<Map<(usize, i32, i32), Arc<ColumnTile>>>,
    ready_columns: Map<(usize, i32, i32), Arc<ColumnTile>>,
    error: Mutex<Option<String>>,
}
struct View<'a, 'b> {
    planner: &'a Planner<'b>,
    before: usize,
    base: Arc<Field<'static>>,
    columns: Mutex<Map<(i32, i32), Arc<ColumnState>>>,
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
    fn column(&self, x: i32, z: i32) -> Result<Arc<ColumnState>, String> {
        if let Some(column) = self
            .columns
            .lock()
            .map_err(|_| "anchor view poisoned")?
            .get(&(x, z))
            .cloned()
        {
            return Ok(column);
        }
        let column = self.planner.column(self.before, x, z)?;
        self.columns
            .lock()
            .map_err(|_| "anchor view poisoned")?
            .insert((x, z), column.clone());
        Ok(column)
    }
}
impl PriorWorld for View<'_, '_> {
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
        match self.column(at[0], at[2]) {
            Ok(column) => Some(column.material((at[1] - self.planner.request.min_y) as usize)),
            Err(error) => {
                self.planner.fail(error);
                None
            }
        }
    }
    fn height(&self, x: i32, z: i32, map: u8) -> Option<i32> {
        match self.column(x, z) {
            Ok(column) => Some(column.heights[map as usize]),
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
        let pending = std::mem::take(
            self.anchors
                .get_mut()
                .map_err(|_| "dependency cache poisoned")?,
        );
        for (key, cell) in pending {
            let cell = Arc::try_unwrap(cell)
                .map_err(|_| "dependency cell still in use at root completion")?;
            let output = cell
                .into_inner()
                .ok_or("unfinished dependency at root completion")??;
            self.ready.insert(key, output);
        }
        // Existing tile slots are OnceLocks; the map itself becomes immutable
        // between root barriers, including slots first needed by later roots.
        self.ready_columns.extend(std::mem::take(
            self.columns
                .get_mut()
                .map_err(|_| "column dependency cache poisoned")?,
        ));
        let mut completed = CompletedRoot {
            anchors: Vec::with_capacity(targets.len()),
            columns: Map::default(),
        };
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
                        .extend(indices.iter().map(|&index| (anchor, index)));
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

    fn anchor(&self, root: usize, x: i32, z: i32) -> Result<Arc<Output>, String> {
        if let Some(output) = self.ready.get(&(root, x, z)) {
            self.metrics.performance.add(Perf::anchor_ready_hits, 1);
            return Ok(output.clone());
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
                    columns: Mutex::new(Map::default()),
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
                for (index, block) in writes.iter().enumerate() {
                    columns.entry((block.x, block.z)).or_default().push(index);
                }
                Ok(Output {
                    writes,
                    columns,
                    metadata: None,
                })
            },
        )
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
            columns: Mutex::new(Map::default()),
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
        let result = slot
            .get_or_init(|| {
                built = true;
                metrics.add(Perf::column_builds, 1);
                if before != 0 {
                    return self.prefix(before, x, z, None);
                }
                let field = self.terrain(x.div_euclid(16), z.div_euclid(16))?;
                if let Some(runs) = field.substrate.as_ref().and_then(|m| m.material_runs(x, z)) {
                    return Ok(Arc::new(ColumnState::from_runs(
                        runs.into(),
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
            })
            .clone();
        if !built {
            metrics.elapsed(Perf::column_join_nanos, joined);
        }
        result
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
            for &(anchor, index) in root.columns.get(&column).into_iter().flatten() {
                let ((ax, az), output) = &root.anchors[anchor];
                let key = (*az, *ax, index);
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
        } else {
            for (ax, az) in self.roots[before - 1].bounds.writers(x, z)? {
                if limit.is_some_and(|key| (az, ax) > (key.0, key.1)) {
                    continue;
                }
                let output = self.anchor(before - 1, ax, az)?;
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
            if result.runs == prior.runs {
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
                bounds: Bounds {
                    x: [0, 15],
                    z: [0, 15],
                },
            },
        );
    }
    let mut planner = Planner {
        engine,
        profile,
        request,
        roots: groups.into_values().collect(),
        terrain: crate::terrain_job::TerrainJob::new(engine, profile, request, initial),
        anchors: Mutex::new(Map::default()),
        ready: Map::default(),
        completed: Vec::new(),
        versions: Versions::new(request, side),
        anchor_ids: Mutex::new(Map::default()),
        starts: Mutex::new(Map::default()),
        discovery: crate::structures::Discovery::new(&profile.structures),
        job,
        metrics: metrics.clone(),
        retain_prefix,
        columns: Mutex::new(Map::default()),
        ready_columns: Map::default(),
        error: Mutex::new(None),
    };
    let mut targets = Vec::new();
    // Find all writers of the requested volume, including anchors beyond the old
    // fixed halo when a supported placement chain reaches farther.
    for root in 0..planner.roots.len() {
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
        if diagnostic {
            eprintln!(
                "Retina dependency root {root}/{}: anchors={}, column_tiles={}, tiles={}, elapsed_ms={:.1}",
                planner.roots.len(),
                planner.ready.len(),
                planner.columns.lock().unwrap().len() + planner.ready_columns.len(),
                planner.terrain.len(),
                started.elapsed().as_secs_f64() * 1000.
            );
        }
    }
    planner.check()?;
    metrics
        .performance
        .elapsed(Perf::dependency_evaluation_nanos, evaluation_started);
    if let Some(details) = details.as_mut() {
        details.dependency_wall_nanos = started.elapsed().as_nanos() as u64;
        details.dependency_anchors = planner.ready.len() as u64;
        details.dependency_column_tiles =
            (planner.columns.lock().unwrap().len() + planner.ready_columns.len()) as u64;
        details.dependency_terrain_tiles = planner.terrain.len() as u64;
    }
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
    drop(std::mem::take(&mut planner.completed));
    drop(std::mem::take(&mut planner.versions.changed));
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
    drop(cache);
    drop(planner);
    metrics
        .performance
        .elapsed(Perf::teardown_nanos, teardown_started);
    diagnostics::record(teardown_detail, details.map(|d| &mut d.teardown_wall_nanos));
    Ok(result)
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
    )?
    .placements)
}

#[cfg(test)]
mod tests {
    use super::*;
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
                    assert_eq!(bounds.writers(x, z).unwrap(), expected);
                }
            }
        }
    }
}
