# Generation fidelity and performance work

The six requested workstreams are implemented. The current requirement audit,
final integrated validation, production-selection measurements and remaining
approximations are recorded in [the goal audit](GENERATION_GOAL_AUDIT.md).

New MCA and chunk presets save `density_composition: true`; worlds whose saved
generator lacks that option keep legacy final-field interpolation. Datapack
imports preserve the choice. Individual registered interpolation fields are
sampled on their own GPU lattices before block-position graph composition, with
resident bounds and sparse lake fields to reduce repeated work. This fidelity
choice has a measured cost; it is not presented as a throughput optimization.
See [production density composition](PRODUCTION_DENSITY_COMPOSITION.md).

The implementation includes complete active Overworld biome sources with GPU
climate interval indexes; complete vertical GPU material runs; local aquifer
fluids and barriers; registered feature counts, providers, filters and ordered
Rust geometry; cached specialized shaders and compact GPU interpreter reuse;
and measured Rust planning, scan and palette improvements. The detailed design
and historical paired measurements remain in the linked milestone documents.
Rejected alternatives, including [per-voxel cave masks](GPU_VOXEL_MASK_TRIALS.md),
are retained as evidence rather than enabled because one stage became faster.

The following sections record earlier milestones and their measurements. Their
profile sizes, selected modes and baseline rates belong to those revisions;
the audit and production-selection report describe the final build.

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
