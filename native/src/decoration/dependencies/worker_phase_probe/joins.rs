//! Noninitializer OnceLock API intervals, not kernel sleep or CPU utilization.
use super::*;
use std::{marker::PhantomData, rc::Rc};
mod lookup;
pub(crate) use lookup::LookupCall;

#[derive(Clone, Copy)]
pub(crate) struct Key {
    pub kind: &'static str,
    pub root: Option<usize>,
    pub at: [i32; 2],
}
impl Key {
    pub(crate) const fn new(kind: &'static str, root: Option<usize>, x: i32, z: i32) -> Self {
        Self { kind, root, at: [x, z] }
    }
}
#[derive(Clone)]
struct JoinEvent {
    key: Key,
    start: u64,
    end: u64,
    success: bool,
}
#[derive(Default)]
pub(super) struct Buffer {
    events: Vec<JoinEvent>,
    pub(super) context_errors: u64,
}
impl Buffer {
    pub(super) fn is_empty(&self) -> bool { self.events.is_empty() }
    pub(super) fn extend(&mut self, other: Self) {
        self.events.extend(other.events);
        self.context_errors += other.context_errors;
    }
}
struct Frame {
    tag: u64,
    token: u64,
    origin: Instant,
    worker: Arc<Worker>,
    work: Option<work::Buffer>,
    anchor_cpu: Option<anchor_cpu::Buffer>,
}
#[derive(Default)]
struct Stack {
    next: u64,
    frames: Vec<Frame>,
}
thread_local! { static CURRENT: RefCell<Stack> = RefCell::new(Stack::default()); }

// Neither this guard nor a candidate can move to another physical thread.
// A nested root restores the outer frame even if LOCAL.bind last saw that root.
pub(super) struct Context {
    tag: u64,
    pub(super) origin: Instant,
    pub(super) worker: Arc<Worker>,
    _thread: PhantomData<Rc<()>>,
}
impl Context {
    pub(super) fn enter(token: u64, origin: Instant, worker: Arc<Worker>) -> Self {
        assert!(!worker.closed.load(Ordering::Relaxed), "task entered a finished root");
        let tag = CURRENT.with(|current| {
            let mut current = current.borrow_mut();
            current.next = current.next.checked_add(1).expect("diagnostic task tag overflow");
            let tag = current.next;
            let work = worker.aggregate.then(|| work::Buffer::take(worker.root_count));
            let anchor_cpu = worker.anchor_cpu_enabled.then(|| anchor_cpu::Buffer::take(worker.root_count));
            current.frames.push(Frame { tag, token, origin, worker: worker.clone(), work, anchor_cpu });
            tag
        });
        worker.active.fetch_add(1, Ordering::Relaxed);
        Self { tag, origin, worker, _thread: PhantomData }
    }
}
impl Drop for Context {
    fn drop(&mut self) {
        let valid = CURRENT.with(|current| {
            let mut current = current.borrow_mut();
            let position = current.frames.iter().rposition(|frame| frame.tag == self.tag);
            let valid = position == current.frames.len().checked_sub(1);
            if let Some(position) = position {
                let mut frame = current.frames.remove(position);
                if let Some(mut cpu) = frame.anchor_cpu.take() {
                    anchor_cpu::close_context(anchor_cpu::ContextId { tag: frame.tag, token: frame.token }, &mut cpu);
                    if !frame.worker.closed.load(Ordering::Relaxed) {
                        frame.worker.anchor_cpu.lock().unwrap().extend(cpu);
                    }
                }
                if let Some(work) = frame.work {
                    if !frame.worker.closed.load(Ordering::Relaxed) {
                        work.drain_into(&mut frame.worker.work.lock().unwrap());
                    }
                }
            }
            valid && position.is_some()
        });
        if !valid && !self.worker.closed.load(Ordering::Relaxed) {
            self.worker.joins.lock().unwrap().context_errors += 1;
            self.worker.terrain.lock().unwrap().context_errors += 1;
            self.worker.lookup.lock().unwrap().context_errors += 1;
        }
        self.worker.active.fetch_sub(1, Ordering::Relaxed);
    }
}

pub(super) fn count_work(op: work::Op, root: Option<usize>, amount: u64) {
    CURRENT.with(|current| {
        if let Some(work) = current.borrow_mut().frames.last_mut().and_then(|frame| frame.work.as_mut()) {
            work.add(op, root, amount);
        }
    });
}

pub(super) fn anchor_begin(root: usize) -> Option<(anchor_cpu::ContextId, Option<u64>)> {
    CURRENT.with(|current| {
        let mut current = current.borrow_mut();
        let frame = current.frames.last_mut()?;
        if frame.worker.closed.load(Ordering::Relaxed) { return None }
        frame.anchor_cpu.as_mut()?.begin(root);
        Some((anchor_cpu::ContextId { tag: frame.tag, token: frame.token }, frame.worker.column_load_salt))
    })
}
pub(super) fn anchor_end(id: anchor_cpu::ContextId, root: usize, observation: anchor_cpu::Observation) {
    CURRENT.with(|current| {
        let mut current = current.borrow_mut();
        // Normally this is the last frame. A stale guard after task exit was
        // already invalidated/drained by close_context; it cannot hit a new task.
        if let Some(frame) = current.frames.iter_mut().rfind(|f| f.tag==id.tag && f.token==id.token) {
            if let Some(buffer) = &mut frame.anchor_cpu { buffer.record(root, observation); }
        }
    });
}
pub(super) fn anchor_current(id: anchor_cpu::ContextId) -> bool {
    CURRENT.with(|current| current.borrow().frames.last().is_some_and(|frame|
        frame.tag==id.tag && frame.token==id.token && !frame.worker.closed.load(Ordering::Relaxed)))
}

enum Bucket { Dependency, Terrain, Lookup }

#[derive(Clone)]
pub(crate) struct Candidate {
    stamp: Option<(u64, u64, u64, Key)>,
    _thread: PhantomData<Rc<()>>,
}
impl Candidate {
    pub(crate) fn start(pending: bool, key: Key) -> Self {
        let stamp = pending.then(|| CURRENT.with(|current| {
            let current = current.borrow();
            let frame = current.frames.last()?;
            if frame.worker.aggregate { return None; }
            Some((frame.tag, frame.token, frame.origin.elapsed().as_nanos() as u64, key))
        })).flatten();
        Self { stamp, _thread: PhantomData }
    }
    pub(crate) fn finish(self, built: bool, success: bool) {
        // No event allocation, Arc clone or collector lock for initializers.
        // An initializer may itself join nested cells, which record separately.
        if built { return; }
        self.record(success, Bucket::Dependency, None, None);
    }
    fn current_time(&self) -> Option<u64> {
        let (tag,token,_,_) = self.stamp?;
        CURRENT.with(|current| {
            let current=current.borrow();
            let frame=current.frames.last().expect("API span lost its task context");
            assert_eq!((frame.tag,frame.token),(tag,token),"API span changed task context");
            assert!(!frame.worker.closed.load(Ordering::Relaxed),"API span outlived its root");
            Some(frame.origin.elapsed().as_nanos() as u64)
        })
    }
    fn record(self, success: bool, bucket: Bucket, kind: Option<&'static str>, captured_end: Option<u64>) {
        let Some((tag, token, start, mut key)) = self.stamp else { return; };
        if let Some(kind) = kind { key.kind = kind; }
        CURRENT.with(|current| {
            let current = current.borrow();
            let frame = current.frames.last().expect("dependency join lost its task context");
            assert_eq!((frame.tag, frame.token), (tag, token), "dependency join changed task context");
            assert!(!frame.worker.closed.load(Ordering::Relaxed), "dependency join outlived its root");
            let end = captured_end.unwrap_or_else(|| frame.origin.elapsed().as_nanos() as u64);
            let buffer = match bucket { Bucket::Dependency=>&frame.worker.joins, Bucket::Terrain=>&frame.worker.terrain, Bucket::Lookup=>&frame.worker.lookup };
            buffer.lock().unwrap().events.push(JoinEvent { key, start, end, success });
        });
    }
}

// Separate terrain/service API intervals: an initializer can contain a reply
// span, so categories overlap. Neither category means pure GPU execution.
pub(crate) struct TerrainSpan(Option<Candidate>);
impl TerrainSpan {
    pub(crate) fn start(pending: bool, kind: &'static str, x: i32, z: i32) -> Self {
        Self(Some(Candidate::start(pending, Key::new(kind, None, x, z))))
    }
    pub(crate) fn finish(mut self, kind: &'static str, success: bool) {
        if let Some(candidate) = self.0.take() { candidate.record(success, Bucket::Terrain, Some(kind), None); }
    }
}
impl Drop for TerrainSpan {
    fn drop(&mut self) {
        if let Some(candidate) = self.0.take() { candidate.record(false, Bucket::Terrain, None, None); }
    }
}

fn intersection(ranges: &[(u64, u64)], window: [u64; 2]) -> u64 {
    ranges.iter().map(|&(a,b)| b.min(window[1]).saturating_sub(a.max(window[0]))).sum()
}
fn fractional_intersection(ranges: &[(u64, u64)], window: [f64; 2]) -> f64 {
    ranges.iter().map(|&(a,b)| ((b as f64).min(window[1]) - (a as f64).max(window[0])).max(0.)).sum()
}
fn ranges(events: &[JoinEvent]) -> Vec<(u64,u64)> {
    union(events.iter().map(|e| (e.start,e.end)).collect())
}
fn event_json(worker: &str, event: &JoinEvent) -> Value {
    json!({"worker":worker,"kind":event.key.kind,"dependency_root":event.key.root,
        "at":event.key.at,"start_ns":event.start,"end_ns":event.end,
        "duration_ns":event.end.checked_sub(event.start),"success":event.success})
}

pub(super) fn summarize(
    tasks: &[(String, Vec<Event>)], workers: &BTreeMap<String, Buffer>,
    window: Option<[u64;2]>, active_contexts: u64,
) -> Value {
    let count: usize = workers.values().map(|v| v.events.len()).sum();
    let failed: usize = workers.values().flat_map(|v| &v.events).filter(|e| !e.success).count();
    let context_errors: u64 = workers.values().map(|v| v.context_errors).sum();
    let mut longest: Vec<_> = workers.iter().flat_map(|(id,v)| v.events.iter().map(move |e| (id,e))).collect();
    longest.sort_unstable_by_key(|(_,e)| std::cmp::Reverse(e.end.checked_sub(e.start)));
    let longest: Vec<_> = longest.into_iter().take(8).map(|(id,e)| event_json(id,e)).collect();
    let mut result = json!({"valid":null,"window":window,"events":count,"failed_events":failed,
        "context_errors":context_errors,"active_contexts_at_root_end":active_contexts,"longest_joins":longest});
    let Some(window) = window else { return result; };
    let task_ranges: BTreeMap<_,_> = tasks.iter().map(|(id,events)|
        (id.as_str(),union(events.iter().map(|e| (e.start,e.end)).collect()))).collect();
    let invalid_tasks = tasks.iter().flat_map(|(_,events)| events)
        .filter(|e| e.start < window[0] || e.end > window[1] || e.end < e.start).count();
    let invalid = workers.iter().map(|(id,buffer)| buffer.events.iter().filter(|e| {
        e.start < window[0] || e.end > window[1] || e.end < e.start ||
            task_ranges.get(id.as_str()).map_or(true, |v| intersection(v,[e.start,e.end]) != e.end-e.start)
    }).count()).sum::<usize>();
    result["outside_or_reversed_events"] = json!(invalid);
    result["invalid_task_events"] = json!(invalid_tasks);
    result["valid"] = json!(window[1] >= window[0] && invalid == 0 && invalid_tasks == 0 &&
        context_errors == 0 && active_contexts == 0);
    if result["valid"] != true { return result; }

    let joined: Vec<_> = workers.iter().map(|(id,buffer)| (id.clone(),buffer.events.iter().map(|e|
        Event { at:e.key.at,start:e.start,end:e.end,success:e.success,cpu:[None,None] }).collect())).collect();
    let occupied = occupancy(&joined,window);
    result["worker_join_histogram_ns"] = occupied["worker_in_flight_histogram_ns"].clone();
    result["worker_join_union_sum_ns"] = occupied["worker_union_sum_ns"].clone();
    result["event_interval_sum_ns"] = occupied["task_interval_sum_ns"].clone();
    result["peak_workers_joining"] = occupied["peak_workers_in_flight"].clone();
    result["mean_workers_joining"] = occupied["mean_workers_in_flight"].clone();

    let mut categories = BTreeMap::<&str, (usize,u64)>::new();
    let mut all_ranges = BTreeMap::new();
    let mut per_kind = BTreeMap::new();
    for (id,buffer) in workers {
        all_ranges.insert(id.as_str(),ranges(&buffer.events));
        let mut grouped = BTreeMap::<&str,Vec<(u64,u64)>>::new();
        for event in &buffer.events {
            categories.entry(event.key.kind).or_default().0 += 1;
            grouped.entry(event.key.kind).or_default().push((event.start,event.end));
        }
        let grouped: BTreeMap<_,_> = grouped.into_iter().map(|(kind,v)| (kind,union(v))).collect();
        for (&kind,v) in &grouped { categories.get_mut(kind).unwrap().1 += v.iter().map(|(a,b)| b-a).sum::<u64>(); }
        per_kind.insert(id.as_str(),grouped);
    }
    result["categories"] = Value::Object(categories.into_iter().map(|(kind,(events,ns))|
        (kind.to_owned(),json!({"events":events,"worker_union_sum_ns":ns}))).collect());

    let mut finishes: Vec<_> = tasks.iter().flat_map(|(_,v)| v.iter().map(|e| e.end-window[0])).collect();
    finishes.sort_unstable();
    let tail = percentile(&finishes,0.95).zip(finishes.last())
        .map(|(p,last)| [window[0] as f64+p, (window[0]+*last) as f64]);
    result["tail_window_ns"] = json!(tail);
    result["tail_worker_join_union_sum_ns"] = json!(tail.map(|tail|
        all_ranges.values().map(|v| fractional_intersection(v,tail)).sum::<f64>()));
    let summarize_task = |id: &str, event: &Event| {
        let duration = event.end-event.start;
        let joined = all_ranges.get(id).map_or(0, |v| intersection(v,[event.start,event.end]));
        let kinds: BTreeMap<_,_> = per_kind.get(id).into_iter().flat_map(|v| v.iter())
            .map(|(&kind,v)| (kind,intersection(v,[event.start,event.end]))).collect();
        json!({"worker":id,"at":event.at,"start_ns":event.start,"end_ns":event.end,
            "duration_ns":duration,"success":event.success,"thread_cpu":cpu::event(event),"api_join_union_ns":joined,
            "api_join_fraction_of_task":(duration>0).then(|| joined as f64/duration as f64),
            "api_join_by_kind_ns":kinds,"tail_api_join_union_ns":tail.map(|tail|
                all_ranges.get(id).map_or(0.,|v| fractional_intersection(v,
                    [tail[0].max(event.start as f64),tail[1].min(event.end as f64)])))})
    };
    let mut long_tasks: Vec<_> = tasks.iter().flat_map(|(id,v)| v.iter().map(move |e| (id,e))).collect();
    long_tasks.sort_unstable_by_key(|(_,e)| std::cmp::Reverse(e.end-e.start));
    result["longest_tasks"] = Value::Array(long_tasks.iter().take(8).map(|(id,e)| summarize_task(id,e)).collect());
    long_tasks.sort_unstable_by_key(|(_,e)| std::cmp::Reverse(e.end));
    result["last_finishing_tasks"] = Value::Array(long_tasks.iter().take(8).map(|(id,e)| summarize_task(id,e)).collect());
    result
}

#[cfg(test)]
mod tests;
