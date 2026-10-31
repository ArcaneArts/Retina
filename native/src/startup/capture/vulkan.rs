//! Capture SPIR-V from the HAL before vkCreateShaderModule / driver optimization.
use super::*;

fn spirv(directory: &Path, id: u64, entry: &str, adapter: Adapter, words: &[u32]) -> Result<(), String> {
    fs::create_dir_all(directory).map_err(|e| e.to_string())?;
    let mut manifest = Manifest {
        schema: 1, backend: "vulkan".into(), pipeline_flags: None,
        pipeline_id: id, entry: entry.into(), adapter,
        compiler: "wgpu-hal 30.0.1 / Naga SPIR-V".into(), arguments: None,
        phase: String::new(), updated_unix_ms: 0, events: Vec::new(), files: BTreeMap::new(), error: None,
    };
    let bytes: Vec<_> = words.iter().flat_map(|word| word.to_le_bytes()).collect();
    manifest.file(directory, "shader.spv", &bytes)?;
    manifest.save(directory, "shader-module-creation")
}
fn driver(directory: &Path, flags: u32) -> Result<(), String> {
    let mut manifest = Manifest::read(directory)?;
    manifest.pipeline_flags = Some(flags);
    manifest.save(directory, "driver-running")
}

#[cfg(all(windows, target_arch = "x86_64"))]
pub(super) fn install(info: &wgpu::AdapterInfo) { windows::install(info); }

#[cfg(all(windows, target_arch = "x86_64"))]
mod windows {
    use super::*;
    use std::{path::PathBuf, sync::OnceLock};
    use wgpu::hal::vulkan::diagnostics::{self, Event};
    struct Session { directory: PathBuf, adapter: Adapter }
    static SESSION: OnceLock<Session> = OnceLock::new();
    pub(super) fn install(info: &wgpu::AdapterInfo) {
        if info.backend != wgpu::Backend::Vulkan || std::env::var("RETINA_SHADER_CAPTURE").as_deref() == Ok("0") { return; }
        if SESSION.get().is_some() { return; }
        let result = (|| {
            let base = std::env::var_os("RETINA_SHADER_CAPTURE_DIR").map(PathBuf::from)
                .unwrap_or_else(|| PathBuf::from("retina-diagnostics"));
            let directory = std::env::current_dir().map_err(|e| e.to_string())?.join(base)
                .join(format!("vulkan-{}-{}", now(), std::process::id()));
            fs::create_dir_all(&directory).map_err(|e| e.to_string())?;
            let adapter = Adapter { name: info.name.clone(), driver: info.driver.clone(), driver_info: info.driver_info.clone(), vendor: info.vendor, device: info.device };
            let _ = SESSION.set(Session { directory: directory.clone(), adapter });
            if !diagnostics::set_observer(observe) { return Err("Vulkan diagnostic observer already installed".into()); }
            crate::startup::message(format_args!("Exact Vulkan shader capture: {} (SPIR-V saved before driver calls)", directory.display()));
            Ok::<_, String>(())
        })();
        if let Err(error) = result { crate::startup::info(format_args!("Shader capture unavailable; generation continues: {error}")); }
    }
    fn observe(event: Event<'_>) {
        let Some(session) = SESSION.get() else { return; };
        let Some((id, entry)) = crate::startup::driver::pipeline_context() else { return; };
        let directory = session.directory.join(case_name(id, &entry));
        let result = match event {
            Event::Spirv { words, entry } => spirv(&directory, id, entry, session.adapter.clone(), words),
            Event::ComputeStart { flags } => {
                crate::startup::message(format_args!("Vulkan driver compilation pipeline_id=#{id} entry={entry} flags=0x{flags:x} optimization_disabled={}", flags & 1 != 0));
                driver(&directory, flags)
            }
            Event::ComputeFeedback { .. } => Ok(()),
            Event::ComputeEnd { error } => finish(&directory, error),
        };
        if let Err(error) = result {
            crate::startup::info(format_args!("Capture incomplete at {}; generation continues: {error}", directory.display()));
        } else if matches!(event, Event::ComputeStart { .. }) {
            crate::startup::message(format_args!("Vulkan shader case saved before driver call: {}", directory.join("case.json").display()));
        }
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    #[test]
    fn exact_spirv_and_effective_flags_survive_interruption() {
        let path = std::env::temp_dir().join(format!("retina-spirv-test-{}-{}", now(), std::process::id()));
        let adapter = Adapter { name: "test".into(), driver: "test".into(), driver_info: "617.14".into(), vendor: 0x10de, device: 0x2684 };
        let words = [0x07230203, 0x00010000, 0xffffffff, 0];
        spirv(&path, 42, "main", adapter, &words).unwrap();
        assert_eq!(Manifest::read(&path).unwrap().phase, "shader-module-creation");
        driver(&path, 1).unwrap();
        let manifest = Manifest::read(&path).unwrap();
        assert_eq!(manifest.backend, "vulkan");
        assert_eq!(manifest.phase, "driver-running");
        assert_eq!(manifest.pipeline_flags, Some(1));
        assert_eq!(manifest.adapter.driver_info, "617.14");
        let bytes = fs::read(path.join("shader.spv")).unwrap();
        assert_eq!(&bytes[..4], &[3, 2, 35, 7]);
        let actual: Vec<_> = bytes.chunks_exact(4).map(|b| u32::from_le_bytes(b.try_into().unwrap())).collect();
        assert_eq!(actual, words);
        assert_eq!(manifest.files["shader.spv"].sha256, sha256(&bytes));
        finish(&path, None).unwrap();
        assert_eq!(Manifest::read(&path).unwrap().phase, "complete");
        fs::remove_dir_all(path).unwrap();
    }
}
