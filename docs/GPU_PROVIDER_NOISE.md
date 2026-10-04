# Registered GPU provider-noise sampler

Minecraft 26.3's noise-based block providers use initialized `NormalNoise`
stacks, rather than the `BIOME_INFO_NOISE` simplex sampler used by placement
counts. `ProviderNoiseProfile` exports each stack's actual Perlin permutations,
offsets, frequencies and float amplitudes after Minecraft initializes it with
the provider's registered seed. The registered spatial scale is incorporated
into each layer frequency. Fast and slow dual-noise stacks have separate entries.
Initialization happens once while exporting the loaded profile; Java and Rust
do not simulate spatial provider noise during generation.

Vanilla exports four distinct stacks/eight Perlin layers. The current Terralith
profile exports 24 stacks/134 layers. Existing feature recipes retain their
previous representation for this foundation milestone. The sparse sampler is
available through `TerrainEngine::provider_noise` and the diagnostic C/FFM bridge;
ordered feature replay still needs to consume its results. This milestone does
not yet replace the legacy noise-based material choices in generated terrain.

## Integer coordinates and resident batches

The GPU receives sixteen bytes per query: signed integer X/Y/Z and a resident
program ID. Perlin's lattice permutation repeats every 256 cells. Frequency and
offset phases are encoded as 48-bit values with forty fractional bits, modulo
that period. WGSL multiplies the frequency phase by the signed coordinate using
32-bit limbs before converting the bounded fractional coordinates to floats.
This avoids rounding far block coordinates to `f32` before scaling, preserves
negative coordinates and needs no GPU `f64` or backend-specific arithmetic.
Minecraft's normal Perlin coordinate wrap has a period divisible by 256, so it
does not require another spatial wrap field.

The shader then evaluates the actual registered gradients, quintic fade,
trilinear interpolation and ordered weighted stack sum. The 40-bit coefficient
quantization and GPU float arithmetic remain approximations. Tiny differences
near a material-selection threshold must be treated as boundary tolerance when
the provider selectors are connected. This is not a promise of CPU seed parity
or cross-backend bit identity.

One table upload holds all stacks for the native profile ID. Later batches reuse
it. The sampler shares the sparse count path's point/output/readback buffers,
bind-group layout, timestamp resources and serialized GPU ownership. Active-length
bindings prevent a small batch from sampling stale points in a larger reused
buffer. Empty batches skip dispatch. Each query reads back one four-byte float;
timestamps add sixteen bytes per batch when available. No full provider grid or
extra terrain readback is introduced by this API.

The provider compute pipeline compiles lazily on its first query, independently
of the large density/material specialization programs. The first observed new
shader query took 138.16 ms including compilation, table upload and buffer setup;
subsequent process/profile first queries took 4–14 ms with warm driver caches.
These startup costs are separate from warmed batch timings.

## Timing ABI and validation

Timing ABI 6 appends **Provider noise** as stage 25: 26 stages, a 240-byte
snapshot and a 280-byte detailed region report. The legacy 40-byte region report
is unchanged. A separate measured flag enables the colored F3 row only when
provider timestamps exist. The device timer overlaps host/planning time and is
not added to region latency. Native, Java, payload serialization, F3 metrics tests
and the region benchmark reader use the same stage layout.

`providerNoiseTest` collects actual configured and inline placed features,
including registered block-state-provider holder references. It compares all
exported stacks with Minecraft's own `Noise.get` implementation. Extra fixtures
cover four seeds, three scales, three octave bases, normalization, absent octave
weights and an entirely zero stack. Equivalent initialized stacks are deduplicated.

The reference corpus contains 32,768 vanilla and 90,112 Terralith XYZ queries.
Half are near spawn and half range to ±30 million blocks; the first points also
exercise neighboring integer positions beyond `f32`'s exact range. Maximum errors
are 0.00001896 and 0.00003812 respectively, including the far-coordinate cases.
The assertions scale a fixed tolerance by the stack's actual absolute amplitude
sum. Small/large/empty batches, resident input reuse, repeated batches, eight calls
from four workers, request-order stability, terrain-seed independence and provider
device timestamps across the C ABI pass. Count and provider queries alternate
through their shared buffers and concurrent calls without changing either result.
Invalid program IDs return the native argument error without GPU transfers.

Twenty identical resident batches measure the actual sampler on Metal / Apple
M4 Max, excluding the Minecraft reference computation:

| Corpus | Queries/batch | Mean host ms/batch | Mean GPU device ms/batch | Upload bytes/batch | Readback bytes/batch |
| --- | ---: | ---: | ---: | ---: | ---: |
| Vanilla plus fixtures | 32,768 | 0.389 | 0.0388 | 524,288 | 131,088 |
| Terralith plus fixtures | 90,112 | 0.653 | 0.1411 | 1,441,792 | 360,464 |

These are the final shared-buffer run's twenty-batch means. Earlier warm runs
measured 0.708–0.867 ms vanilla / 0.927–0.963 ms Terralith host time and
0.0112–0.0159 ms / 0.0385–0.0436 ms device time; system load and GPU clocks affect
these small batches. The table does not establish a feature-planning speedup.

The current profiles' other exported values match the prior provider milestone
exactly. The shared count sampler retains its Minecraft reference checks:
11,008 vanilla / 54,528 Terralith queries pass; the 50 Terralith differences are
within the existing float-boundary tolerance. This measures the new sampler's
cost, not a whole-region or CPU-to-GPU speedup. Vulkan/DX12 use the same shader
but hardware runtime measurements remain unavailable here.

## Whole-region compatibility measurements

The retained `5b653f4` native library and this candidate each generated twenty
adjacent regions, after two warmups, with complete vanilla and Terralith profiles
including structures and decorations. Runs used one or two concurrent region
calls, forced the GPU interpreter and ran sequentially at `nice -n 10`; compilation
and Java tests were not running alongside them. The full exported profiles differ
only by the new stack catalog. Both builds read the same current profile files.

| Profile / simultaneous calls | Retained mean region ms | Candidate mean region ms | Retained chunks/s | Candidate chunks/s |
| --- | ---: | ---: | ---: | ---: |
| Vanilla / 1 | 258.35 | 247.90 | 3,959.6 | 4,125.0 |
| Vanilla / 2 | 467.46 | 439.52 | 4,375.7 | 4,650.8 |
| Terralith / 1 | 554.01 | 523.28 | 1,847.4 | 1,955.9 |
| Terralith / 2 | 1,102.06 | 1,019.85 | 1,856.9 | 2,006.9 |

These differences do not establish a throughput improvement: actual feature
replay does not call the new sampler yet, its region device counter remains zero,
and system load/run order were not controlled. Retained measurements from the
previous milestone were substantially slower, which is why the retained library
was measured again rather than attributing that difference to this change.

Initialization measured 40.5–55.0 ms. Profile registration was 514.8–524.8 ms
vanilla and 1,019.0–1,188.8 ms Terralith. Peak process RSS ranged from 920.0 to
1,201.8 MB vanilla and 1,067.5 to 1,124.7 MB Terralith. Twenty region files totaled
153,907,200 and 131,649,536 bytes respectively, identical between builds and call
counts. Per-region transfers remained approximately 14.27 MB upload / 45.62 MB
readback vanilla and 12.39 MB / 41.9 MB Terralith, including existing terrain,
count and ore work. Resident provider stacks are uploaded only by provider queries.

All 163,840 chunk-NBT comparisons passed: four candidate runs against retained
prior outputs, and four freshly measured retained runs against candidate outputs.
The broader build, native unit/GPU, block-feature, region and preview harnesses
also passed, including MCA decode, chunk/MCA blocks and heightmaps, DH temporary
regions, promotion, concurrent edits, eviction, partial saves and failure cleanup.
The tested and packaged native SHA-256 is
`04eb265ecdc6e0a7d87fb46eb5056f8252e0b71970fc66353ad92986f2c56c4b`.

Reproduction:

```sh
./gradlew build nativeUnitTest nativeGpuTest providerNoiseTest decorationCountTest \
  blockFeatureTest previewTest regionTest \
  -PtestPack=/Users/cyberpwn/Downloads/Terralith_v2.6.5+26.3.zip \
  --no-parallel --max-workers=1
```

The exported full profiles and sampler measurement JSON files are under
`build/provider-noise-{vanilla,terralith}*.json` (ignored). Eight whole-region
run outputs and measurements are under `build/goal-baseline/provider-noise/`;
the reproduction scripts there use `scripts/native-region-benchmark.py` with
`--count 20 --warmups 2 --parallel 1` or `2`, `--program-execution interpreter`
and `--compare` against the other build's MCA files.

## Next integration requirements

Spatial material selectors must preserve each provider's choice equations and
random consumption. `noise` and `dual_noise` do not consume feature randomness;
`noise_threshold` consumes different draws on its low/high branches. Ordered
feature replay must resolve those results before publishing final commands,
retain live survival/canopy checks and avoid one dispatch per block or attempt.
The GPU API now provides the loaded-data sampler needed for that integration.
Property copying, further filters, per-expression density interpolation, cold
specialization cost and additional measured Rust scan work also remain active.
