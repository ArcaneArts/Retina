# Final paired-plant replay

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
