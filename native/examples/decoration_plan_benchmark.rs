//! Real GPU terrain, registered feature planning, and ordered-output validation.
//! decoration_plan_benchmark <profile.json> [seed] [chunk_x] [chunk_z] [iterations]
use retina_worldgen::{ChunkRequest, TerrainEngine};
use std::time::Instant;

fn main() -> Result<(), Box<dyn std::error::Error>> {
    let mut args = std::env::args().skip(1);
    let source = std::fs::read(args.next().ok_or("profile path required")?)?;
    let seed = args
        .next()
        .map(|v| v.parse())
        .transpose()?
        .unwrap_or(123456789);
    let chunk_x = args.next().map(|v| v.parse()).transpose()?.unwrap_or(0);
    let chunk_z = args.next().map(|v| v.parse()).transpose()?.unwrap_or(0);
    let iterations: usize = args.next().map(|v| v.parse()).transpose()?.unwrap_or(25);
    if iterations == 0 {
        return Err("iterations must be positive".into());
    }
    let engine = TerrainEngine::new()?;
    let reserved = engine.register_profile(&source)?;
    let profile = engine.profile(reserved)?.ok_or("missing profile")?;
    let request = ChunkRequest {
        seed,
        chunk_x,
        chunk_z,
        min_y: -64,
        height: 384,
        base_height: 64.,
        amplitude: 48.,
        frequency: 0.008,
        reserved,
    };
    let (field, mask) = engine.terrain_field(request, 32)?;
    let workers = rayon::ThreadPoolBuilder::new().num_threads(16).build()?;
    let mut samples = Vec::new();
    let mut expected = None;
    let mut placements = 0;
    for iteration in 0..iterations + 5 {
        let begin = Instant::now();
        let plan = workers
            .install(|| engine.plan_decorations(&field, &profile, request, 32, mask.as_deref()))?;
        let ms = begin.elapsed().as_secs_f64() * 1000.;
        if iteration >= 5 {
            samples.push(ms);
        }
        // Hashing is outside the measured interval. Every role and ordered write counts.
        let mut hash = 0xcbf29ce484222325u64;
        placements = 0;
        for chunk in &plan {
            placements += chunk.len();
            hash = (hash ^ chunk.len() as u64).wrapping_mul(0x100000001b3);
            for p in chunk {
                for v in [
                    p.index as u64,
                    p.material as u64,
                    p.upper as u64,
                    p.role as u64,
                ] {
                    hash = (hash ^ v).wrapping_mul(0x100000001b3);
                }
            }
        }
        if let Some(expected) = expected {
            assert_eq!(expected, hash, "ordered plan changed");
        } else {
            expected = Some(hash);
        }
    }
    let mut sorted = samples.clone();
    sorted.sort_by(f64::total_cmp);
    println!(
        "{}",
        serde_json::json!({"backend":engine.backend(),"seed":seed,
        "chunk_x":chunk_x,"chunk_z":chunk_z,"workers":16,"warmups":5,"iterations":iterations,
        "placements":placements,"signature":format!("{:016x}", expected.unwrap()),
        "median_ms":sorted[iterations/2],"p95_ms":sorted[(iterations*95/100).min(iterations-1)],
        "samples_ms":samples})
    );
    Ok(())
}
