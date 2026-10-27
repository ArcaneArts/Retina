//! Private solidity-only lattice. Unevaluated corners are never exposed as
//! density values: every uncertain cell receives all eight exact corners.
use super::*;

#[derive(Default)]
pub(crate) struct Statistics {
    pub bounds_nanos: u64,
    pub temporary_bytes: u64,
    pub partial_probes: u64,
    pub exact_corners: u64,
    pub skipped_corners: u64,
}

pub(crate) struct SparseTile {
    inner: DenseTile,
    stats: Statistics,
}

impl SparseTile {
    pub(super) fn new(
        density: &VanillaDensity,
        x: i32,
        z: i32,
        width: u32,
        depth: u32,
    ) -> Result<Self, String> {
        let (mut tile, rows) = density.tile_layout(x, z, width, depth)?;
        let columns = tile.corners_x;
        let count = columns * rows;
        let mut bounds: Vec<[[f32; 2]; 33]> = Vec::with_capacity(count);
        let mut needed = vec![0u64; count];
        let mut stats = Statistics {
            temporary_bytes: (count * (std::mem::size_of::<[[f32; 2]; 33]>() + 8)) as u64,
            ..Default::default()
        };
        let started = crate::performance::enabled().then(std::time::Instant::now);
        for cz in 0..rows {
            for cx in 0..columns {
                let bx = (i64::from(tile.corner_origin[0]) + cx as i64 * 8) as i32;
                let bz = (i64::from(tile.corner_origin[1]) + cz as i64 * 8) as i32;
                let island = tile.islands.sample(bx, bz).unwrap();
                let mut partial = 0u64;
                let mut column = std::array::from_fn(|cy| {
                    let y = cy as i32 * 4;
                    let global = [
                        vertical(y, island, || -super::super::blended::VOLUME_BOUND),
                        vertical(y, island, || super::super::blended::VOLUME_BOUND),
                    ];
                    if classify_range(global[0], global[1]) == Occupancy::Uncertain {
                        partial |= 1u64 << cy;
                    }
                    global
                });
                if partial != 0 {
                    let noise = density.noise.volume_column_bounds(bx, bz, partial);
                    stats.partial_probes += partial.count_ones() as u64;
                    while partial != 0 {
                        let cy = partial.trailing_zeros() as usize;
                        partial &= partial - 1;
                        column[cy] = [
                            vertical(cy as i32 * 4, island, || noise[cy][0]),
                            vertical(cy as i32 * 4, island, || noise[cy][1]),
                        ];
                    }
                }
                bounds.push(column);
            }
        }
        stats.bounds_nanos = started.map_or(0, |t| t.elapsed().as_nanos() as u64);
        let mask_start = crate::performance::enabled().then(std::time::Instant::now);
        tile.occupancy = (0..(rows - 1) * (columns - 1))
            .map(|i| {
                let at = i / (columns - 1) * columns + i % (columns - 1);
                std::array::from_fn(|cy| {
                    let mut low = f32::INFINITY;
                    let mut high = f32::NEG_INFINITY;
                    for corner in [at, at + 1, at + columns, at + columns + 1] {
                        for y in [cy, cy + 1] {
                            let b = bounds[corner][y];
                            if b.iter().any(|v| !v.is_finite()) {
                                return Occupancy::Uncertain;
                            }
                            low = low.min(b[0]);
                            high = high.max(b[1]);
                        }
                    }
                    classify_range(low, high)
                })
            })
            .collect();
        // A complete uncertain cell requires all corners, including those
        // whose own sign was already known. Their magnitudes still matter.
        for (i, cells) in tile.occupancy.iter().enumerate() {
            let at = i / (columns - 1) * columns + i % (columns - 1);
            for (cy, cell) in cells.iter().enumerate() {
                if *cell == Occupancy::Uncertain {
                    for corner in [at, at + 1, at + columns, at + columns + 1] {
                        needed[corner] |= 3u64 << cy;
                    }
                }
            }
        }
        tile.mask_nanos = mask_start.map_or(0, |t| t.elapsed().as_nanos() as u64);
        drop(bounds);
        tile.corners.resize(count, [0.0; 33]);
        for (index, &flags) in needed.iter().enumerate() {
            let bx = (i64::from(tile.corner_origin[0]) + (index % columns) as i64 * 8) as i32;
            let bz = (i64::from(tile.corner_origin[1]) + (index / columns) as i64 * 8) as i32;
            let island = tile.islands.sample(bx, bz).unwrap();
            let mut remaining = flags;
            let noise = density.noise.volume_column(bx, bz, flags & !3);
            while remaining != 0 {
                let y = remaining.trailing_zeros() as usize;
                remaining &= remaining - 1;
                tile.corners[index][y] = vertical(y as i32 * 4, island, || noise[y]);
            }
            stats.exact_corners += (flags & !3).count_ones() as u64;
        }
        stats.skipped_corners = count as u64 * 31 - stats.exact_corners;
        let refine_start = crate::performance::enabled().then(std::time::Instant::now);
        for (i, cells) in tile.occupancy.iter_mut().enumerate() {
            let at = i / (columns - 1) * columns + i % (columns - 1);
            for (cy, cell) in cells.iter_mut().enumerate() {
                if *cell == Occupancy::Uncertain {
                    debug_assert!(
                        [at, at + 1, at + columns, at + columns + 1]
                            .iter()
                            .all(|&c| needed[c] & (3u64 << cy) == 3u64 << cy)
                    );
                    *cell = classify([
                        tile.corners[at][cy],
                        tile.corners[at + 1][cy],
                        tile.corners[at + columns][cy],
                        tile.corners[at + columns + 1][cy],
                        tile.corners[at][cy + 1],
                        tile.corners[at + 1][cy + 1],
                        tile.corners[at + columns][cy + 1],
                        tile.corners[at + columns + 1][cy + 1],
                    ]);
                }
            }
        }
        tile.mask_nanos += refine_start.map_or(0, |t| t.elapsed().as_nanos() as u64);
        Ok(Self { inner: tile, stats })
    }

    pub(crate) fn biome(&self, x: i32, z: i32) -> Option<super::super::Biome> {
        self.inner.biome(x, z)
    }
    pub(crate) fn column_bits(&self, x: i32, z: i32) -> Option<u128> {
        self.inner.column_bits(x, z)
    }
    pub(crate) fn occupancy_counts(&self) -> [u64; 3] {
        self.inner.occupancy_counts()
    }
    pub(crate) fn mask_nanos(&self) -> u64 {
        self.inner.mask_nanos()
    }
    pub(crate) fn mask_bytes(&self) -> usize {
        self.inner.mask_bytes()
    }
    pub(crate) fn stats(&self) -> &Statistics {
        &self.stats
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn sparse_cells_match_independent_vanilla_columns() {
        let source: serde_json::Value =
            serde_json::from_str(include_str!("../../tests/fixtures/end-density-26.3.json"))
                .unwrap();
        for group in source["seeds"].as_array().unwrap() {
            let seed = group["seed"].as_i64().unwrap() as u64;
            let density = VanillaDensity::new(seed);
            for sample in group["columns"].as_array().unwrap() {
                let x = sample["xz"][0].as_i64().unwrap() as i32;
                let z = sample["xz"][1].as_i64().unwrap() as i32;
                let tile = density.sparse_tile(x, z, 1, 1).unwrap();
                let bits = tile.column_bits(x, z).unwrap();
                for y in 0..256 {
                    let expected = sample["solid_y"]
                        .as_array()
                        .unwrap()
                        .iter()
                        .any(|v| v.as_u64() == Some(y));
                    assert_eq!(
                        y < 128 && bits & (1u128 << y) != 0,
                        expected,
                        "seed={seed} at {x},{y},{z}"
                    );
                }
            }
        }
    }

    #[test]
    fn sparse_uncertain_cells_match_dense_across_unaligned_far_and_empty_areas() {
        let mut exact = 0;
        let mut skipped = 0;
        for (seed, x, z) in [
            (0, -9, -17),
            (42, 16777211, -16777217),
            (u64::MAX, -4091, 529),
            (123456789, 0, 0),
            (123456789, 512, 0),
            (123456789, 4091, -529),
        ] {
            let density = VanillaDensity::new(seed);
            let sparse = density.sparse_tile(x, z, 19, 11).unwrap();
            let dense = density.tile(x, z, 19, 11).unwrap();
            for dz in 0..11 {
                for dx in 0..19 {
                    let values = dense.column_densities(x + dx, z + dz).unwrap();
                    let bits = sparse.column_bits(x + dx, z + dz).unwrap();
                    for y in 0..128 {
                        assert_eq!(
                            bits & (1u128 << y) != 0,
                            values[y] > 0.0,
                            "seed={seed} {dx},{y},{dz}"
                        );
                    }
                    assert_eq!(sparse.biome(x + dx, z + dz), dense.biome(x + dx, z + dz));
                }
            }
            assert!(sparse.column_bits(x - 1, z).is_none());
            assert!(sparse.column_bits(x + 19, z).is_none());
            assert!(sparse.column_bits(x, z - 1).is_none());
            assert!(sparse.column_bits(x, z + 11).is_none());
            exact += sparse.stats().exact_corners;
            skipped += sparse.stats().skipped_corners;
        }
        assert!(exact > 0 && skipped > 0);
    }
}
