# Registered columns, bamboo and aquatic vegetation

The decoration exporter now retains common Minecraft 26.3 block-column,
bamboo and aquatic simple-block recipes from the merged biome/feature registries.
Full vanilla export grows from 105 to 132 decoration recipes; Terralith grows
from 352 to 384. The additional recipes include kelp, seagrass, tall seagrass,
lily pads, bamboo, cactus (including its flower) and sugar cane.

These are registered feature adapters, not biome-name density substitutions.
They use the existing ordered placement programs, counts, rarity, selectors,
heightmaps, biome restrictions, offsets and predicates. The GPU's resident
placement-noise permutation supplies kelp/bamboo count fields through the
[sparse count sampler](GPU_FEATURE_COUNTS.md). No full-region plant noise texture
or extra per-block GPU readback is introduced. Conditional geometry and live
survival/canopy checks remain in parallel Rust.

## Recipe semantics

Block columns retain their direction, complete nonnegative integer height
providers, per-layer block-state provider, allowed-placement predicate and
`prioritize_tip`. Minecraft tests the next position ahead of the origin;
truncation removes bottom layers when prioritizing the tip. This keeps kelp's
aged tip when shallow water prevents its requested full height. Atomic,
weighted and randomized integer-property providers are supported. Kelp's
registered ages 20–23 are sampled per tip, rather than collapsed to one state.
Uniform, biased, weighted, trapezoid, clamped and clamped-normal height providers
use the existing Rust provider implementation.

Bamboo follows the game's 5–16-block trunk request, three crown states and
configured podzol probability/radius. It reads the registered support and
podzol-replaceable tags, and places the final crown one block above the last
trunk, matching Minecraft's actual feature code. Out-of-build-height writes are
clipped after geometry evaluation, avoiding artificial crowns at the build roof.

Aquatic simple blocks retain their states/weights, survival predicates and
paired upper-state behavior. Seagrass checks a sturdy supporting face and its
exclusion tag; tall seagrass also requires full water. Lily pads use registered
support-fluid/block tags and an empty origin fluid. Mixed dry/aquatic providers
keep both families rather than dropping one and renormalizing the other.
Cactus and sugar-cane `would_survive` filters project the game's actual neighbor,
support and adjacent-water rules to palette predicates. Sturdy-face predicates
are evaluated for palette states at export time.

New profiles set `ordered_decorations: true`. Final Rust assembly replays complete
feature commands in order, with the same within-feature soil/log/leaf/decorator
ordering as the planning overlay. Forced, already-checked block-column writes
can replace water or earlier geometry. Heightmaps account for removed blocks,
and later features can read cleared space. Native JSON profiles without the flag
retain historical global role replay; existing MCA records and player edits are
preserved. Both chunk and region entry points share the planner. DH uses it when
building temporary regions and promotes their existing output.

The current 16-block horizontal halo supports columns up to 15 blocks long;
vertical columns support up to 4,096 requested blocks. Oversized horizontal
recipes and unsupported spatial/rule block providers are logged as omissions
rather than clipped silently.

## Validation

`blockFeatureTest` loads actual vanilla and Terralith registries and calls
Minecraft's `BlockColumnFeature`, `BambooFeature` and `SimpleBlockFeature` code
against the same controlled terrain/random stream as the production Rust
adapter. It compares final block-state writes in 3,456 vanilla and 4,096 Terralith
cases: all 27 / 32 new recipes, negative coordinates, four terrain/build-height
fixtures and 32 seeds. Cases include failed survival, shallow-water truncation,
varied bamboo heights/podzol and all four kelp tip ages. This compares feature
semantics under a controlled SplitMix/normal stream; it does not claim to reproduce
Minecraft's Java seed RNG or its neighboring-chunk scheduling.

For each profile, forced bamboo-jungle, desert and ocean fixtures decode all
1,024 MCA slots and compare 12 independently generated chunks per biome,
including negative region edges. Blocks and all six serialized heightmaps match.
The fixtures contain bamboo in 730 region chunks, cactus in 256, and aquatic
vegetation in all 1,024 ocean chunks. Rare cactus placement is checked over the
whole region, rather than assuming every sampled chunk must have a cactus.

The full build, 31 native unit tests, actual GPU/bridge tests, count sampler,
material/coordinate/aquifer checks, MCA decoding and DH temporary-cache/promotion
checks pass. Count-reference coverage now exercises 11,008 vanilla and 51,968
Terralith queries; 1 / 37 differences are within the recorded float boundary
tolerance. Promotion, concurrent edits, placeholder/partial regions, eviction,
cold material columns and cleanup remain covered. Evidence lives in
`build/registered-columns-validation.log` and
`build/goal-baseline/block-features/final-validation.log`.

## Costs and compatibility measurements

Measurements are recorded after the final release build, with actual full
vanilla/Terralith profiles, including structures, caves and ores. These new
profiles change feature fidelity and order; comparison with the previous input
is a cost comparison, not a semantics-preserving speedup claim.

On Metal / Apple M4 Max, seed 123456789, two warmups and twenty adjacent full
regions per run, the first serial runs measured:

| Input | Mean region ms | Native chunks/sec | Plant planning ms/region | GPU counts ms/region |
| --- | ---: | ---: | ---: | ---: |
| Previous vanilla export | 133.99 | 7,624 | 9.08 | 0.00446 |
| New vanilla export | 128.15 | 7,976 | 9.59 | 0.00582 |
| Previous Terralith export | 150.30 | 6,802 | 9.95 | 0.00829 |
| New Terralith export | 156.20 | 6,544 | 12.64 | 0.00823 |

Two simultaneous requests measured 11,058 / 8,517 native chunks/sec for the new
vanilla / Terralith profiles, with mean request latency 181.10 / 239.82 ms.
These are native generation rates, not Minecraft or DH loaded-chunk rates.

Repeated serial runs varied substantially: previous/new vanilla means were
131.29 / 181.23 ms, and previous/new Terralith 360.98 / 167.01 ms. CPU stages and
startup slowed together under changing host load; a VM, browser, Steam and other
user applications remained active. No build or other benchmark was run alongside
these measurements. The first runs suggest modest feature cost, but the repeats
do not support a reliable speedup or a tight overhead bound.

In the first serial runs, initialization took 43 / 43 ms, profile registration
483 / 981 ms and first region 638 / 2,680 ms for new vanilla / Terralith. Driver
cache state affects startup; cold shader compilation remains outstanding. The
new twenty-file totals are 152,424,448 / 129,085,440 bytes versus previous
147,857,408 / 126,537,728. Peak serial RSS was 1,052 / 1,695 MiB versus
895 / 1,557 MiB; these process peaks include caches, driver allocations and,
where requested, NBT comparison readers, rather than isolated feature memory.

Uploads/readbacks averaged 14.25 / 37.64 MB per vanilla region and
12.38 / 33.99 MB per Terralith region. Compared with the previous exports, the
additional sparse queries add about 4.79 / 5.76 KB uploaded and 1.20 / 1.44 KB
read back per region. Resident count work stays tiny; geometry and live predicate
checks remain CPU work.

All legacy-profile runs reproduce their earlier outputs, and the new concurrent
and repeated runs reproduce the new serial outputs: 163,840 decompressed chunk
NBT records compared exactly in total. The tested native library SHA-256 is
`1d3ef47aec5f58f7c908227c83a884c49b8c85864e26515a106b57626cd73b73`.
Evidence and exact settings are in `build/goal-baseline/block-features/*/measurements.json`.
The retained previous exporter was compiled from commit `3dae7b3` and used actual
loaded registries, rather than hand-written reduced benchmark profiles.

## Remaining work

Giant mushrooms are implemented in the subsequent
[registered mushroom milestone](REGISTERED_MUSHROOMS.md), which extends the same
reference harness to five biome fixtures and six terrain/build-height fixtures.
Registered fallen logs and their decorators are covered by the subsequent
[fallen-tree milestone](REGISTERED_FALLEN_TREES.md).
Vegetation patches, coral and remaining placement
filters still need adapters. Spatial `noise` / `dual_noise` and rule providers
remain explicit omissions for these column recipes. Some old tree/provider
approximations remain. The overlay still uses representative GPU terrain columns
rather than the full material-run/cave geometry at every predicate position.
Neighboring anchor overlays remain independent for parallel/order-stable chunk
and MCA generation. Context-dependent support shapes, custom light/survival
conditions and requested block ticks are not fully simulated. This milestone
does not complete the broader feature, cold-compilation or Rust scan workstreams.

```sh
JAVA_HOME=/Library/Java/JavaVirtualMachines/jdk-25.jdk/Contents/Home \
  ./gradlew build gpuTest blockFeatureTest decorationCountTest previewTest regionTest \
  -PtestPack=run/datapacks/Terralith.zip --no-parallel --max-workers=1

python3 scripts/native-region-benchmark.py \
  --library build/native-target/release/libretina_worldgen.dylib \
  --profile build/registered-columns-vanilla.json --out build/block-feature-measurement \
  --count 20 --warmups 2 --program-execution specialized
```
