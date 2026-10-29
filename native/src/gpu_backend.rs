//! Backend selection is frozen when requested, before creating any GPU device.
//! Explicit requests go to wgpu; unsupported hardware fails at adapter creation.
use std::sync::OnceLock;

const AUTO: wgpu::Backends = wgpu::Backends::METAL
    .union(wgpu::Backends::VULKAN)
    .union(wgpu::Backends::DX12);
static SELECTED: OnceLock<Result<wgpu::Backends, String>> = OnceLock::new();

fn from_code(code: u32) -> Result<wgpu::Backends, String> {
    match code {
        0 => Ok(AUTO),
        1 => Ok(wgpu::Backends::VULKAN),
        2 => Ok(wgpu::Backends::DX12),
        3 => Ok(wgpu::Backends::METAL),
        _ => Err(format!("unknown GPU backend code {code}")),
    }
}
fn parse(value: &str) -> Result<wgpu::Backends, String> {
    from_code(match value.trim().to_ascii_lowercase().as_str() {
        "auto" => 0,
        "vulkan" => 1,
        "dx12" => 2,
        "metal" => 3,
        _ => return Err(format!("unknown RETINA_GPU_BACKEND {value:?}; use auto, vulkan, dx12 or metal")),
    })
}
fn configure(selected: &OnceLock<Result<wgpu::Backends, String>>, code: u32) -> Result<(), String> {
    let requested = from_code(code)?;
    match selected.get_or_init(|| Ok(requested)) {
        Ok(current) if *current == requested => Ok(()),
        _ => Err("GPU backend has already been selected; restart to change it".into()),
    }
}
pub(crate) fn selected() -> Result<wgpu::Backends, String> {
    SELECTED.get_or_init(|| match std::env::var("RETINA_GPU_BACKEND") {
        Ok(value) => parse(&value),
        Err(std::env::VarError::NotPresent) => Ok(AUTO),
        Err(error) => Err(format!("cannot read RETINA_GPU_BACKEND: {error}")),
    }).clone()
}

/// Configure before GPU initialization. 0=auto, 1=Vulkan, 2=DX12, 3=Metal.
#[unsafe(no_mangle)]
pub extern "C" fn retina_configure_gpu_backend(code: u32) -> i32 {
    crate::boundary(|| configure(&SELECTED, code))
}

#[cfg(test)]
mod tests {
    use super::*;
    #[test]
    fn explicit_backends_do_not_silently_fall_back() {
        assert_eq!(parse(" DX12 ").unwrap(), wgpu::Backends::DX12);
        assert_eq!(parse("vulkan").unwrap(), wgpu::Backends::VULKAN);
        assert_eq!(parse("metal").unwrap(), wgpu::Backends::METAL);
        assert_eq!(parse("auto").unwrap(), AUTO);
        assert!(parse("gles").is_err());
        assert!(parse("").is_err());
    }
    #[test]
    fn configuration_is_idempotent_and_cannot_change_a_running_backend() {
        let selected = OnceLock::new();
        assert!(configure(&selected, 99).is_err());
        assert!(selected.get().is_none());
        configure(&selected, 2).unwrap();
        configure(&selected, 2).unwrap();
        assert!(configure(&selected, 1).is_err());
        assert_eq!(selected.get().unwrap().as_ref().unwrap(), &wgpu::Backends::DX12);
    }
}
