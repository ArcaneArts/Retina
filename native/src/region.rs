//! Anvil terrain writer. The caller owns Minecraft's region I/O queue while this runs.
//! Existing chunk records (including external .mcc records) are never regenerated.
use std::fs::{self, OpenOptions};
use std::io::{ErrorKind, Write};
use std::path::Path;
use std::sync::OnceLock;
use std::sync::atomic::{AtomicU64, Ordering};
use std::time::{Instant, SystemTime, UNIX_EPOCH};

use flate2::{Compression, write::ZlibEncoder};
use rayon::prelude::*;

use crate::{
    COLUMNS, ChunkRequest, TerrainEngine, decoration, geology,
    profile::{Column, WorldProfile},
};

const SECTOR: usize = 4096;
const HEADER: usize = SECTOR * 2;
static TEMP_ID: AtomicU64 = AtomicU64::new(0);
static ASSEMBLERS: OnceLock<Result<rayon::ThreadPool, String>> = OnceLock::new();

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
    generate_region_inner(engine, request, path, data_version, biome, None, None)
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
    )
}

/// Install cached compressed records into missing slots; existing records remain unchanged.
pub fn publish_cached_region(
    engine: &TerrainEngine,
    request: ChunkRequest,
    path: &Path,
    cached: &Path,
) -> Result<RegionReport, String> {
    generate_region_inner(engine, request, path, 0, "", None, Some(cached))
}

fn generate_region_inner(
    engine: &TerrainEngine,
    request: ChunkRequest,
    path: &Path,
    data_version: i32,
    biome: &str,
    output: Option<&mut [Column]>,
    cached: Option<&Path>,
) -> Result<RegionReport, String> {
    request.validate()?;
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
    let mut file = match fs::read(path) {
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
        let source =
            fs::read(cached).map_err(|e| format!("read preview {}: {e}", cached.display()))?;
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
        let (field, cave_mask) = if profile
            .as_deref()
            .is_some_and(|p| decorated || !p.geology.ores.is_empty() || p.geology.caves_enabled(p))
        {
            engine.terrain_field(origin, 32)?
        } else {
            (
                decoration::Field {
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
        let structure_plans = crate::structures::plans(engine, origin, 32)?;
        report.gpu_nanos = start.elapsed().as_nanos() as u64;
        assemblers()?.install(|| {
            let overlays = if decorated {
                decoration::plan(
                    &field,
                    profile.as_deref().unwrap(),
                    origin,
                    origin.chunk_x,
                    origin.chunk_z,
                    32,
                    cave_mask.as_deref(),
                )
            } else {
                vec![Vec::new(); 1024]
            };
            let ores = if let Some(p) = profile.as_deref() {
                geology::plan(&field, p, origin, 32, cave_mask.as_deref())
            } else {
                vec![Vec::new(); 1024]
            };
            requests
                .par_iter()
                .enumerate()
                .map(|(index, &request)| {
                    let columns = field.chunk(request.chunk_x, request.chunk_z);
                    let mut blocks = vec![0; request.block_count()];
                    decoration::assemble(request, columns, profile.as_deref(), &[], &mut blocks);
                    if let Some(p) = profile.as_deref() {
                        geology::apply(
                            request,
                            &field,
                            p,
                            cave_mask.as_deref(),
                            &ores[slots[index]],
                            &mut blocks,
                        );
                    }
                    if let Some(p) = profile.as_deref() {
                        crate::features::apply(request, p, cave_mask.as_deref(), &mut blocks);
                    }
                    decoration::decorate(profile.as_deref(), &overlays[slots[index]], &mut blocks);
                    let structure_data = profile.as_deref().map(|p| {
                        crate::structures::apply(request, p, &structure_plans, Some(&mut blocks))
                    });
                    if let Some(p) = profile.as_deref() {
                        crate::features::snow(request, p, columns, &mut blocks);
                    }
                    let nbt = chunk_nbt_blocks(
                        request,
                        columns,
                        &blocks,
                        data_version,
                        biome,
                        profile.as_deref(),
                        cave_mask.as_deref(),
                        structure_data.as_ref().map(|data| &data.tag),
                    );
                    let mut compressed = ZlibEncoder::new(Vec::new(), Compression::fast());
                    compressed.write_all(&nbt).map_err(|e| e.to_string())?;
                    compressed.finish().map_err(|e| e.to_string())
                })
                .collect()
        })
    };
    let records = records?;
    report.assembly_nanos = (start.elapsed().as_nanos() as u64).saturating_sub(report.gpu_nanos);
    let start = Instant::now();
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
    Ok(report)
}

/// Original, narrow NBT encoder for the generated terrain schema. No numeric registry IDs.
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
    let heights: Vec<_> = columns.iter().map(|c| c.height).collect();
    let mut nbt = Nbt(Vec::with_capacity(20_000));
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
            let (palette, indices) = make_palette(values);
            nbt.list("palette", 10, palette.len());
            for id in &palette {
                let material = &profile.materials[*id as usize];
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
            }
            if palette.len() > 1 {
                nbt.longs("data", &pack(&indices, palette_bits(palette.len()).max(4)));
            }
            nbt.end();
            nbt.compound("biomes");
            // GPU quart biomes use Minecraft's x,z,y order.
            let values: Vec<_> = (0..64)
                .map(|index| {
                    cave_mask
                        .and_then(|m| {
                            m.biome(
                                request.chunk_x * 16 + (index % 4) * 4,
                                y + (index / 16) * 4,
                                request.chunk_z * 16 + ((index % 16) / 4) * 4,
                            )
                        })
                        .unwrap_or(
                            columns[((index % 16) / 4) as usize * 64 + (index % 4) as usize * 4]
                                .biome() as u16,
                        )
                })
                .collect();
            let (palette, indices) = make_palette(&values);
            nbt.list("palette", 8, palette.len());
            for id in &palette {
                nbt.text(&profile.biomes[*id as usize].id);
            }
            if palette.len() > 1 {
                nbt.longs("data", &pack(&indices, palette_bits(palette.len())));
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
    let ground: Vec<u32> = columns
        .iter()
        .map(|column| {
            let icy = profile.is_some() && column.packed & (1 << 28) != 0;
            let height = if icy {
                column.surface_height(profile)
            } else {
                column.height
            };
            (height - request.min_y) as u32
        })
        .collect();
    let surface: Vec<u32> = columns
        .iter()
        .map(|column| (column.surface_height(profile) - request.min_y) as u32)
        .collect();
    let mut final_heights = vec![vec![0u32; COLUMNS]; 6];
    if let Some(profile) = profile {
        for c in 0..COLUMNS {
            let mut pending = 63u8;
            for layer in (0..request.height as usize).rev() {
                let hit = profile.heightmap_masks[blocks[layer * COLUMNS + c] as usize] & pending;
                for (kind, heights) in final_heights.iter_mut().enumerate() {
                    if hit & (1 << kind) != 0 {
                        heights[c] = layer as u32 + 1;
                    }
                }
                pending &= !hit;
                if pending == 0 {
                    break;
                }
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
            &final_heights[kind]
        } else if name.starts_with("OCEAN_FLOOR") {
            &ground
        } else {
            &surface
        };
        nbt.longs(name, &pack(values, bits));
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
    nbt.0
}

fn make_palette<T: Copy + Into<usize>>(values: &[T]) -> (Vec<T>, Vec<u32>) {
    let mut palette = Vec::new();
    let mut lookup = vec![
        u32::MAX;
        values
            .iter()
            .map(|value| (*value).into())
            .max()
            .unwrap_or(0)
            + 1
    ];
    let indices = values
        .iter()
        .map(|value| {
            let id: usize = (*value).into();
            if lookup[id] == u32::MAX {
                lookup[id] = palette.len() as u32;
                palette.push(*value);
            }
            lookup[id]
        })
        .collect();
    (palette, indices)
}
fn palette_bits(length: usize) -> u32 {
    usize::BITS - (length - 1).leading_zeros()
}
fn pack(values: &[u32], bits: u32) -> Vec<u64> {
    let per_long = 64 / bits as usize;
    let mut packed = vec![0; values.len().div_ceil(per_long)];
    for (index, value) in values.iter().enumerate() {
        packed[index / per_long] |= (*value as u64) << ((index % per_long) * bits as usize);
    }
    packed
}
