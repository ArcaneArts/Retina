//! Opt-in encoding inventory on actual generated inputs. No production instrumentation.
use super::*;
use crate::world_definition::{WorldDefinition, runtime::WorldSession};
use std::{hint::black_box, sync::Arc};

#[test]
#[ignore = "requires GPU, RETINA_TRANSFER_DEFINITION and RETINA_TRANSFER_OUTPUT"]
fn native_chunk_encoding_inventory() {
    let definition = Arc::new(
        WorldDefinition::read(
            std::env::var_os("RETINA_TRANSFER_DEFINITION").expect("definition path"),
        )
        .unwrap(),
    );
    let output =
        std::path::PathBuf::from(std::env::var_os("RETINA_TRANSFER_OUTPUT").expect("output path"));
    fs::create_dir(&output).unwrap();
    let engine = Arc::new(TerrainEngine::new().unwrap());
    let session =
        WorldSession::bind(engine.clone(), definition.clone(), codec::Compression::Zlib).unwrap();
    let mut rows = Vec::new();
    for key in [
        "minecraft:overworld",
        "minecraft:the_nether",
        "minecraft:the_end",
    ] {
        let lease = session.dimension(key).unwrap();
        for (x, z) in [(0, 0), (-1, -1), (12, -13)] {
            let request = lease.request(x, z).unwrap();
            engine.validate_request(&request).unwrap();
            let profile = engine.profile(request.reserved).unwrap();
            let mut blocks = vec![0; request.block_count()];
            let columns = engine.generate_columns_into(request, &mut blocks).unwrap();
            let (_, mask) = engine.terrain_field(request, 1).unwrap();
            let data = engine
                .structures
                .lock()
                .unwrap()
                .metadata(request)
                .unwrap_or_else(crate::structures::empty_data);
            let storage = geometry::storage_request(
                request,
                engine.profile_storage_bounds(request.reserved).unwrap(),
            );
            geometry::validate_storage(storage).unwrap();
            geometry::fit_blocks(request, storage, profile.as_deref(), &mut blocks).unwrap();
            let saved_profile = definition.dimension(key).unwrap().profile().unwrap();
            let biome = &saved_profile.biomes[0].id;
            let encode = || {
                chunk_nbt_blocks(
                    storage,
                    &columns,
                    &blocks,
                    definition.data_version(),
                    biome,
                    profile.as_deref(),
                    mask.as_deref(),
                    Some(&data),
                )
            };
            let expected = lease.generate_chunk(x, z).unwrap();
            assert_eq!(
                encode(),
                expected,
                "isolated encoder must match production chunk path"
            );
            for _ in 0..20 {
                black_box(encode());
            }
            let mut samples = Vec::new();
            for _ in 0..100 {
                let start = Instant::now();
                let bytes = encode();
                samples.push(start.elapsed().as_nanos() as u64);
                assert_eq!(bytes, expected); // Comparison is outside the timer.
                black_box(bytes);
            }
            let name = format!("{}_{}_{}", &key[10..], x, z);
            fs::write(output.join(format!("{name}.nbt")), &expected).unwrap();
            rows.push(serde_json::json!({"dimension":key,"x":x,"z":z,"nbt_bytes":expected.len(),"encoding_ns":samples}));
        }
    }
    fs::write(
        output.join("inventory.json"),
        serde_json::to_vec_pretty(&rows).unwrap(),
    )
    .unwrap();
}
