# Refinement implementation — 2026-11-04

Active goal: implement and benchmark the nine areas in [the inspection report](refinement-opportunities-2026-11-04.md), retaining demonstrated improvements and recording rejected experiments. Each cycle uses an isolated worktree and squash auto-merge PR. The inspection timings are context; fresh paired measurements govern retention.

Starting main is `8e37896e219e9ef49eed7fc2509c3c39fa1c0602`. A frozen copy of its native source was built with Rust 1.93.0, `--release --locked --lib`, default features, codegen-units 2, thin LTO, and no RUSTFLAGS. Runtime trials use saved definitions through the production reader, global Rayon 2, explicit backend and worker budget, zlib, and completed lit-MCA output. Source patches, native hashes, individual measurements, host-load snapshots, logs and full MCA corpora remain under `~/.cache/retina/refinement-implementation-20261104/`.

| Area | Disposition |
| --- | --- |
| O1 CPU registry execution | Pending profiling and implementation |
| O2 Nether dependency/planning | Pending attribution and implementation |
| O3 CPU lighting | Cycle 2: row-staging prototype rejected as inconclusive; runtime code discarded. Scratch lifetime and tile size remain separate pending experiments. |
| O4 Windows Vulkan transport/palettes | Pending real Windows validation; no Windows GPU runner is configured for this repository; defaults unchanged |
| O5 Bounded overlap | Pending matched concurrency and fairness experiments |
| O6 Encoding/compression | Pending allocation and end-to-end decode measurements |
| O7 Definition loading/binding | Pending phase attribution and ownership experiments |
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

Twenty secondary processes cover Nether, central and outer End, a second seed, custom data, expanded storage, far/negative coordinates, Metal Overworld/End and the actual 768-height datapack. Candidate strict NBT comparisons and fresh-process preservation checks pass. A first tall-world pair looked slower (70.98 → 91.28s); the same-manifest ABBA control reverses the result (baseline 102.43/126.84s versus candidate 63.83/74.79s). This reinforces the host-variability limitation; it is not a claimed 65% tall-world speedup. Metal ABBA is nominally +4.01%, within a broad interval containing no change.

Five CPU lighting unit tests pass, including 24 scalar/full-scan/reference fixtures in both output formats and invalid-row/material handling. The actual Metal integer-light comparison passes all six cases exactly. No terrain or lighting difference was found.

A final extension applied the same scoped macOS activity token to each actual benchmark owner. It completed one baseline and one candidate process (38.83 versus 30.17 chunks/s), then was stopped during its third process because competing game, compiler, VM and desktop work made it uninformative. Completed results and the incomplete directory are retained. No unrelated process was stopped, no machine setting was changed, and no claim is made that the token normalizes scheduling.

Decision: reject the implementation under the goal's rule for inconclusive end-to-end gains. Reduced lighting-stage time is promising, but does not justify claiming a retained whole-region performance improvement. Scratch reuse and tile sizes remain independent experiments; row staging can be revisited with new, controlled evidence. This cycle publishes only the experiment record.
