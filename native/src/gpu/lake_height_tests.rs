//! Compare the cache-only reduction with the original height query on the GPU.
use super::*;

fn function(source: &str, name: &str) -> String {
    let start = source.find(&format!("fn {name}(")).unwrap();
    let mut depth = 0;
    for (offset, c) in source[start..].char_indices() {
        if c == '{' { depth += 1; }
        if c == '}' {
            depth -= 1;
            if depth == 0 { return source[start..start + offset + 1].to_owned(); }
        }
    }
    panic!("unterminated {name}");
}

#[test]
#[ignore = "requires a real GPU"]
fn cached_lake_heights_match_general_query() {
    compare_heights(false);
}

#[test]
#[ignore = "requires a real GPU"]
fn compact_height_scan_matches_general_query() {
    compare_heights(true);
}

fn compare_heights(compact: bool) {
    let gpu = Gpu::new(std::sync::Arc::new(crate::pipeline::Metrics::default())).unwrap();
    // Each invocation owns its samples. Vary composition, clipped/empty bounds,
    // fractional X/Z, negative origins, non-aligned ceilings and density signs.
    let mut source = r#"
struct Request {min_y:i32,max_y:i32,density_step_xz:u32,density_step_y:u32,mode:u32,index:u32}
@group(0) @binding(0) var<storage,read_write> surface_nodes:array<f32>;
@group(0) @binding(1) var<storage,read_write> results:array<vec2<f32>>;
fn generation_min(r:Request)->i32{return select(r.min_y,-43,r.mode==1u);}
fn generation_max(r:Request)->i32{return select(select(r.max_y,-17,r.mode==1u),-80,r.mode==2u);}
fn empty_surface(r:Request)->f32{return f32(r.min_y);}
fn density_composed(r:Request)->bool{return r.mode==3u;}
fn density_origin(r:Request)->vec3<i32>{return vec3<i32>(-128,-64,-256);}
fn density_layers(r:Request)->u32{return 9u;}
fn lake_probe_offset(r:Request)->u32{return r.index*36u;}
fn density_floor_div(a:i32,b:i32)->i32{return i32(floor(f32(a)/f32(b)));}
fn sample_corner(cell:vec2<i32>,layer:u32,r:Request)->f32{
    return sin(f32(layer)*1.7+f32(r.index/16u))*2.0+f32(cell.x)*0.2-f32(cell.y)*0.3;
}
fn density_layer(cell:vec2<i32>,t:vec2<f32>,layer:u32,r:Request)->f32{
    let a=sample_corner(cell,layer,r);var b=a;
    if t.x>0.0{b=sample_corner(cell+vec2<i32>(1,0),layer,r);}
    let near=mix(a,b,t.x);if t.y==0.0{return near;}
    let c=sample_corner(cell+vec2<i32>(0,1),layer,r);var d=c;
    if t.x>0.0{d=sample_corner(cell+vec2<i32>(1,1),layer,r);}
    return mix(near,mix(c,d,t.x),t.y);
}
fn registered_density(point:vec3<f32>,r:Request)->f32{
    let local=(point-vec3<f32>(density_origin(r)))/vec3<f32>(4.0,8.0,4.0);
    let cell=vec2<i32>(floor(local.xz));let t=fract(local.xz);
    let y=clamp(local.y,0.0,8.0);let layer=min(u32(floor(y)),7u);
    return mix(density_layer(cell,t,layer,r),density_layer(cell,t,layer+1u,r),y-f32(layer));
}
// The reference's uncached composed path is unreachable for a real probe.
fn density_cell_bounds(cell:vec3<i32>,r:Request)->vec2<f32>{return vec2<f32>(0.0);}
fn run_program(id:u32,point:vec3<f32>,r:Request,ctx:vec4<f32>)->array<f32,6>{return array<f32,6>();}
@compute @workgroup_size(64) fn parity(@builtin(global_invocation_id) id:vec3<u32>){
    let r=Request(-61,select(0,-3,(id.x&4u)!=0u),4u,8u,id.x%4u,id.x);
    let point=vec2<f32>(-127.0,-254.0)+vec2<f32>(f32(id.x%8u)*0.375,f32(id.x%5u)*0.5);
    let cell=vec2<i32>(floor((point-vec2<f32>(density_origin(r).xz))/4.0));
    for(var layer=0u;layer<9u;layer++){
        for(var corner=0u;corner<4u;corner++){
            surface_nodes[lake_probe_offset(r)+layer*4u+corner]=sample_corner(cell+vec2<i32>(i32(corner&1u),i32(corner>>1u)),layer,r);
        }
    }
    results[id.x]=vec2<f32>(density_surface_height(point,r,0u),lake_surface_height(point,r,0u));
}
"#.to_owned();
    for name in ["surface_layer", "density_surface_height", "lake_surface_layer", "lake_surface_height"] {
        source.push_str(&function(include_str!("../program.wgsl"), name));
        source.push('\n');
    }
    if compact {
        source.push_str(&function(include_str!("../program/compact_calls.wgsl"), "density_surface_height")
            .replace("fn density_surface_height(", "fn compact_surface_height("));
        // Exercise both the ordinary lattice and lake-probe reductions, with
        // aligned/clipped/empty bounds and composition selected by the fixture.
        let probe = "select(0u,0xffffffffu,(id.x&8u)!=0u)";
        source = source.replace(
            "density_surface_height(point,r,0u),lake_surface_height(point,r,0u)",
            &format!("density_surface_height(point,r,{probe}),compact_surface_height(point,r,{probe})"));
    }
    let module = gpu.device.create_shader_module(wgpu::ShaderModuleDescriptor {
        label: Some("lake height parity"), source: wgpu::ShaderSource::Wgsl(source.into()),
    });
    let pipeline = gpu.device.create_compute_pipeline(&wgpu::ComputePipelineDescriptor {
        label: Some("lake height parity"), layout: None, module: &module,
        entry_point: Some("parity"), compilation_options: Default::default(), cache: None,
    });
    let buffer = |size| gpu.device.create_buffer(&wgpu::BufferDescriptor {
        label: None, size, usage: wgpu::BufferUsages::STORAGE | wgpu::BufferUsages::COPY_SRC, mapped_at_creation: false,
    });
    let samples = buffer(256 * 36 * 4);
    let results = buffer(256 * 8);
    let bindings = gpu.device.create_bind_group(&wgpu::BindGroupDescriptor {
        label: None, layout: &pipeline.get_bind_group_layout(0), entries: &[
            wgpu::BindGroupEntry { binding: 0, resource: samples.as_entire_binding() },
            wgpu::BindGroupEntry { binding: 1, resource: results.as_entire_binding() },
        ],
    });
    let output = gpu.readback_buffer("lake parity", 256 * 8);
    let mut encoder = gpu.device.create_command_encoder(&Default::default());
    {
        let mut pass = encoder.begin_compute_pass(&Default::default());
        pass.set_pipeline(&pipeline);
        pass.set_bind_group(0, &bindings, &[]);
        pass.dispatch_workgroups(4, 1, 1);
    }
    encoder.copy_buffer_to_buffer(&results, 0, &output, 0, 256 * 8);
    gpu.queue.submit([encoder.finish()]);
    let mut mapping = Mapping::new(&output, 256 * 8);
    gpu.device.poll(wgpu::PollType::Wait { submission_index: None, timeout: Some(Duration::from_secs(60)) }).unwrap();
    mapping.check().unwrap();
    let data = output.slice(..).get_mapped_range().unwrap();
    for (case, pair) in bytemuck::cast_slice::<u8, f32>(&data).chunks_exact(2).enumerate() {
        assert_eq!(pair[0].to_bits(), pair[1].to_bits(), "case {case}: {pair:?}");
    }
}
