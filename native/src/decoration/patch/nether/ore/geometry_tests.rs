//! Differential oracle: the complete vein replay from main 0945d3c.
use super::*;

impl Ore {
    fn reference_vein(&self, w: &mut World, at: [i32; 3], rng: &mut Rng) -> bool {
        let vein = Vein::new(self.size, at, rng);
        let (start, width, height) = (vein.start, vein.width, vein.height);
        let reaches_terrain = (start[0]..=start[0] + width).any(|x| {
            (start[2]..=start[2] + width).any(|z| {
                w.overlay
                    .height(w.field, w.profile, w.request, x, z, self.heightmap)
                    .is_some_and(|h| start[1] <= h)
            })
        });
        if !reaches_terrain {
            return false;
        }

        let mut spheres: Vec<_> = vein.spheres(rng).collect();
        for i in 0..spheres.len() {
            if spheres[i].1 <= 0.0 {
                continue;
            }
            for j in i + 1..spheres.len() {
                if spheres[j].1 <= 0.0 {
                    continue;
                }
                let d =
                    std::array::from_fn::<_, 3, _>(|axis| spheres[i].0[axis] - spheres[j].0[axis]);
                let dr = spheres[i].1 - spheres[j].1;
                if dr * dr > d[0] * d[0] + d[1] * d[1] + d[2] * d[2] {
                    if dr > 0.0 {
                        spheres[j].1 = -1.0;
                    } else {
                        spheres[i].1 = -1.0;
                    }
                }
            }
        }
        let mut tested = vec![0u64; ((width * height * width) as usize).div_ceil(64)];
        let mut placed = false;
        for (center, radius) in spheres {
            if radius < 0.0 {
                continue;
            }
            let (min, max) = vein.bounds(center, radius);
            for x in min[0]..=max[0] {
                let xd = (x as f64 + 0.5 - center[0]) / radius;
                if xd * xd >= 1.0 {
                    continue;
                }
                for y in min[1]..=max[1] {
                    let yd = (y as f64 + 0.5 - center[1]) / radius;
                    if xd * xd + yd * yd >= 1.0 {
                        continue;
                    }
                    for z in min[2]..=max[2] {
                        let zd = (z as f64 + 0.5 - center[2]) / radius;
                        if xd * xd + yd * yd + zd * zd >= 1.0
                            || y < w.request.min_y
                            || y >= w.request.min_y + w.request.height as i32
                        {
                            continue;
                        }
                        let index = (x - start[0]
                            + (y - start[1]) * width
                            + (z - start[2]) * width * height)
                            as usize;
                        let word = index / 64;
                        if word >= tested.len() {
                            tested.resize(word + 1, 0);
                        }
                        let bit = 1u64 << (index % 64);
                        if tested[word] & bit != 0 {
                            continue;
                        }
                        tested[word] |= bit;
                        placed |= self.replace(w, [x, y, z], rng);
                    }
                }
            }
        }
        placed
    }
}

use crate::decoration::{WorldBlock, placement::Overlay, provider_noise, replay_tests};

fn replay(
    ore: &Ore,
    profile: &WorldProfile,
    field: &Field,
    request: ChunkRequest,
    seed: u64,
    positions: &[[i32; 3]],
    deferred: bool,
    reference: bool,
) -> (Vec<WorldBlock>, Vec<bool>, u64) {
    let mut overlay = Overlay::default();
    let mut blocks = Vec::new();
    let samples = provider_noise::Samples::default();
    let noise = provider_noise::Context::new(&samples);
    let mut world = World {
        field,
        profile,
        request,
        overlay: &mut overlay,
        blocks: &mut blocks,
        noise: &noise,
        deferred_ore: deferred.then_some((1, 4)),
    };
    // Provisional writes must remain visible to subsequent live replacement.
    if !deferred {
        world.write(positions[0], 0);
        world.write(add(positions[0], [1, 0, 0]), 4);
    }
    let mut rng = Rng::new(seed);
    let result = positions
        .iter()
        .map(|&at| {
            if reference {
                ore.reference_vein(&mut world, at, &mut rng)
            } else {
                ore.vein(&mut world, at, &mut rng)
            }
        })
        .collect();
    (blocks, result, rng.0)
}

fn recipe(size: i32, mode: usize) -> Ore {
    let targets = match mode {
        0 => serde_json::json!([{"rule":{"kind":"material","allowed":[1]},"state":4}]),
        1 => serde_json::json!([
            {"rule":{"kind":"all_of","rules":[
                {"kind":"material","allowed":[1,3,4],"chance":0.57},
                {"kind":"height_match","min":-63,"max":300}]},"state":4},
            {"rule":{"kind":"any_of","rules":[
                {"kind":"material","allowed":[1],"chance":0.31},
                {"kind":"not","rule":{"kind":"material","allowed":[0,4]}}]},"state":3}]),
        _ => serde_json::json!([{"rule":{"kind":"always_true"},"state":4}]),
    };
    serde_json::from_value(serde_json::json!({
        "targets":targets,"size":size,"discard":if mode==1 {0.6} else {0.0},
        "scattered":false,"heightmap":2,"air":[0]
    }))
    .unwrap()
}

#[test]
fn visited_rows_keep_ordered_writes_live_reads_and_random_state() {
    let mut comparisons = 0;
    let mut emitted = 0;
    for origin in [-1_875_000, -2, 0, 1_875_000] {
        let (profile, mut field, mut request) = replay_tests::fixture(42, origin);
        for (min_y, height) in [(-64, 128), (0, 256), (240, 512)] {
            request.min_y = min_y;
            request.height = height;
            let surface = min_y + height as i32 / 2;
            for column in &mut field.columns {
                column.height = surface;
            }
            let x = origin * 16;
            let positions = [
                [x + 15, surface - 2, x + 8],
                [x + 16, surface - 2, x + 8], // overlapping vein and chunk boundary
                [x + 15, min_y, x + 8],
                [x + 15, min_y - 1, x + 8],
                [x + 15, min_y + height as i32, x + 8], // terrain rejection
                [x - 16, surface - 1, x - 16],          // edge of available field
            ];
            for size in 0..=64 {
                for mode in 0..3 {
                    let ore = recipe(size, mode);
                    assert!(ore.validate(profile.materials.len()));
                    for seed in [0, 1, 42, 123456789] {
                        for deferred in [false, true] {
                            if deferred && mode != 0 {
                                continue;
                            }
                            let a = replay(
                                &ore, &profile, &field, request, seed, &positions, deferred, true,
                            );
                            let b = replay(
                                &ore, &profile, &field, request, seed, &positions, deferred, false,
                            );
                            emitted += a.0.len();
                            assert_eq!(
                                a, b,
                                "origin={origin} min_y={min_y} height={height} size={size} mode={mode} seed={seed} deferred={deferred}"
                            );
                            comparisons += 1;
                        }
                    }
                }
            }
        }
    }
    assert_eq!(comparisons, 12_480);
    assert!(emitted > 100_000);
    eprintln!(
        "visited-row oracle: {comparisons} ordered replay comparisons; {emitted} original writes including seeded overlays"
    );
}

#[test]
#[ignore = "paired CPU benchmark; run alone with --nocapture and --test-threads=1"]
fn ore_visited_rows_benchmark() {
    use std::{hint::black_box, time::Instant};
    let (profile, field, request) = replay_tests::fixture(42, 0);
    let mut reports = Vec::new();
    for (size, deferred) in [
        (8, true),
        (10, true),
        (12, true),
        (14, true),
        (16, true),
        (33, true),
        (64, true),
        (33, false),
        (64, false),
    ] {
        let ore = recipe(size, usize::from(!deferred));
        let run = |reference| {
            let start = Instant::now();
            let mut count = 0usize;
            let mut state = 0;
            for seed in 0..256 {
                let (blocks, placed, rng) = replay(
                    black_box(&ore),
                    &profile,
                    &field,
                    request,
                    seed,
                    &[[15, 30, 8], [16, 30, 8]],
                    deferred,
                    reference,
                );
                count += black_box(blocks.len()) + placed.iter().filter(|&&v| v).count();
                state ^= rng;
            }
            (start.elapsed().as_nanos() as u64, count, state)
        };
        for _ in 0..5 {
            black_box(run(true));
            black_box(run(false));
        }
        let mut pairs = Vec::new();
        for iteration in 0..25 {
            let (a, b) = if iteration % 2 == 0 {
                (run(true), run(false))
            } else {
                let b = run(false);
                (run(true), b)
            };
            assert_eq!((a.1, a.2), (b.1, b.2));
            pairs.push(serde_json::json!({"iteration":iteration,"reference_ns":a.0,"candidate_ns":b.0,"writes_and_placed":a.1,"rng_xor":a.2}));
        }
        let report = serde_json::json!({"size":size,"deferred":deferred,"warmups":5,"iterations":25,"veins_per_batch":512,"pairs":pairs,"timing_acceptance":false});
        eprintln!("ORE_VISITED_ROWS_BENCH {report}");
        reports.push(report);
    }
    if let Ok(path) = std::env::var("RETINA_ORE_GEOMETRY_REPORT") {
        std::fs::write(path, serde_json::to_vec_pretty(&reports).unwrap()).unwrap();
    }
}

fn assert_row_bounds(vein: &Vein, center: [f64; 3], radius: f64) {
    let (min, max) = vein.bounds(center, radius);
    for (axis, extent) in [vein.width, vein.height, vein.width]
        .into_iter()
        .enumerate()
    {
        assert!(min[axis] >= vein.start[axis]);
        assert!(max[axis] <= vein.start[axis] + extent);
    }
    let stride = vein.width * vein.height;
    let row_max = max[0] - vein.start[0] + (max[1] - vein.start[1]) * vein.width;
    assert!(row_max < stride);
    assert!(max[2] - vein.start[2] < 32);
    assert!(max[2] - min[2] < 32);
    // Reindexing must preserve the original flattened bitmap's identities,
    // including X at an inclusive rounded endpoint mapping into the next row.
    for x in [min[0], max[0]] {
        for y in [min[1], max[1]] {
            for z in [min[2], max[2]] {
                let row = x - vein.start[0] + (y - vein.start[1]) * vein.width;
                let index = row + (z - vein.start[2]) * stride;
                assert_eq!(index % stride, row);
                assert_eq!(index / stride, z - vein.start[2]);
            }
        }
    }
}

#[test]
fn validated_sphere_bounds_fit_the_visited_rows() {
    for size in 0..=64 {
        for origin in [-30_000_000, -16, 0, 30_000_000] {
            for seed in 0..256 {
                let mut rng = Rng::new(seed);
                let vein = Vein::new(size, [origin, -64, -origin], &mut rng);
                assert!(vein.width <= 26);
                for (center, radius) in vein.spheres(&mut rng) {
                    assert_row_bounds(&vein, center, radius);
                }
                if seed == 0 {
                    // Exercise the limiting radius even if no sampled draw
                    // reaches it, including a floating-point rounded integer.
                    let spread = size as f64 / 8.0;
                    let radius = size as f64 / 16.0 + 0.5;
                    for dx in [-spread, spread] {
                        for dy in [-2.0, 0.0] {
                            for dz in [-spread, spread] {
                                assert_row_bounds(
                                    &vein,
                                    [origin as f64 + dx, -64.0 + dy, -origin as f64 + dz],
                                    radius,
                                );
                            }
                        }
                    }
                }
            }
        }
    }
}
