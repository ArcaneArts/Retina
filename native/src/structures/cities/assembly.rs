//! Source recursion and collision semantics; no global mutable generator state.
use super::*;
#[derive(Clone, Copy)]
enum Generator {
    House,
    Tower,
    Bridge,
    Fat,
}
struct Builder<'a> {
    catalog: &'a Catalog,
    ship_created: bool,
}
const TOWER_BRIDGES: [(usize, [i32; 3]); 4] = [
    (0, [1, -1, 0]),
    (1, [6, -1, 1]),
    (3, [0, -1, 5]),
    (2, [5, -1, 6]),
];
const FAT_BRIDGES: [(usize, [i32; 3]); 4] = [
    (0, [4, -1, 0]),
    (1, [12, -1, 4]),
    (3, [0, -1, 8]),
    (2, [8, -1, 12]),
];
impl Builder<'_> {
    fn piece(&self, name: &str, pos: [i32; 3], rotation: usize, overwrite: bool) -> Piece {
        Piece {
            template: name.into(),
            pos,
            rotation,
            overwrite,
            depth: 0,
            bounds: self.catalog.bounds(name, pos, rotation),
        }
    }
    fn child(
        &self,
        parent: &Piece,
        offset: [i32; 3],
        name: &str,
        rotation: usize,
        overwrite: bool,
        pieces: &mut Vec<Piece>,
    ) -> Piece {
        // calculateConnectedPosition rotates the parent's offset and subtracts
        // the child's rotated zero. All End city settings have the zero pivot.
        let piece = self.piece(
            name,
            add(parent.pos, rotate(offset, parent.rotation)),
            rotation,
            overwrite,
        );
        pieces.push(piece.clone());
        piece
    }
    fn recursive(
        &mut self,
        generator: Generator,
        depth: i32,
        parent: &Piece,
        offset: Option<[i32; 3]>,
        pieces: &mut Vec<Piece>,
        random: &mut Random,
    ) -> bool {
        if depth > 8 {
            return false;
        }
        let mut children = Vec::new();
        if self.generate(generator, depth, parent, offset, &mut children, random) {
            let tag = random.bits(32) as i32;
            for child in &mut children {
                child.depth = tag;
                // Source findCollisionPiece returns the FIRST intersecting
                // piece. A later different group must not override that result.
                if pieces
                    .iter()
                    .find(|p| intersects(p.bounds, child.bounds))
                    .is_some_and(|p| p.depth != parent.depth)
                {
                    return false;
                }
            }
            pieces.extend(children);
            return true;
        }
        // ship_created intentionally survives failed/colliding branches, just
        // like the source generator field. Random draws are never rolled back.
        false
    }
    fn generate(
        &mut self,
        generator: Generator,
        depth: i32,
        parent: &Piece,
        offset: Option<[i32; 3]>,
        pieces: &mut Vec<Piece>,
        random: &mut Random,
    ) -> bool {
        let rotation = parent.rotation;
        match generator {
            Generator::House => {
                if depth > 8 {
                    return false;
                }
                let mut last = self.child(
                    parent,
                    offset.unwrap(),
                    "base_floor",
                    rotation,
                    true,
                    pieces,
                );
                match random.int(3) {
                    0 => {
                        self.child(&last, [-1, 4, -1], "base_roof", rotation, true, pieces);
                    }
                    1 => {
                        last = self.child(
                            &last,
                            [-1, 0, -1],
                            "second_floor_2",
                            rotation,
                            false,
                            pieces,
                        );
                        last =
                            self.child(&last, [-1, 8, -1], "second_roof", rotation, false, pieces);
                        self.recursive(Generator::Tower, depth + 1, &last, None, pieces, random);
                    }
                    _ => {
                        last = self.child(
                            &last,
                            [-1, 0, -1],
                            "second_floor_2",
                            rotation,
                            false,
                            pieces,
                        );
                        last = self.child(
                            &last,
                            [-1, 4, -1],
                            "third_floor_2",
                            rotation,
                            false,
                            pieces,
                        );
                        last = self.child(&last, [-1, 8, -1], "third_roof", rotation, true, pieces);
                        self.recursive(Generator::Tower, depth + 1, &last, None, pieces, random);
                    }
                }
            }
            Generator::Tower => {
                let mut last = self.child(
                    parent,
                    [3 + random.int(2) as i32, -3, 3 + random.int(2) as i32],
                    "tower_base",
                    rotation,
                    true,
                    pieces,
                );
                last = self.child(&last, [0, 7, 0], "tower_piece", rotation, true, pieces);
                let mut bridge = if random.int(3) == 0 {
                    Some(last.clone())
                } else {
                    None
                };
                let height = 1 + random.int(3);
                for i in 0..height {
                    last = self.child(&last, [0, 4, 0], "tower_piece", rotation, true, pieces);
                    if i < height - 1 && random.bits(1) != 0 {
                        bridge = Some(last.clone());
                    }
                }
                if let Some(bridge) = bridge {
                    for (r, offset) in TOWER_BRIDGES {
                        if random.bits(1) != 0 {
                            let start = self.child(
                                &bridge,
                                offset,
                                "bridge_end",
                                (rotation + r) % 4,
                                true,
                                pieces,
                            );
                            self.recursive(
                                Generator::Bridge,
                                depth + 1,
                                &start,
                                None,
                                pieces,
                                random,
                            );
                        }
                    }
                    self.child(&last, [-1, 4, -1], "tower_top", rotation, true, pieces);
                } else {
                    if depth != 7 {
                        return self.recursive(
                            Generator::Fat,
                            depth + 1,
                            &last,
                            None,
                            pieces,
                            random,
                        );
                    }
                    self.child(&last, [-1, 4, -1], "tower_top", rotation, true, pieces);
                }
            }
            Generator::Bridge => {
                let length = random.int(4) + 1;
                let mut last =
                    self.child(parent, [0, 0, -4], "bridge_piece", rotation, true, pieces);
                pieces.last_mut().unwrap().depth = -1;
                last.depth = -1;
                let mut next_y = 0;
                for _ in 0..length {
                    if random.bits(1) != 0 {
                        last = self.child(
                            &last,
                            [0, next_y, -4],
                            "bridge_piece",
                            rotation,
                            true,
                            pieces,
                        );
                        next_y = 0;
                    } else {
                        if random.bits(1) != 0 {
                            last = self.child(
                                &last,
                                [0, next_y, -4],
                                "bridge_steep_stairs",
                                rotation,
                                true,
                                pieces,
                            );
                        } else {
                            last = self.child(
                                &last,
                                [0, next_y, -8],
                                "bridge_gentle_stairs",
                                rotation,
                                true,
                                pieces,
                            );
                        }
                        next_y = 4;
                    }
                }
                if !self.ship_created && random.int((10 - depth) as u32) == 0 {
                    self.child(
                        &last,
                        [
                            -8 + random.int(8) as i32,
                            next_y,
                            -70 + random.int(10) as i32,
                        ],
                        "ship",
                        rotation,
                        true,
                        pieces,
                    );
                    self.ship_created = true;
                } else if !self.recursive(
                    Generator::House,
                    depth + 1,
                    &last,
                    Some([-3, next_y + 1, -11]),
                    pieces,
                    random,
                ) {
                    return false;
                }
                self.child(
                    &last,
                    [4, next_y, 0],
                    "bridge_end",
                    (rotation + 2) % 4,
                    true,
                    pieces,
                );
                pieces.last_mut().unwrap().depth = -1;
            }
            Generator::Fat => {
                let mut last = self.child(
                    parent,
                    [-3, 4, -3],
                    "fat_tower_base",
                    rotation,
                    true,
                    pieces,
                );
                last = self.child(&last, [0, 4, 0], "fat_tower_middle", rotation, true, pieces);
                let mut i = 0;
                while i < 2 && random.int(3) != 0 {
                    last = self.child(&last, [0, 8, 0], "fat_tower_middle", rotation, true, pieces);
                    for (r, offset) in FAT_BRIDGES {
                        if random.bits(1) != 0 {
                            let start = self.child(
                                &last,
                                offset,
                                "bridge_end",
                                (rotation + r) % 4,
                                true,
                                pieces,
                            );
                            self.recursive(
                                Generator::Bridge,
                                depth + 1,
                                &start,
                                None,
                                pieces,
                                random,
                            );
                        }
                    }
                    i += 1;
                }
                self.child(&last, [-2, 8, -2], "fat_tower_top", rotation, true, pieces);
            }
        }
        true
    }
}
pub(super) fn assemble(
    catalog: &Catalog,
    origin: [i32; 3],
    rotation: usize,
    random: &mut Random,
) -> Vec<Piece> {
    let mut builder = Builder {
        catalog,
        ship_created: false,
    };
    let mut pieces = Vec::new();
    let mut last = builder.piece("base_floor", origin, rotation, true);
    pieces.push(last.clone());
    last = builder.child(
        &last,
        [-1, 0, -1],
        "second_floor_1",
        rotation,
        false,
        &mut pieces,
    );
    last = builder.child(
        &last,
        [-1, 4, -1],
        "third_floor_1",
        rotation,
        false,
        &mut pieces,
    );
    last = builder.child(
        &last,
        [-1, 8, -1],
        "third_roof",
        rotation,
        true,
        &mut pieces,
    );
    builder.recursive(Generator::Tower, 1, &last, None, &mut pieces, random);
    pieces
}
