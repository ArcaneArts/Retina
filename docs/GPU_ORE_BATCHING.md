# Ore preparation batching and device measurements

Bulk ore masks already run on the terrain GPU. Rust retains registered attempt
counts, heights, biome membership, sphere geometry, exposure rules and ordered
replacement. Noise-threshold spraying would change those recipes rather than
accelerate them. Registered plant count noise uses the separate
[sparse GPU count sampler](GPU_FEATURE_COUNTS.md); live canopy and branching
geometry remain in parallel Rust.

Ore preparation now retains one sphere vector per anchor instead of one per vein.
Recipe records hold ranges into that vector. Rayon reuses geometry scratch across
anchors, and the final upload vectors reserve their measured lengths before
merging. Sphere values, descriptor order, mask sizes, replacement order and random
streams remain unchanged. This eliminates roughly 72,000 small sphere-vector
allocations at the measured vanilla region without introducing a new GPU pass or
new save/cache data.

## F3 and the native bridge

**Ore planning (CPU + GPU)** remains the complete planning wall phase: preparation,
submission-lock waiting, upload, device execution and readback. **Ore masks** is a
new device row, measured by timestamp queries around the actual mask pass. It
overlaps that wall phase and must not be added to it. Region-local measurements
travel through the existing rolling 20-region window and network payload.
Temporary DH regions measure this work once; promotion retains that window.

The bridge uses timing ABI 5: 25 counters, 232-byte snapshots and 272-byte detailed
region reports. The narrow 40-byte region report and chunk-request ABI are
unchanged. Snapshot flag bit 0 denotes measured device work; bit 1 denotes a valid
ore-mask timestamp. Unavailable, reversed or stale ore query pairs are reported
as not measured, while ore generation continues. Query buffers are reused under
the submission lock and add 16 bytes of readback per measured region.

## Measurements

Release build, Metal / Apple M4 Max, seed 123456789, actual full vanilla and
Terralith registry exports including structures. Baseline is `8b5dded`. Each run
has two warmups and twenty measured adjacent regions, with specialized programs.
Benchmarks run separately from builds, tests and other benchmark processes.
Local evidence: `build/goal-baseline/ore-pruning/`.

| Profile | Before region ms | After region ms | Before chunks/s | After chunks/s | Ore planning before → after ms/region |
| --- | ---: | ---: | ---: | ---: | ---: |
| Vanilla serial | 131.44 | 125.57 | 7,777 | 8,140 | 25.67 → 21.07 |
| Terralith serial | 154.39 | 150.03 | 6,620 | 6,814 | 21.57 → 17.47 |
| Vanilla serial repeat | 129.39 | 125.27 | 7,899 | 8,158 | 24.90 → 20.83 |
| Terralith serial repeat | 155.87 | 150.25 | 6,559 | 6,805 | 22.07 → 17.77 |
| Vanilla two callers | 177.76 | 178.63 | 11,287 | 11,237 | 41.20 → 35.88 |
| Terralith two callers | 245.26 | 246.43 | 8,233 | 8,292 | 31.99 → 28.67 |

Serial gains repeat at approximately 3–5%. **Concurrent throughput has no reliable
improvement**, despite reduced ore planning time. Two-caller average request times
include contention; throughput uses actual elapsed wall time.

Device mask work is about 5.42 ms/region vanilla and 3.70 ms Terralith. Actual
upload volume is unchanged: 14.25 MB/region vanilla and 12.37 MB Terralith. The ore pass
adds only its 16-byte timestamp pair to readback; measured total region readback
remains approximately 37.63 MB vanilla and 33.98 MB Terralith. Process RSS, including profiles,
caches and the comparison reader, is approximately 1,084 → 952 MiB vanilla and
1,665 → 1,646 MiB Terralith. Process peaks vary with allocation lifetime; these
figures do not establish steady-state cache memory savings. Repeat/concurrent
RSS is mixed: vanilla serial repeat increases 916 → 966 MiB, while Terralith
decreases 1,593 → 1,534 MiB; two callers measure 1,319 → 971 MiB vanilla and
1,605 → 1,635 MiB Terralith. No uniform memory reduction is claimed.

All 122,880 decompressed chunk NBT records match the retained baseline. Twenty-region
file totals remain 147,857,408 bytes vanilla and 126,537,728 bytes Terralith.
These are native generation measurements; Minecraft loading, lighting, Java
column-cache work and rendering are outside them. No dramatic or universal
GPU-offload speedup is claimed.

Warm shader-cache initialization is 36–60 ms; registration is 467–474 ms vanilla
and 962–1,008 ms Terralith. First warmup requests are about 603–661 ms vanilla
and 2,495–2,559 ms Terralith, including existing program specialization. This
change does not resolve cold specialized-shader compilation stalls.

## Rejected GPU containment-pruning experiment

An actual-device prototype moved the ordered sphere containment walk into the
existing mask pass, avoiding another round trip. It uploaded all unpruned spheres
and lazily repaired ambiguous float comparisons in Rust. Its full carved/ore block
arrays matched the CPU reference, including far coordinates and reused buffers.
It was **not retained in production** because it made generation slower.

Focused tests use five warmups and twenty measured repeats. Vanilla at chunk
(0,0) increased planning/application from 28.78 to 37.71 ms; Terralith at (32,0)
increased from 21.46 to 25.46 ms. Sphere uploads increased from 14.35 to 26.34 MB
and 11.74 to 17.49 MB. CPU preparation barely changed, while device work nearly
doubled. Thus moving this loop alone to the GPU is not useful. Raw results and the
rejected patch/binary are retained in the local evidence directory. The focused
prototype's baseline shader includes inactive pruning guards; whole-region
comparisons above instead use the unchanged production baseline library.

Seed/recipe-driven GPU descriptors could avoid the larger upload, but would need
resident recipe tables, ordered compaction, preserved placement semantics and a
measured reduction in total latency. Whole-voxel noise scanning is especially
wasteful for sparse placements; registered per-anchor attempts are the useful
batching unit.

## Reproduce

```sh
python3 scripts/native-region-benchmark.py \
  --library build/native-target/release/libretina_worldgen.dylib \
  --profile build/decoration-count-vanilla.json \
  --out build/ore-batching-vanilla --count 20 --warmups 2 \
  --program-execution specialized --compare build/retained-baseline-vanilla
```

Use `--parallel 2` for concurrent requests. The actual GPU regression checks
host replacement chains, exposure, scattered ores, negative/far coordinates,
chunk edges and alternating large/small buffers. The bridge, count sampler,
Minecraft MCA decoder, temporary-region/promotion and F3 payload checks also pass.
The final release JAR contains the tested native library byte for byte
(SHA-256 `6f424587069ef5e0e4c896e8952d08a10840b51b485a8905fbfaa57087537abf`).
Validation logs are `flat-validation.log` and `final-validation.log` in the local
evidence directory; 30 native unit tests and the focused actual-device regressions
pass. Vulkan/DX12 runtime performance still requires those devices.
