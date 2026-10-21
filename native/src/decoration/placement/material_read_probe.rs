//! Test-only exact-coordinate reuse inventory. Simulated values never feed
//! generation: every read performs the original lookup and checks any cache hit.
use super::*;
use crate::TerrainEngine;
use std::cell::RefCell;
use std::sync::{Mutex, atomic::{AtomicU64, Ordering}};

const CAPACITIES: [usize; 5] = [1, 4, 16, 64, 256];
static NEXT: AtomicU64 = AtomicU64::new(1);
static ACTIVE: AtomicU64 = AtomicU64::new(0);
static STARTED: AtomicU64 = AtomicU64::new(0);
static SUMMARY: Mutex<Option<Summary>> = Mutex::new(None);

pub(super) fn enabled() -> bool { ACTIVE.load(Ordering::Relaxed) != 0 }

#[derive(Clone, Copy, PartialEq, Eq)]
struct Context {
    request: crate::CacheKey,
    pointers: [usize; 5],
    geometry: (i32, i32, usize, usize),
}
impl Context {
    fn new(field: &Field, profile: &WorldProfile, request: ChunkRequest) -> Self {
        Self {
            request: request.into(),
            pointers: [
                field as *const _ as usize, profile as *const _ as usize,
                field.columns.as_ptr() as usize,
                field.substrate.as_ref().map_or(0, |s| Arc::as_ptr(s) as usize),
                field.prior.map_or(0, |p| p as *const dyn PriorWorld as *const () as usize),
            ],
            geometry: (field.origin_x, field.origin_z, field.side, field.columns.len()),
        }
    }
}

#[derive(Default, serde::Serialize)]
struct Counts {
    reads: u64,
    overlay_reads: u64,
    hits: u64,
    overlay_hits: u64,
    hits_from_writes: u64,
    collisions: u64,
    missing: u64,
    mismatches: u64,
    writes: u64,
    write_collisions: u64,
}
impl Counts {
    fn add(&mut self, other: &Self) {
        self.reads += other.reads;
        self.overlay_reads += other.overlay_reads;
        self.hits += other.hits;
        self.overlay_hits += other.overlay_hits;
        self.hits_from_writes += other.hits_from_writes;
        self.collisions += other.collisions;
        self.missing += other.missing;
        self.mismatches += other.mismatches;
        self.writes += other.writes;
        self.write_collisions += other.write_collisions;
    }
}
#[derive(Clone, Copy)]
struct Entry { at: [i32; 3], material: u16, from_write: bool }
struct Simulation {
    slots: Box<[Option<Entry>]>,
    counts: Counts,
}
impl Simulation {
    fn new(capacity: usize) -> Self {
        assert!(capacity.is_power_of_two());
        Self { slots: vec![None; capacity].into_boxed_slice(), counts: Counts::default() }
    }
    fn index(&self, at: [i32; 3]) -> usize {
        // Full xyz is still compared; collisions cannot become hits.
        let h = (at[0] as u32).wrapping_mul(0x9e3779b1)
            ^ (at[1] as u32).wrapping_mul(0x85ebca6b)
            ^ (at[2] as u32).wrapping_mul(0xc2b2ae35);
        (h ^ (h >> 16)) as usize & (self.slots.len() - 1)
    }
    fn read(&mut self, at: [i32; 3], actual: Option<u16>, overlay: bool) -> Option<u16> {
        let index = self.index(at);
        let previous = self.slots[index];
        let c = &mut self.counts;
        c.reads += 1;
        c.overlay_reads += u64::from(overlay);
        c.missing += u64::from(actual.is_none());
        let mut mismatch = None;
        if let Some(entry) = previous {
            if entry.at == at {
                c.hits += 1;
                c.overlay_hits += u64::from(overlay);
                c.hits_from_writes += u64::from(entry.from_write);
                if actual != Some(entry.material) {
                    c.mismatches += 1;
                    mismatch = Some(entry.material);
                }
            } else {
                c.collisions += 1;
            }
        }
        // A missing provider is never remembered as a permanent value.
        self.slots[index] = actual.map(|material| Entry { at, material, from_write: false });
        mismatch
    }
    fn write(&mut self, at: [i32; 3], material: u16) {
        let index = self.index(at);
        self.counts.writes += 1;
        self.counts.write_collisions += u64::from(self.slots[index].is_some_and(|e| e.at != at));
        self.slots[index] = Some(Entry { at, material, from_write: true });
    }
}

struct Data {
    session: u64,
    context: Context,
    resets: u64,
    root: Option<String>,
    simulations: [Simulation; CAPACITIES.len()],
    mismatches: Vec<serde_json::Value>,
}
impl Data {
    fn new(session: u64, context: Context) -> Self {
        Self { session, context, resets: 0, root: None,
            simulations: CAPACITIES.map(Simulation::new), mismatches: Vec::new() }
    }
    fn read(&mut self, context: Context, at: [i32; 3], actual: Option<u16>, overlay: bool) {
        if self.context != context {
            self.resets += 1;
            // Do not attribute mixed-context traces to their initial root.
            self.root = None;
            for s in &mut self.simulations { s.slots.fill(None); }
            self.context = context;
        }
        for (i, s) in self.simulations.iter_mut().enumerate() {
            if let Some(cached) = s.read(at, actual, overlay) {
                if self.mismatches.len() < 8 {
                    self.mismatches.push(serde_json::json!({
                        "capacity":CAPACITIES[i], "at":at, "cached":cached,
                        "actual":actual, "overlay":overlay,
                    }));
                }
            }
        }
    }
}

#[derive(Default)]
pub(super) struct Trace(RefCell<Option<Box<Data>>>);
impl Trace {
    pub(super) fn read(&self, field: &Field, profile: &WorldProfile, request: ChunkRequest,
        at: [i32; 3], actual: Option<u16>, overlay: bool)
    {
        let session = ACTIVE.load(Ordering::Relaxed);
        if session == 0 { return }
        let context = Context::new(field, profile, request);
        let mut data = self.0.borrow_mut();
        if data.as_ref().is_none_or(|d| d.session != session) {
            STARTED.fetch_add(1, Ordering::Relaxed);
            let mut fresh = Data::new(session, context);
            fresh.root = field.prior.and_then(|p| p.material_probe_root())
                .map(|(id, name)| format!("{id}:{name}"));
            *data = Some(Box::new(fresh));
        }
        data.as_mut().unwrap().read(context, at, actual, overlay);
    }
    pub(super) fn write(&mut self, at: [i32; 3], material: u16) {
        if let Some(data) = self.0.get_mut() {
            if data.session == ACTIVE.load(Ordering::Relaxed) {
                for s in &mut data.simulations { s.write(at, material); }
            }
        }
    }
}
impl Drop for Trace {
    fn drop(&mut self) {
        let Some(mut data) = self.0.get_mut().take() else { return };
        let mut summary = SUMMARY.lock().unwrap_or_else(|e| e.into_inner());
        let Some(s) = summary.as_mut().filter(|s| s.session == data.session) else { return };
        s.traces += 1;
        s.context_resets += data.resets;
        for (total, local) in s.counts.iter_mut().zip(&data.simulations) { total.add(&local.counts); }
        let root = s.by_root.entry(data.root.take().unwrap_or_else(|| "unattributed".into())).or_default();
        root.traces += 1;
        for (total, local) in root.counts.iter_mut().zip(&data.simulations) { total.add(&local.counts); }
        for mismatch in data.mismatches {
            if s.mismatch_samples.len() < 16 { s.mismatch_samples.push(mismatch); }
        }
    }
}
#[derive(serde::Serialize)]
struct Summary {
    session: u64,
    traces: u64,
    context_resets: u64,
    capacities: [usize; CAPACITIES.len()],
    counts: [Counts; CAPACITIES.len()],
    by_root: std::collections::BTreeMap<String, RootCounts>,
    mismatch_samples: Vec<serde_json::Value>,
}
#[derive(Default, serde::Serialize)]
struct RootCounts {
    traces: u64,
    counts: [Counts; CAPACITIES.len()],
}
struct Installed;
impl Installed {
    fn start() -> Self {
        assert_eq!(ACTIVE.load(Ordering::Relaxed), 0);
        let session = NEXT.fetch_add(1, Ordering::Relaxed);
        STARTED.store(0, Ordering::Relaxed);
        *SUMMARY.lock().unwrap() = Some(Summary {
            session, traces: 0, context_resets: 0, capacities: CAPACITIES,
            counts: std::array::from_fn(|_| Counts::default()), mismatch_samples: Vec::new(),
            by_root: Default::default(),
        });
        ACTIVE.store(session, Ordering::Relaxed);
        Self
    }
    fn finish(self) -> Summary {
        ACTIVE.store(0, Ordering::Relaxed);
        let summary = SUMMARY.lock().unwrap().take().unwrap();
        assert_eq!(summary.traces, STARTED.load(Ordering::Relaxed), "unflushed material traces");
        summary
    }
}
impl Drop for Installed {
    fn drop(&mut self) {
        ACTIVE.store(0, Ordering::Relaxed);
        *SUMMARY.lock().unwrap_or_else(|e| e.into_inner()) = None;
    }
}

#[test]
fn direct_simulation_observes_writes_collisions_and_missing_values() {
    let mut s = Simulation::new(16);
    let at = [-18, 37, 29];
    assert_eq!(s.read(at, Some(4), false), None);
    assert_eq!(s.read(at, Some(4), false), None);
    assert_eq!(s.counts.hits, 1);
    s.write(at, 7);
    assert_eq!(s.read(at, Some(7), true), None);
    assert_eq!(s.counts.hits_from_writes, 1);
    let collision = (0..1000).map(|x| [x, 37, 29]).find(|p| s.index(*p) == s.index(at)).unwrap();
    s.read(collision, Some(8), false);
    s.read(at, Some(4), false);
    assert_eq!(s.counts.collisions, 2);
    assert_eq!(s.counts.hits, 2);
    assert_eq!(s.read(at, None, false), Some(4));
    s.read(at, Some(5), false);
    assert_eq!(s.counts.hits, 3);
    assert_eq!(s.counts.mismatches, 1);
    assert_eq!(s.counts.missing, 1);
}

#[test]
fn context_changes_reset_values_and_snapshots_start_empty() {
    let (profile, field, request) = crate::decoration::replay_tests::fixture(42, 0);
    let context = Context::new(&field, &profile, request);
    let at = [8, 31, 8];
    let mut data = Data::new(0, context);
    data.root = Some("original root".into());
    data.read(context, at, Some(1), false);
    let changed = Context::new(&field, &profile, ChunkRequest { seed: 99, ..request });
    data.read(changed, at, Some(2), false);
    assert_eq!(data.resets, 1);
    assert!(data.root.is_none());
    assert!(data.simulations.iter().all(|s| s.counts.hits == 0 && s.counts.mismatches == 0));
    let mut overlay = Overlay::default();
    *overlay.material_trace.0.get_mut() = Some(Box::new(data));
    overlay.record_feature();
    overlay.write(&profile, at, 3);
    overlay.write(&profile, at, 0);
    assert!(overlay.snapshot().material_trace.0.borrow().is_none());
    let before = overlay.before_feature();
    assert!(before.material_trace.0.borrow().is_none());
    assert!(!before.blocks.contains_key(&at));
}

#[test]
#[ignore = "GPU inventory requires RETINA_TERRAIN_REUSE_PROFILES and RETINA_MATERIAL_READ_OUTPUT"]
fn exact_material_read_inventory() {
    use std::{fs, path::PathBuf, time::Instant};
    let profiles = PathBuf::from(std::env::var("RETINA_TERRAIN_REUSE_PROFILES").unwrap());
    let output = PathBuf::from(std::env::var("RETINA_MATERIAL_READ_OUTPUT").unwrap());
    fs::create_dir(&output).unwrap();
    let mut cases = Vec::new();
    for (name, file, seed) in [
        ("nether-main", "nether.json", 123456789),
        ("nether-second-seed", "nether.json", 42),
        ("nether-custom-bounds", "custom.json", 123456789),
        ("nether-direct-stem", "direct-stem.json", 42),
        ("overworld", "overworld.json", 123456789),
    ] {
        let bytes = fs::read(profiles.join(file)).unwrap();
        let profile: serde_json::Value = serde_json::from_slice(&bytes).unwrap();
        let engine = TerrainEngine::new().unwrap();
        let id = engine.register_profile(&bytes).unwrap();
        let bounds = &profile["generation_bounds"];
        let request = ChunkRequest {
            seed, chunk_x: 0, chunk_z: 0,
            min_y: bounds["storage_min_y"].as_i64().unwrap_or(-64) as i32,
            height: bounds["storage_height"].as_u64().unwrap_or(384) as u32,
            base_height: 64., amplitude: 48., frequency: 0.008, reserved: id,
        };
        let biome = profile["biomes"][0]["id"].as_str().unwrap();
        let directory = output.join(format!("{name}-run0"));
        fs::create_dir(&directory).unwrap();
        let generate = |name: &str, x: i32, z: i32| {
            let before = engine.pipeline.program_snapshot(id);
            let start = Instant::now();
            let report = crate::region::generate_region(
                &engine, ChunkRequest { chunk_x: x * 32, chunk_z: z * 32, ..request },
                &directory.join(format!("{name}.mca")), 0, biome,
            ).unwrap();
            assert_eq!(report.generated, 1024);
            assert_eq!(report.preserved, 0);
            serde_json::json!({"name":name,"x":x,"z":z,
                "instrumented_ms":start.elapsed().as_secs_f64()*1000.,
                "program_before":before,"program_after":engine.pipeline.program_snapshot(id)})
        };
        let warmups: Vec<_> = (0..5).map(|i| generate(&format!("warm{i}"), 8 + i, 8)).collect();
        let installed = Installed::start();
        let regions: Vec<_> = (0..20).map(|i| generate(&i.to_string(), -2 + i % 5, -2 + i / 5)).collect();
        drop(engine);
        let summary = installed.finish();
        println!("material inventory {name}: reads={} hits={:?} mismatches={:?}",
            summary.counts[0].reads, summary.counts.each_ref().map(|c| c.hits),
            summary.counts.each_ref().map(|c| c.mismatches));
        let mismatches: u64 = summary.counts.iter().map(|c| c.mismatches).sum();
        cases.push(serde_json::json!({"case":name,"seed":seed,"profile":file,
            "warmups":warmups,"regions":regions,"summary":summary}));
        fs::write(output.join("report.json"), serde_json::to_vec_pretty(&serde_json::json!({
            "scope":"Original Overlay::material results always drive generation. Five simulated direct-indexed xyz caches compare hits to original results. Excludes the ordered-generation vertical-bounds early return; writes seed simulations after the first read establishes context. Snapshot and undo start cold. Per-root attribution identifies the stable View's root, labeled by its first recipe; providers without that metadata and mixed-context traces remain unattributed. Pointer identity is an observation key, not a production immutability contract. Five uninstrumented warmups and twenty instrumented complete lit regions per case. Observer time/memory are not production performance.",
            "cases":cases,"timing_acceptance":false,
        })).unwrap()).unwrap();
        assert_eq!(mismatches, 0, "simulated material reuse disagreed with actual reads");
    }
}
