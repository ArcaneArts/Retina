//! Raw GPU graph oracle at many Y levels, independent of either column atlas.
use super::*;

#[test]
#[ignore = "requires RETINA_PROGRAM_PARITY_PROFILE and a real GPU"]
fn aquifer_column_roots_match_raw_gpu() {
    let bytes = std::fs::read(std::env::var("RETINA_PROGRAM_PARITY_PROFILE").unwrap()).unwrap();
    let profile = WorldProfile::parse(&bytes).unwrap();
    let registry = profile.registry_program.as_ref().unwrap();
    let aquifer = registry.aquifer.as_ref().unwrap();
    let pid = aquifer.program as usize + 4;
    let plan = crate::column_program::Plan::for_programs(registry, |p| p == pid);
    assert!(
        !plan.owners.is_empty(),
        "fixture must reuse X/Z expressions"
    );
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
    let specialized = crate::specialize::source_with_aquifer_columns(registry).unwrap();
    let kernel = r#"
@compute @workgroup_size(64) fn aquifer_parity(@builtin(global_invocation_id) id:vec3<u32>){
    if id.x>=256u{return;}let r=requests[id.y];
    var point=vec3<f32>(f32(r.origin_x+i32(id.x%16u)*4),0.0,f32(r.origin_z+i32(id.x/16u)*4));
    if id.x%3u==1u {point.x+=0.375;point.z-=0.625;}
    let fields=aquifer_column_values(point,r);var differences=0u;
    for(var y=0u;y<64u;y++) {
        point.y=-80.0+f32(y)*8.0+select(0.0,0.125,id.x%3u==2u);
        let context=vec4<f32>(f32(y%5u),f32(y%7u),f32(y%9u),f32(y%11u));
        let expected=run_reference(AQUIFER_PROGRAM,point,r,context);
        let actual=run_aquifer_column(point,r,context,fields);
        for(var root=0u;root<6u;root++){if bitcast<u32>(expected[root])!=bitcast<u32>(actual[root]) {differences++;}}

    }
    columns[id.y*256u+id.x]=Column(i32(differences),64u*6u,id.x);
}
"#.replace("AQUIFER_PROGRAM", &format!("{pid}u"));
    let source = format!(
        "{}\n{}\n{specialized}\n{}\n{raw}\n{kernel}",
        include_str!("../simplex.wgsl"),
        include_str!("../climate.wgsl"),
        include_str!("../noise3.wgsl")
    );
    let device = gpu.device.clone();
    let module = device.create_shader_module(wgpu::ShaderModuleDescriptor {
        label: Some("Retina local aquifer column oracle"),
        source: wgpu::ShaderSource::Wgsl(source.into()),
    });
    let layout = device.create_pipeline_layout(&wgpu::PipelineLayoutDescriptor {
        label: None,
        bind_group_layouts: &[Some(&gpu.layout)],
        immediate_size: 0,
    });
    let pipeline = device.create_compute_pipeline(&wgpu::ComputePipelineDescriptor {
        label: Some("Retina aquifer graph comparisons"),
        layout: Some(&layout),
        module: &module,
        entry_point: Some("aquifer_parity"),
        compilation_options: Default::default(),
        cache: None,
    });
    let requests = [
        (-33, -129),
        (4096, -4097),
        (2_000_001, -2_000_033),
        (16_777_232, -16_777_232),
        (-16_777_232, 16_777_232),
        (-33, -129),
    ]
    .into_iter()
    .enumerate()
    .map(|(index, (x, z))| GpuRequest {
        origin_x: x,
        origin_z: z,
        min_y: -67,
        max_y: 321,
        seed_low: 123456789 ^ index as u32 * 7919,
        seed_high: 97 * index as u32,
        base_height: 64.0,
        amplitude: 48.0,
        frequency: 0.008,
        profile: 1,
        tile_side: 0,
        padding: 0,
        density_offset: 0,
        density_side: 0,
        density_step_xz: 4,
        density_step_y: 8,
    })
    .collect::<Vec<_>>();
    gpu.queue
        .write_buffer(&gpu.requests, 0, bytemuck::cast_slice(&requests));
    let count = requests.len() * 256;
    let size = count as u64 * 12;
    let output = gpu.readback_buffer("Retina aquifer graph comparisons", size);
    let mut encoder = device.create_command_encoder(&wgpu::CommandEncoderDescriptor::default());
    {
        let mut pass = encoder.begin_compute_pass(&wgpu::ComputePassDescriptor::default());
        pass.set_pipeline(&pipeline);
        pass.set_bind_group(0, &gpu.profiles[&1].3, &[]);
        pass.dispatch_workgroups(4, requests.len() as u32, 1);
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
    for (index, column) in columns.iter().enumerate() {
        assert_eq!(
            column.height,
            0,
            "GPU roots differ at request {}, X/Z sample {}",
            index / 256,
            index % 256
        );
        assert_eq!(column.packed, 64 * 6);
    }
    println!(
        "{{\"event\":\"gpu_aquifer_columns\",\"roots_compared\":{},\"local_fields\":{},\"program\":{pid}}}",
        count * 64 * 6,
        plan.owners.len()
    );
}
