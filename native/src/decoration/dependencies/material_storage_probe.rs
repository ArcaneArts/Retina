//! Test-only material-read weights and joined live storage. Original RLE reads
//! always run and drive generation. No dense data or ColumnState owner is kept.
use super::*;
use serde_json::{Value, json};
use std::cell::RefCell;
use std::collections::HashSet;
use std::mem::size_of;

const KINDS: [&str; 3] = ["owned", "shared_terrain", "cached_archive"];
const OVERFLOW: usize = 64;
static NEXT: AtomicU64 = AtomicU64::new(1);
static ACTIVE_ID: AtomicU64 = AtomicU64::new(0);
static ACTIVE: Mutex<Option<Arc<Session>>> = Mutex::new(None);

fn kind(runs: &ColumnRuns) -> usize {
    match runs {
        ColumnRuns::Owned(_) => 0,
        ColumnRuns::Shared { .. } => 1,
        ColumnRuns::Cached { .. } => 2,
    }
}
struct Counts {
    reads: [AtomicU64; 3],
    words: [AtomicU64; 3],
    maximum: [AtomicU64; 3],
    histogram: [[AtomicU64; OVERFLOW + 1]; 3],
}
impl Default for Counts {
    fn default() -> Self {
        Self {
            reads: std::array::from_fn(|_| AtomicU64::new(0)),
            words: std::array::from_fn(|_| AtomicU64::new(0)),
            maximum: std::array::from_fn(|_| AtomicU64::new(0)),
            histogram: std::array::from_fn(|_| std::array::from_fn(|_| AtomicU64::new(0))),
        }
    }
}
impl Counts {
    fn record(&self, kind: usize, words: usize) {
        self.reads[kind].fetch_add(1, Ordering::Relaxed);
        self.words[kind].fetch_add(words as u64, Ordering::Relaxed);
        self.maximum[kind].fetch_max(words as u64, Ordering::Relaxed);
        self.histogram[kind][words.min(OVERFLOW)].fetch_add(1, Ordering::Relaxed);
    }
}
struct Session {
    token: u64,
    threads: Mutex<Vec<Arc<Counts>>>,
    regions: Mutex<Vec<Value>>,
}
impl Session {
    fn new(token: u64) -> Self {
        Self {
            token,
            threads: Mutex::new(Vec::new()),
            regions: Mutex::new(Vec::new()),
        }
    }
    fn report(&self) -> Value {
        let threads = self.threads.lock().unwrap();
        let read = |x: &AtomicU64| x.load(Ordering::Relaxed);
        let kinds: Vec<_> = (0..KINDS.len()).map(|k| {
            json!({"kind":KINDS[k],"reads":threads.iter().map(|c| read(&c.reads[k])).sum::<u64>(),
                "run_words_read_weighted_sum":threads.iter().map(|c| read(&c.words[k])).sum::<u64>(),
                "maximum_run_words":threads.iter().map(|c| read(&c.maximum[k])).max().unwrap_or(0),
                "run_length_histogram":(0..=OVERFLOW).map(|i| threads.iter().map(|c| read(&c.histogram[k][i])).sum::<u64>()).collect::<Vec<_>>()})
        }).collect();
        json!({"thread_count":threads.len(),"kinds":kinds,"histogram_overflow_bucket":OVERFLOW,
            "regions":*self.regions.lock().unwrap()})
    }
}
#[derive(Default)]
struct Local {
    token: u64,
    counts: Option<Arc<Counts>>,
}
impl Local {
    fn bind(&mut self, session: &Session) {
        if self.token != session.token {
            let counts = Arc::new(Counts::default());
            session.threads.lock().unwrap().push(counts.clone());
            self.counts = Some(counts);
            self.token = session.token;
        }
    }
}
thread_local! {
    static LOCAL: RefCell<Local> = RefCell::new(Local::default());
}
struct Installed(Arc<Session>);
impl Installed {
    fn start() -> Self {
        let mut active = ACTIVE.lock().unwrap();
        assert!(active.is_none());
        let token = NEXT.fetch_add(1, Ordering::Relaxed);
        let session = Arc::new(Session::new(token));
        *active = Some(session.clone());
        ACTIVE_ID.store(token, Ordering::Release);
        Self(session)
    }
}
impl Drop for Installed {
    fn drop(&mut self) {
        ACTIVE_ID.store(0, Ordering::Release);
        *ACTIVE.lock().unwrap_or_else(|e| e.into_inner()) = None;
    }
}
pub(super) fn read(runs: &ColumnRuns, words: usize) {
    let token = ACTIVE_ID.load(Ordering::Acquire);
    if token == 0 {
        return;
    }
    LOCAL.with(|local| {
        let mut local = local.borrow_mut();
        if local.token != token {
            let active = ACTIVE.lock().unwrap();
            let Some(session) = active.as_ref().filter(|s| s.token == token) else {
                return;
            };
            local.bind(session);
        }
        local.counts.as_ref().unwrap().record(kind(runs), words);
    });
}

#[derive(Default)]
struct Storage {
    slots: u64,
    unique_states: u64,
    distinct_slices: u64,
    state_run_words: u64,
    distinct_run_words: u64,
    run_histogram: BTreeMap<usize, u64>,
}
struct Live {
    height: usize,
    cells: [u64; 3],
    kinds: [Storage; 3],
    states: HashSet<usize>,
    slices: HashSet<(usize, usize, usize, usize)>,
    masks: HashSet<usize>,
    packed: HashSet<usize>,
    halos: HashSet<usize>,
    owned_run_bytes: usize,
    mask_word_bytes: usize,
    halo_bytes: usize,
    packed_word_bytes: usize,
}
impl Live {
    fn new(height: usize) -> Self {
        Self {
            height,
            cells: [0; 3],
            kinds: std::array::from_fn(|_| Storage::default()),
            states: HashSet::new(),
            slices: HashSet::new(),
            masks: HashSet::new(),
            packed: HashSet::new(),
            halos: HashSet::new(),
            owned_run_bytes: 0,
            mask_word_bytes: 0,
            halo_bytes: 0,
            packed_word_bytes: 0,
        }
    }
    fn cell(&mut self, cell: &ColumnCell) {
        match cell.get() {
            None => self.cells[0] += 1,
            Some(Err(_)) => self.cells[2] += 1,
            Some(Ok(state)) => {
                self.cells[1] += 1;
                self.state(state);
            }
        }
    }
    fn state(&mut self, state: &Arc<ColumnState>) {
        let k = kind(&state.runs);
        self.kinds[k].slots += 1;
        let pointer = Arc::as_ptr(state) as usize;
        if !self.states.insert(pointer) {
            return;
        }
        let words = state.runs.slice().len();
        let entry = &mut self.kinds[k];
        entry.unique_states += 1;
        entry.state_run_words += words as u64;
        *entry.run_histogram.entry(words).or_default() += 1;
        let key = match &state.runs {
            ColumnRuns::Owned(runs) => {
                self.owned_run_bytes += runs.len() * 4;
                (k, pointer, 0, runs.len())
            }
            ColumnRuns::Shared { mask, range } => {
                let owner = Arc::as_ptr(mask) as usize;
                if self.masks.insert(owner) {
                    self.mask_word_bytes += mask.words.capacity() * 4;
                    if let Some(halo) = &mask.columns {
                        if self.halos.insert(Arc::as_ptr(&halo.columns) as usize) {
                            self.halo_bytes += halo.columns.capacity() * size_of::<crate::Column>();
                        }
                    }
                }
                (k, owner, range.start, range.end)
            }
            ColumnRuns::Cached { runs, range } => {
                let owner = Arc::as_ptr(runs) as usize;
                if self.packed.insert(owner) {
                    self.packed_word_bytes += runs.capacity() * 4;
                }
                (k, owner, range.start, range.end)
            }
        };
        if self.slices.insert(key) {
            entry.distinct_slices += 1;
            entry.distinct_run_words += words as u64;
        }
    }
    fn report(&self) -> Value {
        let kinds: Vec<_> = self.kinds.iter().enumerate().map(|(k, s)| {
            json!({"kind":KINDS[k],"ready_cell_references":s.slots,
                "unique_states":s.unique_states,"distinct_backing_slices":s.distinct_slices,
                "run_words_per_unique_state_sum":s.state_run_words,"distinct_slice_run_words":s.distinct_run_words,
                "run_length_histogram":s.run_histogram,
                "state_objects_bytes":s.unique_states*(size_of::<ColumnState>()+2*size_of::<usize>()) as u64,
                "hypothetical_dense_payload_per_state_bytes":s.unique_states*self.height as u64*2,
                "hypothetical_dense_payload_per_distinct_slice_bytes":s.distinct_slices*self.height as u64*2})
        }).collect();
        json!({"height":self.height,"cells_missing_ok_error":self.cells,"kinds":kinds,
            "owned_run_payload_bytes":self.owned_run_bytes,
            "shared_mask_count":self.masks.len(),"shared_mask_word_capacity_bytes":self.mask_word_bytes,
            "shared_mask_object_bytes":self.masks.len()*(size_of::<crate::geology::CaveMask>()+2*size_of::<usize>()),
            "shared_halo_count":self.halos.len(),"shared_halo_payload_bytes":self.halo_bytes,
            "shared_halo_owner_bytes":self.halos.len()*(size_of::<Vec<crate::Column>>()+2*size_of::<usize>()),
            "cached_run_owner_count":self.packed.len(),"cached_run_capacity_bytes":self.packed_word_bytes,
            "cached_run_owner_bytes":self.packed.len()*(size_of::<Vec<u32>>()+2*size_of::<usize>())})
    }
}
pub(super) fn observe(planner: &Planner<'_>) {
    if ACTIVE_ID.load(Ordering::Acquire) == 0 {
        return;
    }
    let Some(session) = ACTIVE.lock().unwrap().clone() else {
        return;
    };
    let mut live = Live::new(planner.request.height as usize);
    for cell in &planner.versions.base {
        live.cell(cell);
    }
    for column in &planner.versions.changed {
        for version in column {
            live.cell(&version.state);
        }
    }
    for tile in planner.ready_columns.values() {
        for cell in &tile.0 {
            live.cell(cell);
        }
    }
    let mut pending_tiles = 0;
    for shard in &planner.columns.shards {
        let shard = shard.lock().unwrap();
        pending_tiles += shard.len();
        for tile in shard.values() {
            for cell in &tile.0 {
                live.cell(cell);
            }
        }
    }
    session
        .regions
        .lock()
        .unwrap()
        .push(json!({"origin":planner.versions.origin,
        "ready_boundary_tiles":planner.ready_columns.len(),"pending_boundary_tiles":pending_tiles,
        "storage":live.report()}));
}

#[test]
fn material_storage_counters_are_thread_local_and_session_isolated() {
    let first = Arc::new(Session::new(101));
    let mut workers = Vec::new();
    for k in 0..3 {
        let session = first.clone();
        workers.push(std::thread::spawn(move || {
            let mut local = Local::default();
            local.bind(&session);
            for _ in 0..1000 {
                local.counts.as_ref().unwrap().record(k, 100 + k);
            }
            local.bind(&session);
        }));
    }
    for worker in workers {
        worker.join().unwrap();
    }
    let report = first.report();
    assert_eq!(report["thread_count"], 3);
    for k in 0..3 {
        assert_eq!(report["kinds"][k]["reads"], 1000);
        assert_eq!(report["kinds"][k]["maximum_run_words"], 100 + k);
        assert_eq!(report["kinds"][k]["run_length_histogram"][64], 1000);
    }
    let second = Session::new(102);
    let mut local = Local::default();
    local.bind(&first);
    local.counts.as_ref().unwrap().record(0, 3);
    local.bind(&second);
    local.counts.as_ref().unwrap().record(1, 7);
    assert_eq!(first.report()["kinds"][0]["reads"], 1001);
    assert_eq!(second.report()["kinds"][0]["reads"], 0);
    assert_eq!(second.report()["kinds"][1]["reads"], 1);
}

#[test]
fn material_storage_deduplicates_owners_without_retaining_them() {
    let (profile, _, _) = crate::decoration::replay_tests::fixture(42, 0);
    let blocks: Vec<u16> = (0..256)
        .map(|y| ((y / 17) % profile.materials.len()) as u16)
        .collect();
    let owned = Arc::new(ColumnState::new(blocks.clone(), &profile, -64));
    let words = Arc::new(owned.runs.slice().to_vec());
    let mask = Arc::new(crate::geology::CaveMask {
        origin_x: -32,
        origin_z: -32,
        min_y: -64,
        height: 256,
        width: 0,
        air_only: false,
        words: words.as_ref().clone(),
        columns: None,
    });
    let shared = || {
        Arc::new(ColumnState::from_runs(
            ColumnRuns::Shared {
                mask: mask.clone(),
                range: 0..words.len(),
            },
            256,
            &profile,
            -64,
        ))
    };
    let states = [
        owned.clone(),
        shared(),
        shared(),
        Arc::new(ColumnState::from_runs(
            ColumnRuns::Cached {
                runs: words.clone(),
                range: 0..words.len(),
            },
            256,
            &profile,
            -64,
        )),
    ];
    let references: Vec<_> = states.iter().map(Arc::strong_count).collect();
    let mut live = Live::new(256);
    for state in &states {
        live.state(state);
        for (y, &expected) in blocks.iter().enumerate() {
            assert_eq!(state.material(y), expected);
        }
    }
    live.state(&owned);
    assert_eq!(
        references,
        states.iter().map(Arc::strong_count).collect::<Vec<_>>()
    );
    let report = live.report();
    assert_eq!(report["kinds"][0]["unique_states"], 1);
    assert_eq!(report["kinds"][1]["unique_states"], 2);
    assert_eq!(report["kinds"][1]["distinct_backing_slices"], 1);
    assert_eq!(report["kinds"][2]["unique_states"], 1);
    assert_eq!(report["shared_mask_count"], 1);
    assert_eq!(report["cached_run_owner_count"], 1);
    assert_eq!(
        report["kinds"][1]["hypothetical_dense_payload_per_state_bytes"],
        1024
    );
    assert_eq!(
        report["kinds"][1]["hypothetical_dense_payload_per_distinct_slice_bytes"],
        512
    );
}

#[test]
#[ignore = "GPU inventory requires RETINA_TERRAIN_REUSE_PROFILES and RETINA_MATERIAL_STORAGE_OUTPUT"]
fn exact_material_storage_inventory() {
    use std::{fs, path::PathBuf};
    let profiles = PathBuf::from(std::env::var("RETINA_TERRAIN_REUSE_PROFILES").unwrap());
    let output = PathBuf::from(std::env::var("RETINA_MATERIAL_STORAGE_OUTPUT").unwrap());
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
        let summary = installed.0.report();
        let observed = summary["regions"].as_array().unwrap().len();
        assert_eq!(observed, if name == "overworld" { 0 } else { 20 });
        for kind in summary["kinds"].as_array().unwrap() {
            assert_eq!(
                kind["reads"].as_u64().unwrap(),
                kind["run_length_histogram"]
                    .as_array()
                    .unwrap()
                    .iter()
                    .map(|x| x.as_u64().unwrap())
                    .sum::<u64>()
            );
        }
        drop(installed);
        println!(
            "material storage inventory {name}: calls={:?}, joined_regions={observed}",
            summary["kinds"]
                .as_array()
                .unwrap()
                .iter()
                .map(|k| k["reads"].as_u64().unwrap())
                .collect::<Vec<_>>()
        );
        cases.push(json!({"case":name,"seed":seed,"profile":file,
            "warmups":warmups,"regions":regions,"summary":summary}));
        fs::write(output.join("report.json"), serde_json::to_vec_pretty(&json!({
            "scope":"Test-only weights for every ColumnState::material call by Owned, Shared terrain and Cached archive backing. Original RLE result is always computed first and drives generation. Per-thread counters register once per session; no shared hot counter or per-read mutex. Joined scans inspect only existing ready cells and retain no state owners. Counts cover all ColumnState::material call sites, not other Field/substrate readers. Joined storage covers interior base/versions and ready/pending boundary tiles, not transient or engine-wide peak memory. Dense estimates are hypothetical added full-storage-height u16 payload, excluding allocator/object overhead; distinct-slice reuse is a possible lower payload estimate, not an implemented representation. Shared mask word capacity includes metadata; cached backing may contain unused spans. Existing RLE backing is not subtracted from hypothetical added dense payload. Five warmups precede twenty observed complete lit regions per case; observer time/memory are not ordinary performance.",
            "cases":cases,"timing_acceptance":false,
        })).unwrap()).unwrap();
    }
}
