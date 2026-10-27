//! Test-only, wave-bounded comparison of successful blob anchor outputs.
//! Snapshots copy values; weak handles NEVER upgrade or pin generation owners.
//! Live-at-demand is an observed opportunity, not a safe reuse implementation.
use super::*;
use serde::Serialize;
use serde_json::{Value, json};
use std::sync::{Weak, atomic::AtomicBool};

static ENABLED: AtomicBool = AtomicBool::new(false);
static ACTIVE: Mutex<Option<Arc<Session>>> = Mutex::new(None);
const MAX_KEYS: usize = 262_144;
const MAX_SNAPSHOT_BYTES: usize = 256 * 1024 * 1024;

#[derive(Clone, Copy, Hash, PartialEq, Eq)]
struct Key {
    engine: usize,
    request: crate::CacheKey,
    root: usize,
}
impl Key {
    fn new(engine: usize, request: ChunkRequest, root: usize, x: i32, z: i32) -> Self {
        Self {
            engine,
            request: ChunkRequest {
                chunk_x: x,
                chunk_z: z,
                ..request
            }
            .into(),
            root,
        }
    }
}
#[derive(Clone, PartialEq, Eq)]
struct Snapshot {
    writes: Vec<WorldBlock>,
    columns: BTreeMap<(i32, i32), Vec<usize>>,
}
impl Snapshot {
    fn from(output: &Output) -> Self {
        Self {
            writes: output.writes.clone(),
            columns: output
                .columns
                .iter()
                .map(|(key, ids)| (key, ids.to_vec()))
                .collect(),
        }
    }
    fn logical_bytes(output: &Output) -> usize {
        output.writes.len() * std::mem::size_of::<WorldBlock>()
            + output.columns.len() * std::mem::size_of::<((i32, i32), Vec<usize>)>()
            + output
                .columns
                .values()
                .map(|v| v.len() * std::mem::size_of::<usize>())
                .sum::<usize>()
    }
    fn index_valid(output: &Output) -> bool {
        let mut expected: Map<_, Vec<_>> = Map::default();
        for (i, b) in output.writes.iter().enumerate() {
            expected.entry((b.x, b.z)).or_default().push(i);
        }
        expected.len() == output.columns.len()
            && expected.iter().all(|(key, indices)| output.columns.get(key) == Some(indices.as_slice()))
    }
}
#[derive(Default, Serialize)]
struct Counts {
    builds: u64,
    successes: u64,
    failures: u64,
    completed_live_at_demand: u64,
    completed_dead_at_demand: u64,
    other_in_flight_at_demand: u64,
    compared_outputs: u64,
    compared_nonempty_outputs: u64,
    compared_writes: u64,
    mismatched_outputs: u64,
    invalid_indexes_or_metadata: u64,
    writes: u64,
    live_at_demand_writes: u64,
    live_at_demand_nonempty: u64,
    skipped_keys: u64,
    skipped_snapshots: u64,
}
#[derive(Default)]
struct History {
    pending: Vec<u64>,
    published: Vec<(u64, Weak<Output>)>,
    snapshot: Option<Snapshot>,
}
#[derive(Default)]
struct State {
    entries: Map<Key, History>,
    roots: BTreeMap<usize, Counts>,
    jobs: Vec<Value>,
    snapshot_bytes: usize,
}
#[derive(Default)]
struct Session {
    state: Mutex<State>,
}
impl Session {
    fn begin(self: &Arc<Self>, key: Key, job: u64) -> Option<Build> {
        let mut state = self.state.lock().unwrap();
        if state.entries.len() >= MAX_KEYS && !state.entries.contains_key(&key) {
            state.roots.entry(key.root).or_default().skipped_keys += 1;
            return None;
        }
        let history = state.entries.entry(key).or_default();
        assert!(!history.pending.contains(&job));
        assert!(history.published.iter().all(|(owner, _)| *owner != job));
        // Sampling strong_count cannot keep Output alive or interfere with
        // Arc::try_unwrap. It also cannot guarantee a future upgrade succeeds.
        let live = history
            .published
            .iter()
            .any(|(_, weak)| weak.strong_count() > 0);
        let dead = !live && !history.published.is_empty();
        let pending = !history.pending.is_empty();
        history.pending.push(job);
        let counts = state.roots.entry(key.root).or_default();
        counts.builds += 1;
        counts.completed_live_at_demand += live as u64;
        counts.completed_dead_at_demand += dead as u64;
        counts.other_in_flight_at_demand += pending as u64;
        Some(Build {
            session: self.clone(),
            key,
            job,
            live,
            finished: false,
        })
    }
    fn report(&self) -> Value {
        let state = self.state.lock().unwrap();
        json!({"roots":state.roots,"jobs":state.jobs,"keys":state.entries.len(),
            "snapshot_logical_bytes":state.snapshot_bytes,
            "pending_builds":state.entries.values().map(|h|h.pending.len()).sum::<usize>(),
            "published_weak_handles":state.entries.values().map(|h|h.published.len()).sum::<usize>(),
            "live_handles_at_wave_end":state.entries.values().flat_map(|h|&h.published)
                .filter(|(_,weak)|weak.strong_count()>0).count(),
            "max_keys":MAX_KEYS,"max_snapshot_logical_bytes":MAX_SNAPSHOT_BYTES})
    }
}
pub(super) struct Job {
    session: Option<Arc<Session>>,
    id: u64,
    engine: usize,
    request: ChunkRequest,
    eligible: Vec<bool>,
}
impl Job {
    pub(super) fn new<'a>(
        engine: &TerrainEngine,
        profile: &WorldProfile,
        request: ChunkRequest,
        side: usize,
        roots: impl Iterator<Item = &'a Root>,
    ) -> Self {
        let session = ENABLED
            .load(Ordering::Acquire)
            .then(|| ACTIVE.lock().unwrap().clone())
            .flatten();
        let mut job = Self {
            session,
            id: 0,
            engine: engine as *const _ as usize,
            request,
            eligible: Vec::new(),
        };
        if let Some(session) = &job.session {
            let roots: Vec<_> = roots.collect();
            job.eligible = roots
                .iter()
                .map(|root| {
                    root.definition.is_none()
                        && !root.ids.is_empty()
                        && root.ids.iter().all(|&id| {
                            matches!(
                                profile.decorations[id as usize].feature,
                                Kind::NetherFeature {
                                    nether: patch::nether::Feature::ReplaceBlobs { .. }
                                }
                            )
                        })
                })
                .collect();
            let mut state = session.state.lock().unwrap();
            job.id = state.jobs.len() as u64;
            state.jobs.push(json!({"id":job.id,"seed":request.seed,"profile":request.reserved,
                "chunk_x":request.chunk_x,"chunk_z":request.chunk_z,"side":side,
                "min_y":request.min_y,"height":request.height,
                "blob_roots":roots.iter().enumerate().filter(|(i,_)|job.eligible[*i])
                    .map(|(i,r)|json!({"index":i,"sources":r.ids.iter().map(|&id|
                        &profile.decorations[id as usize].source).collect::<Vec<_>>()})).collect::<Vec<_>>()}));
        }
        job
    }
    pub(super) fn begin(&self, root: usize, x: i32, z: i32) -> Option<Build> {
        let session = self.session.as_ref()?;
        self.eligible[root]
            .then(|| session.begin(Key::new(self.engine, self.request, root, x, z), self.id))
            .flatten()
    }
}
pub(super) struct Build {
    session: Arc<Session>,
    key: Key,
    job: u64,
    live: bool,
    finished: bool,
}
impl Build {
    pub(super) fn complete(mut self, output: &Arc<Output>) {
        // Only a completed successful memo result reaches this hook. No pending
        // cell, errors, RNG or mutable replay state is shared between jobs.
        let valid = output.metadata.is_none() && Snapshot::index_valid(output);
        let bytes = Snapshot::logical_bytes(output);
        let mut state = self.session.state.lock().unwrap();
        let room = bytes <= MAX_SNAPSHOT_BYTES.saturating_sub(state.snapshot_bytes);
        let history = state.entries.get_mut(&self.key).unwrap();
        history.pending.retain(|&job| job != self.job);
        let (compared, mismatch, stored, skipped) = if let Some(previous) = &history.snapshot {
            // Compare every field and complete index vector, not a hash.
            let equal = previous.writes == output.writes
                && previous.columns.len() == output.columns.len()
                && previous
                    .columns
                    .iter()
                    .all(|(key, ids)| output.columns.get(key) == Some(ids.as_slice()));
            (true, !equal, false, false)
        } else if room {
            history.snapshot = Some(Snapshot::from(output));
            (false, false, true, false)
        } else {
            (false, false, false, true)
        };
        history.published.push((self.job, Arc::downgrade(output)));
        state.snapshot_bytes += if stored { bytes } else { 0 };
        let c = state.roots.entry(self.key.root).or_default();
        c.successes += 1;
        c.compared_outputs += compared as u64;
        c.compared_nonempty_outputs += (compared && !output.writes.is_empty()) as u64;
        c.compared_writes += if compared {
            output.writes.len() as u64
        } else {
            0
        };
        c.mismatched_outputs += mismatch as u64;
        c.invalid_indexes_or_metadata += (!valid) as u64;
        c.writes += output.writes.len() as u64;
        c.live_at_demand_writes += if self.live {
            output.writes.len() as u64
        } else {
            0
        };
        c.live_at_demand_nonempty += (self.live && !output.writes.is_empty()) as u64;
        c.skipped_snapshots += skipped as u64;
        self.finished = true;
    }
}
impl Drop for Build {
    fn drop(&mut self) {
        if !self.finished {
            let mut state = self.session.state.lock().unwrap_or_else(|e| e.into_inner());
            state
                .entries
                .get_mut(&self.key)
                .unwrap()
                .pending
                .retain(|&job| job != self.job);
            state.roots.entry(self.key.root).or_default().failures += 1;
        }
    }
}
struct Installed(Arc<Session>);
impl Installed {
    fn start() -> Self {
        let mut active = ACTIVE.lock().unwrap();
        assert!(active.is_none());
        let session = Arc::new(Session::default());
        *active = Some(session.clone());
        ENABLED.store(true, Ordering::Release);
        Self(session)
    }
}
impl Drop for Installed {
    fn drop(&mut self) {
        ENABLED.store(false, Ordering::Release);
        *ACTIVE.lock().unwrap_or_else(|e| e.into_inner()) = None;
    }
}

fn fixture_key() -> Key {
    Key::new(
        17,
        ChunkRequest {
            seed: 42,
            chunk_x: -32,
            chunk_z: 0,
            min_y: 0,
            height: 256,
            base_height: 64.,
            amplitude: 48.,
            frequency: 0.008,
            reserved: 1,
        },
        3,
        -2,
        1,
    )
}
fn fixture_output() -> Arc<Output> {
    let writes = vec![WorldBlock {
        root_order: 2,
        x: -16,
        y: 20,
        z: 0,
        material: 4,
        upper: 0,
        role: FEATURE,
        tick: 0,
    }];
    Arc::new(Output {
        writes,
        columns: Map::from_iter([((-16, 0), vec![0])]).into(),
        metadata: None,
    })
}
#[test]
fn blob_inventory_distinguishes_pending_live_dead_and_failed_without_pinning() {
    let session = Arc::new(Session::default());
    let a = session.begin(fixture_key(), 0).unwrap();
    let b = session.begin(fixture_key(), 1).unwrap();
    let output = fixture_output();
    a.complete(&output);
    assert_eq!(Arc::strong_count(&output), 1);
    let c = session.begin(fixture_key(), 2).unwrap();
    b.complete(&fixture_output());
    c.complete(&fixture_output());
    assert!(Arc::try_unwrap(output).is_ok());
    let d = session.begin(fixture_key(), 3).unwrap();
    d.complete(&fixture_output());
    drop(session.begin(fixture_key(), 4));
    let report = session.report();
    let counts = &report["roots"]["3"];
    assert_eq!(counts["builds"], 5);
    assert_eq!(counts["successes"], 4);
    assert_eq!(counts["failures"], 1);
    assert_eq!(counts["completed_live_at_demand"], 1);
    assert_eq!(counts["completed_dead_at_demand"], 2);
    assert_eq!(counts["other_in_flight_at_demand"], 2);
    assert_eq!(counts["compared_outputs"], 3);
    assert_eq!(counts["mismatched_outputs"], 0);
    assert_eq!(report["pending_builds"], 0);
    assert_eq!(report["live_handles_at_wave_end"], 0);
}
#[test]
fn blob_inventory_compares_all_write_fields_order_and_complete_column_indexes() {
    let original = fixture_output();
    let snapshot = Snapshot::from(&original);
    for field in 0..9 {
        let mut altered = Snapshot::from(&original);
        let b = &mut altered.writes[0];
        match field {
            0 => b.root_order += 1,
            1 => b.x += 1,
            2 => b.y += 1,
            3 => b.z += 1,
            4 => b.material += 1,
            5 => b.upper += 1,
            6 => b.role += 1,
            7 => b.tick += 1,
            _ => altered.columns.get_mut(&(-16, 0)).unwrap().push(0),
        }
        assert!(snapshot != altered);
    }
    let mut invalid = Arc::try_unwrap(fixture_output()).unwrap_or_else(|_| unreachable!());
    let output_columns::Columns::Ordered(columns) = &mut invalid.columns else { unreachable!() };
    columns.get_mut(&(-16, 0)).unwrap().push(0);
    assert!(!Snapshot::index_valid(&invalid));
    let session = Arc::new(Session::default());
    session.begin(fixture_key(), 0).unwrap().complete(&original);
    session
        .begin(fixture_key(), 1)
        .unwrap()
        .complete(&Arc::new(invalid));
    assert_eq!(session.report()["roots"]["3"]["mismatched_outputs"], 1);
    assert_eq!(
        session.report()["roots"]["3"]["invalid_indexes_or_metadata"],
        1
    );
}
#[test]
fn blob_inventory_keys_isolate_engine_and_every_generation_input() {
    let key = fixture_key();
    let k = key.request;
    let variants = [
        crate::CacheKey {
            seed: k.seed + 1,
            ..k
        },
        crate::CacheKey { x: k.x + 1, ..k },
        crate::CacheKey { z: k.z + 1, ..k },
        crate::CacheKey {
            min_y: k.min_y + 1,
            ..k
        },
        crate::CacheKey {
            height: k.height + 1,
            ..k
        },
        crate::CacheKey {
            base: k.base + 1,
            ..k
        },
        crate::CacheKey {
            amplitude: k.amplitude + 1,
            ..k
        },
        crate::CacheKey {
            frequency: k.frequency + 1,
            ..k
        },
        crate::CacheKey {
            profile: k.profile + 1,
            ..k
        },
    ];
    assert!(Key { engine: 18, ..key } != key);
    assert!(Key { root: 4, ..key } != key);
    for request in variants {
        assert!(Key { request, ..key } != key);
    }
    let session = Arc::new(Session::default());
    session.state.lock().unwrap().snapshot_bytes = MAX_SNAPSHOT_BYTES;
    session.begin(key, 0).unwrap().complete(&fixture_output());
    assert_eq!(session.report()["roots"]["3"]["skipped_snapshots"], 1);
    assert_eq!(session.report()["pending_builds"], 0);
}

#[test]
#[ignore = "GPU inventory requires RETINA_TERRAIN_REUSE_PROFILES and RETINA_BLOB_OUTPUT"]
fn exact_blob_output_overlap_inventory() {
    use std::{fs, path::PathBuf, time::Instant};
    let profiles = PathBuf::from(std::env::var("RETINA_TERRAIN_REUSE_PROFILES").unwrap());
    let output = PathBuf::from(std::env::var("RETINA_BLOB_OUTPUT").unwrap());
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
        let id = engine.register_reference_profile(&bytes).unwrap();
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
        let dir = output.join(name);
        fs::create_dir(&dir).unwrap();
        let generate = |dir: &std::path::Path, label: &str, x: i32, z: i32| {
            let before = engine.pipeline.program_snapshot(id);
            let start = Instant::now();
            let result = crate::region::generate_region(
                &engine,
                ChunkRequest {
                    chunk_x: x * 32,
                    chunk_z: z * 32,
                    ..request
                },
                &dir.join(format!("{label}.mca")),
                0,
                biome,
            )
            .unwrap();
            assert_eq!(result.generated, 1024);
            assert_eq!(result.preserved, 0);
            json!({"name":label,"x":x,"z":z,"instrumented_ms":start.elapsed().as_secs_f64()*1000.,
                "program_before":before,"program_after":engine.pipeline.program_snapshot(id)})
        };
        let warmups: Vec<_> = (0..5)
            .map(|i| generate(&dir, &format!("warm{i}"), 8 + i, 8))
            .collect();
        let mut modes = Vec::new();
        // Each mode has twenty regions; reset copied evidence every <=8 jobs.
        // Serial uses pairs so it can distinguish dead completed duplicates.
        for callers in [1, 2, 4, 8] {
            let mode = dir.join(format!("callers{callers}"));
            fs::create_dir(&mode).unwrap();
            let width = if callers == 1 { 2 } else { callers };
            let mut waves = Vec::new();
            for start in (0..20usize).step_by(width) {
                let installed = Installed::start();
                let ids: Vec<_> = (start..(start + width).min(20)).collect();
                // Reverse alternate modes while preserving names/coordinates.
                let ids: Vec<_> = if callers == 4 {
                    ids.into_iter().map(|i| 19 - i).collect()
                } else {
                    ids
                };
                let rows = if callers == 1 {
                    ids.iter()
                        .map(|&i| {
                            generate(
                                &mode,
                                &i.to_string(),
                                -2 + (i % 5) as i32,
                                -2 + (i / 5) as i32,
                            )
                        })
                        .collect::<Vec<_>>()
                } else {
                    let barrier = std::sync::Barrier::new(ids.len());
                    std::thread::scope(|scope| {
                        let handles: Vec<_> = ids
                            .iter()
                            .map(|&i| {
                                let barrier = &barrier;
                                let generate = &generate;
                                let mode = &mode;
                                scope.spawn(move || {
                                    barrier.wait();
                                    generate(
                                        mode,
                                        &i.to_string(),
                                        -2 + (i % 5) as i32,
                                        -2 + (i / 5) as i32,
                                    )
                                })
                            })
                            .collect();
                        handles
                            .into_iter()
                            .map(|h| h.join().unwrap())
                            .collect::<Vec<_>>()
                    })
                };
                let report = installed.0.report();
                fs::write(
                    mode.join(format!("wave-{start}.json")),
                    serde_json::to_vec_pretty(&json!({"rows":rows,"inventory":report})).unwrap(),
                )
                .unwrap();
                assert_eq!(report["pending_builds"], 0);
                assert_eq!(report["live_handles_at_wave_end"], 0);
                for counts in report["roots"].as_object().unwrap().values() {
                    for key in [
                        "failures",
                        "mismatched_outputs",
                        "invalid_indexes_or_metadata",
                    ] {
                        assert_eq!(counts[key], 0, "{name} callers={callers} {key}");
                    }
                }
                drop(installed);
                waves.push(json!({"rows":rows,"inventory":report}));
            }
            println!(
                "blob output inventory {name}: callers={callers} waves={}",
                waves.len()
            );
            modes.push(json!({"callers":callers,"waves":waves}));
        }
        drop(engine);
        cases.push(json!({"case":name,"profile":file,"seed":seed,"warmups":warmups,"modes":modes}));
        fs::write(output.join("report.json"),serde_json::to_vec_pretty(&json!({
            "scope":"Only actual parsed ReplaceBlobs roots. Observe only original memo initializers, publish after successful memo completion, compare ordered writes fieldwise and complete per-column index lists. Keys include engine identity and full seed/profile/height/settings/anchor/root context. No reuse, no waits or errors shared. Copied snapshots and non-upgraded weak handles reset every <=8 jobs; they do not keep any Output strong owner alive. Snapshot limit bounds logical vector/key bytes, excluding allocator/tree/control-block overhead. Live-at-demand samples weak strong_count after acquiring the observer lock and is not an atomic reuse guarantee. Instrumentation changes scheduling; counts describe this observed run, not ordinary hit rates or saved wall time. Serial pairs,2/4/8-caller waves; five warmups per profile and twenty complete lit regions per mode. Different roots/in-flight/live categories may overlap and cannot be summed as saved work. Only MCA timestamps/compression representation may be excluded in external complete-NBT comparison.",
            "timing_acceptance":false,"cases":cases})).unwrap()).unwrap();
    }
}
