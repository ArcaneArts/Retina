# Compiled material predicate lookups

The post-investigation optimization cycle replaces repeated binary searches in
CPU placement predicates with immutable compiled membership checks. Empty and
one-to-four-state sets use direct comparisons. Larger, sufficiently dense sets
use a bitset; sparse sets retain binary search. Bitset storage never exceeds the
original ID-list byte length. The sorted IDs remain available unchanged to
validation and GPU program export.

Repeated lists share an `Arc` during profile parsing. A thread-local interning
index has at most 256 weak entries and prunes retired values during insertion;
it does not retain a retired profile's compiled data. Cloning a predicate shares
its immutable set. Live overlay/material queries still execute before membership
checks, including empty sets: unknown input remains unknown. Invalid ordering,
duplicate IDs, and invalid palette indices are still rejected by validation.

The exported vanilla profile contains 853 material predicates but only 61 unique
lists; Terralith contains 2,264 predicates and 127 unique lists. ID payload alone
is 275,276 / 1,010,608 bytes before sharing, versus 35,530 / 61,892 unique ID bytes.
Compiled bitsets add 7,272 / 12,408 bytes, plus the bounded interning keys and
object overhead. This is not a claim of lower whole-generator peak memory.

## Measurements

Raw samples, complete-region stage timings, binary hashes, profile hashes, and
corpus manifests are in
[`benchmarks/material-predicate-trial-2026-10-19.json`](benchmarks/material-predicate-trial-2026-10-19.json).
Baseline native source is `f759181ae8a14d194d1b738b181c235bdf2ff496`.
Both builds use normal Cargo release settings: optimization 3, thin LTO, two
codegen units. All heavy jobs ran sequentially at nice +10, with two Cargo jobs,
two CPU workers, 60-second build deadlines, and 30-second execution deadlines.
No run timed out. No game was launched.

The lookup microbenchmark tests all registered sets against a reproducible
hit/miss mixture, with five warmups and twenty alternating-order samples. It is
not a captured production query trace. Median membership time falls from
0.513 to 0.208 ms for vanilla and 1.408 to 0.631 ms for Terralith: 59.5% and 55.2%
less isolated lookup time.

The 32-by-32 planner fixtures include their normal halo and actual GPU terrain.
One warmup and five samples give median planning times of 98.36 → 88.07 ms
(vanilla) and 452.34 → 446.02 ms (dense Terralith). Complete binary exports of
every ordered placement match across builds: 246,864 and 484,709 placements.
Detailed instrumentation also matches the uninstrumented plan exactly.

Complete lit-MCA generation uses seed 123456789, regions beginning at (-1,-1),
grid width three, two callers, two CPU workers, one warmup at (8,8), and explicit
shader readiness before measurement. Time includes MCA publication; startup and
post-run NBT comparison are excluded. The first two pairs reverse variant order.
The larger third vanilla pair checks an otherwise ambiguous result.

| Profile / pair | Regions per variant | Baseline chunks/s | Compiled chunks/s | Change |
| --- | ---: | ---: | ---: | ---: |
| Vanilla A | 12 | 3,111.17 | 3,047.95 | -2.0% |
| Vanilla B | 12 | 3,054.19 | 3,009.24 | -1.5% |
| Vanilla C | 24 | 2,921.74 | 3,030.12 | +3.7% |
| Terralith A | 12 | 1,884.64 | 1,962.48 | +4.1% |
| Terralith B | 12 | 1,827.59 | 2,029.21 | +11.0% |

The implementation is retained for repeatable isolated CPU savings and improved
complete generation in both Terralith pairs. Vanilla throughput is inconclusive;
these measurements do not establish a universal speedup. Other desktop activity,
GPU waits, and the small number of batches limit extrapolation to eight callers.

Tail latency and process RSS do not consistently improve. Candidate Terralith
peak RSS is 3.35 / 3.39 GiB versus 2.86 / 3.12 GiB; vanilla varies in both
directions, including 3.02 versus 2.22 GiB in the larger pair. The lookup cache is
bounded independently of region concurrency, but faster CPU work can change
pipeline overlap. Eight-caller combined memory validation remains part of the
overall goal. Do not describe this as a whole-process memory optimization.

Logical transfers match per paired workload: 190.55 MiB/region for the twelve
vanilla regions, 189.23 for the larger vanilla set, and 203.00 for Terralith.
Output size is identical per pair. Every candidate corpus matches all decoded
NBT bytes, not only selected fields: 49,152 vanilla chunks across three pairs
and 24,576 Terralith chunks across two pairs.

An initial comparison accidentally used the stale root-level native library
left by an example-only Cargo build. Matching binary SHA-256 values exposed the
mistake. That run is explicitly excluded in the artifact. An explicit library
build in an isolated target directory published the candidate; all retained
comparisons identify distinct, pinned baseline/candidate binaries.

## Correctness and reproduction

- 85 ordinary native tests pass. New tests compare all 65,536 possible material
  IDs for tiny, sparse, dense, and high-ID sets; preserve empty-set unknowns and
  offsets; verify sharing, bounded cache size, retired-data release, and unchanged
  invalid-input validation.
- The actual-GPU vine handoff test passes complete ordered-plan comparisons at
  origins 0, -17 and 1,000,000, with live writes, provider replay, zero retention,
  discarded GPU prefixes, and output overflow falling back to the CPU.
- Whole-region comparisons preserve complete NBT, including lighting,
  structures, block entities, palettes, biomes and heightmaps.

Build the benchmark example and library explicitly, preserving each binary
before switching revisions. Use an isolated Cargo target directory when other
worktrees are active. `RETINA_PLAN_OUTPUT=/path/plan.bin` on
`decoration_plan_benchmark` exports full ordered plans outside timed intervals;
compare the resulting files byte-for-byte. The planner arguments are
`<profile> 123456789 -32 -32 5 32 2` for vanilla and
`<profile> 123456789 0 0 5 32 2` for the dense Terralith fixture.

Run `registered_material_lookup_benchmark --ignored --nocapture --test-threads=1`
on the native test binary with `RETINA_MATERIAL_PROFILE` pointing to an exported
profile. Use `scripts/native-region-benchmark.py --count 12 --warmups 1
--parallel 2 --readiness shader-ready --specialization-timeout 15` for whole
regions, with explicit pinned `--library`, `--profile`, `--out`, and paired
`--compare` directories. Use count 24 for the larger vanilla fixture.
