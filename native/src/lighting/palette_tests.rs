use super::*;
use palettes::Section;

fn expected(section: &[u16]) -> (Vec<u16>, Vec<u8>) {
    let mut ids = Vec::new();
    let mut map = std::collections::HashMap::new();
    let indices: Vec<u32> = section
        .iter()
        .map(|&id| {
            *map.entry(id).or_insert_with(|| {
                let index = ids.len() as u32;
                ids.push(id);
                index
            })
        })
        .collect();
    let bits = (usize::BITS - (ids.len() - 1).leading_zeros()).max(4) as usize;
    let data = if ids.len() == 1 {
        Vec::new()
    } else {
        indices
            .chunks(64 / bits)
            .flat_map(|group| {
                group
                    .iter()
                    .enumerate()
                    .fold(0u64, |word, (i, &v)| word | (u64::from(v) << (i * bits)))
                    .to_be_bytes()
            })
            .collect()
    };
    (ids, data)
}

#[test]
#[ignore = "requires GPU; bounded exact section palette and fallback validation"]
fn gpu_section_palettes_keep_order_bits_overflow_and_light() {
    let engine = crate::TerrainEngine::new().unwrap();
    let mut gpu = engine.light_gpu.lock().unwrap();
    let mut flights = Vec::new();
    for (case, (side, height, crop, core)) in [
        (2usize, 64usize, 0usize, 2usize),
        (4, 32, 1, 2),
        (3, 80, 0, 3),
    ]
    .into_iter()
    .enumerate()
    {
        let mut profile = tests::fixture_profile(case != 1);
        profile.states.resize(65536, [0; 8]);
        for (i, state) in profile.states.iter_mut().enumerate() {
            state[0] = if i % 5 == 0 { 0 } else { 15 };
        }
        let counts = [1, 2, 3, 8, 16, 17, 31, 32, 33, 64, 257, 4096, 1, 32, 2, 17];
        let blocks: Vec<u16> = (0..side * side * height * 256)
            .map(|i| {
                let section = i / 4096;
                let count = counts[section % counts.len()];
                let index = ((i % 4096) * 2053) % count;
                (65535
                    - index
                        * if section % 3 == 0 && count <= 32 {
                            128
                        } else {
                            13
                        }) as u16
            })
            .collect();
        let metadata: Vec<_> = blocks
            .chunks_exact(height * 256)
            .map(transport::classify)
            .collect();
        let core_indices: Vec<_> = (0..core)
            .flat_map(|z| (0..core).map(move |x| (crop + z) * side + crop + x))
            .collect();
        let expected_sections: Vec<_> = core_indices
            .iter()
            .flat_map(|&i| {
                blocks[i * height * 256..(i + 1) * height * 256]
                    .chunks_exact(4096)
                    .map(expected)
            })
            .collect();
        gpu.compact_input = false;
        let handle = 700 + case as u32;
        let expected_light = gpu
            .run(
                handle,
                &profile,
                side as u32,
                height as u32,
                crop as u32,
                core as u32,
                &blocks,
                OutputFormat::Sections,
            )
            .unwrap()
            .bytes;
        for compact in [false, true] {
            let mut packed = Vec::new();
            let payload = if compact {
                transport::pack(
                    &mut packed,
                    blocks
                        .chunks_exact(height * 256)
                        .zip(metadata.iter().map(Vec::as_slice)),
                    height as u32,
                );
                bytemuck::cast_slice(&packed)
            } else {
                bytemuck::cast_slice(&blocks)
            };
            let light_layout = compact.then(|| {
                output::Constants::new(&profile, &metadata, side).tile(core_indices.iter().copied())
            });
            // Dense case also sends uniform sections through the shader, to test
            // the count=1 output, not only CPU-proved uniform descriptors.
            let mixed = vec![vec![0; height / 16]; side * side];
            let palette_layout =
                palettes::Layout::new(if compact { &metadata } else { &mixed }, side, crop, core);
            let flight = gpu
                .submit_payload(
                    handle,
                    &profile,
                    side as u32,
                    height as u32,
                    crop as u32,
                    core as u32,
                    payload,
                    compact,
                    OutputFormat::Sections,
                    light_layout,
                    Some(palette_layout),
                )
                .unwrap();
            flights.push((
                flight,
                expected_light.clone(),
                expected_sections.clone(),
                height,
                core * core,
            ));
        }
        if flights.len() >= 4 || case == 2 {
            for (flight, light, sections, height, chunks) in flights.drain(..).rev() {
                let result = gpu.complete(flight).unwrap();
                let tile = Tile {
                    slots: Vec::new(),
                    bytes: result.bytes,
                    chunk_bytes: (height + 32) * 256,
                    layout: result.layout,
                    palettes: result.palettes,
                };
                let actual_light: Vec<_> = (0..chunks)
                    .flat_map(|i| tile.chunk(i).expanded(height / 16 + 2))
                    .collect();
                assert_eq!(actual_light, light, "lighting must be unchanged");
                for (index, (ids, data)) in sections.iter().enumerate() {
                    let result = tile
                        .chunk(index / (height / 16))
                        .palettes
                        .unwrap()
                        .section(index % (height / 16));
                    if ids.len() > palettes::CAPACITY {
                        assert!(result.is_none(), "large palette must fall back");
                        continue;
                    }
                    match result.expect("representable palette unexpectedly fell back") {
                        Section::Uniform(id) => assert_eq!(ids, &[id]),
                        Section::Packed {
                            palette,
                            data: actual,
                        } => {
                            let palette: Vec<_> = palette
                                .chunks_exact(4)
                                .map(|v| u32::from_le_bytes(v.try_into().unwrap()) as u16)
                                .collect();
                            assert_eq!(&palette, ids, "first-occurrence order");
                            assert_eq!(actual, data, "Minecraft long packing");
                        }
                    }
                }
            }
        }
    }
    assert!(flights.is_empty());
}
