//! Test-only host intervals within one GPU job. Device timestamps overlap these.
use std::time::Instant;
use crate::decoration::dependencies::worker_phase_probe::thread_cpu_ns;

#[derive(Clone, Default, serde::Serialize)]
pub(crate) struct Phases {
    /// Shared intervals belong to this whole GPU batch, not a proportional per-job slice.
    pub batch_jobs: usize,
    pub events: Vec<Event>,
    /// Device pass durations, overlapping the host intervals above. Missing
    /// timestamp endpoints remain None; these exclude material prefix scans.
    pub material_count_ns: Option<u64>,
    pub material_emission_ns: Option<u64>,
}
#[derive(Clone, serde::Serialize)]
pub(crate) struct Event {
    kind: &'static str,
    start_ns: u64,
    end_ns: u64,
    cpu_start_ns: Option<u64>,
    cpu_end_ns: Option<u64>,
}
pub(crate) struct Stamp { start_ns: u64, cpu_start_ns: Option<u64> }
impl Stamp {
    pub(crate) fn start(origin: Instant) -> Self {
        Self { start_ns: origin.elapsed().as_nanos() as u64, cpu_start_ns: thread_cpu_ns() }
    }
    pub(crate) fn finish(self, origin: Instant, kind: &'static str, phases: &mut Phases) {
        let cpu_end_ns=thread_cpu_ns();
        let end_ns=origin.elapsed().as_nanos() as u64;
        phases.events.push(Event { kind, start_ns:self.start_ns, end_ns,
            cpu_start_ns:self.cpu_start_ns, cpu_end_ns });
    }
}
#[test]
fn records_ordered_intervals_and_optional_cpu_without_interpolation() {
    let origin=Instant::now();let mut phases=Phases::default();
    Stamp::start(origin).finish(origin,"poll",&mut phases);
    Stamp::start(origin).finish(origin,"terrain",&mut phases);
    assert_eq!(phases.events.len(),2);
    assert!(phases.events[0].end_ns<=phases.events[1].start_ns);
    assert!(phases.events.iter().all(|e| e.start_ns<=e.end_ns));
    let json=serde_json::to_value(&phases).unwrap();
    assert_eq!(json["events"][1]["kind"],"terrain");
    assert!(json["material_count_ns"].is_null());
    assert!(json["material_emission_ns"].is_null());
    // A missing endpoint remains missing, not a fabricated zero duration.
    let missing=Event {kind:"missing",start_ns:0,end_ns:1,cpu_start_ns:None,cpu_end_ns:None};
    assert!(serde_json::to_value(missing).unwrap()["cpu_start_ns"].is_null());
}
