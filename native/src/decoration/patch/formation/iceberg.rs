//! Sea-level iceberg volumes, smoothing and hollowing.
use super::{HORIZONTAL, Pos, Predicate, Rng, World, offset};
use serde::Deserialize;

#[derive(Clone, Deserialize)]
pub struct Recipe {
    block: u16,
    snow_block: u16,
    water_block: u16,
    air_block: u16,
    air: Predicate,
    water: Predicate,
    ice: Predicate,
    snow_layer: Predicate,
    replaceable: Predicate,
}

impl Recipe {
    pub(super) fn validate(&self, palette: usize) -> bool {
        [
            self.block,
            self.snow_block,
            self.water_block,
            self.air_block,
        ]
        .into_iter()
        .all(|id| usize::from(id) < palette)
            && [
                &self.air,
                &self.water,
                &self.ice,
                &self.snow_layer,
                &self.replaceable,
            ]
            .into_iter()
            .all(|predicate| predicate.validate(palette))
    }

    pub(super) fn place(&self, world: &mut World, mut origin: Pos, rng: &mut Rng) -> bool {
        origin[1] = world.profile.sea_level;
        let snow_cap = rng.unit() > 0.7;
        let angle = rng.unit() * std::f64::consts::TAU;
        let major = 11 - rng.below(5);
        let minor = 3 + rng.below(3);
        let elliptical = rng.unit() > 0.7;
        let mut height = if elliptical {
            6 + rng.below(6)
        } else {
            3 + rng.below(15)
        };
        if !elliptical && rng.unit() > 0.9 {
            height += 7 + rng.below(19);
        }
        let depth = (height + rng.below(11)).min(18);
        let width = (height + rng.below(7) - rng.below(5)).min(11);
        let extent = if elliptical { major } else { 11 };
        let rotation = angle.sin_cos();
        for x in -extent..extent {
            for z in -extent..extent {
                for y in 0..height {
                    let distance = if elliptical {
                        ellipse(x, z, extent, taper(y, height, minor), rotation)
                    } else {
                        let radius = round_radius(y, height, width, rng);
                        if x >= radius {
                            continue;
                        }
                        circle(x, z, radius, rng)
                    };
                    self.deposit(
                        world,
                        offset(origin, [x, y, z]),
                        rng,
                        distance,
                        height,
                        y,
                        elliptical,
                        snow_cap,
                    );
                }
            }
        }
        self.smooth(
            world,
            origin,
            if elliptical { major } else { width / 2 },
            height,
        );

        for x in -extent..extent {
            for z in -extent..extent {
                for y in (1..depth).map(|y| -y) {
                    let axis = if elliptical {
                        (extent as f32 * (1.0 - (y * y) as f32 / (depth as f32 * 8.0))).ceil()
                            as i32
                    } else {
                        extent
                    };
                    let radius = steep_radius(-y, depth, width, rng);
                    if x < radius {
                        let distance = if elliptical {
                            ellipse(x, z, axis, taper(y, depth, minor), rotation)
                        } else {
                            circle(x, z, radius, rng)
                        };
                        self.deposit(
                            world,
                            offset(origin, [x, y, z]),
                            rng,
                            distance,
                            depth,
                            y,
                            elliptical,
                            snow_cap,
                        );
                    }
                }
            }
        }

        if rng.unit() > if elliptical { 0.1 } else { 0.7 } {
            let x_sign = if rng.below(2) == 0 { -1 } else { 1 };
            let z_sign = if rng.below(2) == 0 { -1 } else { 1 };
            let mut x = cutout_offset(width, rng);
            let mut z = cutout_offset(width, rng);
            let cut_angle = if elliptical {
                x = rng.below((major - 5).max(1));
                z = x;
                angle + std::f64::consts::FRAC_PI_2
            } else {
                rng.unit() * std::f64::consts::TAU
            };
            let center = [x_sign * x, z_sign * z];
            let rotation = cut_angle.sin_cos();
            for y in 0..height - 3 {
                let radius = round_radius(y, height, width, rng);
                self.carve(world, origin, y, radius, [major, minor], center, rotation);
            }
            let mut y = -1;
            while y > -height + rng.below(5) {
                let radius = steep_radius(-y, height, width, rng);
                self.carve(world, origin, y, radius, [major, minor], center, rotation);
                y -= 1;
            }
        }
        true
    }

    #[allow(clippy::too_many_arguments)]
    fn deposit(
        &self,
        world: &mut World,
        pos: Pos,
        rng: &mut Rng,
        distance: f64,
        height: i32,
        layer: i32,
        elliptical: bool,
        snow_cap: bool,
    ) {
        if distance < 0.0 {
            let edge = if elliptical {
                -0.5
            } else {
                f64::from(-6 - rng.below(3))
            };
            if distance > edge && rng.unit() > 0.9 || !world.test(&self.replaceable, pos) {
                return;
            }
            let snowy = !elliptical || rng.unit() > 0.05;
            let snow_range = (height / if elliptical { 3 } else { 2 }).max(1);
            let block = if snow_cap
                && !world.test(&self.water, pos)
                && f64::from(height - layer)
                    <= f64::from(rng.below(snow_range)) + f64::from(height) * 0.6
                && snowy
            {
                self.snow_block
            } else {
                self.block
            };
            world.write(pos, block);
        }
    }

    fn smooth(&self, world: &mut World, origin: Pos, extent: i32, height: i32) {
        for x in -extent..=extent {
            for z in -extent..=extent {
                for y in 0..=height {
                    let pos = offset(origin, [x, y, z]);
                    let ice = world.test(&self.ice, pos);
                    if !ice && !world.test(&self.snow_layer, pos) {
                        continue;
                    }
                    if world.test(&self.air, offset(pos, [0, -1, 0])) {
                        world.write(pos, self.air_block);
                        world.write(offset(pos, [0, 1, 0]), self.air_block);
                    } else if ice
                        && HORIZONTAL
                            .iter()
                            .filter(|&&side| !world.test(&self.ice, offset(pos, side)))
                            .count()
                            >= 3
                    {
                        world.write(pos, self.air_block);
                    }
                }
            }
        }
    }

    #[allow(clippy::too_many_arguments)]
    fn carve(
        &self,
        world: &mut World,
        origin: Pos,
        y: i32,
        radius: i32,
        [major, minor]: [i32; 2],
        [cx, cz]: [i32; 2],
        rotation: (f64, f64),
    ) {
        let x_axis = radius + 1 + major / 3;
        let z_axis = (radius - 3).min(3) + minor / 2 - 1;
        for x in -x_axis..x_axis {
            for z in -x_axis..x_axis {
                let pos = offset(origin, [x, y, z]);
                if ellipse(x - cx, z - cz, x_axis, z_axis, rotation) < 0.0
                    && world.test(&self.ice, pos)
                {
                    if y < 0 {
                        world.write(pos, self.water_block);
                    } else {
                        world.write(pos, self.air_block);
                        let above = offset(pos, [0, 1, 0]);
                        if world.test(&self.snow_layer, above) {
                            world.write(above, self.air_block);
                        }
                    }
                }
            }
        }
    }
}

fn ellipse(x: i32, z: i32, major: i32, minor: i32, (sin, cos): (f64, f64)) -> f64 {
    let (x, z) = (f64::from(x), f64::from(z));
    ((x * cos - z * sin) / f64::from(major)).powi(2)
        + ((x * sin + z * cos) / f64::from(minor)).powi(2)
        - 1.0
}

fn circle(x: i32, z: i32, radius: i32, rng: &mut Rng) -> f64 {
    let noise = 10.0 * (rng.unit() as f32).clamp(0.2, 0.8) / radius as f32;
    f64::from(noise) + f64::from(x * x + z * z - radius * radius)
}

fn taper(y: i32, height: i32, minor: i32) -> i32 {
    if y > 0 && height - y <= 3 {
        minor - 4 + height - y
    } else {
        minor
    }
}

fn round_radius(y: i32, height: i32, width: i32, rng: &mut Rng) -> i32 {
    let curvature = 3.5 - rng.unit() as f32;
    let height = height as f32;
    let scale = if height > (15 + rng.below(5)) as f32 {
        let y = if y < 3 + rng.below(6) { y / 2 } else { y };
        1.0 - y as f32 / (height * curvature * 0.4)
    } else {
        1.0 - (y * y) as f32 / (height * curvature)
    };
    (scale * width as f32 / 2.0).ceil() as i32
}

fn steep_radius(depth: i32, height: i32, width: i32, rng: &mut Rng) -> i32 {
    let slope = 1.0 + rng.unit() as f32 / 2.0;
    ((1.0 - depth as f32 / (height as f32 * slope)) * width as f32 / 2.0).ceil() as i32
}

fn cutout_offset(width: i32, rng: &mut Rng) -> i32 {
    let offset = rng.below((width / 2 - 2).max(1));
    if rng.below(2) != 0 {
        width / 2 + 1 - rng.below((width - width / 2 - 1).max(1))
    } else {
        offset
    }
}
