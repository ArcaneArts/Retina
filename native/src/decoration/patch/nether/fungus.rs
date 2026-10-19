//! Loaded huge-fungus geometry, including shared-stream hats and hanging vines.
use super::*;

#[derive(Clone, Deserialize)]
pub struct Fungus {
    base: Predicate,
    hat_block: Predicate,
    replaceable: Predicate,
    replaceable_blocks: Predicate,
    air: Predicate,
    stem_state: u16,
    hat_state: u16,
    decor_state: u16,
    air_state: u16,
    vine_plant: u16,
    vine_tips: [u16; 3],
    planted: bool,
    vines: bool,
    generator_depth: i32,
}

impl Fungus {
    pub(super) fn validate(&self, palette: usize) -> bool {
        [
            &self.base,
            &self.hat_block,
            &self.replaceable,
            &self.replaceable_blocks,
            &self.air,
        ]
        .iter()
        .all(|p| p.validate(palette))
            && [
                self.stem_state,
                self.hat_state,
                self.decor_state,
                self.air_state,
                self.vine_plant,
            ]
            .iter()
            .chain(self.vine_tips.iter())
            .all(|&id| (id as usize) < palette)
            && (0..=4096).contains(&self.generator_depth)
    }

    pub(super) fn place(&self, w: &mut World, at: [i32; 3], rng: &mut Rng) -> bool {
        if !w.test(&self.base, add(at, [0, -1, 0])) {
            return false;
        }
        let mut height = 4 + rng.below(10);
        if rng.below(12) == 0 {
            height *= 2;
        }
        // Minecraft compares absolute Y to the source generator's depth here,
        // not to storage height or the resolved anchor context.
        if !self.planted && at[1] + height + 1 >= self.generator_depth {
            return false;
        }
        let huge = !self.planted && (rng.unit() as f32) < 0.06;
        w.write(at, self.air_state);
        self.stem(w, at, height, huge, rng);
        self.hat(w, at, height, huge, rng);
        true
    }

    fn stem(&self, w: &mut World, at: [i32; 3], height: i32, huge: bool, rng: &mut Rng) {
        let radius: i32 = if huge { 1 } else { 0 };
        for x in -radius..=radius {
            for z in -radius..=radius {
                let corner = huge && x.abs() == radius && z.abs() == radius;
                for y in 0..height {
                    let p = add(at, [x, y, z]);
                    if !w.test(&self.replaceable, p) && !w.test(&self.replaceable_blocks, p) {
                        continue;
                    }
                    if self.planted || !corner || (rng.unit() as f32) < 0.1 {
                        // WorldGenRegion.destroyBlock only clears the old block;
                        // the following write replaces it without spawning drops.
                        w.write(p, self.stem_state);
                    }
                }
            }
        }
    }

    fn hat(&self, w: &mut World, at: [i32; 3], height: i32, huge: bool, rng: &mut Rng) {
        let hat_height = (rng.below(1 + height / 3) + 5).min(height);
        let bottom = height - hat_height;
        for y in bottom..=height {
            let mut radius = if y < height - rng.below(3) { 2 } else { 1 };
            if hat_height > 8 && y < bottom + 4 {
                radius = 3;
            }
            if huge {
                radius += 1;
            }
            for x in -radius..=radius {
                for z in -radius..=radius {
                    let edge_x = x == -radius || x == radius;
                    let edge_z = z == -radius || z == radius;
                    let inside = !edge_x && !edge_z && y != height;
                    let p = add(at, [x, y, z]);
                    if !w.test(&self.replaceable, p) {
                        continue;
                    }
                    if self.planted
                        && !w.test(&self.air, p)
                        && !w.test(&self.air, add(p, [0, -1, 0]))
                    {
                        w.write(p, self.air_state);
                    }
                    if y < bottom + 3 {
                        if !inside {
                            self.drop(w, p, rng);
                        }
                    } else if inside {
                        self.hat_block(w, p, 0.1, 0.2, if self.vines { 0.1 } else { 0. }, rng);
                    } else if edge_x && edge_z {
                        self.hat_block(w, p, 0.01, 0.7, if self.vines { 0.083 } else { 0. }, rng);
                    } else {
                        self.hat_block(w, p, 0.0005, 0.98, if self.vines { 0.07 } else { 0. }, rng);
                    }
                }
            }
        }
    }

    fn hat_block(
        &self,
        w: &mut World,
        p: [i32; 3],
        decor: f32,
        hat: f32,
        vines: f32,
        rng: &mut Rng,
    ) {
        if (rng.unit() as f32) < decor {
            w.write(p, self.decor_state);
        } else if (rng.unit() as f32) < hat {
            w.write(p, self.hat_state);
            if (rng.unit() as f32) < vines {
                self.vines(w, p, rng);
            }
        }
    }

    fn drop(&self, w: &mut World, p: [i32; 3], rng: &mut Rng) {
        if w.test(&self.hat_block, add(p, [0, -1, 0])) {
            w.write(p, self.hat_state);
        } else if ((rng.unit() as f32) as f64) < 0.15 {
            w.write(p, self.hat_state);
            if self.vines && rng.below(11) == 0 {
                self.vines(w, p, rng);
            }
        }
    }

    fn vines(&self, w: &mut World, p: [i32; 3], rng: &mut Rng) {
        let mut p = add(p, [0, -1, 0]);
        if !w.test(&self.air, p) {
            return;
        }
        let mut height = 1 + rng.below(5);
        if rng.below(7) == 0 {
            height *= 2;
        }
        for y in 0..=height {
            if w.test(&self.air, p) {
                if y == height || !w.test(&self.air, add(p, [0, -1, 0])) {
                    w.write(p, self.vine_tips[rng.below(3) as usize]);
                    break;
                }
                w.write(p, self.vine_plant);
            }
            p[1] -= 1;
        }
    }
}
