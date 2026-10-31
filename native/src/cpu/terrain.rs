//! Backend-independent request dispatch; no device, buffer or graphics worker.
use super::{columns::Columns, fields::Fields, noise::fbm};
use crate::{
    COLUMNS, ChunkRequest,
    compute::Sample,
    geology::ColumnHalo,
    profile::{Column, WorldProfile},
    queries::Point,
    timings,
};
use rayon::prelude::*;
use std::sync::Arc;
fn procedural(r: ChunkRequest, x: i32, z: i32) -> Column {
    let height = (r.base_height
        + r.amplitude
            * fbm(
                [x as f32 * r.frequency, z as f32 * r.frequency],
                r.seed as u32,
                (r.seed >> 32) as u32,
            ))
    .floor() as i32;
    Column {
        height: height.clamp(r.min_y + 1, r.min_y + r.height as i32),
        ..Column::default()
    }
}
pub(crate) fn sample(
    profile: Option<&WorldProfile>,
    fields: Option<&Fields>,
    requests: &[ChunkRequest],
    side: u32,
    cave_side: u32,
    mode: u32,
    probe_y: &[i32],
    points: &[Point],
    metrics: &timings::Timings,
) -> Result<Sample, String> {
    let started = metrics.performance.start();
    let mut result = Vec::new();
    let sparse = matches!(mode, 1 | 2 | 4);
    for (index, &r) in requests.iter().enumerate() {
        if sparse {
            let mut columns = match (profile, fields) {
                (Some(p), Some(f)) => Some(Columns::new(p, f, r)),
                (None, None) => None,
                _ => return Err("missing prepared CPU fields".into()),
            };
            let point = points.get(index).copied().unwrap_or(Point {
                x: r.chunk_x * 16 + 8,
                y: probe_y.get(index).copied().unwrap_or(r.min_y),
                z: r.chunk_z * 16 + 8,
            });
            let mut column = match &mut columns {
                Some(c) => c.probe(point.x, point.z, mode == 4)?,
                None => procedural(r, point.x, point.z),
            };
            if mode == 2 {
                if let Some(c) = &mut columns {
                    column.packed = (column.packed & !65535)
                        | c.underground_biome(
                            [point.x, point.y.div_euclid(4) * 4, point.z],
                            column,
                        )?;
                }
            }
            result.push(column);
            continue;
        }
        result.extend(dense_columns(
            profile,
            fields,
            r,
            side,
            mode,
            super::pools::terrain()?,
        )?);
    }
    metrics
        .performance
        .elapsed(crate::performance::Counter::cpu_columns_nanos, started);
    let mask = if cave_side > 0 {
        let p = profile.ok_or("CPU cavity job requires a world profile")?;
        let f = fields.ok_or("CPU cavity job requires prepared fields")?;
        if requests.len() != 1
            || side < 3
            || cave_side != side - 2
            || result.len() != side as usize * side as usize * COLUMNS
        {
            return Err("invalid CPU cavity tile geometry".into());
        }
        let started = metrics.performance.start();
        let r = requests[0];
        let halo = ColumnHalo {
            origin_x: r.chunk_x,
            origin_z: r.chunk_z,
            side: side as usize,
            columns: Arc::new(result),
        };
        let mask = super::materials::build(p, f, r, side, &halo, &metrics.performance)?;
        result = Arc::try_unwrap(halo.columns).unwrap_or_else(|c| (*c).clone());
        metrics
            .performance
            .elapsed(crate::performance::Counter::cpu_masks_nanos, started);
        Some(mask)
    } else {
        None
    };
    metrics
        .performance
        .add(crate::performance::Counter::cpu_tiles, 1);
    Ok(Sample {
        columns: result,
        mask,
        timings: Default::default(),
        #[cfg(test)]
        host_phases: Default::default(),
    })
}

/// Chunk-row strips retain the shared chunk/column order while each task owns
/// its sampler caches. This uses the same terrain lane as material production,
/// rather than creating a pool or allocating a sampler for every column.
fn dense_columns(
    profile: Option<&WorldProfile>,
    fields: Option<&Fields>,
    request: ChunkRequest,
    side: u32,
    mode: u32,
    pool: &rayon::ThreadPool,
) -> Result<Vec<Column>, String> {
    if profile.is_some() != fields.is_some() {
        return Err("missing prepared CPU fields".into());
    }
    let side = side.max(1) as usize;
    let mut result = vec![Column::default(); side * side * COLUMNS];
    pool.install(|| {
        result
            .par_chunks_mut(side * COLUMNS)
            .enumerate()
            .try_for_each_init(
                || {
                    profile
                        .zip(fields)
                        .map(|(p, f)| Columns::new(p, f, request))
                },
                |columns, (cz, row)| {
                    for (index, column) in row.iter_mut().enumerate() {
                        let cx = index / COLUMNS;
                        let x = (request.chunk_x + cx as i32) * 16 + (index % 16) as i32;
                        let z =
                            (request.chunk_z + cz as i32) * 16 + ((index % COLUMNS) / 16) as i32;
                        *column = match columns {
                            Some(c) if mode == 3 => c.probe(x, z, true)?,
                            Some(c) => c.column(x, z, true)?,
                            None => procedural(request, x, z),
                        };
                    }
                    Ok::<_, String>(())
                },
            )
    })?;
    Ok(result)
}

#[cfg(test)]
mod tests;
