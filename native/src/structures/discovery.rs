//! Job-owned discovery tiles retain start cells until the last consumer finishes.
//! Tile construction is inline: yielding inside a dependency OnceLock can steal
//! a Rayon task which depends on the suspended initializer.
use super::*;
use std::sync::Mutex;

const SIDE: i32 = 16;
type Tile = Arc<OnceLock<Result<Vec<PlannedStart>, String>>>;

pub(crate) struct Discovery {
    halos: Vec<i32>,
    cells: Mutex<HashMap<StartKey, StartCell>>,
    tiles: Mutex<HashMap<(CacheKey, usize), Tile>>,
}
impl Discovery {
    pub(crate) fn new(profile: &Profile) -> Self {
        Self {
            halos: profile
                .sets
                .iter()
                .map(|set| {
                    if set.placement_only {
                        0
                    } else {
                        candidate_halo(profile, set)
                    }
                })
                .collect(),
            cells: Mutex::new(HashMap::new()),
            tiles: Mutex::new(HashMap::new()),
        }
    }
    pub(super) fn cell(
        &self,
        cache: &mut Cache,
        key: StartKey,
        metrics: &crate::performance::Metrics,
    ) -> Result<StartCell, String> {
        let mut cells = self
            .cells
            .lock()
            .map_err(|_| "job structure cache poisoned")?;
        if let Some(cell) = cells.get(&key) {
            metrics.add(crate::performance::Counter::structure_job_cache_hits, 1);
            return Ok(cell.clone());
        }
        let cell = cache.cell(key, Some(metrics));
        cells.insert(key, cell.clone());
        Ok(cell)
    }
    pub(crate) fn plans(
        &self,
        engine: &TerrainEngine,
        request: ChunkRequest,
        side: i32,
        terrain: &crate::terrain_job::TerrainJob<'_>,
    ) -> Result<Vec<Arc<Start>>, String> {
        let metrics = engine.timings(request.reserved);
        let mut selected = Vec::new();
        for (set, &halo) in self.halos.iter().enumerate() {
            if halo == 0 {
                continue;
            } // Exclusion-only sets have no starts.
            for tz in (request.chunk_z - halo).div_euclid(SIDE)
                ..=(request.chunk_z + side + halo).div_euclid(SIDE)
            {
                for tx in (request.chunk_x - halo).div_euclid(SIDE)
                    ..=(request.chunk_x + side + halo).div_euclid(SIDE)
                {
                    let tile_request = ChunkRequest {
                        chunk_x: tx * SIDE,
                        chunk_z: tz * SIDE,
                        ..request
                    };
                    let tile = self
                        .tiles
                        .lock()
                        .map_err(|_| "structure discovery tiles poisoned")?
                        .entry((CacheKey::from(tile_request), set))
                        .or_insert_with(|| Arc::new(OnceLock::new()))
                        .clone();
                    let starts = tile
                        .get_or_init(|| {
                            metrics
                                .performance
                                .add(crate::performance::Counter::structure_discovery_tiles, 1);
                            plans_inner(
                                engine,
                                tile_request,
                                SIDE,
                                false,
                                Some(self),
                                Some(set),
                                Some(terrain),
                            )
                        })
                        .as_ref()
                        .map_err(Clone::clone)?;
                    for (_, start) in starts {
                        if within(request, side, halo, start.chunk) {
                            selected.push(((set, start.chunk), start.clone()));
                        }
                    }
                }
            }
        }
        // Original discovery iterates sets first, then stable-sorts starts.
        // Retain that tie order even if a custom profile repeats a definition.
        selected.sort_by_key(|((set, _), s)| (s.chunk[1], s.chunk[0], s.definition, *set));
        Ok(selected.into_iter().map(|(_, start)| start).collect())
    }
}

fn within(request: ChunkRequest, side: i32, halo: i32, at: [i32; 2]) -> bool {
    at[0] >= request.chunk_x - halo
        && at[0] <= request.chunk_x + side + halo
        && at[1] >= request.chunk_z - halo
        && at[1] <= request.chunk_z + side + halo
}

#[cfg(test)]
mod tests {
    use super::*;
    fn request(x: i32, z: i32) -> ChunkRequest {
        ChunkRequest {
            seed: 1,
            chunk_x: x,
            chunk_z: z,
            min_y: 0,
            height: 256,
            base_height: 64.,
            amplitude: 48.,
            frequency: 0.008,
            reserved: 0,
        }
    }
    #[test]
    fn job_pins_survive_recent_cache_eviction_and_share_with_other_jobs() {
        let mut cache = Cache::default();
        let first = Discovery::new(&Profile::default());
        let second = Discovery::new(&Profile::default());
        let metrics = crate::timings::Timings::default();
        let key = (CacheKey::from(request(-32, 17)), 0);
        let pinned = first.cell(&mut cache, key, &metrics.performance).unwrap();
        for x in 0..1024 {
            cache.cell((CacheKey::from(request(x, 0)), 0), None);
        }
        assert!(!cache.entries.contains_key(&key));
        assert!(Arc::ptr_eq(
            &pinned,
            &second.cell(&mut cache, key, &metrics.performance).unwrap()
        ));
        drop(first);
        drop(second);
        drop(pinned);
        for x in 1024..2048 {
            cache.cell((CacheKey::from(request(x, 0)), 0), None);
        }
        assert!(!cache.active.contains_key(&key));
        assert!(cache.active.len() <= 512);
    }
    #[test]
    fn tiled_coverage_contains_every_original_candidate_across_zero_and_boundaries() {
        for origin in [-33, -32, -17, -16, -1, 0, 15, 16, 31] {
            for side in [1, 2, 16, 32] {
                for halo in [1, 3, 11, 30] {
                    let r = request(origin, -origin);
                    for z in r.chunk_z - halo..=r.chunk_z + side + halo {
                        for x in r.chunk_x - halo..=r.chunk_x + side + halo {
                            let owners = ((r.chunk_z - halo).div_euclid(SIDE)
                                ..=(r.chunk_z + side + halo).div_euclid(SIDE))
                                .map(|tz| {
                                    ((r.chunk_x - halo).div_euclid(SIDE)
                                        ..=(r.chunk_x + side + halo).div_euclid(SIDE))
                                        .filter(|&tx| {
                                            within(
                                                request(tx * SIDE, tz * SIDE),
                                                SIDE - 1,
                                                0,
                                                [x, z],
                                            )
                                        })
                                        .count()
                                })
                                .sum::<usize>();
                            assert_eq!(owners, 1, "candidate {x},{z} must have exactly one owner");
                        }
                    }
                }
            }
        }
    }
}
