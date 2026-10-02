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
