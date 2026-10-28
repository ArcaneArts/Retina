//! Opt-in catalog cost inventory. Stage samples are independent operations, not
//! additive portions of the materialization timer; peak RSS is process-wide.
use super::*;
use std::time::Instant;

fn timed<T>(f: impl FnOnce() -> T) -> (T, f64) {
    let start = Instant::now();
    let result = f();
    (result, start.elapsed().as_secs_f64() * 1000.0)
}

#[test]
#[ignore = "requires RETINA_CATALOG_PROBE_INPUT and RETINA_CATALOG_PROBE_OUTPUT"]
fn catalog_loading_inventory() {
    let input = PathBuf::from(std::env::var_os("RETINA_CATALOG_PROBE_INPUT").unwrap());
    let output = PathBuf::from(std::env::var_os("RETINA_CATALOG_PROBE_OUTPUT").unwrap());
    fs::create_dir(&output).unwrap();
    let variant = std::env::var("RETINA_CATALOG_PROBE_VARIANT").unwrap_or("normal".into());
    let compressed = fs::read(&input).unwrap();
    let mut samples = Vec::new();
    for index in 0..12 {
        let (raw, decompress_ms) = timed(|| {
            let mut raw = Vec::new();
            GzDecoder::new(compressed.as_slice()).read_to_end(&mut raw).unwrap();
            raw
        });
        let expanded_bytes = raw.len();
        let (catalog, parse_ms) = timed(|| fastnbt::from_bytes::<WorldCatalog>(&raw).unwrap());
        drop(raw);
        let selected = &catalog.variants[&variant];
        let (cloned, clone_ms) = timed(|| selected.template.clone());
        drop(cloned);
        let seed = [0, -1, i64::MIN + 17, i64::MAX - 11][index % 4];
        let (bytes, materialize_ms) = timed(|| catalog.materialize(&variant, seed).unwrap());
        let mut nbt = Vec::new();
        GzDecoder::new(bytes.as_slice()).read_to_end(&mut nbt).unwrap();
        let value: Value = fastnbt::from_bytes(&nbt).unwrap();
        let (encoded, encode_ms) = timed(|| fastnbt::to_bytes(&Canonical(&value)).unwrap());
        assert_eq!(encoded, nbt);
        drop(value);
        let (recompressed, compress_ms) = timed(|| {
            let mut gzip = GzEncoder::new(Vec::new(), Compression::best());
            gzip.write_all(&encoded).unwrap();
            gzip.finish().unwrap()
        });
        assert_eq!(recompressed, bytes);
        drop(encoded);
        drop(nbt);
        let (definition, validate_ms) = timed(|| WorldDefinition::from_gzip(bytes.as_slice()).unwrap());
        assert_eq!(definition.seed(), seed);
        drop(definition);
        let path = output.join("retina.dat");
        fs::write(&path, &bytes).unwrap();
        let (reopened, reopen_ms) = timed(|| WorldDefinition::read(&path).unwrap());
        assert_eq!(reopened.seed(), seed);
        drop(reopened);
        let mut usage = std::mem::MaybeUninit::<libc::rusage>::uninit();
        assert_eq!(unsafe { libc::getrusage(libc::RUSAGE_SELF, usage.as_mut_ptr()) }, 0);
        let usage = unsafe { usage.assume_init() };
        let peak_rss_bytes = usage.ru_maxrss as u64 * if cfg!(target_os = "macos") { 1 } else { 1024 };
        if index >= 2 {
            samples.push(serde_json::json!({"seed":seed,"expanded_bytes":expanded_bytes,
                "decompress_ms":decompress_ms,"parse_ms":parse_ms,"clone_ms":clone_ms,
                "materialize_ms":materialize_ms,"encode_ms":encode_ms,"compress_ms":compress_ms,
                "validate_ms":validate_ms,"reopen_ms":reopen_ms,"peak_rss_bytes":peak_rss_bytes}));
        }
    }
    fs::write(output.join("inventory.json"), serde_json::to_vec_pretty(&serde_json::json!({
        "input":input,"variant":variant,"compressed_bytes":compressed.len(),"warmups":2,
        "scope":"Independent stage probes; do not sum with inclusive materialize. RSS is whole-process high water including probe intermediates, not production startup RSS.",
        "samples":samples
    })).unwrap()).unwrap();
}
