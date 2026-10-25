//! Test-only root phases and dispatched-task intervals. Occupied workers may be
//! blocked or preempted; these intervals are not measurements of running CPUs.
use super::*;
use serde_json::{Value, json};
use std::{cell::RefCell, time::Instant};
mod joins;
mod cpu;
pub(crate) fn thread_cpu_ns() -> Option<u64> { cpu::now() }
mod anchor_cpu;
mod request_batch;
pub(super) use anchor_cpu::Span as AnchorCpu;
pub(super) use anchor_cpu::column_load::{Span as ColumnLoadCpu, Path as ColumnLoadPath};
pub(super) mod work;
pub(crate) use joins::TerrainSpan;
pub(super) use joins::{Candidate as Join, Key as JoinKey, LookupCall};

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
    cpu: [Option<u64>; 2],
}
struct Worker {
    id: String,
    events: Mutex<Vec<Event>>,
    joins: Mutex<joins::Buffer>,
    terrain: Mutex<joins::Buffer>,
    lookup: Mutex<joins::Buffer>,
    active: AtomicU64,
    closed: std::sync::atomic::AtomicBool,
    aggregate: bool,
    root_count: usize,
    work: Mutex<work::Buffer>,
    anchor_cpu_enabled: bool,
    column_load_salt: Option<u64>,
    anchor_cpu: Mutex<anchor_cpu::Buffer>,
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
                joins: Mutex::new(joins::Buffer::default()),
                terrain: Mutex::new(joins::Buffer::default()),
                lookup: Mutex::new(joins::Buffer::default()),
                active: AtomicU64::new(0),
                aggregate: root.session.aggregate,
                root_count: root.metadata["root_count"].as_u64().unwrap_or(0) as usize,
                work: Mutex::new(work::Buffer::default()),
                anchor_cpu_enabled: root.session.anchor_cpu,
                column_load_salt: root.session.column_load_salt,
                anchor_cpu: Mutex::new(anchor_cpu::Buffer::default()),
                closed: std::sync::atomic::AtomicBool::new(false),
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
    epoch: std::sync::OnceLock<Instant>,
    aggregate: bool,
    anchor_cpu: bool,
    column_load_salt: Option<u64>,
    roots: Mutex<Vec<Value>>,
}
struct Installed(Arc<Session>);
impl Installed {
    fn start() -> Self { Self::with_aggregate(false) }
    fn with_aggregate(aggregate: bool) -> Self {
        Self::with_anchor_cpu(aggregate, false)
    }
    fn with_anchor_cpu(aggregate: bool, anchor_cpu: bool) -> Self {
        Self::with_column_load(aggregate, anchor_cpu, None)
    }
    fn with_column_load(aggregate: bool, anchor_cpu: bool, column_load_salt: Option<u64>) -> Self {
        assert!(anchor_cpu || column_load_salt.is_none());
        let mut active = ACTIVE.lock().unwrap();
        assert!(active.is_none());
        let session = Arc::new(Session { aggregate, anchor_cpu, column_load_salt, ..Session::default() });
        *active = Some(session.clone());
        Self(session)
    }
}
impl Drop for Installed {
    fn drop(&mut self) {
        *ACTIVE.lock().unwrap_or_else(|e| e.into_inner()) = None;
    }
}
#[derive(Default)]
struct State {
    marks: Vec<(&'static str, Stamp)>,
    target_count: Option<usize>,
}
impl Drop for Root {
    fn drop(&mut self) {
        // TLS may retain the last worker collector after a failed/unwound root.
        // Release its buffers on every path, including a failed summary.
        for worker in self.workers.get_mut().unwrap_or_else(|e| e.into_inner()) {
            worker.closed.store(true, Ordering::Relaxed);
            std::mem::take(&mut *worker.events.lock().unwrap_or_else(|e| e.into_inner()));
            std::mem::take(&mut *worker.joins.lock().unwrap_or_else(|e| e.into_inner()));
            std::mem::take(&mut *worker.terrain.lock().unwrap_or_else(|e| e.into_inner()));
            std::mem::take(&mut *worker.lookup.lock().unwrap_or_else(|e| e.into_inner()));
            std::mem::take(&mut *worker.work.lock().unwrap_or_else(|e| e.into_inner()));
            std::mem::take(&mut *worker.anchor_cpu.lock().unwrap_or_else(|e| e.into_inner()));
        }
    }
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
        session.epoch.get_or_init(Instant::now);
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
    pub(super) fn start_continuation(planner: &Planner<'_>, index: usize) -> Self {
        let mut trace = Self::start(planner, index);
        if let Some(root) = &mut trace.0 {
            root.metadata["coordination"] = json!("worker_continuation");
        }
        trace
    }
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
                joins::Context::enter(root.token, root.origin, worker),
                at,
                root.origin.elapsed().as_nanos() as u64,
                cpu::now(),
            )
        }))
    }
    pub(super) fn finish(mut self, success: bool) {
        if let Some(root) = self.0.take() { Self::record(&root, success, false); }
    }
    fn record(root: &Root, success: bool, abandoned: bool) {
        root.mark("returned");
        let analysis_start = Instant::now();
        let state = std::mem::take(&mut *root.state.lock().unwrap());
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
        let mut by_join_worker = BTreeMap::<String, joins::Buffer>::new();
        let mut by_terrain_worker = BTreeMap::<String, joins::Buffer>::new();
        let mut by_lookup_worker = BTreeMap::<String, joins::Buffer>::new();
        let mut work = work::Buffer::default();
        let mut anchor_cpu = anchor_cpu::Buffer::default();
        let mut active_contexts = 0;
        for worker in root.workers.lock().unwrap().iter() {
            // Empty TLS-held collectors now: no task buffers survive a root.
            worker.closed.store(true, Ordering::Relaxed);
            active_contexts += worker.active.load(Ordering::Relaxed);
            work.extend(std::mem::take(&mut *worker.work.lock().unwrap()));
            anchor_cpu.extend(std::mem::take(&mut *worker.anchor_cpu.lock().unwrap()));
            let events = std::mem::take(&mut *worker.events.lock().unwrap());
            by_worker
                .entry(worker.id.clone())
                .or_default()
                .extend(events);
            by_join_worker.entry(worker.id.clone()).or_default()
                .extend(std::mem::take(&mut *worker.joins.lock().unwrap()));
            by_terrain_worker.entry(worker.id.clone()).or_default()
                .extend(std::mem::take(&mut *worker.terrain.lock().unwrap()));
            by_lookup_worker.entry(worker.id.clone()).or_default()
                .extend(std::mem::take(&mut *worker.lookup.lock().unwrap()));
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
        let context_errors: u64 = [&by_join_worker, &by_terrain_worker, &by_lookup_worker].into_iter()
            .flat_map(|v| v.values()).map(|v| v.context_errors).sum();
        let api_summary = |by_worker| if root.session.aggregate {
            json!({"enabled":false,"reason":"aggregate work mode does not capture API intervals"})
        } else { joins::summarize(&workers, by_worker,
            get("evaluation_begin").zip(get("evaluation_end")).map(|(a,b)| [a.wall,b.wall]), active_contexts) };
        let joined = api_summary(&by_join_worker);
        let terrain = api_summary(&by_terrain_worker);
        let lookup = api_summary(&by_lookup_worker);
        let thread_cpu = cpu::summarize(&workers);
        let mut anchor_cpu = if root.session.anchor_cpu { anchor_cpu.summary() }
            else { json!({"enabled":false,"reason":"anchor CPU observer not selected"}) };
        if root.session.anchor_cpu { anchor_cpu["column_load_salt"] = json!(root.session.column_load_salt); }
        if root.session.anchor_cpu && (active_contexts > 0 || context_errors > 0) {
            anchor_cpu::column_load::invalidate_json(&mut anchor_cpu["totals"]["column_load"]);
            anchor_cpu["totals"]["valid"] = json!(false);
            for key in ["inclusive_cpu_ns", "own_cpu_ns", "outermost_cpu_ns"] {
                anchor_cpu["totals"][key] = Value::Null;
            }
        }
        let marks: BTreeMap<_, _> = state
            .marks
            .iter()
            .map(|(name, s)| (*name, json!({"wall_ns":s.wall,"process_cpu_ns":s.cpu})))
            .collect();
        root.session.roots.lock().unwrap().push(json!({"metadata":root.metadata,
            "success":success,"abandoned":abandoned,"target_count":state.target_count,"task_count":task_count,"failed_tasks":failed,
            "capture_mode":if root.session.aggregate { "aggregate" } else { "detailed" },
            "context_validation":{"valid":active_contexts==0 && context_errors==0,"active":active_contexts,"errors":context_errors},
            "dependency_work":if root.session.aggregate {work.summary()} else {json!({"enabled":false,"reason":"detailed interval mode"})},
            "anchor_cpu":anchor_cpu,
            // Add this offset to root-relative event stamps to compare concurrent roots.
            "session_origin_ns":root.origin.duration_since(*root.session.epoch.get().unwrap()).as_nanos() as u64,
            "phase_stamps":marks,"phases":phases,"occupancy":occupied,
            "join_attribution":joined,"terrain_attribution":terrain,"lookup_attribution":lookup,"task_thread_cpu":thread_cpu,
            "observer_summary_wall_ns":analysis_start.elapsed().as_nanos() as u64}));
    }
}
impl Drop for Trace {
    fn drop(&mut self) {
        if let Some(root) = self.0.take() { Self::record(&root, false, true); }
    }
}
pub(super) struct Task(Option<(joins::Context, [i32; 2], u64, Option<u64>)>);
impl Task {
    pub(super) fn finish(mut self, success: bool) { self.record(success); }
    fn record(&mut self, success: bool) {
        if let Some((context, at, start, cpu_start)) = self.0.take() {
            let cpu_end = cpu::now();
            let end = context.origin.elapsed().as_nanos() as u64;
            if context.worker.closed.load(Ordering::Relaxed) { return; }
            context.worker.events.lock().unwrap().push(Event {
                at,
                start,
                end,
                success,
                cpu: [cpu_start, cpu_end],
            });
        }
    }
}
impl Drop for Task {
    fn drop(&mut self) { self.record(false); }
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
    let mut first_start = None::<u64>;
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
            let start = e.start - window[0];
            first_start = Some(first_start.map_or(start, |first| first.min(start)));
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
    let zero_task_ns = histogram.get(&0).copied().unwrap_or(0);
    // Include instantaneous events in the boundaries, even though they occupy
    // no time. With no events, neither boundary exists; do not invent one.
    let boundaries = first_start.zip(finishes.last().copied());
    let zero_task_intervals = match boundaries {
        Some((first, last)) => {
            let after = wall - last;
            json!({"before_first_task":first,
                "between_first_start_and_last_completion":zero_task_ns-first-after,
                "after_last_task":after,"no_task_events":0,"total":zero_task_ns})
        }
        None => json!({"before_first_task":null,
            "between_first_start_and_last_completion":null,
            "after_last_task":null,"no_task_events":wall,"total":zero_task_ns}),
    };
    longest.sort_unstable_by_key(|(duration, _, _)| std::cmp::Reverse(*duration));
    let longest: Vec<_> = longest.into_iter().take(8).map(|(duration,id,e)|
        json!({"worker":id,"at":e.at,"start_ns":e.start,"end_ns":e.end,"duration_ns":duration,"success":e.success,"thread_cpu":cpu::event(e)})).collect();
    let p95 = percentile(&finishes, 0.95);
    json!({"valid":true,"window":window,"outside_or_reversed_events":0,
        "registered_workers":workers.len(),"worker_in_flight_histogram_ns":histogram,
        "peak_workers_in_flight":peak,"worker_union_sum_ns":occupied_ns,"task_interval_sum_ns":sum_task_ns,
        "mean_workers_in_flight":(wall>0).then(|| occupied_ns as f64/wall as f64),
        "per_worker":per_worker,"completion_p50_ns":percentile(&finishes,0.5),"completion_p95_ns":p95,
        "first_start_ns":first_start,"zero_task_intervals_ns":zero_task_intervals,
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
        cpu: [None, None],
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
    assert_eq!(result["first_start_ns"], 2);
    assert_eq!(result["zero_task_intervals_ns"], json!({"before_first_task":2,
        "between_first_start_and_last_completion":2,"after_last_task":2,
        "no_task_events":0,"total":6}));
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
    assert_eq!(occupancy(&[], [6, 0])["valid"], false);
    assert_eq!(occupancy(&[("bad".into(), vec![event(2, 7)])], [0, 6])["valid"], false);
}

#[test]
fn worker_occupancy_distinguishes_absent_and_instantaneous_tasks() {
    for window in [[100, 120], [7, 7]] {
        let result = occupancy(&[("registered".into(), vec![])], window);
        assert_eq!(result["first_start_ns"], Value::Null);
        assert_eq!(result["last_completion_ns"], Value::Null);
        assert_eq!(result["zero_task_intervals_ns"], json!({"before_first_task":null,
            "between_first_start_and_last_completion":null,"after_last_task":null,
            "no_task_events":window[1]-window[0],"total":window[1]-window[0]}));
    }
    for (ranges, expected, first, last, peak) in [
        (vec![(115,115),(104,104)], [4,11,5,20], 4,15,0),
        (vec![(117,117),(106,109),(102,102)], [2,12,3,17], 2,17,1),
    ] {
        let events = ranges.into_iter().map(|(start,end)| Event {
            at:[0,0],start,end,success:true,cpu:[None,None],
        }).collect();
        let result = occupancy(&[("a".into(),events)], [100,120]);
        assert_eq!(result["first_start_ns"], first);
        assert_eq!(result["last_completion_ns"], last);
        assert_eq!(result["peak_workers_in_flight"], peak);
        assert_eq!(result["zero_task_intervals_ns"], json!({"before_first_task":expected[0],
            "between_first_start_and_last_completion":expected[1],"after_last_task":expected[2],
            "no_task_events":0,"total":expected[3]}));
    }
}

#[test]
fn worker_occupancy_gaps_match_discrete_time_oracle() {
    let window = [100,104];
    let intervals: Vec<_> = (100..=104).flat_map(|start| (start..=104).map(move |end| (start,end))).collect();
    // Exhaust all triples, including nested, touching, unsorted and zero-length
    // intervals. The oracle counts each time unit directly, without a sweep.
    for &a in &intervals {
        for &b in &intervals {
            for &c in &intervals {
                let event = |(start,end)| Event { at:[0,0],start,end,success:true,cpu:[None,None] };
                let workers = vec![("a".into(),vec![event(a),event(b)]),("b".into(),vec![event(c)])];
                let result = occupancy(&workers, window);
                let first = a.0.min(b.0).min(c.0);
                let last = a.1.max(b.1).max(c.1);
                let mut expected = [0u64;3];
                let mut histogram = [0u64;3];
                for t in window[0]..window[1] {
                    let count = workers.iter().filter(|(_,events)| events.iter().any(|e| e.start<=t && t<e.end)).count();
                    histogram[count] += 1;
                    if count == 0 { expected[if t<first {0} else if t>=last {2} else {1}] += 1; }
                }
                for (n, &value) in histogram.iter().enumerate() {
                    assert_eq!(result["worker_in_flight_histogram_ns"][n.to_string()].as_u64().unwrap_or(0), value);
                }
                assert_eq!(result["first_start_ns"], first-window[0]);
                assert_eq!(result["last_completion_ns"], last-window[0]);
                assert_eq!(result["zero_task_intervals_ns"], json!({"before_first_task":expected[0],
                    "between_first_start_and_last_completion":expected[1],"after_last_task":expected[2],
                    "no_task_events":0,"total":histogram[0]}));
            }
        }
    }
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
        cpu: [None, None],
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
fn exact_worker_phase_inventory() { worker_phase_inventory(false, false); }

#[test]
#[ignore = "GPU aggregate inventory requires RETINA_TERRAIN_REUSE_PROFILES and RETINA_WORKER_PHASE_OUTPUT"]
fn exact_dependency_work_inventory() { worker_phase_inventory(true, false); }

#[test]
#[ignore = "GPU anchor CPU inventory requires RETINA_TERRAIN_REUSE_PROFILES and RETINA_WORKER_PHASE_OUTPUT"]
fn exact_anchor_cpu_inventory() { worker_phase_inventory(true, true); }

#[test]
#[ignore = "GPU sampled column CPU inventory requires profile/output paths and RETINA_COLUMN_LOAD_SALT"]
fn exact_column_load_cpu_inventory() {
    let salt = std::env::var("RETINA_COLUMN_LOAD_SALT").unwrap().parse().unwrap();
    worker_phase_inventory_with_column_load(true, true, Some(salt));
}

fn worker_phase_inventory(aggregate: bool, anchor_cpu: bool) {
    worker_phase_inventory_with_column_load(aggregate, anchor_cpu, None);
}
fn worker_phase_inventory_with_column_load(aggregate: bool, anchor_cpu: bool, column_load_salt: Option<u64>) {
    use std::{fs, path::PathBuf, time::Instant};
    let profiles = PathBuf::from(std::env::var("RETINA_TERRAIN_REUSE_PROFILES").unwrap());
    let output = PathBuf::from(std::env::var("RETINA_WORKER_PHASE_OUTPUT").unwrap());
    fs::create_dir(&output).unwrap();
    let callers = std::env::var("RETINA_WORKER_PHASE_REQUESTS")
        .map(|v| v.parse::<usize>().expect("RETINA_WORKER_PHASE_REQUESTS must be an integer"))
        .unwrap_or(1);
    assert!([1, 2, 4, 8].contains(&callers), "supported request counts are 1, 2, 4, 8");
    let order = std::env::var("RETINA_WORKER_PHASE_REQUEST_ORDER").unwrap_or_else(|_| "forward".into());
    assert!(matches!(order.as_str(), "forward" | "reverse"), "unknown request order");
    let mut cases = Vec::new();
    for (name, file, seed) in [
        ("nether-main", "nether.json", 123456789),
        ("nether-second-seed", "nether.json", 42),
        ("nether-custom-bounds", "custom.json", 123456789),
        ("nether-direct-stem", "direct-stem.json", 42),
        ("overworld", "overworld.json", 123456789),
    ] {
        if std::env::var("RETINA_WORKER_PHASE_CASE").ok().is_some_and(|case| case != name) { continue }
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
        let installed = if anchor_cpu { Installed::with_column_load(aggregate, true, column_load_salt) }
            else if aggregate { Installed::with_aggregate(true) } else { Installed::start() };
        // Scoped callers share the engine, while Rayon keeps its existing
        // assembly-worker count. All callbacks join before inspecting roots.
        let regions: Vec<_> = request_batch::run(20, callers, order == "reverse", |i| {
            generate(&i.to_string(), -2 + (i % 5) as i32, -2 + (i / 5) as i32)
        }).into_iter().map(|sample| {
            let mut region = sample.value;
            region["request"] = json!({"index":sample.index,
                "queue_ns":sample.queue_ns,"callback_ns":sample.callback_ns,
                "completion_ns":sample.completion_ns});
            region
        }).collect();
        drop(engine);
        let roots = std::mem::take(&mut *installed.0.roots.lock().unwrap());
        let mut regions_seen = BTreeMap::<(i64, i64), Vec<&Value>>::new();
        for root in &roots {
            assert_eq!(root["success"], true);
            assert_eq!(root["abandoned"], false);
            assert_eq!(root["failed_tasks"], 0);
            assert_eq!(root["target_count"], root["task_count"]);
            assert_eq!(root["occupancy"]["valid"], true);
            assert_eq!(root["context_validation"]["valid"], true);
            assert_eq!(root["task_thread_cpu"]["reversed_tasks"], 0);
            if anchor_cpu {
                let cpu = &root["anchor_cpu"];
                assert_eq!(cpu["enabled"], true);
                assert_eq!(cpu["invalid_targets"], 0);
                assert_eq!(cpu["totals"]["started"], cpu["totals"]["finished"]);
                assert_eq!(cpu["totals"]["started"], root["dependency_work"]["totals"]["anchor_build"]);
                for field in ["reversed_clock_calls", "invalid_child_calls", "context_errors", "clock_bounds_errors", "abandoned", "panicked"] {
                    assert_eq!(cpu["totals"][field], 0);
                }
                assert_ne!(cpu["totals"]["valid"], false);
                let columns = &cpu["totals"]["column_load"];
                assert_ne!(columns["valid"], false);
                assert_eq!(columns["calls"], columns["finished"]);
                if column_load_salt.is_none() { assert_eq!(columns["sampled_anchors"], 0); }
            } else { assert_eq!(root["anchor_cpu"]["enabled"], false); }
            if aggregate {
                assert_eq!(root["dependency_work"]["valid"], true);
                assert_eq!(root["dependency_work"]["invalid_targets"], 0);
                for field in ["join_attribution", "terrain_attribution", "lookup_attribution"] {
                    assert_eq!(root[field]["enabled"], false);
                    assert_eq!(root[field]["events"], Value::Null);
                }
            } else {
                assert_eq!(root["join_attribution"]["valid"], true);
                assert_eq!(root["terrain_attribution"]["valid"], true);
                assert_eq!(root["lookup_attribution"]["valid"], true);
                assert_eq!(root["lookup_attribution"]["failed_events"], 0);
                assert_eq!(root["lookup_attribution"]["active_contexts_at_root_end"], 0);
                assert!(root["lookup_attribution"]["worker_join_union_sum_ns"].as_u64().unwrap()
                    <= root["occupancy"]["worker_union_sum_ns"].as_u64().unwrap());
                assert_eq!(root["terrain_attribution"]["failed_events"], 0);
                assert_eq!(root["terrain_attribution"]["active_contexts_at_root_end"], 0);
                assert_eq!(root["task_thread_cpu"]["reversed_tasks"], 0);
                assert!(root["terrain_attribution"]["worker_join_union_sum_ns"].as_u64().unwrap()
                    <= root["occupancy"]["worker_union_sum_ns"].as_u64().unwrap());
                assert_eq!(root["join_attribution"]["failed_events"], 0);
                assert_eq!(root["join_attribution"]["active_contexts_at_root_end"], 0);
                assert!(root["join_attribution"]["worker_join_union_sum_ns"].as_u64().unwrap()
                    <= root["occupancy"]["worker_union_sum_ns"].as_u64().unwrap());
            }
            let histogram = root["occupancy"]["worker_in_flight_histogram_ns"]
                .as_object()
                .unwrap();
            let gaps = &root["occupancy"]["zero_task_intervals_ns"];
            assert_eq!(gaps["total"], histogram["0"]);
            assert_eq!(["before_first_task", "between_first_start_and_last_completion",
                "after_last_task", "no_task_events"].into_iter()
                .filter_map(|key| gaps[key].as_u64()).sum::<u64>(), gaps["total"].as_u64().unwrap());
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
        if anchor_cpu && roots.iter().all(|v| v["anchor_cpu"]["totals"]["valid"] == true) {
            let sum = |key| roots.iter().map(|v| v["anchor_cpu"]["totals"][key].as_u64().unwrap()).sum::<u64>();
            assert_eq!(sum("own_cpu_ns"), sum("outermost_cpu_ns"));
        }
        let summary = json!({"roots":roots});
        drop(installed);
        println!(
            "worker phase inventory {name}: roots={}",
            summary["roots"].as_array().unwrap().len()
        );
        cases.push(json!({"case":name,"seed":seed,"profile":file,
            "warmups":warmups,"regions":regions,"summary":summary}));
        fs::write(output.join("report.json"), serde_json::to_vec_pretty(&json!({
            "scope":"Test-only per-root queue, enumeration, joined anchor evaluation, publication and return-handoff intervals, with optional process CPU deltas. CPU includes native service/shader threads and observer overhead; it is not CPU utilization or task-only work. Top-level dispatched task spans include all nested work, waits and preemption. Per-worker collectors register once per root token; no shared hot collector lock or lock is held across generation. After the root joins, task intervals are unioned by physical thread before integrating workers with a task in flight; this is not workers actually running on CPU. Interval sums are not elapsed phase time. Missing phase/CPU data remains null; anomalous out-of-window events invalidate occupancy rather than being silently clipped. Only scalar metadata, phase/task summaries and top-eight long tasks persist. Collector buffers are emptied at joined root completion and retain no planner/state/GPU owners. Observer summary time is reported separately and remains inside whole-region elapsed time. Five warmups precede twenty complete lit regions per case; actual program snapshots are recorded. Noninitializer OnceLock API join intervals require an initially unfinished cell and an initializer closure that did not execute. Ready calls and initializer intervals are excluded; racing publication, result cloning in memo calls and preemption can still be included. These are not kernel sleep intervals or CPU utilization. Test-only scalar keys identify anchor, anchor-ID, footprint, structure-start and column calls. A thread-local RAII task-context stack restores reentrant roots and panic paths. No Arc clone, event allocation or collector lock is added for initializer candidates. Join intervals are unioned by physical worker and intersected with target tasks and the p95-to-last-completion tail; sums across workers or overlapping categories are not elapsed-time shares. Missing evaluation phases stay null; invalid task/join bounds, non-LIFO contexts or live contexts at root completion invalidate attribution. Context mismatch fails the diagnostic rather than misattributing an event. Panicked tasks/abandoned roots retain failure status and missing phases; raw buffers are released on every root exit. Probe checks, candidate stamps and event recording remain inside evaluation and whole-region timing; only post-join summary overhead is separated. Task entry/exit also samples optional CLOCK_THREAD_CPUTIME_ID on the same physical thread. CPU values include nested work and instrumentation on that thread but exclude service-thread work. Endpoint intervals are unioned per physical worker; missing/reversed samples leave aggregate CPU null. CPU is not interpolated into partial task tails. Separately, initially-unfinished terrain job cells record initializer or noninitializer API spans, and GPU dispatch records the receiver reply API span. These retain the same physical-thread contexts and bounds validation. Terrain initializer and reply categories overlap; they are not pure GPU time, and outside-span time is not classified as CPU. A third buffer records cache-lock acquisition and inclusive lookup intervals for anchor, anchor-ID, footprint, structure-start and boundary column maps, plus feature-GPU lock acquisition and inclusive batch calls. Acquisition timestamps are captured while holding the lock, but events are appended only after the original lock has been released. No try-lock or scheduling change is used. Lookup/call includes acquisition, and categories overlap. Acquisition includes hashing before the lock call and possible preemption; it is not kernel-blocked time. Errors and abandoned calls retain failure evidence. Every recorded lookup adds observer work, which remains inside measured task/root/region intervals; these timings are diagnostic only. Other locks and unmeasured paths remain outside coverage. Normal builds exclude all observer code and use the original generation algorithm.",
            "capture_mode":if aggregate { "aggregate" } else { "detailed" },
            "task_gap_scope":"Occupancy first_start_ns and completion times are offsets from evaluation window start; longest_tasks start_ns/end_ns retain their root-origin offsets. zero_task_intervals_ns partitions histogram bucket zero into before the first task, gaps between first start and last completion, and after the last task. Instantaneous events define boundaries without occupying time. With no task events, boundaries and their three partitions are null and no_task_events contains the whole window. These are intervals without a recorded callback span, not CPU idle or pure scheduler delay. Before-first includes admission, registration and preemption; interior gaps and after-last can include observer work, scheduling and joins. Summarization uses existing events and adds no task clocks. Concurrent roots overlap: do not sum their gaps as batch wall time.",
            "aggregate_scope":"When capture_mode is aggregate, all per-API timed event collection is disabled and explicitly marked unavailable. Task-local work counters merge once on task exit; detailed event buffers stay empty. Root phase and dispatched-task CPU clocks remain enabled, and include counter overhead. Work rows identify demanded earlier roots; they are not exclusive CPU attribution. Empty scratch counters may retain at most one allocation per physical thread and no generation owners.",
            "cases":cases,"timing_acceptance":false,"anchor_cpu_enabled":anchor_cpu,"column_load_salt":column_load_salt,
            "concurrent_requests":callers,"request_order":order,
            "coordination_scope":"Roots tagged metadata.coordination=worker_continuation share one caller-owned scope. The last lane publishes and settles its root, then prepares and schedules its successor without joining child roots. Each root enumerates its own coordinates. Only the first root queue spans external dispatch; later root clocks begin on the preceding final worker. Evaluation includes lane admission and all callback spans. return_handoff ends at root settlement on that worker, not a per-root caller return. Observer summary time still remains inside dependency and region time. Final scope joining and coordinate-owner disposal remain inside whole dependency/region time and are outside per-root phases. Every callback releases read access before publication; old lane epilogues may finish after a successor is scheduled but no longer access planner state.",
            "request_scope":"Twenty requests are available at one batch start after five serial warmups. At most concurrent_requests scoped callers enter complete generation on the same engine; native assembly workers remain independently configured. Queue is batch start to callback entry; callback includes original generation, request setup and report snapshots; completion equals queue plus callback. Existing instrumented_ms retains its narrower native-generation interval. This is not Java/portal admission. Root phases and work group by actual region coordinates. Concurrent root process-CPU intervals overlap and include other requests: never sum or assign them as exclusive region CPU or wall shares. All caller threads join, including on panic, before observer/engine disposal; results retain submission order despite arbitrary completion order.",
        })).unwrap()).unwrap();
    }
    assert!(!cases.is_empty(), "no requested worker-phase case was generated");
}

#[test]
fn session_clock_preserves_cross_root_interval_order() {
    let session = Arc::new(Session::default());
    let first = Root::new(json!({}), session.clone());
    let before_second = first.stamp();
    let second = Root::new(json!({}), session.clone());
    let after_second = second.stamp();
    let epoch = *session.epoch.get().unwrap();
    let first_offset = first.origin.duration_since(epoch).as_nanos() as u64;
    let second_offset = second.origin.duration_since(epoch).as_nanos() as u64;
    assert!(first_offset + before_second.wall <= second_offset);
    assert!(second_offset <= second_offset + after_second.wall);
    assert!(first_offset + first.stamp().wall >= second_offset + after_second.wall);
}
