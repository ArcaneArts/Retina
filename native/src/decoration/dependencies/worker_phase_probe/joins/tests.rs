use super::*;
use std::{sync::mpsc, time::Duration};

fn trace(session: &Arc<Session>, name: &str, targets: usize) -> Trace {
    let trace = Trace(Some(Root::new(json!({"name":name}), session.clone())));
    trace.mark("dispatch_enter");
    trace.evaluation_begin(targets);
    trace
}
fn finish(trace: Trace) {
    trace.mark("evaluation_end");
    trace.mark("publication_end");
    trace.finish(true);
}
fn key(kind: &'static str, x: i32) -> Key { Key::new(kind, Some(2), x, -3) }

#[test]
fn join_probe_excludes_ready_and_owned_calls_but_preserves_noninitializer_errors() {
    let session = Arc::new(Session::default());
    let trace = trace(&session,"a",1);
    let task = trace.task([1,2]);
    let cache = Memo::default();
    let metrics = Metrics::default();
    assert_eq!(*memo(&cache,1,&metrics,key("anchor",1),|| Ok(42)).unwrap(),42);
    assert_eq!(*memo(&cache,1,&metrics,key("anchor",1),|| panic!("ready value rebuilt")).unwrap(),42);
    let cell = Arc::new(OnceLock::<Result<i32,String>>::new());
    let (started,begun) = mpsc::channel();
    let (release,released) = mpsc::channel();
    let owner = {
        let cell = cell.clone();
        std::thread::spawn(move || cell.get_or_init(|| {
            started.send(()).unwrap();
            released.recv_timeout(Duration::from_secs(5)).unwrap();
            Err("original failure".into())
        }).clone())
    };
    begun.recv_timeout(Duration::from_secs(5)).unwrap();
    let join = Candidate::start(cell.get().is_none(),key("column",7));
    release.send(()).unwrap();
    let result = cell.get_or_init(|| panic!("second initializer"));
    join.finish(false,result.is_ok());
    assert_eq!(owner.join().unwrap(),Err("original failure".into()));
    task.finish(false);
    finish(trace);
    let roots = session.roots.lock().unwrap();
    let joined = &roots[0]["join_attribution"];
    assert_eq!(joined["valid"],true);
    assert_eq!(joined["events"],1);
    assert_eq!(joined["failed_events"],1);
    assert_eq!(joined["longest_joins"][0]["kind"],"column");
    assert_eq!(joined["longest_joins"][0]["at"],json!([7,-3]));
    assert_eq!(roots[0]["failed_tasks"],1);
    LOCAL.with(|local| {
        let local = local.borrow();let worker = local.worker.as_ref().unwrap();
        assert!(worker.events.lock().unwrap().is_empty());
        assert!(worker.joins.lock().unwrap().events.is_empty());
        assert!(worker.closed.load(Ordering::Relaxed));
    });
}

#[test]
fn join_probe_restores_outer_context_after_nested_root_and_panic() {
    let session = Arc::new(Session::default());
    let outer = trace(&session,"outer",1);
    let outer_task = outer.task([0,0]);
    let inner = trace(&session,"inner",1);
    let inner_task = inner.task([1,1]);
    Candidate::start(true,key("inner",1)).finish(false,true);
    inner_task.finish(true);
    finish(inner);
    // LOCAL still points at the finished inner root. No new outer task binds it.
    Candidate::start(true,key("outer",2)).finish(false,true);
    let panic = std::panic::catch_unwind(std::panic::AssertUnwindSafe(|| {
        let nested = trace(&session,"panic",1);
        let _task = nested.task([2,2]);
        let cache: Memo<i32,usize> = Memo::default();
        memo(&cache,1,&Metrics::default(),key("owned",3),|| panic!("initializer panic")).unwrap();
    }));
    assert!(panic.is_err());
    Candidate::start(true,key("outer",4)).finish(false,true);
    outer_task.finish(true);
    finish(outer);
    assert!(CURRENT.with(|c| c.borrow().frames.is_empty()));
    let roots = session.roots.lock().unwrap();
    assert_eq!(roots.len(),3);
    assert_eq!(roots[0]["join_attribution"]["events"],1);
    assert_eq!(roots[1]["abandoned"],true);
    assert_eq!(roots[1]["success"],false);
    assert_eq!(roots[1]["failed_tasks"],1);
    assert_eq!(roots[1]["join_attribution"]["events"],0);
    assert_eq!(roots[1]["join_attribution"]["valid"],Value::Null);
    assert_eq!(roots[1]["phases"]["evaluation"],Value::Null);
    assert_eq!(roots[1]["join_attribution"]["active_contexts_at_root_end"],0);
    assert_eq!(roots[2]["join_attribution"]["events"],2);
    assert!(roots[2]["join_attribution"]["longest_joins"].as_array().unwrap()
        .iter().all(|x| x["kind"]=="outer"));
}

#[test]
fn join_probe_rejects_mismatched_context_and_flags_non_lifo_tasks() {
    let session = Arc::new(Session::default());
    let outer = trace(&session,"outer",1);
    let first = outer.task([0,0]);
    let candidate = Candidate::start(true,key("outer",1));
    let inner = trace(&session,"inner",1);
    let second = inner.task([1,1]);
    assert!(std::panic::catch_unwind(std::panic::AssertUnwindSafe(|| candidate.finish(false,true))).is_err());
    // This deliberately invalid order must not silently produce a valid report.
    first.finish(true);
    second.finish(true);
    finish(inner);
    finish(outer);
    assert!(CURRENT.with(|c| c.borrow().frames.is_empty()));
    let roots = session.roots.lock().unwrap();
    assert_eq!(roots[1]["join_attribution"]["context_errors"],1);
    assert_eq!(roots[1]["join_attribution"]["valid"],false);
    assert_eq!(roots[1]["join_attribution"]["events"],0);
}

fn synthetic() -> (Vec<(String,Vec<Event>)>,BTreeMap<String,Buffer>) {
    let event = |start,end| Event { at:[0,0],start,end,success:true,cpu:[None,None] };
    let mut short = vec![event(0,10);19];short.push(event(10,16));
    let tasks = vec![("a".into(),vec![event(0,20)]),("b".into(),short)];
    let join = |kind,start,end,success| JoinEvent { key:key(kind,0),start,end,success,gpu:None };
    let workers = BTreeMap::from([
        ("a".into(),Buffer { events:vec![join("column",2,7,true),join("anchor",5,10,true),join("column",14,18,true)],context_errors:0 }),
        ("b".into(),Buffer { events:vec![join("column",12,16,false)],context_errors:0 }),
    ]);
    (tasks,workers)
}
#[test]
fn join_probe_unions_physical_workers_and_intersects_task_and_tail_intervals() {
    let (tasks,workers) = synthetic();
    let result = summarize(&tasks,&workers,Some([0,20]),0);
    assert_eq!(result["valid"],true);
    assert_eq!(result["events"],4);
    assert_eq!(result["failed_events"],1);
    assert_eq!(result["event_interval_sum_ns"],18);
    assert_eq!(result["worker_join_union_sum_ns"],16);
    assert_eq!(result["worker_join_histogram_ns"],json!({"0":6,"1":12,"2":2}));
    assert_eq!(result["categories"]["column"]["worker_union_sum_ns"],13);
    assert_eq!(result["categories"]["anchor"]["worker_union_sum_ns"],5);
    assert_eq!(result["tail_window_ns"],json!([16.,20.]));
    assert_eq!(result["tail_worker_join_union_sum_ns"],2.);
    let longest = &result["longest_tasks"][0];
    assert_eq!(longest["api_join_union_ns"],12);
    assert_eq!(longest["api_join_fraction_of_task"],0.6);
    assert_eq!(longest["tail_api_join_union_ns"],2.);
    assert_eq!(result["last_finishing_tasks"][0]["worker"],"a");
}
#[test]
fn join_probe_keeps_missing_phases_and_rejects_invalid_or_unjoined_evidence() {
    let (tasks,mut workers) = synthetic();
    let missing = summarize(&tasks,&workers,None,0);
    assert_eq!(missing["valid"],Value::Null);
    assert_eq!(missing["events"],4);
    assert_eq!(missing["failed_events"],1);
    assert_eq!(missing["worker_join_union_sum_ns"],Value::Null);
    assert_eq!(summarize(&tasks,&workers,Some([0,20]),1)["valid"],false);
    assert_eq!(summarize(&tasks,&workers,Some([3,20]),0)["valid"],false);
    workers.get_mut("a").unwrap().events[0].end=1;
    let reversed = summarize(&tasks,&workers,Some([0,20]),0);
    assert_eq!(reversed["valid"],false);
    assert_eq!(reversed["outside_or_reversed_events"],1);
    assert_eq!(reversed["worker_join_union_sum_ns"],Value::Null);
    let (tasks,mut workers) = synthetic();
    workers.insert("no-task".into(),Buffer { events:vec![JoinEvent { key:key("column",0),start:1,end:2,success:true,gpu:None }],context_errors:0 });
    assert_eq!(summarize(&tasks,&workers,Some([0,20]),0)["valid"],false);
}

#[test]
fn terrain_spans_separate_ready_owned_join_reply_and_failure_with_nested_unions() {
    fn access(cell: &OnceLock<Result<u8,String>>) -> Result<u8,String> {
        let span=TerrainSpan::start(cell.get().is_none(),"terrain_job_abandoned",1,2);
        let mut built=false;
        let result=cell.get_or_init(|| {
            built=true;
            let reply=TerrainSpan::start(true,"gpu_reply_abandoned",1,2);
            reply.finish("gpu_reply",false);
            Err("original terrain failure".into())
        }).clone();
        span.finish(if built {"terrain_job_initialize"} else {"terrain_job_join"},result.is_ok());
        result
    }
    let session=Arc::new(Session::default());
    let trace=trace(&session,"terrain",1);let task=trace.task([1,2]);
    let cell=OnceLock::new();
    assert_eq!(access(&cell),Err("original terrain failure".into()));
    assert_eq!(access(&cell),Err("original terrain failure".into()));
    // A noninitializer's pending snapshot can race with publication; preserve it.
    let pending=OnceLock::new();
    let join=TerrainSpan::start(pending.get().is_none(),"terrain_job_abandoned",3,4);
    pending.set(Err::<u8,_>("cached owner error")).unwrap();
    let result=pending.get_or_init(|| panic!("owner result overwritten"));
    join.finish("terrain_job_join",result.is_ok());
    task.finish(false);finish(trace);
    let roots=session.roots.lock().unwrap();let root=&roots[0];let result=&root["terrain_attribution"];
    assert_eq!(root["join_attribution"]["events"],0);
    assert_eq!(result["valid"],true);assert_eq!(result["events"],3);
    assert_eq!(result["failed_events"],3);
    for kind in ["terrain_job_initialize","terrain_job_join","gpu_reply"] {
        assert_eq!(result["categories"][kind]["events"],1);
    }
    let raw=result["event_interval_sum_ns"].as_u64().unwrap();
    let union=result["worker_join_union_sum_ns"].as_u64().unwrap();
    assert!(union<=raw);
    LOCAL.with(|l| assert!(l.borrow().worker.as_ref().unwrap().terrain.lock().unwrap().events.is_empty()));
}

#[test]
fn terrain_spans_keep_abandoned_calls_and_restore_outer_context_after_panic() {
    let session=Arc::new(Session::default());
    let outer=trace(&session,"outer",1);let task=outer.task([0,0]);
    let panic=std::panic::catch_unwind(std::panic::AssertUnwindSafe(|| {
        let inner=trace(&session,"inner",1);let _task=inner.task([1,1]);
        let _span=TerrainSpan::start(true,"terrain_job_abandoned",1,1);
        panic!("terrain initializer panic");
    }));
    assert!(panic.is_err());
    TerrainSpan::start(true,"gpu_reply_abandoned",2,2).finish("gpu_reply",true);
    task.finish(true);finish(outer);
    let roots=session.roots.lock().unwrap();
    assert_eq!(roots[0]["abandoned"],true);
    assert_eq!(roots[0]["terrain_attribution"]["failed_events"],1);
    assert_eq!(roots[0]["terrain_attribution"]["valid"],Value::Null);
    assert_eq!(roots[0]["terrain_attribution"]["longest_joins"][0]["kind"],"terrain_job_abandoned");
    assert_eq!(roots[1]["terrain_attribution"]["valid"],true);
    assert_eq!(roots[1]["terrain_attribution"]["events"],1);
    assert_eq!(roots[1]["terrain_attribution"]["failed_events"],0);
    assert_eq!(roots[1]["terrain_attribution"]["longest_joins"][0]["at"],json!([2,2]));
}

#[test]
fn terrain_reply_snapshots_retain_all_jobs_and_missing_failure_data() {
    let session=Arc::new(Session::default());
    let trace=trace(&session,"gpu-details",1);let task=trace.task([1,2]);
    let mut snapshot=crate::timings::Snapshot::default();
    snapshot.flags=1;snapshot.nanos[crate::timings::QUEUE]=123;
    snapshot.nanos[crate::timings::WAIT_COPY]=456;
    for x in 0..12 {
        TerrainSpan::start(true,"gpu_reply_abandoned",x,2)
            .finish_gpu(true,Some(&snapshot),22,20,0);
    }
    TerrainSpan::start(true,"gpu_reply_abandoned",99,2).finish_gpu(false,None,22,20,0);
    task.finish(true);finish(trace);
    let roots=session.roots.lock().unwrap();let result=&roots[0]["terrain_attribution"];
    assert_eq!(result["valid"],true);
    assert_eq!(result["longest_joins"].as_array().unwrap().len(),8);
    let replies=result["gpu_replies"].as_array().unwrap();assert_eq!(replies.len(),13);
    for (i,reply) in replies[..12].iter().enumerate() {
        assert_eq!(reply["at"],json!([i,2]));
        assert_eq!(reply["gpu"]["tile_side"],22);
        assert_eq!(reply["gpu"]["snapshot"]["nanos"][crate::timings::QUEUE],123);
        assert_eq!(reply["gpu"]["snapshot"]["nanos"][crate::timings::WAIT_COPY],456);
    }
    assert_eq!(replies[12]["success"],false);
    assert_eq!(replies[12]["gpu"]["snapshot"],Value::Null);
}
