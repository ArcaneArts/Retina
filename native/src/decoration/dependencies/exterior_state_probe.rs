//! Observe naturally completed exterior states without using them in generation.
//! This module and its only call site are absent from normal native libraries.
use super::*;
use std::collections::HashSet;

static ACTIVE: Mutex<Option<Probe>> = Mutex::new(None);
const BAND: usize = 64;
type Key = (usize, i32, i32);
struct State {
    runs: Vec<u32>,
    heights: [i32; 6],
}
struct Saved {
    request: crate::CacheKey,
    profile: usize,
    roots: usize,
    origin: [i32; 2],
    slots: Map<Key, u32>,
    states: Vec<State>,
}
struct Probe {
    previous: Option<Saved>,
    rows: Vec<serde_json::Value>,
}
struct Installed;
impl Drop for Installed {
    fn drop(&mut self) {
        *ACTIVE.lock().unwrap_or_else(|e| e.into_inner()) = None;
    }
}
fn context(request: ChunkRequest) -> crate::CacheKey {
    crate::CacheKey::from(ChunkRequest { chunk_x: 0, chunk_z: 0, ..request })
}
fn distance(origin: [i32; 2], width: usize, x: i32, z: i32) -> usize {
    let x = i64::from(x) - i64::from(origin[0]);
    let z = i64::from(z) - i64::from(origin[1]);
    0.max(-x).max(-z).max(x - width as i64 + 1).max(z - width as i64 + 1) as usize
}
pub(super) fn observe(planner: &Planner<'_>) -> Result<(), String> {
    let mut active = ACTIVE.lock().map_err(|_| "exterior probe poisoned")?;
    if let Some(active) = active.as_mut() {
        active.observe(planner)?;
    }
    Ok(())
}
impl Probe {
    fn observe(&mut self, planner: &Planner<'_>) -> Result<(), String> {
        if let Some(previous) = &self.previous {
            if previous.request != context(planner.request)
                || previous.profile != planner.profile as *const _ as usize
                || previous.roots != planner.roots.len()
            {
                return Err("exterior probe crossed a generation context".into());
            }
        }
        let mut checked = [0u64; 2];
        let mut available = [0u64; 2];
        let mut mismatches = Vec::new();
        let mut compare = |key: Key, actual: &ColumnState, exterior: bool| {
            let i = usize::from(exterior);
            checked[i] += 1;
            if let Some(saved) = self.previous.as_ref().and_then(|p| {
                p.slots.get(&key).map(|&id| &p.states[id as usize])
            }) {
                available[i] += 1;
                if (saved.runs != actual.runs.slice() || saved.heights != actual.heights)
                    && mismatches.len() < 10
                {
                    mismatches.push(key);
                }
            }
        };
        // Interior cells expose their exact effective prefix; do not assume an
        // unobserved prefix or missing cell has the same value.
        for (index, cell) in planner.versions.base.iter().enumerate() {
            let x = planner.versions.origin[0] + (index % planner.versions.width) as i32;
            let z = planner.versions.origin[1] + (index / planner.versions.width) as i32;
            if let Some(Ok(state)) = cell.get() {
                compare((0, x, z), state, false);
            }
            for version in &planner.versions.changed[index] {
                if let Some(Ok(state)) = version.state.get() {
                    compare((version.root + 1, x, z), state, false);
                }
            }
        }
        let bands = [16usize, 32, 64, 128, usize::MAX];
        let mut slot_counts = [0u64; 5];
        let mut owners: [HashSet<usize>; 5] = std::array::from_fn(|_| HashSet::new());
        let mut run_bytes = [0u64; 5];
        let mut selected_owners = Map::<usize, u32>::default();
        let mut saved = Saved {
            request: context(planner.request), profile: planner.profile as *const _ as usize,
            roots: planner.roots.len(), origin: planner.versions.origin,
            slots: Map::default(), states: Vec::new(),
        };
        for (&(before, cx, cz), tile) in &planner.ready_columns {
            for (index, cell) in tile.0.iter().enumerate() {
                let Some(Ok(state)) = cell.get() else { continue };
                let x = cx * 16 + (index % 16) as i32;
                let z = cz * 16 + (index / 16) as i32;
                let depth = distance(planner.versions.origin, planner.versions.width, x, z);
                if depth == 0 { continue }
                compare((before, x, z), state, true);
                let pointer = Arc::as_ptr(state) as usize;
                for (i, band) in bands.iter().enumerate() {
                    if depth <= *band {
                        slot_counts[i] += 1;
                        if owners[i].insert(pointer) {
                            run_bytes[i] += state.runs.slice().len() as u64 * 4;
                        }
                    }
                }
                if depth <= BAND {
                    let id = *selected_owners.entry(pointer).or_insert_with(|| {
                        let id = saved.states.len() as u32;
                        saved.states.push(State { runs: state.runs.slice().to_vec(), heights: state.heights });
                        id
                    });
                    saved.slots.insert((before, x, z), id);
                }
            }
        }
        let positions: HashSet<_> = saved.slots.keys().map(|&(_, x, z)| (x, z)).collect();
        let position_states: HashSet<_> = saved.slots.iter().map(|(&(_, x, z), &state)| (x, z, state)).collect();
        let inventory: Vec<_> = bands.iter().enumerate().map(|(i, &band)| serde_json::json!({
            "band_blocks": (band != usize::MAX).then_some(band),
            "initialized_exact_prefix_slots": slot_counts[i], "unique_state_owners": owners[i].len(),
            "packed_run_bytes": run_bytes[i],
        })).collect();
        self.rows.push(serde_json::json!({
            "origin": planner.versions.origin, "previous_origin": self.previous.as_ref().map(|p| p.origin),
            "roots": planner.roots.len(), "inventory": inventory,
            "selected_band": BAND, "selected_positions": positions.len(),
            "selected_position_state_groups": position_states.len(),
            "naturally_initialized_interior_cells": checked[0],
            "naturally_initialized_exterior_cells": checked[1],
            "previous_exact_slots_matching_interior_cells": available[0],
            "previous_exact_slots_matching_exterior_cells": available[1],
            "mismatch_samples": mismatches,
        }));
        if !mismatches.is_empty() { return Err(format!("exterior state mismatches: {mismatches:?}")) }
        self.previous = Some(saved);
        Ok(())
    }
}

#[test]
#[ignore = "GPU observation requires RETINA_TERRAIN_REUSE_PROFILES and RETINA_EXTERIOR_STATE_OUTPUT"]
fn completed_exterior_state_inventory() {
    use std::{fs, path::PathBuf};
    let profiles = PathBuf::from(std::env::var("RETINA_TERRAIN_REUSE_PROFILES").unwrap());
    let output = PathBuf::from(std::env::var("RETINA_EXTERIOR_STATE_OUTPUT").unwrap());
    fs::create_dir(&output).unwrap();
    let mut cases = Vec::new();
    for (name, file, seed) in [
        ("nether-main", "nether.json", 123456789),
        ("nether-second-seed", "nether.json", 42),
        ("nether-custom-bounds", "custom.json", 123456789),
        ("nether-direct-stem", "direct-stem.json", 42),
    ] {
        let bytes = fs::read(profiles.join(file)).unwrap();
        let profile: serde_json::Value = serde_json::from_slice(&bytes).unwrap();
        let engine = TerrainEngine::new().unwrap();
        let id = engine.register_profile(&bytes).unwrap();
        let bounds = &profile["generation_bounds"];
        let request = ChunkRequest {
            seed, chunk_x: 0, chunk_z: 0,
            min_y: bounds["storage_min_y"].as_i64().unwrap_or(-64) as i32,
            height: bounds["storage_height"].as_u64().unwrap_or(384) as u32,
            base_height: 64., amplitude: 48., frequency: 0.008, reserved: id,
        };
        let biome = profile["biomes"][0]["id"].as_str().unwrap();
        let directory = output.join(format!("{name}-run0"));
        fs::create_dir(&directory).unwrap();
        let generate = |name: &str, x: i32, z: i32| {
            let report = crate::region::generate_region(
                &engine, ChunkRequest { chunk_x: x * 32, chunk_z: z * 32, ..request },
                &directory.join(format!("{name}.mca")), 0, biome,
            ).unwrap();
            assert_eq!(report.generated, 1024);
            assert_eq!(report.preserved, 0);
        };
        for i in 0..5 { generate(&format!("warm{i}"), 8 + i, 8); }
        *ACTIVE.lock().unwrap() = Some(Probe { previous: None, rows: Vec::new() });
        let installed = Installed;
        for i in 0..20 { generate(&i.to_string(), -2 + i % 5, -2 + i / 5); }
        let probe = ACTIVE.lock().unwrap().take().unwrap();
        drop(installed);
        let matches: u64 = probe.rows.iter().map(|r| {
            r["previous_exact_slots_matching_interior_cells"].as_u64().unwrap()
                + r["previous_exact_slots_matching_exterior_cells"].as_u64().unwrap()
        }).sum();
        println!("exterior inventory {name}: exact matching naturally initialized cells={matches}");
        cases.push(serde_json::json!({"case":name,"seed":seed,"profile":file,"rows":probe.rows}));
        fs::write(output.join("report.json"), serde_json::to_vec_pretty(&serde_json::json!({
            "scope":"Natural completed exact-prefix cells only, after each joined dependency planner. Prior region exterior snapshots are observed and compared, never used by generation. Snapshot memory and instrumentation are not production costs. Counts are potentially avoided cell builds, not saved queries or elapsed time. Only the immediately preceding measured region is compared. Full lit MCA; five warmups and twenty measured coordinates per case.",
            "cases":cases,"timing_acceptance":false,
        })).unwrap()).unwrap();
    }
}

#[test]
#[ignore = "GPU context proof requires RETINA_TERRAIN_REUSE_PROFILES and RETINA_EXTERIOR_PAIR_REPORT"]
fn independently_computed_exterior_prefixes_match_neighbor_states() {
    use std::{fs, path::PathBuf};
    let directory = PathBuf::from(std::env::var("RETINA_TERRAIN_REUSE_PROFILES").unwrap());
    let report = PathBuf::from(std::env::var("RETINA_EXTERIOR_PAIR_REPORT").unwrap());
    let engine = TerrainEngine::new().unwrap();
    let pool = rayon::ThreadPoolBuilder::new().num_threads(16).build().unwrap();
    let mut rows = Vec::new();
    for name in ["nether", "custom", "direct-stem"] {
        let bytes = fs::read(directory.join(format!("{name}.json"))).unwrap();
        let data: serde_json::Value = serde_json::from_slice(&bytes).unwrap();
        let id = engine.register_profile(&bytes).unwrap();
        let profile = engine.profile(id).unwrap().unwrap();
        for seed in [123456789, 42] {
            for (source, target) in [
                ([-64, -64], [-32, -64]), ([32, 32], [0, 32]),
                ([-32, 0], [-32, -32]), ([0, 0], [32, 32]),
            ] {
                *ACTIVE.lock().unwrap() = Some(Probe { previous: None, rows: Vec::new() });
                let installed = Installed;
                for [x, z] in [source, target] {
                    // Independently rebuild both sides. Registered programs remain;
                    // no completed-region or CPU terrain-value cache is reused.
                    *engine.cache.lock().unwrap() = crate::ColumnCache::default();
                    *engine.height_cache.lock().unwrap() = crate::ColumnCache::default();
                    *engine.query_cache.lock().unwrap() = crate::queries::Cache::default();
                    *engine.caves.lock().unwrap() = crate::CaveCache::default();
                    *engine.structures.lock().unwrap() = crate::structures::Cache::default();
                    *engine.dependency_versions.lock().unwrap() = version_cache::Cache::default();
                    let bounds = &data["generation_bounds"];
                    let request = ChunkRequest {
                        seed, chunk_x: x, chunk_z: z, reserved: id,
                        min_y: bounds["storage_min_y"].as_i64().unwrap_or(-64) as i32,
                        height: bounds["storage_height"].as_u64().unwrap_or(384) as u32,
                        base_height: 64., amplitude: 48., frequency: 0.008,
                    };
                    let (field, _) = engine.terrain_field(request, 32).unwrap();
                    drop(generate(&engine, &profile, request, 32, None, None, true,
                        Some(&field), Some(&pool), true).unwrap());
                }
                let probe = ACTIVE.lock().unwrap().take().unwrap();
                drop(installed);
                let row = &probe.rows[1];
                let matches = row["previous_exact_slots_matching_interior_cells"].as_u64().unwrap()
                    + row["previous_exact_slots_matching_exterior_cells"].as_u64().unwrap();
                assert!(matches > 0, "pair must exercise actual available states");
                assert!(row["mismatch_samples"].as_array().unwrap().is_empty());
                println!("exterior pair {name} seed={seed} source={source:?} target={target:?} matches={matches}");
                rows.push(serde_json::json!({"profile":name,"seed":seed,"source":source,"target":target,"observations":probe.rows}));
                fs::write(&report, serde_json::to_vec_pretty(&serde_json::json!({
                    "rows":rows,"scope":"Independent source/target prefix construction with CPU value and completed-region caches cleared. Compare naturally completed exact states only; no observed states affect generation.","timing_acceptance":false,
                })).unwrap()).unwrap();
            }
        }
    }
}
