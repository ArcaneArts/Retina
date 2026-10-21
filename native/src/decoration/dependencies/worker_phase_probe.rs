//! Test-only root phases and dispatched-task intervals. Occupied workers may be
//! blocked or preempted; these intervals are not measurements of running CPUs.
use super::*;
use serde_json::{Value, json};
use std::{cell::RefCell, time::Instant};

static ACTIVE: Mutex<Option<Arc<Session>>> = Mutex::new(None);
static NEXT: AtomicU64 = AtomicU64::new(1);
const PHASES: [(&str, &str, &str); 5] = [
    ("queue", "start", "dispatch_enter"),
    ("enumeration", "dispatch_enter", "evaluation_begin"),
    ("evaluation", "evaluation_begin", "evaluation_end"),
    ("publication", "evaluation_end", "publication_end"),
    ("return_handoff", "publication_end", "returned"),
];

#[cfg(any(target_os = "linux", target_os = "macos"))]
fn process_cpu_ns() -> Option<u64> {
    let mut value = std::mem::MaybeUninit::<libc::timespec>::uninit();
    // SAFETY: the clock writes a timespec to a properly aligned live allocation;
    // the result is read only after the API reports successful initialization.
    if unsafe { libc::clock_gettime(libc::CLOCK_PROCESS_CPUTIME_ID, value.as_mut_ptr()) } != 0 {
        return None;
    }
    let value = unsafe { value.assume_init() };
    let seconds = u64::try_from(value.tv_sec).ok()?;
    let nanos = u64::try_from(value.tv_nsec).ok()?;
    (nanos < 1_000_000_000)
        .then(|| seconds.checked_mul(1_000_000_000)?.checked_add(nanos))
        .flatten()
}
#[cfg(not(any(target_os = "linux", target_os = "macos")))]
fn process_cpu_ns() -> Option<u64> {
    None
}

#[derive(Clone, Copy)]
struct Stamp {
    wall: u64,
    cpu: Option<u64>,
}
fn interval(a: Stamp, b: Stamp) -> Value {
    let wall = b.wall.checked_sub(a.wall);
    let cpu = a.cpu.zip(b.cpu).and_then(|(a, b)| b.checked_sub(a));
    json!({"wall_ns":wall,"process_cpu_ns":cpu,
        "process_cpu_time_over_wall":wall.filter(|&w| w>0).zip(cpu).map(|(w,c)| c as f64/w as f64)})
}
#[derive(Clone)]
struct Event {
    at: [i32; 2],
    start: u64,
    end: u64,
    success: bool,
}
struct Worker {
    id: String,
    events: Mutex<Vec<Event>>,
}
#[derive(Default)]
struct Local {
    token: u64,
    worker: Option<Arc<Worker>>,
}
impl Local {
    fn bind(&mut self, root: &Root) -> Arc<Worker> {
        if self.token != root.token {
            let worker = Arc::new(Worker {
                id: format!("{:?}", std::thread::current().id()),
                events: Mutex::new(Vec::new()),
            });
            root.workers.lock().unwrap().push(worker.clone());
            self.worker = Some(worker);
            self.token = root.token;
        }
        self.worker.as_ref().unwrap().clone()
    }
}
thread_local! { static LOCAL: RefCell<Local> = RefCell::new(Local::default()); }
#[derive(Default)]
struct Session {
    roots: Mutex<Vec<Value>>,
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
}
impl Drop for Installed {
    fn drop(&mut self) {
        *ACTIVE.lock().unwrap_or_else(|e| e.into_inner()) = None;
    }
}
struct State {
    marks: Vec<(&'static str, Stamp)>,
    target_count: Option<usize>,
}
struct Root {
    token: u64,
    origin: Instant,
    metadata: Value,
    state: Mutex<State>,
    workers: Mutex<Vec<Arc<Worker>>>,
    session: Arc<Session>,
}
impl Root {
    fn new(metadata: Value, session: Arc<Session>) -> Self {
        let origin = Instant::now();
        Self {
            token: NEXT.fetch_add(1, Ordering::Relaxed),
            origin,
            metadata,
            state: Mutex::new(State {
                marks: vec![(
                    "start",
                    Stamp {
                        wall: 0,
                        cpu: process_cpu_ns(),
                    },
                )],
                target_count: None,
            }),
            workers: Mutex::new(Vec::new()),
            session,
        }
    }
    fn stamp(&self) -> Stamp {
        let wall = self.origin.elapsed().as_nanos() as u64;
        Stamp {
            wall,
            cpu: process_cpu_ns(),
        }
    }
    fn mark(&self, name: &'static str) {
        let stamp = self.stamp();
        self.state.lock().unwrap().marks.push((name, stamp));
    }
}
pub(super) struct Trace(Option<Root>);
impl Trace {
    pub(super) fn start(planner: &Planner<'_>, index: usize) -> Self {
        let Some(session) = ACTIVE.lock().unwrap().clone() else {
            return Self(None);
        };
        let root = &planner.roots[index];
        let names: Vec<_> = root
            .ids
            .iter()
            .map(|&id| planner.profile.decorations[id as usize].source.as_str())
            .collect();
        Self(Some(Root::new(
            json!({"seed":planner.request.seed,
            "chunk_x":planner.request.chunk_x,"chunk_z":planner.request.chunk_z,
            "min_y":planner.request.min_y,"height":planner.request.height,
            "profile":planner.request.reserved,"root":index,"root_count":planner.roots.len(),
            "sources":names,"structure_definition":root.definition}),
            session,
        )))
    }
    pub(super) fn mark(&self, name: &'static str) {
        if let Some(root) = &self.0 {
            root.mark(name);
        }
    }
    pub(super) fn evaluation_begin(&self, targets: usize) {
        if let Some(root) = &self.0 {
            let stamp = root.stamp();
            let mut state = root.state.lock().unwrap();
            state.target_count = Some(targets);
            state.marks.push(("evaluation_begin", stamp));
        }
    }
    pub(super) fn task(&self, at: [i32; 2]) -> Task {
        Task(self.0.as_ref().map(|root| {
            let worker = LOCAL.with(|local| local.borrow_mut().bind(root));
            // Registration and the final event push are outside the task span.
            (
                worker,
                root.origin,
                at,
                root.origin.elapsed().as_nanos() as u64,
            )
        }))
    }
    pub(super) fn finish(self, success: bool) {
        let Some(root) = self.0 else {
            return;
        };
        root.mark("returned");
        let analysis_start = Instant::now();
        let state = root.state.into_inner().unwrap();
        let get = |name| {
            state
                .marks
                .iter()
                .find(|(n, _)| *n == name)
                .map(|(_, s)| *s)
        };
        let phases: BTreeMap<_, _> = PHASES
            .into_iter()
            .map(|(name, a, b)| (name, get(a).zip(get(b)).map(|(a, b)| interval(a, b))))
            .collect();
        let mut by_worker = BTreeMap::<String, Vec<Event>>::new();
        for worker in root.workers.into_inner().unwrap() {
            // Empty TLS-held collectors now: no task buffers survive a root.
            let events = std::mem::take(&mut *worker.events.lock().unwrap());
            by_worker
                .entry(worker.id.clone())
                .or_default()
                .extend(events);
        }
        let workers: Vec<_> = by_worker.into_iter().collect();
        let task_count: usize = workers.iter().map(|(_, v)| v.len()).sum();
        let failed: usize = workers
            .iter()
            .map(|(_, v)| v.iter().filter(|e| !e.success).count())
            .sum();
        let occupied = get("evaluation_begin")
            .zip(get("evaluation_end"))
            .map(|(a, b)| occupancy(&workers, [a.wall, b.wall]));
        let marks: BTreeMap<_, _> = state
            .marks
            .iter()
            .map(|(name, s)| (*name, json!({"wall_ns":s.wall,"process_cpu_ns":s.cpu})))
            .collect();
        root.session.roots.lock().unwrap().push(json!({"metadata":root.metadata,
            "success":success,"target_count":state.target_count,"task_count":task_count,"failed_tasks":failed,
            "phase_stamps":marks,"phases":phases,"occupancy":occupied,
            "observer_summary_wall_ns":analysis_start.elapsed().as_nanos() as u64}));
    }
}
pub(super) struct Task(Option<(Arc<Worker>, Instant, [i32; 2], u64)>);
impl Task {
    pub(super) fn finish(self, success: bool) {
        if let Some((worker, origin, at, start)) = self.0 {
            let end = origin.elapsed().as_nanos() as u64;
            worker.events.lock().unwrap().push(Event {
                at,
                start,
                end,
                success,
            });
        }
    }
}

fn union(mut ranges: Vec<(u64, u64)>) -> Vec<(u64, u64)> {
    ranges.retain(|&(a, b)| a < b);
    ranges.sort_unstable();
    let mut result: Vec<(u64, u64)> = Vec::new();
    for (start, end) in ranges {
        if let Some(last) = result.last_mut().filter(|last| start <= last.1) {
            last.1 = last.1.max(end);
        } else {
            result.push((start, end));
        }
    }
    result
}
fn percentile(values: &[u64], fraction: f64) -> Option<f64> {
    if values.is_empty() {
        return None;
    }
    let point = (values.len() - 1) as f64 * fraction;
    let lo = point.floor() as usize;
    let hi = point.ceil() as usize;
    Some(values[lo] as f64 + (values[hi] - values[lo]) as f64 * (point - lo as f64))
}
fn occupancy(workers: &[(String, Vec<Event>)], window: [u64; 2]) -> Value {
    let invalid = workers
        .iter()
        .flat_map(|(_, v)| v)
        .filter(|e| e.start < window[0] || e.end > window[1] || e.end < e.start)
        .count();
    if window[1] < window[0] || invalid > 0 {
        // Preserve anomalous evidence rather than silently clipping intervals.
        return json!({"valid":false,"window":window,"outside_or_reversed_events":invalid});
    }
    let mut changes = BTreeMap::<u64, i64>::from([(window[0], 0), (window[1], 0)]);
    let mut per_worker = Vec::new();
    let mut finishes = Vec::new();
    let mut longest = Vec::new();
    let mut sum_task_ns = 0;
    for (id, events) in workers {
        let ranges = union(events.iter().map(|e| (e.start, e.end)).collect());
        let occupied_ns: u64 = ranges.iter().map(|(a, b)| b - a).sum();
        for (a, b) in ranges {
            *changes.entry(a).or_default() += 1;
            *changes.entry(b).or_default() -= 1;
        }
        per_worker.push(json!({"worker":id,"tasks":events.len(),"in_flight_union_ns":occupied_ns}));
        for e in events {
            sum_task_ns += e.end - e.start;
            finishes.push(e.end - window[0]);
            longest.push((e.end - e.start, id, e));
        }
    }
    let mut histogram = BTreeMap::<i64, u64>::new();
    let (mut count, mut peak, mut previous) = (0, 0, window[0]);
    for (at, delta) in changes {
        *histogram.entry(count).or_default() += at - previous;
        count += delta;
        assert!(count >= 0);
        peak = peak.max(count);
        previous = at;
    }
    assert_eq!(count, 0);
    let wall = window[1] - window[0];
    let occupied_ns: u64 = histogram.iter().map(|(&n, &time)| n as u64 * time).sum();
    finishes.sort_unstable();
    longest.sort_unstable_by_key(|(duration, _, _)| std::cmp::Reverse(*duration));
    let longest: Vec<_> = longest.into_iter().take(8).map(|(duration,id,e)|
        json!({"worker":id,"at":e.at,"start_ns":e.start,"end_ns":e.end,"duration_ns":duration,"success":e.success})).collect();
    let p95 = percentile(&finishes, 0.95);
    json!({"valid":true,"window":window,"outside_or_reversed_events":0,
        "registered_workers":workers.len(),"worker_in_flight_histogram_ns":histogram,
        "peak_workers_in_flight":peak,"worker_union_sum_ns":occupied_ns,"task_interval_sum_ns":sum_task_ns,
        "mean_workers_in_flight":(wall>0).then(|| occupied_ns as f64/wall as f64),
        "per_worker":per_worker,"completion_p50_ns":percentile(&finishes,0.5),"completion_p95_ns":p95,
        "last_completion_ns":finishes.last(),"tail_after_p95_completion_ns":p95.zip(finishes.last()).map(|(p,last)| *last as f64-p),
        "longest_tasks":longest})
}

#[test]
fn worker_occupancy_unions_nested_intervals_and_counts_idle_gaps() {
    let event = |a, b| Event {
        at: [0, 0],
        start: a,
        end: b,
        success: true,
    };
    let workers = vec![
        (
            "a".into(),
            vec![event(2, 8), event(3, 5), event(8, 10), event(10, 10)],
        ),
        ("b".into(), vec![event(4, 6), event(12, 14)]),
    ];
    let result = occupancy(&workers, [0, 16]);
    assert_eq!(result["valid"], true);
    assert_eq!(
        result["worker_in_flight_histogram_ns"],
        json!({"0":6,"1":8,"2":2})
    );
    assert_eq!(result["worker_union_sum_ns"], 12);
    assert_eq!(result["task_interval_sum_ns"], 14);
    assert_eq!(result["peak_workers_in_flight"], 2);
    assert_eq!(result["mean_workers_in_flight"], 0.75);
    assert_eq!(result["last_completion_ns"], 14);
    assert_eq!(
        occupancy(&[], [2, 2])["mean_workers_in_flight"],
        Value::Null
    );
    assert_eq!(
        occupancy(&[("bad".into(), vec![event(0, 5)])], [1, 6])["valid"],
        false
    );
    assert_eq!(
        occupancy(&[("bad".into(), vec![event(5, 3)])], [0, 6])["valid"],
        false
    );
}
#[test]
fn worker_phase_missing_cpu_is_not_zero_and_collectors_rebind() {
    assert_eq!(
        interval(
            Stamp { wall: 1, cpu: None },
            Stamp {
                wall: 5,
                cpu: Some(9)
            }
        )["process_cpu_ns"],
        Value::Null
    );
    assert_eq!(
        interval(
            Stamp {
                wall: 1,
                cpu: Some(2)
            },
            Stamp {
                wall: 5,
                cpu: Some(10)
            }
        )["process_cpu_time_over_wall"],
        2.0
    );
    let session = Arc::new(Session::default());
    let first = Root::new(json!({}), session.clone());
    let second = Root::new(json!({}), session);
    let mut local = Local::default();
    let a = local.bind(&first);
    let b = local.bind(&first);
    assert!(Arc::ptr_eq(&a, &b));
    let c = local.bind(&second);
    assert!(!Arc::ptr_eq(&a, &c));
    a.events.lock().unwrap().push(Event {
        at: [0, 0],
        start: 0,
        end: 1,
        success: true,
    });
    assert!(c.events.lock().unwrap().is_empty());
    let weak = Arc::downgrade(&a);
    drop(a);
    drop(b);
    drop(first);
    assert!(weak.upgrade().is_none());
}
#[test]
fn worker_phase_records_failed_roots_without_fabricating_missing_phases() {
    let session = Arc::new(Session::default());
    let trace = Trace(Some(Root::new(json!({}), session.clone())));
    trace.mark("dispatch_enter");
    trace.evaluation_begin(1);
    let task = trace.task([2, 3]);
    task.finish(false);
    trace.finish(false);
    let roots = session.roots.lock().unwrap();
    let root = &roots[0];
    assert_eq!(root["success"], false);
    assert_eq!(root["failed_tasks"], 1);
    assert_eq!(root["phases"]["evaluation"], Value::Null);
    assert_eq!(root["occupancy"], Value::Null);
    LOCAL.with(|l| {
        assert!(
            l.borrow()
                .worker
                .as_ref()
                .unwrap()
                .events
                .lock()
                .unwrap()
                .is_empty()
        )
    });
}

#[test]
fn worker_phase_nested_roots_rebind_without_double_counting_one_worker() {
    let session = Arc::new(Session::default());
    let a = Trace(Some(Root::new(json!({"name":"a"}), session.clone())));
    a.mark("dispatch_enter");
    a.evaluation_begin(2);
    let first = a.task([0, 0]);
    let b = Trace(Some(Root::new(json!({"name":"b"}), session.clone())));
    b.mark("dispatch_enter");
    b.evaluation_begin(1);
    b.task([1, 1]).finish(true);
    b.mark("evaluation_end");
    b.mark("publication_end");
    b.finish(true);
    a.task([2, 2]).finish(true);
    first.finish(true);
    a.mark("evaluation_end");
    a.mark("publication_end");
    a.finish(true);
    let roots = session.roots.lock().unwrap();
    assert_eq!(roots.len(), 2);
    assert_eq!(roots[1]["task_count"], 2);
    assert_eq!(roots[1]["occupancy"]["registered_workers"], 1);
    assert_eq!(roots[1]["occupancy"]["peak_workers_in_flight"], 1);
    assert!(
        roots[1]["occupancy"]["task_interval_sum_ns"]
            .as_u64()
            .unwrap()
            >= roots[1]["occupancy"]["worker_union_sum_ns"]
                .as_u64()
                .unwrap()
    );
}

#[test]
#[ignore = "GPU inventory requires RETINA_TERRAIN_REUSE_PROFILES and RETINA_WORKER_PHASE_OUTPUT"]
fn exact_worker_phase_inventory() {
    use std::{fs, path::PathBuf, time::Instant};
    let profiles = PathBuf::from(std::env::var("RETINA_TERRAIN_REUSE_PROFILES").unwrap());
    let output = PathBuf::from(std::env::var("RETINA_WORKER_PHASE_OUTPUT").unwrap());
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
        let roots = std::mem::take(&mut *installed.0.roots.lock().unwrap());
        let mut regions_seen = BTreeMap::<(i64, i64), Vec<&Value>>::new();
        for root in &roots {
            assert_eq!(root["success"], true);
            assert_eq!(root["failed_tasks"], 0);
            assert_eq!(root["target_count"], root["task_count"]);
            assert_eq!(root["occupancy"]["valid"], true);
            let histogram = root["occupancy"]["worker_in_flight_histogram_ns"]
                .as_object()
                .unwrap();
            assert_eq!(
                histogram.values().map(|v| v.as_u64().unwrap()).sum::<u64>(),
                root["phases"]["evaluation"]["wall_ns"].as_u64().unwrap()
            );
            let m = &root["metadata"];
            regions_seen
                .entry((
                    m["chunk_x"].as_i64().unwrap(),
                    m["chunk_z"].as_i64().unwrap(),
                ))
                .or_default()
                .push(root);
        }
        assert_eq!(regions_seen.len(), if name == "overworld" { 0 } else { 20 });
        for region in regions_seen.values() {
            let count = region[0]["metadata"]["root_count"].as_u64().unwrap() as usize;
            let mut ids: Vec<_> = region
                .iter()
                .map(|v| v["metadata"]["root"].as_u64().unwrap() as usize)
                .collect();
            ids.sort_unstable();
            assert_eq!(ids, (0..count).collect::<Vec<_>>());
        }
        drop(regions_seen);
        let summary = json!({"roots":roots});
        drop(installed);
        println!(
            "worker phase inventory {name}: roots={}",
            summary["roots"].as_array().unwrap().len()
        );
        cases.push(json!({"case":name,"seed":seed,"profile":file,
            "warmups":warmups,"regions":regions,"summary":summary}));
        fs::write(output.join("report.json"), serde_json::to_vec_pretty(&json!({
            "scope":"Test-only per-root queue, enumeration, joined anchor evaluation, publication and return-handoff intervals, with optional process CPU deltas. CPU includes native service/shader threads and observer overhead; it is not CPU utilization or task-only work. Top-level dispatched task spans include all nested work, waits and preemption. Per-worker collectors register once per root token; no shared hot collector lock or lock is held across generation. After the root joins, task intervals are unioned by physical thread before integrating workers with a task in flight; this is not workers actually running on CPU. Interval sums are not elapsed phase time. Missing phase/CPU data remains null; anomalous out-of-window events invalidate occupancy rather than being silently clipped. Only scalar metadata, phase/task summaries and top-eight long tasks persist. Collector buffers are emptied at joined root completion and retain no planner/state/GPU owners. Observer summary time is reported separately and remains inside whole-region elapsed time. Five warmups precede twenty complete lit regions per case; actual program snapshots are recorded. Normal builds exclude all observer code and use the original generation algorithm.",
            "cases":cases,"timing_acceptance":false,
        })).unwrap()).unwrap();
    }
}
