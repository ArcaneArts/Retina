//! Matched one/two-slot GPU + readback benchmark; output hashing is outside timing.
//! gpu_pipeline_benchmark <retina.dat> <dimension> <depth:1|2> [count]
use rayon::prelude::*;
use retina_worldgen::{
    TerrainEngine,
    region::codec::Compression,
    world_definition::{WorldDefinition, runtime::WorldSession},
};
use std::{sync::Arc, time::Instant};
fn main() -> Result<(), Box<dyn std::error::Error>> {
    let mut args = std::env::args().skip(1);
    let source = Arc::new(WorldDefinition::read(
        args.next().ok_or("definition path required")?,
    )?);
    let key = args.next().ok_or("dimension required")?;
    let depth: usize = args.next().ok_or("pipeline depth required")?.parse()?;
    let count: usize = args.next().map(|s| s.parse()).transpose()?.unwrap_or(12);
    if args.next().is_some() {
        return Err("unexpected argument; seed comes from retina.dat".into());
    }
    let engine = Arc::new(TerrainEngine::with_pipeline_depth(depth)?);
    let world = WorldSession::bind(engine.clone(), source.clone(), Compression::Zlib)?;
    let dimension = world.dimension(&key)?;
    let callers = rayon::ThreadPoolBuilder::new().num_threads(2).build()?;
    callers.install(|| {
        (0..4).into_par_iter().try_for_each(|i| {
            engine
                .terrain_field(dimension.request((8 + i) * 32, 8 * 32)?, 32)
                .map(|_| ())
        })
    })?;
    let before = engine.pipeline_snapshot();
    let start = Instant::now();
    let fields = callers.install(|| {
        (0..count)
            .into_par_iter()
            .map(|i| {
                let begin = Instant::now();
                engine
                    .terrain_field(
                        dimension.request((i % 3) as i32 * 32 - 32, (i / 3) as i32 * 32 - 32)?,
                        32,
                    )
                    .map(|field| (field, begin.elapsed().as_secs_f64() * 1000.))
            })
            .collect::<Result<Vec<_>, String>>()
    })?;
    let wall_ms = start.elapsed().as_secs_f64() * 1000.;
    let after = engine.pipeline_snapshot();
    let mut hash = 0xcbf29ce484222325u64;
    for ((field, mask), _) in &fields {
        for c in &field.columns {
            for value in [c.height as u32, c.packed, c.materials] {
                hash = (hash ^ value as u64).wrapping_mul(0x100000001b3);
            }
        }
        if let Some(mask) = mask {
            for value in &mask.words {
                hash = (hash ^ *value as u64).wrapping_mul(0x100000001b3);
            }
        }
    }
    let mut latencies: Vec<_> = fields.iter().map(|f| f.1).collect();
    latencies.sort_by(f64::total_cmp);
    println!(
        "{}",
        serde_json::json!({"backend":engine.backend(),"dimension":key,"seed":source.seed(),"depth":depth,"callers":2,"regions":count,
        "wall_ms":wall_ms,"regions_per_second":count as f64*1000./wall_ms,"median_request_ms":latencies.get(count/2),
        "signature":format!("{hash:016x}"),"peak_in_flight":after.peak_in_flight,
        "completed":after.completed-before.completed,"device_span_ms":(after.device_span_nanos-before.device_span_nanos) as f64/1e6,
        "device_gap_ms":(after.device_gap_nanos-before.device_gap_nanos) as f64/1e6,
        "unavailable_timestamp_pairs":after.unavailable_timestamp_pairs-before.unavailable_timestamp_pairs})
    );
    Ok(())
}
