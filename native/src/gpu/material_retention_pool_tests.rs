//! Actual GPU ownership, allocation and stale-capacity checks for retained runs.
use super::*;

fn finish(
    gpu: &mut Gpu,
    input: GpuRequest,
    profile: &WorldProfile,
    times: &timings::Timings,
) -> GpuSample {
    let pending = gpu.submit(&[input], Some(profile), times).unwrap();
    gpu.complete(pending, times).unwrap()
}

#[test]
#[ignore = "requires RETINA_MATERIAL_PROFILE and a real GPU"]
fn retained_material_pool_reuses_only_completed_capacity() {
    let profile = fixture();
    let mut other = profile.clone();
    other.sea_level -= 17;
    let mut gpu = Gpu::new(std::sync::Arc::new(crate::pipeline::Metrics::default())).unwrap();
    let times = timings::Timings::default();
    let a = request(1, -33, 5, 128);
    let mut b = request(2, 4095, 8, 192);
    b.seed_low ^= 123;
    let mut widest = request(1, 17, 38, 1);
    widest.min_y = 40;
    widest.max_y = 41;
    gpu.material_retained_runs = 0;
    let expected = [
        finish(&mut gpu, a, &profile, &times),
        finish(&mut gpu, b, &other, &times),
        finish(&mut gpu, widest, &profile, &times),
    ];
    assert_eq!(gpu.retained_allocations, (0, 0));
    assert!(gpu.retained_pool.is_empty());
    gpu.material_retained_runs = 32;
    equal(&expected[0], &finish(&mut gpu, a, &profile, &times));
    assert_eq!(gpu.retained_allocations, (1, 48 * 48 * 128));
    equal(&expected[1], &finish(&mut gpu, b, &other, &times));
    assert_eq!(gpu.retained_allocations, (2, (48 * 48 + 96 * 96) * 128));
    assert_eq!(gpu.retained_pool.len(), 1);
    equal(&expected[0], &finish(&mut gpu, a, &profile, &times));
    assert_eq!(gpu.retained_allocations.0, 2);
    assert_eq!(gpu.retained_reuses, 1);
    assert_eq!(gpu.retained_pool[0].size(), 96 * 96 * 128);

    let first = gpu.submit(&[a], Some(&profile), &times).unwrap();
    let second = gpu.submit(&[b], Some(&other), &times).unwrap();
    assert!(gpu.retained_pool.is_empty());
    let first = continuation(&mut gpu, first, &times);
    let second = continuation(&mut gpu, second, &times);
    assert!(
        gpu.retained_pool.is_empty(),
        "count completion cannot recycle emission inputs"
    );
    equal(&expected[1], &gpu.complete(second, &times).unwrap());
    equal(&expected[0], &gpu.complete(first, &times).unwrap());
    assert_eq!(gpu.retained_pool.len(), 2);
    let allocations = gpu.retained_allocations;
    for (input, index) in [(b, 1), (a, 0), (b, 1), (a, 0)] {
        equal(
            &expected[index],
            &finish(
                &mut gpu,
                input,
                if index == 0 { &profile } else { &other },
                &times,
            ),
        );
    }
    assert_eq!(gpu.retained_allocations, allocations);

    // A zero budget must neither acquire pooled capacity nor change its size.
    gpu.material_retained_runs = 0;
    let reuses = gpu.retained_reuses;
    equal(&expected[0], &finish(&mut gpu, a, &profile, &times));
    assert_eq!(gpu.retained_reuses, reuses);
    assert_eq!(gpu.retained_pool.len(), 2);
    gpu.material_retained_runs = 32;

    // Cancellation in either phase and a failed mapped result drop capacity.
    gpu.retained_pool.clear();
    drop(gpu.submit(&[a], Some(&profile), &times).unwrap());
    assert!(gpu.retained_pool.is_empty());
    let pending = gpu.submit(&[a], Some(&profile), &times).unwrap();
    drop(continuation(&mut gpu, pending, &times));
    assert!(gpu.retained_pool.is_empty());
    let pending = gpu.submit(&[a], Some(&profile), &times).unwrap();
    let mut pending = continuation(&mut gpu, pending, &times);
    if let PendingStage::Materials(materials) = &mut pending.stage {
        materials.mask = None;
    }
    assert_eq!(
        gpu.complete(pending, &times).err().as_deref(),
        Some("missing material mask")
    );
    assert!(gpu.retained_pool.is_empty());
    equal(&expected[0], &finish(&mut gpu, a, &profile, &times));
    assert_eq!(gpu.retained_pool.len(), 1);

    // Direct test callers can exceed production's two-flight limit. Finishing
    // eight in reverse must still retain only two buffers, even at maximum size.
    let pending: Vec<_> = (0..8)
        .map(|_| gpu.submit(&[widest], Some(&profile), &times).unwrap())
        .collect();
    for pending in pending.into_iter().rev() {
        equal(&expected[2], &gpu.complete(pending, &times).unwrap());
        assert!(gpu.retained_pool.len() <= 2);
    }
    assert_eq!(gpu.retained_pool.len(), 2);
    assert_eq!(
        gpu.retained_pool
            .iter()
            .map(wgpu::Buffer::size)
            .sum::<u64>(),
        84_934_656
    );
    let allocations = gpu.retained_allocations;
    // Reinterpret the same capacity with a different column stride, seed,
    // profile, height and overflow budget; stale tails cannot be consumed.
    for (budget, input, index) in [(1, b, 1), (32, a, 0), (1, widest, 2), (32, b, 1)] {
        gpu.material_retained_runs = budget;
        equal(
            &expected[index],
            &finish(
                &mut gpu,
                input,
                if index == 1 { &other } else { &profile },
                &times,
            ),
        );
    }
    assert_eq!(gpu.retained_allocations, allocations);
    println!(
        "C79_POOL {}",
        serde_json::json!({
            "allocations":gpu.retained_allocations.0,
            "allocated_bytes":gpu.retained_allocations.1,
            "reuses":gpu.retained_reuses,
            "idle_buffers":gpu.retained_pool.len(),
            "idle_capacity_bytes":gpu.retained_pool.iter().map(wgpu::Buffer::size).sum::<u64>(),
            "exact":true,
        })
    );
}

#[test]
#[ignore = "bounded fresh/reused storage A/B; requires RETINA_MATERIAL_PROFILE and a real GPU"]
fn retained_material_pool_replay() {
    let profile = fixture();
    let make_gpu = || Gpu::new(std::sync::Arc::new(crate::pipeline::Metrics::default())).unwrap();
    let mut fresh = make_gpu();
    let mut reused = make_gpu();
    let times = timings::Timings::default();
    let inputs = [request(1, -33, 22, 256), request(1, 4095, 22, 256)];
    let run = |gpu: &mut Gpu, keep: bool, index: usize| {
        let allocations = gpu.retained_allocations;
        let reuses = gpu.retained_reuses;
        let start = Instant::now();
        let sample = finish(gpu, inputs[index], &profile, &times);
        if !keep {
            gpu.retained_pool.clear();
        }
        (
            serde_json::json!({
                "pooled":keep,
                "wall_ms":start.elapsed().as_secs_f64()*1000.,
                "material_device_ms":sample.timings.nanos[timings::MATERIALS] as f64/1e6,
                "allocations":gpu.retained_allocations.0-allocations.0,
                "allocated_bytes":gpu.retained_allocations.1-allocations.1,
                "reuses":gpu.retained_reuses-reuses,
            }),
            sample,
        )
    };
    let expected = [run(&mut fresh, false, 0).1, run(&mut fresh, false, 1).1];
    for i in 0..5 {
        equal(&expected[i % 2], &run(&mut fresh, false, i % 2).1);
        equal(&expected[i % 2], &run(&mut reused, true, i % 2).1);
    }
    let mut measurements = Vec::new();
    for i in 0..20 {
        for keep in if i % 2 == 0 {
            [false, true]
        } else {
            [true, false]
        } {
            let (mut measure, sample) =
                run(if keep { &mut reused } else { &mut fresh }, keep, i % 2);
            equal(&expected[i % 2], &sample);
            assert_eq!(measure["allocations"], u64::from(!keep));
            assert_eq!(measure["reuses"], u64::from(keep));
            measure["pair"] = i.into();
            measurements.push(measure);
        }
    }
    println!(
        "C79_REPLAY {}",
        serde_json::json!({
            "measurements":measurements,"exact":true,
            "scope":"Five warmups and twenty pairs on two GPU instances, one generating at a time; raw 22x22 tiles, height 256, interpreter. Fresh mode drops capacity within its timed interval. This isolates allocation/reuse, not complete-region performance or net device traffic. Timing confirmation deferred."
        })
    );
}
