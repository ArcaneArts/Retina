//! Registry-derived vegetation replay. Global anchors make results independent of
//! chunk scheduling and of whether a chunk is assembled through MCA or the FFM path.
use crate::{
    COLUMNS, ChunkRequest,
    profile::{Column, WorldProfile},
};
use rayon::prelude::*;
use serde::Deserialize;
use std::collections::{HashSet, VecDeque};

pub mod placement;
mod spatial;

#[derive(Clone, Deserialize)]
pub struct TreeDecorator {
    pub kind: String,
    pub probability: f64,
    pub states: Vec<u16>,
}

#[derive(Clone, Deserialize)]
pub struct PlantState {
    pub lower: u16,
    pub upper: u16,
    pub weight: f64,
    pub band: u8,
    pub dry: bool,
}
#[derive(Clone, Deserialize)]
pub struct LogState {
    pub axes: [u16; 3],
    pub weight: f64,
}
#[derive(Clone, Deserialize)]
pub struct LeafState {
    pub distances: [u16; 6],
    pub weight: f64,
}
#[derive(Clone, Deserialize)]
pub struct Recipe {
    pub source: String,
    pub salt: i64,
    pub density: f64,
    pub low_density: f64,
    pub noise_count: bool,
    pub rarity: f64,
    pub tries: f64,
    pub spread: [i32; 3],
    #[serde(default)]
    pub water_offsets: Vec<[i32; 3]>,
    #[serde(default)]
    pub placement_salt: Option<i64>,
    #[serde(default)]
    pub placement: Option<Vec<placement::Modifier>>,
    #[serde(flatten)]
    pub feature: Kind,
}
#[derive(Clone, Deserialize)]
#[serde(tag = "kind")]
pub enum Kind {
    #[serde(rename = "plant")]
    Plant { states: Vec<PlantState> },
    #[serde(rename = "tree")]
    Tree {
        trunk_shape: String,
        foliage_shape: String,
        height: [i32; 3],
        radius: [i32; 2],
        offset: [i32; 2],
        foliage_height: [i32; 2],
        trunk_height: [i32; 2],
        logs: Vec<LogState>,
        leaves: Vec<LeafState>,
        soil: u16,
        root: u16,
        root_offset: [i32; 2],
        #[serde(default)]
        decorators: Vec<TreeDecorator>,
    },
}
impl Recipe {
    pub fn validate(&self, material_count: usize) -> Result<(), String> {
        if let Some(program) = &self.placement {
            placement::validate(program, material_count)?;
        }
        if self.placement.is_none()
            && (!self.density.is_finite()
                || !self.low_density.is_finite()
                || !self.rarity.is_finite()
                || !self.tries.is_finite()
                || self.density < 0.0
                || self.low_density < 0.0
                || self.rarity < 1.0
                || !(1.0..=4096.0).contains(&self.tries)
                || self.spread.iter().any(|v| !(0..=15).contains(v)))
        {
            return Err(format!("invalid decoration placement: {}", self.source));
        }
        if self.placement.is_none()
            && self
                .water_offsets
                .iter()
                .flatten()
                .any(|v| !(-15..=15).contains(v))
        {
            return Err("decoration water offset exceeds terrain halo".into());
        }
        let valid = |id: u16| (id as usize) < material_count;
        let okay = match &self.feature {
            Kind::Plant { states } => {
                !states.is_empty()
                    && states.iter().all(|s| {
                        valid(s.lower)
                            && valid(s.upper)
                            && s.weight.is_finite()
                            && s.weight > 0.0
                            && s.band <= 2
                    })
            }
            Kind::Tree {
                height,
                logs,
                leaves,
                soil,
                root,
                radius,
                decorators,
                ..
            } => {
                !logs.is_empty()
                    && !leaves.is_empty()
                    && height.iter().all(|v| (0..=80).contains(v))
                    && radius[0] >= 0
                    && radius[1] >= radius[0]
                    && logs.iter().all(|s| {
                        s.axes.iter().all(|id| valid(*id)) && s.weight.is_finite() && s.weight > 0.0
                    })
                    && leaves.iter().all(|s| {
                        s.distances.iter().all(|id| valid(*id))
                            && s.weight.is_finite()
                            && s.weight > 0.0
                    })
                    && valid(*soil)
                    && valid(*root)
                    && decorators.iter().all(|d| {
                        d.probability.is_finite()
                            && (0.0..=1.0).contains(&d.probability)
                            && d.states.len() == if d.kind == "cocoa" { 12 } else { 4 }
                            && matches!(d.kind.as_str(), "trunk_vine" | "leaf_vine" | "cocoa")
                            && d.states.iter().all(|id| valid(*id))
                    })
            }
        };
        if !okay {
            return Err(format!("invalid decoration materials: {}", self.source));
        }
        Ok(())
    }
}

pub struct Field {
    pub origin_x: i32,
    pub origin_z: i32,
    pub side: usize,
    pub columns: Vec<Column>,
}
impl Field {
    pub fn column(&self, x: i32, z: i32) -> Option<Column> {
        let chunk_x = x.div_euclid(16) - self.origin_x;
        let chunk_z = z.div_euclid(16) - self.origin_z;
        if chunk_x < 0
            || chunk_z < 0
            || chunk_x as usize >= self.side
            || chunk_z as usize >= self.side
        {
            return None;
        }
        Some(
            self.columns[(chunk_z as usize * self.side + chunk_x as usize) * COLUMNS
                + z.rem_euclid(16) as usize * 16
                + x.rem_euclid(16) as usize],
        )
    }
    pub fn chunk(&self, x: i32, z: i32) -> &[Column] {
        let start =
            ((z - self.origin_z) as usize * self.side + (x - self.origin_x) as usize) * COLUMNS;
        &self.columns[start..start + COLUMNS]
    }
}
#[derive(Clone, Copy)]
pub struct Placement {
    pub index: u32,
    pub material: u16,
    pub upper: u16,
    pub role: u8,
}
#[derive(Clone, Copy)]
struct WorldBlock {
    x: i32,
    y: i32,
    z: i32,
    material: u16,
    upper: u16,
    role: u8,
}
const PLANT: u8 = 0;
const LEAF: u8 = 1;
const LOG: u8 = 2;
const SOIL: u8 = 3;
const VINE: u8 = 4;
const COCOA: u8 = 5;
const SIDES: [(i32, i32); 4] = [(1, 0), (-1, 0), (0, 1), (0, -1)];

/// A crown occupies a small local box. Direct byte lookup avoids hashing a world
/// coordinate six times for every visited leaf. State 2 includes trunk positions
/// so leaves never overwrite logs; traversal order still comes from the BFS.
struct LeafOccupancy {
    minimum: [i32; 3],
    sizes: [usize; 3],
    cells: Vec<u8>,
    sparse: Option<spatial::Set<(i32, i32, i32)>>,
}
impl LeafOccupancy {
    fn new(positions: &[(i32, i32, i32)]) -> Self {
        if positions.is_empty() {
            return Self {
                minimum: [0; 3],
                sizes: [0; 3],
                cells: Vec::new(),
                sparse: None,
            };
        }
        let mut minimum = [i32::MAX; 3];
        let mut maximum = [i32::MIN; 3];
        for &(x, y, z) in positions {
            for (i, v) in [x, y, z].into_iter().enumerate() {
                minimum[i] = minimum[i].min(v);
                maximum[i] = maximum[i].max(v);
            }
        }
        let sizes = std::array::from_fn(|i| (maximum[i] as i64 - minimum[i] as i64 + 1) as usize);
        let count = sizes.iter().try_fold(1usize, |n, s| n.checked_mul(*s));
        if count.is_none_or(|n| n > 1_048_576) {
            // Unusual offsets can put crowns far apart or across an integer
            // wrap. Keep sparse membership rather than allocating empty gaps.
            return Self {
                minimum,
                sizes,
                cells: Vec::new(),
                sparse: Some(positions.iter().copied().collect()),
            };
        }
        let mut result = Self {
            minimum,
            sizes,
            cells: vec![0; count.unwrap()],
            sparse: None,
        };
        for &p in positions {
            let index = result.index(p).unwrap();
            result.cells[index] = 1;
        }
        result
    }
    fn index(&self, (x, y, z): (i32, i32, i32)) -> Option<usize> {
        let at = [x, y, z];
        let delta: [usize; 3] =
            std::array::from_fn(|i| (at[i] as i64 - self.minimum[i] as i64) as usize);
        if (0..3).any(|i| delta[i] >= self.sizes[i]) {
            return None;
        }
        Some((delta[1] * self.sizes[2] + delta[2]) * self.sizes[0] + delta[0])
    }
    fn mark_log(&mut self, p: (i32, i32, i32)) {
        if let Some(sparse) = &mut self.sparse {
            sparse.remove(&p);
            return;
        }
        if let Some(i) = self.index(p) {
            self.cells[i] = 2;
        }
    }
    fn take_leaf(&mut self, p: (i32, i32, i32)) -> bool {
        if let Some(sparse) = &mut self.sparse {
            return sparse.remove(&p);
        }
        let Some(i) = self.index(p) else {
            return false;
        };
        if self.cells[i] != 1 {
            return false;
        }
        self.cells[i] = 2;
        true
    }
}

/// A sparse overlay per target chunk; the outer ring only supplies neighboring anchors.
pub fn plan(
    field: &Field,
    profile: &WorldProfile,
    request: ChunkRequest,
    origin_x: i32,
    origin_z: i32,
    side: usize,
    mask: Option<&crate::geology::CaveMask>,
) -> Vec<Vec<Placement>> {
    let origins: Vec<_> = (0..field.side * field.side)
        .map(|index| {
            (
                field.origin_x + (index % field.side) as i32,
                field.origin_z + (index / field.side) as i32,
            )
        })
        .collect();
    let generate = |&(x, z): &(i32, i32)| anchors(field, profile, request, x, z, mask);
    // Indexed collection preserves global anchor order despite parallel planning.
    let blocks: Vec<_> = if side > 1 {
        origins.par_iter().map(generate).collect()
    } else {
        origins.iter().map(generate).collect()
    };
    let mut targets = vec![Vec::new(); side * side];
    for block in blocks.into_iter().flatten() {
        let cx = block.x.div_euclid(16) - origin_x;
        let cz = block.z.div_euclid(16) - origin_z;
        let y = block.y - request.min_y;
        if cx < 0
            || cz < 0
            || cx as usize >= side
            || cz as usize >= side
            || y < 0
            || y >= request.height as i32
        {
            continue;
        }
        targets[cz as usize * side + cx as usize].push(Placement {
            index: y as u32 * 256
                + block.z.rem_euclid(16) as u32 * 16
                + block.x.rem_euclid(16) as u32,
            material: block.material,
            upper: block.upper,
            role: block.role,
        });
    }
    targets
}

fn anchors(
    field: &Field,
    profile: &WorldProfile,
    request: ChunkRequest,
    chunk_x: i32,
    chunk_z: i32,
    mask: Option<&crate::geology::CaveMask>,
) -> Vec<WorldBlock> {
    let mut ids = HashSet::new();
    for z in (0..16).step_by(4) {
        for x in (0..16).step_by(4) {
            let c = field.column(chunk_x * 16 + x, chunk_z * 16 + z).unwrap();
            ids.extend(profile.biomes[c.biome()].decorations.iter().copied());
        }
    }
    let mut ids: Vec<_> = ids.into_iter().collect();
    ids.sort_unstable();
    let center = field.column(chunk_x * 16 + 8, chunk_z * 16 + 8).unwrap();
    let mut blocks = Vec::new();
    let mut candidates = Vec::new();
    let mut groups = std::collections::HashMap::new();
    for id in ids {
        let recipe = &profile.decorations[id as usize];
        if let Some(program) = &recipe.placement {
            let salt = recipe.placement_salt.unwrap_or(recipe.salt) as u64;
            let group = *groups.entry(salt).or_insert(id);
            let rng = Rng::new(
                request.seed
                    ^ salt
                    ^ mix(chunk_x as u32 as u64)
                    ^ mix((chunk_z as u32 as u64) << 32),
            );
            placement::expand(
                program,
                id,
                group,
                [chunk_x * 16, request.min_y, chunk_z * 16],
                rng,
                field,
                &mut candidates,
            );
            continue;
        }
        let mut rng = Rng::new(
            request.seed
                ^ recipe.salt as u64
                ^ mix(chunk_x as u32 as u64)
                ^ mix((chunk_z as u32 as u64) << 32),
        );
        if rng.unit() >= 1.0 / recipe.rarity {
            continue;
        }
        let density = if recipe.noise_count && center.packed & (1 << 27) != 0 {
            recipe.low_density
        } else {
            recipe.density
        };
        for _ in 0..rng.count(density) {
            // Each anchor has its own stream: halo clipping must not advance later anchors differently.
            let mut rng = Rng::new(rng.next());
            let x = chunk_x * 16 + rng.below(16);
            let z = chunk_z * 16 + rng.below(16);
            let Some(column) = field.column(x, z) else {
                continue;
            };
            if !profile.biomes[column.biome()].decorations.contains(&id)
                || column.height < profile.sea_level
                || mask.is_some_and(|m| m.surface_carved(x, z))
            {
                continue;
            }
            if !recipe.water_offsets.is_empty()
                && !recipe.water_offsets.iter().any(|offset| {
                    field.column(x + offset[0], z + offset[2]).is_some_and(|c| {
                        c.material(column.height + offset[1], request.min_y, Some(profile))
                            == profile.water
                    })
                })
            {
                continue;
            }
            match &recipe.feature {
                Kind::Plant { states } => {
                    for _ in 0..rng.count(recipe.tries) {
                        let mut rng = Rng::new(rng.next());
                        let px = x + rng.triangle(recipe.spread[0]);
                        let pz = z + rng.triangle(recipe.spread[2]);
                        let py = column.height + rng.triangle(recipe.spread[1]);
                        let Some(at) = field.column(px, pz) else {
                            continue;
                        };
                        if py != at.height
                            || at.height < profile.sea_level
                            || mask.is_some_and(|m| m.surface_carved(px, pz))
                            || !profile.biomes[at.biome()].decorations.contains(&id)
                        {
                            continue;
                        }
                        let low = at.packed & (1 << 27) != 0;
                        let state = weighted(
                            states
                                .iter()
                                .filter(|s| s.band == 0 || s.band == if low { 1 } else { 2 }),
                            |s| s.weight,
                            &mut rng,
                        );
                        let Some(state) = state else {
                            continue;
                        };
                        let ground = at.material(at.height - 1, request.min_y, Some(profile));
                        if profile.material_flags[ground as usize] & if state.dry { 2 } else { 1 }
                            == 0
                        {
                            continue;
                        }
                        blocks.push(WorldBlock {
                            x: px,
                            y: py,
                            z: pz,
                            material: state.lower,
                            upper: state.upper,
                            role: PLANT,
                        });
                    }
                }
                Kind::Tree { .. } => tree(
                    field,
                    profile,
                    request,
                    recipe,
                    x,
                    z,
                    &mut rng,
                    &mut blocks,
                    mask,
                    None,
                ),
            }
        }
    }
    candidates.sort_by(|a, b| {
        a.group
            .cmp(&b.group)
            .then(a.order.cmp(&b.order))
            .then(a.recipe.cmp(&b.recipe))
    });
    let mut overlay = placement::Overlay::default();
    let mut evaluation_cache = placement::EvaluationCache::default();
    for candidate in candidates {
        let recipe = &profile.decorations[candidate.recipe as usize];
        let Some(position) = placement::position(
            &candidate,
            recipe,
            field,
            profile,
            request,
            &overlay,
            &mut evaluation_cache,
        ) else {
            continue;
        };
        let [x, y, z] = position;
        let Some(column) = field.column(x, z) else {
            continue;
        };
        if mask.is_some_and(|m| y == column.height && m.surface_carved(x, z)) {
            continue;
        }
        let start = blocks.len();
        let mut rng = Rng::new(candidate.seed);
        match &recipe.feature {
            Kind::Tree { .. } => tree(
                field,
                profile,
                request,
                recipe,
                x,
                z,
                &mut rng,
                &mut blocks,
                mask,
                Some((y, &overlay)),
            ),
            Kind::Plant { states } => {
                let low = column.packed & (1 << 27) != 0;
                if let Some(state) = weighted(
                    states
                        .iter()
                        .filter(|s| s.band == 0 || s.band == if low { 1 } else { 2 }),
                    |s| s.weight,
                    &mut rng,
                ) {
                    let soil = overlay.material(field, profile, request, [x, y - 1, z]);
                    if soil.is_some_and(|s| {
                        profile.material_flags[s as usize] & if state.dry { 2 } else { 1 } != 0
                    }) {
                        blocks.push(WorldBlock {
                            x,
                            y,
                            z,
                            material: state.lower,
                            upper: state.upper,
                            role: PLANT,
                        });
                    }
                }
            }
        }
        overlay.commit(&blocks, start, field, profile, request);
    }
    blocks
}

fn tree(
    field: &Field,
    profile: &WorldProfile,
    request: ChunkRequest,
    recipe: &Recipe,
    x: i32,
    z: i32,
    rng: &mut Rng,
    blocks: &mut Vec<WorldBlock>,
    mask: Option<&crate::geology::CaveMask>,
    placed: Option<(i32, &placement::Overlay)>,
) {
    let Kind::Tree {
        trunk_shape,
        foliage_shape,
        height,
        radius,
        offset,
        foliage_height,
        trunk_height,
        logs,
        leaves,
        soil,
        root,
        root_offset,
        decorators,
    } = &recipe.feature
    else {
        return;
    };
    let ground = field.column(x, z).unwrap();
    let ground_y = placed.map_or(ground.height, |p| p.0);
    let ground_material = placed
        .and_then(|p| p.1.material(field, profile, request, [x, ground_y - 1, z]))
        .unwrap_or_else(|| ground.material(ground_y - 1, request.min_y, Some(profile)));
    let snowy_soil = ground_material == profile.snow
        && profile.material_flags[(ground.materials >> 16) as usize] & 1 != 0;
    if profile.material_flags[ground_material as usize] & 1 == 0 && !snowy_soil {
        return;
    }
    let h = (height[0] + rng.below(height[1] + 1) + rng.below(height[2] + 1)).max(1);
    let base_y = ground_y + rng.range(*root_offset);
    if base_y + h + 4 >= request.min_y + request.height as i32 {
        return;
    }
    let log = weighted(logs.iter(), |s| s.weight, rng).unwrap();
    let leaf = weighted(leaves.iter(), |s| s.weight, rng).unwrap();
    let width = if trunk_shape.contains("giant")
        || trunk_shape.contains("mega_jungle")
        || trunk_shape.contains("dark_oak")
    {
        2
    } else {
        1
    };
    if (0..width)
        .any(|dx| (0..width).any(|dz| mask.is_some_and(|m| m.surface_carved(x + dx, z + dz))))
    {
        return;
    }
    let (wood, crowns) = crate::tree_shapes::trunk(trunk_shape, x, base_y, z, h, width, rng);
    // Validate the entire trunk against GPU terrain, including neighboring columns.
    if wood.iter().any(|&(wx, wy, wz, _)| {
        field
            .column(wx, wz)
            .is_none_or(|c| wy < c.surface_height(Some(profile)))
            || placed.is_some_and(|p| {
                p.1.material(field, profile, request, [wx, wy, wz])
                    .is_some_and(|m| m != 0 && profile.material_flags[m as usize] & (4 | 16) == 0)
            })
    }) {
        return;
    }
    let tree_start = blocks.len();
    for &(wx, wy, wz, axis) in &wood {
        blocks.push(WorldBlock {
            x: wx,
            y: wy,
            z: wz,
            material: log.axes[axis],
            upper: 0,
            role: LOG,
        });
    }
    if *soil != 0 {
        for wz in 0..width {
            for wx in 0..width {
                blocks.push(WorldBlock {
                    x: x + wx,
                    y: ground_y - 1,
                    z: z + wz,
                    material: *soil,
                    upper: 0,
                    role: SOIL,
                });
            }
        }
    }
    if *root != 0 {
        for (rx, rz) in [(1, 0), (-1, 0), (0, 1), (0, -1)] {
            for distance in 0..=3 {
                let px = x + rx * distance;
                let pz = z + rz * distance;
                if let Some(c) = field.column(px, pz) {
                    for y in c.height..=base_y - distance / 2 {
                        blocks.push(WorldBlock {
                            x: px,
                            y,
                            z: pz,
                            material: *root,
                            upper: 0,
                            role: LOG,
                        });
                    }
                }
            }
        }
    }
    let r = rng.range(*radius).clamp(1, 4);
    let o = rng.range(*offset);
    let conifer = foliage_shape.contains("spruce") || foliage_shape.contains("pine");
    let fh = if foliage_shape.contains("spruce") {
        (h - rng.range(*trunk_height)).max(4)
    } else if foliage_shape.contains("mega_pine") {
        rng.range(*foliage_height).max(6)
    } else {
        rng.range(*foliage_height).max(2)
    }
    .min(h + 2);
    let mut leaf_positions = Vec::new();
    for crown in &crowns {
        let (cx, cy, cz) = crown.pos;
        let jungle = foliage_shape == "jungle_foliage_placer";
        let layers = if jungle && crown.width == 1 {
            1 + rng.below(2)
        } else if foliage_shape.contains("acacia") {
            2
        } else {
            fh
        };
        for dy in 0..=layers {
            let row_r = if jungle {
                r + crown.radius_offset + 1 - o + dy
            } else if foliage_shape == "fancy_foliage_placer" {
                r + crown.radius_offset + i32::from(dy != 0 && dy != layers)
            } else if foliage_shape == "bush_foliage_placer" {
                r + crown.radius_offset - 1 - o + dy
            } else if foliage_shape == "blob_foliage_placer" {
                r + crown.radius_offset - 1 - (o - dy) / 2
            } else if conifer {
                (dy / 2 + 1).min(r + 1)
            } else if foliage_shape.contains("acacia") {
                r + 1 - dy
            } else {
                (r - 1 + dy / 2).min(5)
            }
            .clamp(0, 6);
            for lx in -row_r..=row_r + crown.width - 1 {
                for lz in -row_r..=row_r + crown.width - 1 {
                    // Giant crowns measure distance to either column of the 2x2 trunk.
                    let ax = lx.abs().min((lx - crown.width + 1).abs());
                    let az = lz.abs().min((lz - crown.width + 1).abs());
                    let skip = if jungle {
                        ax + az >= 7 || ax * ax + az * az > row_r * row_r
                    } else if foliage_shape == "fancy_foliage_placer" {
                        (ax as f64 + 0.5).powi(2) + (az as f64 + 0.5).powi(2)
                            > (row_r * row_r) as f64
                    } else {
                        ax == row_r
                            && az == row_r
                            && (conifer
                                || (foliage_shape != "bush_foliage_placer" && dy == 0)
                                || rng.below(2) == 0)
                    };
                    if skip {
                        continue;
                    }
                    let pos = (cx + lx, cy + o - dy, cz + lz);
                    if field
                        .column(pos.0, pos.2)
                        .is_some_and(|c| pos.1 >= c.surface_height(Some(profile)))
                    {
                        leaf_positions.push(pos);
                    }
                }
            }
        }
    }
    // Real six-neighbor leaf connectivity, not persistent leaves: chopping logs can decay them.
    let mut occupancy = LeafOccupancy::new(&leaf_positions);
    let mut frontier = VecDeque::new();
    let mut seen = spatial::Set::default();
    for &(wx, wy, wz, _) in &wood {
        if seen.insert((wx, wy, wz)) {
            occupancy.mark_log((wx, wy, wz));
            frontier.push_back(((wx, wy, wz), 0u8));
        }
    }
    while let Some((pos, distance)) = frontier.pop_front() {
        if distance >= 6 {
            continue;
        }
        for (dx, dy, dz) in [
            (1, 0, 0),
            (-1, 0, 0),
            (0, 1, 0),
            (0, -1, 0),
            (0, 0, 1),
            (0, 0, -1),
        ] {
            let next = (pos.0 + dx, pos.1 + dy, pos.2 + dz);
            if occupancy.take_leaf(next) {
                let d = distance + 1;
                blocks.push(WorldBlock {
                    x: next.0,
                    y: next.1,
                    z: next.2,
                    material: leaf.distances[d as usize - 1],
                    upper: 0,
                    role: LEAF,
                });
                frontier.push_back((next, d));
            }
        }
    }
    if !decorators.is_empty() {
        decorate_tree(
            field,
            profile,
            decorators,
            &wood,
            &blocks[tree_start..]
                .iter()
                .filter(|b| b.role == LEAF)
                .map(|b| (b.x, b.y, b.z))
                .collect::<Vec<_>>(),
            rng.next(),
            blocks,
        );
    }
}

fn decorate_tree(
    field: &Field,
    profile: &WorldProfile,
    decorators: &[TreeDecorator],
    wood: &[(i32, i32, i32, usize)],
    leaves: &[(i32, i32, i32)],
    seed: u64,
    blocks: &mut Vec<WorldBlock>,
) {
    if decorators.is_empty() {
        return;
    }
    let mut occupied: spatial::Set<_> = wood
        .iter()
        .map(|w| (w.0, w.1, w.2))
        .chain(leaves.iter().copied())
        .collect();
    let base_y = wood.iter().map(|w| w.1).min().unwrap();
    for (index, decorator) in decorators.iter().enumerate() {
        let seed = seed ^ mix(index as u64);
        if decorator.kind == "cocoa" && Rng::new(seed).unit() >= decorator.probability {
            continue;
        }
        let mut positions: Vec<_> = if decorator.kind == "leaf_vine" {
            leaves.to_vec()
        } else {
            wood.iter().map(|w| (w.0, w.1, w.2)).collect()
        };
        positions.sort_unstable_by_key(|p| (p.1, p.0, p.2));
        positions.dedup();
        for (x, y, z) in positions {
            if decorator.kind == "cocoa" && y - base_y > 2 {
                continue;
            }
            // Coordinate streams do not depend on leaf clipping at the terrain halo edge.
            let mut rng = Rng::new(
                seed ^ mix(x as u32 as u64) ^ mix((z as u32 as u64) << 32) ^ mix(y as u64),
            );
            for (side, (dx, dz)) in SIDES.iter().enumerate() {
                let chance = if decorator.kind == "cocoa" {
                    0.25
                } else {
                    decorator.probability
                };
                if rng.unit() >= chance {
                    continue;
                }
                let material = decorator.states[if decorator.kind == "cocoa" {
                    side * 3 + rng.below(3) as usize
                } else {
                    side
                }];
                let length = if decorator.kind == "leaf_vine" { 5 } else { 1 };
                for drop in 0..length {
                    let at = (x + dx, y - drop, z + dz);
                    if occupied.contains(&at)
                        || field
                            .column(at.0, at.2)
                            .is_none_or(|c| at.1 < c.surface_height(Some(profile)))
                    {
                        break;
                    }
                    occupied.insert(at);
                    blocks.push(WorldBlock {
                        x: at.0,
                        y: at.1,
                        z: at.2,
                        material,
                        upper: side as u16,
                        role: if decorator.kind == "cocoa" {
                            COCOA
                        } else {
                            VINE
                        },
                    });
                }
            }
        }
    }
}

pub fn assemble(
    request: ChunkRequest,
    columns: &[Column],
    profile: Option<&WorldProfile>,
    placements: &[Placement],
    blocks: &mut [u16],
) {
    assemble_carved(request, columns, profile, None, blocks);
    decorate(profile, placements, blocks);
}

/// Base material and GPU cavity mask are written together; ore replacement follows this pass.
pub fn assemble_carved(
    request: ChunkRequest,
    columns: &[Column],
    profile: Option<&WorldProfile>,
    mask: Option<&crate::geology::CaveMask>,
    blocks: &mut [u16],
) {
    let Some(p) = profile else {
        for (layer, row) in blocks.chunks_exact_mut(COLUMNS).enumerate() {
            for (block, column) in row.iter_mut().zip(columns) {
                *block = if request.min_y + (layer as i32) < column.height {
                    1
                } else {
                    0
                };
            }
        }
        return;
    };
    let prepared: Vec<_> = columns
        .iter()
        .map(|&c| crate::profile::PreparedColumn::new(c, request.min_y, p))
        .collect();
    let end = prepared
        .iter()
        .map(|c| c.end())
        .max()
        .unwrap()
        .clamp(request.min_y, request.min_y + request.height as i32);
    let layers = (end - request.min_y) as usize;
    // Buffers are recycled: explicitly clear all upper air, including previous buildings.
    blocks[layers * COLUMNS..].fill(0);
    for (layer, row) in blocks[..layers * COLUMNS]
        .chunks_exact_mut(COLUMNS)
        .enumerate()
    {
        let y = request.min_y + layer as i32;
        for z in 0..16 {
            let mut carved = mask.map_or(0, |m| {
                m.row_bits(request.chunk_x * 16, y, request.chunk_z * 16 + z as i32)
            });
            for x in 0..16 {
                let i = z * 16 + x;
                let mut material = prepared[i].material(y);
                if carved & 1 != 0 && p.geology.carveable[material as usize] {
                    material = if y < p.geology.lava_level {
                        p.geology.lava
                    } else if p.biomes[columns[i].biome()].flags & 4 != 0 && y < p.sea_level {
                        p.water
                    } else {
                        0
                    };
                }
                row[i] = material;
                carved >>= 1;
            }
        }
    }
}

pub fn decorate(profile: Option<&WorldProfile>, placements: &[Placement], blocks: &mut [u16]) {
    let Some(profile) = profile else {
        return;
    };
    // Trees settle first; paired plants are placed only when both halves remain free.
    for role in [SOIL, LOG, LEAF, COCOA, VINE, PLANT] {
        for placement in placements.iter().filter(|p| p.role == role) {
            let index = placement.index as usize;
            if index >= blocks.len() {
                continue;
            }
            let previous = blocks[index];
            let flags = profile.material_flags[previous as usize];
            let replace = match role {
                SOIL => flags & 1 != 0 || previous == profile.snow,
                LOG => previous == 0 || flags & (4 | 16) != 0,
                LEAF => previous == 0,
                _ => previous == 0,
            };
            if !replace {
                continue;
            }
            if role == VINE || role == COCOA {
                let (dx, dz) = SIDES[placement.upper as usize];
                let x = (index % 16) as i32 - dx;
                let z = ((index % COLUMNS) / 16) as i32 - dz;
                let supported = if (0..16).contains(&x) && (0..16).contains(&z) {
                    let neighbor = blocks[index / COLUMNS * COLUMNS + z as usize * 16 + x as usize];
                    profile.material_flags[neighbor as usize]
                        & if role == COCOA { 32 } else { 4 | 8 }
                        != 0
                } else {
                    true
                }; // The owner tree validated its supporting block in the halo.
                let hanging = role == VINE
                    && index + COLUMNS < blocks.len()
                    && blocks[index + COLUMNS] == placement.material;
                if !supported && !hanging {
                    continue;
                }
            }
            if role == PLANT && placement.upper != 0 {
                if index + COLUMNS >= blocks.len() || blocks[index + COLUMNS] != 0 {
                    continue;
                }
                blocks[index + COLUMNS] = placement.upper;
            }
            blocks[index] = placement.material;
        }
    }
}

pub(crate) struct Rng(u64);
fn mix(mut value: u64) -> u64 {
    value = (value ^ (value >> 30)).wrapping_mul(0xbf58476d1ce4e5b9);
    value = (value ^ (value >> 27)).wrapping_mul(0x94d049bb133111eb);
    value ^ (value >> 31)
}
impl Rng {
    pub(crate) fn new(seed: u64) -> Self {
        Self(seed)
    }
    fn next(&mut self) -> u64 {
        self.0 = self.0.wrapping_add(0x9e3779b97f4a7c15);
        mix(self.0)
    }
    pub(crate) fn unit(&mut self) -> f64 {
        (self.next() >> 11) as f64 / ((1u64 << 53) as f64)
    }
    pub(crate) fn below(&mut self, limit: i32) -> i32 {
        if limit <= 1 {
            0
        } else {
            (self.next() % limit as u64) as i32
        }
    }
    fn range(&mut self, range: [i32; 2]) -> i32 {
        range[0] + self.below(range[1] - range[0] + 1)
    }
    fn triangle(&mut self, extent: i32) -> i32 {
        self.below(extent + 1) - self.below(extent + 1)
    }
    fn count(&mut self, n: f64) -> usize {
        n.floor() as usize + usize::from(self.unit() < n.fract())
    }
}
fn weighted<'a, T>(
    values: impl Iterator<Item = &'a T> + Clone,
    weight: impl Fn(&T) -> f64,
    rng: &mut Rng,
) -> Option<&'a T> {
    let total: f64 = values.clone().map(&weight).sum();
    let mut choice = rng.unit() * total;
    for value in values {
        choice -= weight(value);
        if choice < 0.0 {
            return Some(value);
        }
    }
    None
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn crown_grid_preserves_breadth_first_distance_order_and_log_occupancy() {
        for origin in [(-30_000_000, -60, 30_000_000), (-17, 63, -33), (0, 0, 0)] {
            for seed in 0..16 {
                let mut rng = Rng::new(seed);
                let mut positions = Vec::new();
                for y in 0..9 {
                    for z in -5..=5 {
                        for x in -5..=5 {
                            if rng.below(5) != 0 {
                                positions.push((origin.0 + x, origin.1 + y, origin.2 + z));
                            }
                        }
                    }
                }
                // Duplicate crowns and logs overlapping foliage must be suppressed.
                positions.extend_from_within(..positions.len() / 2);
                let wood = [origin, origin, (origin.0, origin.1 + 1, origin.2)];
                let expected: HashSet<_> = positions.iter().copied().collect();
                let mut seen = HashSet::new();
                let mut frontier = VecDeque::new();
                for p in wood {
                    if seen.insert(p) {
                        frontier.push_back((p, 0u8));
                    }
                }
                let mut reference = Vec::new();
                while let Some((p, d)) = frontier.pop_front() {
                    if d >= 6 {
                        continue;
                    }
                    for (x, y, z) in [
                        (1, 0, 0),
                        (-1, 0, 0),
                        (0, 1, 0),
                        (0, -1, 0),
                        (0, 0, 1),
                        (0, 0, -1),
                    ] {
                        let p = (p.0 + x, p.1 + y, p.2 + z);
                        if expected.contains(&p) && seen.insert(p) {
                            reference.push((p, d + 1));
                            frontier.push_back((p, d + 1));
                        }
                    }
                }
                let mut grid = LeafOccupancy::new(&positions);
                let mut seen = spatial::Set::default();
                for p in wood {
                    if seen.insert(p) {
                        grid.mark_log(p);
                        frontier.push_back((p, 0u8));
                    }
                }
                let mut actual = Vec::new();
                while let Some((p, d)) = frontier.pop_front() {
                    if d >= 6 {
                        continue;
                    }
                    for (x, y, z) in [
                        (1, 0, 0),
                        (-1, 0, 0),
                        (0, 1, 0),
                        (0, -1, 0),
                        (0, 0, 1),
                        (0, 0, -1),
                    ] {
                        let p = (p.0 + x, p.1 + y, p.2 + z);
                        if grid.take_leaf(p) {
                            actual.push((p, d + 1));
                            frontier.push_back((p, d + 1));
                        }
                    }
                }
                assert!(!reference.is_empty());
                assert_eq!(
                    reference, actual,
                    "distance/order changed at {origin:?}, seed {seed}"
                );
            }
        }
        let mut empty = LeafOccupancy::new(&[]);
        empty.mark_log((1, 2, 3));
        assert!(!empty.take_leaf((1, 2, 3)));
        let extreme = [
            (i32::MIN, i32::MIN, i32::MIN),
            (i32::MAX, i32::MAX, i32::MAX),
        ];
        let mut sparse = LeafOccupancy::new(&extreme);
        assert!(sparse.sparse.is_some());
        sparse.mark_log(extreme[0]);
        assert!(!sparse.take_leaf(extreme[0]));
        assert!(sparse.take_leaf(extreme[1]));
        assert!(!sparse.take_leaf(extreme[1]));
    }

    #[test]
    fn giant_jungle_crowns_close_above_the_trunk_without_four_interior_holes() {
        let profile = WorldProfile {
            program_execution: crate::specialize::Execution::default(),
            climate_lookup: crate::climate::Lookup::default(),
            structures: Default::default(),
            registry_program: None,
            climate_targets: Vec::new(),
            weirdness_noise: None,
            biome_scale: 256.0,
            blend: 0.55,
            sea_level: 63,
            stone: 1,
            water: 1,
            bedrock: 1,
            deepslate: 1,
            snow: 1,
            ice: 1,
            materials: vec![serde_json::json!("minecraft:air"); 10],
            biomes: vec![
                serde_json::from_value(serde_json::json!({
                    "id":"test:jungle", "climate":[0,0,0,0], "terrain":[0,0,0],
                    "top":2,"filler":2,"underwater":1,"flags":0
                }))
                .unwrap(),
            ],
            noises: Vec::new(),
            decorations: Vec::new(),
            geology: Default::default(),
            terrain_features: Default::default(),
            material_flags: vec![0, 0, 1, 8 | 32, 4, 4, 4, 4, 4, 4],
            heightmap_masks: vec![0; 10],
            encoded: Vec::new(),
            material_nbt: Vec::new(),
            ore_membership: Vec::new(),
        };
        let field = Field {
            origin_x: 0,
            origin_z: 0,
            side: 3,
            columns: vec![
                Column {
                    materials: 2 | (2 << 16),
                    height: 64,
                    packed: 0
                };
                9 * COLUMNS
            ],
        };
        let recipe = Recipe {
            source: "test:mega_jungle".into(),
            placement_salt: None,
            placement: None,
            salt: 0,
            density: 1.0,
            low_density: 1.0,
            noise_count: false,
            rarity: 1.0,
            tries: 1.0,
            spread: [0; 3],
            water_offsets: Vec::new(),
            feature: Kind::Tree {
                trunk_shape: "mega_jungle_trunk_placer".into(),
                foliage_shape: "jungle_foliage_placer".into(),
                height: [26, 0, 0],
                radius: [2, 2],
                offset: [0, 0],
                foliage_height: [2, 2],
                trunk_height: [2, 2],
                logs: vec![LogState {
                    axes: [3; 3],
                    weight: 1.0,
                }],
                leaves: vec![LeafState {
                    distances: [4, 5, 6, 7, 8, 9],
                    weight: 1.0,
                }],
                soil: 2,
                root: 0,
                root_offset: [0, 0],
                decorators: Vec::new(),
            },
        };
        let request = ChunkRequest {
            seed: 42,
            chunk_x: 1,
            chunk_z: 1,
            min_y: 0,
            height: 128,
            base_height: 64.0,
            amplitude: 0.0,
            frequency: 0.008,
            reserved: 0,
        };
        for seed in 0..64 {
            let mut blocks = Vec::new();
            tree(
                &field,
                &profile,
                request,
                &recipe,
                16,
                16,
                &mut Rng::new(seed),
                &mut blocks,
                None,
                None,
            );
            let leaves: HashSet<_> = blocks
                .iter()
                .filter(|b| b.role == LEAF)
                .map(|b| (b.x, b.y, b.z))
                .collect();
            for (dx, dz) in [(-1, -1), (-1, 1), (1, -1), (1, 1), (0, 0), (0, 1), (1, 0)] {
                assert!(
                    leaves.contains(&(16 + dx, 90, 16 + dz)),
                    "interior crown hole at {dx},{dz} for seed {seed}"
                );
            }
            assert!(
                !leaves.contains(&(13, 90, 13)),
                "giant crowns have rounded outer corners"
            );
            assert!(
                blocks.iter().any(|b| b.role == LEAF
                    && b.y < 88
                    && (b.x < 16 || b.x > 17 || b.z < 16 || b.z > 17)),
                "lateral branches carry foliage below the main crown"
            );
        }
    }
}
