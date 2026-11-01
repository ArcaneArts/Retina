//! Immutable quart IDs already produced for this exact CPU terrain tile.
//! Out-of-tile samples retain the ordinary registered sampler and local cache.
use std::sync::Arc;
#[derive(Clone)]
pub(crate) struct Grid {
    origin: [i32; 3],
    width: usize,
    layers: usize,
    words: Arc<[u32]>,
}
impl Grid {
    pub(crate) fn new(origin: [i32; 3], width: usize, layers: usize, words: &[u32]) -> Self {
        Self { origin, width, layers, words: Arc::from(words) }
    }
    pub(crate) fn get(&self, point: [i32; 3]) -> Option<u32> {
        // Wide subtraction also makes an out-of-tile far-coordinate query safe.
        let offset = std::array::from_fn::<_, 3, _>(|i| i64::from(point[i]) - i64::from(self.origin[i]));
        if offset.iter().any(|&v| v < 0) { return None; }
        let [x, y, z] = offset.map(|v| v as usize / 4);
        if x >= self.width || z >= self.width || y >= self.layers { return None; }
        let index = (y * self.width + z) * self.width + x;
        self.words.get(index / 2).map(|word| (word >> ((index % 2) * 16)) & 65535)
    }
}
#[cfg(test)]
mod tests {
    use super::*;
    #[test]
    fn packed_quarts_keep_global_alignment_boundaries_and_shared_storage() {
        let (width, layers): (usize, usize) = (3, 5);
        let count = width * width * layers;
        let values: Vec<_> = (0..count).map(|i| ((i * 379 + 65000) & 65535) as u32).collect();
        let mut words = vec![0; count.div_ceil(2)];
        for (i, &value) in values.iter().enumerate() { words[i / 2] |= value << ((i % 2) * 16); }
        let grid = Grid::new([-32,-68,-16], width, layers, &words);
        assert!(Arc::ptr_eq(&grid.words, &grid.clone().words));
        for y in 0..layers * 4 {
            for z in 0..width * 4 {
                for x in 0..width * 4 {
                    assert_eq!(grid.get([-32+x as i32,-68+y as i32,-16+z as i32]),
                        Some(values[(y / 4 * width + z / 4) * width + x / 4]));
                }
            }
        }
        for point in [[-33,-68,-16],[-32,-69,-16],[-32,-68,-17],[-20,-68,-16],
            [-32,-48,-16],[-32,-68,-4],[i32::MIN;3],[i32::MAX;3]] {
            assert_eq!(grid.get(point), None, "{point:?}");
        }
        assert_eq!(Grid::new([0;3], 1, 1, &[]).get([0;3]), None);
    }
}
