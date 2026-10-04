# Registered vertical decoration placement

New registry exports retain `height_range`, `environment_scan` and
`random_chance` placement modifiers. Height distributions include constant,
uniform, biased-to-bottom, very-biased-to-bottom, trapezoid and recursive weighted
providers, with absolute, above-bottom and below-top anchors. Scans retain the
loaded direction, maximum steps, target predicate and allowed-search predicate.
The `solid` block predicate is resolved against actual registered palette states.

These are random placement operations in parallel Rust. Spatial noise remains
on the GPU. Scans read the complete carved material runs and the ordered planning
overlay, including air, local fluids and earlier feature writes. A scan requires
the initial allowed predicate, tests each target before moving, stops at build
limits, and tests its final target after reaching the step limit or a failed
allowed predicate. Those details follow Minecraft 26.3's implementation.

New exports also enable `decoration_biome_3d`. Recipe discovery unions the GPU
quart biomes through the chunk's full vertical range with its precise surface
column biomes. This union is computed once per anchor and shared across GPU
count-query rounds and geometry planning. The final biome filter reads the GPU
biome at the actual candidate position underground. Within the upper twelve
terrain blocks it retains the precise column biome used by the shoreline material
coating. No extra GPU dispatch, biome upload or readback is needed.

Underground biomes are no longer excluded from the registered vegetation-step
export. Vanilla now exports 162 recipes across 56 biomes; Terralith exports 440
across 151. Both retain full structures, ores and cave settings. Lush caves' loaded
downward cave-vine columns now own their placement budget, replacing the older
extra per-roof vine approximation. Moss/clay/other cave dressing remains an
approximation until its corresponding registered patch adapter is implemented.

Saved native profiles without the new flag preserve column-only biome discovery
and restrictions. Existing generated terrain is preserved. Both per-chunk and MCA
generation use the same planner, including DH temporary regions and promotion.
The diagnostic `retina_sample_decoration_placement` entry point invokes that
production expansion/filter path; it does not implement a separate reference
algorithm.

## Validation

`blockFeatureTest` loads actual vanilla and Terralith placed-feature registries,
then compares Minecraft's placement modifiers with the native diagnostic on the
same controlled random stream and terrain. It exercises all supported loaded
height/scan/chance configurations plus build-relative anchors, weighted heights,
upward/downward scans and a scan followed by an offset. Six fixtures cover flat
ground, water, a carved roof, rugged terrain and both build limits, with 64 seeds
and negative coordinates.

All 23,040 vanilla and 70,272 Terralith placement cases match, including 36,872
rejections. This validates placement semantics under the harness random stream;
it does not claim Minecraft's Java world seed RNG or neighboring-chunk schedule.
The existing feature geometry references also pass 10,560 / 14,976 cases. Rust
tests check 3D recipe discovery, legacy profile behavior, precise surface biomes,
carved-fluid scan predicates and scan/build boundaries.

`material_substrate_check` validates independently generated chunks against a
full region at sixteen core/halo corner positions, with four concurrent requests
and negative coordinates. It compares every base voxel, all six heightmaps and
final decorated blocks. Both profiles pass in specialized mode at region size 32
and interpreter mode at size 2: 6,291,456 base and the same number of final voxel
comparisons, 98,304 heightmap checks. Cached requests add zero GPU jobs.

The release build, 33 native unit tests and 3 native integration tests pass; two
separate real-GPU fixtures remain ignored in the unit invocation and are covered
by explicit GPU harnesses. `biomeTest`, `decorationCountTest`, `previewTest` and
`datapackTest` pass, including full MCA decoding, specialization parity, cold
material columns, DH cache/eviction, promotion, concurrent edits and save
isolation. Logs are `build/placement-3d-*-validation.log` and
`build/placement-3d-*-boundaries.log` / `*-interpreter.log`.

## Measured cost

The retained libraries, actual profiles and raw twenty-region measurements are
under `build/goal-baseline/vertical-placement/`. Measurements use Metal on Apple
M4 Max, seed 123456789, two warmups and twenty adjacent regions, including negative
coordinates, with no simultaneous build or GPU test. Startup measurements use a
warm driver cache; cold compilation remains a separate required workstream.

| Profile / input / callers | Mean region ms | Native chunks/sec | Peak process RSS MiB |
| --- | ---: | ---: | ---: |
| Vanilla / previous library and profile / 1 | 136.76 | 7,376 | 901 |
| Vanilla / new library, previous profile / 1 | 138.46 | 7,382 | 998 |
| Vanilla / new registered profile / 1 | 141.56 | 7,221 | 935 |
| Vanilla / new registered profile / 2 | 207.15 | 9,690 | 1,035 |
| Terralith / previous library and profile / 1 | 162.89 | 6,277 | 1,719 |
| Terralith / new library, previous profile / 1 | 159.42 | 6,414 | 1,664 |
| Terralith / new registered profile / 1 | 168.75 | 6,059 | 1,718 |
| Terralith / new registered profile / 2 | 265.05 | 7,625 | 1,698 |

The new input costs about 2.2% / 5.9% region latency against the compatible old
input in this sequence. These are single-run fidelity costs, not a measured
optimization or a universal overhead bound. Indexed discovery avoids repeating
the new 3D union scan, but no isolated speedup is claimed. Serial plant planning
worker time changes from 10.11 to 13.95 ms/region for vanilla and 10.96 to 16.88
for Terralith. Worker and device timings overlap; they cannot be summed into
region latency. Native rates exclude Minecraft loading, lighting and DH rendering.

Each new-library/old-profile run matches all 20,480 decompressed NBT records from
its matched retained-library run. Each new-profile concurrent run matches all
20,480 serial records. Output files total 152,641,536 bytes for new vanilla and
128,901,120 for new Terralith, versus 152,690,688 / 129,323,008 for previous inputs.
Peak RSS includes native caches, driver memory and any NBT comparison reader.

New serial upload/readback averages are 14.27 / 41.48 MB per vanilla region and
12.41 / 37.83 MB per Terralith region, versus 14.25 / 41.48 and 12.38 / 37.82 MB.
The tiny query-buffer difference comes from the changed registered recipes;
3D biome discovery itself reads the existing payload. Warm-cache initialization,
registration and first region are 37 / 504 / 627 ms for vanilla and
38 / 988 / 2,530 ms for Terralith. Java registry export is separate. The tested
native library and bundled JAR library both have SHA-256
`2aec7211634428032aae41b73eb4d4c4e84dc6e679b9db08e4542ce4b4a62868`.

Vegetation patches, other spatial placement filters and spatial/rule-based block
providers remain required. Unsupported recipes still appear in the export log;
this milestone does not silently replace them with a guessed density.
