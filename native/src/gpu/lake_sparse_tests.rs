//! Independent raw-input oracle for the actual fractional lake probe stencils.
use super::*;

#[test]
#[ignore = "requires an exported profile in RETINA_PROGRAM_PARITY_PROFILE and a real GPU"]
fn sparse_lake_roots_match_uncached_gpu() {
    let bytes = std::fs::read(std::env::var("RETINA_PROGRAM_PARITY_PROFILE").unwrap()).unwrap();
    let profile = WorldProfile::parse(&bytes).unwrap();
    let registry = profile.registry_program.as_ref().unwrap();
    let plan = crate::program::lake_sparse::Plan::new(registry)
        .expect("binary original-coordinate fixture");
    let mut gpu = Gpu::new(std::sync::Arc::new(crate::pipeline::Metrics::default())).unwrap();
    gpu.add_profile(
        1,
        &profile.gpu_bytes(),
        &profile.climate_gpu_bytes(),
        &crate::program::RegistryProgram::bytes(Some(&profile)),
    );
    let graph = crate::specialize::source(registry).unwrap();
    let original = format!("{graph}\n{}", plan.source(0));
    let cached = crate::program::lake_sparse::cached_source(&original);
    let capacity = registry.scratch_values().max(1);
    let reference = crate::program::interpreter_body(
        capacity,
        registry.interpolation_depth(),
        "run_reference",
        true,
    );
    let mut bounds = crate::program::density_bounds_source(capacity);
    for symbol in [
        "density_composed",
        "bounds_unknown",
        "bounds_finite",
        "bounds_widen",
        "bounds_product",
        "interpolation_bounds",
        "run_density_bounds",
    ] {
        bounds = bounds.replace(symbol, &format!("reference_{symbol}"));
    }
    bounds = bounds.replace("run_interpolation_input", "reference_interpolation_input");
    let reference = format!(
        "{reference}\nfn reference_interpolation_input(field:u32,point:vec3<f32>,r:Request)->f32{{return run_reference(bytecode[15]+field,point,r,vec4<f32>(0.0))[0];}}\n{bounds}"
    );
    // Compare every returned root; report missing cache hits separately so an
    // unused accelerator cannot satisfy the oracle. Six positions exercise the
    // actual fractional probe, fractional Y and all original cell XZ corners.
    let kernel = r#"
@compute @workgroup_size(64) fn sparse_parity(@builtin(global_invocation_id) id:vec3<u32>){
let r=requests[id.z];let height=u32(r.max_y-r.min_y);let probe=id.x/(height*6u);let sample=id.x%(height*6u);
if probe>=lake_side(r)*lake_side(r)*5u{return;}
lake_sparse_probe=probe;
let p=lake_probe_point(probe,r);let mode=sample%6u;
let step=vec3<f32>(f32(r.density_step_xz),f32(r.density_step_y),f32(r.density_step_xz));
let lo=vec3<f32>(density_origin(r))+floor((vec3<f32>(p.x,f32(r.min_y+i32(sample/6u)),p.y)-vec3<f32>(density_origin(r)))/step)*step;
var point=vec3<f32>(p.x,f32(r.min_y+i32(sample/6u)),p.y);
if mode==1u{point.y+=0.375;}
if mode>=2u{let corner=mode-2u;point.x=lo.x+f32(corner&1u)*step.x;point.z=lo.z+f32(corner>>1u)*step.z;}
var direct=r;direct.padding&=~((1u<<27u)|(1u<<25u));
let expected=run_reference(1u,point,direct,vec4<f32>(0.0));let actual=run_surface_density(point,r,vec4<f32>(0.0));
var mismatches=0u;for(var root=0u;root<6u;root++){if bitcast<u32>(expected[root])!=bitcast<u32>(actual[root]){mismatches+=1u;}}
var missing=0u;var hits=0u;
for(var field=0u;field<bytecode[13];field++){
let info=bytecode[12]+field*4u;let size=vec3<f32>(f32(bytecode[info+1u]),f32(bytecode[info+2u]),f32(bytecode[info+1u]));let lower=floor(point/size)*size;
if lake_sparse_corner(field,lower,r).y!=0.0{hits+=1u;}
}
let bound=run_density_bounds(1u,lo,lo+step,r);let ref_bound=reference_run_density_bounds(1u,lo,lo+step,direct);
if any(bitcast<vec2<u32>>(bound)!=bitcast<vec2<u32>>(ref_bound)){missing+=1u;}
let at=id.z*MAX_SAMPLES+id.x;columns[at]=Column(i32(mismatches),hits,missing);
}
"#;
    let device = gpu.device.clone();
    let make_module = |program: &str, extra: &str| {
        let source = format!(
            "{}\n{}\n{program}\n{}\n{extra}",
            include_str!("../simplex.wgsl"),
            include_str!("../climate.wgsl"),
            include_str!("../noise3.wgsl")
        );
        device.create_shader_module(wgpu::ShaderModuleDescriptor {
            label: Some("Retina sparse lake oracle"),
            source: wgpu::ShaderSource::Wgsl(crate::specialize::static_calls(&source).into()),
        })
    };
    let prepass = make_module(&original, "");
    let layout = device.create_pipeline_layout(&wgpu::PipelineLayoutDescriptor {
        label: None,
        bind_group_layouts: &[Some(&gpu.layout)],
        immediate_size: 0,
    });
    let make_pipeline = |module: &wgpu::ShaderModule, entry: &str| {
        device.create_compute_pipeline(&wgpu::ComputePipelineDescriptor {
            label: Some(entry),
            layout: Some(&layout),
            module,
            entry_point: Some(entry),
            compilation_options: Default::default(),
            cache: None,
        })
    };
    let writer = make_pipeline(&prepass, "lake_sparse_fields");
    // No atlas, a normal atlas, and a deliberately reduced atlas. The sparse
    // writer fills missing corners in all three cases, with no CPU noise values.
    let mut total_roots = 0usize;
    let mut total_bounds = 0usize;
    for atlas in 0..3 {
        let mut floats = SURFACE_METADATA_FLOATS as u64;
        let mut dispatch = 0u32;
        let mut jobs = Vec::new();
        let mut lake_data = Vec::new();
        let mut headers = Vec::new();
        let mut atlas_dispatch = 0u32;
        let mut max_samples = 0u32;
        for (index, (x, z, bottom, top)) in [
            (-33, -129, -67, 318),
            (4096, -4097, -64, 320),
            (2_000_001, -2_000_033, -61, 321),
        ]
        .into_iter()
        .enumerate()
        {
            let [sx, sy] = registry.terrain_cell;
            let side = (16u64
                + 8
                + (x - 4i32)
                    .rem_euclid(sx as i32)
                    .max((z - 4i32).rem_euclid(sx as i32)) as u64)
                .div_ceil(u64::from(sx))
                + 1;
            let y0 = i64::from(bottom).div_euclid(i64::from(sy)) * i64::from(sy);
            let layers = (i64::from(top) - y0) as u64;
            let layers = layers.div_ceil(u64::from(sy)) + 1;
            let lake_side = (16u64 + x.rem_euclid(128).max(z.rem_euclid(128)) as u64).div_ceil(128);
            floats += registry.interpolations.len() as u64 * 8;
            let r = GpuRequest {
                origin_x: x,
                origin_z: z,
                min_y: bottom,
                max_y: top,
                seed_low: 123456789 ^ index as u32 * 7919,
                seed_high: index as u32 * 97,
                base_height: 64.0,
                amplitude: 48.0,
                frequency: 0.008,
                profile: 1,
                tile_side: 0,
                padding: crate::program::lake_sparse::FLAG | 1 << 26,
                density_offset: floats as u32,
                density_side: side as u32,
                density_step_xz: sx,
                density_step_y: sy,
            };
            assert!(plan.request_supported(&r));
            floats += side * side * layers * 3 + 24 * 24;
            lake_data.push((
                floats,
                vec![31.0f32, 0.0, 0.0, 1.0].repeat((lake_side * lake_side) as usize),
            ));
            floats += lake_side * lake_side * (4 + 20 * layers);
            floats += lake_side * lake_side * 5 * plan.words(&r);
            dispatch = dispatch.max((lake_side * lake_side * 5 * plan.max_samples(&r)) as u32);
            max_samples =
                max_samples.max((lake_side * lake_side * 5 * (top - bottom) as u64 * 6) as u32);
            jobs.push(r);
        }
        let cache = crate::program::interpolation::CachePlan::new(registry);
        for r in &mut jobs {
            let limit = if atlas == 0 { floats } else { u32::MAX as u64 };
            let (mut header, n) = cache
                .layout(registry, r, 4, &mut floats, limit, 64 * 65535)
                .unwrap();
            if atlas > 0 {
                r.padding |= crate::program::interpolation::CACHE_FLAG;
            }
            if atlas == 2 {
                for entry in header.chunks_exact_mut(8) {
                    entry[4] = entry[4].min(2);
                    entry[6] = entry[6].min(2);
                }
            }
            headers.push((u64::from(r.density_offset) - header.len() as u64, header));
            atlas_dispatch = atlas_dispatch.max(n);
        }
        gpu.ensure_surface_lattice(floats * 4);
        gpu.queue
            .write_buffer(&gpu.requests, 0, bytemuck::cast_slice(&jobs));
        for (at, values) in &lake_data {
            gpu.queue
                .write_buffer(&gpu.height_nodes, at * 4, bytemuck::cast_slice(values));
        }
        for (at, values) in &headers {
            gpu.queue
                .write_buffer(&gpu.height_nodes, at * 4, bytemuck::cast_slice(values));
        }
        let shader = make_module(
            &cached,
            &format!(
                "{reference}\n{}",
                kernel.replace("MAX_SAMPLES", &format!("{max_samples}u"))
            ),
        );
        let parity = make_pipeline(&shader, "sparse_parity");
        let size = u64::from(max_samples) * jobs.len() as u64 * 12;
        let output = gpu.readback_buffer("Retina sparse lake root checks", size);
        let mut encoder = gpu
            .device
            .create_command_encoder(&wgpu::CommandEncoderDescriptor::default());
        if atlas > 0 {
            for level in 1..=cache.depth {
                let pipeline = make_pipeline(&prepass, &format!("interpolation_nodes_{level}"));
                let mut pass = encoder.begin_compute_pass(&wgpu::ComputePassDescriptor::default());
                pass.set_pipeline(&pipeline);
                pass.set_bind_group(0, &gpu.profiles[&1].3, &[]);
                pass.dispatch_workgroups(
                    atlas_dispatch.div_ceil(64),
                    registry.interpolations.len() as u32,
                    jobs.len() as u32,
                );
            }
        }
        {
            let mut pass = encoder.begin_compute_pass(&wgpu::ComputePassDescriptor::default());
            pass.set_pipeline(&writer);
            pass.set_bind_group(0, &gpu.profiles[&1].3, &[]);
            pass.dispatch_workgroups(dispatch.div_ceil(64), plan.field_count(), jobs.len() as u32);
        }
        {
            let mut pass = encoder.begin_compute_pass(&wgpu::ComputePassDescriptor::default());
            pass.set_pipeline(&parity);
            pass.set_bind_group(0, &gpu.profiles[&1].3, &[]);
            pass.dispatch_workgroups(max_samples.div_ceil(64), 1, jobs.len() as u32);
        }
        encoder.copy_buffer_to_buffer(&gpu.output, 0, &output, 0, size);
        gpu.queue.submit([encoder.finish()]);
        let mut mapping = Mapping::new(&output, size);
        gpu.device
            .poll(wgpu::PollType::Wait {
                submission_index: None,
                timeout: Some(Duration::from_secs(60)),
            })
            .unwrap();
        mapping.check().unwrap();
        let data = output.slice(..size).get_mapped_range().unwrap();
        let words: &[u32] = bytemuck::cast_slice(&data);
        let mut hits = 0u64;
        for (job, r) in jobs.iter().enumerate() {
            let lake_side = (16u32
                + r.origin_x.rem_euclid(128).max(r.origin_z.rem_euclid(128)) as u32)
                .div_ceil(128);
            let count = lake_side * lake_side * 5 * (r.max_y - r.min_y) as u32 * 6;
            for sample in 0..count as usize {
                let at = (job * max_samples as usize + sample) * 3;
                assert_eq!(
                    words[at], 0,
                    "root mismatch: atlas {atlas}, request {job}, sample {sample}"
                );
                assert_eq!(
                    words[at + 2],
                    0,
                    "interval mismatch: atlas {atlas}, request {job}, sample {sample}"
                );
                hits += u64::from(words[at + 1]);
                total_roots += 6;
                total_bounds += 2;
            }
        }
        assert!(hits > 0, "sparse cache must actually be queried");
        drop(data);
        drop(mapping);
    }
    println!(
        "Sparse lake GPU oracle: {total_roots} root float-bit comparisons, {total_bounds} interval endpoint comparisons"
    );
}
