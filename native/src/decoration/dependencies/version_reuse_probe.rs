//! Test-only evidence for context-independent completed column reuse.
//! No observer, snapshot allocation or extra branch exists in normal libraries.
use super::*;
use std::collections::HashSet;

static ACTIVE: Mutex<Option<Arc<Mutex<Probe>>>> = Mutex::new(None);
struct Installed;
impl Drop for Installed {
    fn drop(&mut self) {
        *ACTIVE.lock().unwrap_or_else(|e| e.into_inner()) = None;
    }
}
pub(super) fn observe(planner: &Planner<'_>) -> Result<(), String> {
    let active = ACTIVE.lock().map_err(|_| "version probe poisoned")?.clone();
    if let Some(active) = active {
        active
            .lock()
            .map_err(|_| "version probe poisoned")?
            .observe(planner)?;
    }
    Ok(())
}

type RootSignature = Vec<(Option<usize>, Vec<u32>, [i32; 2], [i32; 2])>;
struct SavedVersion {
    root: usize,
    state: Option<Arc<ColumnState>>,
}
struct Saved {
    request: ChunkRequest,
    profile: usize,
    roots: RootSignature,
    origin: [i32; 2],
    width: usize,
    base: Vec<Option<Arc<ColumnState>>>,
    changed: Vec<Vec<SavedVersion>>,
}
fn success(cell: &ColumnCell) -> Option<Arc<ColumnState>> {
    cell.get().and_then(|result| result.as_ref().ok()).cloned()
}
fn signature(planner: &Planner<'_>) -> RootSignature {
    planner
        .roots
        .iter()
        .map(|r| (r.definition, r.ids.clone(), r.bounds.x, r.bounds.z))
        .collect()
}
fn context(request: ChunkRequest) -> crate::CacheKey {
    crate::CacheKey::from(ChunkRequest {
        chunk_x: 0,
        chunk_z: 0,
        ..request
    })
}
impl Saved {
    fn capture(planner: &Planner<'_>) -> Self {
        Self {
            request: planner.request,
            profile: planner.profile as *const _ as usize,
            roots: signature(planner),
            origin: planner.versions.origin,
            width: planner.versions.width,
            base: planner.versions.base.iter().map(success).collect(),
            changed: planner
                .versions
                .changed
                .iter()
                .map(|versions| {
                    versions
                        .iter()
                        .map(|v| SavedVersion {
                            root: v.root,
                            state: success(&v.state),
                        })
                        .collect()
                })
                .collect(),
        }
    }
    fn index(&self, x: i32, z: i32) -> Option<usize> {
        let x = i64::from(x) - i64::from(self.origin[0]);
        let z = i64::from(z) - i64::from(self.origin[1]);
        (x >= 0 && z >= 0 && x < self.width as i64 && z < self.width as i64)
            .then(|| z as usize * self.width + x as usize)
    }
    fn before(&self, column: usize, before: usize) -> (usize, Option<&Arc<ColumnState>>) {
        let versions = &self.changed[column];
        match versions.partition_point(|v| v.root < before).checked_sub(1) {
            Some(last) => (versions[last].root + 1, versions[last].state.as_ref()),
            None => (0, self.base[column].as_ref()),
        }
    }
    fn retained(&self) -> serde_json::Value {
        let mut states = HashSet::new();
        let mut masks = HashSet::new();
        let mut halos = HashSet::new();
        let mut state_bytes = 0;
        let mut mask_bytes = 0;
        let mut halo_bytes = 0;
        let index_bytes = std::mem::size_of::<Self>()
            + self.base.capacity() * std::mem::size_of::<Option<Arc<ColumnState>>>()
            + self.changed.capacity() * std::mem::size_of::<Vec<SavedVersion>>()
            + self
                .changed
                .iter()
                .map(|v| v.capacity() * std::mem::size_of::<SavedVersion>())
                .sum::<usize>()
            + self.roots.capacity()
                * std::mem::size_of::<(Option<usize>, Vec<u32>, [i32; 2], [i32; 2])>()
            + self.roots.iter().map(|r| r.1.capacity() * 4).sum::<usize>();
        let values = self
            .base
            .iter()
            .chain(self.changed.iter().flat_map(|v| v.iter().map(|v| &v.state)));
        let mut ready_slots = 0;
        for value in values.flatten() {
            ready_slots += 1;
            if !states.insert(Arc::as_ptr(value) as usize) {
                continue;
            }
            state_bytes += std::mem::size_of::<ColumnState>() + 2 * std::mem::size_of::<usize>();
            match &value.runs {
                ColumnRuns::Owned(runs) => state_bytes += runs.len() * 4,
                ColumnRuns::Shared { mask, .. } => {
                    if !masks.insert(Arc::as_ptr(mask) as usize) {
                        continue;
                    }
                    mask_bytes += std::mem::size_of::<crate::geology::CaveMask>()
                        + 2 * std::mem::size_of::<usize>()
                        + mask.words.capacity() * 4;
                    if let Some(halo) = &mask.columns {
                        if halos.insert(Arc::as_ptr(&halo.columns) as usize) {
                            halo_bytes += std::mem::size_of::<Vec<crate::Column>>()
                                + 2 * std::mem::size_of::<usize>()
                                + halo.columns.capacity() * std::mem::size_of::<crate::Column>();
                        }
                    }
                }
            }
        }
        serde_json::json!({"index_bytes":index_bytes,"state_bytes":state_bytes,"mask_bytes":mask_bytes,"halo_bytes":halo_bytes,
            "counted_backing_bytes":index_bytes+state_bytes+mask_bytes+halo_bytes,"ready_slots":ready_slots,
            "unique_states":states.len(),"unique_masks":masks.len(),"unique_halos":halos.len(),
            "note":"Unique retained owners and allocation capacities for this test snapshot. Excludes allocator bookkeeping and transient accounting sets; not process RSS or a production budget."})
    }
}
struct Probe {
    source: Option<Saved>,
    result: Option<serde_json::Value>,
}
impl Probe {
    fn observe(&mut self, planner: &Planner<'_>) -> Result<(), String> {
        let Some(saved) = &self.source else {
            self.source = Some(Saved::capture(planner));
            return Ok(());
        };
        if context(saved.request) != context(planner.request)
            || saved.profile != planner.profile as *const _ as usize
            || saved.roots != signature(planner)
        {
            return Err("probe contexts or source roots differ".into());
        }
        let mut boundary_slots = 0u64;
        let mut inside_saved = 0u64;
        let mut available = 0u64;
        let mut encoding_differences = 0u64;
        let mut mismatches = 0u64;
        let mut samples = Vec::new();
        let mut by_before = BTreeMap::<usize, [u64; 3]>::new();
        // Inspect only already initialized natural boundary work. Do not force
        // columns, anchors, terrain or extra prior-state evaluation in the probe.
        for (&(before, cx, cz), tile) in &planner.ready_columns {
            for (slot, cell) in tile.0.iter().enumerate() {
                let Some(Ok(actual)) = cell.get() else {
                    continue;
                };
                let (x, z) = (cx * 16 + (slot % 16) as i32, cz * 16 + (slot / 16) as i32);
                if planner.versions.index(x, z).is_some() {
                    continue;
                }
                boundary_slots += 1;
                let Some(column) = saved.index(x, z) else {
                    continue;
                };
                inside_saved += 1;
                by_before.entry(before).or_default()[0] += 1;
                let (effective, Some(expected)) = saved.before(column, before) else {
                    continue;
                };
                available += 1;
                by_before.entry(before).or_default()[1] += 1;
                let runs_equal = expected.runs.slice() == actual.runs.slice();
                encoding_differences += u64::from(!runs_equal);
                let different_y = (!runs_equal)
                    .then(|| {
                        (0..planner.request.height as usize)
                            .find(|&y| expected.material(y) != actual.material(y))
                    })
                    .flatten();
                if expected.heights != actual.heights || different_y.is_some() {
                    mismatches += 1;
                    by_before.entry(before).or_default()[2] += 1;
                    if samples.len() < 20 {
                        samples.push(serde_json::json!({"x":x,"z":z,"before":before,"source_effective_before":effective,
                        "different_y":different_y,"expected_heights":expected.heights,"actual_heights":actual.heights,
                        "expected_runs":expected.runs.slice(),"actual_runs":actual.runs.slice()}));
                    }
                }
            }
        }
        self.result = Some(
            serde_json::json!({"seed":planner.request.seed,"source_request":[saved.request.chunk_x,saved.request.chunk_z],
            "target_request":[planner.request.chunk_x,planner.request.chunk_z],"roots":planner.roots.len(),
            "naturally_initialized_boundary_slots":boundary_slots,"boundary_slots_inside_source_interior":inside_saved,
            "available_source_versions":available,"run_encoding_differences":encoding_differences,
            "value_mismatches":mismatches,"mismatch_samples":samples,"by_before_inside_available_mismatch":by_before,
            "retained":saved.retained()}),
        );
        Ok(())
    }
}

#[test]
fn missing_latest_version_never_selects_an_earlier_ready_value() {
    let (profile, _, _) = crate::decoration::replay_tests::fixture(42, 0);
    let state = Arc::new(ColumnState::new(vec![0; 16], &profile, 0));
    let saved = Saved {
        request: ChunkRequest::default(),
        profile: 0,
        roots: Vec::new(),
        origin: [-1, -1],
        width: 1,
        base: vec![Some(state.clone())],
        changed: vec![vec![
            SavedVersion {
                root: 1,
                state: Some(state.clone()),
            },
            SavedVersion {
                root: 3,
                state: None,
            },
            SavedVersion {
                root: 5,
                state: Some(state),
            },
        ]],
    };
    assert_eq!(saved.index(-1, -1), Some(0));
    assert_eq!(saved.index(0, -1), None);
    for (before, effective, ready) in [
        (0, 0, true),
        (1, 0, true),
        (2, 2, true),
        (3, 2, true),
        (4, 4, false),
        (5, 4, false),
        (6, 6, true),
    ] {
        let (e, s) = saved.before(0, before);
        assert_eq!((e, s.is_some()), (effective, ready));
    }
    assert_eq!(saved.retained()["unique_states"], 1);
}

#[test]
#[ignore = "requires GPU, RETINA_TERRAIN_REUSE_PROFILES and RETINA_BOUNDARY_VERSION_REPORT"]
fn independently_computed_neighbor_boundary_versions_match_completed_interiors() {
    let directory =
        std::path::PathBuf::from(std::env::var("RETINA_TERRAIN_REUSE_PROFILES").unwrap());
    let report = std::path::PathBuf::from(std::env::var("RETINA_BOUNDARY_VERSION_REPORT").unwrap());
    let engine = TerrainEngine::new().unwrap();
    let pool = rayon::ThreadPoolBuilder::new()
        .num_threads(16)
        .build()
        .unwrap();
    let mut rows = Vec::new();
    for name in ["nether", "custom", "direct-stem"] {
        let bytes = std::fs::read(directory.join(format!("{name}.json"))).unwrap();
        let data: serde_json::Value = serde_json::from_slice(&bytes).unwrap();
        let id = engine.register_profile(&bytes).unwrap();
        let profile = engine.profile(id).unwrap().unwrap();
        assert!(profile.decoration_source_order);
        for seed in [123456789, 42] {
            for (source, target) in [
                ([-64, -64], [-32, -64]),
                ([32, 32], [0, 32]),
                ([-32, 0], [-32, -32]),
                ([0, 0], [32, 32]),
            ] {
                let probe = Arc::new(Mutex::new(Probe {
                    source: None,
                    result: None,
                }));
                *ACTIVE.lock().unwrap() = Some(probe.clone());
                let installed = Installed;
                for [x, z] in [source, target] {
                    // Keep the registered program but clear CPU value caches so
                    // the two jobs independently reconstruct their terrain/state.
                    *engine.cache.lock().unwrap() = crate::ColumnCache::default();
                    *engine.height_cache.lock().unwrap() = crate::ColumnCache::default();
                    *engine.query_cache.lock().unwrap() = crate::queries::Cache::default();
                    *engine.caves.lock().unwrap() = crate::CaveCache::default();
                    *engine.structures.lock().unwrap() = crate::structures::Cache::default();
                    let bounds = &data["generation_bounds"];
                    let request = ChunkRequest {
                        seed,
                        chunk_x: x,
                        chunk_z: z,
                        reserved: id,
                        min_y: bounds["storage_min_y"].as_i64().unwrap_or(-64) as i32,
                        height: bounds["storage_height"].as_u64().unwrap_or(384) as u32,
                        base_height: 64.,
                        amplitude: 48.,
                        frequency: 0.008,
                    };
                    let (field, _) = engine.terrain_field(request, 32).unwrap();
                    let result = generate(
                        &engine,
                        &profile,
                        request,
                        32,
                        None,
                        None,
                        true,
                        Some(&field),
                        Some(&pool),
                    )
                    .unwrap();
                    drop(result);
                }
                drop(installed);
                let mut result = probe.lock().unwrap().result.take().unwrap();
                result["profile"] = name.into();
                println!(
                    "version comparison: {name} seed={seed} source={source:?} target={target:?} available={} mismatches={} backing_bytes={}",
                    result["available_source_versions"],
                    result["value_mismatches"],
                    result["retained"]["counted_backing_bytes"]
                );
                rows.push(result);
                std::fs::write(&report,serde_json::to_vec_pretty(&serde_json::json!({"rows":rows,"scope":"Naturally requested boundary states versus completed source interiors; exact material runs/voxels and all six heights. No production reuse, extra queries or timing acceptance.","timing_acceptance":false})).unwrap()).unwrap();
                let row = rows.last().unwrap();
                assert!(
                    row["available_source_versions"].as_u64().unwrap() > 0,
                    "comparison must exercise available prior states"
                );
                assert_eq!(
                    row["value_mismatches"], 0,
                    "completed interior state is not context-independent"
                );
            }
        }
    }
}
