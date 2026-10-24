#[cfg(test)]
use super::super::worker_phase_probe::Trace;
use super::super::{Perf, Planner};
use std::time::Instant;

struct Sequence<'state, 'world> {
    planner: &'state mut Planner<'world>,
    targets: &'state mut Vec<(usize, i32, i32)>,
    anchors: Vec<(i32, i32)>,
    side: usize,
    started: Instant,
    queued: Option<Instant>,
    diagnostic: bool,
    #[cfg(test)]
    trace: Option<Trace>,
}

pub(in crate::decoration::dependencies) fn evaluate(
    pool: &rayon::ThreadPool,
    planner: &mut Planner<'_>,
    targets: &mut Vec<(usize, i32, i32)>,
    side: usize,
    started: Instant,
    diagnostic: bool,
) -> Result<(), String> {
    let roots = planner.roots.len();
    #[cfg(test)]
    let trace = (roots > 0).then(|| Trace::start_continuation(planner, 0));
    let queued = planner.metrics.performance.start();
    super::run(
        pool,
        &mut Sequence {
            planner,
            targets,
            anchors: Vec::new(),
            side,
            started,
            queued,
            diagnostic,
            #[cfg(test)]
            trace,
        },
        roots,
    )
}

impl super::Steps for Sequence<'_, '_> {
    fn prepare(&mut self, root: usize) -> Result<usize, String> {
        if root == 0 {
            self.planner
                .metrics
                .performance
                .elapsed(Perf::dependency_pool_wait_nanos, self.queued.take());
        }
        #[cfg(test)]
        {
            if self.trace.is_none() {
                self.trace = Some(Trace::start_continuation(self.planner, root));
            }
            self.trace.as_ref().unwrap().mark("dispatch_enter");
        }
        let request = self.planner.request;
        self.anchors = self.planner.roots[root].bounds.writers_area(
            [request.chunk_x * 16, request.chunk_z * 16],
            [
                (request.chunk_x + self.side as i32) * 16 - 1,
                (request.chunk_z + self.side as i32) * 16 - 1,
            ],
        )?;
        #[cfg(test)]
        self.trace
            .as_ref()
            .unwrap()
            .evaluation_begin(self.anchors.len());
        Ok(self.anchors.len())
    }

    fn run(&self, root: usize, index: usize) -> Result<(), String> {
        let (ax, az) = self.anchors[index];
        #[cfg(test)]
        let task = self.trace.as_ref().unwrap().task([ax, az]);
        let result = self.planner.anchor(root, ax, az).map(|_| ());
        #[cfg(test)]
        task.finish(result.is_ok());
        result
    }

    fn publish(&mut self, root: usize) -> Result<(), String> {
        #[cfg(test)]
        self.trace.as_ref().unwrap().mark("evaluation_end");
        self.planner.complete(root, &self.anchors)?;
        self.targets
            .extend(self.anchors.iter().map(|&(ax, az)| (root, ax, az)));
        #[cfg(test)]
        self.trace.as_ref().unwrap().mark("publication_end");
        Ok(())
    }

    fn settled(&mut self, root: usize, success: bool) {
        #[cfg(test)]
        if let Some(trace) = self.trace.take() {
            trace.finish(success);
        }
        if success && self.diagnostic {
            let planner = &self.planner;
            eprintln!(
                "Retina dependency root {root}/{}: anchors={}, column_tiles={}, tiles={}, elapsed_ms={:.1}, request={},{}",
                planner.roots.len(),
                planner.ready.len(),
                planner.columns.len() + planner.ready_columns.len(),
                planner.terrain.len(),
                self.started.elapsed().as_secs_f64() * 1000.,
                planner.request.chunk_x,
                planner.request.chunk_z,
            );
        }
    }
}
