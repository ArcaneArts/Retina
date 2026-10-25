//! Test-only task-local work counts, indexed by the earlier root demanded.
//! No timestamps, events, mutexes, or atomic updates occur on the count path.
use super::*;
mod target_coverage;

#[derive(Clone, Copy)]
#[repr(usize)]
pub(in crate::decoration::dependencies) enum Op {
    ColumnRequest,
    ColumnInterior,
    ColumnResolvedInterior,
    ColumnVersionsSkipped,
    ColumnReuseBoundary,
    ColumnReadyBoundary,
    ColumnMapLookup,
    ColumnBuild,
    ColumnCellReuse,
    ColumnReuseExterior,
    PrefixInterior,
    PrefixUnfinishedInterior,
    PrefixBoundary,
    PrefixLimited,
    AnchorRequest,
    AnchorReady,
    AnchorBuild,
    AnchorMapLookup,
    AnchorIdsMapLookup,
    FootprintMapLookup,
    StructureMapLookup,
    FeatureGpuCall,
    WriterRequest,
    WriterReady,
    WriterPruned,
    BoundaryTargetPrefix,
    BoundaryTargetLimited,
    BoundaryTargetUnfinished,
    BoundaryTargetUnrepresentable,
    BoundaryTargetEmpty,
    BoundaryTargetCovered,
    BoundaryTargetUncovered,
    BoundaryTargetWriterPositions,
}
const NAMES: [&str; 33] = [
    "column_request",
    "column_interior",
    "column_resolved_interior",
    "column_versions_skipped",
    "column_reuse_boundary",
    "column_ready_boundary",
    "column_map_lookup",
    "column_build",
    "column_cell_reuse",
    "column_reuse_exterior",
    "prefix_interior",
    "prefix_unfinished_interior",
    "prefix_boundary",
    "prefix_limited",
    "anchor_request",
    "anchor_ready",
    "anchor_build",
    "anchor_map_lookup",
    "anchor_ids_map_lookup",
    "footprint_map_lookup",
    "structure_map_lookup",
    "feature_gpu_call",
    "writer_request",
    "writer_ready",
    "writer_pruned",
    "boundary_target_prefix",
    "boundary_target_limited",
    "boundary_target_unfinished",
    "boundary_target_unrepresentable",
    "boundary_target_empty",
    "boundary_target_covered",
    "boundary_target_uncovered",
    "boundary_target_writer_positions",
];
#[derive(Default)]
pub(super) struct Buffer {
    rows: Vec<[u64; NAMES.len()]>,
    used: Vec<bool>,
    touched: Vec<usize>,
    invalid_targets: u64,
}
thread_local! { static SPARE: RefCell<Option<Buffer>> = const { RefCell::new(None) }; }
impl Buffer {
    pub(super) fn take(root_count: usize) -> Self {
        let mut buffer = SPARE
            .with(|spare| spare.borrow_mut().take())
            .unwrap_or_default();
        assert!(buffer.touched.is_empty());
        assert_eq!(buffer.invalid_targets, 0);
        buffer.rows.resize(root_count + 1, [0; NAMES.len()]);
        buffer.used.resize(root_count + 1, false);
        buffer
    }
    pub(super) fn add(&mut self, op: Op, root: Option<usize>, amount: u64) {
        if amount == 0 {
            return;
        }
        let none = self.rows.len() - 1;
        let index = match root {
            Some(root) if root < none => root,
            Some(_) => {
                self.invalid_targets += amount;
                none
            }
            None => none,
        };
        if !self.used[index] {
            self.used[index] = true;
            self.touched.push(index);
        }
        self.rows[index][op as usize] += amount;
    }
    pub(super) fn drain_into(mut self, target: &mut Self) {
        if target.rows.is_empty() {
            target.rows.resize(self.rows.len(), [0; NAMES.len()]);
            target.used.resize(self.rows.len(), false);
        }
        assert_eq!(self.rows.len(), target.rows.len());
        for index in self.touched.drain(..) {
            if !target.used[index] {
                target.used[index] = true;
                target.touched.push(index);
            }
            for (to, from) in target.rows[index].iter_mut().zip(&mut self.rows[index]) {
                *to += std::mem::take(from);
            }
            self.used[index] = false;
        }
        target.invalid_targets += std::mem::take(&mut self.invalid_targets);
        // At most one empty scratch buffer per physical thread. No world,
        // column, output, task or root owner is retained by this scratch space.
        SPARE.with(|spare| {
            let mut spare = spare.borrow_mut();
            if spare.is_none() {
                *spare = Some(self);
            }
        });
    }
    pub(super) fn extend(&mut self, other: Self) {
        if other.rows.is_empty() {
            return;
        }
        other.drain_into(self);
    }
    pub(super) fn summary(&self) -> Value {
        let mut totals = [0; NAMES.len()];
        let mut rows = Vec::new();
        for (index, counts) in self.rows.iter().enumerate() {
            if !self.used[index] {
                continue;
            }
            for (total, value) in totals.iter_mut().zip(counts) {
                *total += value;
            }
            let values: BTreeMap<_, _> =
                NAMES.iter().zip(counts).filter(|(_, v)| **v != 0).collect();
            rows.push(json!({"demanded_root":(index + 1 < self.rows.len()).then_some(index),"counts":values}));
        }
        let totals: BTreeMap<_, _> = NAMES.iter().zip(totals).collect();
        json!({"enabled":true,"valid":self.invalid_targets==0,"invalid_targets":self.invalid_targets,
            "rows":rows,"totals":totals,"root_slots":self.rows.len().saturating_sub(1),
            "scope":"Counts belong to the dispatched task/root, including nested work. demanded_root is the anchor/output root or the last included root of a column version (before-1); null denotes base terrain or root-independent lookups. Counts are requests/attempted builds, not exclusive CPU costs. column_versions_skipped counts version steps; other fields count calls unless named otherwise. boundary_target fields inspect only prefixes outside the version volume: unfinished, unrepresentable, empty, covered and uncovered partition those calls. Covered means the latest root is completed and every inverse-footprint writer is in the original target rectangle; it does not prove an identity result or safe omission of earlier-root work. Writer positions is a geometric candidate count for covered prefixes, not saved checks or commands. Limited prefixes remain separately counted and retain their exact command limit. No per-call intervals are collected."})
    }
}

pub(in crate::decoration::dependencies) fn count(op: Op, root: Option<usize>) {
    add(op, root, 1);
}
pub(in crate::decoration::dependencies) fn add(op: Op, root: Option<usize>, amount: u64) {
    joins::count_work(op, root, amount);
}
pub(in crate::decoration::dependencies) fn boundary_target_coverage(
    planner: &Planner<'_>, before: usize, x: i32, z: i32, limited: bool,
) {
    joins::with_work(|work| target_coverage::observe(work, planner, before, x, z, limited));
}
pub(super) fn lookup(key: joins::Key) {
    let (op, root) = match key.kind {
        "column_map" => (Op::ColumnMapLookup, key.root.and_then(|v| v.checked_sub(1))),
        "anchor" => (Op::AnchorMapLookup, key.root),
        "anchor_ids" => (Op::AnchorIdsMapLookup, key.root),
        "footprint" => (Op::FootprintMapLookup, key.root),
        "structure_starts" => (Op::StructureMapLookup, key.root),
        "feature_gpu" => (Op::FeatureGpuCall, key.root),
        _ => return,
    };
    count(op, root);
}

#[cfg(test)]
mod tests {
    use super::*;
    fn trace(session: &Arc<Session>, name: &str, tasks: usize) -> Trace {
        let trace = Trace(Some(Root::new(
            json!({"name":name,"root_count":4}),
            session.clone(),
        )));
        trace.evaluation_begin(tasks);
        trace
    }
    fn finish(trace: Trace, success: bool) {
        trace.mark("evaluation_end");
        trace.finish(success);
    }

    #[test]
    fn coverage_inspection_runs_only_in_an_aggregate_task_context() {
        let observed = std::cell::Cell::new(0);
        joins::with_work(|_| observed.set(observed.get() + 1));
        assert_eq!(observed.get(), 0);
        for aggregate in [false, true] {
            let session = Arc::new(Session { aggregate, ..Session::default() });
            let trace = trace(&session, "coverage context", 1);
            let task = trace.task([0, 0]);
            joins::with_work(|buffer| {
                observed.set(observed.get() + 1);
                buffer.add(Op::BoundaryTargetCovered, Some(2), 1);
            });
            task.finish(true);
            finish(trace, true);
            assert_eq!(observed.get(), usize::from(aggregate));
            if aggregate {
                assert_eq!(session.roots.lock().unwrap()[0]["dependency_work"]["totals"]["boundary_target_covered"], 1);
            }
        }
        joins::with_work(|_| observed.set(observed.get() + 1));
        assert_eq!(observed.get(), 1);
    }

    #[test]
    fn aggregate_counts_preserve_target_mapping_and_empty_scratch_reuse() {
        let mut total = Buffer::default();
        let mut buffer = Buffer::take(4);
        buffer.add(Op::ColumnRequest, Some(1), 7);
        buffer.add(Op::ColumnVersionsSkipped, Some(3), 23);
        buffer.add(Op::ColumnBuild, None, 2);
        buffer.add(Op::AnchorRequest, Some(99), 1);
        buffer.drain_into(&mut total);
        let report = total.summary();
        assert_eq!(report["valid"], false);
        assert_eq!(report["invalid_targets"], 1);
        assert_eq!(report["totals"]["column_versions_skipped"], 23);
        assert_eq!(report["rows"][0]["demanded_root"], 1);
        assert_eq!(report["rows"][2]["demanded_root"], Value::Null);
        for roots in [1, 8, 4] {
            let mut buffer = Buffer::take(roots);
            assert!(buffer.rows.iter().flatten().all(|&v| v == 0));
            assert!(buffer.used.iter().all(|&v| !v));
            buffer.add(Op::ColumnRequest, None, 0);
            assert!(buffer.touched.is_empty());
            buffer.drain_into(&mut Buffer::default());
        }
    }

    #[test]
    fn aggregate_mode_matches_detailed_lookup_calls_without_timed_event_buffers() {
        let mut reports = Vec::new();
        for aggregate in [false, true] {
            let session = Arc::new(Session {
                aggregate,
                ..Session::default()
            });
            let trace = trace(&session, "same memo work", 1);
            let task = trace.task([0, 0]);
            let cache: Memo<i32, u8> = Memo::default();
            for _ in 0..3 {
                let value = memo(
                    &cache,
                    7,
                    &Metrics::default(),
                    joins::Key::new("anchor", Some(3), 7, 0),
                    || Ok(42),
                )
                .unwrap();
                assert_eq!(*value, 42);
            }
            for before in [0, 2] {
                let mut lookup =
                    joins::LookupCall::start(joins::Key::new("column_map", Some(before), 0, 0));
                lookup.acquired(true);
                lookup.finish(true);
            }
            if aggregate {
                LOCAL.with(|local| {
                    let local = local.borrow();
                    let worker = local.worker.as_ref().unwrap();
                    assert!(worker.joins.lock().unwrap().is_empty());
                    assert!(worker.terrain.lock().unwrap().is_empty());
                    assert!(worker.lookup.lock().unwrap().is_empty());
                });
            }
            task.finish(true);
            finish(trace, true);
            reports.push(session.roots.lock().unwrap()[0].clone());
        }
        let detailed = &reports[0]["lookup_attribution"]["categories"];
        let work = &reports[1]["dependency_work"];
        assert_eq!(work["valid"], true);
        assert_eq!(
            work["totals"]["anchor_map_lookup"],
            detailed["anchor_lookup"]["events"]
        );
        assert_eq!(
            work["totals"]["column_map_lookup"],
            detailed["column_map_lookup"]["events"]
        );
        assert_eq!(work["rows"][0]["demanded_root"], 1);
        assert_eq!(work["rows"][2]["demanded_root"], Value::Null);
        assert_eq!(reports[1]["context_validation"]["valid"], true);
        assert_eq!(reports[1]["lookup_attribution"]["enabled"], false);
        assert_eq!(reports[1]["lookup_attribution"]["events"], Value::Null);
        assert_eq!(reports[0]["dependency_work"]["enabled"], false);
    }

    #[test]
    fn aggregate_nested_unwind_restores_outer_counts_and_releases_root_owners() {
        let session = Arc::new(Session {
            aggregate: true,
            ..Session::default()
        });
        let outer = trace(&session, "outer", 1);
        let task = outer.task([1, 1]);
        count(Op::ColumnRequest, Some(1));
        let panic = std::panic::catch_unwind(std::panic::AssertUnwindSafe(|| {
            let inner = trace(&session, "inner", 1);
            let _task = inner.task([2, 2]);
            count(Op::ColumnRequest, Some(2));
            panic!("original generation panic");
        }));
        assert!(panic.is_err());
        count(Op::ColumnRequest, Some(1));
        task.finish(true);
        finish(outer, true);
        let roots = session.roots.lock().unwrap();
        assert_eq!(roots[0]["abandoned"], true);
        assert_eq!(roots[0]["failed_tasks"], 1);
        assert_eq!(roots[0]["dependency_work"]["totals"]["column_request"], 1);
        assert_eq!(roots[1]["dependency_work"]["totals"]["column_request"], 2);
        assert_eq!(roots[1]["context_validation"]["valid"], true);
        assert_eq!(
            roots[1]["dependency_work"]["rows"]
                .as_array()
                .unwrap()
                .len(),
            1
        );
        LOCAL.with(|local| {
            let local = local.borrow();
            let worker = local.worker.as_ref().unwrap();
            assert!(worker.closed.load(Ordering::Relaxed));
            assert!(worker.work.lock().unwrap().rows.is_empty());
        });
        SPARE.with(|spare| {
            if let Some(buffer) = spare.borrow().as_ref() {
                assert!(buffer.touched.is_empty());
                assert!(buffer.rows.iter().flatten().all(|&v| v == 0));
            }
        });
    }

    #[test]
    fn aggregate_counts_merge_physical_workers_and_preserve_memo_failure() {
        let session = Arc::new(Session {
            aggregate: true,
            ..Session::default()
        });
        let trace = trace(&session, "workers", 8);
        let cache: Memo<i32, u8> = Memo::default();
        std::thread::scope(|scope| {
            for i in 0..8 {
                let trace = &trace;
                let cache = &cache;
                scope.spawn(move || {
                    let task = trace.task([i, 0]);
                    for _ in 0..100 {
                        count(Op::ColumnRequest, Some((i % 4) as usize));
                        let value = memo(
                            cache,
                            0,
                            &Metrics::default(),
                            joins::Key::new("anchor", Some(3), 0, 0),
                            || Err("original failure".into()),
                        );
                        assert_eq!(value.unwrap_err(), "original failure");
                    }
                    task.finish(false);
                });
            }
        });
        finish(trace, false);
        let roots = session.roots.lock().unwrap();
        let root = &roots[0];
        assert_eq!(root["success"], false);
        assert_eq!(root["failed_tasks"], 8);
        assert_eq!(root["context_validation"]["valid"], true);
        assert_eq!(root["dependency_work"]["totals"]["column_request"], 800);
        assert_eq!(root["dependency_work"]["totals"]["anchor_map_lookup"], 800);
        for row in root["dependency_work"]["rows"].as_array().unwrap() {
            assert_eq!(row["counts"]["column_request"], 200);
        }
    }
}
