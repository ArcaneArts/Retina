# Nether implementation

Nether activation remains deferred while terrain, features, structures, and
dimension integration are implemented. The End is outside this effort.

## Cycle 1: generation contexts

`GenerationBounds` distinguishes three ranges exported in `generation_bounds`:

| Fields | Meaning |
| --- | --- |
| `storage_min_y`, `storage_height` | Dimension sections, saved blocks, and inside-world predicates |
| `terrain_min_y`, `terrain_height` | Noise bounds intersected with storage; an empty intersection has zero height |
| `min_y`, `height` | Minecraft `WorldGenerationContext` for material, carver, feature, and structure height anchors |

Vanilla Nether storage is `[0, 256)`, while its density and anchor contexts are
`[0, 128)`. For customized offset dimensions, Minecraft's anchor depth is the
smaller of the source and dimension heights; density uses their intersection.
Those operations are deliberately separate.

`generator_depth` additionally retains the source generator's unmodified depth.
Huge fungi use that value for Minecraft's absolute-Y growth limit; it is neither
the dimension's storage height nor a resolved vertical anchor.

Adapted generators expose their original generator's minimum Y and generation
depth to Minecraft. Native requests continue to carry full dimension storage
bounds. The source descriptor also retains ceiling, skylight, logical height,
and coordinate scale without changing the saved dimension definition.

Surface and geology exports use the anchor context. Decoration height providers
and structure start heights resolve relative anchors before native execution,
so they cannot be reinterpreted using storage bounds. Inside-world predicates
and ore replacement tables still cover the full storage range.

Run `./gradlew netherProfileTest` to check loaded vanilla Nether profiles,
customized settings, fixed biomes, five Minecraft reference height contexts,
height-provider random sequences, bedrock roof anchors, and source preservation.
This is an export test; it does not establish native Nether terrain correctness.
Use `./gradlew :common:netherProfileTest --args=--rust` to also register the
vanilla and customized profiles in the native backend.

## Cycle 2: native terrain

Ceiling profiles now search the source's final density for terrain height. The
Nether's constant `chunk_surface_level` is an aquifer probe, not its terrain roof.
The resident GPU program carries both clipped terrain bounds and the generation
context ceiling. Density, cavity masks, fluid masks, material runs, and independent
base-column queries retain air outside the terrain volume without reducing chunk
storage.

Ceiling worlds use the actual six-channel climate router and complete source
climate table at every quart height. Constant climate channels stay constant.
They do not import Overworld underground biomes, cave-noise registry entries,
coast remapping, synthetic surface lakes, or freezing. Material programs use the
vertical biome field even at the roof. The existing uniform fluid picker handles
Nether settings with disabled local aquifers; cavities below the configured fluid
level receive the default fluid, including compatible water overrides.

Registered cave carvers retain Retina's approximate geometry. Their exported
probability, count, height range, thickness, radius multipliers and floor setting
control native GPU tunnel fields and room ellipsoids. The Nether's start vertical
radius is now retained independently for each carver. Zero probability/count
disables carving, and inactive predecessors do not change another carver's
geometry. Carvers protect the bottom block and seven upper context blocks;
density and surface rules still determine the actual bedrock. Provider means,
GPU hashes, lattice interpolation and coherent tunnel fields do not reproduce
Minecraft's random-walk paths or per-attempt provider distributions.

Run:

```sh
./gradlew netherTerrainTest
./gradlew :common:netherTerrainTest --args=--region
```

The first command generates actual five-biome and fixed-biome terrain, compares
independent columns with generated blocks, checks repeat determinism and material
bounds, and exercises six controlled offset/clipped/empty density volumes with
lava and water. Both interpreter and specialized GPU execution are checked.
Separate fixtures isolate carver controls and depth-only climate selection,
including material selection at upper Y levels.

The region option writes a fresh 1,024-chunk negative-coordinate MCA and reads it
with Minecraft's region, NBT, palette and bit-storage decoders. Every chunk is
checked for source biome palettes, heightmaps, storage sections, air above the
roof, and absent skylight. Four corners and an interior chunk are compared with
independent native generation, including all blocks and biome entries. Repeated
publication must preserve the complete file byte for byte.

Artifacts are retained in `build/nether`: the exported terrain profile, a vertical
cross-section PNG, statistics, and a unique region directory per run. These are
terrain-only harness fixtures: features and structures are deliberately stripped
to isolate this milestone. Region timing is a warm harness measurement and is
not a gameplay or complete-Nether throughput claim. Production Nether activation
remains deferred.

On Metal / Apple M4 Max, the terrain harness passed 1,540,096 controlled
bound/fluid voxel comparisons, 327,680 independent region/chunk block comparisons,
all five fixed-biome surface checks, depth-aware biome/material checks, and
carver disable/radius/room fixtures. The saved region has 1,024 chunks and occupies
4,210,688 bytes. A warm terrain-only run took 124.3 ms for generation/publication;
decoding and assertions are outside that measurement.

Regression validation includes the host Fabric/NeoForge build, registry loading
and independent/native export, 88 Rust unit tests, three native GPU integration
tests, 6,680,576 Minecraft material comparisons, 3,072,000 Minecraft aquifer
comparisons, biome integration, and Superflat (nine presets, 294,912 reference
light comparisons, and 96 feature fixtures). Runtime Nether activation and
gameplay validation belong to the remaining cycles.

## Cycle 3a: Nether block primitives

The shared native decoration replay now supports the loaded
`random_neighbor_spread`, `single_block_pillar`,
`projected_random_patchy_square`, `stepped_column_cluster`,
`netherrack_replace_blobs`, and `delta_feature` implementations. These cover
glowstone spread, basalt pillars and columns, replacement blobs, deltas, and
the pillar/cap graph used by weeping vines. State providers, material/tag
predicates, integer providers, and nested caps use the existing registry
compiler. Each feature reads its earlier writes through the live overlay.

Height-range block predicates resolve their inclusive anchors against the
source generation context, including sea-relative anchors. Storage boundaries
still constrain actual writes. Integer trapezoids now preserve Minecraft's
symmetric-difference sampling and draw order for unequal halves; this also
corrects the shared placement implementation used outside the Nether.

Run:

```sh
./gradlew netherFeatureTest
./gradlew :common:netherFeatureTest --args=--region
```

The reference test loads all five Nether biomes and executes Minecraft's actual
feature implementations against an independently checked cavern substrate.
It compares complete block-write maps for 1,584 feature/height/seed cases,
including negative coordinates, floors, ceilings, and storage boundaries.
Additional predicate fixtures check inclusive, empty, bottom-relative,
top-relative, and sea-relative ranges. `blockFeatureTest` covers symmetric,
asymmetric, full-plateau, and zero-width integer trapezoids with subsequent
random draws. These tests use identical controlled random streams; they do not
claim identical world layouts for Minecraft's seed RNG.

The optional region test publishes the currently exported Nether features and
ores, decodes every chunk, compares corner/interior chunks with independent
generation, requires actual glowstone placement, and checks repeat publication.
Its files are named `feature-region-*` and `feature-region-stats.json` under
`build/nether`. This is partial feature coverage, not a complete Nether milestone.

The loaded feature graph contains 37 reachable named placed features and 14
feature kinds. At the end of cycle 3a, remaining work included huge fungi, springs and required
fluid updates, mushroom/fire/soul-fire survival and other vegetation eligibility,
complete root coverage diagnostics, and shared generation-step ordering with
ores. Existing ore and decoration passes do not yet establish that ordering.
Custom primitive recipes outside the existing 15-block horizontal reach contract
are reported as unsupported. Production Nether activation remains deferred.

## Cycle 3b: fungi and Nether vegetation

Huge crimson and warped fungi now use native stem, cap, shroomlight, and hanging
vine replay. The exporter retains configured base, stem, cap, decoration and
replacement states/predicates, planted behavior, and the source generator depth.
Thick stems, doubled heights, cap probabilities, vine lengths and tip ages consume
the same controlled random stream as the Minecraft reference implementation.
Planted variants can grow above the source noise ceiling while writes remain
bounded by dimension storage.

Small Nether fungi, roots, sprouts and Nether wart use the shared SimpleBlock
provider and survival implementation. Their loaded support tags and weighted
providers now determine placement; they no longer inherit the generic grass
floor mask.

`netherFeatureTest` also runs the actual Minecraft fungi and three loaded forest
vegetation recipes on nylium and unsupported substrates, under lava, against
solid and wart-block obstructions, and near the source ceiling. It checks complete
write maps, thick/doubled stems, planted variants, all three vine-tip ages,
shroomlights, and a customized fungus with a different base, oriented stem,
cap, light block and replacement predicate. This remains a controlled-stream
comparison rather than a claim of identical Minecraft seed layouts.

The `--region` option additionally publishes fixed Crimson Forest and Warped
Forest regions. It requires their characteristic stems and vegetation, decodes
all 1,024 chunks in each region, compares corner/interior chunks with independent
generation, and verifies repeated publication preserves existing bytes. Counts
and paths are recorded separately in `feature-region-crimson_forest-stats.json`
and `feature-region-warped_forest-stats.json`. The mixed-biome region records its
counts in `feature-region-stats.json`.

On Metal / Apple M4 Max, 69,120 fungus/vegetation reference cases compared
1,006,035 final block writes, including 39,249 rejected placements. The three
saved regions passed 983,040 independent block comparisons. Host Fabric/NeoForge
builds, registry export, 88 Rust unit tests, three native GPU integration tests,
and existing Overworld feature, Nether terrain and Superflat regressions passed.

At the end of cycle 3b, remaining feature work included springs and required fluid
updates, mushroom/fire/soul-fire survival, complete feature-root coverage
diagnostics, and shared ordering with ores.

## Cycle 3c: springs and scheduled updates

The shared native spring adapter reads the loaded fluid state, valid block set,
required support, and exact rock/air neighbor counts. It preserves source and
flowing fluid identities, including compatible water overrides. The same adapter
also handles supported spring definitions outside the Nether. This does not
change the Nether activation policy.

Feature commands carry a one-based ID into the profile's `decoration_ticks`
definitions. Distribution, cached replay, native chunk metadata and MCA publication
retain those updates. The native writer deduplicates by tick kind, registered type
and position, retaining the first update even if a later feature overwrites its
block, as Minecraft does. Generation delays and priority are zero/normal.
Both chunk and region decoration imports use Minecraft's saved-tick codecs and
chunk tick containers. Previously Retina always wrote empty tick lists.

`netherFeatureTest` checks 8,640 spring cases against Minecraft's actual feature
implementation: every combination of the six queried neighbors, valid/air/invalid
origins, all five loaded spring placements, custom counts, optional support,
source/flowing water and lava, and valid-block sets that include air. Complete
block-write maps and scheduled fluid identities agree, including rejected cases.
The installation fixture checks duplicate and foreign-chunk updates, block and
fluid tick separation, native metadata import, save/reload and the transition to
runnable ticks. These are native/Minecraft harness checks; actual in-game fluid
flow after automatic Nether activation remains part of cycle 5.

The `--region` option decodes tick lists in mixed, Crimson Forest and Warped Forest
regions. It checks type, coordinates, uniqueness, priority and delay; requires
updates targeting surviving lava; compares ordered lists against independent
corner/interior chunk generation; and preserves the full region on repeat
publication. Statistics include total, active and independently compared fluid
ticks. Rust coverage also checks negative coordinates, storage edges, duplicate
identities, invalid commands and unchanged command sizes.

On Metal / Apple M4 Max, the three regions retained 21,896 scheduled fluid
updates, of which 21,686 still targeted lava after decoration. Fifteen independent
corner/interior chunks matched ordered tick lists and 983,040 blocks. Host
Fabric/NeoForge builds, registry loading/export, 89 Rust unit tests, three native
GPU tests, Overworld block/feature tests, Superflat and Nether terrain checks pass.

At the end of cycle 3c, remaining feature work included mushroom/fire/soul-fire
survival, complete feature-root coverage diagnostics and shared ordering with
ores. Structures, production activation and the final runtime matrix remained
later cycles.

## Cycle 3d: fire and small mushroom survival

SimpleBlock recipes are now considered in every loaded generation step. This
retains Nether fire and soul-fire roots outside vegetation. The shared survival
exporter preserves normal fire's sturdy support or burnable-neighbor rule, the
loaded soul-fire base tag, and mushroom light overrides plus solid-render support.
Fire burnability is sampled from the loaded FireBlock implementation during
profile export, including waterlogged-state behavior. Native replay uses the
resulting material predicates and sees earlier writes.

Mushrooms use the fresh FEATURES-stage light context: Minecraft's uninitialized
block-light storage reads zero, while an uninitialized sky-light column reads 15
in dimensions with skylight. These values are bound only into decoration and
shared survival tables before native execution. An actual Minecraft light engine
and proto-chunk fixture verifies both contexts with emissive blocks present;
FEATURES precedes INITIALIZE_LIGHT. Final chunk lighting is computed later, and
runtime mushroom behavior remains Minecraft's responsibility.

`netherFeatureTest` compares 12,960 complete feature write maps across the six
loaded mushroom/fire roots, 15 floor states, every fire-neighbor direction, wet
and dry leaves, fluid/solid origins, storage boundaries, and dimensions with and
without skylight. It includes the native source noise ceiling and bedrock roof.
There are 2,098 accepted and 10,862 rejected cases.

Loaded mushroom heightmap/offset placements can decorate the top of the Nether's
bedrock roof at Y=128. Region QA permits only those mushrooms above the noise
volume and checks heightmaps using their actual Minecraft opacity predicates;
the terrain itself still ends at Y=127. The mixed region requires brown and red
mushrooms, fire, and soul fire, in addition to glowstone. All three region fixtures
continue to compare blocks and ordered fluid ticks against independent chunks and
preserve existing bytes on repeat publication.

Host Fabric/NeoForge builds, registry export, 89 Rust unit tests, three native
GPU tests, 1,195,846 shared-survival comparisons, Overworld feature tests,
Superflat and Nether terrain regressions pass. The three regions retain 983,040
independent block comparisons.

The next feature cycle must finish whole-graph coverage diagnostics and shared
generation-step ordering with ores. Structures and production activation are
still deferred.

## Cycle 3e: loaded decoration root order

Ceiling-world decoration export now allocates recipes in Minecraft's
`FeatureSorter` order across the complete loaded biome feature graph. This
replaces first-biome encounter order, which could put a shared feature before
a prerequisite introduced by another biome. Native anchor replay already sorts
recipe IDs and evaluates placement predicates against its live write overlay.
The exporter preserves each root's generation step, root order, source name,
and exact biome membership. Selector branches remain grouped with their root.

A registered root used in two different generation steps produces two recipes
with separate deterministic random streams. Inline names include their source
biome, step and list position. An actual cycle in the loaded feature graph raises
Minecraft's diagnostic instead of inventing an order. The Overworld and Superflat
export paths and their random salts are unchanged.

`netherFeatureTest` compares all 29 emitted decoration recipes with the complete
37-root loaded order and checks membership across all five Nether biomes. Custom
fixtures cover prerequisites introduced by a later biome, generation-step order,
the same registered root in multiple steps, and an invalid feature cycle. Eighteen
native chunks exercise a three-feature replacement chain at positive, negative,
and region-edge coordinates. Reversing the two dependent roots produces the
expected different block, verifying that the fixture detects an ordering error.
The three saved-region fixtures retain 983,040 independent block comparisons and
ordered fluid-tick parity after the deterministic layout change.
Host Fabric/NeoForge builds, registry checks, Nether profile/terrain checks,
Overworld feature references and Superflat regressions pass.

This cycle orders the supported decoration roots within each anchor. Ores still
use the earlier geology pass, and different anchors still replay independently
before their commands are distributed. Shared ordering with ores, dependencies
between neighboring anchors, and complete graph coverage diagnostics remain
required feature work. Production Nether activation remains deferred.

## Remaining cycles

3. Complete the remaining Nether feature and decoration coverage and ordering.
4. Bastions, fortresses, fossils, and Nether ruined portals with saved metadata.
5. Automatic activation and dimension lifecycle, lighting, and portal integration.
6. Both-loader runtime validation, regression coverage, visual QA, and benchmarks.
