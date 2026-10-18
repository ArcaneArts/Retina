//! Small real-GPU fixtures: stage scheduling, snapshot isolation and buffer reuse.
use super::*;

fn fixture() -> WorldProfile {
    let bytes = std::fs::read(std::env::var("RETINA_MATERIAL_PROFILE").unwrap()).unwrap();
    let mut value: serde_json::Value = serde_json::from_slice(&bytes).unwrap();
    value["program_execution"] = "interpreter".into();
    let profile = WorldProfile::parse(&serde_json::to_vec(&value).unwrap()).unwrap();
    assert!(profile.registry_program.as_ref().unwrap().material_layers);
    profile
}
fn request(profile: u32, x: i32, side: u32, height: u32) -> GpuRequest {
    let mut r = GpuRequest::from(crate::ChunkRequest {
        seed: 123456789,
        chunk_x: x,
        chunk_z: -33,
        min_y: -64,
        height,
        base_height: 48.,
        amplitude: 24.,
        frequency: 0.008,
        reserved: profile,
    });
    r.tile_side = side;
    r.padding = side - 2;
    r
}
fn equal(a: &GpuSample, b: &GpuSample) {
    assert_eq!(
        bytemuck::cast_slice::<Column, u8>(&a.columns),
        bytemuck::cast_slice::<Column, u8>(&b.columns)
    );
    assert_eq!(
        a.mask.as_ref().map(|m| &m.words),
        b.mask.as_ref().map(|m| &m.words)
    );
    assert_eq!(a.timings.gpu_jobs, b.timings.gpu_jobs);
    assert_eq!(a.timings.gpu_columns, b.timings.gpu_columns);
}
fn continuation(gpu: &mut Gpu, pending: PendingSample, times: &timings::Timings) -> PendingSample {
    match gpu.advance(pending, times).unwrap() {
        Progress::Pending(p) => {
            assert!(matches!(p.stage, PendingStage::Materials(_)));
            p
        }
        Progress::Complete(_) => panic!("material emission must yield before completion"),
    }
}

#[test]
#[ignore = "requires RETINA_MATERIAL_PROFILE and a real GPU"]
fn material_continuations_preserve_interleaved_output() {
    let profile = fixture();
    let mut other = profile.clone();
    other.sea_level -= 17;
    let metrics = std::sync::Arc::new(crate::pipeline::Metrics::default());
    let mut gpu = Gpu::new(metrics.clone()).unwrap();
    let times = timings::Timings::default();
    // Distinct profile IDs, dimensions and coordinates exercise snapshot isolation
    // when later submissions replace the owner's shared request/density buffers.
    let a = request(1, -33, 3, 128);
    let b = request(2, 4095, 5, 192);
    let selected = |r: GpuRequest| if r.profile == 2 { &other } else { &profile };
    let mut expected = Vec::new();
    for r in [a, b] {
        let p = gpu.submit(&[r], Some(selected(r)), &times).unwrap();
        let sample = gpu.complete(p, &times).unwrap();
        sample
            .mask
            .as_ref()
            .unwrap()
            .validate_material_runs()
            .unwrap();
        expected.push(sample);
    }
    let before = metrics.snapshot().completed;
    let first = gpu.submit(&[a], Some(&profile), &times).unwrap();
    let second = gpu.submit(&[b], Some(&other), &times).unwrap();
    let first = continuation(&mut gpu, first, &times);
    assert_eq!(metrics.snapshot().completed, before);
    assert_eq!(gpu.readback_pool.len(), 1);
    let second = continuation(&mut gpu, second, &times);
    assert_eq!(metrics.snapshot().completed, before);
    assert_eq!(gpu.readback_pool.len(), 2);
    // Finish in reverse order as an extra guard against accidentally sharing an
    // output or timestamp buffer. Normal worker completion follows queue order.
    equal(&expected[1], &gpu.complete(second, &times).unwrap());
    equal(&expected[0], &gpu.complete(first, &times).unwrap());
    assert_eq!(metrics.snapshot().completed, before + 2);
    assert_eq!(gpu.material_pool.len(), 2);

    // Small -> large -> small requests reuse/grow slots with logical copy sizes.
    for r in [b, a, b, a] {
        let p = gpu.submit(&[r], Some(selected(r)), &times).unwrap();
        let result = gpu.complete(p, &times).unwrap();
        equal(&expected[usize::from(r.profile == 2)], &result);
        assert!(gpu.readback_pool.len() <= 2 && gpu.material_pool.len() <= 2);
    }

    // A plain height request can finish while another job awaits its second pass.
    let p = gpu.submit(&[a], Some(&profile), &times).unwrap();
    let p = continuation(&mut gpu, p, &times);
    let mut probe = a;
    probe.tile_side = 0;
    probe.padding = (1 << 31) | (1 << 29);
    let q = gpu.submit(&[probe], Some(&profile), &times).unwrap();
    assert!(matches!(
        gpu.advance(q, &times).unwrap(),
        Progress::Complete(_)
    ));
    equal(&expected[0], &gpu.complete(p, &times).unwrap());

    // Cancelling either phase must release its maps, without pooling a slot that
    // is still in flight. Subsequent generation must remain usable.
    let p = gpu.submit(&[a], Some(&profile), &times).unwrap();
    drop(p);
    let p = gpu.submit(&[b], Some(&other), &times).unwrap();
    drop(continuation(&mut gpu, p, &times));
    let p = gpu.submit(&[a], Some(&profile), &times).unwrap();
    equal(&expected[0], &gpu.complete(p, &times).unwrap());

    // A failed result is not recycled or counted as a completed job.
    let before = metrics.snapshot().completed;
    let p = gpu.submit(&[a], Some(&profile), &times).unwrap();
    let mut p = continuation(&mut gpu, p, &times);
    if let PendingStage::Materials(m) = &mut p.stage {
        m.mask = None;
    }
    assert!(gpu.complete(p, &times).is_err());
    assert_eq!(metrics.snapshot().completed, before);
    let p = gpu.submit(&[a], Some(&profile), &times).unwrap();
    equal(&expected[0], &gpu.complete(p, &times).unwrap());

    // Adapters without timestamps use the same continuation and reuse path.
    gpu.timestamp_support = false;
    gpu.readback_pool.clear();
    gpu.material_pool.clear();
    let p = gpu.submit(&[a], Some(&profile), &times).unwrap();
    let result = gpu.complete(p, &times).unwrap();
    equal(&expected[0], &result);
    assert_eq!(result.timings.flags, 0);
}

#[test]
#[ignore = "bounded material-only A/B; requires RETINA_MATERIAL_PROFILE and a real GPU"]
fn material_continuation_replay() {
    let profile = fixture();
    let mut gpu = Gpu::new(std::sync::Arc::new(crate::pipeline::Metrics::default())).unwrap();
    let times = timings::Timings::default();
    let requests = [request(1, -33, 8, 192), request(1, 4095, 8, 192)];
    let mut run = |overlap| {
        let start = Instant::now();
        let a = gpu.submit(&[requests[0]], Some(&profile), &times).unwrap();
        let b = gpu.submit(&[requests[1]], Some(&profile), &times).unwrap();
        let (a, b) = if overlap {
            let a = continuation(&mut gpu, a, &times);
            let b = continuation(&mut gpu, b, &times);
            (
                gpu.complete(a, &times).unwrap(),
                gpu.complete(b, &times).unwrap(),
            )
        } else {
            (
                gpu.complete(a, &times).unwrap(),
                gpu.complete(b, &times).unwrap(),
            )
        };
        (start.elapsed().as_secs_f64() * 1000., [a, b])
    };
    let (_, baseline) = run(false);
    let (_, candidate) = run(true);
    for (a, b) in baseline.iter().zip(&candidate) {
        equal(a, b);
    }
    let mut serial = Vec::new();
    let mut overlap = Vec::new();
    for i in 0..5 {
        for mode in [i % 2 == 0, i % 2 != 0] {
            let (ms, samples) = run(mode);
            for (a, b) in baseline.iter().zip(&samples) {
                equal(a, b);
            }
            if mode {
                overlap.push(ms);
            } else {
                serial.push(ms);
            }
        }
    }
    println!(
        "{}",
        serde_json::json!({"fixture":"two 8x8 tiles, height 192", "serial_ms":serial, "continuation_ms":overlap, "exact_outputs":true,
        "retained_emission_bytes":gpu.material_pool.iter().map(|s| s.storage.size()+s.readback.size()).sum::<u64>()})
    );
}
