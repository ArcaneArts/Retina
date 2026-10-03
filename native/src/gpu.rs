use crate::timings;
use crate::{
    COLUMNS, GpuRequest, MAX_BATCH, MAX_TILES,
    profile::{Column, WorldProfile},
};
use std::{
    collections::HashMap,
    sync::mpsc,
    time::{Duration, Instant},
};
use wgpu::util::DeviceExt;

const SURFACE_METADATA_FLOATS: usize = 8 * if 20000 > MAX_BATCH * 25 {
    20000
} else {
    MAX_BATCH * 25
};

pub(crate) struct Gpu {
    device: wgpu::Device,
    queue: wgpu::Queue,
    sites_pipeline: wgpu::ComputePipeline,
    biome_queries_pipeline: wgpu::ComputePipeline,
    underground_queries_pipeline: wgpu::ComputePipeline,
    climate_pipeline: wgpu::ComputePipeline,
    cave_nodes_pipeline: wgpu::ComputePipeline,
    cave_exterior_pipeline: wgpu::ComputePipeline,
    cave_mask_pipeline: wgpu::ComputePipeline,
    cave_layout: wgpu::BindGroupLayout,
    cave_profiles: HashMap<u32, wgpu::Buffer>,
    cave_buffers: Option<CaveBuffers>,
    columns_pipeline: wgpu::ComputePipeline,
    height_pipeline: wgpu::ComputePipeline,
    density_pipeline: wgpu::ComputePipeline,
    lake_pipeline: wgpu::ComputePipeline,
    lake_candidates_pipeline: wgpu::ComputePipeline,
    lake_density_pipeline: wgpu::ComputePipeline,
    surface_pipeline: wgpu::ComputePipeline,
    height_nodes: wgpu::Buffer,
    layout: wgpu::BindGroupLayout,
    profiles: HashMap<u32, (wgpu::Buffer, wgpu::Buffer, wgpu::Buffer, wgpu::BindGroup)>,
    requests: wgpu::Buffer,
    output: wgpu::Buffer,
    sites: wgpu::Buffer,
    timestamp_support: bool,
    readback_pool: Vec<ReadbackSlot>,
    pipeline: std::sync::Arc<crate::pipeline::Metrics>,
    last_device_tick: Option<u64>,
    pub(crate) backend: String,
}

pub(crate) struct GpuSample {
    pub columns: Vec<Column>,
    pub mask: Option<crate::geology::CaveMask>,
    pub timings: timings::Snapshot,
}
struct Timestamps {
    queries: wgpu::QuerySet,
    resolve: wgpu::Buffer,
    readback: wgpu::Buffer,
}
impl Timestamps {
    fn writes(&self, pair: u32) -> wgpu::ComputePassTimestampWrites<'_> {
        wgpu::ComputePassTimestampWrites {
            query_set: &self.queries,
            beginning_of_pass_write_index: Some(pair * 2),
            end_of_pass_write_index: Some(pair * 2 + 1),
        }
    }
}
struct CaveBuffers {
    nodes: wgpu::Buffer,
    mask: wgpu::Buffer,
    nodes_size: u64,
    mask_size: u64,
}

impl Gpu {
    pub(crate) fn new(metrics: std::sync::Arc<crate::pipeline::Metrics>) -> Result<Self, String> {
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
            required_features: adapter.features() & wgpu::Features::TIMESTAMP_QUERY,
            required_limits: wgpu::Limits {
                max_storage_buffer_binding_size: adapter.limits().max_storage_buffer_binding_size,
                max_buffer_size: adapter.limits().max_buffer_size,
                ..Default::default()
            },
            ..Default::default()
        }))
        .map_err(|e| format!("cannot create the GPU compute device: {e}"))?;
        let shader = device.create_shader_module(wgpu::ShaderModuleDescriptor {
            label: Some("Retina GPU biomes and simplex"),
            source: wgpu::ShaderSource::Wgsl(
                format!(
                    "{}\n{}\n{}",
                    include_str!("simplex.wgsl"),
                    include_str!("program.wgsl"),
                    include_str!("noise3.wgsl")
                )
                .into(),
            ),
        });
        let layout = device.create_bind_group_layout(&wgpu::BindGroupLayoutDescriptor {
            label: Some("Retina world buffers"),
            entries: &(0..7)
                .map(|binding| wgpu::BindGroupLayoutEntry {
                    binding,
                    visibility: wgpu::ShaderStages::COMPUTE,
                    ty: wgpu::BindingType::Buffer {
                        ty: wgpu::BufferBindingType::Storage {
                            read_only: binding == 0 || binding == 2 || binding == 4 || binding == 5,
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
        let cave_shader = device.create_shader_module(wgpu::ShaderModuleDescriptor {
            label: Some("Retina GPU cave fields and mask"),
            source: wgpu::ShaderSource::Wgsl(
                format!(
                    "{}\n{}",
                    include_str!("caves.wgsl"),
                    include_str!("program.wgsl")
                )
                .into(),
            ),
        });
        let cave_layout = device.create_bind_group_layout(&wgpu::BindGroupLayoutDescriptor {
            label: Some("Retina cave buffers"),
            entries: &(0..8)
                .map(|binding| wgpu::BindGroupLayoutEntry {
                    binding,
                    visibility: wgpu::ShaderStages::COMPUTE,
                    ty: wgpu::BindingType::Buffer {
                        ty: wgpu::BufferBindingType::Storage {
                            read_only: binding < 3 || binding == 5 || binding == 7,
                        },
                        has_dynamic_offset: false,
                        min_binding_size: None,
                    },
                    count: None,
                })
                .collect::<Vec<_>>(),
        });
        let cave_pipeline_layout = device.create_pipeline_layout(&wgpu::PipelineLayoutDescriptor {
            label: Some("Retina cave stages"),
            bind_group_layouts: &[Some(&cave_layout)],
            immediate_size: 0,
        });
        let cave_pipeline = |entry| {
            device.create_compute_pipeline(&wgpu::ComputePipelineDescriptor {
                label: Some(entry),
                layout: Some(&cave_pipeline_layout),
                module: &cave_shader,
                entry_point: Some(entry),
                compilation_options: Default::default(),
                cache: None,
            })
        };
        let cave_nodes_pipeline = cave_pipeline("cave_nodes");
        let cave_exterior_pipeline = cave_pipeline("cave_exterior");
        let cave_mask_pipeline = cave_pipeline("cave_mask");
        let sites_pipeline = pipeline("biome_sites");
        let biome_queries_pipeline = pipeline("biome_queries");
        let climate_pipeline = pipeline("climate_nodes");
        let underground_queries_pipeline =
            device.create_compute_pipeline(&wgpu::ComputePipelineDescriptor {
                label: Some("Retina sparse underground biomes"),
                layout: Some(&cave_pipeline_layout),
                module: &cave_shader,
                entry_point: Some("underground_queries"),
                compilation_options: Default::default(),
                cache: None,
            });
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
        let size = (MAX_TILES * COLUMNS * std::mem::size_of::<Column>()) as u64;
        let output = buffer(
            "Retina GPU columns",
            size,
            wgpu::BufferUsages::STORAGE | wgpu::BufferUsages::COPY_SRC,
        );
        let sites = buffer(
            "Retina GPU-only biome sites",
            (MAX_BATCH * 144 * 32) as u64,
            wgpu::BufferUsages::STORAGE,
        );
        let height_pipeline = pipeline("height_nodes");
        let density_pipeline = pipeline("density_nodes");
        let lake_pipeline = pipeline("lake_nodes");
        let lake_candidates_pipeline = pipeline("lake_candidates");
        let lake_density_pipeline = pipeline("lake_density");
        let surface_pipeline = pipeline("surface_columns");
        let height_nodes = buffer(
            "Retina registered surface lattice",
            (SURFACE_METADATA_FLOATS * 4) as u64,
            wgpu::BufferUsages::STORAGE,
        );
        let timestamp_support = device.features().contains(wgpu::Features::TIMESTAMP_QUERY);
        let mut gpu = Self {
            device,
            queue,
            sites_pipeline,
            biome_queries_pipeline,
            underground_queries_pipeline,
            climate_pipeline,
            cave_nodes_pipeline,
            cave_exterior_pipeline,
            cave_mask_pipeline,
            cave_layout,
            cave_profiles: HashMap::new(),
            cave_buffers: None,
            columns_pipeline,
            height_pipeline,
            density_pipeline,
            lake_pipeline,
            lake_candidates_pipeline,
            lake_density_pipeline,
            surface_pipeline,
            height_nodes,
            layout,
            profiles: HashMap::new(),
            requests,
            output,
            sites,
            readback_pool: Vec::new(),
            pipeline: metrics,
            last_device_tick: None,
            timestamp_support,
            backend,
        };
        gpu.add_profile(0, &vec![0; 672], &[0; 224], &[0; 32]);
        Ok(gpu)
    }

    fn add_profile(&mut self, id: u32, bytes: &[u8], climate_bytes: &[u8], program_bytes: &[u8]) {
        let program = self
            .device
            .create_buffer_init(&wgpu::util::BufferInitDescriptor {
                label: Some("Retina registry GPU programs"),
                contents: program_bytes,
                usage: wgpu::BufferUsages::STORAGE,
            });
        let climate = self
            .device
            .create_buffer_init(&wgpu::util::BufferInitDescriptor {
                label: Some("Retina resident climate targets"),
                contents: climate_bytes,
                usage: wgpu::BufferUsages::STORAGE,
            });
        let buffer = self
            .device
            .create_buffer_init(&wgpu::util::BufferInitDescriptor {
                label: Some("Retina resident world profile"),
                contents: bytes,
                usage: wgpu::BufferUsages::STORAGE,
            });
        let group = self.world_group(&buffer, &climate, &program);
        self.profiles.insert(id, (buffer, climate, program, group));
    }

    fn world_group(
        &self,
        buffer: &wgpu::Buffer,
        climate: &wgpu::Buffer,
        program: &wgpu::Buffer,
    ) -> wgpu::BindGroup {
        self.device.create_bind_group(&wgpu::BindGroupDescriptor {
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
                wgpu::BindGroupEntry {
                    binding: 4,
                    resource: climate.as_entire_binding(),
                },
                wgpu::BindGroupEntry {
                    binding: 5,
                    resource: program.as_entire_binding(),
                },
                wgpu::BindGroupEntry {
                    binding: 6,
                    resource: self.height_nodes.as_entire_binding(),
                },
            ],
        })
    }

    fn ensure_surface_lattice(&mut self, size: u64) {
        if self.height_nodes.size() >= size {
            return;
        }
        self.height_nodes = self.device.create_buffer(&wgpu::BufferDescriptor {
            label: Some("Retina GPU-only density and climate lattice"),
            size,
            usage: wgpu::BufferUsages::STORAGE,
            mapped_at_creation: false,
        });
        // Refresh views without uploading immutable registry buffers again.
        let groups: Vec<_> = self
            .profiles
            .iter()
            .map(|(id, (buffer, climate, program, _))| {
                (*id, self.world_group(buffer, climate, program))
            })
            .collect();
        for (id, group) in groups {
            self.profiles.get_mut(&id).unwrap().3 = group;
        }
    }

    pub(crate) fn submit(
        &mut self,
        requests: &[GpuRequest],
        profile: Option<&WorldProfile>,
        timings: &timings::Timings,
    ) -> Result<PendingSample, String> {
        let host_start = Instant::now();
        let surface_probe = requests[0].padding & (1 << 31) != 0;
        let underground_probe = requests[0].padding & (1 << 30) != 0;
        let sparse = surface_probe || underground_probe;
        let profile_id = requests[0].profile;
        let density_program = profile
            .and_then(|p| p.registry_program.as_ref())
            .filter(|p| p.surface[2] == 0 && !surface_probe);
        let mut gpu_requests = requests.to_vec();
        let mut density_dispatch = (0u32, 0u32);
        let mut lake_dispatch = 0u32;
        let mut surface_dispatch = 0u32;
        if let Some(program) = density_program {
            let [sx, sy] = program.terrain_cell;
            let mut floats = SURFACE_METADATA_FLOATS as u64;
            for request in &mut gpu_requests {
                let width = if request.tile_side > 0 {
                    request.tile_side * 16
                } else {
                    16
                };
                let guard_x = request
                    .origin_x
                    .checked_sub(4)
                    .ok_or("GPU density guard X overflows block coordinates")?;
                let guard_z = request
                    .origin_z
                    .checked_sub(4)
                    .ok_or("GPU density guard Z overflows block coordinates")?;
                let remainder = guard_x
                    .rem_euclid(sx as i32)
                    .max(guard_z.rem_euclid(sx as i32)) as u64;
                let side = (width as u64 + 8 + remainder).div_ceil(sx as u64) + 1;
                let bottom = (request.min_y as i64).div_euclid(sy as i64) * sy as i64;
                let layers = (request.max_y as i64 - bottom) as u64;
                let layers = layers.div_ceil(sy as u64) + 1;
                request.density_offset =
                    u32::try_from(floats).map_err(|_| "GPU density address exceeds u32")?;
                request.density_side = side as u32;
                request.density_step_xz = sx;
                request.density_step_y = sy;
                floats += side * side * layers;
                let surface_width = width + 8;
                floats += (surface_width * surface_width) as u64;
                surface_dispatch = surface_dispatch.max(surface_width * surface_width);
                let lake_remainder = request
                    .origin_x
                    .rem_euclid(128)
                    .max(request.origin_z.rem_euclid(128))
                    as u64;
                let lake_side = (width as u64 + lake_remainder).div_ceil(128);
                floats += lake_side * lake_side * (4 + 5 * 4 * layers);
                lake_dispatch = lake_dispatch.max((lake_side * lake_side) as u32);
                density_dispatch.0 = density_dispatch.0.max((side * side) as u32);
                density_dispatch.1 = density_dispatch.1.max(layers as u32);
            }
            self.ensure_surface_lattice(floats * 4);
        }

        if !self.profiles.contains_key(&profile_id) {
            self.add_profile(
                profile_id,
                &profile.ok_or("missing GPU world profile")?.gpu_bytes(),
                &profile.unwrap().climate_gpu_bytes(),
                &crate::program::RegistryProgram::bytes(profile),
            );
        }
        let cave_side = requests[0].padding & 255;
        let cave_width = cave_side * 16 + 2;
        let cave_height = (requests[0].max_y - requests[0].min_y) as u32;
        let surface_width = requests[0].tile_side * 16;
        let volume_words =
            (cave_width as u64 * cave_width as u64 * cave_height as u64).div_ceil(32);
        let quart_width = surface_width as u64 / 4;
        let quart_layers =
            ((requests[0].max_y - requests[0].min_y.div_euclid(4) * 4 + 3) / 4) as u64;
        let biome_words = (quart_width * quart_width * quart_layers).div_ceil(2);
        let mask_size = if underground_probe {
            requests.len() as u64 * 4
        } else {
            (volume_words
                + (surface_width as u64 * surface_width as u64).div_ceil(32)
                + biome_words)
                * 4
        };
        let node_side = requests[0].tile_side * 4 + 1;
        let node_bottom = requests[0].min_y.div_euclid(4) * 4;
        let node_height = ((requests[0].max_y - node_bottom + 3) / 4 + 1) as u32;
        // Exterior limits and coherent entrance strengths remain entirely on the GPU.
        let nodes_size = if underground_probe {
            16
        } else {
            node_side as u64 * node_side as u64 * node_height as u64 * 16
                + surface_width as u64 * surface_width as u64 * 8
        };
        if cave_side > 0 || underground_probe {
            if !self.cave_profiles.contains_key(&profile_id) {
                let data = profile
                    .ok_or("missing cave profile")?
                    .geology
                    .gpu_bytes(profile.unwrap());
                self.cave_profiles.insert(
                    profile_id,
                    self.device
                        .create_buffer_init(&wgpu::util::BufferInitDescriptor {
                            label: Some("Retina resident cave profile"),
                            contents: &data,
                            usage: wgpu::BufferUsages::STORAGE,
                        }),
                );
            }
            if self
                .cave_buffers
                .as_ref()
                .is_none_or(|b| b.nodes_size < nodes_size || b.mask_size < mask_size)
            {
                let buffer = |label, size, usage| {
                    self.device.create_buffer(&wgpu::BufferDescriptor {
                        label: Some(label),
                        size,
                        usage,
                        mapped_at_creation: false,
                    })
                };
                self.cave_buffers = Some(CaveBuffers {
                    nodes: buffer(
                        "Retina GPU-only cave lattice",
                        nodes_size,
                        wgpu::BufferUsages::STORAGE,
                    ),
                    mask: buffer(
                        "Retina packed cave mask",
                        mask_size,
                        wgpu::BufferUsages::STORAGE | wgpu::BufferUsages::COPY_SRC,
                    ),
                    nodes_size,
                    mask_size,
                });
            }
        }
        self.queue
            .write_buffer(&self.requests, 0, bytemuck::cast_slice(&gpu_requests));
        let group = &self.profiles[&profile_id].3;
        let count = if requests[0].tile_side > 0 {
            (requests[0].tile_side * requests[0].tile_side) as usize
        } else {
            requests.len()
        };
        let mut slot = self.readback_pool.pop().unwrap_or_else(|| ReadbackSlot {
            columns: self.readback_buffer("Retina slot columns", self.output.size()),
            mask: None,
            timestamps: self.timestamp_support.then(|| self.create_timestamps()),
        });
        if cave_side > 0 || underground_probe {
            if slot.mask.as_ref().is_none_or(|b| b.size() < mask_size) {
                slot.mask = Some(self.readback_buffer("Retina slot cave/biome mask", mask_size));
            }
        }
        let mut measured = Vec::new();
        if profile.is_some_and(|p| p.registry_program.is_some()) {
            measured.push(timings::HEIGHT);
        }
        if profile_id != 0 {
            measured.push(timings::SITES);
        }
        measured.push(timings::COLUMNS);
        if cave_side > 0 {
            measured.extend([timings::CAVE_DENSITY, timings::CAVE_MASK]);
        } else if underground_probe {
            measured.push(timings::CAVE_DENSITY);
        }
        let timestamp_writes = |stage| {
            slot.timestamps
                .as_ref()
                .map(|t| t.writes(measured.iter().position(|s| *s == stage).unwrap() as u32))
        };
        let mut encoder = self
            .device
            .create_command_encoder(&wgpu::CommandEncoderDescriptor {
                label: Some("Retina terrain batch"),
            });
        if density_program.is_some() {
            let mut writes = timestamp_writes(timings::HEIGHT);
            if let Some(ref mut writes) = writes {
                writes.end_of_pass_write_index = None;
            }
            let mut pass = encoder.begin_compute_pass(&wgpu::ComputePassDescriptor {
                label: Some("Retina registered 3D density lattice"),
                timestamp_writes: writes,
            });
            pass.set_pipeline(&self.density_pipeline);
            pass.set_bind_group(0, group, &[]);
            pass.dispatch_workgroups(
                density_dispatch.0.div_ceil(64),
                density_dispatch.1,
                requests.len() as u32,
            );
        }
        if profile.is_some_and(|p| p.registry_program.is_some()) {
            let side = if requests[0].tile_side > 0 {
                requests[0].tile_side * 4 + 1
            } else {
                5
            };
            let writes = if density_program.is_some() {
                None
            } else {
                timestamp_writes(timings::HEIGHT)
            };
            let mut pass = encoder.begin_compute_pass(&wgpu::ComputePassDescriptor {
                label: Some("Retina registered surface and climate lattice"),
                timestamp_writes: writes,
            });
            pass.set_pipeline(if surface_probe {
                &self.climate_pipeline
            } else {
                &self.height_pipeline
            });
            pass.set_bind_group(0, group, &[]);
            pass.dispatch_workgroups((side * side).div_ceil(64), requests.len() as u32, 1);
        }
        if density_program.is_some() {
            let mut writes = timestamp_writes(timings::HEIGHT);
            if let Some(ref mut writes) = writes {
                writes.beginning_of_pass_write_index = None;
            }
            let mut pass = encoder.begin_compute_pass(&wgpu::ComputePassDescriptor {
                label: Some("Retina density surface extraction and slope halo"),
                timestamp_writes: writes,
            });
            pass.set_pipeline(&self.surface_pipeline);
            pass.set_bind_group(0, group, &[]);
            pass.dispatch_workgroups(surface_dispatch.div_ceil(64), requests.len() as u32, 1);
        }
        if profile_id != 0 {
            let mut pass = encoder.begin_compute_pass(&wgpu::ComputePassDescriptor {
                label: Some("Retina biome site pass"),
                timestamp_writes: timestamp_writes(timings::SITES),
            });
            pass.set_pipeline(&self.sites_pipeline);
            pass.set_bind_group(0, group, &[]);
            pass.dispatch_workgroups(3, requests.len() as u32, 1);
        }
        if density_program.is_some() {
            let mut writes = timestamp_writes(timings::COLUMNS);
            if let Some(ref mut writes) = writes {
                writes.end_of_pass_write_index = None;
            }
            let mut pass = encoder.begin_compute_pass(&wgpu::ComputePassDescriptor {
                label: Some("Retina lake candidate classification"),
                timestamp_writes: writes,
            });
            pass.set_pipeline(&self.lake_candidates_pipeline);
            pass.set_bind_group(0, group, &[]);
            pass.dispatch_workgroups(lake_dispatch.div_ceil(64), requests.len() as u32, 1);
        }
        if density_program.is_some() {
            let mut pass = encoder.begin_compute_pass(&wgpu::ComputePassDescriptor {
                label: Some("Retina parallel lake density probes"),
                timestamp_writes: None,
            });
            pass.set_pipeline(&self.lake_density_pipeline);
            pass.set_bind_group(0, group, &[]);
            pass.dispatch_workgroups(
                (lake_dispatch * 20).div_ceil(64),
                density_dispatch.1,
                requests.len() as u32,
            );
        }
        if density_program.is_some() {
            let mut pass = encoder.begin_compute_pass(&wgpu::ComputePassDescriptor {
                label: Some("Retina shared lake level extraction"),
                timestamp_writes: None,
            });
            pass.set_pipeline(&self.lake_pipeline);
            pass.set_bind_group(0, group, &[]);
            pass.dispatch_workgroups(lake_dispatch.div_ceil(64), requests.len() as u32, 1);
        }
        {
            let mut writes = timestamp_writes(timings::COLUMNS);
            if density_program.is_some() {
                if let Some(ref mut writes) = writes {
                    writes.beginning_of_pass_write_index = None;
                }
            }
            let mut pass = encoder.begin_compute_pass(&wgpu::ComputePassDescriptor {
                label: Some("Retina interpolated column pass"),
                timestamp_writes: writes,
            });
            pass.set_pipeline(if surface_probe {
                &self.biome_queries_pipeline
            } else {
                &self.columns_pipeline
            });
            pass.set_bind_group(0, group, &[]);
            pass.dispatch_workgroups(if sparse { 1 } else { 4 }, count as u32, 1);
        }
        if cave_side > 0 || underground_probe {
            let buffers = self.cave_buffers.as_ref().unwrap();
            let cave_group = self.device.create_bind_group(&wgpu::BindGroupDescriptor {
                label: Some("Retina cave job"),
                layout: &self.cave_layout,
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
                        resource: self.cave_profiles[&profile_id].as_entire_binding(),
                    },
                    wgpu::BindGroupEntry {
                        binding: 3,
                        resource: buffers.nodes.as_entire_binding(),
                    },
                    wgpu::BindGroupEntry {
                        binding: 4,
                        resource: buffers.mask.as_entire_binding(),
                    },
                    wgpu::BindGroupEntry {
                        binding: 5,
                        resource: self.profiles[&profile_id].2.as_entire_binding(),
                    },
                    wgpu::BindGroupEntry {
                        binding: 6,
                        resource: self.height_nodes.as_entire_binding(),
                    },
                    wgpu::BindGroupEntry {
                        binding: 7,
                        resource: self.profiles[&profile_id].1.as_entire_binding(),
                    },
                ],
            });
            if underground_probe {
                let mut pass = encoder.begin_compute_pass(&wgpu::ComputePassDescriptor {
                    label: Some("Retina sparse underground biome query"),
                    timestamp_writes: timestamp_writes(timings::CAVE_DENSITY),
                });
                pass.set_pipeline(&self.underground_queries_pipeline);
                pass.set_bind_group(0, &cave_group, &[]);
                pass.dispatch_workgroups(1, count as u32, 1);
            } else {
                {
                    let mut pass = encoder.begin_compute_pass(&wgpu::ComputePassDescriptor {
                        label: Some("Retina cave density pass"),
                        timestamp_writes: timestamp_writes(timings::CAVE_DENSITY),
                    });
                    pass.set_pipeline(&self.cave_nodes_pipeline);
                    pass.set_bind_group(0, &cave_group, &[]);
                    pass.dispatch_workgroups((node_side * node_side).div_ceil(64), node_height, 1);
                }
                {
                    let mut writes = timestamp_writes(timings::CAVE_MASK);
                    if let Some(ref mut writes) = writes {
                        writes.end_of_pass_write_index = None;
                    }
                    let mut pass = encoder.begin_compute_pass(&wgpu::ComputePassDescriptor {
                        label: Some("Retina GPU exterior density classification"),
                        timestamp_writes: writes,
                    });
                    pass.set_pipeline(&self.cave_exterior_pipeline);
                    pass.set_bind_group(0, &cave_group, &[]);
                    pass.dispatch_workgroups(
                        (surface_width * surface_width / 4).div_ceil(64),
                        1,
                        1,
                    );
                }
                {
                    let mut writes = timestamp_writes(timings::CAVE_MASK);
                    if let Some(ref mut writes) = writes {
                        writes.beginning_of_pass_write_index = None;
                    }
                    let mut pass = encoder.begin_compute_pass(&wgpu::ComputePassDescriptor {
                        label: Some("Retina GPU interpolated cave mask"),
                        timestamp_writes: writes,
                    });
                    pass.set_pipeline(&self.cave_mask_pipeline);
                    pass.set_bind_group(0, &cave_group, &[]);
                    pass.dispatch_workgroups(
                        256,
                        volume_words
                            .max((surface_width as u64 * surface_width as u64).div_ceil(32))
                            .max(biome_words)
                            .div_ceil(16384) as u32,
                        1,
                    );
                }
            }
            encoder.copy_buffer_to_buffer(
                &buffers.mask,
                0,
                slot.mask.as_ref().unwrap(),
                0,
                mask_size,
            );
        }
        let column_count = count * if sparse { 1 } else { COLUMNS };
        let size = (column_count * std::mem::size_of::<Column>()) as u64;
        encoder.copy_buffer_to_buffer(&self.output, 0, &slot.columns, 0, size);
        let query_bytes = measured.len() as u64 * 16;
        if let Some(t) = &slot.timestamps {
            encoder.resolve_query_set(&t.queries, 0..measured.len() as u32 * 2, &t.resolve, 0);
            encoder.copy_buffer_to_buffer(&t.resolve, 0, &t.readback, 0, query_bytes);
        }
        let submission = self.queue.submit([encoder.finish()]);
        let encode_nanos = host_start.elapsed().as_nanos() as u64;
        timings.add(timings::ENCODE, encode_nanos);
        let wait_start = Instant::now();
        let mut job_timings = timings::Snapshot::default();
        job_timings.version = 1;
        job_timings.gpu_jobs = 1;
        job_timings.gpu_columns = column_count as u64;
        job_timings.nanos[timings::ENCODE] = encode_nanos;
        let columns_map = Mapping::new(&slot.columns, size);
        let mask_map = (cave_side > 0 || underground_probe)
            .then(|| Mapping::new(slot.mask.as_ref().unwrap(), mask_size));
        let query_map = slot
            .timestamps
            .as_ref()
            .map(|t| Mapping::new(&t.readback, query_bytes));
        Ok(PendingSample {
            slot,
            submission,
            columns_map,
            mask_map,
            query_map,
            wait_start,
            request: requests[0],
            cave_width,
            cave_height,
            underground_probe,
            size,
            mask_size,
            query_bytes,
            measured,
            timings: job_timings,
        })
    }
    fn create_timestamps(&self) -> Timestamps {
        Timestamps {
            queries: self.device.create_query_set(&wgpu::QuerySetDescriptor {
                label: Some("Retina slot pass timings"),
                ty: wgpu::QueryType::Timestamp,
                count: 10,
            }),
            resolve: self.device.create_buffer(&wgpu::BufferDescriptor {
                label: Some("Retina slot timestamp resolve"),
                size: 256,
                usage: wgpu::BufferUsages::QUERY_RESOLVE | wgpu::BufferUsages::COPY_SRC,
                mapped_at_creation: false,
            }),
            readback: self.readback_buffer("Retina slot timestamp readback", 80),
        }
    }
    fn readback_buffer(&self, label: &str, size: u64) -> wgpu::Buffer {
        self.device.create_buffer(&wgpu::BufferDescriptor {
            label: Some(label),
            size,
            usage: wgpu::BufferUsages::COPY_DST | wgpu::BufferUsages::MAP_READ,
            mapped_at_creation: false,
        })
    }
    pub(crate) fn ready(&self, pending: &mut PendingSample) -> Result<bool, String> {
        self.device
            .poll(wgpu::PollType::Poll)
            .map_err(|e| format!("GPU poll failed: {e}"))?;
        let ready = pending.ready();
        if !ready && pending.wait_start.elapsed() > Duration::from_secs(30) {
            return Err("GPU mapping timed out".into());
        }
        Ok(ready)
    }
    pub(crate) fn complete(
        &mut self,
        mut pending: PendingSample,
        timings: &timings::Timings,
    ) -> Result<GpuSample, String> {
        if !pending.ready() {
            self.device
                .poll(wgpu::PollType::Wait {
                    submission_index: Some(pending.submission.clone()),
                    timeout: Some(Duration::from_secs(30)),
                })
                .map_err(|e| format!("GPU poll failed: {e}"))?;
        }
        pending.columns_map.check()?;
        let mut columns = {
            let mapped = pending
                .slot
                .columns
                .slice(..pending.size)
                .get_mapped_range()
                .map_err(|e| e.to_string())?;
            bytemuck::cast_slice::<u8, Column>(&mapped).to_vec()
        };
        let mask = if let Some(mapping) = &mut pending.mask_map {
            mapping.check()?;
            let words = {
                let mapped = pending
                    .slot
                    .mask
                    .as_ref()
                    .unwrap()
                    .slice(..pending.mask_size)
                    .get_mapped_range()
                    .map_err(|e| e.to_string())?;
                bytemuck::cast_slice::<u8, u32>(&mapped).to_vec()
            };
            if pending.underground_probe {
                for (column, biome) in columns.iter_mut().zip(words) {
                    column.packed = biome;
                }
                None
            } else {
                Some(crate::geology::CaveMask {
                    origin_x: pending.request.origin_x + 15,
                    origin_z: pending.request.origin_z + 15,
                    min_y: pending.request.min_y,
                    height: pending.cave_height,
                    width: pending.cave_width as usize,
                    words,
                })
            }
        } else {
            None
        };
        if let Some(mapping) = &mut pending.query_map {
            mapping.check()?;
            let mapped = pending
                .slot
                .timestamps
                .as_ref()
                .unwrap()
                .readback
                .slice(..pending.query_bytes)
                .get_mapped_range()
                .map_err(|e| e.to_string())?;
            let values = bytemuck::cast_slice::<u8, u64>(&mapped);
            let period = self.queue.get_timestamp_period() as f64;
            let mut first = u64::MAX;
            let mut last = 0;
            for (i, stage) in pending.measured.iter().enumerate() {
                let start = values[i * 2];
                let end = values[i * 2 + 1];
                // Some Metal samples are unavailable or stale. Never treat a
                // zero/old endpoint as an enormous device idle interval.
                if start == 0 || end < start {
                    self.pipeline.unavailable();
                    continue;
                }
                first = first.min(start);
                last = last.max(end);
                let nanos = ((end - start) as f64 * period) as u64;
                timings.add(*stage, nanos);
                pending.timings.nanos[*stage] = nanos;
            }
            if last > 0 {
                let gap = self
                    .last_device_tick
                    .map_or(0, |previous| first.saturating_sub(previous));
                self.last_device_tick = Some(self.last_device_tick.unwrap_or(0).max(last));
                self.pipeline.device(
                    ((last - first) as f64 * period) as u64,
                    (gap as f64 * period) as u64,
                );
            }
        }
        drop(pending.columns_map);
        drop(pending.mask_map);
        drop(pending.query_map);
        pending.timings.flags = u32::from(self.timestamp_support);
        pending.timings.nanos[timings::WAIT_COPY] = pending.wait_start.elapsed().as_nanos() as u64;
        timings.add(
            timings::WAIT_COPY,
            pending.timings.nanos[timings::WAIT_COPY],
        );
        timings.gpu(pending.timings.gpu_columns, self.timestamp_support);
        self.pipeline.completed();
        self.readback_pool.push(pending.slot);
        Ok(GpuSample {
            columns,
            mask,
            timings: pending.timings,
        })
    }
}

struct ReadbackSlot {
    columns: wgpu::Buffer,
    mask: Option<wgpu::Buffer>,
    timestamps: Option<Timestamps>,
}
struct Mapping {
    buffer: wgpu::Buffer,
    receiver: mpsc::Receiver<Result<(), String>>,
    result: Option<Result<(), String>>,
}
impl Mapping {
    fn new(buffer: &wgpu::Buffer, size: u64) -> Self {
        let (sender, receiver) = mpsc::channel();
        buffer
            .slice(..size)
            .map_async(wgpu::MapMode::Read, move |r| {
                let _ = sender.send(r.map_err(|e| e.to_string()));
            });
        Self {
            buffer: buffer.clone(),
            receiver,
            result: None,
        }
    }
    fn ready(&mut self) -> bool {
        if self.result.is_none() {
            match self.receiver.try_recv() {
                Ok(r) => self.result = Some(r),
                Err(mpsc::TryRecvError::Disconnected) => {
                    self.result = Some(Err("GPU mapping callback disconnected".into()))
                }
                Err(mpsc::TryRecvError::Empty) => {}
            }
        }
        self.result.is_some()
    }
    fn check(&mut self) -> Result<(), String> {
        if self.result.is_none() {
            match self.receiver.recv_timeout(Duration::from_secs(30)) {
                Ok(r) => self.result = Some(r),
                Err(error) => return Err(format!("GPU mapping timed out: {error}")),
            }
        }
        self.result.as_ref().unwrap().clone()
    }
}
impl Drop for Mapping {
    fn drop(&mut self) {
        self.ready();
        // An outstanding map can be cancelled; a successful map must be
        // unmapped. Failed mappings never put the buffer in a mapped state.
        if !matches!(self.result, Some(Err(_))) {
            self.buffer.unmap();
        }
    }
}

pub(crate) struct PendingSample {
    slot: ReadbackSlot,
    submission: wgpu::SubmissionIndex,
    columns_map: Mapping,
    mask_map: Option<Mapping>,
    query_map: Option<Mapping>,
    wait_start: Instant,
    request: GpuRequest,
    cave_width: u32,
    cave_height: u32,
    underground_probe: bool,
    size: u64,
    mask_size: u64,
    query_bytes: u64,
    measured: Vec<usize>,
    timings: timings::Snapshot,
}
impl PendingSample {
    fn ready(&mut self) -> bool {
        self.columns_map.ready()
            && self.mask_map.as_mut().is_none_or(|m| m.ready())
            && self.query_map.as_mut().is_none_or(|m| m.ready())
    }
}

#[cfg(test)]
mod mapping_tests {
    use super::*;
    #[test]
    fn cancelled_mapping_cleanup_allows_buffer_reuse() {
        let gpu = Gpu::new(std::sync::Arc::new(crate::pipeline::Metrics::default())).unwrap();
        let buffer = gpu.readback_buffer("Retina mapping cleanup test", 64);
        // Drop a genuinely pending map; this cancels the callback and frees the
        // mapping state without touching any other slot's buffers.
        drop(Mapping::new(&buffer, 64));
        let mut cancelled = Mapping::new(&buffer, 64);
        buffer.unmap();
        gpu.device
            .poll(wgpu::PollType::Wait {
                submission_index: None,
                timeout: Some(Duration::from_secs(30)),
            })
            .unwrap();
        assert!(cancelled.check().is_err());
        drop(cancelled);
        let mut successful = Mapping::new(&buffer, 64);
        gpu.device
            .poll(wgpu::PollType::Wait {
                submission_index: None,
                timeout: Some(Duration::from_secs(30)),
            })
            .unwrap();
        successful.check().unwrap();
        drop(successful);
        let mut reused = Mapping::new(&buffer, 64);
        gpu.device
            .poll(wgpu::PollType::Wait {
                submission_index: None,
                timeout: Some(Duration::from_secs(30)),
            })
            .unwrap();
        reused.check().unwrap();
    }
}
