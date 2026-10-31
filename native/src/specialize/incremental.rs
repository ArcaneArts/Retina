//! Publish validated, immutable specialization groups and resolve their missing
//! stages through the exact loaded interpreter rather than an incompatible base.
use super::*;

impl Pipelines {
    pub(crate) fn overlay(&self, fallback: Option<&Self>) -> Self {
        let mut merged = self.clone();
        let Some(fallback) = fallback else { return merged; };
        for (entries, kinds, base, is_world) in [
            (&mut merged.world, &mut merged.world_kinds, &fallback.world, true),
            (&mut merged.cave, &mut merged.cave_kinds, &fallback.cave, false),
        ] {
            for (entry, pipeline) in base {
                let kind = if is_world { fallback.world_kind(entry) } else { fallback.cave_kind(entry) };
                if kind != "fallback" && !entries.contains_key(entry) {
                    entries.insert(entry.clone(), pipeline.clone());
                    kinds.insert(entry.clone(), kind);
                    if let Some(mode) = fallback.dispatches.get(entry) { merged.dispatches.insert(entry.clone(), *mode); }
                }
            }
        }
        merged
    }
}

pub(super) fn publish(state: &Arc<State>, index: u32, label: &str,
    world: &HashMap<String, wgpu::ComputePipeline>, cave: &HashMap<String, wgpu::ComputePipeline>,
    dispatches: &std::cell::RefCell<HashMap<String, u32>>, horizontal_fields: u32) {
    let pipelines = Arc::new(Pipelines {
        kind: "specialized", complete: false, world_kinds: HashMap::new(), cave_kinds: HashMap::new(),
        world: world.clone(), cave: cave.clone(), horizontal_fields, reuse: None,
        dispatches: dispatches.borrow().clone(), promotion: None, optimization_progress: None,
    });
    *state.partial.lock().unwrap_or_else(|e| e.into_inner()) = Some(pipelines.clone());
    state.progress.groups_total.store(3, std::sync::atomic::Ordering::Relaxed);
    state.progress.groups_ready.store(index, std::sync::atomic::Ordering::Release);
    if crate::startup::debug_enabled() {
        crate::startup::message(format_args!("Specialization cohort {}", serde_json::json!({
            "schema_version": 1, "cohort": label, "ready_groups": index, "total_groups": 3,
            "world_pipelines": world.len(), "cave_pipelines": cave.len(),
            "scope": "Validated immutable groups; final compilation result may still be pending."
        })));
    }
    #[cfg(test)]
    wait_for_test(index);
}

#[cfg(test)]
#[derive(Clone)]
struct Gate {
    index: u32, reached: mpsc::Sender<u32>, resume: Arc<Mutex<mpsc::Receiver<()>>>,
}
#[cfg(test)]
static GATE: Mutex<Option<Gate>> = Mutex::new(None);
#[cfg(test)]
pub(crate) struct TestGate { pub reached: mpsc::Receiver<u32>, resume: mpsc::Sender<()>, index: u32 }
#[cfg(test)]
impl TestGate {
    pub fn release(&self) { let _ = self.resume.send(()); }
}
#[cfg(test)]
impl Drop for TestGate {
    fn drop(&mut self) {
        self.release();
        let mut gate = GATE.lock().unwrap_or_else(|e| e.into_inner());
        if gate.as_ref().is_some_and(|g| g.index == self.index) { *gate = None; }
    }
}
#[cfg(test)]
pub(crate) fn pause_at(index: u32) -> TestGate {
    let (send, reached) = mpsc::channel(); let (resume, wait) = mpsc::channel();
    *GATE.lock().unwrap_or_else(|e| e.into_inner()) = Some(Gate { index, reached: send, resume: Arc::new(Mutex::new(wait)) });
    TestGate { reached, resume, index }
}
#[cfg(test)]
fn wait_for_test(index: u32) {
    let gate = GATE.lock().unwrap_or_else(|e| e.into_inner()).clone();
    if let Some(gate) = gate.filter(|gate| gate.index == index) {
        let _ = gate.reached.send(index);
        let _ = gate.resume.lock().unwrap_or_else(|e| e.into_inner()).recv();
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    fn empty(complete: bool) -> Arc<Pipelines> {
        Arc::new(Pipelines { kind: "specialized", complete,
            world_kinds: HashMap::new(), cave_kinds: HashMap::new(), world: HashMap::new(), cave: HashMap::new(),
            horizontal_fields: 0, reuse: None, dispatches: HashMap::new(), promotion: None, optimization_progress: None })
    }
    #[test]
    fn optional_failure_preserves_validated_partial_and_forced_mode_requires_completion() {
        let state = State::default(); let partial = empty(false);
        *state.partial.lock().unwrap() = Some(partial.clone());
        assert!(Arc::ptr_eq(&state.ready(false).unwrap().unwrap(), &partial));
        *state.result.lock().unwrap() = Some(Err("remaining module rejected".into()));
        assert!(Arc::ptr_eq(&state.ready(false).unwrap().unwrap(), &partial));
        assert!(state.ready(true).is_err());
        let complete = empty(true);
        *state.result.lock().unwrap() = Some(Ok(complete.clone()));
        assert!(Arc::ptr_eq(&state.ready(false).unwrap().unwrap(), &complete));
        assert!(!partial.complete);
    }
    #[test]
    fn separate_group_snapshot_preserves_existing_program_abi() {
        assert_eq!(std::mem::size_of::<crate::pipeline::ProgramSnapshot>(), 64);
        assert_eq!(std::mem::size_of::<crate::pipeline::SpecializationSnapshot>(), 32);
        let progress = Progress::default();
        progress.groups_total.store(3, std::sync::atomic::Ordering::Relaxed);
        progress.groups_ready.store(2, std::sync::atomic::Ordering::Release);
        let snapshot = progress.group_snapshot();
        assert_eq!((snapshot.ready_groups, snapshot.total_groups), (2, 3));
    }
}
