# Mixed GPU stage selection

On Metal, automatic generation now keeps the compact GPU interpreter for
registered interpolation inputs, density nodes, height/climate nodes and surface
extraction. Specialized pipelines handle the remaining world, cave, aquifer and
material stages after background compilation finishes. These operations still
execute on the GPU; Rust does not simulate their noise.

This follows matched whole-region measurements. Specializing every stage was
slower for density/climate, while specialized materials and caves were faster
overall than a wholly interpreted generator. Stage selection is fixed before
each submission's layout and encoding. Both paths share the resident registry
bytecode, interpolation atlas, scratch bindings and readback formats. The
specialized horizontal prepass remains available to its consumers. A compiler
finishing during a request cannot change that request's selected pipelines.

The later [specialized input writers](GPU_INTERPOLATION_WRITERS.md) refine this
selection: ready Metal bundles now compile their interpolation prepasses, while
the other density/height/climate stages retain the compact interpreter. Repeated
whole-region measurements and the independent diagnostic are documented there.

The compact interpreter now also reads those resident horizontal fields on
exact covered X/Z nodes, using schedules that skip their unused dependencies;
see [column reuse](GPU_COLUMN_INTERPRETER.md) for the later implementation,
matched measurements and fallback behavior. This leaves mixed stage selection
intact and does not enable block-position composition by default.
[Optional local aquifer column reuse](GPU_AQUIFER_COLUMN_REUSE.md) shares
horizontal spline values within specialized preliminary-surface searches.
It adds no stage or readback and retains this mixed execution strategy.

Metal defaults to this mixed selection. Vulkan and DX12 retain the previous
selection because they have not been measured here. The diagnostic environment
variable `RETINA_SPECIALIZED_TERRAIN=1` selects all-specialized terrain after
compilation; `=0` selects mixed stages. An explicitly interpreted profile remains
wholly interpreted. These choices do not alter saved generator configuration or
the imported graph semantics.

The compiler now omits the four specialized terrain/climate entrypoints and the
interpolation prepasses when those stages will use the interpreter. For these
actual vanilla and Terralith profiles, that removes five unused pipelines per
profile. The exact source plus terrain-entrypoint selection form the cache key;
source equality alone cannot accidentally reuse an incomplete pipeline bundle.
All other specialized entries and their horizontal scratch allocation remain.
The interpreter depth/capacity bundle is kept available even after specialization
finishes.

The existing 64-byte program-diagnostics ABI adds status `4` for ready mixed
execution. Status `2` still means all-specialized execution. Compilation remains
status `1`, failure status `3`, and interpreter-only status `0`. F3 displays
`mixed (terrain interpreter)` when ready. Compilation cost, source/graph counts,
resident horizontal fields and measured transfers keep their previous reporting.
Graph counts describe the generated WGSL, including functions which the driver
can discard from individual entrypoints; they are not hardware instruction counts.

## Measurements

Apple M4 Max / Metal, seed 123456789, actual complete vanilla/Terralith profiles
with structures and decorations, two warmups and twenty adjacent regions per
row. Builds, tests and benchmarks ran sequentially. Other desktop activity was
uncontrolled. Both stage selections use the same library and loaded profile.
Selection order is reversed in the serial repeat.

| Profile / run | All-specialized mean ms | Mixed mean ms | All-specialized chunks/s | Mixed chunks/s |
| --- | ---: | ---: | ---: | ---: |
| Vanilla / first pair | 204.26 | 167.46 | 5,007 | 6,105 |
| Vanilla / reversed pair | 222.01 | 185.36 | 4,606 | 5,515 |
| Terralith / first pair | 331.82 | 228.41 | 3,084 | 4,478 |
| Terralith / reversed pair | 348.27 | 234.10 | 2,938 | 4,369 |
| Vanilla / two callers | 358.48 | 300.87 | 5,702 | 6,793 |
| Terralith / two callers | 645.55 | 402.45 | 3,170 | 4,990 |

These paired runs show 20–22% higher serial throughput for vanilla and 45–49%
for Terralith. The concurrent pairs improve about 19% / 57%. Concurrent
throughput uses total measured wall time; overlapping request latency is not
inverted to derive throughput.

GPU height/climate time per region changes from 52.31 / 53.38 to 18.97 / 19.15 ms
in the vanilla serial pairs, and from 144.88 / 145.67 to 41.79 / 39.40 ms for
Terralith. This includes the horizontal and interpolation prepasses. Whole-region
results, rather than these device-stage savings alone, justify the new default.

The final implementation also omits unused specialized pipelines. A fresh
twenty-region pair measures 215.78 → 175.61 ms vanilla and 345.58 → 227.59 ms
Terralith, or 4,723 → 5,822 / 2,961 → 4,494 chunks/s. Final mixed two-caller
checks achieve 7,202 / 5,544 chunks/s, at 281.57 / 364.66 ms mean overlapping
request latency. These additional observations corroborate the stage-selection
gain; they do not isolate the effect of compiling fewer unused shaders.

Final serial process RSS is 993 → 1,023 MiB vanilla and 1,574 → 1,689 MiB
Terralith; final mixed concurrent processes peak at 1,037 / 1,681 MiB. Process
RSS includes compiler, driver and comparison-reader memory. Keeping interpreter
and specialized pipeline bundles does not establish a memory reduction.

Dynamic transfers remain approximately 14.30 / 45.62 MB uploaded/read back per
vanilla region and 12.62 / 41.95 MB per Terralith region. No density fields or
interpolation cache samples enter a new readback. Small differences between
serial, concurrent and automatic runs reflect existing sparse-query coalescing.
Twenty-region MCA totals remain exactly 153,911,296 / 131,751,936 bytes.

No-warmup automatic checks of the final build start their first native regions
in 308 / 459 ms after native initialization and registration. They average
188.45 / 306.72 ms over twenty regions while transitioning to specialization,
at 5,426 / 3,335 chunks/s. Shared and profile shaders were already driver-warm:
initialization takes 46 / 50 ms, registration 563 / 1,083 ms and background
profile compilation 471 / 2,355 ms. Peaks are 1,015 / 1,831 MiB. A region
regenerated after compilation matches all 1,024 earlier chunk records for each
profile. These observations are not empty-driver-cache startup measurements.

Eight additional automatic runs force new profile shader identities with one to
four identity clamps on each root, including interpolation inputs. Selection
order is reversed in the second pair. The shared interpreter shaders are already
warm. Each run generates twenty regions while compilation is pending, compares
their complete NBT with the unchanged profile, waits for compilation to finish,
then compares another regenerated region.

| Profile / pair | Selection order | All-specialized compile seconds | Mixed compile seconds |
| --- | --- | ---: | ---: |
| Vanilla / 1 | All, mixed | 27.01 | 19.69 |
| Vanilla / 2 | Mixed, all | 26.99 | 20.34 |
| Terralith / 1 | All, mixed | 112.77 | 97.15 |
| Terralith / 2 | Mixed, all | 121.02 | 96.54 |

First native region requests are 293–327 ms vanilla and 473–502 ms Terralith,
after 40–43 ms shared initialization and 519–553 / 1,040–1,093 ms registration.
While the compiler is active, mean region latency is 263–275 / 528–541 ms at
3,720–3,886 / 1,890–1,937 chunks/s. All twenty regions finish before the background
compiler in each run. Peaks are 1,041–1,081 / 1,814–1,978 MiB, including compilation
and comparison. These runs show that generation continues during expensive cold
profile compilation. Mixed compilation is lower in these observations, but the
different clamp counts and uncontrolled host load prevent attributing their exact
differences solely to the five omitted pipelines. Cold generic shader activation
and the remaining substantial profile compiler work are not eliminated.

## Validation and reproduction

`build gpuTest regionTest previewTest structureTest blockFeatureTest datapackTest`
passes with the new Metal default: 47 native unit checks and actual GPU,
coordinate/interpolation, material, aquifer, region, provider and feature checks.
The suite verifies both generation modes, negative/boundary coordinates, final
metadata, temporary MCA promotion including partial/placeholder cases, existing
edits and save isolation. Its grass regression removes 842 unsupported grass
blocks after road writes and retains 8,271 supported grass blocks. F3 formatting
checks include the mixed status. The log is `build/hybrid-terrain-validation.log`.

All measured pairs compare complete decompressed NBT with the retained
interpolation-cache milestone, including palettes, biomes, final heightmaps,
structures and feature data. Across twenty-eight runs, all 583,680 complete
chunk comparisons pass, including ten regions regenerated after automatic
compilation. Artifacts, retained profiles/library and hashes are
under `build/goal-baseline/hybrid-terrain/`. The final native library is
`8264410ba71eab3e08a40e5da515d3e28ee5be093bf9f8cdf765c0fa7c12eda8`.

```sh
python3 scripts/native-region-benchmark.py \
  --library build/native-target/release/libretina_worldgen.dylib \
  --profile build/goal-baseline/hybrid-terrain/vanilla.json \
  --program-execution specialized --terrain-execution specialized \
  --count 20 --warmups 2 --out build/all-specialized
python3 scripts/native-region-benchmark.py \
  --library build/native-target/release/libretina_worldgen.dylib \
  --profile build/goal-baseline/hybrid-terrain/vanilla.json \
  --program-execution specialized --terrain-execution interpreter \
  --count 20 --warmups 2 --compare build/all-specialized --out build/mixed
```

The benchmark option records its stage selection and accepts both ready
diagnostics statuses when checking automatic compilation. Production automatic
mode keeps Retina's GPU interpreter while the profile compiles or compilation
fails. The shared final-density terrain-composition approximation and cold
generic shader activation remain separate ongoing work; this measured stage
choice does not complete either requirement.
