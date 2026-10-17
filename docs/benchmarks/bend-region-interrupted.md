# Interrupted complete-region comparison

The full Bend run was stopped at the user's request because it disrupted other
work. **Do not restart heavy benchmarks, compilation or servers without their
approval.** The current measurements already justify investigating lighting;
finishing more repetitions is unnecessary for that decision.

This is an incomplete experiment, not a passed end-to-end benchmark or a claim
of feature parity. [Saved measurements](bend-region-interrupted.json) retain
the original report hashes, artifact/profile hashes, completed samples and
stage counters. No new generator invocation was used to produce this report.

## Completed samples

Apple M4 Max, macOS 27.0.1. Both generators used the same vanilla registry,
seed 123456789, 384-block build height, 512x512-block regions and 900 lit
interior chunks. Rust assembly/global Rayon pools and Bend CPU execution used
two workers. Bend used the stock Metal runtime with a 4 GiB GPU heap. CPU worker
limits do not cap GPU use. A captured Bend stack contains `cube_run` waiting in
Metal's `-[_MTLCommandBuffer waitUntilCompleted]`; GPU execution was observed.

| Measurement | Rust | Bend |
| --- | ---: | ---: |
| Startup/registry preparation | 2.48 s | 34.35 s |
| First region (8,8) | 2.19 s | 33.17 min |
| Warm region (-1,0) | 1.41 s | 21.67 min |
| Warm region (0,0) | 1.60 s | 23.32 min |
| Mean of those two warm regions | 1.50 s | 22.50 min |
| Derived throughput for those two | 681.24 chunks/s | 0.759 chunks/s |
| Mean file size for those two | 8,581,120 bytes | 11,016,192 bytes |
| Sampled driver+engine peak RSS over each entire run | 1,556,758,528 bytes | 2,570,223,616 bytes |

Rust also completed (1,0), in 1.89 s. Bend was stopped while generating that
region. Its incomplete work is excluded from the means. RSS includes the entire
run, including startup and unfinished work, can double-count shared pages, and
excludes GPU allocations. Rust's three-warm-region mean is 1.633 s; comparing
that with Bend's two-region mean would mix coordinates/sample counts.

The host was shared with other work. Bend's observed one-minute load average
ranged from 1.78 to 31.08, versus 10.28 to 10.58 for the short Rust run. These
are observed latencies under different load histories, not an isolated hardware
performance ratio. Backend terrain and compressed inputs differ despite sharing
the same registry and coordinates; file sizes are not a codec-only comparison.

## What the saved timings establish

Bend lighting averaged **910.24 seconds**, or **67.44%** of the two completed
warm-region latencies. Carving averaged 86.90 s, NBT/zlib/MCA together 73.57 s,
and ores 71.64 s. These are sequential host spans, not GPU-device timings. Rust's
timing counters include overlapping device/host spans and parallel worker sums;
they must not be summed or compared as identical stage definitions.

Source inspection identifies likely lighting costs: 25 sequential tiles, each
96x96 blocks plus a 16-block halo on every side; repeated material queries over
the vertical run representation; and brick traversal through 14 propagation
rounds. Halo coverage alone visits about 1.78 times the interior voxel volume.
These are source-derived observations, not measured instruction counts or a
proven optimization. A focused algorithm change should precede any more costly
full-region experiments.

## Verification limits and retained artifacts

All 4,096 Rust chunks passed independent MCA/zlib/NBT validation. Bend produced
three closed region files, but its full independent validation phase did not
run. Ten previously inspected chunk records from the first two Bend files had
valid zlib/NBT, coordinates, statuses and expected light-section counts. That
sample does not prove every chunk valid. The old driver's final worker-EOF
error followed the deliberate termination; it is not evidence of an unsolicited
engine crash.

Minecraft decoder timing passed on an earlier Rust corpus. The new live-load
fixture and standalone Rust compression endpoint have not been compiled/run;
the heavy-work stop remains in force. Live chunk activation, the identical-NBT
codec comparison, the remaining Bend region and full Bend validation are still
unverified. This report does not satisfy the full goal's completion audit.

On the development host, immutable original reports, relocated working copies,
completed MCA files, the profile, GPU stack sample and Rust artifact are retained
outside the worktree at:

`~/.local/share/retina-bend/2.0.36/backend-artifacts/region-comparison-0731aec/`

The updated opt-in benchmark harnesses enforce a 60-second runtime limit and
terminate their entire process group on timeout or cancellation. Their
`run-control.json` is authoritative for interruption; checkpoint files never
turn a partial run into a successful experiment.
