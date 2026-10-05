//! Anvil terrain writer. The caller owns Minecraft's region I/O queue while this runs.
//! Existing chunk records (including external .mcc records) are never regenerated.
use std::fs::{self, OpenOptions};
use std::io::{ErrorKind, Write};
use std::path::Path;
use std::sync::OnceLock;
use std::sync::atomic::{AtomicU64, Ordering};
use std::time::{Instant, SystemTime, UNIX_EPOCH};

use libdeflater::{CompressionLvl, Compressor};
use rayon::prelude::*;

use crate::{
    COLUMNS, ChunkRequest, TerrainEngine, decoration, geology,
    profile::{Column, WorldProfile},
    timings,
};

const SECTOR: usize = 4096;
const HEADER: usize = SECTOR * 2;
static TEMP_ID: AtomicU64 = AtomicU64::new(0);
static ASSEMBLERS: OnceLock<Result<rayon::ThreadPool, String>> = OnceLock::new();
#[path = "material_cache.rs"]
mod material_cache;

#[repr(C)]
#[derive(Default, Debug, Clone, Copy)]
pub struct RegionReport {
    pub generated: u32,
    pub preserved: u32,
    pub gpu_nanos: u64,
    pub assembly_nanos: u64,
    pub write_nanos: u64,
    pub bytes: u64,
}

/// Separate ABI keeps legacy region callers compatible. Worker shares are scaled
/// to the measured parallel phase; device timestamps overlap the GPU host phase.
#[repr(C)]
#[derive(Default)]
pub struct DetailedRegionReport {
    pub region: RegionReport,
    pub stages: timings::Snapshot,
}
struct RegionTimers<'a> {
    session: &'a timings::Timings,
    job: &'a timings::Timings,
}
impl RegionTimers<'_> {
    fn time<T>(&self, stage: usize, f: impl FnOnce() -> T) -> T {
        let _span = self.span(stage);
        f()
    }
    fn span(&self, stage: usize) -> RegionGuard<'_> {
        RegionGuard {
            timers: self,
            stage,
            start: Instant::now(),
        }
    }
    fn chunks(&self, count: u64) {
        self.session.chunks(count);
    }
}

struct RegionGuard<'a> {
    timers: &'a RegionTimers<'a>,
    stage: usize,
    start: Instant,
}
impl Drop for RegionGuard<'_> {
    fn drop(&mut self) {
        let elapsed = self.start.elapsed().as_nanos() as u64;
        self.timers.session.add(self.stage, elapsed);
        self.timers.job.add(self.stage, elapsed);
    }
}

fn assemblers() -> Result<&'static rayon::ThreadPool, String> {
    ASSEMBLERS
        .get_or_init(|| {
            let threads = std::thread::available_parallelism()
                .map_or(2, usize::from)
                .clamp(2, 16);
            rayon::ThreadPoolBuilder::new()
                .num_threads(threads)
                .thread_name(|i| format!("retina-mca-{i}"))
                .build()
                .map_err(|e| e.to_string())
        })
        .as_ref()
        .map_err(Clone::clone)
}

/// request.chunk_x/z identify any chunk in the desired region, using floor division.
/// Only absent slots are generated. Publishing uses a sibling file and atomic rename.
pub fn generate_region(
    engine: &TerrainEngine,
    request: ChunkRequest,
    path: &Path,
    data_version: i32,
    biome: &str,
) -> Result<RegionReport, String> {
    generate_region_inner(engine, request, path, data_version, biome, None, None, None)
}

/// Preview generation also returns the undecorated GPU columns, without another dispatch.
pub fn generate_region_columns(
    engine: &TerrainEngine,
    request: ChunkRequest,
    path: &Path,
    data_version: i32,
    biome: &str,
    columns: &mut [Column],
) -> Result<RegionReport, String> {
    if columns.len() != 1024 * COLUMNS {
        return Err("incorrect region column buffer length".into());
    }
    generate_region_inner(
        engine,
        request,
        path,
        data_version,
        biome,
        Some(columns),
        None,
        None,
    )
}

/// Install cached compressed records into missing slots; existing records remain unchanged.
pub fn publish_cached_region(
    engine: &TerrainEngine,
    request: ChunkRequest,
    path: &Path,
    cached: &Path,
) -> Result<RegionReport, String> {
    generate_region_inner(engine, request, path, 0, "", None, Some(cached), None)
}

pub fn generate_region_profiled(
    engine: &TerrainEngine,
    request: ChunkRequest,
    path: &Path,
    data_version: i32,
    biome: &str,
    columns: Option<&mut [Column]>,
) -> Result<DetailedRegionReport, String> {
    let mut stages = timings::Snapshot::default();
    let region = generate_region_inner(
        engine,
        request,
        path,
        data_version,
        biome,
        columns,
        None,
        Some(&mut stages),
    )?;
    Ok(DetailedRegionReport { region, stages })
}

fn generate_region_inner(
    engine: &TerrainEngine,
    request: ChunkRequest,
    path: &Path,
    data_version: i32,
    biome: &str,
    output: Option<&mut [Column]>,
    cached: Option<&Path>,
    detail: Option<&mut timings::Snapshot>,
) -> Result<RegionReport, String> {
    request.validate()?;
    let cache_materials = output.is_some();
    if request.min_y % 16 != 0
        || !request.height.is_multiple_of(16)
        || request.min_y / 16 < -128
        || (request.min_y + request.height as i32 - 1) / 16 > 127
    {
        return Err("MCA sections require aligned heights with section Y in -128..127".into());
    }
    if cached.is_none() && (biome.is_empty() || biome.len() > u16::MAX as usize) {
        return Err("invalid MCA biome identifier".into());
    }
    let session = engine.timings(request.reserved);
    let job = timings::Timings::default();
    let timings = RegionTimers {
        session: &session,
        job: &job,
    };
    let mut gpu_trace = timings::Snapshot::default();
    let mut parallel_nanos = 0;
    let mut file = match timings.time(timings::IO, || fs::read(path)) {
        Ok(bytes) => bytes,
        Err(e) if e.kind() == ErrorKind::NotFound => vec![0; HEADER],
        Err(e) => return Err(format!("read region {}: {e}", path.display())),
    };
    // Vanilla/DH can open an empty region before writing its first record.
    if file.is_empty() {
        file.resize(HEADER, 0);
    }
    if file.len() < HEADER {
        return Err(format!("truncated MCA header: {}", path.display()));
    }
    let region_x = request.chunk_x.div_euclid(32);
    let region_z = request.chunk_z.div_euclid(32);
    let mut slots = Vec::new();
    let mut requests = Vec::new();
    for slot in 0..1024 {
        let location = u32::from_be_bytes(file[slot * 4..slot * 4 + 4].try_into().unwrap());
        if location == 0 {
            let chunk = ChunkRequest {
                chunk_x: region_x * 32 + (slot % 32) as i32,
                chunk_z: region_z * 32 + (slot / 32) as i32,
                ..request
            };
            chunk.validate()?;
            slots.push(slot);
            requests.push(chunk);
        } else {
            // Validate the records we are preserving; never replace a corrupt save with new terrain.
            let offset = (location >> 8) as usize * SECTOR;
            let length = (location & 255) as usize * SECTOR;
            if offset < HEADER || length == 0 || offset + length > file.len() {
                return Err(format!("invalid MCA sector location at slot {slot}"));
            }
            let payload = u32::from_be_bytes(file[offset..offset + 4].try_into().unwrap()) as usize;
            if payload < 1 || payload + 4 > length {
                return Err(format!("invalid MCA record length at slot {slot}"));
            }
        }
    }
    let mut report = RegionReport {
        generated: slots.len() as u32,
        preserved: (1024 - slots.len()) as u32,
        bytes: file.len() as u64,
        ..Default::default()
    };
    if slots.is_empty() {
        if let Some(output) = output {
            output.copy_from_slice(&engine.sample_region(request)?);
        }
        return Ok(report);
    }

    let start = Instant::now();
    let records: Result<Vec<Vec<u8>>, String> = if let Some(cached) = cached {
        let source = timings
            .time(timings::IO, || fs::read(cached))
            .map_err(|e| format!("read preview {}: {e}", cached.display()))?;
        if source.len() < HEADER {
            return Err("truncated cached MCA header".into());
        }
        slots
            .iter()
            .map(|slot| {
                let location =
                    u32::from_be_bytes(source[slot * 4..slot * 4 + 4].try_into().unwrap());
                let offset = (location >> 8) as usize * SECTOR;
                let length = (location & 255) as usize * SECTOR;
                if offset < HEADER || length == 0 || offset + length > source.len() {
                    return Err(format!("invalid cached MCA location at slot {slot}"));
                }
                let payload =
                    u32::from_be_bytes(source[offset..offset + 4].try_into().unwrap()) as usize;
                if payload < 1 || payload + 4 > length || source[offset + 4] != 2 {
                    return Err(format!("invalid cached MCA record at slot {slot}"));
                }
                Ok(source[offset + 5..offset + 4 + payload].to_vec())
            })
            .collect()
    } else {
        let profile = engine.profile(request.reserved)?;
        let origin = ChunkRequest {
            chunk_x: region_x * 32,
            chunk_z: region_z * 32,
            ..request
        };
        let decorated = profile
            .as_deref()
            .is_some_and(|p| !p.decorations.is_empty());
        let (field, cave_mask) = if profile.as_deref().is_some_and(|p| {
            decorated
                || !p.geology.ores.is_empty()
                || p.geology.caves_enabled(p)
                || p.registry_program
                    .as_ref()
                    .is_some_and(|r| r.material_layers)
        }) {
            engine.terrain_field_profiled(origin, 32, Some(&mut gpu_trace))?
        } else {
            (
                decoration::Field {
                    substrate: None,
                    origin_x: origin.chunk_x,
                    origin_z: origin.chunk_z,
                    side: 32,
                    columns: engine.sample_region(origin)?,
                },
                None,
            )
        };
        if let Some(output) = output {
            for slot in 0..1024 {
                output[slot * COLUMNS..(slot + 1) * COLUMNS].copy_from_slice(field.chunk(
                    origin.chunk_x + (slot % 32) as i32,
                    origin.chunk_z + (slot / 32) as i32,
                ));
            }
        }
        report.gpu_nanos = start.elapsed().as_nanos() as u64;
        if cache_materials {
            if let Some(mask) = cave_mask
                .as_deref()
                .filter(|m| m.material_run_count().is_some())
            {
                parallel_nanos += material_cache::write(path, origin, mask, &timings)?;
            }
        }
        // Structure planning already updates the session counter internally.
        let structure_plans = job.time(timings::STRUCTURE_PLAN, || {
            crate::structures::plans(engine, origin, 32)
        })?;
        assemblers()?.install(|| {
            let overlays = timings.time(timings::VEGETATION_PLAN, || {
                if decorated {
                    engine.plan_decorations_profiled(
                        &field,
                        profile.as_deref().unwrap(),
                        origin,
                        32,
                        cave_mask.as_deref(),
                        Some(&job),
                    )
                } else {
                    Ok(vec![Vec::new(); 1024])
                }
            })?;
            let ores = timings.time(timings::ORE_PLAN, || {
                if let Some(p) = profile.as_deref() {
                    engine.plan_ores(&field, p, origin, 32, cave_mask.as_deref(), Some(&job))
                } else {
                    Ok(geology::RegionPlan::Cpu(vec![Vec::new(); 1024]))
                }
            })?;
            let parallel_start = Instant::now();
            let records = requests
                .par_iter()
                // Amortize scratch/compressor setup over small batches without changing slot order.
                .with_min_len(8)
                .enumerate()
                .map_init(ChunkScratch::default, |scratch, (index, &request)| {
                    let columns = field.chunk(request.chunk_x, request.chunk_z);
                    let mut blocks = std::mem::take(&mut scratch.blocks);
                    blocks.resize(request.block_count(), 0);
                    let mut plant_updates = decoration::pairs::Updates::default();
                    timings.time(timings::ASSEMBLY, || {
                        decoration::assemble_carved(
                            request,
                            columns,
                            profile.as_deref(),
                            cave_mask.as_deref(),
                            &mut blocks,
                        );
                        if let Some(p) = profile.as_deref() {
                            plant_updates.base(p, &blocks);
                        }
                    });
                    if let Some(p) = profile.as_deref() {
                        timings.time(timings::GEOLOGY, || {
                            ores.apply(
                                request,
                                &field,
                                p,
                                cave_mask.as_deref(),
                                slots[index],
                                &mut blocks,
                            )
                        });
                    }
                    if let Some(p) = profile.as_deref() {
                        timings.time(timings::CAVE_FEATURES, || {
                            plant_updates.extend(crate::features::apply(
                                request,
                                p,
                                cave_mask.as_deref(),
                                &mut blocks,
                            ))
                        });
                    }
                    timings.time(timings::VEGETATION, || {
                        decoration::decorate(
                            profile.as_deref(),
                            &overlays[slots[index]],
                            &mut blocks,
                        );
                        if let Some(p) = profile.as_deref() {
                            plant_updates.placements(p, &overlays[slots[index]]);
                        }
                    });
                    let structure_data = profile.as_deref().map(|p| {
                        timings.time(timings::STRUCTURES, || {
                            let mut data = crate::structures::apply(
                                request,
                                p,
                                &structure_plans,
                                columns,
                                Some(&mut blocks),
                            );
                            plant_updates.extend(std::mem::take(&mut data.plant_updates));
                            data
                        })
                    });
                    if let Some(p) = profile.as_deref() {
                        timings.time(timings::SNOW, || {
                            crate::features::snow(request, p, columns, &mut blocks)
                        });
                        timings.time(timings::VEGETATION, || plant_updates.finish(p, &mut blocks));
                    }
                    timings.time(timings::NBT, || {
                        encode_chunk(
                            request,
                            columns,
                            &blocks,
                            data_version,
                            biome,
                            profile.as_deref(),
                            cave_mask.as_deref(),
                            structure_data.as_ref().map(|data| &data.tag),
                            scratch,
                        )
                    });
                    scratch.blocks = blocks;
                    timings.time(timings::COMPRESS, || {
                        let bound = scratch.compressor.zlib_compress_bound(scratch.nbt.0.len());
                        scratch.compressed.resize(bound, 0);
                        let size = scratch
                            .compressor
                            .zlib_compress(&scratch.nbt.0, &mut scratch.compressed)
                            .map_err(|e| e.to_string())?;
                        Ok(scratch.compressed[..size].to_vec())
                    })
                })
                .collect();
            parallel_nanos += parallel_start.elapsed().as_nanos() as u64;
            records
        })
    };
    let records = records?;
    if cached.is_none() {
        timings.chunks(records.len() as u64);
    }
    report.assembly_nanos = (start.elapsed().as_nanos() as u64).saturating_sub(report.gpu_nanos);
    let start = Instant::now();
    let _io = timings.span(timings::IO);
    file.resize(file.len().div_ceil(SECTOR) * SECTOR, 0);
    let timestamp = SystemTime::now()
        .duration_since(UNIX_EPOCH)
        .map_err(|e| e.to_string())?
        .as_secs() as u32;
    for (slot, compressed) in slots.into_iter().zip(records) {
        let offset = file.len() / SECTOR;
        let sectors = (compressed.len() + 5).div_ceil(SECTOR);
        if sectors > 255 || offset > 0x00ff_ffff {
            return Err("generated MCA record exceeds the inline Anvil sector limit".into());
        }
        file[slot * 4..slot * 4 + 4]
            .copy_from_slice(&(((offset as u32) << 8) | sectors as u32).to_be_bytes());
        file[SECTOR + slot * 4..SECTOR + slot * 4 + 4].copy_from_slice(&timestamp.to_be_bytes());
        file.extend_from_slice(&((compressed.len() + 1) as u32).to_be_bytes());
        file.push(2); // Standard zlib chunk compression.
        file.extend_from_slice(&compressed);
        file.resize((offset + sectors) * SECTOR, 0);
    }
    let parent = path.parent().ok_or("MCA path needs a parent directory")?;
    fs::create_dir_all(parent).map_err(|e| e.to_string())?;
    let temp = parent.join(format!(
        ".retina-{}-{}.mca.tmp",
        std::process::id(),
        TEMP_ID.fetch_add(1, Ordering::Relaxed)
    ));
    let result: Result<(), String> = (|| {
        let mut output = OpenOptions::new()
            .write(true)
            .create_new(true)
            .open(&temp)
            .map_err(|e| e.to_string())?;
        output.write_all(&file).map_err(|e| e.to_string())?;
        output.sync_all().map_err(|e| e.to_string())?;
        drop(output);
        fs::rename(&temp, path).map_err(|e| format!("publish region {}: {e}", path.display()))?;
        // On Unix, make the directory entry durable along with the file contents.
        #[cfg(unix)]
        fs::File::open(parent)
            .and_then(|directory| directory.sync_all())
            .map_err(|e| e.to_string())?;
        Ok(())
    })();
    if result.is_err() {
        let _ = fs::remove_file(&temp);
    }
    result?;
    report.write_nanos = start.elapsed().as_nanos() as u64;
    report.bytes = file.len() as u64;
    drop(_io);
    if let Some(detail) = detail {
        *detail = job.snapshot();
        detail.chunks = report.generated as u64;
        detail.flags = gpu_trace.flags;
        detail.gpu_columns = gpu_trace.gpu_columns;
        detail.gpu_jobs = gpu_trace.gpu_jobs;
        detail.nanos[..timings::STRUCTURE_PLAN]
            .copy_from_slice(&gpu_trace.nanos[..timings::STRUCTURE_PLAN]);
        detail.nanos[timings::MATERIALS..=timings::AQUIFER_MASK]
            .copy_from_slice(&gpu_trace.nanos[timings::MATERIALS..=timings::AQUIFER_MASK]);
        // Sparse feature sampling belongs to this job; it may overlap a different
        // region's terrain submission on the shared queue.
        detail.flags |= job.snapshot().flags;
        let gpu_host: u64 = detail.nanos[..timings::HEIGHT].iter().sum();
        // Includes field-cache copies and host bookkeeping within the measured GPU phase.
        detail.nanos[timings::WAIT_COPY] += report.gpu_nanos.saturating_sub(gpu_host);
        let worker_total: u64 = detail.nanos[timings::ASSEMBLY..=timings::COMPRESS]
            .iter()
            .sum();
        for stage in timings::ASSEMBLY..=timings::COMPRESS {
            detail.nanos[stage] = if worker_total == 0 {
                0
            } else {
                (detail.nanos[stage] as u128 * parallel_nanos as u128 / worker_total as u128) as u64
            };
        }
    }
    Ok(report)
}

/// Original, narrow NBT encoder for the generated terrain schema. No numeric registry IDs.
#[derive(Default)]
struct Nbt(Vec<u8>);
impl Nbt {
    fn text(&mut self, value: &str) {
        self.0
            .extend_from_slice(&(value.len() as u16).to_be_bytes());
        self.0.extend_from_slice(value.as_bytes());
    }
    fn named(&mut self, kind: u8, name: &str) {
        self.0.push(kind);
        self.text(name);
    }
    fn compound(&mut self, name: &str) {
        self.named(10, name);
    }
    fn end(&mut self) {
        self.0.push(0);
    }
    fn int(&mut self, name: &str, value: i32) {
        self.named(3, name);
        self.0.extend_from_slice(&value.to_be_bytes());
    }
    fn long(&mut self, name: &str, value: i64) {
        self.named(4, name);
        self.0.extend_from_slice(&value.to_be_bytes());
    }
    fn byte(&mut self, name: &str, value: i8) {
        self.named(1, name);
        self.0.push(value as u8);
    }
    fn string(&mut self, name: &str, value: &str) {
        self.named(8, name);
        self.text(value);
    }
    fn list(&mut self, name: &str, kind: u8, length: usize) {
        self.named(9, name);
        self.0.push(kind);
        self.0.extend_from_slice(&(length as i32).to_be_bytes());
    }
    fn longs(&mut self, name: &str, values: &[u64]) {
        self.named(12, name);
        self.0
            .extend_from_slice(&(values.len() as i32).to_be_bytes());
        for value in values {
            self.0.extend_from_slice(&value.to_be_bytes());
        }
    }
}

pub fn chunk_nbt(
    request: ChunkRequest,
    heights: &[i32],
    data_version: i32,
    biome: &str,
) -> Vec<u8> {
    let columns: Vec<_> = heights
        .iter()
        .map(|height| Column {
            materials: 0,
            height: *height,
            packed: 0,
        })
        .collect();
    chunk_nbt_columns(request, &columns, data_version, biome, None)
}

pub fn chunk_nbt_columns(
    request: ChunkRequest,
    columns: &[Column],
    data_version: i32,
    biome: &str,
    profile: Option<&WorldProfile>,
) -> Vec<u8> {
    let mut blocks = vec![0; request.block_count()];
    decoration::assemble(request, columns, profile, &[], &mut blocks);
    chunk_nbt_blocks(
        request,
        columns,
        &blocks,
        data_version,
        biome,
        profile,
        None,
        None,
    )
}

fn chunk_nbt_blocks(
    request: ChunkRequest,
    columns: &[Column],
    blocks: &[u16],
    data_version: i32,
    biome: &str,
    profile: Option<&WorldProfile>,
    cave_mask: Option<&crate::geology::CaveMask>,
    structure_data: Option<&serde_json::Value>,
) -> Vec<u8> {
    let mut scratch = ChunkScratch::default();
    encode_chunk(
        request,
        columns,
        blocks,
        data_version,
        biome,
        profile,
        cave_mask,
        structure_data,
        &mut scratch,
    );
    scratch.nbt.0
}

#[derive(Default)]
struct PaletteScratch {
    palette: Vec<u16>,
    lookup: Vec<u32>,
    indices: Vec<u32>,
}
impl PaletteScratch {
    fn build(&mut self, values: &[u16], domain: usize) {
        self.build_with_indices::<true>(values, domain);
    }
    fn build_with_indices<const INDICES: bool>(&mut self, values: &[u16], domain: usize) {
        for &id in &self.palette {
            self.lookup[id as usize] = u32::MAX;
        }
        self.palette.clear();
        self.lookup.resize(domain, u32::MAX);
        let first = values[0];
        // Uniform air/stone sections need no index buffer or packed data.
        if values.iter().all(|&v| v == first) {
            self.palette.push(first);
            self.indices.clear();
            self.lookup[first as usize] = 0;
            return;
        }
        if INDICES {
            self.indices.resize(values.len(), 0);
        }
        for (i, &id) in values.iter().enumerate() {
            let entry = &mut self.lookup[id as usize];
            if *entry == u32::MAX {
                *entry = self.palette.len() as u32;
                self.palette.push(id);
            }
            if INDICES {
                self.indices[i] = *entry;
            }
        }
    }
}
struct ChunkScratch {
    blocks: Vec<u16>,
    nbt: Nbt,
    palette: PaletteScratch,
    occupied: Vec<bool>,
    biomes: PaletteScratch,
    compressor: Compressor,
    compressed: Vec<u8>,
}
impl Default for ChunkScratch {
    fn default() -> Self {
        Self {
            blocks: Vec::new(),
            nbt: Nbt::default(),
            palette: PaletteScratch::default(),
            occupied: Vec::new(),
            biomes: PaletteScratch::default(),
            compressor: Compressor::new(CompressionLvl::new(1).unwrap()),
            compressed: Vec::new(),
        }
    }
}

pub(crate) fn material_nbt(material: &serde_json::Value) -> Vec<u8> {
    let mut nbt = Nbt::default();
    nbt.string(
        "id",
        material
            .as_str()
            .unwrap_or_else(|| material["id"].as_str().unwrap()),
    );
    if let Some(properties) = material
        .get("properties")
        .and_then(serde_json::Value::as_object)
    {
        nbt.compound("properties");
        for (key, value) in properties {
            nbt.string(key, value.as_str().unwrap());
        }
        nbt.end();
    }
    nbt.end();
    nbt.0
}

fn encode_chunk(
    request: ChunkRequest,
    columns: &[Column],
    blocks: &[u16],
    data_version: i32,
    biome: &str,
    profile: Option<&WorldProfile>,
    cave_mask: Option<&crate::geology::CaveMask>,
    structure_data: Option<&serde_json::Value>,
    scratch: &mut ChunkScratch,
) {
    let heights: [i32; COLUMNS] = std::array::from_fn(|i| columns[i].height);
    let ChunkScratch {
        nbt,
        palette,
        biomes,
        occupied,
        ..
    } = scratch;
    nbt.0.clear();
    occupied.clear();
    nbt.compound("");
    nbt.int("DataVersion", data_version);
    nbt.int("xPos", request.chunk_x);
    nbt.int("zPos", request.chunk_z);
    nbt.int("yPos", request.min_y / 16);
    // Terrain and features are complete. Minecraft calculates lighting before activation.
    nbt.string("Status", "minecraft:features");
    nbt.byte("isLightOn", 0);
    nbt.long("LastUpdate", 0);
    nbt.long("InhabitedTime", 0);
    nbt.list("sections", 10, request.height as usize / 16);
    let lowest = *heights.iter().min().unwrap();
    let highest = *heights.iter().max().unwrap();
    for section in 0..request.height as i32 / 16 {
        let y = request.min_y + section * 16;
        nbt.byte("Y", (y / 16) as i8);
        nbt.compound("block_states");
        if let Some(profile) = profile {
            let values = &blocks[section as usize * 4096..(section as usize + 1) * 4096];
            palette.build_with_indices::<false>(values, profile.materials.len());
            occupied.push(
                palette.palette.len() != 1
                    || profile.heightmap_masks[palette.palette[0] as usize] != 0,
            );
            nbt.list("palette", 10, palette.palette.len());
            for &id in &palette.palette {
                nbt.0.extend_from_slice(&profile.material_nbt[id as usize]);
            }
            if palette.palette.len() > 1 {
                nbt.packed_palette(
                    "data",
                    values,
                    &palette.lookup,
                    palette_bits(palette.palette.len()).max(4),
                );
            }
            nbt.end();
            nbt.compound("biomes");
            // GPU quart biomes use Minecraft's x,z,y order.
            let values: [u16; 64] = std::array::from_fn(|index| {
                cave_mask
                    .and_then(|m| {
                        m.biome(
                            request.chunk_x * 16 + (index % 4) as i32 * 4,
                            y + (index / 16) as i32 * 4,
                            request.chunk_z * 16 + ((index % 16) / 4) as i32 * 4,
                        )
                    })
                    .unwrap_or(
                        columns[((index % 16) / 4) as usize * 64 + (index % 4) as usize * 4].biome()
                            as u16,
                    )
            });
            biomes.build(&values, profile.biomes.len());
            nbt.list("palette", 8, biomes.palette.len());
            for id in &biomes.palette {
                nbt.text(&profile.biomes[*id as usize].id);
            }
            if biomes.palette.len() > 1 {
                nbt.packed("data", &biomes.indices, palette_bits(biomes.palette.len()));
            }
            nbt.end();
        } else {
            let mixed = y < highest && y + 16 > lowest;
            nbt.list("palette", 8, if mixed { 2 } else { 1 });
            nbt.text(if mixed || y >= highest {
                "minecraft:air"
            } else {
                "minecraft:stone"
            });
            if mixed {
                nbt.text("minecraft:stone");
                let mut packed = [0u64; 256]; // Two-entry palette uses four bits per block.
                for index in 0..4096 {
                    if y + ((index / COLUMNS) as i32) < heights[index % COLUMNS] {
                        packed[index / 16] |= 1 << ((index % 16) * 4);
                    }
                }
                nbt.longs("data", &packed);
            }
            nbt.end();
            nbt.compound("biomes");
            nbt.list("palette", 8, 1);
            nbt.text(biome);
            nbt.end();
        }
        nbt.end(); // Section compound.
    }
    nbt.compound("Heightmaps");
    let bits = 32 - request.height.leading_zeros(); // ceil(log2(height + 1)).
    let mut ground = [0u32; COLUMNS];
    let mut surface = [0u32; COLUMNS];
    // Profiled terrain uses final block predicates below, not these legacy maps.
    if profile.is_none() {
        for (i, column) in columns.iter().enumerate() {
            ground[i] = (column.height - request.min_y) as u32;
            surface[i] = (column.surface_height(profile) - request.min_y) as u32;
        }
    }
    let mut final_heights = [[0u32; COLUMNS]; 6];
    if let Some(profile) = profile {
        let mut pending = [63u8; COLUMNS];
        let mut remaining = COLUMNS;
        // Traverse occupied final sections in contiguous rows, including structure/tree tops.
        for section in (0..occupied.len()).rev().filter(|&i| occupied[i]) {
            for layer in (section * 16..(section + 1) * 16).rev() {
                let row = &blocks[layer * COLUMNS..(layer + 1) * COLUMNS];
                for c in 0..COLUMNS {
                    if pending[c] == 0 {
                        continue;
                    }
                    let hit = profile.heightmap_masks[row[c] as usize] & pending[c];
                    let mut hits = hit;
                    while hits != 0 {
                        let kind = hits.trailing_zeros() as usize;
                        final_heights[kind][c] = layer as u32 + 1;
                        hits &= hits - 1;
                    }
                    pending[c] &= !hit;
                    if pending[c] == 0 {
                        remaining -= 1;
                    }
                }
                if remaining == 0 {
                    break;
                }
            }
            if remaining == 0 {
                break;
            }
        }
    }
    for (kind, name) in [
        "WORLD_SURFACE_WG",
        "WORLD_SURFACE",
        "OCEAN_FLOOR_WG",
        "OCEAN_FLOOR",
        "MOTION_BLOCKING",
        "MOTION_BLOCKING_NO_LEAVES",
    ]
    .iter()
    .enumerate()
    {
        let values = if profile.is_some() {
            &final_heights[kind][..]
        } else if name.starts_with("OCEAN_FLOOR") {
            &ground
        } else {
            &surface
        };
        nbt.packed(name, values, bits);
    }
    nbt.end();
    if let Some(data) = structure_data {
        crate::nbt::fields(&mut nbt.0, data);
    } else {
        crate::nbt::fields(&mut nbt.0, &crate::structures::empty_data());
    }
    for name in ["block_ticks", "fluid_ticks"] {
        nbt.list(name, 10, 0);
    }
    nbt.list("PostProcessing", 9, 0);
    nbt.end();
}

fn palette_bits(length: usize) -> u32 {
    usize::BITS - (length - 1).leading_zeros()
}
impl Nbt {
    /// Pack final block IDs through their section-local palette directly. The
    /// palette keeps first-occurrence order; no temporary u32 index pass is needed.
    fn packed_palette(&mut self, name: &str, values: &[u16], lookup: &[u32], bits: u32) {
        match bits {
            4 => self.packed_palette_bits::<4>(name, values, lookup),
            5 => self.packed_palette_bits::<5>(name, values, lookup),
            6 => self.packed_palette_bits::<6>(name, values, lookup),
            _ => self.packed_palette_generic(name, values, lookup, bits),
        }
    }
    fn packed_palette_bits<const BITS: u32>(&mut self, name: &str, values: &[u16], lookup: &[u32]) {
        self.packed_palette_generic(name, values, lookup, BITS);
    }
    #[inline(always)]
    fn packed_palette_generic(&mut self, name: &str, values: &[u16], lookup: &[u32], bits: u32) {
        let per_long = 64 / bits as usize;
        self.named(12, name);
        let longs = values.len().div_ceil(per_long);
        self.0.extend_from_slice(&(longs as i32).to_be_bytes());
        let start = self.0.len();
        self.0.resize(start + longs * 8, 0);
        for (group, output) in values
            .chunks(per_long)
            .zip(self.0[start..].chunks_exact_mut(8))
        {
            let mut word = 0u64;
            for (i, &material) in group.iter().enumerate() {
                word |= (lookup[material as usize] as u64) << (i * bits as usize);
            }
            output.copy_from_slice(&word.to_be_bytes());
        }
    }
    fn packed(&mut self, name: &str, values: &[u32], bits: u32) {
        match bits {
            4 => self.packed_bits::<4>(name, values),
            5 => self.packed_bits::<5>(name, values),
            6 => self.packed_bits::<6>(name, values),
            9 => self.packed_bits::<9>(name, values),
            _ => self.packed_generic(name, values, bits),
        }
    }
    fn packed_bits<const BITS: u32>(&mut self, name: &str, values: &[u32]) {
        self.packed_generic(name, values, BITS);
    }
    #[inline(always)]
    fn packed_generic(&mut self, name: &str, values: &[u32], bits: u32) {
        let per_long = 64 / bits as usize;
        self.named(12, name);
        let longs = values.len().div_ceil(per_long);
        self.0.extend_from_slice(&(longs as i32).to_be_bytes());
        let start = self.0.len();
        self.0.resize(start + longs * 8, 0);
        for (group, output) in values
            .chunks(per_long)
            .zip(self.0[start..].chunks_exact_mut(8))
        {
            let mut word = 0u64;
            for (i, &value) in group.iter().enumerate() {
                word |= (value as u64) << (i * bits as usize);
            }
            output.copy_from_slice(&word.to_be_bytes());
        }
    }
}

#[cfg(test)]
mod palette_tests {
    use super::*;
    use std::hint::black_box;

    fn encode_sections(
        sections: &[Vec<u16>],
        domain: usize,
        materials: &[Vec<u8>],
        indexed: bool,
        palette: &mut PaletteScratch,
        nbt: &mut Nbt,
    ) -> usize {
        let mut bytes = 0;
        for values in sections {
            if indexed {
                palette.build(values, domain);
            } else {
                palette.build_with_indices::<false>(values, domain);
            }
            nbt.0.clear();
            nbt.compound("block_states");
            nbt.list("palette", 10, palette.palette.len());
            for &id in &palette.palette {
                nbt.0.extend_from_slice(&materials[id as usize]);
            }
            if palette.palette.len() > 1 {
                let bits = palette_bits(palette.palette.len()).max(4);
                if indexed {
                    nbt.packed("data", &palette.indices, bits);
                } else {
                    nbt.packed_palette("data", values, &palette.lookup, bits);
                }
            }
            nbt.end();
            bytes += black_box(&nbt.0).len();
        }
        bytes
    }

    #[test]
    fn direct_palette_keeps_indices_order_padding_and_reused_uniform_sections() {
        let mut direct = PaletteScratch::default();
        let mut indexed = PaletteScratch::default();
        let mut reference = Nbt::default();
        let mut actual = Nbt::default();
        for count in [
            1, 2, 3, 16, 17, 32, 33, 64, 65, 128, 129, 256, 257, 512, 513, 1024, 1025, 2048, 2049,
            4096, 1,
        ] {
            // All bit widths reachable by a section, with noncontiguous high
            // block IDs and an arbitrary first-occurrence palette order.
            let values: Vec<u16> = (0..4096)
                .map(|i| (65535 - ((i * 2053) % count) * 13) as u16)
                .collect();
            direct.build_with_indices::<false>(&values, 65536);
            indexed.build(&values, 65536);
            assert_eq!(direct.palette, indexed.palette);
            if count == 1 {
                assert!(direct.indices.is_empty());
                continue;
            }
            assert!(
                direct.indices.is_empty(),
                "direct packing has no index buffer"
            );
            let bits = palette_bits(direct.palette.len()).max(4);
            actual.0.clear();
            reference.0.clear();
            actual.packed_palette("data", &values, &direct.lookup, bits);
            reference.packed("data", &indexed.indices, bits);
            assert_eq!(actual.0, reference.0, "palette width {bits}");
            let data = &actual.0[11..]; // Named long array: type, name length/name, array length.
            let per_long = 64 / bits;
            for (i, &value) in values.iter().enumerate() {
                let start = i / per_long as usize * 8;
                let word = u64::from_be_bytes(data[start..start + 8].try_into().unwrap());
                let index = (word >> (i % per_long as usize * bits as usize)) & ((1 << bits) - 1);
                assert_eq!(direct.palette[index as usize], value);
            }
            let used = values.len() % per_long as usize;
            if used != 0 {
                let word = u64::from_be_bytes(data[data.len() - 8..].try_into().unwrap());
                assert_eq!(word >> (used * bits as usize), 0, "unused final bits");
            }
        }
    }

    #[test]
    #[ignore = "requires actual MCA section fixtures in RETINA_PALETTE_FIXTURES"]
    fn actual_section_palette_encoding_benchmark() {
        let root = std::path::PathBuf::from(std::env::var("RETINA_PALETTE_FIXTURES").unwrap());
        for name in ["vanilla", "terralith"] {
            let profile: serde_json::Value =
                serde_json::from_slice(&fs::read(root.join(format!("{name}.json"))).unwrap())
                    .unwrap();
            let materials: Vec<_> = profile["materials"]
                .as_array()
                .unwrap()
                .iter()
                .map(material_nbt)
                .collect();
            let bytes = fs::read(root.join(format!("{name}-sections.bin"))).unwrap();
            assert_eq!(bytes.len() % 8192, 0);
            let sections: Vec<Vec<u16>> = bytes
                .chunks_exact(8192)
                .map(|s| {
                    s.chunks_exact(2)
                        .map(|v| u16::from_le_bytes(v.try_into().unwrap()))
                        .collect()
                })
                .collect();
            let mut palettes = [PaletteScratch::default(), PaletteScratch::default()];
            let mut nbts = [Nbt::default(), Nbt::default()];
            for section in &sections {
                for variant in 0..2 {
                    encode_sections(
                        std::slice::from_ref(section),
                        materials.len(),
                        &materials,
                        variant == 0,
                        &mut palettes[variant],
                        &mut nbts[variant],
                    );
                }
                assert_eq!(nbts[0].0, nbts[1].0, "{name}: changed section NBT");
            }
            let mut samples = [Vec::new(), Vec::new()];
            for iteration in 0..30 {
                // Reverse the paired order on every iteration. Timers exclude
                // registry loading and include palette construction and writes.
                for variant in if iteration % 2 == 0 { [0, 1] } else { [1, 0] } {
                    let start = Instant::now();
                    let bytes = encode_sections(
                        black_box(&sections),
                        materials.len(),
                        &materials,
                        variant == 0,
                        &mut palettes[variant],
                        &mut nbts[variant],
                    );
                    black_box(bytes);
                    if iteration >= 5 {
                        samples[variant].push(start.elapsed().as_secs_f64() * 1000.0);
                    }
                }
            }
            let report = serde_json::json!({"profile":name,"sections":sections.len(),"indexed_ms":samples[0],"direct_ms":samples[1]});
            fs::write(
                root.join(format!("{name}-palette-benchmark.json")),
                serde_json::to_vec_pretty(&report).unwrap(),
            )
            .unwrap();
            for variant in 0..2 {
                samples[variant].sort_by(f64::total_cmp);
            }
            eprintln!(
                "{name}: {} exact sections; indexed median {:.3} ms, direct median {:.3} ms; {:.3}x",
                sections.len(),
                samples[0][12],
                samples[1][12],
                samples[0][12] / samples[1][12]
            );
        }
    }
}
