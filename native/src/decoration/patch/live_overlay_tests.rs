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
