//! CPU jigsaw planning over resident registry templates. Plans are shared by MCA,
//! individual chunks and DH previews, including pieces crossing region boundaries.
use crate::{CacheKey, ChunkRequest, TerrainEngine, nbt, profile::WorldProfile};
use rayon::prelude::*;
use serde::Deserialize;
use serde_json::{Value, json};
use std::collections::{HashMap, VecDeque};
use std::sync::{Arc, OnceLock};

#[derive(Clone, Default, Deserialize)]
pub struct Profile {
    pub templates: Vec<Template>,
    pub definitions: Vec<Definition>,
    pub pools: HashMap<String, Pool>,
    pub sets: Vec<Set>,
}
#[derive(Clone, Deserialize)]
pub struct Template {
    pub id: String,
    pub size: [i32; 3],
    pub ground: i32,
    pub palettes: Vec<Vec<[u16; 4]>>,
    pub blocks: Vec<i32>,
    pub tags: HashMap<usize, Value>,
    pub joints: Vec<Joint>,
    pub entities: Vec<Value>,
}
#[derive(Clone, Deserialize)]
pub struct Joint {
    pub pos: [i32; 3],
    pub front: usize,
    pub top: usize,
    pub name: String,
    pub target: String,
    pub pool: String,
    pub joint: String,
    pub selection_priority: i32,
    pub placement_priority: i32,
    #[serde(rename = "final")]
    pub final_state: [u16; 4],
}
#[derive(Clone, Deserialize)]
pub struct Element {
    pub parts: Vec<Part>,
    pub terrain_matching: bool,
    pub weight: u32,
}
#[derive(Clone, Deserialize)]
pub struct Part {
    pub template: usize,
    pub ignore_air: bool,
    pub processors: Value,
}
#[derive(Clone, Deserialize)]
pub struct Pool {
    pub fallback: String,
    pub entries: Vec<Element>,
}
#[derive(Clone, Deserialize)]
pub struct Definition {
    pub id: String,
    pub kind: String,
    pub biomes: Vec<u16>,
    pub config: Value,
    #[serde(default)]
    pub pool: String,
    #[serde(default)]
    pub template: usize,
}
#[derive(Clone, Deserialize)]
pub struct Set {
    pub id: String,
    pub placement: Value,
    pub entries: Vec<Entry>,
}
#[derive(Clone, Deserialize)]
pub struct Entry {
    pub definition: usize,
    pub weight: u32,
}
#[derive(Default)]
pub struct Cache {
    entries: HashMap<(CacheKey, usize), Arc<OnceLock<Result<Arc<Start>, String>>>>,
    order: VecDeque<(CacheKey, usize)>,
    metadata: HashMap<CacheKey, Value>,
    meta_order: VecDeque<CacheKey>,
}
impl Cache {
    pub fn metadata(&self, request: ChunkRequest) -> Option<Value> {
        self.metadata.get(&CacheKey::from(request)).cloned()
    }
    pub fn store_metadata(&mut self, request: ChunkRequest, data: Value) {
        let key = CacheKey::from(request);
        if self.metadata.insert(key, data).is_none() {
            self.meta_order.push_back(key);
        }
        while self.metadata.len() > 512 {
            if let Some(old) = self.meta_order.pop_front() {
                self.metadata.remove(&old);
            }
        }
    }
}
pub fn empty_data() -> Value {
    let mut root = nbt::compound();
    let mut structures = nbt::compound();
    nbt::put(&mut structures, "starts", nbt::compound());
    nbt::put(&mut structures, "References", nbt::compound());
    nbt::put(&mut root, "structures", structures);
    nbt::put(&mut root, "entities", nbt::list(10, Vec::new()));
    nbt::put(&mut root, "block_entities", nbt::list(10, Vec::new()));
    root
}
#[derive(Clone)]
pub struct Piece {
    pub element: Element,
    pub pos: [i32; 3],
    pub rotation: usize,
    pub bounds: [i32; 6],
    pub palette: usize,
    pub context: usize,
    pub depth: u32,
    pub priority: i32,
}
pub struct Start {
    pub definition: usize,
    pub chunk: [i32; 2],
    pub seed: u64,
    pub pieces: Vec<Piece>,
    pub terrain: Option<Heights>,
}
pub struct Heights {
    origin: [i32; 2],
    width: usize,
    values: Vec<i32>,
}
impl Heights {
    fn get(&self, x: i32, z: i32) -> i32 {
        let x = (x - self.origin[0]).clamp(0, self.width as i32 - 1) as usize;
        let z = (z - self.origin[1]).clamp(0, self.width as i32 - 1) as usize;
        self.values[z * self.width + x]
    }
}
#[derive(Default)]
pub struct ChunkData {
    pub tag: Value,
    pub starts: usize,
    pub pieces: usize,
    pub placed: usize,
}
const DIRECTIONS: [[i32; 3]; 6] = [
    [0, -1, 0],
    [0, 1, 0],
    [0, 0, -1],
    [0, 0, 1],
    [-1, 0, 0],
    [1, 0, 0],
];
fn value(v: &Value, key: &str, default: i64) -> i64 {
    v.get(key).and_then(Value::as_i64).unwrap_or(default)
}
fn rand64(mut x: u64) -> u64 {
    x = (x ^ (x >> 30)).wrapping_mul(0xbf58476d1ce4e5b9);
    x = (x ^ (x >> 27)).wrapping_mul(0x94d049bb133111eb);
    x ^ (x >> 31)
}
struct Random(u64);
impl Random {
    fn new(seed: u64) -> Self {
        Self((seed ^ 0x5deece66d) & ((1 << 48) - 1))
    }
    fn bits(&mut self, bits: u32) -> u32 {
        self.0 = (self.0.wrapping_mul(0x5deece66d).wrapping_add(11)) & ((1 << 48) - 1);
        (self.0 >> (48 - bits)) as u32
    }
    fn int(&mut self, bound: u32) -> u32 {
        if bound.is_power_of_two() {
            return ((bound as u64 * self.bits(31) as u64) >> 31) as u32;
        }
        loop {
            let bits = self.bits(31);
            let n = bits % bound;
            if bits.wrapping_sub(n).wrapping_add(bound - 1) <= i32::MAX as u32 {
                return n;
            }
        }
    }
    fn long(&mut self) -> u64 {
        let high = self.bits(32) as i32 as i64;
        let low = self.bits(32) as i32 as i64;
        high.wrapping_shl(32).wrapping_add(low) as u64
    }
    fn double(&mut self) -> f64 {
        (((self.bits(26) as u64) << 27) + self.bits(27) as u64) as f64 / (1u64 << 53) as f64
    }
    fn float(&mut self) -> f64 {
        self.bits(24) as f64 / (1u32 << 24) as f64
    }
    fn shuffle<T>(&mut self, v: &mut [T]) {
        for i in (1..v.len()).rev() {
            let j = self.int(i as u32 + 1) as usize;
            v.swap(i, j);
        }
    }
}
pub fn candidate(seed: u64, x: i32, z: i32, placement: &Value) -> [i32; 2] {
    let spacing = value(placement, "spacing", 32) as i32;
    let separation = value(placement, "separation", 8) as i32;
    let gx = x.div_euclid(spacing);
    let gz = z.div_euclid(spacing);
    let salt = value(placement, "salt", 0);
    let mut r = Random::new(
        seed.wrapping_add((gx as i64).wrapping_mul(341873128712) as u64)
            .wrapping_add((gz as i64).wrapping_mul(132897987541) as u64)
            .wrapping_add(salt as u64),
    );
    let limit = (spacing - separation) as u32;
    let mut draw = || {
        if placement["spread_type"] == "triangular" {
            (r.int(limit) + r.int(limit)) / 2
        } else {
            r.int(limit)
        }
    };
    [gx * spacing + draw() as i32, gz * spacing + draw() as i32]
}
fn rotate(p: [i32; 3], rotation: usize) -> [i32; 3] {
    match rotation {
        1 => [-p[2], p[1], p[0]],
        2 => [-p[0], p[1], -p[2]],
        3 => [p[2], p[1], -p[0]],
        _ => p,
    }
}
fn add(a: [i32; 3], b: [i32; 3]) -> [i32; 3] {
    [a[0] + b[0], a[1] + b[1], a[2] + b[2]]
}
fn sub(a: [i32; 3], b: [i32; 3]) -> [i32; 3] {
    [a[0] - b[0], a[1] - b[1], a[2] - b[2]]
}
fn direction(d: usize, r: usize) -> [i32; 3] {
    rotate(DIRECTIONS[d], r)
}
fn contains(a: [i32; 6], b: [i32; 6]) -> bool {
    (0..3).all(|i| a[i] <= b[i] && a[i + 3] >= b[i + 3])
}
fn inside(a: [i32; 6], p: [i32; 3]) -> bool {
    (0..3).all(|i| p[i] >= a[i] && p[i] <= a[i + 3])
}
fn intersects(a: [i32; 6], b: [i32; 6]) -> bool {
    (0..3).all(|i| a[i] <= b[i + 3] && a[i + 3] >= b[i])
}
fn bounds(e: &Element, pos: [i32; 3], rotation: usize, p: &Profile) -> [i32; 6] {
    let mut b = [i32::MAX, i32::MAX, i32::MAX, i32::MIN, i32::MIN, i32::MIN];
    for part in &e.parts {
        let size = p.templates[part.template].size;
        for corner in [[0, 0, 0], [size[0] - 1, size[1] - 1, size[2] - 1]] {
            let q = add(pos, rotate(corner, rotation));
            for i in 0..3 {
                b[i] = b[i].min(q[i]);
                b[i + 3] = b[i + 3].max(q[i]);
            }
        }
    }
    b
}
impl Profile {
    pub fn validate(&self, materials: usize, biomes: usize) -> Result<(), String> {
        for t in &self.templates {
            if t.size.iter().any(|n| *n <= 0 || *n > 512)
                || !t.blocks.len().is_multiple_of(4)
                || t.palettes.is_empty()
                || t.palettes.iter().any(|p| {
                    p.len() != t.palettes[0].len()
                        || p.iter().flatten().any(|id| *id as usize >= materials)
                })
                || t.blocks.chunks_exact(4).any(|b| {
                    (0..3).any(|i| b[i] < 0 || b[i] >= t.size[i])
                        || b[3] < 0
                        || b[3] as usize >= t.palettes[0].len()
                })
                || t.tags
                    .iter()
                    .any(|(i, v)| *i >= t.blocks.len() / 4 || !nbt::validate(v, 0))
                || t.entities.iter().any(|v| !nbt::validate(v, 0))
                || t.joints.iter().any(|j| {
                    j.front >= 6
                        || j.top >= 6
                        || j.final_state.iter().any(|id| *id as usize >= materials)
                })
            {
                return Err(format!("invalid structure template {}", t.id));
            }
        }
        for pool in self.pools.values() {
            for e in &pool.entries {
                if e.weight == 0 || e.parts.iter().any(|p| p.template >= self.templates.len()) {
                    return Err("invalid jigsaw pool element".into());
                }
            }
        }
        for d in &self.definitions {
            if d.biomes.iter().any(|b| *b as usize >= biomes)
                || d.kind != "jigsaw" && d.template >= self.templates.len()
            {
                return Err("invalid structure definition".into());
            }
        }
        for s in &self.sets {
            let space = value(&s.placement, "spacing", 0);
            let sep = value(&s.placement, "separation", 0);
            if space <= sep
                || space > 4096
                || sep < 0
                || s.entries.is_empty()
                || s.entries
                    .iter()
                    .any(|e| e.weight == 0 || e.definition >= self.definitions.len())
            {
                return Err("invalid structure set".into());
            }
        }
        Ok(())
    }
}
fn reach(p: &Profile, d: &Definition) -> i32 {
    if d.kind != "jigsaw" {
        return *p.templates[d.template]
            .size
            .iter()
            .step_by(2)
            .max()
            .unwrap();
    }
    let root = p.pools.get(&d.pool).map_or(0, |pool| {
        pool.entries
            .iter()
            .flat_map(|e| &e.parts)
            .map(|part| {
                let s = p.templates[part.template].size;
                s[0].max(s[2])
            })
            .max()
            .unwrap_or(0)
    });
    let distance = d.config["max_distance_from_center"]
        .as_i64()
        .unwrap_or_else(|| value(&d.config["max_distance_from_center"], "horizontal", 80))
        as i32;
    root + distance
}
fn frequency(seed: u64, x: i32, z: i32, p: &Value) -> bool {
    let probability = p["frequency"].as_f64().unwrap_or(1.0);
    if probability >= 1.0 {
        return true;
    }
    if probability <= 0.0 {
        return false;
    }
    let salt = value(p, "salt", 0);
    let salted = |x: i64, z: i64, s: i64| {
        seed.wrapping_add(x.wrapping_mul(341873128712) as u64)
            .wrapping_add(z.wrapping_mul(132897987541) as u64)
            .wrapping_add(s as u64)
    };
    match p["frequency_reduction_method"]
        .as_str()
        .unwrap_or("default")
    {
        "legacy_type_1" => {
            let mut r = Random::new(((x >> 4) as i64 ^ (((z >> 4) << 4) as i64)) as u64 ^ seed);
            r.bits(32);
            r.int((1.0 / probability) as u32) == 0
        }
        "legacy_type_2" => Random::new(salted(x as i64, z as i64, 10387320)).float() < probability,
        "legacy_type_3" => {
            let mut r = Random::new(seed);
            let a = r.long();
            let b = r.long();
            Random::new(
                (x as i64 as u64).wrapping_mul(a) ^ (z as i64 as u64).wrapping_mul(b) ^ seed,
            )
            .double()
                < probability
        }
        _ => Random::new(salted(salt, x as i64, z as i64)).float() < probability,
    }
}
fn permitted(p: &Profile, set: usize, seed: u64, x: i32, z: i32, visited: &mut Vec<usize>) -> bool {
    let s = &p.sets[set];
    if candidate(seed, x, z, &s.placement) != [x, z] || !frequency(seed, x, z, &s.placement) {
        return false;
    }
    if visited.contains(&set) {
        return false;
    }
    visited.push(set);
    if let Some(other) = s.placement["exclusion_zone"]["other_set"]
        .as_str()
        .and_then(|name| p.sets.iter().position(|s| s.id == name))
    {
        let count = value(&s.placement["exclusion_zone"], "chunk_count", 0) as i32;
        let spacing = value(&p.sets[other].placement, "spacing", 32) as i32;
        for gz in (z - count).div_euclid(spacing)..=(z + count).div_euclid(spacing) {
            for gx in (x - count).div_euclid(spacing)..=(x + count).div_euclid(spacing) {
                let at = candidate(seed, gx * spacing, gz * spacing, &p.sets[other].placement);
                if at[0] >= x - count
                    && at[0] <= x + count
                    && at[1] >= z - count
                    && at[1] <= z + count
                    && permitted(p, other, seed, at[0], at[1], visited)
                {
                    visited.pop();
                    return false;
                }
            }
        }
    }
    visited.pop();
    true
}
pub fn plans(
    engine: &TerrainEngine,
    request: ChunkRequest,
    side: i32,
) -> Result<Vec<Arc<Start>>, String> {
    let Some(profile) = engine.profile(request.reserved)? else {
        return Ok(Vec::new());
    };
    let p = &profile.structures;
    let mut candidates = Vec::new();
    for (set, s) in p.sets.iter().enumerate() {
        let spacing = value(&s.placement, "spacing", 32) as i32;
        let halo = (s
            .entries
            .iter()
            .map(|e| reach(p, &p.definitions[e.definition]))
            .max()
            .unwrap_or(0)
            + 15)
            / 16
            + 1;
        for gz in (request.chunk_z - halo).div_euclid(spacing)
            ..=(request.chunk_z + side + halo).div_euclid(spacing)
        {
            for gx in (request.chunk_x - halo).div_euclid(spacing)
                ..=(request.chunk_x + side + halo).div_euclid(spacing)
            {
                let at = candidate(request.seed, gx * spacing, gz * spacing, &s.placement);
                if at[0] >= request.chunk_x - halo
                    && at[0] <= request.chunk_x + side + halo
                    && at[1] >= request.chunk_z - halo
                    && at[1] <= request.chunk_z + side + halo
                {
                    if permitted(p, set, request.seed, at[0], at[1], &mut Vec::new()) {
                        candidates.push((set, at));
                    }
                }
            }
        }
    }
    let mut result: Vec<_> = candidates
        .par_iter()
        .map(|&(set, at)| {
            let r = ChunkRequest {
                chunk_x: at[0],
                chunk_z: at[1],
                ..request
            };
            let key = (CacheKey::from(r), set);
            let cell = {
                let mut cache = engine
                    .structures
                    .lock()
                    .map_err(|_| "structure cache poisoned")?;
                if let Some(cell) = cache.entries.get(&key) {
                    cell.clone()
                } else {
                    let cell = Arc::new(OnceLock::new());
                    cache.entries.insert(key, cell.clone());
                    cache.order.push_back(key);
                    while cache.entries.len() > 256 {
                        if let Some(old) = cache.order.pop_front() {
                            cache.entries.remove(&old);
                        }
                    }
                    cell
                }
            };
            cell.get_or_init(|| build(engine, r, set, &profile).map(Arc::new))
                .clone()
        })
        .collect::<Result<Vec<_>, String>>()?;
    result.retain(|s| !s.pieces.is_empty());
    result.sort_by_key(|s| (s.chunk[1], s.chunk[0], s.definition));
    Ok(result)
}
fn sample_height(v: &Value, r: &mut Random, request: ChunkRequest) -> i32 {
    fn anchor(v: &Value, r: ChunkRequest) -> i32 {
        if v.is_number() {
            v.as_i64().unwrap() as i32
        } else if let Some(n) = v.get("absolute") {
            n.as_i64().unwrap() as i32
        } else if let Some(n) = v.get("above_bottom") {
            r.min_y + n.as_i64().unwrap() as i32
        } else {
            r.min_y + r.height as i32 - 1 - value(v, "below_top", 0) as i32
        }
    }
    if v.is_null() {
        return 0;
    }
    if v.is_number() || v.get("absolute").is_some() {
        return anchor(v, request);
    }
    if let Some(v) = v.get("value") {
        return anchor(v, request);
    }
    let low = v
        .get("min_inclusive")
        .map(|v| anchor(v, request))
        .unwrap_or(0);
    let high = v
        .get("max_inclusive")
        .map(|v| anchor(v, request))
        .unwrap_or(low);
    low + r.int((high - low + 1).max(1) as u32) as i32
}
fn choose_aliases(v: &Value, out: &mut HashMap<String, String>, r: &mut Random) {
    if let Some(items) = v.as_array() {
        for item in items {
            choose_aliases(item, out, r);
        }
        return;
    }
    let ty = v["type"].as_str().unwrap_or("").replace("minecraft:", "");
    let choose = |items: &Value, r: &mut Random| -> Option<Value> {
        let a = items.as_array()?;
        let sum: u32 = a.iter().map(|e| value(e, "weight", 1) as u32).sum();
        if sum == 0 {
            return None;
        }
        let mut pick = r.int(sum);
        for e in a {
            let w = value(e, "weight", 1) as u32;
            if pick < w {
                return Some(e["data"].clone());
            }
            pick -= w;
        }
        None
    };
    if ty == "random_group" {
        if let Some(group) = choose(&v["groups"], r) {
            choose_aliases(&group, out, r);
        }
    } else if let Some(alias) = v["alias"].as_str() {
        let target = if ty == "random" {
            choose(&v["targets"], r)
        } else {
            Some(v["target"].clone())
        };
        if let Some(target) = target.and_then(|t| t.as_str().map(str::to_owned)) {
            out.insert(alias.into(), target);
        }
    }
}
fn shuffled(pool: Option<&Pool>, r: &mut Random) -> Vec<Element> {
    let mut out = Vec::new();
    if let Some(pool) = pool {
        for e in &pool.entries {
            for _ in 0..e.weight {
                out.push(e.clone());
            }
        }
        r.shuffle(&mut out);
    }
    out
}
fn expansion_height(
    element: &Element,
    rotation: usize,
    profile: &Profile,
    aliases: &HashMap<String, String>,
) -> i32 {
    let local = bounds(element, [0; 3], rotation, profile);
    if local[4] - local[1] + 1 > 16 {
        return 0;
    }
    let pool_height = |pool: &Pool| {
        pool.entries
            .iter()
            .filter(|e| !e.parts.is_empty())
            .map(|e| {
                let b = bounds(e, [0; 3], 0, profile);
                b[4] - b[1] + 1
            })
            .max()
            .unwrap_or(0)
    };
    // Vanilla village streets reserve space above internal building connectors.
    // Their templates are only two blocks high; using that raw box rejects houses.
    profile.templates[element.parts[0].template]
        .joints
        .iter()
        .filter(|j| {
            inside(
                local,
                add(rotate(j.pos, rotation), direction(j.front, rotation)),
            )
        })
        .filter_map(|j| profile.pools.get(aliases.get(&j.pool).unwrap_or(&j.pool)))
        .map(|pool| pool_height(pool).max(profile.pools.get(&pool.fallback).map_or(0, pool_height)))
        .max()
        .unwrap_or(0)
}
fn build(
    engine: &TerrainEngine,
    request: ChunkRequest,
    set: usize,
    profile: &WorldProfile,
) -> Result<Start, String> {
    let p = &profile.structures;
    let s = &p.sets[set];
    let seed = rand64(
        request.seed
            ^ (request.chunk_x as i64 as u64).wrapping_mul(341873128712)
            ^ (request.chunk_z as i64 as u64).wrapping_mul(132897987541)
            ^ value(&s.placement, "salt", 0) as u64,
    );
    let mut out = Start {
        definition: 0,
        chunk: [request.chunk_x, request.chunk_z],
        seed,
        pieces: Vec::new(),
        terrain: None,
    };
    let mut random = Random::new(seed);
    let columns = engine.sample_columns(&[request])?;
    let biome = columns[8 * 16 + 8].biome() as u16;
    let mut choices = s.entries.clone();
    let definition = loop {
        if choices.is_empty() {
            return Ok(out);
        }
        let mut pick = random.int(choices.iter().map(|e| e.weight).sum());
        let mut at = 0;
        for (i, e) in choices.iter().enumerate() {
            if pick < e.weight {
                at = i;
                break;
            }
            pick -= e.weight;
        }
        let choice = choices.remove(at).definition;
        let d = &p.definitions[choice];
        let candidate_biome = if d.kind == "jigsaw"
            && d.config.get("project_start_to_heightmap").is_none()
            && profile.geology.caves_enabled(profile)
        {
            let y = sample_height(&d.config["start_height"], &mut Random::new(seed), request);
            let (_, mask) = engine.terrain_field(request, 1)?;
            mask.as_ref()
                .and_then(|m| m.biome(request.chunk_x * 16 + 8, y, request.chunk_z * 16 + 8))
                .unwrap_or(biome)
        } else {
            biome
        };
        if d.biomes.contains(&candidate_biome) {
            break choice;
        }
    };
    out.definition = definition;
    let d = &p.definitions[definition];
    let root = if d.kind == "jigsaw" {
        let pool = p.pools.get(&d.pool);
        let total = pool.map_or(0, |p| p.entries.iter().map(|e| e.weight).sum());
        if total == 0 {
            return Ok(out);
        }
        let mut choice = random.int(total);
        pool.unwrap()
            .entries
            .iter()
            .find(|e| {
                if choice < e.weight {
                    true
                } else {
                    choice -= e.weight;
                    false
                }
            })
            .unwrap()
            .clone()
    } else {
        Element {
            parts: vec![Part {
                template: d.template,
                ignore_air: false,
                processors: json!([]),
            }],
            terrain_matching: false,
            weight: 1,
        }
    };
    if root.parts.is_empty() {
        return Ok(out);
    }
    let rotation = random.int(4) as usize;
    let template = &p.templates[root.parts[0].template];
    let height = sample_height(&d.config["start_height"], &mut random, request);
    let project = d.kind != "jigsaw" || d.config.get("project_start_to_heightmap").is_some();
    let mut pos = [request.chunk_x * 16, height, request.chunk_z * 16];
    if project {
        let b = bounds(&root, pos, rotation, p);
        let center = [(b[0] + b[3]) / 2, (b[2] + b[5]) / 2];
        // A dedicated height tile is cached per structure, never a CPU noise approximation.
        let radius = if d.kind == "jigsaw" {
            d.config["max_distance_from_center"]
                .as_i64()
                .unwrap_or_else(|| value(&d.config["max_distance_from_center"], "horizontal", 80))
                as i32
        } else {
            template.size[0].max(template.size[2]) / 2
        };
        let radius = (radius + 15) / 16 + 1;
        let origin = ChunkRequest {
            chunk_x: center[0].div_euclid(16) - radius,
            chunk_z: center[1].div_euclid(16) - radius,
            ..request
        };
        let side = (2 * radius + 1) as u32;
        let field = engine.sample_tile(origin, side)?;
        let width = side as usize * 16;
        let mut heights = vec![0; width * width];
        for chunk in 0..(side * side) as usize {
            for z in 0..16 {
                for x in 0..16 {
                    heights[(chunk / side as usize * 16 + z) * width
                        + chunk % side as usize * 16
                        + x] = {
                        let column = field[chunk * 256 + z * 16 + x];
                        if d.config["project_start_to_heightmap"]
                            .as_str()
                            .is_some_and(|name| name.starts_with("WORLD_SURFACE"))
                        {
                            column.surface_height(Some(profile))
                        } else {
                            column.height
                        }
                    };
                }
            }
        }
        let terrain = Heights {
            origin: [origin.chunk_x * 16, origin.chunk_z * 16],
            width,
            values: heights,
        };
        pos[1] += terrain.get(center[0], center[1]) - template.ground;
        out.terrain = Some(terrain);
    }
    if let Some(name) = d.config["start_jigsaw_name"].as_str() {
        if let Some(j) = template.joints.iter().find(|j| j.name == name) {
            pos = sub(pos, rotate(j.pos, rotation));
        } else {
            return Ok(out);
        }
    }
    let b = bounds(&root, pos, rotation, p);
    if b[1] < request.min_y || b[4] >= request.min_y + request.height as i32 {
        return Ok(out);
    }
    out.pieces.push(Piece {
        element: root,
        pos,
        rotation,
        bounds: b,
        palette: random.int(template.palettes.len() as u32) as usize,
        context: 0,
        depth: 0,
        priority: 0,
    });
    if d.kind != "jigsaw" {
        return Ok(out);
    }
    let center = [(b[0] + b[3]) / 2, (b[2] + b[5]) / 2];
    let distance = d.config["max_distance_from_center"]
        .as_i64()
        .unwrap_or_else(|| value(&d.config["max_distance_from_center"], "horizontal", 80))
        as i32;
    let mut aliases = HashMap::new();
    choose_aliases(&d.config["pool_aliases"], &mut aliases, &mut random);
    let max_depth = value(&d.config, "size", 0) as u32;
    let mut queue = vec![0usize];
    while !queue.is_empty() {
        queue.sort_by_key(|i| -out.pieces[*i].priority);
        let index = queue.remove(0);
        let source = out.pieces[index].clone();
        let st = &p.templates[source.element.parts[0].template];
        let mut joints = st.joints.iter().collect::<Vec<_>>();
        random.shuffle(&mut joints);
        joints.sort_by_key(|j| -j.selection_priority);
        'connector: for j in joints {
            let jp = add(source.pos, rotate(j.pos, source.rotation));
            let front = direction(j.front, source.rotation);
            let target = add(jp, front);
            let name = aliases.get(&j.pool).unwrap_or(&j.pool);
            let pool = p.pools.get(name);
            let fallback = pool.and_then(|p2| p.pools.get(&p2.fallback));
            let mut elements = if source.depth < max_depth {
                shuffled(pool, &mut random)
            } else {
                Vec::new()
            };
            elements.extend(shuffled(fallback, &mut random));
            for element in elements {
                if element.parts.is_empty() {
                    break;
                }
                let mut rotations = [0, 1, 2, 3];
                random.shuffle(&mut rotations);
                for rotation in rotations {
                    let t = &p.templates[element.parts[0].template];
                    let expansion = if d.config["use_expansion_hack"].as_bool().unwrap_or(false) {
                        expansion_height(&element, rotation, p, &aliases)
                    } else {
                        0
                    };
                    let mut targets = t.joints.iter().collect::<Vec<_>>();
                    random.shuffle(&mut targets);
                    targets.sort_by_key(|j| -j.selection_priority);
                    for other in targets {
                        let facing = direction(other.front, rotation);
                        if facing != [-front[0], -front[1], -front[2]]
                            || j.target != other.name
                            || j.joint == "aligned"
                                && direction(j.top, source.rotation)
                                    != direction(other.top, rotation)
                        {
                            continue;
                        }
                        let mut pos = sub(target, rotate(other.pos, rotation));
                        if source.element.terrain_matching || element.terrain_matching {
                            if let Some(h) = &out.terrain {
                                pos[1] = h.get(jp[0], jp[2]) - other.pos[1];
                            }
                        }
                        let mut box2 = bounds(&element, pos, rotation, p);
                        if expansion > 0 {
                            box2[4] = box2[4].max(box2[1] + expansion + 1);
                        }
                        if box2[0] < center[0] - distance
                            || box2[3] > center[0] + distance
                            || box2[2] < center[1] - distance
                            || box2[5] > center[1] + distance
                            || box2[1] < request.min_y
                            || box2[4] >= request.min_y + request.height as i32
                        {
                            continue;
                        }
                        let context = if inside(source.bounds, target) {
                            index + 1
                        } else {
                            source.context
                        };
                        if context > 0 && !contains(out.pieces[context - 1].bounds, box2) {
                            continue;
                        }
                        if out.pieces.iter().any(|existing| {
                            existing.context == context && intersects(existing.bounds, box2)
                        }) {
                            continue;
                        }
                        let piece = Piece {
                            element: element.clone(),
                            pos,
                            rotation,
                            bounds: box2,
                            palette: random.int(t.palettes.len() as u32) as usize,
                            context,
                            depth: source.depth + 1,
                            priority: j.placement_priority,
                        };
                        out.pieces.push(piece);
                        if source.depth < max_depth {
                            queue.push(out.pieces.len() - 1);
                        }
                        continue 'connector;
                    }
                }
            }
        }
    }
    if let Some(terrain) = &out.terrain {
        for piece in &mut out.pieces {
            if piece.element.terrain_matching {
                let mut low = i32::MAX;
                let mut high = i32::MIN;
                for z in piece.bounds[2]..=piece.bounds[5] {
                    for x in piece.bounds[0]..=piece.bounds[3] {
                        let y = terrain.get(x, z) - 1;
                        low = low.min(y);
                        high = high.max(y);
                    }
                }
                let height = piece.bounds[4] - piece.bounds[1];
                piece.bounds[1] = low;
                piece.bounds[4] = high + height;
            }
        }
    }
    Ok(out)
}

fn block_name(profile: &WorldProfile, material: u16) -> &str {
    profile.materials[material as usize]
        .as_str()
        .or_else(|| profile.materials[material as usize]["id"].as_str())
        .unwrap_or("")
}
fn predicate(
    v: &Value,
    material: u16,
    rotation: usize,
    profile: &WorldProfile,
    r: &mut Random,
) -> bool {
    let kind = v["predicate_type"]
        .as_str()
        .unwrap_or("minecraft:always_true")
        .trim_start_matches("minecraft:");
    let okay = match kind {
        "block_match" | "random_block_match" => {
            block_name(profile, material) == v["block"].as_str().unwrap_or("")
        }
        "blockstate_match" | "random_blockstate_match" => {
            v["block_state"]["retina_rotations"][rotation].as_u64() == Some(material as u64)
        }
        "tag_match" => v["retina_matching"]
            .as_array()
            .is_some_and(|a| a.iter().any(|n| n.as_u64() == Some(material as u64))),
        "always_true" => true,
        _ => false,
    };
    okay && (!kind.starts_with("random_") || r.float() < v["probability"].as_f64().unwrap_or(1.0))
}
struct ProcessorContext {
    position: [i32; 3],
    origin: [i32; 3],
    counts: HashMap<usize, u32>,
}
fn position_predicate(v: &Value, ctx: &ProcessorContext, r: &mut Random) -> bool {
    let kind = v["predicate_type"]
        .as_str()
        .unwrap_or("minecraft:always_true")
        .trim_start_matches("minecraft:");
    if kind == "always_true" {
        return true;
    }
    let delta = sub(ctx.position, ctx.origin);
    let distance = if kind == "axis_aligned_linear_pos" {
        delta[match v["axis"].as_str().unwrap_or("y") {
            "x" => 0,
            "z" => 2,
            _ => 1,
        }]
        .abs()
    } else if kind == "linear_pos" {
        delta.iter().map(|n| n.abs()).sum()
    } else {
        return false;
    } as f64;
    let min = v["min_dist"].as_f64().unwrap_or(0.0);
    let max = v["max_dist"].as_f64().unwrap_or(100.0);
    let blend = ((distance - min) / (max - min).max(1.0)).clamp(0.0, 1.0);
    r.float()
        < v["min_chance"].as_f64().unwrap_or(0.0) * (1.0 - blend)
            + v["max_chance"].as_f64().unwrap_or(0.0) * blend
}
fn process(
    v: &Value,
    material: &mut u16,
    world: u16,
    tag: &mut Option<Value>,
    rotation: usize,
    profile: &WorldProfile,
    r: &mut Random,
    ctx: &mut ProcessorContext,
) -> bool {
    if let Some(list) = v.as_array() {
        for p in list {
            if !process(p, material, world, tag, rotation, profile, r, ctx) {
                return false;
            }
        }
        return true;
    }
    if let Some(list) = v.get("processors") {
        return process(list, material, world, tag, rotation, profile, r, ctx);
    }
    match v["processor_type"]
        .as_str()
        .unwrap_or("")
        .trim_start_matches("minecraft:")
    {
        "block_rot" => {
            let rottable = v.get("retina_rottable").is_none_or(|a| {
                a.as_array()
                    .is_some_and(|a| a.iter().any(|n| n.as_u64() == Some(*material as u64)))
            });
            if rottable && r.float() > v["integrity"].as_f64().unwrap_or(1.0) {
                return false;
            }
        }
        "rule" => {
            if let Some(rules) = v["rules"].as_array() {
                for rule in rules {
                    if predicate(&rule["input_predicate"], *material, rotation, profile, r)
                        && predicate(&rule["location_predicate"], world, rotation, profile, r)
                        && position_predicate(&rule["position_predicate"], ctx, r)
                    {
                        if let Some(id) =
                            rule["output_state"]["retina_rotations"][rotation].as_u64()
                        {
                            if block_name(profile, *material) != block_name(profile, id as u16) {
                                *tag = rule["output_state"].get("retina_entity").cloned();
                            }
                            *material = id as u16;
                        }
                        let modifier = &rule["block_entity_modifier"];
                        match modifier["type"]
                            .as_str()
                            .unwrap_or("")
                            .trim_start_matches("minecraft:")
                        {
                            "clear" => *tag = None,
                            "append_static" => {
                                if let Some(append) = modifier.get("retina_nbt") {
                                    let t = tag.get_or_insert_with(nbt::compound);
                                    for (key, value) in append[1].as_object().unwrap() {
                                        nbt::put(t, key, value.clone());
                                    }
                                }
                            }
                            _ => {}
                        }
                        if let Some(loot) = modifier["loot_table"].as_str() {
                            let t = tag.get_or_insert_with(nbt::compound);
                            nbt::put(t, "LootTable", nbt::string(loot));
                        }
                        break;
                    }
                }
            }
        }
        "capped" => {
            let key = v as *const Value as usize;
            let limit = v["limit"]
                .as_u64()
                .or_else(|| v["limit"]["value"].as_u64())
                .unwrap_or(1) as u32;
            if *ctx.counts.get(&key).unwrap_or(&0) < limit {
                let before = (*material, tag.clone());
                let keep = process(
                    &v["delegate"],
                    material,
                    world,
                    tag,
                    rotation,
                    profile,
                    r,
                    ctx,
                );
                if !keep || before != (*material, tag.clone()) {
                    *ctx.counts.entry(key).or_default() += 1;
                }
                return keep;
            }
        }
        "protected_blocks" => {
            if v["retina_protected"]
                .as_array()
                .is_some_and(|a| a.iter().any(|n| n.as_u64() == Some(world as u64)))
            {
                return false;
            }
        }
        _ => {}
    }
    true
}
pub fn start_data(request: ChunkRequest, profile: &WorldProfile, plans: &[Arc<Start>]) -> Value {
    let p = &profile.structures;
    let chunk_bounds = [
        request.chunk_x * 16,
        request.min_y,
        request.chunk_z * 16,
        request.chunk_x * 16 + 15,
        request.min_y + request.height as i32 - 1,
        request.chunk_z * 16 + 15,
    ];
    let mut starts = nbt::compound();
    let mut references = nbt::compound();
    for start in plans {
        let definition = &p.definitions[start.definition];
        let relevant = start
            .pieces
            .iter()
            .any(|piece| intersects(piece.bounds, chunk_bounds));
        if start.chunk == [request.chunk_x, request.chunk_z] {
            let mut tag = nbt::compound();
            nbt::put(&mut tag, "id", nbt::string(&definition.id));
            nbt::put(&mut tag, "ChunkX", nbt::int(start.chunk[0]));
            nbt::put(&mut tag, "ChunkZ", nbt::int(start.chunk[1]));
            nbt::put(&mut tag, "references", nbt::int(0));
            let children = start
                .pieces
                .iter()
                .map(|piece| {
                    let mut tag = nbt::compound();
                    nbt::put(&mut tag, "id", nbt::string("retina:template"));
                    nbt::put(&mut tag, "BB", json!([11, piece.bounds]));
                    nbt::put(&mut tag, "O", nbt::int(-1));
                    nbt::put(&mut tag, "GD", nbt::int(piece.depth as i32));
                    nbt::put(
                        &mut tag,
                        "template",
                        nbt::string(&p.templates[piece.element.parts[0].template].id),
                    );
                    tag
                })
                .collect();
            nbt::put(&mut tag, "Children", nbt::list(10, children));
            nbt::put(&mut starts, &definition.id, tag);
        }
        if !relevant {
            continue;
        }
        let key = ((start.chunk[0] as u32 as u64) | ((start.chunk[1] as u32 as u64) << 32)) as i64;
        references[1]
            .as_object_mut()
            .unwrap()
            .entry(&definition.id)
            .or_insert_with(|| json!([12, []]))[1]
            .as_array_mut()
            .unwrap()
            .push(json!(key));
    }
    let mut root = empty_data();
    let mut structures = nbt::compound();
    nbt::put(&mut structures, "starts", starts);
    nbt::put(&mut structures, "References", references);
    nbt::put(&mut root, "structures", structures);
    root
}

pub fn apply(
    request: ChunkRequest,
    profile: &WorldProfile,
    plans: &[Arc<Start>],
    mut blocks: Option<&mut [u16]>,
) -> ChunkData {
    let p = &profile.structures;
    let chunk_bounds = [
        request.chunk_x * 16,
        request.min_y,
        request.chunk_z * 16,
        request.chunk_x * 16 + 15,
        request.min_y + request.height as i32 - 1,
        request.chunk_z * 16 + 15,
    ];
    let mut root = start_data(request, profile, plans);
    let starts_count = nbt::field(nbt::field(&root, "structures").unwrap(), "starts").unwrap()[1]
        .as_object()
        .unwrap()
        .len();
    let mut entities = Vec::new();
    let mut block_entities = HashMap::<[i32; 3], Value>::new();
    let mut count = 0;
    let mut placed = 0;
    for start in plans {
        for piece in &start.pieces {
            if !intersects(piece.bounds, chunk_bounds) {
                continue;
            }
            count += 1;
            for part in &piece.element.parts {
                let template = &p.templates[part.template];
                let palette = &template.palettes[piece.palette % template.palettes.len()];
                let mut ctx = ProcessorContext {
                    position: piece.pos,
                    origin: piece.pos,
                    counts: HashMap::new(),
                };
                // Approximate the registered terrain beard with short supports below rigid floors.
                // Heights were sampled once for the shared start; no CPU noise or per-block GPU calls.
                let definition = &p.definitions[start.definition];
                if !piece.element.terrain_matching
                    && (definition.kind != "jigsaw"
                        || definition.config["terrain_adaptation"] != "none")
                {
                    if let (Some(terrain), Some(output)) = (&start.terrain, blocks.as_deref_mut()) {
                        for block in template.blocks.chunks_exact(4).filter(|b| b[1] == 0) {
                            let pos = add(
                                piece.pos,
                                rotate([block[0], block[1], block[2]], piece.rotation),
                            );
                            if pos[0] < chunk_bounds[0]
                                || pos[0] > chunk_bounds[3]
                                || pos[2] < chunk_bounds[2]
                                || pos[2] > chunk_bounds[5]
                            {
                                continue;
                            }
                            let material = palette[block[3] as usize][piece.rotation];
                            let name = block_name(profile, material);
                            if material == 0
                                || matches!(
                                    name,
                                    "minecraft:water"
                                        | "minecraft:lava"
                                        | "minecraft:jigsaw"
                                        | "minecraft:structure_void"
                                        | "minecraft:structure_block"
                                )
                                || profile.material_flags[material as usize] & (4 | 8 | 16) != 0
                            {
                                continue;
                            }
                            let bottom = (pos[1] - 12)
                                .max(terrain.get(pos[0], pos[2]) - 1)
                                .max(request.min_y);
                            for y in
                                (bottom..pos[1].min(request.min_y + request.height as i32)).rev()
                            {
                                let index = (y - request.min_y) as usize * 256
                                    + (pos[2] - chunk_bounds[2]) as usize * 16
                                    + (pos[0] - chunk_bounds[0]) as usize;
                                if output[index] != 0
                                    && !matches!(
                                        block_name(profile, output[index]),
                                        "minecraft:water" | "minecraft:lava"
                                    )
                                    && profile.material_flags[output[index] as usize] & (4 | 8 | 16)
                                        == 0
                                {
                                    break;
                                }
                                output[index] = material;
                                block_entities.remove(&[pos[0], y, pos[2]]);
                            }
                        }
                    }
                }

                for (i, block) in template.blocks.chunks_exact(4).enumerate() {
                    let mut pos = add(
                        piece.pos,
                        rotate([block[0], block[1], block[2]], piece.rotation),
                    );
                    if piece.element.terrain_matching {
                        if let Some(terrain) = &start.terrain {
                            pos[1] = terrain.get(pos[0], pos[2]) - 1 + block[1];
                        }
                    }
                    let in_chunk = inside(chunk_bounds, pos);
                    let index = if in_chunk {
                        (pos[1] - request.min_y) as usize * 256
                            + (pos[2] - chunk_bounds[2]) as usize * 16
                            + (pos[0] - chunk_bounds[0]) as usize
                    } else {
                        0
                    };
                    let mut material = palette[block[3] as usize][piece.rotation];
                    let name = block_name(profile, material);
                    if name == "minecraft:structure_void"
                        || name == "minecraft:structure_block"
                        || part.ignore_air && material == 0
                    {
                        continue;
                    }
                    if name == "minecraft:jigsaw" {
                        material = template
                            .joints
                            .iter()
                            .find(|j| j.pos == [block[0], block[1], block[2]])
                            .map_or(0, |j| j.final_state[piece.rotation]);
                    }
                    let mut tag = template.tags.get(&i).cloned();
                    let mut r = Random::new(
                        start.seed
                            ^ rand64(pos[0] as i64 as u64)
                            ^ rand64(pos[1] as i64 as u64).rotate_left(21)
                            ^ rand64(pos[2] as i64 as u64).rotate_left(42),
                    );
                    let world = if in_chunk {
                        blocks.as_deref().map_or(profile.stone, |b| b[index])
                    } else {
                        profile.stone
                    };
                    ctx.position = pos;
                    if !process(
                        &part.processors,
                        &mut material,
                        world,
                        &mut tag,
                        piece.rotation,
                        profile,
                        &mut r,
                        &mut ctx,
                    ) {
                        continue;
                    }
                    if !in_chunk {
                        continue;
                    }
                    if let Some(b) = blocks.as_deref_mut() {
                        b[index] = material;
                    }
                    placed += 1;
                    block_entities.remove(&pos);
                    if material != 0 {
                        if let Some(mut tag) = tag {
                            for i in 0..3 {
                                nbt::put(&mut tag, ["x", "y", "z"][i], nbt::int(pos[i]));
                            }
                            nbt::put(&mut tag, "keepPacked", json!([1, 1]));
                            if nbt::field(&tag, "LootTable").is_some() {
                                nbt::put(
                                    &mut tag,
                                    "LootTableSeed",
                                    nbt::long(rand64(start.seed ^ index as u64) as i64),
                                );
                            }
                            block_entities.insert(pos, tag);
                        }
                    }
                }
                for (i, entity) in template.entities.iter().enumerate() {
                    let Some(tag) = nbt::field(entity, "nbt") else {
                        continue;
                    };
                    let mut tag = tag.clone();
                    let values = nbt::field(entity, "pos")
                        .map(nbt::list_values)
                        .unwrap_or(&[]);
                    if values.len() != 3 {
                        continue;
                    }
                    let raw = [
                        nbt::number(&values[0]),
                        nbt::number(&values[1]),
                        nbt::number(&values[2]),
                    ];
                    let q = match piece.rotation {
                        1 => [1.0 - raw[2], raw[1], raw[0]],
                        2 => [1.0 - raw[0], raw[1], 1.0 - raw[2]],
                        3 => [raw[2], raw[1], 1.0 - raw[0]],
                        _ => raw,
                    };
                    let pos = [
                        q[0] + piece.pos[0] as f64,
                        q[1] + piece.pos[1] as f64,
                        q[2] + piece.pos[2] as f64,
                    ];
                    if (pos[0].floor() as i32).div_euclid(16) != request.chunk_x
                        || (pos[2].floor() as i32).div_euclid(16) != request.chunk_z
                    {
                        continue;
                    }
                    nbt::put(
                        &mut tag,
                        "Pos",
                        nbt::list(6, pos.into_iter().map(|v| json!([6, v])).collect()),
                    );
                    let a = rand64(
                        start.seed
                            ^ part.template as u64
                            ^ (i as u64).rotate_left(17)
                            ^ piece.pos[0] as u64
                            ^ (piece.pos[1] as u64).rotate_left(37)
                            ^ (piece.rotation as u64).rotate_left(11),
                    );
                    let b = rand64(a ^ piece.pos[2] as u64);
                    nbt::put(
                        &mut tag,
                        "UUID",
                        json!([11, [a as i32, (a >> 32) as i32, b as i32, (b >> 32) as i32]]),
                    );
                    if let Some(rotation) = nbt::field(&tag, "Rotation").cloned() {
                        let values = nbt::list_values(&rotation);
                        if values.len() == 2 {
                            nbt::put(
                                &mut tag,
                                "Rotation",
                                nbt::list(
                                    5,
                                    vec![
                                        json!([
                                            5,
                                            nbt::number(&values[0]) + piece.rotation as f64 * 90.0
                                        ]),
                                        values[1].clone(),
                                    ],
                                ),
                            );
                        }
                    }
                    entities.push(tag);
                }
            }
        }
    }
    let mut block_entities: Vec<_> = block_entities.into_iter().collect();
    block_entities.sort_by_key(|(pos, _)| *pos);
    nbt::put(
        &mut root,
        "block_entities",
        nbt::list(10, block_entities.into_iter().map(|(_, tag)| tag).collect()),
    );
    nbt::put(&mut root, "entities", nbt::list(10, entities));
    ChunkData {
        tag: root,
        starts: starts_count,
        pieces: count,
        placed,
    }
}
#[cfg(test)]
mod tests {
    use super::*;
    #[test]
    fn rotations_and_negative_spread_cells_are_consistent() {
        assert_eq!(rotate([3, 2, 7], 1), [-7, 2, 3]);
        let p = json!({"spacing":32,"separation":8,"salt":10387312});
        for seed in [0, 1, u64::MAX] {
            for x in -100..100 {
                let a = candidate(seed, x, -x, &p);
                assert_eq!(a, candidate(seed, a[0], a[1], &p));
                assert_eq!(a[0].div_euclid(32), x.div_euclid(32));
            }
        }
    }
}
