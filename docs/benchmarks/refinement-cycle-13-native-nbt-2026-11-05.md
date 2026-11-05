# Refinement cycle 13: native palette/NBT attribution

Cycle PR: [#426](https://github.com/ArcaneArts/Retina/pull/426).

**Discard the CPU uniform-proof forwarding prototype and all private instrumentation.** No runtime change is retained. This cycle profiles native NBT construction, completing the investigation that the [codec-only cycle](refinement-cycle-10-codec-scratch-2026-11-04.md) explicitly left open. A cheap complete-MCA CPU/Metal screen does not establish a worthwhile improvement.

The [machine-readable record](refinement-cycle-13-native-nbt-2026-11-05.json) includes every complete native process, individual latency/CPU/RSS/size/transfer observations, exact source/native/compiler identities, commands, phases, output hashes, byte receipts, adaptive stop and limitations. Private patches, binaries, logs and MCA corpora remain outside Git.

## What NBT construction actually costs

A private probe adds six non-nested phases inside the production encode_chunk path: header, block palette construction/packing/writes, quart biomes, lighting writes, heightmaps and metadata. It allocates isolated per-region counters with RETINA_ENCODING_DIAGNOSTICS=1 and prints them only after publication. Global per-node CPU VM diagnostics remain off. The first global-metrics probe build is unused; only the isolated v2 build supplies attribution.

These are summed **worker-elapsed** spans, including clocks and preemption, with concurrent workers able to overlap. They are neither exact process CPU nor an elapsed-time/GPU-utilization percentage. Phase instrumentation, codec time and whole-MCA time are distinct scopes.

| Measured region | Header | Block palettes | Biomes | Lighting writes | Heightmaps | Metadata |
| --- | ---: | ---: | ---: | ---: | ---: | ---: |
| CPU Overworld (0,0) | 0.32ms | 70.82ms | 13.34ms | 25.55ms | 13.46ms | 1.15ms |
| Metal Overworld (0,0) | 0.26ms | 12.99ms | 13.35ms | 14.11ms | 13.39ms | 1.20ms |
| Metal Overworld (1,0) | 0.31ms | 15.52ms | 13.93ms | 15.72ms | 15.52ms | 1.26ms |
| Metal Nether (0,0) | 0.47ms | 11.52ms | 10.16ms | 5.80ms | 4.18ms | 4.45ms |
| Metal outer End (10,10) | 0.58ms | 2.94ms | 8.62ms | 6.15ms | 9.15ms | 0.71ms |

CPU normal NBT totals about 125ms of worker spans while that measured whole-region process uses 35.10 CPU-seconds. Those scopes are different, but the evidence does not suggest that removing palette traversal can explain a large overall CPU improvement. Native registry execution and lighting remain larger opportunities.

The normal CPU path invokes the palette builder for all 24,576 sections, including dense uniform sections. Metal already uses 13,290 uniform fast paths, 8,265 GPU-packed paths and 3,021 CPU builder fallbacks in region (0,0). These are path counts: a CPU builder can discover a uniform section. They are not physical mixed-section counts.

The normal region emits 70,303,233 raw NBT bytes. Cold NBT scratch appears in 83 CPU / 84 Metal chunks; summed positive ending-minus-starting Vec capacity is approximately 9.9MB. This is logical capacity growth, not allocator-call count, observed DRAM traffic, retained memory or peak RSS. Existing material-NBT caches and lazy compressors remain effective. These observations do not justify complex thread-local pools or unbounded retained buffers.

Four production saved-definition attribution processes generate nine fresh MCAs including four warmups. All 5,120 measured payloads match same-backend goldens; all five reported NBT byte totals equal the actual decompressed MCA sizes exactly. Warmups at (8,8) are recorded separately. End uses its existing CPU substrate plus GPU lighting; no generic GPU-job counter is used to reject that valid mixed path.

## Rejected uniform-proof reuse

CPU lighting already classifies final material sections for constant-light proofs. The prototype copies just the bounded current tile's descriptors, exposes their proven uniform IDs to encoding, gives existing GPU palettes precedence and keeps mixed sections on the old CPU builder. It changes no RNG, material values, lighting rules, thread counts, pipeline depth or publication path. Its draft unit would check IDs 0/37/65,535, mixed fallback and GPU precedence, but is not run or shipped; all prototype source and the test are discarded.

Fresh uninstrumented baseline f2d38a66... and candidate 977166d4... use the same current-main lane, Rust 1.93.0 / LLVM 21.1.8, release ThinLTO/two codegen units, locked dependencies, default scalable-allocator/spirv-tools features, build budget two and no RUSTFLAGS override. Instrumentation is fully restored before both builds. Full hashes and patch/build identities are in JSON.

The first complete CPU pair covers two measured normal regions (0,0)/(1,0), one warmup, one caller, assembly budget 16/global Rayon 2, final readiness, lit/decorated/encoded/zlib/atomic-MCA completion:

| CPU screen | Baseline | Candidate |
| --- | ---: | ---: |
| MCA throughput | 12.05 chunks/s | 14.72 chunks/s |
| Process CPU / region | 52.48s | 51.88s |
| Average scheduled cores | 0.617 | 0.746 |

Throughput rises 22.19%, but CPU work falls only 1.14% while scheduled CPU increases materially. One pair under extreme changing host load cannot establish a code improvement. The planned longer series is stopped after this cheap screen, only after verifying controller/process-group ownership; any started third process is partial and excluded. AC is attached and the OS reports no recorded thermal/performance warning. Other workloads and global power settings are untouched.

Two reversed Metal screen pairs show throughput -37.78% and +2.96%, CPU work +9.41% and +1.02%, generation high-water RSS +1.30% and +2.01%. This does not establish a repeatable benefit or a reliable backend regression magnitude. The new representation also adds descriptor-copy/struct-size costs. No memory/allocation saving or end-to-end performance win is demonstrated, so the prototype is rejected without extending costly confirmation under contention.

Normally five matched primary pairs would be required **before retaining** a performance change. This is an explicitly inconclusive, rejected screen with one CPU pair and two Metal pairs; no confidence interval or qualified speedup is manufactured from them. Codec cycle 10 already performed separate five-pair full-region CPU/Metal experiments and actual Minecraft decoding for its own rejected scratch change.

## Coverage and disposition

Across attribution and screens, ten successful native generator processes produce 27 fresh MCAs including ten warmups, 17 measured regions and 17,408 strict same-backend decompressed-NBT comparisons. These checks include lighting, structures, block entities, heightmaps and metadata. All measured attribution byte totals are independently checked against actual published MCAs. The stopped partial CPU process is excluded.

No new Minecraft decoding or live Java responsiveness claim is made. This discarded prototype is not qualified for additional seeds, custom/tall worlds, partial publication or cancellation; those broader guards would be required if retaining relevant runtime code. Existing codec/storage/actual Minecraft evidence remains documented in cycle 10. The final native source tree is restored exactly; documentation JSON parses and git diff --check passes.

A separate, more promising CPU-lighting follow-up is contiguous direct-sun initialization: the prior synthetic helper attributes about 25ms of a 55ms tile to the existing column-major walk. Reordering it must preserve each column's descending-Y state, integer fixed point and the independent old reference, then demonstrate actual complete-MCA gains. It is neither part of this discarded NBT prototype nor a claimed improvement here.

Actual Windows Vulkan transport qualification remains pending. No backend defaults, JARs or release artifacts change.
