//! Keep terrain evaluation in its saved range and encode full dimension storage.
use crate::{ChunkRequest, profile::WorldProfile};

pub(super) fn validate_storage(request: ChunkRequest) -> Result<(), String> {
    request.validate()?;
    if request.min_y % 16 != 0
        || !request.height.is_multiple_of(16)
        || request.min_y / 16 < -128
        || (request.min_y + request.height as i32 - 1) / 16 > 127
    {
        return Err("MCA sections require aligned heights with section Y in -128..127".into());
    }
    Ok(())
}

pub(super) fn storage_request(
    request: ChunkRequest,
    bounds: Option<crate::world_definition::wire::Bounds>,
) -> ChunkRequest {
    match bounds {
        Some(bounds) => ChunkRequest {
            min_y: bounds.min_y,
            height: bounds.height as u32,
            ..request
        },
        None => request,
    }
}

pub(super) fn fit_blocks(
    generation: ChunkRequest,
    storage: ChunkRequest,
    profile: Option<&WorldProfile>,
    blocks: &mut Vec<u16>,
) -> Result<(), String> {
    if generation.min_y == storage.min_y && generation.height == storage.height {
        return Ok(());
    }
    let air = storage_air(profile)?;
    let mut output = vec![air; storage.block_count()];
    let min = generation.min_y.max(storage.min_y);
    let max =
        (generation.min_y + generation.height as i32).min(storage.min_y + storage.height as i32);
    if min < max {
        let input_start = (min - generation.min_y) as usize * crate::COLUMNS;
        let output_start = (min - storage.min_y) as usize * crate::COLUMNS;
        let count = (max - min) as usize * crate::COLUMNS;
        output[output_start..output_start + count]
            .copy_from_slice(&blocks[input_start..input_start + count]);
    }
    *blocks = output;
    Ok(())
}

pub(super) fn storage_air(profile: Option<&WorldProfile>) -> Result<u16, String> {
    Ok(if let Some(profile) = profile {
        profile
            .materials
            .iter()
            .position(|state| {
                state
                    .as_str()
                    .or_else(|| state.get("id").and_then(serde_json::Value::as_str))
                    == Some("minecraft:air")
            })
            .ok_or("storage padding requires an air block state in the saved palette")?
            as u16
    } else {
        0
    })
}

#[cfg(test)]
mod tests {
    use super::*;
    #[test]
    fn storage_padding_and_clipping_preserve_world_y() {
        let generation = ChunkRequest {
            seed: 0,
            chunk_x: 0,
            chunk_z: 0,
            min_y: 0,
            height: 64,
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
        let mut blocks = vec![1; generation.block_count()];
        fit_blocks(generation, storage, None, &mut blocks).unwrap();
        for y in 0..384 {
            assert!(
                blocks[y * 256..(y + 1) * 256]
                    .iter()
                    .all(|&v| v == u16::from((64..128).contains(&y)))
            );
        }
        let clipped = ChunkRequest {
            min_y: 32,
            height: 64,
            ..generation
        };
        let mut blocks = vec![1; generation.block_count()];
        fit_blocks(generation, clipped, None, &mut blocks).unwrap();
        assert!(blocks[..32 * 256].iter().all(|&v| v == 1));
        assert!(blocks[32 * 256..].iter().all(|&v| v == 0));
    }
}
