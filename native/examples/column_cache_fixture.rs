//! Retain actual GPU columns for CPU-only cache comparisons. No MCA or decorations.
use retina_worldgen::{
    TerrainEngine,
    region::codec::Compression,
    world_definition::{WorldDefinition, runtime::WorldSession},
};
use std::sync::Arc;
fn main() -> Result<(), Box<dyn std::error::Error>> {
    let mut args = std::env::args().skip(1);
    let source = Arc::new(WorldDefinition::read(
        args.next().ok_or("definition required")?,
    )?);
    let key = args.next().ok_or("dimension required")?;
    let output = args.next().ok_or("output required")?;
    if args.next().is_some() {
        return Err("unexpected argument; seed comes from retina.dat".into());
    }
    let engine = Arc::new(TerrainEngine::new()?);
    let world = WorldSession::bind(engine.clone(), source.clone(), Compression::Zlib)?;
    let dimension = world.dimension(&key)?;
    let requests: Vec<_> = (0..1024)
        .map(|i| dimension.request(-32 + i % 32, -32 + i / 32))
        .collect::<Result<Vec<_>, _>>()?;
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
        serde_json::json!({"backend":engine.backend(),"dimension":key,"columns":columns.len(),"bytes":bytes.len(),"seed":source.seed(),"region":[-1,-1]})
    );
    Ok(())
}
