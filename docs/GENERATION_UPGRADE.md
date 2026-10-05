# Generation fidelity and performance work

The active goal covers six workstreams: complete registered biome coverage and
indexed GPU climate selection; compact layered GPU material rules; local GPU
aquifers; broader registered features and placement/provider support; cached
specialized WGSL programs with better density semantics; and measured reductions
in repeated Rust block scans. The first workstream, an initial Rust scan
optimization, and cached WGSL specialization with horizontal field reuse are
implemented so far. Complete GPU material runs now evaluate floor/ceiling depths,
secondary surface noise and fluid context; see
[material layers](GPU_MATERIAL_LAYERS.md) for the run format, DH base-column cache,
validation, transfer costs and substantial cold-compilation cost.
Registered coordinate slices now retain their scopes in both
GPU execution paths; see [coordinate scopes](GPU_COORDINATE_SCOPES.md).
[Individual interpolation operators](GPU_INTERPOLATION_SCOPES.md) now retain
their own child graphs, cell sizes and nested slices in direct GPU samples.
Child horizontal expressions retain GPU column reuse. The shared final-field
terrain lattice and cold compilation cost remain required work. [Resident
interpolation-field lattices](GPU_INTERPOLATION_CACHE.md) now provide GPU-only
sample reuse for that ongoing work.
[Experimental block-position composition](GPU_DENSITY_COMPOSITION.md) now
preserves arithmetic after the individual fields in analytic checks. Parallel
lake probes, direct resident density reads, covered density-lattice kernels and
aquifer pressure pruning reduce its initial cost, but whole-region regressions keep the
experiment disabled by default; fast production composition remains required.
[Separate GPU lake timings](GPU_LAKE_TIMINGS.md) now distinguish candidates,
density probes, level reduction and final columns in F3. Actual-profile checks
identify expensive composed lake density; the paired resident/general probe
trial preserved NBT but showed no gain and was removed.
[Invocation-local lake interpolation reuse](GPU_LAKE_POINT_CACHE.md) now reduces
repeated corner evaluation in composed probes without another dispatch or readback.
Actual-profile repeats preserve complete NBT and improve several whole-region
workloads, with documented mixed preliminary results. Composition remains slower
than production and stays disabled; cold-start work remains required.
[Sparse lake-field stencils](GPU_SPARSE_LAKE_FIELDS.md) now move uncovered
fractional-probe corners into a GPU scratch prepass. The probe shader reuses them
without carrying raw input graphs in its hot call tree. Independent uncached-GPU
oracles preserve actual registered roots and interval endpoints. This continues
the composition experiment; it does not complete fast production composition
or cold-start work.
[Mixed GPU stage selection](GPU_STAGE_SELECTION.md) now retains compact
interpreted terrain/climate on Metal while specializing materials and caves.
Repeated actual-profile comparisons improve whole-region throughput, with
identical NBT; unmeasured backends retain their prior selection. F3 reports the
mixed mode and the compiler skips its unused specialized terrain pipelines.
[Compact interpreter column reuse](GPU_COLUMN_INTERPRETER.md) now lets Metal's
mixed density/climate stages consume that horizontal atlas with pruned resident
instruction schedules. Actual-profile root and NBT comparisons preserve output;
matched production serial throughput improves about 4–7%, while experimental
Terralith composition gains 22–31%. Parallel gains are workload-dependent. The
composition path and cold-start work remain unfinished requirements.
Local registered GPU aquifers and surface-lake corrections are implemented; see
[GPU aquifers](GPU_AQUIFERS.md) for fluid/pressure equations, validation, costs and
remaining fluid-tick approximations. Broader features, further graph
semantics and scan work remain required, along with final integrated validation,
transfer-volume measurements and updated stage telemetry where new stages arise.

Registered noise-based feature counts and arbitrary threshold counts now use
[sparse GPU feature queries](GPU_FEATURE_COUNTS.md), including signed ratios and
nested placements. Broader feature geometry, providers and spatial filters remain
part of the fourth workstream. Common registered block columns, bamboo, cactus,
sugar cane and aquatic vegetation now have ordered native adapters; see
[registered block features](REGISTERED_BLOCK_FEATURES.md). Registered red/brown
giant mushrooms now retain loaded providers, cap faces and clearance rules; see
[registered mushrooms](REGISTERED_MUSHROOMS.md). Loaded fallen trees now retain
their lengths, orientations, terrain checks and decorators; see
[registered fallen trees](REGISTERED_FALLEN_TREES.md). Loaded vegetation patches
and general simple blocks now retain their local placement data and nested
replay; see [registered patches](REGISTERED_VEGETATION_PATCHES.md). Further
spatial filters remain required.
Registered rule-based, rotated and random-block providers now retain their
positions, ordered branches, optional results and random-draw semantics; see
[block-state providers](REGISTERED_BLOCK_PROVIDERS.md). Registered
[property copying](REGISTERED_PROPERTY_COPY.md) now reads live compatible
properties with sparse finalized palette tables and no extra random draw. Some
[nullable live-state transformations](REGISTERED_NULLABLE_PROVIDERS.md) now support
rotations, integer properties, copying, mushroom caps and fallen logs. Arbitrary
current-state SimpleBlock placement now has [shared survival rules](REGISTERED_SIMPLE_SURVIVAL.md)
for supported loaded classes, including paired plants; light-dependent and
unimplemented classes remain explicit omissions. Registered spore blossoms now
use the same adapter. Native
[integer-property providers](REGISTERED_INTEGER_PROPERTIES.md) now preserve
source states without the configured integer property and skip the value draw,
matching loaded provider behavior.
The [GPU provider-noise replay](GPU_PROVIDER_NOISE.md) now samples actual initialized
Perlin stacks with sparse integer XYZ queries and resident buffers. Registered
noise, dual-noise and threshold providers drive production block-feature material
choices. Ordered replay retries unresolved anchors while preserving completed
commands, live overlays and random streams.
The [surface-relative filter](REGISTERED_SPATIAL_FILTERS.md) now respects loaded
heightmaps and inclusive offsets in top-level and nested placement programs.
[Registered cuboid placements](REGISTERED_CUBOID_PLACEMENTS.md) now preserve
inclusive dimensions, edge/interior flags and ordered nested feature draws.
Actual cave-floor/ceiling and uneven-terrain reference checks cover their complete
local footprint; broader enclosing feature geometry remains required.
The [ground-layer placement adapter](REGISTERED_LAYER_PLACEMENTS.md) now follows
loaded `count_on_every_layer` budgets and actual live empty-to-solid transitions,
including sparse GPU counts after floor discovery. The same milestone fixes
exposed dark-oak tops with their registered upper foliage rows and complete trunks.

The [GPU decoration substrate](GPU_DECORATION_SUBSTRATE.md) now supplies complete
carved air, local fluids and material runs across the existing placement halo.
Live predicates and heightmap modifiers consume those runs, consistently across
independent chunks and regions. This supplies the base data needed by underground
vegetation patches. [Registered vertical placements](REGISTERED_VERTICAL_PLACEMENTS.md)
now preserve height distributions, environment scans and 3D biome restrictions,
with one discovery pass per anchor. Patches now receive complete GPU footprint
substrate outside those anchors, preserving region/chunk random replay. Remaining
spatial filters and provider transformations are still required.
[Final paired-plant replay](PAIRED_PLANT_REPLAY.md) now repairs overlapping tall
plants after all feature/structure writes, using registered block identities.
It also checks actual registered grass/fern soil support after village paths and
gravel replace the ground, removing invalid lower and upper halves in order.
Broader biome/MCA validation passes; the previous defect and measured sparse
repair cost are documented there.
The [registered snow-support table](SNOW_SUPPORT.md) now also prevents the final
Rust snow pass from blanketing regular frozen-ocean ice, while preserving snow
on supported cold ground and crowns. Both chunk and MCA output are tested against
Minecraft's loaded block survival rules.
[Registered attachment growth](REGISTERED_ATTACHMENT_GROWTH.md) now imports
underground and vegetation-step multiface/vine recipes, with loaded support
faces, wet states, spreading and nested-patch replay. Actual Minecraft reference
checks cover all six faces, fluids and ordered draws; both assembly paths and
temporary-region promotion remain validated. Other recipe/provider gaps remain.

The [shared compact interpreter](GPU_SHARED_INTERPRETER.md) now reduces required
Metal first-profile pipelines from ten to two. Fresh-identity twenty-region
comparisons cut additional compilation about 70%, saving roughly two seconds
in the first native region. Final outputs match across cold/warm/concurrent
workloads and effective profile switches. Warm throughput shows a small mixed
tradeoff, and about 0.94 seconds of synchronous generic compilation remains;
full startup and fast production density composition remain required.
[Background density pipeline preparation](GPU_INTERPRETER_PRELOAD.md) now
overlaps compact generic compilation with native registry parsing. Actual
dependency masks choose reuse, with normal compilation for other layouts and
real compiler failure recovery. Final twenty-region runs lower initialization-
through-first-region latency 32% vanilla / 39% Terralith, with matching NBT and
small mixed warm differences. Empty-cache desktop startup remains unmeasured.

See [GPU program specialization](GPU_PROGRAM_SPECIALIZATION.md) for the compiler,
resident horizontal cache, startup behavior and its validation.
Material constant parameterization and grouped dispatch experiments were rejected:
counterbalanced cold runs exposed shared driver-cache warmth rather than a reliable
startup gain. The same document records a standalone interpreter liveness analysis
(37 / 64 scratch values for the full vanilla / Terralith profiles). Native register
reuse and a 64-slot interpreter are now implemented, with a cached wide fallback.
Repeated twenty-region interpreter benchmarks improve serial throughput 4.7–4.9×
vanilla and 2.9–3.3× Terralith, preserving all chunk NBT. This accelerates generation
while specialization is pending; cold profile compilation remains required work.
The [shoreline correction](SHORELINES.md) adds a resident coastal index and GPU
height-neighborhood checks to align registered coastal climates with the actual
approximated terrain, preventing disconnected inland beach selection. Its
fidelity checks and separate twenty-region cost measurements are documented there.
The [sparse planning milestone](GPU_SPARSE_PLANNING.md) adds direct GPU bulk-ore
masks and cheaper Rust crown/canopy lookups while preserving final chunk data.
The [final heightmap scan experiment](HEIGHTMAP_SCAN_EXPERIMENT.md) compared an
unfinished-column bitset with the existing row scan. It preserved NBT but did not
show a reliable whole-region speedup, so the production loop was retained.
The [direct palette encoder](DIRECT_PALETTE_PACKING.md) removes the temporary
mixed-section index pass. Paired real-section benchmarks reduce encoding time
about 8%, with exact NBT; complete-region repeats show lower NBT worker time but
do not establish a reliable whole-region speedup.

## World-preset registry lifecycle

World creation loads the Retina presets concurrently with their referenced
Overworld climate parameter registry. Resolving the complete pool in the biome
source constructor dereferenced an unbound holder, failed both presets, and left
the client on its preparation screen before native generation started.

Registry-backed sources now defer and memoize that pool until first use, following
Minecraft's own MultiNoiseBiomeSource lifecycle. Explicit saved pools retain their
existing behavior; the complete imported pool remains available after loading and
through serialization. There is no readiness gate or fallback biome list.

`registryTest` first constructs a source with an actual unbound parameter holder,
then runs the game's parallel registry loader over the real Retina preset files.
Both presets, modes and complete pools are checked after binding. The test failed
with the same unbound-holder exception before the fix. It is part of `check` and
can also load the real Terralith pack with `-PtestPack=run/datapacks/Terralith.zip`.

## Registered biome coverage and climate index

New MCA and chunk presets opt into `use_registry_biomes`, which takes the complete
pool from their registered biome source. Vanilla 26.3 supplies 56 Overworld biomes,
including underground biomes. Explicit saved sources without this option retain
their old list. Both source mode and imported pool survive serialization. Pack
dimension imports also retain the referenced biome source and existing handling
of additional custom biomes. Tagged Nether/End additions remain excluded.

Rust builds two resident stackless bounding-box trees at the first GPU profile
upload. The surface tree removes depth-only duplicates and excludes underground
biomes; the underground tree retains all six climate dimensions and all targets.
Nodes bound interval distance and minimum offset squared. Subtrees can be skipped
without a private traversal stack. Original registry ordinals resolve exact ties;
equal-distance branches are retained, and an equally close unplaced custom-biome
niche retains its previous precedence. Weighted legacy site selection and its
ocean penalty also retain their semantics. The diagnostic profile option
`climate_lookup: "linear"` exercises the reference search.

The complete vanilla table has 7,594 targets: 7,590 surface targets reduce to
3,795 distinct surface entries. Terralith has 1,708 targets and 1,697 distinct
surface entries. Indexed queries add no dispatches or readback bytes. Both trees
remain on the device and share the existing climate-buffer binding.

The resource-based structure exporter now applies the same STRUCTURE data fixer
as Minecraft's template loader. This is needed for older Terralith templates with
`Name`/`Properties` palettes, and also migrates their block/entity NBT. The live
StructureTemplateManager path already performs this migration.

## Measured first milestone

Apple M4 Max / Metal, release libraries, seed 123456789, two warmups followed by
20 adjacent regions, including negative region coordinates. The baseline library
is commit `8ed9076`; both libraries receive identical complete-biome profiles.
Thus these comparisons isolate lookup changes rather than biome-pool changes.
Vanilla includes 30 structure definitions and 105 decoration recipes; Terralith
includes 58 definitions and 262 recipes. Templates and ore/cave data are present.
Builds and GPU tests were stopped before benchmark measurements.

| Profile / callers | Baseline chunks/sec | Indexed chunks/sec | Baseline average region ms | Indexed average region ms |
| --- | ---: | ---: | ---: | ---: |
| Vanilla / 1, first run | 4,932 | 7,613 | 207.32 | 134.24 |
| Vanilla / 1, repeat | 4,772 | 7,231 | 214.31 | 141.33 |
| Vanilla / 2 | 5,449 | 9,421 | 375.18 | 209.97 |
| Terralith / 1, first run | 5,924 | 6,904 | 172.59 | 148.04 |
| Terralith / 1, repeat | 5,869 | 6,997 | 174.20 | 146.09 |
| Terralith / 2 | 6,831 | 6,721 | 297.33 | 294.76 |

Serial gains repeat at 52–54% for vanilla and 17–19% for Terralith in this workload.
Concurrent vanilla improved 73%; concurrent Terralith throughput did not improve.
Keep that distinction when reporting results or considering changes to scheduling.
These measure native production, not Minecraft loading, lighting or rendering.

For the serial repeat, vanilla column GPU work fell from 0.06719 to 0.01614
ms/chunk and cave-density work from 0.07304 to 0.01950. Terralith columns fell from
0.04265 to 0.02555, and cave density from 0.03178 to 0.02118. Device/worker counters
overlap; their sum is not region wall latency.

All 20,480 decompressed chunk NBT records match the baseline in each candidate
serial/repeat/concurrent comparison, including palettes, heightmaps, features,
structures and block/entity data. Region totals remain exactly 137,617,408 bytes
for vanilla and 113,623,040 for Terralith. Serial repeat peak process RSS is about
743 → 761 MiB for vanilla and 725 → 753 MiB for Terralith, including profiles,
buffers, caches and the comparison reader.

Warm shader-cache initialization is about 28–39 ms. The first indexed process
observed 1,099 ms initialization while compiling the changed shaders; later runs
were 30–32 ms. Native profile registration remains about 470–482 ms for vanilla
and 968–1,014 ms for Terralith. Java registry/template export is separate from
these native startup measurements. Avoid treating warm startup as cold startup.

## Reproduction and validation

`exportBenchmarkProfile` loads merged registries and real templates, rather than
using a structures-disabled surface-test profile:

```sh
./gradlew exportBenchmarkProfile -PbenchmarkProfile=build/benchmark-vanilla.json
./gradlew exportBenchmarkProfile -PbenchmarkProfile=build/benchmark-terralith.json \
  -PbenchmarkPack=run/datapacks/Terralith.zip
python3 scripts/native-region-benchmark.py --library build/native-target/release/libretina_worldgen.dylib \
  --profile build/benchmark-vanilla.json --out build/benchmark-vanilla --count 20 --warmups 2
```

Retain a baseline library and matched profile/output directory, then use
`--compare <baseline-directory>`. `--parallel 2` measures overlapping requests.
The script records initialization, profile registration, warmups, average/median
region latency, actual throughput, peak RSS, file sizes and the profile SHA-256.
Local first-milestone evidence is under `build/goal-baseline/`.

Validation: 20 native unit tests (including randomized indexed/linear intervals
and ties); real GPU tests; Java bridge and MCA decoding; biome, geology, feature,
preview/cache/promotion, structure, shoreline, actual Terralith and landscape
suites. GPU lookup parity checks exercise 32 concurrent chunk queries per profile
across three seeds, comparing heights, surface materials/biomes and underground
biomes. The structure suite verifies old palette migration with block properties
intact. The landscape suite compares three-seed ocean coverage and biome spacing.
The combined Terralith/BulkBiomes fixture also passes indexed/linear parity with
418 biomes, GPU selection of IDs above 255, MCA decoding and temporary promotion.

## Rust cave-dressing scans

Cave dressing previously inspected every block column from the bedrock zone to
the top of the world, even when none of its actual GPU quart biomes had a cave
floor recipe. Each chunk now derives the first/last eligible floor quart for its
16 horizontal quart columns. It skips columns with no applicable floor recipe,
and limits the remaining block scans to those bounds. This uses the actual GPU
biome IDs rather than a surface-biome guess or a height approximation.

An air span crossing into the first eligible quart is skipped if its actual floor
began in an ineligible biome below it. A span starting in an eligible quart still
runs to its actual roof, including roofs outside the eligible range. Floor and
roof recipes, plants, vines, random choices and mutation order remain unchanged.
The small bound array is stack-local and needs no allocation, cache invalidation,
additional dispatch or transfer.

The existing assembler already fuses contiguous base/carving writes. NBT recycles
scratch and palette lookups, emits uniform sections directly and scans final
occupied sections for heightmaps. Maintaining heightmaps before ores, plants and
structures would require subsequent correction, so those final-block metadata
paths remain intact. The measured scan change targets cave dressing directly.

Same machine/profiles/coordinates as above; baseline is native commit `905b5ad`,
five warmups then 20 regions per run. Alternating baseline/candidate repeats:

| Profile / run | Cave worker ms/chunk before | After | Native chunks/sec before | After |
| --- | ---: | ---: | ---: | ---: |
| Vanilla / first | 0.06968 | 0.00594 | 7,516 | 7,673 |
| Vanilla / repeat | 0.06873 | 0.00585 | 7,558 | 7,787 |
| Terralith / first | 0.08029 | 0.01965 | 6,281 | 6,495 |
| Terralith / repeat | 0.08554 | 0.01706 | 6,216 | 6,652 |

The CPU stage savings repeat at about 91% for vanilla and 75–80% for Terralith.
Whole-region gains are smaller (2–7%) and sensitive to system load; do not equate
the worker reduction with that much total speedup. A simpler horizontal-only
prototype reduced vanilla worker time by 57% but did little for Terralith; only
the tighter vertical-bound version is retained. Every candidate run matches all
20,480 decompressed NBT records, with unchanged file sizes.

Validation adds a dense-reference comparison over six vertical alignments, four
heights and five biome patterns, including negative coordinates, tiny worlds,
closed and open air spans, fluids and biome transitions. It verifies actual floor
and plant writes, rather than only checking empty output. All 21 native unit tests,
the full build and affected feature/geology/biome/region/preview suites pass.
Raw comparison artifacts use `*-bounds-*` under `build/goal-baseline/`.

## Ore preparation batching

[Ore batching measurements](GPU_ORE_BATCHING.md) record the latest retained
preparation allocation reduction, actual device mask timestamps and a rejected
GPU containment-pruning prototype. Registered noise-based plant budgets remain
on the sparse GPU count sampler; ore attempts/exposure and branching feature
geometry still need further measured offload work. Broader feature adapters and
provider/filter support remain part of the incomplete fourth workstream.
