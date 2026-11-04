# Cycle 6 — CPU lighting scratch reuse experiment

PR: [#419](https://github.com/ArcaneArts/Retina/pull/419). Branch: `codex/refinement-cpu-light-scratch`. Based on main `50c375c1f746c5a504cfdbd5bc873789dac94070`. This evaluates bounded scratch reuse independently of row staging and tile sizes. The previous row prototype remains rejected; core size remains 5 and propagation/source ordering remain unchanged.

The prototype gives one CPU region call its own material, light, sunlight-frontier, queued-bit and queue buffers, reused across nonconstant tiles and dropped with the call. It retains no world/global/thread-local cache, changes no worker budgets, and leaves each output tile independently owned. Material padding slabs are reset; every interior material and light cell is overwritten; frontier and queue state are reset. Error recovery and changing volume/profile shapes are exercised against fresh output. Output nibble packing still starts from zeroed bytes.

## Production-native screen

An APFS clone seeds a private host build target. A fresh locked release baseline builds in 79s and the candidate in 66s using Rust 1.93.0 and the same default features, thin LTO and codegen settings. No benchmark overlaps our builds. The normal primary build target and Windows handoff remain untouched.

| Identity | SHA-256 |
| --- | --- |
| Current-main baseline | `3f97234861143bb9062acb431d4a25b24ff19fc6e7dadbb53436cb60681619c5` |
| Scratch candidate v1 | `52d5e6b6ccd9d15a751a539445dabcc74753142a00fd77b1b19393242ebcf387` |

The production saved vanilla definition is seed 123456789, SHA-256 `98e8cf1a2e3e5e049192b0b5245b58aa44a8001f3b00a6ddeb51a619ad375b4e`. Four fresh processes run ABBA, one untimed warmup and two measured lit/decorated/zlib MCA regions at (0,0)/(1,0), one caller, total native budget 16 (4 dependency/assembly + 12 terrain), global Rayon 2. The same existing finite activity wrapper applies to each actual native owner and reports active/ended; it does not normalize scheduling or alter system power settings. Host observations bracket each trial.

| Trial | Chunks/s | Process CPU ms/region | Lighting host ms/region | Generation high-water MiB |
| --- | ---: | ---: | ---: | ---: |
| Baseline 0 | 198.05 | 33,178.1 | 892.6 | 1,256.9 |
| Candidate 1 | 165.84 | 35,327.1 | 878.7 | 1,253.8 |
| Candidate 2 | 69.28 | 50,332.6 | 2,321.3 | 1,241.7 |
| Baseline 3 | 66.53 | 50,998.5 | 2,093.1 | 1,268.4 |

Nominal median throughput changes **-11.13%**, with a very broad descriptive bootstrap interval **-65.02% to +149.27%**. Median process CPU is about 1.76% higher. These do not establish either a retained improvement or an intrinsic regression: host load rose from 11.19 to 26.98 while other application/game/VM work changed substantially. No unfavorable observation is excluded, no unrelated process is stopped, and power mode remains 0 with AC attached.

The candidate's existing lighting-host timer excludes final region-owned scratch destruction, whereas the baseline frees scratch within each helper. Complete MCA elapsed/CPU measurements include it on both sides. That timer is therefore not used to manufacture a phase-speed gain. A retained implementation would need consistent final-release attribution before qualification.

All actual backends are `CPU: native`. Four processes generate 12 fresh regions including warmups; **6,144 strict decompressed-NBT chunk comparisons pass** across the matched two-region corpus. This is a screen, not five-pair retention qualification. No broader backend/dimension acceptance is claimed for an unretained prototype.

## Component attribution

A separate test-only helper experiment times material initialization, staging, direct-sky initialization, queue initialization, propagation and output packing. It uses a synthetic 7x7-chunk, 384-height input with core 5 and one-chunk halo, five warmups and 20 measured samples per setting in interleaved order. Both fresh and reused buffers must match the independent full-scan reference. Capacity-growth observations distinguish actual buffer reuse from a timing claim; they are not a global allocator-call count.

This experiment is separate from complete-region timing, uses the candidate's fresh/reused helper rather than the frozen old baseline, and excludes output/scratch destruction from helper spans. Raw per-phase observations and their interpretation are in the machine-readable cycle record. It cannot establish live Java delivery or another backend's throughput.

| Synthetic helper median | Fresh scratch | Reused scratch |
| --- | ---: | ---: |
| Material initialization | 0.104ms | 0.008ms |
| Material staging | 9.968ms | 9.735ms |
| Light initialization/direct sky | 24.887ms | 24.590ms |
| Queue initialization | 0.122ms | 0.047ms |
| Frontier seeding/propagation | 12.009ms | 12.919ms |
| Output packing | 8.256ms | 7.847ms |
| Whole helper | 55.322ms | 54.982ms |

The 20 measured reused calls need zero scratch capacity growths, versus 100 across the fresh calls. That confirms reuse, but whole-helper time changes only about 0.6%, treated as inconclusive. Allocation/reset spans are small in this fixture. Independently computed phase medians need not sum to the whole-helper median. The synthetic reader uses a per-read compiler barrier, which can affect staging optimization; these are not production per-stage percentages. All 40 measured outputs and 10 warmup outputs match the full-scan reference.

## Reproduction and evidence

The regular paired harness is `scripts/native-region-benchmark-ab.py`. The archived owned-process controller inserts the existing activity wrapper and before/after host observations. The complete invocation, source patch, frozen native hashes, individual process results, unit logs and component experiment remain under `~/.cache/retina/refinement-implementation-20261104/` with `cycle06-` prefixes. Large MCA corpora and binaries stay outside Git.

The first focused Rust run passes five tests, including 48 reused-buffer/reference fixtures in both formats, changing sizes/sky/faces and explicit error/stale-queue recovery. A formatter command initially used the wrong relative path; formatting was then rerun successfully before the production candidate build. This does not affect generation comparison evidence.

The final test-only run passes six tests including the component experiment. Its build/run takes 369s under the observed host load; actual test execution is 3.46s. The compiler was observed active while waiting and was not restarted. Temporary source and both patches are archived before restoring the runtime file exactly to main. The normal primary native remains SHA-256 `76437e0d177cb691fc91cc922f955af9f3acd0ef2ce3016a164add9530a01a94`.

Decision: retain only demonstrated wins. The complete-region screen is inconclusive and does not justify shipping scratch reuse. Runtime code and temporary attribution scaffolding are discarded; this cycle records the experiment. Tile-size experiments remain independent pending work. No backend default, normal JAR or release changes are made.
