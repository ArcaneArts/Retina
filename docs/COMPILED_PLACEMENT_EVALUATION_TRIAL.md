# Compiled placement evaluation trial

Final status: see the [combined integration audit](POST_INVESTIGATION_INTEGRATION.md).
References below to remaining goal work describe this cycle’s historical state.

The predecoded placement-instruction experiment is **rejected**. Complete
two-caller vanilla throughput fell 5.5% and 8.4% in opposite comparison orders.
Terralith improved in those samples, but large variation and inconsistent stage
results do not justify enabling this representation for all profiles.
Production retains the existing evaluator. No generator speedup ships here.

The accepted benchmark correction includes `Placement.tick` in both the
per-write signature and optional ordered-plan export. Earlier exports contained
index, material, upper material and role, but omitted the tick ID. Both exported
fixture profiles here have no tick definitions; complete vectors verify that
all their tick IDs are zero. Complete-region comparisons already include tick
NBT. This fixes future plan comparisons without claiming earlier terrain differed.

[Raw evidence](benchmarks/compiled-placement-evaluation-2026-10-20.json) retains
samples, diagnostics, region comparisons, hashes, build/test logs, and failed
compilation attempts. The [prototype patch](benchmarks/compiled-placement-evaluation-trial.patch)
applies to `58f6496be2b456a127b68682bab3b49901ec8076` and includes the evaluator,
differential fixture, and benchmark tick correction.

## Implemented experiment

Program construction compiled each evaluation into a compact operation:
offset, height, heightmap, unconditional acceptance, predicate, biome, water
depth, surface-relative range, or scan. Common operations carried decoded
arguments instead of repeatedly matching the generic modifier. Complex
predicates and scans retained their original immutable modifier.

The trial preserved original modifier indices, parent depths, random streams,
offset/count handling, clipping, and evaluation-cache keys. Mutable overlay
queries remained live. A differential test retained the previous evaluator and
compared more than 10,000 results plus complete evaluation-cache maps. It covered
all operation families, partial/empty ranges, clipped/unclipped positions,
shared parent state, changing overlays, unknown inputs, and negative/distant
origins. The patch retains this fixture; it is not an unused production module.

Predecoding did not establish cheaper execution. In vanilla's separate detailed
pass, placement-check worker time grew from approximately 56 to 96 ms. An extra
instruction representation can change layout, branching and compiler decisions;
the measurements establish a regression, not which mechanism caused it.
Another specialization attempt needs a new hypothesis and evidence.

## Isolated planner

Seed 123456789, side 32 plus the planning halo, two workers, one warmup and five
measured plans. Terralith starts at chunk (0,0), vanilla at (-32,-32).
Interpreter preload is disabled. The separate detailed pass is not included in
the median and has overlapping worker spans.

| Profile | Reference median ms | Trial median ms | Complete ordered writes |
| --- | ---: | ---: | ---: |
| Vanilla | 92.75 | 133.03 | 246,425 identical |
| Terralith | 667.49 | 507.98 | 485,663 identical |

Terralith's detailed replay wall time is approximately 561 versus 562 ms,
despite its faster median; placement-check time is approximately 232 versus
240 ms. Its median does not demonstrate reduced dispatch cost. Every ordered
plan byte matches, including all five `Placement` fields and chunk boundaries.
The new export uses a little-endian u32 chunk count, then each chunk's u32 write
count followed by 11-byte records: u32 index, u16 material, u16 upper material,
u8 role, u16 tick.

These profiles exercise Overworld replay. The current Java exporter enables
source-root ordering for ceiling dimensions; no measured Nether/source-root
performance improvement is claimed.

## Complete native generation

Apple M4 Max / Metal; two CPU workers, nice +10, normal release opt-level 3,
thin LTO and two codegen units. Seed 123456789, height 384, zlib, GPU lighting,
interpreter execution, preload disabled. One warmup region at (8,8), followed
by six regions from (-1,-1), grid width 3, two concurrent callers.

Rates include native MCA publication, exclude Java loading/rendering and the
subsequent full-NBT comparison, and divide completed chunks by batch wall time.

| Profile | Comparison order | Reference chunks/s | Trial chunks/s | Change |
| --- | --- | ---: | ---: | ---: |
| Vanilla | Trial, reference | 1,723.2 | 1,628.6 | -5.5% |
| Vanilla | Reference, trial | 1,761.8 | 1,613.6 | -8.4% |
| Terralith | Reference, trial | 421.1 | 539.7 | +28.2% |
| Terralith | Trial, reference | 733.5 | 757.7 | +3.3% |

All four pairs match **24,576 complete decompressed chunk NBT records**. These
are repeated comparisons of fixed corpora, not that many distinct chunks.
Raw data includes output sizes, peak process memory, transfer and stage metrics.
Overlapping wall/worker measurements cannot be added to estimate throughput.

The machine is shared. Large Terralith variation limits attribution; in its first
pair, planning time per chunk rises despite faster overall throughput. The two
vanilla regressions are sufficient to reject the shared implementation.
An eight-caller Terralith reference completed at 401.3 chunks/s and is retained
as **unpaired evidence only**. A different worktree subsequently ran a long,
CPU-heavy benchmark. No further trial benchmark was necessary for the rejection
decision, so no eight-caller candidate or improvement is claimed. Final combined
eight-caller validation remains a separate goal item.

## Validation and reproduction

The prototype passed 108 ordinary Rust tests (37 opt-in tests excluded) and the
separate actual-Metal vine handoff test. The latter exercises GPU/CPU handoff,
overflow, bounded retention, substrate reuse and disabled-device fallback.
The differential evaluator fixture is part of the ordinary tests.

Tests used a root crate at opt-level 0 with LTO off and optimized dependencies.
Measured native libraries used normal optimized production settings. The planner
example's final executable used LTO off **on both sides** after a combined
reference library/example rebuild exceeded its 60-second deadline. Its native
dependency retained normal release settings; the reference library hash remained
byte-identical to the earlier successful release build. This example-only choice
does not affect the ctypes full-region benchmarks.

The first fixture compile failed because an origin array needed an explicit
integer type; it was corrected before tests ran. The corrected test build took
7.82 seconds, ordinary tests 3.54 seconds, and actual-GPU testing 24.49 seconds.
The candidate production library built in 44.09 seconds; separate example links
completed in under two seconds. Logs include failed and successful work.

Apply the archived patch to its stated base in an isolated worktree. Run
`decoration_plan_benchmark PROFILE 123456789 X Z 5 32 2` with
`RETINA_INTERPRETER_PRELOAD=0` and `RETINA_PLAN_OUTPUT` for each variant. Both
executables must include the tick correction. Full-region reproduction uses
`scripts/native-region-benchmark.py` with pinned libraries, matching profiles,
`--parallel 2 --count 6 --grid-width 3 --warmups 1`,
`--program-execution interpreter --interpreter-preload disabled`, and `--compare`
on the second run. Raw evidence records arguments and hashes.

Builds use two Cargo jobs and a 60-second deadline. Tests/benchmarks have a
30-second deadline; cleanup sends TERM to the process group, then KILL after
one second if necessary. CPU workers are limited to two and priority reduced;
compiler/driver internals are not a hard two-core affinity limit. No game or
GitHub Actions run is part of this cycle.
