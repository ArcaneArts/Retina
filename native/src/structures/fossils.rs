//! Nether fossil floor search and positional dried-ghast placement over native columns.
use super::*;
use crate::decoration::placement_height::{HeightProvider, HeightRandom};

#[derive(Clone, Deserialize)]
pub struct Profile {
    pub templates: Vec<usize>,
    pub floor: Vec<u16>,
    pub air: Vec<u16>,
    pub dried_ghast: [u16; 4],
}
impl Profile {
    pub(super) fn validate(&self, p: &super::Profile, materials: usize) -> Result<(), String> {
        if self.templates.len() != 14
            || self.templates.iter().any(|&t| t >= p.templates.len())
            || self
                .floor
                .iter()
                .chain(&self.air)
                .chain(&self.dried_ghast)
                .any(|&id| id as usize >= materials)
        {
            return Err("invalid Nether fossil templates or materials".into());
        }
        for d in &p.definitions {
            if d.kind == "nether_fossil" && !d.unimplemented {
                let height: HeightProvider = serde_json::from_value(d.config["height"].clone())
                    .map_err(|e| format!("Nether fossil height provider {}: {e}", d.id))?;
                if !height.validate() {
                    return Err(format!("invalid Nether fossil height provider {}", d.id));
                }
            }
        }
        Ok(())
    }
}
impl HeightRandom for Random {
    fn below(&mut self, bound: i32) -> i32 {
        self.int(bound as u32) as i32
    }
    fn weighted(&mut self, total: u64) -> u64 {
        self.int(total as u32) as u64
    }
}

pub(super) fn build(
    engine: &TerrainEngine,
    request: ChunkRequest,
    profile: &WorldProfile,
    definition: usize,
    seed: u64,
    mut random: Random,
) -> Result<Start, String> {
    let p = &profile.structures;
    let kit = p.fossils.as_ref().ok_or("missing Nether fossil profile")?;
    let d = &p.definitions[definition];
    let mut start = empty_start(request, seed, definition);
    let x = random.int(16) as i32;
    let z = random.int(16) as i32;
    let height: HeightProvider =
        serde_json::from_value(d.config["height"].clone()).map_err(|e| e.to_string())?;
    let mut y = height.sample(&mut random, request);
    let mut column = vec![0; request.height as usize];
    engine.base_column(request, (z * 16 + x) as u32, &mut column)?;
    let material = |y: i32| {
        column
            .get(y.wrapping_sub(request.min_y) as usize)
            .copied()
            .unwrap_or(0)
    };
    while y > profile.sea_level {
        let current = material(y);
        y -= 1;
        if kit.air.contains(&current) && kit.floor.contains(&material(y)) {
            break;
        }
    }
    if y <= profile.sea_level {
        return Ok(start);
    }
    let pos = [request.chunk_x * 16 + x, y, request.chunk_z * 16 + z];
    let biome = engine.query_points(
        request,
        &[crate::queries::Point {
            x: pos[0],
            y,
            z: pos[2],
        }],
        crate::queries::Kind::Biome,
    )?[0]
        .biome() as u16;
    if !d.biomes.contains(&biome) {
        return Ok(start);
    }
    let rotation = random.int(4) as usize;
    let template = kit.templates[random.int(14) as usize];
    let element = Arc::new(Element {
        parts: vec![Part {
            template,
            ignore_air: true,
            finalize_entities: false,
            processors: json!([]),
            program: Arc::default(),
        }],
        terrain_matching: false,
        weight: 1,
    });
    let bounds = bounds(&element, pos, rotation, p);
    let palette =
        Random::new(position_seed(pos)).int(p.templates[template].palettes.len() as u32) as usize;
    start.pieces.push(Piece {
        element,
        pos,
        rotation,
        bounds,
        palette,
        context: 0,
        depth: 0,
        priority: 0,
        fortress: None,
    });
    Ok(start)
}

pub(super) fn dried_ghast(
    piece: &Piece,
    kit: &Profile,
    request: ChunkRequest,
    blocks: &mut [u16],
) -> Option<usize> {
    let b = piece.bounds;
    let center = std::array::from_fn(|i| b[i] + (b[i + 3] - b[i] + 1) / 2);
    let mut random = Random::new(Random::new(request.seed).long() ^ position_seed(center));
    if random.float() >= 0.5 {
        return None;
    }
    let x = b[0] + random.int((b[3] - b[0] + 1) as u32) as i32;
    let z = b[2] + random.int((b[5] - b[2] + 1) as u32) as i32;
    let y = b[1];
    if x.div_euclid(16) != request.chunk_x
        || z.div_euclid(16) != request.chunk_z
        || y < request.min_y
        || y >= request.min_y + request.height as i32
    {
        return None;
    }
    let index = (y - request.min_y) as usize * 256
        + z.rem_euclid(16) as usize * 16
        + x.rem_euclid(16) as usize;
    if !kit.air.contains(&blocks[index]) {
        return None;
    }
    blocks[index] = kit.dried_ghast[random.int(4) as usize];
    Some(index)
}
