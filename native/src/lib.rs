use std::cell::RefCell;
use std::panic::{AssertUnwindSafe, catch_unwind};
use std::sync::{Arc, OnceLock, mpsc};
use std::time::{Duration, Instant};

use bytemuck::{Pod, Zeroable};
use gpu::Gpu;
use profile::{Column, WorldProfile};
use std::collections::{HashMap, VecDeque};
use std::sync::{Mutex, RwLock};

mod gpu;
pub mod profile;

pub mod region;

pub const CHUNK_SIDE: usize = 16;
pub const COLUMNS: usize = CHUNK_SIDE * CHUNK_SIDE;
pub const AIR: u8 = 0;
pub const STONE: u8 = 1;
const MAX_BATCH: usize = 1024;
const BATCH_WAIT: Duration = Duration::from_micros(250);

/// C ABI layout. reserved is the resident world-profile handle (0 = legacy stone).
/// Keep field offsets in sync with NativeTerrain.java.
#[repr(C)]
#[derive(Clone, Copy, Debug)]
pub struct ChunkRequest {
    pub seed: u64,
    pub chunk_x: i32,
    pub chunk_z: i32,
    pub min_y: i32,
    pub height: u32,
    pub base_height: f32,
    pub amplitude: f32,
    pub frequency: f32,
    pub reserved: u32,
}

impl ChunkRequest {
    pub fn validate(&self) -> Result<(), String> {
        if self.height == 0 || self.height > 4096 {
            return Err("chunk height must be between 1 and 4096".into());
        }
        self.min_y
            .checked_add(self.height as i32)
            .ok_or("vertical range overflows")?;
        self.chunk_x
            .checked_mul(16)
            .ok_or("chunk X overflows block coordinates")?;
        self.chunk_z
            .checked_mul(16)
            .ok_or("chunk Z overflows block coordinates")?;
        if !self.base_height.is_finite()
            || !self.amplitude.is_finite()
            || self.amplitude < 0.0
            || !self.frequency.is_finite()
            || self.frequency <= 0.0
        {
            return Err("invalid simplex settings".into());
        }
        Ok(())
    }

    pub fn block_count(&self) -> usize {
        COLUMNS * self.height as usize
    }
}

#[repr(C)]
#[derive(Clone, Copy, Pod, Zeroable)]
struct GpuRequest {
    origin_x: i32,
    origin_z: i32,
    min_y: i32,
    max_y: i32,
    seed_low: u32,
    seed_high: u32,
    base_height: f32,
    amplitude: f32,
    frequency: f32,
    profile: u32,
    tile_side: u32,
    padding: u32,
}

impl From<ChunkRequest> for GpuRequest {
    fn from(r: ChunkRequest) -> Self {
        Self {
            origin_x: r.chunk_x * 16,
            origin_z: r.chunk_z * 16,
            min_y: r.min_y,
            max_y: r.min_y + r.height as i32,
            seed_low: r.seed as u32,
            seed_high: (r.seed >> 32) as u32,
            base_height: r.base_height,
            amplitude: r.amplitude,
            frequency: r.frequency,
            profile: r.reserved,
            tile_side: 0,
            padding: 0,
        }
    }
}

struct Job {
    requests: Vec<ChunkRequest>,
    profile: Option<Arc<WorldProfile>>,
    region: bool,
    reply: mpsc::Sender<Result<Vec<Column>, String>>,
}

/// Concurrent callers enqueue work; one persistent device batches GPU submissions.
/// Each caller assembles its own chunk in Rust after its GPU result arrives.
pub struct TerrainEngine {
    sender: mpsc::SyncSender<Job>,
    backend: String,
    profiles: RwLock<Vec<Arc<WorldProfile>>>,
    cache: Mutex<ColumnCache>,
}

impl TerrainEngine {
    pub fn new() -> Result<Self, String> {
        let (sender, receiver) = mpsc::sync_channel::<Job>(256);
        let (ready_sender, ready_receiver) = mpsc::sync_channel(1);
        std::thread::Builder::new()
            .name("retina-gpu".into())
            .spawn(move || {
                let startup = catch_unwind(AssertUnwindSafe(Gpu::new));
                let mut gpu = match startup {
                    Ok(Ok(gpu)) => {
                        let _ = ready_sender.send(Ok(gpu.backend.clone()));
                        gpu
                    }
                    Ok(Err(error)) => {
                        let _ = ready_sender.send(Err(error));
                        return;
                    }
                    Err(_) => {
                        let _ = ready_sender.send(Err("GPU initialization panicked".into()));
                        return;
                    }
                };
                let mut pending = None;
                loop {
                    let first = match pending.take().or_else(|| receiver.recv().ok()) {
                        Some(first) => first,
                        None => break,
                    };
                    let profile_id = first.requests[0].reserved;
                    let region = first.region;
                    let mut count = if region {
                        MAX_BATCH
                    } else {
                        first.requests.len()
                    };
                    let mut batch = vec![first];
                    let deadline = Instant::now() + BATCH_WAIT;
                    while count < MAX_BATCH {
                        match receiver
                            .recv_timeout(deadline.saturating_duration_since(Instant::now()))
                        {
                            Ok(job) => {
                                if job.region
                                    || job.requests[0].reserved != profile_id
                                    || count + job.requests.len() > MAX_BATCH
                                {
                                    pending = Some(job);
                                    break;
                                }
                                count += job.requests.len();
                                batch.push(job);
                            }
                            Err(_) => break,
                        }
                    }
                    let mut requests: Vec<_> = batch
                        .iter()
                        .flat_map(|job| job.requests.iter().copied().map(GpuRequest::from))
                        .collect();
                    if region {
                        requests[0].tile_side = 32;
                    }
                    let result = catch_unwind(AssertUnwindSafe(|| {
                        gpu.sample(&requests, batch[0].profile.as_deref())
                    }));
                    let result = match result {
                        Ok(result) => result,
                        Err(_) => Err("GPU dispatch panicked; see native stderr".into()),
                    };
                    match result {
                        Ok(heights) => {
                            let mut offset = 0;
                            for job in batch {
                                let end = offset
                                    + if job.region {
                                        MAX_BATCH
                                    } else {
                                        job.requests.len()
                                    } * COLUMNS;
                                let _ = job.reply.send(Ok(heights[offset..end].to_vec()));
                                offset = end;
                            }
                        }
                        Err(error) => {
                            for job in batch {
                                let _ = job.reply.send(Err(error.clone()));
                            }
                        }
                    }
                }
            })
            .map_err(|e| e.to_string())?;
        let backend = ready_receiver.recv().map_err(|e| e.to_string())??;
        Ok(Self {
            sender,
            backend,
            profiles: RwLock::new(Vec::new()),
            cache: Mutex::new(ColumnCache::default()),
        })
    }

    pub fn backend(&self) -> &str {
        &self.backend
    }

    pub fn register_profile(&self, bytes: &[u8]) -> Result<u32, String> {
        let profile = WorldProfile::parse(bytes)?;
        let mut profiles = self.profiles.write().map_err(|_| "profile lock poisoned")?;
        if let Some(index) = profiles.iter().position(|p| p.encoded == bytes) {
            return Ok(index as u32 + 1);
        }
        profiles.push(Arc::new(profile));
        Ok(profiles.len() as u32)
    }

    pub fn profile(&self, id: u32) -> Result<Option<Arc<WorldProfile>>, String> {
        if id == 0 {
            return Ok(None);
        }
        self.profiles
            .read()
            .map_err(|_| "profile lock poisoned")?
            .get(id as usize - 1)
            .cloned()
            .map(Some)
            .ok_or_else(|| "unknown world profile".into())
    }

    pub fn sample_heights(&self, request: ChunkRequest) -> Result<[i32; COLUMNS], String> {
        self.sample_columns(&[request])?
            .iter()
            .map(|c| c.height)
            .collect::<Vec<_>>()
            .try_into()
            .map_err(|_| "incorrect GPU height count".into())
    }

    pub fn sample_many(&self, requests: &[ChunkRequest]) -> Result<Vec<i32>, String> {
        Ok(self
            .sample_columns(requests)?
            .iter()
            .map(|c| c.height)
            .collect())
    }

    pub fn sample_columns(&self, requests: &[ChunkRequest]) -> Result<Vec<Column>, String> {
        if requests.is_empty() || requests.len() > MAX_BATCH {
            return Err("GPU batch must contain 1 to 1024 chunks".into());
        }
        for request in requests {
            request.validate()?;
            if request.reserved != requests[0].reserved {
                return Err("GPU batch profiles must match".into());
            }
        }
        if let Some(cached) = self
            .cache
            .lock()
            .map_err(|_| "column cache poisoned")?
            .get(requests)
        {
            return Ok(cached);
        }
        let result = self.dispatch(requests.to_vec(), false)?;
        self.cache
            .lock()
            .map_err(|_| "column cache poisoned")?
            .insert(requests, &result);
        Ok(result)
    }

    /// One 48-byte descriptor for all 512 x 512 columns; two GPU stages, one readback.
    pub fn sample_region(&self, mut request: ChunkRequest) -> Result<Vec<Column>, String> {
        request.chunk_x = request.chunk_x.div_euclid(32) * 32;
        request.chunk_z = request.chunk_z.div_euclid(32) * 32;
        let requests: Vec<_> = (0..1024)
            .map(|i| ChunkRequest {
                chunk_x: request.chunk_x + i % 32,
                chunk_z: request.chunk_z + i / 32,
                ..request
            })
            .collect();
        for request in &requests {
            request.validate()?;
        }
        if let Some(cached) = self
            .cache
            .lock()
            .map_err(|_| "column cache poisoned")?
            .get(&requests)
        {
            return Ok(cached);
        }
        let result = self.dispatch(vec![request], true)?;
        self.cache
            .lock()
            .map_err(|_| "column cache poisoned")?
            .insert(&requests, &result);
        Ok(result)
    }

    fn dispatch(&self, requests: Vec<ChunkRequest>, region: bool) -> Result<Vec<Column>, String> {
        let profile = self.profile(requests[0].reserved)?;
        let (reply, receiver) = mpsc::channel();
        self.sender
            .send(Job {
                requests,
                profile,
                region,
                reply,
            })
            .map_err(|_| "GPU worker stopped".to_string())?;
        receiver
            .recv()
            .map_err(|_| "GPU worker stopped before completing this request".to_string())?
    }

    pub fn generate_into(
        &self,
        request: ChunkRequest,
        blocks: &mut [u8],
    ) -> Result<[i32; COLUMNS], String> {
        let columns = self.generate_columns_into(request, blocks)?;
        Ok(std::array::from_fn(|i| columns[i].height))
    }

    pub fn generate_columns_into(
        &self,
        request: ChunkRequest,
        blocks: &mut [u8],
    ) -> Result<[Column; COLUMNS], String> {
        request.validate()?;
        if blocks.len() != request.block_count() {
            return Err("incorrect block buffer length".into());
        }
        let profile = self.profile(request.reserved)?;
        let columns = self.sample_columns(&[request])?;
        for (layer, row) in blocks.chunks_exact_mut(COLUMNS).enumerate() {
            for (block, column) in row.iter_mut().zip(&columns) {
                *block = column.material(
                    request.min_y + layer as i32,
                    request.min_y,
                    profile.as_deref(),
                );
            }
        }
        columns
            .try_into()
            .map_err(|_| "incorrect GPU column count".into())
    }
}

#[derive(Clone, Copy, Hash, Eq, PartialEq)]
struct CacheKey {
    seed: u64,
    x: i32,
    z: i32,
    min_y: i32,
    height: u32,
    base: u32,
    amplitude: u32,
    frequency: u32,
    profile: u32,
}
impl From<ChunkRequest> for CacheKey {
    fn from(r: ChunkRequest) -> Self {
        Self {
            seed: r.seed,
            x: r.chunk_x,
            z: r.chunk_z,
            min_y: r.min_y,
            height: r.height,
            base: r.base_height.to_bits(),
            amplitude: r.amplitude.to_bits(),
            frequency: r.frequency.to_bits(),
            profile: r.reserved,
        }
    }
}
#[derive(Default)]
struct ColumnCache {
    entries: HashMap<CacheKey, Box<[Column; COLUMNS]>>,
    order: VecDeque<CacheKey>,
}
impl ColumnCache {
    fn get(&self, requests: &[ChunkRequest]) -> Option<Vec<Column>> {
        let mut result = Vec::with_capacity(requests.len() * COLUMNS);
        for request in requests {
            result.extend_from_slice(self.entries.get(&(*request).into())?.as_ref());
        }
        Some(result)
    }
    fn insert(&mut self, requests: &[ChunkRequest], columns: &[Column]) {
        for (request, values) in requests.iter().zip(columns.chunks_exact(COLUMNS)) {
            let key = CacheKey::from(*request);
            if self.entries.contains_key(&key) {
                continue;
            }
            let tile: [Column; COLUMNS] = values.try_into().unwrap();
            self.entries.insert(key, Box::new(tile));
            self.order.push_back(key);
            if self.entries.len() > 8192 {
                self.entries.remove(&self.order.pop_front().unwrap());
            }
        }
    }
}

pub fn assemble_stone(request: ChunkRequest, heights: &[i32; COLUMNS], blocks: &mut [u8]) {
    for (layer, row) in blocks.chunks_exact_mut(COLUMNS).enumerate() {
        let y = request.min_y + layer as i32;
        for (block, height) in row.iter_mut().zip(heights) {
            *block = if y < *height { STONE } else { AIR };
        }
    }
}

static ENGINE: OnceLock<Result<Arc<TerrainEngine>, String>> = OnceLock::new();
thread_local! { static LAST_ERROR: RefCell<String> = const { RefCell::new(String::new()) }; }

fn shared_engine() -> Result<&'static Arc<TerrainEngine>, String> {
    ENGINE
        .get_or_init(|| TerrainEngine::new().map(Arc::new))
        .as_ref()
        .map_err(Clone::clone)
}

fn boundary(action: impl FnOnce() -> Result<(), String>) -> i32 {
    let result = catch_unwind(AssertUnwindSafe(action))
        .unwrap_or_else(|_| Err("native terrain call panicked".into()));
    match result {
        Ok(()) => 0,
        Err(error) => {
            LAST_ERROR.with(|slot| *slot.borrow_mut() = error);
            -1
        }
    }
}

#[unsafe(no_mangle)]
pub extern "C" fn retina_initialize() -> i32 {
    boundary(|| shared_engine().map(|_| ()))
}

/// # Safety
/// json contains length readable bytes; id points to a writable u32.
#[unsafe(no_mangle)]
pub unsafe extern "C" fn retina_register_profile(
    json: *const u8,
    length: u64,
    id: *mut u32,
) -> i32 {
    boundary(|| {
        if json.is_null() || id.is_null() {
            return Err("null profile buffer".into());
        }
        let profile = shared_engine()?
            .register_profile(unsafe { std::slice::from_raw_parts(json, length as usize) })?;
        unsafe {
            *id = profile;
        }
        Ok(())
    })
}

/// # Safety
/// request is readable and columns points to 256 writable Column records.
#[unsafe(no_mangle)]
pub unsafe extern "C" fn retina_sample_columns(
    request: *const ChunkRequest,
    columns: *mut Column,
) -> i32 {
    boundary(|| {
        if request.is_null() || columns.is_null() {
            return Err("null column buffer".into());
        }
        let result = shared_engine()?.sample_columns(&[unsafe { *request }])?;
        unsafe {
            std::ptr::copy_nonoverlapping(result.as_ptr(), columns, COLUMNS);
        }
        Ok(())
    })
}

/// # Safety
/// request is readable, blocks has capacity writable bytes, columns has 256 records.
#[unsafe(no_mangle)]
pub unsafe extern "C" fn retina_generate_chunk_columns(
    request: *const ChunkRequest,
    blocks: *mut u8,
    capacity: u64,
    columns: *mut Column,
) -> i32 {
    boundary(|| {
        if request.is_null() || blocks.is_null() || columns.is_null() {
            return Err("null chunk buffer".into());
        }
        let request = unsafe { *request };
        request.validate()?;
        if capacity != request.block_count() as u64 {
            return Err("incorrect native chunk buffer capacity".into());
        }
        let result = shared_engine()?.generate_columns_into(request, unsafe {
            std::slice::from_raw_parts_mut(blocks, request.block_count())
        })?;
        unsafe {
            std::ptr::copy_nonoverlapping(result.as_ptr(), columns, COLUMNS);
        }
        Ok(())
    })
}

/// # Safety
/// request is readable; output points to request.height writable bytes.
#[unsafe(no_mangle)]
pub unsafe extern "C" fn retina_generate_column(
    request: *const ChunkRequest,
    column: u32,
    output: *mut u8,
) -> i32 {
    boundary(|| {
        if request.is_null() || output.is_null() || column >= COLUMNS as u32 {
            return Err("invalid column buffer".into());
        }
        let request = unsafe { *request };
        let engine = shared_engine()?;
        let profile = engine.profile(request.reserved)?;
        let column = engine.sample_columns(&[request])?[column as usize];
        let output = unsafe { std::slice::from_raw_parts_mut(output, request.height as usize) };
        for (layer, block) in output.iter_mut().enumerate() {
            *block = column.material(
                request.min_y + layer as i32,
                request.min_y,
                profile.as_deref(),
            );
        }
        Ok(())
    })
}

/// # Safety
/// The request and output buffers must be valid, aligned, and exclusively owned
/// by the caller for the complete duration of this synchronous call.
#[unsafe(no_mangle)]
pub unsafe extern "C" fn retina_generate_chunk(
    request: *const ChunkRequest,
    blocks: *mut u8,
    capacity: u64,
    heights: *mut i32,
) -> i32 {
    boundary(|| {
        if request.is_null() || blocks.is_null() || heights.is_null() {
            return Err("null native buffer".into());
        }
        let request = unsafe { *request };
        request.validate()?;
        if capacity != request.block_count() as u64 {
            return Err("incorrect native chunk buffer capacity".into());
        }
        let blocks = unsafe { std::slice::from_raw_parts_mut(blocks, request.block_count()) };
        let result = shared_engine()?.generate_into(request, blocks)?;
        unsafe {
            std::ptr::copy_nonoverlapping(result.as_ptr(), heights, COLUMNS);
        }
        Ok(())
    })
}

/// # Safety
/// The request must be valid and heights must point to 256 writable i32 values.
#[unsafe(no_mangle)]
pub unsafe extern "C" fn retina_sample_heights(
    request: *const ChunkRequest,
    heights: *mut i32,
) -> i32 {
    boundary(|| {
        if request.is_null() || heights.is_null() {
            return Err("null native height buffer".into());
        }
        let request = unsafe { *request };
        let result = shared_engine()?.sample_heights(request)?;
        unsafe {
            std::ptr::copy_nonoverlapping(result.as_ptr(), heights, COLUMNS);
        }
        Ok(())
    })
}

/// # Safety
/// Strings are readable UTF-8 buffers with the stated lengths. request/report must
/// be aligned and valid for this call. The caller exclusively owns region-file I/O.
#[unsafe(no_mangle)]
pub unsafe extern "C" fn retina_generate_region(
    request: *const ChunkRequest,
    path: *const u8,
    path_len: u64,
    data_version: i32,
    biome: *const u8,
    biome_len: u64,
    report: *mut region::RegionReport,
) -> i32 {
    boundary(|| {
        if request.is_null() || path.is_null() || biome.is_null() || report.is_null() {
            return Err("null native region buffer".into());
        }
        let path =
            std::str::from_utf8(unsafe { std::slice::from_raw_parts(path, path_len as usize) })
                .map_err(|e| e.to_string())?;
        let biome =
            std::str::from_utf8(unsafe { std::slice::from_raw_parts(biome, biome_len as usize) })
                .map_err(|e| e.to_string())?;
        let result = region::generate_region(
            shared_engine()?,
            unsafe { *request },
            std::path::Path::new(path),
            data_version,
            biome,
        )?;
        unsafe {
            *report = result;
        }
        Ok(())
    })
}

unsafe fn copy_message(message: &str, buffer: *mut u8, capacity: u64) -> u64 {
    if buffer.is_null() || capacity == 0 {
        return 0;
    }
    let length = message.len().min(capacity as usize);
    unsafe {
        std::ptr::copy_nonoverlapping(message.as_ptr(), buffer, length);
    }
    length as u64
}

/// # Safety
/// buffer must point to capacity writable bytes.
#[unsafe(no_mangle)]
pub unsafe extern "C" fn retina_last_error(buffer: *mut u8, capacity: u64) -> u64 {
    LAST_ERROR.with(|slot| unsafe { copy_message(&slot.borrow(), buffer, capacity) })
}

/// # Safety
/// buffer must point to capacity writable bytes.
#[unsafe(no_mangle)]
pub unsafe extern "C" fn retina_backend(buffer: *mut u8, capacity: u64) -> u64 {
    match shared_engine() {
        Ok(engine) => unsafe { copy_message(engine.backend(), buffer, capacity) },
        Err(error) => unsafe { copy_message(&error, buffer, capacity) },
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn assembly_uses_y_z_x_order_and_negative_minimum() {
        let request = ChunkRequest {
            seed: 0,
            chunk_x: -1,
            chunk_z: -1,
            min_y: -2,
            height: 4,
            base_height: 0.0,
            amplitude: 1.0,
            frequency: 0.01,
            reserved: 0,
        };
        let mut heights = [-1; COLUMNS];
        heights[1] = 1;
        heights[16] = 0;
        let mut blocks = vec![99; request.block_count()];
        assemble_stone(request, &heights, &mut blocks);
        assert_eq!(blocks[0], STONE);
        assert_eq!(blocks[COLUMNS], AIR);
        assert_eq!(blocks[2 * COLUMNS + 1], STONE);
        assert_eq!(blocks[3 * COLUMNS + 1], AIR);
        assert_eq!(blocks[COLUMNS + 16], STONE);
        assert_eq!(blocks[2 * COLUMNS + 16], AIR);
    }

    #[test]
    fn abi_layout_is_stable() {
        assert_eq!(std::mem::size_of::<ChunkRequest>(), 40);
        assert_eq!(std::mem::offset_of!(ChunkRequest, frequency), 32);
        assert_eq!(std::mem::size_of::<GpuRequest>(), 48);
        assert_eq!(std::mem::size_of::<region::RegionReport>(), 40);
        assert_eq!(std::mem::offset_of!(region::RegionReport, gpu_nanos), 8);
    }
}
