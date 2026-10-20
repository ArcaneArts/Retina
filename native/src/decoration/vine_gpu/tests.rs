use super::*;
use serde_json::json;
use std::sync::Mutex;
fn fixture() -> (WorldProfile, ChunkRequest) {
    let (mut p, _, mut r) = super::super::replay_tests::fixture(42, 0);
    r.min_y = -64;
    r.height = 384;
    let mat = |offset, allowed| json!({"type":"material","offset":offset,"allowed":allowed});
    let air = mat([0, 0, 0], vec![0]);
    let support = mat([0, 1, 0], vec![1, 6]);
    let recipe:Recipe=serde_json::from_value(json!({"source":"test:vines","salt":1000,
        "density":1,"low_density":1,"noise_count":false,"rarity":1,"tries":1,"spread":[0,0,0],
        "placement":[{"type":"count","count":200},{"type":"count","count":40},{"type":"in_square"},
          {"type":"height_range","height":{"type":"uniform","min_inclusive":{"absolute":64},"max_inclusive":{"absolute":255}}},
          {"type":"block_predicate_filter","predicate":{"type":"true"}},{"type":"biome"}],
        "kind":"attachment_growth","vines":true,"initial":6,"wet_initial":6,"face_mask":63,
        "states":[{"source":6,"faces":2,"added":[6,6,6,6,6,6]}],"directions":[1],
        "search_range":0,"spread_chance":0,"spread_order":[],"sculk":false,
        "air":air,"water":air,"source_water":air,"placed_on":support,"spread_replaceable":air,"spread_support":support,
        "support":[support,support,support,support,support,support],"sturdy":[support,support,support,support,support,support]})).unwrap();
    recipe.validate(p.materials.len()).unwrap();
    p.decorations.push(recipe);
    p.biomes[0].decorations.push(5);
    (p, r)
}
#[test]
fn eligibility_requires_independence_and_unshared_rng_groups() {
    let (mut p, r) = fixture();
    assert!(compile(&p, r)[5].is_some());
    let mut shared = p.decorations[5].clone();
    shared.source = "test:shared".into();
    p.decorations.push(shared);
    assert!(compile(&p, r)[5].is_none());
    p.decorations.pop();
    let mut json = serde_json::json!({"type":"material","offset":[1,0,0],"allowed":[0]});
    let mut ops = p.decorations[5].placement.as_ref().unwrap().to_vec();
    ops[4] = Modifier::BlockPredicateFilter {
        predicate: serde_json::from_value(json.clone()).unwrap(),
    };
    p.decorations[5].placement = Some(ops.into());
    assert!(compile(&p, r)[5].is_none());
    json["allowed"] = json!([0, 6]);
    let mut ops = p.decorations[5].placement.as_ref().unwrap().to_vec();
    ops[4] = Modifier::BlockPredicateFilter {
        predicate: serde_json::from_value(json).unwrap(),
    };
    p.decorations[5].placement = Some(ops.into());
    assert!(compile(&p, r)[5].is_some());
    assert!(compile(&p, ChunkRequest { height: 513, ..r })[5].is_none());
}
fn replay(
    profile: &WorldProfile,
    field: &Field,
    request: ChunkRequest,
    gpu: Option<&Mutex<Gpu>>,
    budget: usize,
    post_gpu_limit: Option<usize>,
) -> (Vec<Vec<Placement>>, Stats, usize) {
    replay_batched(
        profile,
        field,
        request,
        gpu,
        budget,
        post_gpu_limit,
        usize::MAX,
    )
}
fn replay_batched(
    profile: &WorldProfile,
    field: &Field,
    request: ChunkRequest,
    gpu: Option<&Mutex<Gpu>>,
    budget: usize,
    post_gpu_limit: Option<usize>,
    batch: usize,
) -> (Vec<Vec<Placement>>, Stats, usize) {
    let mut state = Replay::new(
        field,
        profile,
        request,
        request.chunk_x,
        request.chunk_z,
        1,
        None,
        None,
    )
    .with_gpu_programs(gpu.map(|_| compile(profile, request)));
    state.retained_limit = budget;
    state.ready_limit = batch;
    state.reuse_substrate = batch != usize::MAX;
    let mut samples = provider_noise::Samples::default();
    let mut counts = counts::Counts::default();
    let mut stats = Stats::default();
    let mut provider_rounds = 0;
    let mut details = diagnostics::Planning::default();
    for _ in 0..256 {
        let q = state.poll_measured(&samples, Some(&counts), Some(&mut details));
        assert!(
            state.retained_bytes.load(Ordering::Relaxed) + state.retained_substrate_bytes()
                <= state.retained_limit
        );
        if q.is_empty() {
            if profile.decoration_source_order {
                assert!(state.blocks.iter().flatten().flatten().all(|block| block.root_order > 0),
                    "CPU and GPU writes retain their nonzero fixture root order through every continuation");
            }
            assert_eq!(
                details.worker.evaluated_candidates,
                details.worker.resolved_candidates + details.worker.speculative_candidates
            );
            assert_eq!(
                details.worker.evaluated_candidates,
                details.worker.unsupported_cpu_candidates
                    + details.worker.gpu_supported_cpu_candidates
            );
            if gpu.is_some() && budget > 0 {
                assert!(
                    details.worker.compacted_gpu_groups > 0,
                    "{} / {}",
                    serde_json::to_string(&details).unwrap(),
                    serde_json::to_string(&stats).unwrap()
                );
                assert!(details.worker.compacted_queue_bytes > 0);
            }
            if gpu.is_some() && budget == 0 {
                assert!(details.worker.retained_limit_gpu_groups > 0);
            }
            return (state.finish(), stats, provider_rounds);
        }
        if !q.vines.is_empty() {
            if let Some(limit) = post_gpu_limit {
                state.retained_limit = limit;
            }
            stats.add(state.run_vines(&q.vines, gpu.unwrap()));
            assert!(
                state.retained_bytes.load(Ordering::Relaxed) + state.retained_substrate_bytes()
                    <= state.retained_limit
            );
        }
        if !q.noise.is_empty() {
            provider_rounds += 1;
        }
        samples.extend(
            q.noise
                .into_iter()
                .map(|q| (q, if q[3] == 2 { -0.75 } else { 0.75 })),
        );
        counts.extend(q.counts.into_iter().map(|q| (q, 1)));
    }
    panic!("handoff must converge");
}
#[test]
#[ignore = "bounded Metal planner handoff, overflow and retained-prefix correctness"]
fn gpu_vine_planner_handoff() {
    let engine = crate::TerrainEngine::new().unwrap();
    let (device, queue) = engine.ore_gpu.lock().unwrap().shared_device();
    let mut gpu = Gpu::new(device, queue, engine.backend());
    gpu.enable_for_test(true);
    let gpu = Mutex::new(gpu);
    let pool = rayon::ThreadPoolBuilder::new()
        .num_threads(2)
        .build()
        .unwrap();
    let mut rows = Vec::new();
    for origin in [0, -17, 1_000_000] {
        let (mut p, r) = fixture();
        p.decoration_source_order = origin == -17;
        for (id, recipe) in p.decorations.iter_mut().enumerate() {
            recipe.root_order = id.min(3) as u32 + 1;
        }
        let r = ChunkRequest {
            chunk_x: origin,
            chunk_z: -origin,
            ..r
        };
        let field = super::super::anchor_probe::vine_field(&p, 5, 6, r, 1);
        let (expected, _, rounds) = pool.install(|| replay(&p, &field, r, None, 64 << 20, None));
        assert!(rounds > 0, "earlier features must have missing providers");
        let (actual, stats, _) = pool.install(|| replay(&p, &field, r, Some(&gpu), 64 << 20, None));
        assert_eq!(actual, expected, "origin {origin}");
        for batch in [1, 4, 9] {
            for budget in [0, 64 << 20] {
                let (batched, _, _) =
                    pool.install(|| replay_batched(&p, &field, r, Some(&gpu), budget, None, batch));
                assert_eq!(
                    batched, expected,
                    "batch {batch}, origin {origin}, budget {budget}"
                );
            }
        }
        assert!(
            stats.anchors > stats.fallbacks,
            "must actually evaluate anchors on GPU"
        );
        let (bounded, _, _) = pool.install(|| replay(&p, &field, r, Some(&gpu), 0, None));
        assert_eq!(bounded, expected, "zero retention must use CPU");
        let (discarded, discarded_stats, _) =
            pool.install(|| replay(&p, &field, r, Some(&gpu), 64 << 20, Some(0)));
        assert!(discarded_stats.batches > 0);
        assert_eq!(
            discarded, expected,
            "discarding an oversized successful prefix must replay on CPU"
        );
        let (batched_discarded, _, _) =
            pool.install(|| replay_batched(&p, &field, r, Some(&gpu), 64 << 20, Some(0), 4));
        assert_eq!(batched_discarded, expected);
        gpu.lock().unwrap().enable_for_test(false);
        let (failed, failed_stats, _) =
            pool.install(|| replay(&p, &field, r, Some(&gpu), 64 << 20, None));
        assert!(failed_stats.device_failure_anchors > 0);
        assert_eq!(
            failed, expected,
            "disabled device keeps the original CPU continuation"
        );
        let (batched_failed, _, _) =
            pool.install(|| replay_batched(&p, &field, r, Some(&gpu), 64 << 20, None, 4));
        assert_eq!(batched_failed, expected);
        gpu.lock().unwrap().enable_for_test(true);
        rows.push(serde_json::json!({"origin":origin,"writes":actual.iter().map(Vec::len).sum::<usize>(),"stats":stats}));
        let programs = compile(&p, r);
        let prog = programs[5].as_ref().unwrap();
        let mut input = Input::new(&field, &p, r, 5, prog, None).unwrap();
        let overlay = Overlay::default();
        let jobs = [(r.chunk_x, r.chunk_z, &overlay)];
        let (normal, _) = gpu.lock().unwrap().run(&input, r, prog, &jobs);
        assert!(!normal[0].as_ref().unwrap().is_empty());
        let shared = Arc::new(Input::new(&field, &p, r, 5, prog, None).unwrap());
        let (first, first_stats) = gpu.lock().unwrap().run_shared(&shared, r, prog, &jobs);
        let (reused, reuse_stats) = gpu.lock().unwrap().run_shared(&shared, r, prog, &jobs);
        assert_eq!(first, normal);
        assert_eq!(reused, normal);
        assert_eq!(
            first_stats.upload_bytes - reuse_stats.upload_bytes,
            shared.words.len() as u64 * 4
        );
        let mut other = Input::new(&field, &p, r, 5, prog, None).unwrap();
        other.words[5] = 0;
        let other = Arc::new(other);
        let (other_result, other_stats) = gpu.lock().unwrap().run_shared(&other, r, prog, &jobs);
        assert!(other_result[0].is_none());
        assert_eq!(other_stats.upload_bytes, first_stats.upload_bytes);
        let (restored, restore_stats) = gpu.lock().unwrap().run_shared(&shared, r, prog, &jobs);
        assert_eq!(restored, normal);
        assert_eq!(restore_stats.upload_bytes, first_stats.upload_bytes);
        gpu.lock().unwrap().discard_buffers_for_test();
        let (rebuilt, rebuild_stats) = gpu.lock().unwrap().run_shared(&shared, r, prog, &jobs);
        assert_eq!(rebuilt, normal);
        assert_eq!(rebuild_stats.upload_bytes, first_stats.upload_bytes);
        input.words[5] = 0;
        let (overflow, stats) = gpu.lock().unwrap().run(&input, r, prog, &jobs);
        assert!(overflow[0].is_none());
        assert_eq!(stats.fallbacks, 1);
        assert_eq!(stats.overflow_anchors, 1);
        assert_eq!(stats.output_capacity_overflow_anchors, 1);
        assert_eq!(stats.column_queue_overflow_anchors, 0);
        assert_eq!(stats.unresolved_material_anchors, 0);
        input.words[5] = CAP as u32;
        input.words[30] = 1;
        let (overflow, stats) = gpu.lock().unwrap().run(&input, r, prog, &jobs);
        assert!(overflow[0].is_none());
        assert_eq!(stats.column_queue_overflow_anchors, 1);
        assert_eq!(stats.output_capacity_overflow_anchors, 0);
        assert_eq!(stats.unresolved_material_anchors, 0);
    }
    println!("VINE_HANDOFF {}", serde_json::to_string(&rows).unwrap());
}
#[test]
#[ignore = "bounded complete registered terrain planning CPU/GPU comparison"]
fn gpu_vine_registered_planner() {
    let file = std::env::var("RETINA_ANCHOR_PROFILE").expect("exported profile");
    let mut source: serde_json::Value =
        serde_json::from_slice(&std::fs::read(file).unwrap()).unwrap();
    source["program_execution"] = "interpreter".into();
    let engine = crate::TerrainEngine::new().unwrap();
    let id = engine
        .register_profile(&serde_json::to_vec(&source).unwrap())
        .unwrap();
    let profile = engine.profile(id).unwrap().unwrap();
    let r = ChunkRequest {
        seed: 123456789,
        chunk_x: 0,
        chunk_z: 0,
        min_y: -64,
        height: 384,
        base_height: 64.,
        amplitude: 48.,
        frequency: 0.008,
        reserved: id,
    };
    let (field, mask) = engine.terrain_field(r, 32).unwrap();
    let pool = rayon::ThreadPoolBuilder::new()
        .num_threads(2)
        .build()
        .unwrap();
    let mut expected = None;
    let mut rows = Vec::new();
    for enabled in [false, true, true, false] {
        engine.vine_gpu.lock().unwrap().enable_for_test(enabled);
        let start = std::time::Instant::now();
        let result = pool
            .install(|| engine.plan_decorations(&field, &profile, r, 32, mask.as_deref()))
            .unwrap();
        let ms = start.elapsed().as_secs_f64() * 1000.;
        if let Some(expected) = &expected {
            assert_eq!(&result, expected, "complete ordered registered plan");
        } else {
            expected = Some(result);
        }
        rows.push(serde_json::json!({"gpu":enabled,"wall_ms":ms}));
    }
    println!("VINE_REGISTERED {}", serde_json::to_string(&rows).unwrap());
}

#[test]
#[ignore = "bounded Metal unknown materials versus capacity overflow classification"]
fn gpu_vine_unknown_material_is_not_overflow() {
    let engine = crate::TerrainEngine::new().unwrap();
    let (device, queue) = engine.ore_gpu.lock().unwrap().shared_device();
    let mut gpu = Gpu::new(device, queue, engine.backend());
    gpu.enable_for_test(true);
    let gpu = Mutex::new(gpu);
    let pool = rayon::ThreadPoolBuilder::new()
        .num_threads(2)
        .build()
        .unwrap();
    for origin in [0, -17, 1_000_000] {
        let (mut p, mut r) = fixture();
        r.chunk_x = origin;
        r.chunk_z = -origin;
        let mut ops = p.decorations[5].placement.as_ref().unwrap().to_vec();
        ops[4] = Modifier::BlockPredicateFilter {
            predicate: serde_json::from_value(json!({
                "type":"material", "offset":[-1,0,0], "allowed":[1]
            }))
            .unwrap(),
        };
        p.decorations[5].placement = Some(ops.into());
        let field = super::super::anchor_probe::vine_field(&p, 5, 6, r, 1);
        let (expected, _, _) = pool.install(|| replay(&p, &field, r, None, 64 << 20, None));
        let (actual, stats, _) = pool.install(|| replay(&p, &field, r, Some(&gpu), 64 << 20, None));
        assert_eq!(actual, expected, "origin {origin}");
        assert!(stats.unresolved_material_anchors > 0);
        assert_eq!(stats.fallbacks, stats.unresolved_material_anchors);
        assert_eq!(stats.overflow_anchors, 0);
        assert_eq!(stats.incomplete_candidate_anchors, 0);
    }
}
