//! Compatible preview column sidecars, packed before the native output buffer is released.
use crate::profile::Column;
use libdeflater::{CompressionLvl, Compressor};
use std::{path::Path, time::Instant};

pub(crate) const MAX_COLUMNS: usize = 1024 * 256;

#[repr(C)]
#[derive(Default, serde::Serialize)]
pub struct Report {
    pub encode_nanos: u64,
    pub compress_nanos: u64,
    pub write_nanos: u64,
    pub bytes: u64,
}

pub(crate) fn write(path: &Path, columns: &[Column]) -> Result<Report, String> {
    if columns.len() > MAX_COLUMNS {
        return Err("column cache exceeds one region".into());
    }
    let start = Instant::now();
    let mut bytes = vec![0u8; columns.len() * 12];
    for (out, column) in bytes.chunks_exact_mut(12).zip(columns) {
        out[..4].copy_from_slice(&column.height.to_be_bytes());
        out[4..8].copy_from_slice(&column.packed.to_be_bytes());
        out[8..].copy_from_slice(&column.materials.to_be_bytes());
    }
    let encoded = Instant::now();
    let mut compressor = Compressor::new(CompressionLvl::new(1).unwrap());
    let mut compressed = vec![0u8; compressor.zlib_compress_bound(bytes.len())];
    let length = compressor
        .zlib_compress(&bytes, &mut compressed)
        .map_err(|e| e.to_string())?;
    let zipped = Instant::now();
    std::fs::write(path, &compressed[..length]).map_err(|e| e.to_string())?;
    Ok(Report {
        encode_nanos: (encoded - start).as_nanos() as u64,
        compress_nanos: (zipped - encoded).as_nanos() as u64,
        write_nanos: zipped.elapsed().as_nanos() as u64,
        bytes: length as u64,
    })
}
