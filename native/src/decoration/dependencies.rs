//! Demand-driven source-root dependencies. Each root reads the accepted result
//! of preceding roots; its anchor-local attempts keep their existing live overlay.
use super::spatial::Map;
use super::*;
use crate::TerrainEngine;
use std::collections::BTreeMap;
use std::sync::{Arc, Mutex, OnceLock};

type Memo<K, V> = Mutex<Map<K, Arc<OnceLock<Result<Arc<V>, String>>>>>;
fn memo<K: Eq + std::hash::Hash, V>(
    cache: &Memo<K, V>,
    key: K,
    build: impl FnOnce() -> Result<V, String>,
) -> Result<Arc<V>, String> {
    memo_arc(cache, key, || build().map(Arc::new))
}
fn memo_arc<K: Eq + std::hash::Hash, V>(
    cache: &Memo<K, V>,
    key: K,
    build: impl FnOnce() -> Result<Arc<V>, String>,
) -> Result<Arc<V>, String> {
    let slot = cache
        .lock()
        .map_err(|_| "decoration dependency cache poisoned")?
        .entry(key)
        .or_insert_with(|| Arc::new(OnceLock::new()))
        .clone();
    slot.get_or_init(build).clone()
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
    ids: Vec<u32>,
    bounds: Bounds,
}
struct Output {
    writes: Vec<WorldBlock>,
    columns: Map<(i32, i32), Vec<usize>>,
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
    terrain: Memo<(i32, i32), Field<'static>>,
    anchors: Memo<(usize, i32, i32), Output>,
    anchor_ids: Memo<(i32, i32), Vec<u32>>,
    job: Option<&'a crate::timings::Timings>,
    retain_prefix: bool,
    columns: Mutex<Map<(usize, i32, i32), Arc<ColumnTile>>>,
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
    fn fail(&self, error: String) {
        self.error.lock().unwrap().get_or_insert(error);
    }
    fn check(&self) -> Result<(), String> {
        self.error.lock().unwrap().clone().map_or(Ok(()), Err)
    }
    fn terrain(&self, x: i32, z: i32) -> Result<Arc<Field<'static>>, String> {
        let (x, z) = (x.div_euclid(16) * 16, z.div_euclid(16) * 16);
        memo(&self.terrain, (x, z), || {
            self.engine
                .terrain_field(
                    ChunkRequest {
                        chunk_x: x,
                        chunk_z: z,
                        ..self.request
                    },
                    16,
                )
                .map(|v| v.0)
        })
    }
    fn anchor(&self, root: usize, x: i32, z: i32) -> Result<Arc<Output>, String> {
        memo(&self.anchors, (root, x, z), || {
            let base = self.terrain(x, z)?;
            let available = memo(&self.anchor_ids, (x, z), || {
                Ok(anchor_ids(&base, self.profile, x, z))
            })?;
            let ids = [available
                .iter()
                .copied()
                .filter(|id| self.roots[root].ids.contains(id))
                .collect::<Vec<_>>()];
            if ids[0].is_empty() {
                return Ok(Output {
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
            let mut replay = Replay::new(&field, self.profile, request, x, z, 1, None, Some(&ids))
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
                self.engine
                    .pipeline
                    .transfer(request.reserved, sampled.upload, sampled.readback);
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
            Ok(Output { writes, columns })
        })
    }
    fn column(&self, before: usize, x: i32, z: i32) -> Result<Arc<ColumnState>, String> {
        let tile = self
            .columns
            .lock()
            .map_err(|_| "column dependency cache poisoned")?
            .entry((before, x.div_euclid(16), z.div_euclid(16)))
            .or_insert_with(|| Arc::new(ColumnTile::default()))
            .clone();
        let index = z.rem_euclid(16) as usize * 16 + x.rem_euclid(16) as usize;
        tile.0[index]
            .get_or_init(|| {
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
            .clone()
    }
    fn prefix(
        &self,
        before: usize,
        x: i32,
        z: i32,
        limit: Option<(i32, i32, usize)>,
    ) -> Result<Arc<ColumnState>, String> {
        let prior = self.column(before - 1, x, z)?;
        let mut blocks: Option<Vec<u16>> = None;
        let height = self.request.height as usize;
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
                let b = output.writes[index];
                let y = b.y - self.request.min_y;
                if y < 0 || y >= height as i32 {
                    continue;
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
                    continue;
                }
                if b.role == VINE || b.role == COCOA {
                    let (dx, dz) = SIDES[b.upper as usize];
                    let nx = x - dx;
                    let nz = z - dz;
                    let supported = if nx.div_euclid(16) != x.div_euclid(16)
                        || nz.div_euclid(16) != z.div_euclid(16)
                    {
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
                        continue;
                    }
                }
                if b.role == PLANT && b.upper != 0 {
                    if y + 1 >= height
                        || blocks
                            .as_ref()
                            .map_or_else(|| prior.material(y + 1), |b| b[y + 1])
                            != 0
                    {
                        continue;
                    }
                    blocks.get_or_insert_with(|| prior.expand(height))[y + 1] = b.upper;
                }
                if previous != b.material {
                    blocks.get_or_insert_with(|| prior.expand(height))[y] = b.material;
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
}

pub(crate) fn plan(
    engine: &TerrainEngine,
    profile: &WorldProfile,
    request: ChunkRequest,
    side: usize,
    job: Option<&crate::timings::Timings>,
    mut details: Option<&mut diagnostics::Planning>,
    retain_prefix: bool,
) -> Result<Vec<Vec<Placement>>, String> {
    let started = std::time::Instant::now();
    let diagnostic = std::env::var("RETINA_DEPENDENCY_DIAGNOSTICS").as_deref() == Ok("1");
    let mut groups = BTreeMap::<u32, Root>::new();
    for (id, recipe) in profile.decorations.iter().enumerate() {
        if !profile
            .biomes
            .iter()
            .any(|b| b.decorations.contains(&(id as u32)))
        {
            continue;
        }
        let bounds = Bounds::recipe(recipe)?;
        let root = groups.entry(recipe.root_order).or_insert(Root {
            ids: Vec::new(),
            bounds,
        });
        root.ids.push(id as u32);
        root.bounds.union(bounds);
    }
    let planner = Planner {
        engine,
        profile,
        request,
        roots: groups.into_values().collect(),
        terrain: Mutex::new(Map::default()),
        anchors: Mutex::new(Map::default()),
        anchor_ids: Mutex::new(Map::default()),
        job,
        retain_prefix,
        columns: Mutex::new(Map::default()),
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
        for (ax, az) in anchors {
            targets.push((root, ax, az));
        }
        if diagnostic {
            eprintln!(
                "Retina dependency root {root}/{}: anchors={}, column_tiles={}, tiles={}, elapsed_ms={:.1}",
                planner.roots.len(),
                planner.anchors.lock().unwrap().len(),
                planner.columns.lock().unwrap().len(),
                planner.terrain.lock().unwrap().len(),
                started.elapsed().as_secs_f64() * 1000.
            );
        }
    }
    planner.check()?;
    if let Some(details) = details.as_mut() {
        details.dependency_wall_nanos = started.elapsed().as_nanos() as u64;
        details.dependency_anchors = planner.anchors.lock().unwrap().len() as u64;
        details.dependency_column_tiles = planner.columns.lock().unwrap().len() as u64;
        details.dependency_terrain_tiles = planner.terrain.lock().unwrap().len() as u64;
    }
    let merge_started = diagnostics::start(details.is_some());
    let mut cache = std::mem::take(
        &mut *planner
            .anchors
            .lock()
            .map_err(|_| "dependency cache poisoned")?,
    );
    let mut outputs = Vec::with_capacity(targets.len());
    for key in targets {
        let slot = Arc::try_unwrap(
            cache
                .remove(&key)
                .ok_or("missing completed dependency anchor")?,
        )
        .map_err(|_| "dependency anchor still in use")?;
        let output = slot.into_inner().ok_or("unfinished dependency anchor")??;
        outputs.push(
            Arc::try_unwrap(output)
                .map_err(|_| "dependency output still in use")?
                .writes,
        );
    }
    drop(cache);
    drop(planner);
    let result = distribution::distribute(
        outputs,
        request,
        request.chunk_x,
        request.chunk_z,
        side,
        true,
    );
    diagnostics::record(
        merge_started,
        details.map(|d| &mut d.distribution_wall_nanos),
    );
    Ok(result)
}

#[cfg(test)]
mod tests {
    use super::*;
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
