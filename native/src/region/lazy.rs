//! Single low-priority worker for MCA replacement. Foreground owners can reopen
//! immediately during staging. Only the final rename excludes new owners.
use super::{
    codec::{Compression, CompressionSettings},
    storage,
};
use std::{
    collections::{BTreeSet, HashMap},
    path::{Path, PathBuf},
    sync::{
        Condvar, LazyLock, Mutex,
        atomic::{AtomicU64, Ordering::Relaxed},
    },
    time::{Duration, Instant},
};

#[derive(Clone, Copy)]
struct Job {
    settings: CompressionSettings,
    preview: bool,
    region: (i32, i32),
    order: u64,
}

#[derive(Default)]
struct Entry {
    pins: usize,
    revision: u64,
    job: Option<Job>,
}

#[derive(Default)]
struct Queue {
    entries: HashMap<PathBuf, Entry>,
    ready: BTreeSet<(bool, u64, PathBuf)>,
    running: Option<PathBuf>,
    committing: Option<PathBuf>,
    next: u64,
    started: bool,
}

static WORK: LazyLock<(Mutex<Queue>, Condvar)> = LazyLock::new(Default::default);
static RAW: AtomicU64 = AtomicU64::new(0);
static ENCODED: AtomicU64 = AtomicU64::new(0);
static LIVE: AtomicU64 = AtomicU64::new(0);
static PREVIEW: AtomicU64 = AtomicU64::new(0);
static SAVED_BYTES: AtomicU64 = AtomicU64::new(0);

#[derive(Clone, Copy, Debug, Default, serde::Serialize)]
pub struct Counts {
    pub raw_chunks: u64,
    pub encoded_chunks: u64,
    pub lazy_live_chunks: u64,
    pub lazy_preview_chunks: u64,
    pub reclaimed_bytes: u64,
}

pub fn counts() -> Counts {
    let live = LIVE.load(Relaxed);
    let preview = PREVIEW.load(Relaxed);
    let converted = live + preview;
    Counts {
        raw_chunks: RAW.load(Relaxed).saturating_sub(converted),
        encoded_chunks: ENCODED.load(Relaxed) + converted,
        lazy_live_chunks: live,
        lazy_preview_chunks: preview,
        reclaimed_bytes: SAVED_BYTES.load(Relaxed),
    }
}

pub(super) fn record_write(raw: u32, encoded: u32) {
    RAW.fetch_add(u64::from(raw), Relaxed);
    ENCODED.fetch_add(u64::from(encoded), Relaxed);
}

fn queue() -> std::sync::MutexGuard<'static, Queue> {
    WORK.0.lock().unwrap_or_else(|error| error.into_inner())
}

fn path_key(path: &Path) -> Result<PathBuf, String> {
    // Resolve save-directory aliases, while keeping the region filename stable
    // across creation, replacement and deletion.
    let absolute = std::path::absolute(path).map_err(|e| e.to_string())?;
    if let (Some(parent), Some(name)) = (absolute.parent(), absolute.file_name()) {
        if let Ok(parent) = parent.canonicalize() {
            return Ok(parent.join(name));
        }
    }
    Ok(absolute)
}

fn ready_key(path: &Path, job: Job) -> (bool, u64, PathBuf) {
    (job.preview, job.order, path.to_owned())
}

fn make_ready(state: &mut Queue, path: &Path) {
    if let Some(entry) = state.entries.get(path) {
        if entry.pins == 0 && state.running.as_deref() != Some(path) {
            if let Some(job) = entry.job {
                state.ready.insert(ready_key(path, job));
                WORK.1.notify_one();
            } else {
                state.entries.remove(path);
            }
        }
    }
}

pub(super) struct Pin(PathBuf);

pub(super) fn pin(path: &Path) -> Result<Pin, String> {
    let path = path_key(path)?;
    pin_key(&path);
    Ok(Pin(path))
}

fn pin_key(path: &Path) {
    let mut state = queue();
    while state.committing.as_deref() == Some(path) {
        state = WORK
            .1
            .wait(state)
            .unwrap_or_else(|error| error.into_inner());
    }
    let entry = state.entries.entry(path.to_owned()).or_default();
    entry.pins += 1;
    entry.revision = entry.revision.wrapping_add(1);
    if let Some(job) = entry.job {
        state.ready.remove(&ready_key(path, job));
        WORK.1.notify_all();
    }
}

fn unpin_key(path: &Path) {
    let mut state = queue();
    if let Some(entry) = state.entries.get_mut(path).filter(|entry| entry.pins > 0) {
        entry.pins -= 1;
        make_ready(&mut state, path);
    }
}

impl Drop for Pin {
    fn drop(&mut self) {
        unpin_key(&self.0);
    }
}

pub fn enqueue(
    path: &Path,
    region: (i32, i32),
    settings: CompressionSettings,
    preview: bool,
) -> Result<(), String> {
    if settings.codec() == Compression::None {
        return Ok(());
    }
    let path = path_key(path)?;
    let mut state = queue();
    if !state.started {
        std::thread::Builder::new()
            .name("retina-lazy-compression".into())
            .spawn(worker)
            .map_err(|error| format!("start lazy compression worker: {error}"))?;
        state.started = true;
    }
    if state
        .entries
        .get(&path)
        .is_some_and(|entry| entry.job.is_some())
    {
        return Ok(());
    }
    let order = state.next;
    state.next += 1;
    state.entries.entry(path.clone()).or_default().job = Some(Job {
        settings,
        preview,
        region,
        order,
    });
    make_ready(&mut state, &path);
    WORK.1.notify_all();
    Ok(())
}

/// Cancel queued work and drain a staging operation before the caller releases
/// the world's session lock or deletes a preview directory.
pub fn cancel(path: &Path, subtree: bool) -> Result<(), String> {
    let path = path_key(path)?;
    let matches = |candidate: &Path| candidate == path || (subtree && candidate.starts_with(&path));
    let mut state = queue();
    if !state.started {
        return Ok(());
    }
    state.ready.retain(|(_, _, key)| !matches(key));
    state.entries.retain(|key, entry| {
        if matches(key) {
            entry.job = None;
            entry.revision = entry.revision.wrapping_add(1);
        }
        entry.job.is_some() || entry.pins != 0
    });
    WORK.1.notify_all();
    while state.running.as_deref().is_some_and(matches) {
        state = WORK
            .1
            .wait(state)
            .unwrap_or_else(|error| error.into_inner());
    }
    Ok(())
}

fn worker() {
    lower_priority();
    loop {
        let (path, job, revision) = {
            let mut state = queue();
            while state.ready.is_empty() {
                state = WORK
                    .1
                    .wait(state)
                    .unwrap_or_else(|error| error.into_inner());
            }
            let (_, _, path) = state.ready.pop_first().unwrap();
            let entry = &state.entries[&path];
            let (job, revision) = (entry.job.unwrap(), entry.revision);
            state.running = Some(path.clone());
            (path, job, revision)
        };
        // Limit both CPU duty cycle and byte rate in addition to kernel
        // priority. Short work slices let a reopened file interrupt staging.
        let mut slice = Instant::now();
        let mut bytes = 0;
        let rewrite = storage::stage_compression(&path, job.region, job.settings, |length| {
            bytes += length;
            let elapsed = slice.elapsed();
            let mut state = queue();
            if elapsed >= Duration::from_millis(2) || bytes >= 512 * 1024 {
                let pause = if job.preview { 3 } else { 1 };
                let delay = (elapsed * pause).max(Duration::from_micros(150));
                (state, _) = WORK
                    .1
                    .wait_timeout_while(state, delay, |state| {
                        still_eligible(state, &path, job, revision)
                    })
                    .unwrap_or_else(|error| error.into_inner());
                bytes = 0;
                slice = Instant::now();
            }
            still_eligible(&state, &path, job, revision)
        });
        let mut state = queue();
        if still_eligible(&state, &path, job, revision) {
            state.entries.get_mut(&path).unwrap().job = None;
            match rewrite {
                Ok(Some(staged)) => {
                    state.committing = Some(path.clone());
                    drop(state);
                    let committed = staged.commit(&path);
                    state = queue();
                    state.committing = None;
                    match committed {
                        Ok((converted, saved)) => {
                            (if job.preview { &PREVIEW } else { &LIVE })
                                .fetch_add(converted, Relaxed);
                            SAVED_BYTES.fetch_add(saved, Relaxed);
                        }
                        Err(error) => eprintln!(
                            "Retina lazy compression skipped {}: {error}",
                            path.display()
                        ),
                    }
                }
                Ok(None) => {}
                Err(error) => eprintln!(
                    "Retina lazy compression skipped {}: {error}",
                    path.display()
                ),
            }
        } else {
            // Drop staging before waking a world or preview closer.
            drop(state);
            drop(rewrite);
            state = queue();
        }
        state.running = None;
        make_ready(&mut state, &path);
        WORK.1.notify_all();
    }
}

fn still_eligible(state: &Queue, path: &Path, job: Job, revision: u64) -> bool {
    state.entries.get(path).is_some_and(|entry| {
        entry.pins == 0
            && entry.revision == revision
            && entry.job.is_some_and(|queued| queued.order == job.order)
    }) && !(job.preview && state.ready.first().is_some_and(|(preview, _, _)| !preview))
}

fn lower_priority() {
    #[cfg(target_os = "macos")]
    unsafe {
        // BACKGROUND QoS also throttles every APFS read. On macOS that can
        // leave the first region unfinished throughout a whole pregen. Use the
        // lowest utility priority and let the explicit duty cycle bound work.
        let _ = libc::pthread_set_qos_class_self_np(libc::qos_class_t::QOS_CLASS_UTILITY, -15);
    }
    #[cfg(target_os = "linux")]
    unsafe {
        let _ = libc::setpriority(libc::PRIO_PROCESS, 0, 19);
        const IOPRIO_WHO_PROCESS: libc::c_int = 1;
        const IOPRIO_CLASS_IDLE: libc::c_int = 3;
        let _ = libc::syscall(
            libc::SYS_ioprio_set,
            IOPRIO_WHO_PROCESS,
            0,
            IOPRIO_CLASS_IDLE << 13,
        );
    }
    #[cfg(windows)]
    unsafe {
        use windows_sys::Win32::System::Threading::*;
        if SetThreadPriority(GetCurrentThread(), THREAD_MODE_BACKGROUND_BEGIN) == 0 {
            let _ = SetThreadPriority(GetCurrentThread(), THREAD_PRIORITY_IDLE);
        }
    }
}

/// Host action codes share the same path lease protocol as native publication.
/// # Safety
/// path must reference length readable UTF-8 bytes.
#[unsafe(no_mangle)]
pub unsafe extern "C" fn retina_lazy_region(
    path: *const u8,
    length: u64,
    action: i32,
    x: i32,
    z: i32,
    codec: i32,
    level: i32,
) -> i32 {
    crate::boundary(|| {
        if path.is_null() {
            return Err("null MCA path".into());
        }
        let length = usize::try_from(length).map_err(|_| "MCA path is too long")?;
        let path = std::str::from_utf8(unsafe { std::slice::from_raw_parts(path, length) })
            .map_err(|e| e.to_string())?;
        let path = Path::new(path);
        match action {
            0 => pin_key(&path_key(path)?),
            1 => unpin_key(&path_key(path)?),
            2 | 3 => {
                let codec = match codec {
                    2 => Compression::Zlib,
                    3 => Compression::None,
                    4 => Compression::Lz4,
                    _ => return Err(format!("unsupported MCA codec {codec}")),
                };
                enqueue(
                    path,
                    (x, z),
                    CompressionSettings::new(codec, level),
                    action == 3,
                )?;
            }
            4 | 5 => cancel(path, action == 5)?,
            _ => return Err(format!("unknown MCA compression action {action}")),
        }
        Ok(())
    })
}

/// # Safety
/// output must hold five writable u64 counters.
#[unsafe(no_mangle)]
pub unsafe extern "C" fn retina_lazy_region_counts(output: *mut u64) {
    let counts = counts();
    let counters = [
        counts.raw_chunks,
        counts.encoded_chunks,
        counts.lazy_live_chunks,
        counts.lazy_preview_chunks,
        counts.reclaimed_bytes,
    ];
    unsafe { std::ptr::copy_nonoverlapping(counters.as_ptr(), output, counters.len()) };
}
