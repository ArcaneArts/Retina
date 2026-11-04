# Cycle 7 — CPU lighting tile sizes

Based on main `4a89afe45f409ad1ecd2bf7ae1d907a4c4c57d4d`. Two variants change only the CPU lighting core width from 5 to 6 or 10. Both still divide the 30x30 interior, use one chunk of halo, keep the existing propagation and seeding algorithms and leave the outer-ring seam path unchanged. No row-staging, scratch-reuse, scheduling or worker-budget changes are combined with this experiment.

Larger tiles reduce overlapping halo volume when every tile is nonconstant: 1,764 staged chunk-equivalents at core 5, 1,600 at core 6 and 1,296 at core 10. These are theoretical all-nonconstant counts, not observed tile inventories. Larger volumes also increase scratch, can change constant-tile skips and change encoding callback batch sizes.

## Production-native evidence

Both locked release builds pass in the private target: core 6 takes 339.42s and core 10 takes 168.43s under the observed competing host load. Rust 1.93.0, default features, thin LTO and codegen units 2 are unchanged. No owned compiler overlaps the timed screens. The normal primary native and Windows test bundle are untouched.

| Native | SHA-256 |
| --- | --- |
| Baseline core 5 | `3f97234861143bb9062acb431d4a25b24ff19fc6e7dadbb53436cb60681619c5` |
| Core 6 | `7047ee7efbaf85cf16acb5c4a51de1ab1ee771834ebbd2ab9997265d2360353f` |
| Core 10 | `ce6f44e2febe17c360b44b36612e8a3bfdccbbc549873908eb7238b48da3ea32` |

The baseline was freshly built in cycle 6 from main `50c375c1`; current main changes only documentation, with identical native tree `b1493193450f86fbb7bc8d13aea8a5357436e1ad` and root Cargo configuration. Each comparison makes fresh baseline measurements rather than reusing earlier timings.

Each screen uses four fresh processes in ABBA order, one untimed warmup plus two measured complete lit/decorated/zlib MCA regions at (0,0)/(1,0), seed 123456789, saved vanilla definition SHA-256 `98e8cf1a2e3e5e049192b0b5245b58aa44a8001f3b00a6ddeb51a619ad375b4e`. Native budget is 16 (4 dependency/assembly + 12 terrain), global Rayon 2, one caller. The same finite activity wrapper applies to actual benchmark owners; active/ended observations and before/after host snapshots are retained. Other applications/game/VM/compiler work remains active; no unrelated process or system setting is changed.

| Core comparison / trial | Chunks/s | Process CPU ms/region | Lighting host ms/region | Generation high-water MiB |
| --- | ---: | ---: | ---: | ---: |
| 6 / baseline 0 | 39.94 | 51,476.4 | 3,457.0 | 1,224.1 |
| 6 / candidate 1 | 60.33 | 51,310.1 | 3,339.6 | 1,242.4 |
| 6 / candidate 2 | 35.48 | 52,922.6 | 5,174.2 | 1,258.0 |
| 6 / baseline 3 | 44.61 | 48,924.3 | 5,121.8 | 1,248.9 |
| 10 / baseline 0 | 44.36 | 52,018.3 | 3,901.6 | 1,255.3 |
| 10 / candidate 1 | 32.09 | 49,492.7 | 4,442.5 | 1,330.8 |
| 10 / candidate 2 | 27.67 | 51,360.0 | 5,794.9 | 1,303.3 |
| 10 / baseline 3 | 46.48 | 48,087.4 | 4,189.1 | 1,246.0 |

Core 6 is nominally +13.33% throughput, but the descriptive two-observation median-bootstrap interval is -20.46% to +51.07%. Median process CPU is 3.82% higher, lighting-host time is nearly unchanged and peak memory is about 13.7 MiB higher. This does not establish a useful improvement.

Core 10 is -34.22% throughput in this screen, with interval -40.46% to -27.67%; both candidate observations are slower than the bracketing controls. Median process CPU is 0.75% higher, lighting-host time 26.53% higher and peak memory about 66.4 MiB higher. That is sufficient to reject this candidate here, without claiming the same slowdown on every machine or a precise causal explanation.

Host scheduling varies substantially. The intervals and per-region p95 are descriptive small-sample observations, not production tail estimates or proof that workload bytes alone determine speed. Reduced theoretical halo work does not establish lower actual latency. The existing column-major direct-sky scan uses different array strides at these widths; cache effects are an unmeasured hypothesis, not a diagnosed cause.

## Correctness, disposition and reproduction

Eight actual `CPU: native` processes generate 16 measured regions and eight warmups. **12,288 strict same-backend decompressed NBT chunk comparisons pass**, including lighting, structures, block entities, heightmaps and metadata; membership is identical. All measured two-region outputs occupy 18,264,064 bytes. Library hashes match their frozen identities. No GPU-job requirement gates CPU generation.

Both candidates are rejected and their runtime edits are restored exactly to main. They do not advance to five-pair retention qualification, broader custom/tall/backend guards or new unit scaffolding. This is not a claim of broad acceptance for discarded code. Only the experiment record is merged; no default, JAR or release changes are made. The normal primary native remains SHA-256 `76437e0d177cb691fc91cc922f955af9f3acd0ef2ce3016a164add9530a01a94`.

The [machine-readable record](refinement-cycle-07-cpu-light-tiles-2026-11-04.json) contains individual metrics, sample variability, descriptive p95, requests, actual backends, hashes and host-load observations. Large logs, MCA output, source patches, frozen natives and the sequential screen controller remain under `~/.cache/retina/refinement-implementation-20261104/` with `cycle07-` prefixes.

Reproduce using `scripts/native-region-benchmark-ab.py` with `--backend cpu --assembly-threads 16 --trials 4 --warmups 1 --count 2 --origin-x 0 --origin-z 0 --grid-width 2 --timeout 1800`, the saved definition, dimension `minecraft:overworld`, and frozen baseline/candidate libraries. The archived `cycle07-run-screens.py` invokes the existing `cycle06-ab-owner.py` to apply identical process context and capture host observations. Use new owned output directories.

CPU lighting row staging, scratch reuse and tile sizes now all have investigated rejection records. Direct-sky streaming remains a separate unimplemented lead from phase evidence. Next mandatory work is Nether dependency/query attribution, followed by the remaining overlap, encoding and Java/Bukkit areas; real Windows transport qualification remains pending.
