# Live substrate transformations in registered providers

Minecraft 26.3 distinguishes required `getState` from optional
`getOptionalState`. A rule provider with no matching nonnull branch and no
fallback, or an empty random-block list, returns no optional state. Required
sampling instead returns the live block at that position. Rules keep trying
later branches after a null result.

Retina now accepts such sources inside registered rotations, integer-property
randomization and property copying. Rust preserves their order:

- Rotation chooses its direction before sampling the child. A fixed direction
  adds no draw. The resulting state uses the registered axis/facing properties.
- Integer randomization samples the child first. It draws a configured value only
  when the sampled state has the named integer property. Absent and noninteger
  properties preserve the state and consume no value draw.
- Copying samples the child, then copies compatible properties from the current
  block. A transformed current block regains its original properties; declared
  source blocks retain their own block identity.

Required nullable mushroom caps and fallen-log trunks now use the same live-state
handling. Mushroom cap and stem providers sample the origin, as the actual game
feature does, rather than each destination block. Vegetation-patch ground that
returns its current state leaves the cursor in place, preserving the game's
same-block handling and subsequent random draws.

## Palette preparation and cost

The Java exporter closes sparse transform tables over the active world palette
before finalizing dense carveable, ore, survival and material predicates. Late
states from structures and geology participate. Nested operations may introduce
registered property variants, so preparation repeats until the finite palette
stops growing. Identity rotations, missing integer properties and geometry
without relevant properties require no transform row. The entire global block
state registry is not imported.

Copying an undeclared, current-derived block requires no table: copying that
block's own properties restores its current state. Declared sources retain the
existing finalized sparse property-copy tables. Profile metadata marks the
exporter's pending transforms; the existing native recipes and FFI layout remain
compatible. No additional GPU stage or readback is needed for these local state
transformations. Spatial children retain their existing resident GPU queries.

A current block with the named integer property still samples the configured
provider. Values invalid for that property's actual domain fail when selected;
preparation does not reject a recipe because some unrelated substrate state
would have an incompatible value range.

Minecraft 26.3's weighted provider contains weighted **block states**, not child
providers. The previous documentation's nullable weighted-provider gap therefore
was not an actual registered codec case. Nested rotation/copy/int combinations
and nullable rule branches are the relevant current-state combinations.

## Validation and compatibility measurements

The production exporter and Minecraft's actual feature/provider codecs now
exercise 27 wrapper combinations and ten required-provider geometry recipes.
Across vanilla and Terralith, **58,688 additional actual-game reference cases**
match. They include 12,096 targeted late-substrate cases, with 1,008 changed
origin states, plus air/water/solid origins and randomized tail layers that
expose incorrect random consumption. The broader provider suite compares 88,576
cases across 115 features per stack, plus 8,064 property-copy cases. Nullable
SimpleBlock skips remain tested separately from the unsupported arbitrary-current
survival case.

The final integration run passes block-feature references for both resource
stacks, three native real-GPU tests, MCA decoding and edit preservation, temporary
region promotion, concurrent requests/edits, cache eviction and save isolation.
The build and all 59 native unit tests pass. The snow check verifies 483 loaded
support states, 256 bare regular-ice columns, 256 snowy land columns and 196,608
identical chunk/MCA blocks. Earlier failed runs exposed two test-fixture mistakes
(codec state shorthand and a geometry loop bound); both were corrected before
the final reference run.

```sh
JAVA_HOME=/Library/Java/JavaVirtualMachines/jdk-25.jdk/Contents/Home \
  ./gradlew build snowTest blockFeatureTest nativeGpuTest regionTest previewTest \
  -PtestPack=run/datapacks/Terralith.zip --no-parallel --max-workers=1 \
  -PcargoExecutable=/Users/cyberpwn/.cargo/bin/cargo
```

Retained logs are `build/nullable-provider-initial-validation.log` (successful
build/units/snow, before the corrected harness loop) and
`build/nullable-provider-reference-validation.log` (final successful reference and
GPU/MCA/preview suite). Rust compilation uses two jobs/code-generation units,
at nice 10. Generation worker limits are unchanged.

Eight sequential release processes compare the retained `fb9d822` library with
this milestone on identical full profiles, Apple M4 Max/Metal, seed 123456789,
two warmups followed by twenty adjacent measured regions per process. Vanilla
has 56 biomes / 30 structure definitions / 176 decoration recipes / 2,423
materials; Terralith has 151 / 58 / 526 / 3,932. Both include registered structures,
plants, ores and caves. These profiles contain no newly accepted current-input
provider markers, so the runs measure compatibility overhead; the targeted
fixtures measure the new semantics. Benchmark processes do not overlap builds
or integration checks.

| Profile / callers | Before average region ms | After average region ms | Before chunks/s | After chunks/s | Before / after peak RSS MiB |
| --- | ---: | ---: | ---: | ---: | ---: |
| Vanilla / 1 | 188.15 | 187.75 | 5,434 | 5,446 | 1,057 / 995 |
| Vanilla / 2 | 305.38 | 313.85 | 6,496 | 6,514 | 1,070 / 1,099 |
| Terralith / 1 | 302.33 | 299.16 | 3,384 | 3,420 | 1,631 / 1,664 |
| Terralith / 2 | 528.44 | 502.68 | 3,871 | 3,938 | 1,756 / 1,792 |

Each pair has small throughput differences on the shared host; these do not
establish an overall speedup. Concurrent throughput uses whole-batch wall time,
while individual region latency includes overlap. Worker and device timers also
overlap and do not sum to wall time. RSS includes native profiles, driver/GPU
buffers and the comparison reader.

All compared outputs match **143,360 decompressed chunk NBT records**, including
concurrent ordering. Twenty-region totals remain 156,364,800 bytes vanilla and
134,320,128 bytes Terralith. Per-region upload/readback is about 14.295 / 45.623 MB
vanilla and 12.618–12.620 / 41.941–41.956 MB Terralith. Small query batching/cache
schedule differences do not add a new transformation transfer.

| Run | Initialize ms | Register ms | First warmup ms |
| --- | ---: | ---: | ---: |
| Vanilla / 1 before | 56.9 | 675.9 | 758.6 |
| Vanilla / 1 after | 56.3 | 668.2 | 786.9 |
| Vanilla / 2 before | 55.1 | 671.8 | 774.0 |
| Vanilla / 2 after | 52.3 | 672.7 | 774.0 |
| Terralith / 1 before | 53.0 | 1,263.5 | 3,103.5 |
| Terralith / 1 after | 54.8 | 1,260.4 | 3,182.3 |
| Terralith / 2 before | 50.8 | 1,284.0 | 3,153.7 |
| Terralith / 2 after | 52.0 | 1,290.3 | 3,186.8 |

Production mixed GPU stage selection is forced ready with interpreted terrain
and block-position density composition disabled. These warm-driver startup
figures exclude Java profile export and desktop world opening; they do not
measure empty-cache startup. Libraries, exact profiles, measurements, output MCA
files and a reproduction script are retained under ignored
`build/goal-baseline/nullable-providers/`, including `evidence.json`.
The tested native library matches the packaged JAR; SHA-256:

- Before: `534fefe49a46209f193d2a77cb1cfcf2b341eaa500bf1ae17005e295c8848bae`.
- After: `894a9bc272b77ee5f2318971c3201a76e62312ae9ee95bc7476ae5803e31ba6b`.

Reproduce with `scripts/native-region-benchmark.py`, `--count 20 --warmups 2`,
`--parallel 1` / `2`, `--program-execution specialized`,
`--terrain-execution interpreter`, `--density-composition disabled` and
`--compare` against a matched-profile retained output directory.

## Subsequent support and remaining scope

[Live-state SimpleBlock survival](REGISTERED_SIMPLE_SURVIVAL.md) now supplies
shared loaded survival and paired-plant rules for supported transformed current
blocks. Ordinary nullable SimpleBlock providers still skip missing optional
states. Unsupported survival classes and spatial predicates remain reported.
The approximate tree planner still flattens trunk and foliage materials. These
milestones do not finish the broader generation goal.
