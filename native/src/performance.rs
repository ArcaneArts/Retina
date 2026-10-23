//! Opt-in, per-profile performance attribution. Durations from concurrent
//! requests and recursive workers overlap; they are not elapsed-time shares.
use crate::CacheKey;
use std::collections::{BTreeMap, HashMap, HashSet};
use std::sync::{
    atomic::{AtomicU64, Ordering},
    Mutex, OnceLock,
};
use std::time::Instant;

macro_rules! counters {
    ($($name:ident),+ $(,)?) => {
        #[derive(Clone, Copy)]
        #[allow(non_camel_case_types)]
        pub(crate) enum Counter { $($name,)+ Count }
        const NAMES: &[&str] = &[$(stringify!($name),)+];
    };
}
counters! {
    dependency_evaluation_nanos, final_structure_planning_nanos,
    distribution_nanos, teardown_nanos, region_pool_wait_nanos,
    teardown_commands_nanos, teardown_versions_nanos, teardown_outputs_nanos,
    teardown_terrain_nanos, teardown_boundary_columns_nanos, teardown_other_nanos,
    memo_requests, memo_builds, memo_ready_hits, memo_lock_nanos, memo_join_nanos,
    column_requests, column_builds, column_tiles, column_lock_nanos, column_join_nanos,
    deferred_ore_roots, deferred_ore_candidates, height_roots_skipped,
    dependency_pool_wait_nanos,
    view_column_cached_reads, view_column_loads, view_column_slot_hits,
    view_terrain_borrowed_reads, view_terrain_loaded_reads,
    blob_mask_reads, blob_mask_builds, blob_mask_hits, blob_mask_scalar_fallbacks,
    blob_mask_cache_allocations, blob_mask_cache_bytes,
    overlay_recommit_calls_skipped, overlay_recommit_commands_skipped,
    blob_overlay_observations, blob_overlay_observed_payload_bytes,
    blob_overlay_observed_points, blob_overlay_promotions,
    anchor_builds, anchor_ready_hits, prefix_evaluations,
    uniform_blob_prefixes, uniform_blob_commands, uniform_blob_changed_columns,
    blob_index_outputs, blob_index_writes, blob_index_columns,
    blob_index_extra_value_bytes, blob_index_rejected_outputs, blob_index_prepare_nanos,
    blob_index_fast_prefixes, blob_index_covered_commands, blob_index_mask_merges,
    footprint_builds, footprint_pruned_writers,
    column_versions_skipped, completed_column_commands, completed_column_groups, completed_column_reads, completed_boundary_reads,
    terrain_job_requests, terrain_initial_tiles_reused,
    terrain_job_snapshot_nanos, terrain_job_cached_tiles_retained, terrain_job_cached_tile_hits,
    terrain_job_direct_requests, terrain_job_fallback_requests,
    terrain_job_lock_nanos, terrain_job_join_nanos, shared_material_columns,
    terrain_requests, terrain_cache_hits, terrain_exact_tile_cache_hits, terrain_generated_tiles,
    terrain_unique_tiles, terrain_generated_chunks,
    structure_plan_calls, structure_candidates, structure_unique_candidates,
    structure_cache_hits, structure_cache_misses, structure_cache_evictions,
    structure_pending_evictions, structure_builds, structure_repeated_builds,
    structure_overlapping_builds, structure_active_hits, structure_job_cache_hits,
    structure_discovery_tiles, structure_fossil_batches, structure_fossil_columns,
    structure_fossil_biome_points,
    structure_fossil_biome_retained, structure_fossil_biome_queried,
    structure_probe_points,
    version_cache_capture_nanos, version_cache_export_nanos, version_cache_publish_nanos,
    version_cache_captured_regions, version_cache_boundary_lookups, version_cache_boundary_hits,
    version_cache_exported_regions, version_cache_exported_bytes, version_cache_export_skips,
    version_cache_published_regions, version_cache_evicted_regions,
    version_cache_exterior_lookups, version_cache_exterior_hits,
    version_cache_exterior_exported_slots, version_cache_exterior_exported_spans,
    version_cache_exterior_exported_states, version_cache_exterior_exported_bytes,
}
use Counter::*;

#[derive(Default)]
struct Seen {
    candidates: HashSet<(CacheKey, usize)>,
    builds: HashSet<(CacheKey, usize)>,
    active_builds: HashMap<(CacheKey, usize), u64>,
    terrain: HashMap<(CacheKey, u32), u64>,
}
struct Enabled {
    counts: [AtomicU64; Counter::Count as usize],
    seen: Mutex<Seen>,
}
pub(crate) struct Metrics(Option<Box<Enabled>>);

pub(crate) fn enabled() -> bool {
    static ENABLED: OnceLock<bool> = OnceLock::new();
    *ENABLED.get_or_init(|| std::env::var("RETINA_PERFORMANCE_DIAGNOSTICS").as_deref() == Ok("1"))
}
impl Default for Metrics {
    fn default() -> Self {
        Self::new(enabled())
    }
}
impl Metrics {
    fn new(enabled: bool) -> Self {
        Self(enabled.then(|| {
            Box::new(Enabled {
                counts: std::array::from_fn(|_| AtomicU64::new(0)),
                seen: Mutex::new(Seen::default()),
            })
        }))
    }
    #[inline]
    pub(crate) fn add(&self, counter: Counter, value: u64) {
        if let Some(inner) = &self.0 {
            inner.counts[counter as usize].fetch_add(value, Ordering::Relaxed);
        }
    }
    #[inline]
    pub(crate) fn start(&self) -> Option<Instant> {
        self.0.as_ref().map(|_| Instant::now())
    }
    pub(crate) fn elapsed(&self, counter: Counter, start: Option<Instant>) {
        if let Some(start) = start {
            self.add(counter, start.elapsed().as_nanos() as u64);
        }
    }
    pub(crate) fn candidate(&self, key: (CacheKey, usize)) {
        if let Some(inner) = &self.0 {
            self.add(structure_candidates, 1);
            if inner
                .seen
                .lock()
                .unwrap_or_else(|e| e.into_inner())
                .candidates
                .insert(key)
            {
                self.add(structure_unique_candidates, 1);
            }
        }
    }
    pub(crate) fn build(&self, key: (CacheKey, usize)) -> Option<Build<'_>> {
        let inner = self.0.as_ref()?;
        self.add(structure_builds, 1);
        let mut seen = inner.seen.lock().unwrap_or_else(|e| e.into_inner());
        if !seen.builds.insert(key) {
            self.add(structure_repeated_builds, 1);
        }
        let active = seen.active_builds.entry(key).or_default();
        if *active != 0 {
            self.add(structure_overlapping_builds, 1);
        }
        *active += 1;
        Some(Build { inner, key })
    }
    pub(crate) fn terrain(&self, key: CacheKey, side: u32) {
        if let Some(inner) = &self.0 {
            self.add(terrain_generated_tiles, 1);
            self.add(terrain_generated_chunks, u64::from(side) * u64::from(side));
            let mut seen = inner.seen.lock().unwrap_or_else(|e| e.into_inner());
            let count = seen.terrain.entry((key, side)).or_default();
            if *count == 0 {
                self.add(terrain_unique_tiles, 1);
            }
            *count += 1;
        }
    }
    pub(crate) fn snapshot(&self) -> serde_json::Value {
        let counters: BTreeMap<_, _> = NAMES
            .iter()
            .enumerate()
            .map(|(i, name)| {
                (
                    *name,
                    self.0
                        .as_ref()
                        .map_or(0, |v| v.counts[i].load(Ordering::Relaxed)),
                )
            })
            .collect();
        let mut tiles = Vec::new();
        if let Some(inner) = &self.0 {
            let seen = inner.seen.lock().unwrap_or_else(|e| e.into_inner());
            for (&(key, side), &count) in &seen.terrain {
                tiles.push((
                    key.seed,
                    key.profile,
                    key.min_y,
                    key.height,
                    key.base,
                    key.amplitude,
                    key.frequency,
                    key.x,
                    key.z,
                    side,
                    count,
                ));
            }
        }
        tiles.sort_unstable();
        let tiles: Vec<_> = tiles
            .into_iter()
            .map(
                |(seed, profile, min_y, height, base, amplitude, frequency, x, z, side, count)| {
                    serde_json::json!({
                        "seed": seed, "profile": profile, "min_y": min_y, "height": height,
                        "base_bits": base, "amplitude_bits": amplitude, "frequency_bits": frequency,
                        "origin_x": x, "origin_z": z, "side": side, "generated_count": count,
                    })
                },
            )
            .collect();
        serde_json::json!({
            "version": 1, "enabled": self.0.is_some(), "counters": counters,
            "scope": "profile lifetime; concurrent and recursive durations overlap",
            "terrain_tiles": tiles,
            "terrain_tile_scope": "Actual GPU tile origin and side, including guards; counts are profile-lifetime totals, not elapsed time",
        })
    }
}

pub(crate) struct Build<'a> {
    inner: &'a Enabled,
    key: (CacheKey, usize),
}
impl Drop for Build<'_> {
    fn drop(&mut self) {
        let mut seen = self.inner.seen.lock().unwrap_or_else(|e| e.into_inner());
        if let Some(count) = seen.active_builds.get_mut(&self.key) {
            *count -= 1;
            if *count == 0 {
                seen.active_builds.remove(&self.key);
            }
        }
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    fn key(seed: u64) -> (CacheKey, usize) {
        (
            CacheKey::from(crate::ChunkRequest {
                seed,
                chunk_x: 0,
                chunk_z: 0,
                min_y: 0,
                height: 256,
                base_height: 64.,
                amplitude: 48.,
                frequency: 0.008,
                reserved: 1,
            }),
            0,
        )
    }

    #[test]
    fn distinguishes_reuse_repeated_builds_and_distinct_worlds() {
        let m = Metrics::new(true);
        for k in [key(1), key(1), key(2)] {
            m.candidate(k);
            let _build = m.build(k);
            m.terrain(k.0, 16);
        }
        let s = m.snapshot();
        let c = &s["counters"];
        assert_eq!(c["structure_candidates"], 3);
        assert_eq!(c["structure_unique_candidates"], 2);
        assert_eq!(c["structure_builds"], 3);
        assert_eq!(c["structure_repeated_builds"], 1);
        assert_eq!(c["structure_overlapping_builds"], 0);
        assert_eq!(c["terrain_unique_tiles"], 2);
        assert_eq!(c["terrain_generated_chunks"], 3 * 256);
        let tiles = s["terrain_tiles"].as_array().unwrap();
        assert_eq!(tiles.len(), 2);
        assert_eq!(tiles[0]["seed"], 1);
        assert_eq!(tiles[0]["generated_count"], 2);
        assert_eq!(tiles[1]["seed"], 2);
        assert_eq!(tiles[1]["generated_count"], 1);
        assert_eq!(tiles[0]["side"], 16);
    }

    #[test]
    fn terrain_attribution_keeps_full_cache_identity_and_tile_geometry() {
        let m = Metrics::new(true);
        let original = key(1).0;
        for changed in [
            CacheKey { x: -17, ..original },
            CacheKey { z: 16, ..original },
            CacheKey {
                profile: 2,
                ..original
            },
            CacheKey {
                min_y: -64,
                ..original
            },
            CacheKey {
                height: 128,
                ..original
            },
            CacheKey {
                base: 0,
                ..original
            },
            CacheKey {
                amplitude: 0,
                ..original
            },
            CacheKey {
                frequency: 0,
                ..original
            },
        ] {
            m.terrain(changed, 22);
        }
        m.terrain(original, 22);
        m.terrain(original, 38);
        let snapshot = m.snapshot();
        assert_eq!(snapshot["terrain_tiles"].as_array().unwrap().len(), 10);
        assert_eq!(
            snapshot["counters"]["terrain_generated_chunks"],
            9 * 22 * 22 + 38 * 38
        );
        assert!(snapshot["terrain_tiles"]
            .as_array()
            .unwrap()
            .iter()
            .any(|tile| tile["origin_x"] == -17 && tile["side"] == 22));
    }

    #[test]
    fn disabled_metrics_allocate_no_tracking_state() {
        let m = Metrics::new(false);
        m.candidate(key(1));
        let _build = m.build(key(1));
        m.terrain(key(1).0, 16);
        assert!(m.0.is_none());
        assert_eq!(m.snapshot()["terrain_tiles"], serde_json::json!([]));
        assert!(m.start().is_none());
        assert!(m.snapshot()["counters"]
            .as_object()
            .unwrap()
            .values()
            .all(|v| v == 0));
    }

    #[test]
    fn overlapping_builds_remain_active_until_every_builder_finishes() {
        let m = Metrics::new(true);
        let first = m.build(key(1));
        let second = m.build(key(1));
        drop(first);
        let third = m.build(key(1));
        drop(second);
        drop(third);
        let fourth = m.build(key(1));
        let c = m.snapshot();
        assert_eq!(c["counters"]["structure_repeated_builds"], 3);
        assert_eq!(c["counters"]["structure_overlapping_builds"], 2);
        drop(fourth);
        assert!(m
            .0
            .as_ref()
            .unwrap()
            .seen
            .lock()
            .unwrap()
            .active_builds
            .is_empty());
    }
}
