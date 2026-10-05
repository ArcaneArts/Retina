# Resident interpolation-field lattices

Registered interpolation inputs can now retain their own globally aligned GPU
lattices for a terrain batch. Samples stay on the imported X/Z and Y lattices.
When the terrain lattice skips finer input nodes by an exact integer multiple,
the cache retains that aligned subset rather than precomputing unused nodes.
Interpolation still uses the field's original cell size; missing corners run the
direct evaluator. Fields at the same dependency depth share a dispatch; dependent fields run in
subsequent passes. The GPU composes nested inputs using already-generated child
samples. Neither the field samples nor a dense volume enters a CPU readback.

A small per-request descriptor table sits before the existing final-density
scratch. Field values sit after the batch's existing density, height, lake and
horizontal-expression buffers. Headers contain offsets, origins and dimensions;
field spacing and dependency depth stay in resident registry bytecode. Cache
stride multiples are part of the per-request header. Inputs
remain resident between batches, and scratch grows only when necessary. Metal,
Vulkan and DX12 use the same WGSL stages and bindings.

An interpolation corner uses the cache only when its exact queried f32 position
matches a stored global node. This matters for negative coordinates, slices,
mismatched nested lattices and far coordinates where f32 merges integers.
Out-of-bounds or unaligned queries run the original GPU graph. Cache planning
excludes material-context inputs and callers which depend on those inputs. Only
fields referenced by climate, terrain or aquifer graphs, including their child
fields, participate. Material emission deliberately disables density scratch,
since its delayed immutable job can run after another region reuses that scratch.

Actual device buffer and dispatch limits, along with representable integer
endpoints, bound optional cache allocation. A field
which cannot fit uses its direct GPU sampler. It does not stop generation or
switch the world to Minecraft's generator. The request and readback ABIs remain
unchanged. Cave, aquifer and material shaders explicitly mask the request's lower
byte when reading tile width, preserving high-bit request flags. F3's existing
height/climate device stage includes interpolation prepasses. Transfer diagnostics
include the small uploaded header tables.

## Scope

This provides reuse of independent field inputs. The terrain pipeline still
samples the final composed graph on its existing shared final-density lattice,
then interpolates that lattice for surfaces and cave classification. Per-voxel
composition after individual field interpolation remains open, as does the cost
of cold generic and specialized shader compilation. This cache milestone must
not be interpreted as completing either requirement. Existing saved terrain and
Distant Horizons promotion retain their existing behavior.

An [experimental block-position composition path](GPU_DENSITY_COMPOSITION.md)
now reuses these fields and passes analytic samplers. It remains disabled by
default because measured whole-region costs regress; efficient production
composition and cold compilation remain required work.

## Validation and measurements

The cache regression compares cached roots with a reference GPU interpreter whose
cache flag is explicitly cleared. It exercises both the compact interpreter and
specialized evaluators, child horizontal reuse, three request seeds, negative
coordinates, fractional and out-of-cache queries, mismatched nested cells and
far coordinates beyond f32's exact integer range. Independent noise-corner and
Minecraft analytic sampler checks remain in the existing interpolation suite.
Native tests cover each field's layout and disabling caches for contextual
inputs, insufficient buffer space and dispatch capacity.

The initial dense-field variant measured 508.75 → 540.42 ms per Terralith
interpreter region, with no meaningful warmed-specialization benefit. This
exposed unnecessary 4-block vertical input samples when terrain only consumed
8-block nodes. The final cache uses the aligned subset described above.
Measurements of both variants are retained under
`build/goal-baseline/interpolation-cache/`. The A/B diagnostic uses the same
library and profiles:

```sh
python3 scripts/native-region-benchmark.py \
  --library build/native-target/release/libretina_worldgen.dylib \
  --profile build/interpolation-vanilla.json --count 20 --warmups 2 \
  --program-execution interpreter --interpolation-cache disabled \
  --out build/interpolation-cache-before
python3 scripts/native-region-benchmark.py \
  --library build/native-target/release/libretina_worldgen.dylib \
  --profile build/interpolation-vanilla.json --count 20 --warmups 2 \
  --program-execution interpreter --interpolation-cache enabled \
  --compare build/interpolation-cache-before \
  --out build/interpolation-cache-after
```

`RETINA_INTERPOLATION_CACHE=0` disables reuse for diagnostics; it preserves the
same graphs, individual interpolation semantics and selected GPU execution mode.
The benchmark runner records this setting when its explicit option is supplied.

The final build passes `build gpuTest regionTest previewTest structureTest
blockFeatureTest datapackTest`: 47 native unit checks plus the separately run
actual GPU, nested interpolation, material, aquifer, biome, terrain, structure and
feature suites. Full vanilla/Terralith cached roots also match direct reference
float bits in interpreter, specialized and far-coordinate runs. The DH suite
covers the 1024-region cache, full/partial promotion, parallel requests, existing
edits and save isolation. The village floor regression again removes 842 invalid
grass blocks while retaining 8,271 supported ones.

Twenty-region measurements below use actual vanilla/Terralith profiles with
structures and decorations, Metal on Apple M4 Max, seed 123456789 and two warmups.
The final compact variant runs cache-on before cache-off, reversing the earlier
dense-field pair order. Each pair uses the same final library. Builds, tests and
benchmarks run sequentially; other desktop activity is uncontrolled.

| Profile / mode | Cache off mean ms | Cache on mean ms | Off chunks/s | On chunks/s |
| --- | ---: | ---: | ---: | ---: |
| Vanilla / interpreter | 274.91 | 264.20 | 3,721 | 3,872 |
| Terralith / interpreter | 546.97 | 521.96 | 1,871 | 1,961 |
| Vanilla / warmed specialized | 203.40 | 200.83 | 5,028 | 5,092 |
| Terralith / warmed specialized | 338.05 | 341.09 | 3,027 | 3,000 |

These pairs show 3.9% / 4.6% lower interpreter latency. Specialized differences
are about -1.3% / +0.9%; this is not a consistent whole-region specialization
speedup. The GPU height/climate stage decreases from 17.58 → 16.41 ms for vanilla
and 36.22 → 33.17 ms for Terralith in interpreter mode. Specialized stage times
are still much higher, 51.79 → 50.64 / 150.65 → 148.69 ms, identifying a separate
optimization target rather than establishing that specialized density is faster.

Two concurrent callers with the final cache achieve 4,119 / 2,099 chunks/s in
interpreter mode (496.50 / 974.89 ms mean overlapping request latency), and
5,921 / 3,335 chunks/s in warmed specialized mode (342.78 / 593.68 ms). Throughput
uses total measured wall time; overlapping request latency is not inverted.

Serial interpreter peak RSS changes from 870 → 886 MiB for vanilla and
1,040 → 1,046 MiB for Terralith. Specialized serial processes peak at
1,034 → 1,054 / 1,688 → 1,740 MiB, including compiler and driver memory.
Concurrent cache-on peaks are 959 / 1,078 MiB interpreted and 1,007 / 1,665 MiB
specialized. These are whole-process peaks, not isolated cache allocation sizes.

Uploaded headers add about 5.5 KB per vanilla region and 21.3 KB per Terralith
region in the serial measurements, including sparse query batches. Total dynamic
upload/readback is about 14.30/45.62 MB for vanilla and 12.62/41.95 MB for
Terralith. There is no field-sample readback. Minor concurrent readback differences
reflect existing sparse-query coalescing. Twenty-region file totals remain exactly
153,911,296 / 131,751,936 bytes in both modes.

Across dense, compact, serial, concurrent and automatic-mode measurements,
452,608 complete decompressed chunk NBT comparisons pass. This includes two
regions regenerated after background specialization completed. All compare
coordinates and seeds, palettes, final heightmaps, biomes, structures and other
serialized data, rather than only selected heights.

Initialization is 40–49 ms with warmed shared shaders, and native profile
registration about 506–559 / 988–1,095 ms. First changed-source compact
interpreter activation measured 5,790 ms for Terralith, including synchronous
creation of the new generic depth/capacity pipeline set; later first regions are
about 314–367 / 629–675 ms. These observations do not eliminate cold startup.
Forced-specialization first regions measured 17.58 / 36.96 seconds for changed
shader identities, versus about 0.70 / 2.98 seconds after driver-cache warming.
Forced mode intentionally waits; production automatic mode keeps the interpreter
available while the profile compiles.

No-warmup automatic runs start their first regions in 289 / 471 ms after prior
validation and benchmark runs warmed the driver cache. They average 204.27 /
398.64 ms over twenty regions, at 5,007 / 2,567 chunks/s while transitioning into
specialization. Background compilation takes 509 / 2,535 ms in these warm-cache
observations; process peaks are 1,021 / 2,020 MiB. This is not an empty-system-cache
startup measurement or proof that the cold compilation requirement is complete.

Final validation log: `build/interpolation-cache-validation-compact.log`.
Raw profiles, both measured variants, hashes and combined results are retained in
`build/goal-baseline/interpolation-cache/`. The packaged JAR contains the exact
native library used for the compact measurements.
