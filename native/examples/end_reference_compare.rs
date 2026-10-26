//! Compare the native island primitive against actual Minecraft query captures.
use retina_worldgen::end::IslandNoise;
use serde_json::Value;
use std::{error::Error, fs};

fn main() -> Result<(), Box<dyn Error>> {
    let path = std::env::args().nth(1).ok_or("expected vanilla reference.json path")?;
    let source: Value = serde_json::from_slice(&fs::read(&path)?)?;
    let mut samples = 0;
    let mut outer_samples = 0;
    let mut center_samples = 0;
    for seed in source["seeds"].as_array().ok_or("missing seeds")? {
        let seed_value = seed["seed"].as_i64().ok_or("missing world seed")?;
        let noise = IslandNoise::new(seed_value as u64);
        for sample in seed["samples"].as_array().ok_or("missing samples")? {
            let x = sample["xz"][0].as_i64().ok_or("missing x")? as i32;
            let z = sample["xz"][1].as_i64().ok_or("missing z")? as i32;
            let expected = sample["island_bits"].as_i64().ok_or("missing island bits")? as u32;
            let actual = noise.sample(x, z).to_bits();
            if actual != expected {
                return Err(format!("island mismatch seed={seed_value} x={x} z={z}: {} != {} ({actual:08x} != {expected:08x})",
                    f32::from_bits(actual), f32::from_bits(expected)).into());
            }
            if let Some(expected) = sample["outer_bits"].as_i64() {
                if noise.outer(x, z).to_bits() != expected as u32 {
                    return Err(format!("outer island mismatch seed={seed_value} x={x} z={z}").into());
                }
                outer_samples += 1;
            }
            if let Some(expected) = sample["center_bits"].as_i64() {
                if noise.center_noise(x / 16, z / 16).to_bits() != expected as u32 {
                    return Err(format!("simplex seed-center mismatch seed={seed_value} x={x} z={z}").into());
                }
                center_samples += 1;
            }
            let biome = noise.biome(x, z).id();
            if sample["biome"].as_str() != Some(biome) {
                return Err(format!("biome mismatch seed={seed_value} x={x} z={z}: {biome} != {}", sample["biome"]).into());
            }
            samples += 1;
        }
    }
    println!("{}", serde_json::json!({"schema":"retina.end.island-reference-comparison.v1", "samples":samples,
        "island_float_bits_equal":true,"biomes_equal":true,"outer_samples":outer_samples,
        "simplex_center_samples":center_samples,"source":path}));
    Ok(())
}
