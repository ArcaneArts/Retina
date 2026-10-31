# CPU tile parallelism

Date: 2026-10-31. Same optimized native CPU library settings, saved definition,
seed and one-region lit Nether corpus as the
[sampling-cache comparison](cpu-sampling-caches-2026-10-31.md). One caller, no
warmups, opt-in diagnostics, Apple M4 Max/macOS ARM64. No client or GPU resources.
One sample per worker setting; other compilation/checks may overlap. These are
diagnostics, not final uncontended timing or percentile claims.

| Measurement | Serial tile work | Four workers | Sixteen workers |
| --- | ---: | ---: | ---: |
| Complete region call, seconds | 38.065 | 28.489 | 22.302 |
| Host initial terrain phase, seconds | — | 2.560 | 1.214 |
| Process CPU seconds | 60.905 | 67.353 | 75.930 |
| Average scheduled cores | 1.600 | 2.364 | 3.404 |
| Summed mask/material-call seconds | 55.386 | 47.044 | 35.745 |
| Peak RSS, GiB | 1.533 | 1.433 | 1.511 |
| Saved chunks | 1,024 | 1,024 | 1,024 |

Both parallel runs match every decompressed chunk NBT record, retaining corpus
SHA-256 `2d98866a9abf049e77c4bdd7a438dc93c0493bd40c5456392301a844b8dba225`.
Initial terrain latency scales well. The dependency phase dominates afterward;
this pool is also used by root/dependency tasks, including blocking terrain-cell
initialization joins. Average utilization remains far below the pool limit.
Process CPU time rises with parallel execution, so the latency gain is not a
claim that fewer CPU instructions or less total work are performed.

Biome strips retain each sampler's X/Z traversal across Y and merge into the
existing quart layout. Material strips own reusable samplers and voxel/depth
scratch, then merge runs and offsets in canonical z-major order. Only cavity-bit
boundary words need atomic OR. All work joins before output publication or error
return. The existing global pool prevents region concurrency from multiplying
full CPU-sized worker pools. GPU dispatch does not use this CPU tile path.

Validation: 57 CPU-related checks, explicit one/four-worker packed-word equality
for both dimensions and registered/legacy material layouts, existing global
overlap/storage checks, 163,840 exact Metal material/cavity/biome comparisons,
and complete-region NBT equality at both worker settings. The optimized native
build and diff checks pass. Compact logs/measurements are retained; disposable
regions are removed after extraction.

Remaining performance acceptance includes all dimensions, small cold/warm and
reopen measurements, final no-diagnostics timing, and addressing the dependency
phase's constrained worker availability. The original CPU diagnostic region took
132.055 seconds; these changes materially improve the path but do not establish
GPU-like throughput or final release acceptance.
