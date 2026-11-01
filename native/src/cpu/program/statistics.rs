//! Job-local diagnostic counts, flushed once when the evaluator is dropped.
//! Const-generic callers remove recording code from normal execution.
use crate::{performance::Counter, timings::Timings};
use std::sync::{
    Arc,
    atomic::{AtomicU64, Ordering},
};

macro_rules! events {
    ($($name:ident),+ $(,)?) => {
        #[derive(Clone, Copy)]
        #[allow(non_camel_case_types)]
        pub(super) enum Event { $($name,)+ Count }
        const COUNTERS: &[Counter] = &[$(Counter::$name,)+];
    };
}
events! {
    cpu_vm_samplers, cpu_vm_calls, cpu_vm_node_visits, cpu_vm_ready_hits,
    cpu_vm_terrain_calls, cpu_vm_material_calls, cpu_vm_auxiliary_calls, cpu_vm_field_calls,
    cpu_vm_computed_nodes, cpu_vm_constant_hits,
    cpu_vm_horizontal_lookups, cpu_vm_horizontal_hits, cpu_vm_horizontal_clears,
    cpu_vm_interpolations, cpu_vm_cell_lookups, cpu_vm_cell_hits, cpu_vm_cell_vertices_reused,
    cpu_vm_corner_lookups, cpu_vm_corner_hits,
    cpu_vm_plain_corner_lookups, cpu_vm_plain_corner_hits, cpu_vm_corner_clears,
    cpu_vm_noise_profiles, cpu_vm_blended_profiles, cpu_vm_noise3_calls,
}

pub(super) struct Statistics {
    timings: Arc<Timings>,
    // Preserve Evaluator's shared-noise Send/Sync contract. These counters are
    // evaluator-local, never a globally contended per-node counter.
    counts: [AtomicU64; Event::Count as usize],
}
impl Statistics {
    pub(super) fn new(timings: Arc<Timings>) -> Self {
        let value = Self {
            timings,
            counts: std::array::from_fn(|_| AtomicU64::new(0)),
        };
        value.add(Event::cpu_vm_samplers, 1);
        value
    }
    #[inline]
    pub(super) fn add(&self, event: Event, amount: u64) {
        self.counts[event as usize].fetch_add(amount, Ordering::Relaxed);
    }
    #[cfg(test)]
    pub(super) fn get(&self, event: Event) -> u64 {
        self.counts[event as usize].load(Ordering::Relaxed)
    }
}
impl Drop for Statistics {
    fn drop(&mut self) {
        for (counter, value) in COUNTERS.iter().zip(&self.counts) {
            self.timings
                .performance
                .add(*counter, value.load(Ordering::Relaxed));
        }
    }
}
