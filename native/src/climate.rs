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
                (target.biome | ((flags & (4 | 64)) << 16)) as f32,
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

/// One shared tree representation for CPU lookup and GPU table encoding.
pub(crate) struct Index {
    nodes: Vec<Node>,
    surface_end: u32,
    underground_end: u32,
}
impl Index {
    pub fn new(profile: &WorldProfile) -> Self {
        let mut surface = Vec::new();
        let mut underground = Vec::new();
        let mut shores = Vec::new();
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
                    if flags & 64 != 0 {
                        // Coastal selection projects only continentalness onto a
                        // registered shore interval. Keep the other climate axes.
                        let mut shore = leaf;
                        shore.low[2] = 0.0;
                        shore.high[2] = 0.0;
                        shore.depth = [target.min[2], target.max[2]];
                        shores.push(shore);
                    }
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
        let underground_end = nodes.len() as u32;
        if !shores.is_empty() {
            if indexed {
                tree(&mut shores, 5, &mut nodes);
            } else {
                for mut leaf in shores {
                    leaf.escape = nodes.len() as u32 + 1;
                    nodes.push(leaf);
                }
            }
        }

        Self {
            nodes,
            surface_end,
            underground_end,
        }
    }
    pub fn has_surface(&self) -> bool {
        self.surface_end > 0
    }
    pub fn has_underground(&self) -> bool {
        self.underground_end > self.surface_end
    }
    pub fn search(
        &self,
        actual: [f32; 6],
        weighted: bool,
        underground: bool,
        ocean_penalty: bool,
        initial: u32,
        initial_fitness: f32,
        exclude_shores: bool,
    ) -> u32 {
        self.search_inner::<false, false>(
            actual,
            weighted,
            underground,
            ocean_penalty,
            initial,
            initial_fitness,
            exclude_shores,
            None,
        )
        .0
    }
    pub(crate) fn search_with_hint(
        &self,
        actual: [f32; 6],
        weighted: bool,
        underground: bool,
        ocean_penalty: bool,
        initial: u32,
        initial_fitness: f32,
        exclude_shores: bool,
        hint: Option<usize>,
    ) -> (u32, Option<usize>) {
        let start = if underground { self.surface_end } else { 0 } as usize;
        let end = if underground {
            self.underground_end
        } else {
            self.surface_end
        } as usize;
        if end - start <= 9 || self.nodes[start].order != u32::MAX {
            return (
                self.search(
                    actual,
                    weighted,
                    underground,
                    ocean_penalty,
                    initial,
                    initial_fitness,
                    exclude_shores,
                ),
                None,
            );
        }
        let (biome, hint, _) = self.search_inner::<true, false>(
            actual,
            weighted,
            underground,
            ocean_penalty,
            initial,
            initial_fitness,
            exclude_shores,
            hint,
        );
        (biome, hint)
    }
    fn search_inner<const HINT: bool, const COUNT: bool>(
        &self,
        actual: [f32; 6],
        weighted: bool,
        underground: bool,
        ocean_penalty: bool,
        initial: u32,
        initial_fitness: f32,
        exclude_shores: bool,
        hint: Option<usize>,
    ) -> (u32, Option<usize>, usize) {
        let start = if underground { self.surface_end } else { 0 } as usize;
        let mut at = start;
        let end = if underground {
            self.underground_end
        } else {
            self.surface_end
        } as usize;
        let (mut best, mut selected, mut order) = (initial_fitness, initial, 0u32);
        let (mut selected_hint, mut visits) = (None, 0);
        // A linear table or one leaf group cannot prune any extra distance
        // calculations, so do not pay to evaluate a hint there.
        let coherent = HINT && end - start > 9 && self.nodes[start].order == u32::MAX;
        if coherent && let Some(index) = hint.filter(|&i| i >= start && i < end) {
            let entry = &self.nodes[index];
            let packed = entry.extra[3] as u32;
            if entry.order != u32::MAX && !(exclude_shores && packed >> 16 & 64 != 0) {
                if COUNT {
                    visits += 1;
                }
                let mut fitness = distance(entry, actual, weighted, underground);
                if ocean_penalty && (packed >> 16 & 4 != if actual[2] < -0.25 { 4 } else { 0 }) {
                    fitness += 8.0;
                }
                // Equality must keep the caller's initial candidate and its
                // original tie precedence. Every hint is re-evaluated exactly.
                if fitness < best {
                    best = fitness;
                    selected = packed & 65535;
                    order = entry.order + 1;
                    selected_hint = Some(index);
                }
            }
        }
        while at < end {
            if COUNT {
                visits += 1;
            }
            let entry = &self.nodes[at];
            let branch = entry.order == u32::MAX;
            let mut fitness = distance(entry, actual, weighted, underground);
            let packed = entry.extra[3] as u32;
            if !branch && exclude_shores && packed >> 16 & 64 != 0 {
                at += 1;
                continue;
            }
            if !branch
                && ocean_penalty
                && (packed >> 16 & 4 != if actual[2] < -0.25 { 4 } else { 0 })
            {
                fitness += 8.0;
            }
            if fitness > best {
                at = entry.escape as usize;
                continue;
            }
            if !branch && (fitness < best || fitness == best && entry.order + 1 < order) {
                best = fitness;
                selected = packed & 65535;
                order = entry.order + 1;
                if coherent {
                    selected_hint = Some(at);
                }
            }
            at += 1;
        }
        (selected, selected_hint, visits)
    }
    pub fn coastal(&self, actual: [f32; 6]) -> [f32; 6] {
        let mut point = actual;
        point[2] = 0.0;
        let (mut at, mut best, mut order) = (self.underground_end as usize, 1e20, u32::MAX);
        let mut result = actual;
        while at < self.nodes.len() {
            let entry = &self.nodes[at];
            let branch = entry.order == u32::MAX;
            let fitness = distance(entry, point, false, false);
            if fitness > best {
                at = entry.escape as usize;
                continue;
            }
            if !branch && (fitness < best || fitness == best && entry.order < order) {
                best = fitness;
                order = entry.order;
                result[2] = (entry.depth[0] + entry.depth[1]) * 0.5;
            }
            at += 1;
        }
        result
    }
}
fn distance(node: &Node, actual: [f32; 6], weighted: bool, underground: bool) -> f32 {
    let weights = if weighted {
        [2.5, 1.5, 2.0, 0.5]
    } else {
        [1.0; 4]
    };
    let mut result = 0.0;
    for axis in 0..4 {
        let difference = actual[axis] - actual[axis].clamp(node.low[axis], node.high[axis]);
        result += difference * difference * weights[axis];
    }
    let ridge = actual[4] - actual[4].clamp(node.extra[0], node.extra[1]);
    result += ridge * ridge;
    if underground {
        let depth = actual[5] - actual[5].clamp(node.depth[0], node.depth[1]);
        result += depth * depth;
    }
    result
        + if node.order == u32::MAX {
            node.extra[2]
        } else {
            node.extra[2] * node.extra[2]
        }
}

pub fn bytes(profile: &WorldProfile) -> Vec<u8> {
    let Index {
        nodes,
        surface_end,
        underground_end,
    } = Index::new(profile);
    let indexed = matches!(profile.climate_lookup, Lookup::Indexed);
    let mut bytes = bytemuck::cast_slice(&[
        surface_end,
        surface_end,
        underground_end,
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
    bytes.extend_from_slice(bytemuck::cast_slice(&[
        underground_end,
        nodes.len() as u32,
        0,
        0,
    ]));
    bytes.extend_from_slice(bytemuck::cast_slice(&nodes));
    bytes.resize(bytes.len().max(240), 0);
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

#[cfg(test)]
mod cpu_index_tests {
    use super::*;
    #[test]
    fn coherent_search_preserves_initial_ties_flags_and_arbitrary_hint_safety() {
        let leaves: Vec<_> = (0..101)
            .map(|i| {
                Node::leaf(
                    &ClimateTarget {
                        biome: i,
                        min: [i as f32 * 0.02 - 1.0, -0.5, -0.5, -0.5],
                        max: [i as f32 * 0.02 - 0.8, 0.5, 0.5, 0.5],
                        weirdness: [-0.5, 0.5],
                        depth: [i as f32 * 0.01, 1.5],
                        offset: 0.01 * (i % 3) as f32,
                    },
                    i as usize,
                    if i % 5 == 0 {
                        64
                    } else if i % 3 == 0 {
                        4
                    } else {
                        0
                    },
                )
            })
            .collect();
        let mut nodes = Vec::new();
        tree(&mut leaves.clone(), 5, &mut nodes);
        let surface_end = nodes.len() as u32;
        tree(&mut leaves.clone(), 6, &mut nodes);
        let index = Index {
            underground_end: nodes.len() as u32,
            surface_end,
            nodes,
        };
        for weighted in [false, true] {
            for underground in [false, true] {
                for ocean in [false, true] {
                    for exclude in [false, true] {
                        for initial_fitness in [-0.03, 0.0, 1e20] {
                            let mut hint = None;
                            for sample in 0..257 {
                                let point = [
                                    sample as f32 * 0.01 - 1.2,
                                    0.1,
                                    -0.3,
                                    0.7,
                                    -0.7,
                                    (sample % 31) as f32 * 0.05,
                                ];
                                let (mut best, mut expected, mut order) = (initial_fitness, 900, 0);
                                for leaf in &leaves {
                                    let packed = leaf.extra[3] as u32;
                                    if exclude && packed >> 16 & 64 != 0 {
                                        continue;
                                    }
                                    let mut fitness = distance(leaf, point, weighted, underground);
                                    if ocean
                                        && (packed >> 16 & 4
                                            != if point[2] < -0.25 { 4 } else { 0 })
                                    {
                                        fitness += 8.0;
                                    }
                                    if fitness < best || fitness == best && leaf.order + 1 < order {
                                        best = fitness;
                                        expected = packed & 65535;
                                        order = leaf.order + 1;
                                    }
                                }
                                assert_eq!(
                                    index.search(
                                        point,
                                        weighted,
                                        underground,
                                        ocean,
                                        900,
                                        initial_fitness,
                                        exclude
                                    ),
                                    expected
                                );
                                let (actual, next) = index.search_with_hint(
                                    point,
                                    weighted,
                                    underground,
                                    ocean,
                                    900,
                                    initial_fitness,
                                    exclude,
                                    hint,
                                );
                                assert_eq!(actual, expected);
                                hint = next;
                                for bad in [Some(usize::MAX), Some(0), Some(surface_end as usize)] {
                                    assert_eq!(
                                        index
                                            .search_with_hint(
                                                point,
                                                weighted,
                                                underground,
                                                ocean,
                                                900,
                                                initial_fitness,
                                                exclude,
                                                bad
                                            )
                                            .0,
                                        expected
                                    );
                                }
                            }
                        }
                    }
                }
            }
        }
        for value in [f32::NAN, f32::INFINITY, f32::NEG_INFINITY] {
            let point = [value; 6];
            assert_eq!(
                index
                    .search_with_hint(
                        point,
                        false,
                        true,
                        false,
                        900,
                        1e20,
                        false,
                        Some(index.nodes.len() - 1)
                    )
                    .0,
                index.search(point, false, true, false, 900, 1e20, false)
            );
        }
        // A linear lookup and a one-group tree must never retain/use a hint.
        for indexed in [false, true] {
            let mut nodes = Vec::new();
            if indexed {
                tree(&mut leaves[..5].to_vec(), 6, &mut nodes);
            } else {
                for leaf in &leaves {
                    let mut n = *leaf;
                    n.escape = nodes.len() as u32 + 1;
                    nodes.push(n);
                }
            }
            let small = Index {
                surface_end: 0,
                underground_end: nodes.len() as u32,
                nodes,
            };
            assert_eq!(
                small
                    .search_with_hint([0.0; 6], false, true, false, 900, 1e20, false, Some(1))
                    .1,
                None
            );
        }
    }
    #[test]
    fn actual_cpu_climate_sequence_reuses_only_an_exact_distance_bound() {
        use crate::world_definition::{WorldDefinition, catalog::WorldCatalog};
        let catalog = WorldCatalog::read(std::path::Path::new(concat!(
            env!("CARGO_MANIFEST_DIR"),
            "/../assets/world-definitions/26.3/vanilla-binary/retina-catalog.dat"
        )))
        .unwrap();
        let d = WorldDefinition::from_gzip(
            catalog.materialize("normal", 123456789).unwrap().as_slice(),
        )
        .unwrap();
        let profile = d
            .dimension("minecraft:overworld")
            .unwrap()
            .profile()
            .unwrap();
        let fields = crate::cpu::fields::Fields::new(profile).unwrap();
        let request = d.request("minecraft:overworld", 1, -1, -1).unwrap();
        let mut sampler = fields.sampler(profile, request);
        let index = Index::new(profile);
        let (mut hint, mut baseline, mut coherent) = (None, 0, 0);
        for x in [-16, -12, 0, 12] {
            for z in [-16, -12, 0, 12] {
                for y in (-64..320).step_by(4) {
                    let point = sampler
                        .climate([x as f32, y as f32, z as f32], false)
                        .unwrap();
                    let (a, _, old) = index.search_inner::<false, true>(
                        point, false, true, false, 0, 1e20, false, None,
                    );
                    let (b, next, new) = index.search_inner::<true, true>(
                        point, false, true, false, 0, 1e20, false, hint,
                    );
                    assert_eq!(a, b);
                    hint = next;
                    baseline += old;
                    coherent += new;
                }
            }
        }
        eprintln!(
            "Actual climate query distance evaluations: unseeded={baseline}, coherent={coherent} (includes hint reevaluation)"
        );
        assert!(coherent < baseline);
    }

    #[test]
    fn native_index_matches_linear_targets_with_flags_depth_and_registry_order() {
        let leaves: Vec<_> = (0..101)
            .map(|i| {
                Node::leaf(
                    &ClimateTarget {
                        biome: i,
                        min: [i as f32 * 0.02 - 1.0, -0.5, -0.5, -0.5],
                        max: [i as f32 * 0.02 - 0.8, 0.5, 0.5, 0.5],
                        weirdness: [-0.5, 0.5],
                        depth: [i as f32 * 0.01, 1.0],
                        offset: 0.01 * (i % 3) as f32,
                    },
                    i as usize,
                    if i % 5 == 0 {
                        64
                    } else if i % 3 == 0 {
                        4
                    } else {
                        0
                    },
                )
            })
            .collect();
        let mut sorted = leaves.clone();
        let mut nodes = Vec::new();
        tree(&mut sorted, 6, &mut nodes);
        let index = Index {
            surface_end: nodes.len() as u32,
            underground_end: nodes.len() as u32,
            nodes,
        };
        for weighted in [false, true] {
            for underground in [false, true] {
                for exclude in [false, true] {
                    for sample in 0..513 {
                        let point = [
                            sample as f32 * 0.005 - 1.2,
                            0.1,
                            -0.3,
                            0.7,
                            -0.7,
                            (sample % 31) as f32 * 0.05,
                        ];
                        let oracle = leaves
                            .iter()
                            .filter(|leaf| !exclude || leaf.extra[3] as u32 >> 16 & 64 == 0)
                            .min_by(|a, b| {
                                distance(a, point, weighted, underground)
                                    .total_cmp(&distance(b, point, weighted, underground))
                                    .then(a.order.cmp(&b.order))
                            })
                            .unwrap();
                        // Underground uses the same tree appended after the surface tree.
                        let actual = if underground {
                            Index {
                                surface_end: 0,
                                underground_end: index.nodes.len() as u32,
                                nodes: index.nodes.clone(),
                            }
                            .search(point, weighted, true, false, 0, 1e20, exclude)
                        } else {
                            index.search(point, weighted, false, false, 0, 1e20, exclude)
                        };
                        assert_eq!(actual, oracle.extra[3] as u32 & 65535);
                    }
                }
            }
        }
    }
    #[test]
    fn coastal_projection_preserves_other_axes_and_selects_interval_interior() {
        let mut leaf = Node::leaf(
            &ClimateTarget {
                biome: 7,
                min: [-1.0; 4],
                max: [1.0; 4],
                weirdness: [-1.0, 1.0],
                depth: [0.0, 0.0],
                offset: 0.0,
            },
            0,
            64,
        );
        leaf.low[2] = 0.0;
        leaf.high[2] = 0.0;
        leaf.depth = [-0.2, 0.1];
        leaf.escape = 1;
        let index = Index {
            surface_end: 0,
            underground_end: 0,
            nodes: vec![leaf],
        };
        let actual = [0.1, 0.2, 0.7, 0.4, 0.5, 0.6];
        let projected = index.coastal(actual);
        assert_eq!(projected[2], -0.05);
        for i in [0, 1, 3, 4, 5] {
            assert_eq!(projected[i], actual[i]);
        }
    }
}
