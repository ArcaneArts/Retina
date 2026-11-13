//! Lossless final-material transport. A uniform section is one descriptor;
//! every mixed section retains all 4,096 registered material IDs.
pub(super) const UNIFORM: u32 = 1 << 31;
const BLOCKS: usize = 4096;

/// Reserve the tile-local input prefix and stage shared mixed sections once.
/// Shared offsets stay valid when later submissions replace the tile prefix.
pub(super) fn shared_input<B: crate::material_blocks::Read>(
    device: &wgpu::Device,
    queue: &wgpu::Queue,
    chunks: &[B],
    descriptors: &mut [Vec<u32>],
    repeated: &[bool],
    prefix_bytes: u64,
) -> Option<(wgpu::Buffer, u64)> {
    let mixed = descriptors
        .iter()
        .zip(repeated)
        .filter(|&(_, repeat)| *repeat)
        .flat_map(|(facts, _)| facts)
        .filter(|&&word| word & UNIFORM == 0)
        .count();
    if mixed == 0 {
        return None;
    }
    let payload_bytes = (mixed * BLOCKS * size_of::<u16>()) as u64;
    let size = prefix_bytes + payload_bytes;
    let limits = device.limits();
    if size > limits.max_buffer_size.min(limits.max_storage_buffer_binding_size.into()) {
        return None;
    }
    let buffer = device.create_buffer(&wgpu::BufferDescriptor {
        label: Some("Retina shared lighting materials"),
        size,
        usage: wgpu::BufferUsages::STORAGE | wgpu::BufferUsages::COPY_DST,
        mapped_at_creation: false,
    });
    let length = std::num::NonZeroU64::new(payload_bytes)?;
    let mut staging = queue.write_buffer_with(&buffer, prefix_bytes, length)?;
    let section_bytes = BLOCKS * size_of::<u16>();
    let mut written = 0;
    for ((chunk, facts), &repeat) in chunks.iter().zip(descriptors).zip(repeated) {
        if !repeat {
            continue;
        }
        for (section, descriptor) in facts.iter_mut().enumerate() {
            if *descriptor & UNIFORM != 0 {
                continue;
            }
            let crate::material_blocks::View::Dense(values) = chunk.section(section) else {
                unreachable!("mixed descriptor requires a dense section");
            };
            let next = written + section_bytes;
            staging
                .slice(written..next)
                .copy_from_slice(bytemuck::cast_slice(values));
            *descriptor = ((prefix_bytes + written as u64) / 4) as u32;
            written = next;
        }
    }
    Some((buffer, payload_bytes))
}

pub(super) fn classify<B: crate::material_blocks::Read + ?Sized>(blocks: &B) -> Vec<u32> {
    (0..blocks.len() / BLOCKS).map(|index| match blocks.section(index) {
        crate::material_blocks::View::Uniform(id) => UNIFORM | u32::from(id),
        crate::material_blocks::View::Dense(section) => {
            let first = section[0];
            if section.iter().fold(0u16, |difference, &m| difference | (m ^ first)) == 0 {
                UNIFORM | u32::from(first)
            } else { 0 }
        }
    }).collect()
}

/// Input order remains chunk, section, y, z, x. Dense descriptors are word
/// offsets into the tile payload or immutable halo in the same buffer.
/// Uniform descriptors carry the exact ID.
pub(super) fn pack<'a, B: crate::material_blocks::Read + ?Sized + 'a>(
    words: &mut Vec<u32>,
    chunks: impl ExactSizeIterator<Item = (&'a B, &'a [u32])>,
    height: u32,
) {
    let sections = height as usize / 16;
    words.clear();
    words.resize(chunks.len() * sections, 0);
    for (chunk, (blocks, descriptors)) in chunks.enumerate() {
        for section in 0..sections {
            let descriptor = descriptors[section];
            words[chunk * sections + section] = if descriptor != 0 {
                descriptor
            } else {
                let crate::material_blocks::View::Dense(values) = blocks.section(section) else {
                    unreachable!("mixed descriptor requires a dense section");
                };
                let offset = words.len();
                words.resize(offset + BLOCKS / 2, 0);
                bytemuck::cast_slice_mut::<u32, u8>(&mut words[offset..])
                    .copy_from_slice(bytemuck::cast_slice(values));
                offset as u32
            };
        }
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn lazy_sections_pack_exactly_like_dense_materials_without_air_arrays() {
        use crate::material_blocks::{Chunk, Read, Write};
        let mut compact = Chunk::uniform(48, 0);
        compact.put(BLOCKS + 17, 42);
        let before = compact.allocation();
        let mut dense = Vec::new(); compact.extend_dense(&mut dense);
        let descriptor = classify(&compact);
        assert_eq!(descriptor, classify(&dense));
        let (mut a, mut b) = (Vec::new(), Vec::new());
        pack(&mut a, std::iter::once((&compact, &descriptor[..])), 48);
        pack(&mut b, std::iter::once((&dense, &descriptor[..])), 48);
        assert_eq!(a, b);
        assert_eq!(a.len(), 3 + BLOCKS / 2);
        assert_eq!(compact.allocation(), before, "packing never expands uniform sections");
        assert_eq!(compact.allocation().1, 2);
    }
    #[test]
    fn material_transport_retains_uniform_and_mixed_ids_across_sections() {
        let chunks: Vec<Vec<u16>> = (0..4)
            .map(|chunk| {
                (0..BLOCKS * 3)
                    .map(|i| match (i / BLOCKS, chunk) {
                        (0, _) => 0,
                        (1, _) => u16::MAX,
                        (_, 0) => 37,
                        _ => (i * 13 + chunk) as u16,
                    })
                    .collect()
            })
            .collect();
        let metadata: Vec<_> = chunks.iter().map(|c| classify(c)).collect();
        let mut words = Vec::new();
        pack(
            &mut words,
            chunks.iter().zip(&metadata).map(|(b, m)| (&b[..], &m[..])),
            48,
        );
        assert_eq!(words.len(), 12 + 3 * 2048);
        for (i, &expected) in chunks.iter().flatten().enumerate() {
            let descriptor = words[i / BLOCKS];
            let actual = if descriptor & UNIFORM != 0 {
                descriptor as u16
            } else {
                (words[descriptor as usize + (i % BLOCKS) / 2] >> ((i % 2) * 16)) as u16
            };
            assert_eq!(actual, expected, "block {i}");
        }
        // Reuse a larger allocation for a smaller, completely uniform input.
        pack(
            &mut words,
            std::iter::once((&chunks[0][..], &metadata[0][..])),
            48,
        );
        assert_eq!(words, [UNIFORM, UNIFORM | 65535, UNIFORM | 37]);
    }

    #[test]
    fn one_different_material_anywhere_keeps_a_dense_section() {
        let mut section = vec![65535; BLOCKS];
        for index in [0, 1, 31, 32, 127, 128, 2047, 2048, 4095] {
            section[index] = 32767;
            assert_eq!(classify(&section), [0], "index {index}");
            section[index] = 65535;
        }
        assert_eq!(classify(&section), [UNIFORM | 65535]);
    }
}
