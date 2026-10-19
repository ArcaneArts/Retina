//! Lossless final-material transport. A uniform section is one descriptor;
//! every mixed section retains all 4,096 registered material IDs.
pub(super) const UNIFORM: u32 = 1 << 31;
const BLOCKS: usize = 4096;

pub(super) fn classify(blocks: &[u16]) -> Vec<u32> {
    blocks
        .chunks_exact(BLOCKS)
        .map(|section| {
            let first = section[0];
            // A reduction lets LLVM compare many IDs per SIMD instruction.
            // Scalar short-circuit `all` was slower on mostly uniform terrain.
            if section
                .iter()
                .fold(0u16, |difference, &m| difference | (m ^ first))
                == 0
            {
                UNIFORM | u32::from(first)
            } else {
                0
            }
        })
        .collect()
}

/// Input order remains chunk, section, y, z, x. Dense descriptors are word
/// offsets into this same allocation; uniform descriptors carry the exact ID.
pub(super) fn pack<'a>(
    words: &mut Vec<u32>,
    chunks: impl ExactSizeIterator<Item = (&'a [u16], &'a [u32])>,
    height: u32,
) {
    let sections = height as usize / 16;
    words.clear();
    words.resize(chunks.len() * sections, 0);
    for (chunk, (blocks, descriptors)) in chunks.enumerate() {
        for (section, values) in blocks.chunks_exact(BLOCKS).enumerate() {
            let descriptor = descriptors[section];
            words[chunk * sections + section] = if descriptor & UNIFORM != 0 {
                descriptor
            } else {
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
