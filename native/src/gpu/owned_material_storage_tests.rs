//! Actual-device checks for a material slot owned across both dispatch phases.
use super::material_continuation_tests::{continuation, equal, fixture, request};
use super::*;

fn owner(pending: &PendingSample) -> &wgpu::Buffer {
    match &pending.stage {
        PendingStage::Terrain(v) => &v.materials.as_ref().unwrap().slot.as_ref().unwrap().storage,
        PendingStage::Materials(v) => &v.slot.storage,
    }
}
fn retained_owner(pending: &PendingSample) -> &wgpu::Buffer {
    match &pending.stage {
        PendingStage::Terrain(v) => v.materials.as_ref().unwrap().retained.as_ref().unwrap(),
        PendingStage::Materials(v) => v._inputs.retained.as_ref().unwrap(),
    }
}
fn sample(
    gpu: &mut Gpu,
    profile: &WorldProfile,
    request: GpuRequest,
    times: &timings::Timings,
) -> GpuSample {
    let p = gpu.submit(&[request], Some(profile), times).unwrap();
    gpu.complete(p, times).unwrap()
}
fn report(name: &str, value: serde_json::Value) {
    if let Ok(directory) = std::env::var("RETINA_OWNED_MATERIAL_REPORTS") {
        std::fs::write(
            std::path::Path::new(&directory).join(format!("{name}.json")),
            serde_json::to_vec_pretty(&value).unwrap(),
        )
        .unwrap();
    }
    println!("{}", value);
}

#[test]
#[ignore = "requires RETINA_MATERIAL_PROFILE and a real GPU"]
fn owned_material_storage_reuses_exact_capacity_across_both_phases() {
    let profile = fixture();
    let mut gpu = Gpu::new(std::sync::Arc::new(crate::pipeline::Metrics::default())).unwrap();
    let times = timings::Timings::default();
    let a = request(1, -33, 22, 256);
    let b = request(1, 4095, 5, 128);
    let first = gpu.submit(&[a], Some(&profile), &times).unwrap();
    let prefix = owner(&first).clone();
    let prefix_bytes = prefix.size();
    assert_eq!(gpu.cave_buffers.as_ref().unwrap().mask.size(), 4);
    let first = continuation(&mut gpu, first, &times);
    if let PendingStage::Materials(p) = &first.stage {
        assert_eq!(p._growth_input.as_ref(), Some(&prefix));
        assert_ne!(&p.slot.storage, &prefix);
        assert!(p._inputs.slot.is_none());
    }
    let expected_a = gpu.complete(first, &times).unwrap();
    let cold = gpu.material_storage_counts.get();
    assert_eq!(cold.growth_copies, 1);
    assert_eq!(cold.encoded_prefix_copy_bytes, prefix_bytes);
    drop(prefix);
    let expected_b = sample(&mut gpu, &profile, b, &times);
    // Prime two slots with the larger geometry, then repeat genuine warm GPU work.
    for _ in 0..5 {
        let left = gpu.submit(&[a], Some(&profile), &times).unwrap();
        let right = gpu.submit(&[a], Some(&profile), &times).unwrap();
        assert_ne!(owner(&left), owner(&right));
        assert!(gpu.material_pool.is_empty());
        equal(&expected_a, &gpu.complete(right, &times).unwrap());
        equal(&expected_a, &gpu.complete(left, &times).unwrap());
    }
    let before = gpu.material_storage_counts.get();
    let retained_before = gpu.retained_allocations;
    let retained_reuses_before = gpu.retained_reuses;
    for i in 0..20 {
        let (r, expected) = if i % 2 == 0 {
            (a, &expected_a)
        } else {
            (b, &expected_b)
        };
        let p = gpu.submit(&[r], Some(&profile), &times).unwrap();
        let storage = owner(&p).clone();
        let retained = retained_owner(&p).clone();
        assert!(gpu.material_pool.iter().all(|v| v.storage != storage));
        assert!(gpu.retained_pool.iter().all(|v| v != &retained));
        let p = continuation(&mut gpu, p, &times);
        assert_eq!(owner(&p), &storage);
        assert_eq!(retained_owner(&p), &retained);
        assert!(gpu.retained_pool.iter().all(|v| v != &retained));
        if let PendingStage::Materials(v) = &p.stage {
            assert!(v._growth_input.is_none());
        }
        equal(expected, &gpu.complete(p, &times).unwrap());
    }
    let after = gpu.material_storage_counts.get();
    assert_eq!(after.allocations, before.allocations);
    assert_eq!(gpu.retained_allocations, retained_before);
    assert_eq!(gpu.retained_reuses - retained_reuses_before, 20);
    assert_eq!(
        after.encoded_prefix_copy_bytes,
        before.encoded_prefix_copy_bytes
    );
    assert_eq!(after.in_place_emissions - before.in_place_emissions, 20);
    assert_eq!(after.slot_reuses - before.slot_reuses, 20);
    assert_eq!(gpu.material_pool.len(), 2);
    report(
        "reuse",
        serde_json::json!({"cold":cold,"before":before,"after":after,
        "measured_requests":20,"warmup_pairs":5,"exact":true,
        "retained_before":retained_before,"retained_after":gpu.retained_allocations,
        "retained_reuses":gpu.retained_reuses-retained_reuses_before,
        "scope":"Raw 22x22/5x5 terrain requests, not full regions. Both private resources remain exclusive through both phases. Counters record allocation and encoded prefix copies, not net device traffic or timing acceptance."}),
    );
}

#[test]
#[ignore = "requires RETINA_TERRAIN_REUSE_PROFILES and a real GPU"]
fn owned_material_storage_isolates_profiles_geometry_and_eight_pending_jobs() {
    let directory =
        std::path::PathBuf::from(std::env::var("RETINA_TERRAIN_REUSE_PROFILES").unwrap());
    let mut profiles = Vec::new();
    for name in ["nether", "custom", "overworld"] {
        let mut v: serde_json::Value =
            serde_json::from_slice(&std::fs::read(directory.join(format!("{name}.json"))).unwrap())
                .unwrap();
        v["program_execution"] = "interpreter".into();
        profiles.push(WorldProfile::parse(&serde_json::to_vec(&v).unwrap()).unwrap());
    }
    let mut plain = profiles[0].clone();
    plain.registry_program.as_mut().unwrap().material_layers = false;
    profiles.push(plain);
    let mut gpu = Gpu::new(std::sync::Arc::new(crate::pipeline::Metrics::default())).unwrap();
    let times = timings::Timings::default();
    let cases = [
        request(1, -33, 3, 128),
        request(2, 4095, 8, 192),
        request(3, -65, 5, 384),
        request(1, 17, 38, 1),
    ];
    let mut expected = Vec::new();
    for mut r in cases {
        r.seed_high = r.profile + 7;
        expected.push(sample(
            &mut gpu,
            &profiles[r.profile as usize - 1],
            r,
            &times,
        ));
    }
    let mut pending = Vec::new();
    for i in 0..8 {
        let mut r = cases[i % cases.len()];
        r.seed_high = r.profile + 7;
        let p = gpu
            .submit(&[r], Some(&profiles[r.profile as usize - 1]), &times)
            .unwrap();
        for (_, earlier) in &pending {
            assert_ne!(owner(&p), owner(earlier));
        }
        pending.push((i % cases.len(), p));
    }
    // Exercise the original shared-mask path while all private jobs are active.
    let plain_request = request(4, 71, 5, 128);
    let plain_expected = sample(&mut gpu, &profiles[3], plain_request, &times);
    assert!(gpu.cave_buffers.as_ref().unwrap().mask.size() > 4);
    let pending: Vec<_> = pending
        .into_iter()
        .map(|(i, p)| (i, continuation(&mut gpu, p, &times)))
        .collect();
    assert!(gpu.material_pool.is_empty());
    for (i, p) in pending.into_iter().rev() {
        equal(&expected[i], &gpu.complete(p, &times).unwrap());
    }
    assert_eq!(gpu.material_pool.len(), 2);
    equal(
        &plain_expected,
        &sample(&mut gpu, &profiles[3], plain_request, &times),
    );
    for i in [3, 0, 2, 1, 0, 3] {
        let mut r = cases[i];
        r.seed_high = r.profile + 7;
        equal(
            &expected[i],
            &sample(&mut gpu, &profiles[r.profile as usize - 1], r, &times),
        );
    }
    report(
        "isolation",
        serde_json::json!({"exact":true,"pending_jobs":8,
        "profile_cases":3,"non_layered_case":true,"largest_side":38,"smallest_height":1,
        "counts":gpu.material_storage_counts.get(),"idle_slots":gpu.material_pool.len()}),
    );
}

#[test]
#[ignore = "requires RETINA_MATERIAL_PROFILE and a real GPU"]
fn owned_material_storage_preserves_invalid_output_and_recovery() {
    let profile = fixture();
    let mut gpu = Gpu::new(std::sync::Arc::new(crate::pipeline::Metrics::default())).unwrap();
    let times = timings::Timings::default();
    let valid = request(1, -33, 3, 128);
    let expected = sample(&mut gpu, &profile, valid, &times);
    for (index, value) in [1_073_741_824.0, 2_147_483_648.0].into_iter().enumerate() {
        let mut invalid = profile.clone();
        let registry = invalid.registry_program.as_mut().unwrap();
        for p in &mut registry.programs[3..3 + invalid.biomes.len()] {
            p.nodes = vec![crate::program::Instruction {
                op: 0,
                a: 0,
                b: 0,
                c: 0,
                p: [value, 0., 0., 0.],
            }];
            p.roots = vec![0];
        }
        registry.validate(invalid.biomes.len()).unwrap();
        for _ in 0..2 {
            let p = gpu
                .submit(
                    &[request(2 + index as u32, -33, 3, 128)],
                    Some(&invalid),
                    &times,
                )
                .unwrap();
            assert!(gpu.material_pool.is_empty());
            match gpu.complete(p, &times) {
                Err(e) => assert_eq!(e, "unordered GPU material runs"),
                Ok(_) => panic!("invalid material output unexpectedly accepted"),
            }
            assert!(gpu.material_pool.is_empty());
            equal(&expected, &sample(&mut gpu, &profile, valid, &times));
        }
    }
    report(
        "failure-recovery",
        serde_json::json!({"invalid_programs":2,"failed_jobs":4,
        "recovery_exact":true,"counts":gpu.material_storage_counts.get()}),
    );
}

#[test]
#[ignore = "requires RETINA_MATERIAL_PROFILE and a real GPU"]
fn owned_material_storage_keeps_cave_capacity_across_underground_probes() {
    let profile = fixture();
    let mut gpu = Gpu::new(std::sync::Arc::new(crate::pipeline::Metrics::default())).unwrap();
    let times = timings::Timings::default();
    let terrain = request(1, -33, 22, 256);
    let probes: Vec<_> = (0..9)
        .map(|i| {
            let mut r = terrain;
            r.origin_x += i * 16;
            r.tile_side = 0;
            r.padding = (1 << 30) | (128 << 8);
            r
        })
        .collect();
    let pending = gpu.submit(&probes, Some(&profile), &times).unwrap();
    let expected_probes = gpu.complete(pending, &times).unwrap();
    let expected_terrain = sample(&mut gpu, &profile, terrain, &times);
    let nodes = gpu.cave_buffers.as_ref().unwrap().nodes.clone();
    assert!(nodes.size() > 16);
    let mut observations = Vec::new();
    for _ in 0..5 {
        let pending = gpu.submit(&probes, Some(&profile), &times).unwrap();
        equal(&expected_probes, &gpu.complete(pending, &times).unwrap());
        let buffers = gpu.cave_buffers.as_ref().unwrap();
        let node_preserved = buffers.nodes == nodes;
        let probe_nodes_bytes = buffers.nodes.size();
        let mask = buffers.mask.clone();
        assert!(mask.size() >= probes.len() as u64 * 4);
        equal(&expected_terrain, &sample(&mut gpu, &profile, terrain, &times));
        let buffers = gpu.cave_buffers.as_ref().unwrap();
        observations.push(serde_json::json!({
            "node_buffer_survived_probe":node_preserved,
            "probe_mask_survived_terrain":buffers.mask == mask,
            "probe_nodes_bytes":probe_nodes_bytes,
            "terrain_nodes_bytes":buffers.nodes.size(),
            "probe_mask_bytes":mask.size(),
            "terrain_mask_bytes":buffers.mask.size(),
        }));
    }
    report("sparse-probe-capacity", serde_json::json!({
        "exact":true,"observations":observations,
        "scope":"Actual alternating 22x22 terrain and nine-point underground biome queries; buffer identity and capacity, not timing acceptance."
    }));
    assert!(observations.iter().all(|v| v["node_buffer_survived_probe"] == true
        && v["probe_mask_survived_terrain"] == true),
        "growing one shared cave buffer must not discard the other's sufficient capacity");
}
