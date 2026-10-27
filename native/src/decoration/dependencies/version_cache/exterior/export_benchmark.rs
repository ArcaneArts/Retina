//! Paired export measurements over the same naturally completed planner.
//! The reference is cycle53's first staging algorithm, including its compact
//! offset table. Neither archive is used to answer generation queries here.
use super::*;
use crate::decoration::dependencies::Planner;
use std::{fs, path::PathBuf, sync::Mutex, time::Instant};

static ACTIVE: Mutex<Option<PathBuf>> = Mutex::new(None);
struct Installed;
impl Drop for Installed {
    fn drop(&mut self) {
        *ACTIVE.lock().unwrap_or_else(|e| e.into_inner()) = None;
    }
}
struct Reference {
    origin: [i32; 2],
    width: usize,
    offsets: Box<[u32]>,
    spans: Box<[Span]>,
    states: Box<[SavedState]>,
    runs: Arc<Vec<u32>>,
}
impl Reference {
    fn capture(planner: &Planner<'_>) -> Option<Box<Self>> {
        let origin = planner.versions.origin;
        let width = planner.versions.width;
        let outer = width + 2 * BAND;
        let count = outer * outer - width * width;
        let mut cells = (0..count)
            .map(|_| Vec::<(usize, u32)>::new())
            .collect::<Vec<_>>();
        let mut owners = Map::<usize, u32>::default();
        let mut states = Vec::new();
        let mut runs = Vec::new();
        for (&(before, cx, cz), tile) in &planner.ready_columns {
            for (slot, cell) in tile.0.iter().enumerate() {
                let x = cx * 16 + (slot % 16) as i32;
                let z = cz * 16 + (slot / 16) as i32;
                let Some(column) = index(origin, width, x, z) else {
                    continue;
                };
                let Some(value) = cell.get() else { continue };
                let value = value.as_ref().ok()?;
                let pointer = Arc::as_ptr(value) as usize;
                let id = if let Some(&id) = owners.get(&pointer) {
                    id
                } else {
                    let id = u32::try_from(states.len()).ok()?;
                    let start = runs.len();
                    runs.extend_from_slice(value.runs.slice());
                    states.push(SavedState {
                        range: start..runs.len(),
                    });
                    owners.insert(pointer, id);
                    id
                };
                cells[column].push((before, id));
            }
        }
        let mut offsets = Vec::with_capacity(count + 1);
        let mut spans: Vec<Span> = Vec::new();
        for mut cell in cells {
            offsets.push(u32::try_from(spans.len()).ok()?);
            cell.sort_unstable_by_key(|&(before, _)| before);
            let start = spans.len();
            for (before, state) in cell {
                let end = before.checked_add(1)?;
                if spans.len() > start {
                    let last = spans.last_mut().unwrap();
                    if last.end == before && last.state == state {
                        last.end = end;
                        continue;
                    }
                }
                spans.push(Span {
                    start: before,
                    end,
                    state,
                });
            }
        }
        offsets.push(u32::try_from(spans.len()).ok()?);
        Some(Box::new(Self {
            origin,
            width,
            offsets: offsets.into_boxed_slice(),
            spans: spans.into_boxed_slice(),
            states: states.into_boxed_slice(),
            runs: Arc::new(runs),
        }))
    }
    fn compare(&self, actual: &Exterior) -> Result<usize, String> {
        if self.origin != actual.origin
            || self.width != actual.width
            || self.offsets.len() != actual.ranges.len() + 1
        {
            return Err("exterior index size differs".into());
        }
        let mut checked = 0;
        // Compare every interval boundary and immutable material-run sequence. This
        // includes all holes, without allocating a result for every prefix.
        for (column, &[start, end]) in actual.ranges.iter().enumerate() {
            let expected =
                &self.spans[self.offsets[column] as usize..self.offsets[column + 1] as usize];
            let found = &actual.spans[start as usize..end as usize];
            if expected.len() != found.len() {
                return Err(format!("interval count differs at {column}"));
            }
            for (a, b) in expected.iter().zip(found) {
                let a_state = &self.states[a.state as usize];
                let b_state = &actual.states[b.state as usize];
                if a.start != b.start
                    || a.end != b.end
                    || self.runs[a_state.range.clone()] != actual.runs[b_state.range.clone()]
                {
                    return Err(format!("exterior value or interval differs at {column}"));
                }
                checked += 1;
            }
        }
        Ok(checked)
    }
}
fn stats(values: &[f64]) -> serde_json::Value {
    let mut sorted = values.to_vec();
    sorted.sort_by(f64::total_cmp);
    let n = sorted.len();
    serde_json::json!({"samples_ms":values,"median_ms":(sorted[(n-1)/2]+sorted[n/2])/2.,
        "p95_nearest_rank_ms":sorted[((n as f64 * 0.95).ceil() as usize)-1],"maximum_ms":sorted[n-1]})
}
pub(in crate::decoration::dependencies) fn observe(planner: &Planner<'_>) -> Result<(), String> {
    let Some(path) = ACTIVE
        .lock()
        .map_err(|_| "export benchmark poisoned")?
        .take()
    else {
        return Ok(());
    };
    let original = Reference::capture(planner).ok_or("reference export failed")?;
    let (current, slots) = Exterior::capture(
        planner.versions.origin,
        planner.versions.width,
        &planner.ready_columns,
    )
    .ok_or("direct export failed")?;
    let checked = original.compare(&current)?;
    let states = current.states.len();
    drop(original);
    drop(current);
    let mut timings = [Vec::new(), Vec::new()];
    for round in 0..25 {
        for variant in if round % 2 == 0 { [0, 1] } else { [1, 0] } {
            let started = Instant::now();
            if variant == 0 {
                let saved = Reference::capture(planner).ok_or("reference export failed")?;
                std::hint::black_box(&saved);
                drop(saved);
            } else {
                let saved = Exterior::capture(
                    planner.versions.origin,
                    planner.versions.width,
                    &planner.ready_columns,
                )
                .ok_or("direct export failed")?;
                std::hint::black_box(&saved);
                drop(saved);
            }
            let ms = started.elapsed().as_secs_f64() * 1000.;
            if round >= 5 {
                timings[variant].push(ms);
            }
        }
    }
    let report = serde_json::json!({
        "scope":"Paired component experiment on identical immutable natural planner cells. Five warmups and twenty measured exports per variant; alternating order. Includes archive destruction, excludes original planner retirement and generation. Normal libraries contain no benchmark. This is not a complete-region speedup or uncontended acceptance.",
        "origin":planner.versions.origin,"seed":planner.request.seed,"min_y":planner.request.min_y,
        "height":planner.request.height,"root_count":planner.roots.len(),"exact_slots":slots,
        "intervals_compared":checked,"states":states,"full_interval_value_parity":true,
        "reference_staged":stats(&timings[0]),"direct_ordered_tiles":stats(&timings[1]),"timing_acceptance":false,
    });
    fs::write(
        path,
        serde_json::to_vec_pretty(&report).map_err(|e| e.to_string())?,
    )
    .map_err(|e| e.to_string())
}

#[test]
#[ignore = "GPU component benchmark requires RETINA_TERRAIN_REUSE_PROFILES and RETINA_EXTERIOR_EXPORT_BENCH_OUTPUT"]
fn paired_exterior_exports_from_natural_planners() {
    let profiles = PathBuf::from(std::env::var("RETINA_TERRAIN_REUSE_PROFILES").unwrap());
    let output = PathBuf::from(std::env::var("RETINA_EXTERIOR_EXPORT_BENCH_OUTPUT").unwrap());
    fs::create_dir(&output).unwrap();
    for (name, file, seed) in [
        ("nether-main", "nether.json", 123456789),
        ("nether-second-seed", "nether.json", 42),
        ("nether-custom-bounds", "custom.json", 123456789),
        ("nether-direct-stem", "direct-stem.json", 42),
    ] {
        let bytes = fs::read(profiles.join(file)).unwrap();
        let data: serde_json::Value = serde_json::from_slice(&bytes).unwrap();
        let engine = crate::TerrainEngine::new().unwrap();
        let id = engine.register_reference_profile(&bytes).unwrap();
        let bounds = &data["generation_bounds"];
        let request = crate::ChunkRequest {
            seed,
            chunk_x: 0,
            chunk_z: 0,
            min_y: bounds["storage_min_y"].as_i64().unwrap_or(-64) as i32,
            height: bounds["storage_height"].as_u64().unwrap_or(384) as u32,
            base_height: 64.,
            amplitude: 48.,
            frequency: 0.008,
            reserved: id,
        };
        let biome = data["biomes"][0]["id"].as_str().unwrap();
        let dir = output.join(name);
        fs::create_dir(&dir).unwrap();
        let generate = |label: &str, x: i32, z: i32| {
            let report = crate::region::generate_region(
                &engine,
                crate::ChunkRequest {
                    chunk_x: x * 32,
                    chunk_z: z * 32,
                    ..request
                },
                &dir.join(format!("{label}.mca")),
                0,
                biome,
            )
            .unwrap();
            assert_eq!(report.generated, 1024);
        };
        for i in 0..5 {
            generate(&format!("warm{i}"), 8 + i, 8);
        }
        *ACTIVE.lock().unwrap() = Some(output.join(format!("{name}.json")));
        let installed = Installed;
        generate("0", -2, -2);
        assert!(ACTIVE.lock().unwrap().is_none());
        drop(installed);
        println!("paired exterior export {name} complete");
    }
}
