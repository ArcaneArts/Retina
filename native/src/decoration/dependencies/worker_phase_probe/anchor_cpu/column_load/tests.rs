use super::*;
use super::super::tests::{make_trace, finish, read_cpu, root};

fn session(salt: Option<u64>) -> Arc<Session> {
    Arc::new(Session { aggregate:true, anchor_cpu:true, column_load_salt:salt, ..Session::default() })
}
fn anchor(root: usize, salt: u64, at: u64) -> super::super::Span {
    let x = (-4096..4096).find(|&x| selected(root,x,17,salt)).unwrap();
    super::super::Span::begin_at(root,x,17,||Some(at))
}
fn end_anchor(mut anchor: super::super::Span, at: u64) { anchor.finish(||Some(at)); }
fn load(at: u64) -> Span { Span::begin(||Some(at)) }
fn end(mut load: Span, at: u64) { load.finish(||Some(at)); }
fn cols(cpu: &Value) -> &Value { &cpu["totals"]["column_load"] }

#[test]
fn column_load_subtracts_all_child_anchors_and_uses_same_sample_denominator() {
    let s=session(Some(7));let trace=make_trace(&s,"sample");let task=trace.task([0,0]);
    let parent=anchor(3,7,100);let first=load(110);
    // This child is unselected, but its CPU still must be subtracted.
    let x=(-4096..4096).find(|&x| !selected(1,x,17,7)).unwrap();
    let child=super::super::Span::begin_at(1,x,17,||Some(120));end_anchor(child,150);
    end(first,170);end(load(180),190);end_anchor(parent,200);finish(trace,task);
    let cpu=read_cpu(&s,0);let c=cols(&cpu);
    assert_eq!(c["valid"],true);assert_eq!(c["sampled_anchors"],1);
    assert_eq!(c["calls"],2);assert_eq!(c["finished"],2);
    assert_eq!(c["load_inclusive_cpu_ns"],70);assert_eq!(c["load_own_cpu_ns"],40);
    assert_eq!(c["sampled_anchor_own_cpu_ns"],70);
    assert_eq!(root(&cpu,1)["column_load"]["sampled_anchors"],0);
    assert_eq!(cpu["totals"]["own_cpu_ns"],100);
}

#[test]
fn nested_selected_loads_across_task_contexts_are_disjoint_after_subtraction() {
    let s=session(Some(9));let outer_trace=make_trace(&s,"outer");let outer_task=outer_trace.task([0,0]);
    let parent=anchor(3,9,100);let outer=load(110);
    let inner_trace=make_trace(&s,"inner");let inner_task=inner_trace.task([1,1]);
    let child=anchor(1,9,120);end(load(125),145);end_anchor(child,150);finish(inner_trace,inner_task);
    end(outer,170);end_anchor(parent,200);finish(outer_trace,outer_task);
    let child=read_cpu(&s,0);let parent=read_cpu(&s,1);
    assert_eq!(cols(&child)["load_own_cpu_ns"],20);assert_eq!(cols(&parent)["load_own_cpu_ns"],30);
    assert_eq!(cols(&child)["sampled_anchor_own_cpu_ns"],30);assert_eq!(cols(&parent)["sampled_anchor_own_cpu_ns"],70);
    assert_eq!(cols(&child)["valid"],true);assert_eq!(cols(&parent)["valid"],true);
}

#[test]
fn unavailable_and_reversed_load_clocks_never_become_zero_cost() {
    for (start,end_time,valid) in [(None,Some(150),Value::Null),(Some(110),None,Value::Null),
        (Some(150),Some(140),json!(false)),(Some(90),Some(110),json!(false))] {
        let s=session(Some(0));let trace=make_trace(&s,"clock");let task=trace.task([0,0]);
        let a=anchor(0,0,100);let mut l=Span::begin(||start);l.finish(||end_time);
        end_anchor(a,200);finish(trace,task);let cpu=read_cpu(&s,0);
        assert_eq!(cols(&cpu)["valid"],valid);assert_eq!(cols(&cpu)["load_own_cpu_ns"],Value::Null);
    }
    let s=session(Some(0));let trace=make_trace(&s,"missing-child");let task=trace.task([0,0]);
    let a=anchor(3,0,100);let l=load(110);
    let mut child=super::super::Span::begin(0,||None);child.finish(||Some(140));
    end(l,150);end_anchor(a,200);finish(trace,task);
    assert_eq!(cols(&read_cpu(&s,0))["valid"],Value::Null);
}

#[test]
fn load_bounds_and_overlapping_loads_in_one_anchor_invalidate_samples() {
    for (child_start,child_end,load_end) in [(105,120,150),(120,160,150)] {
        let s=session(Some(0));let trace=make_trace(&s,"bounds");let task=trace.task([0,0]);
        let a=anchor(3,0,100);let l=load(110);
        end_anchor(super::super::Span::begin(0,||Some(child_start)),child_end);
        end(l,load_end);end_anchor(a,200);finish(trace,task);
        assert_eq!(cols(&read_cpu(&s,0))["valid"],false);
    }
    let s=session(Some(0));let trace=make_trace(&s,"overlap");let task=trace.task([0,0]);
    let a=anchor(3,0,100);let first=load(110);let second=load(120);
    end(second,130);end(first,140);end_anchor(a,200);finish(trace,task);
    assert_eq!(cols(&read_cpu(&s,0))["valid"],false);
}

#[test]
fn ending_anchor_with_live_load_invalidates_it_and_stale_load_cannot_charge_next_anchor() {
    let s=session(Some(1));let trace=make_trace(&s,"stale");let task=trace.task([0,0]);
    let a=anchor(0,1,100);let stale=load(110);end_anchor(a,150);
    let b=anchor(1,1,200);end(stale,210);end(load(220),230);end_anchor(b,250);finish(trace,task);
    let cpu=read_cpu(&s,0);
    assert_eq!(root(&cpu,0)["column_load"]["valid"],false);
    assert_eq!(root(&cpu,1)["column_load"]["valid"],true);
    assert_eq!(root(&cpu,1)["column_load"]["load_own_cpu_ns"],10);
    STACK.with(|stack| assert!(stack.borrow().frames.is_empty()));
}

#[test]
fn task_exit_drains_abandoned_samples_and_wrong_context_finishes_are_invalid() {
    let s=session(Some(1));let trace=make_trace(&s,"abandon");let task=trace.task([0,0]);
    let a=anchor(0,1,100);let stale=load(110);finish(trace,task);
    end(stale,120);end_anchor(a,150);
    assert_eq!(cols(&read_cpu(&s,0))["valid"],false);
    STACK.with(|stack| assert!(stack.borrow().frames.is_empty()));
    let trace=make_trace(&s,"parent");let task=trace.task([0,0]);
    let a=anchor(0,1,200);let l=load(210);
    let child_trace=make_trace(&s,"wrong-context");let child_task=child_trace.task([1,1]);
    end(l,220);finish(child_trace,child_task);end_anchor(a,250);finish(trace,task);
    assert_eq!(cols(&read_cpu(&s,2))["valid"],false);
}

#[test]
fn unselected_and_disabled_samples_do_not_read_load_clocks() {
    for salt in [None,Some(2)] {
        let s=session(salt);let trace=make_trace(&s,"disabled");let task=trace.task([0,0]);
        let x=(-4096..4096).find(|&x| !selected(0,x,17,2)).unwrap();
        let a=super::super::Span::begin_at(0,x,17,||Some(100));
        let mut l=Span::begin(||panic!("unselected load clock"));
        assert!(l.key.is_none());l.finish(||panic!("unselected finish clock"));
        end_anchor(a,200);finish(trace,task);
        assert_eq!(cols(&read_cpu(&s,0))["sampled_anchors"],0);
    }
    // A selected anchor with no loads contributes its full denominator.
    let s=session(Some(2));let trace=make_trace(&s,"empty");let task=trace.task([0,0]);
    end_anchor(anchor(0,2,100),200);finish(trace,task);
    let cpu=read_cpu(&s,0);assert_eq!(cols(&cpu)["sampled_anchor_own_cpu_ns"],100);
    assert_eq!(cols(&cpu)["calls"],0);assert_eq!(cols(&cpu)["load_own_cpu_ns"],0);
}

#[test]
fn panic_and_result_failure_release_load_and_anchor_frames() {
    let s=session(Some(0));let trace=make_trace(&s,"panic");let task=trace.task([0,0]);
    let x=(-4096..4096).find(|&x| selected(0,x,17,0)).unwrap();
    assert!(std::panic::catch_unwind(|| {
        let _a=super::super::Span::start_at(0,x,17);let _l=Span::start();panic!("load probe fixture");
    }).is_err());
    let result=(|| -> Result<(), &'static str> {
        let _a=super::super::Span::start_at(0,x,17);let _l=Span::start();Err("fixture")
    })();assert!(result.is_err());finish(trace,task);
    let cpu=read_cpu(&s,0);assert_eq!(cols(&cpu)["calls"],2);
    assert_eq!(cols(&cpu)["finished"],2);assert_eq!(cols(&cpu)["panicked_calls"],1);
    assert_ne!(cols(&cpu)["valid"],false);
    STACK.with(|stack| assert!(stack.borrow().frames.is_empty()));
}

#[test]
fn sample_keys_cover_negative_coordinates_and_two_threads_keep_separate_totals() {
    let keys=|salt| (-512..512).filter(|&x| selected(3,x,-17,salt)).collect::<Vec<_>>();
    assert_eq!(keys(4),keys(4));assert_ne!(keys(4),keys(5));
    assert!(keys(4).len()>5 && keys(4).len()<40);
    let s=session(Some(4));let barrier=Arc::new(std::sync::Barrier::new(2));
    std::thread::scope(|scope| { for _ in 0..2 {
        let s=s.clone();let barrier=barrier.clone();scope.spawn(move || {
            let trace=make_trace(&s,"thread");let task=trace.task([0,0]);let a=anchor(3,4,100);
            let l=load(110);barrier.wait();end(l,150);end_anchor(a,200);finish(trace,task);
        });
    }});
    for row in s.roots.lock().unwrap().iter() {
        let c=&row["anchor_cpu"]["totals"]["column_load"];
        assert_eq!(c["valid"],true);assert_eq!(c["load_own_cpu_ns"],40);assert_eq!(c["sampled_anchor_own_cpu_ns"],100);
    }
}

#[test]
fn load_counter_cpu_overflow_and_invalid_targets_leave_totals_unavailable() {
    for value in [Totals { calls:u64::MAX, ..Totals::default() },
        Totals { own:u64::MAX, ..Totals::default() }] {
        let mut total=value;total.add(&Totals { calls:1, own:1, ..Totals::default() });
        assert_eq!(total.json()["valid"],false);assert_eq!(total.json()["load_own_cpu_ns"],Value::Null);
    }
    let s=session(Some(0));let trace=make_trace(&s,"invalid-target");let task=trace.task([0,0]);
    let a=anchor(90,0,100);end(load(110),120);end_anchor(a,150);finish(trace,task);
    assert_eq!(cols(&read_cpu(&s,0))["valid"],false);
    let mut state=State::default();state.totals.own=60;
    let value=state.finish(&Observation { inclusive:Some(50),own:Some(50), ..Observation::default() },Some(50)).json();
    assert_eq!(value["valid"],false);assert_eq!(value["load_own_cpu_ns"],Value::Null);
}

#[test]
fn column_load_clock_outside_anchor_end_cannot_pass_by_having_short_duration() {
    let s=session(Some(0));let trace=make_trace(&s,"outside-end");let task=trace.task([0,0]);
    let a=anchor(0,0,100);end(load(190),210);end_anchor(a,200);finish(trace,task);
    assert_eq!(cols(&read_cpu(&s,0))["valid"],false);
}
