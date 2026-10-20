# Bounded decoration batches and substrate reuse

Final status: see the [combined integration audit](POST_INVESTIGATION_INTEGRATION.md).
References below to remaining goal work describe this cycle’s historical state.

The legacy decoration replay planner now admits at most 512 anchors at a time
on Metal when the loaded profile has an eligible GPU vine program. It reuses
one immutable vine substrate between those batches. Two reversed-order
eight-caller Terralith comparisons improved complete native throughput **4.9%
and 1.3%**; a two-caller pair improved 9.0%. These are small desktop samples,
not a universal speed guarantee.

Profiles without eligible GPU programs keep the original schedule. Forcing
batching on vanilla improved eight-caller measurements but regressed the
two-caller interpreter pair by 3.5%, so it is not enabled there by default.
Other backends also retain the original defaults. The separate source-root
dependency planner is unchanged; the exported benchmark profiles exercise
legacy replay. No improvement to modern source-root planning is claimed.

[Raw evidence](benchmarks/ready-decoration-batches-2026-10-20.json) contains
every planner variant, complete-region sample, library/profile hash, validation
log, unsuccessful build/setup, and full-output comparison result.

## Implementation and bounds

Replay drains missing providers, live-layer counts and GPU continuations for a
contiguous window before admitting the next window. Output remains indexed by
the original anchor. Windows without missing inputs finish within the same poll:
an empty query response never means unfinished anchors were skipped.

The optional substrate cache holds at most one recipe's immutable input, at most
32 MiB, scoped to the replay's field/profile/mask. Its actual vector capacity
shares the existing **64 MiB checkpoint budget**; it is not a second allowance.
New checkpoints use the remaining budget, and exhausted retention falls back to
the existing exact CPU/replay path. Changing recipes drops the old substrate.
Mutable anchor overlays are freshly packed for each dispatch.

The shared GPU arena remembers a weak identity for its last uploaded input.
Repeated batches can omit that upload only while it is still the same input.
Another region, changed input, lost buffers, resize, or arena reconstruction
requires an upload. The weak reference cannot retain the substrate vector after
the replay ends or match a newly allocated input at a reused address. The
existing GPU mutex serializes arena users, and the arena remains bounded at
128 MiB. Device failure retains the original CPU continuation.

Detailed planning now reports admitted window count, peak active anchors,
substrate cache hits and peak retained substrate bytes. Repeated-anchor counts
use the current window's round instead of treating each newly admitted window
as replayed work. These diagnostics do not add per-candidate timers to normal
generation. F3 timing definitions, the transfer ABI, MCA encoding and Java
publication behavior are unchanged.

Diagnostic overrides:

- `RETINA_DECORATION_READY_BATCH=N` sets the maximum active anchors.
  `0` restores whole-field scheduling.
- `RETINA_DECORATION_SUBSTRATE_REUSE=0` disables substrate reuse; `1` enables it.

Set both to `0` to restore the previous scheduling/upload policy. With neither
set, both optimizations apply only to Metal profiles with an eligible GPU vine
program. Unsupported/sparse programs retain existing fallbacks; no feature
attempts, RNG draws or accepted writes are removed.

## Why reuse is required

The dense Terralith planner has 2,412,278 CPU candidate evaluations in every
variant below. One warmup precedes five timed plans; detailed diagnostics come
from a separate pass. Seed 123456789, chunk origin (0,0), region side 32 plus its
planning halo, two workers, normal opt-level-3/thin-LTO release.

| Variant | Median ms | Replay rounds | Peak saved prefixes MiB | Cached substrate MiB | Vine upload MiB |
| --- | ---: | ---: | ---: | ---: | ---: |
| Whole field | 340.6 | 4 | 30.62 | 0 | 27.15 |
| 256 anchors, no reuse | 441.7 | 16 | 11.16 | 0 | 128.94 |
| 512 anchors, no reuse | 402.1 | 10 | 18.45 | 0 | 78.04 |
| 256 anchors, reuse | 359.5 | 16 | 11.16 | 25.45 | 27.15 |
| 512 anchors, reuse | 348.9 | 10 | 18.45 | 25.45 | 27.15 |

Batching alone repeats expensive substrate preparation and transfer. Reuse
restores packing to approximately 16 ms and uploads to the original amount.
The selected isolated dense planner is still 2.4% slower than the whole-field
baseline; its benefit is in the complete concurrent workloads below.

The saved-prefix peak shrinks, but cached substrate consumes memory too.
Neither these figures nor the process RSS observations establish lower total
memory usage. The combined retained budget remains bounded.

Vanilla's isolated planner changes from 84.38 to 83.25 ms when batching is
explicitly forced. It has no eligible GPU vine programs and caches no substrate.
All complete ordered plan files match byte for byte: 246,425 vanilla placements
and 485,663 Terralith placements, including rejected intermediate variants.

The [uncached prototype patch](benchmarks/ready-decoration-uncached-trial.patch)
applies to `340d61d` using `git apply --unidiff-zero` and reproduces the expensive
batch-only variant. It was not shipped as a default.

## Complete generation

Apple M4 Max / Metal, two CPU workers, nice +10, sequential experiments,
normal opt-level-3/thin-LTO builds with two codegen units. Seed 123456789, height
384, zlib and GPU lighting, warmup region (8,8), measured grid starting (-1,-1).
Six-region/two-caller runs use grid width 3; eight-region/eight-caller runs use
width 4. Rates count completed chunks through native MCA publication divided
by batch wall time, excluding Java loading, DH conversion, rendering and the
subsequent corpus comparison.

| Workload | Callers / regions | Order | Reference chunks/s | Forced batching + reuse | Change |
| --- | ---: | --- | ---: | ---: | ---: |
| Terralith interpreter | 8 / 8 | Reference first | 651.8 | 683.7 | +4.9% |
| Terralith interpreter | 8 / 8 | Candidate first | 691.4 | 700.7 | +1.3% |
| Terralith interpreter | 2 / 6 | Reference first | 399.2 | 435.3 | +9.0% |
| Vanilla shader-ready auto | 8 / 8 | Candidate first | 2,557.3 | 2,626.1 | +2.7% |
| Vanilla shader-ready auto | 8 / 8 | Reference first, delayed candidate | 2,319.4 | 2,604.2 | +12.3% |
| Vanilla interpreter | 2 / 6 | Candidate first | 1,726.6 | 1,665.9 | −3.5% |

The delayed vanilla pair has approximately seven minutes between result
timestamps, weakening attribution. An attempted additional reference failed to
reach shader-ready status within the eight-second setup allowance; it produced
no benchmark result and is retained as a failure. A prior-day standalone
Terralith run is retained without using it as a matched comparison.

Interpreter pairs explicitly disable interpreter preload. Successful vanilla
auto pairs reach readiness status 4. No Terralith shader-ready result is claimed.
Eight callers still use only two CPU workers; they do not imply eight simultaneous
GPU dispatches. Planning wall counters include contention and are not isolated
CPU instruction costs. Process RSS varies and includes Python NBT comparison.
Unrelated compilation was observed on the shared machine during final validation.

The six completed paired comparisons match **45,056 full decompressed NBT
records**, including complete lighting, biomes, structures and heightmaps.
These are repeated comparisons of a fixed corpus, not 45,056 distinct chunks.

## Correctness and reproduction

Final current-source validation passes 107 ordinary Rust tests; 37 hardware or
manual tests remain explicitly opt-in. The separate actual-Metal handoff test
passes in 16.8 seconds. Correctness tests use root opt-level 0 without LTO after
a bounded opt-level-1 build timed out; the final production library uses normal
opt-level-3/thin-LTO settings and builds in 27.1 seconds.

After integrating main's newer structure-reference fixes, comparing to the old
baseline correctly reports added trial-chamber references. Comparing enabled and
disabled policies on the **same final library** instead matches another 8,192
complete NBT records with eight callers. This brings the cycle total to 53,248
comparisons. The enabled run records four actual substrate cache hits, with
maximum cached capacity 28,218,832 bytes. This is integration evidence, not
another matched throughput claim.

`:common:mcaCodecTest` passes against that final library using Minecraft's real
readers: 900 lit/124 edge chunks per codec, complete zlib/LZ4 NBT parity,
mixed-codec promotion, preserved player edits, partial repair, malformed-source
atomicity and eight cache callers. Its flat height-64 fixture supplies lifecycle
coverage; rich height-384 region comparisons supply content coverage.

Focused replay tests compare complete ordered output across three seeds,
negative and distant origins, both root-order policies, windows 1/3/7/16/100,
and zero/16 KiB/64 MiB budgets. They exercise provider and live-layer count
queries, discarded checkpoints and query-free completion.

The actual-GPU vine handoff test additionally covers windowed execution,
zero-budget fallback, successful-prefix discard, disabled devices, queue/output
overflow, identical input reuse, a different input replacing the arena, and
buffer loss. Reused and freshly uploaded raw GPU output must match completely;
the second upload saves exactly the immutable input bytes. Combined substrate
and checkpoint retention is asserted against the same budget.

The backend-policy unit test covers Metal, Vulkan, DX12, GL, absent eligible
programs and explicit overrides. Only Metal execution/performance was tested.

Use `scripts/native-region-benchmark.py` with matching pinned libraries/profiles,
fresh output directories, `--compare`, and the caller/count/readiness settings
above. Planner reproduction uses `decoration_plan_benchmark PROFILE 123456789
X Z 5 32 2`, `RETINA_INTERPRETER_PRELOAD=0`, and `RETINA_PLAN_OUTPUT` to export
the complete ordered vector.

Builds have 60-second process-group deadlines; tests and benchmarks have
30-second deadlines, with TERM then KILL after a one-second grace period.
Some test builds exceeded the limit and were terminated. Correctness compilation
was changed to reduced optimization; this does not change the benchmark build
settings. The raw evidence records both successful and unsuccessful invocations.
A first final-test invocation accidentally used the previous binary's name after
the optimization setting changed. Its log is excluded; the stated 107 tests and
GPU result come from the newly built `retina_worldgen-f9043f18dd64833a` binary.
No game or GitHub Actions workload was launched.

Further compiled placement specialization and the final combined goal audit
remain tracked in [the implementation goal](POST_INVESTIGATION_OPTIMIZATIONS.md).
