mod args;
mod output;

use retina_worldgen::{
    TerrainEngine,
    world_definition::{WorldDefinition, runtime::WorldSession},
};
use std::{
    fs,
    process::ExitCode,
    sync::{
        Arc,
        atomic::{AtomicBool, Ordering},
    },
    time::Instant,
};

struct CacheFlush;
impl Drop for CacheFlush {
    fn drop(&mut self) { retina_worldgen::retina_flush_pipeline_caches(); }
}
fn main() -> ExitCode {
    let _cache_flush = CacheFlush;
    let options = match args::parse(std::env::args_os().skip(1)) {
        Ok(args::Command::Help) => {
            print!("{}", args::HELP);
            return ExitCode::SUCCESS;
        }
        Ok(args::Command::Generate(options)) => options,
        Err(error) => {
            eprintln!("retina: {error}\nUse retina --help");
            return ExitCode::from(2);
        }
    };
    let cancelled = Arc::new(AtomicBool::new(false));
    let signal = cancelled.clone();
    if let Err(error) = ctrlc::set_handler(move || {
        if !signal.swap(true, Ordering::SeqCst) {
            eprintln!("Cancellation requested; finishing the active region before stopping.");
        }
    }) {
        eprintln!("retina: install cancellation handler: {error}");
        return ExitCode::FAILURE;
    }
    match generate(options, &cancelled) {
        Ok(false) => ExitCode::SUCCESS,
        Ok(true) => ExitCode::from(130),
        Err(error) => {
            eprintln!("retina: {error}");
            ExitCode::FAILURE
        }
    }
}

fn generate(options: args::Generate, cancelled: &AtomicBool) -> Result<bool, String> {
    let started = Instant::now();
    let definition = Arc::new(WorldDefinition::read(&options.definition)?);
    definition.dimension(&options.dimension)?.profile()?;
    eprintln!(
        "Loaded {}: seed {}, DataVersion {}, {} dimensions in {:.3}s",
        options.definition.display(),
        definition.seed(),
        definition.data_version(),
        definition.dimensions().len(),
        started.elapsed().as_secs_f64()
    );
    let directory = output::region_directory(&options.output, &options.dimension)?;
    if cancelled.load(Ordering::SeqCst) {
        return Ok(true);
    }
    let _lock = output::lock(&options.output)?;
    fs::create_dir_all(&directory)
        .map_err(|error| format!("create region directory {}: {error}", directory.display()))?;
    let engine = Arc::new(TerrainEngine::new()?);
    eprintln!(
        "Backend: {}; generating {} inclusive regions for {}",
        engine.backend(),
        options.count(),
        options.dimension
    );
    let world = WorldSession::bind(engine, definition, options.compression)?;
    let dimension = world.dimension(&options.dimension)?;
    let mut regions = 0u64;
    let mut generated = 0u64;
    let mut preserved = 0u64;
    'rows: for z in options.min[1]..=options.max[1] {
        for x in options.min[0]..=options.max[0] {
            if cancelled.load(Ordering::SeqCst) {
                break 'rows;
            }
            eprintln!(
                "Region {},{} ({}/{})",
                x,
                z,
                regions as u128 + 1,
                options.count()
            );
            let began = Instant::now();
            let path = directory.join(format!("r.{x}.{z}.mca"));
            let report = dimension.generate_region(x, z, &path)?;
            regions += 1;
            generated += report.generated as u64;
            preserved += report.preserved as u64;
            eprintln!(
                "  generated {}, preserved {}, {} bytes, {:.3}s",
                report.generated,
                report.preserved,
                report.bytes,
                began.elapsed().as_secs_f64()
            );
        }
    }
    let interrupted = cancelled.load(Ordering::SeqCst);
    println!(
        "{}",
        serde_json::json!({"status":if interrupted {"cancelled"} else {"complete"},
        "dimension":options.dimension,"regions":regions,"generated_chunks":generated,
        "preserved_chunks":preserved,"seconds":started.elapsed().as_secs_f64(),
        "backend":world.engine().backend(),"seed":world.definition().seed(),"data_version":world.definition().data_version()})
    );
    Ok(interrupted)
}
