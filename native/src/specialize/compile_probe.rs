//! Test-only compiler observations. Fresh entry names distinguish diagnostic
//! identities without changing shader arithmetic or clearing the user's caches.
use std::time::Instant;

pub(super) struct Probe {
    enabled: bool,
    tag: Option<String>,
    specialized: bool,
}
impl Probe {
    pub fn new(specialized: bool) -> Self {
        Self {
            enabled: std::env::var_os("RETINA_COMPILE_PROBE").is_some(),
            tag: std::env::var(if specialized {
                "RETINA_SPECIALIZED_PIPELINE_TAG"
            } else {
                "RETINA_GENERIC_PIPELINE_TAG"
            })
            .ok(),
            specialized,
        }
    }
    pub fn entry(&self, entry: &str) -> String {
        match &self.tag {
            None => entry.to_owned(),
            Some(tag) => {
                let suffix = tag.bytes().map(|b| format!("{b:02x}")).collect::<String>();
                format!("{entry}_rt_{suffix}")
            }
        }
    }
    pub fn source(&self, mut source: String, entries: &[&str]) -> String {
        if self.tag.is_some() {
            for entry in entries {
                source = source.replace(
                    &format!("fn {entry}("),
                    &format!("fn {}(", self.entry(entry)),
                );
            }
        }
        if let Some(directory) = std::env::var_os("RETINA_COMPILE_DUMP") {
            static NEXT: std::sync::atomic::AtomicUsize = std::sync::atomic::AtomicUsize::new(0);
            let id = NEXT.fetch_add(1, std::sync::atomic::Ordering::Relaxed);
            let directory = std::path::PathBuf::from(directory);
            std::fs::create_dir_all(&directory).unwrap();
            std::fs::write(directory.join(format!("{id:04}-{}.wgsl", entries.join("-"))), &source).unwrap();
        }
        source
    }
    pub fn report(&self, phase: &str, entry: &str, start: Instant, bytes: usize) {
        if self.enabled {
            println!(
                "RETINA_COMPILE {}",
                serde_json::json!({"phase":phase,"entry":entry,"specialized":self.specialized,
                    "tag":self.tag,"ms":start.elapsed().as_secs_f64()*1000.0,"source_bytes":bytes})
            );
        }
    }
}
