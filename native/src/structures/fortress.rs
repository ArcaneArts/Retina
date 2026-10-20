//! Connected Nether fortress planning. Geometry is captured from the loaded game at startup.
use super::*;

const KINDS: [&str; 13] = [
    "bridge_straight",
    "bridge_crossing",
    "room_crossing",
    "stairs_room",
    "monster_throne",
    "castle_entrance",
    "small_corridor",
    "corridor_crossing",
    "right_turn",
    "left_turn",
    "corridor_stairs",
    "corridor_balcony",
    "stalk_room",
];
const END: usize = 13;
const WEIGHTS: [u32; 13] = [30, 10, 10, 10, 5, 5, 25, 15, 5, 5, 10, 7, 5];
const LIMITS: [u32; 13] = [0, 4, 4, 3, 2, 1, 0, 5, 10, 10, 3, 2, 2];
const REPEAT: [bool; 13] = [
    true, false, false, false, false, false, true, false, false, false, true, false, false,
];

#[derive(Clone, Deserialize)]
pub struct Profile {
    pieces: Vec<Blueprint>,
    end_filler: EndFiller,
    support_replaceable: Vec<u16>,
}
#[derive(Clone, Deserialize)]
struct Blueprint {
    kind: String,
    orientations: Vec<Vec<Variant>>,
}
#[derive(Clone, Deserialize)]
struct Variant {
    template: usize,
    bounds: [i32; 6],
    supports: Vec<[i32; 4]>,
    ticks: Vec<Tick>,
    post_processing: Vec<[i32; 3]>,
}
#[derive(Clone, Deserialize)]
struct Tick {
    pos: [i32; 3],
    fluid: String,
    delay: i32,
    priority: i32,
}
#[derive(Clone, Deserialize)]
struct EndFiller {
    rows: Vec<[i32; 3]>,
    templates: [usize; 4],
    bounds: [[i32; 6]; 4],
}
#[derive(Clone)]
pub struct Placement {
    kind: usize,
    direction: usize,
    variant: usize,
    rows: Vec<i32>,
}
impl Profile {
    fn variant(&self, at: &Placement) -> &Variant {
        &self
            .pieces
            .iter()
            .find(|p| p.kind == KINDS[at.kind])
            .unwrap()
            .orientations[at.direction][at.variant]
    }
    pub(super) fn validate(
        &self,
        profile: &super::Profile,
        materials: usize,
    ) -> Result<(), String> {
        if self
            .support_replaceable
            .iter()
            .any(|&m| m as usize >= materials)
            || self
                .end_filler
                .templates
                .iter()
                .any(|&t| t >= profile.templates.len())
            || self
                .end_filler
                .rows
                .iter()
                .any(|r| r[0] < 0 || r[1] < 0 || r[2] <= 0 || r[2] > 8)
        {
            return Err("invalid fortress materials/end-filler recipe".into());
        }
        for (kind, name) in KINDS.iter().enumerate() {
            let p = self
                .pieces
                .iter()
                .find(|p| p.kind == *name)
                .ok_or_else(|| format!("missing fortress piece {name}"))?;
            if p.orientations.len() != 4
                || p.orientations
                    .iter()
                    .any(|v| v.len() != if kind == 8 || kind == 9 { 2 } else { 1 })
            {
                return Err(format!("invalid fortress variants {name}"));
            }
            for v in p.orientations.iter().flatten() {
                if v.template >= profile.templates.len()
                    || (0..3).any(|i| v.bounds[i] > v.bounds[i + 3])
                    || v.supports
                        .iter()
                        .any(|s| s[3] < 0 || s[3] as usize >= materials)
                    || v.ticks
                        .iter()
                        .any(|t| t.fluid.is_empty() || !(-3..=3).contains(&t.priority))
                {
                    return Err(format!("invalid fortress geometry {name}"));
                }
            }
        }
        Ok(())
    }
}
impl Placement {
    pub(super) fn allows(&self, pos: [i32; 3], profile: &Profile, piece: &Piece) -> bool {
        if self.kind != END {
            return true;
        }
        let [xx, y, zz] = pos;
        let (x, z) = match self.direction {
            1 => (zz, piece.bounds[3] - piece.bounds[0] - xx),
            2 => (xx, piece.bounds[5] - piece.bounds[2] - zz),
            3 => (zz, xx),
            _ => (xx, zz),
        };
        profile
            .end_filler
            .rows
            .iter()
            .zip(&self.rows)
            .any(|(r, &length)| r[0] == x && r[1] == y && z <= length)
    }
}

pub(super) fn build(
    request: ChunkRequest,
    profile: &super::Profile,
    definition: usize,
    seed: u64,
    random: Random,
) -> Result<Start, String> {
    let kit = profile
        .fortress
        .as_ref()
        .ok_or("missing fortress geometry export")?;
    let mut builder = Builder {
        kit,
        random,
        pieces: Vec::new(),
        pending: Vec::new(),
        counts: [0; 13],
        previous: None,
        origin: [request.chunk_x * 16 + 2, 64, request.chunk_z * 16 + 2],
    };
    // Direction.Plane.HORIZONTAL draws N,E,S,W; exports use horizontal data order S,W,N,E.
    let direction = [2, 3, 0, 1][builder.random.int(4) as usize];
    let root = Placement {
        kind: 1,
        direction,
        variant: 0,
        rows: Vec::new(),
    };
    let v = kit.variant(&root);
    let size = [
        v.bounds[3] - v.bounds[0],
        v.bounds[4] - v.bounds[1],
        v.bounds[5] - v.bounds[2],
    ];
    let o = builder.origin;
    builder.pieces.push(make_piece(
        root,
        v.template,
        [
            o[0],
            o[1],
            o[2],
            o[0] + size[0],
            o[1] + size[1],
            o[2] + size[2],
        ],
        0,
    ));
    builder.children(0);
    while !builder.pending.is_empty() {
        let slot = builder.random.int(builder.pending.len() as u32) as usize;
        let index = builder.pending.remove(slot);
        builder.children(index);
    }
    let min = builder.pieces.iter().map(|p| p.bounds[1]).min().unwrap();
    let max = builder.pieces.iter().map(|p| p.bounds[4]).max().unwrap();
    let span = 70 - 48 + 1 - (max - min + 1);
    let dy =
        48 + if span > 1 {
            builder.random.int(span as u32) as i32
        } else {
            0
        } - min;
    let mut start = empty_start(request, seed, definition);
    for mut piece in builder.pieces {
        piece.pos[1] += dy;
        piece.bounds[1] += dy;
        piece.bounds[4] += dy;
        let t = &profile.templates[piece.element.parts[0].template];
        if piece.pos[1] < request.min_y
            || piece.pos[1] + t.size[1] > request.min_y + request.height as i32
        {
            return Ok(empty_start(request, seed, definition));
        }
        start.pieces.push(piece);
    }
    Ok(start)
}
fn make_piece(at: Placement, template: usize, bounds: [i32; 6], depth: u32) -> Piece {
    Piece {
        element: Arc::new(Element {
            parts: vec![Part {
                template,
                ignore_air: false,
                processors: json!([]),
                program: Arc::default(),
            }],
            terrain_matching: false,
            weight: 1,
        }),
        pos: [bounds[0], bounds[1], bounds[2]],
        rotation: 0,
        bounds,
        palette: 0,
        context: 0,
        depth,
        priority: 0,
        fortress: Some(at),
    }
}
struct Builder<'a> {
    kit: &'a Profile,
    random: Random,
    pieces: Vec<Piece>,
    pending: Vec<usize>,
    counts: [u32; 13],
    previous: Option<usize>,
    origin: [i32; 3],
}
impl Builder<'_> {
    fn create(
        &mut self,
        kind: usize,
        foot: [i32; 3],
        direction: usize,
        depth: u32,
    ) -> Option<Piece> {
        let mut at = Placement {
            kind,
            direction,
            variant: 0,
            rows: Vec::new(),
        };
        let relative = if kind == END {
            self.kit.end_filler.bounds[direction]
        } else {
            self.kit.variant(&at).bounds
        };
        let bounds = std::array::from_fn(|i| relative[i] + foot[i % 3]);
        if bounds[1] <= 10 || self.pieces.iter().any(|p| intersects(p.bounds, bounds)) {
            return None;
        }
        let template = if kind == END {
            let seed = self.random.bits(32) as i32;
            let mut random = Random::new(seed as i64 as u64);
            at.rows = self
                .kit
                .end_filler
                .rows
                .iter()
                .map(|r| random.int(r[2] as u32) as i32)
                .collect();
            self.kit.end_filler.templates[direction]
        } else {
            if kind == 8 || kind == 9 {
                at.variant = usize::from(self.random.int(3) == 0);
            }
            self.kit.variant(&at).template
        };
        Some(make_piece(at, template, bounds, depth))
    }
    fn generate(
        &mut self,
        foot: [i32; 3],
        direction: usize,
        depth: u32,
        castle: bool,
    ) -> Option<Piece> {
        let range = if castle { 6..13 } else { 0..6 };
        let available: Vec<_> = range
            .filter(|&i| LIMITS[i] == 0 || self.counts[i] < LIMITS[i])
            .collect();
        let finite = available.iter().any(|&i| LIMITS[i] > 0);
        let total = available.iter().map(|&i| WEIGHTS[i]).sum();
        if finite && depth <= 30 {
            for _ in 0..5 {
                let mut roll = self.random.int(total) as i32;
                for &kind in &available {
                    roll -= WEIGHTS[kind] as i32;
                    if roll < 0 {
                        if self.previous == Some(kind) && !REPEAT[kind] {
                            break;
                        }
                        // A collided selected factory falls through to subsequent entries,
                        // matching NetherBridgePiece's weighted factory loop.
                        if let Some(piece) = self.create(kind, foot, direction, depth) {
                            self.counts[kind] += 1;
                            self.previous = Some(kind);
                            return Some(piece);
                        }
                    }
                }
            }
        }
        self.create(END, foot, direction, depth)
    }
    fn child(&mut self, parent: &Piece, side: usize, horizontal: i32, vertical: i32, castle: bool) {
        let b = parent.bounds;
        let direction = parent.fortress.as_ref().unwrap().direction;
        let (foot, next) = match side {
            0 => match direction {
                0 => ([b[0] + horizontal, b[1] + vertical, b[5] + 1], 0),
                1 => ([b[0] - 1, b[1] + vertical, b[2] + horizontal], 1),
                2 => ([b[0] + horizontal, b[1] + vertical, b[2] - 1], 2),
                _ => ([b[3] + 1, b[1] + vertical, b[2] + horizontal], 3),
            },
            1 => {
                if direction == 0 || direction == 2 {
                    ([b[0] - 1, b[1] + vertical, b[2] + horizontal], 1)
                } else {
                    ([b[0] + horizontal, b[1] + vertical, b[2] - 1], 2)
                }
            }
            _ => {
                if direction == 0 || direction == 2 {
                    ([b[3] + 1, b[1] + vertical, b[2] + horizontal], 3)
                } else {
                    ([b[0] + horizontal, b[1] + vertical, b[5] + 1], 0)
                }
            }
        };
        if (foot[0] - self.origin[0]).abs() > 112 || (foot[2] - self.origin[2]).abs() > 112 {
            // The source creates a discarded filler here, including its seed draw.
            self.create(END, foot, next, parent.depth);
            return;
        }
        if let Some(piece) = self.generate(foot, next, parent.depth + 1, castle) {
            self.pending.push(self.pieces.len());
            self.pieces.push(piece);
        }
    }
    fn children(&mut self, index: usize) {
        let piece = self.pieces[index].clone();
        let at = piece.fortress.as_ref().unwrap();
        match at.kind {
            0 => self.child(&piece, 0, 1, 3, false),
            1 => {
                self.child(&piece, 0, 8, 3, false);
                self.child(&piece, 1, 8, 3, false);
                self.child(&piece, 2, 8, 3, false);
            }
            2 => {
                self.child(&piece, 0, 2, 0, false);
                self.child(&piece, 1, 2, 0, false);
                self.child(&piece, 2, 2, 0, false);
            }
            3 => self.child(&piece, 2, 2, 6, false),
            5 => self.child(&piece, 0, 5, 3, true),
            6 | 10 => self.child(&piece, 0, 1, 0, true),
            7 => {
                self.child(&piece, 0, 1, 0, true);
                self.child(&piece, 1, 1, 0, true);
                self.child(&piece, 2, 1, 0, true);
            }
            8 => self.child(&piece, 2, 1, 0, true),
            9 => self.child(&piece, 1, 1, 0, true),
            11 => {
                let offset = if at.direction == 1 || at.direction == 2 {
                    5
                } else {
                    1
                };
                let castle = self.random.int(8) > 0;
                self.child(&piece, 1, offset, 0, castle);
                let castle = self.random.int(8) > 0;
                self.child(&piece, 2, offset, 0, castle);
            }
            12 => {
                self.child(&piece, 0, 5, 3, true);
                self.child(&piece, 0, 5, 11, true);
            }
            _ => {}
        }
    }
}

/// Support columns use placed terrain, not the highest roof heightmap.
pub(super) fn supports(
    piece: &Piece,
    kit: &Profile,
    request: ChunkRequest,
    output: &mut [u16],
    entities: &mut HashMap<[i32; 3], Value>,
) {
    let at = piece.fortress.as_ref().unwrap();
    if at.kind == END {
        return;
    }
    for s in &kit.variant(at).supports {
        let pos = add(piece.pos, [s[0], s[1], s[2]]);
        let x = pos[0] - request.chunk_x * 16;
        let z = pos[2] - request.chunk_z * 16;
        if !(0..16).contains(&x) || !(0..16).contains(&z) {
            continue;
        }
        for y in ((request.min_y + 2)..=pos[1].min(request.min_y + request.height as i32 - 1)).rev()
        {
            let index = (y - request.min_y) as usize * 256 + z as usize * 16 + x as usize;
            if !kit.support_replaceable.contains(&output[index]) {
                break;
            }
            output[index] = s[3] as u16;
            entities.remove(&[pos[0], y, pos[2]]);
        }
    }
}
pub(super) fn metadata(
    piece: &Piece,
    kit: &Profile,
    request: ChunkRequest,
    ticks: &mut Vec<Value>,
    processing: &mut [Vec<Value>],
) {
    let at = piece.fortress.as_ref().unwrap();
    if at.kind == END {
        return;
    }
    let v = kit.variant(at);
    let local = |p: [i32; 3]| {
        p[0].div_euclid(16) == request.chunk_x
            && p[2].div_euclid(16) == request.chunk_z
            && p[1] >= request.min_y
            && p[1] < request.min_y + request.height as i32
    };
    for t in &v.ticks {
        let p = add(piece.pos, t.pos);
        if !local(p) {
            continue;
        }
        let mut tick = nbt::compound();
        nbt::put(&mut tick, "i", nbt::string(&t.fluid));
        for (k, n) in ["x", "y", "z"].into_iter().zip(p) {
            nbt::put(&mut tick, k, nbt::int(n));
        }
        // ProtoChunkTicks stores generation delays as zero, preserving priority.
        let _ = t.delay;
        nbt::put(&mut tick, "t", nbt::int(0));
        nbt::put(&mut tick, "p", nbt::int(t.priority));
        ticks.push(tick);
    }
    for &at in &v.post_processing {
        let p = add(piece.pos, at);
        if !local(p) {
            continue;
        }
        let packed = (p[0] & 15) | ((p[1] & 15) << 4) | ((p[2] & 15) << 8);
        let section = ((p[1] - request.min_y) / 16) as usize;
        let tag = json!([2, packed]);
        if !processing[section].contains(&tag) {
            processing[section].push(tag);
        }
    }
}
