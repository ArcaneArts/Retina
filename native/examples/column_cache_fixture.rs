//! Retain actual GPU columns for CPU-only cache comparisons. No MCA or decorations.
use retina_worldgen::{ChunkRequest, TerrainEngine};
fn main() -> Result<(), Box<dyn std::error::Error>> {
    let mut args = std::env::args().skip(1);
    let source = std::fs::read(args.next().ok_or("profile required")?)?;
    let output = args.next().ok_or("output required")?;
    let engine = TerrainEngine::new()?;
    let reserved = engine.register_profile(&source)?;
    let requests: Vec<_> = (0..1024)
        .map(|i| ChunkRequest {
            seed: 123456789,
            chunk_x: -32 + i % 32,
            chunk_z: -32 + i / 32,
            min_y: -64,
            height: 384,
            base_height: 64.,
            amplitude: 48.,
            frequency: 0.008,
            reserved,
        })
        .collect();
    let columns = engine.sample_columns(&requests)?;
    let mut bytes = Vec::with_capacity(columns.len() * 12);
    for c in &columns {
        bytes.extend(c.height.to_be_bytes());
        bytes.extend(c.packed.to_be_bytes());
        bytes.extend(c.materials.to_be_bytes());
    }
    std::fs::write(output, &bytes)?;
    println!(
        "{}",
        serde_json::json!({"backend":engine.backend(),"columns":columns.len(),"bytes":bytes.len(),"seed":123456789,"region":[-1,-1]})
    );
    Ok(())
}
