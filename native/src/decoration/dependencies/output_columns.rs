//! Immutable original command indexes. Compact bounds share a flat index buffer;
//! proven uniform blobs additionally keep one edit bitmap per bounded column.
use super::*;
use std::collections::hash_map;

type Position = (i32, i32);
const MAX_CELLS: usize = 40 * 40;
const MIN_WRITES: usize = 64;

#[derive(Default)]
pub(super) struct BlobColumn {
    indices: Vec<usize>,
    edits: uniform_blob::Edits,
}

pub(super) enum Columns {
    Ordered(Map<Position, Vec<usize>>),
    Uniform(Map<Position, BlobColumn>),
    Packed(Packed),
}
pub(super) struct Packed {
    origin: Position,
    width: usize,
    offsets: Box<[usize]>,
    indices: Box<[usize]>,
    edits: Option<Box<[uniform_blob::Edits]>>,
}
impl Packed {
    fn slot(&self, (x, z): Position) -> Option<usize> {
        let dx = i64::from(x) - i64::from(self.origin.0);
        let dz = i64::from(z) - i64::from(self.origin.1);
        let depth = (self.offsets.len() - 1) / self.width;
        if dx < 0 || dz < 0 || dx >= self.width as i64 || dz >= depth as i64 {
            return None;
        }
        Some(dz as usize * self.width + dx as usize)
    }
    fn indices(&self, slot: usize) -> &[usize] {
        &self.indices[self.offsets[slot]..self.offsets[slot + 1]]
    }
}
impl Default for Columns {
    fn default() -> Self { Self::Ordered(Map::default()) }
}
impl From<Map<Position, Vec<usize>>> for Columns {
    fn from(columns: Map<Position, Vec<usize>>) -> Self { Self::Ordered(columns) }
}
#[cfg(test)]
impl FromIterator<(Position, Vec<usize>)> for Columns {
    fn from_iter<T: IntoIterator<Item = (Position, Vec<usize>)>>(items: T) -> Self {
        Self::Ordered(items.into_iter().collect())
    }
}
impl Columns {
    pub(super) fn from_writes(
        writes: &[WorldBlock], material: Option<u16>, min_y: i32,
        height: usize, metrics: &Metrics,
    ) -> (Self, u64) {
        // Keep sparse/small/wide outputs on the existing map representation.
        // This changes storage only; every original command remains available.
        if writes.len() < MIN_WRITES {
            metrics.add(Perf::output_index_ordered_outputs, 1);
            metrics.add(Perf::output_index_ordered_writes, writes.len() as u64);
            return Self::from_writes_map(writes, material, min_y, height, metrics);
        }
        let material = material.filter(|_| (1..=uniform_blob::MAX_HEIGHT).contains(&height));
        let started = if material.is_some() { metrics.start() } else { None };
        let first = writes[0];
        let (mut min_x, mut max_x, mut min_z, mut max_z) = (first.x, first.x, first.z, first.z);
        let mut deferred = 0;
        let mut valid = true;
        for block in writes {
            deferred += u64::from(block.role == DEFERRED_ORE);
            min_x = min_x.min(block.x); max_x = max_x.max(block.x);
            min_z = min_z.min(block.z); max_z = max_z.max(block.z);
            if let Some(material) = material {
                valid &= block.role == FEATURE && block.material == material
                    && block.y.checked_sub(min_y).is_some();
            }
        }
        let width = i64::from(max_x) - i64::from(min_x) + 1;
        let depth = i64::from(max_z) - i64::from(min_z) + 1;
        let Some(area) = width.checked_mul(depth)
            .filter(|&area| area <= MAX_CELLS as i64 && area <= writes.len() as i64)
        else {
            metrics.add(Perf::output_index_ordered_outputs, 1);
            metrics.add(Perf::output_index_ordered_writes, writes.len() as u64);
            return Self::from_writes_map(writes, material, min_y, height, metrics);
        };
        let (width, area) = (width as usize, area as usize);
        let slot = |block: &WorldBlock| {
            (i64::from(block.z) - i64::from(min_z)) as usize * width
                + (i64::from(block.x) - i64::from(min_x)) as usize
        };
        let mut offsets = vec![0; area + 1];
        let mut edits: Option<Box<[uniform_blob::Edits]>> = material.filter(|_| valid)
            .map(|_| (0..area).map(|_| uniform_blob::Edits::default()).collect());
        for block in writes {
            let column = slot(block);
            offsets[column] += 1;
            if let Some(edits) = edits.as_mut() {
                if let Some(y) = block.y.checked_sub(min_y) {
                    if y >= 0 && (y as usize) < height { edits[column].insert(y as usize); }
                }
            }
        }
        let mut total = 0;
        for count in &mut offsets { total += *count; *count = total; }
        let mut indices = vec![0; writes.len()];
        // Consume each exclusive end in reverse source order. The resulting
        // slices retain ascending ORIGINAL indices, including duplicate writes
        // and every possible partial within-root prefix.
        for (index, block) in writes.iter().enumerate().rev() {
            let end = &mut offsets[slot(block)];
            *end -= 1;
            indices[*end] = index;
        }
        let bitmap_bytes = edits.as_ref().map_or(0, |v| v.len() * std::mem::size_of::<uniform_blob::Edits>());
        metrics.add(Perf::output_index_packed_outputs, 1);
        metrics.add(Perf::output_index_packed_writes, writes.len() as u64);
        metrics.add(Perf::output_index_packed_cells, area as u64);
        metrics.add(Perf::output_index_packed_buffer_bytes,
            ((offsets.len() + indices.len()) * std::mem::size_of::<usize>()) as u64);
        metrics.add(Perf::output_index_uniform_bitmap_bytes, bitmap_bytes as u64);
        if started.is_some() {
            metrics.add(Perf::blob_index_outputs, 1);
            metrics.add(Perf::blob_index_writes, writes.len() as u64);
            metrics.add(Perf::blob_index_columns,
                offsets.windows(2).filter(|v| v[0] != v[1]).count() as u64);
            // Actual additional bitmap payload for this representation; map
            // fallback still reports its extra value capacity as before.
            metrics.add(Perf::blob_index_extra_value_bytes, bitmap_bytes as u64);
            metrics.add(Perf::blob_index_rejected_outputs, u64::from(!valid));
        }
        metrics.elapsed(Perf::blob_index_prepare_nanos, started);
        (Self::Packed(Packed { origin: (min_x, min_z), width,
            offsets: offsets.into_boxed_slice(), indices: indices.into_boxed_slice(), edits }), deferred)
    }
    fn from_writes_map(
        writes: &[WorldBlock],
        material: Option<u16>,
        min_y: i32,
        height: usize,
        metrics: &Metrics,
    ) -> (Self, u64) {
        let mut deferred = 0;
        let Some(material) = material.filter(|_| (1..=uniform_blob::MAX_HEIGHT).contains(&height))
        else {
            let mut columns: Map<_, Vec<_>> = Map::default();
            for (index, block) in writes.iter().enumerate() {
                deferred += u64::from(block.role == DEFERRED_ORE);
                columns.entry((block.x, block.z)).or_default().push(index);
            }
            return (Self::Ordered(columns), deferred);
        };
        let started = metrics.start();
        let mut columns: Map<_, BlobColumn> = Map::default();
        let mut valid = true;
        for (index, block) in writes.iter().enumerate() {
            deferred += u64::from(block.role == DEFERRED_ORE);
            let column = columns.entry((block.x, block.z)).or_default();
            column.indices.push(index);
            valid &= block.role == FEATURE && block.material == material;
            if let Some(y) = block.y.checked_sub(min_y) {
                if y >= 0 && (y as usize) < height {
                    column.edits.insert(y as usize);
                }
            } else {
                valid = false;
            }
        }
        if started.is_some() {
            metrics.add(Perf::blob_index_outputs, 1);
            metrics.add(Perf::blob_index_writes, writes.len() as u64);
            metrics.add(Perf::blob_index_columns, columns.len() as u64);
            // Extra value payload relative to the original map, including spare
            // map capacity. This is cumulative preparation, not live/RSS bytes.
            metrics.add(
                Perf::blob_index_extra_value_bytes,
                (columns.capacity()
                    * (std::mem::size_of::<BlobColumn>() - std::mem::size_of::<Vec<usize>>()))
                    as u64,
            );
            metrics.add(Perf::blob_index_rejected_outputs, u64::from(!valid));
        }
        let result = if valid {
            (Self::Uniform(columns), deferred)
        } else {
            // Keep every original index if an output violates the uniform
            // contract. Consumers then use the existing command path.
            (
                Self::Ordered(
                    columns
                        .into_iter()
                        .map(|(key, column)| (key, column.indices))
                        .collect(),
                ),
                deferred,
            )
        };
        metrics.elapsed(Perf::blob_index_prepare_nanos, started);
        result
    }
    pub(super) fn get(&self, key: &Position) -> Option<&[usize]> {
        match self {
            Self::Ordered(columns) => columns.get(key).map(Vec::as_slice),
            Self::Uniform(columns) => columns.get(key).map(|v| v.indices.as_slice()),
            Self::Packed(packed) => {
                let indices = packed.indices(packed.slot(*key)?);
                (!indices.is_empty()).then_some(indices)
            }
        }
    }
    pub(super) fn edits(&self, key: &Position) -> Option<(&uniform_blob::Edits, usize)> {
        match self {
            Self::Uniform(columns) => columns.get(key).map(|v| (&v.edits, v.indices.len())),
            Self::Packed(packed) => {
                let edits = packed.edits.as_ref()?;
                let slot = packed.slot(*key)?;
                let count = packed.offsets[slot + 1] - packed.offsets[slot];
                (count != 0).then_some((&edits[slot], count))
            }
            Self::Ordered(_) => None,
        }
    }
    pub(super) fn iter(&self) -> impl Iterator<Item = (Position, &[usize])> {
        match self {
            Self::Ordered(columns) => Iter::Ordered(columns.iter()),
            Self::Uniform(columns) => Iter::Uniform(columns.iter()),
            Self::Packed(packed) => Iter::Packed { packed, slot: 0 },
        }
    }
    #[cfg(test)]
    pub(super) fn len(&self) -> usize { self.iter().count() }
    #[cfg(test)]
    pub(super) fn values(&self) -> impl Iterator<Item = &[usize]> {
        self.iter().map(|(_, indices)| indices)
    }
}
enum Iter<'a> {
    Ordered(hash_map::Iter<'a, Position, Vec<usize>>),
    Uniform(hash_map::Iter<'a, Position, BlobColumn>),
    Packed { packed: &'a Packed, slot: usize },
}
impl<'a> Iterator for Iter<'a> {
    type Item = (Position, &'a [usize]);
    fn next(&mut self) -> Option<Self::Item> {
        match self {
            Self::Ordered(iter) => iter.next().map(|(&k, v)| (k, v.as_slice())),
            Self::Uniform(iter) => iter.next().map(|(&k, v)| (k, v.indices.as_slice())),
            Self::Packed { packed, slot } => {
                while *slot + 1 < packed.offsets.len() {
                    let current = *slot; *slot += 1;
                    let indices = packed.indices(current);
                    if !indices.is_empty() {
                        let x = i64::from(packed.origin.0) + (current % packed.width) as i64;
                        let z = i64::from(packed.origin.1) + (current / packed.width) as i64;
                        return Some(((x as i32, z as i32), indices));
                    }
                }
                None
            }
        }
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    fn write(x: i32, y: i32, z: i32, material: u16) -> WorldBlock {
        WorldBlock {
            root_order: 2,
            x,
            y,
            z,
            material,
            role: FEATURE,
            upper: 0,
            tick: 0,
        }
    }
    #[test]
    fn indexed_blob_edits_preserve_order_and_match_dense_columns() {
        let (profile, _, _) = crate::decoration::replay_tests::fixture(42, 0);
        for height in [1, 2, 63, 64, 65, 127, 128, 255, 256] {
            for min_y in [-64, 0, 16] {
                for material in [0, 4, 7] {
                    let mut writes = Vec::new();
                    for i in 0..height * 3 {
                        writes.push(write(
                            -17,
                            min_y + ((i * 71 + 13) % height) as i32,
                            32,
                            material,
                        ));
                        writes.push(write(
                            16,
                            min_y + (height - 1 - i % height) as i32,
                            -33,
                            material,
                        ));
                    }
                    writes.push(write(-17, min_y - 1, 32, material));
                    writes.push(write(-17, min_y + height as i32, 32, material));
                    let before = writes.clone();
                    let (columns, deferred) = Columns::from_writes(
                        &writes,
                        Some(material),
                        min_y,
                        height,
                        &Metrics::default(),
                    );
                    let (plain, _) =
                        Columns::from_writes(&writes, None, min_y, height, &Metrics::default());
                    assert_eq!(deferred, 0);
                    assert_eq!(columns.len(), plain.len());
                    for (key, ids) in columns.iter() {
                        assert_eq!(Some(ids), plain.get(&key));
                        let original: Vec<_> = (0..height).map(|y| ((y / 7) % 9) as u16).collect();
                        let prior = ColumnState::new(original.clone());
                        let mut expected = original;
                        for &index in ids {
                            let y = writes[index].y - min_y;
                            if y >= 0 && (y as usize) < height {
                                expected[y as usize] = material;
                            }
                        }
                        let (edits, count) = columns.edits(&key).unwrap();
                        assert_eq!(count, ids.len());
                        let result = edits.finish(&prior, height, material);
                        let dense = ColumnState::new(expected);
                        let actual = result.as_ref().unwrap_or(&prior);
                        assert_eq!(actual.runs.slice(), dense.runs.slice());
                        assert_eq!(actual.heights(&profile, min_y, height), dense.heights(&profile, min_y, height));
                    }
                    assert!(columns.edits(&(999, -999)).is_none());
                    assert_eq!(writes, before);
                }
            }
        }
    }
    #[test]
    fn indexed_blob_edits_keep_original_indexes_for_ineligible_outputs() {
        for (height, material, writes) in [
            (384, Some(4), vec![write(0, 300, 0, 4)]),
            (256, None, vec![write(0, 3, 0, 4)]),
            (256, Some(4), vec![write(0, 3, 0, 7)]),
            (
                256,
                Some(4),
                vec![WorldBlock {
                    role: DEFERRED_ORE,
                    ..write(0, 3, 0, 4)
                }],
            ),
        ] {
            let (columns, deferred) =
                Columns::from_writes(&writes, material, 0, height, &Metrics::default());
            assert!(matches!(columns, Columns::Ordered(_)));
            assert_eq!(columns.get(&(0, 0)), Some([0].as_slice()));
            assert!(columns.edits(&(0, 0)).is_none());
            assert_eq!(deferred, u64::from(writes[0].role == DEFERRED_ORE));
        }
        let writes = [write(0, i32::MAX, 0, 4)];
        let (columns, _) =
            Columns::from_writes(&writes, Some(4), i32::MIN, 256, &Metrics::default());
        assert!(matches!(columns, Columns::Ordered(_)));
        assert_eq!(columns.get(&(0, 0)), Some([0].as_slice()));
    }
    #[test]
    fn completed_blob_edits_merge_original_groups_but_reject_partial_prefixes() {
        let (profile, _, _) = crate::decoration::replay_tests::fixture(42, 0);
        for height in [1, 64, 128, 256] {
            for min_y in [-64, 16] {
                for material in [0, 4, 7] {
                    let first = vec![
                        write(-32, min_y, -16, material),
                        write(-31, min_y, -16, material),
                        write(-32, min_y + height as i32 - 1, -16, material),
                    ];
                    let second = vec![
                        write(-32, min_y + (height / 2) as i32, -16, material),
                        write(-32, min_y, -16, material),
                    ];
                    let output = |writes: Vec<WorldBlock>| {
                        let (columns, _) = Columns::from_writes(
                            &writes,
                            Some(material),
                            min_y,
                            height,
                            &Metrics::default(),
                        );
                        Arc::new(Output {
                            metadata: None,
                            writes,
                            columns,
                        })
                    };
                    let mut root = CompletedRoot {
                        anchors: vec![
                            ((-2, -1), output(first.clone())),
                            ((-1, -1), output(second.clone())),
                        ],
                        columns: Map::from_iter([(0, vec![0, 1]), (1, vec![0])]),
                    };
                    let commands: Vec<_> = root.writes(0, -32, -16).collect();
                    assert_eq!(
                        commands.iter().map(|(key, _)| *key).collect::<Vec<_>>(),
                        vec![(-1, -2, 0), (-1, -2, 2), (-1, -1, 0), (-1, -1, 1)]
                    );
                    let (edits, covered, merges) = root.blob_edits(0, -32, -16, None).unwrap();
                    assert_eq!((covered, merges), (4, 2));
                    let original: Vec<_> = (0..height).map(|y| ((y / 7) % 9) as u16).collect();
                    let prior = ColumnState::new(original.clone());
                    let mut expected = original;
                    for (_, block) in &commands {
                        expected[(block.y - min_y) as usize] = block.material;
                    }
                    let result = edits.finish(&prior, height, material);
                    let dense = ColumnState::new(expected);
                    assert_eq!(
                        result.as_ref().unwrap_or(&prior).runs.slice(),
                        dense.runs.slice()
                    );
                    assert_eq!(result.as_ref().unwrap_or(&prior).heights(&profile, min_y, height), dense.heights(&profile, min_y, height));
                    for limit in [(-1, -2, 0), (-1, -2, 2), (-1, -1, 1), (0, 0, 0)] {
                        assert!(root.blob_edits(0, -32, -16, Some(limit)).is_none());
                        assert_eq!(
                            root.writes(0, -32, -16)
                                .filter(|(key, _)| *key < limit)
                                .collect::<Vec<_>>(),
                            commands
                                .iter()
                                .copied()
                                .filter(|(key, _)| *key < limit)
                                .collect::<Vec<_>>()
                        );
                    }
                    assert_eq!(root.anchors[0].1.writes, first);
                    assert_eq!(root.anchors[1].1.writes, second);
                    assert_eq!(Arc::strong_count(&root.anchors[0].1), 1);
                    // One ordinary output rejects the entire shortcut; it must
                    // not accidentally return only the first anchor's edits.
                    let (columns, _) =
                        Columns::from_writes(&second, None, min_y, height, &Metrics::default());
                    Arc::get_mut(&mut root.anchors[1].1).unwrap().columns = columns;
                    assert!(root.blob_edits(0, -32, -16, None).is_none());
                    assert_eq!(root.writes(0, -32, -16).collect::<Vec<_>>(), commands);
                }
            }
        }
    }
}

#[cfg(test)]
mod packed_tests {
    use super::*;

    fn write(x: i32, z: i32, i: usize) -> WorldBlock {
        WorldBlock {
            root_order: (i % 11) as u32,
            x,
            y: (i % 384) as i32 - 64,
            z,
            material: (i % 19) as u16,
            role: (i % 8) as u8,
            upper: 0,
            tick: 0,
        }
    }

    fn original(writes: &[WorldBlock]) -> Map<Position, Vec<usize>> {
        let mut result: Map<_, Vec<_>> = Map::default();
        for (index, block) in writes.iter().enumerate() {
            result.entry((block.x, block.z)).or_default().push(index);
        }
        result
    }

    fn check(writes: &[WorldBlock], packed: bool) {
        let expected = original(writes);
        let (actual, deferred) = Columns::from_writes(writes, None, -64, 384, &Metrics::default());
        assert_eq!(matches!(actual, Columns::Packed(_)), packed);
        assert_eq!(
            deferred,
            writes.iter().filter(|b| b.role == DEFERRED_ORE).count() as u64
        );
        assert_eq!(actual.len(), expected.len());
        let materialized: Map<_, Vec<_>> = actual
            .iter()
            .map(|(key, ids)| (key, ids.to_vec()))
            .collect();
        assert_eq!(materialized, expected);
        for (key, ids) in expected {
            assert_eq!(actual.get(&key), Some(ids.as_slice()));
            // Compare every command prefix, not only the final set of writes.
            for limit in 0..=writes.len() {
                let selected: Vec<_> = actual
                    .get(&key)
                    .unwrap()
                    .iter()
                    .copied()
                    .filter(|&i| i < limit)
                    .collect();
                let expected: Vec<_> = writes[..limit]
                    .iter()
                    .enumerate()
                    .filter_map(|(i, b)| ((b.x, b.z) == key).then_some(i))
                    .collect();
                assert_eq!(selected, expected);
            }
        }
        for key in [(i32::MIN, i32::MAX), (i32::MAX, i32::MIN), (7, -9), (0, 0)] {
            let expected: Vec<_> = writes
                .iter()
                .enumerate()
                .filter_map(|(i, b)| ((b.x, b.z) == key).then_some(i))
                .collect();
            assert_eq!(
                actual.get(&key),
                (!expected.is_empty()).then_some(expected.as_slice())
            );
        }
    }

    #[test]
    fn packed_output_indexes_preserve_every_original_command_prefix() {
        for origin in [
            (0, 0),
            (-64, -80),
            (i32::MIN, i32::MIN),
            (i32::MAX - 7, i32::MAX - 7),
        ] {
            for width in [1, 2, 4, 8] {
                let writes: Vec<_> = (0..192)
                    .map(|i| {
                        write(
                            origin.0 + (i * 11 % width) as i32,
                            origin.1 + (i * 17 / width % 8) as i32,
                            i,
                        )
                    })
                    .collect();
                check(&writes, true);
            }
        }
        // Empty holes inside an otherwise dense bounding rectangle are absent.
        let writes: Vec<_> = (0..96)
            .map(|i| write((i % 3 * 3) as i32, (i % 2 * 2) as i32, i))
            .collect();
        check(&writes, true);
    }

    #[test]
    fn packed_output_indexes_keep_original_fallback_for_sparse_large_and_empty_layouts() {
        check(&[], false);
        check(&(0..63).map(|i| write(0, 0, i)).collect::<Vec<_>>(), false);
        check(&(0..64).map(|i| write(0, 0, i)).collect::<Vec<_>>(), true);
        check(
            &(0..64)
                .map(|i| write((i % 2 * 39) as i32, (i % 3 * 19) as i32, i))
                .collect::<Vec<_>>(),
            false,
        );
        check(
            &(0..64)
                .map(|i| write(if i % 2 == 0 { i32::MIN } else { i32::MAX }, 0, i))
                .collect::<Vec<_>>(),
            false,
        );
        check(
            &(0..64)
                .map(|i| {
                    write(
                        if i % 2 == 0 { i32::MIN } else { i32::MAX },
                        if i % 3 == 0 { i32::MIN } else { i32::MAX },
                        i,
                    )
                })
                .collect::<Vec<_>>(),
            false,
        );
        let writes: Vec<_> = (0..1600)
            .map(|i| write((i % 40) as i32, (i / 40) as i32, i))
            .collect();
        let (columns, _) = Columns::from_writes(&writes, None, -64, 384, &Metrics::default());
        assert!(matches!(columns, Columns::Packed(_)));
        assert_eq!(columns.iter().count(), 1600);
        for (i, block) in writes.iter().enumerate() {
            assert_eq!(columns.get(&(block.x, block.z)), Some([i].as_slice()));
        }
        let writes: Vec<_> = (0..1681)
            .map(|i| write((i % 41) as i32, (i / 41) as i32, i))
            .collect();
        assert!(matches!(
            Columns::from_writes(&writes, None, -64, 384, &Metrics::default()).0,
            Columns::Ordered(_)
        ));
    }

    #[test]
    fn packed_output_indexes_preserve_cross_anchor_source_limits() {
        let mut completed = CompletedRoot {
            anchors: Vec::new(),
            columns: Map::default(),
        };
        for ax in -2..=0 {
            let writes: Vec<_> = (0..96)
                .map(|i| write(-32 + (i % 8) as i32, -16, i))
                .collect();
            let (columns, _) = Columns::from_writes(&writes, None, -64, 384, &Metrics::default());
            assert!(matches!(columns, Columns::Packed(_)));
            let anchor = completed.anchors.len();
            for ((x, _), _) in columns.iter() {
                completed
                    .columns
                    .entry((x + 32) as usize)
                    .or_default()
                    .push(anchor);
            }
            completed.anchors.push((
                (ax, -1),
                Arc::new(Output {
                    writes,
                    columns,
                    metadata: None,
                }),
            ));
        }
        for x in -32..-24 {
            for limit_ax in -3..=1 {
                for limit_index in 0..=96 {
                    let limit = (-1, limit_ax, limit_index);
                    let expected: Vec<_> =
                        completed
                            .anchors
                            .iter()
                            .flat_map(|((ax, az), output)| {
                                output.writes.iter().enumerate().filter_map(
                                    move |(index, block)| {
                                        let key = (*az, *ax, index);
                                        (block.x == x && key < limit).then_some((key, *block))
                                    },
                                )
                            })
                            .collect();
                    let actual: Vec<_> = completed
                        .writes((x + 32) as usize, x, -16)
                        .filter(|(key, _)| *key < limit)
                        .collect();
                    assert_eq!(actual, expected);
                }
            }
        }
    }

}

#[cfg(test)]
mod packed_uniform_tests {
    use super::*;
    fn write(x: i32, y: i32, z: i32, material: u16) -> WorldBlock {
        WorldBlock { root_order: 2, x, y, z, material, role: FEATURE, upper: 0, tick: 0 }
    }
    fn same_indices(actual: &Columns, baseline: &Columns) {
        assert_eq!(actual.len(), baseline.len());
        for (key, ids) in baseline.iter() {
            assert_eq!(actual.get(&key), Some(ids));
        }
    }
    #[test]
    fn packed_uniform_bitmaps_match_map_and_dense_replay() {
        let (profile, _, _) = crate::decoration::replay_tests::fixture(42, 0);
        for height in [1, 2, 63, 64, 65, 127, 128, 255, 256] {
            for min_y in [-64, 0, 16] {
                for material in [0, 4, 7] {
                    for origin in [(0, 0), (-33, -17), (i32::MIN, i32::MAX - 7)] {
                        let writes: Vec<_> = (0..192).map(|i| write(
                            origin.0 + (i * 11 % 8) as i32,
                            min_y + (i * 71 % (height + 2)) as i32 - 1,
                            origin.1 + (i * 17 / 8 % 8) as i32, material)).collect();
                        let before = writes.clone();
                        let (actual, deferred) = Columns::from_writes(&writes, Some(material), min_y, height, &Metrics::default());
                        let (baseline, old_deferred) = Columns::from_writes_map(&writes, Some(material), min_y, height, &Metrics::default());
                        assert!(matches!(actual, Columns::Packed(_)));
                        assert_eq!(deferred, old_deferred);
                        same_indices(&actual, &baseline);
                        for (key, ids) in actual.iter() {
                            let (edits, count) = actual.edits(&key).unwrap();
                            assert_eq!(count, ids.len());
                            let original: Vec<_> = (0..height).map(|y| ((y / 7) % 9) as u16).collect();
                            let prior = ColumnState::new(original.clone());
                            let mut expected = original;
                            for &i in ids {
                                let y = writes[i].y - min_y;
                                if y >= 0 && (y as usize) < height { expected[y as usize] = material; }
                            }
                            let actual_state = edits.finish(&prior, height, material);
                            let map_state = baseline.edits(&key).unwrap().0.finish(&prior, height, material);
                            let dense = ColumnState::new(expected);
                            assert_eq!(actual_state.as_ref().unwrap_or(&prior).runs.slice(), dense.runs.slice());
                            assert_eq!(actual_state.as_ref().unwrap_or(&prior).runs.slice(), map_state.as_ref().unwrap_or(&prior).runs.slice());
                            assert_eq!(actual_state.as_ref().unwrap_or(&prior).heights(&profile, min_y, height), dense.heights(&profile, min_y, height));
                        }
                        assert_eq!(writes, before);
                    }
                }
            }
        }
    }
    #[test]
    fn packed_uniform_rejects_whole_invalid_bitmap_without_losing_commands() {
        for mode in 0..5 {
            let mut writes: Vec<_> = (0..96).map(|i| write((i % 3 * 3) as i32, (i % 256) as i32, (i % 2 * 2) as i32, 4)).collect();
            let mut min_y = 0;
            let mut height = 256;
            let mut material = Some(4);
            match mode {
                0 => writes[63].role = DEFERRED_ORE,
                1 => writes[63].material = 7,
                2 => { min_y = i32::MIN; writes[63].y = i32::MAX; }
                3 => height = 384,
                _ => material = None,
            }
            let (actual, deferred) = Columns::from_writes(&writes, material, min_y, height, &Metrics::default());
            let (baseline, old_deferred) = Columns::from_writes_map(&writes, material, min_y, height, &Metrics::default());
            assert!(matches!(actual, Columns::Packed(_)));
            same_indices(&actual, &baseline);
            assert_eq!(deferred, old_deferred);
            for (key, _) in actual.iter() { assert!(actual.edits(&key).is_none()); }
            assert!(actual.get(&(1, 1)).is_none());
            assert!(actual.edits(&(1, 1)).is_none());
            assert!(actual.edits(&(i32::MAX, i32::MIN)).is_none());
        }
    }
    #[test]
    fn packed_uniform_completed_groups_preserve_partial_source_limits() {
        let mut completed = CompletedRoot { anchors: Vec::new(), columns: Map::default() };
        for ax in -2..=0 {
            let writes: Vec<_> = (0..96).map(|i| write(-32 + (i % 8) as i32, (i * 17 % 256) as i32, -16, 4)).collect();
            let (columns, _) = Columns::from_writes(&writes, Some(4), 0, 256, &Metrics::default());
            assert!(matches!(columns, Columns::Packed(_)));
            let anchor = completed.anchors.len();
            for ((x, _), _) in columns.iter() { completed.columns.entry((x + 32) as usize).or_default().push(anchor); }
            completed.anchors.push(((ax, -1), Arc::new(Output { writes, columns, metadata: None })));
        }
        for x in -32..-24 {
            let column = (x + 32) as usize;
            let (edits, count, groups) = completed.blob_edits(column, x, -16, None).unwrap();
            assert_eq!((count, groups), (36, 3));
            let prior = ColumnState::new(vec![1; 256]);
            let mut expected = prior.expand(256);
            for (_, block) in completed.writes(column, x, -16) { expected[block.y as usize] = block.material; }
            assert_eq!(edits.finish(&prior, 256, 4).unwrap().expand(256), expected);
            for limit_ax in -3..=1 {
                for limit_index in 0..=96 {
                    let limit = (-1, limit_ax, limit_index);
                    assert!(completed.blob_edits(column, x, -16, Some(limit)).is_none());
                    let expected: Vec<_> = completed.anchors.iter().flat_map(|((ax, az), output)| {
                        output.writes.iter().enumerate().filter_map(move |(index, block)| {
                            let key = (*az, *ax, index);
                            (block.x == x && key < limit).then_some((key, *block))
                        })
                    }).collect();
                    let actual: Vec<_> = completed.writes(column, x, -16).filter(|(key, _)| *key < limit).collect();
                    assert_eq!(actual, expected);
                }
            }
        }
    }
    #[test]
    #[ignore = "diagnostic combined index build/drop microbenchmark; not whole-region timing"]
    fn packed_uniform_index_build_drop_benchmark() {
        use std::{hint::black_box, time::Instant};
        for (name, columns, repeats, span, material) in [
            ("uniform_blob", 225, 20, 15, Some(4)),
            ("ordered_compact", 225, 20, 15, None),
            ("small_uniform", 16, 2, 4, Some(4)),
            ("single_column", 1, 256, 1, Some(4)),
            ("wide_uniform", 128, 1, 256, Some(4)),
        ] {
            let writes: Vec<_> = (0..columns * repeats).map(|i| {
                let column = i % columns;
                write((column % span) as i32 * if name == "wide_uniform" { 64 } else { 1 }, (i * 71 % 256) as i32, (column / span) as i32, 4)
            }).collect();
            let (actual, _) = Columns::from_writes(&writes, material, 0, 256, &Metrics::default());
            let (baseline, _) = Columns::from_writes_map(&writes, material, 0, 256, &Metrics::default());
            same_indices(&actual, &baseline);
            for round in 0..25 {
                for packed in if round % 2 == 0 { [false, true] } else { [true, false] } {
                    let start = Instant::now();
                    for _ in 0..100 {
                        let result = if packed {
                            Columns::from_writes(black_box(&writes), material, 0, 256, &Metrics::default())
                        } else {
                            Columns::from_writes_map(black_box(&writes), material, 0, 256, &Metrics::default())
                        };
                        black_box(&result); drop(result);
                    }
                    if round >= 5 {
                        println!("PACKED_UNIFORM_BENCH {{\"case\":\"{name}\",\"round\":{},\"packed\":{packed},\"nanos_per_build_drop\":{}}}", round - 5, start.elapsed().as_nanos() / 100);
                    }
                }
            }
        }
    }
}
