//! Base interpreters are fallbacks. A world-specific replacement must not force
//! compilation of the unused base entrypoint on its way through selection.
use std::sync::OnceLock;

pub(super) struct BasePipeline {
    compiled: OnceLock<wgpu::ComputePipeline>,
    device: wgpu::Device,
    module: wgpu::ShaderModule,
    layout: wgpu::PipelineLayout,
    label: String,
    entry: String,
    cache: Option<std::sync::Arc<crate::pipeline_cache::Cache>>,
}

impl BasePipeline {
    pub fn new(device: &wgpu::Device, cache: Option<std::sync::Arc<crate::pipeline_cache::Cache>>, eager: bool, descriptor: &wgpu::ComputePipelineDescriptor<'_>) -> Self {
        let pipeline = Self {
            compiled: OnceLock::new(),
            cache,
            device: device.clone(),
            module: descriptor.module.clone(),
            layout: descriptor.layout.expect("base pipeline has an explicit layout").clone(),
            label: descriptor.label.unwrap_or("unnamed").to_owned(),
            entry: descriptor.entry_point.expect("base pipeline has an explicit entrypoint").to_owned(),
        };
        if eager {
            pipeline.get();
        }
        pipeline
    }

    pub fn get(&self) -> &wgpu::ComputePipeline {
        self.compiled.get_or_init(|| crate::pipeline_cache::create(&self.device, &wgpu::ComputePipelineDescriptor {
            label: Some(&self.label),
            layout: Some(&self.layout),
            module: &self.module,
            entry_point: Some(&self.entry),
            compilation_options: Default::default(),
            cache: None,
        }, true, self.cache.as_deref()))
    }
}

impl<'a> From<&'a BasePipeline> for crate::specialize::Selected<'a> {
    fn from(pipeline: &'a BasePipeline) -> Self {
        pipeline.get().into()
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use super::super::*;

    fn bases(gpu: &Gpu) -> Vec<&BasePipeline> {
        vec![&gpu.sites_pipeline, &gpu.biome_queries_pipeline, &gpu.underground_queries_pipeline,
            &gpu.climate_pipeline, &gpu.cave_nodes_pipeline, &gpu.cave_exterior_pipeline,
            &gpu.cave_mask_pipeline, &gpu.aquifer_surface_pipeline, &gpu.aquifer_centers_pipeline,
            &gpu.aquifer_barrier_pipeline, &gpu.aquifer_mask_pipeline, &gpu.material_counts_pipeline,
            &gpu.material_prefix_blocks_pipeline, &gpu.material_prefix_total_pipeline,
            &gpu.material_emit_pipeline, &gpu.columns_pipeline, &gpu.height_pipeline,
            &gpu.density_pipeline, &gpu.lake_pipeline, &gpu.lake_candidates_pipeline,
            &gpu.lake_density_pipeline, &gpu.surface_pipeline]
    }

    #[test]
    #[ignore = "requires a real GPU, RETINA_EAGER_PIPELINES=0 and RETINA_STARTUP_DEFINITION"]
    fn deferred_base_selection_preserves_world_and_procedural_output() {
        assert_eq!(std::env::var("RETINA_EAGER_PIPELINES").as_deref(), Ok("0"));
        let definition = crate::world_definition::WorldDefinition::read(
            std::path::Path::new(&std::env::var("RETINA_STARTUP_DEFINITION").unwrap())).unwrap();
        let mut profile = (**definition.dimension("minecraft:overworld").unwrap().profile().unwrap()).clone();
        profile.program_execution = crate::specialize::Execution::Interpreter;
        let mut gpu = Gpu::new(std::sync::Arc::new(crate::pipeline::Metrics::default())).unwrap();
        assert!(bases(&gpu).iter().all(|p| p.compiled.get().is_none()));
        let times = timings::Timings::default();
        let mut request = GpuRequest::from(crate::ChunkRequest {
            seed: 123456789, chunk_x: -33, chunk_z: -33, min_y: -64, height: 384,
            base_height: 64.0, amplitude: 48.0, frequency: 0.008, reserved: 1,
        });
        request.tile_side = 3;
        request.padding = 1;
        let pending = gpu.submit(&[request], Some(&profile), &times).unwrap();
        let actual = gpu.complete(pending, &times).unwrap();
        // This vanilla profile replaces main/surface with interpolated programs.
        // Neither map_or nor an implicit conversion may compile the unused base.
        assert!(gpu.columns_pipeline.compiled.get().is_none());
        assert!(gpu.surface_pipeline.compiled.get().is_none());
        assert!(gpu.specialized.is_empty(), "forced interpreter never starts specialization");
        let used = bases(&gpu).iter().filter(|p| p.compiled.get().is_some()).count();
        eprintln!("STARTUP_DEMAND used_base={used}/{} columns={} materials={}", bases(&gpu).len(), actual.columns.len(), actual.mask.is_some());

        // Compile the complete former eager inventory and compare exact output.
        for base in bases(&gpu) {
            let first = base.get();
            assert!(std::ptr::eq(first, base.get()), "entrypoint compiled more than once");
        }
        let pending = gpu.submit(&[request], Some(&profile), &times).unwrap();
        let expected = gpu.complete(pending, &times).unwrap();
        super::super::material_continuation_tests::equal(&actual, &expected);

        // Non-registry worlds still reach the same base pipelines and generate.
        let request = GpuRequest::from(crate::ChunkRequest {
            seed: 123456789, chunk_x: -33, chunk_z: 32, min_y: -64, height: 384,
            base_height: 64.0, amplitude: 48.0, frequency: 0.008, reserved: 0,
        });
        let pending = gpu.submit(&[request], None, &times).unwrap();
        let sample = gpu.complete(pending, &times).unwrap();
        assert_eq!(sample.columns.len(), 256);
        assert!(sample.columns.iter().all(|c| c.height > -64 && c.height <= 320));
    }
}
