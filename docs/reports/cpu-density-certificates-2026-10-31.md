# CPU density certificates: bounded diagnostic comparison

Date: 2026-10-31. Apple M4 Max, macOS ARM64, optimized Cargo release with
the default scalable allocator. Both runs explicitly selected native CPU.
No client, graphics instance or software graphics adapter was used.

One lit MCA Nether region, seed 123456789, region (0,0), vanilla-binary 26.3
catalog materialized through the Java headless interface. Definition SHA-256:
`98e8cf1a2e3e5e049192b0b5245b58aa44a8001f3b00a6ddeb51a619ad375b4e`.
The benchmark used one caller, no warmups, and opt-in diagnostics. Another agent's
benchmarks and compilation overlapped the candidate run. These are diagnostic
observations, not uncontended speed claims or production percentile estimates.

| Measurement | Before | Density certificates |
| --- | ---: | ---: |
| Region call, seconds | 132.055 | 119.320 |
| Process CPU seconds, measured batch | 211.032 | 178.629 |
| Average scheduled cores | 1.598 | 1.497 |
| Summed mask/material-call seconds | 203.602 | 183.675 |
| Peak RSS, GiB | 1.481 | 1.553 |
| Saved chunks | 1,024 | 1,024 |
| Dependency terrain chunks generated | 10,851 | 10,851 |

The candidate certified 69,168,560 air and 178,667,760 solid density decisions;
54,051,424 decisions retained scalar evaluation. All 1,024 decompressed chunk NBT
records matched. Corpus SHA-256:
`2d98866a9abf049e77c4bdd7a438dc93c0493bd40c5456392301a844b8dba225`.

Summed call durations overlap and are not elapsed-time shares. The harness's
legacy `gpu_ms` report member is the host terrain phase even in CPU mode;
neither run has GPU timestamp measurements or GPU jobs. The measured CPU work
still warrants further optimization. A three-second native process sample finds
substantial eager program evaluation/noise work in material rules, alongside
cavity evaluation. Avoiding unselected conditional branches is the next separate
optimization to evaluate.

Validation: 53 CPU-related unit checks; exhaustive interval operation and sampled
cell containment checks; 163,840 exact Metal material/cavity/biome comparisons;
complete-region NBT equality; tests compile after rebasing over PRs #387 and #388.
Compact measurements, logs, native sample and retained comparison libraries live
under the goal's `435af8f5-435b-4b07-88a3-ea8d36758d6e` evidence directory.
