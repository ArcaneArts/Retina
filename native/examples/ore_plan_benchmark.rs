//! CPU ore-planning benchmark on a single real GPU field; no GPU work during measurements.
//! cargo run --release --example ore_plan_benchmark -- <profile.json> [seed] [chunk_x] [chunk_z] [workers] [iterations]
use retina_worldgen::{ChunkRequest, TerrainEngine, geology};
use std::{hint::black_box, time::Instant};

fn signature(plan: &[Vec<geology::OrePlacement>]) -> (usize, u64) {
    let mut hash = 0xcbf29ce484222325u64;
    let mut count = 0;
    for (slot, ores) in plan.iter().enumerate() {
        for value in [slot as u64, ores.len() as u64] {
            hash = (hash ^ value).wrapping_mul(0x100000001b3);
        }
        count += ores.len();
        for ore in ores {
            for value in [ore.index, ore.recipe, ore.random] {
                hash = (hash ^ value as u64).wrapping_mul(0x100000001b3);
            }
        }
    }
    (count, hash)
}
fn main() -> Result<(), Box<dyn std::error::Error>> {
    let mut args = std::env::args().skip(1);
    let source = std::fs::read(
        args.next()
            .ok_or("usage: ore_plan_benchmark <profile.json> [seed] [chunk_x] [chunk_z] [workers] [iterations]")?,
    )?;
    let seed = args
        .next()
        .map(|s| s.parse())
        .transpose()?
        .unwrap_or(123456789);
    let chunk_x = args.next().map(|s| s.parse()).transpose()?.unwrap_or(0);
    let chunk_z = args.next().map(|s| s.parse()).transpose()?.unwrap_or(0);
    let threads: usize = args.next().map(|s| s.parse()).transpose()?.unwrap_or(16);
    let iterations: usize = args.next().map(|s| s.parse()).transpose()?.unwrap_or(25);
    if iterations == 0 {
        return Err("iterations must be positive".into());
    }
    let engine = TerrainEngine::new()?;
    let reserved = engine.register_profile(&source)?;
    let profile = engine
        .profile(reserved)?
        .ok_or("missing registered profile")?;
    let request = ChunkRequest {
        seed,
        chunk_x,
        chunk_z,
        min_y: -64,
        height: 384,
        base_height: 64.0,
        amplitude: 48.0,
        frequency: 0.008,
        reserved,
    };
    let (field, mask) = engine.terrain_field(request, 32)?;
    let workers = rayon::ThreadPoolBuilder::new()
        .num_threads(threads)
        .build()?;
    let mut timings = Vec::new();
    let mut expected = None;
    for iteration in 0..iterations + 5 {
        let start = Instant::now();
        let plan =
            workers.install(|| geology::plan(&field, &profile, request, 32, mask.as_deref()));
        let ms = start.elapsed().as_secs_f64() * 1000.0;
        if iteration >= 5 {
            timings.push(ms);
        }
        let actual = signature(black_box(&plan));
        if let Some(expected) = expected {
            assert_eq!(expected, actual, "repeated ore plans changed");
        } else {
            expected = Some(actual);
        }
    }
    let mut sorted = timings.clone();
    sorted.sort_by(f64::total_cmp);
    let (placements, signature) = expected.unwrap();
    println!(
        "{}",
        serde_json::json!({"backend":engine.backend(),"seed":seed,"chunk_x":chunk_x,"chunk_z":chunk_z,"workers":threads,"warmups":5,"iterations":iterations,"ore_recipes":profile.geology.ores.len(),"placements":placements,"signature":format!("{signature:016x}"),"median_ms":sorted[sorted.len()/2],"p95_ms":sorted[(sorted.len()*95/100).min(sorted.len()-1)],"samples_ms":timings})
    );
    Ok(())
}
