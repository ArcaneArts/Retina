# Ore planning investigation

The reported 23.8% of a 463.22 ms average region is about **110.25 ms per
region**, or **0.108 ms per chunk**. This is planning wall time, including time
sharing the Rayon pool with other region jobs. It is distinct from ore application.

The exported vanilla `structureTest` fixture has 40 recipes. These include dirt,
gravel, granite, diorite, andesite, tuff and other host-material patches as well as
metal/gem ores. A real GPU field at seed 123456789, chunk (0, 0), produces
**10,908,758 ordered candidate placements** across 1,024 chunks and their anchor
halo. At 12 bytes per placement, the final plans alone hold about **125 MiB**;
anchor buckets also hold candidates while they are merged. Most planning work
expands and deduplicates ellipsoids before final host-material checks.

## Measurements and rejected changes

Baseline: `9ccacaf`, Apple M4 Max/Metal, release build. The focused benchmark
samples the GPU field once, then measures only CPU ore planning using 16 workers,
five warmups and 50–100 measured iterations. Full-region comparisons use the
complete registry fixture, five warmups and 20 measured regions.

Tried last-bucket caching, sparse contributor indexing, cached sine curves,
skipping unused exposure hashes, forced inlining, earlier duplicate checks,
containment-axis rejection, upper-air candidate filtering and cached ellipsoid
axis distances. **None was retained in the generator.**

Axis-distance caching was the strongest focused candidate. Repeated median
planning latency changed from 42.42 to 39.01 ms on the original field, and from
44.64 to 36.54 ms at seed 42, chunk (-32, 32). An earlier run on the latter field
regressed from 40.34 to 42.86 ms. Overall workload results did not consistently
reproduce an improvement:

| Full-region run | Ore planning ms/region, before → candidate | Native chunks/s, before → candidate |
| --- | ---: | ---: |
| Sequential | 47.59 → 47.85 | 3,518 → 3,345 |
| Sequential, reversed run order | 43.70 → 42.88 | 3,685 → 3,612 |
| Two concurrent jobs | 68.05 → 59.97 | 4,480 → 4,548 |
| Two concurrent jobs, reversed run order | 90.43 → 93.12 | 4,203 → 3,844 |

All **81,920 compared decompressed chunk NBT records matched byte-for-byte** for
the axis-distance candidate, including concurrent generation. Correctness alone
does not establish a performance improvement. Other system activity varied; no
general speedup is claimed, and production geology code was restored.

A conservative per-chunk upper-air filter reduced stored candidates from
10,908,758 to 7,537,933 (**31%**) on the focused field, but median planning time
was essentially unchanged (42.60 → 42.80 ms). It still rasterized every vein and
consumed its random draws. Removing records after expansion is insufficient.

Early A/B runs accidentally shared Cargo example artifact names between source
trees and reused an executable. Those results were discarded. Subsequent CPU
comparisons used distinct package, library and example names with verified
different executable hashes. Whole-region comparisons used the separately
preserved baseline dynamic library. Local measurement files are in
`build/ore-final-*` and `build/ore-parallel-*`.

## High-upside opportunity: reject entire veins before expansion

- Category: `ALGORITHM_COMPLEXITY`; expected_gain: **high, unmeasured**;
  risk: **high**; complexity: **medium**; confidence: **medium**.
- Evidence: `native/src/geology.rs:388` shares a random stream across all attempts
  of an anchor/recipe; `native/src/geology.rs:546` advances it for each emitted
  voxel. The nested rasterization starts at `native/src/geology.rs:528`.
- Hypothesis: conservative terrain/fluid bounds and actual biome/height
  eligibility can reject entire irrelevant veins before generating spheres or
  enumerating their voxels. This avoids work rather than merely storing less data.
- Constraint: skipping a vein currently changes the random state for later
  attempts. Independent per-attempt geometry/exposure streams would enable
  culling, but change seed layouts. That needs an explicit generator version and
  temporary-cache invalidation. Replacement chains, air-replacing datapack
  recipes, cave-fluid replacements and boundary halos must remain supported.
- Validation: measure generated/rejected veins, examined voxels and candidate
  bytes by recipe. Compare ore counts and height distributions across vanilla and
  Terralith; verify per-chunk/MCA/DH parity, cross-region veins, exposure rules and
  concurrent promotion. Old-layout NBT equality would not be the acceptance test
  for an intentionally versioned new sampling algorithm.

## Medium-upside opportunity: control planning contention

- Category: `STATE_OR_CACHE_STRATEGY`; expected_gain: **medium, unmeasured**;
  risk: **medium**; complexity: **medium**; confidence: **medium**.
- Evidence: `native/src/region.rs:80` selects up to 16 shared assembler threads;
  `native/src/geology.rs:414` submits parallel anchor work to that pool. The
  isolated and two-job measurements differ materially.
- Hypothesis: scheduling and pool contention contribute to the much larger live
  F3 planning latency. Measure active CPU time and pool wait time separately
  before changing thread counts or stage concurrency.
- Validation: repeat matched two-job runs at 8, 12 and 16 workers, with Minecraft
  and DH enabled. Check total throughput, rolling mean/p95 region latency, CPU
  usage and render responsiveness; do not optimize only one stage's percentage.

## Low-confidence watchlist: GPU vein rasterization and compact plans

- Category: `REDUNDANT_COMPUTATION` / `HOT_PATH_ALLOCATION`;
  expected_gain: **potentially high, unmeasured**; risk: **high**;
  complexity: **high**; confidence: **low**.
- Evidence: `native/src/geology.rs:500` prunes sphere pairs;
  `native/src/geology.rs:528` rasterizes surviving spheres;
  `native/src/geology.rs:435` copies ordered candidate buffers during merging.
- Hypothesis: a region batch of compact vein descriptors could drive GPU
  rasterization, followed by compact candidate output or bitsets. Rust would keep
  registered replacement and exposure rules. Compact recipe runs could reduce
  repeated metadata and memory traffic even with CPU geometry.
- Constraint: GPU append order cannot decide overlapping material replacement
  priority. Current RNG consumption, readback size, compaction cost and bitmap
  size per recipe can outweigh device savings. This is an architectural change,
  not a proven fast replacement for the current planner.
- Validation: compare complete dispatch/readback/merge/application time and peak
  memory against CPU generation on identical workloads, including Metal and a
  discrete GPU. Preserve anchor/recipe priority and all datapack host rules.

## Reproduction

Export the full registry fixture with `./gradlew structureTest`, then run:

```sh
cargo run --manifest-path native/Cargo.toml --release \
  --example ore_plan_benchmark -- \
  build/structure-profile.json 123456789 0 0 16 100
```

Arguments after the profile are seed, chunk X/Z, worker count and measured
iteration count. JSON includes all samples, median, p95, candidate count and an
ordered signature of every index, recipe and exposure random value. Hashing is
outside the measured interval. Build baseline and candidate in distinct Cargo
target directories, or preserve the baseline executable before editing/rebuilding
the candidate. Build separately from measurements.

For full-region proof, use `scripts/native-region-benchmark.py` with
`--count 20 --warmups 5`, repeat with `--parallel 2`, reverse run order and compare
raw NBT using `--compare`. See [the native performance report](NATIVE_PERFORMANCE.md)
for complete commands. Evaluate actual region throughput as well as the focused
planner; promising microbenchmarks did not establish an overall improvement here.
