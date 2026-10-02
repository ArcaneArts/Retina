use crate::{
    COLUMNS, GpuRequest, MAX_BATCH,
    profile::{Column, WorldProfile},
};
use std::{collections::HashMap, sync::mpsc, time::Duration};
use wgpu::util::DeviceExt;

pub(crate) struct Gpu {
    device: wgpu::Device,
    queue: wgpu::Queue,
    sites_pipeline: wgpu::ComputePipeline,
    columns_pipeline: wgpu::ComputePipeline,
    layout: wgpu::BindGroupLayout,
    profiles: HashMap<u32, (wgpu::Buffer, wgpu::BindGroup)>,
    requests: wgpu::Buffer,
    output: wgpu::Buffer,
    sites: wgpu::Buffer,
    readback: wgpu::Buffer,
    pub(crate) backend: String,
}

impl Gpu {
    pub(crate) fn new() -> Result<Self, String> {
        let instance = wgpu::Instance::new(wgpu::InstanceDescriptor {
            backends: wgpu::Backends::METAL | wgpu::Backends::VULKAN | wgpu::Backends::DX12,
            ..wgpu::InstanceDescriptor::new_without_display_handle()
        });
        let adapter = pollster::block_on(instance.request_adapter(&wgpu::RequestAdapterOptions {
            power_preference: wgpu::PowerPreference::HighPerformance,
            compatible_surface: None,
            force_fallback_adapter: false,
            apply_limit_buckets: false,
        }))
        .map_err(|e| format!("cannot create a GPU compute adapter: {e}"))?;
        let info = adapter.get_info();
        let backend = format!("{:?}: {}", info.backend, info.name);
        let (device, queue) = pollster::block_on(adapter.request_device(&wgpu::DeviceDescriptor {
            label: Some("Retina terrain device"),
            ..Default::default()
        }))
        .map_err(|e| format!("cannot create the GPU compute device: {e}"))?;
        let shader = device.create_shader_module(wgpu::ShaderModuleDescriptor {
            label: Some("Retina GPU biomes and simplex"),
            source: wgpu::ShaderSource::Wgsl(include_str!("simplex.wgsl").into()),
        });
        let layout = device.create_bind_group_layout(&wgpu::BindGroupLayoutDescriptor {
            label: Some("Retina world buffers"),
            entries: &(0..4)
                .map(|binding| wgpu::BindGroupLayoutEntry {
                    binding,
                    visibility: wgpu::ShaderStages::COMPUTE,
                    ty: wgpu::BindingType::Buffer {
                        ty: wgpu::BufferBindingType::Storage {
                            read_only: binding == 0 || binding == 2,
                        },
                        has_dynamic_offset: false,
                        min_binding_size: None,
                    },
                    count: None,
                })
                .collect::<Vec<_>>(),
        });
        let pipeline_layout = device.create_pipeline_layout(&wgpu::PipelineLayoutDescriptor {
            label: Some("Retina two-stage terrain"),
            bind_group_layouts: &[Some(&layout)],
            immediate_size: 0,
        });
        let pipeline = |entry| {
            device.create_compute_pipeline(&wgpu::ComputePipelineDescriptor {
                label: Some(entry),
                layout: Some(&pipeline_layout),
                module: &shader,
                entry_point: Some(entry),
                compilation_options: Default::default(),
                cache: None,
            })
        };
        let sites_pipeline = pipeline("biome_sites");
        let columns_pipeline = pipeline("main");
        let requests = device.create_buffer_init(&wgpu::util::BufferInitDescriptor {
            label: Some("Retina batched descriptors"),
            contents: &vec![0; MAX_BATCH * std::mem::size_of::<GpuRequest>()],
            usage: wgpu::BufferUsages::STORAGE | wgpu::BufferUsages::COPY_DST,
        });
        let buffer = |label, size, usage| {
            device.create_buffer(&wgpu::BufferDescriptor {
                label: Some(label),
                size,
                usage,
                mapped_at_creation: false,
            })
        };
        let size = (MAX_BATCH * COLUMNS * std::mem::size_of::<Column>()) as u64;
        let output = buffer(
            "Retina GPU columns",
            size,
            wgpu::BufferUsages::STORAGE | wgpu::BufferUsages::COPY_SRC,
        );
        let readback = buffer(
            "Retina column readback",
            size,
            wgpu::BufferUsages::COPY_DST | wgpu::BufferUsages::MAP_READ,
        );
        let sites = buffer(
            "Retina GPU-only biome sites",
            (MAX_BATCH * 144 * 32) as u64,
            wgpu::BufferUsages::STORAGE,
        );
        let mut gpu = Self {
            device,
            queue,
            sites_pipeline,
            columns_pipeline,
            layout,
            profiles: HashMap::new(),
            requests,
            output,
            sites,
            readback,
            backend,
        };
        gpu.add_profile(0, &vec![0; 656]);
        Ok(gpu)
    }

    fn add_profile(&mut self, id: u32, bytes: &[u8]) {
        let buffer = self
            .device
            .create_buffer_init(&wgpu::util::BufferInitDescriptor {
                label: Some("Retina resident world profile"),
                contents: bytes,
                usage: wgpu::BufferUsages::STORAGE,
            });
        let group = self.device.create_bind_group(&wgpu::BindGroupDescriptor {
            label: Some("Retina world profile"),
            layout: &self.layout,
            entries: &[
                wgpu::BindGroupEntry {
                    binding: 0,
                    resource: self.requests.as_entire_binding(),
                },
                wgpu::BindGroupEntry {
                    binding: 1,
                    resource: self.output.as_entire_binding(),
                },
                wgpu::BindGroupEntry {
                    binding: 2,
                    resource: buffer.as_entire_binding(),
                },
                wgpu::BindGroupEntry {
                    binding: 3,
                    resource: self.sites.as_entire_binding(),
                },
            ],
        });
        self.profiles.insert(id, (buffer, group));
    }

    pub(crate) fn sample(
        &mut self,
        requests: &[GpuRequest],
        profile: Option<&WorldProfile>,
    ) -> Result<Vec<Column>, String> {
        let profile_id = requests[0].profile;
        if !self.profiles.contains_key(&profile_id) {
            self.add_profile(
                profile_id,
                &profile.ok_or("missing GPU world profile")?.gpu_bytes(),
            );
        }
        self.queue
            .write_buffer(&self.requests, 0, bytemuck::cast_slice(requests));
        let group = &self.profiles[&profile_id].1;
        let count = if requests[0].tile_side > 0 {
            MAX_BATCH
        } else {
            requests.len()
        };
        let mut encoder = self
            .device
            .create_command_encoder(&wgpu::CommandEncoderDescriptor {
                label: Some("Retina terrain batch"),
            });
        if profile_id != 0 {
            let mut pass = encoder.begin_compute_pass(&wgpu::ComputePassDescriptor {
                label: Some("Retina biome site pass"),
                timestamp_writes: None,
            });
            pass.set_pipeline(&self.sites_pipeline);
            pass.set_bind_group(0, group, &[]);
            pass.dispatch_workgroups(3, requests.len() as u32, 1);
        }
        {
            let mut pass = encoder.begin_compute_pass(&wgpu::ComputePassDescriptor {
                label: Some("Retina interpolated column pass"),
                timestamp_writes: None,
            });
            pass.set_pipeline(&self.columns_pipeline);
            pass.set_bind_group(0, group, &[]);
            pass.dispatch_workgroups(4, count as u32, 1);
        }
        let size = (count * COLUMNS * std::mem::size_of::<Column>()) as u64;
        encoder.copy_buffer_to_buffer(&self.output, 0, &self.readback, 0, size);
        let submission = self.queue.submit([encoder.finish()]);
        let slice = self.readback.slice(..size);
        let (sender, receiver) = mpsc::channel();
        slice.map_async(wgpu::MapMode::Read, move |result| {
            let _ = sender.send(result);
        });
        self.device
            .poll(wgpu::PollType::Wait {
                submission_index: Some(submission),
                timeout: Some(Duration::from_secs(30)),
            })
            .map_err(|e| format!("GPU poll failed: {e}"))?;
        receiver
            .recv_timeout(Duration::from_secs(30))
            .map_err(|e| format!("GPU readback timed out: {e}"))?
            .map_err(|e| format!("GPU readback failed: {e}"))?;
        let columns = {
            let mapped = slice
                .get_mapped_range()
                .map_err(|e| format!("mapped GPU buffer unavailable: {e}"))?;
            bytemuck::cast_slice::<u8, Column>(&mapped).to_vec()
        };
        self.readback.unmap();
        Ok(columns)
    }
}
