# Complete GPU material runs

New registry exports enable `registry_program.material_layers`. Instead of
repeating two representative surface blocks, the GPU evaluates the loaded
material program over every solid span in a column. It returns ordered vertical
runs to Rust, which replays them before ores, decorations and structures. Both
chunk and MCA generation use this path, including profiles without cave or
feature recipes. Retained diagnostic profiles without this flag keep their old
surface semantics and do not compile unused material pipelines.

## Material context

The evaluator derives floor stone depth, ceiling stone depth and fluid height
from the actual GPU base/carving field. Air resets floor depth and fluid context;
fluid does not reset floor depth. Ceiling depth uses the next non-solid boundary
below the current solid span. Secondary surface noise, signed surface depth,
terracotta bands and the height-neighborhood slope are calculated on the GPU.
Material rules use the exact GPU column biome in the upper twelve blocks, matching
the existing surface/underground split in biome classification. Deeper rules use
the resident quart biome at each Y. Reusing that coarse 4×4×4 grid at the surface
previously moved shoreline material boundaries into square patches, despite the
column shader selecting a smooth, per-block coast. This supports multiple floor,
ceiling and underwater layers rather than a fixed filler thickness.

`AbovePreliminarySurface` is now a real condition. Its preliminary level is still
approximated from final column height and surface depth, rather than Minecraft's
separate preliminary-density search. The exporter reports
`material:preliminary-surface-from-final-height` explicitly. Surface noise and
terrain geometry retain Retina's existing GPU approximations. This material milestone initially retained the
existing ocean/lava cavity classification. The subsequent
[local GPU aquifer milestone](GPU_AQUIFERS.md) now supplies air, local fluids and
pressure barriers before the same run evaluator. Registered lake caps remain intact.

The small column descriptor continues to hold stable representative top/filler
materials for fast probes. Full base-column queries and base assembly consume the
complete runs. New exported profiles also provide runs across the complete
decoration halo: live placement predicates and heightmap modifiers use actual
carved materials and local fluids there. See
[decoration substrate](GPU_DECORATION_SUBSTRATE.md) for the coverage, compatibility
and measured cost. Older profiles retain representative placement checks.

## Compact count and emission

The first pass counts runs per evaluated column. A workgroup prefix scan and a total
prefix pass calculate offsets. The normal column/cave-mask readback includes
these counts, so the host can allocate exactly the required run payload. A second
GPU pass emits runs and reads back only that tail. There is no fixed layer cap,
truncation or per-voxel material readback.

The cavity/biome mask is followed by four header words: magic `0x52554e53`, evaluated
width, column count and total run count. Each column has a count and a local
offset; each 256-column group has a total and an exclusive prefix. Each run is one
`u32`: its relative lower Y in the upper 16 bits and its palette ID in the lower
16 bits. Runs descend from the implicit world-height upper boundary to Y zero.
Rust validates dimensions, contiguous ranges and complete descending coverage.

This adds one GPU/CPU synchronization and evaluates the material column twice.
Resident registry bytecode, noise data, climate data and palettes are reused.
Each pending count job snapshots its request, columns and mask before shared
scratch can be reused by another submission. Count and emission retain the same
interpreter/specialized selection, even if compilation finishes between them.
The material pass disables unrelated shared density-cache references. Parallel
and request-order comparisons cover this separation.

Rust clears the upper air span in bulk and replays only occupied rows with
stack-local run cursors. Final NBT palettes and heightmaps still reflect blocks
after all features and structures. The new F3 `Material layers` line reports real
device time for count plus emission. It overlaps host time and is excluded from
Rust wall-percentage attribution; the small prefix passes are not separately
timestamped. This milestone introduced timing ABI version 2 with 21 stages;
local aquifers extend it to version 3 with 23 stages.

## Distant Horizons base-column cache

A temporary preview region also gets a private `r.X.Z.materials` companion. It
contains 1,024 independently zlib-compressed base-run records, created from the
existing GPU result without extra terrain dispatches. Java seeks/decompresses a
single chunk when a cold base-column query arrives, retaining only its last
decoded chunk. This preserves exact undecorated base columns after native field
eviction, without routing DH back through individual GPU chunk generation.

The companion's little-endian header contains its version, vertical bounds and
region chunk origin, followed by 1,024 offset/length entries. Each chunk stores
257 column offsets and its run words. Readers validate the actual file header,
ranges and requested run coverage. LRU retirement and world disposal delete the
companion with its temporary MCA. The cache capacity remains 1,024 regions.
Promotion publishes MCA records into missing saved slots while preserving edits;
companions are private preview metadata and are not copied into the save folder.

## Measurements

Apple M4 Max / Metal, release libraries, seed 123456789, five warmups followed by
20 adjacent regions including negative coordinates. Real merged profiles retain
structures, templates, ores, caves and decorations: vanilla has 56 biomes, 30
structure definitions and 105 decoration recipes; Terralith has 151 biomes and
262 decoration recipes. Measurements exclude Minecraft loading/rendering.
Builds and tests were stopped during benchmarks. System load was not controlled,
so these values are workload observations, not universal speedup claims.

The retained `8b53baa` library uses the old two-material profiles. Complete runs
change terrain intentionally, so this comparison includes additional fidelity:

| Profile / callers | Old chunks/s | Complete runs chunks/s | Old mean request ms | Complete runs mean request ms |
| --- | ---: | ---: | ---: | ---: |
| Vanilla / 1 | 3,036 | 3,077 | 336.09 | 332.03 |
| Terralith / 1 | 3,132 | 2,109 | 326.34 | 484.42 |
| Vanilla / 2 | — | 3,394 | — | 584.45 |
| Terralith / 2 | — | 3,031 | — | 664.29 |

Terralith's serial generation is slower with complete rules; vanilla's small
difference is not evidence of a whole-generator speedup. Parallel throughput is
measured from actual elapsed time, not by dividing 1,024 by overlapping request
latencies. Serial/concurrent complete-run outputs match all 20,480 decompressed
NBT records per profile.

Within the complete-run implementation, replacing per-row upper-air replay with
a bulk clear reduced vanilla assembly worker time from 0.68955 to 0.23349
ms/chunk. The same-output pair measured 2,316 → 3,077 chunks/s and 441.39 → 332.03
ms/region; all 20,480 NBT records and file sizes match. An earlier row-loop repeat
measured 0.82662 ms/chunk, demonstrating variability. Worker savings are not a
percentage of total wall time.

| Serial complete-run metric | Vanilla | Terralith |
| --- | ---: | ---: |
| Material count + emission device ms/region | 17.08 | 20.84 |
| GPU readback MB/region, old → complete | 27.12 → 37.33 | 23.29 → 33.68 |
| CPU upload MB/region | 14.24 | 12.31 |
| Peak process RSS MiB, old → complete | 762 → 857 | 1,229 → 1,390 |
| Total MCA bytes for 20 regions, old → complete | 135,274,496 → 145,076,224 | 112,930,816 → 123,318,272 |

MB is decimal. Upload is unchanged for the matched serial profiles; most of it
is existing ore-planning input. Run counts/offsets add roughly 2 MB per MCA plus
the variable run payload; these measurements include the whole GPU output.
Saved-MCA benchmarks do not include the private preview companions. Their writes
are included in normal preview compression/I/O timers, but their disk overhead
has not been characterized with a 20-region preview benchmark.

## Compilation and startup

Material specialization can be costly: the first full Terralith compilation took
207.93 seconds. A fresh equivalent shader identity took 200.40 seconds. Cached
Terralith runs compiled in about 5–6 seconds. Normal automatic mode continues
using the GPU interpreter during compilation; forced `specialized` diagnostics
wait deliberately. Legacy profiles now skip material entrypoints entirely.

Fresh automatic-mode checks append an identity multiplication to the material
graph, preserving output while changing shader source. Both generate terrain
while pending; Terralith completed all 20 regions before compilation finished.

| Fresh auto metric | Vanilla | Terralith |
| --- | ---: | ---: |
| Native initialization ms | 132.6 | 132.0 |
| Native profile registration ms | 1,477.3 | 2,756.0 |
| First region ms | 2,226.04 | 2,131.20 |
| Measured 20-region chunks/s | 477 | 398 |
| Mean region ms while transitioning/pending | 2,143.71 | 2,570.24 |
| Background compile seconds | 39.24 | 200.40 |
| Regenerated first region after compilation ms | 339.05 | 447.04 |

All 20,480 measured NBT records match the corresponding compiled-run reference,
and each post-compilation check matches another 1,024 records. Java registry
export is separate from native registration. This proves continuity, not fast
cold startup: interpreter material evaluation and large shader compilation need
further optimization under the specialization workstream.

## Validation and reproduction

`materialTest` evaluates the actual Minecraft material-rule classes as its
reference. Analytic flat dry/wet surfaces and underground air spans exercise
floor, ceiling, secondary-depth, water, preliminary-surface and Y conditions.
Interpreter and specialized paths, guarded/unguarded rules, negative coordinates,
unaligned vertical bounds and cave-disabled requests cover 6,287,360 chunk voxels.
An additional material-only MCA verifies 393,216 corner-chunk voxels plus private
cached base columns with no ore, decoration, structure or cave recipes.

The biome suite checks every block and final heightmap in 1,024 MCA chunks, plus
independent chunk/boundary queries. Preview tests cover cold base columns after
native eviction with no added GPU jobs, temporary promotion, saved edits, partial
files, reopen, shutdown and cleanup. Actual vanilla/Terralith registry, geology,
feature, structure, shoreline and landscape suites pass. F3 snapshot/packet and
colored material-line checks pass. Legacy-profile 20-region benchmarks match the
retained baseline exactly. New full-run outputs match across concurrent calls
and automatic/compiled execution.

```sh
JAVA_HOME=/Library/Java/JavaVirtualMachines/jdk-25.jdk/Contents/Home \
  ./gradlew build gpuTest materialTest biomeTest previewTest regionTest \
  --no-parallel --max-workers=1
./gradlew exportBenchmarkProfile -PbenchmarkProfile=build/material-vanilla.json
./gradlew exportBenchmarkProfile -PbenchmarkProfile=build/material-terralith.json \
  -PbenchmarkPack=run/datapacks/Terralith.zip
python3 scripts/native-region-benchmark.py \
  --library build/native-target/release/libretina_worldgen.dylib \
  --profile build/material-vanilla.json --out build/material-vanilla \
  --count 20 --warmups 5 --program-execution specialized
```

Use `--parallel 2` for concurrency, `--compare <output-directory>` for decompressed
NBT parity, and `--program-execution auto --warmups 0 --await-specialization
--specialization-timeout 600`
to measure cold automatic startup and its post-compilation parity check. Retained
local evidence is under `build/goal-baseline/layers-*` and `build/layers-*.log`.

Remaining work includes faster material specialization/interpreter execution,
richer registered feature recipes and providers, per-expression
density interpolation, additional measured Rust reductions and final integrated
validation of all goal workstreams.
