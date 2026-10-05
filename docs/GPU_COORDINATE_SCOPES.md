# Registered coordinate scopes on the GPU

Minecraft 26.3's `SliceFunction` replaces one coordinate before sampling its
entire child expression. Retina previously discarded this wrapper. For example,
`slice(y=64, noise(...))` sampled noise at the caller's Y instead of the registered
Y. A slice of a referenced registry function had the same problem.

The Java exporter now compiles each expression under an immutable coordinate
scope. Expression memoization includes that scope. An inner slice of the same
axis overrides the outer slice; other axes retain their earlier replacements.
The scope is restored before compiling the surrounding expression and other
graph roots. Constants and semantically identical emitted nodes still share DAG
entries.

Scoped leaves lower to explicit coordinate values, scaled/shifted noise inputs,
gradients and blended-noise inputs in the existing resident bytecode. Arithmetic,
branching, splines and referenced functions inherit the scope. Both the GPU
interpreter and specialized SSA programs execute these operations. Shift-A and
Shift-B retain Minecraft's distinct coordinate permutations.

The horizontal-cache dependency analysis follows the actual coordinate inputs.
A noise with a replaced Y can become a reusable X/Z field; replacing X or Z does
not incorrectly eliminate a remaining Y dependency. A unit regression checks
both cases and sharing between graphs. The resident index, material palette,
column readback and timing ABIs are unchanged. There is no new dispatch or output
record for coordinate scopes. F3's existing graph/compile/cache diagnostics cover
the resulting program.

The blended-noise approximation now has one shared GPU helper for interpreter,
specialized and scoped callers. Direct-root testing exposed five small rounding
differences when the same body was compiled separately in the interpreter and
specialized graph. The shared helper removes those differences in the exercised
cases. It retains Retina's existing approximate noise, not Minecraft's CPU noise.

## Validation

`coordinateTest` loads actual 26.3 registries and parses its analytic fixtures
with Minecraft's `DensityFunction.CODEC`. Minecraft's own compiled sampler
provides the reference for six scoped/nested/arithmetic fields. On the actual
Metal device, 6,144 columns match their expected heights, including negative
coordinates; 3,888 distinguish the new behavior from discarding the slices.
Independent chunk queries and generation with the decoration halo agree.

`nativeCoordinateGpuTest`, included in `gpuTest`, checks every root of the
exported fixture against the interpreter bit for bit. Analytic roots also match
Minecraft's sampler to a 0.0001 float tolerance. Noise roots match the same GPU
noise sampled at manually replaced coordinates, including all three shift modes,
shift expressions, blended noise and a referenced Overworld continentalness
function. This avoids substituting a CPU implementation for GPU noise.
The additional cache/far-coordinate run checks aligned, fractional and outside
queries, multiple seeds and coordinates beyond f32's exact integer range.

```sh
./gradlew coordinateTest nativeCoordinateGpuTest --no-parallel
RETINA_PROGRAM_PARITY_PROFILE="$PWD/build/registry-coordinate-profile.json" \
  RETINA_PROGRAM_PARITY_CACHE=1 RETINA_PROGRAM_PARITY_FAR=1 \
  cargo test --manifest-path native/Cargo.toml --release --locked --lib \
  --target-dir build/native-target specialized_roots_match_interpreter_on_real_gpu \
  -- --ignored --nocapture
```

Actual vanilla and Terralith exports remain identical to the retained profiles:
neither loaded graph relies on the formerly discarded slices. Custom registered
graphs which use slices now change newly generated terrain as their scopes
require. Previously saved blocks remain intact. New profile bytes participate in
native profile identity, and compiled source identifies shader pipelines.
Temporary regions belong to the loaded generator/profile instance and use a
fresh directory, so a prior session's preview files cannot retain obsolete scopes.

The full build and GPU, region, biome, datapack, preview, geology, feature,
structure, shoreline and landscape suites passed. Additional actual-profile
tests compared 81,792 root pairs bit for bit with horizontal caching and far
coordinates enabled. Multi-region comparisons checked 145,408 complete decoded
chunk NBT records against `9c7e3e6`, including generation before and after
specialization completed. Both full profiles include structures and decorations.
The preview suite covers the 1,024-region cache, promotion, existing edits and
serialized metadata.

## Measurements

Apple M4 Max / Metal; seed 123456789; 20 regions per run, five warmups; the same
exported vanilla and Terralith data, including structures and decorations.
Baseline is retained `9c7e3e6`; both sides use strict specialized execution for
this comparison. Two-call runs issue concurrent region requests. Throughput is
total chunks divided by wall time; region time is mean request latency, so
concurrent throughput does not equal 1,024 divided by that latency.

| Workload | Before chunks/s | After chunks/s | Before mean region ms | After mean region ms |
| --- | ---: | ---: | ---: | ---: |
| Vanilla, one call | 3,522 | 3,939 | 287.83 | 259.14 |
| Terralith, one call | 2,384 | 2,605 | 428.63 | 392.29 |
| Vanilla, two calls | 2,652 | 2,242 | 763.94 | 909.13 |
| Terralith, two calls | 2,648 | 2,997 | 748.31 | 681.06 |
| Vanilla, two calls, reversed repeat | 2,781 | 2,745 | 734.32 | 728.98 |

A separate game and other desktop processes were active throughout these runs.
The slower concurrent vanilla run retained similar GPU device timings while
CPU planning, assembly, encoding and compression times rose broadly. Its
reversed repeat differed by about 1.3% in throughput. These mixed measurements
do not establish a consistent speedup or a clean regression bound. This
milestone repairs graph semantics; it adds no scope-specific dispatch/readback.

Peak RSS across the matched runs was 806–873 MiB for vanilla and 1,237–1,265 MiB
for Terralith. Dynamic upload/readback per region remained approximately
13.58/25.86 MiB and 11.74/22.21 MiB respectively; small differences reflect
request coalescing. All 20-region outputs retained their exact total file sizes:
135,274,496 bytes for vanilla and 112,930,816 bytes for Terralith.

The first changed-source native initialization took 5,088.71 ms, versus 79.79 ms
for the retained baseline. Later initializations took 78–198 ms. The first strict
profile compilation took 15.98 s for vanilla and 61.95 s for Terralith; subsequent
strict runs took 1.23–7.16 s. These costs are reported separately from warmed
throughput and must not be hidden by warmups. The strict test setting waits for
compilation by design; production's `auto` execution keeps the interpreter
available while the profile pipeline compiles.

To exercise a pending compilation, two fresh-source profiles added a neutral
clamp to the first three roots, with no warmups and `program_execution=auto`.
Vanilla produced its first region in 346.05 ms and all 20 regions at 2,537
chunks/s (402.63 ms mean); Terralith produced its first in 297.06 ms and all 20 at
2,267 chunks/s (450.95 ms mean). Compilation was still pending at the end of
each measurement: it completed in 25.87/44.02 s, after separate waits of
15.97/33.64 s. All 40,960 measured chunks matched baseline NBT, and the additional
2,048 chunks regenerated after compilation also matched. Those cold automatic
runs are semantic/startup checks, not like-for-like throughput comparisons with
the warmed strict runs.

Raw local artifacts are under `build/goal-baseline/coordinate-*` (ignored).
Each benchmark directory contains `measurements.json` and the generated MCAs;
the retained baseline library is `9c7e3e6-native.dylib`. Profiles are
`coordinate-vanilla.json`, `coordinate-terralith.json` and the two
`coordinate-cold-*.json` variants. The full integration result is recorded in
`coordinate-validation-final.log`.

## Remaining graph approximations

This change fixes coordinate replacement. A subsequent
[interpolation milestone](GPU_INTERPOLATION_SCOPES.md) preserves distinct
trilinear operators and their nested coordinate scopes in direct GPU samples.
The shared world-aligned density lattice still approximates final terrain
composition. Registered simplex/Perlin and old blended noise remain GPU
approximations; unsupported operations still appear in export diagnostics.
Layered surfaces and local aquifers are documented in their separate milestones.
