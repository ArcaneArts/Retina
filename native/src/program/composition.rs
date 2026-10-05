//! Eligibility for kernels whose point-density interpolation corners are resident.
//! Missing coverage retains the ordinary GPU sampler; it never rejects a job.
use super::RegistryProgram;
use crate::GpuRequest;

pub(crate) struct CachedDensity {
    fields: Vec<(usize, [u32; 2])>,
}
impl CachedDensity {
    pub(super) fn fields(&self) -> &[(usize, [u32; 2])] {
        &self.fields
    }
    pub fn new(registry: &RegistryProgram) -> Option<Self> {
        Self::for_programs(registry, &[1])
    }
    pub fn for_nodes(registry: &RegistryProgram) -> Option<Self> {
        Self::for_programs(registry, &[0, 1, 2])
    }
    fn for_programs(registry: &RegistryProgram, programs: &[usize]) -> Option<Self> {
        let mut fields = std::collections::BTreeSet::new();
        for &pid in programs {
            let program = registry.programs.get(pid)?;
            let mut used = vec![false; program.nodes.len()];
            // The emitted evaluator returns six slots. Include every root and
            // its implicit node-zero slots even when a caller consumes one.
            let mut pending = program.roots.clone();
            pending.push(0);
            while let Some(id) = pending.pop() {
                if std::mem::replace(&mut used[id as usize], true) {
                    continue;
                }
                let n = &program.nodes[id as usize];
                if n.op == 28 {
                    // Mask point calls use their original XYZ coordinates. A slice,
                    // warp or other remapping retains the general fallback sampler.
                    for (axis, operand) in [n.a, n.b, n.c].into_iter().enumerate() {
                        let coord = &program.nodes[operand as usize];
                        if coord.op != 29 || coord.a != axis as u32 {
                            return None;
                        }
                    }
                    let field = n.p[0] as usize;
                    let cell = registry.interpolations[field].cell;
                    // Binary scales preserve exact corner arithmetic in the bounded
                    // f32 coordinate domain checked below. Other cells remain valid
                    // with the existing direct GPU path.
                    if !cell.into_iter().all(u32::is_power_of_two) {
                        return None;
                    }
                    fields.insert((field, cell));
                }
                pending.extend(n.dependencies(&registry.points));
            }
        }
        (!fields.is_empty()).then(|| Self {
            fields: fields.into_iter().collect(),
        })
    }

    pub fn covers(&self, r: &GpuRequest, header: &[u32]) -> bool {
        self.covers_frame(r, header, false, 0)
    }
    pub fn covers_nodes(&self, r: &GpuRequest, header: &[u32]) -> bool {
        self.covers_frame(r, header, true, 0)
    }
    pub fn covers_surface(&self, r: &GpuRequest, header: &[u32]) -> bool {
        let guard = if r.padding & (1 << 28) != 0 { 6 } else { 4 };
        self.covers_frame(r, header, false, guard)
    }
    pub fn covers_lattice(&self, r: &GpuRequest, header: &[u32]) -> bool {
        if r.density_side == 0 || r.density_step_xz == 0 || r.density_step_y == 0 {
            return false;
        }
        let guard = if r.padding & (1 << 28) != 0 { 6 } else { 4 };
        let sx = i64::from(r.density_step_xz);
        let sy = i64::from(r.density_step_y);
        let lo = [
            (i64::from(r.origin_x) - guard).div_euclid(sx) * sx,
            i64::from(r.min_y).div_euclid(sy) * sy,
            (i64::from(r.origin_z) - guard).div_euclid(sx) * sx,
        ];
        let hi = [
            lo[0] + i64::from(r.density_side - 1) * sx,
            lo[1] + (i64::from(r.max_y) - lo[1] + sy - 1).div_euclid(sy) * sy,
            lo[2] + i64::from(r.density_side - 1) * sx,
        ];
        self.covers_box(r, header, lo, hi)
    }
    fn covers_frame(&self, r: &GpuRequest, header: &[u32], nodes: bool, guard: i64) -> bool {
        let width = i64::from(r.tile_side) * 16;
        let lo = [
            i64::from(r.origin_x) - guard,
            if nodes {
                i64::from(r.min_y).div_euclid(4) * 4
            } else {
                i64::from(r.min_y)
            },
            i64::from(r.origin_z) - guard,
        ];
        let hi = if nodes {
            [
                lo[0] + width,
                (i64::from(r.max_y) + 3).div_euclid(4) * 4,
                lo[2] + width,
            ]
        } else {
            [
                lo[0] + width + 2 * guard - 1,
                i64::from(r.max_y) - 1,
                lo[2] + width + 2 * guard - 1,
            ]
        };
        self.covers_box(r, header, lo, hi)
    }
    fn covers_box(&self, r: &GpuRequest, header: &[u32], lo: [i64; 3], hi: [i64; 3]) -> bool {
        if r.padding & ((1 << 26) | (1 << 27)) != (1 << 26) | (1 << 27)
            || r.density_side == 0
            || r.tile_side == 0
        {
            return false;
        }
        self.fields.iter().all(|&(field, cell)| {
            let Some(h) = header.get(field * 8..field * 8 + 8) else {
                return false;
            };
            if h[0] == 0 || h[7] != 1 | (1 << 16) || h[4..7].contains(&0) {
                return false;
            }
            let steps = [i64::from(cell[0]), i64::from(cell[1]), i64::from(cell[0])];
            (0..3).all(|axis| {
                let start = i64::from(h[axis + 1] as i32);
                let end = start + i64::from(h[axis + 4] - 1) * steps[axis];
                let lower = lo[axis].div_euclid(steps[axis]) * steps[axis];
                let upper = (hi[axis] + steps[axis] - 1).div_euclid(steps[axis]) * steps[axis];
                start <= lower && upper <= end && start.abs() <= 8_388_608 && end.abs() <= 8_388_608
            })
        })
    }
}

/// Used only after the kernel's coverage check verifies every needed field.
/// Disconnect raw interpolation-input graphs from these hot pipeline call trees.
pub(crate) fn cached_source(source: &str) -> String {
    let mut source = source.replace(
        "fn density_composed(r:Request)->bool {return (r.padding&(1u<<26u))!=0u;}",
        "fn density_composed(r:Request)->bool {return true;}",
    );
    let fields = source.matches("fn interpolation_field_").count();
    for field in 0..fields {
        source = source.replace(
            &super::interpolation::specialized(field),
            &super::interpolation::resident(field),
        );
    }
    // Outside a stored density cell, missing interval corners remain uncertain.
    // The actual mask point still lies inside the proven resident domain.
    source = source.replace(
        "if cached.y==0.0 {value=run_interpolation_input(field,point,r);}",
        "if cached.y==0.0 {return bounds_unknown();}",
    );
    // Missing density cells need no new interval interpreter in a mask kernel:
    // an unbounded certificate makes it evaluate the actual resident point.
    source.replace(
        "return run_density_bounds(1u,lower,lower+vec3<f32>(step),r);",
        "return bounds_unknown();",
    )
}

#[cfg(test)]
mod tests {
    use super::*;
    #[test]
    fn coverage_requires_exact_coordinates_and_complete_fine_fields() {
        let mut registry:RegistryProgram=serde_json::from_value(serde_json::json!({
            "programs":[{"nodes":[{"op":0,"a":0,"b":0,"c":0,"p":[0,0,0,0]}],"roots":[0]},
            {"nodes":[{"op":29,"a":0,"b":0,"c":0,"p":[0,0,0,0]},{"op":29,"a":1,"b":0,"c":0,"p":[0,0,0,0]},{"op":29,"a":2,"b":0,"c":0,"p":[0,0,0,0]},{"op":28,"a":0,"b":1,"c":2,"p":[0,0,0,0]}],"roots":[3]}],
            "noises":[],"points":[],"surface":[-64,8,0],
            "interpolations":[{"cell":[4,4],"input":{"nodes":[{"op":0,"a":0,"b":0,"c":0,"p":[1,0,0,0]}],"roots":[0]}}]
        })).unwrap();
        let mut r = GpuRequest {
            origin_x: -32,
            origin_z: -16,
            min_y: -64,
            max_y: 320,
            tile_side: 2,
            density_side: 11,
            padding: (1 << 26) | (1 << 27),
            ..GpuRequest::from(crate::ChunkRequest {
                seed: 0,
                chunk_x: 0,
                chunk_z: 0,
                min_y: -64,
                height: 384,
                base_height: 64.0,
                amplitude: 48.0,
                frequency: 0.008,
                reserved: 0,
            })
        };
        let plan = CachedDensity::new(&registry).unwrap();
        let header = [
            100,
            (-36i32) as u32,
            (-64i32) as u32,
            (-20i32) as u32,
            11,
            97,
            11,
            1 | (1 << 16),
        ];
        assert!(plan.covers(&r, &header));
        // Exact aligned endpoints do not sample the next corner. Surface
        // extraction additionally needs its actual four/six-block halo.
        assert!(plan.covers_nodes(&r, &header));
        assert!(plan.covers_surface(&r, &header));
        assert!(plan.covers_lattice(&r, &header));
        r.density_side = 12;
        assert!(!plan.covers_lattice(&r, &header));
        r.density_side = 11;
        r.padding |= 1 << 28;
        assert!(!plan.covers_surface(&r, &header));
        assert!(!plan.covers_lattice(&r, &header));
        r.padding &= !(1 << 28);
        r.min_y = -63;
        r.max_y = 319;
        assert!(plan.covers_nodes(&r, &header));
        r.max_y = 321;
        assert!(!plan.covers_nodes(&r, &header));
        assert!(!plan.covers_lattice(&r, &header));
        r.min_y = -64;
        r.max_y = 320;
        let mut missing = header;
        missing[0] = 0;
        assert!(!plan.covers(&r, &missing));
        let mut coarse = header;
        coarse[7] = 1 | (2 << 16);
        assert!(!plan.covers(&r, &coarse));
        let mut short = header;
        short[5] = 96;
        assert!(!plan.covers(&r, &short));
        r.origin_x = 8_388_608;
        assert!(!plan.covers(&r, &header));
        registry.programs[1].nodes[3].b = 0;
        assert!(CachedDensity::new(&registry).is_none());
        registry.programs[1].nodes[3].b = 1;
        registry.interpolations[0].cell = [5, 3];
        assert!(CachedDensity::new(&registry).is_none());

        // Cave nodes also evaluate underground climate and the cave root.
        // Every field used by either must have coverage; the mask also checks
        // all slots returned by the surface-density evaluator.
        registry.interpolations[0].cell = [4, 4];
        registry
            .interpolations
            .push(registry.interpolations[0].clone());
        registry.programs[0] = registry.programs[1].clone();
        registry.programs[0].nodes[3].p[0] = 1.0;
        registry.programs.push(registry.programs[1].clone());
        r.origin_x = -32;
        let node_plan = CachedDensity::for_nodes(&registry).unwrap();
        assert!(!node_plan.covers_nodes(&r, &header));
        assert!(node_plan.covers_nodes(&r, &[header, header].concat()));
        registry.programs[0].nodes[3].a = 2;
        assert!(CachedDensity::for_nodes(&registry).is_none());
        assert!(CachedDensity::new(&registry).is_some());
        registry.programs[0] = registry.programs[1].clone();
        registry.programs[2].nodes[3].c = 0;
        assert!(CachedDensity::for_nodes(&registry).is_none());
        let mut remapped = registry.programs[1].nodes[3].clone();
        remapped.b = 0;
        registry.programs[1].nodes.push(remapped);
        registry.programs[1].roots.push(4);
        assert!(CachedDensity::new(&registry).is_none());
    }

    #[test]
    fn cached_source_disconnects_raw_input_and_interval_fallbacks() {
        let source = format!(
            "fn density_composed(r:Request)->bool {{return (r.padding&(1u<<26u))!=0u;}}\n{}\n{}\n{}\n{}",
            super::super::interpolation::specialized(0),
            super::super::interpolation::specialized(1),
            include_str!("../density_bounds.wgsl"),
            include_str!("../program.wgsl"),
        );
        let cached = cached_source(&source);
        assert!(cached.contains("fn density_composed(r:Request)->bool {return true;}"));
        assert!(!cached.contains("interpolation_graph_0(point,request,context,program)[0]"));
        assert!(!cached.contains("interpolation_graph_1(point,request,context,program)[0]"));
        assert!(
            !cached.contains("if cached.y==0.0 {value=run_interpolation_input(field,point,r);}")
        );
        assert!(!cached.contains("return run_density_bounds(1u,lower,lower+vec3<f32>(step),r);"));
    }
}
