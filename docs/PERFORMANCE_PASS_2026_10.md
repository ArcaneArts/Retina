# Rust/GPU performance pass

The October pass keeps the existing terrain algorithms, loaded registry rules,
feature order, lighting and MCA publication. Accepted changes reduce work or
overlap independent stages. Measurements below are scoped experiments, not a
combined whole-generator speedup.

## Delivered areas

| Objective | Production implementation and evidence | Measured scope |
| --- | --- | --- |
| Pipeline lighting and encoding | [PR100](https://github.com/ArcaneArts/Retina/pull/100), [lighting](GPU_REGION_LIGHTING.md): two owned readback slots, waits and consumers outside the mutex, completed tiles streamed into parallel NBT/compression | Three saved-terrain lighting tiles: 19.48 → 13.33 ms median, 31.6% lower |
| Asynchronous material completion | [PR101](https://github.com/ArcaneArts/Retina/pull/101), [continuations](GPU_PIPELINE_OPTIMIZATIONS.md#material-emission-continuations-october-2026): independent continuation ownership, pooled buffers, direct append | Eight uncached material-bearing chunks with two callers: 19–22% lower batch median |
| Decoration timing and distribution | [PR102](https://github.com/ArcaneArts/Retina/pull/102), [phase diagnostics](DECORATION_PLANNING_PERFORMANCE.md): opt-in disjoint wall spans and nested worker counters, ordered parallel distribution for large plans | Dense distribution: 2.548 → 1.826 ms; sparse plans retain the serial path |
| Resume unresolved anchors | [PR103](https://github.com/ArcaneArts/Retina/pull/103), [prefix retention](DECORATION_REPLAY_RESUME.md): checkpoint RNG, queues and overlay; preserve speculative query discovery and rewind feature-local writes | Retried Terralith fixtures: 9.7–12.9% lower complete planning median |
| Batch compatible queries | [PR104](https://github.com/ArcaneArts/Retina/pull/104), [sample batching](DECORATION_QUERY_BATCHES.md): one submission/readback for independent count/provider queries | Mixed-query dispatcher fixtures: 37.7–43.6% lower median; sequential dependencies still require separate rounds |
| Reuse tree workspace | [PR105](https://github.com/ArcaneArts/Retina/pull/105), [scratch](DECORATION_SCRATCH_REUSE.md): job-owned reusable leaf/BFS/decorator storage, no new pool | Tree batches: 4.8–12.7% lower median depending on shape; whole-chunk comparisons were mixed |
| Investigate GPU trees/leaves | [PR106](https://github.com/ArcaneArts/Retina/pull/106), [decision](GPU_TREE_LEAF_EVALUATION.md): two ordered real-GPU leaf probes remain test-only | At 256 trees GPU leaf work alone exceeded complete CPU tree construction; no production offload shipped |
| GPU ore preparation | [PR107](https://github.com/ArcaneArts/Retina/pull/107), [seeded geometry](GPU_ORE_PREPARATION.md): seeds/endpoints replace CPU spheres for supported regular recipes, fused preparation/pruning/masks | Complete ore stage on 8×8 fields: 15.4% lower vanilla median, 9.1% lower Terralith median |
| Column cache | [PR108](https://github.com/ArcaneArts/Retina/pull/108), [bulk encoding](COLUMN_CACHE_PERFORMANCE.md): native packing/compression from original columns, batched Java cold reload | Full sidecar write: about 69% lower median; cold reload: 55–59% lower median |

Each linked document includes raw sample records, fixture identity, correctness
coverage, bounds and limitations. Source audit checked the current implementations
in `lighting.rs`, `region.rs`, `gpu_worker.rs`, `gpu.rs`,
`decoration.rs` and its distribution/replay/scratch modules,
`geology/raster.rs`, `geology/raster_gpu.rs`, `column_cache.rs`,
`ColumnCache.java`, `NativeTerrain.java` and `TemporaryRegions.java`.

## Completion rate and latency

F3 retains the rolling last-20-region mean, nearest-rank p95, latency-derived
chunks/sec, amortized chunk time and stage percentages. A separate **Completed
terrain** row now reports actual completed chunks divided by elapsed wall time
over the last five seconds, or elapsed session time during startup. The displayed
window length makes the startup denominator explicit (clamped to 1 ms).

For example, two 1-second regions finishing together have 1,024 chunks/sec from
region latency and 2,048 completed chunks/sec over the first wall second. Neither
number replaces the other. Temporary generation and direct generation count once;
promotion, cache hits, failed generation and zero-chunk saves do not contribute.
Partial repairs contribute only newly generated chunks. An idle window reaches
zero while the last-20 latency samples remain available. Completions are discrete,
so the short wall window can fluctuate around large batch boundaries.

This reuses the existing completion deque and adds no generation queue, worker,
GPU dispatch or per-chunk clock read. Two doubles are carried in the debug packet;
client and server should use the same build, as with the existing packet schema.
Device timings still explicitly overlap host time. Stage-specific diagnostics
separate CPU preparation, locking, submission/readback and device work where
needed; worker estimates are not independent critical-path costs.

`generationMetricsTest` uses an injected monotonic clock without sleeping.
It checks overlapping direct/temporary completions, exclusions, partial counts,
startup, exact expiry boundaries, idle decay, the retained latency window,
packet round trips and colored F3 labels. The bounded local Gradle invocation
passed in 10 seconds, including common source/test compilation. The test itself
has a 30-second timeout, two JVM processors and a 128 MiB heap.

## Preservation and resource audit

- Lighting still uses final assembled blocks, a one-chunk halo, 900 lit interior
  chunks and 124 unlit ring chunks. Region encoding assigns original slots and
  calls the existing atomic publication once after success. Lighting replay bytes,
  1,024 decoded fresh records and partial repair records matched the retained
  reference; the repair preserved 1,023 existing records and a complete save was
  unchanged.
- Material continuation tests cover exact columns/runs, interleaved profiles,
  resized buffers, reversed completion, cancellation, invalid results,
  timestamp-free operation and sender shutdown. Retained vanilla/Terralith
  block-and-column comparisons cover the decoration changes as well.
- Decoration tests compare ordered placements, RNG tails, selector budgets,
  live overlays, six heightmaps, equal-order candidates, cross-boundary clipping
  and query batches. Prefix retention is capped at 64 MiB per replay; oversized
  checkpoints use exact full replay. Tree scratch retains at most 2 MiB per job.
- Ore tests compare actual final blocks, hosts/replacement chains, exposure,
  scattered recipes, far/negative coordinates, reused buffers and CPU repairs.
  The actual-GPU probe matched 83,200 sphere records and pruning decisions plus
  2,192 arithmetic pairs. Unsupported cases retain the existing path.
- Column tests verify old/new zlib sidecar compatibility, arbitrary integer bits,
  batch edges, malformed input, Unicode paths and concurrent writers. The real
  cache lifecycle test covers cold reload without generation, LRU deletion,
  promotion, preserved edits/partial saves, pinned shutdown and a sidecar write
  failure after MCA creation. The existing two-region permit limit is unchanged.
- Native unit validation reached 71 passing tests in the ore cycle. Cache work
  then passed its focused C-ABI/Java tests and native release build. The metrics
  cycle changes no native algorithms and does not repeat GPU benchmarks.
- Timing records retain sample maxima and process memory observations. Short
  comparisons do not establish stable production p95; combined-process RSS is
  not evidence of per-path memory savings. Active buffers may exceed retention
  budgets transiently. No unlimited queues or additional region workers were
  introduced.
- Local builds used two jobs/codegen units and reduced priority; checks used
  bounded processes and two runtime workers. No Minecraft session was launched
  for this pass. The root `runClient` alias still targets only Fabric; a bounded
  dry-run confirmed that task graph without running compilation or the client.
- PR100–108 are merged, and GitHub reports no Actions runs for their head or
  merge commits. Each implementation was isolated and primary main was advanced
  by fast-forward. Unrelated worktrees are preserved.

## Limits and next opportunities

The overall gain cannot be obtained by adding the stage percentages: work overlaps,
different fixtures activate different rules, and some whole-chunk comparisons
were flat or mixed. No new sustained region benchmark or complete game-load
speedup is claimed. Actual GPU checks ran on Apple M4 Max/Metal; other backends
still need hardware validation.

Future work can target tighter conservative ore readbacks, additional independent
decoration batches, or keeping whole independent feature anchors GPU-resident.
Those are new experiments, not unimplemented promises of the accepted incremental
paths. Full GPU tree geometry remains dependent on equivalent RNG, numerical
operations and live-overlay ordering; the tested leaf-only transfers did not win.
