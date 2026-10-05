//! Registered trilinear operators. Child graphs are ordered before callers;
//! generated evaluator levels are acyclic and require no CPU spatial samples.
use super::Program;
use crate::{GpuRequest, program::RegistryProgram};
use serde::Deserialize;
use std::fmt::Write;

#[derive(Clone, Deserialize)]
pub struct Field {
    pub input: Program,
    pub cell: [u32; 2],
}

pub(crate) const CACHE_FLAG: u32 = 1 << 27;
pub(crate) const CACHE_WORDS: usize = 8;

/// Only pure, reachable density fields can share a context-free spatial cache.
/// Material context, or an input which depends on it, keeps the direct sampler.
pub(crate) struct CachePlan {
    fields: Vec<bool>,
    pub depth: usize,
}
impl CachePlan {
    pub fn new(registry: &RegistryProgram) -> Self {
        let mut pure = Vec::new();
        for f in &registry.interpolations {
            pure.push(f.input.nodes.iter().all(|n| match n.op {
                28 => pure[n.p[0] as usize],
                42 | 45..=51 | 53 | 54 => false,
                _ => true,
            }));
        }
        let mut fields = vec![false; pure.len()];
        let aquifer = registry
            .aquifer
            .as_ref()
            .filter(|a| a.enabled)
            .map(|a| a.program as usize);
        let mut pending = registry
            .programs
            .iter()
            .enumerate()
            .filter(|(i, _)| *i < 3 || aquifer.is_some_and(|a| *i >= a && *i < a + 5))
            .flat_map(|(_, p)| {
                p.nodes
                    .iter()
                    .filter(|n| n.op == 28)
                    .map(|n| n.p[0] as usize)
            })
            .collect::<Vec<_>>();
        while let Some(field) = pending.pop() {
            if std::mem::replace(&mut fields[field], true) {
                continue;
            }
            pending.extend(
                registry.interpolations[field]
                    .input
                    .nodes
                    .iter()
                    .filter(|n| n.op == 28)
                    .map(|n| n.p[0] as usize),
            );
        }
        for (used, pure) in fields.iter_mut().zip(pure) {
            *used &= pure;
        }
        let levels = depths(&registry.interpolations);
        let depth = fields
            .iter()
            .zip(levels)
            .filter_map(|(&used, d)| used.then_some(d))
            .max()
            .unwrap_or(0);
        Self { fields, depth }
    }

    /// Small host descriptors only. Samples are produced and consumed on GPU.
    /// Optional caches which exceed the real buffer/dispatch limit use the
    /// existing direct sampler; they never prevent terrain generation.
    pub fn layout(
        &self,
        registry: &RegistryProgram,
        r: &GpuRequest,
        guard: i32,
        floats: &mut u64,
        limit: u64,
        max_samples: u64,
    ) -> Result<(Vec<u32>, u32), String> {
        let width = if r.tile_side > 0 {
            r.tile_side * 16
        } else {
            16
        };
        let mut header = vec![0; self.fields.len() * CACHE_WORDS];
        let mut dispatch = 0;
        for (field, f) in registry
            .interpolations
            .iter()
            .enumerate()
            .filter(|(i, _)| self.fields[*i])
        {
            // Cache an aligned subset when the current terrain sampling grid
            // skips finer input nodes. Other field corners keep direct GPU
            // evaluation, rather than paying to precompute unused fine layers.
            let multiple = |field: u32, terrain: u32| {
                if r.padding & (1 << 26) == 0
                    && terrain.is_multiple_of(field)
                    && terrain / field <= 65535
                {
                    terrain / field
                } else {
                    1
                }
            };
            let mx = multiple(f.cell[0], r.density_step_xz);
            let my = multiple(f.cell[1], r.density_step_y);
            let sx = (f.cell[0] * mx) as i64;
            let sy = (f.cell[1] * my) as i64;
            let origin = [
                (r.origin_x as i64 - guard as i64).div_euclid(sx) * sx,
                (r.min_y as i64).div_euclid(sy) * sy,
                (r.origin_z as i64 - guard as i64).div_euclid(sx) * sx,
            ];
            let top = [
                r.origin_x as i64 + width as i64 + guard as i64,
                r.max_y as i64,
                r.origin_z as i64 + width as i64 + guard as i64,
            ];
            let sizes = [sx, sy, sx];
            let counts = std::array::from_fn::<_, 3, _>(|axis| {
                ((top[axis] - origin[axis] + sizes[axis] - 1) / sizes[axis]) as u64 + 1
            });
            let samples = counts.into_iter().try_fold(1u64, |v, n| v.checked_mul(n));
            let Some(samples) =
                samples.filter(|&n| n <= max_samples && n <= limit.saturating_sub(*floats))
            else {
                continue;
            };
            // If global integer endpoints are not representable, skip the cache
            // rather than changing the direct sampler's floating-point query.
            let coords = origin.map(i32::try_from);
            if coords.iter().any(Result::is_err)
                || (0..3).any(|axis| {
                    i32::try_from(origin[axis] + (counts[axis] as i64 - 1) * sizes[axis]).is_err()
                })
            {
                continue;
            }
            let at = field * CACHE_WORDS;
            header[at] = u32::try_from(*floats)
                .map_err(|_| "GPU interpolation cache address exceeds u32")?;
            for axis in 0..3 {
                header[at + 1 + axis] = *coords[axis].as_ref().unwrap() as u32;
                header[at + 4 + axis] = counts[axis] as u32;
            }
            header[at + 7] = mx | (my << 16);
            *floats += samples;
            dispatch = dispatch.max(samples as u32);
        }
        Ok((header, dispatch))
    }
}

pub(super) fn depths(fields: &[Field]) -> Vec<usize> {
    let mut result = Vec::with_capacity(fields.len());
    for field in fields {
        let child = field
            .input
            .nodes
            .iter()
            .filter(|n| n.op == 28)
            .map(|n| result[n.p[0] as usize])
            .max()
            .unwrap_or(0);
        result.push(child + 1);
    }
    result
}

/// Skip duplicate corners on aligned axes, and bypass interpolation altogether
/// on grid nodes. Negative coordinates use floor, never truncation or clamping.
fn sample_body(call: &str, field: &str) -> String {
    format!(
        "let at=bytecode[12]+{field}*4u;let program=bytecode[at];\n\
         let size=vec3<f32>(f32(bytecode[at+1u]),f32(bytecode[at+2u]),f32(bytecode[at+1u]));\n\
         let lower=floor(point/size)*size;let alpha=(point-lower)/size;\n\
         if all(alpha==vec3<f32>(0.0)){{let cached=interpolation_cached_corner({field},point,request);if cached.y!=0.0{{return cached.x;}}return {call}[0];}}\n\
         var corners:array<f32,8>;\n\
         for(var corner=0u;corner<8u;corner++){{\n\
             let upper=vec3<u32>(corner&1u,(corner>>1u)&1u,(corner>>2u)&1u);\n\
             if any((upper!=vec3<u32>(0u)) & (alpha==vec3<f32>(0.0))){{continue;}}\n\
             let point=lower+vec3<f32>(upper)*size;let cached=interpolation_cached_corner({field},point,request);\n\
             if cached.y!=0.0{{corners[corner]=cached.x;}}else{{corners[corner]={call}[0];}}\n\
         }}\nreturn interpolation_mix(corners,alpha);\n"
    )
}

pub(crate) fn prepass(depth: usize, specialized: bool, fields: usize) -> String {
    let input = if specialized {
        let mut code =
            "fn run_interpolation_input(field:u32,point:vec3<f32>,r:Request)->f32{switch field{\n"
                .to_owned();
        for field in 0..fields {
            writeln!(code,"case {field}u:{{return interpolation_graph_{field}(point,r,vec4<f32>(0.0),bytecode[15]+{field}u)[0];}}").unwrap();
        }
        code.push_str("default:{return 0.0;}}}\n");
        code
    } else {
        "fn run_interpolation_input(field:u32,point:vec3<f32>,r:Request)->f32{return run_program(bytecode[15]+field,point,r,vec4<f32>(0.0))[0];}\n".to_owned()
    };
    let mut code = input;
    for level in 1..=depth {
        writeln!(code,"@compute @workgroup_size(64) fn interpolation_nodes_{level}(@builtin(global_invocation_id) id:vec3<u32>){{
let r=requests[id.z];let field=id.y;if field>=bytecode[13] || (r.padding&{CACHE_FLAG}u)==0u{{return;}}
let info=bytecode[12]+field*4u;if bytecode[info+3u]!={level}u{{return;}}
let at=r.density_offset-bytecode[13]*8u+field*8u;
let size=vec3<u32>(bitcast<u32>(surface_nodes[at+4u]),bitcast<u32>(surface_nodes[at+5u]),bitcast<u32>(surface_nodes[at+6u]));
if id.x>=size.x*size.y*size.z{{return;}}
let cell=vec3<u32>(id.x%size.x,id.x/(size.x*size.z),id.x/size.x%size.z);
let origin=vec3<i32>(bitcast<i32>(surface_nodes[at+1u]),bitcast<i32>(surface_nodes[at+2u]),bitcast<i32>(surface_nodes[at+3u]));
let step=interpolation_cached_step(at,info);
let point=vec3<f32>(origin+vec3<i32>(cell)*step);
surface_nodes[bitcast<u32>(surface_nodes[at])+id.x]=run_interpolation_input(field,point,r);
}}").unwrap();
    }
    code
}

pub(super) fn interpreter(body: &str, depth: usize, prefix: &str) -> String {
    let mut result = format!(
        "fn {prefix}_field_none(field:u32,point:vec3<f32>,request:Request,context:vec4<f32>)->f32{{return 0.0;}}\n"
    );
    for level in 0..=depth {
        let name = if level == depth {
            prefix.to_owned()
        } else {
            format!("{prefix}_level_{level}")
        };
        let call = if level == 0 {
            format!("{prefix}_field_none")
        } else {
            format!("{prefix}_field_{}", level - 1)
        };
        result.push_str(
            &body
                .replace("fn run_program(", &format!("fn {name}("))
                .replace("INTERPOLATION_CALL", &call),
        );
        if level < depth {
            let call = format!("{name}(program,point,request,context)");
            writeln!(result,"fn {prefix}_field_{level}(field:u32,point:vec3<f32>,request:Request,context:vec4<f32>)->f32{{\n{}}}",sample_body(&call,"field")).unwrap();
        }
    }
    result
}

pub(crate) fn specialized(field: usize) -> String {
    let call = format!("interpolation_graph_{field}(point,request,context,program)");
    format!(
        "fn interpolation_field_{field}(point:vec3<f32>,request:Request,context:vec4<f32>)->f32{{\n{}}}\n",
        sample_body(&call, &format!("{field}u"))
    )
}

/// Point queries in kernels whose host coverage proof includes every required
/// fine-grid corner. Preserve global floor/mix arithmetic, but calculate the
/// resident base address once instead of validating each of the eight corners.
/// General, remapped and prepass queries continue to use `specialized` above.
pub(crate) fn resident(field: usize) -> String {
    format!(
        "fn interpolation_field_{field}(point:vec3<f32>,request:Request,context:vec4<f32>)->f32{{
let info=bytecode[12]+{field}u*4u;
let step=vec3<i32>(i32(bytecode[info+1u]),i32(bytecode[info+2u]),i32(bytecode[info+1u]));
let size=vec3<f32>(step);let lower=floor(point/size)*size;let alpha=(point-lower)/size;
let header=request.density_offset-bytecode[13]*8u+{field}u*8u;
let origin=vec3<i32>(bitcast<i32>(surface_nodes[header+1u]),bitcast<i32>(surface_nodes[header+2u]),bitcast<i32>(surface_nodes[header+3u]));
let width=bitcast<u32>(surface_nodes[header+4u]);let depth=bitcast<u32>(surface_nodes[header+6u]);
let cell=vec3<u32>((vec3<i32>(lower)-origin)/step);
let base=bitcast<u32>(surface_nodes[header])+(cell.y*depth+cell.z)*width+cell.x;
if all(alpha==vec3<f32>(0.0)){{return surface_nodes[base];}}
var corners:array<f32,8>;
for(var corner=0u;corner<8u;corner++){{
let upper=vec3<u32>(corner&1u,(corner>>1u)&1u,(corner>>2u)&1u);
if any((upper!=vec3<u32>(0u)) & (alpha==vec3<f32>(0.0))){{continue;}}
corners[corner]=surface_nodes[base+upper.x+upper.z*width+upper.y*width*depth];
}}
return interpolation_mix(corners,alpha);
}}\n"
    )
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::program::{Instruction, RegistryProgram};
    fn input(field: Option<usize>) -> Program {
        let mut nodes = vec![Instruction {
            op: 0,
            a: 0,
            b: 0,
            c: 0,
            p: [0.0; 4],
        }];
        for axis in 0..3 {
            nodes.push(Instruction {
                op: 29,
                a: axis,
                b: 0,
                c: 0,
                p: [0.0; 4],
            });
        }
        if let Some(field) = field {
            nodes.push(Instruction {
                op: 28,
                a: 1,
                b: 2,
                c: 3,
                p: [field as f32, 0.0, 0.0, 0.0],
            });
        }
        Program {
            roots: vec![(nodes.len() - 1) as u32],
            nodes,
        }
    }
    fn registry() -> RegistryProgram {
        serde_json::from_value(serde_json::json!({
            "programs":[{"nodes":[{"op":0,"a":0,"b":0,"c":0,"p":[0,0,0,0]}],"roots":[0]}],
            "noises":[{"frequency":1,"amplitude":1,"salt":0,"coefficients":[1]}],"points":[],"surface":[-64,8,0]
        })).unwrap()
    }
    #[test]
    fn field_dependencies_are_topological_and_coordinate_operands_stay_live() {
        let mut r = registry();
        r.programs = vec![input(Some(2)), input(None), input(None)];
        r.interpolations = vec![
            Field {
                input: input(None),
                cell: [4, 8],
            },
            Field {
                input: input(Some(0)),
                cell: [7, 5],
            },
            Field {
                input: input(Some(1)),
                cell: [5, 3],
            },
        ];
        r.validate(0).unwrap();
        assert_eq!(r.interpolation_depth(), 3);
        assert_eq!(r.programs[0].nodes[4].dependencies(&[]), vec![1, 2, 3]);
        let scratch = r.scratch_values();
        assert!(scratch >= 4);
        r.interpolations[0].input = input(Some(0));
        assert!(r.validate(0).unwrap_err().contains("earlier subgraphs"));
        r.interpolations[0].input = input(None);
        r.interpolations[2].cell[1] = 0;
        assert!(r.validate(0).unwrap_err().contains("interpolation field"));
        r.interpolations[2].cell[1] = 3;
        r.programs[0].nodes[4].p[0] = 0.5;
        assert!(r.validate(0).unwrap_err().contains("earlier subgraphs"));
    }
    #[test]
    fn cache_layout_uses_each_global_lattice_and_skips_unusable_context_or_capacity() {
        let mut registry = registry();
        registry.programs = vec![input(Some(2)), input(None), input(None)];
        registry.interpolations = vec![
            Field {
                input: input(None),
                cell: [4, 8],
            },
            Field {
                input: input(Some(0)),
                cell: [7, 5],
            },
            Field {
                input: input(Some(1)),
                cell: [5, 3],
            },
        ];
        let r = GpuRequest {
            origin_x: -17,
            origin_z: -1,
            min_y: 5,
            max_y: 37,
            ..GpuRequest::from(crate::ChunkRequest {
                seed: 0,
                chunk_x: 0,
                chunk_z: 0,
                min_y: 0,
                height: 64,
                base_height: 0.0,
                amplitude: 0.0,
                frequency: 0.0,
                reserved: 0,
            })
        };
        let plan = CachePlan::new(&registry);
        assert_eq!(plan.depth, 3);
        let mut floats = 1000;
        let (header, dispatch) = plan
            .layout(&registry, &r, 6, &mut floats, 10000, 10000)
            .unwrap();
        assert_eq!(
            header[0..7],
            [1000, (-24i32) as u32, 0, (-8i32) as u32, 9, 6, 9]
        );
        assert_eq!(
            header[8..15],
            [1486, (-28i32) as u32, 5, (-7i32) as u32, 6, 8, 5]
        );
        assert_eq!(
            header[16..23],
            [1726, (-25i32) as u32, 3, (-10i32) as u32, 7, 13, 8]
        );
        assert_eq!((floats, dispatch), (2454, 728));
        let mut floats = 1000;
        let (limited, dispatch) = plan
            .layout(&registry, &r, 6, &mut floats, 1486, 10000)
            .unwrap();
        assert_eq!(limited[0..8], header[0..8]);
        assert_eq!(limited[8..], [0; 16]);
        assert_eq!((floats, dispatch), (1486, 486));
        let mut floats = 1000;
        let (limited, dispatch) = plan
            .layout(&registry, &r, 6, &mut floats, 10000, 400)
            .unwrap();
        assert_eq!(limited[0..8], [0; 8]);
        assert_eq!(limited[16..], [0; 8]);
        assert_eq!((floats, dispatch), (1240, 240));
        registry.interpolations[0].input.nodes[0].op = 45;
        let context = CachePlan::new(&registry);
        assert_eq!(context.fields, [false; 3]);
        assert_eq!(context.depth, 0);
    }
    #[test]
    fn composed_queries_retain_finer_vertical_input_nodes() {
        let mut registry = registry();
        registry.programs = vec![input(None), input(Some(0)), input(None)];
        registry.interpolations = vec![Field {
            input: input(None),
            cell: [4, 4],
        }];
        let plan = CachePlan::new(&registry);
        let mut request = GpuRequest::from(crate::ChunkRequest {
            seed: 0,
            chunk_x: -1,
            chunk_z: 0,
            min_y: -64,
            height: 384,
            base_height: 64.0,
            amplitude: 48.0,
            frequency: 0.008,
            reserved: 0,
        });
        request.density_step_xz = 4;
        request.density_step_y = 8;
        let mut floats = 1000;
        let (coarse, _) = plan
            .layout(&registry, &request, 4, &mut floats, 100000, 100000)
            .unwrap();
        request.padding |= 1 << 26;
        let mut floats = 1000;
        let (dense, _) = plan
            .layout(&registry, &request, 4, &mut floats, 100000, 100000)
            .unwrap();
        assert_eq!(coarse[5], 49);
        assert_eq!(dense[5], 97);
        assert_eq!(coarse[7], 1 | (2 << 16));
        assert_eq!(dense[7], 1 | (1 << 16));
        assert_eq!(coarse[1..4], dense[1..4]);
    }
}
