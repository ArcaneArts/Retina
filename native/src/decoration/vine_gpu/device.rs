//! One reusable, bounded batch arena on the terrain device. The mutex owner
//! retains every buffer until its submission has completed or mapping is cancelled.
use super::*;
use std::{
    collections::HashMap,
    num::NonZeroU64,
    sync::mpsc,
    time::{Duration, Instant},
};

#[derive(Default, serde::Serialize)]
pub(crate) struct Stats {
    pub anchors: u64,
    pub fallbacks: u64,
    pub batches: u64,
    pub upload_bytes: u64,
    pub readback_bytes: u64,
    pub arena_bytes: u64,
    pub host_nanos: u64,
    pub substrate_pack_nanos: u64,
    pub device_nanos: [u64; 3],
    pub invalid_timestamps: u64,
}
impl Stats {
    pub(super) fn add(&mut self, b: Self) {
        self.anchors += b.anchors;
        self.fallbacks += b.fallbacks;
        self.batches += b.batches;
        self.upload_bytes += b.upload_bytes;
        self.readback_bytes += b.readback_bytes;
        self.arena_bytes = self.arena_bytes.max(b.arena_bytes);
        self.host_nanos += b.host_nanos;
        self.substrate_pack_nanos += b.substrate_pack_nanos;
        self.invalid_timestamps += b.invalid_timestamps;
        for (a, b) in self.device_nanos.iter_mut().zip(b.device_nanos) {
            *a += b;
        }
    }
}
struct Pipelines {
    layout: wgpu::BindGroupLayout,
    stages: [wgpu::ComputePipeline; 3],
    queries: Option<wgpu::QuerySet>,
    resolve: Option<wgpu::Buffer>,
}
struct Buffers {
    storage: [wgpu::Buffer; 6],
    read: wgpu::Buffer,
    sizes: [u64; 7],
}
pub(crate) struct Gpu {
    device: wgpu::Device,
    queue: wgpu::Queue,
    enabled: bool,
    programs: HashMap<u32, (i32, u32, Programs)>,
    pipelines: Option<Pipelines>,
    buffers: Option<Buffers>,
}
impl Gpu {
    pub(crate) fn new(device: wgpu::Device, queue: wgpu::Queue, backend: &str) -> Self {
        Self {
            device,
            queue,
            enabled: match std::env::var("RETINA_GPU_VINES").as_deref() {
                Ok("0") => false,
                Ok("1") => true,
                _ => backend.starts_with("Metal:"),
            },
            programs: HashMap::new(),
            pipelines: None,
            buffers: None,
        }
    }
    pub(crate) fn programs(
        &mut self,
        profile: &WorldProfile,
        request: ChunkRequest,
    ) -> Option<Programs> {
        if !self.enabled {
            return None;
        }
        let entry = self
            .programs
            .entry(request.reserved)
            .or_insert_with(|| (request.min_y, request.height, compile(profile, request)));
        if (entry.0, entry.1) != (request.min_y, request.height) {
            *entry = (request.min_y, request.height, compile(profile, request));
        }
        let programs = entry.2.clone();
        if !programs.iter().any(Option::is_some) {
            return None;
        }
        // Compile once on the first planning request for this eligible profile,
        // including sparse warmup terrain, rather than at its first dense batch.
        if let Err(error) = self.initialize() {
            eprintln!("Retina vine compiler keeps CPU planning: {error}");
            self.enabled = false;
            return None;
        }
        Some(programs)
    }
    #[cfg(test)]
    pub(crate) fn enable_for_test(&mut self, enabled: bool) {
        self.enabled = enabled;
    }
    fn initialize(&mut self) -> Result<(), String> {
        if self.pipelines.is_some() {
            return Ok(());
        }
        let scope = self.device.push_error_scope(wgpu::ErrorFilter::Validation);
        let layout = self
            .device
            .create_bind_group_layout(&wgpu::BindGroupLayoutDescriptor {
                label: Some("Retina vine programs"),
                entries: &(0..6)
                    .map(|binding| wgpu::BindGroupLayoutEntry {
                        binding,
                        visibility: wgpu::ShaderStages::COMPUTE,
                        ty: wgpu::BindingType::Buffer {
                            ty: wgpu::BufferBindingType::Storage {
                                read_only: binding < 2,
                            },
                            has_dynamic_offset: false,
                            min_binding_size: None,
                        },
                        count: None,
                    })
                    .collect::<Vec<_>>(),
            });
        let shader = self
            .device
            .create_shader_module(wgpu::ShaderModuleDescriptor {
                label: Some("Retina vine programs"),
                source: wgpu::ShaderSource::Wgsl(include_str!("vines.wgsl").into()),
            });
        let pl = self
            .device
            .create_pipeline_layout(&wgpu::PipelineLayoutDescriptor {
                label: Some("Retina vine programs"),
                bind_group_layouts: &[Some(&layout)],
                immediate_size: 0,
            });
        let stages = ["prepare", "columns", "gather"].map(|entry| {
            self.device
                .create_compute_pipeline(&wgpu::ComputePipelineDescriptor {
                    label: Some(entry),
                    layout: Some(&pl),
                    module: &shader,
                    entry_point: Some(entry),
                    compilation_options: Default::default(),
                    cache: None,
                })
        });
        if let Some(e) = pollster::block_on(scope.pop()) {
            return Err(e.to_string());
        }
        let queries = self
            .device
            .features()
            .contains(wgpu::Features::TIMESTAMP_QUERY)
            .then(|| {
                self.device.create_query_set(&wgpu::QuerySetDescriptor {
                    label: Some("Retina vine timestamps"),
                    ty: wgpu::QueryType::Timestamp,
                    count: 6,
                })
            });
        let resolve = queries.as_ref().map(|_| {
            self.buffer(
                48,
                wgpu::BufferUsages::QUERY_RESOLVE | wgpu::BufferUsages::COPY_SRC,
            )
        });
        self.pipelines = Some(Pipelines {
            layout,
            stages,
            queries,
            resolve,
        });
        Ok(())
    }
    fn buffer(&self, size: u64, usage: wgpu::BufferUsages) -> wgpu::Buffer {
        self.device.create_buffer(&wgpu::BufferDescriptor {
            label: Some("Retina vine arena"),
            size,
            usage,
            mapped_at_creation: false,
        })
    }
    fn reserve(&mut self, sizes: [u64; 7]) -> Result<(), String> {
        let limits = self.device.limits();
        if sizes[..6]
            .iter()
            .any(|&n| n > limits.max_storage_buffer_binding_size as u64)
            || sizes.iter().any(|&n| n > limits.max_buffer_size)
            || sizes.iter().sum::<u64>() > 128 * 1024 * 1024
        {
            return Err("vine batch exceeds bounded device arena".into());
        }
        // Retain one arena, not one per region. Resize only buffers that grow.
        let usage = |i| {
            wgpu::BufferUsages::STORAGE
                | if i == 3 {
                    wgpu::BufferUsages::COPY_SRC
                } else {
                    wgpu::BufferUsages::COPY_DST
                }
        };
        if let Some(mut buffers) = self.buffers.take() {
            for i in 0..7 {
                if sizes[i] > buffers.sizes[i] {
                    if i < 6 {
                        buffers.storage[i] = self.buffer(sizes[i], usage(i));
                    } else {
                        buffers.read = self.buffer(
                            sizes[i],
                            wgpu::BufferUsages::MAP_READ | wgpu::BufferUsages::COPY_DST,
                        );
                    }
                    buffers.sizes[i] = sizes[i];
                }
            }
            // Different batches can have different high-water shapes. Bound
            // the retained combination as well as each individual request.
            if buffers.sizes.iter().sum::<u64>() <= 128 * 1024 * 1024 {
                self.buffers = Some(buffers);
                return Ok(());
            }
        }
        self.buffers = Some(Buffers {
            storage: std::array::from_fn(|i| self.buffer(sizes[i], usage(i))),
            read: self.buffer(
                sizes[6],
                wgpu::BufferUsages::MAP_READ | wgpu::BufferUsages::COPY_DST,
            ),
            sizes,
        });
        Ok(())
    }
    pub(super) fn run(
        &mut self,
        input: &Input,
        request: ChunkRequest,
        program: &Program,
        jobs: &[(i32, i32, &Overlay)],
    ) -> (Vec<Option<Vec<WorldBlock>>>, Stats) {
        let start = Instant::now();
        let mut stats = Stats::default();
        let mut output = Vec::with_capacity(jobs.len());
        if !self.enabled || self.initialize().is_err() {
            self.enabled = false;
            stats.fallbacks = jobs.len() as u64;
            return ((0..jobs.len()).map(|_| None).collect(), stats);
        }
        let mut uploaded = false;
        for jobs in jobs.chunks(BATCH) {
            stats.anchors += jobs.len() as u64;
            let anchors = input.anchors(request, program, jobs);
            match self.batch(input, &anchors, jobs.len(), &mut uploaded, &mut stats) {
                Ok(blocks) => {
                    stats.fallbacks += blocks.iter().filter(|b| b.is_none()).count() as u64;
                    output.extend(blocks);
                }
                Err(error) => {
                    eprintln!("Retina GPU vine batch reverted to CPU: {error}");
                    self.buffers = None;
                    self.enabled = false;
                    stats.fallbacks += jobs.len() as u64;
                    output.extend((0..jobs.len()).map(|_| None));
                }
            }
        }
        stats.host_nanos = start.elapsed().as_nanos() as u64;
        (output, stats)
    }
    fn batch(
        &mut self,
        input: &Input,
        anchors: &[u32],
        count: usize,
        uploaded: &mut bool,
        stats: &mut Stats,
    ) -> Result<Vec<Option<Vec<WorldBlock>>>, String> {
        if !self.enabled {
            return Err("vine device disabled after an earlier batch failure".into());
        }
        let stamp = if self.pipelines.as_ref().unwrap().queries.is_some() {
            48
        } else {
            0
        };
        let n = count as u64;
        let capacity = input.words[5] as usize;
        let out = n * (4 + capacity as u64 * 5) * 4;
        let sizes = [
            input.words.len() as u64 * 4,
            anchors.len() as u64 * 4,
            n * 256 * input.height as u64 * 4,
            out,
            n * input.attempts as u64 * 16,
            n * (1 + 256 * (1 + QUEUE as u64)) * 4,
            out + stamp,
        ];
        // If resizing replaces the substrate buffer, it must be uploaded again.
        let old_data = self.buffers.as_ref().map(|b| b.sizes[0]);
        let rebuilding = self.buffers.as_ref().is_none_or(|b| {
            std::array::from_fn::<_, 7, _>(|i| b.sizes[i].max(sizes[i]))
                .iter()
                .sum::<u64>()
                > 128 * 1024 * 1024
        });
        self.reserve(sizes)?;
        if rebuilding || old_data.is_none_or(|n| n < sizes[0]) {
            *uploaded = false;
        }
        let b = self.buffers.as_ref().unwrap();
        let p = self.pipelines.as_ref().unwrap();
        stats.arena_bytes = stats.arena_bytes.max(b.sizes.iter().sum());
        if !*uploaded {
            self.queue
                .write_buffer(&b.storage[0], 0, bytemuck::cast_slice(&input.words));
            stats.upload_bytes += sizes[0];
            *uploaded = true;
        }
        self.queue
            .write_buffer(&b.storage[1], 0, bytemuck::cast_slice(anchors));
        stats.upload_bytes += sizes[1];
        let bind = self.device.create_bind_group(&wgpu::BindGroupDescriptor {
            label: Some("Retina vine batch"),
            layout: &p.layout,
            entries: &b
                .storage
                .iter()
                .enumerate()
                .map(|(i, b)| wgpu::BindGroupEntry {
                    binding: i as u32,
                    resource: wgpu::BindingResource::Buffer(wgpu::BufferBinding {
                        buffer: b,
                        offset: 0,
                        size: NonZeroU64::new(sizes[i]),
                    }),
                })
                .collect::<Vec<_>>(),
        });
        let mut enc = self.device.create_command_encoder(&Default::default());
        enc.clear_buffer(&b.storage[2], 0, Some(sizes[2]));
        enc.clear_buffer(&b.storage[5], 0, Some(sizes[5]));
        for i in 0..3 {
            let mut pass = enc.begin_compute_pass(&wgpu::ComputePassDescriptor {
                label: Some("Retina vine stage"),
                timestamp_writes: p
                    .queries
                    .as_ref()
                    .map(|q| wgpu::ComputePassTimestampWrites {
                        query_set: q,
                        beginning_of_pass_write_index: Some(i * 2),
                        end_of_pass_write_index: Some(i * 2 + 1),
                    }),
            });
            pass.set_pipeline(&p.stages[i as usize]);
            pass.set_bind_group(0, &bind, &[]);
            match i {
                0 => pass.dispatch_workgroups(input.attempts.div_ceil(64), count as u32, 1),
                1 => pass.dispatch_workgroups(4, count as u32, 1),
                _ => pass.dispatch_workgroups(count as u32, 1, 1),
            }
        }
        enc.copy_buffer_to_buffer(&b.storage[3], 0, &b.read, 0, out);
        if let (Some(q), Some(resolve)) = (&p.queries, &p.resolve) {
            enc.resolve_query_set(q, 0..6, resolve, 0);
            enc.copy_buffer_to_buffer(resolve, 0, &b.read, out, 48);
        }
        let start = Instant::now();
        let index = self.queue.submit([enc.finish()]);
        stats.batches += 1;
        stats.readback_bytes += sizes[6];
        let (tx, rx) = mpsc::channel();
        b.read
            .slice(..sizes[6])
            .map_async(wgpu::MapMode::Read, move |r| {
                let _ = tx.send(r);
            });
        let result = (|| {
            self.device
                .poll(wgpu::PollType::Wait {
                    submission_index: Some(index),
                    timeout: Some(Duration::from_secs(10)),
                })
                .map_err(|e| e.to_string())?;
            rx.recv_timeout(Duration::from_secs(1))
                .map_err(|e| e.to_string())?
                .map_err(|e| e.to_string())?;
            let view = b
                .read
                .slice(..sizes[6])
                .get_mapped_range()
                .map_err(|e| e.to_string())?;
            if stamp > 0 {
                let ticks: &[u64] = bytemuck::cast_slice(&view[out as usize..]);
                let mut times = [0; 3];
                let elapsed = start.elapsed().as_nanos() as u64;
                for i in 0..3 {
                    let a = ticks[i * 2];
                    let z = ticks[i * 2 + 1];
                    let ns = (z.saturating_sub(a) as f64 * self.queue.get_timestamp_period() as f64)
                        as u64;
                    if a > 0 && z >= a && ns <= elapsed {
                        times[i] = ns;
                    } else {
                        stats.invalid_timestamps += 1;
                    }
                }
                if times.iter().sum::<u64>() <= elapsed {
                    for (i, t) in times.into_iter().enumerate() {
                        stats.device_nanos[i] += t;
                    }
                } else {
                    stats.invalid_timestamps += 3;
                }
            }
            let words: &[u32] = bytemuck::cast_slice(&view[..out as usize]);
            Ok(words
                .chunks_exact(4 + capacity * 5)
                .map(|words| {
                    if words[1] != 0 || words[0] as usize > capacity {
                        return None;
                    }
                    Some(
                        words[4..4 + words[0] as usize * 5]
                            .chunks_exact(5)
                            .map(|v| WorldBlock {
                                x: v[0] as i32,
                                y: v[1] as i32,
                                z: v[2] as i32,
                                material: v[3] as u16,
                                upper: 0,
                                role: FEATURE,
                            })
                            .collect(),
                    )
                })
                .collect())
        })();
        b.read.unmap();
        result
    }
}
