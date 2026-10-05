# Registered attachment growth

Retina now exports and replays Minecraft 26.3's `multiface_growth` and `vines`
features. Complete vanilla profiles gain four recipes (glow lichen, sculk veins
and two vine placements), increasing 172 → 176. Terralith gains sixteen recipes,
510 → 526, including its cave, highland, dark-forest and skyland attachments.
These come from the active registry and its placement programs, not biome names.

The exporter also finds attachment features in generation steps preceding
vegetation. It retains their order before the existing vegetation recipes and
follows selector branches using the existing count, rarity, selector and spatial
filter adapters. Other generation steps retain their respective exporters.
Nested vegetation patches can replay attachment recipes in the same live overlay
and random stream as their ground and other vegetation writes.

## Geometry and loaded state data

Java exports every state of the registered attachment block, face additions,
supported face properties, dry/wet defaults, configured placement directions,
search range, spreading chance and spread-type order. Neighbor attachment uses
the loaded block's complete support or collision face, rather than a solidity
flag. Palette predicates are finalized after all exporters have added states.

Rust reads those predicates from the existing GPU material/cavity/fluid substrate
and earlier ordered feature writes. It retains horizontal direction order,
direction shuffles, first-matching-support behavior, existing face accumulation,
and same-position, same-plane and wrap-around spreading. Source water makes the
new state waterlogged; flowing water follows Minecraft's dry default. Sculk veins
also retain their forbidden support blocks, replaceable-state/fluid restrictions
and wrap-around occlusion check. Vines take the first valid face in the game's
direction order, excluding downward attachment, without consuming random draws.

Minecraft 26.3's multiface search loop probes `origin.relative(direction)` on
each range iteration. Failed growth neither writes blocks nor draws randomness,
so the adapter reuses that result within the attempt instead of repeating up to
64 identical substrate probes. Stack arrays also avoid allocations for direction
shuffles. The target and random stream remain unchanged; this is not an extended
ray search.
The resulting local footprint fits the existing complete GPU placement halo.
Writes respect the build range immediately. A rejected spread write continues
with the next shuffled direction, matching Minecraft and avoiding ghost writes
that could affect later geometry.

No extra GPU dispatch, spatial CPU noise or terrain readback is introduced.
Sparse placement counts and spatial fields remain on the GPU; small branching
geometry and live writes stay in Rust. Tables upload once with the profile.
F3's existing plant-planning category includes attachment replay. Both chunk and
MCA assembly use the same commands; saved chunks retain their existing blocks.

## Validation

The production exporter and Minecraft feature codecs produce targeted fixtures
for glow lichen, sculk veins, vines and nested patches. Minecraft's actual
`Feature.place` methods and native replay receive the same substrate and random
stream. **48,384 additional cases** match across vanilla and Terralith. Each stack
has 7,168 placements, 2,306 successful spread cases, 2,204 waterlogged writes,
768 dry writes at flowing-water inputs and 128 vine writes. All six faces occur.
Nested patches exercise subsequent random draws and ordered interactions.
A further **27,648 build-boundary cases** match the actual game against a
GPU-generated vertical wall. Across the two stacks, these include 15,320 rejected
write attempts and 1,168 cases with an in-range spread after rejection. The
unfixed adapter missed the second lichen write at `(-17, 319, -17)`, seed 17;
that regression is now covered.

The cases include negative chunk boundaries, all configured direction groups,
spreading chances 0 / 0.37 / 1, range 5 / 64, occupied origins, uneven terrain,
carved cave floors and ceilings, source water and flowing water. The real
registered recipes are also included in the broader Minecraft reference checks.
Forced-biome regions decode all 1,024 slots; twelve independent chunks per region
match their blocks and all six final heightmaps, including attachment fixtures.

`build`, `blockFeatureTest`, `nativeGpuTest`, `regionTest`, `previewTest` and
`snowTest` pass. These include 58 native unit tests, parallel GPU requests,
serialized regions and edits, DH temporary MCA generation and promotion,
concurrent LRU/save isolation, partial promotion and failure/shutdown cleanup.
The expanded snow oracle compares 483 registered states; frozen-ocean ice remains
bare and cold land snowy, with 196,608 matching MCA/chunk blocks.

```sh
./gradlew build blockFeatureTest -PtestPack=run/datapacks/Terralith.zip \
  --no-parallel --max-workers=1
./gradlew nativeGpuTest regionTest previewTest snowTest \
  --no-parallel --max-workers=1
```

Initial logs are `build/multiface-growth-validation.log` and
`build/multiface-growth-integration.log`; the final combined run is
`build/multiface-growth-boundary-validation.log`. The demonstrated boundary
failure is retained in `build/multiface-growth-boundary-repro.log`.
Builds use two Cargo jobs/code-generation
units and lower native process priority; runtime generation workers are unchanged.

## Measured cost and planning optimization

Apple M4 Max / Metal, release libraries, actual full merged vanilla/Terralith
profiles, seed 123456789, two warmups then twenty adjacent regions including
negative coordinates. Vanilla has 56 biomes / 30 structure definitions / 176
decoration recipes / 2,423 material states; Terralith has 151 / 58 / 526 / 3,932.
Both include real templates, ores and cave data. The production Metal mixed
stage selection is used, with block-position density composition disabled.
Builds and integration runs finish before benchmark measurements.

The initial new-recipe implementation was also compared with the previous
milestone using unchanged legacy profiles (172 / 510 recipes). Eight
serial/concurrent cases match **163,840 decompressed chunk NBT records**, with
small mixed throughput differences around 1–2%. Adding the newly supported
content initially raises serial region time from 182.66 → 194.79 ms vanilla and
249.85 → 315.94 ms Terralith in that series: roughly 7% / 26%. These are fidelity
costs, not a speedup, and include the larger loaded material palette.

A second comparison isolates the failed-probe reuse and stack direction arrays
on the identical new profiles. The final native also includes the demonstrated
build-boundary correction. Every one of these sixteen serial/concurrent runs
matches all 20,480 reference chunk records (**327,680 comparisons**). Orders are
reversed in the repeat to reduce ordering bias.

| Profile / callers / run | Initial avg region ms | Final avg region ms | Initial chunks/s | Final chunks/s | Plant-planning worker ms/chunk |
| --- | ---: | ---: | ---: | ---: | ---: |
| Vanilla / 1 / first | 191.82 | 194.59 | 5,331 | 5,255 | 0.03108 → 0.02924 |
| Vanilla / 1 / repeat | 353.28 | 358.53 | 2,895 | 2,853 | 0.04979 → 0.04669 |
| Vanilla / 2 / first | 355.89 | 336.74 | 5,745 | 5,991 | 0.06816 → 0.06177 |
| Vanilla / 2 / repeat | 637.29 | 551.50 | 3,209 | 3,662 | 0.13327 → 0.10124 |
| Terralith / 1 / first | 374.87 | 330.30 | 2,729 | 3,097 | 0.11249 → 0.10762 |
| Terralith / 1 / repeat | 436.81 | 390.61 | 2,342 | 2,619 | 0.15791 → 0.13552 |
| Terralith / 2 / first | 679.04 | 741.38 | 2,917 | 2,700 | 0.23731 → 0.24395 |
| Terralith / 2 / repeat | 683.92 | 664.08 | 2,879 | 2,974 | 0.27270 → 0.27273 |

Serial Terralith throughput improves 12–13% in these two pairs, while its
planning counter falls about 4–14%. Vanilla serial throughput is about 1.5%
lower in both pairs despite roughly 6% lower planning time. Concurrent results
are mixed, especially Terralith (−7% / +3% throughput). Shared-machine timings
vary substantially across series. The retained implementation removes known
redundant reads and allocation, but these measurements do **not** establish a
general whole-generator speedup. Worker/device counters overlap and do not add
up to region wall time. These are native generation measurements; Minecraft
loading, lighting, rendering and DH consumption are separate.

Every new-profile run writes the same twenty-region totals: **156,364,800 bytes
vanilla / 134,320,128 Terralith**. Upload/readback is effectively unchanged between
initial and final: about 14.30 MB / 45.62 MB per vanilla region and 12.62 MB /
41.93–41.96 MB per Terralith region. Tiny differences come from existing query
batch/cache scheduling; attachment geometry introduces no field readback.
The larger palette is exported and registered once per profile.

Across both comparison series, peak process RSS (including GPU buffers, native
profile and comparison reader) is about 951–1,074 MiB vanilla / 1,635–1,741 MiB
Terralith. Native initialization is 51–67 ms with the existing driver cache;
registration is approximately 0.66–0.88 s vanilla / 1.26–1.35 s Terralith. First
warmup regions still take around 0.76–1.16 s vanilla / 3.11–3.26 s Terralith in
these processes. Those are warm-driver observations, **not empty-cache desktop
startup measurements**. Java export and world opening are outside this timing.

Private artifacts are under `build/goal-baseline/multiface-growth/`:
`evidence.json`, `optimized-evidence.json`, `optimized-evidence-repeat.json`, the
retained native libraries, all per-run measurements and MCA comparison inputs.
The final library bundled in `build/libs/retina-0.1.0.jar` matches the tested
native bytes. SHA-256:

- Prior milestone: `d4c75f5345bfc03d4ec375419ab87e32114bab32accea10008511c7333a9a0bf`.
- Initial new recipes: `bac6cda9a2e968d6987c2490b6e0da9adeeda478637d9022873b2dab6619b6ff`.
- Final: `534fefe49a46209f193d2a77cb1cfcf2b341eaa500bf1ae17005e295c8848bae`.

Reproduce with the exported `build/registered-columns-{vanilla,terralith}.json`
and `scripts/native-region-benchmark.py`, using `--count 20 --warmups 2`,
`--parallel 1` / `2`, `--program-execution specialized`,
`--terrain-execution interpreter`, `--density-composition disabled`, and
`--compare` against a retained matching-profile output directory.

## Remaining approximations

Exported support shapes use the existing empty block getter. State geometry is
retained, but neighbor-dependent custom collision/support shapes are not simulated
by this table. The adapter handles the default spread configuration and the loaded
sculk-vein configuration; other custom spread configurations remain explicit
logged omissions. Block postprocessing/tick behavior retains Retina's existing
approximation rather than running Minecraft feature placement in Java.

Root systems, coral geometry, several simple-block survival rules and tree
decorators still appear in the actual export omission reports. Nullable provider
transformations, other feature/filter gaps, production density composition and
the final integrated goal audit remain separate required work.
