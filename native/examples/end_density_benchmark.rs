//! Bounded density-stage probe, not a complete-region performance benchmark.
//! Includes dense lattice construction, every column and hashing of every float.
use retina_worldgen::end::VanillaDensity;
use serde_json::json;
use std::{error::Error, time::Instant};
use xxhash_rust::xxh32::Xxh32;

fn run(noise: &VanillaDensity, width: u32, tiled: bool) -> Result<serde_json::Value, Box<dyn Error>> {
    let start = Instant::now();
    let tile = if tiled { Some(noise.tile(4096, -528, width, width)?) } else { None };
    let mut hash = Xxh32::new(0);
    for z in -528..-528 + width as i32 {
        for x in 4096..4096 + width as i32 {
            let values = match &tile {
                Some(tile) => tile.column_densities(x, z).unwrap(),
                None => noise.column_densities(x, z),
            };
            let bits = values.map(|v| v.to_bits().to_le());
            hash.update(bytemuck::cast_slice(&bits));
        }
    }
    let elapsed_ms = start.elapsed().as_secs_f64() * 1000.0;
    Ok(json!({"width_blocks":width, "columns":u64::from(width)*u64::from(width),
        "mode":if tiled {"dense_lattice"} else {"independent_columns"},
        "elapsed_ms":elapsed_ms, "density_bits_xxh32":format!("{:08x}", hash.digest()),
        "lattice_points":tile.as_ref().map(|t| t.lattice_points()),
        "lattice_bytes":tile.as_ref().map(|t| t.lattice_bytes())}))
}

fn main() -> Result<(), Box<dyn Error>> {
    let noise = VanillaDensity::new(123456789);
    // Warm both paths, then alternate their order. Only the small tile runs the
    // intentionally redundant reference evaluator. Larger tiles stay bounded.
    run(&noise, 16, false)?;
    run(&noise, 16, true)?;
    let mut trials = Vec::new();
    for trial in 0..4 {
        let order = if trial % 2 == 0 { [false, true] } else { [true, false] };
        let mut pair = Vec::new();
        for tiled in order { pair.push(run(&noise, 16, tiled)?); }
        if pair[0]["density_bits_xxh32"] != pair[1]["density_bits_xxh32"] {
            return Err("density-stage A/B hashes differ".into());
        }
        trials.extend(pair);
    }
    for width in [64, 512] {
        run(&noise, width, true)?;
        for _ in 0..3 { trials.push(run(&noise, width, true)?); }
    }
    println!("{}", json!({"schema":"retina.end.dense-lattice-probe.v1", "seed":123456789,
        "origin_blocks":[4096,-528], "scope":"CPU standard-graph density only, lattice construction and full density-bit hashing included; no features, lighting, encoding or publication",
        "full_regions_generated":0, "trials":trials}));
    Ok(())
}
