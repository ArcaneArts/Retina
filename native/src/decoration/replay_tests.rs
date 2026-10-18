use super::*;
use serde_json::{Value, json};

fn simple(provider: Value) -> Value {
    json!({"kind":"simple_block", "provider":provider,
        "states":([3,4,6].into_iter().map(|s| json!({"source":s,"lower":[s,s],
            "upper":if s==6 {[8,8]} else {[0,0]},"survival":{"type":"true"},
            "upper_allowed":{"type":"true"}})).collect::<Vec<_>>()),
        "water":{"type":"material","offset":[0,0,0],"allowed":[7]}})
}
fn recipe(id: usize, placement: Value, feature: Value) -> Value {
    let mut r = json!({"source":format!("test:replay_{id}"),"salt":id,
        "density":1,"low_density":1,"noise_count":false,"rarity":1,
        "tries":1,"spread":[0,0,0],"placement":placement});
    r.as_object_mut()
        .unwrap()
        .extend(feature.as_object().unwrap().clone());
    r
}
pub(super) fn fixture(seed: u64, origin: i32) -> (WorldProfile, Field, ChunkRequest) {
    let plant =
        json!({"kind":"plant","states":[{"lower":6,"upper":8,"weight":1,"band":0,"dry":false}]});
    let trees = recipe(
        0,
        json!([{"type":"count","count":8},{"type":"in_square"},
        {"type":"heightmap","map":1},{"type":"biome"}]),
        json!({"kind":"tree",
        "trunk_shape":"straight_trunk_placer","foliage_shape":"blob_foliage_placer",
        "height":[5,2,1],"radius":[2,3],"offset":[0,0],"foliage_height":[3,3],"trunk_height":[0,0],
        "logs":[{"axes":[4,4,4],"weight":1}],"leaves":[{"distances":[5,5,5,5,5,5],"weight":1}],
        "soil":3,"root":0,"root_offset":[0,0]}),
    );
    let grass = recipe(
        1,
        json!([{"type":"count","count":64},{"type":"in_square"},
        {"type":"heightmap","map":1}]),
        plant.clone(),
    );
    // A nested feature writes before its first query, then overwrites the same
    // block. The final provider depends on that provisional result. Dual-noise
    // lookup positions change when samples arrive, forcing multiple rounds.
    let composite = recipe(
        2,
        json!([{"type":"count","count":2},{"type":"in_square"},
        {"type":"heightmap","map":1}]),
        json!({"kind":"sequence","features":[
            {"placement":[],"feature":simple(json!({"type":"state","material":4}))},
            {"placement":[],"feature":simple(json!({"type":"dual_noise","fast":0,"slow":1,
                "variety":[2,3],"states":[3,4,6]}))},
            {"placement":[],"feature":simple(json!({"type":"rule_based","fallback":{"type":"state","material":4},
                "rules":[{"predicate":{"type":"material","offset":[0,0,0],"allowed":[6]},
                "provider":{"type":"noise","program":2,"states":[3,4,6]}}]}))}
        ]}),
    );
    let layers = |lo: f64, hi: f64| {
        json!([{"type":"count_on_every_layer","count":1},
        {"type":"noise_based_count","noise_to_count_ratio":1,"noise_factor":30,"noise_offset":1},
        {"type":"select","min":lo,"max":hi}])
    };
    let mut a = recipe(3, layers(0., 0.5), plant.clone());
    let mut b = recipe(4, layers(0.5, 1.), plant);
    a["placement_salt"] = 33.into();
    b["placement_salt"] = 33.into();
    let profile: WorldProfile = serde_json::from_value(json!({
        "biome_scale":256,"blend":0.55,"sea_level":63,
        "stone":1,"water":7,"bedrock":1,"deepslate":1,"snow":1,"ice":7,
        "materials":["air","stone","grass","dirt","log","leaves","plant","water","upper"],
        "biomes":[{"id":"test:forest","climate":[0,0,0,0],"terrain":[0,0,0],
            "top":2,"filler":3,"underwater":3,"flags":0,"decorations":[0,1,2,3,4]}],
        "noises":[],"material_flags":[0,0,1,1,8,4,16,0,16],
        "heightmap_masks":[0,63,63,63,63,31,3,19,3],"ordered_decorations":true,
        "decoration_provider_noises":[{"layers":[]},{"layers":[]},{"layers":[]}],
        "decorations":[trees,grass,composite,a,b]
    }))
    .unwrap();
    for r in &profile.decorations {
        r.validate(profile.materials.len()).unwrap();
    }
    let field = Field {
        substrate: None,
        origin_x: origin - 1,
        origin_z: origin - 1,
        side: 4,
        columns: vec![
            Column {
                height: 64,
                materials: 2 | (3 << 16),
                packed: 0
            };
            16 * COLUMNS
        ],
    };
    let request = ChunkRequest {
        seed,
        chunk_x: origin,
        chunk_z: origin,
        min_y: 0,
        height: 128,
        base_height: 64.,
        amplitude: 0.,
        frequency: 0.008,
        reserved: 0,
    };
    (profile, field, request)
}
fn run(
    profile: &WorldProfile,
    field: &Field,
    request: ChunkRequest,
    retain: bool,
    budget: usize,
) -> (
    Vec<Vec<Placement>>,
    diagnostics::Planning,
    Vec<(Vec<[i32; 4]>, Vec<counts::Query>)>,
) {
    let mut replay = Replay::new(
        field,
        profile,
        request,
        request.chunk_x,
        request.chunk_z,
        2,
        None,
        None,
    );
    if !retain {
        replay = replay.without_prefix_resume();
    }
    replay.retained_limit = budget;
    let mut samples = provider_noise::Samples::default();
    let mut counts = counts::Counts::default();
    let mut details = diagnostics::Planning::default();
    let mut rounds = Vec::new();
    for _ in 0..16 {
        let queries = replay.poll_measured(&samples, Some(&counts), Some(&mut details));
        if queries.is_empty() {
            assert_eq!(replay.retained_bytes.load(Ordering::Relaxed), 0);
            return (replay.finish(), details, rounds);
        }
        rounds.push((queries.noise.clone(), queries.counts.clone()));
        samples.extend(queries.noise.into_iter().map(|q| {
            let value = if q[3] == 2 { -0.75 } else { 0.75 };
            (q, value)
        }));
        counts.extend(queries.counts.into_iter().map(|q| (q, 1)));
    }
    panic!("replay failed to converge");
}
#[test]
fn resumed_prefix_matches_full_replay_and_query_batches() {
    let pool = rayon::ThreadPoolBuilder::new()
        .num_threads(2)
        .build()
        .unwrap();
    for (seed, origin) in [(42, 0), (123456789, -33), (987654321, 700_000)] {
        let (profile, field, request) = fixture(seed, origin);
        let (expected, old, queries) = pool.install(|| run(&profile, &field, request, false, 0));
        let (actual, new, resumed_queries) =
            pool.install(|| run(&profile, &field, request, true, 64 * 1024 * 1024));
        assert!(!expected.iter().all(Vec::is_empty));
        assert_eq!(actual, expected, "seed {seed}, origin {origin}");
        assert_eq!(
            resumed_queries, queries,
            "query batching must not become serial"
        );
        assert!(
            new.rounds >= 3 && new.worker.resumed_anchors > 0 && new.worker.resumed_candidates > 0
        );
        assert!(new.worker.evaluated_candidates < old.worker.evaluated_candidates);
        assert!(new.worker.tree_attempts < old.worker.tree_attempts);
        assert!(new.peak_retained_bytes > 0 && new.peak_retained_bytes <= 64 * 1024 * 1024);
        let (fallback, limited, limited_queries) =
            pool.install(|| run(&profile, &field, request, true, 0));
        assert_eq!(fallback, expected);
        assert_eq!(limited_queries, queries);
        assert!(limited.worker.discarded_checkpoints > 0);
        assert_eq!(limited.worker.resumed_anchors, 0);
        assert_eq!(limited.peak_retained_bytes, 0);
        let budget = (new.peak_retained_bytes / 2) as usize;
        let (bounded, usage, bounded_queries) =
            pool.install(|| run(&profile, &field, request, true, budget));
        assert_eq!(bounded, expected);
        assert_eq!(bounded_queries, queries);
        assert!(usage.peak_retained_bytes <= budget as u64);
    }
}
#[test]
fn overlay_snapshot_restores_repeated_writes_and_heightmaps() {
    let (profile, field, request) = fixture(42, 0);
    let mut overlay = placement::Overlay::default();
    overlay.write(&profile, [0, 63, 0], 3);
    overlay.record_feature();
    for (at, material) in [
        ([0, 63, 0], 4),
        ([0, 69, 0], 5),
        ([0, 63, 0], 0),
        ([0, 69, 0], 0),
    ] {
        overlay.write(&profile, at, material);
    }
    let before = overlay.before_feature();
    assert_eq!(
        before.material(&field, &profile, request, [0, 63, 0]),
        Some(3)
    );
    assert_eq!(
        before.material(&field, &profile, request, [0, 69, 0]),
        Some(0)
    );
    for map in 0..6 {
        assert_eq!(
            before.height(&field, &profile, request, 0, 0, map),
            Some(64)
        );
    }
    assert_eq!(
        overlay.material(&field, &profile, request, [0, 63, 0]),
        Some(0)
    );
    overlay.finish_feature();
}
