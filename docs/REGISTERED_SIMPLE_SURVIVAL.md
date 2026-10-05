# Live-state SimpleBlock survival

Registered optional providers can transform the current block, rather than
returning one of their explicitly declared states. Retina now supports these
results inside `SimpleBlockFeature`, including nested rotations, integer-property
randomization and property copying. Missing optional results still skip placement
and preserve the provider's random-draw behavior.

The exporter builds one palette-indexed `simple_current_states` table only when
a recipe can return a transformed current state. It closes this table, required
provider transformations, wet variants and paired-plant states together before
geology and other dense material predicates are finalized. All recipes share the
table; it does not import the global block-state registry. Ordinary profiles
without contextual SimpleBlock recipes receive no additional table.

Parallel Rust replay samples the provider against the live overlay, then selects
either its declared state row or the shared current-state row. Survival and
paired upper-position checks use the existing material predicates. Double plants
receive lower and upper states at their respective positions, with separate
waterlogging, even when the provider samples an upper-half state. Placement
ordering, random streams, local heightmaps and cleared space keep their existing
handling. These rules add no GPU stage, spatial CPU noise or readback.

## Registered rules and explicit omissions

Loaded tags and state geometry supply vegetation soils, cactus neighbors,
sugar-cane adjacency, bamboo support, aquatic fluids, snow and carpets. Cactus
flowers use their registered support-override tag or an upward center support
face. Leaf litter needs a sturdy upper face. Wither roses, nether sprouts/wart,
fungus, roots and stems retain their actual configured support tags. The
exporter recognizes source-known survival and soil-method implementations;
unknown overrides cannot silently inherit a generic grass-soil rule.

Spore-blossom features are now exported from vanilla and Terralith. Their ceiling
must support its downward center, excluding the loaded unstable-bottom-center
tag, and their origin must have no water. Rust places them through the same
SimpleBlock adapter, using GPU-generated cave substrate.

Unsupported live-state rows are explicitly null and their registered block names
are recorded in `simple_current_omissions` and the profile-loading log. Light
dependent crops and mushrooms, pale moss-carpet geometry, and unimplemented
special survival classes remain omissions. A null row skips that unsupported
result; it does not assume that the block survives. Explicit unsupported provider
outputs still omit and report the recipe. The ordinary approximate tree/plant
planner and other missing feature/placement adapters remain separate required
work for the broader goal.

Face/collision support uses the established export-time empty block getter. This
retains loaded state geometry and tags, but does not model arbitrary custom
neighbor-dependent support shapes. Density, caves, fluids and spatial provider
noise continue using the resident GPU data and their documented approximations.

## Compatibility

Both per-chunk and MCA entry points use this replay. Distant Horizons uses it
when generating temporary regions; promotion preserves those generated blocks.
Old profiles without the new table/flag retain their previous behavior. The FFI
layout is unchanged. The shared survival table resides in Rust and is not uploaded
as a new GPU buffer. SimpleBlock work remains in the plant-planning F3 timer.

Existing generated chunks and player edits are preserved. New loaded spore
recipes affect newly generated terrain after restarting the client. The earlier
[regular-ice snow fix](SNOW_SUPPORT.md) continues to leave frozen oceans bare
while placing snow on supported cold land and tree crowns.

## Validation

The actual Minecraft 26.3 feature/provider codecs and production exporter now
exercise all 27 nullable wrapper combinations in SimpleBlock as well as columns.
Across vanilla and Terralith, the main provider suite compares 130,048 cases
across 142 features per stack. Targeted late-substrate fixtures compare another
38,016 cases, with 2,052 changed origin states, plus 12,672 property-copy cases.
Fixtures include air, water, solid blocks, paired lower/upper plants, snow and
carpet, with tail layers that expose incorrect random consumption.

A separate oracle compares exported survival predicates directly with each
supported loaded state's `canSurvive` method. It exercises every palette floor
and ceiling, dry/water origins, and independently varied cactus/sugar-cane
neighbors. All **3,672,883 cases** match: vanilla 1,168,356 and Terralith 2,504,527.
The synthetic closed palettes contain 2,926 / 5,226 supported states and
874 / 1,253 explicitly omitted states. These counts include injected provider
transformations; they are not normal production profile sizes. The initial
oracle exposed a cactus-flower soil approximation. Source inspection also
corrected leaf-litter/tag rules and removed inappropriate generic handling of
light-dependent survival. A test fixture initially used a removed colored-carpet
constant; the fixture now uses the registered moss carpet.

The final build, 59 native unit tests (five optional fixtures ignored), three
real-GPU native tests and actual vanilla/Terralith block-feature references pass.
Forced biome regions match independently generated chunks at negative edges,
including decoded blocks and all six final heightmaps. MCA edit/error
preservation, temporary-region promotion, partial/placeholder regions,
concurrent requests/edits, LRU eviction, shutdown cleanup and save isolation pass.
The snow oracle still verifies 483 loaded states, 256 bare ice columns, 256 snowy
land columns and 196,608 matching MCA/chunk blocks.

```sh
JAVA_HOME=/Library/Java/JavaVirtualMachines/jdk-25.jdk/Contents/Home \
  nice -n 10 ./gradlew build blockFeatureTest nativeGpuTest regionTest previewTest snowTest \
  -PtestPack=run/datapacks/Terralith.zip --no-parallel --max-workers=1 \
  -PcargoExecutable=/Users/cyberpwn/.cargo/bin/cargo
```

Final evidence is `build/current-survival-final-validation.log`. Earlier logs
retain the failed oracle and corrected intermediate runs. Native builds continue
using two jobs/code-generation units at nice 10.

## Release compatibility measurements

Eight sequential native release processes compare the retained `3b12a7e`
library with this milestone using the **same new full profiles**. Vanilla has
56 biomes / 30 structure definitions / 177 decoration recipes / 2,423 materials;
Terralith has 151 / 58 / 524 / 3,932. Spore blossoms add one recipe per stack.
Three Terralith mushroom-column recipes previously used an incorrect generic
soil approximation for light-dependent `would_survive`; they now report explicit
predicate omissions. These changed profiles are not compared to old-profile
terrain for equality.

Neither loaded resource stack contains transformed-current SimpleBlock recipes,
so these measurements assess compatibility overhead, not the shared table's cost
in a custom datapack. The reference fixtures exercise that new behavior. Runs use
Metal / Apple M4 Max, seed 123456789, two warmups, twenty adjacent measured regions
and one or two simultaneous callers. No builds or integration processes overlap.

| Profile / callers | Before mean region ms | After mean region ms | Before chunks/s | After chunks/s | Before / after peak RSS MiB |
| --- | ---: | ---: | ---: | ---: | ---: |
| Vanilla / 1 | 189.69 | 190.74 | 5,390 | 5,361 | 961 / 1,003 |
| Vanilla / 2 | 320.02 | 312.75 | 6,384 | 6,446 | 1,108 / 1,057 |
| Terralith / 1 | 310.37 | 298.98 | 3,296 | 3,422 | 1,630 / 1,609 |
| Terralith / 2 | 509.31 | 487.04 | 3,953 | 4,053 | 1,619 / 1,635 |

These small shared-host differences do not establish a general speedup.
Concurrent throughput uses batch wall time; individual latency includes overlap.
Native rates exclude Minecraft chunk loading/lighting and DH rendering. RSS
includes profiles, driver buffers, caches and the comparison reader.

All six comparison processes match **122,880 decompressed chunk NBT records**,
including concurrent request ordering. Twenty-region file totals are unchanged
at 156,385,280 bytes vanilla and 134,328,320 bytes Terralith. Per-region GPU
upload/readback remains about 14.295 / 45.623 MB vanilla and 12.616–12.618 /
41.938–41.955 MB Terralith. Small batching differences do not introduce a new
survival transfer.

| Run | Initialize ms | Register ms | First warmup ms |
| --- | ---: | ---: | ---: |
| Vanilla / 1 before | 59.4 | 677.0 | 766.1 |
| Vanilla / 1 after | 56.6 | 673.5 | 766.5 |
| Vanilla / 2 before | 53.5 | 679.7 | 764.4 |
| Vanilla / 2 after | 53.9 | 682.2 | 759.4 |
| Terralith / 1 before | 54.6 | 1,287.0 | 3,180.3 |
| Terralith / 1 after | 54.4 | 1,276.6 | 3,135.5 |
| Terralith / 2 before | 55.5 | 1,288.4 | 3,174.7 |
| Terralith / 2 after | 52.6 | 1,290.7 | 3,127.7 |

These warm-driver startup figures exclude Java export and desktop world opening.
Production mixed GPU stage selection is forced ready, with interpreted terrain
and experimental block-position density composition disabled. That experiment
and remaining feature/filter gaps still require work before the broader goal
is complete.

Libraries, exact profiles, MCA outputs, measurements, hashes and the sequential
reproduction script are retained under ignored
`build/goal-baseline/simple-survival/`, including `evidence.json`. The tested
candidate matches the packaged JAR's native library. SHA-256:

- Before: `894a9bc272b77ee5f2318971c3201a76e62312ae9ee95bc7476ae5803e31ba6b`.
- After: `d7446b06dc149a8d7a1f0007ad72939ebadc44edec724c72bc3cb0e28da82a8b`.

Reproduce with `scripts/native-region-benchmark.py`, `--count 20 --warmups 2`,
`--parallel 1` / `2`, `--program-execution specialized`,
`--terrain-execution interpreter`, `--density-composition disabled`, and
`--compare` against a retained matched-profile output directory.
