//! Test-only inventory of exact ordered prefix streams. Generation still uses
//! every original command. Counters retain no columns, commands or world owners.
use super::*;
use serde::Serialize;
use serde_json::{Value, json};
use std::cell::RefCell;

static NEXT: AtomicU64 = AtomicU64::new(1);
static ACTIVE_ID: AtomicU64 = AtomicU64::new(0);
static ACTIVE: Mutex<Option<Arc<Session>>> = Mutex::new(None);
const BINS: usize = 14;

#[derive(Default, Serialize)]
struct Counts {
    prefixes: u64,
    limited_prefixes: u64,
    incomplete_prefixes: u64,
    commands: u64,
    out_of_bounds_commands: u64,
    conditional_commands: u64,
    feature_commands: u64,
    feature_changes: u64,
    feature_noops: u64,
    changed_points: u64,
    changed_upper_points: u64,
    spans: u64,
    multi_span_commands: u64,
    spans_by_ceil_log2_length: [u64; BINS],
    commands_by_ceil_log2_length: [u64; BINS],
    dense_expansions: u64,
    dense_payload_bytes: u64,
    dense_unchanged_results: u64,
    feature_only_dense_expansions: u64,
    feature_only_multi_span_dense_expansions: u64,
    prior_run_words: u64,
    result_run_words: u64,
}
impl Counts {
    fn add(&mut self, other: &Self) {
        macro_rules! sum { ($($field:ident),*) => { $(self.$field += other.$field;)* }; }
        sum!(
            prefixes,
            limited_prefixes,
            incomplete_prefixes,
            commands,
            out_of_bounds_commands,
            conditional_commands,
            feature_commands,
            feature_changes,
            feature_noops,
            changed_points,
            changed_upper_points,
            spans,
            multi_span_commands,
            dense_expansions,
            dense_payload_bytes,
            dense_unchanged_results,
            feature_only_dense_expansions,
            feature_only_multi_span_dense_expansions,
            prior_run_words,
            result_run_words
        );
        for i in 0..BINS {
            self.spans_by_ceil_log2_length[i] += other.spans_by_ceil_log2_length[i];
            self.commands_by_ceil_log2_length[i] += other.commands_by_ceil_log2_length[i];
        }
    }
}
type Roots = BTreeMap<usize, Counts>;
struct Session {
    token: u64,
    threads: Mutex<Vec<Arc<Mutex<Roots>>>>,
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
        let mut roots = Roots::new();
        let threads = self.threads.lock().unwrap();
        for thread in threads.iter() {
            for (root, counts) in thread.lock().unwrap().iter() {
                roots.entry(*root).or_default().add(counts);
            }
        }
        let mut total = Counts::default();
        for counts in roots.values() {
            total.add(counts);
        }
        json!({"threads":threads.len(),"roots":roots,"total":total,
            "regions":*self.regions.lock().unwrap()})
    }
}
#[derive(Default)]
struct Local {
    token: u64,
    counts: Option<Arc<Mutex<Roots>>>,
}
impl Local {
    fn bind(&mut self, session: &Session) {
        if self.token != session.token {
            let counts = Arc::new(Mutex::new(Roots::new()));
            session.threads.lock().unwrap().push(counts.clone());
            self.counts = Some(counts);
            self.token = session.token;
        }
    }
}
thread_local! { static LOCAL: RefCell<Local> = RefCell::new(Local::default()); }
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
struct Span {
    material: u16,
    last_y: i32,
    direction: i32,
    length: usize,
}
pub(super) struct Prefix {
    token: u64,
    root: usize,
    counts: Counts,
    span: Option<Span>,
}
impl Prefix {
    pub(super) fn start(root: usize, limited: bool, prior_words: usize) -> Self {
        Self::new(
            ACTIVE_ID.load(Ordering::Acquire),
            root,
            limited,
            prior_words,
        )
    }
    fn new(token: u64, root: usize, limited: bool, prior_words: usize) -> Self {
        Self {
            token,
            root,
            counts: Counts {
                prefixes: 1,
                limited_prefixes: limited as u64,
                incomplete_prefixes: 1,
                prior_run_words: prior_words as u64,
                ..Counts::default()
            },
            span: None,
        }
    }
    pub(super) fn barrier(&mut self) {
        if let Some(span) = self.span.take() {
            let bin = (span.length.next_power_of_two().trailing_zeros() as usize).min(BINS - 1);
            self.counts.spans += 1;
            self.counts.spans_by_ceil_log2_length[bin] += 1;
            self.counts.commands_by_ceil_log2_length[bin] += span.length as u64;
            if span.length > 1 {
                self.counts.multi_span_commands += span.length as u64;
            }
        }
    }
    pub(super) fn command(&mut self, role: u8, material: u16, y: i32, height: usize) {
        if self.token == 0 {
            return;
        }
        self.counts.commands += 1;
        if y < 0 || y as usize >= height {
            self.counts.out_of_bounds_commands += 1;
            self.barrier();
            return;
        }
        if role != FEATURE {
            self.counts.conditional_commands += 1;
            self.barrier();
            return;
        }
        self.counts.feature_commands += 1;
        if let Some(span) = self.span.as_mut() {
            let delta = y - span.last_y;
            if material == span.material
                && (delta == 1 || delta == -1)
                && (span.direction == 0 || span.direction == delta)
            {
                span.direction = delta;
                span.last_y = y;
                span.length += 1;
                return;
            }
        }
        self.barrier();
        self.span = Some(Span {
            material,
            last_y: y,
            direction: 0,
            length: 1,
        });
    }
    pub(super) fn feature_result(&mut self, role: u8, changed: bool) {
        if self.token != 0 && role == FEATURE {
            self.counts.feature_changes += changed as u64;
            self.counts.feature_noops += u64::from(!changed);
        }
    }
    pub(super) fn point_change(&mut self, upper: bool) {
        if self.token != 0 {
            self.counts.changed_points += 1;
            self.counts.changed_upper_points += upper as u64;
        }
    }
    pub(super) fn finish(
        &mut self,
        dense_height: Option<u32>,
        result_words: usize,
        unchanged: bool,
    ) {
        if self.token == 0 {
            return;
        }
        self.barrier();
        self.counts.incomplete_prefixes = 0;
        self.counts.result_run_words = result_words as u64;
        if let Some(height) = dense_height {
            self.counts.dense_expansions = 1;
            self.counts.dense_payload_bytes = u64::from(height) * 2;
            self.counts.dense_unchanged_results = unchanged as u64;
            let only_feature = self.counts.feature_commands == self.counts.commands;
            self.counts.feature_only_dense_expansions = only_feature as u64;
            self.counts.feature_only_multi_span_dense_expansions =
                (only_feature && self.counts.multi_span_commands > 0) as u64;
        }
    }
}
impl Drop for Prefix {
    fn drop(&mut self) {
        if self.token == 0 || ACTIVE_ID.load(Ordering::Acquire) != self.token {
            return;
        }
        self.barrier();
        LOCAL.with(|local| {
            let mut local = local.borrow_mut();
            if local.token != self.token {
                let active = ACTIVE.lock().unwrap();
                let Some(session) = active.as_ref().filter(|s| s.token == self.token) else {
                    return;
                };
                local.bind(session);
            }
            local
                .counts
                .as_ref()
                .unwrap()
                .lock()
                .unwrap()
                .entry(self.root)
                .or_default()
                .add(&self.counts);
        });
    }
}
pub(super) fn observe(planner: &Planner<'_>) {
    if ACTIVE_ID.load(Ordering::Acquire) == 0 {
        return;
    }
    let Some(session) = ACTIVE.lock().unwrap().clone() else {
        return;
    };
    let roots: Vec<_> = planner.roots.iter().enumerate().map(|(i, root)| json!({
        "index":i,"structure_definition":root.definition,
        "sources":root.ids.iter().map(|&id| &planner.profile.decorations[id as usize].source).collect::<Vec<_>>()
    })).collect();
    session
        .regions
        .lock()
        .unwrap()
        .push(json!({"chunk_x":planner.request.chunk_x,
        "chunk_z":planner.request.chunk_z,"roots":roots}));
}

#[test]
fn column_edit_spans_preserve_direction_material_bounds_and_barriers() {
    let mut p = Prefix::new(0, 0, false, 4);
    // A private token enables counting without installing global observation.
    p.token = u64::MAX;
    for y in [3, 4, 5, 4, 3, 3, 2] {
        p.command(FEATURE, 1, y, 256);
    }
    p.command(FEATURE, 2, 1, 256);
    p.command(DEFERRED_ORE, 2, 0, 256);
    p.command(FEATURE, 2, 0, 256);
    p.command(FEATURE, 2, -1, 256);
    p.command(FEATURE, 2, 1, 256);
    p.barrier(); // Exact prefix limit separates otherwise adjacent writes.
    p.command(FEATURE, 2, 2, 256);
    p.finish(Some(256), 6, false);
    assert_eq!(p.counts.commands, 13);
    assert_eq!(p.counts.feature_commands, 11);
    assert_eq!(p.counts.conditional_commands, 1);
    assert_eq!(p.counts.out_of_bounds_commands, 1);
    assert_eq!(&p.counts.spans_by_ceil_log2_length[..3], &[4, 2, 1]);
    assert_eq!(&p.counts.commands_by_ceil_log2_length[..3], &[4, 4, 3]);
    assert_eq!(p.counts.multi_span_commands, 7);
    assert_eq!(p.counts.feature_only_dense_expansions, 0);
    assert_eq!(p.counts.dense_payload_bytes, 512);
}

#[test]
fn column_edit_span_inventory_counts_long_spans_and_noops() {
    for height in [1, 2, 8, 9, 128, 256, 4096] {
        let mut p = Prefix::new(u64::MAX, 0, true, 3);
        for y in (0..height).rev() {
            p.command(FEATURE, 7, y as i32, height);
            p.feature_result(FEATURE, y % 2 == 0);
            if y % 2 == 0 {
                p.point_change(false);
            }
        }
        p.finish(Some(height as u32), 2, false);
        assert_eq!(p.counts.spans, 1);
        assert_eq!(p.counts.feature_changes, height.div_ceil(2) as u64);
        assert_eq!(p.counts.feature_noops, (height / 2) as u64);
        assert_eq!(p.counts.changed_points, p.counts.feature_changes);
        assert_eq!(p.counts.feature_only_dense_expansions, 1);
        assert_eq!(
            p.counts.feature_only_multi_span_dense_expansions,
            (height > 1) as u64
        );
        assert_eq!(
            p.counts.commands_by_ceil_log2_length.iter().sum::<u64>(),
            height as u64
        );
    }
}

#[test]
fn column_edit_thread_counters_isolate_sessions_and_roots() {
    let first = Arc::new(Session::new(91));
    let mut workers = Vec::new();
    for root in 0..3 {
        let session = first.clone();
        workers.push(std::thread::spawn(move || {
            let mut local = Local::default();
            local.bind(&session);
            local.bind(&session);
            local
                .counts
                .as_ref()
                .unwrap()
                .lock()
                .unwrap()
                .entry(root)
                .or_default()
                .add(&Counts {
                    prefixes: 10,
                    ..Counts::default()
                });
        }));
    }
    for worker in workers {
        worker.join().unwrap();
    }
    let second = Session::new(92);
    let mut local = Local::default();
    local.bind(&first);
    local.bind(&second);
    local
        .counts
        .as_ref()
        .unwrap()
        .lock()
        .unwrap()
        .entry(9)
        .or_default()
        .add(&Counts {
            prefixes: 2,
            ..Counts::default()
        });
    assert_eq!(first.report()["total"]["prefixes"], 30);
    assert_eq!(second.report()["total"]["prefixes"], 2);
    assert_eq!(first.report()["roots"].as_object().unwrap().len(), 3);
}

#[test]
#[ignore = "GPU inventory requires RETINA_TERRAIN_REUSE_PROFILES and RETINA_COLUMN_EDIT_OUTPUT"]
fn exact_column_edit_inventory() {
    use std::{fs, path::PathBuf, time::Instant};
    let profiles = PathBuf::from(std::env::var("RETINA_TERRAIN_REUSE_PROFILES").unwrap());
    let output = PathBuf::from(std::env::var("RETINA_COLUMN_EDIT_OUTPUT").unwrap());
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
        let total = &summary["total"];
        assert_eq!(total["incomplete_prefixes"], 0);
        let number = |key: &str| total[key].as_u64().unwrap();
        assert_eq!(
            number("commands"),
            number("out_of_bounds_commands")
                + number("conditional_commands")
                + number("feature_commands")
        );
        assert_eq!(
            number("feature_commands"),
            number("feature_changes") + number("feature_noops")
        );
        assert_eq!(
            number("feature_commands"),
            total["commands_by_ceil_log2_length"]
                .as_array()
                .unwrap()
                .iter()
                .map(|v| v.as_u64().unwrap())
                .sum::<u64>()
        );
        assert!(number("changed_points") >= number("feature_changes"));
        assert!(number("dense_expansions") <= number("prefixes"));
        drop(installed);
        println!("column edit inventory {name}: {total}");
        cases.push(json!({"case":name,"seed":seed,"profile":file,
            "warmups":warmups,"regions":regions,"summary":summary}));
        fs::write(output.join("report.json"), serde_json::to_vec_pretty(&json!({
            "scope":"Test-only observer of commands actually applied by Planner::prefix, grouped by profile-local root index. Same-material in-bounds unconditional FEATURE commands form a span only while consecutive and strictly monotonic in Y with step +1 or -1. Reversals, duplicates, gaps, different materials, conditional commands, out-of-bounds commands and exact prefix limits break spans. No commands are removed or rewritten. Feature changes/noops reflect the original previous-material read; changed_points includes successful conditional and plant-upper writes. Dense expansions count actual completed prefix buffers; payload is height*2 without allocator overhead. Histogram bin i covers lengths greater than 2^(i-1) through 2^i, with bin0=1 and final overflow bucket. Span command counts include noops and are opportunities, not speedup estimates. Original generation drives all outputs. Per-prefix stack scalars publish once to a per-thread root map; no hot-command lock or retained world owners. Other column construction paths and feature-local overlays are outside this scope. Five warmups and twenty measured complete lit regions per case; observer times are not ordinary performance. Root IDs are only comparable within each registered profile.",
            "cases":cases,"timing_acceptance":false,
        })).unwrap()).unwrap();
    }
}
