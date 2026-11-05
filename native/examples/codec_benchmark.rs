//! Codec-only attribution over real, already encoded chunk NBT. Complete-region
//! and actual Minecraft decoding checks remain necessary for accepting changes.
#[path = "../src/region/codec.rs"]
mod codec;

use codec::{Compression, Encoder, Record};
use serde_json::json;
use std::{fs, hint::black_box, path::PathBuf, time::Instant};

#[cfg(feature = "scalable-allocator")]
#[global_allocator]
static ALLOCATOR: mimalloc::MiMalloc = mimalloc::MiMalloc;

const SECTOR: usize = 4096;

fn encode(inputs: &[Vec<u8>], batch: usize, codec: Compression) -> Result<Vec<Record>, String> {
    let mut records = Vec::with_capacity(inputs.len());
    for group in inputs.chunks(batch) {
        let mut encoder = Encoder::default();
        for input in group {
            records.push(encoder.encode(codec, input)?);
        }
    }
    Ok(records)
}

fn main() -> Result<(), Box<dyn std::error::Error>> {
    let args: Vec<_> = std::env::args().collect();
    if args.len() != 7 {
        return Err(
            "usage: codec_benchmark INPUT_DIR OUTPUT_JSON BATCH ITERATIONS WARMUPS zlib|lz4".into(),
        );
    }
    let input_dir = PathBuf::from(&args[1]);
    let output = PathBuf::from(&args[2]);
    let batch: usize = args[3].parse()?;
    let iterations: usize = args[4].parse()?;
    let warmups: usize = args[5].parse()?;
    if batch == 0 || iterations == 0 {
        return Err("batch and measured iterations must be nonzero".into());
    }
    let codec = match args[6].as_str() {
        "zlib" => Compression::Zlib,
        "lz4" => Compression::Lz4,
        _ => return Err("codec must be zlib or lz4".into()),
    };
    let manifest: serde_json::Value =
        serde_json::from_slice(&fs::read(input_dir.join("manifest.json"))?)?;
    let entries = manifest["records"]
        .as_array()
        .ok_or("missing input records")?;
    let inputs: Vec<_> = entries
        .iter()
        .map(|entry| {
            let path = entry["file"].as_str().ok_or("missing record filename")?;
            Ok::<_, Box<dyn std::error::Error>>(fs::read(input_dir.join(path))?)
        })
        .collect::<Result<_, _>>()?;
    if inputs.is_empty() {
        return Err("no inputs supplied".into());
    }
    let reference = encode(&inputs, batch, codec)?;
    let mut samples = Vec::new();
    for iteration in 0..warmups + iterations {
        let started = Instant::now();
        let records = black_box(encode(black_box(&inputs), batch, codec)?);
        let elapsed = started.elapsed().as_nanos();
        // All output checking and record destruction are outside this timer.
        for (index, (actual, expected)) in records.iter().zip(&reference).enumerate() {
            if actual.codec != expected.codec || actual.bytes != expected.bytes {
                return Err(format!("unstable encoded record {index}").into());
            }
        }
        if iteration >= warmups {
            samples.push(elapsed as u64);
        }
    }
    let retained_record_bytes: usize = reference.iter().map(|r| r.bytes.len()).sum();
    let mut dump = Vec::new();
    for record in &reference {
        dump.push(record.codec.id());
        dump.extend_from_slice(&(record.bytes.len() as u32).to_le_bytes());
        dump.extend_from_slice(&record.bytes);
    }
    let records_file = output.with_extension("records");
    fs::write(&records_file, &dump)?;

    // Source-derived logical Vec fill counts, not measured DRAM traffic or a
    // duration percentage. Compute outside timing from the actual payloads.
    let fills = if codec == Compression::Zlib {
        let mut encoder =
            libdeflater::Compressor::new(libdeflater::CompressionLvl::new(1).unwrap());
        let (mut legacy, mut grow_only) = (0usize, 0usize);
        for (raw, packed) in inputs.chunks(batch).zip(reference.chunks(batch)) {
            let (mut prior_len, mut largest) = (0, 0);
            for (input, record) in raw.iter().zip(packed) {
                let bound = encoder.zlib_compress_bound(input.len());
                legacy += bound.saturating_sub(prior_len);
                grow_only += bound.saturating_sub(largest);
                largest = largest.max(bound);
                prior_len = record.bytes.len();
            }
        }
        Some(
            json!({"legacy_resize_fill_bytes": legacy, "grow_only_fill_bytes": grow_only,
            "scope": "Modeled logical Vec fill lengths for reference truncate/resize versus grow-only scratch; not observed DRAM bytes"}),
        )
    } else {
        None
    };
    let report = json!({
        "schema": "retina.codec-benchmark", "schema_version": 1,
        "allocator": if cfg!(feature = "scalable-allocator") { "mimalloc, matching production" } else { "system, matching production without scalable-allocator" },
        "scope": "Production Encoder source; codec-only encode plus retained Record allocation, excludes NBT construction, terrain, lighting, publication, decoding and Record destruction",
        "input_manifest": input_dir.join("manifest.json"), "records": inputs.len(),
        "raw_bytes": inputs.iter().map(Vec::len).sum::<usize>(),
        "codec": args[6], "batch": batch, "encoders_per_iteration": inputs.len().div_ceil(batch),
        "warmups": warmups, "iterations": iterations, "wall_nanos": samples,
        "retained_record_bytes": retained_record_bytes, "encoded_records": records_file,
        "logical_fill_model": fills,
        "correctness": "Every warmup/measured output is byte-equal to this binary's first encode; compare .records artifacts between binaries/batch settings separately",
    });
    fs::write(&output, serde_json::to_vec_pretty(&report)?)?;
    println!(
        "{}",
        json!({"output": output, "records": inputs.len(), "batch": batch,
        "retained_record_bytes": retained_record_bytes, "codec": args[6]})
    );
    Ok(())
}
