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

#[test]
fn outermost_column_calls_fold_same_anchor_recursion_without_reading_clocks() {
    let s=session(Some(0));let trace=make_trace(&s,"recursive-columns");let task=trace.task([0,0]);
    let a=anchor(0,0,100);let first=Span::begin_outermost(||Some(110));
    for _ in 0..3 {
        let mut recursive=Span::begin_outermost(||panic!("recursive column clock"));
        assert!(recursive.key.is_none());recursive.finish(||panic!("recursive finish clock"));
    }
    end(first,160);end(Span::begin_outermost(||Some(170)),190);
    end_anchor(a,200);finish(trace,task);
    let cpu=read_cpu(&s,0);let c=cols(&cpu);
    assert_eq!(c["valid"],true);assert_eq!(c["calls"],2);assert_eq!(c["finished"],2);
    assert_eq!(c["load_own_cpu_ns"],70);assert_eq!(c["sampled_anchor_own_cpu_ns"],100);
}

#[test]
fn outermost_column_coalescing_resets_for_nested_selected_anchors_and_task_contexts() {
    let s=session(Some(9));let trace=make_trace(&s,"outer-columns");let task=trace.task([0,0]);
    let a=anchor(3,9,100);let outer=Span::begin_outermost(||Some(110));
    let child_trace=make_trace(&s,"inner-columns");let child_task=child_trace.task([1,1]);
    let child=anchor(1,9,120);let inner=Span::begin_outermost(||Some(125));
    drop(Span::begin_outermost(||panic!("nested same-anchor clock")));
    end(inner,145);end_anchor(child,150);finish(child_trace,child_task);
    drop(Span::begin_outermost(||panic!("parent still has active column call")));
    end(outer,170);end_anchor(a,200);finish(trace,task);
    let child=read_cpu(&s,0);let parent=read_cpu(&s,1);
    assert_eq!(cols(&child)["valid"],true);assert_eq!(cols(&parent)["valid"],true);
    assert_eq!(cols(&child)["calls"],1);assert_eq!(cols(&parent)["calls"],1);
    assert_eq!(cols(&child)["load_own_cpu_ns"],20);assert_eq!(cols(&parent)["load_own_cpu_ns"],30);
}

#[test]
fn column_path_partition_records_actual_ready_initialized_and_raced_states() {
    let s=session(Some(0));let trace=make_trace(&s,"paths");let task=trace.task([0,0]);
    let a=anchor(3,0,100);let mut at=110;
    for path in [Path::Interior,Path::PublishedTile,Path::PendingTile] {
        for resolved in [None,Some((false,true)),Some((true,false)),Some((false,false))] {
            let mut l=Span::begin_outermost(||Some(at));l.path(path);
            if let Some((built,ready))=resolved { l.resolved(built,ready); }
            end(l,at+6);at+=10;
        }
    }
    let mut l=load(at);l.path(Path::NeighborArchive);end(l,at+6);at+=10;
    end(load(at),at+6);end_anchor(a,300);finish(trace,task);
    let cpu=read_cpu(&s,0);let c=cols(&cpu);let rows=c["paths"].as_array().unwrap();
    assert_eq!(c["valid"],true);assert_eq!(rows.len(),14);
    assert_eq!(c["calls"],14);assert_eq!(c["load_own_cpu_ns"],84);
    for row in rows {
        assert_eq!(row["calls"],1);assert_eq!(row["load_own_cpu_ns"],6);
        assert_eq!(row["load_inclusive_cpu_ns"],6);
    }
    for name in ["interior_initialized","published_tile_ready","pending_tile_became_ready","neighbor_archive"] {
        assert!(rows.iter().any(|r|r["path"]==name));
    }
}

#[test]
fn column_path_keeps_outer_route_across_recursion_and_subtracts_nested_anchor_cpu() {
    let s=session(Some(9));let trace=make_trace(&s,"outer-path");let task=trace.task([0,0]);
    let a=anchor(3,9,100);let mut outer=Span::begin_outermost(||Some(110));
    outer.path(Path::PendingTile);
    let mut recursive=Span::begin_outermost(||panic!("recursive clock"));
    recursive.path(Path::NeighborArchive);recursive.resolved(true,false);drop(recursive);
    let inner_trace=make_trace(&s,"inner-path");let inner_task=inner_trace.task([1,1]);
    let child=anchor(1,9,120);let mut inner=Span::begin_outermost(||Some(125));
    inner.path(Path::Interior);inner.resolved(false,true);end(inner,145);
    end_anchor(child,150);finish(inner_trace,inner_task);
    outer.resolved(true,false);end(outer,170);end_anchor(a,200);finish(trace,task);
    for (index,path,own,inclusive) in [(0,"interior_ready",20,20),(1,"pending_tile_initialized",30,60)] {
        let cpu=read_cpu(&s,index);let c=cols(&cpu);let rows=c["paths"].as_array().unwrap();
        assert_eq!(c["valid"],true);assert_eq!(rows.len(),1);
        assert_eq!(rows[0]["path"],path);assert_eq!(rows[0]["calls"],1);
        assert_eq!(rows[0]["load_own_cpu_ns"],own);assert_eq!(rows[0]["load_inclusive_cpu_ns"],inclusive);
    }
}

#[test]
fn column_path_missing_invalid_and_abandoned_clocks_never_publish_zero_cpu() {
    for root_id in [0,90] {
        let s=session(Some(0));let trace=make_trace(&s,"path-invalid");let task=trace.task([0,0]);
        let a=anchor(root_id,0,100);let mut l=Span::begin(||if root_id==0 { None } else { Some(110) });
        l.path(Path::Interior);l.resolved(true,false);end(l,140);end_anchor(a,150);finish(trace,task);
        let cpu=read_cpu(&s,0);let c=cols(&cpu);assert_ne!(c["valid"],true);
        let rows=c["paths"].as_array().unwrap();assert_eq!(rows.len(),1);
        assert_eq!(rows[0]["calls"],1);assert!(rows[0]["load_own_cpu_ns"].is_null());
        assert!(rows[0]["load_inclusive_cpu_ns"].is_null());
    }
    let s=session(Some(0));let trace=make_trace(&s,"path-abandoned");let task=trace.task([0,0]);
    let a=anchor(0,0,100);let mut stale=load(110);stale.path(Path::PendingTile);
    end_anchor(a,150);stale.resolved(true,false);end(stale,160);finish(trace,task);
    let cpu=read_cpu(&s,0);assert_eq!(cols(&cpu)["valid"],false);
    assert_eq!(cols(&cpu)["paths"],json!([]));
}

#[test]
fn column_path_counter_overflow_and_unreconciled_partition_invalidate_costs() {
    let mut huge=paths::Totals::default();huge.record(2,Some(u64::MAX),Some(u64::MAX));
    let mut one=paths::Totals::default();one.record(2,Some(1),Some(1));
    assert!(huge.add(&one));assert!(!huge.reconciles(2,0,0));
    let total=Totals { calls:2,finished:2,paths:huge, ..Totals::default() };
    assert_eq!(total.json()["valid"],false);
    assert!(total.json()["paths"][0]["load_own_cpu_ns"].is_null());
    let total=Totals { calls:1,finished:1,own:10,inclusive:10, ..Totals::default() };
    assert_eq!(total.json()["valid"],false);
}
