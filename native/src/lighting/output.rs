//! Conservative constant-light proofs from final section materials. Unknown
//! layers retain full GPU output; no estimated light level is ever accepted.
use super::{Profile, transport::UNIFORM};

const DENSE: u32 = 0;

pub(super) struct Constants {
    layers: Vec<Vec<[u32; 2]>>,
}
impl Constants {
    pub(super) fn new(profile: &Profile, materials: &[Vec<u32>], side: usize) -> Self {
        let sections = materials[0].len() + 2;
        let air = profile.states[0];
        let stride = profile.faces.div_ceil(32) as usize;
        let clear_air = air[0] & 15 == 0
            && profile.blocked[air[1] as usize * stride + air[2] as usize / 32]
                & (1 << (air[2] % 32))
                == 0;
        // Mixed sections conservatively count as possible emitters. One section
        // of halo is enough: a section two away starts at least 17 edges away,
        // beyond the fourteen edges over which positive block light can spread.
        let mut possible = vec![vec![false; sections]; materials.len()];
        for (chunk, input) in materials.iter().enumerate() {
            for (section, out) in possible[chunk].iter_mut().enumerate() {
                let descriptor = if section == 0 || section + 1 == sections {
                    UNIFORM
                } else {
                    input[section - 1]
                };
                *out = descriptor & UNIFORM == 0
                    || profile.states[(descriptor & 65535) as usize][0] >> 4 != 0;
            }
        }
        let mut layers = vec![vec![[DENSE; 2]; sections]; materials.len()];
        for (chunk, input) in materials.iter().enumerate() {
            let x = chunk % side;
            let z = chunk / side;
            let mut open = clear_air;
            for section in (0..sections).rev() {
                let descriptor = if section == 0 || section + 1 == sections {
                    UNIFORM
                } else {
                    input[section - 1]
                };
                open &= descriptor == UNIFORM;
                let uniform = (descriptor & UNIFORM != 0)
                    .then(|| profile.states[(descriptor & 65535) as usize][0]);
                let opaque = uniform.is_some_and(|traits| traits & 15 == 15);
                let layer = &mut layers[chunk][section];
                if !profile.sky || opaque {
                    layer[1] = UNIFORM;
                } else if open {
                    layer[1] = UNIFORM | 15;
                }
                if opaque || uniform.is_some_and(|traits| traits >> 4 == 15) {
                    layer[0] = UNIFORM | (uniform.unwrap() >> 4);
                } else {
                    let source = (z.saturating_sub(1)..=(z + 1).min(side - 1)).any(|nz| {
                        (x.saturating_sub(1)..=(x + 1).min(side - 1)).any(|nx| {
                            (section.saturating_sub(1)..=(section + 1).min(sections - 1))
                                .any(|y| possible[nz * side + nx][y])
                        })
                    });
                    if !source {
                        layer[0] = UNIFORM;
                    }
                }
            }
        }
        Self { layers }
    }
    pub(super) fn tile(&self, chunks: impl Iterator<Item = usize>) -> Layout {
        let mut words = 0;
        let mut descriptors = Vec::new();
        let mut source_channels = Vec::new();
        for chunk in chunks {
            for section in &self.layers[chunk] {
                for &constant in section {
                    let source = descriptors.len() as u32;
                    descriptors.push(if constant & UNIFORM != 0 {
                        constant
                    } else {
                        let offset = words;
                        source_channels.push(source);
                        words += 512;
                        offset
                    });
                }
            }
        }
        Layout {
            descriptors,
            source_channels,
            bytes: (words as usize * 4).max(4),
        }
    }
}

pub(super) struct Layout {
    pub descriptors: Vec<u32>,
    pub source_channels: Vec<u32>,
    pub bytes: usize,
}

#[derive(Clone, Copy)]
pub(crate) enum Channel<'a> {
    Constant(u8),
    Dense(&'a [u8]),
}
#[derive(Clone, Copy)]
pub(crate) struct ChunkLight<'a> {
    bytes: &'a [u8],
    descriptors: Option<&'a [u32]>,
    pub(crate) palettes: Option<super::palettes::ChunkPalettes<'a>>,
}
impl<'a> ChunkLight<'a> {
    pub(super) fn new(bytes: &'a [u8], descriptors: Option<&'a [u32]>) -> Self {
        Self {
            bytes,
            descriptors,
            palettes: None,
        }
    }
    pub(crate) fn section(self, section: usize) -> [Channel<'a>; 2] {
        std::array::from_fn(|channel| {
            let index = section * 2 + channel;
            if let Some(descriptors) = self.descriptors {
                let descriptor = descriptors[index];
                if descriptor & UNIFORM != 0 {
                    Channel::Constant((descriptor & 15) as u8)
                } else {
                    let start = descriptor as usize * 4;
                    Channel::Dense(&self.bytes[start..start + 2048])
                }
            } else {
                Channel::Dense(&self.bytes[index * 2048..(index + 1) * 2048])
            }
        })
    }
    #[cfg(test)]
    pub(super) fn expanded(self, sections: usize) -> Vec<u8> {
        (0..sections)
            .flat_map(|i| self.section(i))
            .flat_map(|channel| match channel {
                Channel::Constant(value) => vec![value * 17; 2048],
                Channel::Dense(bytes) => bytes.to_vec(),
            })
            .collect()
    }
}
