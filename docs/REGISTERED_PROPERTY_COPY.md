# Registered property-copy providers

Minecraft 26.3's `copy_properties` provider is now exported for supported,
nonnullable source programs. Rust samples its source first, then copies compatible
properties from the live block at that exact position, matching the game's
`source.getState(...).withPropertiesOf(current)` order. Copying consumes no
additional random draw. Ordered feature writes and earlier blocks in the same
feature are visible; missing compatible properties preserve the source state.
Nested copies, weighted/noise sources and rotations around copies retain that order.

The Java exporter reserves the source block's complete registered state definition
before downstream survival and state-transform tables are constructed. It then
finishes sparse `[input state, output state]` replacement tables after every
exporter has finished adding palette states. This includes late structure/geology
substrates and prevents freezing the property table too early. Rust validates the
tables once and binary-searches them locally. Possible-output sets deliberately
include every registered source-block state; a pack using many complicated copied
blocks can therefore enlarge its palette/profile even when few states occur.

No new GPU dispatch, spatial query or readback is added by copying. Spatial noise
inside a source continues through the existing GPU provider replay. Local copies
remain in parallel Rust and their cost is included in plant planning. Timing ABI
7 remains unchanged. Retina still uses its native chunk/MCA paths, with no normal
Minecraft generator fallback.

## Validation

The actual Minecraft feature/provider codecs parse nine new copy combinations,
including log axes, facing, stair halves, waterlogging, snowy state and fluid
levels. The reference harness runs Minecraft's actual feature placement against
Rust with identical terrain and random streams. Seven different live substrate
states are added after export to exercise late palette finalization.

All 8,064 targeted copy cases pass across vanilla and Terralith. They include
negative chunk coordinates, nested/noise providers and randomized tail layers
that detect extra source draws. Another 29,696 broader provider cases pass across
58 simple-block/column features per stack, including optional skips. Forced-biome
regions decode all 1,024 MCA slots; twelve independent chunks per region match
blocks and all six final heightmaps. The existing common feature, mushroom,
fallen-tree, aquatic and placement reference checks also pass.

The build passes with 52 native unit tests (three benchmark tests ignored).
Additional native GPU, region serialization, DH temporary-region generation,
promotion, parallel LRU/save isolation, concurrent edits and failure/shutdown
checks pass. The snow regression verifies regular frozen-ocean ice stays bare
while supported cold land receives snow.

```sh
./gradlew build blockFeatureTest \
  -PtestPack=/Users/cyberpwn/Downloads/Terralith_v2.6.5+26.3.zip \
  --no-parallel --max-workers=1
./gradlew nativeGpuTest previewTest regionTest snowTest \
  --no-parallel --max-workers=1
```

Logs are `build/property-copy-validation.log` and
`build/property-copy-integration.log`. The release and packaged macOS native
library both have SHA-256
`b2198de157e50933db1fcba01ea2318cb18f459b96e9669f7f296f2a7c965eaa`.

## Whole-profile compatibility measurements

Apple M4 Max / Metal, release builds, seed `123456789`, two warmup regions then
20 measured regions per process. Eight processes ran sequentially at nice 10;
vanilla ran before/after, Terralith after/before, with one or two simultaneous
region callers. Rust compilation used two jobs and code-generation units;
runtime generation workers were unchanged.

Both full registry exports remain identical as JSON values to the previous
profiles: vanilla has 56 biomes, 172 decorations and 2,156 material states;
Terralith has 151, 510 and 3,673 respectively. These profiles do not currently
contain copy providers. These runs measure compatibility/framework cost; the
Minecraft codec fixtures exercise the new capability. They do not measure a
large pack newly adding property-copy features.

| Profile / callers | Before mean region ms | After mean region ms | Before chunks/s | After chunks/s | Before / after peak RSS MiB |
| --- | ---: | ---: | ---: | ---: | ---: |
| Vanilla / 1 | 183.33 | 179.56 | 5,576 | 5,694 | 1,021 / 1,006 |
| Vanilla / 2 | 300.87 | 293.86 | 6,794 | 6,909 | 1,074 / 1,055 |
| Terralith / 1 | 242.69 | 247.75 | 4,215 | 4,128 | 1,538 / 1,535 |
| Terralith / 2 | 380.46 | 386.93 | 5,264 | 5,101 | 1,585 / 1,580 |

Concurrent throughput uses total batch elapsed time; individual region latencies
include overlap/queue time. Single pairs on a shared host vary by roughly 2–3%
and establish neither a speedup nor tight overhead bounds. Peak RSS includes the
GPU driver/compiler, profile buffers and NBT comparison reader.

| Run | Initialize ms | Register ms | First warmup ms |
| --- | ---: | ---: | ---: |
| Vanilla / 1 before | 54.1 | 583.2 | 796.1 |
| Vanilla / 1 after | 56.8 | 583.9 | 826.3 |
| Vanilla / 2 before | 55.2 | 605.0 | 820.0 |
| Vanilla / 2 after | 53.1 | 580.3 | 796.8 |
| Terralith / 1 before | 53.8 | 1,185.3 | 3,076.6 |
| Terralith / 1 after | 55.0 | 1,183.6 | 3,114.8 |
| Terralith / 2 before | 51.6 | 1,151.7 | 3,203.7 |
| Terralith / 2 after | 51.1 | 1,190.5 | 3,087.6 |

Mixed program specialization was forced ready, with interpreted terrain and
block-position density composition disabled. The first warmup includes profile
shader compilation (about 0.5 seconds vanilla / 2.5–2.7 seconds Terralith).
Device initialization benefits from shared driver warmth; these measurements do
not demonstrate an improvement in automatic cold-start scheduling.

All 163,840 decompressed NBT records match the retained production baseline,
including concurrent request orderings. Twenty-region files total exactly
153,845,760 bytes for vanilla and 130,899,968 for Terralith in every run.
Average per-region transfers remain about 14.295 MB upload / 45.623 MB readback
for vanilla, 12.615 / 41.955 MB for serial Terralith and 12.613 / 41.937 MB for
concurrent Terralith. Small counter/batching differences add no copy-specific
traffic. Profiles, before/after libraries, measurements, hashes and comparisons
are retained under ignored `build/goal-baseline/property-copy/`, including
`manifest.json`. The before library is the retained `37ff2d5` lake-timing build.

## Remaining scope

Sources that may return arbitrary current substrate states remain explicitly
omitted as `block_provider:copy_nullable_current_state`; complete nullable
transformations are still required. Unknown survival/spatial predicates remain
reported. The approximate tree planner still flattens trunk/foliage materials.
This milestone does not complete broader spatial filters, fast production
block-position density composition or cold profile compiler startup.
