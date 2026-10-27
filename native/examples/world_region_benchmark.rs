//! Complete saved-definition region benchmark. Deletes only its own fresh MCA outputs.
//! world_region_benchmark <retina.dat> <dimension> <void|islands|heavy|control> <output-dir> [samples=8] [warmups=2]
use retina_worldgen::{
    TerrainEngine,
    region::{self, codec::Compression},
    world_definition::{WorldDefinition, runtime::WorldSession},
};
use serde_json::{Value, json};
use sha2::{Digest, Sha256};
use std::{
    error::Error,
    fs,
    io::Read,
    path::PathBuf,
    sync::Arc,
    time::{Instant, SystemTime, UNIX_EPOCH},
};

const STAGES: [&str; 33] = [
    "queue",
    "encode",
    "wait_copy",
    "height",
    "sites",
    "columns",
    "cave_density",
    "cave_mask",
    "structure_plan",
    "decoration_plan",
    "ore_plan",
    "assembly",
    "geology",
    "cave_features",
    "vegetation",
    "structures",
    "snow",
    "nbt",
    "compress",
    "io",
    "materials",
    "aquifer_fields",
    "aquifer_mask",
    "feature_counts",
    "ore_mask",
    "provider_noise",
    "lake_candidates",
    "lake_density",
    "lake_reduce",
    "light_host",
    "light_sky",
    "light_spread",
    "light_pack",
];
fn percentile(mut values: Vec<f64>, p: f64) -> f64 {
    values.sort_by(f64::total_cmp);
    if p == 0.5 {
        return (values[(values.len() - 1) / 2] + values[values.len() / 2]) * 0.5;
    }
    values[((values.len() as f64 * p).ceil() as usize)
        .saturating_sub(1)
        .min(values.len() - 1)]
}
fn coordinates(category: &str, i: usize) -> Result<[i32; 2], String> {
    let at = match category {
        "void" => *[
            [1, 0],
            [0, 1],
            [-2, 0],
            [0, -2],
            [1, -1],
            [-1, 1],
            [-2, -1],
            [-1, -2],
        ]
        .get(i)
        .ok_or("void catalog contains eight distinct regions")?,
        "heavy" => *[
            [-2, -3],
            [0, -3],
            [1, -2],
            [2, -2],
            [-1, -1],
            [-1, 0],
            [0, -1],
            [0, 0],
        ]
        .get(i)
        .ok_or("heavy catalog contains eight distinct regions")?,
        "islands" | "control" => [8 + i as i32, 8],
        _ => return Err(format!("unknown workload {category}")),
    };
    Ok(at)
}
fn signature(path: &std::path::Path) -> Result<Value, Box<dyn Error>> {
    let file = fs::read(path)?;
    let mut digest = Sha256::new();
    let mut nonair_sections = 0;
    let mut total_sections = 0;
    let mut entities = 0;
    let mut block_entities = 0;
    let mut starts = 0;
    for slot in 0..1024 {
        let location = u32::from_be_bytes(file[slot * 4..slot * 4 + 4].try_into()?);
        let offset = (location >> 8) as usize * 4096;
        let length = u32::from_be_bytes(file[offset..offset + 4].try_into()?) as usize;
        if file[offset + 4] != 2 {
            return Err("benchmark expects production zlib records".into());
        }
        let mut bytes = Vec::new();
        flate2::read::ZlibDecoder::new(&file[offset + 5..offset + 4 + length])
            .read_to_end(&mut bytes)?;
        digest.update((bytes.len() as u64).to_be_bytes());
        digest.update(&bytes);
        let root: fastnbt::Value = fastnbt::from_bytes(&bytes)?;
        if let fastnbt::Value::Compound(root) = root {
            for key in ["entities", "block_entities"] {
                if let Some(fastnbt::Value::List(list)) = root.get(key) {
                    if key == "entities" {
                        entities += list.len();
                    } else {
                        block_entities += list.len();
                    }
                }
            }
            if let Some(fastnbt::Value::Compound(structures)) = root.get("structures") {
                if let Some(fastnbt::Value::Compound(saved)) = structures.get("starts") {
                    for start in saved.values() {
                        if let fastnbt::Value::Compound(s) = start {
                            if s.get("id") != Some(&fastnbt::Value::String("INVALID".into())) {
                                starts += 1;
                            }
                        }
                    }
                }
            }
            if let Some(fastnbt::Value::List(sections)) = root.get("sections") {
                total_sections += sections.len();
                for section in sections {
                    if let fastnbt::Value::Compound(s) = section {
                        if let Some(fastnbt::Value::Compound(blocks)) = s.get("block_states") {
                            if let Some(fastnbt::Value::List(palette)) = blocks.get("palette") {
                                let only_air = palette.len() == 1
                                    && matches!(&palette[0],fastnbt::Value::Compound(p) if p.get("id").or_else(||p.get("Name"))==Some(&fastnbt::Value::String("minecraft:air".into())));
                                if !only_air {
                                    nonair_sections += 1;
                                }
                            }
                        }
                    }
                }
            }
        }
    }
    Ok(
        json!({"decompressed_record_sha256":format!("{:x}",digest.finalize()),"nonair_sections":nonair_sections,"sections":total_sections,"entities":entities,"block_entities":block_entities,"valid_structure_starts":starts}),
    )
}
#[cfg(unix)]
fn peak_rss() -> Option<u64> {
    unsafe {
        let mut r: libc::rusage = std::mem::zeroed();
        if libc::getrusage(libc::RUSAGE_SELF, &mut r) != 0 {
            return None;
        }
        Some(r.ru_maxrss as u64 * if cfg!(target_os = "macos") { 1 } else { 1024 })
    }
}
#[cfg(not(unix))]
fn peak_rss() -> Option<u64> {
    None
}
fn main() -> Result<(), Box<dyn Error>> {
    let mut args = std::env::args().skip(1);
    let file = PathBuf::from(args.next().ok_or("retina.dat required")?);
    let key = args.next().ok_or("dimension required")?;
    let category = args.next().ok_or("workload required")?;
    let output = PathBuf::from(args.next().ok_or("output directory required")?);
    let count: usize = args.next().map(|s| s.parse()).transpose()?.unwrap_or(8);
    let warmups: usize = args.next().map(|s| s.parse()).transpose()?.unwrap_or(2);
    if count == 0 {
        return Err("at least one sample required".into());
    }
    let bytes = fs::read(&file)?;
    let definition_hash = format!("{:x}", Sha256::digest(&bytes));
    let executable_hash = format!("{:x}", Sha256::digest(fs::read(std::env::current_exe()?)?));
    let began = Instant::now();
    let definition = Arc::new(WorldDefinition::read(&file)?);
    let engine = Arc::new(TerrainEngine::new()?);
    let world = WorldSession::bind(engine.clone(), definition, Compression::Zlib)?;
    let dimension = world.dimension(&key)?;
    let startup_ms = began.elapsed().as_secs_f64() * 1000.;
    fs::create_dir_all(&output)?;
    let disposable = output.join(format!(
        "disposable-{}-{}",
        std::process::id(),
        SystemTime::now().duration_since(UNIX_EPOCH)?.as_nanos()
    ));
    fs::create_dir(&disposable)?;
    let mut rows = Vec::new();
    for sample in 0..warmups + count {
        let warm = sample < warmups;
        let at = if warm {
            [20 + sample as i32, 20]
        } else {
            coordinates(&category, sample - warmups)?
        };
        let path = disposable.join(format!("r.{}.{}.mca", at[0], at[1]));
        let request = dimension.request(at[0] * 32, at[1] * 32)?;
        let biome = &world.definition().dimension(&key)?.profile()?.biomes[0].id;
        let start = Instant::now();
        let report = region::generate_region_profiled(
            &engine,
            request,
            &path,
            world.definition().data_version(),
            biome,
            None,
        )?;
        let ms = start.elapsed().as_secs_f64() * 1000.;
        if report.region.generated != 1024 || report.region.preserved != 0 {
            return Err("benchmark did not generate a fresh complete region".into());
        }
        let signature = signature(&path)?;
        fs::remove_file(&path)?; // Output verification/deletion outside generation timing.
        let stages: serde_json::Map<_, _> = STAGES
            .iter()
            .zip(report.stages.nanos)
            .map(|(name, n)| (name.to_string(), json!(n as f64 / 1e6)))
            .collect();
        let row = json!({"warmup":warm,"region":at,"ms":ms,"bytes":report.region.bytes,"gpu_host_ms":report.region.gpu_nanos as f64/1e6,"assembly_host_ms":report.region.assembly_nanos as f64/1e6,"write_host_ms":report.region.write_nanos as f64/1e6,"stages_ms":stages,"output":signature});
        eprintln!("Region {:?}, warmup={}, {:.3} ms", at, warm, ms);
        rows.push(row);
    }
    fs::remove_dir(&disposable)?;
    let measured: Vec<_> = rows
        .iter()
        .filter(|r| r["warmup"] == false)
        .map(|r| r["ms"].as_f64().unwrap())
        .collect();
    let sum: f64 = measured.iter().sum();
    let stage_medians: serde_json::Map<_, _> = STAGES
        .iter()
        .map(|name| {
            (
                name.to_string(),
                json!(percentile(
                    rows.iter()
                        .filter(|r| r["warmup"] == false)
                        .map(|r| r["stages_ms"][name].as_f64().unwrap())
                        .collect(),
                    0.5
                )),
            )
        })
        .collect();
    let stats = json!({"schema":"retina.world-region-benchmark.v1","definition":file,"definition_sha256":definition_hash,"executable_sha256":executable_hash,"revision_label":std::env::var("RETINA_BENCH_REVISION").ok(),"seed":world.definition().seed(),"data_version":world.definition().data_version(),"dimension":key,"category":category,"backend":engine.backend(),"compression":"zlib","callers":1,"workers":std::env::var("RETINA_REGION_THREADS").unwrap_or_else(|_|"shipping default".into()),"warmups":warmups,"samples":count,"startup_ms":startup_ms,"median_ms":percentile(measured.clone(),0.5),"p95_ms":percentile(measured.clone(),0.95),"maximum_ms":percentile(measured.clone(),1.0),"chunks_per_second":count as f64*1024.*1000./sum,"peak_process_rss_bytes":peak_rss(),"stage_medians_ms":stage_medians,"scope":"complete 1024-chunk region including production lighting, NBT, compression, publication and synchronous generation cleanup; output decode/hash/deletion excluded","stages_overlap":"GPU device stages overlap host work; additive worker stages are wall-scaled by production report; do not sum all stages","rows":rows,"performance":engine.performance_snapshot(dimension.profile_id())});
    fs::write(
        output.join(format!("{category}.json")),
        serde_json::to_vec_pretty(&stats)?,
    )?;
    println!("{stats}");
    Ok(())
}
