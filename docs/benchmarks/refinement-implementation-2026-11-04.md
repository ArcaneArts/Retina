# Refinement implementation — 2026-11-04

Active goal: implement and benchmark the nine areas in [the inspection report](refinement-opportunities-2026-11-04.md), retaining demonstrated improvements and recording rejected experiments. Each cycle uses an isolated worktree and squash auto-merge PR. The inspection timings are context; fresh paired measurements govern retention.

Starting main is `8e37896e219e9ef49eed7fc2509c3c39fa1c0602`. A frozen copy of its native source was built with Rust 1.93.0, `--release --locked --lib`, default features, codegen-units 2, thin LTO, and no RUSTFLAGS. Runtime trials use saved definitions through the production reader, global Rayon 2, explicit backend and worker budget, zlib, and completed lit-MCA output. Source patches, native hashes, individual measurements, host-load snapshots, logs and full MCA corpora remain under `~/.cache/retina/refinement-implementation-20261104/`.

| Area | Disposition |
| --- | --- |
| O1 CPU registry execution | Cycle 4: three prepared arithmetic block prototypes rejected after actual profiling/screens; runtime code discarded |
| O2 Nether dependency/planning | Pending attribution and implementation |
| O3 CPU lighting | Cycles 2/6: row staging and region-lifetime scratch prototypes rejected as inconclusive; runtime code discarded. Tile sizes remain a separate pending experiment. |
| O4 Windows Vulkan transport/palettes | Cycle 5 prepares a portable Windows matrix bundle; real Windows qualification remains pending, defaults unchanged |
| O5 Bounded overlap | Pending matched concurrency and fairness experiments |
| O6 Encoding/compression | Pending allocation and end-to-end decode measurements |
| O7 Definition loading/binding | Cycle 3: merged loader memory/CPU improvement in [PR #416](https://github.com/ArcaneArts/Retina/pull/416), commit `3d7c83b2` |
| O8 Interval diagnostics | Cycle 1: merged in [PR #414](https://github.com/ArcaneArts/Retina/pull/414), commit `c62bff79` |
| O9 Java/Bukkit delivery | Pending actual decode/JFR profiling |

## Cycle 1 — opt-in submission windows

Branch: `codex/refinement-gpu-intervals`. PR: [#414](https://github.com/ArcaneArts/Retina/pull/414).

The existing snapshot ABI remains unchanged. New bounded capture records identify terrain, material-emission and lighting submissions on the shared device. Capture begins before warmup; a measured window then separates fully contained successful submissions, incoming completed work, outgoing pending work, and aborted observations. Dropped records, missing/invalid timestamps, interval peaks and saturated duration sums are explicit. Pending work is not awaited to manufacture a complete trace.

GPU timestamp ranges use their own clock. Their union avoids overlap double-counting among recorded contained stages, but cannot establish host GPU-idle percentages or account for copies, compilation and the untracked ore/feature/vine paths. Stage ranges may include intervening GPU work. Work submitted before capture was enabled is outside capture coverage. Submission sequence IDs are capture-local; window identities are recorder-local and remain distinct across disable/re-enable.

The normal path only checks a disabled atomic flag: it acquires no capture lock and allocates no records. Detailed capture is opt-in and capped at 4,096 submission records. It does not change generation admission or backend selection.

C ABI:

- `retina_gpu_interval_capture(enabled)` enables before warmup or disables capture.
- `retina_gpu_interval_begin(output_id)` opens one host observation window.
- `retina_gpu_interval_end(id, output_bytes, output_length)` closes it without draining pending GPU work. Free the JSON with `retina_free_bytes`.

The benchmark harness exposes `--gpu-interval`, and the paired harness accepts `--baseline-gpu-interval` / `--candidate-gpu-interval`. Older natives without the API still generate and report diagnostic unavailability rather than failing a capability precheck.

Validation covers warmup boundaries, overlapping stages, unavailable/reversed timestamps, pending work, errors/abandonment, stale handles, bounded capture, concurrent submitters and large-duration saturation. Real-native integration exercises CPU, Metal Overworld terrain/material/lighting, and CPU End substrate with Metal lighting. Final paired benchmark results are recorded below.

### Cycle 1 results

Each comparison uses five baseline and five candidate processes in ABBA order, one warmup, the same saved seed/coordinates/readiness/budget, zlib and complete lit-MCA output. Native hashes, all per-trial metrics and the real parallel capture are retained in [the cycle evidence](refinement-cycle-01-intervals-2026-11-04.json).

| Comparison | Baseline median chunks/s | Candidate median chunks/s | Nominal change | Bootstrap interval for change |
| --- | ---: | ---: | ---: | --- |
| final-metal-default-off | 4215.7 | 4031.8 | -4.36% | -7.27% to +7.51% |
| final-cpu-default-off | 231.1 | 229.4 | -0.75% | -10.48% to +13.88% |
| final-metal-capture-overhead | 3601.6 | 3624.8 | +0.64% | -10.32% to +19.88% |
| final-metal-long-default-off | 3143.7 | 3108.0 | -1.14% | -20.45% to +18.60% |

These intervals all include no change. The short default-off Metal case is nominally slower; the longer qualification case also remains inconclusive. There is no generation-speed claim or claim of exact zero overhead. Retention is for correct, bounded, opt-in measurement, with no allocation or capture lock on the disabled path. Shared-host variability limits small overhead conclusions.

Validation passed: six Rust interval unit tests; real CPU, Metal Overworld and Metal End integration; 41 final native processes / 154 measured fresh regions; 142,336 strict same-backend decompressed NBT comparisons; and a four-region parallel capture with a fresh-process preservation check retaining all 1,024 original records. CPU/GPU cross-backend bit equality is not required. The parallel window contains 60 observed submissions with peak 4, no dropped records, no aborted observations, and no outgoing pending work. Timestamp omissions/invalid pairs remain explicit.

A real three-region window illustrates the distinction: legacy deltas report roughly 2.05s of summed spans and 1.19s of gaps during an approximately 0.85s host batch, while contained stage ranges union to approximately 0.36s. The new data also includes lighting submissions absent from the legacy terrain-job count. None of these ranges is labeled an idle percentage.

No source optimization, backend-default change, JAR build or release is part of this cycle. The pre-review measurements without `final-` in their names are exploratory and excluded after correcting abandoned-versus-completed attribution. The full nine-area goal remains active.

## Cycle 2 — CPU lighting row staging (rejected)

Branch: `codex/refinement-cpu-light-rows`. PR: [#415](https://github.com/ArcaneArts/Retina/pull/415). Based on merged cycle 1. The prototype loaded immutable Y-layer views once, validated the maximum material index, copied contiguous X runs or filled uniform rows, and left proven zero rows in fresh zeroed scratch. Propagation, tile size and publication were unchanged. The runtime patch and native binaries are retained outside Git for reproduction; **no runtime change is merged**.

| Qualification | Baseline median chunks/s | Candidate median chunks/s | Nominal change | 95% bootstrap interval |
| --- | ---: | ---: | ---: | --- |
| Initial primary, five processes each | 260.64 | 287.83 | +10.43% | -0.59% to +17.84% |
| Same-manifest rebuild, five processes each | 148.74 | 145.06 | -2.47% | -46.11% to +853.74% |

The initial primary lighting host time falls 824 → 560ms/region, and median process work falls 24.47 → 23.48 CPU-seconds/region. In the second series lighting falls 1,125 → 812ms, but process work only falls 33.97 → 33.58 CPU-seconds. All individual results remain in [the cycle evidence](refinement-cycle-02-cpu-light-rows-2026-11-04.json), including the second series' severely delayed first baseline batch. Neither interval proves an overall generation-speed gain.

Twenty secondary processes cover Nether, central and outer End, a second seed, custom data, expanded storage, far/negative coordinates, Metal Overworld/End and the actual 768-height datapack. Candidate strict NBT comparisons and same-process definition-close/reopen preservation checks pass. A first tall-world pair looked slower (70.98 → 91.28s); the same-manifest ABBA control reverses the result (baseline 102.43/126.84s versus candidate 63.83/74.79s). This reinforces the host-variability limitation; it is not a claimed 65% tall-world speedup. Metal ABBA is nominally +4.01%, within a broad interval containing no change.

Five CPU lighting unit tests pass, including 24 scalar/full-scan/reference fixtures in both output formats and invalid-row/material handling. The actual Metal integer-light comparison passes all six cases exactly. No terrain or lighting difference was found.

A final extension applied the same scoped macOS activity token to each actual benchmark owner. It completed one baseline and one candidate process (38.83 versus 30.17 chunks/s), then was stopped during its third process because competing game, compiler, VM and desktop work made it uninformative. Completed results and the incomplete directory are retained. No unrelated process was stopped, no machine setting was changed, and no claim is made that the token normalizes scheduling.

Decision: reject the implementation under the goal's rule for inconclusive end-to-end gains. Reduced lighting-stage time is promising, but does not justify claiming a retained whole-region performance improvement. Scratch reuse and tile sizes remain independent experiments; row staging can be revisited with new, controlled evidence. This cycle publishes only the experiment record.

## Cycle 3 — borrow the saved structural profile (validated)

Branch: `codex/refinement-definition-borrow`. PR: [#416](https://github.com/ArcaneArts/Retina/pull/416). Based on `985c0e02` (cycle 2 changes only documentation). The loader keeps its authoritative wire document while converting each supported profile, so cloning the complete NBT subtree before conversion is unnecessary. The prototype builds the owned JSON conversion directly from an immutable borrow; validation, initialization, profile identities, metadata, binding and generation remain eager and unchanged. The consuming converter remains an independent reference and serves existing diagnostic callers.

Fresh processes use the same saved inputs, Rust 1.93.0 release settings and scoped process context in ABBA order, five baseline and five candidate processes for each input. The production C API is measured at separate initialize/open/metadata/copy/host-decode/binding/close boundaries. No GPU or terrain preparation is moved into the first request. OS caches are retained, so this is not a physical cold-disk measurement.

| Definition open | Baseline | Candidate | Reduction |
| --- | ---: | ---: | ---: |
| Tall 768-height datapack, process CPU | 1,366.9ms | 1,109.7ms | 18.82% |
| Tall datapack, elapsed | 1,377.4ms | 1,118.0ms | 18.83% |
| Tall datapack, process high-water RSS at open | 4.034 GiB | 2.345 GiB | 41.87% / 1.689 GiB |
| Vanilla, process CPU | 1,145.7ms | 1,091.5ms | 4.73% (small timing difference) |
| Vanilla, process high-water RSS at open | 410.14 MiB | 397.11 MiB | 3.18% |

The tall-input effect is clear across all five pairs. Vanilla timing is not treated as a robust speed claim. These are definition-open observations, not steady-state generation RSS or Minecraft world-startup timings. Metadata hashes are identical across all 20 processes. Large logs and process snapshots remain outside Git.

Thirty focused Rust tests pass. Standalone tests confirm definition I/O never starts the GPU and all execution-mode bindings preserve the saved file and retire runtime profiles. The complete Java fixture suite passes seven cases including vanilla, custom, flat, legacy, procedural and cropped ranges plus embedded typed NBT fidelity. The first attempt used an older incomplete fixture set: all seven generation cases passed, then the absent embedded codec case failed. That failed log is retained; the successful rerun used an owned copy of the complete fixture collection without weakening the test.

Internal attribution used a separate temporary instrumented baseline, followed by exact restoration of the candidate sources. For the tall file it observes gzip 44ms, NBT parse 524ms, raw NBT clone 292ms, structural conversion 206ms, typed conversion 39ms and semantic validation 3ms. This single process is attribution, not another headline speed trial. Fingerprinting and shared/dimension checks are outside those named spans. Vanilla immutable preparation dominates its much smaller clone cost, so that remains a future profiling lead. CPU VM preparation and GPU compilation were already execution work and have not been moved by this patch.

Eleven complete-region guards cover CPU normal Overworld, Nether, central/outer End, second seed, custom data, expanded storage, far coordinates, Metal Overworld/End and the actual tall pack: **11,264 strict same-backend decompressed NBT comparisons pass**. Each also preserves all 1,024 records after definition close/reopen in the same process. An additional fresh native process opens the saved normal region, generates zero replacements, preserves all 1,024 records, and verifies exact NBT and membership. Input definitions remain authoritative and unchanged.

Decision: retain for the demonstrated tall-input startup CPU and memory reduction, with no deferred preparation and exact profile/generation equivalence. This is not a claim of faster steady-state generation or complete Minecraft startup. There is no JAR build, release or Windows performance claim. The cycle also corrects cycle 2's preservation wording: its standard helper reopens in the same process, rather than starting a fresh one.

## Cycle 4 — prepared CPU arithmetic blocks (rejected)

Branch: `codex/refinement-cpu-kernels`. PR: [#417](https://github.com/ArcaneArts/Retina/pull/417). Based on merged cycle 3. Fresh native baseline SHA-256 `76437e0d177cb691fc91cc922f955af9f3acd0ef2ce3016a164add9530a01a94`; Rust 1.93.0, locked release, default features, thin LTO, codegen units 2. [Machine-readable evidence](refinement-cycle-04-cpu-kernels-2026-11-04.json) includes all per-process results and hashes; source variants, full traces, binaries and MCA remain outside Git.

A fresh six-region CPU workload completed under a separate 20-second OS stack sample with no per-node counters. The evaluator has 28,228 top-of-stack observations versus 12,024 in primitive noise. Parked threads are separate; these are observations, not CPU percentages. Inlined arithmetic is part of the evaluator symbol, so its whole count is not attributed to scheduling overhead.

The prototypes prepared private arithmetic/coordinate/gradient subgraphs while keeping lazy branches, spline references, shared/returned values (including implicit node zero), interpolation and cached nodes as boundaries. They retained original scalar operations. Three concrete variants were built and screened:

| Variant | Baseline median chunks/s | Candidate median chunks/s | Nominal throughput change | CPU-time reduction | Throughput bootstrap interval |
| --- | ---: | ---: | ---: | ---: | --- |
| Private arithmetic body with ordinary global frame storage | 121.87 | 123.88 | +1.65% | +1.37% | -8.40% to +13.96% |
| Pre-bound operands and local scratch | 74.92 | 87.18 | +16.37% | +1.20% | -9.14% to +58.89% |
| Local scratch with original prepared-step size | 186.53 | 181.16 | -2.88% | -0.10% | -12.85% to +8.68% |

Every screen uses four interleaved processes (ABBA), one warmup and one measured complete lit-MCA region, the same saved input/seed/coordinates, assembly budget 16 and global Rayon 2. Each actual owner gets the same scoped process activity. Shared-host scheduling/load varies materially; individual results and competing-process snapshots are retained. Small-sample p95 is descriptive. No unfavorable trial is silently removed.

The third variant addressed an introduced layout cost: a same-definition layout probe grows prepared steps from 32 to 40 bytes with a 32-bit kernel reference; a 16-bit reference fits in the original size. Excess kernel references fall back to scalar evaluation rather than rejecting generation. Restoring that size still did not establish a speed win.

A separate diagnostic pair reports work-stack visits **1,824,680,912 → 1,407,450,982 (-22.87%)**, with 252,192,696 operations handled in prepared bodies. Computed nodes, constant hits, primitive-noise calls and corner/interpolation reuse counts match exactly. Its timing is excluded: per-node instrumentation is enabled and our next variant was compiling concurrently. Counter reduction alone does not establish lower total CPU cost.

All three production release builds succeeded. Across the screens, diagnostic pair and sampled workload, 15 fresh native processes generated 33 regions including warmups, and **10,240 strict same-backend NBT chunk comparisons passed**. The code did not advance to the broader retention suite: reference unit-test drafts and all source variants are archived, but those drafts were not executed and are not claimed as passing tests. No runtime code is retained, so no Metal/Windows or custom-dimension acceptance is claimed for these prototypes.

Decision: reject the implementation. The measured benefit does not justify the added execution representation and scratch state. This rejects the investigated private arithmetic designs under these workloads; it does not prove every prepared-kernel or exact-certificate approach ineffective. Distinct entry-root scheduling remains an unmeasured lead, and the previously rejected identity-index shortcut remains rejected. Nether dependency/query work and host/GPU delivery remain separate tasks.

## Cycle 5 — Windows transport test bundle

PR: [#418](https://github.com/ArcaneArts/Retina/pull/418). Branch: `codex/refinement-windows-transport-qa`.

The [cycle report](refinement-cycle-05-windows-transport-2026-11-04.md) describes the offline factorial transport controller, portable CPython/native packaging, exact same-backend output checks and private cache policy. The stable Windows ZIP is a test artifact only. Local CPU/Metal validation verifies runner behavior; real Windows Vulkan/DX12 results remain pending. No transport default or generation change is part of this cycle.

## Cycle 6 — CPU lighting scratch reuse (rejected)

The [cycle report](refinement-cycle-06-cpu-light-scratch-2026-11-04.md) records a tested region-owned scratch prototype, four fresh production-native ABBA processes, 6,144 exact NBT comparisons and a separate 40-sample component experiment. Scratch capacity growths disappear in the synthetic reused case, but its whole-helper timing changes only about 0.6%; complete-region results are inconclusive under substantial host-load changes. All runtime and temporary benchmark code is discarded. This cycle publishes evidence only; tile sizes and the other pending areas remain active work.
