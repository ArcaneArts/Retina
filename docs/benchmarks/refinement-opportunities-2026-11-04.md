# CPU, Metal and Vulkan refinement opportunities — 2026-11-04

This is an inspection and benchmark report, with **no generation changes or backend-default changes**. The most promising follow-ups are reducing CPU graph traversal, reducing Nether dependency/planning work, improving CPU lighting data movement, and validating Metal's compact lighting transport on Windows Vulkan. Scheduling and publication work also matter once GPU terrain becomes fast.

The [machine-readable evidence](refinement-opportunities-2026-11-04.json) contains all 29 successful process measurements, 37 measured complete MCA regions, backend identities, timings, process CPU work, transfer bytes, high-water memory, comparisons, native hashes and the ranked opportunity records. The larger logs, MCA output, profiler sample and experiment controllers remain locally under `~/.cache/retina/refinement-20261104/`. No distribution JAR was rebuilt and no release was requested.

## Scope and comparability

- Source: `eb648c7346933c4d4ffa59c6adf9c64958073539`; actual published release **v1.1.494**. CPU and Metal use its extracted Mac native, SHA-256 `28ebe8497b20a722b0f21d529e43384fdcda904c659ef85d51db8e7b5ab8146f`. The JSON records the release JAR and separate Vulkan feature-build hashes. Both native builds use Rust 1.93.0.
- Host: Apple M4 Max, 16 logical processors, 128 GiB, macOS 27.0.1. AC attached, power mode 0; no recorded thermal/performance warning. Other Rust, Node, VM and desktop workloads varied substantially. Nothing unrelated was stopped and no power settings were changed. The Vulkan build finished before uninstrumented measurements.
- Input: saved vanilla `retina.dat`, SHA-256 `98e8cf1a2e3e5e049192b0b5245b58aa44a8001f3b00a6ddeb51a619ad375b4e`, seed 123456789, Overworld generator/storage -64/384. Region (0,0); 2-region controls also include (1,0). Nether uses (0,0), outer End uses (10,10).
- Completion: a **lit, decorated, encoded, zlib-compressed and saved MCA**, 1,024 generated records and zero preserved records per fresh region. It excludes Minecraft chunk decoding/activation, outer-ring seam lighting, packets, rendering and client FPS.
- Budget: one caller unless stated; native budget 16, global Rayon 2. CPU divides its combined budget into 4 dependency/assembly and 12 terrain workers. Four-worker controls use 1+3. Metal's assembly budget is 16 and is not the same CPU scheduling topology.
- Warm CPU and Metal trials use one completed warmup outside timing. Metal also waits for a final shader readiness state. CPU first-region controls have no warmup. Instrumented/sampled attribution and transport controls are separate from uninstrumented observations.
- These are shared-host observations, **not a regression verdict or a precise cross-backend speedup claim**. Warm CPU versus initial Metal sampling happened in different load windows. Small-sample p95 is descriptive, not a population tail estimate. Throughput uses batch wall time; parallel per-request latency is a different quantity.

## Completed region measurements

| Overworld series | Fresh processes | Median region | Sample p95 | Observed range | Median process CPU / region |
| --- | ---: | ---: | ---: | ---: | ---: |
| CPU, 16 workers, warmed | 3 | 5.433s | 6.047s | 4.451–6.115s | 34.39s |
| Metal, 16 assembly workers, shader ready | 3 | 0.732s | 1.018s | 0.639–1.050s | 1.30s |
| CPU, 16 workers, first region / earlier load | 3 | 14.243s | 17.270s | 11.743–17.607s | 37.89s |
| CPU, 4 workers, first region / earlier load | 2 | 15.714s | 16.424s | 14.924–16.503s | 35.05s |

The initial CPU series consumed about 37.7–39.0 CPU-seconds per region while wall time varied 11.7–17.6 seconds; later warmed runs used 30.6–34.5 CPU-seconds and 4.45–6.12 seconds wall time. Readiness, reuse and changed host scheduling all differ. This does not establish that warmup alone causes the change, or that the release regressed from an earlier date. Later Metal controls complete in 0.28–0.34 seconds; that is another reason not to turn the initial series into a universal throughput figure.

The following are **one-process coverage observations**, not robust dimension comparisons:

| Case | Complete region | Process CPU / region | Generation high-water RSS |
| --- | ---: | ---: | ---: |
| cpu-nether | 25.637s | 116.60s | 1495 MiB |
| metal-nether | 2.925s | 4.07s | 1989 MiB |
| cpu-end | 1.420s | 1.32s | 535 MiB |
| metal-end | 0.314s | 0.34s | 737 MiB |

The Metal End case uses the existing CPU End substrate plus GPU lighting; its terrain-job counter is zero while lighting timestamps and transfer bytes are positive. It is not an all-GPU End terrain benchmark.

Nether is a useful next workload: its CPU vegetation/structure planning timers accumulate **21.19s / 13.26s**, and Metal vegetation planning accumulates **2.54s** on a **2.93s** region. Planning timers can overlap recursively or across workers; they must not be added into an elapsed-time percentage.

## Focused controls

**Region overlap, identical 2-region workload, ABBA:** one caller completes batches in **0.640s / 0.967s**; two callers in **0.573s / 0.520s**. All compared NBT is identical. Two callers increase generation high-water RSS to **1,738–1,747 MiB**, versus **1,485–1,544 MiB**, and individual requests generally take longer. This is promising throughput evidence with only two trials per setting; it does not justify increasing all generation concurrency defaults.

**Lighting transport, Metal, ABBA, diagnostics enabled in both settings:**

| Setting | Region times | Lighting material upload | All profile upload | All profile readback |
| --- | --- | ---: | ---: | ---: |
| Existing compact input/output + GPU palettes | 0.282s / 0.304s | 93.78 MiB | 100.58 MiB | 113.65 MiB |
| Dense input/output + CPU palettes | 0.335s / 0.340s | 243.00 MiB | 249.69 MiB | 143.66 MiB |

The combined compact path sends **61.4% fewer lighting-input bytes** and **20.9% fewer total readback bytes**, with exact NBT equality. These tiny local timing samples suggest a benefit but combine three switches; they do not isolate each switch or predict a discrete Windows GPU. Vulkan/DX12 currently default to dense transfer and CPU palette encoding; Metal already uses the compact path. Test those existing switches on Windows before changing defaults.

**CPU work trace:** a separate, heavily contended, sampled two-region run executes **106.48 million VM calls**, **3.604 billion node visits**, **1.340 billion computed nodes**, and **385.30 million primitive noise calls**. That establishes substantial remaining graph work, not a time percentage. The sample captured a lighting/encoding interval, not a representative whole-run flame graph. Uninstrumented warm CPU lighting occupies approximately **0.86–1.20s per region**; the contended diagnostic lighting timing is not used as the expected saving.

**Timeline interpretation:** one 648.6ms Metal batch has a snapshot delta of 2,483.7ms in spans and 1,998.7ms in gaps. Concurrent spans may overlap, gaps can cross the warmup/readiness boundary, and peak in-flight is lifetime-wide. These quantities are not an interval GPU-idle percentage. Opportunity O8 describes better attribution without inventing a runtime failure or adding a generation gate.

## Vulkan attempt on this Mac

A clean release build with `--features vulkan-portability` selected **Vulkan: Apple M4 Max**, using MoltenVK 1.4.2 and Vulkan loader 1.4.363.0. Two complete vanilla attempts and two saved-procedural column attempts failed with:

```text
GPU device lost: backend=Vulkan: Apple M4 Max reason=Unknown
message=Unexpected error variant (driver implementation is at fault)
last_submission=none
```

The full-attempt logs associate the loss with startup around the `guarded` ore-mask compute pipeline, before any terrain submission. They provide no completed region or column query, no terrain-throughput measurement, and no successful cold/warm shader-cache comparison. Backend selection alone is not a successful GPU test. The exact cause needs separate investigation; this is neither a measurement of Windows Vulkan nor evidence that Windows has the same fault. Stop using this local portability path to rank Vulkan performance until it can complete the real workload.

## Ranked implementation opportunities

Every item below is an **opportunity**, not a proven defect or promised speedup. Expected gain is a prioritization estimate; validation must establish the actual end-to-end result.

### O1. Opportunity: Reduce CPU registry VM work with prepared execution kernels

Expected gain: **high; unquantified end-to-end**. Risk: **high**. Complexity: **high**. Confidence: **high for remaining work; medium for proposed solution**.

The separate two-region trace still executes 106,479,261 VM calls, 3,603,641,198 node visits and 1,340,066,854 computed nodes. Prototype prepared branch/basic-block kernels or additional exact material certificates that eliminate traversal rather than assuming noise arithmetic is the whole cost. Compact dependencies, constant folding, bounded corner caches and high-Y material-zero proofs already exist. Do not resurrect the rejected identity-index shortcut as an established win.

Evidence: [native/src/cpu/program.rs:494](https://github.com/ArcaneArts/Retina/blob/eb648c7346933c4d4ffa59c6adf9c64958073539/native/src/cpu/program.rs#L494), [native/src/cpu/program.rs:515](https://github.com/ArcaneArts/Retina/blob/eb648c7346933c4d4ffa59c6adf9c64958073539/native/src/cpu/program.rs#L515), [native/src/cpu/program/material_zero.rs:24](https://github.com/ArcaneArts/Retina/blob/eb648c7346933c4d4ffa59c6adf9c64958073539/native/src/cpu/program/material_zero.rs#L24).

Validation: A/B process CPU work and wall time separately, scalar-reference all six roots, strict NBT on both seeds/dimensions/custom and 768-height packs. Preserve f32 order, lazy branches, implicit root zero, nonfinite failures and inclusive random endpoints. Retain only reproducible full-MCA improvements.

### O2. Opportunity: Batch Nether dependency sampling across independent anchors

Expected gain: **high for Nether; unquantified**. Risk: **high**. Complexity: **high**. Confidence: **high for stage concentration; medium for cause**.

Nether CPU takes 25.64s and 116.60 CPU-seconds in this single early trial; vegetation/structure planning timers sum to 21.19s/13.26s. Metal still spends 2.54s in vegetation planning on a 2.93s region. Replay polls already batch within one anchor and memoize dependencies, but each GPU query batch synchronously locks feature_gpu. Measure batch sizes and repeated independent lookups, then coalesce compatible batches across anchors or reuse immutable results by complete dependency/version identity. Never cache solely by X/Z across edited prefixes.

Evidence: [native/src/decoration/dependencies.rs:929](https://github.com/ArcaneArts/Retina/blob/eb648c7346933c4d4ffa59c6adf9c64958073539/native/src/decoration/dependencies.rs#L929), [native/src/decoration/dependencies.rs:944](https://github.com/ArcaneArts/Retina/blob/eb648c7346933c4d4ffa59c6adf9c64958073539/native/src/decoration/dependencies.rs#L944), [native/src/decoration/dependencies.rs:1669](https://github.com/ArcaneArts/Retina/blob/eb648c7346933c4d4ffa59c6adf9c64958073539/native/src/decoration/dependencies.rs#L1669).

Validation: First collect one opt-in Nether anchor/query/lock-wait trace; several detailed worker probes are cfg(test), so zero release counters are not proof of no work. Separate locks, terrain dependencies, replay and GPU execution. Preserve root order/RNG state/version visibility and cancellation; exact NBT, seam/structure tests, bounded queue/memory and caller fairness.

### O3. Opportunity: Make CPU lighting use bulk material rows and bounded reusable scratch

Expected gain: **medium; serial stage roughly 0.86–1.20s in warm CPU trials**. Risk: **medium**. Complexity: **medium**. Confidence: **high for source/stage evidence; medium for speedup**.

CPU lighting allocates dense materials/light/queue state per nonconstant tile and fetches each material through a scalar closure. Use existing Uniform/Dense row and section views, validated bulk fills and bounded scratch reuse. Compare tile cores 5/6/10: full nonconstant tiling stages 1,764/1,600/1,296 chunk equivalents for the 900-chunk interior; larger tiles also increase peak scratch and can lose constant-tile skips. Exact frontier seeding and constant tile elision already exist.

Evidence: [native/src/lighting/cpu.rs:113](https://github.com/ArcaneArts/Retina/blob/eb648c7346933c4d4ffa59c6adf9c64958073539/native/src/lighting/cpu.rs#L113), [native/src/lighting/cpu.rs:288](https://github.com/ArcaneArts/Retina/blob/eb648c7346933c4d4ffa59c6adf9c64958073539/native/src/lighting/cpu.rs#L288), [native/src/material_blocks.rs:21](https://github.com/ArcaneArts/Retina/blob/eb648c7346933c4d4ffa59c6adf9c64958073539/native/src/material_blocks.rs#L21).

Validation: Measure packing/initialization/propagation/output separately, not just a larger tile microbenchmark. Compare final complete MCA and exact integer lighting on transparent blocks, emitters, overhangs, Nether/End, custom heights and boundary rings. Keep original producer/consumer lanes runnable; do not add nested blocking Rayon dependencies or unbounded tile parallelism.

### O4. Opportunity: Validate compact lighting and GPU palettes on Windows Vulkan

Expected gain: **high reduction in bytes; Windows speedup unknown**. Risk: **medium**. Complexity: **medium**. Confidence: **high for byte reduction; low for Windows performance**.

Metal defaults to compact input/output and GPU palettes; Vulkan and DX12 default to dense paths. The matched Metal ABBA control reduces lighting upload from 243.00 to 93.78 MiB, total upload from 249.69 to 100.58 MiB, and total readback from 143.66 to 113.65 MiB per region. Existing flags provide the experiment: RETINA_LIGHTING_INPUT=compact, RETINA_LIGHTING_OUTPUT=compact, RETINA_GPU_PALETTES=1. There is no new format to invent.

Evidence: [native/src/lighting.rs:38](https://github.com/ArcaneArts/Retina/blob/eb648c7346933c4d4ffa59c6adf9c64958073539/native/src/lighting.rs#L38), [native/src/lighting.rs:179](https://github.com/ArcaneArts/Retina/blob/eb648c7346933c4d4ffa59c6adf9c64958073539/native/src/lighting.rs#L179), [native/src/lighting.rs:783](https://github.com/ArcaneArts/Retina/blob/eb648c7346933c4d4ffa59c6adf9c64958073539/native/src/lighting.rs#L783).

Validation: On real Windows Vulkan, vary input, output and palettes separately before the combined setting. Fresh/repeated driver caches, representative NVIDIA/AMD adapters, Nether and tall packs, strict integer-light/NBT equivalence, transfer bytes, end-to-end throughput and peak memory. MoltenVK failure here cannot justify enabling defaults globally.

### O5. Opportunity: Tune bounded region overlap around throughput, latency and memory

Expected gain: **medium; small sample suggests throughput upside**. Risk: **medium**. Complexity: **medium**. Confidence: **medium**.

Existing depth-two terrain and lighting pipelines already overlap submissions. In the matched 2-region ABBA test, one caller takes 0.640/0.967s per batch and two callers 0.573/0.520s. Two callers raise per-request latency and generation high-water RSS to about 1,738–1,747 MiB versus 1,485–1,544 MiB. Investigate host handoff bubbles and overlap encoding with ready work within current budgets, rather than simply raising caller counts.

Evidence: [native/src/gpu_worker.rs:130](https://github.com/ArcaneArts/Retina/blob/eb648c7346933c4d4ffa59c6adf9c64958073539/native/src/gpu_worker.rs#L130), [native/src/gpu_worker.rs:143](https://github.com/ArcaneArts/Retina/blob/eb648c7346933c4d4ffa59c6adf9c64958073539/native/src/gpu_worker.rs#L143), [native/src/region.rs:629](https://github.com/ArcaneArts/Retina/blob/eb648c7346933c4d4ffa59c6adf9c64958073539/native/src/region.rs#L629), [native/src/lighting.rs:829](https://github.com/ArcaneArts/Retina/blob/eb648c7346933c4d4ffa59c6adf9c64958073539/native/src/lighting.rs#L829).

Validation: Repeat longer interleaved 1/2/4-caller trials at identical coordinates, completion boundaries and readiness. Record per-request p95, process CPU, interval-scoped GPU events, byte counts, RSS and fairness with foreground loads plus Chunky/DH. Require useful throughput gain without unacceptable memory/latency or starvation.

### O6. Opportunity: Amortize chunk encoding/compressor scratch across lighting tiles

Expected gain: **medium for fast GPU generation; exact share unknown**. Risk: **medium**. Complexity: **medium**. Confidence: **medium**.

The lighting callback repeatedly creates parallel map_init scratch/compressor state; compression copies its reusable output into each retained Record. NBT/compression become more relevant when terrain is fast. Existing code already caches material NBT, handles uniform sections, uses libdeflate level 1, supports LZ4 and directly writes some GPU palette data. Profile scratch lifetime and allocation/copy cost before changing ownership or adding worker-retained buffers.

Evidence: [native/src/region.rs:601](https://github.com/ArcaneArts/Retina/blob/eb648c7346933c4d4ffa59c6adf9c64958073539/native/src/region.rs#L601), [native/src/region.rs:629](https://github.com/ArcaneArts/Retina/blob/eb648c7346933c4d4ffa59c6adf9c64958073539/native/src/region.rs#L629), [native/src/region/codec.rs:51](https://github.com/ArcaneArts/Retina/blob/eb648c7346933c4d4ffa59c6adf9c64958073539/native/src/region/codec.rs#L51), [native/src/region.rs:848](https://github.com/ArcaneArts/Retina/blob/eb648c7346933c4d4ffa59c6adf9c64958073539/native/src/region.rs#L848).

Validation: Use allocation/CPU attribution with full lit-MCA output plus actual Minecraft read/decompress/decode. Benchmark existing zlib/LZ4 separately as a compatibility/size tradeoff; LZ4 is not a new feature. Preserve record limits, timestamps, partial-region preservation, atomic publication and bounded retained capacity. Summed parallel timers cannot be used as a serial speedup bound.

### O7. Opportunity: Reduce definition-loading copies and unnecessary binding preparation

Expected gain: **medium for startup/memory; little steady-state benefit**. Risk: **medium**. Complexity: **medium**. Confidence: **medium**.

The loader retains the wire document and initialized profiles, clones structural profiles during decode, and constructs adapter metadata as another owned Value tree/JSON buffer. First binding registers every supported dimension. The measured load-and-bind range is about 0.7–4.0s in ordinary trials, strongly affected by host load; it includes metadata and preparation, not just disk IO. Profile these phases individually, then consider consuming conversions, shared immutable palette/metadata ownership and dimension-scoped preparation.

Evidence: [native/src/world_definition/mod.rs:51](https://github.com/ArcaneArts/Retina/blob/eb648c7346933c4d4ffa59c6adf9c64958073539/native/src/world_definition/mod.rs#L51), [native/src/world_definition/mod.rs:180](https://github.com/ArcaneArts/Retina/blob/eb648c7346933c4d4ffa59c6adf9c64958073539/native/src/world_definition/mod.rs#L180), [native/src/world_definition/adapter.rs:7](https://github.com/ArcaneArts/Retina/blob/eb648c7346933c4d4ffa59c6adf9c64958073539/native/src/world_definition/adapter.rs#L7), [native/src/world_definition/runtime.rs:49](https://github.com/ArcaneArts/Retina/blob/eb648c7346933c4d4ffa59c6adf9c64958073539/native/src/world_definition/runtime.rs#L49).

Validation: Measure parse, validation, metadata construction/serialization, CPU preparation and registration separately on vanilla and the large pack. Keep retina.dat authoritative, full error validation and stable identities; lazy binding must not merely move the same stall to the first request or break retirement, concurrent binds, alternate execution/compression contracts or unsupported-dimension reporting.

### O8. Opportunity: Make timeline attribution belong to the measured interval

Expected gain: **high decision value; no direct runtime gain claimed**. Risk: **low**. Complexity: **medium**. Confidence: **high**.

One Metal measurement lasts 648.6ms but its snapshot delta reports 2,483.7ms of device spans and 1,998.7ms of gaps. last_device_tick spans warmups/readiness waits and peak_in_flight is lifetime-wide. Concurrent spans can overlap and device gaps include copies/untracked work. These counters cannot be divided by batch wall time to claim GPU utilization or idle percentage.

Evidence: [native/src/gpu.rs:2325](https://github.com/ArcaneArts/Retina/blob/eb648c7346933c4d4ffa59c6adf9c64958073539/native/src/gpu.rs#L2325), [native/src/pipeline.rs:119](https://github.com/ArcaneArts/Retina/blob/eb648c7346933c4d4ffa59c6adf9c64958073539/native/src/pipeline.rs#L119), [scripts/native-region-benchmark.py:337](https://github.com/ArcaneArts/Retina/blob/eb648c7346933c4d4ffa59c6adf9c64958073539/scripts/native-region-benchmark.py#L337).

Validation: Add timestamp/event window membership and submission identities, explicitly account for boundary-crossing events and separate interval from lifetime peaks. Keep counter semantics documented and versioned. Validate warmup→readiness→measurement and overlapping terrain/material/lighting traces without synthetic preflight blockers; report measurement limitations instead of gating generation.

### O9. Opportunity: Profile Java delivery and reuse Bukkit section bindings

Expected gain: **low-to-medium; watchlist, not timed here**. Risk: **low**. Complexity: **medium**. Confidence: **low until host profiling**.

Bukkit already prebinds BlockData and caches chunk snapshots, but reconstructs canonical palette strings and resolves section BlockData arrays during installation. The generic FFI byte return also copies into Java. Investigate per-snapshot bound palettes/compact palette identities after actual host profiling. Native timings exclude Minecraft decoding, outer-ring seam lighting, live activation, rendering and packet delivery.

Evidence: [bukkit/src/main/java/art/arcane/retina/bukkit/ChunkSnapshot.java:34](https://github.com/ArcaneArts/Retina/blob/eb648c7346933c4d4ffa59c6adf9c64958073539/bukkit/src/main/java/art/arcane/retina/bukkit/ChunkSnapshot.java#L34), [bukkit/src/main/java/art/arcane/retina/bukkit/ChunkSnapshot.java:77](https://github.com/ArcaneArts/Retina/blob/eb648c7346933c4d4ffa59c6adf9c64958073539/bukkit/src/main/java/art/arcane/retina/bukkit/ChunkSnapshot.java#L77), [bukkit/src/main/java/art/arcane/retina/bukkit/DimensionContext.java:42](https://github.com/ArcaneArts/Retina/blob/eb648c7346933c4d4ffa59c6adf9c64958073539/bukkit/src/main/java/art/arcane/retina/bukkit/DimensionContext.java#L42), [core/src/main/java/art/arcane/retina/core/NativeWorldDefinition.java:220](https://github.com/ArcaneArts/Retina/blob/eb648c7346933c4d4ffa59c6adf9c64958073539/core/src/main/java/art/arcane/retina/core/NativeWorldDefinition.java#L220), [common/src/gpuTest/java/art/arcane/retina/worldgen/RegionDecodeBenchmark.java:23](https://github.com/ArcaneArts/Retina/blob/eb648c7346933c4d4ffa59c6adf9c64958073539/common/src/gpuTest/java/art/arcane/retina/worldgen/RegionDecodeBenchmark.java#L23).

Validation: Run the existing RegionDecodeBenchmark plus JFR for real server chunk loading/Bukkit generation, measuring allocation and main/IO-thread occupancy. Compare decoded values and block properties, plugin callback/thread requirements, unload lifetime and cache bounds. Do not call nested fixed-size voxel loops superlinear or remove bounded caches without evidence.

## Suggested follow-up sequence

1. Fix interval attribution and capture one representative Nether query/dependency trace. This makes subsequent decisions reviewable without changing generation behavior.
2. Try CPU lighting row/fill/scratch changes and Windows Vulkan lighting-transfer controls in separate, bounded experiments. They have clear inputs, outputs and rollback boundaries.
3. Pursue prepared CPU kernels/exact certificates and Nether cross-anchor batching as larger changes, each with strict scalar/NBT reference coverage and explicit memory/lifetime constraints.
4. Tune Metal handoff/encoding overlap and scratch lifetime using long interleaved workloads, then revisit startup representation and Java/Bukkit delivery from measured allocation traces.

Keep CPU, Metal and real Windows Vulkan controls for each retained change. Preserve the current exact generation rules, caller cancellation, dimension/profile ownership, saved-region preservation and bounded memory. Existing shader caches, lazy base pipelines, interpreter reuse, compact VM dependencies, material proofs, frontier lighting, uniform sections, palette handling and LZ4 support are already implemented; adding them again is not an opportunity.

The scout is only a lead generator. It flagged sorts inside tests and fixed-size voxel loops; those are not evidence of a runtime or algorithmic defect. The previously rejected identity-index shortcut remains rejected. Raising global thread counts or enabling every experimental compiler flag is not a supported conclusion from this pass.

## Validation and reproduction

Every successful fresh output contains 1,024 generated records per region. Repeated CPU/Metal controls compare strict decompressed NBT within their backend, including the concurrency and lighting-transport controls. Current CPU Overworld, Nether and outer End each also match all 1,024 retained reference chunks. CPU and GPU are not required to be bit-identical to one another. Input definitions and released native bytes were not modified.

Three exploratory parallel runs initially used a one-region reference for a two-region workload; comparison stopped on the absent reference file. They are excluded and were rerun against a matched two-region baseline. Two initial column probes used retired profile-zero requests and are also excluded; the reported Vulkan failure comes from the corrected saved-definition probes. Neither harness mistake is reported as a production terrain mismatch.

From a checkout of the pinned source, with the extracted release native and saved definition:

```sh
RAYON_NUM_THREADS=2 python3 scripts/native-region-benchmark.py \
  --library "$LIBRARY" --definition "$DEFINITION" \
  --dimension minecraft:overworld --backend cpu --assembly-threads 16 \
  --origin-x 0 --origin-z 0 --warmups 1 --count 1 \
  --scope lit-mca --lighting enabled --mca-compression zlib --out "$OUTPUT"
```

Repeat in fresh processes and unique output directories. For Metal use `--backend metal --readiness shader-ready`; for overlap use `--count 2 --parallel 1|2` with identical coordinates and a matched two-region `--compare` directory. Transport controls set `RETINA_LIGHTING_INPUT=dense`, `RETINA_LIGHTING_OUTPUT=dense`, `RETINA_GPU_PALETTES=0`, versus the existing Metal defaults, with diagnostics enabled equally on both sides. Clear unrelated `RETINA_*` overrides and keep the native hash, fixture, readiness, compression, budget, workload and host-load evidence with each result. Do not time output comparison/hash work as generation.

For the separate Mac Vulkan attempt, build the same source in release mode with `--locked --lib --features vulkan-portability`, then directly launch the harness with `RETINA_GPU_BACKEND=vulkan`, `VK_DRIVER_FILES=/opt/homebrew/etc/vulkan/icd.d/MoltenVK_icd.json` and `DYLD_LIBRARY_PATH=/opt/homebrew/lib`. The ordinary release Mac native is the Metal build; this feature build is a local diagnostic artifact. Reproduction must allow the actual backend operation to run and report its real error.
