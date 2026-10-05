//! A raw GPU oracle for compact schedules, independent of the column atlas.
use super::*;

#[test]
#[ignore = "requires RETINA_PROGRAM_PARITY_PROFILE and a real GPU"]
fn column_interpreter_roots_match_raw_gpu() {
    let bytes = std::fs::read(std::env::var("RETINA_PROGRAM_PARITY_PROFILE").unwrap()).unwrap();
    let profile = WorldProfile::parse(&bytes).unwrap();
    let registry = profile.registry_program.as_ref().unwrap();
    let plan = crate::column_program::Plan::new(registry);
    assert!(
        !plan.owners.is_empty(),
        "fixture must reuse actual X/Z expressions"
    );
    let bytecode = crate::program::RegistryProgram::bytes(Some(&profile));
    let words: &[u32] = bytemuck::cast_slice(&bytecode);
    let table = (words[6] + words[7]) as usize;
    for pid in (0..3)
        .chain(registry.programs.len()..registry.programs.len() + registry.interpolations.len())
    {
        println!(
            "{{\"event\":\"column_schedule\",\"program\":{pid},\"original_nodes\":{},\"cached_nodes\":{}}}",
            words[17 + pid * 8],
            words[table + 2 + pid * 2]
        );
    }
    let mut gpu = Gpu::new(std::sync::Arc::new(crate::pipeline::Metrics::default())).unwrap();
    gpu.add_profile(
        1,
        &profile.gpu_bytes(),
        &profile.climate_gpu_bytes(),
        &crate::program::RegistryProgram::bytes(Some(&profile)),
    );
    let raw = crate::program::interpreter_body(
        registry.scratch_values().max(1),
        registry.interpolation_depth(),
        "run_reference",
        true,
    );
    let actual = crate::program::interpreter_source_density(
        registry.scratch_values().max(1),
        registry.interpolation_depth(),
        false,
    );
    let kernel=r#"
@compute @workgroup_size(64) fn column_parity(@builtin(global_invocation_id) id:vec3<u32>){
    if id.x>=384u{return;}
    let r=requests[id.z];let program=select(id.y,bytecode[15]+id.y-3u,id.y>=3u);
    let origin=density_origin(r);let sample=id.x;let mode=sample%4u;
    var point=vec3<f32>(vec3<i32>(origin)+vec3<i32>(i32(sample%8u)*i32(r.density_step_xz),i32(sample/16u)*17,i32(sample/8u%8u)*i32(r.density_step_xz)));
    if mode==1u{point.x+=0.375;point.y+=0.125;}
    if mode==2u{point.z+=1024.0;}
    if mode==3u{point.y+=0.625;}
    let context=vec4<f32>(f32(sample%7u),f32(sample%5u),f32(sample%9u),f32(sample%11u));
    var direct=r;direct.padding&=~(1u<<27u);
    let expected=run_reference(program,point,direct,context);let actual=run_program(program,point,r,context);
    var differences=0u;for(var root=0u;root<6u;root++){if bitcast<u32>(expected[root])!=bitcast<u32>(actual[root]){differences+=1u;}}
    let at=(id.z*PROGRAMS+id.y)*384u+id.x;
    let hit=interpreter_column_index(point,r)!=0xffffffffu && bytecode[bytecode[6]+bytecode[7]+1u+program*2u]!=0u;
    columns[at]=Column(i32(differences),select(0u,1u,hit),sample);
}
"#.replace("PROGRAMS",&format!("{}u",3+registry.interpolations.len()));
    let source = format!(
        "{}\n{}\n{actual}\n{}\n{raw}\n{kernel}",
        include_str!("../simplex.wgsl"),
        include_str!("../climate.wgsl"),
        include_str!("../noise3.wgsl")
    );
    let device = gpu.device.clone();
    let module = device.create_shader_module(wgpu::ShaderModuleDescriptor {
        label: Some("Retina compact column oracle"),
        source: wgpu::ShaderSource::Wgsl(source.into()),
    });
    let horizontal_source = format!(
        "{}\n{}\n{}\n{}",
        include_str!("../simplex.wgsl"),
        include_str!("../climate.wgsl"),
        crate::specialize::source(registry).unwrap(),
        include_str!("../noise3.wgsl")
    );
    let horizontal = device.create_shader_module(wgpu::ShaderModuleDescriptor {
        label: Some("Retina oracle X/Z writer"),
        source: wgpu::ShaderSource::Wgsl(
            crate::specialize::static_calls(&horizontal_source).into(),
        ),
    });
    let layout = device.create_pipeline_layout(&wgpu::PipelineLayoutDescriptor {
        label: None,
        bind_group_layouts: &[Some(&gpu.layout)],
        immediate_size: 0,
    });
    let pipeline = |module: &wgpu::ShaderModule, entry: &str| {
        device.create_compute_pipeline(&wgpu::ComputePipelineDescriptor {
            label: Some(entry),
            layout: Some(&layout),
            module,
            entry_point: Some(entry),
            compilation_options: Default::default(),
            cache: None,
        })
    };
    let writer = pipeline(&horizontal, "horizontal_nodes");
    let check = pipeline(&module, "column_parity");
    let mut floats = SURFACE_METADATA_FLOATS as u64;
    let mut requests = Vec::new();
    for (index, (x, z, step)) in [
        (-33, -129, 4),
        (4096, -4097, 4),
        (2_000_001, -2_000_033, 4),
        (16_777_232, -16_777_232, 3),
        (-16_777_232, 16_777_232, 3),
        (-33, -129, 4),
    ]
    .into_iter()
    .enumerate()
    {
        let bottom = -67i32;
        let top = 321i32;
        let layers = (top - i32::div_euclid(bottom, 8) * 8 + 7) / 8 + 1;
        requests.push(GpuRequest {
            origin_x: x,
            origin_z: z,
            min_y: bottom,
            max_y: top,
            seed_low: 123456789 ^ index as u32 * 7919,
            seed_high: 97 * index as u32,
            base_height: 64.0,
            amplitude: 48.0,
            frequency: 0.008,
            profile: 1,
            tile_side: 0,
            padding: if index == 5 {
                1 << 28
            } else {
                1 << 28 | crate::program::COLUMN_INTERPRETER_FLAG
            },
            density_offset: floats as u32,
            density_side: 9,
            density_step_xz: step,
            density_step_y: 8,
        });
        let lake_side = (16 + x.rem_euclid(128).max(z.rem_euclid(128)) + 127) / 128;
        floats += 9 * 9 * layers as u64
            + 28 * 28
            + lake_side as u64 * lake_side as u64 * (4 + 20 * layers as u64)
            + 9 * 9 * plan.owners.len() as u64;
    }
    gpu.ensure_surface_lattice(floats * 4);
    gpu.queue
        .write_buffer(&gpu.requests, 0, bytemuck::cast_slice(&requests));
    let count = 384 * (3 + registry.interpolations.len()) * requests.len();
    let size = count as u64 * 12;
    let output = gpu.readback_buffer("Retina column schedule comparisons", size);
    let mut encoder = device.create_command_encoder(&wgpu::CommandEncoderDescriptor::default());
    {
        let mut pass = encoder.begin_compute_pass(&wgpu::ComputePassDescriptor::default());
        pass.set_pipeline(&writer);
        pass.set_bind_group(0, &gpu.profiles[&1].3, &[]);
        pass.dispatch_workgroups(2, requests.len() as u32, 1);
    }
    {
        let mut pass = encoder.begin_compute_pass(&wgpu::ComputePassDescriptor::default());
        pass.set_pipeline(&check);
        pass.set_bind_group(0, &gpu.profiles[&1].3, &[]);
        pass.dispatch_workgroups(
            6,
            3 + registry.interpolations.len() as u32,
            requests.len() as u32,
        );
    }
    encoder.copy_buffer_to_buffer(&gpu.output, 0, &output, 0, size);
    gpu.queue.submit([encoder.finish()]);
    let mut mapping = Mapping::new(&output, size);
    device
        .poll(wgpu::PollType::Wait {
            submission_index: None,
            timeout: Some(Duration::from_secs(60)),
        })
        .unwrap();
    mapping.check().unwrap();
    let data = output.slice(..size).get_mapped_range().unwrap();
    let columns: &[Column] = bytemuck::cast_slice(&data);
    let mut hits = 0;
    for (index, c) in columns.iter().enumerate() {
        assert_eq!(
            c.height,
            0,
            "GPU roots differ at {index}; request {}, program {}, sample {}",
            index / (384 * (3 + registry.interpolations.len())),
            index / 384 % (3 + registry.interpolations.len()),
            index % 384
        );
        hits += c.packed;
    }
    assert!(
        hits > 100,
        "must exercise resident values, not only fallback"
    );
    assert!(
        columns[(requests.len() - 1) * 384 * (3 + registry.interpolations.len())..]
            .iter()
            .all(|c| c.packed == 0),
        "unflagged submissions must use raw bytecode"
    );
    println!(
        "{{\"event\":\"gpu_column_interpreter\",\"roots_compared\":{},\"cached_queries\":{hits},\"horizontal_fields\":{}}}",
        count * 6,
        plan.owners.len()
    );
}
