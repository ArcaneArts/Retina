# Native region optimization

All terrain features remain enabled. The optimization changes how registered data is
prepared and encoded, with unchanged blocks, biome palettes, heightmaps, structure
metadata, entities and loot NBT on the matched Metal fixtures.

## Implementation

- **NBT:** uniform sections emit only their palette entry. Rayon work units recycle
  chunk blocks, NBT output, palette lookups and index storage. Immutable material
  compounds are encoded once at profile registration. Packing writes longs directly
  to the output. Final heightmaps scan contiguous rows in occupied final sections,
  retaining tree and structure tops and all six registered predicates.
- **Ores:** each anchor emits directly into its few target chunk buckets. Targets
  merge those buckets in parallel, preserving anchor/recipe/emission ordering for
  overlapping replacement recipes. Vein spheres and visited bits use reusable
  scratch storage; biome membership uses a resident lookup table.
- **Structures:** registry processors compile into typed predicates, rules,
  material tables, position checks and quota counters. Rotated palettes, jigsaw
  replacements and template coordinates are lazily shared across starts. Starts
  index pieces, blocks and support floors by target chunk. Ordinary processors
  operate on the target's block indices; capped processors retain the full template
  traversal and its outside-chunk quota semantics. Block entity tags use borrowed
  data until mutation. Cold start probes are batched and mixed GPU query batches
  reuse cached columns instead of regenerating their resident subset.
- **Assembly/carving:** column constants are resolved once. A contiguous write pass
  combines base materials and the GPU cavity bits; sixteen neighboring mask bits
  require at most two loads. Upper air is cleared explicitly when buffers are
  recycled. Ore replacement still follows carving, preserving exposure checks.
- **Region pipeline:** `IOWorker.loadAsync` registers actual new-region requests
  before they enter its consecutive executor. Two preparation threads share the
  native GPU worker and existing Rayon assembly pool. At most four preparations
  are pending per coordinator; a global two-permit limit bounds temporary native
  region jobs across DH and ordinary loads. Completed temporary MCAs remain leased
  until publication. Live save handles, merge validation and atomic publication
  stay on the owning I/O queue. Existing records and concurrent saved edits win
  over the prepared snapshot. Shutdown releases pending leases. Existing files
  retain the ordinary read and missing-slot generation path.

The pipeline can increase a region's individual latency while improving throughput.
Overlapping job times are not additive wall time. F3's production count includes newly produced temporary batches; promotion counts
publication without counting production again. MCA speed now uses the rolling
latency window described below. Cave carving time is now part of base assembly; the ores/carve
worker bucket mostly measures ore replacement and exposure checks.

## Measured results

Apple M4 Max / Metal, release build, fixture from `structureTest`: 67 biomes,
2,080 block states, 40 ore recipes and 1,288 registered templates. Seed 123456789,
-64..319, one warm-up region then nine matched cold regions around the origin.
No features were removed. Baseline library: commit `40df9f4`.

| Configuration | Native chunks/s | Median request time per region |
| --- | ---: | ---: |
| Baseline, sequential | 2,525 | 414 ms |
| Optimized, sequential | 3,533 | 287 ms |
| Optimized, two concurrent regions | 4,418 | 431 ms |

This repeat improved throughput by **1.75×** over the baseline. Other runs varied
with load; these are native generation measurements, excluding Minecraft loading,
lighting and rendering, rather than a guarantee of client F3 throughput.

Sequential worker/plan counters, ms per produced chunk:

| Stage | Baseline | Optimized |
| --- | ---: | ---: |
| NBT worker | 0.4981 | 0.0866 |
| Structure placement worker | 0.2013 | 0.0649 |
| Base assembly worker | 0.2276 | 0.0816 |
| Ore/carving worker | 0.1149 | 0.0293 |
| Ore planning wall | 0.1018 | 0.0437 |
| Structure planning wall, including GPU queries | 0.1240 | 0.1301 |

Worker counters sum elapsed time across concurrent CPU tasks. Planning counters
include GPU waits. Compare like stages, rather than adding them into region wall
latency. Structure planning remains a significant cost after placement gets faster.

## Validation and reproduction

The full build, native unit/GPU tests, Java bridge, region/biome/geology/feature,
preview, datapack, structure and landscape suites passed. A final affected-path
run repeated build, GPU, preview and structure checks after batching probes.
`previewTest` now checks concurrent preparation and deduplication, no early live
save writes, publication without regeneration, and preservation of an intervening
saved chunk edit. Existing tests cover partial and corrupt saves, eviction,
shutdown, per-chunk/MCA parity, capped structure processors, properties, loot,
spawners, entities, region crossings and all six heightmaps.

The benchmark compared raw decompressed chunk NBT byte-for-byte against the
retained baseline: **17,408 chunks across seeds 123456789, 42 and 987654321**.
Both sequential and concurrent output passed for the nine-region default seed.
A dedicated Minecraft server also passed actual `IOWorker.loadAsync` preparation,
MCA promotion, activated terrain and F3 timing packet checks.

Run from the repository root after exporting `build/structure-profile.json` with
`./gradlew structureTest`. Preserve a baseline native library before rebuilding a
candidate. Each output directory must be new; all outputs are private benchmark
MCAs and are never installed into a Minecraft world:

```sh
python3 scripts/native-region-benchmark.py \
  --library build/optimization-baseline/libretina_worldgen.dylib \
  --profile build/structure-profile.json --out build/perf-before --count 9
python3 scripts/native-region-benchmark.py \
  --library build/native-target/release/libretina_worldgen.dylib \
  --profile build/structure-profile.json --out build/perf-after --count 9 \
  --compare build/perf-before
python3 scripts/native-region-benchmark.py \
  --library build/native-target/release/libretina_worldgen.dylib \
  --profile build/structure-profile.json --out build/perf-pipeline --count 9 \
  --parallel 2 --compare build/perf-before
```

Each directory gets `measurements.json` with request durations, cumulative stage
counter deltas, throughput and equality-check counts. Hardware GPU counters use
the original timing ABI. Run builds separately from measurements to avoid
compilation or other GPU tests competing with the benchmark.

For live QA, use an isolated fresh server directory configured with
`level-type=retina:gpu` and an accepted EULA:

```sh
./gradlew runServer -PretinaQa -PretinaTimingsQa -PretinaPipelineQa \
  -PretinaDatapackQa=/absolute/path/to/fresh/server-directory \
  -PcargoExecutable=/absolute/path/to/cargo
```

The server checks three distant load requests, activates their terrain, validates
stage telemetry and the stats packet, then stops. These QA flags are opt-in.

## Encoding and timing window update

Baseline: `4d2093d`. Same Apple M4 Max/Metal and full `structureTest` fixture,
seed 123456789, five warm-up regions followed by 20 matched regions (20,480
chunks). The candidate retains all features and switches type-2 zlib encoding
from flate2's level-1 encoder to reusable libdeflate level-1 compressors. Small
Rayon batches amortize scratch/compressor setup. Palette indices retain their
length, common bit widths specialize packing, unused legacy heightmaps are
skipped, and NBT strings bypass modified-UTF-8 allocation for non-NUL ASCII.

The repeated matched run measured:

| Measurement | Baseline | Candidate |
| --- | ---: | ---: |
| NBT worker ms/chunk | 0.08774 | 0.06903 |
| Compression worker ms/chunk | 0.09123 | 0.06663 |
| Total region file bytes | 173,981,696 | 148,955,136 |
| Native chunks/s | 3,654 | 3,695 |
| Median region request ms | 274.88 | 276.18 |

NBT CPU elapsed time decreased **21%**, compression decreased **27%**, and the
region files were **14% smaller**. All **20,480 decompressed chunk NBT records
matched byte-for-byte**, including palettes, heightmaps, structures, block
entities and entities. Whole-region throughput and median latency were essentially
unchanged; these stage improvements do not establish a significant end-to-end
speedup. An earlier run also improved NBT/compression stages while total throughput
was lower. Other system activity varied, so compare stage savings conservatively.

A zlib-rs candidate improved compression but made files larger and did not improve
whole-region throughput. A libdeflate level-0 trial preserved NBT but tripled file
size and worsened I/O/region latency; level 1 remains the chosen compatible format.
No features or save durability were removed to improve the measurements.

Reproduce with the commands above, using `--count 20 --warmups 5` and matched fresh
output directories. Local evidence: `build/encoding-repeat-before/measurements.json`
and `build/encoding-reuse/measurements.json`; the first baseline was retained in
`build/encoding-baseline/libretina_worldgen.dylib`.

F3 now uses the last 20 generated regions for average latency and both reciprocal
speed figures, excluding promotions and existing saves. Each region returns its
own stage report so concurrent callers cannot contaminate another job's counters.
Serial phases use measured wall latency; parallel worker costs are scaled to the
measured parallel-phase wall time and marked as estimates. GPU device percentages
use the same denominator but explicitly overlap GPU host work. Raw cumulative
worker measurements remain available to this benchmark.

Validation passed: full build and native unit/GPU, Java bridge, MCA region,
biome, geology, feature, preview, datapack and structure suites. Follow-up build,
GPU/packet/formatting and preview checks passed after measuring Java column-cache
writes separately. An isolated dedicated Minecraft server generated ten regions,
passed real requested-region loading/promotion, and verified the rolling averages,
per-region stage data and column-cache measurement through the actual stats packet.
The packaged JAR's native library is byte-identical to the release build.
