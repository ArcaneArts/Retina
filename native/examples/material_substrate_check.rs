//! Check actual exported profiles' GPU placement substrate across region/chunk
//! boundaries, independent requests, cached fields and concurrent submissions.
//! material_substrate_check <profile.json> [specialized|interpreter] [side=32]
use retina_worldgen::{COLUMNS, ChunkRequest, TerrainEngine};
use std::sync::Arc;

fn main() -> Result<(), Box<dyn std::error::Error>> {
    let mut args = std::env::args().skip(1);
    let source = std::fs::read(args.next().ok_or("profile path required")?)?;
    let mode = args.next().unwrap_or_else(|| "specialized".into());
    let side: u32 = args.next().map(|v| v.parse()).transpose()?.unwrap_or(32);
    let mut value: serde_json::Value = serde_json::from_slice(&source)?;
    value["program_execution"] = mode.clone().into();
    value["registry_program"]["material_halo"] = true.into();
    let source = serde_json::to_vec(&value)?;
    let engine = TerrainEngine::new()?;
    let profile_id = engine.register_profile(&source)?;
    let profile = engine.profile(profile_id)?.ok_or("missing profile")?;
    value["registry_program"]["material_halo"] = false.into();
    let legacy_id = engine.register_profile(&serde_json::to_vec(&value)?)?;
    let request = ChunkRequest {
        seed: 123456789,
        chunk_x: -32,
        chunk_z: -32,
        min_y: -64,
        height: 384,
        base_height: 64.,
        amplitude: 48.,
        frequency: 0.008,
        reserved: profile_id,
    };
    let (field, mask) = engine.terrain_field(request, side)?;
    let mask = mask.ok_or("missing substrate")?;
    assert!(field.substrate.is_some());
    assert_eq!(field.side, side as usize + 2, "no additional anchor ring");
    let (legacy_field, legacy_mask) = engine.terrain_field(
        ChunkRequest {
            reserved: legacy_id,
            ..request
        },
        side,
    )?;
    let legacy_mask = legacy_mask.ok_or("missing legacy base mask")?;
    assert!(
        legacy_field.substrate.is_none(),
        "old profile behavior retained"
    );
    assert_eq!(field.columns.len(), legacy_field.columns.len());
    for (a, b) in field.columns.iter().zip(&legacy_field.columns) {
        assert_eq!(
            (a.height, a.packed, a.materials),
            (b.height, b.packed, b.materials)
        );
    }
    let ends = [-1, 0, side as i32 - 1, side as i32];
    let origins: Vec<_> = ends
        .into_iter()
        .flat_map(|z| ends.map(|x| (request.chunk_x + x, request.chunk_z + z)))
        .collect();
    // A separate engine prevents these calls from borrowing the region mask.
    let chunks = Arc::new(TerrainEngine::new()?);
    let chunk_id = chunks.register_profile(&source)?;
    let mut comparisons = 0u64;
    let mut heightmaps = 0u64;
    let mut voids = 0u64;
    let mut fluids = 0u64;
    for group in origins.chunks(4) {
        let jobs: Vec<_> = group
            .iter()
            .map(|&(x, z)| {
                let chunks = chunks.clone();
                std::thread::spawn(move || {
                    chunks.terrain_field(
                        ChunkRequest {
                            chunk_x: x,
                            chunk_z: z,
                            reserved: chunk_id,
                            ..request
                        },
                        1,
                    )
                })
            })
            .collect();
        for (&(cx, cz), job) in group.iter().zip(jobs) {
            let (_, chunk_mask) = job.join().map_err(|_| "substrate thread panicked")??;
            let chunk_mask = chunk_mask.ok_or("missing independent chunk mask")?;
            for i in 0..COLUMNS {
                let x = cx * 16 + (i % 16) as i32;
                let z = cz * 16 + (i / 16) as i32;
                let mut heights = [request.min_y; 6];
                for y in request.min_y..request.min_y + request.height as i32 {
                    let actual = mask.material_at(x, y, z).ok_or("region halo incomplete")?;
                    assert_eq!(
                        Some(actual),
                        chunk_mask.material_at(x, y, z),
                        "independent base at {x}/{y}/{z} in {mode}"
                    );
                    if cx >= request.chunk_x
                        && cz >= request.chunk_z
                        && cx < request.chunk_x + side as i32
                        && cz < request.chunk_z + side as i32
                    {
                        assert_eq!(
                            Some(actual),
                            legacy_mask.material_at(x, y, z),
                            "unchanged core base at {x}/{y}/{z}"
                        );
                    }
                    for (map, height) in heights.iter_mut().enumerate() {
                        if profile.heightmap_masks[actual as usize] & (1 << map) != 0 {
                            *height = y + 1;
                        }
                    }
                    if y < field.column(x, z).unwrap().height {
                        voids += u64::from(actual == 0);
                        fluids +=
                            u64::from(actual == profile.water || actual == profile.geology.lava);
                    }
                    comparisons += 1;
                }
                for (map, expected) in heights.into_iter().enumerate() {
                    assert_eq!(
                        mask.height_at(x, z, &profile.heightmap_masks, map as u8),
                        Some(expected)
                    );
                    heightmaps += 1;
                }
            }
        }
    }
    // A cached core chunk must expose its entire own placement halo, too.
    let before = engine.timings(profile_id).snapshot().gpu_jobs;
    let (cached, _) = engine.terrain_field(request, 1)?;
    assert_eq!(engine.timings(profile_id).snapshot().gpu_jobs, before);
    let cached = cached.substrate.ok_or("cached field lost substrate")?;
    assert!(
        cached
            .material_at(request.chunk_x * 16 - 16, 0, request.chunk_z * 16 - 16)
            .is_some()
    );
    assert_eq!(
        mask.material_at(request.chunk_x * 16 - 17, 0, request.chunk_z * 16),
        None
    );
    assert_eq!(
        mask.material_at(request.chunk_x * 16, -65, request.chunk_z * 16),
        Some(0)
    );
    assert_eq!(
        mask.material_at(request.chunk_x * 16, 320, request.chunk_z * 16),
        Some(0)
    );
    let mut cached_blocks = vec![0; request.block_count()];
    let mut independent_blocks = vec![0; request.block_count()];
    for &(cx, cz) in &origins {
        let r = ChunkRequest {
            chunk_x: cx,
            chunk_z: cz,
            ..request
        };
        let a = engine.generate_into(r, &mut cached_blocks)?;
        let b = chunks.generate_into(
            ChunkRequest {
                reserved: chunk_id,
                ..r
            },
            &mut independent_blocks,
        )?;
        assert_eq!(a, b, "final heights at {cx}/{cz}");
        if let Some(i) = cached_blocks
            .iter()
            .zip(&independent_blocks)
            .position(|(a, b)| a != b)
        {
            panic!(
                "full cold/cached output at {cx}/{cz}, index {i}: {} vs {}",
                cached_blocks[i], independent_blocks[i]
            );
        }
    }
    println!(
        "{}",
        serde_json::json!({"status":"pass","mode":mode,"side":side,"backend":engine.backend(),"columns":origins.len()*COLUMNS,"voxel_comparisons":comparisons,"final_block_comparisons":origins.len()*request.block_count(),"heightmap_checks":heightmaps,"below_surface_air":voids,"below_surface_fluid":fluids,"region_mask_words":mask.words.len(),"legacy_mask_words":legacy_mask.words.len(),"cached_gpu_jobs":0})
    );
    Ok(())
}
