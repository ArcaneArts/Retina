//! Sparse registered count queries. Rules/permutations remain resident; jobs
//! exchange only integer coordinates/rule IDs and one count per unique point.
use super::spatial::Map;
use bytemuck::{Pod, Zeroable};
use serde::Deserialize;

pub mod gpu;

#[derive(Clone, Deserialize)]
pub struct Noise {
    pub permutation: Vec<u32>,
}
impl Noise {
    pub(crate) fn validate(&self) -> Result<(), String> {
        let mut sorted = self.permutation.clone();
        sorted.sort_unstable();
        if sorted != (0..256).collect::<Vec<_>>() {
            return Err("decoration noise must contain the complete permutation".into());
        }
        Ok(())
    }
}

#[repr(C)]
#[derive(Clone, Copy, Debug, PartialEq, Eq, Hash, PartialOrd, Ord, Pod, Zeroable)]
pub struct Rule {
    words: [u32; 8],
}
impl Rule {
    pub fn threshold(level: f64, below: i32, above: i32) -> Self {
        Self {
            words: [
                0,
                (level as f32).to_bits(),
                below as u32,
                above as u32,
                200f32.to_bits(),
                0,
                0,
                0,
            ],
        }
    }
    pub fn based(ratio: i32, factor: f64, offset: f64) -> Self {
        Self {
            words: [
                1,
                0,
                0,
                0,
                (factor as f32).to_bits(),
                (offset as f32).to_bits(),
                ratio as u32,
                0,
            ],
        }
    }
}
#[derive(Clone, Copy, Debug, PartialEq, Eq, Hash, PartialOrd, Ord)]
pub struct Query {
    pub x: i32,
    pub z: i32,
    pub rule: Rule,
}
#[derive(Default)]
pub struct Counts {
    values: Map<Query, i32>,
}
impl Counts {
    pub(crate) fn extend(&mut self, queries: impl IntoIterator<Item = (Query, i32)>) {
        self.values.extend(queries);
    }
    pub fn get(&self, rule: Rule, x: i32, z: i32) -> Option<i32> {
        self.values.get(&Query { x, z, rule }).copied()
    }
}
