# Final paired-plant replay

## Grass support after structures

New profiles also export `plant_floor_masks` for actual plain `TallGrassBlock`
states (short grass and ferns) and lower halves of plain `DoublePlantBlock`
states (tall grass and large ferns). These classes use `VegetationBlock`'s loaded
`SUPPORTS_VEGETATION` block tag. Rust tests the final block below each candidate
against that exported support flag. Village paths and gravel fail that check in
the active vanilla/Terralith registries, so grass is removed after the structure
changes its ground. Supported soil from a datapack remains valid.

The sparse candidate list now includes those single-block grasses as well as
paired plants. Support checks run before partner checks; otherwise a removed
lower half could leave an upper half which was visited first. Ordinary terrain
still adds no full chunk scan. Specialized aquatics, dripleaf and custom survival
overrides retain their own behavior. Optional metadata keeps old profile replay
compatible. Existing saved chunks are preserved; new chunk and MCA assembly use
the repaired final blocks before NBT palettes and heightmaps are built.

The village regression reproduces 624 grass overlaps on paths/gravel with the
floor table disabled. Enabling it removes 842 unsupported grass blocks including
invalid upper halves, while 8,271 supported grass blocks survive. Five affected
chunks match decoded MCA voxels and all six final heightmaps. A native regression
covers path/gravel replacement, upper-first candidate order, duplicate records,
build-bottom bounds, later unrelated writes and registered custom soil support.
Minecraft's actual `canSurvive` matches 8,624 vanilla and 18,365 Terralith exported
plant/support pairs. The build, 44 native unit tests, block-feature/structure
integration, region serialization and DH cache/promotion/edit checks pass;
logs are in `build/plant-floor-validation.log`.

Six twenty-region measurements use the same new library with full actual
vanilla/Terralith profiles and only the optional floor table enabled or removed.
The disabled table exercises compatible prior support behavior; it is not a
separately compiled old-library baseline. All runs use Metal on Apple M4 Max,
seed 123456789, two warmups, adjacent regions including negative coordinates and
the GPU interpreter, without simultaneous builds or tests.

| Profile / floor check / callers | Mean region ms | Native chunks/sec | Peak process RSS MiB |
| --- | ---: | ---: | ---: |
| Vanilla / disabled / 1 | 256.80 | 3,983 | 867 |
| Vanilla / enabled / 1 | 258.35 | 3,960 | 871 |
| Vanilla / enabled / 2 | 485.85 | 4,210 | 936 |
| Terralith / disabled / 1 | 518.05 | 1,976 | 987 |
| Terralith / enabled / 1 | 556.16 | 1,840 | 975 |
| Terralith / enabled / 2 | 1,045.35 | 1,944 | 1,381 |

Serial vegetation worker time, including candidate collection and final repair,
changes from 1.55 to 2.15 ms/region for vanilla and 1.59 to 1.93 for Terralith.
Whole-region latency rises 0.6% / 7.4% in this shared-machine sequence; GPU queue,
wait and other CPU stages vary too, so the full delta cannot be assigned to the
small repair stage. No speedup or universal cost bound is claimed. Native rates
exclude Java loading, lighting and rendering; RSS includes driver/cache memory
and concurrent-run NBT comparison readers.

Across the 40,960 enabled/disabled chunk comparisons, 8,411 vanilla and 11,144
Terralith unsupported plant blocks become air. Decoded blocks verify their actual
pre-change unsupported floor, including matching lower halves for removed uppers.
There are zero unrelated block changes and zero unrelated metadata changes beyond
block palettes and final heightmaps. Concurrent enabled output matches another
40,960 serial chunk NBT records. Output totals change from 153,931,776 to
153,911,296 bytes for vanilla and 131,764,224 to 131,751,936 for Terralith.

GPU upload/readback remains about 14.29/45.62 MB per vanilla region and
12.59/41.95 MB per Terralith region; this CPU repair adds no GPU transfers or
dispatches. Tiny existing readback-byte differences reflect cache coalescing.
Enabled serial warm-cache initialize / native registration / first region is
40.9/508.8/275.4 ms for vanilla and 41.9/1,058.8/610.5 for Terralith. Java registry
export and cold specialization remain separate measurements.

Retained profiles, the tested library, scripts and raw results are in ignored
`build/goal-baseline/plant-floors/`. The tested native library and packaged JAR
library have matching SHA-256:
`1be72f9ea16e3ca06ae05a3d0987035daac85bdbe026cab6f9af98e3f2137b77`.

## Paired-block identity

New registry profiles export `plant_halves`, a signed block-identity table for
actual registered `DoublePlantBlock` instances. Lower halves have a positive
identity and upper halves its negative. Facing and waterlogging need not match:
Minecraft checks the same block and the opposite half, rather than exact states.
No biome/block-name list selects which plants participate.

Rust records possible paired positions from decoration commands, cave plants and
structure-template writes. After structures and snow, it removes orphaned halves
before returning chunk blocks or constructing MCA palettes and heightmaps. The
replacement is air, matching `DoublePlantBlock.updateShape`, including aquatic
and waterlogged variants. This does not simulate all neighboring shape updates
or subsequent fluid settling.

The candidate list stays sparse. Ordinary base terrain adds no full block scan;
profiles whose material graphs can emit paired plants conservatively scan base
assembly too. Possible writes which were skipped or subsequently overwritten are
safe to retain as candidates. The final stage reads the actual blocks, so a valid
new pair remains intact. Existing vegetation timings include collection/repair;
no GPU dispatch, shader stage or native timing ABI changes are added.
Profiles without the optional table retain their previous replay. Reloaded world
registries produce the new metadata; existing saved chunks remain unchanged.
DH temporary generation, its 1024-region capacity, promotion and edits use the
same repaired output through the existing region pipeline.

## Validation

The known failure at seed 123456789, chunk -111/-82, world -1762/49/-1297 now
removes the tall-seagrass upper while preserving the single seagrass below it.
`biomeTest` passes 128 parallel chunks and all 1024 MCA slots, including final
partner checks, block parity, biome palettes and six heightmaps. The retained
pre-change library reproduces the original failure.

`blockFeatureTest` compares the exported table with Minecraft's actual
`BlockState.updateShape` for 37,422 vanilla and 101,612 Terralith state pairs,
using actual surviving support blocks. It also passes the existing 10,368 /
11,712 controlled feature cases and chunk/MCA feature fixtures. Native checks
pass 33 tests (two explicitly GPU-dependent tests remain ignored in this command),
including overlap, both build bounds, differing facing states, a cleared upper
and mismatched plant identities. `regionTest` passes serialized metadata,
preserved edits, missing-slot writes and failed-publication preservation.

```sh
JAVA_HOME=/Library/Java/JavaVirtualMachines/jdk-25.jdk/Contents/Home \
  ./gradlew build biomeTest blockFeatureTest \
  -PtestPack=run/datapacks/Terralith.zip --no-parallel --max-workers=1
```

## Measurements

Evidence: `build/goal-baseline/plant-pairs/`. Metal / Apple M4 Max, release
libraries, actual full vanilla/Terralith profiles including all structures,
decorations, ores and GPU material/aquifer programs. Two warmups, then twenty
adjacent regions per run with negative coordinates; builds/tests/other benchmarks
were stopped. Both libraries receive identical newly exported profile JSON.

| Profile / replay | Average region ms | Native chunks/sec | Peak process RSS MiB |
| --- | ---: | ---: | ---: |
| Vanilla / previous | 143.80 | 7,045 | 912 |
| Vanilla / repaired | 143.67 | 7,115 | 900 |
| Terralith / previous | 168.37 | 6,072 | 1,580 |
| Terralith / repaired | 167.22 | 6,113 | 1,660 |

These close timings do not establish a speedup. Vegetation worker time rises
0.66 → 1.11 ms/region for vanilla and 0.32 → 0.72 for Terralith, including the
new repair. Driver/process RSS variation is not an isolated allocation measure.
Two concurrent repaired callers measured 10,018 / 7,843 native chunks/sec and
200.46 / 260.61 ms average request latency, with peaks 1,016 / 1,667 MiB.

Across the serial comparison's 40,960 chunks, 2,529 records change. Decoding their
blocks verifies exactly 1,808 vanilla and 1,812 Terralith invalid halves become
air, with zero unrelated block or metadata changes. Another 40,960 concurrent
NBT records match repaired serial output exactly. Output totals rise
152,641,536 → 152,690,688 bytes and 129,310,720 → 129,323,008 bytes; removing a
block can change compressed size or sector allocation in either direction.

GPU upload/readback stays about 14.25/41.48 MB per vanilla region and
12.38/37.82 MB per Terralith region. Minor byte differences come from existing
query caching/coalescing; the repair itself transfers no GPU data.
Warm-cache initialization/registration/first region is 44/504/653 ms for repaired
vanilla and 39/1,021/2,686 ms for repaired Terralith. Cold compilation remains
separate required goal work.

The built JAR and tested native library have matching SHA-256:
`51043c7d4b3304b0ba26f4f9aaa05bcd6d2d7f725e14f0f5ef292be62442fdf3`.
