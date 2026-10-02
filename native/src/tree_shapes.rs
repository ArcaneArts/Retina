//! Procedural trunk/attachment rules adapted from the registered vanilla placers.
use crate::decoration::Rng;

pub(crate) type Wood = (i32, i32, i32, usize);
pub(crate) struct Crown {
    pub pos: (i32, i32, i32),
    pub radius_offset: i32,
    pub width: i32,
}

pub(crate) fn trunk(
    shape: &str,
    x: i32,
    y: i32,
    z: i32,
    height: i32,
    width: i32,
    rng: &mut Rng,
) -> (Vec<Wood>, Vec<Crown>) {
    if shape == "fancy_trunk_placer" {
        return fancy(x, y, z, height, rng);
    }
    let mut wood = Vec::new();
    let mut crowns = vec![Crown {
        pos: (x, y + height, z),
        radius_offset: 0,
        width,
    }];
    let (dx, dz) = match rng.below(4) {
        0 => (1, 0),
        1 => (-1, 0),
        2 => (0, 1),
        _ => (0, -1),
    };
    for h in 0..height {
        let bend = if shape.contains("forking") && h >= height - 3 {
            h - (height - 3) + 1
        } else {
            0
        };
        for wz in 0..width {
            for wx in 0..width {
                if width == 2 && h == height - 1 && (wx != 0 || wz != 0) {
                    continue;
                }
                wood.push((x + wx + dx * bend, y + h, z + wz + dz * bend, 0));
            }
        }
    }
    if shape == "mega_jungle_trunk_placer" {
        let mut branch_height = height - 2 - rng.below(4);
        while branch_height > height / 2 {
            let angle = rng.unit() * std::f64::consts::TAU;
            let mut end = (0, 0);
            for b in 0..5 {
                end = (
                    (1.5 + angle.cos() * b as f64) as i32,
                    (1.5 + angle.sin() * b as f64) as i32,
                );
                wood.push((x + end.0, y + branch_height - 3 + b / 2, z + end.1, 0));
            }
            crowns.push(Crown {
                pos: (x + end.0, y + branch_height, z + end.1),
                radius_offset: -2,
                width: 1,
            });
            branch_height -= 2 + rng.below(4);
        }
    } else if shape.contains("forking") || shape.contains("branching") {
        let branch_y = y + height - 2;
        for i in 1..=3 {
            wood.push((
                x + dx * i,
                branch_y + i / 2,
                z + dz * i,
                if dx != 0 { 1 } else { 2 },
            ));
        }
        crowns.push(Crown {
            pos: (x + dx * 3, branch_y + 2, z + dz * 3),
            radius_offset: 0,
            width: 1,
        });
        if shape.contains("forking") {
            crowns[0].pos = (x + dx * 3, y + height, z + dz * 3);
        }
    }
    (wood, crowns)
}

fn limb(wood: &mut Vec<Wood>, from: (i32, i32, i32), to: (i32, i32, i32)) {
    let delta = (to.0 - from.0, to.1 - from.1, to.2 - from.2);
    let steps = delta.0.abs().max(delta.1.abs()).max(delta.2.abs()).max(1);
    for i in 0..=steps {
        let at = (
            from.0 + (0.5 + i as f64 * delta.0 as f64 / steps as f64).floor() as i32,
            from.1 + (0.5 + i as f64 * delta.1 as f64 / steps as f64).floor() as i32,
            from.2 + (0.5 + i as f64 * delta.2 as f64 / steps as f64).floor() as i32,
        );
        let dx = (at.0 - from.0).abs();
        let dz = (at.2 - from.2).abs();
        let axis = if dx.max(dz) == 0 {
            0
        } else if dx >= dz {
            1
        } else {
            2
        };
        wood.push((at.0, at.1, at.2, axis));
    }
}

fn fancy(x: i32, y: i32, z: i32, tree_height: i32, rng: &mut Rng) -> (Vec<Wood>, Vec<Crown>) {
    let height = tree_height + 2;
    let trunk_height = (height as f64 * 0.618).floor() as i32;
    let mut wood = Vec::new();
    let mut crowns = Vec::new();
    limb(&mut wood, (x, y, z), (x, y + trunk_height, z));
    let mut attachments = vec![((x, y + height - 5, z), y + trunk_height)];
    for layer in (0..=height - 5).rev() {
        if (layer as f64) < height as f64 * 0.3 {
            continue;
        }
        let radius = height as f64 / 2.0;
        let adjacent = radius - layer as f64;
        let shape = (radius * radius - adjacent * adjacent).max(0.0).sqrt() * 0.5;
        let radius = shape * (rng.unit() + 0.328);
        let angle = rng.unit() * std::f64::consts::TAU;
        let dx = (radius * angle.sin() + 0.5).floor() as i32;
        let dz = (radius * angle.cos() + 0.5).floor() as i32;
        let at = (x + dx.clamp(-7, 7), y + layer - 1, z + dz.clamp(-7, 7));
        let branch_base = ((at.1 as f64
            - ((at.0 - x).pow(2) as f64 + (at.2 - z).pow(2) as f64).sqrt() * 0.381)
            as i32)
            .min(y + trunk_height);
        attachments.push((at, branch_base));
    }
    for (at, branch_base) in attachments {
        if (branch_base - y) as f64 >= height as f64 * 0.2 {
            limb(&mut wood, (x, branch_base, z), at);
            crowns.push(Crown {
                pos: at,
                radius_offset: 0,
                width: 1,
            });
        }
    }
    (wood, crowns)
}

#[cfg(test)]
mod tests {
    use super::*;
    use std::collections::HashSet;
    #[test]
    fn mega_jungle_has_many_branch_levels_and_procedural_variants() {
        let mut variants = HashSet::new();
        for seed in 0..64 {
            let (wood, crowns) = trunk(
                "mega_jungle_trunk_placer",
                0,
                64,
                0,
                26,
                2,
                &mut Rng::new(seed),
            );
            assert!(crowns.len() >= 3);
            assert!(
                crowns
                    .iter()
                    .skip(1)
                    .all(|c| c.width == 1 && c.radius_offset == -2)
            );
            assert!(crowns.iter().map(|c| c.pos.1).collect::<HashSet<_>>().len() >= 3);
            assert!(wood.contains(&(0, 89, 0, 0)) && !wood.contains(&(1, 89, 1, 0)));
            variants.insert(wood);
        }
        assert!(
            variants.len() >= 60,
            "branch angles and heights vary across seeds"
        );
    }
    #[test]
    fn fancy_oaks_have_supported_distributed_crowns() {
        let (wood, crowns) = trunk("fancy_trunk_placer", 0, 64, 0, 14, 1, &mut Rng::new(42));
        assert!(crowns.len() >= 5);
        assert!(crowns.iter().any(|c| c.pos.0 != 0 && c.pos.2 != 0));
        for crown in crowns {
            assert!(wood.iter().any(|w| (w.0, w.1, w.2) == crown.pos));
        }
    }
}
