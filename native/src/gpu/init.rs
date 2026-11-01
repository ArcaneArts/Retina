//! Actual adapter/device initialization. Availability is separate from shader,
//! program and configuration failures, so fallback cannot hide implementation bugs.
use super::policy::Policy;
use crate::compute::InitError;
pub(super) struct Device {
    pub device: wgpu::Device,
    pub queue: wgpu::Queue,
    pub info: wgpu::AdapterInfo,
    pub policy: Policy,
}
pub(super) fn open(
    backends: wgpu::Backends,
    resolve: fn(wgpu::Features, u32, wgpu::Backend) -> Policy,
) -> Result<Device, InitError> {
    if backends.is_empty() {
        return Err(InitError::Unavailable(
            "all graphics APIs are disabled".into(),
        ));
    }
    let stage = crate::startup::Stage::new(format!("GPU instance creation requested={backends:?}"));
    let instance = wgpu::Instance::new(crate::gpu_backend::instance_descriptor(backends));
    drop(stage);
    let stage = crate::startup::Stage::new("GPU hardware adapter enumeration");
    let mut adapters: Vec<_> = pollster::block_on(instance.enumerate_adapters(backends))
        .into_iter()
        .map(|a| (a.get_info(), a))
        .collect();
    drop(stage);
    // These are actual initialized adapters, not hardware-name or OS guesses.
    // Integrated and virtual GPUs remain candidates; software graphics belongs
    // to the native CPU path instead of requiring a software graphics driver.
    adapters.sort_by_key(|(info, _)| match info.device_type {
        wgpu::DeviceType::DiscreteGpu => 0,
        wgpu::DeviceType::IntegratedGpu => 1,
        wgpu::DeviceType::VirtualGpu => 2,
        wgpu::DeviceType::Other => 3,
        wgpu::DeviceType::Cpu => 4,
    });
    let mut failures = Vec::new();
    let mut software = false;
    for (info, adapter) in adapters {
        if info.device_type == wgpu::DeviceType::Cpu {
            software = true;
            continue;
        }
        let policy = resolve(adapter.features(), adapter.limits().max_immediate_size, info.backend);
        let immediate = policy.interpreter_dispatch || policy.material_dispatch;
        let stage = crate::startup::Stage::new(format!(
            "GPU device creation {:?}: {}",
            info.backend, info.name
        ));
        let result = pollster::block_on(adapter.request_device(&wgpu::DeviceDescriptor {
            label: Some("Retina terrain device"),
            required_features: (adapter.features()
                & (wgpu::Features::TIMESTAMP_QUERY | wgpu::Features::PIPELINE_CACHE))
                | if immediate {
                    wgpu::Features::IMMEDIATES
                } else {
                    wgpu::Features::empty()
                },
            required_limits: wgpu::Limits {
                max_immediate_size: if immediate { 4 } else { 0 },
                max_storage_buffer_binding_size: adapter.limits().max_storage_buffer_binding_size,
                max_buffer_size: adapter.limits().max_buffer_size,
                ..Default::default()
            },
            ..Default::default()
        }));
        drop(stage);
        match result {
            Ok((device, queue)) => {
                crate::startup::message(format_args!(
                    "Selected adapter: backend={:?} name={} type={:?} vendor={:#x} device={:#x} driver={} driver_info={}",
                    info.backend,
                    info.name,
                    info.device_type,
                    info.vendor,
                    info.device,
                    info.driver,
                    info.driver_info
                ));
                crate::startup::message(format_args!(
                    "GPU generation policy (shared defaults): {policy:?}; adapter_immediates={} max_immediate_size={}",
                    adapter.features().contains(wgpu::Features::IMMEDIATES),
                    adapter.limits().max_immediate_size
                ));
                return Ok(Device {
                    device,
                    queue,
                    info,
                    policy,
                });
            }
            Err(error) => failures.push(format!("{:?}: {}: {error}", info.backend, info.name)),
        }
    }
    let reason = if !failures.is_empty() {
        format!(
            "no usable hardware device through {backends:?}: {}",
            failures.join("; ")
        )
    } else if software {
        format!("only software graphics adapters are available through {backends:?}")
    } else {
        format!("no hardware graphics adapter is available through {backends:?}")
    };
    Err(InitError::Unavailable(reason))
}
