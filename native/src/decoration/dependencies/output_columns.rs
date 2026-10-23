//! Original per-column command indexes, with compact edits only for uniform
//! blob outputs. The existing indexing pass builds both using one map lookup.
use super::*;
use std::collections::hash_map;

type Position = (i32, i32);

#[derive(Default)]
pub(super) struct BlobColumn {
    indices: Vec<usize>,
    edits: uniform_blob::Edits,
}

pub(super) enum Columns {
    Ordered(Map<Position, Vec<usize>>),
    Uniform(Map<Position, BlobColumn>),
}
impl Default for Columns {
    fn default() -> Self {
        Self::Ordered(Map::default())
    }
}
impl From<Map<Position, Vec<usize>>> for Columns {
    fn from(columns: Map<Position, Vec<usize>>) -> Self {
        Self::Ordered(columns)
    }
}
#[cfg(test)]
impl FromIterator<(Position, Vec<usize>)> for Columns {
    fn from_iter<T: IntoIterator<Item = (Position, Vec<usize>)>>(items: T) -> Self {
        Self::Ordered(items.into_iter().collect())
    }
}
impl Columns {
    pub(super) fn from_writes(
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
    pub(super) fn get(&self, key: &Position) -> Option<&Vec<usize>> {
        match self {
            Self::Ordered(columns) => columns.get(key),
            Self::Uniform(columns) => columns.get(key).map(|column| &column.indices),
        }
    }
    pub(super) fn edits(&self, key: &Position) -> Option<(&uniform_blob::Edits, usize)> {
        match self {
            Self::Uniform(columns) => columns
                .get(key)
                .map(|column| (&column.edits, column.indices.len())),
            Self::Ordered(_) => None,
        }
    }
    pub(super) fn iter(&self) -> impl Iterator<Item = (&Position, &Vec<usize>)> {
        match self {
            Self::Ordered(columns) => Iter::Ordered(columns.iter()),
            Self::Uniform(columns) => Iter::Uniform(columns.iter()),
        }
    }
    #[cfg(test)]
    pub(super) fn len(&self) -> usize {
        match self {
            Self::Ordered(columns) => columns.len(),
            Self::Uniform(columns) => columns.len(),
        }
    }
    #[cfg(test)]
    pub(super) fn values(&self) -> impl Iterator<Item = &Vec<usize>> {
        self.iter().map(|(_, indices)| indices)
    }
}
enum Iter<'a> {
    Ordered(hash_map::Iter<'a, Position, Vec<usize>>),
    Uniform(hash_map::Iter<'a, Position, BlobColumn>),
}
impl<'a> Iterator for Iter<'a> {
    type Item = (&'a Position, &'a Vec<usize>);
    fn next(&mut self) -> Option<Self::Item> {
        match self {
            Self::Ordered(iter) => iter.next(),
            Self::Uniform(iter) => iter.next().map(|(key, column)| (key, &column.indices)),
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
                    for (&key, ids) in columns.iter() {
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
            assert_eq!(columns.get(&(0, 0)), Some(&vec![0]));
            assert!(columns.edits(&(0, 0)).is_none());
            assert_eq!(deferred, u64::from(writes[0].role == DEFERRED_ORE));
        }
        let writes = [write(0, i32::MAX, 0, 4)];
        let (columns, _) =
            Columns::from_writes(&writes, Some(4), i32::MIN, 256, &Metrics::default());
        assert!(matches!(columns, Columns::Ordered(_)));
        assert_eq!(columns.get(&(0, 0)), Some(&vec![0]));
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
