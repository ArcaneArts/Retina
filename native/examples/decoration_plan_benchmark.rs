//! Real GPU terrain, registered feature planning, and ordered-output validation.
//! decoration_plan_benchmark <profile.json> [seed] [chunk_x] [chunk_z] [iterations=3] [side=4] [workers=2]
//! Run under a process-group deadline. This diagnostic forces interpreter terrain
//! to avoid starting expensive asynchronous shader specialization.
use retina_worldgen::{ChunkRequest, TerrainEngine};
use std::time::Instant;

fn main() -> Result<(), Box<dyn std::error::Error>> {
    let mut args = std::env::args().skip(1);
    let mut source: serde_json::Value =
        serde_json::from_slice(&std::fs::read(args.next().ok_or("profile path required")?)?)?;
    source["program_execution"] = "interpreter".into();
    let source = serde_json::to_vec(&source)?;
    let seed = args
        .next()
        .map(|v| v.parse())
        .transpose()?
        .unwrap_or(123456789);
    let chunk_x = args.next().map(|v| v.parse()).transpose()?.unwrap_or(0);
    let chunk_z = args.next().map(|v| v.parse()).transpose()?.unwrap_or(0);
    let iterations: usize = args.next().map(|v| v.parse()).transpose()?.unwrap_or(3);
    let side: usize = args.next().map(|v| v.parse()).transpose()?.unwrap_or(4);
    let threads: usize = args.next().map(|v| v.parse()).transpose()?.unwrap_or(2);
    if !(1..=9).contains(&iterations) || !(1..=32).contains(&side) || !(1..=2).contains(&threads) {
        return Err("use iterations 1..9, side 1..32 and workers 1..2".into());
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
    let (field, mask) = engine.terrain_field(request, side as u32)?;
    let workers = rayon::ThreadPoolBuilder::new()
        .num_threads(threads)
        .build()?;
    let mut samples = Vec::new();
    let mut expected = None;
    let mut placements = 0;
    for iteration in 0..iterations + 1 {
        let begin = Instant::now();
        let plan = workers.install(|| {
            engine.plan_decorations(&field, &profile, request, side, mask.as_deref())
        })?;
        let ms = begin.elapsed().as_secs_f64() * 1000.;
        if iteration >= 1 {
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
                    p.tick as u64,
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
    let reference = workers
        .install(|| engine.plan_decorations(&field, &profile, request, side, mask.as_deref()))?;
    let (detailed, phases) = workers.install(|| {
        engine.plan_decorations_detailed(&field, &profile, request, side, mask.as_deref())
    })?;
    assert_eq!(
        reference, detailed,
        "diagnostics must preserve every ordered placement"
    );
    // Optional cross-build comparison artifact, outside all measured intervals.
    if let Some(path) = std::env::var_os("RETINA_PLAN_OUTPUT") {
        use std::io::Write;
        let mut out = std::io::BufWriter::new(std::fs::File::create(path)?);
        out.write_all(&(reference.len() as u32).to_le_bytes())?;
        for chunk in &reference {
            out.write_all(&(chunk.len() as u32).to_le_bytes())?;
            for p in chunk {
                out.write_all(&p.index.to_le_bytes())?;
                out.write_all(&p.material.to_le_bytes())?;
                out.write_all(&p.upper.to_le_bytes())?;
                out.write_all(&[p.role])?;
                out.write_all(&p.tick.to_le_bytes())?;
            }
        }
        out.flush()?;
    }
    let mut sorted = samples.clone();
    sorted.sort_by(f64::total_cmp);
    println!(
        "{}",
        serde_json::json!({"backend":engine.backend(),"seed":seed,
        "chunk_x":chunk_x,"chunk_z":chunk_z,"workers":threads,"side":side,"warmups":1,"iterations":iterations,"phases":phases,
        "placements":placements,"signature":format!("{:016x}", expected.unwrap()),
        "median_ms":sorted[iterations/2],"max_ms":sorted[iterations-1],
        "samples_ms":samples})
    );
    Ok(())
}
