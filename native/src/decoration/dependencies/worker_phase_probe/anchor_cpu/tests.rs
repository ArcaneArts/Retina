use super::*;

fn make_session(enabled: bool) -> Arc<Session> {
    Arc::new(Session { aggregate: true, anchor_cpu: enabled, ..Session::default() })
}
fn make_trace(session: &Arc<Session>, label: &str) -> Trace {
    let trace = Trace(Some(Root::new(json!({"root_count":4,"label":label}), session.clone())));
    trace.evaluation_begin(1);
    trace
}
fn finish(trace: Trace, task: Task) {
    task.finish(true); trace.mark("evaluation_end"); trace.finish(true);
}
fn read_cpu(session: &Arc<Session>, index: usize) -> Value {
    session.roots.lock().unwrap()[index]["anchor_cpu"].clone()
}
fn begin(root: usize, at: u64) -> Span { Span::begin(root, || Some(at)) }
fn end(mut span: Span, at: u64) { span.finish(|| Some(at)); }
fn root(cpu: &Value, index: usize) -> &Value {
    &cpu["rows"].as_array().unwrap().iter().find(|row| row["executed_root"]==index).unwrap()["cpu"]
}

#[test]
fn nested_and_sibling_anchor_cpu_reconciles_once_per_executed_root() {
    let session = make_session(true); let trace = make_trace(&session, "nested"); let task = trace.task([0,0]);
    let outer = begin(2,100); let child = begin(1,120);
    end(begin(0,130),140); end(child,160);
    end(begin(0,170),190); end(outer,200);
    finish(trace,task);
    let cpu = read_cpu(&session,0);
    assert_eq!(cpu["totals"]["valid"],true);
    assert_eq!(cpu["totals"]["started"],4);
    assert_eq!(cpu["totals"]["finished"],4);
    assert_eq!(cpu["totals"]["inclusive_cpu_ns"],170);
    assert_eq!(cpu["totals"]["own_cpu_ns"],100);
    assert_eq!(cpu["totals"]["outermost_cpu_ns"],100);
    assert_eq!(root(&cpu,0)["own_cpu_ns"],30);
    assert_eq!(root(&cpu,1)["own_cpu_ns"],30);
    assert_eq!(root(&cpu,2)["own_cpu_ns"],40);
}

#[test]
fn reentrant_task_contexts_subtract_children_on_the_same_physical_thread() {
    let session = make_session(true);
    let parent_trace = make_trace(&session,"parent"); let parent_task=parent_trace.task([0,0]);
    let parent=begin(3,100);
    let child_trace=make_trace(&session,"child"); let child_task=child_trace.task([1,1]);
    end(begin(1,120),160); finish(child_trace,child_task);
    end(parent,200); finish(parent_trace,parent_task);
    let child=read_cpu(&session,0); let parent=read_cpu(&session,1);
    assert_eq!(child["totals"]["own_cpu_ns"],40);
    assert_eq!(child["totals"]["outermost_cpu_ns"],0);
    assert_eq!(parent["totals"]["own_cpu_ns"],60);
    assert_eq!(parent["totals"]["outermost_cpu_ns"],100);
    assert_eq!(child["totals"]["valid"],true); assert_eq!(parent["totals"]["valid"],true);
    STACK.with(|stack| assert!(stack.borrow().frames.is_empty()));
}

#[test]
fn clocks_preserve_missing_reversed_and_impossible_nested_samples() {
    for (start,end,expected) in [(None,Some(20),Value::Null),
        (Some(10),None,Value::Null),(Some(20),Some(10),json!(false))] {
        let session=make_session(true); let trace=make_trace(&session,"clock"); let task=trace.task([0,0]);
        let mut span=Span::begin(0,||start);span.finish(||end);finish(trace,task);
        let cpu=read_cpu(&session,0);
        assert_eq!(cpu["totals"]["valid"],expected);
        assert_eq!(cpu["totals"]["own_cpu_ns"],Value::Null);
    }
    let session=make_session(true);let trace=make_trace(&session,"missing-child");let task=trace.task([0,0]);
    let parent=begin(1,100);let mut child=Span::begin(0,||None);child.finish(||Some(150));
    end(parent,200);finish(trace,task);
    let cpu=read_cpu(&session,0);
    assert_eq!(root(&cpu,1)["inclusive_cpu_ns"],100);
    assert_eq!(root(&cpu,1)["own_cpu_ns"],Value::Null);
    assert_eq!(root(&cpu,1)["valid"],Value::Null);
    assert_eq!(cpu["totals"]["valid"],Value::Null);

    let session=make_session(true);let trace=make_trace(&session,"oversized-child");let task=trace.task([0,0]);
    let parent=begin(1,100);end(begin(0,110),200);end(parent,150);finish(trace,task);
    let cpu=read_cpu(&session,0);
    assert_eq!(cpu["totals"]["valid"],false);
    assert_eq!(cpu["totals"]["invalid_child_calls"],1);
    assert_eq!(cpu["totals"]["own_cpu_ns"],Value::Null);
}

#[test]
fn non_lifo_and_context_exit_invalidate_without_charging_a_later_task() {
    let session=make_session(true);let trace=make_trace(&session,"non-lifo");let task=trace.task([0,0]);
    let parent=begin(1,100);let child=begin(0,110);end(parent,120);end(child,130);finish(trace,task);
    let cpu=read_cpu(&session,0);assert_eq!(cpu["totals"]["valid"],false);
    assert_eq!(cpu["totals"]["context_errors"],2);
    STACK.with(|stack| assert!(stack.borrow().frames.is_empty()));

    let session=make_session(true);let stale_trace=make_trace(&session,"stale");let task=stale_trace.task([0,0]);
    let stale=begin(0,100);finish(stale_trace,task);
    assert_eq!(read_cpu(&session,0)["totals"]["abandoned"],1);
    assert_eq!(read_cpu(&session,0)["totals"]["valid"],false);
    let next_trace=make_trace(&session,"next");let task=next_trace.task([0,0]);
    end(stale,200);end(begin(2,300),350);finish(next_trace,task);
    let cpu=read_cpu(&session,1);assert_eq!(cpu["totals"]["started"],1);
    assert_eq!(cpu["totals"]["own_cpu_ns"],50);
    assert_eq!(cpu["totals"]["valid"],true);
    STACK.with(|stack| assert!(stack.borrow().frames.is_empty()));
}

#[test]
fn root_exit_with_live_context_keeps_cpu_unavailable_and_releases_frames() {
    let session=make_session(true);let trace=make_trace(&session,"live");let task=trace.task([0,0]);
    let span=begin(0,100);trace.finish(false);
    let cpu=read_cpu(&session,0);assert_eq!(cpu["totals"]["valid"],false);
    assert_eq!(cpu["totals"]["own_cpu_ns"],Value::Null);
    drop(task);drop(span);
    STACK.with(|stack| assert!(stack.borrow().frames.is_empty()));
    LOCAL.with(|local| {
        let local=local.borrow();let worker=local.worker.as_ref().unwrap();
        assert!(worker.closed.load(Ordering::Relaxed));
        assert!(worker.anchor_cpu.lock().unwrap().rows.is_empty());
    });
}

#[test]
fn panic_and_result_errors_restore_the_cpu_stack_and_preserve_counts() {
    let session=make_session(true);let trace=make_trace(&session,"failure");let task=trace.task([0,0]);
    let result=std::panic::catch_unwind(|| { let _span=Span::start(0);panic!("CPU probe fixture"); });
    assert!(result.is_err());
    let result=(|| -> Result<(), &'static str> { let _span=Span::start(1);Err("fixture") })();
    assert!(result.is_err());
    STACK.with(|stack| assert!(stack.borrow().frames.is_empty()));
    finish(trace,task);let cpu=read_cpu(&session,0);
    assert_eq!(cpu["totals"]["started"],2);assert_eq!(cpu["totals"]["finished"],2);
    assert_eq!(cpu["totals"]["panicked"],1);assert_eq!(cpu["totals"]["abandoned"],0);
    assert_ne!(cpu["totals"]["valid"],false);
}

#[test]
fn disabled_observer_does_not_read_the_clock_and_empty_buffers_are_reused() {
    let session=make_session(false);let trace=make_trace(&session,"disabled");let task=trace.task([0,0]);
    let span=Span::begin(0,||panic!("disabled clock read"));assert!(span.key.is_none());
    drop(span);finish(trace,task);assert_eq!(read_cpu(&session,0)["enabled"],false);
    let mut buffer=Buffer::take(4);buffer.begin(2);buffer.record(2,Observation {
        inclusive:Some(50),own:Some(50),outermost:true,..Observation::default() });
    let mut collected=Buffer::default();collected.extend(buffer);
    assert_eq!(collected.summary()["totals"]["own_cpu_ns"],50);
    let buffer=Buffer::take(2);
    assert_eq!(buffer.summary()["totals"]["started"],0);
    assert!(buffer.touched.is_empty());assert_eq!(buffer.rows.len(),3);
}

#[test]
fn physical_threads_keep_independent_nesting_and_exact_sums() {
    let session=make_session(true);let barrier=Arc::new(std::sync::Barrier::new(4));
    std::thread::scope(|scope| {
        for i in 0..4 {
            let session=session.clone();let barrier=barrier.clone();
            scope.spawn(move || {
                let trace=make_trace(&session,"parallel");let task=trace.task([i,i]);
                let span=begin(3,100);barrier.wait();end(begin(0,120),160);
                barrier.wait();end(span,200);finish(trace,task);
            });
        }
    });
    let rows=session.roots.lock().unwrap();assert_eq!(rows.len(),4);
    for row in rows.iter() {
        assert_eq!(row["anchor_cpu"]["totals"]["own_cpu_ns"],100);
        assert_eq!(row["anchor_cpu"]["totals"]["outermost_cpu_ns"],100);
        assert_eq!(row["anchor_cpu"]["totals"]["valid"],true);
    }
}

#[test]
fn invalid_root_indices_and_counter_overflow_stay_invalid() {
    let mut buffer=Buffer::take(2);buffer.begin(8);buffer.record(8,Observation {
        inclusive:Some(1),own:Some(1),outermost:true,..Observation::default() });
    let cpu=buffer.summary();assert_eq!(cpu["invalid_targets"],2);
    assert_eq!(cpu["totals"]["valid"],false);assert_eq!(cpu["totals"]["own_cpu_ns"],Value::Null);
    let mut row=Row { started:u64::MAX,..Row::default() };
    row.add(&Row { started:1,..Row::default() });
    assert_eq!(row.json()["overflow"],true);assert_eq!(row.json()["valid"],false);
}

#[test]
fn short_children_outside_parent_or_sibling_clock_bounds_are_invalid() {
    for (a,b,parent_end) in [(90,120,200),(190,220,200)] {
        let session=make_session(true);let trace=make_trace(&session,"bounds");let task=trace.task([0,0]);
        let parent=begin(1,100);end(begin(0,a),b);end(parent,parent_end);finish(trace,task);
        let cpu=read_cpu(&session,0);
        assert_eq!(cpu["totals"]["valid"],false);
        assert!(cpu["totals"]["clock_bounds_errors"].as_u64().unwrap()>0);
        assert_eq!(cpu["totals"]["own_cpu_ns"],Value::Null);
    }
    let session=make_session(true);let trace=make_trace(&session,"siblings");let task=trace.task([0,0]);
    let parent=begin(1,100);end(begin(0,110),130);end(begin(0,120),140);end(parent,200);finish(trace,task);
    assert_eq!(read_cpu(&session,0)["totals"]["valid"],false);
}

#[test]
fn span_finishing_in_another_live_context_is_not_silently_attributed() {
    let session=make_session(true);
    let parent_trace=make_trace(&session,"outer");let parent_task=parent_trace.task([0,0]);
    let parent=begin(1,100);
    let child_trace=make_trace(&session,"inner");let child_task=child_trace.task([1,1]);
    end(parent,200);finish(child_trace,child_task);finish(parent_trace,parent_task);
    let cpu=read_cpu(&session,1);
    assert_eq!(cpu["totals"]["context_errors"],1);
    assert_eq!(cpu["totals"]["valid"],false);
    assert_eq!(cpu["totals"]["own_cpu_ns"],Value::Null);
}
