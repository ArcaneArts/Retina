//! Disposable driver compilation data. World definitions and terrain never live here.
use std::time::Instant;
use sha2::{Digest, Sha256};
use std::{fs, io::{Read, Write}, path::{Path, PathBuf}, sync::{Arc, Mutex, atomic::{AtomicBool, AtomicU64, Ordering}}};

const MAGIC: &[u8; 8] = b"RTPC0001";
const MAX_BYTES: usize = 256 * 1024 * 1024;
static TEMP_ID: AtomicU64 = AtomicU64::new(0);

pub(crate) struct Cache {
    raw: wgpu::PipelineCache,
    path: PathBuf,
    // Each compiler lane owns its own raw cache, so foreground snapshots never
    // contend with a long background driver compilation.
    saving: Mutex<()>,
    warned: AtomicBool,
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
        Some(Arc::new(Self { raw, path, saving: Mutex::new(()), warned: AtomicBool::new(false) }))
    }
    fn save(&self) {
        let _saving = self.saving.lock().unwrap_or_else(|e| e.into_inner());
        let started = Instant::now();
        let Some(data) = self.raw.get_data() else { return; };
        let snapshot = started.elapsed();
        match write(&self.path, &data) {
            Ok(()) => crate::startup::message(format_args!("Pipeline cache saved bytes={} snapshot_ms={:.3} persist_ms={:.3} path={}", data.len(), snapshot.as_secs_f64()*1000.0, (started.elapsed()-snapshot).as_secs_f64()*1000.0, self.path.display())),
            Err(error) if !self.warned.swap(true, Ordering::Relaxed) =>
                crate::startup::info(format_args!("Pipeline cache could not be saved: {error}; generation continues")),
            Err(_) => {},
        }
    }
}

pub(crate) fn create(device: &wgpu::Device, descriptor: &wgpu::ComputePipelineDescriptor<'_>,
                     interpreter: bool, cache: Option<&Cache>) -> wgpu::ComputePipeline {
    let mut descriptor = descriptor.clone();
    if let Some(cache) = cache { descriptor.cache = Some(&cache.raw); }
    let pipeline = if interpreter { crate::startup::interpreter_pipeline(device, &descriptor) }
        else { crate::startup::pipeline(device, &descriptor) };
    // Persist each completed compile, including when the player closes before
    // the background specialization bundle finishes. Never wait until shutdown.
    if let Some(cache) = cache { cache.save(); }
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
