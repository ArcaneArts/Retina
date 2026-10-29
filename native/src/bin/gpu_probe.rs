//! Double-clickable, JVM-free reproduction of Retina's actual GPU startup.
use retina_worldgen::{TerrainEngine, retina_configure_gpu_backend,
    world_definition::{WorldDefinition, runtime::WorldSession}, region::codec::Compression};
use std::{fs::OpenOptions, io::{self, Write}, path::PathBuf,
    process::{Command, ExitCode, Stdio}, sync::Arc, time::{SystemTime, UNIX_EPOCH}};

fn worker(backend: &str, definition: Option<PathBuf>) -> Result<(), String> {
    let code = match backend { "vulkan" => 1, "dx12" => 2, "metal" => 3, "auto" => 0,
        _ => return Err(format!("unknown backend {backend}")) };
    if retina_configure_gpu_backend(code) != 0 { return Err("cannot configure backend".into()); }
    eprintln!("Retina standalone GPU probe; source={}; backend={backend}; no JVM or Minecraft process",
        option_env!("GITHUB_SHA").unwrap_or("local build"));
    let engine = Arc::new(TerrainEngine::new()?);
    eprintln!("GPU engine ready: {}; selected pipelines compile on demand", engine.backend());
    if let Some(path) = definition {
        eprintln!("Loading definition {} (read-only)", path.display());
        let definition = Arc::new(WorldDefinition::read(&path)?);
        let session = WorldSession::bind(engine, definition, Compression::Zlib)?;
        let dimension = session.dimension("minecraft:overworld")?;
        let chunk = dimension.generate_chunk(-3, -3)?;
        eprintln!("Overworld chunk -3,-3 generated in memory: {} NBT bytes", chunk.len());
    } else {
        let heights = engine.sample_heights(retina_worldgen::ChunkRequest {
            seed: 123456789, chunk_x: -3, chunk_z: -3, min_y: -64, height: 384,
            base_height: 64.0, amplitude: 48.0, frequency: 0.008, reserved: 0,
        })?;
        eprintln!("Procedural terrain query completed: {} columns. Drop retina.dat onto this executable to test world-specific pipelines too.", heights.len());
    }
    eprintln!("GPU probe PASS");
    Ok(())
}
fn interactive(definition: Option<PathBuf>) -> Result<bool, Box<dyn std::error::Error>> {
    println!("Retina GPU probe (no Java required)\n1: Vulkan\n2: DirectX 12\n3: Metal\nEnter: automatic");
    print!("Backend: "); io::stdout().flush()?;
    let mut choice = String::new(); io::stdin().read_line(&mut choice)?;
    let backend = match choice.trim() { "1" => "vulkan", "2" => "dx12", "3" => "metal", "" => "auto",
        _ => return Err("Choose 1, 2, 3, or Enter".into()) };
    let executable = std::env::current_exe()?;
    let directory = executable.parent().ok_or("cannot locate executable directory")?;
    let stamp = SystemTime::now().duration_since(UNIX_EPOCH)?.as_millis();
    let log = directory.join(format!("retina-gpu-probe-{backend}-{stamp}-{}.log", std::process::id()));
    let file = OpenOptions::new().write(true).create_new(true).open(&log)?;
    let mut command = Command::new(&executable);
    command.args(["--worker", backend]);
    if let Some(path) = definition { command.arg(path); }
    println!("Log: {}\nThe log updates while the probe is running. Ctrl+C stops the probe.", log.display());
    let mut child = command.stdout(Stdio::from(file.try_clone()?)).stderr(Stdio::from(file)).spawn()?;
    let cancelled = Arc::new(std::sync::atomic::AtomicBool::new(false));
    let flag = cancelled.clone();
    // A failed console-handler registration does not prevent the actual test.
    if let Err(error) = ctrlc::set_handler(move || { flag.store(true, std::sync::atomic::Ordering::Relaxed); }) {
        eprintln!("Console cancellation handler unavailable: {error}");
    }
    loop {
        if cancelled.load(std::sync::atomic::Ordering::Relaxed) { let _ = child.kill(); }
        if let Some(status) = child.try_wait()? {
            println!("Probe finished: {status}\nLog: {}", log.display());
            return Ok(status.success());
        }
        std::thread::sleep(std::time::Duration::from_millis(200));
    }
}
fn main() -> ExitCode {
    let mut args = std::env::args_os().skip(1);
    let first = args.next();
    if first.as_deref() == Some(std::ffi::OsStr::new("--help")) {
        println!("Double-click and select a backend. Drop retina.dat onto the executable to also generate one Overworld chunk in memory. Logs are written beside the executable. No world files are modified.\nAutomation: retina-gpu-probe --worker <auto|vulkan|dx12|metal> [retina.dat]");
        return ExitCode::SUCCESS;
    }
    if first.as_deref() == Some(std::ffi::OsStr::new("--worker")) {
        let backend = args.next().unwrap_or_default().to_string_lossy().into_owned();
        return match worker(&backend, args.next().map(PathBuf::from)) {
            Ok(()) => ExitCode::SUCCESS,
            Err(error) => { eprintln!("GPU probe FAILED: {error}"); ExitCode::FAILURE }
        };
    }
    let result = interactive(first.map(PathBuf::from));
    let success = match result { Ok(success) => success, Err(error) => { eprintln!("GPU probe FAILED: {error}"); false } };
    println!("Press Enter to close.");
    let _ = io::stdin().read_line(&mut String::new());
    if success { ExitCode::SUCCESS } else { ExitCode::FAILURE }
}
