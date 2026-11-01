//! Shared generation algorithms with backend-specific compiler compatibility.
//! DX12 uses independent entrypoints by default: its combined interpreter can
//! remove an otherwise healthy device during PSO creation, before submission.
#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub(super) struct Policy {
    pub specialized_terrain: bool,
    pub specialized_interpolation: bool,
    pub lake_point_cache: bool,
    pub lake_primed_corners: bool,
    pub lake_sparse_fields: bool,
    pub interpreter_columns: bool,
    pub aquifer_columns: bool,
    pub interpreter_dispatch: bool,
    pub material_dispatch: bool,
    pub eager_pipelines: bool,
    pub defer_specialization: bool,
    pub interpolation_cache: bool,
    pub cached_density_masks: bool,
    pub interpreter_preload: bool,
}

impl Policy {
    pub fn from_env(
        features: wgpu::Features,
        max_immediate_size: u32,
        backend: wgpu::Backend,
    ) -> Self {
        Self::resolve_for_backend(features, max_immediate_size, backend, |key| {
            std::env::var(key).ok()
        })
    }
    #[cfg(test)]
    pub fn resolve(
        features: wgpu::Features,
        max_immediate_size: u32,
        mut read: impl FnMut(&str) -> Option<String>,
    ) -> Self {
        Self::resolve_for_backend(
            features,
            max_immediate_size,
            wgpu::Backend::Metal,
            &mut read,
        )
    }
    pub fn resolve_for_backend(
        features: wgpu::Features,
        max_immediate_size: u32,
        backend: wgpu::Backend,
        mut read: impl FnMut(&str) -> Option<String>,
    ) -> Self {
        let mut flag = |key, default| match read(key).as_deref() {
            Some("0") => false,
            Some("1") => true,
            _ => default,
        };
        // This chooses shader organization after adapter enumeration, not GPU
        // availability. The same stage bodies still execute on the DX12 GPU.
        // Explicit diagnostic overrides may compare the combined path.
        let immediate_dispatch = backend != wgpu::Backend::Dx12
            && features.contains(wgpu::Features::IMMEDIATES)
            && max_immediate_size >= 4;
        let interpreter_dispatch = flag("RETINA_INTERPRETER_DISPATCH", immediate_dispatch);
        let material_dispatch = flag("RETINA_MATERIAL_DISPATCH", immediate_dispatch);
        Self {
            // Generation algorithms remain shared; unused fallbacks compile on demand.
            specialized_terrain: flag("RETINA_SPECIALIZED_TERRAIN", false),
            specialized_interpolation: flag("RETINA_SPECIALIZED_INTERPOLATION", true),
            lake_point_cache: flag("RETINA_LAKE_POINT_CACHE", true),
            lake_primed_corners: flag("RETINA_LAKE_PRIMED_CORNERS", true),
            lake_sparse_fields: flag("RETINA_LAKE_SPARSE_FIELDS", true),
            interpreter_columns: flag("RETINA_INTERPRETER_COLUMNS", true),
            aquifer_columns: flag("RETINA_AQUIFER_COLUMNS", false),
            interpreter_dispatch,
            material_dispatch,
            eager_pipelines: flag("RETINA_EAGER_PIPELINES", false),
            defer_specialization: flag("RETINA_DEFER_SPECIALIZATION", false),
            interpolation_cache: flag("RETINA_INTERPOLATION_CACHE", true),
            cached_density_masks: flag("RETINA_CACHED_DENSITY_MASKS", true),
            interpreter_preload: interpreter_dispatch && flag("RETINA_INTERPRETER_PRELOAD", true),
        }
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    #[test]
    fn dx12_keeps_gpu_algorithms_and_uses_independent_compiler_entrypoints() {
        let shared = Policy::resolve(wgpu::Features::IMMEDIATES, 4, |_| None);
        let independent = Policy {
            interpreter_dispatch: false,
            material_dispatch: false,
            interpreter_preload: false,
            ..shared
        };
        assert_eq!(
            Policy::resolve_for_backend(wgpu::Features::IMMEDIATES, 4, wgpu::Backend::Dx12, |_| {
                None
            }),
            independent
        );
        for backend in [wgpu::Backend::Metal, wgpu::Backend::Vulkan] {
            assert_eq!(
                Policy::resolve_for_backend(wgpu::Features::IMMEDIATES, 4, backend, |_| None),
                shared
            );
        }
        assert_eq!(
            Policy::resolve_for_backend(
                wgpu::Features::IMMEDIATES,
                4,
                wgpu::Backend::Dx12,
                |key| {
                    matches!(
                        key,
                        "RETINA_INTERPRETER_DISPATCH" | "RETINA_MATERIAL_DISPATCH"
                    )
                    .then(|| "1".into())
                }
            ),
            shared
        );
    }

    #[test]
    fn adapter_capabilities_only_change_dispatch_and_its_preload() {
        let shared = Policy::resolve(wgpu::Features::IMMEDIATES, 4, |_| None);
        assert!(
            shared.interpreter_dispatch && shared.material_dispatch && shared.interpreter_preload
        );
        assert!(
            !shared.eager_pipelines,
            "unused fallback pipelines must not compile at startup"
        );
        for (features, size) in [
            (wgpu::Features::empty(), 128),
            (wgpu::Features::IMMEDIATES, 0),
            (wgpu::Features::IMMEDIATES, 3),
        ] {
            let limited = Policy::resolve(features, size, |_| None);
            assert_eq!(
                limited,
                Policy {
                    interpreter_dispatch: false,
                    material_dispatch: false,
                    interpreter_preload: false,
                    ..shared
                }
            );
        }
        assert_eq!(
            shared,
            Policy::resolve(wgpu::Features::IMMEDIATES, 128, |_| None)
        );
    }

    #[test]
    fn overrides_preserve_independent_paths_and_do_not_preempt_device_creation() {
        let shared = Policy::resolve(wgpu::Features::IMMEDIATES, 4, |_| None);
        let independent_interpreter = Policy::resolve(wgpu::Features::IMMEDIATES, 4, |key| {
            (key == "RETINA_INTERPRETER_DISPATCH").then(|| "0".into())
        });
        assert_eq!(
            independent_interpreter,
            Policy {
                interpreter_dispatch: false,
                interpreter_preload: false,
                ..shared
            }
        );
        // A forced request still reaches request_device, which reports the
        // actual unsupported feature/limit instead of a separate preflight gate.
        let forced = Policy::resolve(wgpu::Features::empty(), 0, |key| {
            matches!(
                key,
                "RETINA_INTERPRETER_DISPATCH" | "RETINA_MATERIAL_DISPATCH"
            )
            .then(|| "1".into())
        });
        assert_eq!(forced, shared);
    }
}
