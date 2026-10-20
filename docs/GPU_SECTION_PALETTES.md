# GPU section palettes

Metal now builds and packs eligible final block-section palettes on the GPU,
using final materials already uploaded for lighting. Rust copies the exact
palette IDs and packed block data into NBT. Two matched eight-caller vanilla
comparisons improved complete native throughput by **9.3% and 9.8%**, while NBT
worker time fell about half. Eight-caller Terralith interpretation improved 6.9%.
Two-caller results were mixed; this is not a universal speedup claim.

Logical readback increases approximately **23 MiB per region**. That tradeoff is
worthwhile on the measured Apple M4 Max / Metal workload. Other backends retain
CPU palettes by default. `RETINA_GPU_PALETTES=0` selects CPU encoding;
`RETINA_GPU_PALETTES=1` explicitly enables GPU encoding. The native lighting engine
reads this setting at creation. Existing saves are not rewritten.

[Raw measurements and validation logs](benchmarks/gpu-section-palettes-2026-10-19.json)
retain every comparison, including regressions and unsuccessful readiness setups.
CPU heightmap scanning remains enabled: the earlier
[GPU heightmap trial](GPU_HEIGHTMAP_TRIAL.md) was rejected independently.

## Implementation and bounds

Existing final-section classification proves uniform sections without device
output. Each mixed section receives one 64-lane GPU workgroup. Its 128-entry
workgroup hash table pairs exact 16-bit material IDs with their lowest block
indices. Ranking entries by those indices reproduces CPU first-occurrence palette
order regardless of insertion races. The shader packs Minecraft's non-straddling
long arrays into NBT big-endian byte order using pairs of `u32` words.

Each mixed section has a fixed 717-word output record: one count, 32 material
slots, and up to 342 packed longs. More than 32 distinct materials, or exhaustion
of bounded hash-insertion attempts, produces a zero count and routes the entire
section through the original CPU encoder. No partial palette is used. Uniform
sections skip GPU palette work; the 124 unlit outer chunks, partial repairs, and
paths without GPU lighting retain CPU encoding. Only actual palette entries and
packed longs are saved, so MCA sizes and decoded contents do not change.

The source-section list is appended to the existing material upload. Results are
appended to each tile's lighting readback. This adds one compute pass per nonempty
tile, with no second material upload or host roundtrip. Each pending tile owns its
output layout through completion, including reversed completion, buffer growth,
and profile changes. The existing two pending tiles per region and eight-region
admission limit remain unchanged. Output storage is bounded by dense light size
plus fixed mixed-section records.

F3's device label is now **Light / block packing** because its timestamp span
includes both passes. Lighting host and transfer counters include the additional
work/data; the timing ABI is unchanged. Device costs overlap host/worker costs
and must not be added as independent wall-time stages.

A sizing sample inspected every eighth chunk in one vanilla and one Terralith
region: 3,072 sections per profile. Vanilla had 2,042 uniform sections and only
one mixed section above the cap (33 materials). Terralith had 2,091 uniform
sections and a maximum mixed palette of 28. This informed the cap; it is not a
guarantee for arbitrary datapacks.

## Correctness and compatibility

- 100 ordinary Rust tests passed; 37 GPU/manual tests remain explicitly opt-in.
- The new actual-GPU test checks six dense/compact payload variants across three
  geometries, heights 32/64/80, cropping, buffer growth, different profiles and
  reversed pending-flight completion. Its 138 section results cover uniform,
  4-bit, 5-bit and overflow palettes, hash collisions, and material 65,535.
  Supported palettes/order/packed arrays match an independent scalar reference
  and independent `u64` packing. Expanded light matches original GPU output byte
  for byte. Unsupported palette sizes require CPU fallback.
- Five existing actual-GPU lighting tests passed, including faces, padding,
  sparse/uniform channels, allocation reuse, abandoned maps, streamed region
  assignments, consumer failure and recovery.
- Seven paired whole-generation comparisons matched **55,296 complete decoded
  NBT records**. Another default-enabled final build matched 8,192, for **63,488**
  comparisons. These include complete blocks, lighting, biomes, structures and
  six heightmaps, negative region coordinates, and minimum Y -64.
- `:common:mcaCodecTest` passed using Minecraft's real readers with the final
  library/default selection: 900 lit/124 outer chunks per codec, zlib/LZ4 parity,
  mixed-codec promotion both ways, player edits, partial repair, malformed-cache
  atomicity and eight cache-publication callers. This lifecycle fixture is
  flat/height 64; rich vanilla/Terralith comparisons supply broader content coverage.
- Seven Python benchmark-support tests pass. Investigation exposed a diagnostic
  NBT reader bug: assignment evaluated a compound payload before consuming its
  field name. The parser now reads the name first, with nested-tag and exact
  mismatch-path tests. Previous byte-equality comparisons were unaffected because
  this decoder was only used to explain mismatches.

The default-selection unit test covers Metal, Vulkan, DX12 and explicit overrides.
Actual execution/performance was tested on Metal only. No game was launched.

## Complete generation measurements

Apple M4 Max / Metal, normal optimization-level-3/thin-LTO release builds, two CPU
workers, nice +10, sequential experiments. Seed 123456789, height 384, zlib output,
one warmup at (8,8), measured coordinates beginning (-1,-1). Two-caller rows use a
three-region grid width; eight-caller rows use four. Interpreter preload was
disabled on both sides. Each pair has identical exported profiles and output
file sizes. Rates measure batch wall time through native MCA publication,
excluding Minecraft loading/rendering and subsequent corpus verification.

| Workload | Callers / regions | Order | CPU palettes chunks/s | GPU palettes chunks/s | Change | NBT worker ms/region, CPU → GPU |
| --- | ---: | --- | ---: | ---: | ---: | ---: |
| Vanilla interpreter | 2 / 6 | CPU, GPU | 1,644.0 | 1,888.7 | +14.9% | 70.76 → 33.89 |
| Vanilla interpreter | 2 / 12 | GPU, CPU | 1,777.4 | 1,702.3 | -4.2% | 76.76 → 37.27 |
| Terralith interpreter | 2 / 6 | CPU, GPU | 801.4 | 788.8 | -1.6% | 78.50 → 37.60 |
| Terralith interpreter | 2 / 6 | GPU, CPU | 768.5 | 804.3 | +4.7% | 73.94 → 37.84 |
| Vanilla auto, shader-ready | 8 / 8 | CPU, GPU | 2,289.4 | 2,502.4 | +9.3% | 74.77 → 37.87 |
| Vanilla auto, shader-ready | 8 / 8 | GPU, CPU | 2,311.4 | 2,537.1 | +9.8% | 79.12 → 38.76 |
| Terralith interpreter | 8 / 8 | CPU, GPU | 650.3 | 694.9 | +6.9% | 76.14 → 36.89 |

Vanilla auto reached mixed-ready program status 4 before measurement on both
libraries. Eight callers do not imply eight CPU workers or eight active GPU
dispatches: the terrain pipeline reported two peak outstanding submissions.
The final default-enabled run is correctness evidence, not another time-adjacent
performance pair.

NBT worker savings were consistent (about 49–52%), but GPU/CPU waiting and planning
varied considerably. The repeated eight-caller vanilla gain supports the Metal
default; mixed smaller runs remain a limitation. Terralith's eight-caller result
has only one comparison order.

In the first vanilla pair, logical readback rose from 94,786,801 to 118,523,804
bytes/region; the first Terralith pair rose from 90,373,653 to 114,424,567. Extra
source-list upload was approximately 33 KiB/region. Light/block device packing
rose from about 0.35–0.54 to 1.8–4.3 ms/region across the pairs. These counters
measure logical payload, not physical bus traffic on a unified-memory GPU.

Peak process RSS rose in each pair. Vanilla auto's two pairs were 1.48 → 1.76 and
2.01 → 2.08 GiB; Terralith eight-caller was 1.65 → 1.81 GiB. These peaks include
Python corpus checking and do not isolate native allocations. No memory or
transfer reduction is claimed.

Terralith shader-ready performance remains unverified under the bounded setup.
An actual warmup plus an eight-second readiness wait remained compiling. A
discarded diagnostic waited before triggering lazy compilation and reported
inactive status; a corrected one-chunk trigger plus a twenty-second wait still
remained compiling. Neither produced a measured region batch. These processes
exited with diagnostic failures within the external deadline; the readiness
experiments were not benchmark successes.

## Reproduction

Reference source is `71261da`; library SHA-256:
`fc5f7720839f56307f4f7b7b8a41b45e1442ffcec1dbef040642775a42866233`.
Measured opt-in candidate:
`8c28200b5a1ef92716a6c185b980d3756c35636ac54b4f78ee19f864c8983c33`.
Final default-enabled library:
`a1ac8737dd8305b6102fe02d7aa00dc0f3c3524ef0253c5c2bac7b50e5381633`.
These comparison libraries remain pinned to the original base. After rebasing
the change onto `cf482e8`, the release test build and all 100 ordinary plus six
actual-GPU tests passed again. A fresh integrated release library also passed
the Minecraft reader/lifecycle suite. Integration evidence is recorded separately from
the matched benchmarks. Profile hashes, readiness, stage counters and individual
region samples are retained in the raw evidence.

Build with `cargo build --release --locked --manifest-path native/Cargo.toml`.
Use `scripts/native-region-benchmark.py` with separate retained libraries/output
directories and `--compare` against the paired corpus. Select `--parallel 8
--count 8 --grid-width 4 --warmups 1 --interpreter-preload disabled`; vanilla auto
also uses `--program-execution auto --readiness shader-ready
--specialization-timeout 8`. Interpreter pairs use `--program-execution interpreter`
and default driver-warm readiness. On current source compare
`RETINA_GPU_PALETTES=0` against the Metal default or explicit `=1`.

Build Rust tests with `cargo test --release --locked --manifest-path
native/Cargo.toml --lib --no-run`. Run the produced binary normally, then run
`gpu_section_palettes_keep_order_bits_overflow_and_light --ignored
--test-threads=1` explicitly. Existing `lighting::tests --ignored --skip
pipeline_replay_timing --test-threads=1` covers correctness without the manual benchmark.

Minecraft validation uses `./gradlew :common:mcaCodecTest
-PretinaNativePath=<retained-library> -x buildNative -x packageNative`, with two
Gradle workers and two active JVM processors. Python validation uses `python -m
unittest discover -s scripts -p test_benchmark_support.py`, with its LZ4 dependencies.
All native jobs used two Cargo jobs/two CPU workers, nice +10, 60-second build and
30-second execution deadlines with process-group cleanup. No run hit the external
deadline, and no game or GitHub Actions workload was launched.
