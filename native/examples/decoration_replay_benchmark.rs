//! <retina.dat> <dimension> [side=4] [pairs=5] [chunk_x=0] [chunk_z=0]
//! Bounded planner A/B on one retained GPU field. Use a 30-second process-group
//! deadline, reduced priority, and RETINA_INTERPRETER_PRELOAD=0. No region writes.
use retina_worldgen::{
    TerrainEngine,
    decoration::Placement,
    region::codec::Compression,
    world_definition::{
        WorldDefinition,
        runtime::{ProgramExecution, WorldSession},
    },
};
use std::{sync::Arc, time::Instant};

fn signature(plan: &[Vec<Placement>]) -> u64 {
    let mut hash = 0xcbf29ce484222325u64;
    for chunk in plan {
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
    hash
}
fn main() -> Result<(), Box<dyn std::error::Error>> {
    let mut args = std::env::args().skip(1);
    let source = Arc::new(WorldDefinition::read(
        args.next().ok_or("definition path required")?,
    )?);
    let key = args.next().ok_or("dimension required")?;
    let side: usize = args.next().map(|a| a.parse()).transpose()?.unwrap_or(4);
    let pairs: usize = args.next().map(|a| a.parse()).transpose()?.unwrap_or(5);
    let chunk_x = args.next().map(|a| a.parse()).transpose()?.unwrap_or(0);
    let chunk_z = args.next().map(|a| a.parse()).transpose()?.unwrap_or(0);
    if args.next().is_some() {
        return Err("unexpected argument; seed comes from retina.dat".into());
    }
    if !(1..=16).contains(&side) || !(1..=9).contains(&pairs) {
        return Err("side must be 1..16; pairs 1..9".into());
    }
    let engine = Arc::new(TerrainEngine::new()?);
    let world = WorldSession::bind_with_execution(
        engine.clone(),
        source.clone(),
        Compression::Zlib,
        Some(ProgramExecution::Interpreter),
    )?;
    let dimension = world.dimension(&key)?;
    let request = dimension.request(chunk_x, chunk_z)?;
    let profile = engine.profile(request.reserved)?.ok_or("missing profile")?;
    let (field, mask) = engine.terrain_field(request, side as u32)?;
    let workers = rayon::ThreadPoolBuilder::new().num_threads(2).build()?;
    let mut samples = [Vec::new(), Vec::new()];
    let mut expected = None;
    for round in 0..=pairs {
        for resume in [round % 2 == 0, round % 2 != 0] {
            let start = Instant::now();
            let result = workers.install(|| {
                if resume {
                    engine.plan_decorations(&field, &profile, request, side, mask.as_deref())
                } else {
                    engine.plan_decorations_full_replay(
                        &field,
                        &profile,
                        request,
                        side,
                        mask.as_deref(),
                    )
                }
            })?;
            let elapsed = start.elapsed().as_secs_f64() * 1000.;
            if round > 0 {
                samples[resume as usize].push(elapsed);
            }
            if let Some(expected) = &expected {
                assert_eq!(&result, expected, "ordered output differs");
            } else {
                expected = Some(result);
            }
        }
    }
    let (detailed, phases) = workers.install(|| {
        engine.plan_decorations_detailed(&field, &profile, request, side, mask.as_deref())
    })?;
    assert_eq!(&detailed, expected.as_ref().unwrap());
    let median = samples.each_ref().map(|v| {
        let mut v = v.clone();
        v.sort_by(f64::total_cmp);
        v[v.len() / 2]
    });
    let max = samples
        .each_ref()
        .map(|v| v.iter().copied().reduce(f64::max).unwrap());
    println!(
        "{}",
        serde_json::json!({"backend":engine.backend(),"dimension":key,"seed":source.seed(),"chunk_x":chunk_x,"chunk_z":chunk_z,
        "side":side,"workers":2,"warmup_pairs":1,"pairs":pairs,"full_replay_ms":samples[0],"resumed_ms":samples[1],
        "median_ms":{"full_replay":median[0],"resumed":median[1]},"max_ms":{"full_replay":max[0],"resumed":max[1]},
        "placements":detailed.iter().map(Vec::len).sum::<usize>(),"ordered_signature":format!("{:016x}",signature(&detailed)),
        "exact_order":true,"phases":phases})
    );
    Ok(())
}
