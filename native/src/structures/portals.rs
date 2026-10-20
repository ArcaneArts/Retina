//! Nether ruined portals: source setup/floor selection and a shared cross-chunk replay.
mod shapes;
use super::*;

#[derive(Clone, Deserialize)]
pub struct Profile {
    pub templates: Vec<[usize; 2]>,
    pub netherrack: u16,
    pub magma: u16,
    pub leaves: u16,
    pub vines: [u16; 4],
    pub replaceable: Vec<bool>,
    pub air: Vec<bool>,
    pub full_faces: Vec<u8>,
    pub vine_states: Vec<bool>,
    shapes: shapes::Profile,
}
#[derive(Clone, Deserialize)]
pub struct Setup {
    placement: String,
    air_pocket_probability: f32,
    mossiness: f32,
    overgrown: bool,
    vines: bool,
    can_be_cold: bool,
    weight: f32,
    retina_pool: String,
}
pub struct Placement {
    setup: Setup,
    writes: OnceLock<Result<Writes, String>>,
}
pub struct Writes {
    chunks: HashMap<[i32; 2], Vec<([i32; 3], u16, Option<Value>)>>,
    ticks: Vec<([i32; 3], String, bool)>,
}
impl Profile {
    pub(super) fn validate(&self, p: &super::Profile, materials: usize) -> Result<(), String> {
        if !self.shapes.valid(materials)
            || self.templates.len() != 13
            || self
                .templates
                .iter()
                .flatten()
                .any(|&id| id >= p.templates.len() || p.templates[id].transform.is_none())
            || self.replaceable.len() != materials
            || self.air.len() != materials
            || self.full_faces.len() != materials
            || self.vine_states.len() != materials
            || [self.netherrack, self.magma, self.leaves]
                .iter()
                .chain(&self.vines)
                .any(|&id| id as usize >= materials)
        {
            return Err("invalid Nether portal templates/materials".into());
        }
        for d in &p.definitions {
            if d.kind != "ruined_portal" || d.unimplemented {
                continue;
            }
            let setups: Vec<Setup> = serde_json::from_value(d.config["setups"].clone())
                .map_err(|e| format!("portal setups {}: {e}", d.id))?;
            if setups.is_empty()
                || setups.iter().any(|s| {
                    s.placement != "in_nether"
                        || s.can_be_cold
                        || !s.weight.is_finite()
                        || s.weight <= 0.0
                        || !(0.0..=1.0).contains(&s.air_pocket_probability)
                        || !(0.0..=1.0).contains(&s.mossiness)
                        || p.pools
                            .get(&s.retina_pool)
                            .is_none_or(|p| p.entries.len() != 1 || p.entries[0].parts.len() != 1)
                })
            {
                return Err(format!(
                    "unsupported or invalid Nether portal setup {}",
                    d.id
                ));
            }
        }
        Ok(())
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
    let kit = p.portals.as_ref().ok_or("missing Nether portal kit")?;
    let d = &p.definitions[definition];
    let setups: Vec<Setup> =
        serde_json::from_value(d.config["setups"].clone()).map_err(|e| e.to_string())?;
    let setup = if setups.len() == 1 {
        setups[0].clone()
    } else {
        let total: f32 = setups.iter().map(|s| s.weight).sum();
        let mut pick = random.float() as f32;
        setups
            .iter()
            .find(|s| {
                pick -= s.weight / total;
                pick < 0.0
            })
            .ok_or("portal setup selection exhausted")?
            .clone()
    };
    let air_pocket = setup.air_pocket_probability == 1.0
        || setup.air_pocket_probability != 0.0
            && random.float() < (setup.air_pocket_probability as f64);
    let which = if random.float() < 0.05f32 as f64 {
        10 + random.int(3)
    } else {
        random.int(10)
    } as usize;
    let rotation = random.int(4) as usize;
    let mirror = usize::from(random.float() >= 0.5);
    let template = kit.templates[which][mirror];
    let mut part = p.pools[&setup.retina_pool].entries[0].parts[0].clone();
    part.template = template;
    part.ignore_air = !air_pocket;
    let element = Arc::new(Element {
        parts: vec![part],
        terrain_matching: false,
        weight: 1,
    });
    let mut pos = [request.chunk_x * 16, 0, request.chunk_z * 16];
    let b = bounds(&element, pos, rotation, p);
    let mut y = if air_pocket {
        32 + random.int(69) as i32
    } else if random.float() < 0.5 {
        27 + random.int(3) as i32
    } else {
        29 + random.int(72) as i32
    };
    let mut columns = Vec::new();
    for [x, z] in [[b[0], b[2]], [b[3], b[2]], [b[0], b[5]], [b[3], b[5]]] {
        let r = ChunkRequest {
            chunk_x: x.div_euclid(16),
            chunk_z: z.div_euclid(16),
            ..request
        };
        let mut column = vec![0; request.height as usize];
        engine.base_column(
            r,
            (z.rem_euclid(16) * 16 + x.rem_euclid(16)) as u32,
            &mut column,
        )?;
        columns.push(column);
    }
    while y > request.min_y + 15 {
        let solid = columns
            .iter()
            .filter(|column| {
                column
                    .get(y.wrapping_sub(request.min_y) as usize)
                    .is_some_and(|&id| profile.heightmap_masks[id as usize] & 1 != 0)
            })
            .count();
        if solid >= 3 {
            break;
        }
        y -= 1;
    }
    pos[1] = y;
    let mut start = empty_start(request, seed, definition);
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
    let palette =
        Random::new(position_seed(pos)).int(p.templates[template].palettes.len() as u32) as usize;
    start.pieces.push(Piece {
        bounds: bounds(&element, pos, rotation, p),
        element,
        pos,
        rotation,
        palette,
        context: 0,
        depth: 0,
        priority: 0,
        fortress: None,
    });
    start.portal = Some(Placement {
        setup,
        writes: OnceLock::new(),
    });
    Ok(start)
}

// The replay reads one world snapshot and records all writes before partitioning.
// The source-step integration will provide the earlier-feature/structure snapshot;
// the current structure-isolated harness supplies the native base terrain here.
struct World<'a> {
    request: ChunkRequest,
    profile: &'a WorldProfile,
    base: HashMap<[i32; 2], Vec<u16>>,
    writes: HashMap<[i32; 3], (u16, Option<Value>)>,
    ticks: Vec<([i32; 3], String, bool)>,
    tick_seen: std::collections::HashSet<([i32; 3], String, bool)>,
}
impl World<'_> {
    fn get(&self, p: [i32; 3]) -> u16 {
        if let Some(v) = self.writes.get(&p) {
            return v.0;
        }
        if p[1] < self.request.min_y || p[1] >= self.request.min_y + self.request.height as i32 {
            return 0;
        }
        self.base
            .get(&[p[0].div_euclid(16), p[2].div_euclid(16)])
            .map_or(0, |b| {
                b[(p[1] - self.request.min_y) as usize * 256
                    + p[2].rem_euclid(16) as usize * 16
                    + p[0].rem_euclid(16) as usize]
            })
    }
    fn set(&mut self, p: [i32; 3], id: u16, tag: Option<Value>) {
        if p[1] >= self.request.min_y && p[1] < self.request.min_y + self.request.height as i32 {
            self.writes.insert(p, (id, tag));
        }
    }
    fn height(&self, x: i32, z: i32) -> i32 {
        for y in (self.request.min_y..self.request.min_y + self.request.height as i32).rev() {
            if self.profile.heightmap_masks[self.get([x, y, z]) as usize] & 1 != 0 {
                return y;
            }
        }
        self.request.min_y - 1
    }
    fn magma(&mut self, kit: &Profile, r: &mut Random, p: [i32; 3]) {
        if kit.replaceable[self.get(p) as usize] {
            let material = if r.float() < 0.07f32 as f64 {
                kit.magma
            } else {
                kit.netherrack
            };
            self.set(p, material, None);
        }
    }
    fn drip(&mut self, kit: &Profile, r: &mut Random, mut p: [i32; 3]) {
        self.magma(kit, r, p);
        for _ in 0..8 {
            if r.float() >= 0.5 {
                break;
            }
            p[1] -= 1;
            self.magma(kit, r, p);
        }
    }
    fn leaves(&mut self, kit: &Profile, r: &mut Random, p: [i32; 3]) {
        let above = add(p, [0, 1, 0]);
        if r.float() < 0.5 && self.get(p) == kit.netherrack && kit.air[self.get(above) as usize] {
            self.set(above, kit.leaves, None);
        }
    }
}
pub(super) fn prepare(
    engine: &TerrainEngine,
    request: ChunkRequest,
    profile: &WorldProfile,
    start: &Start,
) -> Result<(), String> {
    if let Some(portal) = &start.portal {
        portal
            .writes
            .get_or_init(|| render(engine, request, profile, start, portal))
            .as_ref()
            .map_err(Clone::clone)?;
    }
    Ok(())
}
fn render(
    engine: &TerrainEngine,
    request: ChunkRequest,
    profile: &WorldProfile,
    start: &Start,
    portal: &Placement,
) -> Result<Writes, String> {
    let p = &profile.structures;
    let kit = p.portals.as_ref().unwrap();
    let piece = &start.pieces[0];
    let b = piece.bounds;
    let center = std::array::from_fn::<_, 3, _>(|i| b[i] + (b[i + 3] - b[i] + 1) / 2);
    let low = [b[0].min(center[0] - 14) - 1, b[2].min(center[2] - 14) - 1];
    let high = [b[3].max(center[0] + 14) + 1, b[5].max(center[2] + 14) + 1];
    let origin = ChunkRequest {
        chunk_x: low[0].div_euclid(16),
        chunk_z: low[1].div_euclid(16),
        ..request
    };
    let nx = high[0].div_euclid(16) - origin.chunk_x + 1;
    let nz = high[1].div_euclid(16) - origin.chunk_z + 1;
    let (field, mask) = engine.terrain_field(origin, nx.max(nz) as u32)?;
    let mut world = World {
        request,
        profile,
        base: HashMap::new(),
        writes: HashMap::new(),
        ticks: Vec::new(),
        tick_seen: std::collections::HashSet::new(),
    };
    for z in origin.chunk_z..origin.chunk_z + nz {
        for x in origin.chunk_x..origin.chunk_x + nx {
            let r = ChunkRequest {
                chunk_x: x,
                chunk_z: z,
                ..request
            };
            let mut blocks = vec![0; r.block_count()];
            crate::decoration::assemble_carved(
                r,
                field.chunk(x, z),
                Some(profile),
                mask.as_deref(),
                &mut blocks,
            );
            world.base.insert([x, z], blocks);
        }
    }
    let part = &piece.element.parts[0];
    let template = &p.templates[part.template];
    let rotated = p.rotated(part.template, piece.palette, piece.rotation);
    let mut ctx = part.program.context(piece.pos);
    ctx.source_template = true;
    let mut r = Random::new(start.seed);
    let mut processed = Vec::new();
    for block in &rotated.blocks {
        if block.skip || part.ignore_air && block.original == 0 {
            continue;
        }
        let pos = add(piece.pos, block.pos);
        ctx.position = pos;
        let mut material = block.original;
        let mut tag = template.tags.get(&block.index).map(Cow::Borrowed);
        if part.program.apply(
            &mut material,
            world.get(pos),
            &mut tag,
            0,
            profile,
            &mut r,
            &mut ctx,
        ) {
            material =
                template.transform.as_ref().unwrap().materials[material as usize][piece.rotation];
            processed.push((pos, material, tag.map(Cow::into_owned)));
        }
    }
    let mut placed = Vec::new();
    let mut locked = std::collections::HashSet::new();
    let mut to_fill = Vec::new();
    for (pos, material, mut tag) in processed {
        if pos[1] < request.min_y || pos[1] >= request.min_y + request.height as i32 {
            continue;
        }
        let original = world.get(pos);
        let state = &kit.shapes.states[material as usize];
        if !state.entity {
            tag = None;
        }
        placed.push(pos);
        if let Some(tag) = tag.as_mut() {
            for i in 0..3 {
                nbt::put(tag, ["x", "y", "z"][i], nbt::int(pos[i]));
            }
            nbt::put(tag, "keepPacked", json!([1, 1]));
            if state.container {
                nbt::put(tag, "LootTableSeed", nbt::long(r.long() as i64));
            }
        }
        world.set(pos, material, tag);
        if state.source_fluid {
            locked.insert(pos);
        } else if state.wet != material && !kit.shapes.states[original as usize].source_fluid {
            to_fill.push(pos);
        }
        world.wet(pos, original);
    }
    world.fill(&mut to_fill, &locked);
    world.shapes(&placed);
    // TemplateStructurePiece installs raw jigsaw final states after template updates.
    for joint in &template.joints {
        world.set(
            add(piece.pos, template.position(joint.pos, piece.rotation)),
            joint.final_state[0],
            None,
        );
    }
    let probabilities = [
        1.0f32, 1.0, 1.0, 1.0, 1.0, 1.0, 1.0, 0.9, 0.9, 0.8, 0.7, 0.6, 0.4, 0.2,
    ];
    let average = (b[3] - b[0] + 1 + b[5] - b[2] + 1) / 2;
    let adjustment = r.int((8 - average / 2).max(1) as u32) as i32;
    for x in center[0] - 14..=center[0] + 14 {
        for z in center[2] - 14..=center[2] + 14 {
            let distance = (x - center[0]).abs() + (z - center[2]).abs() + adjustment;
            if distance < 14 && r.double() < probabilities[distance as usize] as f64 {
                let y = b[1].min(world.height(x, z));
                let pos = [x, y, z];
                if (y - b[1]).abs() <= 3 && kit.replaceable[world.get(pos) as usize] {
                    world.magma(kit, &mut r, pos);
                    if portal.setup.overgrown {
                        world.leaves(kit, &mut r, pos);
                    }
                    world.drip(kit, &mut r, [x, y - 1, z]);
                }
            }
        }
    }
    for x in b[0] + 1..b[3] {
        for z in b[2] + 1..b[5] {
            if world.get([x, b[1], z]) == kit.netherrack {
                world.drip(kit, &mut r, [x, b[1] - 1, z]);
            }
        }
    }
    if portal.setup.vines || portal.setup.overgrown {
        // BlockPos.betweenClosed traverses X fastest, then Y, then Z.
        for z in b[2]..=b[5] {
            for y in b[1]..=b[4] {
                for x in b[0]..=b[3] {
                    let pos = [x, y, z];
                    let id = world.get(pos) as usize;
                    if portal.setup.vines && !kit.air[id] && !kit.vine_states[id] {
                        let direction = r.int(4) as usize;
                        let neighbor = add(
                            pos,
                            [[0, 0, -1], [1, 0, 0], [0, 0, 1], [-1, 0, 0]][direction],
                        );
                        if kit.air[world.get(neighbor) as usize]
                            && kit.full_faces[id] & (1 << [2, 3, 0, 1][direction]) != 0
                        {
                            world.set(neighbor, kit.vines[direction], None);
                        }
                    }
                    if portal.setup.overgrown {
                        world.leaves(kit, &mut r, pos);
                    }
                }
            }
        }
    }
    let mut chunks: HashMap<_, Vec<_>> = HashMap::new();
    for (pos, (material, tag)) in world.writes {
        chunks
            .entry([pos[0].div_euclid(16), pos[2].div_euclid(16)])
            .or_default()
            .push((pos, material, tag));
    }
    for writes in chunks.values_mut() {
        writes.sort_by_key(|(pos, _, _)| *pos);
    }
    Ok(Writes {
        chunks,
        ticks: world.ticks,
    })
}
pub(super) fn apply(
    start: &Start,
    request: ChunkRequest,
    profile: &WorldProfile,
    blocks: Option<&mut [u16]>,
    entities: &mut HashMap<[i32; 3], Value>,
    updates: &mut crate::decoration::pairs::Updates,
    ticks: &mut Vec<Value>,
    block_ticks: &mut Vec<Value>,
) -> usize {
    let writes = start
        .portal
        .as_ref()
        .unwrap()
        .writes
        .get()
        .expect("portal replay prepared")
        .as_ref()
        .expect("portal replay succeeded");
    for (pos, fluid, block) in &writes.ticks {
        if pos[0].div_euclid(16) != request.chunk_x
            || pos[2].div_euclid(16) != request.chunk_z
            || pos[1] < request.min_y
            || pos[1] >= request.min_y + request.height as i32
        {
            continue;
        }
        let mut tag = nbt::compound();
        nbt::put(&mut tag, "i", nbt::string(fluid));
        for i in 0..3 {
            nbt::put(&mut tag, ["x", "y", "z"][i], nbt::int(pos[i]));
        }
        nbt::put(&mut tag, "t", nbt::int(0));
        nbt::put(&mut tag, "p", nbt::int(0));
        if *block {
            block_ticks.push(tag);
        } else {
            ticks.push(tag);
        }
    }
    let Some(writes) = writes.chunks.get(&[request.chunk_x, request.chunk_z]) else {
        return 0;
    };
    let mut blocks = blocks;
    for (pos, material, tag) in writes {
        let index = (pos[1] - request.min_y) as usize * 256
            + pos[2].rem_euclid(16) as usize * 16
            + pos[0].rem_euclid(16) as usize;
        if let Some(blocks) = blocks.as_deref_mut() {
            blocks[index] = *material;
            updates.record(profile, index, *material);
        }
        entities.remove(pos);
        if let Some(tag) = tag {
            entities.insert(*pos, tag.clone());
        }
    }
    writes.len()
}
