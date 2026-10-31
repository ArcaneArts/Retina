//! Immutable interpreter replacements: a submitted job keeps its original
//! pipelines/bindings while later jobs may select the completed optimized set.
use super::*;
use crate::startup::{InterpreterPolicy, with_interpreter_policy};

impl State {
    pub(crate) fn pending(&self) -> bool {
        self.result.lock().unwrap_or_else(|e| e.into_inner()).is_none()
    }
}

impl Pipelines {
    #[cfg(test)]
    pub(crate) fn initial_only(&self) -> Self {
        let mut initial = self.clone(); initial.promotion = None; initial
    }
    pub(crate) fn promoted(&self) -> Option<Arc<Self>> {
        self.promotion.as_ref()?.ready(false).ok().flatten()
    }
    pub(crate) fn promotion_pending(&self) -> bool {
        self.promotion.as_ref().is_some_and(|state| state.pending())
    }
    pub(crate) fn promotion_progress(&self) -> Option<Arc<Progress>> {
        self.promotion.as_ref().map(|state| state.progress.clone()).or_else(|| self.optimization_progress.clone())
    }
}

impl Compiler {
    #[cfg(test)]
    pub(crate) fn exercise_tiered_lifecycle_on_metal(&mut self) {
        // Validate publication/material lifetimes on the established local
        // backend even when a portability driver cannot translate a shader.
        self.tiered_interpreter = true;
    }
    pub(super) fn prepare_optimized(&mut self, source: &str, reuse: Option<interpreter_reuse::Plan>, shared: bool)
        -> (Arc<State>, Option<Job>) {
        let key = (source.to_owned(), reuse, shared);
        if let Some(state) = self.optimized_interpreters.get(&key) { return (state, None); }
        let state = Arc::new(State { progress: Arc::new(Progress {
            status: std::sync::atomic::AtomicU32::new(1), source_bytes: source.len() as u64,
            ..Default::default()
        }), ..Default::default() });
        let job = Job {
            interpreter: Some(reuse), shared_dispatch: shared, policy: InterpreterPolicy::Optimized,
            promotion: None, program: key.0.clone(), state: Arc::downgrade(&state),
            incremental: false,
            horizontal_fields: 0, material_layers: true, aquifers: 2, terrain: true,
            cached_masks: false, cached_nodes: false, lake_point_cache: false,
            material_dispatch: false, specialized_interpolation: false,
            #[cfg(test)] reference_specialization: false,
        };
        self.optimized_interpreters.insert(key, state.clone());
        (state, Some(job))
    }

    pub(crate) fn compile_interpreter_on_demand(&mut self, device: &wgpu::Device,
        world: &wgpu::BindGroupLayout, cave: &wgpu::BindGroupLayout, source: &str,
        reuse: Option<interpreter_reuse::Plan>, shared: bool, cache: Option<&crate::pipeline_cache::Cache>)
        -> Result<Pipelines, String> {
        let policy = if self.tiered_interpreter { InterpreterPolicy::Fast } else { InterpreterPolicy::Inherited };
        let first = with_interpreter_policy(policy, || compile_interpreter(device, world, cave, source, reuse, shared,
            if self.tiered_interpreter { self.terrain_fast_cache.as_deref() } else { cache }));
        let mut pipelines = match first {
            Err(error) if self.tiered_interpreter => {
                crate::startup::info(format_args!("Fast interpreter unavailable: {error}; compiling normally"));
                return with_interpreter_policy(InterpreterPolicy::Optimized,
                    || compile_interpreter(device, world, cave, source, reuse, shared, cache));
            }
            result => result?,
        };
        if self.tiered_interpreter {
            let (promotion, job) = self.prepare_optimized(source, reuse, shared);
            if let Some(job) = job {
                if self.sender.send(job).is_err() {
                    *promotion.result.lock().unwrap_or_else(|e| e.into_inner()) = Some(Err("GPU shader compiler stopped".into()));
                    promotion.progress.status.store(3, std::sync::atomic::Ordering::Release);
                    crate::startup::info("Optional optimized interpreter could not be queued; keeping the initial interpreter");
                }
            }
            pipelines.kind = "interpreter-fast";
            pipelines.promotion = Some(promotion);
        }
        Ok(pipelines)
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    fn pipelines(kind: &'static str, promotion: Option<Arc<State>>) -> Pipelines {
        Pipelines { kind, world: HashMap::new(), cave: HashMap::new(), horizontal_fields: 0,
            complete: true, world_kinds: HashMap::new(), cave_kinds: HashMap::new(),
            reuse: None, dispatches: HashMap::new(), promotion, optimization_progress: None }
    }
    #[test]
    fn promotion_is_published_only_when_complete_and_failure_keeps_initial() {
        let state = Arc::new(State::default());
        let fast = pipelines("interpreter-fast", Some(state.clone()));
        assert!(fast.promotion_pending()); assert!(fast.promoted().is_none());
        *state.result.lock().unwrap() = Some(Err("optional compile failed".into()));
        assert!(!fast.promotion_pending()); assert!(fast.promoted().is_none());
        assert_eq!(fast.kind, "interpreter-fast");
        let optimized = Arc::new(pipelines("interpreter-optimized", None));
        *state.result.lock().unwrap() = Some(Ok(optimized.clone()));
        assert!(Arc::ptr_eq(&fast.promoted().unwrap(), &optimized));
        assert_eq!(fast.kind, "interpreter-fast", "in-flight owner is immutable");
    }
    #[test]
    fn idle_reuse_does_not_keep_obsolete_pending_jobs_alive() {
        let mut cache = reuse_cache::Cache::new(8);
        let state = Arc::new(State::default()); let queued = Arc::downgrade(&state);
        cache.insert("pending", state.clone()); drop(state);
        cache.retain_warm(|state| !state.pending());
        assert!(queued.upgrade().is_none());
    }
    #[test]
    fn completed_replacement_keeps_readiness_for_new_profile_owners() {
        let mut optimized = pipelines("interpreter-optimized", None);
        let progress = Arc::new(Progress::default());
        progress.status.store(2, std::sync::atomic::Ordering::Release);
        optimized.optimization_progress = Some(progress.clone());
        let reused = optimized.with_reuse(Default::default());
        assert_eq!(reused.promotion_progress().unwrap().snapshot().status, 2);
        assert!(!reused.promotion_pending());
    }
}
