//! Final material sections. Untouched uniform air stays a descriptor from End
//! substrate replay through lighting transport and NBT publication.
use crate::{COLUMNS, ChunkRequest, geology::CaveMask};

pub(crate) const SECTION: usize = 4096;
#[derive(Clone, Copy)]
pub(crate) enum View<'a> {
    Uniform(u16),
    Dense(&'a [u16]),
}
impl View<'_> {
    #[inline]
    pub fn value(self, index: usize) -> u16 {
        match self {
            Self::Uniform(id) => id,
            Self::Dense(values) => values[index],
        }
    }
}

pub(crate) trait Read {
    fn len(&self) -> usize;
    fn value(&self, index: usize) -> u16;
    fn section(&self, index: usize) -> View<'_>;
    fn row(&self, layer: usize) -> View<'_>;
    #[inline]
    fn get(&self, index: usize) -> Option<u16> {
        (index < self.len()).then(|| self.value(index))
    }
    fn extend_dense(&self, output: &mut Vec<u16>) {
        for section in 0..self.len() / SECTION {
            match self.section(section) {
                View::Uniform(id) => output.resize(output.len() + SECTION, id),
                View::Dense(values) => output.extend_from_slice(values),
            }
        }
    }
}
pub(crate) trait Write: Read {
    fn put(&mut self, index: usize, material: u16);
}
impl Read for [u16] {
    #[inline]
    fn len(&self) -> usize {
        <[u16]>::len(self)
    }
    #[inline]
    fn value(&self, index: usize) -> u16 {
        self[index]
    }
    #[inline]
    fn section(&self, index: usize) -> View<'_> {
        View::Dense(&self[index * SECTION..(index + 1) * SECTION])
    }
    #[inline]
    fn row(&self, layer: usize) -> View<'_> {
        View::Dense(&self[layer * COLUMNS..(layer + 1) * COLUMNS])
    }
    fn extend_dense(&self, output: &mut Vec<u16>) {
        output.extend_from_slice(self);
    }
}
impl Write for [u16] {
    #[inline]
    fn put(&mut self, index: usize, material: u16) {
        self[index] = material;
    }
}
impl Read for Vec<u16> {
    #[inline]
    fn len(&self) -> usize {
        self.as_slice().len()
    }
    #[inline]
    fn value(&self, index: usize) -> u16 {
        self[index]
    }
    #[inline]
    fn section(&self, index: usize) -> View<'_> {
        self.as_slice().section(index)
    }
    #[inline]
    fn row(&self, layer: usize) -> View<'_> {
        self.as_slice().row(layer)
    }
    fn extend_dense(&self, output: &mut Vec<u16>) {
        output.extend_from_slice(self);
    }
}
impl Write for Vec<u16> {
    #[inline]
    fn put(&mut self, index: usize, material: u16) {
        self[index] = material;
    }
}

pub(crate) enum Section {
    Uniform(u16),
    Dense(Box<[u16; SECTION]>),
}
pub(crate) enum Chunk {
    Dense(Vec<u16>),
    Sections(Vec<Section>),
}
impl Chunk {
    pub fn uniform(height: u32, material: u16) -> Self {
        Self::Sections(
            (0..height as usize / 16)
                .map(|_| Section::Uniform(material))
                .collect(),
        )
    }
    pub fn from_runs(request: ChunkRequest, mask: &CaveMask) -> Result<Self, String> {
        let mut result = Self::uniform(request.height, 0);
        for column in 0..COLUMNS {
            let runs = mask
                .material_runs(
                    request.chunk_x * 16 + (column % 16) as i32,
                    request.chunk_z * 16 + (column / 16) as i32,
                )
                .ok_or("End material runs do not cover chunk")?;
            let mut top = mask.min_y + mask.height as i32;
            for &run in runs {
                let bottom = mask.min_y + (run >> 16) as i32;
                let id = run as u16;
                let first = bottom.max(request.min_y) - request.min_y;
                let last = top.min(request.min_y + request.height as i32) - request.min_y;
                if id != 0 && first < last {
                    result.column_range(column, first as usize, last as usize, id);
                }
                top = bottom;
            }
        }
        Ok(result)
    }
    fn column_range(&mut self, column: usize, mut first: usize, last: usize, id: u16) {
        let Self::Sections(sections) = self else {
            unreachable!()
        };
        while first < last {
            let index = first / 16;
            let end = last.min((index + 1) * 16);
            let section = &mut sections[index];
            if let Section::Uniform(previous) = section {
                if *previous == id {
                    first = end;
                    continue;
                }
                *section = Section::Dense(Box::new([*previous; SECTION]));
            }
            let Section::Dense(values) = section else {
                unreachable!()
            };
            for y in first..end {
                values[(y % 16) * COLUMNS + column] = id;
            }
            first = end;
        }
    }
    pub fn fit(&mut self, generation: ChunkRequest, storage: ChunkRequest, air: u16) {
        if generation.min_y == storage.min_y && generation.height == storage.height {
            return;
        }
        let Self::Sections(previous) = self else {
            unreachable!()
        };
        let mut output: Vec<_> = (0..storage.height / 16)
            .map(|_| Section::Uniform(air))
            .collect();
        for (index, section) in std::mem::take(previous).into_iter().enumerate() {
            let y = generation.min_y / 16 + index as i32 - storage.min_y / 16;
            if let Some(target) = usize::try_from(y).ok().and_then(|i| output.get_mut(i)) {
                *target = section;
            }
        }
        *previous = output;
    }
    pub fn allocation(&self) -> (usize, usize) {
        match self {
            Self::Dense(values) => (values.capacity() * 2, 0),
            Self::Sections(sections) => (
                sections.capacity() * std::mem::size_of::<Section>()
                    + sections
                        .iter()
                        .filter(|s| matches!(s, Section::Dense(_)))
                        .count()
                        * SECTION
                        * 2,
                sections
                    .iter()
                    .filter(|s| matches!(s, Section::Uniform(_)))
                    .count(),
            ),
        }
    }
}
impl Read for Chunk {
    #[inline]
    fn len(&self) -> usize {
        match self {
            Self::Dense(v) => v.len(),
            Self::Sections(v) => v.len() * SECTION,
        }
    }
    #[inline]
    fn value(&self, index: usize) -> u16 {
        match self {
            Self::Dense(v) => v[index],
            Self::Sections(v) => match &v[index / SECTION] {
                Section::Uniform(id) => *id,
                Section::Dense(v) => v[index % SECTION],
            },
        }
    }
    #[inline]
    fn section(&self, index: usize) -> View<'_> {
        match self {
            Self::Dense(v) => v.as_slice().section(index),
            Self::Sections(v) => match &v[index] {
                Section::Uniform(id) => View::Uniform(*id),
                Section::Dense(v) => View::Dense(&v[..]),
            },
        }
    }
    #[inline]
    fn row(&self, layer: usize) -> View<'_> {
        match self.section(layer / 16) {
            View::Uniform(id) => View::Uniform(id),
            View::Dense(v) => View::Dense(&v[(layer % 16) * COLUMNS..(layer % 16 + 1) * COLUMNS]),
        }
    }
}
impl Write for Chunk {
    #[inline]
    fn put(&mut self, index: usize, material: u16) {
        match self {
            Self::Dense(values) => values[index] = material,
            Self::Sections(sections) => {
                let section = &mut sections[index / SECTION];
                if let Section::Uniform(previous) = section {
                    if *previous == material {
                        return;
                    }
                    *section = Section::Dense(Box::new([*previous; SECTION]));
                }
                let Section::Dense(values) = section else {
                    unreachable!()
                };
                values[index % SECTION] = material;
            }
        }
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    #[test]
    fn uniform_sections_materialize_only_changed_sections_and_keep_world_y_on_padding() {
        let mut chunk = Chunk::uniform(256, 0);
        assert_eq!(chunk.allocation().1, 16);
        assert!(chunk.allocation().0 < 1024);
        chunk.put(SECTION, 0);
        assert_eq!(
            chunk.allocation().1,
            16,
            "identity air write never allocates a section"
        );
        let mut expected = vec![0; 256 * COLUMNS];
        for (at, id) in [
            (0, 7),
            (SECTION - 1, 19),
            (SECTION, 23),
            (SECTION * 15, u16::MAX),
        ] {
            chunk.put(at, id);
            expected[at] = id;
        }
        assert_eq!(chunk.allocation().1, 13);
        for (i, &id) in expected.iter().enumerate() {
            assert_eq!(chunk.value(i), id);
        }
        let mut dense = Vec::new();
        chunk.extend_dense(&mut dense);
        assert_eq!(dense, expected);
        let generation = ChunkRequest {
            seed: 0,
            chunk_x: -1,
            chunk_z: 2,
            min_y: 0,
            height: 256,
            base_height: 0.,
            amplitude: 0.,
            frequency: 1.,
            reserved: 0,
        };
        let storage = ChunkRequest {
            min_y: -64,
            height: 384,
            ..generation
        };
        chunk.fit(generation, storage, 31);
        assert_eq!(chunk.allocation().1, 21);
        for i in 0..chunk.len() {
            assert_eq!(
                chunk.value(i),
                if (64 * COLUMNS..320 * COLUMNS).contains(&i) {
                    expected[i - 64 * COLUMNS]
                } else {
                    31
                }
            );
        }
        chunk.fit(storage, generation, 0);
        let mut restored = Vec::new();
        chunk.extend_dense(&mut restored);
        assert_eq!(restored, expected);
    }
}
