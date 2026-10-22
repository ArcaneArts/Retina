//! Job-owned base terrain, shared by ordered dependencies and structure probes.
use crate::decoration::Field;
use crate::geology::CaveMask;
use crate::performance::Counter as Perf;
use crate::{ChunkRequest, TerrainEngine, WorldProfile};
use std::collections::HashMap as Map;
use std::sync::{Arc, Mutex, OnceLock};

struct Tile {
    field: Arc<Field<'static>>,
    mask: Option<Arc<CaveMask>>,
}
type TileCell = OnceLock<Result<Arc<Tile>, String>>;
type Cell = Arc<TileCell>;
const SLOT_WIDTH: usize = 8;
/// A small direct index covers the usual region neighborhood. Every coordinate
/// outside it still uses the unbounded job map; this is not a generation halo.
struct NearbyTiles {
    origin: [i32; 2],
    cells: [TileCell; SLOT_WIDTH * SLOT_WIDTH],
}
impl NearbyTiles {
    fn new(chunk_x: i32, chunk_z: i32) -> Self {
        Self {
            origin: [chunk_x.div_euclid(16) - 3, chunk_z.div_euclid(16) - 3],
            cells: std::array::from_fn(|_| OnceLock::new()),
        }
    }
    fn index(&self, x: i32, z: i32) -> Option<usize> {
        let x = i64::from(x.div_euclid(16)) - i64::from(self.origin[0]);
        let z = i64::from(z.div_euclid(16)) - i64::from(self.origin[1]);
        (x >= 0 && z >= 0 && x < SLOT_WIDTH as i64 && z < SLOT_WIDTH as i64)
            .then(|| z as usize * SLOT_WIDTH + x as usize)
    }
    fn len(&self) -> usize {
        self.cells
            .iter()
            .filter(|cell| cell.get().is_some())
            .count()
    }
}
pub(crate) struct TerrainJob<'a> {
    engine: &'a TerrainEngine,
    request: ChunkRequest,
    initial: Option<&'a Field<'a>>,
    nearby: NearbyTiles,
    cached: Map<(i32, i32), Arc<CaveMask>>,
    tiles: Mutex<Map<(i32, i32), Cell>>,
    metrics: Arc<crate::timings::Timings>,
}
impl<'a> TerrainJob<'a> {
    pub(crate) fn new(
        engine: &'a TerrainEngine,
        profile: &WorldProfile,
        request: ChunkRequest,
        initial: Option<&'a Field<'a>>,
    ) -> Self {
        // Older profiles may return material masks separately from the field.
        // Generate those tiles normally so fossil callers still receive a mask.
        let reusable = !profile
            .registry_program
            .as_ref()
            .is_some_and(|p| p.material_layers && !p.material_halo);
        let initial = initial.filter(|field| reusable && field.prior.is_none());
        let nearby = NearbyTiles::new(request.chunk_x, request.chunk_z);
        let metrics = engine.timings(request.reserved);
        let started = metrics.performance.start();
        let cached = if profile.decoration_patch_halo
            && profile.registry_program.as_ref().is_some_and(|p| p.material_halo) {
            crate::terrain_cache::capture_patch_tiles(engine, request, |x, z| {
                nearby.index(x, z).is_some()
                    && !initial.is_some_and(|field| covers(field, x - 1, z - 1, 18))
            })
        } else { Map::new() };
        metrics.performance.elapsed(Perf::terrain_job_snapshot_nanos, started);
        metrics.performance.add(Perf::terrain_job_cached_tiles_retained, cached.len() as u64);
        Self {
            engine, request, initial, nearby, cached,
            tiles: Mutex::new(Map::default()), metrics,
        }
    }
    pub(crate) fn len(&self) -> usize {
        self.nearby.len() + self.tiles.lock().unwrap_or_else(|e| e.into_inner()).len()
    }
    pub(crate) fn field(&self, x: i32, z: i32) -> Result<Arc<Field<'static>>, String> {
        Ok(self.tile(x, z)?.field.clone())
    }
    pub(crate) fn mask(&self, x: i32, z: i32) -> Result<Option<Arc<CaveMask>>, String> {
        Ok(self.tile(x, z)?.mask.clone())
    }
    fn tile(&self, x: i32, z: i32) -> Result<Arc<Tile>, String> {
        let x = x.div_euclid(16) * 16;
        let z = z.div_euclid(16) * 16;
        self.metrics.performance.add(Perf::terrain_job_requests, 1);
        if let Some(index) = self.nearby.index(x, z) {
            self.metrics
                .performance
                .add(Perf::terrain_job_direct_requests, 1);
            return self.initialize(&self.nearby.cells[index], x, z);
        }
        self.metrics
            .performance
            .add(Perf::terrain_job_fallback_requests, 1);
        let lock_started = self.metrics.performance.start();
        let cell = self
            .tiles
            .lock()
            .map_err(|_| "job terrain cache poisoned")?
            .entry((x, z))
            .or_insert_with(|| Arc::new(OnceLock::new()))
            .clone();
        self.metrics
            .performance
            .elapsed(Perf::terrain_job_lock_nanos, lock_started);
        self.initialize(&cell, x, z)
    }
    fn cached_tile(&self, x: i32, z: i32) -> Result<Option<(Field<'static>, Option<Arc<CaveMask>>)>, String> {
        let Some(mask) = self.cached.get(&(x, z)) else { return Ok(None) };
        let request = ChunkRequest { chunk_x: x, chunk_z: z, ..self.request };
        let Some(field) = self.engine.cached_patch_field(request, 16, mask)? else { return Ok(None) };
        self.metrics.performance.add(Perf::terrain_job_cached_tile_hits, 1);
        Ok(Some((field, Some(mask.clone()))))
    }
    fn initialize(&self, cell: &TileCell, x: i32, z: i32) -> Result<Arc<Tile>, String> {
        let joined = self.metrics.performance.start();
        #[cfg(test)]
        let span = crate::decoration::dependencies::worker_phase_probe::TerrainSpan::start(
            cell.get().is_none(), "terrain_job_abandoned", x, z);
        let mut built = false;
        let result = cell
            .get_or_init(|| {
                built = true;
                let (field, mask) = if let Some(initial) =
                    self.initial.filter(|field| covers(field, x - 1, z - 1, 18))
                {
                    let mut columns = Vec::with_capacity(18 * 18 * crate::COLUMNS);
                    for dz in -1..=16 {
                        for dx in -1..=16 {
                            columns.extend_from_slice(initial.chunk(x + dx, z + dz));
                        }
                    }
                    self.metrics
                        .performance
                        .add(Perf::terrain_initial_tiles_reused, 1);
                    (
                        Field {
                            origin_x: x - 1,
                            origin_z: z - 1,
                            side: 18,
                            columns,
                            substrate: initial.substrate.clone(),
                            prior: None,
                        },
                        initial.substrate.clone(),
                    )
                } else if let Some(cached) = self.cached_tile(x, z)? {
                    cached
                } else {
                    self.engine.terrain_field(
                        ChunkRequest {
                            chunk_x: x,
                            chunk_z: z,
                            ..self.request
                        },
                        16,
                    )?
                };
                Ok(Arc::new(Tile {
                    field: Arc::new(field),
                    mask,
                }))
            })
            .clone();
        #[cfg(test)]
        span.finish(if built { "terrain_job_initialize" } else { "terrain_job_join" }, result.is_ok());
        if !built {
            self.metrics
                .performance
                .elapsed(Perf::terrain_job_join_nanos, joined);
        }
        result
    }
}
fn covers(field: &Field<'_>, x: i32, z: i32, side: usize) -> bool {
    let dx = i64::from(x) - i64::from(field.origin_x);
    let dz = i64::from(z) - i64::from(field.origin_z);
    dx >= 0
        && dz >= 0
        && dx + side as i64 <= field.side as i64
        && dz + side as i64 <= field.side as i64
}
#[cfg(test)]
mod tests {
    use super::*;
    #[test]
    fn direct_slots_are_unique_across_negative_boundaries_and_fall_back_outside() {
        for origin in [-65, -16, -1, 0, 17, i32::MIN, i32::MAX] {
            let slots = NearbyTiles::new(origin, origin);
            let mut seen = std::collections::HashSet::new();
            for z in -1..=SLOT_WIDTH as i64 {
                for x in -1..=SLOT_WIDTH as i64 {
                    let cx = (i64::from(slots.origin[0]) + x) * 16;
                    let cz = (i64::from(slots.origin[1]) + z) * 16;
                    let (Ok(cx), Ok(cz)) = (i32::try_from(cx), i32::try_from(cz)) else {
                        continue;
                    };
                    let actual = slots.index(cx, cz);
                    if x >= 0 && z >= 0 && x < SLOT_WIDTH as i64 && z < SLOT_WIDTH as i64 {
                        assert!(seen.insert(actual.unwrap()));
                        assert_eq!(actual, Some(z as usize * SLOT_WIDTH + x as usize));
                        if let (Some(cx), Some(cz)) = (cx.checked_add(15), cz.checked_add(15)) {
                            assert_eq!(slots.index(cx, cz), actual);
                        }
                    } else {
                        assert_eq!(actual, None);
                    }
                }
            }
            assert_eq!(slots.len(), 0);
        }
    }
    #[test]
    fn initial_reuse_requires_the_entire_dependency_tile_and_halo() {
        let field = Field {
            origin_x: -33,
            origin_z: -1,
            side: 34,
            columns: Vec::new(),
            substrate: None,
            prior: None,
        };
        for (x, z) in [(-33, -1), (-17, -1), (-33, 15), (-17, 15)] {
            assert!(covers(&field, x, z, 18));
        }
        for (x, z) in [(-34, -1), (-16, -1), (-33, -2), (-33, 16)] {
            assert!(!covers(&field, x, z, 18));
        }
    }
}
