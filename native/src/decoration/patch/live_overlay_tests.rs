use super::*;
use serde_json::json;

fn entries(overlay: &Overlay) -> Vec<([i32; 3], u16)> {
    let mut entries = overlay.probe_entries().collect::<Vec<_>>();
    entries.sort_unstable();
    entries
}

fn same_state(a: &Overlay, b: &Overlay, field: &Field, profile: &WorldProfile, request: ChunkRequest) {
    assert_eq!(entries(a), entries(b));
    let x = request.chunk_x * 16 + 8;
    let z = request.chunk_z * 16 + 8;
    for dx in -2..=2 {
        for dz in -2..=2 {
            for map in 0..6 {
                assert_eq!(a.height(field, profile, request, x + dx, z + dz, map),
                    b.height(field, profile, request, x + dx, z + dz, map));
            }
            for layer in 0..3 {
                assert_eq!(a.ground_layer(field, profile, request, x + dx, z + dz, layer),
                    b.ground_layer(field, profile, request, x + dx, z + dz, layer));
            }
        }
    }
}

#[test]
fn blob_bitmap_replays_exact_commands_rng_predicates_and_undo_like_map_storage() {
    for origin in [-33, 0, 17] {
        let (mut profile, field, mut request) = crate::decoration::replay_tests::fixture(42, origin);
        profile.ordered_decorations = true;
        profile.decorations.truncate(1);
        profile.decorations[0].source = "test:arbitrary_blob_label".into();
        profile.decorations[0].placement = Some(serde_json::from_value(json!([
            {"type":"in_square"}])).unwrap());
        for (min_y,height) in [(-64,256),(0,128),(16,384)] {
            request.min_y=min_y;request.height=height;
            for state in [0,4,7] {
                for target in [json!({"type":"true"}),
                    json!({"type":"material","offset":[0,0,0],"allowed":[1,2,3]}),
                    json!({"type":"material","offset":[1,-1,0],"allowed":[0,1,2,3,7]})] {
                    let feature: nether::Feature = serde_json::from_value(json!({
                        "kind":"replace_blobs","target":target,"state":state,
                        "radius":{"type":"uniform","min_inclusive":0,"max_inclusive":7}})).unwrap();
                    profile.decorations[0].feature=Kind::NetherFeature{nether:feature.clone()};
                    for seed in [0,42,u64::MAX] {
                        let mut actual=Overlay::for_recipes(&profile,request,origin,origin,&[0]);
                        let mut expected=Overlay::default();
                        let initial=actual.snapshot();
                        actual.record_feature();expected.record_feature();
                        let mut a=Vec::new();let mut b=Vec::new();
                        let mut ra=Rng::new(seed);let mut rb=Rng::new(seed);
                        let samples=provider_noise::Samples::default();
                        let noise=provider_noise::Context::new(&samples);
                        for at in [[origin*16+7,63,origin*16+8],
                            [origin*16+7,63,origin*16+8],[origin*16+15,70,origin*16]] {
                            let av=feature.place(&mut World{field:&field,profile:&profile,request,
                                overlay:&mut actual,blocks:&mut a,noise:&noise,deferred_ore:None},at,&mut ra);
                            let bv=feature.place(&mut World{field:&field,profile:&profile,request,
                                overlay:&mut expected,blocks:&mut b,noise:&noise,deferred_ore:None},at,&mut rb);
                            assert_eq!(av,bv);assert_eq!(ra.0,rb.0);assert_eq!(a,b);
                            same_state(&actual,&expected,&field,&profile,request);
                        }
                        same_state(&initial,&actual.before_feature(),&field,&profile,request);
                        actual.finish_feature();expected.finish_feature();
                        same_state(&actual,&expected,&field,&profile,request);
                        assert!(noise.missing().is_empty());
                    }
                }
            }
        }
    }
}

#[test]
fn blob_singleton_matches_scalar_predicates_queries_rng_commands_and_undo() {
    use std::sync::{Mutex, atomic::{AtomicUsize, Ordering}};
    #[derive(Default)]
    struct Prior { queries: Mutex<Vec<[i32; 3]>>, matching: AtomicUsize }
    impl Prior {
        fn value(at: [i32; 3]) -> Option<u16> {
            if (at[0] ^ at[2]).rem_euclid(17) == 0 { return None; }
            Some(if at[1] <= 64 { 1 } else if at[1] < 96 { 3 } else { 0 })
        }
    }
    impl PriorWorld for Prior {
        fn column(&self, _: i32, _: i32) -> Option<Column> { None }
        fn biome(&self, _: [i32; 3]) -> Option<usize> { Some(0) }
        fn height(&self, _: i32, _: i32, _: u8) -> Option<i32> { Some(96) }
        fn material(&self, at: [i32; 3]) -> Option<u16> {
            self.queries.lock().unwrap().push(at); Self::value(at)
        }
        fn material_matches(&self, at: [i32; 3], material: u16) -> Option<bool> {
            self.matching.fetch_add(1, Ordering::Relaxed);
            self.queries.lock().unwrap().push(at); Self::value(at).map(|m| m == material)
        }
    }
    for origin in [-33, 0, 17] {
        let (mut profile, base, mut request) = crate::decoration::replay_tests::fixture(42, origin);
        for ordered in [false, true] {
            profile.ordered_decorations = ordered;
            for (min_y, height) in [(-64, 256), (0, 128), (16, 384)] {
                request.min_y = min_y; request.height = height;
                for target in [json!({"type":"material","offset":[0,0,0],"allowed":[1]}),
                    json!({"type":"material","offset":[0,0,0],"allowed":[0]}),
                    json!({"type":"material","offset":[1,-1,0],"allowed":[1]}),
                    json!({"type":"material","offset":[0,0,0],"allowed":[0,1]}),
                    json!({"type":"true"})] {
                    for state in [0, 1, 4] {
                        for seed in [0, 42, u64::MAX] {
                            let fast: nether::Feature = serde_json::from_value(json!({
                                "kind":"replace_blobs","target":target,"state":state,
                                "radius":{"type":"uniform","min_inclusive":0,"max_inclusive":7}})).unwrap();
                            let mut reference = fast.clone();
                            if let nether::Feature::ReplaceBlobs { target, .. } = &mut reference {
                                *target = Predicate::AllOf { predicates: vec![target.clone()] };
                            }
                            let pa = Prior::default(); let pb = Prior::default();
                            let fa = Field { prior: Some(&pa), origin_x: base.origin_x, origin_z: base.origin_z,
                                side: base.side, columns: base.columns.clone(), substrate: None };
                            let fb = Field { prior: Some(&pb), origin_x: base.origin_x, origin_z: base.origin_z,
                                side: base.side, columns: base.columns.clone(), substrate: None };
                            let mut oa = Overlay::default(); let mut ob = Overlay::default();
                            oa.record_feature(); ob.record_feature();
                            let mut a = Vec::new(); let mut b = Vec::new();
                            let mut ra = Rng::new(seed); let mut rb = Rng::new(seed);
                            let samples = provider_noise::Samples::default();
                            let noise = provider_noise::Context::new(&samples);
                            for at in [[origin*16+7, 70, origin*16+8], [origin*16+7, 70, origin*16+8],
                                [origin*16+15, 63, origin*16]] {
                                let av = fast.place(&mut World { field: &fa, profile: &profile, request,
                                    overlay: &mut oa, blocks: &mut a, noise: &noise, deferred_ore: None }, at, &mut ra);
                                let bv = reference.place(&mut World { field: &fb, profile: &profile, request,
                                    overlay: &mut ob, blocks: &mut b, noise: &noise, deferred_ore: None }, at, &mut rb);
                                assert_eq!(av, bv); assert_eq!(ra.0, rb.0); assert_eq!(a, b);
                                assert_eq!(entries(&oa), entries(&ob));
                                assert_eq!(*pa.queries.lock().unwrap(), *pb.queries.lock().unwrap());
                            }
                            let eligible = ordered && target["type"] == "material"
                                && target["offset"] == json!([0,0,0])
                                && target["allowed"].as_array().unwrap().len() == 1;
                            assert_eq!(pa.matching.load(Ordering::Relaxed) > 0, eligible);
                            assert_eq!(pb.matching.load(Ordering::Relaxed), 0);
                            same_state(&oa, &ob, &fa, &profile, request);
                            same_state(&oa.before_feature(), &ob.before_feature(), &fa, &profile, request);
                            oa.finish_feature(); ob.finish_feature();
                            assert!(noise.missing().is_empty());
                        }
                    }
                }
            }
        }
    }
}

#[test]
fn combined_blob_bitmap_and_singleton_queries_preserve_live_state_and_promotion() {
    use std::sync::{Mutex, atomic::{AtomicUsize, Ordering}};

    #[derive(Default)]
    struct Prior {
        queries: Mutex<Vec<[i32; 3]>>,
        matching: AtomicUsize,
        storage: Mutex<Vec<(usize, usize, bool)>>,
    }
    impl Prior {
        fn value(at: [i32; 3]) -> Option<u16> {
            if (at[0] ^ at[2]).rem_euclid(17) == 0 { return None; }
            Some(if at[1] <= 64 { 1 } else if at[1] < 96 { 3 } else { 0 })
        }
    }
    impl PriorWorld for Prior {
        fn column(&self, _: i32, _: i32) -> Option<Column> { None }
        fn biome(&self, _: [i32; 3]) -> Option<usize> { Some(0) }
        fn height(&self, _: i32, _: i32, _: u8) -> Option<i32> { Some(96) }
        fn material(&self, at: [i32; 3]) -> Option<u16> {
            self.queries.lock().unwrap().push(at);
            Self::value(at)
        }
        fn material_matches(&self, at: [i32; 3], material: u16) -> Option<bool> {
            self.matching.fetch_add(1, Ordering::Relaxed);
            self.queries.lock().unwrap().push(at);
            Self::value(at).map(|actual| actual == material)
        }
        fn blob_overlay_observed(&self, bytes: usize, points: usize, promoted: bool) {
            self.storage.lock().unwrap().push((bytes, points, promoted));
        }
    }
    for origin in [-33, 0, 17] {
        let (mut profile, base, mut request) = crate::decoration::replay_tests::fixture(42, origin);
        profile.decorations.truncate(1);
        profile.decorations[0].source = "test:combined_arbitrary_blob_label".into();
        profile.decorations[0].placement = Some(serde_json::from_value(json!([
            {"type":"in_square"}])).unwrap());
        for ordered in [false, true] {
            profile.ordered_decorations = ordered;
            for (min_y, height) in [(-64, 256), (0, 128), (16, 384)] {
                request.min_y = min_y;
                request.height = height;
                for target in [json!({"type":"material","offset":[0,0,0],"allowed":[1]}),
                    json!({"type":"material","offset":[0,0,0],"allowed":[0]}),
                    json!({"type":"material","offset":[1,-1,0],"allowed":[1]}),
                    json!({"type":"material","offset":[0,0,0],"allowed":[0,1]}),
                    json!({"type":"true"})] {
                    for state in [0, 1, 4] {
                        let fast: nether::Feature = serde_json::from_value(json!({
                            "kind":"replace_blobs","target":target,"state":state,
                            "radius":{"type":"uniform","min_inclusive":0,"max_inclusive":7}})).unwrap();
                        profile.decorations[0].feature = Kind::NetherFeature { nether: fast.clone() };
                        let mut reference = fast.clone();
                        if let nether::Feature::ReplaceBlobs { target, .. } = &mut reference {
                            *target = Predicate::AllOf { predicates: vec![target.clone()] };
                        }
                        for seed in [0, 42, u64::MAX] {
                            for promote in [false, true] {
                                let pa = Prior::default();
                                let pb = Prior::default();
                                let fa = Field { prior: Some(&pa), origin_x: base.origin_x,
                                    origin_z: base.origin_z, side: base.side,
                                    columns: base.columns.clone(), substrate: None };
                                let fb = Field { prior: Some(&pb), origin_x: base.origin_x,
                                    origin_z: base.origin_z, side: base.side,
                                    columns: base.columns.clone(), substrate: None };
                                let mut oa = Overlay::for_recipes(&profile, request, origin, origin, &[0]);
                                let mut ob = Overlay::default();
                                oa.observe_storage(&pa);
                                let initial = oa.snapshot();
                                oa.record_feature();
                                ob.record_feature();
                                let probe = [origin * 16 + 7, 63, origin * 16 + 8];
                                // Force an owned entry even when the selected predicate places nothing.
                                // Air is an authoritative value, not an absent bitmap bit.
                                oa.write(&profile, probe, state);
                                ob.write(&profile, probe, state);
                                let mut a = Vec::new();
                                let mut b = Vec::new();
                                let mut ra = Rng::new(seed);
                                let mut rb = Rng::new(seed);
                                let samples = provider_noise::Samples::default();
                                let noise = provider_noise::Context::new(&samples);
                                for (index, at) in [[origin * 16 + 7, 70, origin * 16 + 8],
                                    [origin * 16 + 7, 70, origin * 16 + 8],
                                    [origin * 16 + 15, 63, origin * 16]].into_iter().enumerate() {
                                    let owned = if promote && index == 1 {
                                        let unexpected = if state == 0 { 1 } else { 0 };
                                        oa.write(&profile, probe, unexpected);
                                        ob.write(&profile, probe, unexpected);
                                        unexpected
                                    } else {
                                        ob.material(&fb, &profile, request, probe).unwrap()
                                    };
                                    let before = pa.queries.lock().unwrap().len();
                                    for wanted in [owned, if owned == 0 { 1 } else { 0 }] {
                                        assert_eq!(oa.material_matches(&fa, &profile, request, probe, wanted),
                                            ob.material(&fb, &profile, request, probe).map(|m| m == wanted));
                                    }
                                    assert_eq!(pa.queries.lock().unwrap().len(), before);
                                    let av = fast.place(&mut World { field: &fa, profile: &profile, request,
                                        overlay: &mut oa, blocks: &mut a, noise: &noise, deferred_ore: None },
                                        at, &mut ra);
                                    let bv = reference.place(&mut World { field: &fb, profile: &profile, request,
                                        overlay: &mut ob, blocks: &mut b, noise: &noise, deferred_ore: None },
                                        at, &mut rb);
                                    assert_eq!(av, bv);
                                    assert_eq!(ra.0, rb.0);
                                    assert_eq!(a, b);
                                    assert_eq!(entries(&oa), entries(&ob));
                                    assert_eq!(*pa.queries.lock().unwrap(), *pb.queries.lock().unwrap());
                                }
                                let equality = ordered && target["type"] == "material"
                                    && target["offset"] == json!([0,0,0])
                                    && target["allowed"].as_array().unwrap().len() == 1;
                                assert_eq!(pa.matching.load(Ordering::Relaxed) > 0, equality);
                                assert_eq!(pb.matching.load(Ordering::Relaxed), 0);
                                oa.observe_storage(&pa);
                                ob.observe_storage(&pb);
                                let observations = pa.storage.lock().unwrap();
                                if ordered && height <= 256 {
                                    assert_eq!(observations.len(), 2);
                                    assert_eq!(observations[0], (0, 0, false));
                                    assert!(observations[1].0 > 0);
                                    assert_eq!(observations[1].2, promote);
                                    if !promote { assert!(observations[1].1 > 0); }
                                } else {
                                    assert!(observations.is_empty());
                                }
                                assert!(pb.storage.lock().unwrap().is_empty());
                                same_state(&oa, &ob, &fa, &profile, request);
                                same_state(&initial, &oa.before_feature(), &fa, &profile, request);
                                same_state(&oa.before_feature(), &ob.before_feature(), &fa, &profile, request);
                                oa.finish_feature();
                                ob.finish_feature();
                                assert!(noise.missing().is_empty());
                            }
                        }
                    }
                }
            }
        }
    }
}

#[test]
fn live_writes_are_unchanged_by_recommit_including_repeats_air_ticks_and_undo() {
    for origin in [-17, 0, 16] {
        let (profile, field, mut request) = crate::decoration::replay_tests::fixture(42, origin);
        for (min_y, height) in [(-64, 256), (0, 128), (16, 80)] {
            request.min_y = min_y;
            request.height = height;
            let x = origin * 16 + 8;
            let z = origin * 16 + 8;
            let mut overlay = Overlay::default();
            overlay.write(&profile, [x, 63, z], 3);
            let initial = overlay.snapshot();
            let samples = provider_noise::Samples::default();
            let noise = provider_noise::Context::new(&samples);
            let mut blocks = Vec::new();
            overlay.record_feature();
            {
                let mut world = World { field: &field, profile: &profile, request,
                    overlay: &mut overlay, blocks: &mut blocks, noise: &noise, deferred_ore: None };
                for material in [4, 0, 5, 0, 1] {
                    for dx in -2..=2 {
                        for dz in -2..=2 {
                            world.write_tick([x + dx, 63 + dx, z + dz], material, 7);
                        }
                    }
                }
                let accepted = world.blocks.len();
                world.write([x, min_y - 1, z], 4);
                world.write([x, min_y + height as i32, z], 4);
                world.write([x + 1024, 63, z], 4);
                assert_eq!(world.blocks.len(), accepted);
            }
            same_state(&initial, &overlay.before_feature(), &field, &profile, request);
            overlay.finish_feature();
            assert!(blocks.iter().all(|b| b.role == FEATURE && b.upper == 0 && b.tick == 7));
            // Populate derived ground caches before the reference replay clears
            // them. The retained caches must describe the same final state.
            let mut replayed = overlay.snapshot();
            same_state(&overlay, &replayed, &field, &profile, request);
            replayed.commit(&blocks, 0, &field, &profile, request);
            same_state(&overlay, &replayed, &field, &profile, request);
        }
    }
}

#[test]
fn ore_recommit_is_redundant_for_live_and_deferred_commands() {
    let (profile, field, request) = crate::decoration::replay_tests::fixture(42, 0);
    for scattered in [false, true] {
        let feature: nether::Feature = serde_json::from_value(json!({
            "kind":"ore", "targets":[{"rule":{"kind":"always_true"},"state":4}],
            "size":33,"discard":0.0,"scattered":scattered,"heightmap":2,"air":[0]
        })).unwrap();
        assert!(feature.commits_overlay());
        for deferred in [None, Some((1, 4))] {
            for seed in [0, 42, u64::MAX] {
                let mut overlay = Overlay::default();
                let mut blocks = Vec::new();
                let samples = provider_noise::Samples::default();
                let noise = provider_noise::Context::new(&samples);
                feature.place(&mut World { field: &field, profile: &profile, request,
                    overlay: &mut overlay, blocks: &mut blocks, noise: &noise,
                    deferred_ore: deferred }, [8, 30, 8], &mut Rng::new(seed));
                let mut replayed = overlay.snapshot();
                replayed.commit(&blocks, 0, &field, &profile, request);
                same_state(&overlay, &replayed, &field, &profile, request);
                if deferred.is_some() {
                    assert!(entries(&overlay).is_empty());
                    assert!(blocks.iter().all(|b| b.role == DEFERRED_ORE));
                } else {
                    assert!(blocks.iter().all(|b| b.role == FEATURE));
                }
            }
        }
    }
}

#[test]
fn pillars_with_nested_caps_keep_normal_recommit() {
    let mut value = json!({"kind":"pillar", "provider":{"type":"state","material":4},
        "can_replace":{"type":"true"},"direction":1,"chance_to_continue":0.5,"cap":null});
    let bare: nether::Feature = serde_json::from_value(value.clone()).unwrap();
    assert!(bare.commits_overlay());
    value["cap"] = json!({"placement":[],"feature":{"kind":"nether_feature", "nether":{
        "kind":"replace_blobs","target":{"type":"true"},"state":4,"radius":1}}});
    let capped: nether::Feature = serde_json::from_value(value).unwrap();
    assert!(!capped.commits_overlay());
}
