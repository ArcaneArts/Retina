//! BlockAgeProcessor's source draw order over registered replacement-state tables.
use crate::structures::{Random, position_seed};
use serde::Deserialize;

#[derive(Deserialize)]
pub(super) struct Profile {
    pub kinds: Vec<u8>,
    pub mossy: Vec<u16>,
    pub normal_stairs: [[u16; 4]; 8],
    pub mossy_stairs: [[u16; 4]; 8],
    pub cracked: u16,
    pub mossy_bricks: u16,
    pub mossy_slab: u16,
    pub slabs: [u16; 2],
    pub crying: u16,
}
impl Profile {
    pub fn valid(&self, materials: usize) -> bool {
        self.kinds.len() == materials
            && self.kinds.iter().all(|&v| v <= 5)
            && self.mossy.len() == materials
            && self
                .mossy
                .iter()
                .chain(self.normal_stairs.iter().flatten())
                .chain(self.mossy_stairs.iter().flatten())
                .chain(&self.slabs)
                .chain([
                    &self.cracked,
                    &self.mossy_bricks,
                    &self.mossy_slab,
                    &self.crying,
                ])
                .all(|&v| (v as usize) < materials)
    }
    pub fn apply(&self, material: u16, rotation: usize, position: [i32; 3], mossiness: f64) -> u16 {
        // Each source processor requests its own positional stream from place settings.
        let mut r = Random::new(position_seed(position));
        match self.kinds[material as usize] {
            1 => {
                if r.float() >= 0.5 {
                    return material;
                }
                let normal = r.int(4) * 2 + r.int(2);
                let mossy = r.int(4) * 2 + r.int(2);
                let replace_mossy = r.float() < mossiness;
                let stairs = r.int(2) != 0;
                match (replace_mossy, stairs) {
                    (false, false) => self.cracked,
                    (false, true) => self.normal_stairs[normal as usize][rotation],
                    (true, false) => self.mossy_bricks,
                    (true, true) => self.mossy_stairs[mossy as usize][rotation],
                }
            }
            2 => {
                if r.float() >= 0.5 {
                    return material;
                }
                let mossy = r.float() < mossiness;
                let choice = r.int(2) as usize;
                if mossy {
                    if choice == 0 {
                        self.mossy[material as usize]
                    } else {
                        self.mossy_slab
                    }
                } else {
                    self.slabs[choice]
                }
            }
            3 | 4 if r.float() < mossiness => self.mossy[material as usize],
            5 if r.float() < 0.15f32 as f64 => self.crying,
            _ => material,
        }
    }
}
