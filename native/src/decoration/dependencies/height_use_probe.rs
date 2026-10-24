//! Test-only lifetime census. A token lives inside each concrete ColumnState,
//! so Arc aliases and recycled allocator addresses cannot inflate uniqueness.
//! Counts never retain states, terrain, archives, planners or GPU resources.
use super::*;
use serde_json::{Value, json};
use std::cell::RefCell;
use std::sync::atomic::AtomicU8;

const MATERIAL: u8 = 64;
const ARCHIVE: u8 = 128;
const KINDS: [&str; 3] = ["owned", "shared_terrain", "cached_archive"];
static NEXT: AtomicU64 = AtomicU64::new(1);
static ACTIVE_ID: AtomicU64 = AtomicU64::new(0);
static ACTIVE: Mutex<Option<Arc<Session>>> = Mutex::new(None);

struct Bucket {
    created: AtomicU64,
    retired: AtomicU64,
    run_words: AtomicU64,
    identity_discards: AtomicU64,
    height_queries: [AtomicU64; 6],
    query_run_words: [AtomicU64; 6],
    archive_copies: [AtomicU64; 2],
    classes: [AtomicU64; 8],
    class_words: [AtomicU64; 8],
    height_masks: [AtomicU64; 64],
}
impl Default for Bucket {
    fn default() -> Self {
        Self {
            created: AtomicU64::new(0), retired: AtomicU64::new(0), run_words: AtomicU64::new(0),
            identity_discards: AtomicU64::new(0),
            height_queries: std::array::from_fn(|_| AtomicU64::new(0)),
            query_run_words: std::array::from_fn(|_| AtomicU64::new(0)),
            archive_copies: std::array::from_fn(|_| AtomicU64::new(0)),
            classes: std::array::from_fn(|_| AtomicU64::new(0)),
            class_words: std::array::from_fn(|_| AtomicU64::new(0)),
            height_masks: std::array::from_fn(|_| AtomicU64::new(0)),
        }
    }
}
struct Counts {
    // Each backing kind is split into restored (even) and constructed (odd).
    buckets: [Bucket; 6],
    untracked_queries: [AtomicU64; 3],
}
impl Default for Counts {
    fn default() -> Self {
        Self {
            buckets: std::array::from_fn(|_| Bucket::default()),
            untracked_queries: std::array::from_fn(|_| AtomicU64::new(0)),
        }
    }
}
#[derive(Default)]
pub(super) struct State {
    counts: Option<Arc<Counts>>,
    flags: AtomicU8,
    bucket: usize,
    words: usize,
}
impl State {
    fn bound(counts: Arc<Counts>, bucket: usize, words: usize) -> Self {
        counts.buckets[bucket].created.fetch_add(1, Ordering::Relaxed);
        counts.buckets[bucket].run_words.fetch_add(words as u64, Ordering::Relaxed);
        Self { counts: Some(counts), bucket, words, flags: AtomicU8::new(0) }
    }
    pub(super) fn constructed(runs: &ColumnRuns) -> Self {
        let kind = match runs {
            ColumnRuns::Owned(_) => 0,
            ColumnRuns::Shared { .. } => 1,
            ColumnRuns::Cached { .. } => 2,
        };
        current().map_or_else(Self::default, |counts| {
            Self::bound(counts, kind * 2 + 1, runs.slice().len())
        })
    }
    pub(super) fn restored(words: usize) -> Self {
        current().map_or_else(Self::default, |counts| Self::bound(counts, 4, words))
    }
    pub(super) fn material(&self) {
        if self.counts.is_some() {
            if self.flags.load(Ordering::Relaxed) & MATERIAL == 0 {
                self.flags.fetch_or(MATERIAL, Ordering::Relaxed);
            }
        } else {
            untracked(0);
        }
    }
    pub(super) fn height(&self, map: u8) {
        if let Some(counts) = &self.counts {
            // The original array access still determines invalid-map behavior.
            if let Some(counter) = counts.buckets[self.bucket].height_queries.get(map as usize) {
                counter.fetch_add(1, Ordering::Relaxed);
                let bit = 1 << map;
                if self.flags.load(Ordering::Relaxed) & bit == 0 {
                    self.flags.fetch_or(bit, Ordering::Relaxed);
                }
            }
        } else {
            untracked(1);
        }
    }
    pub(super) fn height_scan(&self, map: u8, visited: usize) {
        self.height(map);
        if let Some(counts) = &self.counts {
            assert!(visited <= self.words);
            counts.buckets[self.bucket].query_run_words[map as usize]
                .fetch_add(visited as u64, Ordering::Relaxed);
        }
    }
    pub(super) fn archive(&self, exterior: bool) {
        if let Some(counts) = &self.counts {
            counts.buckets[self.bucket].archive_copies[usize::from(exterior)]
                .fetch_add(1, Ordering::Relaxed);
            self.flags.fetch_or(ARCHIVE, Ordering::Relaxed);
        } else {
            untracked(2);
        }
    }
    pub(super) fn discarded_identity(&self) {
        if let Some(counts) = &self.counts {
            counts.buckets[self.bucket].identity_discards.fetch_add(1, Ordering::Relaxed);
        }
    }
}
impl Drop for State {
    fn drop(&mut self) {
        let Some(counts) = &self.counts else { return };
        let flags = *self.flags.get_mut();
        let heights = flags & 63;
        let class = usize::from(flags & MATERIAL != 0)
            | (usize::from(heights != 0) << 1)
            | (usize::from(flags & ARCHIVE != 0) << 2);
        let bucket = &counts.buckets[self.bucket];
        bucket.classes[class].fetch_add(1, Ordering::Relaxed);
        bucket.class_words[class].fetch_add(self.words as u64, Ordering::Relaxed);
        bucket.height_masks[heights as usize].fetch_add(1, Ordering::Relaxed);
        bucket.retired.fetch_add(1, Ordering::Relaxed);
    }
}

struct Session {
    token: u64,
    threads: Mutex<Vec<Arc<Counts>>>,
}
impl Session {
    fn new(token: u64) -> Self {
        Self { token, threads: Mutex::new(Vec::new()) }
    }
    fn report(&self) -> Value {
        let threads = self.threads.lock().unwrap();
        let read = |v: &AtomicU64| v.load(Ordering::Relaxed);
        let sum = |pick: &dyn Fn(&Counts) -> u64| threads.iter().map(|c| pick(c)).sum::<u64>();
        let mut buckets = Vec::new();
        for b in 0..6 {
            let created = sum(&|c| read(&c.buckets[b].created));
            let retired = sum(&|c| read(&c.buckets[b].retired));
            let words = sum(&|c| read(&c.buckets[b].run_words));
            let classes: Vec<_> = (0..8).map(|i| sum(&|c| read(&c.buckets[b].classes[i]))).collect();
            let class_words: Vec<_> = (0..8).map(|i| sum(&|c| read(&c.buckets[b].class_words[i]))).collect();
            let masks: Vec<_> = (0..64).map(|i| sum(&|c| read(&c.buckets[b].height_masks[i]))).collect();
            let queries: Vec<_> = (0..6).map(|i| sum(&|c| read(&c.buckets[b].height_queries[i]))).collect();
            let archive: Vec<_> = (0..2).map(|i| sum(&|c| read(&c.buckets[b].archive_copies[i]))).collect();
            let unused: u64 = [0, 1, 4, 5].into_iter().map(|i| classes[i]).sum();
            let unused_words: u64 = [0, 1, 4, 5].into_iter().map(|i| class_words[i]).sum();
            buckets.push(json!({"kind":KINDS[b / 2],"origin":if b % 2 == 0 {"restored"} else {"constructed"},
                "created_states":created,"retired_states":retired,"created_run_words":words,
                "identity_temporary_discards":sum(&|c| read(&c.buckets[b].identity_discards)),
                "access_classes":classes,"class_run_words":class_words,"height_masks":masks,
                "height_queries_by_map":queries,
                "height_query_run_words_by_map":(0..6).map(|i| sum(&|c| read(&c.buckets[b].query_run_words[i]))).collect::<Vec<_>>(),
                "material_archive_copies_interior_exterior":archive,
                "height_unused_states":unused,"height_unused_run_words":unused_words,
                "height_unused_not_archived_states":classes[0]+classes[1],
                "height_unused_not_archived_run_words":class_words[0]+class_words[1],
                "balanced":created==retired && retired==classes.iter().sum::<u64>()
                    && retired==masks.iter().sum::<u64>() && words==class_words.iter().sum::<u64>()}));
        }
        let missing: Vec<_> = (0..3).map(|i| sum(&|c| read(&c.untracked_queries[i]))).collect();
        json!({"creation_counter_shards":threads.len(),"buckets":buckets,
            "untracked_material_height_archive_calls":missing,
            "access_class_bits":{"material":1,"height":2,"archive":4},
            "height_mask_bits":"Bits 0..5 identify the heightmap queried at least once in this state lifetime."})
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
thread_local! { static LOCAL: RefCell<Local> = RefCell::new(Local::default()); }
fn current() -> Option<Arc<Counts>> {
    let token = ACTIVE_ID.load(Ordering::Acquire);
    if token == 0 { return None; }
    LOCAL.with(|local| {
        let mut local = local.borrow_mut();
        if local.token != token {
            let active = ACTIVE.lock().unwrap();
            let session = active.as_ref().filter(|s| s.token == token)?;
            local.bind(session);
        }
        local.counts.clone()
    })
}
fn untracked(op: usize) {
    if let Some(counts) = current() {
        counts.untracked_queries[op].fetch_add(1, Ordering::Relaxed);
    }
}
struct Installed(Arc<Session>);
impl Installed {
    fn start() -> Self {
        let mut active = ACTIVE.lock().unwrap();
        assert!(active.is_none());
        let session = Arc::new(Session::new(NEXT.fetch_add(1, Ordering::Relaxed)));
        *active = Some(session.clone());
        ACTIVE_ID.store(session.token, Ordering::Release);
        Self(session)
    }
}
impl Drop for Installed {
    fn drop(&mut self) {
        ACTIVE_ID.store(0, Ordering::Release);
        *ACTIVE.lock().unwrap_or_else(|e| e.into_inner()) = None;
    }
}
fn assert_balanced(report: &Value) {
    assert!(report["buckets"].as_array().unwrap().iter().all(|b| b["balanced"] == true));
    assert!(report["untracked_material_height_archive_calls"].as_array().unwrap().iter().all(|n| n == 0));
}

#[test]
fn height_use_partitions_every_access_mask_and_counts_aliases_once() {
    let session = Session::new(101);
    let mut local = Local::default(); local.bind(&session);
    for flags in 0..=255u8 {
        let state = Arc::new(State::bound(local.counts.as_ref().unwrap().clone(), 1, usize::from(flags) + 1));
        for map in 0..6 {
            if flags & (1 << map) != 0 { for _ in 0..3 { state.height(map); } }
        }
        if flags & MATERIAL != 0 { for _ in 0..100 { state.material(); } }
        if flags & ARCHIVE != 0 { state.archive(false); state.archive(true); }
        let alias = state.clone(); drop(state);
        let report = session.report();
        assert_eq!(report["buckets"][1]["created_states"].as_u64().unwrap(), u64::from(flags) + 1);
        assert_eq!(report["buckets"][1]["retired_states"].as_u64().unwrap(), u64::from(flags));
        drop(alias);
    }
    let report = session.report(); assert_balanced(&report);
    let bucket = &report["buckets"][1];
    assert_eq!(bucket["created_states"], 256);
    assert_eq!(bucket["height_unused_states"], 4);
    assert!(bucket["height_masks"].as_array().unwrap().iter().all(|v| v == 4));
    assert!(bucket["height_queries_by_map"].as_array().unwrap().iter().all(|v| v == 384));
    assert_eq!(bucket["created_run_words"], 256 * 257 / 2);
}

#[test]
fn height_use_concurrent_readers_and_retirement_preserve_exact_counts() {
    let session = Session::new(202); let mut local = Local::default(); local.bind(&session);
    let state = Arc::new(ColumnState {
        runs: ColumnRuns::Owned(vec![7].into_boxed_slice()),
        height_trace: State::bound(local.counts.as_ref().unwrap().clone(), 1, 1),
    });
    let weak = Arc::downgrade(&state);
    std::thread::scope(|scope| {
        for map in 0..6 {
            let state = state.clone();
            scope.spawn(move || {
                for _ in 0..1000 { assert_eq!(state.material(0), 7); state.height_trace.height(map); }
                state.height_trace.archive(map % 2 == 0);
            });
        }
    });
    assert_eq!(session.report()["buckets"][1]["created_states"], 1);
    drop(state); assert!(weak.upgrade().is_none());
    let report = session.report(); assert_balanced(&report);
    assert_eq!(report["buckets"][1]["access_classes"][7], 1);
    assert_eq!(report["buckets"][1]["height_masks"][63], 1);
    assert!(report["buckets"][1]["height_queries_by_map"].as_array().unwrap().iter().all(|v| v == 1000));
}


#[test]
fn direct_heights_census_counts_actual_visits_including_repeat_queries() {
    let (mut profile, _, _) = crate::decoration::replay_tests::fixture(42, 0);
    profile.heightmap_masks[0] = 0; profile.heightmap_masks[1] = 4; profile.heightmap_masks[2] = 16;
    let session = Session::new(404); let mut local = Local::default(); local.bind(&session);
    let column = ColumnState {
        runs: ColumnRuns::Owned(vec![(8 << 16), (4 << 16) | 1, 2].into_boxed_slice()),
        height_trace: State::bound(local.counts.as_ref().unwrap().clone(), 1, 3),
    };
    for _ in 0..2 { assert_eq!(column.height(&profile, -64, 12, 2), -56); }
    assert_eq!(column.height(&profile, -64, 12, 4), -60);
    assert_eq!(column.height(&profile, -64, 12, 1), -64);
    assert_eq!(column.material(0), 2); column.height_trace.archive(false);
    drop(column); let report = session.report(); assert_balanced(&report);
    assert_eq!(report["buckets"][1]["height_queries_by_map"], json!([0,1,2,0,1,0]));
    assert_eq!(report["buckets"][1]["height_query_run_words_by_map"], json!([0,3,4,0,3,0]));
    assert_eq!(report["buckets"][1]["access_classes"][7], 1);
    assert_eq!(report["buckets"][1]["height_masks"][22], 1);
    assert_eq!(report["buckets"][1]["material_archive_copies_interior_exterior"], json!([1,0]));
}

#[test]
fn direct_heights_composed_run_consumers_record_material_access() {
    let (profile, _, _) = crate::decoration::replay_tests::fixture(42, 0);
    let session = Session::new(405); let mut local = Local::default(); local.bind(&session);
    let make = || ColumnState {
        runs: ColumnRuns::Owned(vec![(8 << 16), (4 << 16) | 1, 2].into_boxed_slice()),
        height_trace: State::bound(local.counts.as_ref().unwrap().clone(), 1, 3),
    };
    let mask_column = make();
    super::material_masks::from_column(&mask_column, 12, 1);
    let ground_column = make();
    super::ground_transitions::from_column(&ground_column, &profile, 0, 12, 12);
    let merge_column = make();
    assert!(super::uniform_blob::Edits::default().finish(&merge_column, 12, 1).is_none());
    drop((mask_column, ground_column, merge_column));
    let report = session.report(); assert_balanced(&report);
    assert_eq!(report["buckets"][1]["created_states"], 3);
    assert_eq!(report["buckets"][1]["access_classes"][1], 3);
    assert_eq!(report["buckets"][1]["height_queries_by_map"], json!([0,0,0,0,0,0]));
    assert_eq!(report["untracked_material_height_archive_calls"], json!([0,0,0]));
}

#[test]
fn height_use_rebinding_preserves_old_lifetimes_and_restored_classification() {
    let first = Session::new(301); let second = Session::new(302);
    let mut local = Local::default(); local.bind(&first);
    let old = State::bound(local.counts.as_ref().unwrap().clone(), 3, 17);
    local.bind(&second);
    let restored = State::bound(local.counts.as_ref().unwrap().clone(), 4, 9);
    old.material(); old.archive(false); restored.height(4);
    drop(old); drop(restored);
    let a = first.report(); let b = second.report(); assert_balanced(&a); assert_balanced(&b);
    assert_eq!(a["buckets"][3]["access_classes"][5], 1);
    assert_eq!(a["buckets"][3]["height_unused_run_words"], 17);
    assert_eq!(b["buckets"][4]["created_states"], 1);
    assert_eq!(b["buckets"][4]["height_unused_states"], 0);
    assert_eq!(b["buckets"][3]["created_states"], 0);
}

#[test]
#[ignore = "GPU census requires RETINA_TERRAIN_REUSE_PROFILES and RETINA_HEIGHT_USE_OUTPUT"]
fn exact_column_height_use_inventory() {
    use std::{fs, path::PathBuf, time::{Duration, Instant}};
    let profiles = PathBuf::from(std::env::var("RETINA_TERRAIN_REUSE_PROFILES").unwrap());
    let output = PathBuf::from(std::env::var("RETINA_HEIGHT_USE_OUTPUT").unwrap());
    fs::create_dir(&output).unwrap();
    let mut cases = Vec::new();
    for (name, file, seed) in [
        ("nether-main", "nether.json", 123456789), ("nether-second-seed", "nether.json", 42),
        ("nether-custom-bounds", "custom.json", 123456789), ("nether-direct-stem", "direct-stem.json", 42),
        ("overworld", "overworld.json", 123456789),
    ] {
        let bytes = fs::read(profiles.join(file)).unwrap();
        let profile: Value = serde_json::from_slice(&bytes).unwrap();
        let engine = TerrainEngine::new().unwrap(); let id = engine.register_profile(&bytes).unwrap();
        let bounds = &profile["generation_bounds"];
        let request = ChunkRequest { seed, chunk_x: 0, chunk_z: 0,
            min_y: bounds["storage_min_y"].as_i64().unwrap_or(-64) as i32,
            height: bounds["storage_height"].as_u64().unwrap_or(384) as u32,
            base_height: 64., amplitude: 48., frequency: 0.008, reserved: id };
        let biome = profile["biomes"][0]["id"].as_str().unwrap();
        let directory = output.join(format!("{name}-run0")); fs::create_dir(&directory).unwrap();
        let generate = |name: &str, x: i32, z: i32| {
            let before = engine.pipeline.program_snapshot(id); let start = Instant::now();
            let report = crate::region::generate_region(&engine,
                ChunkRequest { chunk_x: x * 32, chunk_z: z * 32, ..request },
                &directory.join(format!("{name}.mca")), 0, biome).unwrap();
            assert_eq!(report.generated, 1024); assert_eq!(report.preserved, 0);
            json!({"name":name,"x":x,"z":z,"instrumented_ms":start.elapsed().as_secs_f64()*1000.,
                "program_before":before,"program_after":engine.pipeline.program_snapshot(id)})
        };
        let warmups: Vec<_> = (0..5).map(|i| generate(&format!("warm{i}"), 8+i, 8)).collect();
        let ready = Instant::now();
        while engine.pipeline.program_snapshot(id).status == 1 && ready.elapsed() < Duration::from_secs(180) {
            std::thread::sleep(Duration::from_millis(100));
        }
        let readiness = engine.pipeline.program_snapshot(id);
        assert_eq!(readiness.status, 4);
        let installed = Installed::start(); let mut regions = Vec::new();
        for i in 0..20 {
            let mut region = generate(&i.to_string(), -2+i%5, -2+i/5);
            assert_eq!(region["program_before"]["status"], 4); assert_eq!(region["program_after"]["status"], 4);
            let summary = installed.0.report(); assert_balanced(&summary);
            region["cumulative_census"] = summary; regions.push(region);
            fs::write(directory.join("census.json"), serde_json::to_vec_pretty(&regions).unwrap()).unwrap();
        }
        drop(engine); let summary = installed.0.report(); assert_balanced(&summary); drop(installed);
        let created: u64 = summary["buckets"].as_array().unwrap().iter().map(|b| b["created_states"].as_u64().unwrap()).sum();
        if name == "overworld" { assert_eq!(created, 0); } else { assert!(created > 0); }
        println!("height-use census {name}: distinct states={created}");
        cases.push(json!({"case":name,"seed":seed,"profile":file,"readiness":readiness,
            "warmups":warmups,"regions":regions,"summary":summary}));
        fs::write(output.join("report.json"), serde_json::to_vec_pretty(&json!({"cases":cases,"timing_acceptance":false,
            "scope":"Test-only concrete ColumnState lifetime census. Arc aliases do not create states; tokens retain counters only. Each construction records backing kind, run count and constructed-versus-restored state origin. Material marks cover scalar reads, dense expansion, production RLE equality checks, uniform-run merges, target-mask construction and ground-transition scans; height queries count calls by map and lifetime map masks. Archives copy material runs, not height arrays. Height queries scan only the requested map and count actual run words visited, including repeated reads. No height-array scan occurs at creation or capture. Temporary states discarded by the original equality check are counted separately. Final access classes and run-word weights are recorded on destruction. Every measured complete region must balance created/retired/class/mask/run counts and have zero untracked calls. Five unobserved warmups precede twenty observed complete lit regions per case. Counter snapshots and serialization occur after the synchronous native region call. Observer atomics, extra fields, counter ownership and memory affect instrumented costs; these are not production timings or allocation counts. State-use and actual query-scan counts describe work, not a production elapsed-time speedup or a claim that an entire column is unnecessary. Normal builds contain no census fields or code."})).unwrap()).unwrap();
    }
}
