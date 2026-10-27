//! Actual-GPU evidence for cross-geometry terrain reuse. This observer does not
//! change cache admission or assume that matching coordinates imply equal data.
use crate::geology::CaveMask;
use crate::{COLUMNS, ChunkRequest, Column, TerrainEngine};
use serde::Serialize;
use std::collections::BTreeMap;

struct Tile {
    request: ChunkRequest,
    side: i32,
    origin: [i32; 2],
    width: i32,
    columns: Vec<Column>,
    mask: CaveMask,
}
impl Tile {
    fn generate(engine: &TerrainEngine, request: ChunkRequest, side: i32, ring: i32) -> Self {
        let origin = [request.chunk_x - ring, request.chunk_z - ring];
        let width = side + 2 * ring;
        let sample = engine
            .dispatch_full(
                vec![ChunkRequest {
                    chunk_x: origin[0],
                    chunk_z: origin[1],
                    ..request
                }],
                width as u32,
                (width - 2) as u32,
            )
            .unwrap();
        assert_eq!(
            sample.columns.len(),
            width as usize * width as usize * COLUMNS
        );
        let mask = sample.mask.unwrap();
        assert_eq!(mask.origin_x, origin[0] * 16 + 15);
        assert_eq!(mask.origin_z, origin[1] * 16 + 15);
        assert_eq!(mask.width, ((width - 2) * 16 + 2) as usize);
        Self {
            request,
            side,
            origin,
            width,
            columns: sample.columns,
            mask,
        }
    }
    fn column(&self, x: i32, z: i32) -> Column {
        let cx = x.div_euclid(16) - self.origin[0];
        let cz = z.div_euclid(16) - self.origin[1];
        self.columns[(cz * self.width + cx) as usize * COLUMNS
            + z.rem_euclid(16) as usize * 16
            + x.rem_euclid(16) as usize]
    }
    fn depth(&self, x: i32, z: i32) -> i32 {
        let dx = x.div_euclid(16) - self.origin[0];
        let dz = z.div_euclid(16) - self.origin[1];
        dx.min(dz).min(self.width - 1 - dx).min(self.width - 1 - dz)
    }
    fn target(&self, x: i32, z: i32) -> bool {
        let dx = x.div_euclid(16) - self.request.chunk_x;
        let dz = z.div_euclid(16) - self.request.chunk_z;
        (0..self.side).contains(&dx) && (0..self.side).contains(&dz)
    }
}

#[derive(Default, Serialize)]
struct Count {
    compared: u64,
    unequal: u64,
    first_unequal: Vec<[i32; 3]>,
}
impl Count {
    fn add(&mut self, equal: bool, at: [i32; 3]) {
        self.compared += 1;
        if !equal {
            self.unequal += 1;
            if self.first_unequal.len() < 8 {
                self.first_unequal.push(at);
            }
        }
    }
}
type Counts = BTreeMap<String, Count>;
fn add(counts: &mut Counts, kind: &str, depth: i32, core: bool, at: [i32; 3], equal: bool) {
    let zone = if core {
        "both_targets"
    } else {
        "guard_in_at_least_one"
    };
    counts
        .entry(format!("{kind}/{zone}/raw_edge_depth_{}", depth.min(3)))
        .or_default()
        .add(equal, at);
}

fn compare(a: &Tile, b: &Tile, heights: &[u8]) -> Counts {
    let mut counts = Counts::new();
    let x0 = a.origin[0].max(b.origin[0]) * 16;
    let z0 = a.origin[1].max(b.origin[1]) * 16;
    let x1 = (a.origin[0] + a.width).min(b.origin[0] + b.width) * 16;
    let z1 = (a.origin[1] + a.width).min(b.origin[1] + b.width) * 16;
    assert!(x0 < x1 && z0 < z1);
    assert_eq!(a.request.min_y, b.request.min_y);
    assert_eq!(a.request.height, b.request.height);
    assert_eq!(a.mask.air_only, b.mask.air_only);
    for z in z0..z1 {
        for x in x0..x1 {
            let depth = a.depth(x, z).min(b.depth(x, z));
            let core = a.target(x, z) && b.target(x, z);
            let at = [x, a.request.min_y, z];
            add(
                &mut counts,
                "columns",
                depth,
                core,
                at,
                a.column(x, z) == b.column(x, z),
            );
            add(
                &mut counts,
                "surface_carved",
                depth,
                core,
                at,
                a.mask.surface_carved(x, z) == b.mask.surface_carved(x, z),
            );
            // Compare the complete RLE stream: this covers every stored Y, not
            // just selected voxel samples. Missing coverage is never equality.
            if let (Some(ar), Some(br)) = (a.mask.material_runs(x, z), b.mask.material_runs(x, z)) {
                add(&mut counts, "material_runs", depth, core, at, ar == br);
                for map in 0..6 {
                    let ah = a.mask.height_at(x, z, heights, map).unwrap();
                    let bh = b.mask.height_at(x, z, heights, map).unwrap();
                    add(
                        &mut counts,
                        "heightmaps",
                        depth,
                        core,
                        [x, map as i32, z],
                        ah == bh,
                    );
                }
            }
            // All source quart cells, including raw guard columns. Tile origins
            // are chunk aligned, so these samples enumerate each packed value.
            if x.rem_euclid(4) == 0 && z.rem_euclid(4) == 0 {
                let y0 = a.request.min_y.div_euclid(4) * 4;
                for y in (y0..a.request.min_y + a.request.height as i32).step_by(4) {
                    let ab = a.mask.biome(x, y, z).unwrap();
                    let bb = b.mask.biome(x, y, z).unwrap();
                    add(
                        &mut counts,
                        "quart_biomes",
                        depth,
                        core,
                        [x, y, z],
                        ab == bb,
                    );
                }
            }
            // A 16-bit row compares every cave bit without a per-voxel map
            // update. Include only rows fully covered by both masks.
            if x.rem_euclid(16) == 0
                && x >= a.mask.origin_x
                && x >= b.mask.origin_x
                && z >= a.mask.origin_z
                && z >= b.mask.origin_z
                && x + 16 <= a.mask.origin_x + a.mask.width as i32
                && x + 16 <= b.mask.origin_x + b.mask.width as i32
                && z < a.mask.origin_z + a.mask.width as i32
                && z < b.mask.origin_z + b.mask.width as i32
            {
                for y in a.request.min_y..a.request.min_y + a.request.height as i32 {
                    add(
                        &mut counts,
                        "cave_rows_16",
                        depth,
                        core,
                        [x, y, z],
                        a.mask.row_bits(x, y, z) == b.mask.row_bits(x, y, z),
                    );
                }
            }
        }
    }
    assert!(counts.values().any(|c| c.compared > 0));
    counts
}

#[test]
#[ignore = "requires GPU, RETINA_TERRAIN_REUSE_PROFILES and RETINA_TERRAIN_OVERLAP_REPORT"]
fn actual_cross_geometry_overlap_inventory() {
    let directory =
        std::path::PathBuf::from(std::env::var("RETINA_TERRAIN_REUSE_PROFILES").unwrap());
    let output = std::env::var("RETINA_TERRAIN_OVERLAP_REPORT").unwrap();
    let engine = TerrainEngine::new().unwrap();
    let mut cases = Vec::new();
    let mut unequal_total = 0;
    for name in ["nether", "custom", "direct-stem", "overworld"] {
        let bytes = std::fs::read(directory.join(format!("{name}.json"))).unwrap();
        let source: serde_json::Value = serde_json::from_slice(&bytes).unwrap();
        let id = engine.register_reference_profile(&bytes).unwrap();
        let profile = engine.profile(id).unwrap().unwrap();
        let material_halo = profile
            .registry_program
            .as_ref()
            .is_some_and(|p| p.material_halo);
        let ring = if material_halo && profile.decoration_patch_halo {
            3
        } else if material_halo {
            2
        } else {
            1
        };
        for (seed, x, z) in [(123456789, -32, 16), (42, 16, -32)] {
            let bounds = &source["generation_bounds"];
            let r = ChunkRequest {
                seed,
                chunk_x: x,
                chunk_z: z,
                reserved: id,
                min_y: bounds["storage_min_y"].as_i64().unwrap_or(-64) as i32,
                height: bounds["storage_height"].as_u64().unwrap_or(384) as u32,
                base_height: 64.,
                amplitude: 48.,
                frequency: 0.008,
            };
            // Explicit origins cover nested, horizontal, vertical, diagonal,
            // unaligned and single-chunk producers. No cache can satisfy these
            // direct GPU dispatches, so both sides are independently produced.
            for (label, side, other_side, dx, dz) in [
                ("nested_32_16", 32, 16, 16, 0),
                ("horizontal_16", 16, 16, 16, 0),
                ("vertical_16", 16, 16, 0, 16),
                ("diagonal_16", 16, 16, 16, 16),
                ("probe_16_1", 16, 1, 16, 16),
                ("unaligned_7_2", 7, 2, 5, 1),
            ] {
                let a = Tile::generate(&engine, r, side, ring);
                let other = ChunkRequest {
                    chunk_x: x + dx,
                    chunk_z: z + dz,
                    ..r
                };
                let b = Tile::generate(&engine, other, other_side, ring);
                let counts = compare(&a, &b, &profile.heightmap_masks);
                let unequal: u64 = counts.values().map(|c| c.unequal).sum();
                unequal_total += unequal;
                println!("{name} seed={seed} {label}: unequal observations={unequal}");
                cases.push(
                    serde_json::json!({"profile":name,"seed":seed,"geometry":label,
                    "request_origin":[x,z],"other_origin":[x+dx,z+dz],
                    "sides":[side,other_side],"ring":ring,"counts":counts}),
                );
            }
        }
    }
    std::fs::write(output,serde_json::to_vec_pretty(&serde_json::json!({
        "schema":1,"cases":cases,
        "limitations":"Actual raw GPU overlap inventory, not a proof for all seeds/profiles. Counts overlap across data types and are not elapsed time. Material runs require common coverage; cave rows exclude unaligned one-block mask fringes. No production reuse is enabled."
    })).unwrap()).unwrap();
    assert_eq!(
        unequal_total, 0,
        "cross-geometry GPU output differs; inspect the saved inventory"
    );
}
