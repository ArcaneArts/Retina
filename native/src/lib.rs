mod climate;
mod column_program;
mod nbt;
mod program;
mod specialize;
mod structure_processors;
pub mod structures;
pub mod timings;
use std::cell::RefCell;
use std::panic::{AssertUnwindSafe, catch_unwind};
use std::sync::{Arc, OnceLock, mpsc};
use std::time::{Duration, Instant};

use bytemuck::{Pod, Zeroable};
use profile::{Column, WorldProfile};
use std::collections::{HashMap, VecDeque};
use std::sync::{Mutex, RwLock};

pub mod decoration;
mod features;
pub mod geology;
mod gpu;
mod gpu_worker;
pub mod pipeline;
pub mod profile;
mod tree_shapes;

pub mod region;

pub const CHUNK_SIDE: usize = 16;
pub const COLUMNS: usize = CHUNK_SIDE * CHUNK_SIDE;
pub const AIR: u16 = 0;
pub const STONE: u16 = 1;
const MAX_BATCH: usize = 1024;
const MAX_TILES: usize = 38 * 38;
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
    density_offset: u32,
    density_side: u32,
    density_step_xz: u32,
    density_step_y: u32,
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
            density_offset: 0,
            density_side: 0,
            density_step_xz: 4,
            density_step_y: 8,
        }
    }
}

struct Job {
    requests: Vec<ChunkRequest>,
    profile: Option<Arc<WorldProfile>>,
    tile_side: u32,
    cave_side: u32,
    probe_mode: u32,
    probe_y: Vec<i32>,
    reply: mpsc::Sender<Result<gpu::GpuSample, String>>,
    queued: Instant,
    queue_nanos: u64,
    timings: Arc<timings::Timings>,
}

/// Concurrent callers enqueue work; one persistent device batches GPU submissions.
/// Each caller assembles its own chunk in Rust after its GPU result arrives.
pub struct TerrainEngine {
    sender: mpsc::SyncSender<Job>,
    backend: String,
    profiles: RwLock<Vec<Arc<WorldProfile>>>,
    cache: Mutex<ColumnCache>,
    height_cache: Mutex<ColumnCache>,
    caves: Mutex<CaveCache>,
    structures: Mutex<structures::Cache>,
    timings: Mutex<HashMap<u32, Arc<timings::Timings>>>,
    pipeline: Arc<pipeline::Metrics>,
    ore_gpu: Mutex<geology::raster_gpu::Gpu>,
    feature_gpu: Mutex<decoration::counts::gpu::Gpu>,
}

impl TerrainEngine {
    pub fn new() -> Result<Self, String> {
        Self::with_pipeline_depth(2)
    }
    /// One-slot mode supports matched diagnostics; normal generation uses two.
    pub fn with_pipeline_depth(depth: usize) -> Result<Self, String> {
        if !(1..=2).contains(&depth) {
            return Err("GPU pipeline depth must be 1 or 2".into());
        }
        let (sender, receiver) = mpsc::sync_channel::<Job>(256);
        let (ready_sender, ready_receiver) = mpsc::sync_channel(1);
        let pipeline = Arc::new(pipeline::Metrics::default());
        let metrics = pipeline.clone();
        std::thread::Builder::new()
            .name("retina-gpu".into())
            .spawn(move || gpu_worker::run(receiver, ready_sender, metrics, depth))
            .map_err(|e| e.to_string())?;
        let (backend, ores, counts) = ready_receiver.recv().map_err(|e| e.to_string())??;
        Ok(Self {
            sender,
            backend,
            pipeline,
            ore_gpu: Mutex::new(ores),
            feature_gpu: Mutex::new(counts),
            profiles: RwLock::new(Vec::new()),
            cache: Mutex::new(ColumnCache::default()),
            height_cache: Mutex::new(ColumnCache::default()),
            caves: Mutex::new(CaveCache::default()),
            structures: Mutex::new(structures::Cache::default()),
            timings: Mutex::new(HashMap::new()),
        })
    }

    pub fn pipeline_snapshot(&self) -> pipeline::Snapshot {
        self.pipeline.snapshot()
    }

    pub fn timings(&self, profile: u32) -> Arc<timings::Timings> {
        self.timings
            .lock()
            .unwrap_or_else(|e| e.into_inner())
            .entry(profile)
            .or_default()
            .clone()
    }
    pub fn backend(&self) -> &str {
        &self.backend
    }

    pub(crate) fn plan_ores(
        &self,
        field: &decoration::Field,
        profile: &WorldProfile,
        request: ChunkRequest,
        side: usize,
        mask: Option<&geology::CaveMask>,
        job: Option<&timings::Timings>,
    ) -> Result<geology::RegionPlan, String> {
        if profile.geology.ore_layout < 2 || side == 1 || profile.geology.ores.is_empty() {
            return Ok(geology::RegionPlan::Cpu(geology::plan(
                field, profile, request, side, mask,
            )));
        }
        let batch = geology::raster::Batch::prepare_compact(field, profile, request, side, mask);
        let result = self
            .ore_gpu
            .lock()
            .map_err(|_| "ore GPU lock poisoned")?
            .run(&batch)?;
        self.pipeline.transfer(
            request.reserved,
            (batch.descriptors.len() * 48 + batch.spheres.len() * 16) as u64,
            result.readback_bytes,
        );
        if let Some(nanos) = result.device_nanos {
            self.timings(request.reserved)
                .device(timings::ORE_MASK, nanos);
            if let Some(job) = job {
                job.device(timings::ORE_MASK, nanos);
            }
        }
        Ok(geology::RegionPlan::Gpu {
            batch,
            words: result.words,
        })
    }

    fn feature_counts(
        &self,
        profile: &WorldProfile,
        request: ChunkRequest,
        queries: &[decoration::counts::Query],
        job: Option<&timings::Timings>,
    ) -> Result<Vec<i32>, String> {
        let noise = profile
            .decoration_noise
            .as_ref()
            .ok_or("profile has no registered placement noise")?;
        let result = self
            .feature_gpu
            .lock()
            .map_err(|_| "feature GPU lock poisoned")?
            .run(request.reserved, noise, queries)?;
        self.pipeline
            .transfer(request.reserved, result.upload, result.readback);
        if let Some(nanos) = result.device_nanos {
            self.timings(request.reserved)
                .device(timings::FEATURE_COUNTS, nanos);
            if let Some(job) = job {
                job.device(timings::FEATURE_COUNTS, nanos);
            }
        }
        Ok(result.values.into_iter().map(|v| v as i32).collect())
    }

    /// Sparse diagnostics use exactly the production rule descriptors/sampler.
    pub fn decoration_counts(
        &self,
        request: ChunkRequest,
        points: &[[i32; 4]],
    ) -> Result<Vec<i32>, String> {
        request.validate()?;
        let profile = self
            .profile(request.reserved)?
            .ok_or("count queries require a registered profile")?;
        let queries = points
            .iter()
            .map(|&[x, z, recipe, op]| {
                let program = profile
                    .decorations
                    .get(recipe as usize)
                    .and_then(|r| r.placement.as_ref())
                    .ok_or("invalid decoration count recipe")?;
                let rule = program
                    .get(op as usize)
                    .and_then(|op| op.noise_rule())
                    .ok_or("invalid decoration noise count modifier")?;
                Ok(decoration::counts::Query { x, z, rule })
            })
            .collect::<Result<Vec<_>, String>>()?;
        self.feature_counts(&profile, request, &queries, None)
    }

    /// Sparse XYZ provider-noise sampling, shared by diagnostics and feature replay.
    pub fn provider_noise(
        &self,
        request: ChunkRequest,
        points: &[[i32; 4]],
        job: Option<&timings::Timings>,
    ) -> Result<Vec<f32>, String> {
        request.validate()?;
        let profile = self
            .profile(request.reserved)?
            .ok_or("provider noise requires a registered profile")?;
        let result = self
            .feature_gpu
            .lock()
            .map_err(|_| "feature GPU lock poisoned")?
            .run_providers(
                request.reserved,
                &profile.decoration_provider_noises,
                points,
            )?;
        self.pipeline
            .transfer(request.reserved, result.upload, result.readback);
        if let Some(nanos) = result.device_nanos {
            self.timings(request.reserved)
                .device(timings::PROVIDER_NOISE, nanos);
            if let Some(job) = job {
                job.device(timings::PROVIDER_NOISE, nanos);
            }
        }
        Ok(result.values.into_iter().map(f32::from_bits).collect())
    }

    /// Spatial counts/provider noise cross the device boundary. Crowns, live canopy,
    /// survival and recipe/anchor write order stay in the parallel Rust planner.
    pub fn plan_decorations(
        &self,
        field: &decoration::Field,
        profile: &WorldProfile,
        request: ChunkRequest,
        side: usize,
        mask: Option<&geology::CaveMask>,
    ) -> Result<Vec<Vec<decoration::Placement>>, String> {
        self.plan_decorations_profiled(field, profile, request, side, mask, None)
    }
    pub(crate) fn plan_decorations_profiled(
        &self,
        field: &decoration::Field,
        profile: &WorldProfile,
        request: ChunkRequest,
        side: usize,
        mask: Option<&geology::CaveMask>,
        job: Option<&timings::Timings>,
    ) -> Result<Vec<Vec<decoration::Placement>>, String> {
        let anchors = decoration::anchor_recipes(field, profile);
        let mut counts = decoration::counts::Counts::default();
        if profile.decoration_noise.is_some()
            && profile.decorations.iter().any(|r| {
                r.placement
                    .as_ref()
                    .is_some_and(|ops| ops.iter().any(|op| op.noise_rule().is_some()))
            })
        {
            loop {
                let queries = decoration::count_queries(field, profile, request, &counts, &anchors);
                if queries.is_empty() {
                    break;
                }
                let values = self.feature_counts(profile, request, &queries, job)?;
                counts.extend(queries.into_iter().zip(values));
            }
        }
        let mut replay = decoration::Replay::new(
            field,
            profile,
            request,
            request.chunk_x,
            request.chunk_z,
            side,
            mask,
            Some(&anchors),
        );
        let mut samples = decoration::provider_noise::Samples::default();
        loop {
            let queries = replay.poll(&samples, profile.decoration_noise.as_ref().map(|_| &counts));
            if queries.is_empty() {
                return Ok(replay.finish());
            }
            if !queries.counts.is_empty() {
                let values = self.feature_counts(profile, request, &queries.counts, job)?;
                counts.extend(queries.counts.into_iter().zip(values));
            }
            if !queries.noise.is_empty() {
                let values = self.provider_noise(request, &queries.noise, job)?;
                samples.extend(queries.noise.into_iter().zip(values));
            }
        }
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

    /// Center biome probes reuse complete cached columns/quart biomes. Cold
    /// probes return one GPU record each, never a full cavity volume.
    pub fn sample_biomes(
        &self,
        requests: &[ChunkRequest],
        ys: Option<&[i32]>,
    ) -> Result<Vec<u16>, String> {
        if requests.is_empty() {
            return Ok(Vec::new());
        }
        if requests.len() > MAX_BATCH || ys.is_some_and(|y| y.len() != requests.len()) {
            return Err("invalid biome query batch".into());
        }
        for r in requests {
            r.validate()?;
            if r.reserved != requests[0].reserved {
                return Err("biome query profiles must match".into());
            }
        }
        let profile = self.profile(requests[0].reserved)?;
        let underground = ys.is_some()
            && profile
                .as_deref()
                .is_some_and(|p| p.geology.caves_enabled(p));
        let cached: Vec<_> = {
            let columns = self.cache.lock().map_err(|_| "column cache poisoned")?;
            let heights = self
                .height_cache
                .lock()
                .map_err(|_| "height cache poisoned")?;
            let caves = self.caves.lock().map_err(|_| "cave cache poisoned")?;
            requests
                .iter()
                .enumerate()
                .map(|(i, r)| {
                    let key = CacheKey::from(*r);
                    let surface = columns
                        .entries
                        .get(&key)
                        .or_else(|| heights.entries.get(&key))
                        .map(|c| c[8 * 16 + 8].biome() as u16);
                    if underground && (r.min_y..r.min_y + r.height as i32).contains(&ys.unwrap()[i])
                    {
                        caves.biome(key, r.chunk_x * 16 + 8, ys.unwrap()[i], r.chunk_z * 16 + 8)
                    } else {
                        surface
                    }
                })
                .collect()
        };
        let mut result = vec![0; requests.len()];
        let mut missing = Vec::new();
        let mut ids = Vec::new();
        let mut y = Vec::new();
        // Out-of-range underground starts have the surface biome, as before.
        for (i, (r, cached)) in requests.iter().zip(cached).enumerate() {
            if let Some(b) = cached {
                result[i] = b;
            } else if underground && !(r.min_y..r.min_y + r.height as i32).contains(&ys.unwrap()[i])
            {
                result[i] = self.sample_biomes(&[*r], None)?[0];
            } else {
                missing.push(*r);
                ids.push(i);
                if underground {
                    y.push(ys.unwrap()[i]);
                }
            }
        }
        if !missing.is_empty() {
            let samples = self.dispatch_job(missing, 0, 0, if underground { 2 } else { 1 }, y)?;
            for (i, c) in ids.into_iter().zip(samples.columns) {
                result[i] = c.biome() as u16;
            }
        }
        Ok(result)
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
        let cached: Vec<_> = {
            let cache = self.cache.lock().map_err(|_| "column cache poisoned")?;
            requests
                .iter()
                .map(|r| cache.entries.get(&CacheKey::from(*r)).cloned())
                .collect()
        };
        let missing: Vec<_> = requests
            .iter()
            .zip(&cached)
            .filter_map(|(r, c)| c.is_none().then_some(*r))
            .collect();
        let result = if missing.is_empty() {
            Vec::new()
        } else {
            self.dispatch(missing.clone(), 0)?
        };
        self.cache
            .lock()
            .map_err(|_| "column cache poisoned")?
            .insert(&missing, &result);
        let mut fresh = result.chunks_exact(COLUMNS);
        let mut out = Vec::with_capacity(requests.len() * COLUMNS);
        for chunk in cached {
            out.extend_from_slice(
                chunk
                    .as_deref()
                    .map(|c| c.as_slice())
                    .unwrap_or_else(|| fresh.next().unwrap()),
            );
        }
        Ok(out)
    }

    /// One descriptor and two GPU stages for a square tile, including decoration halos.
    pub fn sample_tile(&self, request: ChunkRequest, side: u32) -> Result<Vec<Column>, String> {
        if !(1..=34).contains(&side) {
            return Err("GPU tile side must be 1..34".into());
        }
        let requests: Vec<_> = (0..side * side)
            .map(|i| {
                Ok(ChunkRequest {
                    chunk_x: request
                        .chunk_x
                        .checked_add((i % side) as i32)
                        .ok_or("tile X overflows")?,
                    chunk_z: request
                        .chunk_z
                        .checked_add((i / side) as i32)
                        .ok_or("tile Z overflows")?,
                    ..request
                })
            })
            .collect::<Result<_, String>>()?;
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
        let cached: Vec<_> = {
            let cache = self.cache.lock().map_err(|_| "column cache poisoned")?;
            requests
                .iter()
                .map(|r| cache.entries.get(&CacheKey::from(*r)).cloned())
                .collect()
        };
        if cached.iter().any(Option::is_some) {
            let missing: Vec<_> = requests
                .iter()
                .zip(&cached)
                .filter_map(|(r, c)| c.is_none().then_some(*r))
                .collect();
            let mut samples = Vec::with_capacity(missing.len() * COLUMNS);
            for batch in missing.chunks(MAX_BATCH) {
                samples.extend(self.sample_columns(batch)?);
            }
            let mut chunks = samples.chunks_exact(COLUMNS);
            let mut result = Vec::with_capacity(requests.len() * COLUMNS);
            for chunk in cached {
                result.extend_from_slice(
                    chunk
                        .as_deref()
                        .map(|c| c.as_slice())
                        .unwrap_or_else(|| chunks.next().unwrap()),
                );
            }
            return Ok(result);
        }
        let result = self.dispatch(vec![request], side)?;
        self.cache
            .lock()
            .map_err(|_| "column cache poisoned")?
            .insert(&requests, &result);
        Ok(result)
    }

    /// Projected structure heights need lakes/fluid levels, but no surface
    /// material program. Reuse cached columns and dispatch only missing chunks.
    pub fn sample_height_tile(
        &self,
        request: ChunkRequest,
        side: u32,
        water_surface: bool,
    ) -> Result<Vec<i32>, String> {
        if !(1..=34).contains(&side) {
            return Err("height tile side must be 1..34".into());
        }
        let requests: Vec<_> = (0..side * side)
            .map(|i| {
                let r = ChunkRequest {
                    chunk_x: request
                        .chunk_x
                        .checked_add((i % side) as i32)
                        .ok_or("height tile X overflows")?,
                    chunk_z: request
                        .chunk_z
                        .checked_add((i / side) as i32)
                        .ok_or("height tile Z overflows")?,
                    ..request
                };
                r.validate()?;
                Ok(r)
            })
            .collect::<Result<_, String>>()?;
        let profile = self.profile(request.reserved)?;
        let cached: Vec<_> = {
            let cache = self.cache.lock().map_err(|_| "column cache poisoned")?;
            let heights = self
                .height_cache
                .lock()
                .map_err(|_| "height cache poisoned")?;
            requests
                .iter()
                .map(|r| {
                    cache
                        .entries
                        .get(&CacheKey::from(*r))
                        .or_else(|| heights.entries.get(&CacheKey::from(*r)))
                        .cloned()
                })
                .collect()
        };
        let missing: Vec<_> = requests
            .iter()
            .zip(&cached)
            .filter_map(|(r, c)| c.is_none().then_some(*r))
            .collect();
        let mut fresh = Vec::new();
        if missing.len() == requests.len() {
            fresh = self
                .dispatch_job(vec![request], side, 0, 3, Vec::new())?
                .columns;
        } else {
            for batch in missing.chunks(MAX_BATCH) {
                fresh.extend(
                    self.dispatch_job(batch.to_vec(), 0, 0, 3, Vec::new())?
                        .columns,
                );
            }
        }
        self.height_cache
            .lock()
            .map_err(|_| "height cache poisoned")?
            .insert(&missing, &fresh);
        let mut fresh = fresh.chunks_exact(COLUMNS);
        let mut heights = Vec::with_capacity(requests.len() * COLUMNS);
        for chunk in cached {
            let columns = chunk
                .as_deref()
                .map(|c| c.as_slice())
                .unwrap_or_else(|| fresh.next().unwrap());
            heights.extend(columns.iter().map(|c| {
                if water_surface {
                    c.surface_height(profile.as_deref())
                } else {
                    c.height
                }
            }));
        }
        Ok(heights)
    }

    pub fn sample_region(&self, mut request: ChunkRequest) -> Result<Vec<Column>, String> {
        request.chunk_x = request.chunk_x.div_euclid(32) * 32;
        request.chunk_z = request.chunk_z.div_euclid(32) * 32;
        self.sample_tile(request, 32)
    }

    pub fn decoration_field(
        &self,
        request: ChunkRequest,
        side: u32,
    ) -> Result<decoration::Field, String> {
        let origin = ChunkRequest {
            chunk_x: request
                .chunk_x
                .checked_sub(1)
                .ok_or("decoration X overflows")?,
            chunk_z: request
                .chunk_z
                .checked_sub(1)
                .ok_or("decoration Z overflows")?,
            ..request
        };
        Ok(decoration::Field {
            origin_x: origin.chunk_x,
            origin_z: origin.chunk_z,
            side: (side + 2) as usize,
            columns: self.sample_tile(origin, side + 2)?,
            substrate: None,
        })
    }

    /// Terrain generation adds GPU cavities; height/biome queries stay on the two-pass column path.
    pub fn terrain_field(
        &self,
        request: ChunkRequest,
        side: u32,
    ) -> Result<(decoration::Field, Option<Arc<geology::CaveMask>>), String> {
        self.terrain_field_profiled(request, side, None)
    }
    pub(crate) fn terrain_field_profiled(
        &self,
        request: ChunkRequest,
        side: u32,
        trace: Option<&mut timings::Snapshot>,
    ) -> Result<(decoration::Field, Option<Arc<geology::CaveMask>>), String> {
        if !(1..=32).contains(&side) {
            return Err("terrain side must be 1..32".into());
        }
        request.validate()?;
        let profile = self.profile(request.reserved)?;
        let material_halo = profile
            .as_deref()
            .is_some_and(|p| p.registry_program.as_ref().is_some_and(|r| r.material_halo));
        let patch_halo =
            material_halo && profile.as_deref().is_some_and(|p| p.decoration_patch_halo);
        if !profile.as_deref().is_some_and(|p| {
            p.geology.caves_enabled(p)
                || p.registry_program
                    .as_ref()
                    .is_some_and(|r| r.material_layers)
        }) {
            return Ok((self.decoration_field(request, side)?, None));
        }
        let key = CacheKey::from(request);
        let cached = self
            .caves
            .lock()
            .map_err(|_| "cave cache poisoned")?
            .entries
            .get(&key)
            .cloned();
        if side == 1 {
            if let Some(mask) = cached {
                let mut field = self.decoration_field(request, side)?;
                if material_halo {
                    field.substrate = Some(mask.clone());
                }
                return Ok((field, Some(mask)));
            }
        }
        // Complete runs cover existing decoration anchors and, for patches, their
        // outer footprint. One further tile keeps slope/cave/aquifer evaluation
        // away from tile edges. These guards do not add feature anchors.
        let ring = if patch_halo {
            3
        } else if material_halo {
            2
        } else {
            1
        };
        let tile_side = side + ring * 2;
        let origin = ChunkRequest {
            chunk_x: request
                .chunk_x
                .checked_sub(ring as i32)
                .ok_or("cave X overflows")?,
            chunk_z: request
                .chunk_z
                .checked_sub(ring as i32)
                .ok_or("cave Z overflows")?,
            ..request
        };
        // Validate the halo as well as the target, using the same checks as height tile jobs.
        for dz in 0..tile_side {
            for dx in 0..tile_side {
                ChunkRequest {
                    chunk_x: origin
                        .chunk_x
                        .checked_add(dx as i32)
                        .ok_or("cave X overflows")?,
                    chunk_z: origin
                        .chunk_z
                        .checked_add(dz as i32)
                        .ok_or("cave Z overflows")?,
                    ..origin
                }
                .validate()?;
            }
        }
        let sample = self.dispatch_full(vec![origin], tile_side, tile_side - 2)?;
        if let Some(trace) = trace {
            *trace = sample.timings;
        }
        let columns = Arc::new(sample.columns);
        let mut mask = sample.mask.ok_or("GPU cave job returned no mask")?;
        if patch_halo {
            mask.columns = Some(geology::ColumnHalo {
                origin_x: origin.chunk_x,
                origin_z: origin.chunk_z,
                side: tile_side as usize,
                columns: columns.clone(),
            });
        }
        let mask = Arc::new(mask);
        let requests: Vec<_> = (0..tile_side * tile_side)
            .map(|i| ChunkRequest {
                chunk_x: origin.chunk_x + (i % tile_side) as i32,
                chunk_z: origin.chunk_z + (i / tile_side) as i32,
                ..origin
            })
            .collect();
        self.cache
            .lock()
            .map_err(|_| "column cache poisoned")?
            .insert(&requests, &columns);
        self.caves
            .lock()
            .map_err(|_| "cave cache poisoned")?
            .insert(request, side, mask.clone());
        let columns = if material_halo {
            // Keep anchor counts/order and CPU planning bounds exactly as before.
            // The extra GPU guard's columns can still satisfy future cache queries.
            (ring - 1..ring - 1 + side + 2)
                .flat_map(|z| {
                    let start = (z * tile_side + ring - 1) as usize * COLUMNS;
                    columns[start..start + (side + 2) as usize * COLUMNS]
                        .iter()
                        .copied()
                })
                .collect()
        } else {
            Arc::try_unwrap(columns).unwrap_or_else(|columns| (*columns).clone())
        };
        Ok((
            decoration::Field {
                origin_x: request.chunk_x - 1,
                origin_z: request.chunk_z - 1,
                side: (side + 2) as usize,
                columns,
                substrate: material_halo.then(|| mask.clone()),
            },
            Some(mask),
        ))
    }

    fn dispatch(&self, requests: Vec<ChunkRequest>, tile_side: u32) -> Result<Vec<Column>, String> {
        Ok(self.dispatch_full(requests, tile_side, 0)?.columns)
    }
    fn dispatch_full(
        &self,
        requests: Vec<ChunkRequest>,
        tile_side: u32,
        cave_side: u32,
    ) -> Result<gpu::GpuSample, String> {
        self.dispatch_job(requests, tile_side, cave_side, 0, Vec::new())
    }
    fn dispatch_job(
        &self,
        requests: Vec<ChunkRequest>,
        tile_side: u32,
        cave_side: u32,
        probe_mode: u32,
        probe_y: Vec<i32>,
    ) -> Result<gpu::GpuSample, String> {
        let profile = self.profile(requests[0].reserved)?;
        let (reply, receiver) = mpsc::channel();
        let timing_profile = requests[0].reserved;
        self.sender
            .send(Job {
                requests,
                profile,
                tile_side,
                cave_side,
                probe_mode,
                probe_y,
                reply,
                queued: Instant::now(),
                queue_nanos: 0,
                timings: self.timings(timing_profile),
            })
            .map_err(|_| "GPU worker stopped".to_string())?;
        receiver
            .recv()
            .map_err(|_| "GPU worker stopped before completing this request".to_string())?
    }

    pub fn generate_into(
        &self,
        request: ChunkRequest,
        blocks: &mut [u16],
    ) -> Result<[i32; COLUMNS], String> {
        let columns = self.generate_columns_into(request, blocks)?;
        Ok(std::array::from_fn(|i| columns[i].height))
    }

    pub fn generate_columns_into(
        &self,
        request: ChunkRequest,
        blocks: &mut [u16],
    ) -> Result<[Column; COLUMNS], String> {
        request.validate()?;
        if blocks.len() != request.block_count() {
            return Err("incorrect block buffer length".into());
        }
        let profile = self.profile(request.reserved)?;
        let timings = self.timings(request.reserved);
        let mut plant_updates = decoration::pairs::Updates::default();
        let columns = if let Some(p) = profile.as_deref().filter(|p| {
            !p.decorations.is_empty()
                || !p.geology.ores.is_empty()
                || p.geology.caves_enabled(p)
                || p.registry_program
                    .as_ref()
                    .is_some_and(|r| r.material_layers)
        }) {
            let (field, mask) = self.terrain_field(request, 1)?;
            let placements = timings.time(timings::VEGETATION_PLAN, || {
                self.plan_decorations(&field, p, request, 1, mask.as_deref())
            })?;
            let columns = field.chunk(request.chunk_x, request.chunk_z);
            let ores = timings.time(timings::ORE_PLAN, || {
                geology::plan(&field, p, request, 1, mask.as_deref())
            });
            timings.time(timings::ASSEMBLY, || {
                decoration::assemble_carved(request, columns, Some(p), mask.as_deref(), blocks);
                plant_updates.base(p, blocks);
            });
            timings.time(timings::GEOLOGY, || {
                geology::apply_ores(request, &field, p, mask.as_deref(), &ores[0], blocks)
            });
            timings.time(timings::CAVE_FEATURES, || {
                plant_updates.extend(features::apply(request, p, mask.as_deref(), blocks))
            });
            timings.time(timings::VEGETATION, || {
                decoration::decorate(Some(p), &placements[0], blocks);
                plant_updates.placements(p, &placements[0]);
            });

            columns.to_vec()
        } else {
            let columns = self.sample_columns(&[request])?;
            timings.time(timings::ASSEMBLY, || {
                decoration::assemble(request, &columns, profile.as_deref(), &[], blocks);
                if let Some(p) = profile.as_deref() {
                    plant_updates.base(p, blocks);
                }
            });
            columns
        };
        if let Some(p) = profile.as_deref() {
            let plans = structures::plans(self, request, 1)?;
            let data = timings.time(timings::STRUCTURES, || {
                structures::apply(request, p, &plans, &columns, Some(blocks))
            });
            plant_updates.extend(data.plant_updates);
            self.structures
                .lock()
                .map_err(|_| "structure cache poisoned")?
                .store_metadata(request, data.tag);
            timings.time(timings::SNOW, || {
                features::snow(request, p, &columns, blocks)
            });
            timings.time(timings::VEGETATION, || plant_updates.finish(p, blocks));
        }
        let result = columns
            .try_into()
            .map_err(|_| "incorrect GPU column count".to_string())?;
        timings.chunks(1);
        Ok(result)
    }
}

impl TerrainEngine {
    pub fn structure_starts(&self, request: ChunkRequest) -> Result<Vec<u8>, String> {
        request.validate()?;
        let plans = structures::plans(self, request, 1)?;
        let root = if let Some(profile) = self.profile(request.reserved)? {
            structures::start_data(request, &profile, &plans)
        } else {
            structures::empty_data()
        };
        Ok(nbt::root(&root))
    }
    pub fn structure_data(&self, request: ChunkRequest) -> Result<Vec<u8>, String> {
        request.validate()?;
        if let Some(data) = self
            .structures
            .lock()
            .map_err(|_| "structure cache poisoned")?
            .metadata(request)
        {
            return Ok(nbt::root(&data));
        }
        let mut blocks = vec![0; request.block_count()];
        self.generate_columns_into(request, &mut blocks)?;
        let data = self
            .structures
            .lock()
            .map_err(|_| "structure cache poisoned")?
            .metadata(request)
            .unwrap_or_else(structures::empty_data);
        Ok(nbt::root(&data))
    }
}

/// # Safety
/// output must point to a writable timing snapshot.
#[unsafe(no_mangle)]
pub unsafe extern "C" fn retina_gpu_pipeline_snapshot(output: *mut pipeline::Snapshot) -> i32 {
    boundary(|| {
        if output.is_null() {
            return Err("null GPU pipeline snapshot".into());
        }
        unsafe {
            *output = shared_engine()?.pipeline_snapshot();
        }
        Ok(())
    })
}
/// # Safety
/// output points to a writable 64-byte version-1 program diagnostic snapshot.
#[unsafe(no_mangle)]
pub unsafe extern "C" fn retina_gpu_program_snapshot(
    profile: u32,
    output: *mut pipeline::ProgramSnapshot,
) -> i32 {
    boundary(|| {
        if output.is_null() {
            return Err("null GPU program snapshot".into());
        }
        unsafe {
            *output = shared_engine()?.pipeline.program_snapshot(profile);
        }
        Ok(())
    })
}
/// # Safety
/// output must point to a writable timing snapshot.
#[unsafe(no_mangle)]
pub unsafe extern "C" fn retina_timing_snapshot(
    profile: u32,
    output: *mut timings::Snapshot,
) -> i32 {
    boundary(|| {
        if output.is_null() {
            return Err("null timing snapshot".into());
        }
        let engine = shared_engine()?;
        engine.profile(profile)?;
        unsafe {
            *output = engine.timings(profile).snapshot();
        }
        Ok(())
    })
}
/// # Safety
/// request is readable; output and length are writable. Free the returned allocation with retina_free_bytes.
#[unsafe(no_mangle)]
pub unsafe extern "C" fn retina_chunk_structure_data(
    request: *const ChunkRequest,
    output: *mut *mut u8,
    length: *mut u64,
) -> i32 {
    boundary(|| {
        if request.is_null() || output.is_null() || length.is_null() {
            return Err("null structure metadata buffer".into());
        }
        let bytes = shared_engine()?
            .structure_data(unsafe { *request })?
            .into_boxed_slice();
        unsafe {
            *length = bytes.len() as u64;
            *output = Box::into_raw(bytes) as *mut u8;
        }
        Ok(())
    })
}
/// # Safety
/// Same ownership contract as retina_chunk_structure_data; returns only starts and references.
#[unsafe(no_mangle)]
pub unsafe extern "C" fn retina_chunk_structure_starts(
    request: *const ChunkRequest,
    output: *mut *mut u8,
    length: *mut u64,
) -> i32 {
    boundary(|| {
        if request.is_null() || output.is_null() || length.is_null() {
            return Err("null structure metadata buffer".into());
        }
        let bytes = shared_engine()?
            .structure_starts(unsafe { *request })?
            .into_boxed_slice();
        unsafe {
            *length = bytes.len() as u64;
            *output = Box::into_raw(bytes) as *mut u8;
        }
        Ok(())
    })
}
/// # Safety
/// data and length must describe an allocation returned by retina_chunk_structure_data, freed exactly once.
#[unsafe(no_mangle)]
pub unsafe extern "C" fn retina_free_bytes(data: *mut u8, length: u64) {
    if !data.is_null() {
        unsafe {
            drop(Box::from_raw(std::ptr::slice_from_raw_parts_mut(
                data,
                length as usize,
            )));
        }
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
struct CaveCache {
    entries: HashMap<CacheKey, Arc<geology::CaveMask>>,
    order: VecDeque<CacheKey>,
}
impl CaveCache {
    fn biome(&self, key: CacheKey, x: i32, y: i32, z: i32) -> Option<u16> {
        self.entries
            .get(&key)
            .and_then(|m| m.biome(x, y, z))
            .or_else(|| {
                // Quart data covers the decoration halo too, although only target
                // chunks have cache keys. Reuse it without generating another mask.
                self.entries.iter().find_map(|(k, m)| {
                    (CacheKey {
                        x: key.x,
                        z: key.z,
                        ..*k
                    } == key)
                        .then(|| m.biome(x, y, z))
                        .flatten()
                })
            })
    }
    fn insert(&mut self, request: ChunkRequest, side: u32, mask: Arc<geology::CaveMask>) {
        for z in 0..side {
            for x in 0..side {
                let key = CacheKey::from(ChunkRequest {
                    chunk_x: request.chunk_x + x as i32,
                    chunk_z: request.chunk_z + z as i32,
                    ..request
                });
                if self.entries.contains_key(&key) {
                    continue;
                }
                self.entries.insert(key, mask.clone());
                self.order.push_back(key);
                if self.entries.len() > 2048 {
                    self.entries.remove(&self.order.pop_front().unwrap());
                }
            }
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

pub fn assemble_stone(request: ChunkRequest, heights: &[i32; COLUMNS], blocks: &mut [u16]) {
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
/// request is readable; points contains count [x,z,recipe,modifier] records and
/// output has count writable i32 elements. Buffers live until this call returns.
#[unsafe(no_mangle)]
pub unsafe extern "C" fn retina_sample_decoration_counts(
    request: *const ChunkRequest,
    points: *const [i32; 4],
    count: u64,
    output: *mut i32,
) -> i32 {
    boundary(|| {
        if request.is_null() || points.is_null() || output.is_null() {
            return Err("null feature count buffer".into());
        }
        let count = usize::try_from(count).map_err(|_| "feature count buffer too large")?;
        if count > isize::MAX as usize / 16 {
            return Err("feature count buffer too large".into());
        }
        let result = shared_engine()?.decoration_counts(unsafe { *request }, unsafe {
            std::slice::from_raw_parts(points, count)
        })?;
        unsafe {
            std::ptr::copy_nonoverlapping(result.as_ptr(), output, count);
        }
        Ok(())
    })
}

/// # Safety
/// request is readable; points contains count [x,y,z,program] records and
/// output has count writable f32 elements. Buffers live until this call returns.
#[unsafe(no_mangle)]
pub unsafe extern "C" fn retina_sample_provider_noise(
    request: *const ChunkRequest,
    points: *const [i32; 4],
    count: u64,
    output: *mut f32,
) -> i32 {
    boundary(|| {
        if request.is_null() || points.is_null() || output.is_null() {
            return Err("null provider noise buffer".into());
        }
        let count = usize::try_from(count).map_err(|_| "provider noise buffer too large")?;
        if count > isize::MAX as usize / 16 {
            return Err("provider noise buffer too large".into());
        }
        let result = shared_engine()?.provider_noise(
            unsafe { *request },
            unsafe { std::slice::from_raw_parts(points, count) },
            None,
        )?;
        unsafe {
            std::ptr::copy_nonoverlapping(result.as_ptr(), output, count);
        }
        Ok(())
    })
}

/// # Safety
/// request/position are readable; output holds capacity [x,y,z,material] records;
/// length is writable. Diagnostic invocation uses the production block-feature logic.
/// Returns 1 with the actual required length when output is too small; no records
/// are copied in that case.
#[unsafe(no_mangle)]
pub unsafe extern "C" fn retina_sample_decoration_feature(
    request: *const ChunkRequest,
    recipe: u32,
    position: *const [i32; 3],
    seed: u64,
    output: *mut [i32; 4],
    capacity: u64,
    length: *mut u64,
) -> i32 {
    let mut too_small = false;
    let status = boundary(|| {
        if request.is_null() || position.is_null() || output.is_null() || length.is_null() {
            return Err("null registered feature buffer".into());
        }
        let request = unsafe { *request };
        request.validate()?;
        let engine = shared_engine()?;
        let profile = engine
            .profile(request.reserved)?
            .ok_or("registered feature requires a profile")?;
        let (field, _) = engine.terrain_field(request, 1)?;
        let mut samples = decoration::provider_noise::Samples::default();
        let result = loop {
            let noise = decoration::provider_noise::Context::new(&samples);
            let blocks = decoration::feature_sample(
                &field,
                &profile,
                request,
                recipe as usize,
                unsafe { *position },
                seed,
                &noise,
            )?;
            let queries = noise.missing();
            if queries.is_empty() {
                break blocks;
            }
            let values = engine.provider_noise(request, &queries, None)?;
            samples.extend(queries.into_iter().zip(values));
        };
        unsafe {
            *length = result.len() as u64;
        }
        if result.len() as u64 > capacity {
            too_small = true;
            return Ok(());
        }
        unsafe {
            std::ptr::copy_nonoverlapping(result.as_ptr(), output, result.len());
        }
        Ok(())
    });
    if status == 0 && too_small { 1 } else { status }
}

/// # Safety
/// request and position are readable; output has capacity [x, y, z, 0] records
/// and length is writable. Returns 1 with the actual required length when the
/// output is too small; no records are copied in that case.
#[unsafe(no_mangle)]
pub unsafe extern "C" fn retina_sample_decoration_placement(
    request: *const ChunkRequest,
    recipe: u32,
    position: *const [i32; 3],
    seed: u64,
    output: *mut [i32; 4],
    capacity: u64,
    length: *mut u64,
) -> i32 {
    let mut too_small = false;
    let status = boundary(|| {
        if request.is_null() || position.is_null() || output.is_null() || length.is_null() {
            return Err("null registered placement buffer".into());
        }
        let request = unsafe { *request };
        request.validate()?;
        let engine = shared_engine()?;
        let profile = engine
            .profile(request.reserved)?
            .ok_or("registered placement requires a profile")?;
        let (field, _) = engine.terrain_field(request, 1)?;
        let result = decoration::placement::sample(
            &field,
            &profile,
            request,
            recipe as usize,
            unsafe { *position },
            seed,
        )?;
        unsafe {
            *length = result.len() as u64;
        }
        if result.len() as u64 > capacity {
            too_small = true;
            return Ok(());
        }
        unsafe {
            std::ptr::copy_nonoverlapping(result.as_ptr(), output, result.len());
        }
        Ok(())
    });
    if status == 0 && too_small { 1 } else { status }
}
/// # Safety
/// request is readable; output has capacity u16 elements, exactly height*4 quart biome IDs.
#[unsafe(no_mangle)]
pub unsafe extern "C" fn retina_sample_biomes_u16(
    request: *const ChunkRequest,
    output: *mut u16,
    capacity: u64,
) -> i32 {
    boundary(|| {
        if request.is_null() || output.is_null() {
            return Err("null biome buffer".into());
        }
        let r = unsafe { *request };
        r.validate()?;
        if r.height % 4 != 0 || r.min_y.rem_euclid(4) != 0 {
            return Err("biome queries require quart-aligned vertical bounds".into());
        }
        if capacity != r.height as u64 * 4 {
            return Err("incorrect biome buffer capacity".into());
        }
        let engine = shared_engine()?;
        let (field, mask) = engine.terrain_field(r, 1)?;
        let out = unsafe { std::slice::from_raw_parts_mut(output, capacity as usize) };
        for (i, value) in out.iter_mut().enumerate() {
            let x = r.chunk_x * 16 + (i % 4) as i32 * 4;
            let z = r.chunk_z * 16 + ((i / 4) % 4) as i32 * 4;
            let y = r.min_y + (i / 16) as i32 * 4;
            *value = mask
                .as_ref()
                .and_then(|m| m.biome(x, y, z))
                .unwrap_or(field.column(x, z).ok_or("missing biome column")?.biome() as u16);
        }
        Ok(())
    })
}

/// # Safety
/// request is readable, blocks has capacity writable u16 elements, columns has 256 records.
#[unsafe(no_mangle)]
pub unsafe extern "C" fn retina_generate_chunk_columns_u16(
    request: *const ChunkRequest,
    blocks: *mut u16,
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
pub unsafe extern "C" fn retina_generate_column_u16(
    request: *const ChunkRequest,
    column: u32,
    output: *mut u16,
) -> i32 {
    boundary(|| {
        if request.is_null() || output.is_null() || column >= COLUMNS as u32 {
            return Err("invalid column buffer".into());
        }
        let request = unsafe { *request };
        let engine = shared_engine()?;
        let profile = engine.profile(request.reserved)?;
        let output = unsafe { std::slice::from_raw_parts_mut(output, request.height as usize) };
        if profile.as_deref().is_some_and(|p| {
            p.registry_program
                .as_ref()
                .is_some_and(|r| r.material_layers)
        }) {
            let (_, mask) = engine.terrain_field(request, 1)?;
            let runs = mask
                .as_ref()
                .and_then(|m| {
                    m.material_runs(
                        request.chunk_x * 16 + (column % 16) as i32,
                        request.chunk_z * 16 + (column / 16) as i32,
                    )
                })
                .ok_or("GPU material column missing")?;
            let mut cursor = runs.len() - 1;
            for (y, block) in output.iter_mut().enumerate() {
                while cursor > 0 && y as u32 >= runs[cursor - 1] >> 16 {
                    cursor -= 1;
                }
                *block = runs[cursor] as u16;
            }
            return Ok(());
        }
        let column = engine.sample_columns(&[request])?[column as usize];
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
pub unsafe extern "C" fn retina_generate_chunk_u16(
    request: *const ChunkRequest,
    blocks: *mut u16,
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

/// # Safety
/// Region buffers follow retina_generate_region. report holds DetailedRegionReport;
/// optional columns is null or points to 1024 * 256 writable Column records.
#[unsafe(no_mangle)]
pub unsafe extern "C" fn retina_generate_region_profiled(
    request: *const ChunkRequest,
    path: *const u8,
    path_len: u64,
    data_version: i32,
    biome: *const u8,
    biome_len: u64,
    report: *mut region::DetailedRegionReport,
    columns: *mut Column,
) -> i32 {
    boundary(|| {
        if request.is_null() || path.is_null() || biome.is_null() || report.is_null() {
            return Err("null profiled region buffer".into());
        }
        let path =
            std::str::from_utf8(unsafe { std::slice::from_raw_parts(path, path_len as usize) })
                .map_err(|e| e.to_string())?;
        let biome =
            std::str::from_utf8(unsafe { std::slice::from_raw_parts(biome, biome_len as usize) })
                .map_err(|e| e.to_string())?;
        let columns = if columns.is_null() {
            None
        } else {
            Some(unsafe { std::slice::from_raw_parts_mut(columns, 1024 * COLUMNS) })
        };
        let result = region::generate_region_profiled(
            shared_engine()?,
            unsafe { *request },
            std::path::Path::new(path),
            data_version,
            biome,
            columns,
        )?;
        unsafe {
            *report = result;
        }
        Ok(())
    })
}

/// # Safety
/// Region arguments follow retina_generate_region; columns points to 1024 * 256 writable records.
#[unsafe(no_mangle)]
pub unsafe extern "C" fn retina_generate_region_columns(
    request: *const ChunkRequest,
    path: *const u8,
    path_len: u64,
    data_version: i32,
    biome: *const u8,
    biome_len: u64,
    report: *mut region::RegionReport,
    columns: *mut Column,
) -> i32 {
    boundary(|| {
        if request.is_null()
            || path.is_null()
            || biome.is_null()
            || report.is_null()
            || columns.is_null()
        {
            return Err("null native region column buffer".into());
        }
        let path =
            std::str::from_utf8(unsafe { std::slice::from_raw_parts(path, path_len as usize) })
                .map_err(|e| e.to_string())?;
        let biome =
            std::str::from_utf8(unsafe { std::slice::from_raw_parts(biome, biome_len as usize) })
                .map_err(|e| e.to_string())?;
        let result = region::generate_region_columns(
            shared_engine()?,
            unsafe { *request },
            std::path::Path::new(path),
            data_version,
            biome,
            unsafe { std::slice::from_raw_parts_mut(columns, 1024 * COLUMNS) },
        )?;
        unsafe {
            *report = result;
        }
        Ok(())
    })
}

/// # Safety
/// request and report point to valid records; both paths point to their stated UTF-8 byte lengths.
#[unsafe(no_mangle)]
pub unsafe extern "C" fn retina_publish_region_cache(
    request: *const ChunkRequest,
    path: *const u8,
    path_len: u64,
    cached: *const u8,
    cached_len: u64,
    report: *mut region::RegionReport,
) -> i32 {
    boundary(|| {
        if request.is_null() || path.is_null() || cached.is_null() || report.is_null() {
            return Err("null cached region buffer".into());
        }
        let path =
            std::str::from_utf8(unsafe { std::slice::from_raw_parts(path, path_len as usize) })
                .map_err(|e| e.to_string())?;
        let cached =
            std::str::from_utf8(unsafe { std::slice::from_raw_parts(cached, cached_len as usize) })
                .map_err(|e| e.to_string())?;
        let result = region::publish_cached_region(
            shared_engine()?,
            unsafe { *request },
            std::path::Path::new(path),
            std::path::Path::new(cached),
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
    #[ignore = "requires RETINA_PROGRAM_PARITY_PROFILE and a fresh RETINA_COMPILE_OUT directory"]
    fn actual_interpreter_pipeline_compile() {
        let path = std::env::var("RETINA_PROGRAM_PARITY_PROFILE").unwrap();
        let out = std::path::PathBuf::from(std::env::var("RETINA_COMPILE_OUT").unwrap());
        std::fs::create_dir(&out).unwrap();
        let mut json: serde_json::Value =
            serde_json::from_slice(&std::fs::read(&path).unwrap()).unwrap();
        json["program_execution"] = serde_json::json!("interpreter");
        let bytes = serde_json::to_vec(&json).unwrap();
        let count: u32 = std::env::var("RETINA_COMPILE_REGIONS").map_or(20, |n| n.parse().unwrap());
        let start = Instant::now();
        let engine = TerrainEngine::new().unwrap();
        let initialize_ms = start.elapsed().as_secs_f64() * 1000.0;
        let start = Instant::now();
        let profile = engine.register_profile(&bytes).unwrap();
        let registration_ms = start.elapsed().as_secs_f64() * 1000.0;
        let start = Instant::now();
        let mut regions = Vec::new();
        for index in 0..count {
            let request = ChunkRequest {
                seed: 123456789,
                chunk_x: (index as i32 % 3 - 1) * 32,
                chunk_z: (index as i32 / 3 - 1) * 32,
                min_y: -64,
                height: 384,
                base_height: 64.0,
                amplitude: 48.0,
                frequency: 0.008,
                reserved: profile,
            };
            let region_start = Instant::now();
            let report = region::generate_region_profiled(
                &engine,
                request,
                &out.join(format!("{index}.mca")),
                0,
                "minecraft:plains",
                None,
            )
            .unwrap();
            assert_eq!(report.region.generated, 1024);
            regions.push(serde_json::json!({"name":index,"ms":region_start.elapsed().as_secs_f64()*1000.0,
                "gpu_ms":report.region.gpu_nanos as f64/1e6,"assembly_ms":report.region.assembly_nanos as f64/1e6,
                "write_ms":report.region.write_nanos as f64/1e6,"bytes":report.region.bytes,
                "stage_nanos":report.stages.nanos.to_vec()}));
        }
        let total_ms = start.elapsed().as_secs_f64() * 1000.0;
        let result = serde_json::json!({"profile":path,"pipeline_tag":std::env::var("RETINA_GENERIC_PIPELINE_TAG").ok(),
            "reuse":std::env::var("RETINA_COMPILE_REUSE").as_deref()!=Ok("0"),
            "shared_dispatch":std::env::var("RETINA_INTERPRETER_DISPATCH").as_deref()==Ok("1"),
            "initialize_ms":initialize_ms,"registration_ms":registration_ms,
            "total_ms":total_ms,"chunks_per_second":count as f64*1024.0*1000.0/total_ms,"regions":regions});
        std::fs::write(
            out.join("measurements.json"),
            serde_json::to_vec_pretty(&result).unwrap(),
        )
        .unwrap();
        println!(
            "QA_EVT {}",
            serde_json::json!({"event":"actual_interpreter_pipeline_compile","status":"pass","context":result})
        );
    }

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
        assert_eq!(std::mem::size_of::<pipeline::ProgramSnapshot>(), 64);
        assert_eq!(
            std::mem::offset_of!(pipeline::ProgramSnapshot, horizontal_fields),
            36
        );
        assert_eq!(
            std::mem::offset_of!(pipeline::ProgramSnapshot, upload_bytes),
            48
        );
        assert_eq!(std::mem::size_of::<ChunkRequest>(), 40);
        assert_eq!(std::mem::offset_of!(ChunkRequest, frequency), 32);
        // GPU-only descriptors include density lattice addressing; the public
        // ChunkRequest ABI above remains unchanged.
        assert_eq!(std::mem::size_of::<GpuRequest>(), 64);
        assert_eq!(std::mem::offset_of!(GpuRequest, density_offset), 48);
        assert_eq!(std::mem::size_of::<timings::Snapshot>(), 264);
        assert_eq!(std::mem::offset_of!(timings::Snapshot, nanos), 32);
        assert_eq!(std::mem::size_of::<region::RegionReport>(), 40);
        assert_eq!(std::mem::offset_of!(region::RegionReport, gpu_nanos), 8);
        assert_eq!(std::mem::size_of::<region::DetailedRegionReport>(), 304);
        assert_eq!(
            std::mem::offset_of!(region::DetailedRegionReport, stages),
            40
        );
    }
}
