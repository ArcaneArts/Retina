//! Bounded completed interior states, captured once for a neighboring job.
use super::{ColumnCell, ColumnRuns, ColumnState, Map, Metrics, Perf, Versions};
use crate::{CacheKey, ChunkRequest, TerrainEngine, WorldProfile};
use std::collections::VecDeque;
use std::sync::Arc;
mod exterior;
use exterior::Exterior;

#[cfg(test)]
mod footprint_probe;

const MAX_ENTRIES: usize = 8;
const MAX_BYTES: usize = 512 * 1024 * 1024;
// Observed neighboring reads concentrate near region edges. This is only a
// retention policy: omitted columns miss and use the full dependency planner.
const EDGE_WIDTH: usize = 32;
struct Snapshot {
    context: CacheKey,
    region: [i32; 2],
    roots: usize,
    versions: Archived,
    exterior: Option<Box<Exterior>>,
    bytes: usize,
}
const MISSING: u32 = u32::MAX;
struct SavedVersion {
    root: usize,
    state: u32,
}
struct SavedState {
    range: std::ops::Range<usize>,
    heights: [i32; 6],
}
// Immutable flat arrays replace the planner's OnceLocks, per-column vectors,
// ColumnState allocations and retained GPU mask owners. A returned state pins
// only the packed material buffer; it cannot keep a planner or index alive.
struct Archived {
    origin: [i32; 2],
    width: usize,
    base: Box<[u32]>,
    offsets: Box<[usize]>,
    changed: Box<[SavedVersion]>,
    states: Box<[SavedState]>,
    runs: Arc<Vec<u32>>,
}
// Pack the edge band in row-major order, without allocating entries for the
// unused center. Small test/planner grids retain every column.
fn edge_index(width: usize, x: usize, z: usize) -> Option<usize> {
    if x >= width || z >= width {
        return None;
    }
    if width <= 2 * EDGE_WIDTH || z < EDGE_WIDTH {
        return Some(z * width + x);
    }
    let inner = width - 2 * EDGE_WIDTH;
    if z >= width - EDGE_WIDTH {
        return Some(z * width + x - inner * inner);
    }
    let start = EDGE_WIDTH * width + (z - EDGE_WIDTH) * 2 * EDGE_WIDTH;
    if x < EDGE_WIDTH {
        Some(start + x)
    } else if x >= width - EDGE_WIDTH {
        Some(start + x - inner)
    } else {
        None
    }
}
impl Archived {
    fn capture(versions: &Versions) -> Option<Self> {
        let mut owners = Map::<usize, u32>::default();
        let mut states = Vec::new();
        let mut runs = Vec::new();
        let mut intern = |cell: &ColumnCell| -> Option<u32> {
            let Some(value) = cell.get() else {
                return Some(MISSING);
            };
            let value = value.as_ref().ok()?;
            let pointer = Arc::as_ptr(value) as usize;
            if let Some(&id) = owners.get(&pointer) {
                return Some(id);
            }
            let id = u32::try_from(states.len())
                .ok()
                .filter(|&id| id != MISSING)?;
            let start = runs.len();
            runs.extend_from_slice(value.runs.slice());
            states.push(SavedState {
                range: start..runs.len(),
                heights: value.heights,
            });
            owners.insert(pointer, id);
            Some(id)
        };
        // Borrow all original owners throughout interning: allocator address
        // reuse must never turn a different state into a false pointer hit.
        let width = versions.width;
        let inner = width.saturating_sub(2 * EDGE_WIDTH);
        let count = width * width - inner * inner;
        let mut base = Vec::with_capacity(count);
        let mut offsets = Vec::with_capacity(count + 1);
        let mut changed = Vec::new();
        for z in 0..width {
            let full = width <= 2 * EDGE_WIDTH || z < EDGE_WIDTH || z >= width - EDGE_WIDTH;
            let left_end = if full { width } else { EDGE_WIDTH };
            let right_start = if full { width } else { width - EDGE_WIDTH };
            for x in (0..left_end).chain(right_start..width) {
                let index = z * width + x;
                base.push(intern(&versions.base[index])?);
                offsets.push(changed.len());
                for value in &versions.changed[index] {
                    changed.push(SavedVersion {
                        root: value.root,
                        state: intern(&value.state)?,
                    });
                }
            }
        }
        offsets.push(changed.len());
        Some(Self {
            origin: versions.origin,
            width: versions.width,
            base: base.into_boxed_slice(),
            offsets: offsets.into_boxed_slice(),
            changed: changed.into_boxed_slice(),
            states: states.into_boxed_slice(),
            runs: Arc::new(runs),
        })
    }
    fn column(&self, before: usize, x: i32, z: i32) -> Option<Arc<ColumnState>> {
        let x = i64::from(x) - i64::from(self.origin[0]);
        let z = i64::from(z) - i64::from(self.origin[1]);
        if x < 0 || z < 0 || x >= self.width as i64 || z >= self.width as i64 {
            return None;
        }
        let column = edge_index(self.width, x as usize, z as usize)?;
        let changed = &self.changed[self.offsets[column]..self.offsets[column + 1]];
        let state = changed
            .partition_point(|v| v.root < before)
            .checked_sub(1)
            .map_or(self.base[column], |last| changed[last].state);
        // Preserve missing latest markers; never search backward for a value.
        let value = self.states.get(state as usize)?;
        #[cfg(test)]
        footprint_probe::hit(x as usize, z as usize, self.width);
        Some(Arc::new(ColumnState {
            runs: ColumnRuns::Cached {
                runs: self.runs.clone(),
                range: value.range.clone(),
            },
            heights: value.heights,
        }))
    }
}
pub(crate) struct Cache {
    entries: VecDeque<Arc<Snapshot>>,
    bytes: usize,
    max_entries: usize,
    max_bytes: usize,
}
impl Default for Cache {
    fn default() -> Self {
        Self {
            entries: VecDeque::new(),
            bytes: 0,
            max_entries: MAX_ENTRIES,
            max_bytes: MAX_BYTES,
        }
    }
}
impl Cache {
    #[cfg(test)]
    pub(crate) fn retention_probe(&self) -> serde_json::Value {
        let entries: Vec<_> = self.entries.iter().map(|saved| serde_json::json!({
            "region":saved.region,"profile":saved.context.profile,"seed":saved.context.seed,
            "tracked_bytes":saved.bytes,"recounted_bytes":retained_bytes(saved),
            "external_snapshot_references":Arc::strong_count(saved).saturating_sub(1),
            "external_interior_run_references":Arc::strong_count(&saved.versions.runs).saturating_sub(1),
            "exterior":saved.exterior.as_ref().map(|v| v.retention_probe()),
        })).collect();
        serde_json::json!({"entries":entries,"tracked_bytes":self.bytes,
            "recounted_bytes":self.entries.iter().map(|v| retained_bytes(v)).sum::<usize>(),
            "entry_limit":self.max_entries,"byte_limit":self.max_bytes})
    }
    #[cfg(test)]
    pub(crate) fn release_probe(&self) -> impl FnOnce() -> serde_json::Value + use<> {
        let snapshots: Vec<_> = self.entries.iter().map(Arc::downgrade).collect();
        let runs: Vec<_> = self.entries.iter().map(|v| Arc::downgrade(&v.versions.runs)).collect();
        move || serde_json::json!({
            "observed_snapshots":snapshots.len(),
            "live_snapshots":snapshots.iter().filter(|v| v.strong_count()!=0).count(),
            "observed_interior_run_buffers":runs.len(),
            "live_interior_run_buffers":runs.iter().filter(|v| v.strong_count()!=0).count(),
        })
    }
    fn capture(&self, key: CacheKey, region: [i32; 2], roots: usize) -> Vec<Arc<Snapshot>> {
        self.entries
            .iter()
            .filter(|v| v.context == key && v.roots == roots && near(v.region, region))
            .cloned()
            .collect()
    }
    // Return retired owners so the caller drops them after releasing the lock,
    // synchronously inside the current generation completion interval.
    fn insert(&mut self, value: Arc<Snapshot>) -> (Vec<Arc<Snapshot>>, usize, bool) {
        if self.max_entries == 0 || value.bytes > self.max_bytes {
            return (vec![value], 0, false);
        }
        let mut retired = Vec::new();
        if let Some(index) = self
            .entries
            .iter()
            .position(|old| old.context == value.context && old.region == value.region)
        {
            let old = self.entries.remove(index).unwrap();
            self.bytes -= old.bytes;
            retired.push(old);
        }
        while self.entries.len() >= self.max_entries
            || value.bytes > self.max_bytes.saturating_sub(self.bytes)
        {
            let old = self.entries.pop_front().unwrap();
            self.bytes -= old.bytes;
            retired.push(old);
        }
        let count = retired.len();
        self.bytes += value.bytes;
        self.entries.push_back(value);
        (retired, count, true)
    }
    #[cfg(test)]
    pub(super) fn len(&self) -> usize {
        self.entries.len()
    }
}
pub(crate) struct Pending(Arc<Snapshot>);
impl Pending {
    pub(crate) fn publish(self, engine: &TerrainEngine, metrics: &Metrics) {
        let (retired, evicted, inserted) = match engine.dependency_versions.lock() {
            Ok(mut cache) => cache.insert(self.0),
            Err(_) => (vec![self.0], 0, false),
        };
        metrics.add(Perf::version_cache_evicted_regions, evicted as u64);
        metrics.add(Perf::version_cache_published_regions, u64::from(inserted));
        drop(retired);
    }
}
pub(super) struct Reuse {
    context: CacheKey,
    region: [i32; 2],
    roots: usize,
    captured: Vec<Arc<Snapshot>>,
}
fn context(request: ChunkRequest) -> CacheKey {
    CacheKey::from(ChunkRequest {
        chunk_x: 0,
        chunk_z: 0,
        ..request
    })
}
fn near(a: [i32; 2], b: [i32; 2]) -> bool {
    (i64::from(a[0]) - i64::from(b[0])).abs() <= 1 && (i64::from(a[1]) - i64::from(b[1])).abs() <= 1
}
pub(super) fn capture(
    engine: &TerrainEngine,
    profile: &WorldProfile,
    request: ChunkRequest,
    side: usize,
    roots: usize,
    allowed: bool,
    metrics: &Metrics,
) -> Option<Reuse> {
    // Native fresh-region calls opt in. Other plan/ad-hoc paths keep their
    // original evaluation, even when a caller supplies a registered profile ID.
    if !allowed
        || side != 32
        || !profile.decoration_source_order
        || !profile
            .registry_program
            .as_ref()
            .is_some_and(|p| p.material_layers && p.material_halo)
    {
        return None;
    }
    let registered = engine.profile(request.reserved).ok().flatten()?;
    if !std::ptr::eq(registered.as_ref(), profile) {
        return None;
    }
    let started = metrics.start();
    let key = context(request);
    let region = [
        request.chunk_x.div_euclid(32),
        request.chunk_z.div_euclid(32),
    ];
    let captured = engine
        .dependency_versions
        .lock()
        .ok()?
        .capture(key, region, roots);
    metrics.elapsed(Perf::version_cache_capture_nanos, started);
    metrics.add(Perf::version_cache_captured_regions, captured.len() as u64);
    // This neighborhood bounds optional pinning, not dependency reach. Misses
    // always retain the full original recursive generation path.
    Some(Reuse {
        context: key,
        region,
        roots,
        captured,
    })
}
impl Reuse {
    pub(super) fn column(&self, before: usize, x: i32, z: i32) -> Option<Arc<ColumnState>> {
        if before > self.roots {
            return None;
        }
        self.captured
            .iter()
            .rev()
            .find_map(|saved| saved.versions.column(before, x, z))
    }

    pub(super) fn exterior_column(&self, before: usize, x: i32, z: i32) -> Option<Arc<ColumnState>> {
        if before > self.roots { return None }
        self.captured.iter().rev().find_map(|saved| {
            saved.exterior.as_ref()?.column(before, x, z)
        })
    }

    pub(super) fn export(&self, versions: Versions,
        columns: &Map<(usize, i32, i32), Arc<super::ColumnTile>>, metrics: &Metrics) -> Option<Pending> {
        let started = metrics.start();
        #[cfg(test)]
        let mut export_probe = super::version_layout_probe::Export::start(&versions);
        let archived = Archived::capture(&versions);
        #[cfg(test)]
        export_probe.interior();
        let exterior = Exterior::capture(versions.origin, versions.width, columns).map(|(saved, slots)| {
            let (spans, states) = saved.counts();
            metrics.add(Perf::version_cache_exterior_exported_slots, slots as u64);
            metrics.add(Perf::version_cache_exterior_exported_spans, spans as u64);
            metrics.add(Perf::version_cache_exterior_exported_states, states as u64);
            metrics.add(Perf::version_cache_exterior_exported_bytes, saved.retained_bytes() as u64);
            saved
        });
        #[cfg(test)]
        export_probe.exterior();
        // Original planner allocations retire synchronously while exporting,
        // not during later eviction or after the measured generation interval.
        drop(versions);
        #[cfg(test)]
        export_probe.finish();
        let Some(versions) = archived else {
            metrics.add(Perf::version_cache_export_skips, 1);
            metrics.elapsed(Perf::version_cache_export_nanos, started);
            return None;
        };
        let mut snapshot = Snapshot {
            context: self.context,
            region: self.region,
            roots: self.roots,
            versions,
            exterior,
            bytes: 0,
        };
        let bytes = Some(retained_bytes(&snapshot));
        let pending = match bytes.filter(|&bytes| bytes <= MAX_BYTES) {
            Some(bytes) => {
                snapshot.bytes = bytes;
                metrics.add(Perf::version_cache_exported_regions, 1);
                metrics.add(Perf::version_cache_exported_bytes, bytes as u64);
                Some(Pending(Arc::new(snapshot)))
            }
            None => {
                metrics.add(Perf::version_cache_export_skips, 1);
                drop(snapshot);
                None
            }
        };
        metrics.elapsed(Perf::version_cache_export_nanos, started);
        pending
    }
}
fn retained_bytes(snapshot: &Snapshot) -> usize {
    let v = &snapshot.versions;
    std::mem::size_of::<Snapshot>()
        + 2 * std::mem::size_of::<usize>()
        + v.base.len() * std::mem::size_of::<u32>()
        + v.offsets.len() * std::mem::size_of::<usize>()
        + v.changed.len() * std::mem::size_of::<SavedVersion>()
        + v.states.len() * std::mem::size_of::<SavedState>()
        + std::mem::size_of::<Vec<u32>>()
        + 2 * std::mem::size_of::<usize>()
        + v.runs.capacity() * std::mem::size_of::<u32>()
        + snapshot.exterior.as_ref().map_or(0, |v| v.retained_bytes())
}

#[cfg(test)]
mod tests {
    use super::super::{ColumnCell, ColumnVersion};
    use super::*;
    #[test]
    fn exterior_backing_counts_toward_the_existing_global_budget() {
        let mut value = match Arc::try_unwrap(snapshot(0, 42)) {
            Ok(value) => value,
            Err(_) => unreachable!(),
        };
        let interior_bytes = retained_bytes(&value);
        let (profile, _, _) = crate::decoration::replay_tests::fixture(42, 0);
        let tile = Arc::new(super::super::ColumnTile::default());
        tile.0[15].set(Ok(Arc::new(ColumnState::new(vec![0; 16], &profile, 0)))).ok().unwrap();
        let mut columns = Map::default();
        columns.insert((0, -1, 0), tile);
        let (exterior, _) = Exterior::capture([0; 2], 512, &columns).unwrap();
        let exterior_bytes = exterior.retained_bytes();
        value.exterior = Some(exterior);
        value.bytes = retained_bytes(&value);
        assert_eq!(value.bytes, interior_bytes + exterior_bytes);
        let mut cache = Cache { max_bytes: value.bytes - 1, ..Cache::default() };
        let value = Arc::new(value);
        let (retired, _, inserted) = cache.insert(value.clone());
        assert!(!inserted);
        assert_eq!(retired.len(), 1);
        assert_eq!(cache.len(), 0);
        cache.max_bytes = value.bytes;
        let (retired, _, inserted) = cache.insert(value);
        assert!(inserted);
        assert!(retired.is_empty());
        assert_eq!(cache.len(), 1);
        assert_eq!(cache.bytes, cache.max_bytes);
    }
    #[test]
    fn packed_edge_indices_cover_each_retained_column_exactly_once() {
        for width in [0, 1, 16, 63, 64, 65, 80, 512] {
            let mut next = 0;
            for z in 0..width {
                for x in 0..width {
                    let retained = x.min(z).min(width - 1 - x).min(width - 1 - z) < EDGE_WIDTH;
                    let index = edge_index(width, x, z);
                    if retained {
                        assert_eq!(index, Some(next), "width={width} x={x} z={z}");
                        next += 1;
                    } else {
                        assert!(index.is_none());
                    }
                }
            }
            let inner = width.saturating_sub(2 * EDGE_WIDTH);
            assert_eq!(next, width * width - inner * inner);
            assert!(edge_index(width, width, 0).is_none());
            assert!(edge_index(width, 0, width).is_none());
        }
    }
    #[test]
    fn omitted_center_states_are_not_packed_and_remain_available_to_the_planner() {
        let r = request(-32, -32);
        let (profile, _, _) = crate::decoration::replay_tests::fixture(42, 0);
        let versions = Versions::new(r, 5);
        let state = Arc::new(ColumnState::new(vec![0; 16], &profile, 0));
        let core = Arc::new(ColumnState::new(vec![1; 16], &profile, 0));
        for (x, z) in [(31, 32), (48, 32), (32, 31), (32, 48)] {
            versions.base[z * 80 + x]
                .set(Ok(state.clone()))
                .ok()
                .unwrap();
        }
        versions.base[32 * 80 + 32]
            .set(Ok(core.clone()))
            .ok()
            .unwrap();
        let archive = Archived::capture(&versions).unwrap();
        assert_eq!(archive.base.len(), 80 * 80 - 16 * 16);
        assert_eq!(archive.states.len(), 1);
        for (x, z) in [(31, 32), (48, 32), (32, 31), (32, 48)] {
            let saved = archive.column(0, -512 + x, -512 + z).unwrap();
            assert_eq!(saved.runs.slice(), state.runs.slice());
            assert_eq!(saved.heights, state.heights);
        }
        assert!(archive.column(0, -480, -480).is_none());
        assert!(Arc::ptr_eq(
            versions
                .before(32 * 80 + 32, 0)
                .1
                .get()
                .unwrap()
                .as_ref()
                .ok()
                .unwrap(),
            &core
        ));
    }
    fn request(x: i32, z: i32) -> ChunkRequest {
        ChunkRequest {
            seed: 42,
            chunk_x: x,
            chunk_z: z,
            min_y: 0,
            height: 16,
            base_height: 64.,
            amplitude: 48.,
            frequency: 0.008,
            reserved: 1,
        }
    }
    fn snapshot(x: i32, seed: u64) -> Arc<Snapshot> {
        let r = ChunkRequest {
            seed,
            ..request(x, 0)
        };
        let (profile, _, _) = crate::decoration::replay_tests::fixture(42, 0);
        let versions = Versions::new(r, 1);
        versions.base[0]
            .set(Ok(Arc::new(ColumnState::new(vec![0; 16], &profile, 0))))
            .ok()
            .unwrap();
        let mut snapshot = Snapshot {
            context: context(r),
            region: [x.div_euclid(32), 0],
            roots: 8,
            versions: Archived::capture(&versions).unwrap(),
            exterior: None,
            bytes: 0,
        };
        snapshot.bytes = retained_bytes(&snapshot);
        Arc::new(snapshot)
    }
    #[test]
    fn resident_and_captured_ownership_obey_entry_byte_and_replacement_bounds() {
        let a = snapshot(0, 42);
        let b = snapshot(32, 42);
        let c = snapshot(64, 42);
        let weak = Arc::downgrade(&a);
        let mut cache = Cache {
            max_entries: 2,
            max_bytes: usize::MAX,
            ..Cache::default()
        };
        drop(cache.insert(a.clone()));
        drop(cache.insert(b));
        let captured = cache.entries.iter().cloned().collect::<Vec<_>>();
        drop(cache.insert(c));
        assert_eq!(cache.len(), 2);
        assert!(Arc::ptr_eq(&captured[0], &a));
        drop(a);
        drop(cache);
        assert!(weak.upgrade().is_some());
        drop(captured);
        assert!(weak.upgrade().is_none());
        let a = snapshot(0, 42);
        let bytes = a.bytes;
        let mut cache = Cache {
            max_entries: 8,
            max_bytes: bytes,
            ..Cache::default()
        };
        drop(cache.insert(a));
        drop(cache.insert(snapshot(32, 42)));
        assert_eq!(cache.len(), 1);
        assert_eq!(cache.bytes, bytes);
        drop(cache.insert(snapshot(32, 42)));
        assert_eq!(cache.len(), 1);
        assert_eq!(cache.bytes, bytes);
        let mut zero = Cache {
            max_bytes: 0,
            ..Cache::default()
        };
        let (_, _, inserted) = zero.insert(snapshot(64, 42));
        assert!(!inserted);
        assert_eq!(zero.len(), 0);
    }
    #[test]
    fn exact_before_root_selection_never_skips_missing_or_failed_latest_cells() {
        let r = request(0, 0);
        let (profile, _, _) = crate::decoration::replay_tests::fixture(42, 0);
        let mut versions = Versions::new(r, 1);
        let state = Arc::new(ColumnState::new(vec![0; 16], &profile, 0));
        versions.base[0].set(Ok(state.clone())).ok().unwrap();
        let ready = ColumnCell::new();
        ready.set(Ok(state)).ok().unwrap();
        let failed = ColumnCell::new();
        failed
            .set(Err(Box::new(super::super::ColumnFailure("failed".into()))))
            .ok()
            .unwrap();
        versions.changed[0] = vec![
            ColumnVersion {
                root: 1,
                state: ready,
            },
            ColumnVersion {
                root: 3,
                state: ColumnCell::new(),
            },
            ColumnVersion {
                root: 5,
                state: failed,
            },
        ];
        assert!(Archived::capture(&versions).is_none());
        versions.changed[0][2].state = ColumnCell::new();
        let a = Snapshot {
            context: context(r),
            region: [0, 0],
            roots: 8,
            versions: Archived::capture(&versions).unwrap(),
            exterior: None,
            bytes: 0,
        };
        let reuse = Reuse {
            context: a.context,
            region: a.region,
            roots: a.roots,
            captured: vec![Arc::new(a)],
        };
        for (before, ready) in [
            (0, true),
            (1, true),
            (2, true),
            (3, true),
            (4, false),
            (5, false),
            (6, false),
            (9, false),
        ] {
            assert_eq!(reuse.column(before, 0, 0).is_some(), ready);
        }
        assert!(reuse.column(0, -1, 0).is_none());
        assert!(reuse.column(0, 16, 0).is_none());
    }
    #[test]
    fn full_context_and_optional_neighbor_selection_remain_distinct() {
        let r = request(-32, 0);
        let key = context(r);
        for changed in [
            ChunkRequest { seed: 43, ..r },
            ChunkRequest { reserved: 2, ..r },
            ChunkRequest { min_y: -16, ..r },
            ChunkRequest { height: 32, ..r },
            ChunkRequest {
                base_height: 65.,
                ..r
            },
            ChunkRequest {
                amplitude: 49.,
                ..r
            },
            ChunkRequest {
                frequency: 0.009,
                ..r
            },
        ] {
            assert!(key != context(changed));
        }
        assert!(key == context(request(64, -64)));
        let mut cache = Cache::default();
        drop(cache.insert(snapshot(0, 42)));
        drop(cache.insert(snapshot(32, 43)));
        assert_eq!(cache.capture(key, [-1, 0], 8).len(), 1);
        assert!(cache.capture(key, [-2, 0], 8).is_empty());
        assert!(cache.capture(key, [-1, 0], 7).is_empty());
        for changed in [
            ChunkRequest { seed: 99, ..r },
            ChunkRequest { reserved: 2, ..r },
            ChunkRequest { height: 32, ..r },
            ChunkRequest { min_y: -16, ..r },
        ] {
            assert!(cache.capture(context(changed), [-1, 0], 8).is_empty());
        }

        assert!(near([-1, 0], [0, 1]));
        assert!(!near([-1, 0], [1, 0]));
        assert!(!near([i32::MIN, 0], [i32::MAX, 0]));
    }
    #[test]
    fn archive_deduplicates_owners_and_outlives_original_and_index() {
        let r = request(0, 0);
        let (profile, _, _) = crate::decoration::replay_tests::fixture(42, 0);
        let mut versions = Versions::new(r, 1);
        let state = Arc::new(ColumnState::new(vec![0; 16], &profile, 0));
        let weak = Arc::downgrade(&state);
        versions.base[0].set(Ok(state.clone())).ok().unwrap();
        versions.base[1].set(Ok(state.clone())).ok().unwrap();
        let ready = ColumnCell::new();
        ready.set(Ok(state.clone())).ok().unwrap();
        versions.changed[0].push(ColumnVersion {
            root: 1,
            state: ready,
        });
        let archive = Archived::capture(&versions).unwrap();
        assert_eq!(archive.states.len(), 1);
        assert_eq!(archive.runs.as_slice(), state.runs.slice());
        assert_eq!(archive.column(2, 0, 0).unwrap().heights, state.heights);
        assert!(archive.column(2, 2, 0).is_none());
        drop(state);
        drop(versions);
        assert!(weak.upgrade().is_none());
        let held = archive.column(2, 0, 0).unwrap();
        let packed = Arc::downgrade(&archive.runs);
        // Copying a previously archived state cannot create an ownership cycle.
        let next = Versions::new(r, 1);
        next.base[0].set(Ok(held.clone())).ok().unwrap();
        let second = Archived::capture(&next).unwrap();
        drop(next);
        drop(archive);
        assert!(packed.upgrade().is_some());
        assert_eq!(
            held.runs.slice(),
            second.column(2, 0, 0).unwrap().runs.slice()
        );
        drop(held);
        assert!(packed.upgrade().is_none());
    }
}

#[cfg(test)]
pub(super) use exterior::export_benchmark;
