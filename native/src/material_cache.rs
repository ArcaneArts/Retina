//! Private preview companion: independently compressed base runs for each chunk.
//! Keeps cold Java column queries exact without another GPU terrain dispatch.
use super::{RegionTimers, assemblers};
use crate::{COLUMNS, ChunkRequest, geology::CaveMask, timings};
use libdeflater::{CompressionLvl, Compressor};
use rayon::prelude::*;
use std::path::Path;
use std::time::Instant;

pub(super) fn write(
    path: &Path,
    request: ChunkRequest,
    mask: &CaveMask,
    timers: &RegionTimers<'_>,
) -> Result<u64, String> {
    let start = Instant::now();
    let records: Result<Vec<Vec<u8>>, String> = assemblers()?.install(|| {
        (0..1024usize)
            .into_par_iter()
            .map_init(
                || Compressor::new(CompressionLvl::new(1).unwrap()),
                |compressor, chunk| {
                    let x = request.chunk_x + (chunk % 32) as i32;
                    let z = request.chunk_z + (chunk / 32) as i32;
                    let runs: [&[u32]; COLUMNS] = std::array::from_fn(|i| {
                        mask.material_runs(x * 16 + (i % 16) as i32, z * 16 + (i / 16) as i32)
                            .expect("validated material runs cover preview region")
                    });
                    let total: usize = runs.iter().map(|r| r.len()).sum();
                    let mut bytes = Vec::with_capacity((257 + total) * 4);
                    let mut offset = 0u32;
                    for run in runs {
                        bytes.extend(offset.to_le_bytes());
                        offset += run.len() as u32;
                    }
                    bytes.extend(offset.to_le_bytes());
                    for run in runs {
                        for word in run {
                            bytes.extend(word.to_le_bytes());
                        }
                    }
                    timers.time(timings::COMPRESS, || {
                        let mut compressed = vec![0; compressor.zlib_compress_bound(bytes.len())];
                        let length = compressor
                            .zlib_compress(&bytes, &mut compressed)
                            .map_err(|e| e.to_string())?;
                        compressed.truncate(length);
                        Ok(compressed)
                    })
                },
            )
            .collect()
    });
    let parallel = start.elapsed().as_nanos() as u64;
    let records = records?;
    timers.time(timings::IO, || {
        const HEADER: usize = 24 + 1024 * 12;
        let mut bytes = vec![0; HEADER];
        for (i, value) in [
            0x524d4154u32,
            1,
            request.min_y as u32,
            request.height,
            request.chunk_x as u32,
            request.chunk_z as u32,
        ]
        .into_iter()
        .enumerate()
        {
            bytes[i * 4..i * 4 + 4].copy_from_slice(&value.to_le_bytes());
        }
        for (i, record) in records.into_iter().enumerate() {
            let at = 24 + i * 12;
            let offset = bytes.len() as u64;
            bytes[at..at + 8].copy_from_slice(&offset.to_le_bytes());
            bytes[at + 8..at + 12].copy_from_slice(&(record.len() as u32).to_le_bytes());
            bytes.extend(record);
        }
        let cache = path.with_extension("materials");
        std::fs::create_dir_all(cache.parent().ok_or("material cache needs a parent")?)
            .map_err(|e| e.to_string())?;
        std::fs::write(cache, bytes).map_err(|e| e.to_string())
    })?;
    Ok(parallel)
}
