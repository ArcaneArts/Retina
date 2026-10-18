# Streamed decoration candidates

Eligible terminal count branches now retain a cursor instead of a candidate for
every child. Common positional draw traces stay inline. Two matched Terralith
lit-MCA trials improved throughput **5.6–8.1% against PR #115** while preserving
every decompressed chunk NBT byte. The final dense planner comparison improved
18.4%; this is not a claim of an 18.4% whole-generator speedup.

This follows [compact programs](COMPACT_DECORATION_PROGRAMS.md). The
[cycle tracker](OPTIMIZATION_CYCLES_2026_10.md) retains the remaining GPU and
transport work. No generation settings, feature counts, native ABI, F3 metrics,
or production concurrency limits changed.

## Implementation and ordering

`placement::Program` identifies the last branching modifier. When it is an
ordinary or noise-derived count, it compiles the following random operations
once: rarity/chance/select, in-square, offsets, and height providers. A cursor
holds the parent RNG state, child index, immutable positional prefix, and reusable
scratch. It emits the next accepted child using the original provider methods
and RNG forks/draws. Static live-world predicates remain in their original
ordered evaluation path; they are not hoisted into expansion.

The queue merges one successor at a time with other recipes, selectors and
terrain-dependent layers. Successors that precede the sorted vector's next entry
use its fast path; interleaved successors use the existing ordered heap. Selecting
a child advances its cursor once. Retrying that child after a missing provider
cannot advance it again. Queue checkpoints own cloned cursor state and preserve
the original prefix, cache, and overlay semantics.

Two positional draws fit inside each candidate without a per-leaf `Arc`
allocation. Longer traces and deeper order paths spill without truncation.
Cursor prefixes remain shared and immutable. Programs whose final branch is a
cuboid or terrain-dependent layer retain their existing expansion path; eligible
counts after those branches still stream. Counts of zero/one retain the ordinary
path. No attempts, features, or densities are removed.

The opt-in planner diagnostic field `worker.candidates` is renamed
`worker.queued_entries`: it counts physically enqueued initial entries, which
may now represent many future leaves. `evaluated_candidates` continues to count
actual evaluations. Production telemetry is unchanged. Replay retention includes
cursor storage and spilled traces; shared cursor prefixes are conservatively
counted for each owner. It remains an estimate, not process RSS.

## Measurement

[Raw results](benchmarks/streamed-decoration-candidates-2026-10-18.json) retain
individual region samples, comparison manifests, stage/transfer/memory counters,
profile/library hashes, and separate intermediate experiments.

Apple M4 Max / Metal, release builds, two shared region workers and two Rayon
workers, `nice -n 10`. Seed 123456789; height 384; the same exported vanilla and
Terralith profiles as the preceding cycle. Each region run uses six measured
regions at X=-1..1, Z=-1..0 and one separate warmup at (8,8). Measurement waits
for current mixed shader readiness and includes lighting, encoding, compression,
and final writing. Startup and NBT comparison are outside generation timing.
Each execution has a 30-second process-group deadline; builds use two Cargo jobs
and a 60-second deadline. The final production and unit builds completed in
about 40 and 42 seconds. No game or GitHub Actions was run.

| Profile / build | Callers | Chunks/s | Median region ms | Sample p95 ms |
| --- | ---: | ---: | ---: | ---: |
| Terralith PR #115 A | 1 | 1,239 | 807 | 1,172 |
| Terralith streaming A | 1 | 1,308 | 781 | 1,082 |
| Terralith streaming B | 1 | 1,320 | 783 | 1,064 |
| Terralith PR #115 B | 1 | 1,222 | 808 | 1,207 |
| Vanilla streaming | 1 | 2,279 | 424 | 569 |
| Vanilla PR #115 | 1 | 2,180 | 435 | 589 |
| Terralith streaming, concurrent | 2 | 1,548 | 1,124 | 2,049 |

Order was baseline/candidate/candidate/baseline for Terralith, candidate/baseline
for vanilla. Rates use completed chunks divided by measured batch wall time.
Vanilla's 4.6% difference is one pair and remains inconclusive. The concurrent
case verifies interleaved output, with no matched concurrent baseline here;
it does not establish a concurrency-specific speedup. Six-region p95 observations
are not stable production tail estimates. Java sidecars, DH/Chunky scheduling,
loading and rendering are outside this native benchmark.

Terralith decoration planning decreased from approximately 404/411 to 354/353 ms
per region. Logical transfers remain 386.32 MiB/region for Terralith and 392.92
for vanilla. Serial Terralith peak RSS was 2,867–3,199 MiB baseline and
3,040–3,148 MiB candidate, including GPU/compiler/runtime and optional comparison
allocations. These measurements do not prove a whole-process memory reduction.

Final planner-only comparisons prepare interpreter terrain outside the clock,
then run one warmup and three timed iterations with two workers on 32x32 chunks:

| Fixture / chunk origin | Baseline median ms | Streaming median ms | Ordered-write signature |
| --- | ---: | ---: | --- |
| Terralith dense (0,0) | 738.5 | 602.7 | `1c50195a12ef29b2` |
| Terralith sparse (-32,-32) | 96.5 | 85.2 | `a1c48709ec77dc0b` |
| Vanilla (0,0) | 118.5 | 104.0 | `a6c56cc801cb3fb4` |

Dense estimated replay retention fell from 47,963,932 to 7,004,628 bytes
(45.7 to 6.7 MiB), and initial queue entries from 8,406,197 to 257,143. Both
versions evaluated exactly 8,664,271 candidates and emitted 484,709 writes.
Expansion/evaluation worker counters overlap wall time; moving expansion into
cursor iteration shifts work between those counters. They must not be added
as though each were critical-path wall time.

## Correctness and experiments

The final release unit pass passed 74 tests, with 10 diagnostic tests ignored
and 17 GPU tests excluded from that invocation. New eager-versus-streaming
checks compare every ordered candidate field, RNG seed, positional draw, and
live evaluated position. Cases cover shared selectors, nested counts, random
filters/offsets/heights, inline and spilled paths, layers, cuboids, legacy noise
counts, all-rejected streams, zero/wrapping seeds, checkpoint cloning, retry,
and live overlay writes. A 4,096-child count retains one entry while producing
identical results. Unsupported terminal cuboids preserve eager behavior.

Real-GPU full-region comparisons matched all 6,144 decompressed NBT chunks per
comparison: both Terralith candidate trials, repeated baseline, vanilla, and
concurrent Terralith. This covers full blocks, biomes, heightmaps, entities,
structures, and lighting, including negative region coordinates. Planner
instrumentation additionally compares complete ordered placement vectors against
ordinary planning. Cross-binary planner signatures are supplemental fingerprints,
not substitutes for the full-NBT comparisons.

The first fixed-position-only cursor removed almost no dense entries. General
positional cursors reached useful coverage but retained expensive per-leaf draw
allocations and showed only small full-region differences. Inline draws and
direct successor construction produced the accepted result. An earlier dense
inline run measured 572 ms against 768 ms; the final repeat above is more
conservative. All experiments are labeled separately in the artifact.

Reproduce full regions with `scripts/native-region-benchmark.py --count 6
--warmups 1 --readiness shader-ready --specialization-timeout 8`, matching
`--library`, `--profile`, and a fresh `--out` directory. Use `--compare` against
retained baseline output and `--parallel 2` for interleaved correctness. Planner
arguments are `PROFILE 123456789 X Z 3 32 2`. Use the worker, priority, and deadline
limits above. Merged changes remain testable with Fabric `./gradlew runClient`.
