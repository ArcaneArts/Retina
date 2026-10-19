use super::*;
use std::time::Instant;

fn fixture(origin: i32) -> (WorldProfile, Field<'static>, ChunkRequest, Vec<Recipe>) {
    let (mut profile, mut field, mut request) = replay_tests::fixture(123456789, origin);
    request.height = 384;
    field.origin_x = origin - 2;
    field.origin_z = origin - 2;
    field.side = 6;
    field.columns.resize(36 * COLUMNS, field.columns[0]);
    for i in 0..6 {
        profile
            .materials
            .push(serde_json::json!(format!("test:leaf_{i}")));
        profile.material_flags.push(4);
        profile.heightmap_masks.push(31);
    }
    let mut recipes = Vec::new();
    for (trunk, foliage, h, r) in [
        ("straight", "blob", 6, 2),
        ("fancy", "fancy", 16, 3),
        ("mega_jungle", "jungle", 26, 3),
        ("dark_oak", "dark_oak", 8, 0),
        ("giant", "mega_pine", 25, 4),
        ("forking", "acacia", 8, 3),
    ] {
        let mut recipe = profile.decorations[0].clone();
        if let Kind::Tree {
            trunk_shape,
            foliage_shape,
            height,
            radius,
            foliage_height,
            leaves,
            decorators,
            ..
        } = &mut recipe.feature
        {
            *trunk_shape = format!("{trunk}_trunk_placer");
            *foliage_shape = format!("{foliage}_foliage_placer");
            *height = [h, 3, 2];
            *radius = [r, r];
            *foliage_height = [4, 8];
            leaves[0].distances = [9, 10, 11, 12, 13, 14];
            if trunk == "mega_jungle" {
                *decorators = vec![
                    TreeDecorator {
                        kind: "trunk_vine".into(),
                        probability: 0.2,
                        states: vec![6; 4],
                    },
                    TreeDecorator {
                        kind: "leaf_vine".into(),
                        probability: 0.3,
                        states: vec![6; 4],
                    },
                    TreeDecorator {
                        kind: "cocoa".into(),
                        probability: 1.0,
                        states: vec![8; 12],
                    },
                ];
            }
        }
        recipe.validate(profile.materials.len()).unwrap();
        recipes.push(recipe);
    }
    (profile, field, request, recipes)
}
fn exact(blocks: &[WorldBlock]) -> Vec<(i32, i32, i32, u16, u16, u8)> {
    blocks
        .iter()
        .map(|b| (b.x, b.y, b.z, b.material, b.upper, b.role))
        .collect()
}

#[test]
fn reused_tree_scratch_preserves_commands_rng_and_bounds() {
    let mut scratch = TreeScratch::default();
    for origin in [0, -33, 700000] {
        let (profile, field, request, recipes) = fixture(origin);
        for seed in 0..48 {
            let recipe = &recipes[seed as usize % recipes.len()];
            let x = origin * 16 + if seed % 3 == 0 { -29 } else { 8 };
            let z = origin * 16 + if seed % 5 == 0 { 59 } else { 8 };
            let mut expected = Vec::new();
            let mut actual = Vec::new();
            let mut a = Rng::new(seed);
            let mut b = Rng::new(seed);
            tree(
                &field,
                &profile,
                request,
                recipe,
                x,
                z,
                &mut a,
                &mut expected,
                None,
                None,
                None,
                &mut TreeScratch::default(),
            );
            tree(
                &field,
                &profile,
                request,
                recipe,
                x,
                z,
                &mut b,
                &mut actual,
                None,
                None,
                None,
                &mut scratch,
            );
            assert_eq!(exact(&actual), exact(&expected));
            assert_eq!(a.next(), b.next(), "scratch must not affect decorator RNG");
            assert!(scratch.retained_bytes() <= TreeScratch::RETAIN_LIMIT);
        }
    }
    // An oversized custom crown may allocate transiently, but must not pin its
    // buffers to every later small tree in the worker job.
    scratch.leaves.reserve(TreeScratch::RETAIN_LIMIT / 12 + 1);
    assert!(scratch.retained_bytes() > TreeScratch::RETAIN_LIMIT);
    scratch.recycle();
    assert_eq!(scratch.retained_bytes(), 0);
}

#[test]
fn reused_leaf_grid_clears_dense_sparse_empty_and_log_states() {
    let mut grid = LeafOccupancy::default();
    let near = [(1, 2, 3), (1, 2, 3), (1, 2, 4), (2, 3, 4)];
    let far = [
        (i32::MIN, i32::MIN, i32::MIN),
        (i32::MAX, i32::MAX, i32::MAX),
    ];
    for points in [&near[..], &far[..], &[], &far[..], &near[..], &near[..]] {
        grid.reset(points);
        let mut fresh = LeafOccupancy::new(points);
        for &p in points {
            assert_eq!(grid.take_leaf(p), fresh.take_leaf(p));
        }
        grid.reset(points);
        if let Some(&p) = points.first() {
            grid.mark_log(p);
            assert!(!grid.take_leaf(p));
            grid.reset(points);
            assert!(grid.take_leaf(p), "log state leaked across trees");
        }
    }
}

#[test]
#[ignore = "bounded tree scratch A/B: five warmups, 21 alternating pairs, no GPU; use a 30s deadline"]
fn tree_scratch_benchmark() {
    let (profile, field, request, recipes) = fixture(0);
    for (label, selected) in [
        ("oak", vec![0]),
        ("jungle", vec![2]),
        ("mixed", (0..recipes.len()).collect()),
    ] {
        let mut times: [Vec<f64>; 2] = Default::default();
        let mut retained = 0;
        // Both paths create one scratch per batch. The reference drops it after
        // each tree; the candidate retains only the bounded temporary buffers.
        for pair in 0..26 {
            let mut outputs = Vec::new();
            for step in 0..2 {
                let reuse = (pair + step) % 2 == 1;
                let mut scratch = TreeScratch::default();
                let mut blocks = Vec::new();
                let mut rng_tail = 0;
                let start = Instant::now();
                for i in 0..64 {
                    let recipe = &recipes[selected[i % selected.len()]];
                    let mut rng = Rng::new(1000 + i as u64);
                    tree(
                        &field,
                        &profile,
                        request,
                        recipe,
                        8,
                        8,
                        &mut rng,
                        &mut blocks,
                        None,
                        None,
                        None,
                        &mut scratch,
                    );
                    rng_tail ^= rng.next();
                    if !reuse {
                        scratch = TreeScratch::default();
                    }
                }
                let ms = start.elapsed().as_secs_f64() * 1000.;
                if pair >= 5 {
                    times[usize::from(reuse)].push(ms);
                }
                if reuse {
                    retained = scratch.retained_bytes();
                }
                outputs.push((exact(&blocks), rng_tail));
            }
            assert_eq!(outputs[0], outputs[1]);
        }
        println!(
            "{}",
            serde_json::json!({"case":label,"trees_per_batch":64,"warmups":5,"pairs":21,"fresh_ms":times[0],"reused_ms":times[1],"retained_bytes":retained,"equal_commands_and_rng":true})
        );
    }
}
