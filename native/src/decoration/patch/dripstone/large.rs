use super::provider::FloatProvider;
use super::{Pos, Predicate, Rng, World, at_y, offset, scan};
use crate::decoration::placement::IntProvider;
use serde::Deserialize;

#[derive(Clone, Deserialize)]
pub struct Recipe {
    block: u16,
    open: Predicate,
    boundary: Predicate,
    replaceable: Predicate,
    embedded: Predicate,
    base_stone: Predicate,
    lava: Predicate,
    search: i32,
    radius: IntProvider,
    height_scale: FloatProvider,
    radius_ratio: f32,
    ceiling_bluntness: FloatProvider,
    floor_bluntness: FloatProvider,
    wind_speed: FloatProvider,
    wind_min_radius: i32,
    wind_min_bluntness: f32,
}

impl Recipe {
    pub(super) fn validate(&self, palette: usize) -> bool {
        self.block != 0
            && usize::from(self.block) < palette
            && [
                &self.open,
                &self.boundary,
                &self.replaceable,
                &self.embedded,
                &self.base_stone,
                &self.lava,
            ]
            .into_iter()
            .all(|p| p.validate(palette))
            && (1..=512).contains(&self.search)
            && self
                .radius
                .bounds()
                .is_ok_and(|(min, max)| min >= 1 && max <= 60)
            && self.height_scale.valid(0.0, 20.0)
            && (0.1..=1.0).contains(&self.radius_ratio)
            && self.ceiling_bluntness.valid(0.1, 10.0)
            && self.floor_bluntness.valid(0.1, 10.0)
            && self.wind_speed.valid(0.0, 2.0)
            && (0..=100).contains(&self.wind_min_radius)
            && (0.0..=5.0).contains(&self.wind_min_bluntness)
    }

    pub(super) fn place(&self, world: &mut World, at: Pos, rng: &mut Rng) -> bool {
        let Some((Some(floor), Some(ceiling))) =
            scan(world, at, self.search, &self.open, &self.boundary)
        else {
            return false;
        };
        let gap = ceiling - floor - 1;
        if gap < 4 {
            return false;
        }
        // Vanilla samples a uniform radius within the configured provider's bounds.
        let (min, max) = self.radius.bounds().expect("validated dripstone radius");
        let cap = ((gap as f32 * self.radius_ratio) as i32).clamp(min, max);
        let radius = rng.range([min, cap]);
        let mut hanging = Column {
            root: at_y(at, ceiling - 1),
            direction: -1,
            radius,
            bluntness: self.ceiling_bluntness.sample(rng),
            scale: self.height_scale.sample(rng),
        };
        let mut rising = Column {
            root: at_y(at, floor + 1),
            direction: 1,
            radius,
            bluntness: self.floor_bluntness.sample(rng),
            scale: self.height_scale.sample(rng),
        };
        let wind = if radius >= self.wind_min_radius
            && hanging.bluntness >= self.wind_min_bluntness
            && rising.bluntness >= self.wind_min_bluntness
        {
            let speed = self.wind_speed.sample(rng);
            let (sin, cos) = (std::f32::consts::PI * rng.unit() as f32).sin_cos();
            Wind {
                y: at[1],
                x: cos * speed,
                z: sin * speed,
            }
        } else {
            Wind {
                y: at[1],
                x: 0.0,
                z: 0.0,
            }
        };
        let place_hanging = hanging.anchor(world, self, &wind);
        let place_rising = rising.anchor(world, self, &wind);
        if place_hanging {
            hanging.place(world, self, &wind, rng);
        }
        if place_rising {
            rising.place(world, self, &wind, rng);
        }
        true
    }
}

struct Wind {
    y: i32,
    x: f32,
    z: f32,
}
impl Wind {
    fn shift(&self, pos: Pos) -> Pos {
        let delta = f64::from(self.y - pos[1]);
        let dx = (f64::from(self.x) * delta).floor() as i32;
        let dz = (f64::from(self.z) * delta).floor() as i32;
        [pos[0].saturating_add(dx), pos[1], pos[2].saturating_add(dz)]
    }
}

struct Column {
    root: Pos,
    direction: i32,
    radius: i32,
    bluntness: f32,
    scale: f32,
}

impl Column {
    fn height(&self, radial: f32) -> i32 {
        let x = f64::from(radial.max(self.bluntness)) / f64::from(self.radius) * 0.384;
        let shape = 0.75 * x.powf(4.0 / 3.0) - x.powf(2.0 / 3.0) - x.ln() / 3.0;
        (f64::from(self.scale) * shape.max(0.0) / 0.384 * f64::from(self.radius)) as i32
    }

    fn anchor(&mut self, world: &World, recipe: &Recipe, wind: &Wind) -> bool {
        while self.radius > 1 {
            for i in 0..self.height(0.0).min(10) {
                let pos = offset(self.root, [0, -self.direction * i, 0]);
                if world.test(&recipe.lava, pos) {
                    return false;
                }
                if self.is_embedded(world, &recipe.embedded, wind.shift(pos)) {
                    self.root = pos;
                    return true;
                }
            }
            self.radius /= 2;
        }
        false
    }

    fn is_embedded(&self, world: &World, embedded: &Predicate, pos: Pos) -> bool {
        if !world.test(embedded, pos) {
            return false;
        }
        let radius = self.radius as f32;
        let step = 6.0 / radius;
        let mut theta = 0.0_f32;
        while theta < std::f32::consts::TAU {
            let (sin, cos) = theta.sin_cos();
            let point = offset(pos, [(cos * radius) as i32, 0, (sin * radius) as i32]);
            if !world.test(embedded, point) {
                return false;
            }
            theta += step;
        }
        true
    }

    fn place(&self, world: &mut World, recipe: &Recipe, wind: &Wind, rng: &mut Rng) {
        for x in -self.radius..=self.radius {
            for z in -self.radius..=self.radius {
                let radial = ((x * x + z * z) as f32).sqrt();
                if radial > self.radius as f32 {
                    continue;
                }
                let mut height = self.height(radial);
                if height <= 0 {
                    continue;
                }
                if rng.unit() < 0.2 {
                    height = (height as f32 * (0.8 + 0.2 * rng.unit() as f32)) as i32;
                }
                let mut pos = offset(self.root, [x, 0, z]);
                let min = world.request.min_y;
                let max = if self.direction > 0 {
                    let Some(surface) = world.overlay.height(
                        world.field,
                        world.profile,
                        world.request,
                        pos[0],
                        pos[2],
                        0,
                    ) else {
                        continue;
                    };
                    surface
                } else {
                    min + world.request.height as i32
                };
                let mut placed = false;
                for _ in 0..height {
                    if pos[1] < min || pos[1] >= max {
                        break;
                    }
                    let shifted = wind.shift(pos);
                    if world.test(&recipe.replaceable, shifted) {
                        placed = true;
                        world.write(shifted, recipe.block);
                    } else if placed && world.test(&recipe.base_stone, shifted) {
                        break;
                    }
                    pos[1] += self.direction;
                }
            }
        }
    }
}
