//! Bounded GPU section output. Uniform sections need
//! no device output; mixed sections above the palette cap retain the CPU encoder.
use super::transport::UNIFORM;

pub(super) const CAPACITY: usize = 32;
pub(super) const RECORD_WORDS: usize = 1 + CAPACITY + 4096usize.div_ceil(12) * 2;

pub(super) struct Layout {
    pub descriptors: Vec<u32>,
    pub sources: Vec<u32>,
}
impl Layout {
    pub fn new(materials: &[Vec<u32>], side: usize, crop: usize, core: usize) -> Self {
        let sections = materials[0].len();
        let mut descriptors = Vec::with_capacity(core * core * sections);
        let mut sources = Vec::new();
        for z in crop..crop + core {
            for x in crop..crop + core {
                let chunk = z * side + x;
                for (section, &descriptor) in materials[chunk].iter().enumerate() {
                    descriptors.push(if descriptor & UNIFORM != 0 {
                        descriptor
                    } else {
                        let offset = sources.len() * RECORD_WORDS;
                        sources.push((chunk * sections + section) as u32);
                        offset as u32
                    });
                }
            }
        }
        Self {
            descriptors,
            sources,
        }
    }
    pub fn bytes(&self) -> usize {
        self.sources.len() * RECORD_WORDS * 4
    }
}

#[derive(Clone, Copy)]
pub(crate) struct ChunkPalettes<'a> {
    pub(super) bytes: &'a [u8],
    pub(super) descriptors: &'a [u32],
}
#[derive(Clone, Copy)]
pub(crate) enum Section<'a> {
    Uniform(u16),
    Packed { palette: &'a [u8], data: &'a [u8] },
}
impl<'a> ChunkPalettes<'a> {
    pub(crate) fn section(self, section: usize) -> Option<Section<'a>> {
        let descriptor = self.descriptors[section];
        if descriptor & UNIFORM != 0 {
            return Some(Section::Uniform(descriptor as u16));
        }
        let start = descriptor as usize * 4;
        let count = u32::from_le_bytes(self.bytes[start..start + 4].try_into().unwrap()) as usize;
        if count == 0 {
            return None;
        }
        let bits = (usize::BITS - (count - 1).leading_zeros()).max(4) as usize;
        let packed = start + (1 + CAPACITY) * 4;
        Some(Section::Packed {
            palette: &self.bytes[start + 4..start + 4 + count * 4],
            data: if count == 1 {
                &[]
            } else {
                &self.bytes[packed..packed + 4096usize.div_ceil(64 / bits) * 8]
            },
        })
    }
}
