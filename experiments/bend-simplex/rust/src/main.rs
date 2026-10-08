//! Standalone benchmark. Production generator and saved-world format are unchanged.
use rayon::prelude::*;
use serde_json::json;
use std::{
    hint::black_box,
    sync::mpsc,
    time::{Duration, Instant},
};
fn hash(mut v: u32) -> u32 {
    v = (v ^ (v >> 16)).wrapping_mul(0x7feb352d);
    v = (v ^ (v >> 15)).wrapping_mul(0x846ca68b);
    v ^ (v >> 16)
}
fn corner(cx: i32, cz: i32, dx: f32, dz: f32, low: u32) -> f32 {
    let h = hash(
        (cx as u32).wrapping_mul(0x9e3779b9)
            ^ (cz as u32).wrapping_mul(0x85ebca6b)
            ^ low
            ^ hash(987654321),
    );
    let (gx, gz) = match h & 7 {
        0 => (1., 1.),
        1 => (-1., 1.),
        2 => (1., -1.),
        3 => (-1., -1.),
        4 => (1., 0.),
        5 => (-1., 0.),
        6 => (0., 1.),
        _ => (0., -1.),
    };
    let t = (0.5 - (dx * dx + dz * dz)).max(0.);
    let t2 = t * t;
    t2 * t2 * (gx * dx + gz * dz)
}
fn simplex(x: f32, z: f32, low: u32) -> f32 {
    let skew = (x + z) * 0.3660254037844386_f32;
    let cx = (x + skew).floor() as i32;
    let cz = (z + skew).floor() as i32;
    let unskew = (cx + cz) as f32 * 0.2113248654051871_f32;
    let dx = x - (cx as f32 - unskew);
    let dz = z - (cz as f32 - unskew);
    let (sx, sz) = if dx > dz { (1, 0) } else { (0, 1) };
    70. * (corner(cx, cz, dx, dz, low)
        + corner(
            cx + sx,
            cz + sz,
            dx - sx as f32 + 0.2113248654051871_f32,
            dz - sz as f32 + 0.2113248654051871_f32,
            low,
        )
        + corner(
            cx + 1,
            cz + 1,
            dx - 1. + 0.4226497308103742_f32,
            dz - 1. + 0.4226497308103742_f32,
            low,
        ))
}
fn sample(i: usize) -> f32 {
    let mut x = ((i & 511) as f32 - 4096.) * 0.0035;
    let mut z = ((i >> 9) as f32 + 8192.) * 0.0035;
    let mut sum = 0.;
    let mut weight = 1.;
    for octave in 0..4 {
        sum += simplex(x, z, 123456789 + octave * 1013) * weight;
        x *= 2.;
        z *= 2.;
        weight *= 0.5;
    }
    sum / 1.875
}
fn checksum(values: &[f32]) -> u32 {
    values
        .iter()
        .fold(0u32, |a, &v| a.wrapping_add(((v + 2.) * 65536.) as u32))
}
fn ms(t: Instant) -> f64 {
    t.elapsed().as_secs_f64() * 1000.
}
struct Gpu {
    d: wgpu::Device,
    q: wgpu::Queue,
    p: wgpu::ComputePipeline,
    b: wgpu::BindGroup,
    out: wgpu::Buffer,
    read: wgpu::Buffer,
    queries: wgpu::QuerySet,
    resolve: wgpu::Buffer,
    times: wgpu::Buffer,
    n: usize,
    backend: String,
}
impl Gpu {
    fn new(n: usize) -> Self {
        let instance = wgpu::Instance::new(wgpu::InstanceDescriptor {
            backends: wgpu::Backends::METAL,
            ..wgpu::InstanceDescriptor::new_without_display_handle()
        });
        let a = pollster::block_on(instance.request_adapter(&wgpu::RequestAdapterOptions {
            power_preference: wgpu::PowerPreference::HighPerformance,
            compatible_surface: None,
            force_fallback_adapter: false,
            apply_limit_buckets: false,
        }))
        .unwrap();
        let info = a.get_info();
        let backend = format!("{:?}: {}", info.backend, info.name);
        let (d, q) = pollster::block_on(a.request_device(&wgpu::DeviceDescriptor {
            required_features: wgpu::Features::TIMESTAMP_QUERY,
            required_limits: wgpu::Limits {
                max_storage_buffer_binding_size: a.limits().max_storage_buffer_binding_size,
                max_buffer_size: a.limits().max_buffer_size,
                ..Default::default()
            },
            ..Default::default()
        }))
        .unwrap();
        let buffer = |size, usage| {
            d.create_buffer(&wgpu::BufferDescriptor {
                label: None,
                size,
                usage,
                mapped_at_creation: false,
            })
        };
        let out = buffer(
            n as u64 * 4,
            wgpu::BufferUsages::STORAGE | wgpu::BufferUsages::COPY_SRC,
        );
        let read = buffer(
            n as u64 * 4,
            wgpu::BufferUsages::MAP_READ | wgpu::BufferUsages::COPY_DST,
        );
        let queries = d.create_query_set(&wgpu::QuerySetDescriptor {
            label: None,
            ty: wgpu::QueryType::Timestamp,
            count: 2,
        });
        let resolve = buffer(
            16,
            wgpu::BufferUsages::QUERY_RESOLVE | wgpu::BufferUsages::COPY_SRC,
        );
        let times = buffer(
            16,
            wgpu::BufferUsages::MAP_READ | wgpu::BufferUsages::COPY_DST,
        );
        // Compile the exact production functions, not a hand-optimized replacement.
        let production = include_str!("../../../../native/src/simplex.wgsl");
        let functions = &production[production.find("fn mix_hash(").unwrap()
            ..production.find("fn registry_noise(").unwrap()];
        let code = format!(
            "{functions}\n@group(0) @binding(0) var<storage,read_write> output:array<f32>;\n@compute @workgroup_size(256) fn main(@builtin(global_invocation_id) id:vec3<u32>) {{let i=id.x;if i>={n}u {{return;}} let x=(f32(i&511u)-4096.0)*0.0035;let z=(f32(i>>9u)+8192.0)*0.0035;output[i]=fbm(vec2<f32>(x,z),123456789u,987654321u);}}"
        );
        let shader = d.create_shader_module(wgpu::ShaderModuleDescriptor {
            label: Some("Retina production simplex/fBm"),
            source: wgpu::ShaderSource::Wgsl(code.into()),
        });
        let p = d.create_compute_pipeline(&wgpu::ComputePipelineDescriptor {
            label: None,
            layout: None,
            module: &shader,
            entry_point: Some("main"),
            compilation_options: Default::default(),
            cache: None,
        });
        let b = d.create_bind_group(&wgpu::BindGroupDescriptor {
            label: None,
            layout: &p.get_bind_group_layout(0),
            entries: &[wgpu::BindGroupEntry {
                binding: 0,
                resource: out.as_entire_binding(),
            }],
        });
        Self {
            d,
            q,
            p,
            b,
            out,
            read,
            queries,
            resolve,
            times,
            n,
            backend,
        }
    }
    fn run(&self) -> (Vec<f32>, f64) {
        let mut e = self.d.create_command_encoder(&Default::default());
        {
            let mut p = e.begin_compute_pass(&wgpu::ComputePassDescriptor {
                label: None,
                timestamp_writes: Some(wgpu::ComputePassTimestampWrites {
                    query_set: &self.queries,
                    beginning_of_pass_write_index: Some(0),
                    end_of_pass_write_index: Some(1),
                }),
            });
            p.set_pipeline(&self.p);
            p.set_bind_group(0, &self.b, &[]);
            p.dispatch_workgroups((self.n as u32).div_ceil(256), 1, 1);
        }
        e.copy_buffer_to_buffer(&self.out, 0, &self.read, 0, self.n as u64 * 4);
        e.resolve_query_set(&self.queries, 0..2, &self.resolve, 0);
        e.copy_buffer_to_buffer(&self.resolve, 0, &self.times, 0, 16);
        let submit = self.q.submit([e.finish()]);
        let (tx, rx) = mpsc::channel();
        self.read
            .slice(..)
            .map_async(wgpu::MapMode::Read, move |r| tx.send(r).unwrap());
        let (tt, tr) = mpsc::channel();
        self.times
            .slice(..)
            .map_async(wgpu::MapMode::Read, move |r| tt.send(r).unwrap());
        self.d
            .poll(wgpu::PollType::Wait {
                submission_index: Some(submit),
                timeout: Some(Duration::from_secs(30)),
            })
            .unwrap();
        rx.recv().unwrap().unwrap();
        tr.recv().unwrap().unwrap();
        let values =
            bytemuck::cast_slice::<u8, f32>(&self.read.slice(..).get_mapped_range().unwrap())
                .to_vec();
        let ts = bytemuck::cast_slice::<u8, u64>(&self.times.slice(..).get_mapped_range().unwrap())
            .to_vec();
        self.read.unmap();
        self.times.unmap();
        (
            values,
            (ts[1] - ts[0]) as f64 * self.q.get_timestamp_period() as f64 / 1e6,
        )
    }
}
fn main() {
    let args: Vec<String> = std::env::args().collect();
    let n: usize = args[1].parse().unwrap();
    let repeats: usize = args[2].parse().unwrap();
    let mode = &args[3];
    let threads: usize = args.get(4).map(|v| v.parse().unwrap()).unwrap_or(4);
    if mode == "dump" {
        println!("{}", json!((0..n).map(sample).collect::<Vec<_>>()));
        return;
    }
    let setup = Instant::now();
    let pool = rayon::ThreadPoolBuilder::new()
        .num_threads(threads)
        .build()
        .unwrap();
    let gpu = (mode == "gpu").then(|| Gpu::new(n));
    let setup_ms = ms(setup);
    for k in 0..repeats + 3 {
        let t = Instant::now();
        let (v, device_ms) = if let Some(g) = &gpu {
            let (v, t) = g.run();
            (v, Some(t))
        } else {
            let mut v = vec![0.; n];
            if threads == 1 {
                for (i, x) in v.iter_mut().enumerate() {
                    *x = sample(i)
                }
            } else {
                pool.install(|| {
                    v.par_iter_mut()
                        .enumerate()
                        .for_each(|(i, x)| *x = sample(i))
                });
            }
            (v, None)
        };
        let materialize_ms = ms(t);
        let sum = black_box(checksum(&v));
        if k == 0 {
            let max_error = v
                .iter()
                .enumerate()
                .map(|(i, &x)| (x - sample(i)).abs())
                .fold(0f32, f32::max);
            let indices = [0, 1, 7, 255, 256, 511, 12345, n - 1];
            println!(
                "{}",
                json!({"validation":true,"mode":mode,"n":n,"threads":threads,"setup_ms":setup_ms,"backend":gpu.as_ref().map(|g|&g.backend),"max_error_vs_rust":max_error,"points":indices.map(|i|json!([i%n,v[i%n]])),"checksum":sum})
            );
        }
        drop(v);
        let total_ms = ms(t);
        if k >= 3 {
            println!(
                "{}",
                json!({"mode":mode,"n":n,"threads":threads,"materialize_ms":materialize_ms,"total_ms":total_ms,"device_ms":device_ms,"checksum":sum})
            );
        }
    }
}
