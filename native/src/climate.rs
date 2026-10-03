//! Resident stackless bounding-box indices for surface and depth-aware climate queries.
//! Original target order resolves ties even when spatial sorting changes traversal order.
use crate::profile::{ClimateTarget, WorldProfile};
use bytemuck::{Pod, Zeroable};
use std::collections::HashSet;

#[derive(Clone, Copy, Default, serde::Deserialize)]
#[serde(rename_all = "snake_case")]
pub enum Lookup {
    #[default]
    Indexed,
    Linear,
}

#[repr(C)]
#[derive(Clone, Copy, Pod, Zeroable, Debug)]
struct Node {
    low: [f32; 4],
    high: [f32; 4],
    extra: [f32; 4], // weirdness interval, offset (leaves) / minimum offset² (branches), biome+flags
    depth: [f32; 2],
    order: u32,
    escape: u32,
}
impl Node {
    fn leaf(target: &ClimateTarget, order: usize, flags: u32) -> Self {
        Self {
            low: target.min,
            high: target.max,
            extra: [
                target.weirdness[0],
                target.weirdness[1],
                target.offset,
                (target.biome | ((flags & 4) << 16)) as f32,
            ],
            depth: target.depth,
            order: order as u32,
            escape: 0,
        }
    }
    fn interval(&self, axis: usize) -> (f32, f32) {
        match axis {
            0..=3 => (self.low[axis], self.high[axis]),
            4 => (self.extra[0], self.extra[1]),
            _ => (self.depth[0], self.depth[1]),
        }
    }
    fn bound(leaves: &[Self]) -> Self {
        let mut bound = leaves[0];
        bound.order = u32::MAX;
        bound.extra[2] *= bound.extra[2];
        bound.extra[3] = 0.0;
        for leaf in &leaves[1..] {
            for i in 0..4 {
                bound.low[i] = bound.low[i].min(leaf.low[i]);
                bound.high[i] = bound.high[i].max(leaf.high[i]);
            }
            bound.extra[0] = bound.extra[0].min(leaf.extra[0]);
            bound.extra[1] = bound.extra[1].max(leaf.extra[1]);
            bound.extra[2] = bound.extra[2].min(leaf.extra[2] * leaf.extra[2]);
            bound.depth[0] = bound.depth[0].min(leaf.depth[0]);
            bound.depth[1] = bound.depth[1].max(leaf.depth[1]);
        }
        bound
    }
}

fn tree(leaves: &mut [Node], dimensions: usize, output: &mut Vec<Node>) {
    let at = output.len();
    output.push(Node::bound(leaves));
    if leaves.len() <= 8 {
        leaves.sort_unstable_by_key(|leaf| leaf.order);
        for leaf in leaves {
            let mut leaf = *leaf;
            leaf.escape = output.len() as u32 + 1;
            output.push(leaf);
        }
    } else {
        let mut spread = -1.0;
        let mut axis = 0;
        for dimension in 0..dimensions {
            let mut low = f32::INFINITY;
            let mut high = f32::NEG_INFINITY;
            for leaf in leaves.iter() {
                let (a, b) = leaf.interval(dimension);
                let center = (a + b) * 0.5;
                low = low.min(center);
                high = high.max(center);
            }
            if high - low > spread {
                spread = high - low;
                axis = dimension;
            }
        }
        leaves.sort_unstable_by(|a, b| {
            let a_interval = a.interval(axis);
            let b_interval = b.interval(axis);
            (a_interval.0 + a_interval.1)
                .total_cmp(&(b_interval.0 + b_interval.1))
                .then(a.order.cmp(&b.order))
        });
        let middle = leaves.len() / 2;
        let (left, right) = leaves.split_at_mut(middle);
        tree(left, dimensions, output);
        tree(right, dimensions, output);
    }
    output[at].escape = output.len() as u32;
}

pub fn bytes(profile: &WorldProfile) -> Vec<u8> {
    let mut surface = Vec::new();
    let mut underground = Vec::new();
    let mut seen = HashSet::new();
    for (order, target) in profile.climate_targets.iter().enumerate() {
        let flags = profile.biomes[target.biome as usize].flags;
        let leaf = Node::leaf(target, order, flags);
        underground.push(leaf);
        if flags & 16 == 0 {
            // Depth never participates in a surface query. Include biome and offset in the key.
            let key: Vec<u32> = target
                .min
                .iter()
                .chain(&target.max)
                .chain(&target.weirdness)
                .chain(std::iter::once(&target.offset))
                .map(|x| x.to_bits())
                .chain(std::iter::once(target.biome))
                .collect();
            if seen.insert(key) {
                surface.push(leaf);
            }
        }
    }
    let mut nodes = Vec::new();
    let indexed = matches!(profile.climate_lookup, Lookup::Indexed);
    let mut append = |leaves: &mut Vec<Node>, dimensions| {
        if leaves.is_empty() {
            return;
        }
        if indexed {
            tree(leaves, dimensions, &mut nodes);
        } else {
            for leaf in leaves.iter() {
                let mut leaf = *leaf;
                leaf.escape = nodes.len() as u32 + 1;
                nodes.push(leaf);
            }
        }
    };
    append(&mut surface, 5);
    let surface_end = nodes.len() as u32;
    // End the mutable borrow before asking for the size of the surface tree.
    if !underground.is_empty() {
        if indexed {
            tree(&mut underground, 6, &mut nodes);
        } else {
            for mut leaf in underground {
                leaf.escape = nodes.len() as u32 + 1;
                nodes.push(leaf);
            }
        }
    }
    let mut bytes = bytemuck::cast_slice(&[
        surface_end,
        surface_end,
        nodes.len() as u32,
        u32::from(indexed),
    ])
    .to_vec();
    let mut noise = [0u32; 36];
    if let Some(source) = &profile.weirdness_noise {
        noise[0] = source.frequency.to_bits();
        noise[1] = source.amplitude.to_bits();
        noise[2] = source.modifiers.len() as u32;
        for (i, value) in source.modifiers.iter().enumerate() {
            noise[i + 4] = value.to_bits();
        }
    }
    bytes.extend_from_slice(bytemuck::cast_slice(&noise));
    bytes.extend_from_slice(bytemuck::cast_slice(&nodes));
    bytes.resize(bytes.len().max(224), 0);
    bytes
}

#[cfg(test)]
mod tests {
    use super::*;
    fn fitness(node: &Node, point: [f32; 6], weights: [f32; 4], depth: bool) -> f32 {
        let mut result = 0.0;
        for i in 0..4 {
            result += (point[i] - point[i].clamp(node.low[i], node.high[i])).powi(2) * weights[i];
        }
        result += (point[4] - point[4].clamp(node.extra[0], node.extra[1])).powi(2);
        if depth {
            result += (point[5] - point[5].clamp(node.depth[0], node.depth[1])).powi(2);
        }
        result
            + if node.order == u32::MAX {
                node.extra[2]
            } else {
                node.extra[2] * node.extra[2]
            }
    }
    #[test]
    fn stackless_index_matches_linear_intervals_and_ties() {
        let mut rng = 777u32;
        let mut random = || {
            rng = rng.wrapping_mul(1664525).wrapping_add(1013904223);
            (rng >> 8) as f32 / 16777216.0 * 4.0 - 2.0
        };
        let mut leaves = Vec::new();
        for i in 0..1003 {
            let mut min = [0.0; 4];
            let mut max = [0.0; 4];
            for j in 0..4 {
                min[j] = random();
                max[j] = min[j] + random().abs();
            }
            let target = ClimateTarget {
                biome: i % 67,
                min,
                max,
                weirdness: [-0.3, 0.6],
                depth: [random(), 2.0],
                offset: random() * 0.1,
            };
            leaves.push(Node::leaf(&target, i as usize, 0));
        }
        leaves.push(Node {
            order: 1003,
            ..leaves[0]
        }); // Deliberate exact tie with a later ordinal.
        for depth in [false, true] {
            let mut sorted = leaves.clone();
            let mut nodes = Vec::new();
            tree(&mut sorted, if depth { 6 } else { 5 }, &mut nodes);
            for weights in [[1.0; 4], [2.5, 1.5, 2.0, 0.5]] {
                for _ in 0..2000 {
                    let point = std::array::from_fn(|_| random());
                    let expected = leaves
                        .iter()
                        .min_by(|a, b| {
                            fitness(a, point, weights, depth)
                                .total_cmp(&fitness(b, point, weights, depth))
                                .then(a.order.cmp(&b.order))
                        })
                        .unwrap()
                        .order;
                    let mut at = 0;
                    let mut best = f32::INFINITY;
                    let mut order = u32::MAX;
                    while at < nodes.len() {
                        let node = &nodes[at];
                        let f = fitness(node, point, weights, depth);
                        if f > best {
                            at = node.escape as usize;
                            continue;
                        }
                        if node.order != u32::MAX && (f < best || f == best && node.order < order) {
                            best = f;
                            order = node.order;
                        }
                        at += 1;
                    }
                    assert_eq!(order, expected);
                }
            }
        }
    }
}
