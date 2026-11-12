//! Stronghold layout and saved piece metadata. Geometry is exported by the game adapter.
use super::*;

#[derive(Clone, Copy, PartialEq, Eq, Hash, Deserialize)]
#[serde(rename_all = "snake_case")]
enum Kind {
    Straight,
    Prison,
    Left,
    Right,
    Room,
    StraightStairs,
    Stairs,
    Crossing,
    Chest,
    Library,
    Portal,
    Start,
    Filler,
}

const WEIGHTS: [(Kind, u32, u8, u32); 11] = [
    (Kind::Straight, 40, 0, 0),
    (Kind::Prison, 5, 5, 0),
    (Kind::Left, 20, 0, 0),
    (Kind::Right, 20, 0, 0),
    (Kind::Room, 10, 6, 0),
    (Kind::StraightStairs, 5, 5, 0),
    (Kind::Stairs, 5, 5, 0),
    (Kind::Crossing, 5, 4, 0),
    (Kind::Chest, 5, 4, 0),
    (Kind::Library, 10, 2, 5),
    (Kind::Portal, 20, 1, 6),
];
const DOORS: [&str; 4] = ["OPENING", "WOOD_DOOR", "GRATES", "IRON_DOOR"];
const CROSSING_EXITS: [&str; 4] = ["leftLow", "leftHigh", "rightLow", "rightHigh"];

#[derive(Clone, Deserialize)]
pub struct Profile {
    generator_min_y: i32,
    pieces: HashMap<Kind, Geometry>,
}

#[derive(Clone, Deserialize)]
struct Geometry {
    id: String,
    orientations: [Orientation; 4],
}

#[derive(Clone, Deserialize)]
struct Orientation {
    base: Vec<usize>,
    doors: Vec<usize>,
    exits: Vec<usize>,
}

impl Profile {
    pub(super) fn validate(&self, templates: usize) -> Result<(), String> {
        for kind in WEIGHTS
            .map(|(kind, _, _, _)| kind)
            .into_iter()
            .chain([Kind::Start, Kind::Filler])
        {
            let geometry = self
                .pieces
                .get(&kind)
                .ok_or("missing stronghold piece geometry")?;
            let base = match kind {
                Kind::Room => 5,
                Kind::Library | Kind::Filler => 2,
                _ => 1,
            };
            let doors = if kind.door() { 3 } else { 0 };
            let exits = match kind {
                Kind::Straight => 2,
                Kind::Crossing => 4,
                _ => 0,
            };
            if !geometry.id.starts_with("minecraft:")
                || geometry.orientations.iter().any(|variants| {
                    variants.base.len() != base
                        || variants.doors.len() != doors
                        || variants.exits.len() != exits
                        || variants
                            .base
                            .iter()
                            .chain(&variants.doors)
                            .chain(&variants.exits)
                            .any(|&id| id >= templates)
                })
            {
                return Err("invalid stronghold piece geometry".into());
            }
        }
        Ok(())
    }
}

impl Kind {
    fn door(self) -> bool {
        !matches!(self, Self::Start | Self::Portal | Self::Filler)
    }

    fn dimensions(self, variant: usize) -> ([i32; 2], [i32; 3]) {
        match self {
            Self::Straight | Self::Chest => ([-1, -1], [5, 5, 7]),
            Self::Prison => ([-1, -1], [9, 5, 11]),
            Self::Left | Self::Right => ([-1, -1], [5, 5, 5]),
            Self::Room => ([-4, -1], [11, 7, 11]),
            Self::StraightStairs => ([-1, -7], [5, 11, 8]),
            Self::Stairs | Self::Start => ([-1, -7], [5, 11, 5]),
            Self::Crossing => ([-4, -3], [10, 9, 11]),
            Self::Library => ([-4, -1], [14, if variant == 1 { 11 } else { 6 }, 15]),
            Self::Portal => ([-4, -1], [11, 8, 16]),
            Self::Filler => ([-1, -1], [5, 5, variant as i32 + 2]),
        }
    }
}

#[derive(Clone, Copy)]
struct PiecePlan {
    kind: Kind,
    direction: usize,
    bounds: [i32; 6],
    depth: u32,
    variant: usize,
    door: usize,
    exits: u8,
}

impl PiecePlan {
    fn saved(self, geometry: &Geometry) -> Value {
        let mut tag = nbt::compound();
        nbt::put(&mut tag, "id", nbt::string(&geometry.id));
        nbt::put(&mut tag, "BB", json!([11, self.bounds]));
        nbt::put(&mut tag, "O", nbt::int(self.direction as i32));
        nbt::put(&mut tag, "GD", nbt::int(self.depth as i32));
        nbt::put(&mut tag, "EntryDoor", nbt::string(DOORS[self.door]));
        let boolean =
            |tag: &mut Value, key, value: bool| nbt::put(tag, key, json!([1, u8::from(value)]));
        match self.kind {
            Kind::Start | Kind::Stairs => boolean(&mut tag, "Source", self.kind == Kind::Start),
            Kind::Straight | Kind::Crossing => {
                let keys = if self.kind == Kind::Straight {
                    &["Left", "Right"][..]
                } else {
                    &CROSSING_EXITS
                };
                for (bit, &key) in keys.iter().enumerate() {
                    boolean(&mut tag, key, self.exits & (1 << bit) != 0);
                }
            }
            Kind::Chest => boolean(&mut tag, "Chest", true),
            Kind::Portal => boolean(&mut tag, "Mob", true),
            Kind::Library => boolean(&mut tag, "Tall", self.variant == 1),
            Kind::Room => nbt::put(&mut tag, "Type", nbt::int(self.variant as i32)),
            Kind::Filler => nbt::put(&mut tag, "Steps", nbt::int(self.variant as i32 + 2)),
            _ => {}
        }
        tag
    }

    fn geometry(self, geometry: &Geometry) -> Piece {
        let tables = &geometry.orientations[self.direction];
        let mut indices = vec![tables.base[self.variant]];
        if self.door > 0 {
            indices.push(tables.doors[self.door - 1]);
        }
        indices.extend(
            tables
                .exits
                .iter()
                .enumerate()
                .filter_map(|(bit, &id)| (self.exits & (1 << bit) != 0).then_some(id)),
        );
        Piece {
            element: Arc::new(Element {
                parts: indices
                    .into_iter()
                    .map(|template| Part {
                        template,
                        ignore_air: false,
                        finalize_entities: false,
                        processors: json!([]),
                        program: Arc::default(),
                    })
                    .collect(),
                terrain_matching: false,
                weight: 1,
            }),
            pos: [self.bounds[0] - 1, self.bounds[1], self.bounds[2] - 1],
            rotation: 0,
            bounds: self.bounds,
            palette: 0,
            context: 0,
            depth: self.depth,
            priority: 0,
            fortress: None,
        }
    }
}

fn oriented(at: [i32; 3], [ox, oy]: [i32; 2], [w, h, d]: [i32; 3], dir: usize) -> [i32; 6] {
    let [x, y, z] = at;
    let (x, z, w, d) = match dir {
        0 => (x + ox, z, w, d),
        1 => (x - d + 1, z + ox, d, w),
        2 => (x + ox, z - d + 1, w, d),
        _ => (x, z + ox, d, w),
    };
    [x, y + oy, z, x + w - 1, y + oy + h - 1, z + d - 1]
}

struct Builder {
    random: Random,
    pieces: Vec<PiecePlan>,
    pending: Vec<usize>,
    counts: [u8; WEIGHTS.len()],
    previous: Option<Kind>,
}

impl Builder {
    fn collides(&self, bounds: [i32; 6]) -> Option<[i32; 6]> {
        self.pieces
            .iter()
            .find(|p| intersects(p.bounds, bounds))
            .map(|p| p.bounds)
    }

    fn propose(
        &mut self,
        kind: Kind,
        at: [i32; 3],
        direction: usize,
        depth: u32,
    ) -> Option<PiecePlan> {
        let mut variant = usize::from(kind == Kind::Library);
        let (offset, dimensions) = kind.dimensions(variant);
        let mut bounds = oriented(at, offset, dimensions, direction);
        if kind == Kind::Library && (bounds[1] <= 10 || self.collides(bounds).is_some()) {
            variant = 0;
            let (offset, dimensions) = kind.dimensions(variant);
            bounds = oriented(at, offset, dimensions, direction);
        }
        if bounds[1] <= 10 || self.collides(bounds).is_some() {
            return None;
        }
        let door = if kind.door() {
            self.random.int(5).saturating_sub(1) as usize
        } else {
            0
        };
        let mut exits = 0;
        match kind {
            Kind::Straight => {
                for bit in 0..2 {
                    exits |= u8::from(self.random.int(2) == 0) << bit;
                }
            }
            Kind::Crossing => {
                for bit in 0..3 {
                    exits |= u8::from(self.random.int(2) != 0) << bit;
                }
                exits |= u8::from(self.random.int(3) > 0) << 3;
            }
            Kind::Room => variant = self.random.int(5) as usize,
            _ => {}
        }
        Some(PiecePlan {
            kind,
            direction,
            bounds,
            depth,
            variant,
            door,
            exits,
        })
    }

    fn choose(&mut self, at: [i32; 3], dir: usize, depth: u32, imposed: bool) -> Option<PiecePlan> {
        let mut remaining = false;
        let mut total = 0;
        for (index, &(_, weight, limit, _)) in WEIGHTS.iter().enumerate() {
            if limit != 0 && self.counts[index] >= limit {
                continue;
            }
            remaining |= limit != 0;
            total += weight;
        }
        if !remaining {
            return None;
        }
        if imposed && let Some(piece) = self.propose(Kind::Crossing, at, dir, depth) {
            return Some(piece);
        }
        for _ in 0..5 {
            let mut pick = self.random.int(total) as i32;
            for (index, &(kind, weight, limit, earliest)) in WEIGHTS.iter().enumerate() {
                if limit != 0 && self.counts[index] >= limit {
                    continue;
                }
                pick -= weight as i32;
                if pick >= 0 {
                    continue;
                }
                if depth < earliest || self.previous == Some(kind) {
                    break;
                }
                if let Some(piece) = self.propose(kind, at, dir, depth) {
                    self.counts[index] += u8::from(limit != 0);
                    self.previous = Some(kind);
                    return Some(piece);
                }
            }
        }
        let offset = [-1, -1];
        let probe = oriented(at, offset, [5, 5, 4], dir);
        let hit = self.collides(probe)?;
        if hit[1] != probe[1] || probe[1] <= 1 {
            return None;
        }
        for length in [2, 1] {
            if !intersects(hit, oriented(at, offset, [5, 5, length], dir)) {
                let bounds = oriented(at, offset, [5, 5, length + 1], dir);
                return Some(PiecePlan {
                    kind: Kind::Filler,
                    bounds,
                    direction: dir,
                    depth,
                    variant: (length - 1) as usize,
                    door: 0,
                    exits: 0,
                });
            }
        }
        None
    }

    fn attach(&mut self, parent: PiecePlan, side: u8, horizontal: i32, vertical: i32) {
        let [x, y, z, max_x, _, max_z] = parent.bounds;
        let dir = parent.direction;
        let (at, facing) = match (side, dir) {
            (0, 0) => ([x + horizontal, y + vertical, max_z + 1], dir),
            (0, 1) => ([x - 1, y + vertical, z + horizontal], dir),
            (0, 2) => ([x + horizontal, y + vertical, z - 1], dir),
            (0, _) => ([max_x + 1, y + vertical, z + horizontal], dir),
            (1, 0 | 2) => ([x - 1, y + vertical, z + horizontal], 1),
            (1, _) => ([x + horizontal, y + vertical, z - 1], 2),
            (_, 0 | 2) => ([max_x + 1, y + vertical, z + horizontal], 3),
            _ => ([x + horizontal, y + vertical, max_z + 1], 0),
        };
        let origin = self.pieces[0].bounds;
        if parent.depth > 50 || (at[0] - origin[0]).abs() > 112 || (at[2] - origin[2]).abs() > 112 {
            return;
        }
        if let Some(piece) = self.choose(at, facing, parent.depth + 1, parent.kind == Kind::Start) {
            self.pending.push(self.pieces.len());
            self.pieces.push(piece);
        }
    }

    fn children(&mut self, index: usize) {
        let piece = self.pieces[index];
        match piece.kind {
            Kind::Library | Kind::Portal | Kind::Filler => {}
            Kind::Left | Kind::Right => {
                let left = (piece.kind == Kind::Left) == matches!(piece.direction, 2 | 3);
                self.attach(piece, if left { 1 } else { 2 }, 1, 1);
            }
            Kind::Room => {
                self.attach(piece, 0, 4, 1);
                self.attach(piece, 1, 4, 1);
                self.attach(piece, 2, 4, 1);
            }
            Kind::Crossing => {
                self.attach(piece, 0, 5, 1);
                let reverse = matches!(piece.direction, 1 | 2);
                for bit in 0..4 {
                    if piece.exits & (1 << bit) == 0 {
                        continue;
                    }
                    let low = bit % 2 == 0;
                    self.attach(
                        piece,
                        if bit < 2 { 1 } else { 2 },
                        if low { 1 } else { 7 },
                        if low == reverse { 5 } else { 3 },
                    );
                }
            }
            _ => {
                self.attach(piece, 0, 1, 1);
                if piece.kind == Kind::Straight {
                    for bit in 0..2 {
                        if piece.exits & (1 << bit) != 0 {
                            self.attach(piece, bit + 1, 2, 1);
                        }
                    }
                }
            }
        }
    }
}

pub(super) fn build(
    request: ChunkRequest,
    profile: &WorldProfile,
    definition: usize,
    seed: u64,
) -> Result<Start, String> {
    let kit = profile
        .structures
        .strongholds
        .as_ref()
        .ok_or("missing stronghold geometry export")?;
    let mut start = empty_start(request, seed, definition);
    for attempt in 0..4096 {
        let world = request.seed.wrapping_add(attempt);
        let mut seeder = Random::new(world);
        let mixed = seeder.long().wrapping_mul(request.chunk_x as i64 as u64)
            ^ seeder.long().wrapping_mul(request.chunk_z as i64 as u64)
            ^ world;
        let mut random = Random::new(mixed);
        let direction = [2, 3, 0, 1][random.int(4) as usize];
        let x = request.chunk_x * 16 + 2;
        let z = request.chunk_z * 16 + 2;
        let root = PiecePlan {
            kind: Kind::Start,
            direction,
            bounds: [x, 64, z, x + 4, 74, z + 4],
            depth: 0,
            variant: 0,
            door: 0,
            exits: 0,
        };
        let mut builder = Builder {
            random,
            pieces: vec![root],
            pending: Vec::new(),
            counts: [0; WEIGHTS.len()],
            previous: None,
        };
        builder.children(0);
        while !builder.pending.is_empty() {
            let slot = builder.random.int(builder.pending.len() as u32) as usize;
            let piece = builder.pending.remove(slot);
            builder.children(piece);
        }
        if !builder
            .pieces
            .iter()
            .any(|piece| piece.kind == Kind::Portal)
        {
            continue;
        }
        let min = builder
            .pieces
            .iter()
            .map(|piece| piece.bounds[1])
            .min()
            .unwrap();
        let max = builder
            .pieces
            .iter()
            .map(|piece| piece.bounds[4])
            .max()
            .unwrap();
        let mut target = max - min + kit.generator_min_y + 2;
        let slack = profile.sea_level - 10 - target;
        if slack > 0 {
            target += builder.random.int(slack as u32) as i32;
        }
        let shift = target - max;
        let mut saved = Vec::with_capacity(builder.pieces.len());
        for mut piece in builder.pieces {
            piece.bounds[1] += shift;
            piece.bounds[4] += shift;
            let geometry = &kit.pieces[&piece.kind];
            start.pieces.push(piece.geometry(geometry));
            saved.push(piece.saved(geometry));
        }
        start.stronghold_pieces = Some(saved);
        return Ok(start);
    }
    Err("stronghold layout did not find a portal room".into())
}
