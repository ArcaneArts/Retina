//! Test-only observation of successful production snapshot reads.
//! Normal libraries contain neither the probe nor its call site.
use crate::{ChunkRequest, TerrainEngine};
use std::sync::atomic::{AtomicBool, AtomicU64, Ordering};

static ACTIVE: AtomicBool = AtomicBool::new(false);
static DEPTH: [AtomicU64; 256] = [const { AtomicU64::new(0) }; 256];

pub(super) fn hit(x: usize, z: usize, width: usize) {
    if ACTIVE.load(Ordering::Relaxed) {
        let depth = x.min(z).min(width - 1 - x).min(width - 1 - z);
        DEPTH[depth].fetch_add(1, Ordering::Relaxed);
    }
}
struct Observing;
impl Observing {
    fn start() -> Self {
        for count in &DEPTH {
            count.store(0, Ordering::Relaxed);
        }
        ACTIVE.store(true, Ordering::Relaxed);
        Self
    }
}
impl Drop for Observing {
    fn drop(&mut self) {
        ACTIVE.store(false, Ordering::Relaxed);
    }
}

#[test]
#[ignore = "requires GPU, RETINA_TERRAIN_REUSE_PROFILES and RETINA_VERSION_FOOTPRINT_OUTPUT"]
fn completed_snapshot_read_depths() {
    use std::{fs, path::PathBuf};
    let profiles = PathBuf::from(std::env::var("RETINA_TERRAIN_REUSE_PROFILES").unwrap());
    let output = PathBuf::from(std::env::var("RETINA_VERSION_FOOTPRINT_OUTPUT").unwrap());
    fs::create_dir(&output).unwrap();
    let mut rows = Vec::new();
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
        let biome = profile["biomes"][0]["id"].as_str().unwrap();
        let directory = output.join(format!("{name}-run0"));
        fs::create_dir(&directory).unwrap();
        let generate = |name: &str, x: i32, z: i32| {
            let request = ChunkRequest {
                chunk_x: x * 32,
                chunk_z: z * 32,
                ..request
            };
            let report = crate::region::generate_region(
                &engine,
                request,
                &directory.join(format!("{name}.mca")),
                0,
                biome,
            )
            .unwrap();
            assert_eq!(report.generated, 1024);
            assert_eq!(report.preserved, 0);
        };
        for i in 0..5 {
            generate(&format!("warm{i}"), 8 + i, 8);
        }
        let observing = Observing::start();
        for i in 0..20 {
            generate(&i.to_string(), -2 + i % 5, -2 + i / 5);
        }
        drop(observing);
        let counts: Vec<u64> = DEPTH.iter().map(|v| v.load(Ordering::Relaxed)).collect();
        let hits: u64 = counts.iter().sum();
        assert!(hits > 0, "actual snapshot hits must be observed");
        let maximum = counts.iter().rposition(|&v| v > 0).unwrap();
        let bands: Vec<_> = [8, 16, 32, 64, 128, 256].into_iter().map(|width| {
            serde_json::json!({"edge_width":width,"hits_inside_band":counts[..width].iter().sum::<u64>()})
        }).collect();
        println!("snapshot read depths {name}: hits={hits} max_depth={maximum} bands={bands:?}");
        rows.push(serde_json::json!({"case":name,"seed":seed,"profile":file,
            "hits":hits,"maximum_depth":maximum,"counts_by_depth":counts,"bands":bands}));
        fs::write(output.join("report.json"), serde_json::to_vec_pretty(&serde_json::json!({
            "scope":"Actual successful immutable snapshot reads during twenty fresh lit-MCA regions after five completed warmups. Depth is distance in blocks from the source region's nearest edge. No production retention change and no timing acceptance.",
            "rows":rows,"timing_acceptance":false,
        })).unwrap()).unwrap();
    }
}
