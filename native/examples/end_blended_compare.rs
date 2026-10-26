//! Independent source capture comparison, including scalar versus volume rounding.
use retina_worldgen::end::{BlendedNoise, BlendedParameters};
use serde_json::Value;
use std::{error::Error, fs};

fn main() -> Result<(), Box<dyn Error>> {
    let path = std::env::args().nth(1).ok_or("expected blended-reference.json path")?;
    let reference: Value = serde_json::from_slice(&fs::read(&path)?)?;
    let mut scalar_samples = 0;
    let mut volume_samples = 0;
    for case in reference["cases"].as_array().ok_or("missing cases")? {
        let seed = case["seed"].as_i64().ok_or("missing seed")?;
        let parameters: BlendedParameters = serde_json::from_value(case["parameters"].clone())?;
        let noise = BlendedNoise::new(seed as u64, parameters)?;
        for point in case["points"].as_array().ok_or("missing points")? {
            let x = point["xyz"][0].as_i64().ok_or("missing x")? as i32;
            let y = point["xyz"][1].as_i64().ok_or("missing y")? as i32;
            let z = point["xyz"][2].as_i64().ok_or("missing z")? as i32;
            for (field, actual) in [("scalar_bits", noise.sample(x, y, z)), ("volume_bits", noise.volume_point(x, y, z))] {
                if let Some(expected) = point[field].as_i64() {
                    if actual.to_bits() != expected as u32 {
                        eprintln!("NATIVE_MISMATCH {}", serde_json::to_string(&noise)?);
                        return Err(format!("{field} differs seed={seed} at {x},{y},{z} params={parameters:?}: {actual} ({:08x}) != {} ({:08x})",
                            actual.to_bits(), f32::from_bits(expected as u32), expected as u32).into());
                    }
                    if field == "scalar_bits" { scalar_samples += 1; } else { volume_samples += 1; }
                }
            }
        }
    }
    println!("{}", serde_json::json!({"schema":"retina.end.blended-comparison.v1", "source":path,
        "scalar_samples":scalar_samples,"volume_samples":volume_samples,"float_bits_exact":true}));
    Ok(())
}
