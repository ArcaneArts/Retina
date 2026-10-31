//! Queue-based native lighting with the same loaded attenuation and face rules
//! as lighting.wgsl. No device resources, shader passes or dense ping-pong grids.
use super::{OutputFormat, Profile, ResultLight, Tile};
use crate::material_blocks::Read;
use std::{
    collections::VecDeque,
    time::{Duration, Instant},
};

#[inline]
fn occludes(profile: &Profile, from: u16, to: u16, direction: usize) -> bool {
    let a = profile.states[from as usize][1 + direction] as usize;
    let b = profile.states[to as usize][1 + (direction ^ 1)] as usize;
    let stride = profile.faces.div_ceil(32) as usize;
    profile.blocked[a * stride + b / 32] & (1 << (b % 32)) != 0
}
fn neighbors(index: usize, side: usize, height: usize, mut visit: impl FnMut(usize, usize)) {
    let plane = side * side;
    let x = index % side;
    let z = index / side % side;
    let y = index / plane;
    if y > 0 {
        visit(index - plane, 0);
    }
    if y + 1 < height {
        visit(index + plane, 1);
    }
    if z > 0 {
        visit(index - side, 2);
    }
    if z + 1 < side {
        visit(index + side, 3);
    }
    if x > 0 {
        visit(index - 1, 4);
    }
    if x + 1 < side {
        visit(index + 1, 5);
    }
}
fn spread(
    index: usize,
    side: usize,
    height: usize,
    profile: &Profile,
    materials: &[u16],
    light: &mut [u8],
    queued: &mut [bool],
    queue: &mut VecDeque<usize>,
) {
    let value = light[index];
    if value & 15 <= 1 && value >> 4 <= 1 {
        return;
    }
    neighbors(index, side, height, |next, direction| {
        let material = materials[next];
        let opacity = (profile.states[material as usize][0] as u8 & 15).max(1);
        if opacity >= 15 {
            return;
        }
        let old = light[next];
        let block = (value & 15).saturating_sub(opacity);
        let sky = (value >> 4).saturating_sub(opacity);
        if block <= old & 15 && sky <= old >> 4 {
            return;
        }
        if occludes(profile, materials[index], material, direction) {
            return;
        }
        light[next] = (old & 15).max(block) | ((old >> 4).max(sky) << 4);
        if !queued[next] {
            queued[next] = true;
            queue.push_back(next);
        }
    });
}

pub(crate) fn run(
    profile: &Profile,
    chunks: usize,
    height: usize,
    crop: usize,
    core: usize,
    format: OutputFormat,
    read: impl Fn(usize) -> u16,
) -> Result<ResultLight, String> {
    if chunks == 0 || height == 0 || core == 0 || crop.checked_add(core).is_none_or(|v| v > chunks)
    {
        return Err("invalid CPU lighting volume".into());
    }
    if matches!(format, OutputFormat::Sections) && height % 16 != 0 {
        return Err("section lighting requires a complete section height".into());
    }
    profile.validate(profile.states.len())?;
    let side = chunks.checked_mul(16).ok_or("CPU light side overflow")?;
    let padded = height.checked_add(32).ok_or("CPU light height overflow")?;
    let plane = side.checked_mul(side).ok_or("CPU light plane overflow")?;
    let count = plane
        .checked_mul(padded)
        .ok_or("CPU light volume overflow")?;
    let mut materials = vec![0u16; count];
    for cz in 0..chunks {
        for cx in 0..chunks {
            for y in 0..height {
                for z in 0..16 {
                    for x in 0..16 {
                        let material = read(((cz * chunks + cx) * height + y) * 256 + z * 16 + x);
                        if material as usize >= profile.states.len() {
                            return Err(format!(
                                "CPU lighting material {material} is outside the loaded palette"
                            ));
                        }
                        materials[(y + 16) * plane + (cz * 16 + z) * side + cx * 16 + x] = material;
                    }
                }
            }
        }
    }
    let mut light = vec![0u8; count];
    for z in 0..side {
        for x in 0..side {
            let mut sun = profile.sky;
            let mut above = 0;
            for y in (0..padded).rev() {
                let index = y * plane + z * side + x;
                let material = materials[index];
                let traits = profile.states[material as usize][0];
                sun = sun && traits & 15 == 0 && !occludes(profile, above, material, 0);
                light[index] = ((traits >> 4) & 15) as u8 | if sun { 240 } else { 0 };
                above = material;
            }
        }
    }
    let mut queued = vec![false; count];
    let mut queue = VecDeque::new();
    // Only transitions that can raise another voxel enter the queue. Open sky
    // never creates a frontier for every air voxel at every propagation step.
    for index in 0..count {
        spread(
            index,
            side,
            padded,
            profile,
            &materials,
            &mut light,
            &mut queued,
            &mut queue,
        );
    }
    while let Some(index) = queue.pop_front() {
        queued[index] = false;
        spread(
            index,
            side,
            padded,
            profile,
            &materials,
            &mut light,
            &mut queued,
            &mut queue,
        );
    }
    let chunk_bytes = padded * 256;
    let mut bytes = vec![0u8; core * core * chunk_bytes];
    for cz in 0..core {
        for cx in 0..core {
            for y in 0..padded {
                for z in 0..16 {
                    for x in 0..16 {
                        let value =
                            light[y * plane + ((crop + cz) * 16 + z) * side + (crop + cx) * 16 + x];
                        let chunk = cz * core + cx;
                        let voxel = y * 256 + z * 16 + x;
                        match format {
                            OutputFormat::Voxels => bytes[chunk * chunk_bytes + voxel] = value,
                            OutputFormat::Sections => {
                                let section = y / 16;
                                let local = (y % 16) * 256 + z * 16 + x;
                                let shift = (local % 2) * 4;
                                let at = chunk * chunk_bytes + section * 4096 + local / 2;
                                bytes[at] |= (value & 15) << shift;
                                bytes[at + 2048] |= (value >> 4) << shift;
                            }
                        }
                    }
                }
            }
        }
    }
    Ok(ResultLight {
        bytes,
        device_nanos: None,
        upload: 0,
        layout: None,
        palettes: None,
    })
}

pub(super) fn region<B: Read>(
    engine: &crate::TerrainEngine,
    request: crate::ChunkRequest,
    profile: &Profile,
    chunks: &[B],
    job: &crate::timings::Timings,
    mut consume: impl FnMut(Tile) -> Result<(), String>,
) -> Result<(), String> {
    if chunks.len() != 1024
        || chunks
            .iter()
            .any(|chunk| chunk.len() != request.block_count())
    {
        return Err("CPU region lighting requires the final 32x32 chunk region".into());
    }
    let start = Instant::now();
    let mut consuming = Duration::ZERO;
    let descriptors: Vec<_> = chunks.iter().map(super::transport::classify).collect();
    let constants = super::output::Constants::new(profile, &descriptors, 32);
    let core = 5usize; // Divides the inner 30x30 chunks, with one chunk of halo.
    let chunk_bytes = (request.height as usize + 32) * 256;
    for cz in (1..31).step_by(core) {
        for cx in (1..31).step_by(core) {
            let slots: Vec<_> = (0..core)
                .flat_map(|z| (0..core).map(move |x| (cz + z) * 32 + cx + x))
                .collect();
            let layout = constants.tile(slots.iter().copied());
            let tile = if layout.source_channels.is_empty() {
                Tile {
                    slots,
                    bytes: Vec::new(),
                    chunk_bytes,
                    layout: Some(layout),
                    palettes: None,
                }
            } else {
                let side = core + 2;
                let blocks = request.block_count();
                let result = run(
                    profile,
                    side,
                    request.height as usize,
                    1,
                    core,
                    OutputFormat::Sections,
                    |index| {
                        let chunk = index / blocks;
                        let z = cz - 1 + chunk / side;
                        let x = cx - 1 + chunk % side;
                        chunks[z * 32 + x].value(index % blocks)
                    },
                )?;
                Tile {
                    slots,
                    bytes: result.bytes,
                    chunk_bytes,
                    layout: None,
                    palettes: None,
                }
            };
            let began = Instant::now();
            consume(tile)?;
            consuming += began.elapsed();
        }
    }
    let elapsed = start.elapsed().saturating_sub(consuming).as_nanos() as u64;
    engine
        .timings(request.reserved)
        .add(crate::timings::LIGHT_HOST, elapsed);
    job.add(crate::timings::LIGHT_HOST, elapsed);
    Ok(())
}

#[cfg(test)]
mod tests {
    use super::*;
    fn profile(sky: bool) -> Profile {
        Profile {
            sky,
            faces: 1,
            states: vec![
                [0; 8],
                [15, 0, 0, 0, 0, 0, 0, 0],
                [240, 0, 0, 0, 0, 0, 0, 0],
                [1, 0, 0, 0, 0, 0, 0, 0],
            ],
            blocked: vec![0],
        }
    }
    #[test]
    fn emissions_attenuate_and_opaque_faces_stop_sun_without_gpu_resources() {
        let p = profile(true);
        let mut blocks = vec![0; 16 * 256];
        blocks[8 * 256 + 8 * 16 + 8] = 2;
        blocks[12 * 256 + 4 * 16 + 4] = 1;
        let result = run(&p, 1, 16, 0, 1, OutputFormat::Voxels, |i| blocks[i]).unwrap();
        assert_eq!(result.device_nanos, None);
        assert_eq!(result.upload, 0);
        let at = |x: usize, y: usize, z: usize| result.bytes[y * 256 + z * 16 + x];
        assert_eq!(at(8, 24, 8) & 15, 15);
        assert_eq!(at(9, 24, 8) & 15, 14);
        assert_eq!(at(4, 28, 4) >> 4, 0);
        assert_eq!(at(4, 27, 4) >> 4, 14);
        assert_eq!(at(0, 47, 0) >> 4, 15);
        assert!(run(&p, 1, 16, 0, 1, OutputFormat::Voxels, |_| 99).is_err());
    }
    #[test]
    fn empty_region_uses_shared_constant_proofs_and_never_initializes_graphics() {
        let engine = crate::TerrainEngine::from_compute(None, Default::default());
        let request = crate::ChunkRequest {
            seed: 1,
            chunk_x: 0,
            chunk_z: 0,
            min_y: 0,
            height: 16,
            base_height: 8.0,
            amplitude: 0.0,
            frequency: 0.01,
            reserved: 0,
        };
        let chunks: Vec<_> = (0..1024)
            .map(|_| crate::material_blocks::Chunk::uniform(16, 0))
            .collect();
        let mut seen = vec![false; 1024];
        super::region(
            &engine,
            request,
            &profile(true),
            &chunks,
            &Default::default(),
            |tile| {
                assert!(
                    tile.bytes.is_empty(),
                    "empty tiles need no dense light arrays"
                );
                for (index, &slot) in tile.slots.iter().enumerate() {
                    assert!(!seen[slot]);
                    seen[slot] = true;
                    for section in 0..3 {
                        let [block, sky] = tile.chunk(index).section(section);
                        assert!(matches!(block, crate::lighting::Channel::Constant(0)));
                        assert!(matches!(sky, crate::lighting::Channel::Constant(15)));
                    }
                }
                Ok(())
            },
        )
        .unwrap();
        assert_eq!(seen.iter().filter(|&&v| v).count(), 900);
        assert!(engine.compute.is_none());
    }
    #[test]
    fn sections_and_voxel_formats_encode_the_same_channels() {
        let p = profile(false);
        let mut blocks = vec![0; 16 * 256];
        blocks[8 * 256 + 8 * 16 + 8] = 2;
        let voxels = run(&p, 1, 16, 0, 1, OutputFormat::Voxels, |i| blocks[i])
            .unwrap()
            .bytes;
        let sections = run(&p, 1, 16, 0, 1, OutputFormat::Sections, |i| blocks[i])
            .unwrap()
            .bytes;
        for (i, &voxel) in voxels.iter().enumerate() {
            let section = i / 4096;
            let local = i % 4096;
            let shift = local % 2 * 4;
            assert_eq!(
                (sections[section * 4096 + local / 2] >> shift) & 15,
                voxel & 15
            );
            assert_eq!(
                (sections[section * 4096 + 2048 + local / 2] >> shift) & 15,
                voxel >> 4
            );
        }
    }
}

#[cfg(test)]
mod hardware_tests {
    use super::*;
    #[test]
    #[ignore = "bounded exact CPU/GPU light comparison; requires a real GPU"]
    fn cpu_queue_matches_gpu_integer_light_and_registered_faces() {
        let engine = crate::TerrainEngine::new().unwrap();
        let mut gpu = engine.gpu().unwrap().light_gpu.lock().unwrap();
        for (id, sky, faces) in [(901, false, false), (902, true, false), (903, true, true)] {
            let mut profile = crate::lighting::tests::fixture_profile(sky);
            if faces {
                profile.faces = 2;
                profile.blocked = vec![0, 2];
                profile.states[3] = [0, 1, 1, 1, 1, 1, 1, 0];
            }
            let chunks = 3;
            let height = 32;
            let mut blocks = vec![0u16; chunks * chunks * height * 256];
            for (i, m) in blocks.iter_mut().enumerate() {
                let y = i / 256 % height;
                *m = if y < 8 && (i * 17) % 19 > 2 {
                    1
                } else if (i * 13) % 977 == 0 {
                    2
                } else if (y == 15 || y == 16) && (i % 7) < 3 {
                    3
                } else {
                    0
                };
            }
            for format in [OutputFormat::Voxels, OutputFormat::Sections] {
                let expected = gpu
                    .run(
                        id,
                        &profile,
                        chunks as u32,
                        height as u32,
                        1,
                        1,
                        &blocks,
                        format,
                    )
                    .unwrap();
                let actual = run(&profile, chunks, height, 1, 1, format, |i| blocks[i]).unwrap();
                assert_eq!(
                    actual.bytes, expected.bytes,
                    "sky={sky} partial_faces={faces}"
                );
                assert_eq!(actual.device_nanos, None);
                assert_eq!(actual.upload, 0);
            }
        }
        eprintln!("CPU_LIGHT_PASS cases=6 integer_channels=exact crop=one_chunk halo=one_chunk");
    }
}
