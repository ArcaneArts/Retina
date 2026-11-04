# Refinement implementation — 2026-11-04

Active goal: implement and benchmark the nine areas in [the inspection report](refinement-opportunities-2026-11-04.md), retaining demonstrated improvements and recording rejected experiments. Each cycle uses an isolated worktree and squash auto-merge PR. The inspection timings are context; fresh paired measurements govern retention.

Starting main is `8e37896e219e9ef49eed7fc2509c3c39fa1c0602`. A frozen copy of its native source was built with Rust 1.93.0, `--release --locked --lib`, default features, codegen-units 2, thin LTO, and no RUSTFLAGS. Runtime trials use saved definitions through the production reader, global Rayon 2, explicit backend and worker budget, zlib, and completed lit-MCA output. Source patches, native hashes, individual measurements, host-load snapshots, logs and full MCA corpora remain under `~/.cache/retina/refinement-implementation-20261104/`.

| Area | Disposition |
| --- | --- |
| O1 CPU registry execution | Pending profiling and implementation |
| O2 Nether dependency/planning | Pending attribution and implementation |
| O3 CPU lighting | Next bounded implementation experiments: bulk row staging, scratch lifetime and tile size separately |
| O4 Windows Vulkan transport/palettes | Pending real Windows validation; no Windows GPU runner is configured for this repository; defaults unchanged |
| O5 Bounded overlap | Pending matched concurrency and fairness experiments |
| O6 Encoding/compression | Pending allocation and end-to-end decode measurements |
| O7 Definition loading/binding | Pending phase attribution and ownership experiments |
| O8 Interval diagnostics | Cycle 1: validated in [PR #414](https://github.com/ArcaneArts/Retina/pull/414); merge state is authoritative on GitHub |
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
