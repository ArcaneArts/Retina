//! Native End columns and the existing material-run substrate. Dimension
//! attributes remain in the world profile; this descriptor selects terrain only.
use super::{Biome, IslandNoise, VanillaDensity};
use crate::{ChunkRequest, COLUMNS, CacheKey, TerrainEngine, decoration::Field,
    geology::{CaveMask, ColumnHalo}, profile::Column};
use serde::Deserialize;
use std::sync::Arc;

#[derive(Clone, Deserialize)]
#[serde(deny_unknown_fields)]
pub struct Profile {
    pub material: u16,
    /// Center, highlands, midlands, small islands, barrens. A fixed source maps
    /// every entry to the same loaded biome rather than borrowing climate rules.
    pub biomes: [u16; 5],
}
impl Profile {
    pub fn validate(&self, materials: usize, biomes: usize) -> Result<(), String> {
        if self.material == 0 || self.material as usize >= materials
            || self.biomes.iter().any(|&b| b as usize >= biomes) {
            return Err("invalid native End material or biome mapping".into());
        }
        Ok(())
    }
    pub fn biome(&self, islands: &IslandNoise, x: i32, z: i32) -> u16 {
        self.biomes[match islands.biome(x, z) {
            Biome::Center => 0, Biome::Highlands => 1, Biome::Midlands => 2,
            Biome::SmallIslands => 3, Biome::Barrens => 4,
        }]
    }
    fn column(&self, r: ChunkRequest, biome: u16, solid: &[bool; 256]) -> Column {
        let top = (0..r.height as usize).rev().find(|&y| {
            let world_y = i64::from(r.min_y) + y as i64;
            (0..256).contains(&world_y) && solid[world_y as usize]
        });
        Column { height: r.min_y + top.map_or(0, |y| y + 1) as i32,
            packed: u32::from(biome), materials: u32::from(self.material) * 0x10001 }
    }
    pub(crate) fn query(&self, r: ChunkRequest, x: i32, z: i32, height: bool,
        islands: &IslandNoise, density: Option<&VanillaDensity>) -> Column {
        let biome = self.biome(islands, x, z);
        if !height { return Column { packed: u32::from(biome), ..Column::default() }; }
        self.column(r, biome, &density.expect("height query density").column(x, z))
    }
    pub(crate) fn columns(&self, requests: &[ChunkRequest], side: u32, biome_only: bool) -> Result<Vec<Column>, String> {
        let mut result = Vec::new();
        for &r in requests {
            let islands = IslandNoise::new(r.seed);
            if biome_only {
                let x = r.chunk_x.checked_mul(16).and_then(|v| v.checked_add(8)).ok_or("End probe X overflows")?;
                let z = r.chunk_z.checked_mul(16).and_then(|v| v.checked_add(8)).ok_or("End probe Z overflows")?;
                result.push(self.query(r, x, z, false, &islands, None));
                continue;
            }
            let side = side.max(1);
            let x = r.chunk_x * 16; let z = r.chunk_z * 16;
            let density = VanillaDensity::new(r.seed);
            let tile = density.tile(x, z, side * 16, side * 16)?;
            for cz in 0..side { for cx in 0..side {
                let bx = x + cx as i32 * 16; let bz = z + cz as i32 * 16;
                let biome = self.biome(&islands, bx, bz);
                for dz in 0..16 { for dx in 0..16 {
                    result.push(self.column(r, biome, &tile.column(bx + dx, bz + dz).unwrap()));
                }}
            }}
        }
        Ok(result)
    }

    /// The same six-chunk guard geometry used by ordered decoration's cached
    /// substrate. Material runs cover the inner tile, including feature anchors;
    /// the outer chunk supplies biome/column probes without truncating writes.
    fn build(&self, request: ChunkRequest, side: u32) -> Result<(Field<'static>, Arc<CaveMask>), String> {
        let tile_side = side as usize + 6;
        let origin = ChunkRequest {
            chunk_x: request.chunk_x.checked_sub(3).ok_or("End halo X overflows")?,
            chunk_z: request.chunk_z.checked_sub(3).ok_or("End halo Z overflows")?, ..request };
        for z in 0..tile_side { for x in 0..tile_side {
            ChunkRequest { chunk_x: origin.chunk_x.checked_add(x as i32).ok_or("End halo X overflows")?,
                chunk_z: origin.chunk_z.checked_add(z as i32).ok_or("End halo Z overflows")?, ..origin }.validate()?;
        }}
        let ox = origin.chunk_x * 16; let oz = origin.chunk_z * 16;
        let density = VanillaDensity::new(request.seed);
        let islands = IslandNoise::new(request.seed);
        let tile = density.tile(ox, oz, tile_side as u32 * 16, tile_side as u32 * 16)?;
        let run_width = (tile_side - 2) * 16;
        let width = run_width + 2;
        let surface = width + 30;
        let quart = surface / 4;
        let layers = ((request.min_y.rem_euclid(4) as usize + request.height as usize) + 3) / 4;
        let air_words = (width * width * request.height as usize).div_ceil(32);
        let biome_offset = air_words + (surface * surface).div_ceil(32);
        let base = biome_offset + (quart * quart * layers).div_ceil(2);
        let count = run_width * run_width; let groups = count.div_ceil(256);
        let mut words = vec![0u32; base + 4 + count * 2 + groups * 2];
        words[base..base+4].copy_from_slice(&[0x52554e53, run_width as u32, count as u32, 0]);
        let mut runs = Vec::with_capacity(count * 3);
        let mut columns = vec![Column::default(); tile_side * tile_side * COLUMNS];
        let biomes: Vec<_> = (0..tile_side * tile_side).map(|i|
            self.biome(&islands, ox + (i % tile_side) as i32 * 16, oz + (i / tile_side) as i32 * 16)).collect();
        // Material runs are row-major even though the compact column cache is
        // chunk-major. Keep both layouts explicit to avoid region-edge aliasing.
        for z in 0..tile_side * 16 { for x in 0..tile_side * 16 {
            let wx = ox + x as i32; let wz = oz + z as i32;
            let solid = tile.column(wx, wz).unwrap();
            let chunk = z / 16 * tile_side + x / 16;
            let biome = biomes[chunk];
            columns[chunk * COLUMNS + (z % 16) * 16 + x % 16] = self.column(request, biome, &solid);
            if x % 4 == 0 && z % 4 == 0 {
                for y in 0..layers {
                    let index = (y * quart + z / 4) * quart + x / 4;
                    words[biome_offset + index / 2] |= u32::from(biome) << ((index % 2) * 16);
                }
            }
            let material = |y: usize| {
                let wy = i64::from(request.min_y) + y as i64;
                if (0..256).contains(&wy) && solid[wy as usize] { self.material } else { 0 }
            };
            // Populate the air exposure plane including its single-block guards.
            if (15..tile_side*16-15).contains(&x) && (15..tile_side*16-15).contains(&z) {
                for y in 0..request.height as usize {
                    if material(y) == 0 {
                        let index = (y * width + z - 15) * width + x - 15;
                        words[index / 32] |= 1 << (index % 32);
                    }
                }
            }
            if !(16..tile_side*16-16).contains(&x) || !(16..tile_side*16-16).contains(&z) { continue; }
            let index = (z - 16) * run_width + x - 16;
            // Iterate columns in row order; the group prefixes used by the GPU
            // encoder can be zero because native offsets are already absolute.
            words[base + 5 + index * 2] = runs.len() as u32;
            let before = runs.len();
            let mut current = material(request.height as usize - 1);
            for y in (0..request.height as usize - 1).rev() {
                let next = material(y);
                if next != current { runs.push(((y as u32 + 1) << 16) | u32::from(current)); current = next; }
            }
            runs.push(u32::from(current));
            words[base + 4 + index * 2] = (runs.len() - before) as u32;
        }}
        words[base + 3] = runs.len() as u32;
        words.extend(runs);
        let columns = Arc::new(columns);
        let mask = Arc::new(CaveMask { origin_x: ox + 15, origin_z: oz + 15,
            min_y: request.min_y, height: request.height, width, air_only: true, words,
            columns: Some(ColumnHalo { origin_x: origin.chunk_x, origin_z: origin.chunk_z,
                side: tile_side, columns }) });
        mask.validate_material_runs()?;
        let field = crop(request, side, &mask).ok_or("End tile does not cover its target")?;
        Ok((field, mask))
    }
}

fn crop(request: ChunkRequest, side: u32, mask: &Arc<CaveMask>) -> Option<Field<'static>> {
    let halo = mask.columns.as_ref()?;
    let x = request.chunk_x.checked_sub(1)?.checked_sub(halo.origin_x)?;
    let z = request.chunk_z.checked_sub(1)?.checked_sub(halo.origin_z)?;
    let width = side as usize + 2;
    if x < 0 || z < 0 || x as usize + width > halo.side || z as usize + width > halo.side { return None; }
    let bx = (request.chunk_x - 1) * 16; let bz = (request.chunk_z - 1) * 16;
    mask.material_runs(bx, bz)?;
    mask.material_runs(bx + width as i32 * 16 - 1, bz + width as i32 * 16 - 1)?;
    let mut columns = Vec::with_capacity(width * width * COLUMNS);
    for row in z as usize..z as usize + width {
        let start = (row * halo.side + x as usize) * COLUMNS;
        columns.extend_from_slice(&halo.columns[start..start + width * COLUMNS]);
    }
    Some(Field { origin_x: request.chunk_x - 1, origin_z: request.chunk_z - 1,
        side: width, columns, substrate: Some(mask.clone()), prior: None })
}

impl TerrainEngine {
    pub(crate) fn end_field(&self, request: ChunkRequest, side: u32, profile: &Profile)
        -> Result<(Field<'static>, Option<Arc<CaveMask>>), String> {
        let metrics = self.timings(request.reserved);
        let started = metrics.performance.start();
        let cached = self.caves.lock().map_err(|_| "cave cache poisoned")?
            .entries.get(&CacheKey::from(request)).cloned();
        let (field, mask) = if let Some((mask, field)) = cached.and_then(|m| crop(request, side, &m).map(|f| (m, f))) {
            (field, mask)
        } else { profile.build(request, side)? };
        let halo = mask.columns.as_ref().unwrap();
        self.cache_terrain(request, side, ChunkRequest { chunk_x: halo.origin_x,
            chunk_z: halo.origin_z, ..request }, halo.side as u32, &halo.columns, &mask)?;
        metrics.performance.elapsed(crate::performance::Counter::end_terrain_nanos, started);
        Ok((field, Some(mask)))
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    fn request(x: i32, z: i32) -> ChunkRequest {
        ChunkRequest { seed: 123456789, chunk_x: x, chunk_z: z, min_y: 0, height: 256,
            base_height: 64.0, amplitude: 0.0, frequency: 1.0, reserved: 0 }
    }
    #[test]
    fn native_end_runs_columns_biomes_and_air_guards_match_vanilla_fixture() {
        let fixture: serde_json::Value = serde_json::from_str(include_str!("../../tests/fixtures/end-density-26.3.json")).unwrap();
        let profile = Profile { material: 1, biomes: [4, 1, 2, 3, 0] };
        profile.validate(2, 5).unwrap();
        let world = crate::profile::WorldProfile::parse(&serde_json::to_vec(&serde_json::json!({
            "biome_scale":128, "blend":0.55, "sea_level":0,
            "stone":1,"water":0,"bedrock":1,"deepslate":1,"snow":1,"ice":1,
            "materials":["minecraft:air","minecraft:end_stone"],
            "material_flags":[0,0],"heightmap_masks":[0,63],
            "end":{"material":1,"biomes":[4,1,2,3,0]},
            "biomes":(0..5).map(|i|serde_json::json!({"id":format!("test:biome{i}"),
                "climate":[0,0,0,0],"terrain":[0,0,0],"top":1,"filler":1,"underwater":1,"flags":0})).collect::<Vec<_>>(),
            "noises":(0..4).map(|_|serde_json::json!({"frequency":1,"amplitude":1,"modifiers":[1]})).collect::<Vec<_>>()
        })).unwrap()).unwrap();
        let group = &fixture["seeds"][0];
        // Two tiny substrates cover central floating terrain and an outer edge;
        // the independent fixture supplies actual vanilla occupied heights.
        for sample in group["columns"].as_array().unwrap().iter().take(2) {
            let x = sample["xz"][0].as_i64().unwrap() as i32;
            let z = sample["xz"][1].as_i64().unwrap() as i32;
            let r = request(x.div_euclid(16), z.div_euclid(16));
            let (field, mask) = profile.build(r, 1).unwrap();
            mask.validate_material_runs().unwrap();
            let islands = IslandNoise::new(r.seed);
            let expected_biome = profile.biome(&islands, x, z);
            let mut height = 0;
            for y in 0..256 {
                let solid = sample["solid_y"].as_array().unwrap().iter().any(|v| v.as_i64() == Some(y as i64));
                if solid { height = y + 1; }
                assert_eq!(mask.material_at(x, y, z), Some(u16::from(solid)), "material {x},{y},{z}");
                assert_eq!(mask.carved(x, y, z), !solid, "air plane {x},{y},{z}");
                assert_eq!(mask.biome(x, y, z), Some(expected_biome));
            }
            assert_eq!(field.column(x, z).unwrap().height, height);
            assert_eq!(mask.height_at(x, z, &[0, 63], 0), Some(height));
            let mut blocks = vec![99; r.block_count()];
            crate::decoration::assemble_carved(r, field.chunk(r.chunk_x, r.chunk_z), Some(&world), Some(&mask), &mut blocks);
            let index = z.rem_euclid(16) as usize * 16 + x.rem_euclid(16) as usize;
            for y in 0..256 {
                assert_eq!(blocks[y * COLUMNS + index], mask.material_at(x, y as i32, z).unwrap());
            }
            assert!(blocks[128 * COLUMNS..].iter().all(|&b| b == 0));
            let repeated = crop(r, 1, &mask).unwrap();
            assert_eq!(repeated.columns, field.columns);
            assert!(crop(request(r.chunk_x + 100, r.chunk_z), 1, &mask).is_none());
        }
    }
    #[test]
    fn end_columns_clip_to_storage_bounds_and_reject_invalid_references() {
        let profile = Profile { material: 1, biomes: [0; 5] };
        let mut r = request(0, 0);
        let density = VanillaDensity::new(r.seed);
        let islands = IslandNoise::new(r.seed);
        r.min_y = -64; r.height = 384;
        let column = profile.query(r, 0, 0, true, &islands, Some(&density));
        assert!(column.height > 0 && column.height < 128);
        r.min_y = 128; r.height = 128;
        assert_eq!(profile.query(r, 0, 0, true, &islands, Some(&density)).height, 128);
        assert!(profile.validate(1, 1).is_err());
        assert!(Profile { material: 0, biomes: [0; 5] }.validate(2, 1).is_err());
        assert!(Profile { material: 1, biomes: [1; 5] }.validate(2, 1).is_err());
    }
}
