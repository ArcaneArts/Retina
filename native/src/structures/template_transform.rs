//! StructureTemplate's mirror-then-rotation around its explicit pivot.
use super::{Template, add, rotate, sub};
use serde::Deserialize;

#[derive(Clone, Deserialize)]
pub(super) struct Transform {
    pub mirror: u8,
    pub pivot: [i32; 3],
    pub materials: Vec<[u16; 4]>,
}
impl Transform {
    pub fn valid(&self, materials: usize) -> bool {
        self.mirror <= 2
            && self.pivot.iter().all(|&v| (-512..=512).contains(&v))
            && self.materials.len() == materials
            && self
                .materials
                .iter()
                .flatten()
                .all(|&id| (id as usize) < materials)
    }
    pub fn vector(&self, mut p: [i32; 3], rotation: usize) -> [i32; 3] {
        if self.mirror == 1 {
            p[2] = -p[2];
        }
        if self.mirror == 2 {
            p[0] = -p[0];
        }
        rotate(p, rotation)
    }
    pub fn offset(&self, rotation: usize) -> [i32; 3] {
        sub(self.pivot, rotate(self.pivot, rotation))
    }
    pub fn position(&self, p: [i32; 3], rotation: usize) -> [i32; 3] {
        add(self.vector(p, rotation), self.offset(rotation))
    }
    pub fn point(&self, mut p: [f64; 3], rotation: usize) -> [f64; 3] {
        if self.mirror == 1 {
            p[2] = 1.0 - p[2];
        }
        if self.mirror == 2 {
            p[0] = 1.0 - p[0];
        }
        let q = match rotation {
            1 => [1.0 - p[2], p[1], p[0]],
            2 => [1.0 - p[0], p[1], 1.0 - p[2]],
            3 => [p[2], p[1], 1.0 - p[0]],
            _ => p,
        };
        let offset = self.offset(rotation);
        std::array::from_fn(|i| q[i] + offset[i] as f64)
    }
    pub fn yaw(&self, yaw: f64, rotation: usize) -> f64 {
        let angle = (yaw + 180.0).rem_euclid(360.0) - 180.0;
        let mirrored = match self.mirror {
            1 => 180.0 - angle,
            2 => -angle,
            _ => angle,
        };
        angle + rotation as f64 * 90.0 + mirrored - yaw
    }
}
impl Template {
    pub(super) fn position(&self, p: [i32; 3], rotation: usize) -> [i32; 3] {
        self.transform
            .as_ref()
            .map_or_else(|| rotate(p, rotation), |t| t.position(p, rotation))
    }
    pub(super) fn direction(&self, direction: usize, rotation: usize) -> [i32; 3] {
        let p = super::DIRECTIONS[direction];
        self.transform
            .as_ref()
            .map_or_else(|| rotate(p, rotation), |t| t.vector(p, rotation))
    }
    pub(super) fn horizontal_reach(&self) -> i32 {
        if self.transform.is_none() {
            return self.size[0].max(self.size[2]);
        }
        (0..4)
            .flat_map(|rotation| {
                [
                    [0, 0, 0],
                    [self.size[0] - 1, self.size[1] - 1, self.size[2] - 1],
                ]
                .map(|p| self.position(p, rotation))
            })
            .map(|p| p[0].abs().max(p[2].abs()) + 1)
            .max()
            .unwrap()
    }
}
