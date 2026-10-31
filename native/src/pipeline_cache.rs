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
        if !device.features().contains(wgpu::Features::PIPELINE_CACHE)
            || std::env::var("RETINA_PIPELINE_CACHE").as_deref() == Ok("0") {
            crate::startup::message(format_args!("Pipeline cache unavailable lane={lane} feature={} disabled={}",
                device.features().contains(wgpu::Features::PIPELINE_CACHE), std::env::var("RETINA_PIPELINE_CACHE").as_deref() == Ok("0")));
            return None;
        }
        let info = device.adapter_info();
        let key = wgpu::util::pipeline_cache_key(&info)?;
        let key = format!("{:x}", Sha256::digest(format!("{key}\n{}\n{}\n{lane}", info.driver, info.driver_info)));
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
        let raw = unsafe { device.create_pipeline_cache(&wgpu::PipelineCacheDescriptor {
            label: Some("Retina persistent driver cache"), data: data.as_deref(), fallback: true,
        }) };
        let writer = match writer::Writer::new(path) {
            Ok(writer) => Some(writer),
            Err(error) => {
                crate::startup::info(format_args!("Pipeline cache writer unavailable: {error}; generation continues"));
                None
            }
        };
        let cache = Arc::new(Self { raw, lane: lane.into(), checkpoint: Mutex::new(Checkpoint::default()), writer });
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
