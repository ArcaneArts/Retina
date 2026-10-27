# Compact decoration programs

Dense registered placement programs no longer allocate copied filter/heightmap
actions and a heap-backed order vector for every candidate. Two matched Terralith
lit-MCA trials improved throughput by **11–14%**. Sparse and vanilla differences
were smaller and remain inconclusive.

This is the first implementation following the [October 18 triage](PERFORMANCE_TRIAGE_2026_10_18.md).
It does not finish the [remaining optimization cycles](OPTIMIZATION_CYCLES_2026_10.md).

## Implementation

- Profile decoding builds an immutable `placement::Program`: evaluation indices,
  parent-count depths, packed noise-count rules, and the last noise/expansion
  positions. Read-only access prevents mutation from invalidating this metadata.
  Registry/profile JSON and native ABI remain unchanged.
- Candidates reference an evaluation range in that program. Random position draws
  are frozen into immutable shared slices. Siblings reuse their parent's slice;
  a new offset or height draw creates a new prefix. A completed nested branch
  restores its parent's slice without mutating existing candidates/checkpoints.
- `SmallVec` keeps up to three ordering indices inline, including parent-cache
  keys. Deeper count/layer paths spill without changing their values or order.
  The dependency was already locked transitively; no package version changed.
- A suffix consisting solely of live filters/heightmaps is emitted as a compiled
  range instead of recursively visiting those modifiers during expansion. Its
  actual evaluation still happens against the live overlay in original order.
- Checkpoint accounting counts shared draw allocations once per queue and counts
  ordering storage only when it spills. The 64 MiB retention limit is unchanged.

Selectors, counts, cuboid enumeration, RNG forks/draws, parent-filter caches,
layer discovery, role ordering, and live heightmaps keep their existing semantics.
No feature density or generation quality is reduced. This still materializes
candidate queues; general streaming and GPU anchor evaluation are future cycles.

## Measurements

[Raw measurements](benchmarks/compact-decoration-programs-2026-10-18.json) include
individual region times, stage counters, profile/library hashes, readiness,
transfers, peak RSS, and exact NBT comparison results.

Apple M4 Max / Metal, release, two shared region workers and two Rayon workers,
`nice -n 10`. Seed 123456789, height 384, exported vanilla/Terralith profiles from
the triage. Six measured regions at X=-1..1, Z=-1..0, plus a separate warmup at
(8,8). Each process had a 30-second deadline. Builds used two Cargo jobs and a
60-second deadline; all completed in about 41–43 seconds.

Full lit-MCA generation includes lighting, encoding, compression, and file writing.
Shader readiness was awaited before measurement, using the current mixed path.
Startup and comparison work are excluded from the measured generation interval.
Java sidecars, loading, DH/Chunky scheduling, and rendering are outside this scope.

| Profile / build | Callers | Chunks/s | Median region ms | Sample p95 ms |
| --- | ---: | ---: | ---: | ---: |
| Terralith baseline A | 1 | 1,046 | 948 | 1,417 |
| Terralith candidate A | 1 | 1,157 | 866 | 1,249 |
| Terralith candidate B | 1 | 1,158 | 852 | 1,260 |
| Terralith baseline B | 1 | 1,017 | 956 | 1,485 |
| Vanilla candidate | 1 | 2,035 | 477 | 634 |
| Vanilla baseline | 1 | 1,968 | 481 | 687 |
| Terralith candidate, concurrent | 2 | 1,352 | 1,382 | 2,297 |

Terralith execution order was baseline/candidate/candidate/baseline. Its decoration
planning counter decreased from about 533/554 ms per region to 435/428 ms.
Other stages overlap and their counters must not be added to infer total time.
Vanilla's 3.4% throughput difference is one pair, not an established improvement.
The concurrent run verifies interleaved output; there is no matched concurrent
baseline in this cycle, so it does not establish a concurrency-specific speedup.
Six-sample p95 values are observations, not stable production tail estimates.

Final planner-only fixtures used interpreter terrain prepared before timing,
one warmup and three timed iterations, with two workers:

| Fixture (chunk origin, 32x32) | Baseline median ms | Candidate median ms | Ordered-write signature |
| --- | ---: | ---: | --- |
| Terralith dense (0,0) | 824.0 | 760.4 | `1c50195a12ef29b2` |
| Terralith sparse (-32,-32) | 101.3 | 98.2 | `a1c48709ec77dc0b` |
| Vanilla (0,0) | 128.2 | 124.2 | `a6c56cc801cb3fb4` |

The dense planner improved about 8%; the smaller sparse/vanilla differences are
inconclusive. Every signature includes ordered chunk boundaries, indices,
materials, upper states, and roles. Instrumented versus ordinary planning also
compares the complete ordered vectors directly.

Dense replay retention fell from roughly 64 MiB to 46 MiB (estimated owned
checkpoint allocations). All 184 resumed anchors fit; the baseline discarded
three checkpoints. This reduces replay rather than skipping placements. It does
not prove a whole-process RAM reduction: serial Terralith peak RSS ranged from
2,699–2,940 MiB baseline and 2,686–2,823 MiB candidate, including GPU/runtime/compiler
allocations and optional correctness comparison.

Logical transfer remains about 386.32 MiB/region for Terralith and 392.92 MiB for
vanilla. This change targets CPU allocation/interpretation, not transport. The
production eight-region limit and F3 metric definitions are unchanged.

## Correctness and rejected work

The final release unit pass passed 72 tests with two test threads; 10 diagnostic
benchmarks remained ignored and 17 GPU tests were excluded from that unit invocation.
Real-GPU lit-MCA comparisons matched every decompressed NBT byte in all 6,144
chunks per comparison, for both profiles and concurrent Terralith. These include
negative region coordinates and complete final block, biome, heightmap, structure,
entity, and lighting data.

Focused tests cover independent RNG streams despite shared draws, restoration of
immutable prefixes, nested parent/live-canopy behavior with inline and spilled
paths, cuboid draws/order, selectors, noise-count discovery, lazy ground layers,
checkpoint replay, and ordered distribution.

An earlier nested-map cache-key experiment produced only a 2% planner difference
and was removed. Compiled programs/shared draws without inline ordering paths
also did not demonstrate a speed improvement. Their raw intermediate results are
retained separately and are not substituted for the final implementation results.

Reproduce using `scripts/native-region-benchmark.py` with `--count 6 --warmups 1
--readiness shader-ready --specialization-timeout 8`, the matching profile/library,
a fresh `--out` directory, and `--compare` pointing at the retained baseline output.
Use `--parallel 2` for the concurrent correctness case. Set `RAYON_NUM_THREADS=2`
and `RETINA_REGION_THREADS=2`, lower process priority, and wrap each invocation in
a 30-second process-group deadline. Build separately with `CARGO_BUILD_JOBS=2`.
For the planner use `decoration_plan_benchmark DEFINITION DIMENSION X Z 3 32 2`.

No game launch or GitHub Actions was used. Merged changes can be tested normally
through the existing Fabric `./gradlew runClient` command.
