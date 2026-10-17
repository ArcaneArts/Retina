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
three closed region files; its original validation phase did not run. A later
[offline audit](bend-saved-output-audit.json) verified **all 2,048 chunks in the
first two files**, including MCA allocation, complete zlib/NBT streams, every
palette index, registry membership, coordinates, section ranges, heightmap
packing/ranges and light-array layouts. Each file has 900 lit interior chunks.
This checks serialized structure, not light propagation or heightmap agreement
with the actual blocks.

The single low-priority Python audit stopped at its ten-second deadline after
192 chunks of the third file. That file is not counted as fully validated.
No generator, native compiler, GPU work or Minecraft run was invoked. The
original benchmark report and timing samples remain unchanged and interrupted.
The old driver's final worker-EOF error followed the deliberate termination; it
is not evidence of an unsolicited engine crash.

Minecraft decoder timing passed on an earlier Rust corpus. The new live-load
fixture and standalone Rust compression endpoint have not been compiled/run;
the heavy-work stop remains in force. Live chunk activation, the identical-NBT
codec comparison, the remaining Bend generation sample and third-file
validation are still unverified. This report does not satisfy the full goal's completion audit.

On the development host, immutable original reports, relocated working copies,
completed MCA files, the profile, GPU stack sample and Rust artifact are retained
outside the worktree at:

`~/.local/share/retina-bend/2.0.36/backend-artifacts/region-comparison-0731aec/`

The updated opt-in benchmark harnesses enforce a 60-second runtime limit and
terminate their entire process group on timeout or cancellation. Their
`run-control.json` is authoritative for interruption; checkpoint files never
turn a partial run into a successful experiment.

### Offline file validation

`scripts/validate_backend_output.py` can audit completed files recorded by an
interrupted run without needing the original executable or a complete sample
schedule. It runs at reduced priority, decodes one chunk at a time, and defaults
to a ten-second deadline with up to one second for process cleanup. It never
restarts generation or retries on timeout. Use a new output directory:

```sh
python3 scripts/validate_backend_output.py \
  --report /path/to/saved/bend/report.json \
  --profile /path/to/saved/profile.json \
  --out build/bend/saved-output-audit --max-seconds 10
```

The audit keeps completed-region results and partial progress separately. Its
supervisor's `run-control.json` determines whether the audit completed; timeout
or cancellation never becomes a pass. Source/profile/output hashes tie results
to the bytes checked. The original report, generation errors and timings are
read-only. `benchmark_backends.py --validate-only` now delegates to this same
bounded path; `--validation-out` selects its new report directory and
`--artifact` is unnecessary for that mode. A passing offline audit does not
complete the original benchmark or establish runtime/gameplay correctness.
