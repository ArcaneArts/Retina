//! Read-only point queries. No region, decoration, block assembly or NBT path.
use crate::gpu::GpuSample;
use crate::profile::Column;
use crate::{CacheKey, ChunkRequest, Job, TerrainEngine, boundary, shared_engine};
use std::collections::{HashMap, VecDeque};
use std::sync::mpsc;
use std::time::Instant;

// Density/interpolation scratch stays bounded even for wide datapack graphs.
pub const BATCH: usize = 64;
#[repr(C)]
#[derive(Clone, Copy, Debug, Hash, Eq, PartialEq)]
pub struct Point {
    pub x: i32,
    pub y: i32,
    pub z: i32,
}
#[derive(Clone, Copy, Hash, Eq, PartialEq)]
pub enum Kind {
    Biome,
    Height,
}
#[derive(Clone, Copy, Hash, Eq, PartialEq)]
struct Key {
    request: CacheKey,
    point: Point,
    kind: Kind,
}
#[derive(Default)]
pub(crate) struct Cache {
    entries: HashMap<Key, Column>,
    order: VecDeque<Key>,
}
impl Cache {
    fn insert(&mut self, key: Key, column: Column) {
        if self.entries.insert(key, column).is_none() {
            self.order.push_back(key);
        }
        while self.entries.len() > 8192 {
            if let Some(key) = self.order.pop_front() {
                self.entries.remove(&key);
            }
        }
    }
}
impl TerrainEngine {
    pub fn query_points(
        &self,
        request: ChunkRequest,
        points: &[Point],
        kind: Kind,
    ) -> Result<Vec<Column>, String> {
        request.validate()?;
        if request.min_y.rem_euclid(4) != 0 || request.height % 4 != 0 {
            return Err("point queries require quart-aligned vertical bounds".into());
        }
        let mut output = Vec::with_capacity(points.len());
        for points in points.chunks(BATCH) {
            let points: Vec<_> = points
                .iter()
                .map(|p| {
                    let p = match kind {
                        Kind::Biome => Point {
                            x: p.x.div_euclid(4) * 4,
                            y: p.y
                                .clamp(request.min_y, request.min_y + request.height as i32 - 1)
                                .div_euclid(4)
                                * 4,
                            z: p.z.div_euclid(4) * 4,
                        },
                        Kind::Height => Point {
                            y: request.min_y,
                            ..*p
                        },
                    };
                    p.x.div_euclid(4)
                        .checked_mul(4)
                        .and_then(|v| v.checked_sub(8))
                        .ok_or("query X overflows")?;
                    p.z.div_euclid(4)
                        .checked_mul(4)
                        .and_then(|v| v.checked_sub(8))
                        .ok_or("query Z overflows")?;
                    Ok(p)
                })
                .collect::<Result<_, String>>()?;
            let keys: Vec<_> = points
                .iter()
                .map(|p| Key {
                    request: CacheKey::from(ChunkRequest {
                        chunk_x: p.x.div_euclid(16),
                        chunk_z: p.z.div_euclid(16),
                        ..request
                    }),
                    point: *p,
                    kind,
                })
                .collect();
            let mut missing = Vec::new();
            let mut pending = HashMap::new();
            let mut result = vec![None; points.len()];
            {
                let cache = self
                    .query_cache
                    .lock()
                    .map_err(|_| "point query cache poisoned")?;
                for (i, key) in keys.iter().enumerate() {
                    if let Some(column) = cache.entries.get(key) {
                        result[i] = Some(*column);
                    } else if !pending.contains_key(key) {
                        pending.insert(*key, missing.len());
                        missing.push(points[i]);
                    }
                }
            }
            if !missing.is_empty() {
                let requests: Vec<_> = missing
                    .iter()
                    .map(|p| ChunkRequest {
                        chunk_x: p.x.div_euclid(16),
                        chunk_z: p.z.div_euclid(16),
                        ..request
                    })
                    .collect();
                let profile = self.profile(request.reserved)?;
                let underground = profile.as_deref().is_some_and(|p| {
                    p.geology.caves_enabled(p)
                        || p.registry_program
                            .as_ref()
                            .is_some_and(|r| r.material_layers)
                });
                let (reply, receiver) = mpsc::channel();
                self.sender
                    .send(Job {
                        requests,
                        profile,
                        tile_side: 0,
                        cave_side: 0,
                        probe_mode: match kind {
                            Kind::Biome if underground => 2,
                            Kind::Biome => 1,
                            Kind::Height => 4,
                        },
                        probe_y: missing.iter().map(|p| p.y).collect(),
                        probe_points: missing,
                        reply,
                        queued: Instant::now(),
                        queue_nanos: 0,
                        timings: self.timings(request.reserved),
                    })
                    .map_err(|_| "GPU worker stopped")?;
                let GpuSample { columns, .. } = receiver
                    .recv()
                    .map_err(|_| "GPU worker stopped before completing point queries")??;
                let mut cache = self
                    .query_cache
                    .lock()
                    .map_err(|_| "point query cache poisoned")?;
                for (i, key) in keys.iter().enumerate() {
                    if result[i].is_none() {
                        let column = columns[pending[key]];
                        cache.insert(*key, column);
                        result[i] = Some(column);
                    }
                }
            }
            output.extend(result.into_iter().map(Option::unwrap));
        }
        Ok(output)
    }
}

/// request is readable; points/output contain count elements. Output is a biome
/// ID for kind 0 or a pre-decoration terrain height for kind 1 (including lakes).
#[unsafe(no_mangle)]
pub unsafe extern "C" fn retina_query_points(
    request: *const ChunkRequest,
    points: *const Point,
    count: u64,
    kind: u32,
    output: *mut i32,
) -> i32 {
    boundary(|| {
        if request.is_null() || (count > 0 && (points.is_null() || output.is_null())) {
            return Err("null point query buffer".into());
        }
        if count > 1024 {
            return Err("point query batch exceeds 1024".into());
        }
        let kind = match kind {
            0 => Kind::Biome,
            1 => Kind::Height,
            _ => return Err("unknown point query kind".into()),
        };
        if count == 0 {
            return Ok(());
        }
        let request = unsafe { *request };
        let points = unsafe { std::slice::from_raw_parts(points, count as usize) };
        let out = unsafe { std::slice::from_raw_parts_mut(output, count as usize) };
        let columns = shared_engine()?.query_points(request, points, kind)?;
        for (out, column) in out.iter_mut().zip(columns) {
            *out = match kind {
                Kind::Biome => column.biome() as i32,
                Kind::Height => column.height,
            };
        }
        Ok(())
    })
}

/// candidates contain [chunk X, chunk Z, native structure-set index] triples.
/// Output is the selected definition index, or -1 for an invalid/empty start.
#[unsafe(no_mangle)]
pub unsafe extern "C" fn retina_query_structure_starts(
    request: *const ChunkRequest,
    candidates: *const [i32; 3],
    count: u64,
    output: *mut i32,
) -> i32 {
    boundary(|| {
        if request.is_null() || (count > 0 && (candidates.is_null() || output.is_null())) {
            return Err("null structure query buffer".into());
        }
        if count > 64 {
            return Err("structure query batch exceeds 64".into());
        }
        if count == 0 {
            return Ok(());
        }
        let input = unsafe { std::slice::from_raw_parts(candidates, count as usize) };
        let out = unsafe { std::slice::from_raw_parts_mut(output, count as usize) };
        out.copy_from_slice(&crate::structures::query_starts(
            shared_engine()?,
            unsafe { *request },
            input,
        )?);
        Ok(())
    })
}
