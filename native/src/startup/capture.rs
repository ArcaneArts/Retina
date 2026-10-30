//! Exact inputs from the patched HAL, persisted before the expensive calls.
// Keep the portable persistence code available for host tests; production capture
// is Windows x64 only.
#![cfg_attr(not(all(windows, target_arch = "x86_64")), allow(dead_code))]
use serde::{Deserialize, Serialize};
use sha2::{Digest, Sha256};
use std::{collections::BTreeMap, fs, path::Path, time::{SystemTime, UNIX_EPOCH}};
mod vulkan;

pub(crate) fn now() -> u64 {
    SystemTime::now().duration_since(UNIX_EPOCH).unwrap_or_default().as_millis() as u64
}
pub(crate) fn sha256(bytes: &[u8]) -> String { format!("{:x}", Sha256::digest(bytes)) }

#[derive(Clone, Serialize, Deserialize)]
pub(crate) struct Adapter {
    pub name: String,
    pub driver: String,
    #[serde(default)]
    pub driver_info: String,
    pub vendor: u32,
    pub device: u32,
}
#[derive(Serialize, Deserialize)]
pub(crate) struct FileInfo {
    pub bytes: usize,
    pub sha256: String,
}
#[derive(Serialize, Deserialize)]
pub(crate) struct Manifest {
    pub schema: u32,
    #[serde(default = "dx12_backend")]
    pub backend: String,
    #[serde(default)]
    pub pipeline_flags: Option<u32>,
    pub pipeline_id: u64,
    pub entry: String,
    pub adapter: Adapter,
    pub compiler: String,
    pub arguments: Option<Vec<String>>,
    pub phase: String,
    pub updated_unix_ms: u64,
    pub events: Vec<(String, u64)>,
    pub files: BTreeMap<String, FileInfo>,
    pub error: Option<String>,
}
fn dx12_backend() -> String { "dx12".into() }

pub(crate) fn atomic_write(path: &Path, bytes: &[u8]) -> Result<(), String> {
    let temporary = path.with_extension("tmp");
    fs::write(&temporary, bytes).map_err(|e| format!("{}: {e}", temporary.display()))?;
    fs::rename(&temporary, path).map_err(|e| format!("{}: {e}", path.display()))
}
impl Manifest {
    pub fn read(directory: &Path) -> Result<Self, String> {
        let path = directory.join("case.json");
        let bytes = fs::read(&path).map_err(|e| format!("{}: {e}", path.display()))?;
        serde_json::from_slice(&bytes).map_err(|e| format!("{}: {e}", path.display()))
    }
    fn save(&mut self, directory: &Path, phase: &str) -> Result<(), String> {
        self.phase = phase.into();
        self.updated_unix_ms = now();
        self.events.push((phase.into(), self.updated_unix_ms));
        atomic_write(&directory.join("case.json"), &serde_json::to_vec_pretty(self).map_err(|e| e.to_string())?)
    }
    fn file(&mut self, directory: &Path, name: &str, bytes: &[u8]) -> Result<(), String> {
        atomic_write(&directory.join(name), bytes)?;
        self.files.insert(name.into(), FileInfo { bytes: bytes.len(), sha256: sha256(bytes) });
        Ok(())
    }
}

fn begin(directory: &Path, id: u64, entry: String, adapter: Adapter, root: &[u8]) -> Result<(), String> {
    fs::create_dir_all(directory).map_err(|e| e.to_string())?;
    let mut manifest = Manifest {
        schema: 1, backend: dx12_backend(), pipeline_flags: None, pipeline_id: id, entry, adapter,
        compiler: "wgpu-hal 30.0.1 / bundled mach-dxcompiler-rs 0.1.7+2026.10.26-141dd7f.1".into(),
        arguments: None, phase: String::new(), updated_unix_ms: 0, events: Vec::new(),
        files: BTreeMap::new(), error: None,
    };
    manifest.file(directory, "root-signature.bin", root)?;
    manifest.save(directory, "translating")
}
fn input(directory: &Path, source: &str, arguments: &[String]) -> Result<(), String> {
    let mut manifest = Manifest::read(directory)?;
    manifest.file(directory, "shader.hlsl", source.as_bytes())?;
    manifest.arguments = Some(arguments.to_vec());
    manifest.save(directory, "dxc-running")
}
fn bytecode(directory: &Path, bytes: &[u8]) -> Result<(), String> {
    let mut manifest = Manifest::read(directory)?;
    manifest.file(directory, "shader.dxil", bytes)?;
    manifest.save(directory, "driver-running")
}
fn finish(directory: &Path, error: Option<&str>) -> Result<(), String> {
    let mut manifest = Manifest::read(directory)?;
    manifest.error = error.map(str::to_owned);
    manifest.save(directory, if error.is_some() { "driver-error" } else { "complete" })
}
fn case_name(id: u64, entry: &str) -> String {
    let safe: String = entry.chars().take(100).map(|c| if c.is_ascii_alphanumeric() || c == '_' { c } else { '_' }).collect();
    format!("{id:06}-{safe}")
}

#[cfg(all(windows, target_arch = "x86_64"))]
mod windows {
    use super::*;
    use std::{path::PathBuf, sync::OnceLock};
    use wgpu::hal::dx12::diagnostics::{self, Event};
    struct Session { directory: PathBuf, adapter: Adapter }
    static SESSION: OnceLock<Session> = OnceLock::new();
    pub(super) fn install(info: &wgpu::AdapterInfo) {
        if info.backend != wgpu::Backend::Dx12 || std::env::var("RETINA_SHADER_CAPTURE").as_deref() == Ok("0") { return; }
        if SESSION.get().is_some() { return; }
        let result = (|| {
            let base = std::env::var_os("RETINA_SHADER_CAPTURE_DIR").map(PathBuf::from)
                .unwrap_or_else(|| PathBuf::from("retina-diagnostics"));
            let directory = std::env::current_dir().map_err(|e| e.to_string())?.join(base)
                .join(format!("dx12-{}-{}", now(), std::process::id()));
            fs::create_dir_all(&directory).map_err(|e| e.to_string())?;
            let adapter = Adapter { name: info.name.clone(), driver: info.driver.clone(), driver_info: info.driver_info.clone(), vendor: info.vendor, device: info.device };
            let _ = SESSION.set(Session { directory: directory.clone(), adapter });
            if diagnostics::set_observer(observe) {
                crate::startup::message(format_args!("Exact DX12 shader capture: {} (inputs saved before compiler/driver calls)", directory.display()));
            } else {
                return Err("DX12 diagnostic observer already installed".into());
            }
            Ok::<_, String>(())
        })();
        if let Err(error) = result { crate::startup::info(format_args!("Shader capture unavailable; generation continues: {error}")); }
    }
    fn observe(event: Event<'_>) {
        let Some(session) = SESSION.get() else { return; };
        let Some((id, entry)) = crate::startup::driver::pipeline_context() else { return; };
        let directory = session.directory.join(case_name(id, &entry));
        let result = match event {
            Event::ComputeStart { root_signature } => begin(&directory, id, entry, session.adapter.clone(), root_signature),
            Event::DxcInput { source, arguments } => input(&directory, source, arguments),
            Event::ComputeInput { bytecode: bytes } => bytecode(&directory, bytes),
            Event::ComputeEnd { error } => finish(&directory, error),
        };
        if let Err(error) = result {
            crate::startup::info(format_args!("Capture incomplete at {}; generation continues: {error}", directory.display()));
        } else if matches!(event, Event::ComputeInput { .. }) {
            crate::startup::message(format_args!("DX12 replay case saved before driver call: {}", directory.join("case.json").display()));
        }
    }
}

pub(crate) fn install(info: &wgpu::AdapterInfo) {
    if !super::debug_enabled() && std::env::var("RETINA_SHADER_CAPTURE").as_deref() != Ok("1") { return; }
    #[cfg(all(windows, target_arch = "x86_64"))]
    {
        windows::install(info);
        vulkan::install(info);
    }
    #[cfg(not(all(windows, target_arch = "x86_64")))]
    let _ = info;
}

#[cfg(test)]
mod tests {
    use super::*;
    #[test]
    fn capture_survives_interruption_before_driver_and_preserves_exact_bytes() {
        let path = std::env::temp_dir().join(format!("retina-capture-test-{}-{}", now(), std::process::id()));
        let adapter = Adapter { name: "test".into(), driver: "test".into(), driver_info: "test".into(), vendor: 1, device: 2 };
        let args = vec!["label with spaces".into(), "-E".into(), "surface_columns".into(), "-T".into(), "cs_6_7".into()];
        begin(&path, 50, "surface_columns".into(), adapter, &[1, 2, 3]).unwrap();
        input(&path, "// exact CRLF\r\nshader", &args).unwrap();
        assert_eq!(Manifest::read(&path).unwrap().phase, "dxc-running");
        bytecode(&path, &[0, 1, 255, 2]).unwrap();
        let m = Manifest::read(&path).unwrap();
        assert_eq!(m.phase, "driver-running");
        assert_eq!(m.arguments.unwrap(), args);
        for (name, info) in m.files {
            let bytes = fs::read(path.join(name)).unwrap();
            assert_eq!(bytes.len(), info.bytes); assert_eq!(sha256(&bytes), info.sha256);
        }
        finish(&path, Some("actual driver failure")).unwrap();
        assert_eq!(Manifest::read(&path).unwrap().error.as_deref(), Some("actual driver failure"));
        finish(&path, None).unwrap();
        assert_eq!(Manifest::read(&path).unwrap().phase, "complete");
        // Previously saved DX12 cases predate the shared backend/flags fields.
        let mut old: serde_json::Value = serde_json::from_slice(&fs::read(path.join("case.json")).unwrap()).unwrap();
        old.as_object_mut().unwrap().remove("backend");
        old.as_object_mut().unwrap().remove("pipeline_flags");
        old["adapter"].as_object_mut().unwrap().remove("driver_info");
        fs::write(path.join("case.json"), serde_json::to_vec(&old).unwrap()).unwrap();
        let restored = Manifest::read(&path).unwrap();
        assert_eq!(restored.backend, "dx12");
        assert_eq!(restored.pipeline_flags, None);
        assert_eq!(restored.adapter.driver_info, "");
        fs::remove_dir_all(path).unwrap();
        assert!(!case_name(2, "../../bad:name").contains('/'));
    }
    #[test]
    fn capture_write_failure_is_reported() {
        assert!(input(Path::new("/this-path-is-deliberately-missing/retina"), "source", &[]).is_err());
    }
}
