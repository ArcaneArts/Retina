//! Inclusive calling-thread CPU endpoints. No interpolation into partial tails.
use super::*;

#[cfg(any(target_os = "linux", target_os = "macos"))]
pub(super) fn now() -> Option<u64> {
    let mut value = std::mem::MaybeUninit::<libc::timespec>::uninit();
    // SAFETY: a live, aligned timespec is read only after successful initialization.
    if unsafe { libc::clock_gettime(libc::CLOCK_THREAD_CPUTIME_ID, value.as_mut_ptr()) } != 0 {
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
pub(super) fn now() -> Option<u64> { None }

pub(super) fn event(event: &Event) -> Value {
    let endpoints = event.cpu[0].zip(event.cpu[1]);
    let cpu = endpoints.and_then(|(a,b)| b.checked_sub(a));
    let wall = event.end.checked_sub(event.start).filter(|&w| w>0);
    json!({"start_ns":event.cpu[0],"end_ns":event.cpu[1],
        "valid":endpoints.map(|(a,b)| b>=a),"duration_ns":cpu,
        "time_over_task_wall":cpu.zip(wall).map(|(c,w)| c as f64/w as f64)})
}

pub(super) fn summarize(workers: &[(String,Vec<Event>)]) -> Value {
    let mut per_worker = Vec::new();
    let (mut missing,mut reversed,mut measured,mut raw_sum,mut union_sum) = (0,0,0,0u64,0u64);
    for (id,events) in workers {
        let mut ranges = Vec::new();
        let (mut absent,mut invalid,mut sum) = (0,0,0u64);
        for event in events {
            match event.cpu[0].zip(event.cpu[1]) {
                None => absent += 1,
                Some((a,b)) if b<a => invalid += 1,
                Some((a,b)) => { ranges.push((a,b));sum += b-a; }
            }
        }
        let present = ranges.len();
        let ns: u64 = union(ranges).iter().map(|(a,b)| b-a).sum();
        missing += absent;reversed += invalid;measured += present;
        raw_sum += sum;union_sum += ns;
        per_worker.push(json!({"worker":id,"tasks":events.len(),"measured_tasks":present,
            "missing_tasks":absent,"reversed_tasks":invalid,
            "cpu_interval_sum_ns":(absent==0 && invalid==0).then_some(sum),
            "cpu_union_ns":(absent==0 && invalid==0).then_some(ns)}));
    }
    let valid = if reversed>0 { Some(false) } else if missing>0 { None } else { Some(true) };
    json!({"valid":valid,"measured_tasks":measured,"missing_tasks":missing,"reversed_tasks":reversed,
        "task_cpu_interval_sum_ns":(valid==Some(true)).then_some(raw_sum),
        "worker_cpu_union_sum_ns":(valid==Some(true)).then_some(union_sum),"per_worker":per_worker})
}

#[test]
fn task_cpu_keeps_missing_and_reversed_samples_and_unions_nested_worker_intervals() {
    let event = |a,b| Event { at:[0,0],start:0,end:100,success:true,cpu:[a,b] };
    let workers = vec![("a".into(),vec![event(Some(10),Some(30)),event(Some(15),Some(20))]),
        ("b".into(),vec![event(Some(1),Some(8))])];
    let result = summarize(&workers);
    assert_eq!(result["valid"],true);
    assert_eq!(result["task_cpu_interval_sum_ns"],32);
    assert_eq!(result["worker_cpu_union_sum_ns"],27);
    assert_eq!(result["measured_tasks"],3);
    assert_eq!(self::event(&workers[0].1[0])["time_over_task_wall"],0.2);
    let mut workers = workers;
    workers[0].1.push(event(None,Some(40)));
    let result = summarize(&workers);
    assert_eq!(result["valid"],Value::Null);
    assert_eq!(result["missing_tasks"],1);
    assert_eq!(result["worker_cpu_union_sum_ns"],Value::Null);
    assert_eq!(result["per_worker"][1]["cpu_union_ns"],7);
    workers[0].1.push(event(Some(50),Some(40)));
    let result = summarize(&workers);
    assert_eq!(result["valid"],false);
    assert_eq!(result["reversed_tasks"],1);
    assert_eq!(result["task_cpu_interval_sum_ns"],Value::Null);
    assert_eq!(self::event(workers[0].1.last().unwrap())["duration_ns"],Value::Null);
}

#[test]
fn task_cpu_uses_the_actual_optional_calling_thread_clock() {
    let a = now();
    let mut n=1u64;
    for i in 1..1000 { n=std::hint::black_box(n.wrapping_mul(i)); }
    std::hint::black_box(n);
    let b = now();
    // Unsupported/error returns stay absent. No elapsed-time or speed threshold.
    if let (Some(a),Some(b))=(a,b) { assert!(b>=a); }
    let session = Arc::new(Session::default());
    let trace = Trace(Some(Root::new(json!({}),session.clone())));
    trace.evaluation_begin(1);
    trace.task([0,0]).finish(true);
    trace.mark("evaluation_end");trace.finish(true);
    let roots=session.roots.lock().unwrap();
    let sample=&roots[0]["occupancy"]["longest_tasks"][0]["thread_cpu"];
    assert_eq!(roots[0]["task_thread_cpu"]["reversed_tasks"],0);
    if sample["start_ns"].is_null() || sample["end_ns"].is_null() {
        assert_eq!(sample["duration_ns"],Value::Null);
    } else { assert_eq!(sample["valid"],true); }
}
