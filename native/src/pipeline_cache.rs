//! Disposable driver compilation data. World definitions and terrain never live here.
mod writer;
use std::time::{Duration, Instant};
use sha2::{Digest, Sha256};
use std::{fs, io::{Read, Write}, path::{Path, PathBuf}, sync::{Arc, LazyLock, Mutex, Weak, atomic::{AtomicU64, Ordering}}};

const MAGIC: &[u8; 8] = b"RTPC0001";
const MAX_BYTES: usize = 256 * 1024 * 1024;
static TEMP_ID: AtomicU64 = AtomicU64::new(0);

static CACHES: LazyLock<Mutex<Vec<Weak<Cache>>>> = LazyLock::new(Default::default);
#[derive(Default)]
struct Checkpoint { dirty: u32, last: Option<Instant> }
impl Checkpoint {
    fn due(&self, now: Instant) -> bool {
        self.dirty > 0 && (self.last.is_none() || self.dirty >= 8
            || now.duration_since(self.last.unwrap()) >= Duration::from_secs(2))
    }
    fn captured(&mut self, now: Instant) { self.dirty = 0; self.last = Some(now); }
}
pub(crate) struct Cache {
    raw: wgpu::PipelineCache,
    lane: String,
    // Serialize actual creation and snapshots within this lane only. The worker
    // never accesses driver objects, and disk I/O never holds this mutex.
    checkpoint: Mutex<Checkpoint>,
    writer: Option<writer::Writer>,
}
impl Cache {
    pub fn open(device: &wgpu::Device, lane: &str) -> Option<Arc<Self>> {
        Self::open_variant(device, lane, "")
    }
    /// Each compiler worker/policy owns its cache and snapshot mutex. Empty
    /// variant preserves existing filenames; explicit variants cannot alias it.
    pub fn open_variant(device: &wgpu::Device, lane: &str, variant: &str) -> Option<Arc<Self>> {
        if !device.features().contains(wgpu::Features::PIPELINE_CACHE)
            || std::env::var("RETINA_PIPELINE_CACHE").as_deref() == Ok("0") {
            crate::startup::message(format_args!("Pipeline cache unavailable lane={lane} feature={} disabled={}",
                device.features().contains(wgpu::Features::PIPELINE_CACHE), std::env::var("RETINA_PIPELINE_CACHE").as_deref() == Ok("0")));
            return None;
        }
        let info = device.adapter_info();
        let key = cache_key(&info, lane, variant)?;
        let root = std::env::var_os("RETINA_PIPELINE_CACHE_DIR").map(PathBuf::from)
            .unwrap_or_else(|| PathBuf::from("retina-cache/pipelines"));
        let path = root.join(format!("{key}.bin"));
        let data = match read(&path) {
            Ok(data) => {
                crate::startup::message(format_args!("Pipeline cache loaded lane={lane} bytes={} path={}", data.len(), path.display()));
                Some(data)
            }
            Err(error) if error.kind() == std::io::ErrorKind::NotFound => {
                crate::startup::message(format_args!("Pipeline cache cold lane={lane} path={}", path.display()));
                None
            }
            Err(error) => {
                crate::startup::info(format_args!("Pipeline cache ignored: {error}; compiling normally"));
                None
            }
        };
        // SAFETY: payloads originate only from PipelineCache::get_data below.
        // The envelope detects interrupted/corrupt files; wgpu validates device,
        // driver and format compatibility, falling back to an empty cache.
        let raw = load(device, data.as_deref());
        let writer = match writer::Writer::new(path) {
            Ok(writer) => Some(writer),
            Err(error) => {
                crate::startup::info(format_args!("Pipeline cache writer unavailable: {error}; generation continues"));
                None
            }
        };
        let lane = if variant.is_empty() { lane.into() } else { format!("{lane}/{variant}") };
        let cache = Arc::new(Self { raw, lane, checkpoint: Mutex::new(Checkpoint::default()), writer });
        let mut caches = CACHES.lock().unwrap_or_else(|e| e.into_inner());
        caches.retain(|cache| cache.strong_count() > 0);
        caches.push(Arc::downgrade(&cache));
        Some(cache)
    }
    fn snapshot(&self, state: &mut Checkpoint, reason: &str) {
        let Some(writer) = &self.writer else { return; };
        let retry = writer.failed() && state.last.is_none_or(|last| last.elapsed() >= Duration::from_secs(2));
        if state.dirty == 0 && !retry { return; }
        let started = Instant::now();
        let Some(data) = self.raw.get_data() else {
            state.captured(Instant::now());
            return;
        };
        let bytes = data.len();
        if bytes > MAX_BYTES {
            crate::startup::message("Pipeline cache snapshot exceeds persistence limit; generation continues");
            state.captured(Instant::now());
            return;
        }
        writer.enqueue(data);
        state.captured(Instant::now());
        crate::startup::message(format_args!("Pipeline cache checkpoint lane={} reason={reason} bytes={bytes} snapshot_enqueue_ms={:.3}", self.lane, started.elapsed().as_secs_f64()*1000.0));
    }
    pub fn checkpoint(&self) {
        self.snapshot(&mut self.checkpoint.lock().unwrap_or_else(|e| e.into_inner()), "milestone");
    }
}

fn cache_key(info: &wgpu::AdapterInfo, lane: &str, variant: &str) -> Option<String> {
    let key = wgpu::util::pipeline_cache_key(info)?;
    let legacy = format!("{key}\n{}\n{}\n{lane}", info.driver, info.driver_info);
    let identity = if variant.is_empty() { legacy } else { format!("{legacy}\nvariant={variant}") };
    Some(format!("{:x}", Sha256::digest(identity)))
}

fn load(device: &wgpu::Device, data: Option<&[u8]>) -> wgpu::PipelineCache {
    let scope = device.push_error_scope(wgpu::ErrorFilter::Validation);
    // SAFETY: stored data originated from get_data; wgpu validates its header
    // before exposing any driver data. Retina also checks the whole envelope.
    let raw = unsafe { device.create_pipeline_cache(&wgpu::PipelineCacheDescriptor {
        label: Some("Retina persistent driver cache"), data, fallback: true,
    }) };
    if let Some(error) = pollster::block_on(scope.pop()) {
        // wgpu's fallback=true deliberately does not recover DeviceMismatch.
        // A misplaced disposable file must not fail actual terrain generation.
        crate::startup::info(format_args!("Pipeline cache rejected: {error}; creating an empty cache"));
        unsafe { device.create_pipeline_cache(&wgpu::PipelineCacheDescriptor {
            label: Some("Retina fresh driver cache"), data: None, fallback: true,
        }) }
    } else { raw }
}
impl Drop for Cache {
    fn drop(&mut self) {
        self.checkpoint();
        // Writer::drop drains the latest pending snapshot and joins its one thread.
    }
}

/// Flush completed compilation data without waiting for a driver compile which
/// may still be running. That lane's last checkpoint is already in its writer.
pub(crate) fn flush_all() {
    let caches: Vec<_> = CACHES.lock().unwrap_or_else(|e| e.into_inner())
        .iter().filter_map(Weak::upgrade).collect();
    for cache in caches {
        match cache.checkpoint.try_lock() {
            Ok(mut state) => cache.snapshot(&mut state, "shutdown"),
            Err(std::sync::TryLockError::Poisoned(error)) => cache.snapshot(&mut error.into_inner(), "shutdown"),
            Err(std::sync::TryLockError::WouldBlock) => crate::startup::message("Pipeline cache shutdown: compiler active; flushing previous completed checkpoint"),
        }
        if let Some(writer) = &cache.writer { writer.flush(); }
    }
}

pub(crate) fn create(device: &wgpu::Device, descriptor: &wgpu::ComputePipelineDescriptor<'_>,
                     interpreter: bool, cache: Option<&Cache>) -> wgpu::ComputePipeline {
    let mut descriptor = descriptor.clone();
    if let Some(cache) = cache { descriptor.cache = Some(&cache.raw); }
    let mut state = cache.map(|cache| cache.checkpoint.lock().unwrap_or_else(|e| e.into_inner()));
    let pipeline = if interpreter { crate::startup::interpreter_pipeline(device, &descriptor) }
        else { crate::startup::pipeline(device, &descriptor) };
    if let (Some(cache), Some(state)) = (cache, state.as_mut()) {
        state.dirty = state.dirty.saturating_add(1);
        if state.due(Instant::now()) { cache.snapshot(state, "progress"); }
    }
    pipeline
}

fn read(path: &Path) -> std::io::Result<Vec<u8>> {
    let mut bytes = Vec::new();
    fs::File::open(path)?.take((MAX_BYTES + 49) as u64).read_to_end(&mut bytes)?;
    decode(&bytes).ok_or_else(|| std::io::Error::new(std::io::ErrorKind::InvalidData, "invalid pipeline cache envelope"))
}
fn decode(bytes: &[u8]) -> Option<Vec<u8>> {
    if bytes.len() < 48 || bytes.len() > MAX_BYTES + 48 || &bytes[..8] != MAGIC { return None; }
    let len = u64::from_le_bytes(bytes[8..16].try_into().ok()?);
    let data = &bytes[48..];
    if len != data.len() as u64 || Sha256::digest(data)[..] != bytes[16..48] { return None; }
    Some(data.to_vec())
}
fn write(path: &Path, data: &[u8]) -> std::io::Result<()> {
    if data.len() > MAX_BYTES { return Err(std::io::Error::other("pipeline cache exceeds 256 MiB")); }
    let parent = path.parent().ok_or_else(|| std::io::Error::other("cache has no parent"))?;
    fs::create_dir_all(parent)?;
    let temporary = path.with_extension(format!("{}-{}.tmp", std::process::id(), TEMP_ID.fetch_add(1, Ordering::Relaxed)));
    let result = (|| {
        let mut file = fs::OpenOptions::new().write(true).create_new(true).open(&temporary)?;
        file.write_all(MAGIC)?;
        file.write_all(&(data.len() as u64).to_le_bytes())?;
        file.write_all(&Sha256::digest(data))?;
        file.write_all(data)?;
        file.sync_all()?;
        drop(file);
        fs::rename(&temporary, path)
    })();
    if result.is_err() { let _ = fs::remove_file(&temporary); }
    result
}

#[cfg(test)]
mod tests {
    use super::*;
    #[test]
    fn cache_round_trip_replaces_atomically_and_rejects_corruption() {
        let root = std::env::temp_dir().join(format!("retina-cache-{}-{}", std::process::id(), TEMP_ID.fetch_add(1, Ordering::Relaxed)));
        let path = root.join("cache.bin");
        write(&path, b"first driver payload").unwrap();
        assert_eq!(read(&path).unwrap(), b"first driver payload");
        write(&path, b"second driver payload").unwrap();
        let bytes = fs::read(&path).unwrap();
        assert_eq!(decode(&bytes).unwrap(), b"second driver payload");
        for n in 0..bytes.len() { assert!(decode(&bytes[..n]).is_none()); }
        let mut corrupt = bytes.clone(); corrupt[48] ^= 1;
        assert!(decode(&corrupt).is_none());
        corrupt = bytes; corrupt.push(0); assert!(decode(&corrupt).is_none());
        assert_eq!(fs::read_dir(&root).unwrap().count(), 1);
        fs::remove_dir_all(root).unwrap();
    }
    #[test]
    fn worker_policy_and_driver_identities_are_distinct() {
        let mut info = wgpu::AdapterInfo { name: "test".into(), vendor: 0x10de, device: 0x2504,
            device_type: wgpu::DeviceType::DiscreteGpu, driver: "NVIDIA".into(), driver_info: "621.01".into(),
            ..wgpu::AdapterInfo::new(wgpu::DeviceType::DiscreteGpu, wgpu::Backend::Vulkan) };
        let baseline = cache_key(&info, "compiler", "").unwrap();
        assert_ne!(baseline, cache_key(&info, "compiler", "fast").unwrap());
        assert_ne!(baseline, cache_key(&info, "compiler/world", "").unwrap());
        info.driver_info = "next driver".into();
        assert_ne!(baseline, cache_key(&info, "compiler", "").unwrap());
    }

    #[test]
    fn interrupted_process_retains_completed_checkpoint() {
        let root = std::env::temp_dir().join(format!("retina-cache-exit-{}", std::process::id()));
        std::fs::create_dir_all(&root).unwrap();
        let status = std::process::Command::new(std::env::current_exe().unwrap())
            .args(["pipeline_cache::tests::checkpoint_child", "--exact", "--ignored"])
            .env("RETINA_CACHE_TEST_CHILD", &root).status().unwrap();
        assert_eq!(status.code(), Some(17));
        assert_eq!(read(&root.join("cache.bin")).unwrap(), b"completed checkpoint");
        assert!(read(&root.join("interrupted.tmp")).is_err());
        std::fs::remove_dir_all(root).unwrap();
    }

    #[test]
    #[ignore = "child process of interrupted_process_retains_completed_checkpoint"]
    fn checkpoint_child() {
        let root = PathBuf::from(std::env::var_os("RETINA_CACHE_TEST_CHILD").unwrap());
        let writer = writer::Writer::new(root.join("cache.bin")).unwrap();
        writer.enqueue(b"completed checkpoint".to_vec());
        writer.flush();
        std::fs::write(root.join("interrupted.tmp"), b"RTPC0001partial").unwrap();
        std::process::exit(17); // No Rust Drop or shutdown hook runs.
    }

    #[test]
    #[ignore = "requires explicitly selected Vulkan, including optional Mac portability"]
    fn vulkan_cache_rejects_incompatible_data_and_keeps_compiling() {
        assert_eq!(std::env::var("RETINA_GPU_BACKEND").as_deref(), Ok("vulkan"));
        let instance = wgpu::Instance::new(crate::gpu_backend::instance_descriptor(wgpu::Backends::VULKAN));
        let adapter = pollster::block_on(instance.request_adapter(&Default::default())).unwrap();
        let (device, _) = pollster::block_on(adapter.request_device(&wgpu::DeviceDescriptor {
            required_features: wgpu::Features::PIPELINE_CACHE, ..Default::default()
        })).unwrap();
        let cache = load(&device, None);
        let data = cache.get_data().unwrap();
        let _reloaded = load(&device, Some(&data));
        // wgpu 30's serialized header: 8 magic + 4 version + 4 ABI + 1 backend,
        // followed by adapter key. Only its header is altered; driver data is intact.
        let mut other_adapter = data.clone(); other_adapter[17] ^= 1;
        let recovered = load(&device, Some(&other_adapter));
        let module = device.create_shader_module(wgpu::ShaderModuleDescriptor {
            label: None, source: wgpu::ShaderSource::Wgsl("@compute @workgroup_size(1) fn main() {}".into()),
        });
        let first = device.create_compute_pipeline(&wgpu::ComputePipelineDescriptor {
            label: None, layout: None, module: &module, entry_point: Some("main"),
            compilation_options: Default::default(), cache: Some(&recovered),
        });
        let second = device.create_compute_pipeline(&wgpu::ComputePipelineDescriptor {
            label: None, layout: None, module: &module, entry_point: Some("main"),
            compilation_options: Default::default(), cache: Some(&recovered),
        });
        drop((first, second));
        assert!(recovered.get_data().unwrap().len() >= data.len());
    }
}

#[cfg(test)]
mod checkpoint_tests {
    use super::*;
    #[test]
    fn checkpoints_preserve_first_progress_slow_compiles_and_bundle_tail() {
        let now = Instant::now(); let mut state = Checkpoint::default(); let mut captures = 0;
        for _ in 0..64 {
            state.dirty += 1;
            if state.due(now) { captures += 1; state.captured(now); }
        }
        assert_eq!(captures, 8); assert_eq!(state.dirty, 7);
        state.captured(now); assert!(!state.due(now + Duration::from_secs(10)));
        state.dirty += 1;
        assert!(!state.due(now + Duration::from_millis(1999)));
        assert!(state.due(now + Duration::from_secs(2)));
    }
    #[test]
    #[ignore = "filesystem persistence benchmark; run explicitly"]
    fn persistence_foreground_benchmark() {
        let root = std::env::temp_dir().join(format!("retina-cache-benchmark-{}", std::process::id()));
        fs::create_dir_all(&root).unwrap();
        for bytes in [4096, 1024*1024, 8*1024*1024] {
            let mut data = vec![17u8; bytes];
            let path = root.join(format!("sync-{bytes}"));
            let old = Instant::now();
            for generation in 0..64 { data[0] = generation; let snapshot=data.clone(); write(&path, &snapshot).unwrap(); }
            let old = old.elapsed();
            let writer = writer::Writer::new(root.join(format!("coalesced-{bytes}"))).unwrap();
            let mut state = Checkpoint::default(); let mut snapshots = 0;
            let now = Instant::now(); let foreground = Instant::now();
            for generation in 0..64 {
                data[0] = generation; state.dirty += 1;
                if state.due(now) { writer.enqueue(data.clone()); snapshots += 1; state.captured(now); }
            }
            if state.dirty > 0 { writer.enqueue(data.clone()); snapshots += 1; }
            let foreground = foreground.elapsed();
            writer.flush();
            assert_eq!(read(&root.join(format!("coalesced-{bytes}"))).unwrap(), data);
            println!("CACHE_BENCH bytes={bytes} updates=64 old_snapshots=64 snapshots={snapshots} old_foreground_ms={:.3} foreground_ms={:.3}", old.as_secs_f64()*1000., foreground.as_secs_f64()*1000.);
        }
        fs::remove_dir_all(root).unwrap();
    }
}
