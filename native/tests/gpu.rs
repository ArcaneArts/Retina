use std::sync::Arc;
use std::time::Instant;

use retina_worldgen::{AIR, COLUMNS, ChunkRequest, STONE, TerrainEngine};

fn request(x: i32, z: i32) -> ChunkRequest {
    ChunkRequest {
        seed: 0x1234_5678_9abc_def0,
        chunk_x: x,
        chunk_z: z,
        min_y: -64,
        height: 384,
        base_height: 64.0,
        amplitude: 48.0,
        frequency: 0.008,
        reserved: 0,
    }
}

#[test]
fn actual_gpu_parallel_chunks_and_height_queries() {
    let engine = Arc::new(TerrainEngine::new().expect("create a real compute device"));
    println!(
        "QA_EVT {{\"event\":\"gpu_backend\",\"status\":\"pass\",\"details\":\"{}\"}}",
        engine.backend()
    );
    let origin = engine.sample_heights(request(0, 0)).unwrap();
    assert!(
        origin.iter().any(|h| *h != origin[0]),
        "simplex must vary across columns"
    );
    let mut other_seed = request(0, 0);
    other_seed.seed ^= 1 << 40;
    assert_ne!(
        origin,
        engine.sample_heights(other_seed).unwrap(),
        "all 64 seed bits participate"
    );
    println!("QA_EVT {{\"event\":\"simplex_variation\",\"status\":\"pass\"}}");

    let start = Instant::now();
    let threads: Vec<_> = (0..16)
        .map(|worker| {
            let engine = Arc::clone(&engine);
            std::thread::spawn(move || {
                let mut total_ms = 0.0;
                for i in 0..16 {
                    let request = request(worker - 8, i - 8);
                    let begin = Instant::now();
                    let mut blocks = vec![255; request.block_count()];
                    let heights = engine.generate_into(request, &mut blocks).unwrap();
                    total_ms += begin.elapsed().as_secs_f64() * 1000.0;
                    assert_eq!(
                        heights,
                        engine.sample_heights(request).unwrap(),
                        "height query matches generated chunk"
                    );
                    for (layer, row) in blocks.chunks_exact(COLUMNS).enumerate() {
                        let y = request.min_y + layer as i32;
                        for (column, block) in row.iter().enumerate() {
                            assert_eq!(*block, if y < heights[column] { STONE } else { AIR });
                        }
                    }
                }
                total_ms
            })
        })
        .collect();
    let latency_ms: f64 = threads
        .into_iter()
        .map(|thread| thread.join().unwrap())
        .sum();
    let seconds = start.elapsed().as_secs_f64();
    println!(
        "QA_EVT {{\"event\":\"parallel_gpu_chunks\",\"status\":\"pass\",\"context\":{{\"workers\":16,\"chunks\":256,\"chunks_per_second\":{:.2},\"mean_request_ms\":{:.3},\"wall_seconds\":{:.3}}}}}",
        256.0 / seconds,
        latency_ms / 256.0,
        seconds
    );

    // Verify the global-coordinate convention across a negative chunk boundary.
    let left = engine.sample_heights(request(-1, 0)).unwrap();
    let right = engine.sample_heights(request(0, 0)).unwrap();
    for z in 0..16 {
        assert!(
            (left[z * 16 + 15] - right[z * 16]).abs() <= 12,
            "adjacent columns should not reset their simplex coordinates"
        );
    }
    let short = ChunkRequest {
        min_y: -16,
        height: 16,
        base_height: -8.0,
        amplitude: 64.0,
        ..request(-1, -1)
    };
    let heights = engine.sample_heights(short).unwrap();
    assert!(
        heights.iter().all(|height| (-15..=0).contains(height)),
        "GPU respects vertical bounds"
    );
    println!("QA_EVT {{\"event\":\"negative_coordinates_and_bounds\",\"status\":\"pass\"}}");
}

#[test]
fn sparse_biomes_match_full_fields_and_reuse_cached_quarts() {
    use serde_json::json;
    let engine = TerrainEngine::new().unwrap();
    let constant = |v: f32| json!({"nodes":[{"op":0,"a":0,"b":0,"c":0,"p":[v,0,0,0]}],"roots":[0]});
    let noise = json!({"frequency":0.02,"amplitude":0.5,"modifiers":[1,0.5]});
    let climate = json!({"nodes":[{"op":2,"a":0,"b":0,"c":0,"p":[0,0,0,0]}],"roots":[0,0,0,0,0,0]});
    let density = json!({"nodes":[{"op":3,"a":1,"b":0,"c":0,"p":[-64,128,1,-1]}],"roots":[0]});
    let mut last_profile = 0;
    for mode in 0..3 {
        let mut source = json!({
            "biome_scale":256,"blend":0.55,"sea_level":32,"stone":1,"water":2,
            "bedrock":1,"deepslate":1,"snow":1,"ice":2,
            "materials":["minecraft:air","minecraft:stone","minecraft:water"],
            "biomes":[
                {"id":"test:surface","climate":[-0.5,-0.5,-0.5,-0.5],"terrain":[0,1,1],"top":1,"filler":1,"underwater":1,"flags":0},
                {"id":"test:caves","climate":[0.5,0.5,0.5,0.5],"terrain":[10,1,1],"top":1,"filler":1,"underwater":1,"flags":16,"cave_kind":1,"cave_depth":[0.2,0.9]}
            ],"noises":[noise,noise,noise,noise],"cave_noises":[noise,noise,noise,noise,noise,noise],
            "geology_min_y":-64,"geology_height":192,"carveable":[false,true,false],"lava":2,"lava_level":-54,
            "material_flags":[0,1,0],"heightmap_masks":[0,63,0]
        });
        if mode > 0 {
            source["registry_program"] = json!({"programs":[climate,if mode==1 {constant(70.)} else {density.clone()},constant(1.),constant(0.),constant(0.)],
                "noises":[{"frequency":0.02,"amplitude":0.5,"salt":42,"coefficients":[1,0.5]}],
                "points":[],"surface":[-64,192,if mode==1 {1} else {0}]});
            source["climate_targets"] = json!([
                {"biome":0,"min":[-1,-1,-1,-1],"max":[0,0,0,0],"weirdness":[-1,1],"depth":[-1,1],"offset":0},
                {"biome":1,"min":[0,0,0,0],"max":[1,1,1,1],"weirdness":[-1,1],"depth":[-1,1],"offset":0}
            ]);
            source["weirdness_noise"] = noise.clone();
        }
        let id = engine
            .register_profile(&serde_json::to_vec(&source).unwrap())
            .unwrap();
        last_profile = id;
        let requests: Vec<_> = [(-31, 17), (17, -31), (10_000, -10_000), (-10_000, 10_000)]
            .into_iter()
            .map(|(x, z)| ChunkRequest {
                reserved: id,
                min_y: -63,
                height: 191,
                ..request(x, z)
            })
            .collect();
        let ys = [-61, -27, 8, 66];
        let before = engine.timings(id).snapshot();
        let surface = engine.sample_biomes(&requests, None).unwrap();
        let after = engine.timings(id).snapshot();
        assert_eq!(
            after.gpu_columns - before.gpu_columns,
            4,
            "surface probes return one column each"
        );
        let underground = engine.sample_biomes(&requests, Some(&ys)).unwrap();
        for (i, r) in requests.iter().enumerate() {
            let ground = engine.sample_height_tile(*r, 1, false).unwrap();
            let water = engine.sample_height_tile(*r, 1, true).unwrap();
            let (field, mask) = engine.terrain_field(*r, 1).unwrap();
            let col = field
                .column(r.chunk_x * 16 + 8, r.chunk_z * 16 + 8)
                .unwrap();
            assert_eq!(surface[i], col.biome() as u16, "surface mode {mode}");
            let profile = engine.profile(id).unwrap().unwrap();
            for (j, c) in field.chunk(r.chunk_x, r.chunk_z).iter().enumerate() {
                assert_eq!(ground[j], c.height, "height mode {mode}");
                assert_eq!(
                    water[j],
                    c.surface_height(Some(&profile)),
                    "fluid height mode {mode}"
                );
            }
            assert_eq!(
                underground[i],
                mask.as_ref()
                    .and_then(|m| m.biome(r.chunk_x * 16 + 8, ys[i], r.chunk_z * 16 + 8))
                    .unwrap_or(col.biome() as u16),
                "underground mode {mode}"
            );
            let halo = ChunkRequest {
                chunk_x: r.chunk_x - 1,
                ..*r
            };
            let before = engine.timings(id).snapshot();
            let biome = engine.sample_biomes(&[halo], Some(&[ys[i]])).unwrap()[0];
            if let Some(mask) = mask {
                assert_eq!(
                    biome,
                    mask.biome(halo.chunk_x * 16 + 8, ys[i], halo.chunk_z * 16 + 8)
                        .unwrap()
                );
                assert_eq!(
                    before.gpu_jobs,
                    engine.timings(id).snapshot().gpu_jobs,
                    "halo quart queries reuse masks"
                );
            }
        }
        let before = engine.timings(id).snapshot();
        assert_eq!(surface, engine.sample_biomes(&requests, None).unwrap());
        assert_eq!(
            underground,
            engine.sample_biomes(&requests, Some(&ys)).unwrap()
        );
        assert_eq!(
            before.gpu_jobs,
            engine.timings(id).snapshot().gpu_jobs,
            "cached probes must not dispatch"
        );
    }
    // Different mask sizes force scratch growth and slot reuse while submissions
    // overlap. Compare every returned byte with an independent one-slot engine.
    let serial = TerrainEngine::with_pipeline_depth(1).unwrap();
    let serial_profile = serial
        .register_profile(&engine.profile(last_profile).unwrap().unwrap().encoded)
        .unwrap();
    let cases = [(0, 0, 32), (32, 0, 16), (-32, 0, 8), (0, -32, 1)];
    let barrier = std::sync::Barrier::new(cases.len());
    let parallel = std::thread::scope(|scope| {
        let handles: Vec<_> = cases
            .iter()
            .map(|&(x, z, side)| {
                let engine = &engine;
                let barrier = &barrier;
                scope.spawn(move || {
                    barrier.wait();
                    let r = ChunkRequest {
                        reserved: last_profile,
                        min_y: -63,
                        height: 191,
                        ..request(x, z)
                    };
                    engine.terrain_field(r, side).unwrap()
                })
            })
            .collect();
        handles
            .into_iter()
            .map(|h| h.join().unwrap())
            .collect::<Vec<_>>()
    });
    for ((x, z, side), (actual, mask)) in cases.into_iter().zip(parallel) {
        let r = ChunkRequest {
            reserved: serial_profile,
            min_y: -63,
            height: 191,
            ..request(x, z)
        };
        let (expected, reference) = serial.terrain_field(r, side).unwrap();
        assert_eq!(
            bytemuck::cast_slice::<_, u8>(&actual.columns),
            bytemuck::cast_slice::<_, u8>(&expected.columns)
        );
        assert_eq!(mask.unwrap().words, reference.unwrap().words);
    }
    assert_eq!(engine.pipeline_snapshot().peak_in_flight, 2);
    assert_eq!(serial.pipeline_snapshot().peak_in_flight, 1);
    println!(
        "QA_EVT {{\"event\":\"gpu_readback_ring_parity\",\"status\":\"pass\",\"context\":{}}}",
        serde_json::to_string(&engine.pipeline_snapshot()).unwrap()
    );
}
