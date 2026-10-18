//! Partition contiguous anchor ranges, then concatenate each target's buckets in
//! that same order. No parallel reduction or hash iteration may reorder writes.
use super::*;

fn bucket(
    blocks: impl IntoIterator<Item = WorldBlock>,
    request: ChunkRequest,
    origin_x: i32,
    origin_z: i32,
    side: usize,
) -> Vec<Vec<Placement>> {
    let mut targets = vec![Vec::new(); side * side];
    for block in blocks {
        let cx = block.x.div_euclid(16) - origin_x;
        let cz = block.z.div_euclid(16) - origin_z;
        let y = block.y - request.min_y;
        if cx < 0
            || cz < 0
            || cx as usize >= side
            || cz as usize >= side
            || y < 0
            || y >= request.height as i32
        {
            continue;
        }
        targets[cz as usize * side + cx as usize].push(Placement {
            index: y as u32 * 256
                + block.z.rem_euclid(16) as u32 * 16
                + block.x.rem_euclid(16) as u32,
            material: block.material,
            upper: block.upper,
            role: block.role,
        });
    }
    targets
}
pub(super) fn serial(
    blocks: Vec<Vec<WorldBlock>>,
    request: ChunkRequest,
    origin_x: i32,
    origin_z: i32,
    side: usize,
) -> Vec<Vec<Placement>> {
    bucket(
        blocks.into_iter().flatten(),
        request,
        origin_x,
        origin_z,
        side,
    )
}
pub(super) fn distribute(
    blocks: Vec<Vec<WorldBlock>>,
    request: ChunkRequest,
    origin_x: i32,
    origin_z: i32,
    side: usize,
) -> Vec<Vec<Placement>> {
    // Small queries cannot amortize extra tasks/bucket metadata. A single worker
    // also gains nothing from this partition and should keep the direct path.
    if side <= 2
        || blocks.len() < 128
        || rayon::current_num_threads() == 1
        || blocks.iter().map(Vec::len).sum::<usize>() < 262_144
    {
        return serial(blocks, request, origin_x, origin_z, side);
    }
    parallel(blocks, request, origin_x, origin_z, side)
}
fn parallel(
    blocks: Vec<Vec<WorldBlock>>,
    request: ChunkRequest,
    origin_x: i32,
    origin_z: i32,
    side: usize,
) -> Vec<Vec<Placement>> {
    // Bound bucket metadata to two tasks per worker (and at least 64 anchors per
    // task). Tiny partitions cost more than the writes in sparse vegetation.
    let anchors_per_task = blocks
        .len()
        .div_ceil(rayon::current_num_threads().saturating_mul(2))
        .max(64);
    // Indexed collect preserves source order even if worker completion differs.
    let parts: Vec<_> = blocks
        .into_par_iter()
        .chunks(anchors_per_task)
        .map(|anchors| {
            bucket(
                anchors.into_iter().flatten(),
                request,
                origin_x,
                origin_z,
                side,
            )
        })
        .collect();
    let mut targets: Vec<Vec<Vec<Placement>>> = (0..side * side).map(|_| Vec::new()).collect();
    for part in parts {
        for (target, writes) in targets.iter_mut().zip(part) {
            if !writes.is_empty() {
                target.push(writes);
            }
        }
    }
    targets
        .into_par_iter()
        .map(|parts| {
            let mut parts = parts.into_iter();
            let mut result = parts.next().unwrap_or_default();
            result.reserve(parts.as_slice().iter().map(Vec::len).sum());
            for mut writes in parts {
                result.append(&mut writes);
            }
            result
        })
        .collect()
}

#[cfg(test)]
mod tests {
    use super::*;
    fn request() -> ChunkRequest {
        ChunkRequest {
            seed: 42,
            chunk_x: -33,
            chunk_z: -65,
            min_y: -64,
            height: 384,
            base_height: 64.,
            amplitude: 0.,
            frequency: 0.008,
            reserved: 0,
        }
    }
    fn writes(side: usize, per_anchor: usize) -> Vec<Vec<WorldBlock>> {
        let r = request();
        let halo = side + 2;
        (0..halo * halo)
            .map(|a| {
                let x = (r.chunk_x - 1 + (a % halo) as i32) * 16;
                let z = (r.chunk_z - 1 + (a / halo) as i32) * 16;
                (0..per_anchor)
                    .map(|i| WorldBlock {
                        // Cross chunk borders, duplicate cells and out-of-world writes.
                        x: x + ((i * 13) % 42) as i32 - 13,
                        z: z + ((i * 7) % 42) as i32 - 13,
                        y: -70 + (i % 401) as i32,
                        material: (a * 11 + i) as u16,
                        upper: (i % 13) as u16,
                        role: (i % 7) as u8,
                    })
                    .collect()
            })
            .collect()
    }
    #[test]
    fn parallel_distribution_preserves_every_ordered_write() {
        let pool = rayon::ThreadPoolBuilder::new()
            .num_threads(2)
            .build()
            .unwrap();
        let r = request();
        for side in [1, 2, 3, 8, 16, 32] {
            let mut source = writes(side, 333);
            source[0].clear();
            let expected = serial(source.clone(), r, r.chunk_x, r.chunk_z, side);
            let actual = pool.install(|| parallel(source.clone(), r, r.chunk_x, r.chunk_z, side));
            assert_eq!(actual, expected, "source order differs for side {side}");
            assert_eq!(
                pool.install(|| distribute(source, r, r.chunk_x, r.chunk_z, side)),
                expected
            );
        }
        assert!(
            parallel(Vec::new(), r, r.chunk_x, r.chunk_z, 8)
                .iter()
                .all(Vec::is_empty)
        );
    }
    #[test]
    #[ignore = "bounded CPU distribution A/B; two workers, no GPU or region generation"]
    fn distribution_replay_benchmark() {
        let pool = rayon::ThreadPoolBuilder::new()
            .num_threads(2)
            .build()
            .unwrap();
        let r = request();
        for per_anchor in [32, 192, 768] {
            let source = writes(32, per_anchor);
            let expected = serial(source.clone(), r, r.chunk_x, r.chunk_z, 32);
            let mut serial_ms = Vec::new();
            let mut parallel_ms = Vec::new();
            for round in 0..8 {
                for parallel_mode in [round % 2 == 0, round % 2 != 0] {
                    let input = source.clone();
                    let start = std::time::Instant::now();
                    let result = if parallel_mode {
                        pool.install(|| parallel(input, r, r.chunk_x, r.chunk_z, 32))
                    } else {
                        serial(input, r, r.chunk_x, r.chunk_z, 32)
                    };
                    let ms = start.elapsed().as_secs_f64() * 1000.;
                    assert_eq!(result, expected);
                    if round != 0 {
                        if parallel_mode {
                            parallel_ms.push(ms);
                        } else {
                            serial_ms.push(ms);
                        }
                    }
                }
            }
            println!(
                "{}",
                serde_json::json!({"anchors":source.len(),"source_writes":source.iter().map(Vec::len).sum::<usize>(),
            "accepted_writes":expected.iter().map(Vec::len).sum::<usize>(),"workers":2,"serial_ms":serial_ms,"parallel_ms":parallel_ms,"exact_order":true})
            );
        }
    }
}
