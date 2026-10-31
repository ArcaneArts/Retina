//! Cheap hashing for short, internally generated coordinate/placement keys.
//! These maps never iterate to choose placement order. Keys retain normal Eq
//! comparisons, so hash collisions cannot alter generated terrain.
use std::{
    collections::{HashMap, HashSet},
    hash::{BuildHasherDefault, Hasher},
};

#[derive(Default)]
pub(crate) struct CoordinateHasher<const AVALANCHE: bool>(u64);
pub(crate) type SpatialHasher = CoordinateHasher<false>;
impl<const AVALANCHE: bool> CoordinateHasher<AVALANCHE> {
    fn word(&mut self, value: u64) {
        self.0 = (self.0.rotate_left(5) ^ value).wrapping_mul(0x517cc1b727220a95);
    }
}
impl<const AVALANCHE: bool> Hasher for CoordinateHasher<AVALANCHE> {
    fn finish(&self) -> u64 {
        if !AVALANCHE {
            return self.0;
        }
        // Floating-point grid keys share long zero mantissa tails. Finish with
        // an avalanche before HashMap selects buckets/control bits. Integer
        // placement caches retain their existing hashing unchanged.
        let mut hash = self.0;
        hash = (hash ^ (hash >> 30)).wrapping_mul(0xbf58476d1ce4e5b9);
        hash = (hash ^ (hash >> 27)).wrapping_mul(0x94d049bb133111eb);
        hash ^ (hash >> 31)
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
pub(crate) type Map<K, V> = HashMap<K, V, BuildHasherDefault<SpatialHasher>>;
pub(crate) type Set<K> = HashSet<K, BuildHasherDefault<SpatialHasher>>;

/// Bounded CPU sampling caches; generation never depends on map iteration.
pub(crate) type SamplingMap<K, V> = HashMap<K, V, BuildHasherDefault<CoordinateHasher<true>>>;
