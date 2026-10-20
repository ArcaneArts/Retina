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
type Cell = Arc<OnceLock<Result<Arc<Tile>, String>>>;
pub(crate) struct TerrainJob<'a> {
    engine: &'a TerrainEngine,
    request: ChunkRequest,
    initial: Option<&'a Field<'a>>,
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
        Self {
            engine,
            request,
            initial: initial.filter(|field| reusable && field.prior.is_none()),
            tiles: Mutex::new(Map::default()),
            metrics: engine.timings(request.reserved),
        }
    }
    pub(crate) fn len(&self) -> usize {
        self.tiles.lock().unwrap_or_else(|e| e.into_inner()).len()
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
        let cell = self
            .tiles
            .lock()
            .map_err(|_| "job terrain cache poisoned")?
            .entry((x, z))
            .or_insert_with(|| Arc::new(OnceLock::new()))
            .clone();
        cell.get_or_init(|| {
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
        .clone()
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
