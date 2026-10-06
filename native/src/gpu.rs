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
    aquifer_surface_pipeline: wgpu::ComputePipeline,
    aquifer_centers_pipeline: wgpu::ComputePipeline,
    aquifer_barrier_pipeline: wgpu::ComputePipeline,
    aquifer_mask_pipeline: wgpu::ComputePipeline,
    material_counts_pipeline: wgpu::ComputePipeline,
    material_prefix_blocks_pipeline: wgpu::ComputePipeline,
    material_prefix_total_pipeline: wgpu::ComputePipeline,
    material_emit_pipeline: wgpu::ComputePipeline,
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
    compiler: crate::specialize::Compiler,
    specialized: HashMap<u32, std::sync::Arc<crate::specialize::State>>,
    wide_profiles: std::collections::HashSet<u32>,
    wide_interpreter: Option<std::sync::Arc<crate::specialize::Pipelines>>,
    interpreter_reuse_plans: HashMap<u32, crate::specialize::interpreter_reuse::Plan>,
    interpolation_interpreters: HashMap<
        (
            usize,
            usize,
            bool,
            crate::specialize::interpreter_reuse::Plan,
        ),
        std::sync::Arc<crate::specialize::Pipelines>,
    >,
    interpolation_plans: HashMap<u32, std::sync::Arc<crate::program::interpolation::CachePlan>>,
    cached_density_plans: HashMap<u32, crate::program::composition::CachedDensity>,
    cached_node_plans: HashMap<u32, crate::program::composition::CachedDensity>,
    sparse_lake_plans: HashMap<u32, crate::program::lake_sparse::Plan>,
    interpolation_cache: bool,
    specialized_terrain: bool,
    specialized_interpolation: bool,
    interpreter_dispatch: bool,
    preloaded_interpreters: HashMap<bool, std::sync::Arc<crate::specialize::State>>,
    density_composition: bool,
    cached_density_masks: bool,
    lake_point_cache: bool,
    lake_primed_corners: bool,
    lake_sparse_fields: bool,
    interpreter_columns: bool,
    aquifer_columns: bool,
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
    // Eleven pairs cover the largest terrain/material/aquifer batch. Keep an
    // extra pair available without enlarging the aligned resolve buffer.
    const PAIRS: u32 = 12;
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
    pub(crate) fn decoration_counts(&self) -> crate::decoration::counts::gpu::Gpu {
        crate::decoration::counts::gpu::Gpu::new(self.device.clone(), self.queue.clone())
    }
    pub(crate) fn ore_planner(&self) -> crate::geology::raster_gpu::Gpu {
        crate::geology::raster_gpu::Gpu::new(self.device.clone(), self.queue.clone())
    }
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
        // Matched Metal measurements favor the compact density/climate stages.
        // Keep the prior default on unmeasured backends; diagnostics can select
        // either mode without changing loaded graph or saved-world semantics.
        let specialized_terrain = match std::env::var("RETINA_SPECIALIZED_TERRAIN").as_deref() {
            Ok("0") => false,
            Ok("1") => true,
            _ => info.backend != wgpu::Backend::Metal,
        };
        // Input-field writers benefit from direct graph calls even when the
        // remaining Metal terrain stages favor the compact interpreter.
        let specialized_interpolation =
            match std::env::var("RETINA_SPECIALIZED_INTERPOLATION").as_deref() {
                Ok("0") => false,
                Ok("1") => true,
                _ => info.backend == wgpu::Backend::Metal,
            };
        let lake_point_cache = match std::env::var("RETINA_LAKE_POINT_CACHE").as_deref() {
            Ok("0") => false,
            Ok("1") => true,
            _ => info.backend == wgpu::Backend::Metal,
        };
        let lake_primed_corners = match std::env::var("RETINA_LAKE_PRIMED_CORNERS").as_deref() {
            Ok("0") => false,
            Ok("1") => true,
            _ => info.backend == wgpu::Backend::Metal,
        };
        let lake_sparse_fields = match std::env::var("RETINA_LAKE_SPARSE_FIELDS").as_deref() {
            Ok("0") => false,
            Ok("1") => true,
            _ => info.backend == wgpu::Backend::Metal,
        };
        let interpreter_columns = match std::env::var("RETINA_INTERPRETER_COLUMNS").as_deref() {
            Ok("0") => false,
            Ok("1") => true,
            _ => info.backend == wgpu::Backend::Metal,
        };
        // Real profiles show small serial benefits but concurrent regressions.
        // Keep this measured alternative opt-in until it improves both workloads.
        let aquifer_columns = std::env::var("RETINA_AQUIFER_COLUMNS").as_deref() == Ok("1");
        // Matched Metal runs cut compact interpreter first-use compilation by
        // about 70%. Unmeasured backends retain their independent pipelines.
        let interpreter_dispatch = match std::env::var("RETINA_INTERPRETER_DISPATCH").as_deref() {
            Ok("0") => false,
            Ok("1") => true,
            _ => {
                info.backend == wgpu::Backend::Metal
                    && adapter.features().contains(wgpu::Features::IMMEDIATES)
                    && adapter.limits().max_immediate_size >= 4
            }
        };
        // The two material scans share a call graph. Fresh-identity Metal pairs
        // cut their cold compilation by 48–63%; warm throughput is mixed.
        let material_dispatch = match std::env::var("RETINA_MATERIAL_DISPATCH").as_deref() {
            Ok("0") => false,
            Ok("1") => true,
            _ => {
                info.backend == wgpu::Backend::Metal
                    && adapter.features().contains(wgpu::Features::IMMEDIATES)
                    && adapter.limits().max_immediate_size >= 4
            }
        };
        let (device, queue) = pollster::block_on(adapter.request_device(&wgpu::DeviceDescriptor {
            label: Some("Retina terrain device"),
            required_features: (adapter.features() & wgpu::Features::TIMESTAMP_QUERY)
                | if interpreter_dispatch || material_dispatch {
                    wgpu::Features::IMMEDIATES
                } else {
                    wgpu::Features::empty()
                },
            required_limits: wgpu::Limits {
                max_immediate_size: if interpreter_dispatch || material_dispatch {
                    4
                } else {
                    0
                },
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
                    "{}\n{}\n{}\n{}",
                    include_str!("simplex.wgsl"),
                    include_str!("climate.wgsl"),
                    crate::program::interpreter_source(crate::program::COMPACT_VALUES),
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
                    "{}\n{}\n{}\n{}\n{}",
                    include_str!("caves.wgsl"),
                    include_str!("climate.wgsl"),
                    crate::program::interpreter_source(crate::program::COMPACT_VALUES),
                    include_str!("aquifers.wgsl"),
                    include_str!("materials.wgsl")
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
                            read_only: binding <= 2 || binding == 5 || binding == 7,
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
        let aquifer_surface_pipeline = cave_pipeline("aquifer_surface");
        let aquifer_centers_pipeline = cave_pipeline("aquifer_centers");
        let aquifer_barrier_pipeline = cave_pipeline("aquifer_barrier");
        let aquifer_mask_pipeline = cave_pipeline("aquifer_mask");
        let material_counts_pipeline = cave_pipeline("material_counts");
        let material_prefix_blocks_pipeline = cave_pipeline("material_prefix_blocks");
        let material_prefix_total_pipeline = cave_pipeline("material_prefix_total");
        let material_emit_pipeline = cave_pipeline("material_emit");
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
            usage: wgpu::BufferUsages::STORAGE
                | wgpu::BufferUsages::COPY_DST
                | wgpu::BufferUsages::COPY_SRC,
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
            wgpu::BufferUsages::STORAGE | wgpu::BufferUsages::COPY_DST,
        );
        let timestamp_support = device.features().contains(wgpu::Features::TIMESTAMP_QUERY);
        let compiler = crate::specialize::Compiler::new(
            &device,
            &layout,
            &cave_layout,
            material_dispatch,
            specialized_interpolation,
        )?;
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
            aquifer_surface_pipeline,
            aquifer_centers_pipeline,
            aquifer_barrier_pipeline,
            aquifer_mask_pipeline,
            material_counts_pipeline,
            material_prefix_blocks_pipeline,
            material_prefix_total_pipeline,
            material_emit_pipeline,
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
            compiler,
            specialized: HashMap::new(),
            wide_profiles: std::collections::HashSet::new(),
            wide_interpreter: None,
            interpreter_reuse_plans: HashMap::new(),
            interpolation_interpreters: HashMap::new(),
            interpolation_plans: HashMap::new(),
            cached_density_plans: HashMap::new(),
            cached_node_plans: HashMap::new(),
            sparse_lake_plans: HashMap::new(),
            interpolation_cache: std::env::var("RETINA_INTERPOLATION_CACHE").as_deref() != Ok("0"),
            specialized_terrain,
            specialized_interpolation,
            interpreter_dispatch,
            preloaded_interpreters: HashMap::new(),
            // The saved profile selects graph semantics. This diagnostic may
            // disable composition, but cannot opt a legacy saved profile in.
            density_composition: std::env::var("RETINA_DENSITY_COMPOSITION").as_deref() != Ok("0"),
            lake_sparse_fields,
            interpreter_columns,
            aquifer_columns,
            cached_density_masks: std::env::var("RETINA_CACHED_DENSITY_MASKS").as_deref()
                != Ok("0"),
            lake_point_cache,
            lake_primed_corners,
        };
        gpu.add_profile(0, &vec![0; 672], &[0; 240], &[0; 32]);
        Ok(gpu)
    }

    pub(crate) fn start_interpreter_preload(&mut self) {
        // Overlap compact density compilation with native profile parsing.
        if self.interpreter_dispatch
            && std::env::var("RETINA_INTERPRETER_PRELOAD").as_deref() != Ok("0")
        {
            // New presets use composition; absent saved fields use legacy.
            // Each inventory has its own immutable source/layout identity.
            let modes: &[bool] = if self.density_composition {
                &[true, false]
            } else {
                &[false]
            };
            for &composition in modes {
                match self.compiler.compact_interpreter(composition) {
                    Ok(state) => {
                        self.preloaded_interpreters.insert(composition, state);
                    }
                    Err(error) => {
                        eprintln!("Retina keeps on-demand interpreter compilation: {error}")
                    }
                }
            }
        }
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
        self.pipeline.transfer(
            id,
            (bytes.len() + climate_bytes.len() + program_bytes.len()) as u64,
            0,
        );
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
            usage: wgpu::BufferUsages::STORAGE | wgpu::BufferUsages::COPY_DST,
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
        let align_shores = profile.is_some_and(|p| {
            p.registry_program
                .as_ref()
                .is_some_and(|program| program.surface[2] == 0)
                && p.climate_targets
                    .iter()
                    .any(|t| p.biomes[t.biome as usize].flags & 64 != 0)
        });
        let profile_id = requests[0].profile;
        if !self.profiles.contains_key(&profile_id) {
            self.add_profile(
                profile_id,
                &profile.ok_or("missing GPU world profile")?.gpu_bytes(),
                &profile.unwrap().climate_gpu_bytes(),
                &crate::program::RegistryProgram::bytes(profile),
            );
            if let Some(program) = profile.and_then(|p| p.registry_program.as_ref()) {
                self.interpolation_plans.insert(
                    profile_id,
                    std::sync::Arc::new(crate::program::interpolation::CachePlan::new(program)),
                );
                if let Some(plan) = crate::program::composition::CachedDensity::new(program) {
                    self.cached_density_plans.insert(profile_id, plan);
                }
                if let Some(plan) = crate::program::composition::CachedDensity::for_nodes(program) {
                    self.cached_node_plans.insert(profile_id, plan);
                }
                if let Some(plan) = crate::program::lake_sparse::Plan::new(program) {
                    self.sparse_lake_plans.insert(profile_id, plan);
                }
                if program.scratch_values() > crate::program::COMPACT_VALUES {
                    self.wide_profiles.insert(profile_id);
                }
                if profile.unwrap().program_execution != crate::specialize::Execution::Interpreter {
                    match self.compiler.request(
                        program,
                        self.specialized_terrain,
                        self.density_composition && program.density_composition,
                        self.cached_density_masks,
                        self.lake_point_cache,
                        self.lake_primed_corners,
                        self.lake_sparse_fields,
                        self.aquifer_columns,
                    ) {
                        Ok(state) => {
                            self.pipeline.shader(profile_id, state.progress.clone());
                            self.specialized.insert(profile_id, state);
                        }
                        Err(error) => {
                            if profile.unwrap().program_execution
                                == crate::specialize::Execution::Specialized
                            {
                                return Err(error);
                            }
                            eprintln!("Retina keeps the GPU interpreter: {error}");
                        }
                    }
                }
            }
        }
        let specialized = if let Some(state) = self.specialized.get(&profile_id) {
            let wait =
                profile.unwrap().program_execution == crate::specialize::Execution::Specialized;
            match state.ready(wait) {
                Ok(p) => p,
                Err(e) if wait => return Err(e),
                Err(_) => None,
            }
        } else {
            None
        };
        let depth = profile
            .and_then(|p| p.registry_program.as_ref())
            .map_or(0, |p| p.interpolation_depth());
        let needs_interpreter = specialized.is_none() || !self.specialized_terrain;
        let composition = self.density_composition
            && profile
                .and_then(|p| p.registry_program.as_ref())
                .is_some_and(|p| p.density_composition);
        if depth == 0
            && needs_interpreter
            && self.wide_profiles.contains(&profile_id)
            && self.wide_interpreter.is_none()
        {
            self.wide_interpreter =
                Some(std::sync::Arc::new(crate::specialize::compile_interpreter(
                    &self.device,
                    &self.layout,
                    &self.cave_layout,
                    &crate::program::interpreter_source(1024),
                    None,
                    false,
                )?));
        }
        let interpreted = if needs_interpreter && (depth > 0 || composition) {
            let capacity = if self.wide_profiles.contains(&profile_id) {
                1024
            } else {
                crate::program::COMPACT_VALUES
            };
            // Profiles and execution/layout options are immutable on this GPU.
            // Resolve graph dependencies once, including for frequent sparse
            // biome/structure probes, rather than scanning every material DAG.
            let reuse = *self
                .interpreter_reuse_plans
                .entry(profile_id)
                .or_insert_with(|| {
                    profile.and_then(|p| p.registry_program.as_ref()).map_or(
                        Default::default(),
                        |p| {
                            crate::specialize::interpreter_reuse::Plan::new(
                                p,
                                capacity,
                                composition,
                            )
                        },
                    )
                });
            #[cfg(test)]
            let reuse = if std::env::var("RETINA_COMPILE_REUSE").as_deref() == Ok("0") {
                Default::default()
            } else {
                reuse
            };
            let key = (capacity, depth, composition, reuse);
            if !self.interpolation_interpreters.contains_key(&key) {
                let source =
                    crate::program::interpreter_source_density(capacity, depth, composition);
                let preloaded = if capacity == crate::program::COMPACT_VALUES
                    && depth == 1
                    && crate::specialize::interpreter_reuse::Plan::density_preload(composition)
                        .supports(reuse)
                {
                    self.preloaded_interpreters
                        .get(&composition)
                        .and_then(|state| match state.ready(true) {
                            Ok(pipelines) => pipelines,
                            Err(error) => {
                                eprintln!(
                                    "Retina keeps on-demand interpreter compilation: {error}"
                                );
                                None
                            }
                        })
                } else {
                    None
                };
                let pipelines = if let Some(pipelines) = preloaded {
                    pipelines.with_reuse(reuse)
                } else {
                    crate::specialize::compile_interpreter(
                        &self.device,
                        &self.layout,
                        &self.cave_layout,
                        &source,
                        Some(reuse),
                        self.interpreter_dispatch && capacity == crate::program::COMPACT_VALUES,
                    )?
                };
                self.interpolation_interpreters
                    .insert(key, std::sync::Arc::new(pipelines));
            }
            self.interpolation_interpreters.get(&key).cloned()
        } else {
            self.wide_interpreter
                .as_ref()
                .filter(|_| self.wide_profiles.contains(&profile_id))
                .cloned()
        };
        let selected = specialized.as_deref().or(interpreted.as_deref());
        // Terrain input and climate passes may use the compact interpreter while
        // materials/caves retain specialization. Both use the same resident
        // bytecode, field cache and immutable per-batch layout.
        let terrain = if self.specialized_terrain {
            selected
        } else {
            interpreted.as_deref()
        };
        let world_pipeline = |entry, fallback| {
            selected.map_or(crate::specialize::Selected::from(fallback), |p| {
                p.world_or(entry, fallback)
            })
        };
        let terrain_pipeline = |entry, fallback| {
            terrain.map_or(crate::specialize::Selected::from(fallback), |p| {
                p.world_or(entry, fallback)
            })
        };
        let density_program = profile
            .and_then(|p| p.registry_program.as_ref())
            .filter(|p| p.surface[2] == 0 && (!surface_probe || align_shores));
        let horizontal_fields = specialized.as_ref().map_or(0, |p| p.horizontal_fields);
        let interpolation_plan = self
            .interpolation_plans
            .get(&profile_id)
            .cloned()
            .filter(|_| self.interpolation_cache);
        let mut gpu_requests = requests.to_vec();
        let guard = if align_shores { 6 } else { 4 };
        if align_shores {
            for request in &mut gpu_requests {
                request.padding |= 1 << 28;
            }
        }
        let mut density_dispatch = (0u32, 0u32);
        let mut lake_dispatch = 0u32;
        let mut surface_dispatch = 0u32;
        let mut interpolation_dispatch = 0u32;
        let mut interpolation_headers = Vec::new();
        let mut interpolation_depth = 0;
        let sparse_plan = self
            .sparse_lake_plans
            .get(&profile_id)
            .cloned()
            .filter(|_| {
                composition
                    && self.lake_sparse_fields
                    && !surface_probe
                    && selected.is_some_and(|p| p.cached_world("lake_density_sparse").is_some())
            });
        let mut sparse_dispatch = 0u32;
        if let Some(program) = density_program {
            let compose = self.density_composition && program.density_composition;
            let [sx, sy] = program.terrain_cell;
            let mut floats = SURFACE_METADATA_FLOATS as u64;
            for request in &mut gpu_requests {
                if compose {
                    request.padding |= 1 << 26;
                }
                let width = if request.tile_side > 0 {
                    request.tile_side * 16
                } else {
                    16
                };
                let guard_x = request
                    .origin_x
                    .checked_sub(guard)
                    .ok_or("GPU density guard X overflows block coordinates")?;
                let guard_z = request
                    .origin_z
                    .checked_sub(guard)
                    .ok_or("GPU density guard Z overflows block coordinates")?;
                let remainder = guard_x
                    .rem_euclid(sx as i32)
                    .max(guard_z.rem_euclid(sx as i32)) as u64;
                let side = (width as u64 + 2 * guard as u64 + remainder).div_ceil(sx as u64) + 1;
                let bottom = (request.min_y as i64).div_euclid(sy as i64) * sy as i64;
                let layers = (request.max_y as i64 - bottom) as u64;
                let layers = layers.div_ceil(sy as u64) + 1;
                if interpolation_plan.as_ref().is_some_and(|p| p.depth > 0) {
                    floats += (program.interpolations.len()
                        * crate::program::interpolation::CACHE_WORDS)
                        as u64;
                }
                request.density_offset =
                    u32::try_from(floats).map_err(|_| "GPU density address exceeds u32")?;
                request.density_side = side as u32;
                request.density_step_xz = sx;
                request.density_step_y = sy;
                if self.interpreter_columns && horizontal_fields > 0 {
                    request.padding |= crate::program::COLUMN_INTERPRETER_FLAG;
                }
                floats += side * side * layers * if compose { 3 } else { 1 };
                let surface_width = width + 2 * guard as u32;
                floats += (surface_width * surface_width) as u64;
                surface_dispatch = surface_dispatch.max(surface_width * surface_width);
                let lake_remainder = request
                    .origin_x
                    .rem_euclid(128)
                    .max(request.origin_z.rem_euclid(128))
                    as u64;
                let lake_side = (width as u64 + lake_remainder).div_ceil(128);
                floats += lake_side * lake_side * (4 + 5 * 4 * layers);
                floats += side * side * horizontal_fields as u64;
                if let Some(plan) = sparse_plan
                    .as_ref()
                    .filter(|p| p.request_supported(request))
                {
                    let samples = plan.max_samples(request);
                    let count = lake_side * lake_side * 5;
                    let extra = count * plan.words(request);
                    let limit = self
                        .device
                        .limits()
                        .max_buffer_size
                        .min(self.device.limits().max_storage_buffer_binding_size as u64)
                        / 4;
                    if extra <= limit.saturating_sub(floats)
                        && count * samples
                            <= u64::from(self.device.limits().max_compute_workgroups_per_dimension)
                                * 64
                    {
                        floats += extra;
                        request.padding |= crate::program::lake_sparse::FLAG;
                        sparse_dispatch = sparse_dispatch.max((count * samples) as u32);
                    }
                }
                lake_dispatch = lake_dispatch.max((lake_side * lake_side) as u32);
                density_dispatch.0 = density_dispatch.0.max((side * side) as u32);
                density_dispatch.1 = density_dispatch.1.max(layers as u32);
            }
            if let Some(plan) = interpolation_plan.filter(|p| p.depth > 0) {
                let limit = self
                    .device
                    .limits()
                    .max_buffer_size
                    .min(self.device.limits().max_storage_buffer_binding_size as u64)
                    / 4;
                let max_samples =
                    self.device.limits().max_compute_workgroups_per_dimension as u64 * 64;
                for request in &mut gpu_requests {
                    let (header, dispatch) =
                        plan.layout(program, request, guard, &mut floats, limit, max_samples)?;
                    if dispatch > 0 {
                        request.padding |= crate::program::interpolation::CACHE_FLAG;
                    }
                    let address = request.density_offset as u64 - header.len() as u64;
                    interpolation_headers.push((address, header));
                    interpolation_dispatch = interpolation_dispatch.max(dispatch);
                }
                if interpolation_dispatch > 0 {
                    interpolation_depth = plan.depth;
                }
            }
            self.ensure_surface_lattice(floats * 4);
        }
        // A submission selects one complete sampler. Partial optional capacity
        // or coordinate coverage keeps the original GPU path for the whole batch.
        let sparse_lakes = sparse_plan.is_some()
            && gpu_requests
                .iter()
                .all(|r| r.padding & crate::program::lake_sparse::FLAG != 0);

        let cached_masks = self.cached_density_masks
            && composition
            && self
                .cached_density_plans
                .get(&profile_id)
                .is_some_and(|plan| {
                    interpolation_headers
                        .first()
                        .is_some_and(|(_, header)| plan.covers(&gpu_requests[0], header))
                });
        let cave_pipeline = |entry, fallback| {
            let cached = if entry == "cave_nodes" {
                self.cached_density_masks
                    && composition
                    && self.cached_node_plans.get(&profile_id).is_some_and(|plan| {
                        interpolation_headers
                            .first()
                            .is_some_and(|(_, header)| plan.covers_nodes(&gpu_requests[0], header))
                    })
            } else {
                cached_masks
            };
            selected.map_or(crate::specialize::Selected::from(fallback), |p| {
                if cached {
                    p.cached_cave(entry)
                        .map(crate::specialize::Selected::from)
                        .unwrap_or_else(|| p.cave_or(entry, fallback))
                } else {
                    p.cave_or(entry, fallback)
                }
            })
        };

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
        let layered = cave_side > 0
            && profile.is_some_and(|p| {
                p.registry_program
                    .as_ref()
                    .is_some_and(|r| r.material_layers)
            });
        let material_columns = (cave_side * 16).pow(2) as u64;
        let aquifer = if layered {
            profile
                .and_then(|p| p.registry_program.as_ref())
                .and_then(|p| p.aquifer.as_ref())
        } else {
            None
        };
        let material_header_words = if layered {
            4 + material_columns * 2 + material_columns.div_ceil(256) * 2
        } else {
            0
        };
        let mask_size = if underground_probe {
            requests.len() as u64 * 4
        } else {
            (volume_words
                + (surface_width as u64 * surface_width as u64).div_ceil(32)
                + biome_words
                + material_header_words)
                * 4
        };
        // Fluid planes stay GPU-only. CPU mask/header and final run readbacks
        // deliberately omit them; they are included in the emission snapshot.
        let storage_mask_size = mask_size
            + if aquifer.is_some() {
                volume_words * 8
            } else {
                0
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
                + if aquifer.is_some_and(|a| a.enabled) {
                    let r = requests[0];
                    let min = [
                        (r.origin_x + 10).div_euclid(16),
                        (r.min_y + 1).div_euclid(12) - 1,
                        (r.origin_z + 10).div_euclid(16),
                    ];
                    let max = [
                        (r.origin_x + cave_side as i32 * 16 + 11).div_euclid(16) + 1,
                        r.max_y.div_euclid(12) + 1,
                        (r.origin_z + cave_side as i32 * 16 + 11).div_euclid(16) + 1,
                    ];
                    let centers = (max[0] - min[0] + 1) as u64
                        * (max[1] - min[1] + 1) as u64
                        * (max[2] - min[2] + 1) as u64;
                    let surfaces = ((max[0] * 16 + 25 - (min[0] * 16 - 48)) / 4 + 1) as u64
                        * ((max[2] * 16 + 25 - (min[2] * 16 - 48)) / 4 + 1) as u64;
                    (surfaces
                        + centers * 2
                        + (node_side as u64 * node_side as u64 * node_height as u64).div_ceil(4))
                        * 16
                } else {
                    0
                }
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
                self.pipeline.transfer(profile_id, data.len() as u64, 0);
            }
            if self
                .cave_buffers
                .as_ref()
                .is_none_or(|b| b.nodes_size < nodes_size || b.mask_size < storage_mask_size)
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
                        storage_mask_size,
                        wgpu::BufferUsages::STORAGE | wgpu::BufferUsages::COPY_SRC,
                    ),
                    nodes_size,
                    mask_size: storage_mask_size,
                });
            }
        }
        self.queue
            .write_buffer(&self.requests, 0, bytemuck::cast_slice(&gpu_requests));
        for (address, header) in &interpolation_headers {
            self.queue.write_buffer(
                &self.height_nodes,
                address * 4,
                bytemuck::cast_slice(header),
            );
        }
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
        let mut materials = None;
        let mut measured = Vec::new();
        if profile.is_some_and(|p| p.registry_program.is_some()) {
            measured.push(timings::HEIGHT);
        }
        if profile_id != 0 {
            measured.push(timings::SITES);
        }
        measured.push(timings::COLUMNS);
        if density_program.is_some() && !surface_probe {
            measured.extend([
                timings::LAKE_CANDIDATES,
                timings::LAKE_DENSITY,
                timings::LAKE_REDUCE,
            ]);
        }
        if cave_side > 0 {
            measured.extend([timings::CAVE_DENSITY, timings::CAVE_MASK]);
        } else if underground_probe {
            measured.push(timings::CAVE_DENSITY);
        }
        if layered {
            measured.push(timings::MATERIALS);
        }
        if aquifer.is_some_and(|a| a.enabled) {
            measured.push(timings::AQUIFER_FIELDS);
        }
        if aquifer.is_some() {
            measured.push(timings::AQUIFER_MASK);
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
        if density_program.is_some() && horizontal_fields > 0 {
            let mut writes = timestamp_writes(timings::HEIGHT);
            if let Some(ref mut writes) = writes {
                writes.end_of_pass_write_index = None;
            }
            let mut pass = encoder.begin_compute_pass(&wgpu::ComputePassDescriptor {
                label: Some("Retina shared horizontal program fields"),
                timestamp_writes: writes,
            });
            pass.set_pipeline(specialized.as_ref().unwrap().world("horizontal_nodes"));
            pass.set_bind_group(0, group, &[]);
            pass.dispatch_workgroups(density_dispatch.0.div_ceil(64), requests.len() as u32, 1);
        }
        if density_program.is_some() {
            for level in 1..=interpolation_depth {
                let mut writes = if horizontal_fields == 0 && level == 1 {
                    timestamp_writes(timings::HEIGHT)
                } else {
                    None
                };
                if let Some(ref mut w) = writes {
                    w.end_of_pass_write_index = None;
                }
                let mut pass = encoder.begin_compute_pass(&wgpu::ComputePassDescriptor {
                    label: Some("Retina resident registered interpolation fields"),
                    timestamp_writes: writes,
                });
                let entry = format!("interpolation_nodes_{level}");
                let fields = if self.specialized_interpolation {
                    specialized.as_deref().or(terrain)
                } else {
                    terrain
                };
                fields.unwrap().world_selected(&entry).bind(&mut pass);
                pass.set_bind_group(0, group, &[]);
                pass.dispatch_workgroups(
                    interpolation_dispatch.div_ceil(64),
                    profile
                        .unwrap()
                        .registry_program
                        .as_ref()
                        .unwrap()
                        .interpolations
                        .len() as u32,
                    requests.len() as u32,
                );
            }
            let mut writes = if horizontal_fields > 0 {
                None
            } else if interpolation_depth > 0 {
                None
            } else {
                timestamp_writes(timings::HEIGHT)
            };
            if let Some(ref mut writes) = writes {
                writes.end_of_pass_write_index = None;
            }
            let mut pass = encoder.begin_compute_pass(&wgpu::ComputePassDescriptor {
                label: Some("Retina registered 3D density lattice"),
                timestamp_writes: writes,
            });
            let cached_lattice = self.cached_density_masks
                && composition
                && self
                    .cached_density_plans
                    .get(&profile_id)
                    .is_some_and(|plan| {
                        gpu_requests
                            .iter()
                            .zip(&interpolation_headers)
                            .all(|(r, (_, header))| plan.covers_lattice(r, header))
                            && interpolation_headers.len() == gpu_requests.len()
                    });
            (selected
                .filter(|_| cached_lattice)
                .and_then(|p| p.cached_world("density_nodes"))
                .map(crate::specialize::Selected::from)
                .unwrap_or_else(|| terrain_pipeline("density_nodes", &self.density_pipeline)))
            .bind(&mut pass);
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
            (if surface_probe && !align_shores {
                terrain_pipeline("climate_nodes", &self.climate_pipeline)
            } else {
                terrain_pipeline("height_nodes", &self.height_pipeline)
            })
            .bind(&mut pass);
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
            let cached_surface = self.cached_density_masks
                && composition
                && self
                    .cached_density_plans
                    .get(&profile_id)
                    .is_some_and(|plan| {
                        gpu_requests
                            .iter()
                            .zip(&interpolation_headers)
                            .all(|(r, (_, header))| plan.covers_surface(r, header))
                            && interpolation_headers.len() == gpu_requests.len()
                    });
            (selected
                .filter(|_| cached_surface)
                .and_then(|p| p.cached_world("surface_columns"))
                .map(crate::specialize::Selected::from)
                .unwrap_or_else(|| terrain_pipeline("surface_columns", &self.surface_pipeline)))
            .bind(&mut pass);
            pass.set_bind_group(0, group, &[]);
            pass.dispatch_workgroups(surface_dispatch.div_ceil(64), requests.len() as u32, 1);
        }
        if profile_id != 0 {
            let mut pass = encoder.begin_compute_pass(&wgpu::ComputePassDescriptor {
                label: Some("Retina biome site pass"),
                timestamp_writes: timestamp_writes(timings::SITES),
            });
            (world_pipeline("biome_sites", &self.sites_pipeline)).bind(&mut pass);
            pass.set_bind_group(0, group, &[]);
            pass.dispatch_workgroups(3, requests.len() as u32, 1);
        }
        if density_program.is_some() && !surface_probe {
            let mut pass = encoder.begin_compute_pass(&wgpu::ComputePassDescriptor {
                label: Some("Retina lake candidate classification"),
                timestamp_writes: timestamp_writes(timings::LAKE_CANDIDATES),
            });
            (world_pipeline("lake_candidates", &self.lake_candidates_pipeline)).bind(&mut pass);
            pass.set_bind_group(0, group, &[]);
            pass.dispatch_workgroups(lake_dispatch.div_ceil(64), requests.len() as u32, 1);
        }
        if density_program.is_some() && !surface_probe {
            if sparse_lakes {
                let mut writes = timestamp_writes(timings::LAKE_DENSITY);
                if let Some(ref mut w) = writes {
                    w.end_of_pass_write_index = None;
                }
                let mut pass = encoder.begin_compute_pass(&wgpu::ComputePassDescriptor {
                    label: Some("Retina sparse lake interpolation corners"),
                    timestamp_writes: writes,
                });
                pass.set_pipeline(
                    selected
                        .unwrap()
                        .cached_world("lake_sparse_fields")
                        .unwrap(),
                );
                pass.set_bind_group(0, group, &[]);
                pass.dispatch_workgroups(
                    sparse_dispatch.div_ceil(64),
                    sparse_plan.as_ref().unwrap().field_count(),
                    requests.len() as u32,
                );
            }
            let mut writes = timestamp_writes(timings::LAKE_DENSITY);
            if sparse_lakes {
                if let Some(ref mut w) = writes {
                    w.beginning_of_pass_write_index = None;
                }
            }
            let mut pass = encoder.begin_compute_pass(&wgpu::ComputePassDescriptor {
                label: Some("Retina parallel lake density probes"),
                timestamp_writes: writes,
            });
            (if sparse_lakes {
                crate::specialize::Selected::from(
                    selected
                        .unwrap()
                        .cached_world("lake_density_sparse")
                        .unwrap(),
                )
            } else {
                world_pipeline("lake_density", &self.lake_density_pipeline)
            })
            .bind(&mut pass);
            pass.set_bind_group(0, group, &[]);
            pass.dispatch_workgroups(
                (lake_dispatch * 20).div_ceil(64),
                density_dispatch.1,
                requests.len() as u32,
            );
        }
        if density_program.is_some() && !surface_probe {
            let mut pass = encoder.begin_compute_pass(&wgpu::ComputePassDescriptor {
                label: Some("Retina shared lake level extraction"),
                timestamp_writes: timestamp_writes(timings::LAKE_REDUCE),
            });
            (world_pipeline("lake_nodes", &self.lake_pipeline)).bind(&mut pass);
            pass.set_bind_group(0, group, &[]);
            pass.dispatch_workgroups(lake_dispatch.div_ceil(64), requests.len() as u32, 1);
        }
        {
            let mut pass = encoder.begin_compute_pass(&wgpu::ComputePassDescriptor {
                label: Some("Retina interpolated column pass"),
                timestamp_writes: timestamp_writes(timings::COLUMNS),
            });
            (if surface_probe && !align_shores {
                world_pipeline("biome_queries", &self.biome_queries_pipeline)
            } else {
                world_pipeline("main", &self.columns_pipeline)
            })
            .bind(&mut pass);
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
                (cave_pipeline("underground_queries", &self.underground_queries_pipeline))
                    .bind(&mut pass);
                pass.set_bind_group(0, &cave_group, &[]);
                pass.dispatch_workgroups(1, count as u32, 1);
            } else {
                if aquifer.is_some_and(|a| a.enabled) {
                    let r = requests[0];
                    let min = [
                        (r.origin_x + 10).div_euclid(16),
                        (r.min_y + 1).div_euclid(12) - 1,
                        (r.origin_z + 10).div_euclid(16),
                    ];
                    let max = [
                        (r.origin_x + cave_side as i32 * 16 + 11).div_euclid(16) + 1,
                        r.max_y.div_euclid(12) + 1,
                        (r.origin_z + cave_side as i32 * 16 + 11).div_euclid(16) + 1,
                    ];
                    let centers = (max[0] - min[0] + 1) as u32
                        * (max[1] - min[1] + 1) as u32
                        * (max[2] - min[2] + 1) as u32;
                    let surfaces = ((max[0] * 16 + 25 - (min[0] * 16 - 48)) / 4 + 1) as u32
                        * ((max[2] * 16 + 25 - (min[2] * 16 - 48)) / 4 + 1) as u32;
                    for (i, (name, pipeline, items)) in [
                        ("aquifer_surface", &self.aquifer_surface_pipeline, surfaces),
                        ("aquifer_centers", &self.aquifer_centers_pipeline, centers),
                        (
                            "aquifer_barrier",
                            &self.aquifer_barrier_pipeline,
                            (node_side * node_side * node_height).div_ceil(4),
                        ),
                    ]
                    .into_iter()
                    .enumerate()
                    {
                        let mut writes = timestamp_writes(timings::AQUIFER_FIELDS);
                        if let Some(ref mut w) = writes {
                            if i != 0 {
                                w.beginning_of_pass_write_index = None;
                            }
                            if i != 2 {
                                w.end_of_pass_write_index = None;
                            }
                        }
                        if i == 1 {
                            writes = None;
                        }
                        let mut pass = encoder.begin_compute_pass(&wgpu::ComputePassDescriptor {
                            label: Some(name),
                            timestamp_writes: writes,
                        });
                        (cave_pipeline(name, pipeline)).bind(&mut pass);
                        pass.set_bind_group(0, &cave_group, &[]);
                        pass.dispatch_workgroups(items.div_ceil(64), 1, 1);
                    }
                }
                {
                    let mut pass = encoder.begin_compute_pass(&wgpu::ComputePassDescriptor {
                        label: Some("Retina cave density pass"),
                        timestamp_writes: timestamp_writes(timings::CAVE_DENSITY),
                    });
                    (cave_pipeline("cave_nodes", &self.cave_nodes_pipeline)).bind(&mut pass);
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
                    (cave_pipeline("cave_exterior", &self.cave_exterior_pipeline)).bind(&mut pass);
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
                    (cave_pipeline("cave_mask", &self.cave_mask_pipeline)).bind(&mut pass);
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
                if aquifer.is_some() {
                    let mut pass = encoder.begin_compute_pass(&wgpu::ComputePassDescriptor {
                        label: Some("Retina local aquifer fluids and pressure barriers"),
                        timestamp_writes: timestamp_writes(timings::AQUIFER_MASK),
                    });
                    (cave_pipeline("aquifer_mask", &self.aquifer_mask_pipeline)).bind(&mut pass);
                    pass.set_bind_group(0, &cave_group, &[]);
                    pass.dispatch_workgroups(256, volume_words.div_ceil(16384) as u32, 1);
                }
            }
            if layered {
                {
                    let mut pass = encoder.begin_compute_pass(&wgpu::ComputePassDescriptor {
                        label: Some("Retina GPU material run counts"),
                        timestamp_writes: timestamp_writes(timings::MATERIALS),
                    });
                    (cave_pipeline("material_counts", &self.material_counts_pipeline))
                        .bind(&mut pass);
                    pass.set_bind_group(0, &cave_group, &[]);
                    pass.dispatch_workgroups((material_columns as u32).div_ceil(64), 1, 1);
                }
                for (pipeline, groups) in [
                    (
                        &self.material_prefix_blocks_pipeline,
                        (material_columns as u32).div_ceil(256),
                    ),
                    (&self.material_prefix_total_pipeline, 1),
                ] {
                    let mut pass =
                        encoder.begin_compute_pass(&wgpu::ComputePassDescriptor::default());
                    pass.set_pipeline(pipeline);
                    pass.set_bind_group(0, &cave_group, &[]);
                    pass.dispatch_workgroups(groups, 1, 1);
                }
                // Immutable GPU snapshots allow other submitted regions to overwrite
                // the shared density buffers while this job waits for its exact run count.
                let snapshot = |label, size| {
                    self.device.create_buffer(&wgpu::BufferDescriptor {
                        label: Some(label),
                        size,
                        usage: wgpu::BufferUsages::STORAGE
                            | wgpu::BufferUsages::COPY_DST
                            | wgpu::BufferUsages::COPY_SRC,
                        mapped_at_creation: false,
                    })
                };
                let columns = snapshot(
                    "Retina material job columns",
                    count as u64 * COLUMNS as u64 * std::mem::size_of::<Column>() as u64,
                );
                let mask = snapshot(
                    "Retina material job mask and run offsets",
                    storage_mask_size,
                );
                let requests = snapshot(
                    "Retina material job descriptor",
                    std::mem::size_of::<GpuRequest>() as u64,
                );
                encoder.copy_buffer_to_buffer(&self.output, 0, &columns, 0, columns.size());
                encoder.copy_buffer_to_buffer(&buffers.mask, 0, &mask, 0, storage_mask_size);
                encoder.copy_buffer_to_buffer(&self.requests, 0, &requests, 0, requests.size());
                let emit = cave_pipeline("material_emit", &self.material_emit_pipeline);
                materials = Some(MaterialPending {
                    columns,
                    mask,
                    requests,
                    pipeline: emit.pipeline.clone(),
                    dispatch: emit.dispatch,
                });
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
        self.pipeline.transfer(
            profile_id,
            (gpu_requests.len() * std::mem::size_of::<GpuRequest>()) as u64
                + interpolation_headers
                    .iter()
                    .map(|(_, h)| h.len() as u64 * 4)
                    .sum::<u64>(),
            size + if cave_side > 0 || underground_probe {
                mask_size
            } else {
                0
            } + if slot.timestamps.is_some() {
                query_bytes
            } else {
                0
            },
        );
        let encode_nanos = host_start.elapsed().as_nanos() as u64;
        timings.add(timings::ENCODE, encode_nanos);
        let wait_start = Instant::now();
        let mut job_timings = timings::Snapshot::default();
        job_timings.version = timings::VERSION;
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
            materials,
            aquifer: aquifer.is_some(),
        })
    }
    fn emit_materials(
        &mut self,
        job: &MaterialPending,
        request: GpuRequest,
        count: usize,
        timings: &timings::Timings,
        trace: &mut timings::Snapshot,
    ) -> Result<Vec<u32>, String> {
        let start = Instant::now();
        let size = count as u64 * 4;
        let buffer = self.device.create_buffer(&wgpu::BufferDescriptor {
            label: Some("Retina exact material runs"),
            size: job.mask.size() + size,
            usage: wgpu::BufferUsages::STORAGE
                | wgpu::BufferUsages::COPY_DST
                | wgpu::BufferUsages::COPY_SRC,
            mapped_at_creation: false,
        });
        let group = self.device.create_bind_group(&wgpu::BindGroupDescriptor {
            label: Some("Retina material emission"),
            layout: &self.cave_layout,
            entries: &[
                wgpu::BindGroupEntry {
                    binding: 0,
                    resource: job.requests.as_entire_binding(),
                },
                wgpu::BindGroupEntry {
                    binding: 1,
                    resource: job.columns.as_entire_binding(),
                },
                wgpu::BindGroupEntry {
                    binding: 2,
                    resource: self.cave_profiles[&request.profile].as_entire_binding(),
                },
                wgpu::BindGroupEntry {
                    binding: 3,
                    resource: self
                        .cave_buffers
                        .as_ref()
                        .unwrap()
                        .nodes
                        .as_entire_binding(),
                },
                wgpu::BindGroupEntry {
                    binding: 4,
                    resource: buffer.as_entire_binding(),
                },
                wgpu::BindGroupEntry {
                    binding: 5,
                    resource: self.profiles[&request.profile].2.as_entire_binding(),
                },
                wgpu::BindGroupEntry {
                    binding: 6,
                    resource: self.height_nodes.as_entire_binding(),
                },
                wgpu::BindGroupEntry {
                    binding: 7,
                    resource: self.profiles[&request.profile].1.as_entire_binding(),
                },
            ],
        });
        let out = self.readback_buffer("Retina compact material runs", size);
        let timestamps = self.timestamp_support.then(|| self.create_timestamps());
        let mut encoder = self
            .device
            .create_command_encoder(&wgpu::CommandEncoderDescriptor::default());
        encoder.copy_buffer_to_buffer(&job.mask, 0, &buffer, 0, job.mask.size());
        {
            let mut pass = encoder.begin_compute_pass(&wgpu::ComputePassDescriptor {
                label: Some("Retina GPU material run emission"),
                timestamp_writes: timestamps.as_ref().map(|t| t.writes(0)),
            });
            crate::specialize::Selected {
                pipeline: &job.pipeline,
                dispatch: job.dispatch,
            }
            .bind(&mut pass);
            pass.set_bind_group(0, &group, &[]);
            pass.dispatch_workgroups((request.padding * 16).pow(2).div_ceil(64), 1, 1);
        }
        encoder.copy_buffer_to_buffer(&buffer, job.mask.size(), &out, 0, size);
        if let Some(t) = &timestamps {
            encoder.resolve_query_set(&t.queries, 0..2, &t.resolve, 0);
            encoder.copy_buffer_to_buffer(&t.resolve, 0, &t.readback, 0, 16);
        }
        let encode = start.elapsed().as_nanos() as u64;
        timings.add(timings::ENCODE, encode);
        trace.nanos[timings::ENCODE] += encode;
        let submission = self.queue.submit([encoder.finish()]);
        let mut mapping = Mapping::new(&out, size);
        let mut query = timestamps.as_ref().map(|t| Mapping::new(&t.readback, 16));
        self.device
            .poll(wgpu::PollType::Wait {
                submission_index: Some(submission),
                timeout: Some(Duration::from_secs(30)),
            })
            .map_err(|e| format!("GPU material poll: {e}"))?;
        mapping.check()?;
        let words = {
            let view = out
                .slice(..size)
                .get_mapped_range()
                .map_err(|e| e.to_string())?;
            bytemuck::cast_slice::<u8, u32>(&view).to_vec()
        };
        if let Some(m) = &mut query {
            m.check()?;
            let view = timestamps
                .as_ref()
                .unwrap()
                .readback
                .slice(..16)
                .get_mapped_range()
                .map_err(|e| e.to_string())?;
            let values = bytemuck::cast_slice::<u8, u64>(&view);
            if values[0] != 0 && values[1] >= values[0] {
                let nanos = ((values[1] - values[0]) as f64
                    * self.queue.get_timestamp_period() as f64) as u64;
                timings.add(timings::MATERIALS, nanos);
                trace.nanos[timings::MATERIALS] += nanos;
                // Emission can follow another pending job's first submission.
                // Count its span, without labelling that intervening job as idle.
                self.pipeline.device(nanos, 0);
                self.last_device_tick = Some(self.last_device_tick.unwrap_or(0).max(values[1]));
            } else {
                self.pipeline.unavailable();
            }
        }
        self.pipeline.transfer(
            request.profile,
            0,
            size + if timestamps.is_some() { 16 } else { 0 },
        );
        Ok(words)
    }
    fn create_timestamps(&self) -> Timestamps {
        Timestamps {
            queries: self.device.create_query_set(&wgpu::QuerySetDescriptor {
                label: Some("Retina slot pass timings"),
                ty: wgpu::QueryType::Timestamp,
                count: Timestamps::PAIRS * 2,
            }),
            resolve: self.device.create_buffer(&wgpu::BufferDescriptor {
                label: Some("Retina slot timestamp resolve"),
                size: 256,
                usage: wgpu::BufferUsages::QUERY_RESOLVE | wgpu::BufferUsages::COPY_SRC,
                mapped_at_creation: false,
            }),
            readback: self.readback_buffer(
                "Retina slot timestamp readback",
                u64::from(Timestamps::PAIRS) * 16,
            ),
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
        let mut mask = if let Some(mapping) = &mut pending.mask_map {
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
                    air_only: pending.aquifer,
                    words,
                    columns: None,
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
                pending.timings.nanos[*stage] += nanos;
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
        if let (Some(materials), Some(mask)) = (&pending.materials, &mut mask) {
            let count = mask
                .material_run_count()
                .ok_or("missing GPU material run header")?;
            let runs = self.emit_materials(
                materials,
                pending.request,
                count,
                timings,
                &mut pending.timings,
            )?;
            mask.words.extend(runs);
            mask.validate_material_runs()?;
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
    materials: Option<MaterialPending>,
    aquifer: bool,
}
struct MaterialPending {
    columns: wgpu::Buffer,
    mask: wgpu::Buffer,
    requests: wgpu::Buffer,
    pipeline: wgpu::ComputePipeline,
    dispatch: Option<u32>,
}
impl PendingSample {
    fn ready(&mut self) -> bool {
        self.columns_map.ready()
            && self.mask_map.as_mut().is_none_or(|m| m.ready())
            && self.query_map.as_mut().is_none_or(|m| m.ready())
    }
}

#[cfg(test)]
mod aquifer_columns_tests;
#[cfg(test)]
mod column_interpreter_tests;
#[cfg(test)]
mod lake_sparse_tests;

#[cfg(test)]
mod mapping_tests {
    use super::*;
    use std::fmt::Write;
    #[test]
    fn rejected_specialization_leaves_interpreter_device_usable() {
        let mut gpu = Gpu::new(std::sync::Arc::new(crate::pipeline::Metrics::default())).unwrap();
        assert!(
            crate::specialize::compile(
                &gpu.device,
                &gpu.layout,
                &gpu.cave_layout,
                "invalid WGSL",
                0,
                false,
                0
            )
            .is_err()
        );
        let request = GpuRequest::from(crate::ChunkRequest {
            seed: 123456789,
            chunk_x: -33,
            chunk_z: 32,
            min_y: -64,
            height: 384,
            base_height: 64.0,
            amplitude: 48.0,
            frequency: 0.008,
            reserved: 0,
        });
        let metrics = timings::Timings::default();
        let mut pending = gpu.submit(&[request], None, &metrics).unwrap();
        gpu.device
            .poll(wgpu::PollType::Wait {
                submission_index: None,
                timeout: Some(Duration::from_secs(30)),
            })
            .unwrap();
        assert!(gpu.ready(&mut pending).unwrap());
        let sample = gpu.complete(pending, &metrics).unwrap();
        assert_eq!(sample.columns.len(), 256);
        assert!(
            sample
                .columns
                .iter()
                .all(|c| c.height > -64 && c.height <= 320)
        );
    }
    #[test]
    #[ignore = "requires an exported registry profile in RETINA_PROGRAM_PARITY_PROFILE"]
    fn specialized_roots_match_interpreter_on_real_gpu() {
        let path = std::env::var("RETINA_PROGRAM_PARITY_PROFILE").unwrap();
        let bytes = std::fs::read(path).unwrap();
        let fixture: serde_json::Value = serde_json::from_slice(&bytes).unwrap();
        let mut profile = WorldProfile::parse(&bytes).unwrap();
        if let Ok(roots) = std::env::var("RETINA_PROGRAM_PARITY_ROOTS") {
            profile.registry_program.as_mut().unwrap().programs[0].roots =
                roots.split(',').map(|r| r.parse().unwrap()).collect();
        }
        let registry = profile.registry_program.as_ref().unwrap();
        let interpreted = std::env::var_os("RETINA_PROGRAM_PARITY_INTERPRETER").is_some();
        let primed_corners = std::env::var_os("RETINA_PROGRAM_PARITY_PRIMED_CORNERS").is_some();
        let point_cached =
            primed_corners || std::env::var_os("RETINA_PROGRAM_PARITY_POINT_CACHE").is_some();
        let field_cached = std::env::var_os("RETINA_PROGRAM_PARITY_INTERPOLATION_CACHE").is_some();
        // Specialized interpolation inputs can read the horizontal atlas on
        // aligned corners. Populate both caches, as production does, even when
        // the requested oracle only targets interpolation-field reuse.
        let cached = std::env::var_os("RETINA_PROGRAM_PARITY_CACHE").is_some()
            || field_cached && !interpreted;
        let bounds_check = std::env::var_os("RETINA_PROGRAM_PARITY_BOUNDS").is_some();
        let samples_per_program = if bounds_check { 256 } else { 64 };
        let programs = if bounds_check {
            registry.programs.len().min(3)
        } else {
            registry.programs.len()
        };
        assert!(
            !interpreted || !cached,
            "compact interpreter has no horizontal cache"
        );
        let far =
            (cached || field_cached) && std::env::var_os("RETINA_PROGRAM_PARITY_FAR").is_some();
        let mut gpu = Gpu::new(std::sync::Arc::new(crate::pipeline::Metrics::default())).unwrap();
        gpu.add_profile(
            1,
            &profile.gpu_bytes(),
            &profile.climate_gpu_bytes(),
            &crate::program::RegistryProgram::bytes(Some(&profile)),
        );
        let mut kernel = String::from(
            "@compute @workgroup_size(64) fn parity(@builtin(global_invocation_id) id:vec3<u32>){let program=id.x/64u;let sample=id.x%64u;",
        );
        kernel.push_str(&format!("if program>={}u{{return;}}", programs));
        kernel.push_str("let seed=min(sample/21u,2u);var r=requests[0];r.seed_low^=seed*7919u;let point=vec3<f32>(f32(i32(sample%8u)*131-513),f32(i32(sample)*6-64),f32(i32(sample/8u)*127-511));let context=vec4<f32>(f32(sample%8u+1u),f32(sample%3u+3u),f32(sample%6u),f32(sample%7u));let reference=run_reference(program,point,r,context);var actual:array<f32,6>;switch program{case 0u:{actual=run_climate(point,r,context);}case 1u:{actual=run_surface_density(point,r,context);}case 2u:{actual=run_final_density(point,r,context);}default:{actual=run_program(program,point,r,context);}}var expected=reference;COORDINATE_EXPECTED let at=id.x*6u;columns[at]=Column(bitcast<i32>(reference[0]),bitcast<u32>(reference[1]),bitcast<u32>(reference[2]));columns[at+1u]=Column(bitcast<i32>(reference[3]),bitcast<u32>(reference[4]),bitcast<u32>(reference[5]));columns[at+2u]=Column(bitcast<i32>(actual[0]),bitcast<u32>(actual[1]),bitcast<u32>(actual[2]));columns[at+3u]=Column(bitcast<i32>(actual[3]),bitcast<u32>(actual[4]),bitcast<u32>(actual[5]));columns[at+4u]=Column(bitcast<i32>(expected[0]),bitcast<u32>(expected[1]),bitcast<u32>(expected[2]));columns[at+5u]=Column(bitcast<i32>(expected[3]),bitcast<u32>(expected[4]),bitcast<u32>(expected[5]));}");
        let coordinate_expected = fixture.get("coordinate_expected_roots");
        let coordinate_noise = fixture.get("coordinate_noise").map(|n| n.as_u64().unwrap());
        let interpolation_noise = fixture
            .get("interpolation_noise")
            .map(|n| n.as_u64().unwrap());
        let mut expected_kernel = coordinate_noise.map_or(String::new(), |noise| format!(
            "if program==1u{{let clamped=clamp(point,vec3<f32>(-1024.0),vec3<f32>(1024.0))*0.125;expected[0]=program_noise(vec3<f32>(point.x*0.5+17.0*0.125,17.0*0.25+clamped.z,point.z*0.5+clamped.x),{noise}u,r);expected[1]=program_noise(vec3<f32>(-96.0,point.y,point.z)*0.25,{noise}u,r)*4.0;expected[2]=program_noise(vec3<f32>(point.x,0.0,160.0)*0.25,{noise}u,r)*4.0;expected[3]=program_noise(vec3<f32>(-96.0,17.0,0.0)*0.25,{noise}u,r)*4.0;expected[4]=run_reference(2u,vec3<f32>(point.x,32.0,point.z),r,context)[0];expected[5]=run_reference(3u,vec3<f32>(17.0,point.y,point.z),r,context)[0];}}"
        ));
        if let Some(noise) = interpolation_noise {
            // Independent eight-corner noise reference, not another call to
            // the emitted interpolation graph/evaluator.
            let helper = format!(
                "fn parity_interpolated_noise(point:vec3<f32>,r:Request)->f32{{let size=vec3<f32>(4.0,8.0,4.0);let lower=floor(point/size)*size;let t=(point-lower)/size;var c:array<f32,8>;for(var i=0u;i<8u;i++){{let offset=vec3<f32>(f32(i&1u),f32((i>>1u)&1u),f32((i>>2u)&1u));c[i]=program_noise((lower+offset*size)*vec3<f32>(0.125,0.25,0.125),{noise}u,r);}}return interpolation_mix(c,t);}}\n"
            );
            kernel = format!("{helper}{kernel}");
            expected_kernel.push_str(&format!("if program==1u{{expected[0]=parity_interpolated_noise(point,r);expected[1]=program_noise(point*vec3<f32>(0.125,0.25,0.125),{noise}u,r);}}"));
        }
        kernel = kernel.replace("COORDINATE_EXPECTED", &expected_kernel);
        if bounds_check {
            kernel = kernel.replace("let at=id.x*6u;", "let cell=floor(point/vec3<f32>(4.0,8.0,4.0))*vec3<f32>(4.0,8.0,4.0);let bound=run_density_bounds(program,cell,cell+vec3<f32>(4.0,8.0,4.0),r);expected[0]=bound.x;expected[1]=bound.y;expected[2]=reference[0];let at=id.x*6u;");
            kernel = kernel
                .replace("/64u", "/256u")
                .replace("%64u", "%256u")
                .replace("sample/21u", "sample/85u")
                .replace("f32(i32(sample)*6-64)", "f32(i32(sample%64u)*6-64)+0.375");
        }
        // The reference deliberately bypasses the field cache, including in
        // nested helpers. Agreement cannot conceal incorrect cached samples.
        kernel = kernel.replace("let reference=run_reference(program,point,r,context);", "var direct=r;direct.padding&=~(1u<<27u);let reference=run_reference(program,point,direct,context);");
        if cached || field_cached {
            kernel = kernel
                .replace(
                    "var r=requests[0];r.seed_low^=seed*7919u;",
                    "let r=requests[seed];",
                )
                .replace(
                    "f32(i32(sample%8u)*131-513)",
                    "f32(i32(sample%8u)*4-36)+select(0.0,0.25,sample%3u==1u)",
                )
                .replace(
                    "f32(i32(sample/8u)*127-511)",
                    "f32(i32(sample/8u)*4-36)+select(0.0,512.0,sample%3u==2u)",
                );
        }
        if far {
            kernel = kernel
                .replace(
                    "f32(i32(sample%8u)*4-36)+select(0.0,0.25,sample%3u==1u)",
                    "f32(density_origin(r).x)+f32(sample%8u)*3.0",
                )
                .replace(
                    "f32(i32(sample/8u)*4-36)+select(0.0,512.0,sample%3u==2u)",
                    "f32(density_origin(r).z)+f32(sample/8u)*3.0",
                );
        }
        if point_cached {
            assert!(!interpreted && !bounds_check);
            // Repeated queries within one invocation exercise reuse, newly needed
            // corners, cell changes and context invalidation. The independent
            // interpreter always bypasses both caches. Count every root mismatch,
            // rather than allowing aggregate checksums to cancel differences.
            kernel = format!(
                "@compute @workgroup_size(64) fn parity(@builtin(global_invocation_id) id:vec3<u32>){{
let program=id.x/64u;let sample=id.x%64u;if program>={programs}u{{return;}}
var r=requests[0];r.seed_low^=min(sample/21u,2u)*7919u;r.padding&=~(1u<<27u);
var mismatches=0u;
for(var query=0u;query<24u;query++){{
let phase=query%12u;
let base=select(f32(i32(sample%8u)*11-48),16777210.0,sample%4u==3u);
let point=vec3<f32>(base+select(0.0,0.375,phase%3u!=0u)+select(0.0,9.0,phase>=8u),
f32(i32(sample%16u)*5-64)-f32(phase)*0.5,f32(i32(sample/8u)*13-48)+select(0.0,0.625,phase%4u!=0u));
let context=vec4<f32>(f32(sample%8u+1u),f32(sample%3u+3u),f32(sample%6u)+select(0.0,1.0,query>=12u),f32(sample%7u));
let reference=run_reference(program,point,r,context);var actual:array<f32,6>;
switch program{{case 0u:{{actual=run_climate(point,r,context);}}case 1u:{{actual=run_surface_density(point,r,context);}}case 2u:{{actual=run_final_density(point,r,context);}}default:{{actual=run_program(program,point,r,context);}}}}
for(var root=0u;root<6u;root++){{if bitcast<u32>(reference[root])!=bitcast<u32>(actual[root]){{mismatches+=1u;}}}}
}}
let at=id.x*6u;
for(var slot=0u;slot<6u;slot++){{columns[at+slot]=Column(0,0u,0u);}}
columns[at+2u]=Column(i32(mismatches),0u,0u);
}}"
            );
        }
        let mut reference = crate::program::interpreter_body(
            1024,
            registry.interpolation_depth(),
            "run_reference",
            false,
        );
        if primed_corners {
            // The original bounds implementation samples the independent
            // interpreter and bypasses both caches. Check endpoints bit for bit,
            // then sample every graph with the newly primed production cache.
            let mut bound_reference = crate::program::density_bounds_source(1024);
            for symbol in [
                "density_composed",
                "bounds_unknown",
                "bounds_finite",
                "bounds_widen",
                "bounds_product",
                "interpolation_bounds",
                "run_density_bounds",
            ] {
                bound_reference = bound_reference.replace(symbol, &format!("parity_{symbol}"));
            }
            bound_reference = bound_reference.replace(
                "run_interpolation_input",
                "parity_reference_interpolation_input",
            );
            reference.push_str("\nfn parity_reference_interpolation_input(field:u32,point:vec3<f32>,r:Request)->f32{return run_reference(bytecode[15]+field,point,r,vec4<f32>(0.0))[0];}\n");
            reference.push_str(&bound_reference);
            kernel = kernel.replace("var mismatches=0u;", "var mismatches=0u;var primed=0u;");
            kernel = kernel.replace("let context=vec4<f32>(", "let changed_context=vec4<f32>(");
            kernel = kernel.replace(
                "let reference=run_reference(program,point,r,context);",
                "let context=select(vec4<f32>(0.0),changed_context,query>=12u);\n\
                let step=vec3<f32>(4.0,8.0,4.0);let cell=floor(point/step)*step;\n\
                var lo=cell;var hi=cell+step;\n\
                switch phase%6u{\n\
                case 0u:{lo=point;hi=point;}\n\
                case 1u:{hi=cell+step*2.0;}\n\
                case 2u:{lo=point;hi=point+vec3<f32>(0.125);}\n\
                case 3u:{hi=cell+vec3<f32>(0.0,8.0,0.0);}\n\
                case 4u:{hi=cell+vec3<f32>(4096.0);}\n\
                default:{}\n}\n\
                let original_bound=parity_run_density_bounds(1u,lo,hi,r);\n\
                let bound=run_density_bounds(1u,lo,hi,r);\n\
                if any(bitcast<vec2<u32>>(original_bound)!=bitcast<vec2<u32>>(bound)){mismatches+=1u;}\n\
                PRIMED_MASK_COUNT\n\
                let reference=run_reference(program,point,r,context);",
            );
            let masks = (0..registry.interpolations.len())
                .map(|field| format!("primed+=countOneBits(lake_field_{field}_mask);\n"))
                .collect::<String>();
            kernel = kernel.replace("PRIMED_MASK_COUNT", &masks);
            kernel = kernel.replace(
                "columns[at+2u]=Column(i32(mismatches),0u,0u);",
                "columns[at+2u]=Column(i32(mismatches),0u,0u);columns[at+5u]=Column(i32(primed),0u,0u);",
            );
        }
        let mut program_source = if interpreted {
            kernel = kernel.replace("var actual:array<f32,6>;switch program{case 0u:{actual=run_climate(point,r,context);}case 1u:{actual=run_surface_density(point,r,context);}case 2u:{actual=run_final_density(point,r,context);}default:{actual=run_program(program,point,r,context);}}", "let actual=run_program(program,point,r,context);");
            let capacity = if registry.scratch_values() <= crate::program::COMPACT_VALUES {
                crate::program::COMPACT_VALUES
            } else {
                1024
            };
            crate::program::interpreter_source_depth(capacity, registry.interpolation_depth())
        } else {
            // Production aquifer stages use direct calls and deliberately stay
            // outside material dispatch. Exercise those calls in full-profile
            // parity checks as well, rather than comparing the dispatch default.
            if let Some(aquifer) = registry.aquifer.as_ref().filter(|a| a.enabled) {
                let cases = (0..5)
                    .map(|slot| {
                        format!(
                            "case {}u:{{actual=run_aquifer_{slot}(point,r,context);}}",
                            aquifer.program + slot
                        )
                    })
                    .collect::<String>();
                kernel = kernel.replace(
                    "default:{actual=run_program(program,point,r,context);}",
                    &format!("{cases}default:{{actual=run_program(program,point,r,context);}}"),
                );
            }
            crate::specialize::source(registry).unwrap()
        };
        if point_cached {
            if primed_corners {
                writeln!(
                    program_source,
                    "\n{}",
                    crate::program::interpolation::PRIMED_CORNERS_SOURCE_KEY
                )
                .unwrap();
            }
            program_source = crate::program::interpolation::point_cached_source(&program_source);
        }
        let source = format!(
            "{}\n{}\n{}\n{}\n{reference}\n{kernel}",
            include_str!("simplex.wgsl"),
            include_str!("climate.wgsl"),
            program_source,
            include_str!("noise3.wgsl")
        );
        let source = if interpreted {
            source
        } else {
            crate::specialize::static_calls(&source)
        };
        let module = gpu
            .device
            .create_shader_module(wgpu::ShaderModuleDescriptor {
                label: Some("Retina interpreter specialization parity"),
                source: wgpu::ShaderSource::Wgsl(source.into()),
            });
        let layout = gpu
            .device
            .create_pipeline_layout(&wgpu::PipelineLayoutDescriptor {
                label: None,
                bind_group_layouts: &[Some(&gpu.layout)],
                immediate_size: 0,
            });
        let pipeline = gpu
            .device
            .create_compute_pipeline(&wgpu::ComputePipelineDescriptor {
                label: Some("parity"),
                layout: Some(&layout),
                module: &module,
                entry_point: Some("parity"),
                compilation_options: Default::default(),
                cache: None,
            });
        let mut r = GpuRequest {
            origin_x: 0,
            origin_z: 0,
            min_y: -64,
            max_y: 320,
            seed_low: 123456789,
            seed_high: 0,
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
        };
        let mut jobs = vec![r];
        let horizontal = cached.then(|| {
            gpu.device
                .create_compute_pipeline(&wgpu::ComputePipelineDescriptor {
                    label: Some("parity horizontal cache"),
                    layout: Some(&layout),
                    module: &module,
                    entry_point: Some("horizontal_nodes"),
                    compilation_options: Default::default(),
                    cache: None,
                })
        });
        let mut headers = Vec::new();
        let mut field_dispatch = 0;
        let mut field_depth = 0;
        if cached || field_cached {
            r.origin_x = -32;
            r.origin_z = -32;
            r.density_side = 9;
            if far {
                r.origin_x = 16_777_232;
                r.origin_z = -16_777_232;
                r.density_step_xz = 3;
            }
            let fields = if cached {
                crate::column_program::Plan::new(registry).owners.len() as u32
            } else {
                0
            };
            let header_words = if field_cached {
                registry.interpolations.len() as u32 * 8
            } else {
                0
            };
            let floats = header_words + 9 * 9 * 49 + 24 * 24 + 4 + 20 * 49 + 9 * 9 * fields;
            jobs = (0..3)
                .map(|i| GpuRequest {
                    seed_low: r.seed_low ^ (i * 7919),
                    density_offset: SURFACE_METADATA_FLOATS as u32 + i * floats + header_words,
                    ..r
                })
                .collect();
            let mut size = SURFACE_METADATA_FLOATS as u64 + 3 * floats as u64;
            if field_cached {
                let plan = crate::program::interpolation::CachePlan::new(registry);
                for request in &mut jobs {
                    let (header, dispatch) = plan
                        .layout(registry, request, 4, &mut size, u32::MAX as u64, 64 * 65535)
                        .unwrap();
                    request.padding |= crate::program::interpolation::CACHE_FLAG;
                    headers.push((request.density_offset as u64 - header_words as u64, header));
                    field_dispatch = field_dispatch.max(dispatch);
                }
                field_depth = plan.depth;
                assert!(
                    field_dispatch > 0,
                    "fixture must exercise resident interpolation fields"
                );
            }
            gpu.ensure_surface_lattice(size * 4);
        }
        gpu.queue
            .write_buffer(&gpu.requests, 0, bytemuck::cast_slice(&jobs));
        for (address, header) in headers {
            gpu.queue.write_buffer(
                &gpu.height_nodes,
                address * 4,
                bytemuck::cast_slice(&header),
            );
        }
        let count = programs * samples_per_program;
        let size = (count * 72) as u64;
        let output = gpu.readback_buffer("Retina parity roots", size);
        let mut encoder = gpu
            .device
            .create_command_encoder(&wgpu::CommandEncoderDescriptor::default());
        if let Some(horizontal) = horizontal.as_ref() {
            let mut pass = encoder.begin_compute_pass(&wgpu::ComputePassDescriptor::default());
            pass.set_pipeline(horizontal);
            pass.set_bind_group(0, &gpu.profiles[&1].3, &[]);
            pass.dispatch_workgroups(2, 3, 1);
        }
        for level in 1..=field_depth {
            let field_pipeline =
                gpu.device
                    .create_compute_pipeline(&wgpu::ComputePipelineDescriptor {
                        label: Some("parity resident interpolation cache"),
                        layout: Some(&layout),
                        module: &module,
                        entry_point: Some(&format!("interpolation_nodes_{level}")),
                        compilation_options: Default::default(),
                        cache: None,
                    });
            let mut pass = encoder.begin_compute_pass(&wgpu::ComputePassDescriptor::default());
            pass.set_pipeline(&field_pipeline);
            pass.set_bind_group(0, &gpu.profiles[&1].3, &[]);
            pass.dispatch_workgroups(
                field_dispatch.div_ceil(64),
                registry.interpolations.len() as u32,
                3,
            );
        }
        {
            let mut pass = encoder.begin_compute_pass(&wgpu::ComputePassDescriptor::default());
            pass.set_pipeline(&pipeline);
            pass.set_bind_group(0, &gpu.profiles[&1].3, &[]);
            pass.dispatch_workgroups(count.div_ceil(64) as u32, 1, 1);
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
        let mut differences = 0;
        let mut finite_bounds = 0;
        let mut primed_samples = 0;
        for sample in 0..count {
            if primed_corners {
                primed_samples += words[sample * 18 + 15] as usize;
            }
            if bounds_check && sample / samples_per_program > 0 {
                let lower = f32::from_bits(words[sample * 18 + 12]);
                let upper = f32::from_bits(words[sample * 18 + 13]);
                let value = f32::from_bits(words[sample * 18 + 14]);
                assert!(
                    lower <= value && value <= upper,
                    "density bound [{lower}, {upper}] misses {value}, program {}, sample {}",
                    sample / samples_per_program,
                    sample % samples_per_program
                );
                finite_bounds += usize::from(lower.is_finite() && upper.is_finite());
            }
            for root in 0..6 {
                let old = words[sample * 18 + root];
                let new = words[sample * 18 + 6 + root];
                if !point_cached && coordinate_noise.is_some() && sample / 64 == 1 {
                    let expected = words[sample * 18 + 12 + root];
                    assert_eq!(
                        old,
                        expected,
                        "scoped interpreter noise root {root}, sample {}",
                        sample % 64
                    );
                    assert_eq!(
                        new,
                        expected,
                        "scoped specialized noise root {root}, sample {}",
                        sample % 64
                    );
                }
                if !point_cached && interpolation_noise.is_some() && sample / 64 == 1 && root < 2 {
                    let expected = f32::from_bits(words[sample * 18 + 12 + root]);
                    for value in [old, new] {
                        assert!(
                            (f32::from_bits(value) - expected).abs() <= 0.000001,
                            "interpolated GPU noise root {root}, sample {}: {} vs {expected}",
                            sample % 64,
                            f32::from_bits(value)
                        );
                    }
                }
                if !point_cached && !cached && !field_cached && sample / 64 == 0 {
                    if let Some(expected) = coordinate_expected {
                        let expected = expected[sample % 64][root].as_f64().unwrap() as f32;
                        let actual = f32::from_bits(old);
                        assert!(
                            (actual - expected).abs() <= 0.0001,
                            "Minecraft scoped root {root}, sample {}: {actual} vs {expected}",
                            sample % 64
                        );
                    }
                }
                if old != new {
                    if differences < 32 {
                        eprintln!(
                            "program {} sample {} root {}: {:?} ({old:08x}) => {:?} ({new:08x})",
                            sample / 64,
                            sample % 64,
                            root,
                            f32::from_bits(old),
                            f32::from_bits(new)
                        );
                    }
                    differences += 1;
                }
            }
        }
        if bounds_check && !far {
            assert!(finite_bounds >= 32, "fixture must exercise finite bounds");
            eprintln!("Checked {finite_bounds} finite density bounds against direct GPU points");
        }
        assert_eq!(differences, 0, "specialization changed root float bits");
        if point_cached {
            eprintln!(
                "Invocation-local interpolation cache: {} exact root comparisons",
                programs * samples_per_program * 24 * 6
            );
        }
        if primed_corners {
            assert!(
                primed_samples > 0,
                "fixture must actually prime cache corners"
            );
            eprintln!(
                "Primed interval cache: {} exact endpoint comparisons, {primed_samples} populated corner observations",
                programs * samples_per_program * 24 * 2
            );
        }
    }
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
