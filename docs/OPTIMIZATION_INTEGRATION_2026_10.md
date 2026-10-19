# Combined Rust/GPU optimization validation

The accepted changes improve complete native lit-MCA throughput by **7–9% for
vanilla and 25–32% for Terralith** in two matched, reversed-order comparisons.
Logical transfers fall approximately **52% and 47%**, respectively. All four
candidate corpora match the starting implementation's complete decompressed NBT.
Process peak memory is somewhat higher in several candidate runs; this is not a
memory-reduction claim or a measurement of Minecraft loading/rendering speed.

[Raw evidence](benchmarks/combined-optimization-validation-2026-10-19.json) retains
individual regions, stage counters, startup observations, library/profile hashes,
test/build logs, runner sources and the merged-PR/Actions audit. The
[cycle tracker](OPTIMIZATION_CYCLES_2026_10.md) links each accepted implementation
and rejected experiment. These results compare the combined implementation with
the starting baseline; earlier stage speedups are not added together.

## Matched complete-generation results

Apple M4 Max / Metal, seed 123456789, height 384, exported vanilla and Terralith
profiles, twelve regions at X=-1..1/Z=-1..2, and one separate warmup at (8,8).
Each process has two shared CPU workers and two concurrent callers at nice +10.
All measured regions use shader-ready mixed execution (status 4). Runs execute
sequentially, with the candidate/reference order reversed in the second pair.
Startup and NBT comparison are outside the timed generation interval.

The baseline is `d4f300f4983f14bee40f7fe73e9646b9ed0cd7fb`. The candidate is
`1a3bde97bc8f5124a9a3b1397fc78e1d97c79093`, whose native tree is identical to
PR #120's `1bc1009a450db57b952f7b851675206a9cc3c527`. Both libraries use matched
final-crate optimization level 2 without LTO to fit the bounded build deadline;
dependencies retain repository release settings. Normal optimization-level-3,
thin-LTO unit/GPU tests also pass, but the rates below are not measurements of that
different build configuration. Toolchain and exact binary hashes are in the artifact.

| Profile / pair | Baseline chunks/s | Current chunks/s | Change | Mean region ms, before → after | Sample p95 ms, before → after |
| --- | ---: | ---: | ---: | ---: | ---: |
| Vanilla / reference first | 2,563 | 2,738 | +6.8% | 756 → 726 | 1,258 → 1,359 |
| Vanilla / candidate first | 2,471 | 2,691 | +8.9% | 811 → 741 | 1,527 → 1,160 |
| Terralith / reference first | 1,375 | 1,819 | +32.3% | 1,464 → 1,092 | 2,667 → 1,766 |
| Terralith / candidate first | 1,388 | 1,729 | +24.5% | 1,466 → 1,160 | 2,777 → 1,801 |

Throughput divides completed chunks by batch wall time through MCA publication.
It is not the inverse of region latency. Twelve-sample p95 values are descriptive,
not stable production tail estimates. Desktop activity is uncontrolled. Each
candidate corpus matches all **12,288** NBT records and region membership; the
two repeated baseline corpora match as well. These repeats reuse the same
coordinates, rather than representing additional unique terrain.

| Profile | Upload MiB/region, before → after | Readback MiB/region, before → after | Total MiB/region, before → after |
| --- | ---: | ---: | ---: |
| Vanilla | 249.66 → 100.02 | 143.41 → 90.53 | 393.07 → 190.55 |
| Terralith | 249.88 → 115.98–115.99 | 136.72 → 87.01 | 386.60 → 202.99–203.00 |

These are logical host/device payload counters, not physical unified-memory bus
traffic. Process peak RSS for the vanilla pairs is 2.211 → 2.421 GiB and
2.372 → 2.379 GiB; Terralith is 3.091 → 3.178 GiB and 3.202 → 3.555 GiB. These
include CPU/GPU runtime caches and compilation; they do not isolate retained GPU
memory. Buffer ownership remains bounded, but whole-process memory is not lower.

Terralith decoration planning decreases from 702–729 to 435–516 ms/region. This
wall counter includes its GPU work. Structure planning remains roughly
140–161 ms, and terrain GPU work/readback remains roughly 261–275 ms, including
actual device execution. NBT worker time rises from 83–84 to 93–94 ms in these
combined samples; compression stays around 84–85 ms. The combined improvement
does not mean every stage improved. Worker and device counters overlap and must
not be summed into a purported critical path.

## Specialization decision

Keep the current mixed stage selection. A separate current-library six-region
Terralith diagnostic measures 1,546 chunks/s in mixed mode versus 758 chunks/s
with the interpreter forced, with both matching all 6,144 retained NBT records.
Mixed mode waits for ready shaders; explicit interpreter mode has no background
specialization to await. This single pair corroborates the earlier
[triage](PERFORMANCE_TRIAGE_2026_10_18.md); it is not a new shader-change speedup.

The actual-registry integration check also compares specialized/interpreted
outputs across 24 chunks and three seeds. Existing
[stage-selection evidence](GPU_STAGE_SELECTION.md) explains why all-specialized
terrain is not the default. This goal extended specialization where the measured
workload justified it: complete eligible registered vine programs. Remaining
decoration CPU work and structure planning are more useful next profiling targets
than indiscriminately compiling one giant shader. No speculative new compiler
variant is enabled, and the existing mixed/interpreter fallback remains intact.

## Combined correctness and lifecycle checks

The current native source passes 82 ordinary unit tests. Additional actual-Metal
checks cover vine handoff, ordered output, distant origins, bounded retention,
whole-result overflow and CPU fallback; lighting buffer growth, interleaving and
abandonment; streaming consumer failure/recovery; exact compact input/output with
registered faces and padding; cancelled readback cleanup; and interleaved material
continuations for both loaded profiles, including reverse completion, changing
dimensions, sparse queries, failures and cancellation. The normal native test
build takes 47.17 seconds under the 60-second limit.

Java checks compile current sources and load the recorded current native library:

| Requirement | Direct evidence |
| --- | --- |
| Eight-region admission and bounded scheduling | `RegionPreparationTest` runs the scheduler and ahead-pump fixtures: eight immediate admissions, blocked ninth permit, independent refill, deduplication, pause/drain, cancellation, failures, exactly-once lease release and late shutdown cleanup. Permit holders are lightweight; this is not eight full regions generating simultaneously. |
| Ordinary MCA loading and partial saves | `NativeRegionIntegrationTest` decodes all 1,024 records using Minecraft classes, checks palettes/heightmaps/coordinates, preserves player block and custom-NBT edits, repairs only missing slots, leaves complete files byte-identical and rejects corruption without overwriting it. |
| GPU light fidelity and single publication | `NativeLightingIntegrationTest` compares 4,128,768 light values with Minecraft, checks prelit/unlit seams and dynamic edits, and checks the first published MCA has 900 lit interior chunks and 124 unlit border chunks. |
| DH-style surface generation and promotion | `NativePreviewIntegrationTest` exercises actual terrain/surface calls, shared temporary MCAs, promotion without native regeneration, concurrent requests/edits, empty placeholders, partial promotion, cold columns/material companions, LRU eviction, save isolation, shutdown and native failure cleanup. |
| Column-cache lifecycle and I/O failure | `ColumnCacheLifecycleTest` checks exact reload, eviction, promotion, preservation of compressed records, active leases at shutdown and an actual sidecar-write failure with orphan cleanup. |
| Loaded datapack behavior | `NativeDatapackIntegrationTest` loads the installed Terralith ZIP, checks registered noise normalization, climate/specialization parity, feature density and shore boundaries, retains the selected generator through codec reopen, and promotes/decodes its actual MCA, including material IDs above 255. |
| Actual Chunky and Fabric I/O | Chunky-Fabric-1.5.3's own iterators/shapes match the region sequence for six shapes, negative boundaries and resume offsets; its real GenerationTask receives the mixin. Actual I/O mixins allow ready reads/scans past pending terrain. Native ahead publication creates all MCA slots before chunk loading, coalesces duplicate requests and preserves concurrent edits. |
| F3 and packet semantics | `GenerationMetricsTest` verifies last-20 latency, the ten-second wall window and startup duration, partial-region accounting, exclusion of hits/promotions/failures/existing slots, profile transfer denominators, large counters, colored formatting and packet round trips. |

Source inspection of `native/src/region.rs` confirms lighting and encoding
finish before the single publication path writes a sibling file, syncs it, renames
it atomically and syncs the directory. Failure removes the temporary file. No
later lighting rewrite was introduced; the partial-save fixtures separately
verify preservation and repair of existing records.

The installed-Chunky run initially exposed a test-fixture conflict: the simulated
startup test expects its own controllable `ChunkyProvider.loaded` field, while the
real JAR supplies a different class. The simulated transition is now explicitly
disabled only in the installed-Chunky test mode. Six real-mode tests pass; its one
skipped simulated transition passes separately in the ordinary mode, alongside
the unrelated-failure test. Both modes and the initial failure are retained in
the artifact. This changes test selection, not production error handling or
generation admission. A temporary classpath-export initialization script also
needed its Gradle project lookup corrected; the corrected source compilation
passes. No failed runtime assertion was bypassed.

## Requirement audit and delivery

| Goal area | Disposition and evidence |
| --- | --- |
| 1. Compact/compile placement programs | [PR #115](https://github.com/ArcaneArts/Retina/pull/115) compiles reusable operations/metadata and compacts references; [PR #116](https://github.com/ArcaneArts/Retina/pull/116) streams eligible ordered candidates. Reports cover sparse/dense planning, replay memory, RNG/order and whole-region parity. Current combined NBT and native tests validate their integration. |
| 2. Complete GPU feature anchors | [PR #117](https://github.com/ArcaneArts/Retina/pull/117) evaluates complete programs; [PR #118](https://github.com/ArcaneArts/Retina/pull/118) integrates the measured vine subset with bounded storage and exact CPU continuation. It targets the registered program responsible for most candidates in the dense fixture. Current handoff/fallback tests and NBT comparisons pass. |
| 3. Lighting transport | [PR #119](https://github.com/ArcaneArts/Retina/pull/119) compacts uniform material inputs; [PR #120](https://github.com/ArcaneArts/Retina/pull/120) compacts constant outputs. Exact light and NBT tests pass. Broader halo residency is deferred because ownership/memory changes lack a demonstrated benefit; the effective combinations are shipped. |
| 4. GPU material output arena | [PR #121](https://github.com/ArcaneArts/Retina/pull/121) retains two exact prototypes and overflow/interleaving evidence. Faster isolated material work did not establish faster complete generation and increased transfer/memory, so production continuations remain. |
| 5. Ore masks | [PR #122](https://github.com/ArcaneArts/Retina/pull/122) retains two conservative uncertainty-mask encodings, exact geometry/application tests and full-region comparisons. Smaller transfers did not produce a reliable throughput gain; production remains unchanged. |
| 6. Sections/NBT/compression | [PR #123](https://github.com/ArcaneArts/Retina/pull/123) retains reusable classifications/bulk biome fetches and exact tests. NBT work falls 20–25%, but whole-generation results are inconsistent, including slower vanilla samples; production encoding and level-1 compression remain. |
| 7. Combined integration | This cycle supplies the matched starting-baseline comparison, current-source lifecycle/reference/telemetry checks, real Chunky integration, specialization review and reproducible artifacts above. |

Every prior cycle is merged; the artifact records full head/merge SHAs and zero
Actions runs for PRs #115–#123. This cycle uses its own `codex/pipeline-integration`
worktree/PR and squash automerge. All expensive jobs execute sequentially with
two Cargo/Gradle workers, two JVM processors, two native CPU workers and nice +10.
Compilation is capped at 60 seconds; test/benchmark processes at 30 seconds, with
process-group termination and one-second cleanup grace. No execution times out.
The eight-region production budget is unchanged. Minecraft is never launched.

## Limits and reproduction

This establishes the measured combined benefit on Metal, not Vulkan/DX12 speed or
maximum all-core/eight-caller throughput. The native completion boundary excludes
Java sidecar creation, ticket loading, remaining game stages and rendering. The
Java fixtures establish compatibility separately. Empty-driver-cache startup and
live gameplay performance are not measured. Existing approximations and unsupported
datapack constructs are preserved rather than silently replaced with vanilla
generation. Future optimization should continue measuring memory and whole-region
time, especially where a stage counter improves without a pipeline gain.

Use the retained runner definitions and hashes in the artifact to reproduce.
Whole-region runs use `scripts/native-region-benchmark.py` with matched libraries,
profiles, `--count 12 --warmups 1 --parallel 2 --readiness shader-ready`, and
`--compare` against the baseline corpus. Use fresh output directories and the
recorded deadlines. Build each comparison library with
`cargo rustc --release --locked --manifest-path native/Cargo.toml --lib --crate-type
cdylib -- -C lto=off -C opt-level=2`, preserving it before building the next.
Normal focused test commands remain listed in the README and component reports.

Once merged, the existing Fabric `./gradlew runClient` command tests the combined
implementation. No world-type change or new launch flags are required.
