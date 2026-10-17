//! Benchmark-only persistent compression endpoint using the production codec.
//! It shares Bend's framing so host measurements include equivalent pipe IO.
//! No world generation or application bridge uses this executable.
use libdeflater::{CompressionLvl, Compressor};
use std::io::{self, Read, Write};

const MAGIC: u32 = 0x5242_4e44;
const MAX_INPUT: usize = 16 * 1024 * 1024;

fn main() -> Result<(), Box<dyn std::error::Error>> {
    let mut input = io::stdin().lock();
    let mut output = io::stdout().lock();
    let mut compressor = Compressor::new(CompressionLvl::new(1)?);
    let mut source = Vec::new();
    let mut compressed = Vec::new();
    loop {
        let mut header = [0u8; 16];
        if input.read(&mut header[..1])? == 0 {
            return Ok(());
        }
        input.read_exact(&mut header[1..])?;
        let word = |offset| u32::from_be_bytes(header[offset..offset + 4].try_into().unwrap());
        let size = word(4) as usize;
        if word(0) != MAGIC || size > MAX_INPUT {
            return Err("invalid compression benchmark frame".into());
        }
        source.resize(size, 0);
        input.read_exact(&mut source)?;
        let opcode = word(12);
        let response: &[u8] = match opcode {
            0 if size == 0 => b"Rust libdeflater; compression level 1; serial requests",
            3 => {
                compressed.resize(compressor.zlib_compress_bound(size), 0);
                let length = compressor.zlib_compress(&source, &mut compressed)?;
                &compressed[..length]
            }
            4 if size == 0 => &[],
            _ => return Err("unsupported compression benchmark command".into()),
        };
        for value in [MAGIC, response.len() as u32, word(8), opcode, 0] {
            output.write_all(&value.to_be_bytes())?;
        }
        output.write_all(response)?;
        output.flush()?;
        if opcode == 4 {
            return Ok(());
        }
    }
}
