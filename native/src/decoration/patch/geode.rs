//! Layered geodes sampled against the same live overlay as their placement.
use super::{Predicate, Provider, Rng, World, placement::IntProvider};
use crate::{decoration::provider_noise, structures::Random};
use serde::Deserialize;

type Pos = [i32; 3];
const DIRECTIONS: [Pos; 6] = [
    [0, -1, 0], [0, 1, 0], [0, 0, -1], [0, 0, 1], [-1, 0, 0], [1, 0, 0],
];

#[derive(Clone, Deserialize)]
pub struct Recipe {
    offsets: [i32; 2],
    point_count: IntProvider,
    wall_distance: IntProvider,
    point_offset: IntProvider,
    noise_multiplier: f64,
    invalid_threshold: usize,
    layers: [f64; 4],
    crack_chance: f64,
    crack_size: f64,
    crack_offset: u32,
    alternate_chance: f64,
    placement_chance: f64,
    require_alternate: bool,
    filling: Provider,
    inner: Provider,
    alternate: Provider,
    middle: Provider,
    outer: Provider,
    air: u16,
    replaceable: Predicate,
    invalid: Predicate,
    grow_at: Predicate,
    source_fluid: Predicate,
    placements: Vec<[[u16; 2]; 6]>,
}

fn offset([x, y, z]: Pos, [dx, dy, dz]: Pos) -> Pos {
    [x + dx, y + dy, z + dz]
}

fn potential([x, y, z]: Pos, [px, py, pz]: Pos, distance_bias: u32) -> f64 {
    let [dx, dy, dz] = [x - px, y - py, z - pz].map(f64::from);
    (dx * dx + dy * dy + dz * dz + f64::from(distance_bias)).sqrt().recip()
}

impl Recipe {
    pub(in crate::decoration) fn reach(&self) -> i32 {
        self.offsets[0].abs().max(self.offsets[1].abs()).saturating_add(1)
            .max(self.wall_distance.bounds().map_or(0, |(_, max)| max))
    }

    pub(in crate::decoration) fn validate(&self, palette: usize) -> bool {
        let [lo, hi] = self.offsets;
        (-32..=32).contains(&lo) && (lo..=32).contains(&hi)
            && self.point_count.bounds().is_ok_and(|(a, b)| a >= 1 && b <= 20)
            && self.wall_distance.bounds().is_ok_and(|(a, b)| a >= 1 && b <= 20)
            && self.point_offset.bounds().is_ok_and(|(a, b)| a >= 0 && b <= 20)
            && self.noise_multiplier.is_finite()
            && self.layers.iter().all(|&v| v.is_finite() && v > 0.0)
            && self.crack_size.is_finite() && self.crack_size >= 0.0
            && [self.crack_chance, self.alternate_chance, self.placement_chance]
                .into_iter().all(|v| (0.0..=1.0).contains(&v))
            && [&self.filling, &self.inner, &self.alternate, &self.middle, &self.outer]
                .into_iter().all(|p| p.validate(palette))
            && [&self.replaceable, &self.invalid, &self.grow_at, &self.source_fluid]
                .into_iter().all(|p| p.validate(palette))
            && usize::from(self.air) < palette
            && self.placements.iter().flatten().flatten().all(|&id| usize::from(id) < palette)
    }

    pub(super) fn place(&self, world: &mut World, at: Pos, rng: &mut Rng) -> bool {
        let count = self.point_count.sample(rng);
        let wall_max = self.wall_distance.bounds().unwrap().1;
        let bias = f64::from(count) / f64::from(wall_max);
        let [filling, inner, middle, outer] = std::array::from_fn(|i| {
            (self.layers[i] + if i == 0 { 0.0 } else { bias }).sqrt().recip()
        });
        let crack_threshold = (self.crack_size + rng.unit() * 0.5
            + if count > 3 { bias } else { 0.0 }).sqrt().recip();
        let cracked = rng.unit() < self.crack_chance;
        let mut points = Vec::with_capacity(count as usize);
        let mut invalid = 0;
        for _ in 0..count {
            let p = offset(at, std::array::from_fn(|_| self.wall_distance.sample(rng)));
            if world.test(&self.invalid, p) {
                invalid += 1;
                if invalid > self.invalid_threshold {
                    return false;
                }
            }
            points.push((p, self.point_offset.sample(rng) as u32));
        }
        let crack = cracked.then(|| {
            let edge = count * 2 + 1;
            let (x, z) = [(edge, 0), (0, edge), (edge, edge), (0, 0)][rng.below(4) as usize];
            [7, 5, 1].map(|y| offset(at, [x, y, z]))
        });
        let noise = (self.noise_multiplier != 0.0).then(|| noise(world.request.seed));
        let mut growth = Vec::new();
        let [lo, hi] = self.offsets;
        for z in lo..=hi {
            for y in lo..=hi {
                for x in lo..=hi {
                    let p = offset(at, [x, y, z]);
                    let perturbation = noise.as_ref().map_or(0.0, |n|
                        f64::from(n.sample(p)) * self.noise_multiplier);
                    let strength: f64 = points.iter().map(|&(center, bias)|
                        potential(p, center, bias) + perturbation).sum();
                    if strength < outer {
                        continue;
                    }
                    if strength < filling && crack.as_ref().is_some_and(|line|
                        line.iter().map(|&center| potential(p, center, self.crack_offset) + perturbation)
                            .sum::<f64>() >= crack_threshold)
                    {
                        if world.test(&self.replaceable, p) {
                            world.write(p, self.air);
                        }
                        for side in DIRECTIONS {
                            world.schedule_fluid(offset(p, side));
                        }
                        continue;
                    }
                    let inner_layer = strength < filling && strength >= inner;
                    let alternate = inner_layer && rng.unit() < self.alternate_chance;
                    let provider = if strength >= filling {
                        &self.filling
                    } else if inner_layer {
                        if alternate { &self.alternate } else { &self.inner }
                    } else if strength >= middle {
                        &self.middle
                    } else {
                        &self.outer
                    };
                    if let Some(id) = provider.sample_optional(rng, p, &|p| world.material(p), world.noise) {
                        if world.test(&self.replaceable, p) {
                            world.write(p, id);
                        }
                    }
                    if inner_layer && (!self.require_alternate || alternate) && rng.unit() < self.placement_chance {
                        growth.push(p);
                    }
                }
            }
        }
        if !self.placements.is_empty() {
            for source in growth {
                let states = &self.placements[rng.below(self.placements.len() as i32) as usize];
                for (side, variants) in DIRECTIONS.into_iter().zip(states) {
                    let target = offset(source, side);
                    if world.test(&self.grow_at, target) {
                        if world.test(&self.replaceable, target) {
                            let waterlogged = usize::from(world.test(&self.source_fluid, target));
                            world.write(target, variants[waterlogged]);
                        }
                        break;
                    }
                }
            }
        }
        true
    }
}

fn noise(seed: u64) -> provider_noise::Program {
    // GeodeFeature uses the single octave at -4 with a legacy positional fork.
    let salt = b"octave_-4".iter().fold(0i32, |h, &b| h.wrapping_mul(31).wrapping_add(i32::from(b))) as i64 as u64;
    let mut world = Random::new(seed);
    let layers = [0.0625, 0.0625 * 1.0181268882175227].map(|frequency| {
        let mut rng = Random::new(world.long() ^ salt);
        let offsets = std::array::from_fn(|_| rng.double() * 256.0);
        let mut permutation: Vec<u32> = (0..256).collect();
        for i in 0..256 {
            permutation.swap(i, i + rng.int((256 - i) as u32) as usize);
        }
        provider_noise::Layer { frequency, amplitude: 5.0 / 6.0, offsets, permutation }
    });
    provider_noise::Program { layers: layers.into(), coordinate_scale: None }
}
