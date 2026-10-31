//! Independent interpreter halves with independent driver-cache locks.
use super::*;

#[derive(Clone, Copy, Debug, Eq, PartialEq)]
pub(super) enum Part { All, World, Caves }
pub(crate) struct Caches {
    world: Option<Arc<crate::pipeline_cache::Cache>>,
    caves: Option<Arc<crate::pipeline_cache::Cache>>,
    fast_world: Option<Arc<crate::pipeline_cache::Cache>>,
    fast_caves: Option<Arc<crate::pipeline_cache::Cache>>,
}
impl Caches {
    pub fn new(device: &wgpu::Device, lane: &str, fast: bool) -> Self {
        let fast = fast || std::env::var("RETINA_VULKAN_FAST_COMPILE").as_deref() == Ok("1");
        Self {
            world: crate::pipeline_cache::Cache::open_variant(device, lane, "world-optimized"),
            caves: crate::pipeline_cache::Cache::open_variant(device, lane, "caves-optimized"),
            fast_world: fast.then(|| crate::pipeline_cache::Cache::open_variant(device, lane, "world-fast")).flatten(),
            fast_caves: fast.then(|| crate::pipeline_cache::Cache::open_variant(device, lane, "caves-fast")).flatten(),
        }
    }
    fn get(&self, part: Part, policy: crate::startup::InterpreterPolicy) -> Option<&crate::pipeline_cache::Cache> {
        let fast = policy == crate::startup::InterpreterPolicy::Fast || policy == crate::startup::InterpreterPolicy::Inherited
            && std::env::var("RETINA_VULKAN_FAST_COMPILE").as_deref() == Ok("1");
        match (part, fast) {
            (Part::World, false) => self.world.as_deref(), (Part::World, true) => self.fast_world.as_deref(),
            (Part::Caves, false) => self.caves.as_deref(), (Part::Caves, true) => self.fast_caves.as_deref(),
            (Part::All, _) => None,
        }
    }
}

pub(super) fn compile(device: &wgpu::Device, world: &wgpu::BindGroupLayout, caves: &wgpu::BindGroupLayout,
    program: &str, reuse: Option<interpreter_reuse::Plan>, shared: bool, caches: &Caches) -> Result<Pipelines, String> {
    let policy = crate::startup::interpreter_policy();
    let priority = crate::compile_budget::priority();
    let owner = compile_lifetime::owner();
    crate::startup::message(format_args!("Parallel interpreter world+caves workers=2 policy={policy:?} priority={priority:?}"));
    std::thread::scope(|scope| {
        let run = move |part, owner| compile_lifetime::with_owner(owner, || crate::compile_budget::with_priority(priority, || crate::startup::with_interpreter_policy(policy, ||
            super::compile_interpreter_part(device, world, caves, program, reuse, shared, caches.get(part, policy), part))));
        let world_owner = owner.clone();
        let world_job = std::thread::Builder::new().name("retina-world-compiler".into()).spawn_scoped(scope, move || run(Part::World, world_owner));
        let (world, cave) = match world_job {
            Ok(world_job) => {
                let cave_owner = owner.clone();
                let cave = match std::thread::Builder::new().name("retina-cave-compiler".into()).spawn_scoped(scope, move || run(Part::Caves, cave_owner)) {
                    Ok(job) => job.join().unwrap_or_else(|_| Err("cave interpreter compiler panicked".into())),
                    Err(error) => {
                        crate::startup::info(format_args!("Cave compiler worker unavailable; compiling on caller: {error}"));
                        run(Part::Caves, owner)
                    }
                };
                let world = world_job.join().unwrap_or_else(|_| Err("world interpreter compiler panicked".into()));
                (world?, cave?)
            }
            Err(error) => {
                crate::startup::info(format_args!("World compiler worker unavailable; compiling sequentially: {error}"));
                (run(Part::World, owner.clone())?, run(Part::Caves, owner)?)
            }
        };
        let mut world = world;
        debug_assert!(world.cave.is_empty() && cave.world.is_empty());
        world.cave = cave.cave;
        world.dispatches.extend(cave.dispatches);
        Ok(world)
    })
}

#[cfg(test)]
impl Compiler {
    pub(crate) fn sequential_interpreter_for_test(&mut self) { self.parallel = None; }
}
