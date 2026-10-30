//! One GPU owner with bounded submissions and independent readback slots.
use crate::{BATCH_WAIT, COLUMNS, GpuRequest, GpuCommand, Job, MAX_BATCH, gpu, pipeline, timings};
use std::{
    collections::VecDeque,
    panic::{AssertUnwindSafe, catch_unwind},
    sync::{Arc, mpsc},
    time::Instant,
};

fn reply(batch: Vec<Job>, result: Result<gpu::GpuSample, String>) {
    match result {
        Ok(mut sample) => {
            #[cfg(test)]
            { sample.host_phases.batch_jobs = batch.len(); }
            if batch.len() == 1 {
                let job = batch.into_iter().next().unwrap();
                sample.timings.nanos[timings::QUEUE] = job.queue_nanos;
                // Region jobs own their complete vector: avoid a second bulk copy.
                let _ = job.reply.send(Ok(sample));
                return;
            }
            let total = sample.columns.len() as u64;
            let mut offset = 0;
            for job in batch {
                let count = if job.tile_side != 0 {
                    (job.tile_side * job.tile_side) as usize
                } else {
                    job.requests.len()
                };
                let end = offset
                    + count
                        * if matches!(job.probe_mode, 1 | 2 | 4) {
                            1
                        } else {
                            COLUMNS
                        };
                let mut trace = sample.timings;
                for value in &mut trace.nanos {
                    *value = value.saturating_mul((end - offset) as u64) / total;
                }
                trace.gpu_columns = (end - offset) as u64;
                trace.nanos[timings::QUEUE] = job.queue_nanos;
                let _ = job.reply.send(Ok(gpu::GpuSample {
                    columns: sample.columns[offset..end].to_vec(),
                    mask: sample.mask.take(),
                    timings: trace,
                    #[cfg(test)]
                    host_phases: sample.host_phases.clone(),
                }));
                offset = end;
            }
        }
        Err(error) => {
            for job in batch {
                let _ = job.reply.send(Err(error.clone()));
            }
        }
    }
}
fn advance(gpu: &mut gpu::Gpu, flights: &mut VecDeque<(Vec<Job>, gpu::PendingSample)>) {
    let (batch, pending) = flights.pop_front().unwrap();
    let result = catch_unwind(AssertUnwindSafe(|| gpu.advance(pending, &batch[0].timings)))
        .unwrap_or_else(|payload| Err(gpu.operation_panic("completion", payload.as_ref())));
    match result {
        Ok(gpu::Progress::Pending(next)) => flights.push_back((batch, next)),
        Ok(gpu::Progress::Complete(sample)) => {
            #[cfg(test)]
            if flights.is_empty() {
                gpu.publish_buffer_census();
            }
            reply(batch, Ok(sample));
        }
        Err(error) => reply(batch, Err(error)),
    }
}
pub(crate) fn run(
    receiver: mpsc::Receiver<GpuCommand>,
    ready: mpsc::SyncSender<
        Result<
            (
                String,
                crate::geology::raster_gpu::Gpu,
                crate::decoration::counts::gpu::Gpu,
            ),
            String,
        >,
    >,
    metrics: Arc<pipeline::Metrics>,
    depth: usize,
) {
    let startup = catch_unwind(AssertUnwindSafe(|| {
        gpu::Gpu::new(metrics.clone()).map(|gpu| {
            let ores = gpu.ore_planner();
            let counts = gpu.decoration_counts();
            (gpu, ores, counts)
        })
    }));
    let mut gpu = match startup {
        Ok(Ok((gpu, ores, counts))) => {
            let _ = ready.send(Ok((gpu.backend.clone(), ores, counts)));
            gpu
        }
        Ok(Err(error)) => {
            let _ = ready.send(Err(error));
            return;
        }
        Err(_) => {
            let _ = ready.send(Err("GPU initialization panicked".into()));
            return;
        }
    };
    // The owner and auxiliary devices are ready before starting background work,
    // so shader compilation cannot delay their initialization API calls.
    gpu.start_interpreter_preload();
    let mut queued = None;
    let mut flights: VecDeque<(Vec<Job>, gpu::PendingSample)> = VecDeque::new();
    loop {
        // This state is set only by a real device-loss callback or failed GPU
        // operation. Drain callers without touching invalid GPU resources.
        if let Some(error) = gpu.failure() {
            for (batch, _) in flights.drain(..) { reply(batch, Err(error.clone())); }
            let command = queued.take().or_else(|| receiver.recv().ok());
            match command {
                Some(GpuCommand::Generate(job)) => reply(vec![job], Err(error)),
                Some(GpuCommand::Retire(_, done)) => { let _ = done.send(()); }
                None => break,
            }
            continue;
        }
        if flights.len() == depth {
            // Only wait for one submission. A dependent material pass joins
            // the back of the queue, behind other already-submitted terrain.
            advance(&mut gpu, &mut flights);
            if flights.len() == depth {
                continue;
            }
        }
        let first = if let Some(job) = queued.take() {
            Some(job)
        } else if flights.is_empty() {
            receiver.recv().ok()
        } else {
            // Continue accepting work while an earlier submission executes. A
            // short poll supplies mapping callbacks without blocking on its fence.
            match receiver.try_recv() {
                Ok(job) => Some(job),
                Err(mpsc::TryRecvError::Disconnected) => None,
                Err(mpsc::TryRecvError::Empty) => {
                    match gpu.ready(&mut flights.front_mut().unwrap().1) {
                        Ok(true) => {
                            advance(&mut gpu, &mut flights);
                            continue;
                        }
                        Err(error) => {
                            let (batch, _) = flights.pop_front().unwrap();
                            reply(batch, Err(error));
                            continue;
                        }
                        Ok(false) => {}
                    }
                    match receiver.recv_timeout(BATCH_WAIT) {
                        Ok(job) => Some(job),
                        Err(mpsc::RecvTimeoutError::Timeout) => continue,
                        Err(mpsc::RecvTimeoutError::Disconnected) => None,
                    }
                }
            }
        };
        let Some(first) = first else {
            while !flights.is_empty() {
                advance(&mut gpu, &mut flights);
            }
            break;
        };
        let first = match first {
            GpuCommand::Generate(job) => job,
            GpuCommand::Retire(ids, reply) => {
                while !flights.is_empty() { advance(&mut gpu, &mut flights); }
                gpu.retire(&ids);
                #[cfg(test)] gpu.publish_buffer_census();
                let _ = reply.send(());
                continue;
            }
        };
        let profile = first.requests[0].reserved;
        let tile = first.tile_side;
        let mode = first.probe_mode;
        let mut count = if tile != 0 {
            MAX_BATCH
        } else {
            first.requests.len()
        };
        let mut batch = vec![first];
        let deadline = Instant::now() + BATCH_WAIT;
        while count < MAX_BATCH {
            match receiver.recv_timeout(deadline.saturating_duration_since(Instant::now())) {
                Ok(GpuCommand::Generate(job)) => {
                    if job.tile_side != 0
                        || job.probe_mode != mode
                        || job.requests[0].reserved != profile
                        || count + job.requests.len() > MAX_BATCH
                    {
                        queued = Some(GpuCommand::Generate(job));
                        break;
                    }
                    count += job.requests.len();
                    batch.push(job);
                }
                Ok(command) => { queued = Some(command); break; }
                Err(_) => break,
            }
        }
        let mut requests: Vec<_> = batch
            .iter()
            .flat_map(|job| {
                job.requests.iter().enumerate().map(|(i, r)| {
                    let mut gpu = GpuRequest::from(*r);
                    // The world handle still identifies ownership/caches. Zero
                    // selects the shared procedural shader and default buffers.
                    if job.profile.is_none() { gpu.profile = 0; }
                    gpu.padding = match mode {
                        1 => 1 << 31,
                        3 => 1 << 29,
                        4 => (1 << 31) | (1 << 29),
                        2 => {
                            let y = job.probe_y[i].div_euclid(4) * 4 - r.min_y.div_euclid(4) * 4;
                            (1 << 30) | ((y as u32) << 8)
                        }
                        _ => 0,
                    };
                    if let Some(point) = job.probe_points.get(i) {
                        gpu.origin_x = point.x.div_euclid(4) * 4 - 8;
                        gpu.origin_z = point.z.div_euclid(4) * 4 - 8;
                        gpu.padding |= (1 << 21)
                            | (point.x.rem_euclid(4) as u32)
                            | ((point.z.rem_euclid(4) as u32) << 2);
                    }
                    gpu
                })
            })
            .collect();
        if tile != 0 {
            requests[0].tile_side = tile;
            requests[0].padding |= batch[0].cave_side;
        }
        for job in &mut batch {
            job.queue_nanos = job.queued.elapsed().as_nanos() as u64;
            job.timings.add(timings::QUEUE, job.queue_nanos);
        }
        let result = catch_unwind(AssertUnwindSafe(|| {
            gpu.submit_for_world(&requests, batch[0].profile.as_deref(), &batch[0].timings, profile)
        }))
        .unwrap_or_else(|payload| Err(gpu.operation_panic("dispatch", payload.as_ref())));
        match result {
            Ok(pending) => {
                flights.push_back((batch, pending));
                metrics.in_flight(flights.len());
            }
            Err(error) => reply(batch, Err(error)),
        }
    }
    #[cfg(test)]
    {
        drop(gpu);
        metrics
            .gpu_buffer_probe
            .stopped
            .store(true, std::sync::atomic::Ordering::Release);
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    #[ignore = "requires RETINA_MATERIAL_PROFILE and a real GPU"]
    fn material_continuations_drain_after_sender_closes() {
        let source = std::fs::read(std::env::var("RETINA_MATERIAL_PROFILE").unwrap()).unwrap();
        let mut value: serde_json::Value = serde_json::from_slice(&source).unwrap();
        value["program_execution"] = "interpreter".into();
        let profile = Arc::new(
            crate::profile::WorldProfile::parse(&serde_json::to_vec(&value).unwrap()).unwrap(),
        );
        let metrics = Arc::new(pipeline::Metrics::default());
        let times = Arc::new(timings::Timings::default());
        for depth in [1, 2] {
            let before = metrics.snapshot().completed;
            let (send, receive) = mpsc::channel();
            let (ready, startup) = mpsc::sync_channel(1);
            let worker_metrics = metrics.clone();
            let worker = std::thread::spawn(move || run(receive, ready, worker_metrics, depth));
            let devices = startup
                .recv_timeout(std::time::Duration::from_secs(10))
                .unwrap()
                .unwrap();
            let (reply, result) = mpsc::channel();
            for x in [-33, 4095] {
                send.send(GpuCommand::Generate(Job {
                    requests: vec![crate::ChunkRequest {
                        seed: 123456789,
                        chunk_x: x,
                        chunk_z: -33,
                        min_y: -64,
                        height: 128,
                        base_height: 48.,
                        amplitude: 24.,
                        frequency: 0.008,
                        reserved: 1,
                    }],
                    profile: Some(profile.clone()),
                    tile_side: 3,
                    cave_side: 1,
                    probe_mode: 0,
                    probe_y: Vec::new(),
                    probe_points: Vec::new(),
                    reply: reply.clone(),
                    queued: Instant::now(),
                    queue_nanos: 0,
                    timings: times.clone(),
                }))
                .unwrap();
            }
            drop(send);
            drop(reply);
            for _ in 0..2 {
                let sample = result
                    .recv_timeout(std::time::Duration::from_secs(10))
                    .unwrap()
                    .unwrap();
                sample
                    .mask
                    .as_ref()
                    .unwrap()
                    .validate_material_runs()
                    .unwrap();
                let host=serde_json::to_value(&sample.host_phases).unwrap();
                assert_eq!(host["batch_jobs"],1);
                if sample.timings.flags & 1 != 0 {
                    let count=host["material_count_ns"].as_u64();
                    let emission=host["material_emission_ns"].as_u64();
                    // Metal can omit a timestamp pair. Whenever both were
                    // measured, their sum must equal the existing stage total.
                    if let (Some(count),Some(emission))=(count,emission) {
                        assert_eq!(count+emission,sample.timings.nanos[timings::MATERIALS]);
                    }
                } else {
                    assert!(host["material_count_ns"].is_null());
                    assert!(host["material_emission_ns"].is_null());
                }
                let events=host["events"].as_array().unwrap();
                let kinds: Vec<_>=events.iter().map(|e| e["kind"].as_str().unwrap()).collect();
                assert_eq!(kinds,["poll","complete_terrain","emit_materials","poll","complete_materials"]);
                let mut previous=0;
                for event in events {
                    let start=event["start_ns"].as_u64().unwrap();
                    let end=event["end_ns"].as_u64().unwrap();
                    assert!(previous<=start && start<=end);
                    assert!(end<=sample.timings.nanos[timings::WAIT_COPY]);
                    previous=end;
                }
            }
            assert!(
                result
                    .recv_timeout(std::time::Duration::from_secs(10))
                    .is_err()
            );
            worker.join().unwrap();
            assert_eq!(metrics.snapshot().completed, before + 2);
            assert!(metrics.snapshot().peak_in_flight <= depth as u32);
            drop(devices);
        }
    }
}
