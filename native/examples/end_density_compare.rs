//! Full vanilla End graph scalar samples and bulk base-column decisions.
use retina_worldgen::end::VanillaDensity;
use serde_json::Value;
use std::{collections::HashMap, error::Error, fs};

fn main() -> Result<(), Box<dyn Error>> {
    let path = std::env::args().nth(1).ok_or("expected reference.json")?;
    let reference: Value = serde_json::from_slice(&fs::read(&path)?)?;
    let mut points = 0;
    let mut columns = 0;
    let mut column_float_bits = 0;
    for group in reference["seeds"].as_array().ok_or("missing seeds")? {
        let seed = group["seed"].as_i64().ok_or("missing seed")?;
        let noise = VanillaDensity::new(seed as u64);
        let mut tiles = HashMap::new();
        for point in group["samples"].as_array().ok_or("missing samples")? {
            let x = point["xz"][0].as_i64().unwrap() as i32;
            let z = point["xz"][1].as_i64().unwrap() as i32;
            for value in point["density_y_bits"].as_array().ok_or("missing densities")? {
                let y = value[0].as_i64().unwrap() as i32;
                let expected = value[1].as_i64().unwrap() as u32;
                let actual = noise.sample(x, y, z);
                if actual.to_bits() != expected {
                    return Err(format!("density seed={seed} at {x},{y},{z}: {actual} ({:08x}) != {} ({expected:08x})",
                        actual.to_bits(), f32::from_bits(expected)).into());
                }
                points += 1;
            }
        }
        for point in group["columns"].as_array().ok_or("missing columns")? {
            let x = point["xz"][0].as_i64().unwrap() as i32;
            let z = point["xz"][1].as_i64().unwrap() as i32;
            let expected = point["solid_y"].as_array().unwrap();
            let column = noise.column(x, z);
            let chunk = (x.div_euclid(16), z.div_euclid(16));
            if !tiles.contains_key(&chunk) {
                tiles.insert(chunk, noise.tile(chunk.0 * 16, chunk.1 * 16, 16, 16)?);
            }
            let tile = &tiles[&chunk];
            let tiled = tile.column_densities(x, z).unwrap();
            if tile.column(x, z).unwrap() != column {
                return Err(format!("tiled column differs at seed={seed}, {x},{z}").into());
            }
            if let Some(expected) = point["density_bits"].as_array() {
                let actual = noise.column_densities(x, z);
                for (y, value) in expected.iter().enumerate() {
                    if actual[y].to_bits() != value.as_i64().unwrap() as u32 {
                        return Err(format!("bulk density seed={seed} at {x},{y},{z}: {} ({:08x}) != {value}",
                            actual[y], actual[y].to_bits()).into());
                    }
                    if tiled[y].to_bits() != value.as_i64().unwrap() as u32 {
                        return Err(format!("tiled bulk density seed={seed} at {x},{y},{z}: {:08x} != {value}", tiled[y].to_bits()).into());
                    }
                    column_float_bits += 1;
                }
            }
            for (y, &solid) in column.iter().enumerate() {
                if solid != expected.iter().any(|v| v.as_u64() == Some(y as u64)) {
                    return Err(format!("column seed={seed} at {x},{y},{z}: solid={solid}").into());
                }
            }
            let height = column.iter().rposition(|v| *v).map_or(0, |y| y + 1) as u64;
            for (kind, value) in point["heights"].as_object().unwrap() {
                if value.as_u64() != Some(height) {
                    return Err(format!("height {kind} seed={seed} at {x},{z}: {height} != {value}").into());
                }
            }
            columns += 1;
        }
    }
    println!("{}", serde_json::json!({"schema":"retina.end.density-comparison.v1","scalar_float_bits_exact":points,
        "base_columns_exact":columns,"column_voxels":columns*256,"column_float_bits_exact":column_float_bits,
        "tiled_column_float_bits_exact":column_float_bits,"tiled_column_voxels_exact":columns*256,
        "all_height_queries_exact":true,"source":path}));
    Ok(())
}
