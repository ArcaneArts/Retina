# Prepared placement lookup trial — rejected

Prepared material layouts, cached column slices, and compiled biome recipe
membership were implemented and tested. **None of this cycle's changes is enabled
in production.** The microbenchmarks improved substantially, but the combined
prototype regressed the vanilla planner and the isolated biome implementation
did not improve complete-region throughput. Production keeps the existing paths,
including the previously merged compiled material predicates and compact GPU
continuations.

This closes these particular implementations with evidence, rather than shipping
them based on lookup-only timings. The broader
[post-investigation goal](POST_INVESTIGATION_OPTIMIZATIONS.md) remains open.

## Implemented experiments

The material prototype added an immutable field-local substrate view holding the
original mask through a private Arc. It prepared column width, entry/group offsets,
and payload offsets once, rather than recomputing them in each query. Borrowed
column slices reused those offsets. A short-lived last-column cache served
environment scans and initially heightmap scans. Every block query still checked
the live overlay first; no mutable material results were cached.

The biome prototype replaced linear recipe membership with an immutable ordered
list plus a bounded lookup representation. Small lists kept direct slice lookup;
dense IDs used bitsets, and sparse IDs used a separate sorted copy. Original
order and duplicate entries were preserved for iteration, validation, and export.
Clones shared the immutable data. Additional membership payload was bounded by
the original ID-list bytes: 792 bytes for the vanilla export and 4,424 for
Terralith, excluding object/Arc overhead.

Three variants were measured:

1. Both prepared lookups, with column caching in environment and height scans.
2. Both prepared lookups, with the height-scan cache removed. This tested whether
   initializing a cache for usually short height scans caused the regression.
3. Biome membership only, restoring the original material/height/scan paths.

The height-cache removal did not resolve the planner regression. Biome-only
planning improved about 1.8% in its pair, which is inconclusive, and its four
complete-region comparisons were all slower. The exact cause of the material
view's regression is not established; the trial does not claim that preparing
metadata can never help. Do not repeat this implementation unchanged.

## Measurements

[Raw evidence](benchmarks/prepared-placement-lookups-2026-10-19.json) includes
every region sample, planner samples, library/profile hashes, stage counters,
transfers, output sizes, peak RSS, and correctness results for all variants.

Apple M4 Max / Metal; native source baseline `43a809c`, using the pinned #129
binary whose native source matches that revision. The later `dbbdfeb` merge only
changes Java/Gradle/docs and is included in this report's PR base. Normal release:
opt-level 3, thin LTO, two codegen units. Two Cargo jobs and two Rayon/region
workers, nice +10, with builds limited to 60 seconds and each execution to 30
seconds plus one second for process-group termination. Builds took 42–46 seconds.
No benchmark timeout, Minecraft launch, or GitHub Actions workload was needed.

Microbenchmarks use five warmups and twenty timed iterations, alternating mode
order within each round and comparing results. Parsing/setup is outside timing.
The material fixture uses 180,224 same-column/vertical queries across four-run
columns. The biome fixtures use 57,344/154,624 queries across the loaded lists.
These access patterns isolate lookup costs; they do not model all placement work.

| Microbenchmark | Original median ms | Prepared median ms | Time reduction |
| --- | ---: | ---: | ---: |
| Material query | 0.835 | 0.522 | 37.5% |
| Repeated-column material query | 0.835 | 0.364 | 56.4% |
| Vanilla biome membership | 0.271 | 0.094 | 65.2% |
| Terralith biome membership | 0.718 | 0.311 | 56.7% |

Planner fixtures prepare interpreter terrain before timing, use one warmup and
five measured iterations, followed by a separate detailed pass. Each is 32x32
chunks, seed 123456789, with vanilla origin (-32,-32) and Terralith (0,0).

| Variant / fixture | Baseline median ms | Candidate median ms |
| --- | ---: | ---: |
| Combined initial / vanilla | 84.3 | 95.2 |
| Combined initial / Terralith | 355.7 | 336.0 |
| Without height cache / vanilla | 81.6 | 92.0 |
| Without height cache / Terralith | 337.8 | 319.3 |
| Biome only / vanilla | 89.2 | 87.6 |
| Biome only / Terralith | 377.4 | 370.6 |

Full lit-MCA runs use twelve regions X=-1..1/Z=-1..2, a separate warmup at (8,8),
height 384, two callers, and shader-ready status 4. Each profile/variant uses
baseline/candidate then candidate/baseline. Rates count completed chunks divided
by measured batch wall time through publication. Startup and comparison work are
outside that interval; Java loading, sidecars, DH/Chunky and rendering are excluded.

| Variant / fixture | Pair A, baseline → candidate chunks/s | Pair B, baseline → candidate chunks/s |
| --- | ---: | ---: |
| Combined initial / vanilla | 3,030 → 2,979 | 2,876 → 3,041 |
| Combined initial / Terralith | 1,983 → 1,972 | 2,007 → 2,032 |
| Without height cache / vanilla | 3,087 → 3,243 | 2,965 → 3,017 |
| Without height cache / Terralith | 2,135 → 2,038 | 2,026 → 2,019 |
| Biome only / vanilla | 3,117 → 2,986 | 3,146 → 3,104 |
| Biome only / Terralith | 2,078 → 2,062 | 2,002 → 1,996 |

Desktop variability and small samples limit the precision of these differences.
They do not justify a production speedup claim. The vanilla planner regression
recurs with and without height caching; the isolated membership version also
fails the complete-workload acceptance criterion. Reduced lookup time alone is
insufficient. The raw report retains negative and inconclusive samples equally.

## Correctness and reproducibility

The combined implementation passed 90 ordinary native tests and the actual Metal
handoff/fallback test. Thirty-seven diagnostic/hardware tests were ignored by the
ordinary test invocation. Existing placement tests exercise live overlay changes,
carved materials/fluids, and 3D biome membership. New tests compare raw/prepared
runs and all heightmaps across negative/distant coordinates, vertical boundaries,
unknown columns, immutable ownership, and ordered/duplicate/extreme recipe IDs.

All three variants' complete ordered planner files match their corresponding
baseline for both profiles; detailed and ordinary plans also compare equal.
The complete-region comparisons match 221,184 decompressed NBT records across
repeated corpora, not that many unique chunks. No save/promotion/lifecycle claims
are inferred from those comparisons.

Two patches preserve the implemented alternatives against `43a809c`:

- [Prepared material view plus membership, without height caching](benchmarks/prepared-material-view-trial.patch).
- [Isolated biome membership](benchmarks/prepared-biome-membership-trial.patch).

Both patches were checked for clean application. Apply only one to an isolated
checkout of that revision. Build with the resource limits above. For focused
tests use `prepared_material_lookup_benchmark` or
`registered_recipe_membership_benchmark` with `--ignored --nocapture`; the latter
takes `RETINA_LOOKUP_PROFILE` pointing to an exported profile. The material test
is present only in the combined patch. The earliest height-caching variant is
retained as measurement evidence; the repeatable combined patch is the revised
version without that cache.

Use `decoration_plan_benchmark DEFINITION DIMENSION X Z 5 32 2` with
`RETINA_INTERPRETER_PRELOAD=0` and `RETINA_PLAN_OUTPUT` for complete vector export.
Use `scripts/native-region-benchmark.py --count 12 --warmups 1 --parallel 2
--readiness shader-ready --specialization-timeout 15`, pinned matching libraries,
fresh outputs, and `--compare` for full NBT comparison. Source/profile/library
hashes and individual samples in the raw report identify the measured versions.

The next optimization should target the measured large CPU continuations and
GPU overflow/sparse batching rather than further tuning this rejected lookup
representation without a different design or new evidence.
