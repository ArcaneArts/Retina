//! Cheap hashing for short, internally generated coordinate/placement keys.
//! These maps never iterate to choose placement order. Keys retain normal Eq
//! comparisons, so hash collisions cannot alter generated terrain.
use std::{
    collections::{HashMap, HashSet},
    hash::{BuildHasherDefault, Hasher},
};

#[derive(Default)]
pub(super) struct SpatialHasher(u64);
impl SpatialHasher {
    fn word(&mut self, value: u64) {
        self.0 = (self.0.rotate_left(5) ^ value).wrapping_mul(0x517cc1b727220a95);
    }
}
impl Hasher for SpatialHasher {
    fn finish(&self) -> u64 {
        self.0
    }
    fn write(&mut self, bytes: &[u8]) {
        let mut chunks = bytes.chunks_exact(8);
        for chunk in &mut chunks {
            self.word(u64::from_ne_bytes(chunk.try_into().unwrap()));
        }
        let remainder = chunks.remainder();
        if !remainder.is_empty() {
            let mut last = [0u8; 8];
            last[..remainder.len()].copy_from_slice(remainder);
            self.word(u64::from_ne_bytes(last));
        }
    }
    fn write_u32(&mut self, value: u32) {
        self.word(value as u64);
    }
    fn write_i32(&mut self, value: i32) {
        self.word(value as u32 as u64);
    }
    fn write_u64(&mut self, value: u64) {
        self.word(value);
    }
    fn write_usize(&mut self, value: usize) {
        self.word(value as u64);
    }
}
pub(super) type Map<K, V> = HashMap<K, V, BuildHasherDefault<SpatialHasher>>;
pub(super) type Set<K> = HashSet<K, BuildHasherDefault<SpatialHasher>>;
