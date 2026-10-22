//! Capture acquisition endpoints without taking a collector lock while the
//! observed cache/GPU lock is held. The caller finishes after releasing it.
use super::*;

pub(crate) struct LookupCall {
    candidate: Option<Candidate>,
    acquired: Option<(u64, bool)>,
}
impl LookupCall {
    pub(crate) fn start(key: Key) -> Self {
        Self { candidate: Some(Candidate::start(true,key)), acquired: None }
    }
    pub(crate) fn acquired(&mut self, success: bool) {
        assert!(self.acquired.is_none(), "lookup acquisition recorded twice");
        self.acquired = self.candidate.as_ref().and_then(Candidate::current_time)
            .map(|end| (end,success));
    }
    pub(crate) fn finish(mut self, success: bool) { self.record(success); }
    fn record(&mut self, success: bool) {
        let Some(candidate) = self.candidate.take() else { return; };
        let Some((_,_,_,key)) = candidate.stamp else { return; };
        let end = candidate.current_time();
        let (lock_kind,call_kind) = match key.kind {
            "anchor" => ("anchor_lock","anchor_lookup"),
            "anchor_ids" => ("anchor_ids_lock","anchor_ids_lookup"),
            "footprint" => ("footprint_lock","footprint_lookup"),
            "structure_starts" => ("structure_starts_lock","structure_starts_lookup"),
            "column_map" => ("column_map_lock","column_map_lookup"),
            "feature_gpu" => ("feature_gpu_lock","feature_gpu_call"),
            _ => ("memo_lock","memo_lookup"),
        };
        assert!(!success || self.acquired.is_some(), "successful lookup missing acquisition");
        if let Some((acquired,ok)) = self.acquired.take() {
            candidate.clone().record(ok, Bucket::Lookup, Some(lock_kind), Some(acquired));
        }
        candidate.record(success, Bucket::Lookup, Some(call_kind), end);
    }
}
impl Drop for LookupCall {
    fn drop(&mut self) { self.record(false); }
}

#[cfg(test)]
mod tests {
    use super::*;
    use std::{sync::mpsc, time::Duration};
    fn trace(session: &Arc<Session>, name: &str) -> Trace {
        let trace=Trace(Some(Root::new(json!({"name":name}),session.clone())));
        trace.evaluation_begin(1);trace
    }
    fn finish(trace: Trace, success: bool) {
        trace.mark("evaluation_end");trace.finish(success);
    }

    #[test]
    fn lookup_probe_records_real_cache_contention_poison_and_releases_buffers() {
        let session=Arc::new(Session::default());
        let cache: Memo<i32,u8>=Memo::default();
        let held=cache.shard(&1).lock().unwrap();
        let (attempt,attempted)=mpsc::channel();let (done,result)=mpsc::channel();
        std::thread::scope(|scope| {
            let session=session.clone();let cache=&cache;
            let worker=scope.spawn(move || {
                let trace=trace(&session,"contended");let task=trace.task([1,2]);
                attempt.send(()).unwrap();
                let value=memo(cache,1,&Metrics::default(),Key::new("anchor",Some(3),1,2),|| Ok(42));
                task.finish(value.is_ok());finish(trace,value.is_ok());done.send(value).unwrap();
            });
            let started=attempted.recv_timeout(Duration::from_secs(5));
            let early=result.try_recv();
            // Always release the owner before assertions or scoped-thread join.
            drop(held);
            let value=result.recv_timeout(Duration::from_secs(5));
            let joined=worker.join();
            assert!(started.is_ok());assert!(matches!(early,Err(mpsc::TryRecvError::Empty)));
            assert_eq!(*value.unwrap().unwrap(),42);assert!(joined.is_ok());
        });
        let poison: Memo<i32,u8>=Memo::default();
        std::thread::scope(|scope| {
            assert!(scope.spawn(|| { let _held=poison.shard(&1).lock().unwrap();panic!("original poison"); }).join().is_err());
        });
        let trace=trace(&session,"poison");let task=trace.task([1,2]);
        let value=memo(&poison,1,&Metrics::default(),Key::new("anchor",Some(3),1,2),|| Ok(0));
        assert_eq!(value.unwrap_err(),"decoration dependency cache poisoned");
        task.finish(false);finish(trace,false);
        let roots=session.roots.lock().unwrap();
        for (i,failed) in [0,2].into_iter().enumerate() {
            let result=&roots[i]["lookup_attribution"];
            assert_eq!(result["valid"],true);assert_eq!(result["events"],2);
            assert_eq!(result["failed_events"],failed);
            assert_eq!(result["categories"]["anchor_lock"]["events"],1);
            assert_eq!(result["categories"]["anchor_lookup"]["events"],1);
            let lock=result["longest_joins"].as_array().unwrap().iter().find(|x| x["kind"]=="anchor_lock").unwrap();
            let lookup=result["longest_joins"].as_array().unwrap().iter().find(|x| x["kind"]=="anchor_lookup").unwrap();
            assert_eq!(lock["start_ns"],lookup["start_ns"]);
            assert!(lock["end_ns"].as_u64().unwrap()<=lookup["end_ns"].as_u64().unwrap());
            assert_eq!(result["worker_join_union_sum_ns"],lookup["duration_ns"]);
        }
        LOCAL.with(|l| assert!(l.borrow().worker.as_ref().unwrap().lookup.lock().unwrap().events.is_empty()));
    }

    #[test]
    fn lookup_probe_unwind_preserves_acquisition_success_and_restores_outer_context() {
        let session=Arc::new(Session::default());
        let outer=trace(&session,"outer");let task=outer.task([0,0]);
        let lock=Mutex::new(0);
        let panic=std::panic::catch_unwind(std::panic::AssertUnwindSafe(|| {
            let inner=trace(&session,"inner");let _task=inner.task([1,1]);
            let mut probe=LookupCall::start(Key::new("feature_gpu",None,1,1));
            let acquired=lock.lock();probe.acquired(acquired.is_ok());let _held=acquired.unwrap();
            panic!("feature batch failure");
        }));
        assert!(panic.is_err());
        // Poison retains a guard but the unwind released the original holder.
        match lock.try_lock() {
            Err(std::sync::TryLockError::Poisoned(error)) => drop(error.into_inner()),
            _ => panic!("expected the released, poisoned mutex"),
        }
        let mut probe=LookupCall::start(Key::new("anchor_ids",None,2,2));
        let lock=Mutex::new(0);let acquired=lock.lock();probe.acquired(acquired.is_ok());drop(acquired);
        probe.finish(true);task.finish(true);finish(outer,true);
        let roots=session.roots.lock().unwrap();let inner=&roots[0]["lookup_attribution"];
        assert_eq!(roots[0]["abandoned"],true);assert_eq!(inner["valid"],Value::Null);
        assert_eq!(inner["events"],2);assert_eq!(inner["failed_events"],1);
        let events=inner["longest_joins"].as_array().unwrap();
        assert_eq!(events.iter().find(|x| x["kind"]=="feature_gpu_lock").unwrap()["success"],true);
        assert_eq!(events.iter().find(|x| x["kind"]=="feature_gpu_call").unwrap()["success"],false);
        let outer=&roots[1]["lookup_attribution"];
        assert_eq!(outer["valid"],true);assert_eq!(outer["events"],2);assert_eq!(outer["failed_events"],0);
        assert!(outer["longest_joins"].as_array().unwrap().iter().all(|x| x["at"]==json!([2,2])));
    }
}
