//! One GPU owner with bounded submissions and independent readback slots.
use crate::{BATCH_WAIT, COLUMNS, GpuRequest, Job, MAX_BATCH, gpu, pipeline, timings};
use std::{
    collections::VecDeque,
    panic::{AssertUnwindSafe, catch_unwind},
    sync::{Arc, mpsc},
    time::Instant,
};

fn reply(batch: Vec<Job>, result: Result<gpu::GpuSample, String>) {
    match result {
        Ok(mut sample) => {
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
fn complete(gpu: &mut gpu::Gpu, batch: Vec<Job>, pending: gpu::PendingSample) {
    let result = catch_unwind(AssertUnwindSafe(|| {
        gpu.complete(pending, &batch[0].timings)
    }))
    .unwrap_or_else(|_| Err("GPU completion panicked; see native stderr".into()));
    reply(batch, result);
}
pub(crate) fn run(
    receiver: mpsc::Receiver<Job>,
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
        if flights.len() == depth {
            let (batch, pending) = flights.pop_front().unwrap();
            complete(&mut gpu, batch, pending);
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
                            let (batch, pending) = flights.pop_front().unwrap();
                            complete(&mut gpu, batch, pending);
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
            while let Some((batch, pending)) = flights.pop_front() {
                complete(&mut gpu, batch, pending);
            }
            break;
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
                Ok(job) => {
                    if job.tile_side != 0
                        || job.probe_mode != mode
                        || job.requests[0].reserved != profile
                        || count + job.requests.len() > MAX_BATCH
                    {
                        queued = Some(job);
                        break;
                    }
                    count += job.requests.len();
                    batch.push(job);
                }
                Err(_) => break,
            }
        }
        let mut requests: Vec<_> = batch
            .iter()
            .flat_map(|job| {
                job.requests.iter().enumerate().map(|(i, r)| {
                    let mut gpu = GpuRequest::from(*r);
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
            gpu.submit(&requests, batch[0].profile.as_deref(), &batch[0].timings)
        }))
        .unwrap_or_else(|_| Err("GPU dispatch panicked; see native stderr".into()));
        match result {
            Ok(pending) => {
                flights.push_back((batch, pending));
                metrics.in_flight(flights.len());
            }
            Err(error) => reply(batch, Err(error)),
        }
    }
}
