# Refinement cycle 9: reject smaller fixed view caches

Cycle PR: [#422](https://github.com/ArcaneArts/Retina/pull/422).

**Reject both variants; retain no runtime or test changes.** Sparse occupancy is real, but shrinking fixed caches does not establish a useful performance improvement. The first variant also changed material-mask coverage accidentally. Separating that coupling makes the column-cache cost measurable and fails the Metal screen.

The [machine-readable record](refinement-cycle-09-view-cache-size-2026-11-04.json) preserves source/native hashes, actual commands, individual results, startup, CPU work, descriptive latency statistics, variance, RSS, output size, correctness, successful tests and the failed development test. Full profiler/capture data, source patches and native binaries stay outside Git.

## Hypothesis and controlled workload

Cycle8 observed an average of only 68 filled slots in each allocated 1,024-slot cache. Try 256 column slots, keeping the existing exact-key map for collisions. These slots belong to one immutable anchor view; no dependency identity, RNG, root order or lifetime rule is relaxed.

Fresh CPU and Metal baselines ran before editing. Base is `b2cf48bc`, using frozen native SHA-256 `775bb1317e26f7aa17459dedb91fc030093ab4354c76ea250b53a1e0df548597`. Its full native source tree matches current main. Both prototypes use locked release builds, Rust 1.93 / default features / thin LTO / codegen units 2, frozen before measurements.

All cycle9 processes use vanilla retina.dat, seed 123456789, Nether min Y 0 / height 256, native budget 16 / global Rayon 2, one completed warmup, two measured complete lit/decorated/zlib MCA regions, one caller, and shader-ready final state. The harness defaults select **regions (-1,-1) and (0,-1)**. Both sides match; these are not cycle8's region (0,0), and neither timing nor occupancy is compared across those coordinate windows.

## Two separately identified variants

1. **Compound width16**, native `e07bc4ee...`: the shared geometry constant also sizes/indexes the derived material-mask cache through `super::*`. It shrinks both caches. Five slot tests pass and generated NBT matches, but a subsequently run existing material-mask test fails its complete 32×32 coverage assertion: collisions return scalar-fallback `None`. The failed log is preserved; this is not reported as a passing candidate or a generated-NBT mismatch.
2. **Isolated column width16**, native `e8510cd9...`: preserve material masks at 32×32 with their own unchanged spatial formula, while using 16×16 column slots plus exact-key fallback. Five slot tests and all four mask tests pass, including concurrent/recursive behavior, errors, extreme coordinates, lifetime and complete-window memoization.

## Full-region screening

Each row uses four fresh ABBA processes (two matched pairs). This is cheap rejection screening, not five-pair acceptance qualification. No instrumented timings are used here.

| Variant | Backend | Throughput change | Descriptive bootstrap interval | Process CPU change |
| --- | --- | ---: | ---: | ---: |
| Compound: column + mask 16 | cpu | -6.16% | -13.74% to +2.41% | +2.21% |
| Compound: column + mask 16 | metal | +5.44% | +1.62% to +9.39% | -1.85% |
| Isolated: column 16 / mask 32 | cpu | +7.19% | -10.00% to +29.93% | +1.47% |
| Isolated: column 16 / mask 32 | metal | -21.17% | -36.11% to -2.66% | +4.17% |

CPU timings are inconclusive under changing shared-host load. The compound Metal screen looks promising in isolation but is disqualified by its mask-coverage failure and confounded by changing two caches. The isolated variant's Metal screen is slower, with no corresponding CPU-work improvement. Its median generation high-water RSS also increases from about 2,192 MiB to 2,252 MiB rather than demonstrating a memory win. These small samples justify rejecting the prototypes, not a universal claim that a particular percentage applies on every machine.

Because neither candidate passes screening, neither proceeds to five-pair/broad-dimension acceptance or ships. No failed repository check is bypassed. All runtime/test edits are restored to base.

## Actual same-coordinate attribution

Separate opt-in summary captures compare baseline, compound and isolated variants on the same two regions. They do not rank performance.

| Region | Baseline requested column slots | Isolated requested column slots | Baseline fallback entries | Isolated fallback entries |
| --- | ---: | ---: | ---: | ---: |
| (-1,-1) | 417,251,328B | 104,312,832B | 1,294 | 284,214 |
| (0,-1) | 371,859,456B | 92,964,864B | 1,281 | 135,520 |

The isolated candidate requests **75% fewer fixed-slot payload bytes**, but moves hundreds of thousands of cache entries into the fallback map. Unique cached-column totals and successful reconstructed-prefix outcomes are identical to baseline. Material-mask payload remains exactly unchanged at 50,577,408 B and 13,189,120 B respectively. This is less fixed allocation, not a demonstrated reduction in logical reconstruction work or peak memory.

The compound variant additionally quarters mask capacity and increases scalar fallback. Its allocated column-view count rises by 850 and 222 at the two origins. This explains why the mask and column geometries must be treated separately when experimenting; a single shared constant was not an isolated column-cache experiment.

## Validation and disposition

21 actual native processes completed 63 fresh regions including 21 warmups. Measured outputs contain 43,008 generated records and 30,720 strict same-backend decompressed NBT comparisons. File output size remains identical within each screen. CPU and real Metal executed; no Windows/Vulkan/DX12 performance claim is made.

Existing 32×32 caches remain in production. A future lazy-page experiment could preserve direct coverage while avoiding sparse initialization, but the older unmerged prototype is not validated and its extra indirection/allocation must be measured. Prefix identity also remains an investigation lead rather than permission to skip dependency evaluation.

This cycle records an evidence-backed rejection within O2. It does not substitute for the remaining overlap, encoding/compression or actual Java/Bukkit work. Windows transport qualification remains pending. No release or distribution JAR was built.
