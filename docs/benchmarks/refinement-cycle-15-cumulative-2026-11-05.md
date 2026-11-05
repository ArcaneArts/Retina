# Refinement cycle 15: cumulative local qualification

Fresh comparisons use the **starting goal native versus the current merged runtime**, rather than multiplying earlier percentages. They reproduce tall-definition CPU/memory savings and the scoped no-skylight CPU lighting improvement. Whole-region throughput remains inconclusive. Actual Windows Vulkan transport/palette qualification remains pending; the full nine-area goal is **not complete**.

The [machine-readable record](refinement-cycle-15-cumulative-2026-11-05.json) retains all individual commands, metrics, identities, source/fixture hashes, workload boundaries, load snapshots, actual-output checks and the requirement audit. Cycle PR: [#428](https://github.com/ArcaneArts/Retina/pull/428). This cycle changes documentation only. Branch: `codex/refinement-cumulative-validation`, base `4c1231d4`.

## Matched starting-versus-current comparison

Starting main is `8e37896e219e9ef49eed7fc2509c3c39fa1c0602`, frozen native SHA-256 `ccd7084444c80737a5d582cf710773ffcfa6383fb58f72558e9cc1bfa44a1c0a`. Current native is `648240065f37e3cd135ec4df4cd1f0eabbe10bc73eb081fac8c28a4d42e5f6f7`, runtime-source equivalent to merged PR #427. Both use Rust 1.93.0/LLVM 21.1.8, locked release/default features, ThinLTO/two codegen units and no RUSTFLAGS. Native binaries are frozen outside mutable target directories.

The short series uses five adjacent AB/BA pairs per CPU/Metal Overworld/Nether case and the tall-definition loader. Each generation process completes warmup (8,8), awaits final shader readiness and measures one lit/decorated/NBT/zlib/atomically published region (0,0). Native budget 16/global Rayon 2 and one caller are unchanged. The production saved-definition loader uses the pinned vanilla seed 123456789; the tall loader uses the actual 768-height datapack.

| Comparison (native units: chunks/s or ms) | Baseline median | Current median | Median paired change | Descriptive paired bootstrap interval |
| --- | ---: | ---: | ---: | --- |
| definition-tall / definition_open_cpu_ms | 950.42 | 774.33 | -18.53% | -27.58% to -8.93% |
| cpu-nether / lighting_host_ms_per_region | 619.11 | 489.94 | -22.25% | -33.57% to -20.86% |
| cpu-overworld / chunks_per_second | 241.28 | 235.24 | -2.37% | -4.61% to -0.72% |
| metal-overworld / chunks_per_second | 4154.79 | 4051.68 | +0.30% | -4.20% to +9.06% |
| cpu-nether / chunks_per_second | 87.25 | 88.54 | +4.04% | -0.52% to +13.49% |
| metal-nether / chunks_per_second | 1351.86 | 1331.91 | -1.06% | -9.85% to -0.01% |

Tall open high-water RSS falls 4.041 → 2.344 GiB, median paired change **-41.99%**. This is definition-open lifetime high-water RSS, not steady-state generation memory. All ten canonical metadata hashes match. The loader retains eager preparation; the measured saving is not moved into first generation. Python FFI-copy/JSON phases are distinct from Java decoding.

The no-skylight value is actual production CPU lighting-host elapsed, excluding synchronous encoding callbacks and unscaled by worker-share attribution. It is not process CPU time, whole-MCA speed or GPU utilization. Java/Bukkit’s separately qualified 13,160 fewer allocated bytes/chunk (−5.0803%, PR #424) is a different contribution; native trials do not establish its live latency/FPS/TPS effect.

## Longer confirmation and negative controls

The small short CPU Overworld loss prompted five longer matched pairs, now measuring four identical regions (0,0)/(1,0)/(0,1)/(1,1) after one warmup. The same extension includes Metal Nether. Every result, including the unfavorable first CPU pair and Metal observations, is retained.

| Four-region confirmation | Baseline median chunks/s | Current median chunks/s | Median paired change | Descriptive paired bootstrap interval |
| --- | ---: | ---: | ---: | --- |
| cpu-overworld / chunks_per_second | 216.00 | 216.02 | +0.01% | -20.27% to +7.78% |
| metal-nether / chunks_per_second | 1496.94 | 1367.68 | -11.16% | -12.64% to +9.44% |

CPU Overworld’s short small slowdown is not reproduced as a consistent longer-batch result. This does not prove zero overhead or establish a CPU throughput win. Metal Nether’s five paired changes are −11.71%, +9.44%, −11.16%, +2.87%, −12.64%: whichever binary runs first is faster in this series. The negative median is recorded as unresolved, not hidden or assigned to code without evidence.

Four additional identical-binary first/second pairs repeat the preceding CPU batch: two pairs use the original native, two use current. Both positions use the same file/hash.

| Identical-binary control | First chunks/s | Second chunks/s | Second versus first |
| --- | ---: | ---: | ---: |
| Pair 0, ccd70844 | 1564.5 | 1549.7 | -0.95% |
| Pair 1, 64824006 | 1560.1 | 1556.8 | -0.21% |
| Pair 2, 64824006 | 1521.3 | 1483.8 | -2.47% |
| Pair 3, ccd70844 | 1302.5 | 1297.2 | -0.41% |

These same-library controls do **not** reproduce the approximately 10% within-pair gap. Original and current natives both reach roughly 1,550 chunks/s in nearby controls, while later original-native results also fall near 1,300. Changing host windows remain a material confound; these controls neither prove a particular OS cause nor qualify a stable code regression. There is no speculative runtime tweak or default change to chase these noisy observations.

## Completed CPU foreground/caller coverage

The earlier CPU fairness sweep stopped under extreme host load. This cycle completes all six forward/reverse cases at callers 1/2/4/4/2/1, each with four measured MCAs and the same fixed native/global budgets. The existing bounded stress thread offers one eight-point production biome query at a time with 10ms think time. Its 16,384-point reference exceeds the query cache; every actual answer is verified.

| Callers / trial | MCA chunks/s | Native-call p95 ms | RSS MiB | Concurrent query p95 ms | Query maximum ms | Concurrent calls |
| --- | ---: | ---: | ---: | ---: | ---: | ---: |
| 1 / cpu-foreground-0-p1 | 203.4 | 5837.0 | 1354.3 | 10.38 | 2201.48 | 542 |
| 2 / cpu-foreground-1-p2 | 204.1 | 10728.7 | 1608.0 | 13.33 | 5529.77 | 353 |
| 4 / cpu-foreground-2-p4 | 250.3 | 16344.4 | 2317.0 | 9.70 | 8339.72 | 208 |
| 4 / cpu-foreground-3-p4 | 192.8 | 21075.4 | 2099.0 | 10.82 | 10875.45 | 262 |
| 2 / cpu-foreground-4-p2 | 204.1 | 11843.9 | 1661.8 | 12.91 | 4579.25 | 388 |
| 1 / cpu-foreground-5-p1 | 185.1 | 6049.1 | 1348.9 | 11.59 | 2447.17 | 562 |

All native generation/query outputs match their references. Long-tail query waits remain visible despite low medians/p95; these observations do not establish a source-level cause or a safe default budget ranking. Foreground process CPU includes query work, native MCA wall ends at generation completion, admitted queries drain before profile ownership closes, and high-water RSS includes reference setup/startup. This is native stress coverage, not Java/FPS/TPS, Chunky or Distant Horizons gameplay. Existing concurrency/depth/defaults remain unchanged.

## Evidence and goal audit

Across the direct cumulative comparison and longer confirmation, 60 generation processes complete 180 fresh MCAs including warmups, with 110,592 strict same-backend NBT chunk comparisons. Ten separate tall-definition processes complete. The 18 added order/fairness processes complete 90 fresh MCAs and 73,728 further strict NBT checks. No CPU/GPU cross-backend bit equality is assumed. No completed trial is dropped.

Code retention remains based on each focused cycle’s qualification and correctness scope: loader CPU/memory (PR #416), scoped CPU no-skylight lighting (PR #427), Java allocation (PR #424), and bounded opt-in diagnostics/tooling. Losing row/scratch/tile/kernel/cache/encoding/refill experiments are discarded. The updated [implementation ledger](refinement-implementation-2026-11-04.md) links every cycle and merge; JSON includes a 31-item requirement audit with explicit limitations.

Actual Windows Vulkan all-eight transport/palette combinations, custom/tall data and qualification remain pending. The portable `build/release/retina-windows-transport-test.zip` is ready: extract, run `Run-tests.cmd`, choose Vulkan screening and return `SEND-THIS-results.zip`, including failures. Local Metal, a successful cross-build or failed MoltenVK startup cannot close that requirement. No backend defaults, JAR release or public release change in this cycle.
