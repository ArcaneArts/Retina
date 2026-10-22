//! Test-only request footprint before TerrainJob normalizes to its 16-chunk
//! grid. This is NOT an access trace: consumers can read other halo coordinates
//! through a returned Field or CaveMask, or bypass TerrainJob with initial data.
use crate::{ChunkRequest, TerrainEngine};
use serde_json::{Value, json};
use std::collections::BTreeMap;
use std::sync::{
    Arc, Mutex,
    atomic::{AtomicUsize, Ordering},
};

static ACTIVE: Mutex<Option<Arc<Session>>> = Mutex::new(None);
#[derive(Default)]
struct Session {
    live: AtomicUsize,
    jobs: Mutex<Vec<Value>>,
}
struct Installed(Arc<Session>);
impl Installed {
    fn start() -> Self {
        let mut active = ACTIVE.lock().unwrap();
        assert!(active.is_none());
        let session = Arc::new(Session::default());
        *active = Some(session.clone());
        Self(session)
    }
    fn report(&self) -> Value {
        assert_eq!(
            self.0.live.load(Ordering::Acquire),
            0,
            "terrain jobs must have joined and dropped"
        );
        json!({"jobs":*self.0.jobs.lock().unwrap(),"live_jobs":0})
    }
}
impl Drop for Installed {
    fn drop(&mut self) {
        *ACTIVE.lock().unwrap_or_else(|e| e.into_inner()) = None;
    }
}
struct Job {
    session: Arc<Session>,
    request: ChunkRequest,
    queries: Mutex<BTreeMap<(i32, i32), [u64; 2]>>,
}
pub(super) struct Trace(Option<Job>);
impl Trace {
    pub(super) fn new(request: ChunkRequest) -> Self {
        Self(ACTIVE.lock().unwrap().as_ref().map(|session| {
            session.live.fetch_add(1, Ordering::Relaxed);
            Job {
                session: session.clone(),
                request,
                queries: Mutex::new(BTreeMap::new()),
            }
        }))
    }
    pub(super) fn query(&self, x: i32, z: i32, kind: usize) {
        if let Some(job) = &self.0 {
            job.queries.lock().unwrap().entry((x, z)).or_default()[kind] += 1;
        }
    }
}
impl Drop for Trace {
    fn drop(&mut self) {
        if let Some(job) = self.0.take() {
            let coordinates: Vec<_> = job.queries.into_inner().unwrap().into_iter()
                .map(|((x,z),calls)| json!({"x":x,"z":z,"field_calls":calls[0],"mask_calls":calls[1]})).collect();
            let r = job.request;
            job.session.jobs.lock().unwrap().push(json!({
                "origin":[r.chunk_x,r.chunk_z],"seed":r.seed,"profile_id":r.reserved,
                "min_y":r.min_y,"height":r.height,"base_height_bits":r.base_height.to_bits(),
                "amplitude_bits":r.amplitude.to_bits(),"frequency_bits":r.frequency.to_bits(),
                "coordinates":coordinates,
            }));
            job.session.live.fetch_sub(1, Ordering::Release);
        }
    }
}

#[test]
fn preserves_requested_coordinates_and_counts_and_releases_job() {
    let session = Arc::new(Session::default());
    session.live.store(1, Ordering::Relaxed);
    let trace = Trace(Some(Job {
        session: session.clone(),
        request: ChunkRequest {
            seed: 42,
            chunk_x: -32,
            chunk_z: 16,
            min_y: 0,
            height: 256,
            base_height: 64.,
            amplitude: 48.,
            frequency: 0.008,
            reserved: 1,
        },
        queries: Mutex::new(BTreeMap::new()),
    }));
    trace.query(-1, 17, 0);
    trace.query(-1, 17, 0);
    trace.query(-1, 17, 1);
    trace.query(-16, 31, 1);
    drop(trace);
    assert_eq!(session.live.load(Ordering::Acquire), 0);
    assert_eq!(Arc::strong_count(&session), 1);
    let jobs = session.jobs.lock().unwrap();
    assert_eq!(jobs.len(), 1);
    assert_eq!(
        jobs[0]["coordinates"],
        json!([
            {"x":-16,"z":31,"field_calls":0,"mask_calls":1},
            {"x":-1,"z":17,"field_calls":2,"mask_calls":1},
        ])
    );
}

#[test]
#[ignore = "requires GPU, RETINA_TERRAIN_REUSE_PROFILES and RETINA_TERRAIN_DEMAND_OUTPUT"]
fn exact_terrain_request_inventory() {
    use std::{fs, path::PathBuf, time::Instant};
    let profiles = PathBuf::from(std::env::var("RETINA_TERRAIN_REUSE_PROFILES").unwrap());
    let output = PathBuf::from(std::env::var("RETINA_TERRAIN_DEMAND_OUTPUT").unwrap());
    fs::create_dir(&output).unwrap();
    let mut cases = Vec::new();
    for (name, file, seed) in [
        ("nether-main", "nether.json", 123456789),
        ("nether-second-seed", "nether.json", 42),
        ("nether-custom-bounds", "custom.json", 123456789),
        ("nether-direct-stem", "direct-stem.json", 42),
        ("overworld", "overworld.json", 123456789),
    ] {
        let bytes = fs::read(profiles.join(file)).unwrap();
        let profile: Value = serde_json::from_slice(&bytes).unwrap();
        let engine = TerrainEngine::new().unwrap();
        let id = engine.register_profile(&bytes).unwrap();
        let bounds = &profile["generation_bounds"];
        let request = ChunkRequest {
            seed,
            chunk_x: 0,
            chunk_z: 0,
            reserved: id,
            min_y: bounds["storage_min_y"].as_i64().unwrap_or(-64) as i32,
            height: bounds["storage_height"].as_u64().unwrap_or(384) as u32,
            base_height: 64.,
            amplitude: 48.,
            frequency: 0.008,
        };
        let biome = profile["biomes"][0]["id"].as_str().unwrap();
        let directory = output.join(format!("{name}-run0"));
        fs::create_dir(&directory).unwrap();
        let generate = |name: &str, x: i32, z: i32, observe: bool| {
            let observer = observe.then(Installed::start);
            let before = engine.timings(id).performance.snapshot();
            let program_before = engine.pipeline.program_snapshot(id);
            let start = Instant::now();
            let report = crate::region::generate_region(
                &engine,
                ChunkRequest {
                    chunk_x: x * 32,
                    chunk_z: z * 32,
                    ..request
                },
                &directory.join(format!("{name}.mca")),
                0,
                biome,
            )
            .unwrap();
            assert_eq!(report.generated, 1024);
            assert_eq!(report.preserved, 0);
            let elapsed = start.elapsed().as_secs_f64() * 1000.;
            json!({"name":name,"x":x,"z":z,"instrumented_ms":elapsed,
                "program_before":program_before,"program_after":engine.pipeline.program_snapshot(id),
                "performance_before":before,"performance_after":engine.timings(id).performance.snapshot(),
                "requests":observer.as_ref().map(Installed::report)})
        };
        let warmups: Vec<_> = (0..5)
            .map(|i| generate(&format!("warm{i}"), 8 + i, 8, false))
            .collect();
        let regions: Vec<_> = (0..20)
            .map(|i| generate(&i.to_string(), -2 + i % 5, -2 + i / 5, true))
            .collect();
        drop(engine);
        println!("terrain request inventory {name}: 20 complete regions");
        cases.push(
            json!({"case":name,"seed":seed,"profile":file,"warmups":warmups,"regions":regions}),
        );
        fs::write(output.join("report.json"),serde_json::to_vec_pretty(&json!({
            "schema":1,"cases":cases,"timing_acceptance":false,
            "scope":"Test-only TerrainJob field/mask request coordinates BEFORE 16-chunk normalization. Job-local observer locks perturb timing; all timings are diagnostic. This is NOT the full material/column/height/biome access footprint: returned fields and masks can be read elsewhere in their halo, and initial borrowed fields can bypass TerrainJob. It cannot justify shrinking coverage or truncating reach. Observers retain scalar metadata only and assert every traced job has dropped at joined region completion. GPU rectangles are existing performance snapshots, with warmups excluded by subtracting before from after. No production behavior change."
        })).unwrap()).unwrap();
    }
}
