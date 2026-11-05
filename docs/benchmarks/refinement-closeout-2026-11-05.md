# Refinement pass closeout — 2026-11-05

The local refinement pass is finalized. The user explicitly deferred Windows testing on 2026-11-05: “we will focus on windows tests later, finish up your work.” Actual Windows Vulkan/DX12 qualification remains **deferred and unvalidated**; it no longer blocks this pass. Backend defaults are unchanged.

This cycle updates the disposition and delivery records only. Runtime sources still match the qualified native build. No new timing series, generation change or release is introduced. Prior experiment snapshots remain historical evidence; the current [implementation ledger](refinement-implementation-2026-11-04.md) and [machine-readable closeout](refinement-closeout-2026-11-05.json) record the revised scope.

## Retained improvements

| Component and measured boundary | Measured result | Limits |
| --- | --- | --- |
| Tall-definition open | 18.53% less process CPU; 41.99% lower high-water RSS | Definition open; no whole-Minecraft-startup or terrain-memory claim |
| CPU Nether lighting | 22.25% less production lighting-host elapsed time | Lighting pass; overall MCA throughput, CPU and RSS gains remain inconclusive |
| Actual Bukkit delivery | 13,160 fewer allocated Java bytes/chunk, 5.0803% | Allocated bytes, not object count; no latency/FPS/TPS speedup claim |
| GPU interval and Nether diagnostics | Correct bounded, opt-in attribution | Measurement correctness; no invented utilization or generation-speed claim |

The [cumulative measurements](refinement-cycle-15-cumulative-2026-11-05.md) compare the original starting native with the current runtime. They include all completed unfavorable results, longer confirmation, identical-binary controls and CPU foreground tests at one/two/four callers. Overall chunks/sec remains inconclusive; the order-dependent Metal Nether observations are unresolved.

## All nine dispositions

1. CPU registry execution: three prepared arithmetic/kernel variants rejected after profiling and screening.
2. Nether planning: bounded attribution retained; both smaller-cache variants rejected.
3. CPU lighting: no-skylight emission initialization retained; row staging, scratch, tile-width and sky-row alternatives rejected.
4. Windows transport/palettes: portable matrix and test bundle delivered; real Windows acceptance deferred by the user.
5. Region overlap: bounded early refill rejected after confirmation; fairness/dispatch measurement tools and CPU/Metal caller coverage retained.
6. Encoding/compression: codec and NBT attribution completed; grow-only scratch and uniform-proof forwarding rejected.
7. Definition loading: immutable borrowed conversion retained, without moving preparation into first generation.
8. Interval diagnostics: bounded submission/window attribution retained and validated.
9. Java/Bukkit: allocation reduction retained after actual delivery profiling and lifecycle/value checks.

## Verification and future work

Closeout rechecks all 15 prior PRs as merged, both frozen native hashes, unchanged runtime sources, per-cycle evidence records, existing successful test logs, broad exact-output/preservation receipts and actual decoder results. The cumulative/control evidence includes 270 fresh MCAs and 184,320 strict NBT comparisons; native lighting/storage/real-Metal checks and actual Bukkit lifecycle tests are linked in JSON. These checks revalidate existing evidence; no tests are falsely described as rerun for a documentation change.

Future Windows testing should evaluate all eight input/output/palette combinations with real hardware, startup/cache and steady-state metrics, Nether and custom/tall inputs, and strict output checks before changing defaults. The existing test bundle remains available for that later work; no Windows run is requested to finish this pass.

Other leads remain exact CPU certificates/root-entry scheduling, dependency-view reuse with complete prefix identity, attribution of native foreground query tail latency, and Java cold-generation/biome/boxing/closure costs. They require new evidence; previously rejected variants are not accepted wins.

Closeout PR: [#429](https://github.com/ArcaneArts/Retina/pull/429). Delivery uses branch `codex/refinement-closeout` in its own worktree and ready PR with squash Automerge. Actual merge, primary synchronization and worktree cleanup are observed by the live task after publication. No public release is part of this pass.
