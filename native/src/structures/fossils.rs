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

pub(super) struct Prepared {
    random: Random,
    position: Option<[i32; 3]>,
    biome: Option<u16>,
}

fn begin(
    request: ChunkRequest,
    profile: &WorldProfile,
    definition: usize,
    mut random: Random,
) -> Result<(Random, i32, i32, i32), String> {
    let x = random.int(16) as i32;
    let z = random.int(16) as i32;
    let height: HeightProvider =
        serde_json::from_value(profile.structures.definitions[definition].config["height"].clone())
            .map_err(|e| e.to_string())?;
    let y = height.sample(&mut random, request);
    Ok((random, x, y, z))
}

fn floor(
    request: ChunkRequest,
    profile: &WorldProfile,
    x: i32,
    mut y: i32,
    z: i32,
    mut material: impl FnMut(i32) -> u16,
) -> Option<[i32; 3]> {
    let kit = profile
        .structures
        .fossils
        .as_ref()
        .expect("validated fossil profile");
    while y > profile.sea_level {
        let current = material(y);
        y -= 1;
        if kit.air.contains(&current) && kit.floor.contains(&material(y)) {
            break;
        }
    }
    (y > profile.sea_level).then(|| [request.chunk_x * 16 + x, y, request.chunk_z * 16 + z])
}

pub(super) fn build(
    engine: &TerrainEngine,
    request: ChunkRequest,
    profile: &WorldProfile,
    definition: usize,
    seed: u64,
    random: Random,
) -> Result<Start, String> {
    let (random, x, y, z) = begin(request, profile, definition, random)?;
    let mut column = vec![0; request.height as usize];
    engine.base_column(request, (z * 16 + x) as u32, &mut column)?;
    let position = floor(request, profile, x, y, z, |y| {
        column
            .get(y.wrapping_sub(request.min_y) as usize)
            .copied()
            .unwrap_or(0)
    });
    let biome = match position {
        Some(pos) => Some(
            engine.query_points(
                request,
                &[crate::queries::Point {
                    x: pos[0],
                    y: pos[1],
                    z: pos[2],
                }],
                crate::queries::Kind::Biome,
            )?[0]
                .biome() as u16,
        ),
        None => None,
    };
    Ok(finish(
        request,
        profile,
        definition,
        seed,
        &Prepared {
            random,
            position,
            biome,
        },
    ))
}

/// Prepare independent fossil probes in material tiles and sparse biome batches.
/// Selection streams have already consumed their weighted-choice draw. Template
/// and rotation draws remain in finish, exactly after the original biome check.
pub(super) fn prepare_batch(
    engine: &TerrainEngine,
    profile: &WorldProfile,
    inputs: &[(ChunkRequest, usize, Random)],
    terrain: Option<&crate::terrain_job::TerrainJob<'_>>,
) -> Result<Vec<Prepared>, String> {
    let mut prepared = Vec::with_capacity(inputs.len());
    let mut columns = Vec::with_capacity(inputs.len());
    let mut tiles = std::collections::BTreeMap::<(i32, i32), Vec<usize>>::new();
    for (index, (request, definition, random)) in inputs.iter().enumerate() {
        let (random, x, y, z) = begin(*request, profile, *definition, random.clone())?;
        prepared.push(Prepared {
            random,
            position: None,
            biome: None,
        });
        columns.push((x, y, z));
        tiles
            .entry((
                request.chunk_x.div_euclid(16),
                request.chunk_z.div_euclid(16),
            ))
            .or_default()
            .push(index);
    }
    let material_layers = profile
        .registry_program
        .as_ref()
        .is_some_and(|p| p.material_layers);
    for ((tx, tz), indices) in tiles {
        let request = inputs[indices[0]].0;
        let mask = if material_layers {
            let mask = if let Some(terrain) = terrain {
                terrain.mask(tx * 16, tz * 16)?
            } else {
                engine
                    .terrain_field(
                        ChunkRequest {
                            chunk_x: tx * 16,
                            chunk_z: tz * 16,
                            ..request
                        },
                        16,
                    )?
                    .1
            };
            Some(mask.ok_or("GPU fossil material tile missing")?)
        } else {
            None
        };

        for index in indices {
            let request = inputs[index].0;
            let (x, y, z) = columns[index];
            prepared[index].position = if let Some(mask) = &mask {
                let runs = mask
                    .material_runs(request.chunk_x * 16 + x, request.chunk_z * 16 + z)
                    .ok_or("GPU fossil material column missing")?;
                floor(request, profile, x, y, z, |y| {
                    let relative = y.wrapping_sub(request.min_y) as u32;
                    if relative >= request.height {
                        return 0;
                    }
                    runs[runs.partition_point(|run| run >> 16 > relative)] as u16
                })
            } else {
                let mut column = vec![0; request.height as usize];
                engine.base_column(request, (z * 16 + x) as u32, &mut column)?;
                floor(request, profile, x, y, z, |y| {
                    column
                        .get(y.wrapping_sub(request.min_y) as usize)
                        .copied()
                        .unwrap_or(0)
                })
            };
        }
    }
    let mut points = Vec::new();
    let mut indices = Vec::new();
    for (i, p) in prepared.iter().enumerate() {
        if let Some(pos) = p.position {
            points.push(crate::queries::Point {
                x: pos[0],
                y: pos[1],
                z: pos[2],
            });
            indices.push(i);
        }
    }
    if let Some((request, _, _)) = inputs.first() {
        let metrics = engine.timings(request.reserved);
        use crate::performance::Counter::*;
        metrics.performance.add(structure_fossil_batches, 1);
        metrics
            .performance
            .add(structure_fossil_columns, inputs.len() as u64);
        metrics
            .performance
            .add(structure_fossil_biome_points, points.len() as u64);
        let biomes = engine.query_points(*request, &points, crate::queries::Kind::Biome)?;
        for (index, biome) in indices.into_iter().zip(biomes) {
            prepared[index].biome = Some(biome.biome() as u16);
        }
    }
    Ok(prepared)
}

pub(super) fn finish(
    request: ChunkRequest,
    profile: &WorldProfile,
    definition: usize,
    seed: u64,
    prepared: &Prepared,
) -> Start {
    let p = &profile.structures;
    let kit = p.fossils.as_ref().expect("validated fossil profile");
    let d = &p.definitions[definition];
    let mut start = empty_start(request, seed, definition);
    let Some(pos) = prepared.position else {
        return start;
    };
    if !prepared.biome.is_some_and(|b| d.biomes.contains(&b)) {
        return start;
    }
    let mut random = prepared.random.clone();
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
    start
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
