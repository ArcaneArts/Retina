use super::provider::{FloatProvider, normal};
use super::{HORIZONTAL, Pos, Predicate, Rng, Spikes, World, at_y, offset, scan};
use crate::decoration::placement::IntProvider;
use serde::Deserialize;

#[derive(Clone, Deserialize)]
pub struct Recipe {
    open: Predicate,
    closed: Predicate,
    lava: Predicate,
    water: u16,
    pool_target: Predicate,
    pool_water_above: Predicate,
    pool_edge: Predicate,
    search: i32,
    height: IntProvider,
    radius: IntProvider,
    height_difference: i32,
    height_deviation: i32,
    layer_thickness: IntProvider,
    density: FloatProvider,
    wetness: FloatProvider,
    edge_chance: f32,
    edge_distance: i32,
    height_bias_distance: i32,
}

impl Recipe {
    pub(super) fn validate(&self, palette: usize) -> bool {
        self.water != 0
            && usize::from(self.water) < palette
            && [
                &self.open,
                &self.closed,
                &self.lava,
                &self.pool_target,
                &self.pool_water_above,
                &self.pool_edge,
            ]
            .into_iter()
            .all(|p| p.validate(palette))
            && (1..=512).contains(&self.search)
            && self
                .height
                .bounds()
                .is_ok_and(|(min, max)| min >= 1 && max <= 128)
            && self
                .radius
                .bounds()
                .is_ok_and(|(min, max)| min >= 1 && max <= 128)
            && self
                .layer_thickness
                .bounds()
                .is_ok_and(|(min, max)| min >= 0 && max <= 128)
            && (0..=64).contains(&self.height_difference)
            && (1..=64).contains(&self.height_deviation)
            && (1..=64).contains(&self.edge_distance)
            && (1..=64).contains(&self.height_bias_distance)
            && self.density.valid(0.0, 2.0)
            && self.wetness.valid(0.0, 2.0)
            && (0.0..=1.0).contains(&self.edge_chance)
    }

    pub(super) fn place(&self, world: &mut World, at: Pos, rng: &mut Rng, spikes: &Spikes) -> bool {
        if !world.test(&self.open, at) {
            return false;
        }
        let height = self.height.sample(rng);
        let wetness = self.wetness.sample(rng);
        let density = self.density.sample(rng);
        let [rx, rz] = [self.radius.sample(rng), self.radius.sample(rng)];
        for x in -rx..=rx {
            for z in -rz..=rz {
                let edge = (rx - x.abs()).min(rz - z.abs());
                let fraction = (edge as f64 / f64::from(self.edge_distance)).min(1.0);
                let chance =
                    f64::from(self.edge_chance) + (1.0 - f64::from(self.edge_chance)) * fraction;
                let column = offset(at, [x, 0, z]);
                self.place_column(
                    world,
                    column,
                    rng,
                    spikes,
                    x.abs() + z.abs(),
                    height,
                    wetness,
                    density,
                    chance,
                );
            }
        }
        true
    }

    #[allow(clippy::too_many_arguments)]
    fn place_column(
        &self,
        world: &mut World,
        at: Pos,
        rng: &mut Rng,
        spikes: &Spikes,
        distance: i32,
        max_height: i32,
        wetness: f32,
        density: f32,
        chance: f64,
    ) {
        let Some((mut floor, ceiling)) = scan(world, at, self.search, &self.open, &self.closed)
        else {
            return;
        };
        if floor.is_none() && ceiling.is_none() {
            return;
        }
        if rng.unit() < f64::from(wetness)
            && let Some(y) = floor
            && self.can_pool(world, at_y(at, y))
        {
            world.write(at_y(at, y), self.water);
            floor = Some(y - 1);
        }
        let ceiling_grows = rng.unit() < chance;
        let mut hanging = if let Some(y) = ceiling
            && ceiling_grows
            && !world.test(&self.lava, at_y(at, y))
        {
            self.replace_support(world, at_y(at, y), 1, rng, spikes);
            let limit = floor.map_or(max_height, |floor| max_height.min(y - floor));
            self.height_at(rng, distance, limit, density)
        } else {
            0
        };
        let floor_grows = rng.unit() < chance;
        let mut rising = if let Some(y) = floor
            && floor_grows
            && !world.test(&self.lava, at_y(at, y))
        {
            self.replace_support(world, at_y(at, y), -1, rng, spikes);
            if ceiling.is_some() {
                (hanging + rng.range([-self.height_difference, self.height_difference])).max(0)
            } else {
                self.height_at(rng, distance, max_height, density)
            }
        } else {
            0
        };
        let cave_height = floor.zip(ceiling).map(|(low, high)| high - low - 1);
        if let Some((low, high)) = floor.zip(ceiling)
            && high - hanging <= low + rising
        {
            let first = (high - hanging).max(low + 1);
            let last = (low + rising).min(high - 1) + 1;
            let split = rng.range([first, last]);
            hanging = high - split;
            rising = split - 1 - low;
        }
        let merge =
            rng.below(2) != 0 && hanging > 0 && rising > 0 && cave_height == Some(hanging + rising);
        if let Some(y) = ceiling {
            spikes.grow(world, at_y(at, y - 1), -1, hanging, merge);
        }
        if let Some(y) = floor {
            spikes.grow(world, at_y(at, y + 1), 1, rising, merge);
        }
    }

    fn height_at(&self, rng: &mut Rng, distance: i32, limit: i32, density: f32) -> i32 {
        if rng.unit() > f64::from(density) {
            return 0;
        }
        let bias = 1.0 - (distance as f64 / f64::from(self.height_bias_distance)).min(1.0);
        normal(
            rng,
            (f64::from(limit) * 0.5 * bias) as f32,
            self.height_deviation as f32,
            0.0,
            limit as f32,
        ) as i32
    }

    fn can_pool(&self, world: &World, at: Pos) -> bool {
        world.test(&self.pool_target, at)
            && !world.test(&self.pool_water_above, at)
            && world.test(&self.pool_edge, offset(at, [0, -1, 0]))
            && HORIZONTAL
                .iter()
                .all(|&side| world.test(&self.pool_edge, offset(at, side)))
    }

    fn replace_support(
        &self,
        world: &mut World,
        at: Pos,
        direction: i32,
        rng: &mut Rng,
        spikes: &Spikes,
    ) {
        for i in 0..self.layer_thickness.sample(rng) {
            if !spikes.replace(world, offset(at, [0, i * direction, 0])) {
                break;
            }
        }
    }
}
