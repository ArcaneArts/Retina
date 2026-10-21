//! Test-only allocation shape and historical lookup evidence. The original
//! partition search, cells, output and synchronous destruction remain in use.
//! Observer counters and scans are absent from ordinary native libraries.
use super::*;
use serde_json::{Value, json};
use std::mem::size_of;
use std::time::Instant;

const OVERFLOW: usize = 64;
static ACTIVE: Mutex<Option<Arc<Session>>> = Mutex::new(None);

#[derive(Default)]
struct Session {
    started: AtomicU64,
    regions: Mutex<Vec<Value>>,
    exports: Mutex<Vec<Value>>,
}
struct Installed(Arc<Session>);
impl Installed {
    fn start() -> Self {
        let mut active = ACTIVE.lock().unwrap();
        assert!(active.is_none());
        let session = Arc::new(Session::default());
        *active = Some(session.clone());
        Self(session)
    }
    fn report(&self) -> Value {
        let regions = self.0.regions.lock().unwrap();
        assert_eq!(self.0.started.load(Ordering::Relaxed), regions.len() as u64);
        json!({"started":regions.len(), "regions":*regions,
            "exports":*self.0.exports.lock().unwrap()})
    }
}
impl Drop for Installed {
    fn drop(&mut self) {
        *ACTIVE.lock().unwrap_or_else(|e| e.into_inner()) = None;
    }
}

struct Counts {
    length: [AtomicU64; OVERFLOW + 1],
    newer: [AtomicU64; OVERFLOW + 1],
    max_length: AtomicU64,
    max_newer: AtomicU64,
    total_newer: AtomicU64,
    base_queries: AtomicU64,
    appends: u64,
    initial_allocations: u64,
    growth_requests: u64,
    live_bytes_at_growth: u64,
}
impl Default for Counts {
    fn default() -> Self {
        Self {
            length: std::array::from_fn(|_| AtomicU64::new(0)),
            newer: std::array::from_fn(|_| AtomicU64::new(0)),
            max_length: AtomicU64::new(0),
            max_newer: AtomicU64::new(0),
            total_newer: AtomicU64::new(0),
            base_queries: AtomicU64::new(0),
            appends: 0,
            initial_allocations: 0,
            growth_requests: 0,
            live_bytes_at_growth: 0,
        }
    }
}
#[derive(Default)]
pub(super) struct Trace(Option<(Arc<Session>, Box<Counts>)>);
impl Trace {
    pub(super) fn new() -> Self {
        Self(ACTIVE.lock().unwrap().as_ref().map(|session| {
            session.started.fetch_add(1, Ordering::Relaxed);
            (session.clone(), Box::new(Counts::default()))
        }))
    }
    pub(super) fn query(&self, len: usize, count: usize) {
        let Some((_, counts)) = &self.0 else { return };
        let newer = len - count;
        counts.length[len.min(OVERFLOW)].fetch_add(1, Ordering::Relaxed);
        counts.newer[newer.min(OVERFLOW)].fetch_add(1, Ordering::Relaxed);
        counts.max_length.fetch_max(len as u64, Ordering::Relaxed);
        counts.max_newer.fetch_max(newer as u64, Ordering::Relaxed);
        counts
            .total_newer
            .fetch_add(newer as u64, Ordering::Relaxed);
        if count == 0 {
            counts.base_queries.fetch_add(1, Ordering::Relaxed);
        }
    }
    pub(super) fn append(&mut self, versions: &Vec<ColumnVersion>) {
        let Some((_, counts)) = &mut self.0 else {
            return;
        };
        counts.appends += 1;
        if versions.len() == versions.capacity() {
            if versions.is_empty() {
                counts.initial_allocations += 1;
            } else {
                counts.growth_requests += 1;
            }
            // The allocator may grow in place. This is existing live storage
            // presented to a growth operation, never a claim of bytes copied.
            counts.live_bytes_at_growth += (versions.len() * size_of::<ColumnVersion>()) as u64;
        }
    }
    fn snapshot(&self, versions: &Versions) -> Option<Value> {
        let (_, counts) = self.0.as_ref()?;
        let mut lengths = BTreeMap::<usize, usize>::new();
        let mut capacities = BTreeMap::<usize, usize>::new();
        let mut markers = 0;
        let mut capacity = 0;
        let mut allocated = 0;
        let mut states = [0usize; 3];
        let mut bases = [0usize; 3];
        let mut by_root = BTreeMap::<usize, usize>::new();
        let state = |cell: &ColumnCell| match cell.get() {
            None => 0,
            Some(Ok(_)) => 1,
            Some(Err(_)) => 2,
        };
        for column in &versions.changed {
            *lengths.entry(column.len()).or_default() += 1;
            *capacities.entry(column.capacity()).or_default() += 1;
            markers += column.len();
            capacity += column.capacity();
            allocated += usize::from(column.capacity() != 0);
            for version in column {
                states[state(&version.state)] += 1;
                *by_root.entry(version.root).or_default() += 1;
            }
        }
        for cell in &versions.base {
            bases[state(cell)] += 1;
        }
        let read = |v: &AtomicU64| v.load(Ordering::Relaxed);
        Some(json!({"origin":versions.origin,"width":versions.width,
            "columns":versions.changed.len(),"allocated_column_vectors":allocated,
            "markers":markers,"marker_capacity":capacity,"unused_marker_slots":capacity-markers,
            "marker_bytes":capacity*size_of::<ColumnVersion>(),
            "unused_marker_bytes":(capacity-markers)*size_of::<ColumnVersion>(),
            "outer_vector_bytes":versions.changed.capacity()*size_of::<Vec<ColumnVersion>>(),
            "base_cell_bytes":versions.base.capacity()*size_of::<ColumnCell>(),
            "column_cell_size":size_of::<ColumnCell>(),"column_version_size":size_of::<ColumnVersion>(),
            "length_histogram":lengths,"capacity_histogram":capacities,"markers_by_root":by_root,
            "marker_cells_missing_ok_error":states,"base_cells_missing_ok_error":bases,
            "append_count":counts.appends,"initial_allocations":counts.initial_allocations,
            "growth_requests":counts.growth_requests,"live_bytes_at_growth":counts.live_bytes_at_growth,
            "query_length_histogram":counts.length.iter().map(read).collect::<Vec<_>>(),
            "query_newer_histogram":counts.newer.iter().map(read).collect::<Vec<_>>(),
            "query_histogram_overflow_bucket":OVERFLOW,
            "max_query_length":read(&counts.max_length),"max_query_newer":read(&counts.max_newer),
            "total_newer_markers_skipped":read(&counts.total_newer),"base_queries":read(&counts.base_queries)}))
    }
    pub(super) fn observe(&self, versions: &Versions) {
        if let Some(report) = self.snapshot(versions) {
            self.0
                .as_ref()
                .unwrap()
                .0
                .regions
                .lock()
                .unwrap()
                .push(report);
        }
    }
}

/// Four clock reads around the existing export phases, without changing drop
/// order. Retirement includes ColumnState/Arc destruction, not just Vec headers.
pub(super) struct Export(Option<ExportData>);
struct ExportData {
    session: Arc<Session>,
    origin: [i32; 2],
    start: Instant,
    interior_ns: u128,
    exterior_ns: u128,
}
impl Export {
    pub(super) fn start(versions: &Versions) -> Self {
        Self(
            versions
                .layout_trace
                .0
                .as_ref()
                .map(|(session, _)| ExportData {
                    session: session.clone(),
                    origin: versions.origin,
                    start: Instant::now(),
                    interior_ns: 0,
                    exterior_ns: 0,
                }),
        )
    }
    pub(super) fn interior(&mut self) {
        if let Some(data) = &mut self.0 {
            data.interior_ns = data.start.elapsed().as_nanos();
        }
    }
    pub(super) fn exterior(&mut self) {
        if let Some(data) = &mut self.0 {
            data.exterior_ns = data.start.elapsed().as_nanos();
        }
    }
    pub(super) fn finish(self) {
        if let Some(data) = self.0 {
            let final_ns = data.start.elapsed().as_nanos();
            data.session
                .exports
                .lock()
                .unwrap()
                .push(json!({"origin":data.origin,
                "interior_capture_ns":data.interior_ns,
                "exterior_capture_ns":data.exterior_ns-data.interior_ns,
                "original_versions_retirement_ns":final_ns-data.exterior_ns}));
        }
    }
}

#[test]
fn version_layout_probe_preserves_strict_prefix_and_missing_latest_cell() {
    let (_, _, request) = crate::decoration::replay_tests::fixture(42, 0);
    let mut versions = Versions::new(request, 1);
    let session = Arc::new(Session::default());
    versions.layout_trace = Trace(Some((session, Box::default())));
    let (profile, _, _) = crate::decoration::replay_tests::fixture(42, 0);
    let state = Arc::new(ColumnState::new(vec![0; 16], &profile, 0));
    versions.base[0].set(Ok(state.clone())).ok().unwrap();
    for root in [0, 3, 8] {
        versions.layout_trace.append(&versions.changed[0]);
        let cell = ColumnCell::new();
        if root == 0 {
            cell.set(Ok(state.clone())).ok().unwrap();
        }
        versions.changed[0].push(ColumnVersion { root, state: cell });
    }
    for before in [0, 1, 3, 4, 8, 9] {
        let expected = versions.changed[0].iter().rev().find(|v| v.root < before);
        let (key, cell) = versions.before(0, before);
        assert_eq!(key, expected.map_or(0, |v| v.root + 1));
        assert!(std::ptr::eq(
            cell,
            expected.map_or(&versions.base[0], |v| &v.state)
        ));
        assert_eq!(cell.get().is_none(), before >= 4);
    }
    let report = versions.layout_trace.snapshot(&versions).unwrap();
    assert_eq!(report["markers"], 3);
    assert_eq!(report["marker_cells_missing_ok_error"], json!([2, 1, 0]));
    assert_eq!(report["initial_allocations"], 1);
    assert_eq!(
        report["query_newer_histogram"].as_array().unwrap()[..4],
        json!([1, 2, 2, 1]).as_array().unwrap()[..]
    );
    assert_eq!(report["base_queries"], 1);
    // std::mem::take must not install a second observer for an empty replacement.
    assert!(Versions::default().layout_trace.0.is_none());
}

#[test]
fn version_layout_probe_histograms_keep_overflow_and_exact_totals() {
    let (_, _, request) = crate::decoration::replay_tests::fixture(42, 0);
    let mut versions = Versions::new(request, 1);
    versions.layout_trace = Trace(Some((Arc::new(Session::default()), Box::default())));
    versions.layout_trace.query(100, 0);
    versions.layout_trace.query(200, 150);
    let report = versions.layout_trace.snapshot(&versions).unwrap();
    assert_eq!(report["query_length_histogram"][64], 2);
    assert_eq!(report["query_newer_histogram"][64], 1);
    assert_eq!(report["query_newer_histogram"][50], 1);
    assert_eq!(report["max_query_length"], 200);
    assert_eq!(report["max_query_newer"], 100);
    assert_eq!(report["total_newer_markers_skipped"], 150);
}

#[test]
#[ignore = "GPU inventory requires RETINA_TERRAIN_REUSE_PROFILES and RETINA_VERSION_LAYOUT_OUTPUT"]
fn exact_version_layout_inventory() {
    use std::{fs, path::PathBuf};
    let profiles = PathBuf::from(std::env::var("RETINA_TERRAIN_REUSE_PROFILES").unwrap());
    let output = PathBuf::from(std::env::var("RETINA_VERSION_LAYOUT_OUTPUT").unwrap());
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
        let profile: Value = serde_json::from_slice(&bytes).unwrap();
        let engine = TerrainEngine::new().unwrap();
        let id = engine.register_profile(&bytes).unwrap();
        let bounds = &profile["generation_bounds"];
        let request = ChunkRequest {
            seed,
            chunk_x: 0,
            chunk_z: 0,
            min_y: bounds["storage_min_y"].as_i64().unwrap_or(-64) as i32,
            height: bounds["storage_height"].as_u64().unwrap_or(384) as u32,
            base_height: 64.,
            amplitude: 48.,
            frequency: 0.008,
            reserved: id,
        };
        let biome = profile["biomes"][0]["id"].as_str().unwrap();
        let directory = output.join(format!("{name}-run0"));
        fs::create_dir(&directory).unwrap();
        let generate = |name: &str, x: i32, z: i32| {
            let before = engine.pipeline.program_snapshot(id);
            let start = Instant::now();
            let report = crate::region::generate_region(
                &engine,
                ChunkRequest {
                    chunk_x: x * 32,
                    chunk_z: z * 32,
                    ..request
                },
                &directory.join(format!("{name}.mca")),
                0,
                biome,
            )
            .unwrap();
            assert_eq!(report.generated, 1024);
            assert_eq!(report.preserved, 0);
            json!({"name":name,"x":x,"z":z,"instrumented_ms":start.elapsed().as_secs_f64()*1000.,
                "program_before":before,"program_after":engine.pipeline.program_snapshot(id)})
        };
        let warmups: Vec<_> = (0..5)
            .map(|i| generate(&format!("warm{i}"), 8 + i, 8))
            .collect();
        let installed = Installed::start();
        let regions: Vec<_> = (0..20)
            .map(|i| generate(&i.to_string(), -2 + i % 5, -2 + i / 5))
            .collect();
        drop(engine);
        let summary = installed.report();
        if name == "overworld" {
            assert_eq!(summary["started"], 0);
        } else {
            assert_eq!(summary["started"], 20);
            assert_eq!(summary["exports"].as_array().unwrap().len(), 20);
        }
        drop(installed);
        println!(
            "version layout inventory {name}: planners={}",
            summary["started"]
        );
        cases.push(json!({"case":name,"seed":seed,"profile":file,
            "warmups":warmups,"regions":regions,"summary":summary}));
        fs::write(output.join("report.json"), serde_json::to_vec_pretty(&json!({
            "scope":"Test-only Versions allocation capacities and exact historical lookup shapes. Original partition_point result always used. No added column evaluation, cell initialization or retained ColumnState owners. Scans occur after all roots join. Five warmups precede twenty observed complete lit regions per case. Export timestamps split existing interior capture, exterior capture and synchronous Versions destruction, including all owned cell/state destruction. Observed timings include atomic counter and scan overhead; not ordinary-library performance. Capacity bytes exclude pointees and allocator overhead; growth live bytes are not measured copying.",
            "cases":cases,"timing_acceptance":false,
        })).unwrap()).unwrap();
    }
}
