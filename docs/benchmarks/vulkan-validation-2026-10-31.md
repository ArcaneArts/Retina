# Vulkan efficiency pass: local validation and remaining Windows work

This pass improves observability and removes repeated foreground cache-file I/O.
It does not establish Vulkan/DirectX performance parity on NVIDIA.

## Delivered changes

- Optional Vulkan driver feedback reports support, validity, supplied application
  cache, reported hit and creation duration. Loaded bytes are not reported as hits.
- Region-sized debug jobs identify individual interpreter/specialized/fallback
  stages and correlate selection with completion by job ID and block origin.
- Source preparation, backend translation/driver spans and cache checkpoint/write
  timings remain behind debug. Normal preparation progress remains available.
- Cache snapshots stay serialized on their compiling lane, coalesce progress, and
  persist through a bounded writer. Java and native CLI flush completed work on
  normal shutdown without waiting for an unfinished driver compile.
- An explicit developer-only portability feature exercises the actual Vulkan
  path on macOS. Production Windows optimization defaults are unchanged.

## Metal comparison

Baseline: `3fd5d28128cbef1d5628a9602785d4e0bcddd9a2` (before this goal).
Candidate native source: `c1a6581bb95c4f2d5addb74a3e5cec40b3eaa021`.
Both libraries were built from those clean source states in release mode.

M4 Max, AC power, normal power mode (`powermode=0`), one caller, identical vanilla
saved definition/seed, Overworld, lighting disabled, native region/MCA completion.
Four fresh processes ran A/B/B/A, each with five warmups and twenty measured
regions. Measurement starts after specialization reaches its final ready state.
All measured program states were 4 (specialized materials ready; terrain policy
can still select an interpreter), rather than a failed/incomplete compilation.

| Per-region wall latency, 40 samples per build | Baseline | Candidate |
| --- | ---: | ---: |
| Median | 410.221 ms | 381.010 ms |
| p95, nearest rank | 619.600 ms | 585.726 ms |

The performance regression guard passes its 5% median-slowdown threshold. Desktop
load varied, and per-process candidate throughput ranged from 2,039 to 3,191
chunks/sec versus baseline 2,349–2,476. The direction was not consistent across
trials; these observations do not establish a Metal speedup. All four corpora
have the same chunk-NBT hash. Both candidate trials compared all 20,480 chunks
against baseline, for 40,960 explicit candidate comparisons.

Fresh-process initialization, first-region work, background compile duration and
steady region measurements are recorded separately in
[vulkan-metal-validation-2026-10-31.json](vulkan-metal-validation-2026-10-31.json).
The first baseline compile took 30.75 seconds; later fresh-process compiles took
2.07–5.68 seconds. This was not a controlled clearing of Metal's internal cache,
so it is not a before/after cold-driver compilation claim.

A separate `--startup-debug` run verified real mixed-path summaries: the initial
job used interpreter/fallback stages, and a ready job mixed specialized stages,
interpreter height nodes and fallback material-prefix passes. Both jobs emitted
matching selected/completed IDs. These debug timings are excluded from the
headline comparison.

## Correctness and portability

- Native suite: 398 passed; 103 environment-dependent tests ignored in the default
  build. Portability build: 398 passed, 104 ignored.
- Real Metal shared/independent dispatch parity: Overworld and Nether,
  interpreter and specialized modes, negative coordinates, expanded height and
  legacy composition all pass.
- The supplied large datapack still validates at 16,890 nodes / 1,080 live values.
  Its cached/uncached GPU queries match exactly at (0,0) and (-3,-3).
- Actual Vulkan-on-Metal smoke: four deterministic queries per process, first and
  repeated launches, with the adapter verified as Vulkan. See the
  [portability report](vulkan-compilation-followup-2026-10-31.md).
- Writer tests cover replacement during a blocked save, concurrent producers,
  failure/recovery and final drain. Real envelope/file validation remains intact.
- The [persistence component benchmark](vulkan-cache-persistence-2026-10-31.md)
  measures foreground copy/hash/file cost separately from driver serialization.
- Java core compilation, native CLI checks and benchmark script syntax pass.
  Benchmark helper tests: nine passed, one optional dependency case skipped.

Raw local logs, guard JSON and temporary benchmark corpora are under
`~/.cache/retina/vulkan-efficiency-20261031/`. The Windows test JAR is delivered
separately at the established `build/release/retina-windows-x64.jar` path after
this PR merges; it is not a published GitHub release.

## Remaining evidence

Use [Windows backend retest](../WINDOWS_BACKEND_RETEST.md). Determine whether
Vulkan's slow invocations report application-cache hits, which driver phases
still dominate, whether both backends reach equivalent execution paths, and
which GPU stages remain slower after preparation settles. Keep fast interpreter
compilation as an explicit experiment; it may trade generation throughput for
startup speed. Further shader/compiler changes require that evidence.
