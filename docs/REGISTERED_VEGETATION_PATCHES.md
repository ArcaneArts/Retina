# Registered vegetation patches and simple blocks

The exporter now retains supported `VegetationPatchFeature`,
`WaterloggedVegetationPatchFeature` and general `SimpleBlockFeature` recipes from
the active registries. Full vanilla exports 170 decoration recipes across 56
biomes, including four patch and four new simple-block recipes. Terralith exports
463 across 151 biomes, including five patches and eighteen simple-block recipes.
Structures, geology and the rest of the loaded decorations remain enabled.

Patch data includes the loaded ground provider, replaceable tag, floor/ceiling
direction, depth distribution, vertical search range, radius distribution,
extra-edge/bottom chances and vegetation chance. Child placed features retain
ordered count, rarity, chance, square, offset, height, scan, block-predicate and
water-depth operations. Nested simple blocks, columns, patches and random,
simple, boolean and weighted selectors share the parent's random stream and live
block overlay. Rust performs branching replay; terrain, biomes, carved spaces,
fluids and surface rules come from the existing GPU substrate.

Replay follows Minecraft 26.3's patch search and ground-placement behavior,
including the same-block case that consumes a depth draw without advancing the
position. Water pools require the loaded enclosure predicates, then run their
child features and waterlog their resulting ground block where applicable.
Simple blocks use actual registered survival predicates, paired-plant states and
separate lower/upper waterlogging. Column palettes now include registered wet
states needed by nested dripleaf providers.

Ordinary Java `HashSet<BlockPos>` iteration is reproduced by stable final-bucket
ordering, preserving child random draws. Pathological tree bins use Java object
identity; native ties retain insertion order instead. The controlled references
below match for all exercised loaded recipes, but this does not claim Java world
seed RNG or schedule equivalence.

Supported cave-floor/ceiling patches own their loaded moss/clay/vegetation
budgets. The previous per-exposed-floor/ceiling moss coating is disabled only
where a supported patch provides that material. Registered nested cave-vine
providers likewise suppress the extra heuristic vines. Unsupported cave dressing
and dripstone remain approximations.

## Complete footprint substrate

New profiles with patches set `decoration_patch_halo: true`. Existing feature
anchors still occupy a one-chunk ring. The GPU now evaluates complete material
runs through a second ring, plus the existing outer computation guard: a region
uses a 38×38 column tile and a 36×36 material tile; a chunk uses 7×7 and 5×5.
Guard-column metadata shares an `Arc` with the cached material mask. It permits
local reads/writes outside the anchor ring without adding feature attempts or
GPU dispatches. This prevents clipped patch footprints from changing later
random draws between independent chunks and region requests.

The exporter currently accepts complete nested horizontal reach up to fifteen
blocks; larger or unsupported child graphs are reported as omissions. Nested
GPU noise-count modifiers and biome filters are explicitly omitted until their
query/context handling is implemented. Spatial noise providers, pale moss carpet
geometry, trees nested inside patches and other unsupported survival recipes
remain reported, rather than substituted with guessed block distributions.

Native profiles without the new flag retain their previous halo and output.
Only core blocks are serialized. Both per-chunk and MCA requests, including DH
temporary-region generation and promotion, use the same planner. Existing F3
GPU stages include the larger substrate work; patch replay is included in plant
planning. No new round trip or timing stage is introduced.

## Validation

Actual Minecraft feature code is compared against native replay using a
controlled shared random stream on eight fixtures: flat, submerged, rugged,
build-limit and GPU-carved floor/ceiling terrain. All 12,608 vanilla and 20,864
Terralith feature cases match, including empty/rejected results and waterlogging.
The placement reference passes 30,720 / 93,696 cases. Registered paired-plant
tables match Minecraft's neighbor updates for 64,680 / 117,504 state pairs.

Forced biome MCA checks now include lush caves alongside eight existing biomes.
All 216 selected chunks match independent native generation voxel-for-voxel;
Minecraft decodes every slot and all six final heightmaps match the blocks. Both
lush-cave regions contain registered features in all 1,024 chunks, with 24,218
feature blocks in each set of twelve inspected chunks.

`material_substrate_check` passes actual full vanilla and Terralith profiles in
specialized mode at side 32 and interpreter mode at side 2. It checks sixteen
core/halo corner positions, negative coordinates and four concurrent callers:
6,291,456 base comparisons, the same number of final-block comparisons and
98,304 heightmap checks. Cached requests add zero GPU jobs.

The release build, 33 native unit tests and three native GPU integration tests
pass. `biomeTest`, `decorationCountTest`, `previewTest` and `datapackTest` pass,
including full MCA decoding, shoreline resolution, cache eviction, partial
promotion, edits, shutdown cleanup and save isolation. Two optional real-GPU
unit fixtures remain ignored in the ordinary native unit invocation. Logs are
`build/patch-halo-validation.log`, `build/patch-reference-regions.log` and
`build/patch-substrate-*.log`.

## Measured cost

Libraries, full profiles and raw measurements are retained under
`build/goal-baseline/vegetation-patches/`. These use Metal / Apple M4 Max, seed
123456789, two warmups and twenty adjacent regions with negative coordinates.
Builds and other GPU tests were stopped during measurement. Startup figures use
a warm driver cache; cold-compilation stalls remain a separate required task.

| Profile / input / callers | Mean region ms | Native chunks/sec | Peak process RSS MiB |
| --- | ---: | ---: | ---: |
| Vanilla / previous library and profile / 1 | 143.36 | 7,060 | 903 |
| Vanilla / new library, previous profile / 1 | 144.64 | 7,067 | 947 |
| Vanilla / new registered profile / 1 | 154.53 | 6,615 | 981 |
| Vanilla / new registered profile / 2 | 231.47 | 8,565 | 1,063 |
| Terralith / previous library and profile / 1 | 165.55 | 6,176 | 1,523 |
| Terralith / new library, previous profile / 1 | 167.57 | 6,102 | 1,641 |
| Terralith / new registered profile / 1 | 180.68 | 5,659 | 1,620 |
| Terralith / new registered profile / 2 | 295.28 | 6,922 | 1,736 |

The new registered input costs approximately 6.8% / 7.8% region latency against
the compatible old input in this sequence. This is a measured fidelity cost,
not an optimization. Plant-planning worker time changes from 16.85 to 19.79
ms/region for vanilla and 18.16 to 24.34 for Terralith. Worker/device stages
overlap and cannot be summed into elapsed region time. Native rates exclude
Minecraft loading, lighting and DH rendering.

Each compatible run matches all 20,480 decompressed NBT records from its retained
library baseline. Each concurrent registered run matches all 20,480 serial
records: 81,920 identical records in total. Registered file totals are
152,977,408 bytes vanilla / 129,515,520 Terralith, versus 152,641,536 / 128,901,120
for previous profiles. Peak RSS includes caches, driver memory and comparison
readers.

Serial upload/readback averages are 14.27 / 45.55 MB per vanilla region and
12.41 / 41.90 MB per Terralith region. Previous inputs read back 41.48 / 37.83 MB;
the expanded substrate accounts for the increase. Initialization, registration
and first region measure 38 / 519 / 656 ms vanilla and 40 / 1,079 / 2,764 ms
Terralith, excluding Java registry export. The tested native library SHA-256 is
`6278929b6df9e058964ed95eb4fdb1f97cdd18438dd91510b3c420117d2d0202`.
