//! Bounded completed interior states, captured once for a neighboring job.
use super::{ColumnRuns, ColumnState, Metrics, Perf, Versions};
use crate::{CacheKey, ChunkRequest, TerrainEngine, WorldProfile};
use std::collections::{HashSet, VecDeque};
use std::sync::Arc;

const MAX_ENTRIES: usize = 8;
const MAX_BYTES: usize = 512 * 1024 * 1024;
struct Snapshot {
    context: CacheKey,
    region: [i32; 2],
    roots: usize,
    versions: Versions,
    bytes: usize,
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
        self.captured.iter().rev().find_map(|saved| {
            let column = saved.versions.index(x, z)?;
            let (_, cell) = saved.versions.before(column, before);
            // An uninitialized or failed latest version is a miss, never an
            // invitation to substitute an earlier or later completed state.
            cell.get()?.as_ref().ok().cloned()
        })
    }
    pub(super) fn export(&self, versions: Versions, metrics: &Metrics) -> Option<Pending> {
        let started = metrics.start();
        let mut snapshot = Snapshot {
            context: self.context,
            region: self.region,
            roots: self.roots,
            versions,
            bytes: 0,
        };
        let bytes = retained_bytes(&snapshot);
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
fn retained_bytes(snapshot: &Snapshot) -> Option<usize> {
    let versions = &snapshot.versions;
    let mut bytes = std::mem::size_of::<Snapshot>()
        + 2 * std::mem::size_of::<usize>()
        + versions.base.capacity() * std::mem::size_of::<super::ColumnCell>()
        + versions.changed.capacity() * std::mem::size_of::<Vec<super::ColumnVersion>>()
        + versions
            .changed
            .iter()
            .map(|v| v.capacity() * std::mem::size_of::<super::ColumnVersion>())
            .sum::<usize>();
    let mut states = HashSet::new();
    let mut masks = HashSet::new();
    let mut halos = HashSet::new();
    for cell in versions.base.iter().chain(
        versions
            .changed
            .iter()
            .flat_map(|v| v.iter().map(|v| &v.state)),
    ) {
        let Some(value) = cell.get() else {
            continue;
        };
        let value = value.as_ref().ok()?;
        if !states.insert(Arc::as_ptr(value) as usize) {
            continue;
        }
        bytes = bytes
            .saturating_add(std::mem::size_of::<ColumnState>() + 2 * std::mem::size_of::<usize>());
        match &value.runs {
            ColumnRuns::Owned(runs) => bytes = bytes.saturating_add(runs.len() * 4),
            ColumnRuns::Shared { mask, .. } => {
                if !masks.insert(Arc::as_ptr(mask) as usize) {
                    continue;
                }
                bytes = bytes
                    .saturating_add(
                        std::mem::size_of::<crate::geology::CaveMask>()
                            + 2 * std::mem::size_of::<usize>(),
                    )
                    .saturating_add(mask.words.capacity() * 4);
                if let Some(halo) = &mask.columns {
                    if halos.insert(Arc::as_ptr(&halo.columns) as usize) {
                        bytes = bytes
                            .saturating_add(
                                std::mem::size_of::<Vec<crate::Column>>()
                                    + 2 * std::mem::size_of::<usize>(),
                            )
                            .saturating_add(
                                halo.columns.capacity() * std::mem::size_of::<crate::Column>(),
                            );
                    }
                }
            }
        }
    }
    // Unique retained backing capacities are charged within each snapshot.
    // Shared owners across snapshots may be overcounted. Entry count bounds
    // deque/Arc handles; temporary accounting sets are dropped before return.
    Some(bytes)
}

#[cfg(test)]
mod tests {
    use super::super::{ColumnCell, ColumnVersion};
    use super::*;
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
            versions,
            bytes: 0,
        };
        snapshot.bytes = retained_bytes(&snapshot).unwrap();
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
        let mut a = Arc::try_unwrap(snapshot(0, 42)).ok().unwrap();
        let state = a.versions.base[0]
            .get()
            .unwrap()
            .as_ref()
            .ok()
            .unwrap()
            .clone();
        let ready = ColumnCell::new();
        ready.set(Ok(state)).ok().unwrap();
        let failed = ColumnCell::new();
        failed
            .set(Err(Box::new(super::super::ColumnFailure("failed".into()))))
            .ok()
            .unwrap();
        a.versions.changed[0] = vec![
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
        assert!(retained_bytes(&a).is_none());
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
    fn repeated_state_owners_charge_only_the_new_index_capacity() {
        let mut saved = Arc::try_unwrap(snapshot(0, 42)).ok().unwrap();
        let state = saved.versions.base[0]
            .get()
            .unwrap()
            .as_ref()
            .ok()
            .unwrap()
            .clone();
        let original = retained_bytes(&saved).unwrap();
        saved.versions.base[1].set(Ok(state.clone())).ok().unwrap();
        assert_eq!(retained_bytes(&saved).unwrap(), original);
        let ready = ColumnCell::new();
        ready.set(Ok(state)).ok().unwrap();
        saved.versions.changed[0].push(ColumnVersion {
            root: 1,
            state: ready,
        });
        assert_eq!(
            retained_bytes(&saved).unwrap(),
            original + saved.versions.changed[0].capacity() * std::mem::size_of::<ColumnVersion>()
        );
    }
}
