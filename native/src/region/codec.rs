//! Minecraft's MCA codecs. Type 4 is the Java LZ4Block stream, not an LZ4 frame.
use std::ptr::NonNull;

use libdeflater::{CompressionLvl, Compressor};
use serde::Deserialize;

const BLOCK: usize = 64 * 1024;
const MAGIC: &[u8; 8] = b"LZ4Block";
const RAW_TOKEN: u8 = 0x16; // Raw method, log2(64 KiB) - 10.
const LZ4_TOKEN: u8 = 0x26;
const CHECKSUM_SEED: u32 = 0x9747_b28c;
pub(super) const MAX_INLINE_BYTES: usize = 255 * super::SECTOR - 5;

#[derive(Clone, Copy, Debug, Default, Deserialize, PartialEq, Eq)]
#[serde(rename_all = "lowercase")]
pub enum Compression {
    Zlib,
    #[default]
    None,
    Lz4,
}

impl Compression {
    pub(super) fn id(self) -> u8 {
        match self {
            Self::Zlib => 2,
            Self::None => 3,
            Self::Lz4 => 4,
        }
    }

    pub(super) fn from_id(id: u8) -> Result<Self, String> {
        match id {
            2 => Ok(Self::Zlib),
            3 => Ok(Self::None),
            4 => Ok(Self::Lz4),
            _ => Err(format!("unsupported cached MCA compression type {id}")),
        }
    }
}

/// Output codec and zlib level for a world binding.
#[derive(Clone, Copy, Debug, Default, PartialEq, Eq)]
pub struct CompressionSettings {
    codec: Compression,
    level: u8,
}

impl CompressionSettings {
    pub fn new(codec: Compression, level: i32) -> Self {
        let level = if codec == Compression::Zlib {
            level.clamp(0, 9) as u8
        } else {
            0
        };
        Self { codec, level }
    }

    pub fn codec(self) -> Compression {
        self.codec
    }
}

pub(super) struct Record {
    pub codec: Compression,
    pub bytes: Vec<u8>,
}

#[derive(Default)]
pub(super) struct Encoder {
    zlib: Option<(u8, Compressor)>,
    lz4: Option<Lz4>,
    output: Vec<u8>,
}

impl Encoder {
    pub fn record(
        &mut self,
        settings: CompressionSettings,
        input: &mut Vec<u8>,
    ) -> Result<Record, String> {
        if settings.codec == Compression::None {
            return Ok(Record {
                codec: settings.codec,
                bytes: std::mem::take(input),
            });
        }
        self.encode(settings, input)
    }

    fn encode(&mut self, settings: CompressionSettings, input: &[u8]) -> Result<Record, String> {
        self.compress(settings, input)?;
        let mut codec = settings.codec;
        if self.output.len() > MAX_INLINE_BYTES && codec == Compression::Lz4 {
            // Keep the existing zlib level 1 fallback for oversized LZ4 chunks.
            self.compress(CompressionSettings::new(Compression::Zlib, 1), input)?;
            if self.output.len() > MAX_INLINE_BYTES {
                return Err("generated MCA record exceeds the inline Anvil sector limit".into());
            }
            codec = Compression::Zlib;
        }
        Ok(Record {
            codec,
            bytes: self.output.clone(),
        })
    }

    fn compress(&mut self, settings: CompressionSettings, input: &[u8]) -> Result<(), String> {
        match settings.codec {
            Compression::None => unreachable!("raw NBT bypasses compression"),
            Compression::Zlib => {
                if self.zlib.as_ref().map(|(level, _)| *level) != Some(settings.level) {
                    let level = CompressionLvl::new(i32::from(settings.level)).unwrap();
                    self.zlib = Some((settings.level, Compressor::new(level)));
                }
                let (_, encoder) = self.zlib.as_mut().unwrap();
                self.output
                    .resize(encoder.zlib_compress_bound(input.len()), 0);
                let size = encoder
                    .zlib_compress(input, &mut self.output)
                    .map_err(|e| e.to_string())?;
                self.output.truncate(size);
            }
            Compression::Lz4 => {
                if self.lz4.is_none() {
                    self.lz4 = Some(Lz4::new()?);
                }
                self.lz4.as_mut().unwrap().encode(input, &mut self.output)?;
            }
        }
        Ok(())
    }
}

// lz4-sys links this public LZ4 API but does not declare its reusable-state variant.
unsafe extern "C" {
    fn LZ4_compress_fast_extState(
        state: *mut std::ffi::c_void,
        source: *const std::ffi::c_char,
        dest: *mut std::ffi::c_char,
        source_size: std::ffi::c_int,
        dest_capacity: std::ffi::c_int,
        acceleration: std::ffi::c_int,
    ) -> std::ffi::c_int;
}

struct Lz4 {
    state: NonNull<lz4_sys::LZ4StreamEncode>,
    block: Vec<u8>,
}

impl Lz4 {
    fn new() -> Result<Self, String> {
        // createStream allocates and aligns an LZ4_stream_t suitable for extState.
        let state = NonNull::new(unsafe { lz4_sys::LZ4_createStream() })
            .ok_or("unable to allocate LZ4 compressor")?;
        let bound = unsafe { lz4_sys::LZ4_compressBound(BLOCK as i32) } as usize;
        Ok(Self {
            state,
            block: vec![0; bound],
        })
    }

    fn encode(&mut self, input: &[u8], output: &mut Vec<u8>) -> Result<(), String> {
        output.clear();
        for raw in input.chunks(BLOCK) {
            // extState resets its dictionary on every call. Each block must be
            // independently decodable by Java's LZ4BlockInputStream.
            let size = unsafe {
                LZ4_compress_fast_extState(
                    self.state.as_ptr().cast(),
                    raw.as_ptr().cast(),
                    self.block.as_mut_ptr().cast(),
                    raw.len() as i32,
                    self.block.len() as i32,
                    1,
                )
            };
            if size <= 0 {
                return Err("LZ4 block compression failed".into());
            }
            let packed = &self.block[..size as usize];
            let (token, payload) = if packed.len() < raw.len() {
                (LZ4_TOKEN, packed)
            } else {
                (RAW_TOKEN, raw)
            };
            // StreamingXXHash32.asChecksum exposes the low 28 bits in lz4-java.
            let checksum = xxhash_rust::xxh32::xxh32(raw, CHECKSUM_SEED) & 0x0fff_ffff;
            header(output, token, payload.len(), raw.len(), checksum);
            output.extend_from_slice(payload);
        }
        header(output, RAW_TOKEN, 0, 0, 0);
        Ok(())
    }
}

fn header(output: &mut Vec<u8>, token: u8, packed: usize, raw: usize, checksum: u32) {
    output.extend_from_slice(MAGIC);
    output.push(token);
    output.extend_from_slice(&(packed as u32).to_le_bytes());
    output.extend_from_slice(&(raw as u32).to_le_bytes());
    output.extend_from_slice(&checksum.to_le_bytes());
}

impl Drop for Lz4 {
    fn drop(&mut self) {
        unsafe {
            lz4_sys::LZ4_freeStream(self.state.as_ptr());
        }
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    fn decode(record: &Record, size: usize) -> Vec<u8> {
        if record.codec == Compression::None {
            return record.bytes.clone();
        }
        if record.codec == Compression::Zlib {
            let mut raw = vec![0; size];
            assert_eq!(
                libdeflater::Decompressor::new()
                    .zlib_decompress(&record.bytes, &mut raw)
                    .unwrap(),
                size
            );
            return raw;
        }
        let mut cursor = record.bytes.as_slice();
        let mut output = Vec::new();
        loop {
            assert_eq!(&cursor[..8], b"LZ4Block");
            assert_eq!(cursor[8] & 15, 6);
            let method = cursor[8] >> 4;
            let packed = u32::from_le_bytes(cursor[9..13].try_into().unwrap()) as usize;
            let raw_size = u32::from_le_bytes(cursor[13..17].try_into().unwrap()) as usize;
            let checksum = u32::from_le_bytes(cursor[17..21].try_into().unwrap());
            cursor = &cursor[21..];
            if raw_size == 0 {
                assert_eq!((method, packed, checksum), (1, 0, 0));
                assert!(cursor.is_empty());
                break;
            }
            assert!(raw_size <= 65536);
            let raw = if method == 1 {
                assert_eq!(packed, raw_size);
                cursor[..packed].to_vec()
            } else {
                assert_eq!(method, 2);
                let mut raw = vec![0u8; raw_size];
                assert_eq!(
                    unsafe {
                        lz4_sys::LZ4_decompress_safe(
                            cursor.as_ptr().cast(),
                            raw.as_mut_ptr().cast(),
                            packed as i32,
                            raw_size as i32,
                        )
                    },
                    raw_size as i32
                );
                raw
            };
            assert_eq!(
                xxhash_rust::xxh32::xxh32(&raw, 0x9747_b28c) & 0x0fff_ffff,
                checksum
            );
            output.extend(raw);
            cursor = &cursor[packed..];
        }
        assert_eq!(output.len(), size);
        output
    }

    fn random(size: usize, mask: u8) -> Vec<u8> {
        let mut rng = 7u64;
        (0..size)
            .map(|_| {
                rng ^= rng << 13;
                rng ^= rng >> 7;
                rng ^= rng << 17;
                rng as u8 & mask
            })
            .collect()
    }

    #[test]
    fn independent_blocks_raw_compressed_empty_and_reused_state_roundtrip() {
        let mut encoder = Encoder::default();
        for input in [
            vec![],
            vec![42; 1],
            vec![42; 65536],
            vec![42; 131073],
            random(131073, 255),
            vec![0; 4096],
        ] {
            for codec in [Compression::None, Compression::Zlib, Compression::Lz4] {
                let record = encoder
                    .record(CompressionSettings::new(codec, 0), &mut input.clone())
                    .unwrap();
                assert_eq!(record.codec, codec);
                assert_eq!(decode(&record, input.len()), input);
            }
        }
    }

    #[test]
    fn zlib_level_clamps_and_reconfigures_the_reused_encoder() {
        let mut encoder = Encoder::default();
        let input = vec![42; MAX_INLINE_BYTES];
        let zlib = |level| CompressionSettings::new(Compression::Zlib, level);
        let stored = encoder.encode(zlib(-1), &input).unwrap();
        let compressed = encoder.encode(zlib(10), &input).unwrap();
        assert_eq!(stored.bytes[2] & 0b110, 0); // DEFLATE stored block.
        assert!(stored.bytes.len() > MAX_INLINE_BYTES);
        assert!(compressed.bytes.len() < input.len() / 2);
        assert_eq!(
            compressed.bytes,
            encoder.encode(zlib(9), &input).unwrap().bytes
        );
        assert_eq!(stored.bytes, encoder.encode(zlib(0), &input).unwrap().bytes);
        for record in [stored, compressed] {
            assert_eq!(decode(&record, input.len()), input);
        }
    }

    #[test]
    fn oversize_lz4_uses_zlib_and_unrepresentable_records_fail() {
        let input = random(1_500_000, 15);
        let mut encoder = Encoder::default();
        let settings = CompressionSettings::new(Compression::Lz4, 0);
        encoder.compress(settings, &input).unwrap();
        assert!(encoder.output.len() > MAX_INLINE_BYTES);
        let record = encoder.encode(settings, &input).unwrap();
        assert_eq!(record.codec, Compression::Zlib);
        assert_eq!(decode(&record, input.len()), input);
        assert!(encoder.encode(settings, &random(1_100_000, 255)).is_err());
    }

    #[test]
    fn codec_ids_reject_external_or_unknown_cached_records() {
        assert_eq!(Compression::from_id(2).unwrap(), Compression::Zlib);
        assert_eq!(Compression::from_id(4).unwrap(), Compression::Lz4);
        assert_eq!(Compression::default(), Compression::from_id(3).unwrap());
        for id in [0, 1, 127, 130, 131, 132] {
            assert!(Compression::from_id(id).is_err());
        }
        assert!(serde_json::from_str::<Compression>("\"gzip\"").is_err());
    }
}
