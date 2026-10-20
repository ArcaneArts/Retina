# Nether implementation

Supported Nether sources now activate Retina automatically on creation and reopen.
The milestones below record the staged implementation; earlier milestones kept
activation deferred. Live portal, progression, lighting and unload checks are
recorded below. Remaining final validation is listed at the end. The End is
outside this effort.

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

## Cycle 3f: ores in the live decoration stream

All ten loaded Nether ore/replacement roots now use native vein or scattered-ore
adapters in their source generation step. They no longer run in the separate,
earlier geology pass. Their complete placement programs and biome restrictions
use the same replay as the other Nether features. Previous accepted writes can
create or remove ore hosts, and later features see accepted ore blocks.
The Overworld and Superflat keep their existing geology path.

The adapters preserve the configured size, ordered targets, scatter offsets,
vein ellipsoids and containment pruning, heightmap entry condition, storage bounds,
and air-exposure rejection. Air checks inspect the current six neighbors;
lava does not count as air. A matching target rejected by its exposure check
still permits later targets to run. Loaded block/tag/state rules, absolute height
rules, random block/state rules and ordered all/any/not combinations are compiled
once for native execution. Unsupported custom ore subclasses or rules are
reported instead of having their behavior inferred from base fields.

`netherFeatureTest` compares 4,992 cases and 219,373 changed blocks against the
actual Minecraft vein and scattered feature implementations. Its reference world
uses real chunk sections to capture vein writes that bypass WorldGenLevel.setBlock.
Every source chunk is first checked against a controlled native/GPU substrate.
The 52 configurations include all loaded roots, sizes 0–64, zero/partial/full
exposure rejection, multiple/random/composite targets, a solid/air/lava layer,
storage edges and the separate noise ceiling. There are 3,140 accepted cases.

Ordering fixtures now include an ore between two dependent replacement features,
plus a reversed-order control, bringing the native ordering checks to 30 chunks.
The mixed saved region requires quartz, Nether gold and ancient debris; all
three regions count these blocks, compare 983,040 blocks and ordered fluid ticks
against independent chunk generation, and preserve their bytes on repeat writes.
Host Fabric/NeoForge builds, 90 Rust unit tests, three native GPU tests, loaded
registry/profile checks, Overworld feature references, Superflat and Nether
terrain regressions pass.

Complete nested-graph coverage diagnostics and dependencies between neighboring
anchors remain feature-cycle work. Structures, automatic activation and the
runtime validation matrix remain deferred.

## Cycle 3g: feature graph coverage evidence

Ceiling-world profiles now include `decoration_coverage`, with an export result
for every loaded root in every generation step. Each result retains its source
name, step/order, exact biome membership, emitted recipe IDs and omissions.
Unsupported roots outside vegetation are attempted and reported instead of being
skipped. Omissions are collected independently for each root, so an unsupported
child shared by several selectors remains visible on every affected selector.

The report includes the source definition encoded by Minecraft's actual codecs,
with feature and provider references expanded where possible. `typed_inputs`
locates feature, provider, distribution, predicate and placement discriminators
by JSON pointer; the surrounding source values retain their complete settings.
This captures nested pillar caps that Minecraft's `getSubFeatures()` traversal
does not expose. The loaded five-biome Nether has 37 roots, 39 emitted recipes,
14 feature kinds and no export omissions or inventory errors.

Statuses distinguish `exported`, `partial`, `omitted` and `no_commands`. They
describe adapter output, not proof of complete runtime semantics. Inventory
encoding limitations are reported separately and never prevent a working
adapter from running. For example, a tag-backed placed-feature set can retain
its normal registry definition while reporting that reference expansion failed.
The source world definitions are not modified by this inventory.

`netherProfileTest` and `netherFeatureTest` verify complete loaded membership and
recipe attribution, the hidden pillar cap, every typed input's source pointer,
deterministic repeat export, and preservation of registered definitions. Synthetic
fixtures exercise an unsupported early-stage root, two partially supported
selectors sharing an omitted child, an omitted composite, an unsupported placement
modifier, and a supported tag-backed selector with an inventory limitation.
Reports are written to `build/nether/feature-coverage*.json`.

The native feature references and three saved-region fixtures pass, retaining
983,040 independent block comparisons and ordered fluid-tick parity. Exported
profiles are identical to cycle 3f after removing the new coverage field, and all
three regions retain their previous feature and tick counts. Host Fabric/NeoForge
builds, 90 Rust unit tests, three native GPU tests, Nether profile/terrain tests,
Overworld feature references and Superflat regressions pass.

Dependencies between neighboring anchors remain unfinished: the report's complete
loaded export coverage does not resolve that ordering requirement. Structures,
automatic activation and the runtime matrix remain deferred.

## Cycle 3h: generation order across distributed writes

Ceiling-world replay now carries the loaded root order with each provisional
write. Distribution merges roots in generation order across neighboring anchors,
then retains canonical anchor order and the original write order within each
root. An early-step write from a later anchor can no longer overwrite a later-step
write merely because that anchor's command list was concatenated last. Selector
branches share their source root's order, and scheduled ticks follow the same
merged stream. The existing Overworld and Superflat distribution path is retained.

The merge processes contiguous root spans through a heap, without allocating a
second full command buffer. Provisional world commands grow from 20 to 24 bytes;
retained-prefix accounting includes that cost. Published/cached chunk commands
remain 12 bytes. CPU replay, sparse-provider continuation and GPU vine continuation
all retain source order metadata.

Native distribution fixtures compare the complete stream against a separate
stable-sort reference across chunk/region sizes, duplicate writes, sparse root
IDs, out-of-bounds writes and ticks. Resume fixtures cover both distribution modes
with complete, partial and zero retention budgets. The Minecraft/native ordering
harness adds 24 chunks containing overlapping placements across X and Z boundaries,
including negative coordinates and region edges. The old distribution mode is an
explicit failing-order control. Together with previous replacement fixtures this
checks 54 chunks. The GPU vine handoff fixture also compares source-ordered CPU
and GPU output through successful, discarded and failed GPU continuations.

All three saved-region fixtures pass 983,040 independent block comparisons,
ordered fluid-tick parity and repeat-publication preservation after this layout
change. Host Fabric/NeoForge builds, 91 Rust unit tests, three native GPU tests,
the GPU vine continuation fixture, and Nether/Overworld/Superflat regressions pass.

This resolves the order of emitted writes. Feature evaluation still reads its own
anchor's earlier writes; dependencies on accepted writes from other anchors remain
unfinished. Production Nether activation remains deferred.

## Cycle 3i: dependencies on neighboring feature roots

Source-ordered replay now resolves material and heightmap reads against the
accepted output of preceding loaded roots, including writes from neighboring
anchors. Dependencies are evaluated on demand and can cross multiple chunk or
region boundaries; a fixed one-chunk halo no longer truncates the read chain.
Column and biome queries also resolve distant placement locations through the
same canonical GPU terrain tiles.

A root is a deterministic batch: anchors evaluate against preceding roots plus
their own live overlay, and their proposals commit in canonical Z/X anchor and
original command order. Anchors in the same root do not observe one another's
provisional attempts. This retains Retina's parallel anchor model rather than
reproducing Minecraft's chunk scheduling. Later roots see the committed result,
including ordinary replacement rules, paired plants, and heightmap changes.
Final distribution uses that same order for blocks and scheduled updates.

The exporter supplies conservative horizontal feature footprints; placement
program offsets are included separately when discovering possible writers.
Vertical block columns now explicitly export zero horizontal reach. A missing or
negative footprint in a source-ordered profile is an actual generation error,
not an arbitrary fallback radius. Legacy profiles do not require this metadata.

The dependency planner memoizes terrain tiles, anchor biome membership, feature
outputs, and accepted columns. Columns retain compact material runs, share
unchanged states across roots, and expand only when a command changes a block.
Independent target anchors use the existing Rayon pool. Recursive dependencies
only move to preceding roots; support checks within a committed root use strictly
earlier command prefixes. Cache locks are released before resolving dependencies.
GPU provider/count queries retain device timing and transfer accounting; the
optional detailed planner reports dependency wall time and cache counts separately
from the legacy replay phases. Nether feature geometry uses the existing native
implementations, without Java block callbacks or vanilla terrain fallback.

`netherFeatureTest` now checks 114 native chunks in 21 ordering fixtures. New
fixtures require two-hop neighboring dependencies in X and Z, heightmap reads of
accepted neighboring writes above the roof, and valid offset chains reaching 31
chunks away with biome filters. They cover negative coordinates and region edges,
with the legacy execution mode as an explicit control. Loaded primitive reference
writes must also remain inside their exported footprints. Native tests compare
footprint inversion with independent world-coordinate enumeration, rectangular
writer discovery with individual-column unions, and material-run lookups and all
six heightmaps with dense columns across offset storage ranges.

All three saved-region fixtures retain 983,040 independent block comparisons,
ordered fluid-tick parity, and byte-preserving repeated publication. The accepted
feature layout changes from cycle 3h because later roots can now observe earlier
neighboring writes. Within this cycle, cache/parallelism improvements preserve
all feature counts, tick counts and saved sizes relative to the first passing
implementation. Mixed, Crimson Forest and Warped Forest region generation times
fell from 14.61/7.88/11.63 seconds to 4.03/2.05/2.58 seconds on Metal / Apple M4 Max.
These are individual warm harness observations, excluding decoding/assertions;
they are not complete-Nether gameplay throughput or a repeated benchmark median.
An observed test-process RSS sample was approximately 4.1 GiB, including the JVM,
loaded registries and other harness state; it is not isolated planner allocation.

Host Fabric/NeoForge builds, 97 Rust unit tests, three native GPU tests, and
Nether profile/terrain, Overworld feature and Superflat regression suites pass.

The dependency path remains materially more expensive than cycle 3h's incomplete
read semantics. Further throughput/memory validation remains necessary before
production rollout. Structures, automatic activation, gameplay lighting and the
both-loader runtime matrix remain deferred.

## Cycle 4a: complete weighted structure sets

The exporter now retains every entry of a random-spread structure set that has
an executable member. Source entry order, weights, biome restrictions and placement
settings remain intact. Previously the Nether complex set lost fortress weight 2
and kept only bastion weight 3, assigning every eligible candidate to a bastion.

Definitions without a native adapter are marked `unimplemented` with an explicit
reason, preserving their source configuration and biome membership. Entries with
no possible source biome retain identity/weight without compiling an unrelated
dimension's structure configuration. A selected,
biome-eligible reserved definition creates no start and does not transfer its
selection to a supported sibling. Definitions with no possible source biome are
rejected normally. Fully unsupported sets remain outside the generation and locate
adapter. Startup diagnostics distinguish executable and reserved definitions.

Native selection also retries remaining weighted entries when an executable
candidate produces an empty start, as it already did for biome rejection. Each
attempt receives a copy of the current random state so geometry cannot advance
the independent candidate-selection stream. Existing successful first-attempt
geometry retains its random sequence. Empty/reserved candidates never produce
saved starts or positive locate results.

Run `./gradlew -PretinaHostOnly :common:netherStructureTest`. The fixture exports
actual Nether registries and templates and writes `build/nether/structure-profile.json`
and `structure-coverage.json`. It verifies all four loaded Nether families, complete
weighted entry identities/order/weights, biome membership, placement configuration,
structures disabled, an explicit empty source-set filter, and a fixed Plains biome
whose village set retains the other ineligible families. Coverage distinguishes
executable, reserved and unimplemented families rather than counting reserved
metadata as generated structures.

The native selection fixtures compare 288 candidate queries across three seeds and
positive/negative coordinates against an independent Java random-stream reference.
They cover original and overridden weights, biome rejection, empty pools, empty
selected elements, and a three-entry retry that consumes geometry randomness.
Repeated queries must remain identical without assembling chunks. These fixtures
retain Retina's existing candidate-selection seed mapping; they do not assert
identical Minecraft structure layouts for the same seed.

Host Fabric/NeoForge builds, 98 Rust unit tests, three native GPU tests, Nether
profile checks, the existing structure assembly/MCA metadata suite, and Superflat
regressions pass.

Fortress geometry, fossils, Nether ruined portals, complete Nether bastion gameplay
validation and automatic activation remain unfinished. Reserved selection slots are
an intermediate rollout behavior, not implementation of those missing structures.

## Cycle 4b: fortress piece export

Startup export now captures the loaded Minecraft fortress piece implementations
into 60 templates: 13 fixed piece families in four horizontal orientations, with
separate chest/no-chest variants for both corridor turns. This remains export data
for the next native assembly cycle. Fortress definitions stay reserved; production
Nether activation is still deferred.

The export executes each piece factory and `postProcess` once per variant. It
retains source block states, blaze-spawner NBT, Nether bridge loot tables, lava-well
fluid ticks and shape postprocessing positions. Each orientation is captured
directly because procedural structure orientation includes mirroring, and cannot
be represented by rotating one template. Collision bounds remain separate from
physical template extents: the throne's upper fence extends one block above its
collision box.

Support columns are captured against a floor two blocks below the piece origin.
The resulting single support layer becomes a list of downward column starts,
not a baked pillar or a projection onto the Nether roof. The source replaceability
table is completed after all material exporters finish. Native placement will use
these starts and the actual blocks below the piece to determine each pillar's
length. Seeded bridge end fillers export their ordered random-length row recipe,
so the native assembler can produce different rubble per piece.

The `structures.fortress` contract contains:
- `pieces`: named fixed piece families, each with `orientations` in Minecraft's
  horizontal data order (south, west, north, east). Each orientation has one
  variant, or two for corridor turns (without/with chest).
- Each variant supplies a template index, collision `bounds` relative to the
  attachment point, and `supports`, `ticks` and `post_processing` relative to the
  collision box minimum. Templates already contain oriented block states and
  coordinates; they must be placed without an additional rotation.
- `end_filler`: oriented attachment bounds, material rotations and ordered rows
  `[x, y, nextIntBound]`. A piece-local Java-compatible random stream chooses the
  inclusive final Z coordinate for each row, starting at Z=0.
- `support_replaceable`: final material IDs accepted by Minecraft's downward
  support predicate. Pillars also stop above the dimension minimum plus one.

`./gradlew -PretinaHostOnly :common:netherStructureTest` now replays the exported
blocks and supports against actual source `postProcess` results at four floor
depths. All 240 oriented/variant/depth fixtures pass, comparing 630,200 writes,
including the source lower support bound. The suite separately checks exact
block-entity NBT, four blaze spawners, eight chest variants, four deduplicated lava
updates and 1,380 shape postprocessing positions. Another 32 fixtures compare
seeded end-filler recipes against Minecraft, including negative and extreme
integer seeds. Every exported material's support eligibility is checked against
the source predicate. Disabled and biome-ineligible exports contain no fortress
kit. The existing 288 weighted-selection checks still pass.

Host Fabric/NeoForge builds, 98 Rust unit tests, the existing structure
assembly/MCA metadata suite and Superflat regressions pass.

The report is written to `build/nether/fortress-export.json`; the full piece kit
is in `build/nether/structure-profile.json`. These checks validate startup data,
not native connected fortresses or gameplay. Native graph assembly, terrain-aware
support placement, scheduled update installation, saved fortress starts and
spawning/progression validation remain in the next fortress cycle.

## Cycle 4c: connected native fortresses

Fortress selections now create native starts. Rust assembles the bridge and castle
families with the source weights, placement limits, repeat rules, collision checks,
attachment offsets, randomized pending-piece order, depth/range limits and vertical
shift. Corridor chests and end-filler rubble vary deterministically with the plan's
random stream. No Java callbacks occur while native chunks are generated.

The planner queries biome eligibility at the source generation point (chunk minimum
X/Z, Y=64). It does not project the start onto the highest Nether roof. Complete
pieces must fit the request's storage bounds. Downward supports inspect actual
placed blocks until reaching a non-replaceable block or the source lower limit;
they use neither the Overworld terrain beard nor a fixed pillar length. Fortress
loot seeds include world position, so matching local chest coordinates in different
chunks do not share a seed.

The chunk and MCA paths retain blaze-spawner NBT, loot tables, lava-well fluid ticks
and packed shape postprocessing positions. Feature and structure updates merge by
fluid/block identity and position with the first scheduled update retained. Java
prototype chunks install the same updates and postprocessing positions as the MCA
serializer. Saved starts use the actual registered fortress identity and source
piece bounds, allowing Minecraft's existing monster spawn overrides. References
cover the entire fortress bounding box, including gaps between pieces, because
Minecraft also checks the outer fortress box over Nether-brick floors.

The Nether structure suite compares 12 native plans, containing 1,365 pieces and
all 14 families, with Minecraft's actual assembler using the same internal random
stream. It checks piece order, kinds, chest variants, orientation, collision bounds,
vertical shifting, depth, decoded structure identity and native locate candidates.
Retina's world-seed-to-plan mapping remains its existing approximation; this does
not claim the same fortress locations as vanilla for a world seed.

A placement fixture executes the source pieces against Retina's actual GPU Nether
terrain and compares every block in the containing chunk rectangle. It also checks
full block-entity data (excluding intentionally different loot seeds), unique loot
seeds, lava updates, shape updates, prototype-chunk installation, concurrent replay,
MCA block/metadata equality across region boundaries, and saved start reopening.
The selected layout spans 140 chunks and four regions, with two blaze spawners,
seven chests with distinct loot seeds, one lava-well update and 1,403 support blocks
below the main structure box. All 9,175,040 chunk voxels match the source placement
reference, and the same voxels survive MCA serialization. The fixture deliberately
isolates structures from decoration to make terrain and piece differences observable. Reports and inspectable region files are written to
`build/nether/fortress-assembly.json` and a fresh `build/nether/fortress-regions-*`
directory (recorded in `fortress-regions-path.txt`). Each run uses new output because
MCA publication intentionally preserves existing chunks, including earlier fixture
terrain generated with a different seed.

`./gradlew -PretinaHostOnly build :common:netherStructureTest :common:structureTest
:common:flatTest :common:nativeGpuTest :common:netherFeatureTest` passes, including
both loader builds, 99 Rust unit tests, three GPU tests, structure/MCA regressions,
Superflat coverage and Nether feature/ore/survival/fungus reference fixtures.

This completes native fortress assembly and placement, not the Nether rollout.
The shared structure pipeline currently overlays structures after decoration;
source generation-step interleaving and feature reads of earlier structures remain
unfinished and must be resolved before production Nether activation. Actual server
mob spawning, portal travel and gameplay progression remain part of the runtime
matrix. Fossils, Nether ruined portals and Nether-specific bastion validation are
also still required.

## Cycle 4d: Nether fossils and dried ghasts

Fossils now use all 14 effective loaded templates, native base-column floor
searches, and the source biome check at the selected floor. Their height provider
uses the generation context: the bundled `below_top: 2` anchor resolves to Y=125,
not the top of the 256-block storage range. The native sampler shares the existing
height-provider implementation while retaining each caller's random stream,
including Minecraft's bounded draws for structure selection and degenerate
uniform/trapezoid ranges. Constant, uniform, biased, very biased, trapezoid and
nested weighted distributions are supported; unsupported provider encodings fail
profile loading with the definition name.

Floor searches use the same native/GPU base columns exposed to Minecraft, looking
for air above soul sand or a block with a sturdy upper face, and reject starts at
or below the configured sea level. Java exports actual registered block predicates
and template geometry at startup. Structure planning has no Java per-block
callbacks and does not project fossils onto the Nether roof or add Overworld
foundation pillars.

Template placement retains air in the surrounding terrain and rotates the loaded
block states. Minecraft 26.3's dried ghast uses its world-seed/position random
factory, 50% attempt probability, footprint position, air requirement and random
facing. Native chunk and region paths partition the same complete placement across
boundaries. Saved starts retain the registered fossil identity and piece bounds;
references include the source's 12-block terrain-adjustment margin. Locate queries
share the accepted floor/biome plan, including rejection.

The Nether structure fixture checks 1,152 source height-provider/floor outcomes
(301 accepted and 851 rejected), plus loaded biome eligibility, and executes
Minecraft's actual fossil piece `postProcess` against Retina base terrain for all
14 templates and four rotations. Every voxel in each containing chunk matches;
the cases cover 69 chunks, including 13 cases crossing chunk boundaries, and two
successful dried-ghast placements. A separate fossil spans four chunks and two MCA
regions; all 262,144 compared voxels survive serialization. It checks source-adjusted
bounding boxes, reference coverage, saved-start decoding, concurrent repeatability,
and MCA block/structure parity. Negative structure coordinates are included in the
provider/placement-plan checks. Fixtures isolate structures from decorations;
structure/feature generation-step integration remains a separate unfinished task.
Reports and a reproducible profile are written to `build/nether/fossil-*.json`;
`fossil-regions-path.txt` identifies the fresh inspectable MCA directory.

The host build and `netherStructureTest`, `structureTest`, `flatTest`,
`nativeGpuTest`, `netherFeatureTest`, and `netherProfileTest` pass, including both
loader builds, 99 Rust unit tests, and three native GPU tests. The final cross-region
save fixture also passes in a focused rerun.

Production Nether activation remains deferred. Ruined portals, Nether-specific
bastion validation, structure/feature ordering, runtime gameplay, and the remaining
integration matrix are still required.

## Cycle 4e: ruined-portal processors

The shared native template pipeline now implements `block_age`,
`blackstone_replace`, and `lava_submerged_block`. These are prerequisites for
Nether ruined portals; portal starts and placement are still deferred.

Java exports the actual blackstone replacement results, loaded stair/slab/wall
membership, property-preserving mossy variants, and full outline-shape predicates.
Every possible processor output is registered before material flags and lighting
are built. The final table pass includes palette inputs added later by decoration;
it does not require additional output states. Superflat now performs that final
structure-material pass as well. Native profile loading rejects incomplete or
out-of-range processor tables before compiling them.

Rust reproduces block aging's positional random stream and draw order, including
both independently sampled stair alternatives, mossiness as a Minecraft float,
and rotation of generated stair states. Blackstone replacement retains exactly
the source's selected properties, including its deliberate reset of other stair,
slab and wall properties. Lava submersion tests the original world block and the
processed block's full outline shape. A conversion to lava cannot retain a chest
block entity. Processor ordering and protected-block behavior remain observable.
The runtime path uses native tables and random draws without Java callbacks.

The Nether structure suite compares the native results with actual Minecraft
processor calls for 24 profiles, four rotations, and 1,699 input placements per
template: 163,104 source comparisons. Inputs include complete stair, slab, wall,
and iron-bar property families; repeated full blocks and obsidian; and chest,
lava and gold states. The fixtures exercise three mossiness values, independent
processors, ordered chains, air/lava/bedrock worlds, and retained or discarded
block entities. All containing chunk voxels are compared, including untouched
background. A separate export check adds late palette inputs and verifies that
the processor output palette stays closed. Reports and the reproducible chained
fixture are written to `build/nether/portal-processors.json` and
`portal-processor-fixture.json`.

Portal template selection, mirrored geometry, source placement, surrounding
netherrack and magma, loot, and integrated save validation remain required in the
next subcycle. This processor milestone does not enable Nether activation or
claim completed ruined-portal support.

## Cycle 4f: source template transforms

Templates can now carry an explicit mirror and rotation pivot. The native path
transforms geometry and bounds around that pivot, runs processors on the original
block states, and applies the registered mirror-then-rotation state map afterward.
This preserves handed stair shapes, oriented replacement states and the directions
of stairs created by aging. Rule processors use independent positional random
streams in this path, including repeated processors; location predicates inspect
the actual world state without rotating it. Existing templates retain their
previous placement path unless they opt into the transform contract.

The exporter closes the material palette under mirror and rotation before dependent
placement tables, flags and lighting are finalized. Processor output tables and
tag predicates are refreshed for added materials. Native validation checks the
transform map's size and IDs. Geometry bounds, candidate reach, chunk partitions,
connector directions, fractional entity positions, attachment anchors, direction
codecs and entity yaw use the same configured transform.

`netherStructureTest` now compares 304,980 processor outputs against Minecraft,
including 117,300 comparisons with explicit transforms. The 39 fixtures cover all
three mirror modes, four rotations, a nonzero asymmetric pivot, complete stair,
slab, wall and bar property families, repeated positional rules, air/lava/bedrock
backgrounds and retained block entities. All 156 template bounding boxes match
Minecraft. A late-palette fixture verifies transform closure and refreshed source
tag membership. A mirrored fixture crosses two chunks and two MCA regions; all
131,072 voxels, block entities, starts and references survive serialization, and
concurrent replay produces the same blocks. Native entity tests additionally
exercise mirror/pivot placement and all attachment direction encodings.

The host build, `netherStructureTest`, `structureTest`, `flatTest`,
`nativeGpuTest`, and `netherProfileTest` pass on Metal / Apple M4 Max, including
Fabric and NeoForge builds, 99 Rust unit tests and three GPU tests. A focused
final run also passes the late-palette and cross-region fixtures.

The source transform contract is a prerequisite for ruined portals. No production
structure definition uses it yet. Full ruined-portal template selection, Nether
floor placement, netherrack/magma spread and drips, shape updates, loot-stream
integration, and source-step interaction with decoration still need implementation
and validation before Nether activation. The synthetic transform fixture does not
claim full portal `postProcess` or gameplay parity. Reports are in
`build/nether/portal-processors.json` and `portal-transform-mca.json`; the fixture
profiles and `portal-transform-regions-path.txt` retain reproducible inputs and
inspectable MCA output.

## Cycle 4g: Nether ruined-portal placement

The registry export now includes all 13 effective ruined-portal templates and
executable Nether portal definitions. Native selection preserves weighted setups,
air-pocket probabilities, giant-template selection, mirrors, rotations, four-corner
floor search, and the biome restriction at the chosen origin. Set weights still
retain the other, biome-ineligible portal families.

Native placement applies the ordered processors to the original states, then
transforms the outputs and consumes one shared loot/spread random stream. It
replays the complete footprint once before distributing writes among chunks.
Placement includes stairs, wall and bar neighbor shapes, jigsaw final states,
netherrack/magma spread and drips, optional vines and overgrowth, waterlogging,
fluid ticks, and water block ticks for bubble-column support. Registered fluid
acceptance predicates prevent double slabs from becoming waterlogged. Template
entities share the existing transform and serialization path.

The source comparison runs Minecraft's actual `RuinedPortalPiece.postProcess`
against the same initial native terrain. It covers every template with all four
rotations and both mirrors, 24 weighted custom-setup cases at negative coordinates,
and eight flooded placement cases. All 136 replays match across 1,200 chunks and
78,643,200 voxels, including untouched terrain, normalized block-entity NBT with
exact loot seeds, and ordered fluid/block ticks. The custom cases exercise three
mossiness values, both air-pocket outcomes, blackstone replacement, vines, and
overgrowth. A nine-chunk footprint crosses four MCA regions; saved blocks,
metadata, starts and references are compared, and the start reloads through
Minecraft's structure loader. A fresh native profile also replays that footprint
concurrently in reverse request order.

Validation passes with `./gradlew -PretinaHostOnly build
:common:netherStructureTest :common:structureTest :common:flatTest
:common:nativeGpuTest :common:netherProfileTest` on Metal / Apple M4 Max.
This includes Fabric and NeoForge builds, 99 Rust unit tests and three GPU tests.
The flooded cases exercise 122 waterlogged placements and 55 source block-tick
scheduling calls, with repeated ticks deduplicated as in protochunks.

Reports are written to `build/nether/portal-placement.json` and `portal-mca.json`.
`portal-fixture-profile.json` and `portal-regions-path.txt` retain the customized
input and inspectable output. Exact vanilla world-seed layouts remain outside
Retina's approximation contract; the comparison supplies the same placement and
postprocessing random stream to both implementations.

This milestone accepts `in_nether` setups with `can_be_cold=false`. Other placement
or cold-biome setups remain reported as unsupported. Neighbor-shape coverage is
for the block families in the loaded vanilla portal templates, not arbitrary
custom block behavior. Production Nether activation remains deferred. Portal
replay currently reads a carved base-terrain snapshot; integration with earlier
structures and features at the proper generation step is still required. The
Java structure-state advertisement and live `/locate`/gameplay checks are also
part of the remaining integration work.

## Cycle 4h: shared structure and feature generation steps

Source-ordered profiles now place structures and features in one native dependency
stream. Structure definitions retain registry order within their configured step,
followed by that step's sorted feature roots. Loaded bastions and ruined portals
run in `surface_structures`; fortresses and fossils run in
`underground_decoration`, before the Nether ores in that same step. This follows
the loaded Minecraft definitions rather than assuming all structures run last.

Structure processors, supports, and portal postprocessing read earlier accepted
writes. Later features read the resulting blocks and heightmaps, including across
chunk and region boundaries. Portal replay still computes the complete footprint
once, now against its preceding generation stages. Start/locate planning does not
render a portal or populate its placement cache with a base-only snapshot.

Chunk and MCA replay share the ordered commands and metadata events. Accepted
changes to a different block remove old block entities; state changes within the
same block retain their data. Rejected placements do not remove data. Scheduled
block/fluid ticks preserve the first type/position update in generation order,
even when a later write replaces its block. Structure entities and shape updates
are retained. Existing non-source-ordered profiles retain their legacy pipeline.
Combined dependency planning and command replay use the existing vegetation-plan
and vegetation timing counters; these are not feature-only costs in this mode.

`netherStructureTest` includes a synthetic feature/structure chain with two
structure steps, cross-chunk predicates, heightmap placement, chest replacement,
negative coordinates, and a deliberately wrong-step control. Eighteen independent
chunks and cold concurrent reverse replay agree; nine chunks also match saved
blocks and metadata across four regions. Rust tests separately check rejected
writes, same-block state changes, replacement without stale data revival, and
interleaved tick order and deduplication.

The full loaded five-biome profile is also generated with terrain, decorations,
structures and lighting enabled together. The initial 1,024-chunk negative-X
region contains a fortress, six fossils and a ruined portal, seven block entities,
and 9,798 fluid updates, along with glowstone, quartz, gold, ancient debris and
both forest stems. Eleven corner/interior/structure probes compare 720,896 voxels
and metadata with independent chunks, then with a cold profile requested
concurrently in reverse order. Start-only queries precede cold generation, and
repeat publication preserves the region byte for byte. This region did not
contain a bastion; dedicated bastion coverage remains required.

Run the combined fixture independently with:

```sh
./gradlew -PretinaHostOnly :common:netherStructureTest --args=--combined-only
```

Reports are in `build/nether/combined-generation.json`, with the saved region
location in `combined-regions-path.txt`. The synthetic profile and region paths
are in `generation-step-fixture.json` and `generation-step-regions-path.txt`.
The first combined region generation/publication took 11.06 seconds on Metal /
Apple M4 Max; that harness observation includes structures and lighting and is not
a comparison with earlier terrain-only or decoration-only measurements.

The host Fabric/NeoForge build and `netherStructureTest`, `structureTest`,
`flatTest`, `nativeGpuTest`, `netherFeatureTest`, and `netherProfileTest` pass,
including 102 Rust unit tests and three native GPU integration tests.

Production Nether activation remains deferred. Bastion-specific validation,
applicable structure-set exclusions, Java structure-state advertisement, live
locate/spawning/progression, dimension integration and the final runtime matrix
remain required.

## Cycle 4i: bastions on Nether terrain

All four loaded bastion root families now have dedicated Nether fixtures: housing
units, hoglin stables, treasure rooms and bridges. The fixtures isolate each root
pool entry while retaining the actual Nether density/surface programs, loaded
biome eligibility, configured start height, child pools, processors and templates.
They cover positive and negative coordinates and do not project starts onto the
bedrock roof. These are generation and persistence checks, not a claim of exact
vanilla seed layouts or live gameplay validation.

The checks found repeated loot seeds for template chests at matching local
coordinates in different chunks. Template loot now consumes the existing
world-position random stream, as fortress loot already does. This also fixes
other template structures. Existing saved chunks are not rewritten.

Eight layouts contain 820 pieces across 144 chunks and 24 saved region files.
They preserve 60 loot containers, 273 template mobs and two magma-cube spawners.
Every changed block stays within the loaded piece bounds; block entities load
against their final states, loot seeds and mob UUIDs are distinct within each
layout, editor blocks are absent, and starts retain the registered bastion
identity. Cold concurrent reverse generation agrees with the initial blocks and
metadata. MCA decoding compares all 9,437,184 voxels and structure/entity metadata,
and saved starts reopen through Minecraft's structure loader.

A second fixture uses the unmodified loaded profile and naturally selected
weighted starts. Its 1,024-chunk region contains two bastions, 23 fossils and a
ruined portal together with normal vegetation and ores. Ten independent chunks
compare 655,360 voxels and metadata, followed by cold concurrent replay and
byte-for-byte preservation on repeated publication. The region retains 12 block
entities and 10,706 fluid updates, including one flowing-lava update from
structure processing. Combined-region validation now decodes registered fluid
identities and deduplicates by type/position; the spring-only assertion remains
specific to the isolated feature suite.

Run the focused fixture with:

```sh
./gradlew -PretinaHostOnly :common:netherStructureTest --args=--bastions-only
```

`build/nether/bastion-generation.json` records each layout and its region directory;
`bastion-fixture-0.json` through `bastion-fixture-3.json` retain reproducible inputs.
`bastion-combined-generation.json` and `bastion-combined-regions-path.txt` describe
the unmodified profile's region. Its first measured generation/publication took
7.78 seconds on Metal / Apple M4 Max; this is a different workload from the
negative-X region and is not a performance comparison.

The host Fabric/NeoForge build and `netherStructureTest`, `structureTest`,
`flatTest`, `nativeGpuTest`, `netherFeatureTest`, and `netherProfileTest` pass,
including 102 Rust unit tests and three native GPU integration tests.

Production Nether activation remains deferred. Structure-set exclusion closure,
Java structure-state advertisement, actual spawning/loot gameplay, dimension
integration and the final runtime matrix remain required.

## Cycle 4j: structure-set exclusion dependencies

Structure export retains the transitive placement dependencies of each generated
set. A referenced set can suppress placement even if none of its structures can
start in the current biomes or if the source's structure-set override excludes it.
Such sets carry only placement rules: they cannot generate structures, enter
native start queries, or appear as locate candidates. A dependency that is also
selected for generation keeps its original weighted entries and registry order.

Required unsupported placement implementations fail export with the dependency
path instead of silently losing the exclusion. Custom random-spread subclasses
are not treated as the base implementation. Native profile registration rejects
missing references, cycles, duplicate set names, invalid ranges and malformed
dependency sets.

The fixture compares 1,536 native candidate decisions against Minecraft's actual
`isStructureChunk` implementation across six loaded-registry variants, four
seeds, positive/negative coordinates, linear/triangular spread, all four frequency
reducers, and nested exclusions. It observes 795 accepted candidates and 369
otherwise eligible candidates suppressed by exclusions. It also verifies cached
queries, source-set overrides, dependencies shared with generation, locate
filtering, and diagnostics for unsupported rings/custom placement classes.

```sh
./gradlew -PretinaHostOnly :common:netherStructureTest --args=--exclusions-only
```

Reproducible native profiles are in `build/nether/exclusion-fixture-*.json`.
`exclusion-parity.json` records the Minecraft decision at each candidate.
The host Fabric/NeoForge build and `netherStructureTest`, `structureTest`,
`flatTest`, `nativeGpuTest`, `netherFeatureTest`, and `netherProfileTest` pass,
including 103 Rust unit tests and three native GPU integration tests.
Production Nether activation and live gameplay validation remain deferred.

## Cycle 4k: Java structure search state

Java structure-state construction now includes supported Nether ruined portals.
It shares the exporter's structure implementation/configuration decision rather
than maintaining a separate family list. Effective portal setups are read before
profile export, when Minecraft constructs its search state: all setups must use
Nether placement without cold-biome processing. Other placements and custom
structure subclasses remain unavailable.

Mixed sets retain only supported definitions in Java's search metadata. Native
generation still receives every source weighted slot, so this search view cannot
redistribute failed/unsupported selections. The registered structure set and saved
generator definition remain unchanged. Flat source structure overrides continue
to limit the advertised sets.

The focused fixture checks all four loaded Nether families across the full
five-biome source and each fixed biome, explicit empty/portal-only flat overrides,
and six customized definitions. It verifies accepted Nether portal settings,
rejected cold/surface/mixed setups, custom portal/fortress subclasses, mixed-set
search filtering, native slot/weight preservation and unchanged source encoding.

```sh
./gradlew -PretinaHostOnly :common:netherStructureTest --args=--state-only
```

`build/nether/structure-state.json` records the loaded source results. These checks
exercise Minecraft's placement lookup used by structure search; actual server
commands, spawning and survival progression remain runtime validation work.
The host Fabric/NeoForge build, 103 Rust tests, three native GPU tests, and
`netherStructureTest`, `structureTest`, `flatTest`, `locateTest`,
`netherFeatureTest`, and `netherProfileTest` pass. The existing Overworld structure
test now accepts direct holders in filtered search views while still requiring
the registered village placement.
Production Nether activation remains deferred.

## Cycle 5a: automatic activation and dimension identity

The server adapter now accepts supported Nether noise and flat sources while
retaining their original `LevelStem` definitions. Exact supported generator and
biome-source classes remain required. The End and unsupported implementations keep
their source generator and diagnostic; failures after acceptance are propagated.
Inline compatible settings and dimension changes remain intact. Nether generation
semantics do not depend on the dimension's gameplay `has_ceiling` flag.

Native profile identity, temporary region storage, promotion logs and telemetry
carry the actual dimension key. Identical coordinates in separate dimensions
cannot share native cache entries because the profile identity includes that key.
Client telemetry is displayed only for the current dimension. Region preparation
continues to use the shared global budget.

The first live dedicated test exposed a nested Rayon deadlock: a structure-plan
join inside an unfinished dependency memo could steal a decoration job that waited
on that same memo. Dependency builders now plan structures inline; independent
decoration anchors and top-level structure planning remain parallel.
`netherConcurrencyTest` exercises a complete negative-coordinate region at normal
worker counts, independent chunk parity, concurrent reverse-order probes, metadata
and repeat-save preservation.

The automatic runtime harness now checks both original source definitions,
dimension identity and independent profiles, then saves and reopens edits in the
Overworld and at Nether Y=200. These checks do not establish portal travel,
survival progression or the complete lighting matrix.

Fabric and NeoForge dedicated creation/second-process reopening and integrated
creation/disconnect/reopening passed on Java 25 / macOS / Metal. Both integrated
runs also passed strict loaded-block and heightmap comparisons. The host build,
103 Rust tests, three GPU tests, metrics, scheduling, preview-cache lifecycle,
normal-worker Nether concurrency, Nether/Overworld structures, Superflat, locate,
Nether features and profile checks passed. The cold replay fixture now uses an
independent profile namespace instead of deduplicating onto its original profile.

## Cycle 5b: portal destination preparation and lifecycle QA

Ordinary Nether portals now prepare destination regions through the existing
asynchronous region scheduler before allowing Minecraft's portal search and
creation to proceed. The range uses the effective dimension coordinate scale,
world border, vanilla search/frame reach and entity-ticking halo. Publication uses
the existing I/O queue and global generation budget. Accepted generation failures
propagate; other portal implementations and non-region generators keep their
existing behavior.

Dedicated testing exposed a second blocking load after the destination was ready:
the player's cached supporting block still referred to the source dimension.
The next movement tick read those unscaled coordinates in the destination and
generated unrelated remote regions. Changing to a Retina dimension now clears
that stale support position.

The opt-in `retinaNetherLifecycleQa` harness exercises real portal collision and
dimension transfer, decoy portals to detect incorrect scaling, and a remote
destination with no prebuilt Nether exit. Each connection makes two round trips.
The first remote visit generates exactly four destination regions; reopening
requires zero new regions and intact saved portal frames. It checks simultaneous
dimension reads, duplicate-observer cancellation, native light over 16 interior
chunks, runtime lava/glowstone/shroomlight propagation and removal across a chunk
boundary, zero skylight, dimension telemetry and saved edits at Nether Y=200.
See [runtime QA](RUNTIME_QA.md) for commands and recorded results.

## Cycle 4l: live fortress progression

The opt-in `retinaNetherProgressionQa` harness uses a connected player to validate
native fortresses through Minecraft's actual gameplay APIs. The interactive
`/locate` dispatcher retains the player source and empty result callback, delivers
the normal chat result asynchronously, and finds the same start before generation,
after gameplay, and after reopening. Loaded starts, all piece bounds, references,
chests and blaze-spawner block entities are checked across the fortress footprint.
Lazy block-entity NBT is accessed through Minecraft's normal chunk API.

Survival block interaction opens the original fortress loot table. The resulting
inventory and a player edit are retained through save/reopen. Minecraft's natural
spawn-placement routine creates both blazes and wither skeletons on the generated
fortress with its registered spawn weights, Nether-brick floor rule, player
distance, light and collision checks. The controlled attempts bypass the random
chunk/height lottery and mob cap; they do not establish normal spawn rates.

A separate check disables natural spawning temporarily and waits for an untouched
generated spawner. The connected survival player uses ordinary attacks to kill a
spawned blaze and picks up a newly dropped rod. The harness supplies a sword and
defensive effects, restores the spawning gamerule, and removes the effects when
finished. No fortress geometry or spawner configuration is replaced for this test.

During progression QA, the existing remote block/heightmap comparison prepares its
region asynchronously and must finish before the player returns. This fixes a
test-induced dedicated-server timeout: the old synchronous comparison waited more
than 60 seconds behind concurrent fortress-region preparation. Ordinary runtime
generation and other QA modes keep their existing behavior.

See [runtime QA](RUNTIME_QA.md#live-nether-fortress-progression) for exact commands,
events, fixture side effects and coverage limits. The End remains on its original
generator.

The final matrix passed 12 progression sessions: fresh dedicated worlds with
reconnect and full process restart on both loaders, plus integrated creation and
save/reopen on both. Seed 42 exercised a 103-piece fortress over 49 chunks;
seed 123456789 exercised 134 pieces over 180 chunks crossing two regions. The
host build, scheduling/configuration/profile checks, 19 portal-boundary cases and
103 Rust unit tests passed. This milestone does not establish visual quality,
ordinary spawn rates or throughput.

## Cycle 4m: live structure references and template mob initialization

Native structure references now follow Minecraft's adjusted whole-start box in
X/Z, including gaps between pieces and the 12-block terrain-adaptation margin.
They retain Minecraft's eight-chunk source-search limit and do not depend on the
structure's vertical intersection with chunk storage. Candidate planning includes
the margin. A live bastion test exposed the previous piece-only references;
focused fixtures cover margins, gaps, negative coordinates and vertical offsets.

Jigsaw single and legacy-single elements now export their source
`finalizeEntities` behavior. Native publication marks these template entities for
one-time initialization. Retina completes that initialization in Minecraft's
ordinary spawn-generation stage using the same `WorldGenRegion` context as
`StructureTemplate`. Fabric uses vanilla's initializer; NeoForge uses its
`FinalizeSpawnEvent` hook and honors spawn cancellation. This supplies brute
equipment and home memory while retaining source template properties. It
introduces no Java terrain-generation callbacks.

The finalized entity NBT replaces the pending record before the chunk reaches
SPAWN status. Existing saved mobs without the marker are preserved, and reopening
retains player equipment edits. The same source setting applies to supported
jigsaw templates outside the Nether. An earlier registration-time attempt
exposed a recursive FULL-chunk wait in `ServerLevel.getCurrentDifficultyAt`;
using the generation region avoids that dependency and follows Minecraft's
original structure-placement context.

The opt-in live scenario covers bastions, Nether fossils and Nether ruined
portals on ordinary registered settings. It checks interactive locate, loaded
starts/references, piece bounds, generated blocks, original chest loot, native
mob UUIDs and save/reopen behavior. See
[runtime QA](RUNTIME_QA.md#live-bastions-fossils-and-nether-ruined-portals) for
commands, fixture edits and limits.

The final matrix passed all 12 live structure sessions across both loaders,
dedicated fresh/reconnect and full process restart/reconnect, and integrated
creation/save-reopen. The seed-42 bastion had 174 pieces, 41 template mobs and
15 brutes; seed 123456789 had 89 pieces, 32 mobs and eight brutes. NeoForge
initialization hooks had unique UUIDs, with no new hooks on dedicated restart.
Both host builds, 104 native unit tests, all eight bastion layouts, shared
structure regressions, Nether profiles and 136 portal source replays passed.
An additional exact replay confirms the integrated fixture's chestless portal
matches Minecraft's lava processing.

## Cycle 5c: in-flight world unload

The opt-in `retinaNetherUnloadQa` scenario queues cold regions in the Overworld
and Nether through their actual I/O workers, using the shared native budget.
It cancels duplicate read observers, then shuts down the real server while
generation is running. Loader unload callbacks record active generation and
pending requests before Retina closes its previews.

This exposed a cold-restart shutdown race on NeoForge: the mod module could close
before a native call returned and first loaded `ColumnCache`. Its canceled
observer had already received a close result, so that late `NoClassDefFoundError`
was hidden from the caller. The storage coordinators reject pending reads as
their I/O queues close. The server-stopped hook then waits for actual native
generation completion before loader teardown, after every dimension has closed.
Preview closure retains the completion snapshot even when the loader's earlier
unload callback has already retired the cache entries. This drain does not wait
for read leases, whose existing ownership and cleanup rules remain intact.

An independent observer waits for every request's terminal result and verifies
that late native completion releases its leases, removes both temporary preview
directories and leaves no generation in flight or hidden generation failures.
The integrated client waits for
that observation after closing the world, then reopens it. Dedicated runs repeat
in a new process. Both paths verify original generator definitions, the unchanged
End and saved edits in both dimensions, including Nether Y=200.

This scenario tests normal shutdown during active generation. It does not kill
the process or claim abrupt-crash durability. Native calls already running finish
and release their temporary data; pending requests receive explicit close
results. All I/O queues drain before the native completion wait; generation does
not depend on a server callback. See
[runtime QA](RUNTIME_QA.md#in-flight-nether-world-unload).

Validation on the final implementation covered eight server lifetimes on Fabric
and NeoForge, dedicated fresh/restart and integrated create/reopen, with 290
passing QA events. All 304 owned requests terminated: five completed before
storage close and 299 returned explicit close results. Sixteen preview cleanups
finished with no generation failures, and saved region inspection found no
publication after storage close. Cache lifecycle, request scheduling and
configuration checks, native Fabric region publication and both host builds
passed. Installed DH/Chunky coverage and performance measurements remain part of
the final cycle.

## Cycle 6a: source customization, previews and partial saves

`netherCompatibilityTest` exercises all five fixed Nether biomes and the normal
climate source with full decoration and structures disabled. Each uses the
production synchronous biome/terrain entry points used by DH-style preview
requests, compares 65,536 blocks with an independently generated native chunk,
checks source biome palettes and zero skylight, then promotes its temporary MCA.
Removing one saved chunk and adding a player marker to another exercises partial
repair: the missing record returns without native regeneration, all 1,023
existing compressed records remain byte-identical, and the marker survives.
Preview closure removes its temporary directory. This is a direct API test;
installed DH coverage is separate.

The same task exports an actual datapack fixture from loaded Minecraft codecs
under `build/nether-compatibility/datapack`. It changes Nether noise bounds to
`[16, 112)`, sea level to 40, ceiling to false, logical height to 192 and coordinate
scale to 4. Storage remains `[0, 256)`. The ordinary Normal and Flat presets use a
fixed Warped Forest Nether; their Overworld and End definitions remain intact.

The opt-in `retinaNetherCompatibilityQa` runtime scenario reads these effective
loaded settings, checks native output outside the configured bounds, lava and
solid output, absent structure starts when structures are disabled, live biome
palettes, and source serialization across save/reopen. Existing automatic
activation checks also verify the unchanged End and saved edits in both
dimensions. The client harness can disable structures for a disposable world
using `retinaClientNoStructuresQa=true`.

The final runtime matrix passed on Fabric and NeoForge, dedicated fresh/process
restart and integrated create/reopen, for ordinary Normal worlds and customized
Flat worlds. Twelve invocations covered sixteen server lifetimes and 370
passing events. All eight saved worlds retained their source definitions,
disabled structure setting, Overworld/Y=200 Nether edits and unchanged End.
The six native preview cases passed 393,216 independent block comparisons and
preserved 6,138 existing compressed records during partial repair.

Regression checks passed for vanilla registry loading, all nine native Superflat
presets, 294,912 reference light comparisons, 96 flat feature fixtures, Nether
profile/height contexts and cache lifecycle. Both host builds passed. Fabric's
native publication and actual Chunky 1.5.3 selection/mixin fixtures passed;
installed-mod gameplay remains separate.

## Cycle 6b: installed Distant Horizons and Chunky

Reopening an integrated world replaces Chunky's provider, but DH keeps its
accessor and its old `listenerBound` flag. The installed test exposed the result:
Chunky completed the second task while DH no longer received its progress events.
Retina now observes the actual provider registration/unregistration and resets
DH's binding for each lifetime. Setup is synchronized to bind once under
concurrent chunk updates. The existing retry before Chunky's startup still
applies, and unrelated setup failures still propagate. No server references or
optional mod instances are retained by the lifecycle counter.

The warning audit also found DH reaching its decoration callback after world
unload had cleared Retina's previews. Decoration now captures the cache reference
and acquires a read only while that cache is open. A read accepted before closing
retains its normal lease and finishes; a callback arriving after closing does no
new work. Native and file-read failures on an open cache still propagate, and
ordinary closed-cache reads still report misuse. The GPU preview test covers
both sides of that close/read race and a late generator callback.

The opt-in `retinaNetherIntegrationsQa` scenario runs the installed mods against
an ordinary Nether. It starts a real Chunky selection, pauses while native
generation is active, waits for the stopped region pump, resumes the saved task,
and requires Chunky's completed chunk count and a completed Retina ahead pump.
It also observes DH's generator plan being disabled during Chunky progress and
restored afterward. DH restores that plan after its progress inactivity delay;
the test does not assume it remains disabled for the entire task.
Chunky removes a task from its active map when dispatch ends, before every
outstanding chunk load completes. The harness waits for the task's real
completion flag and count rather than treating map removal as completion.

After Chunky completes, the player travels beyond its generated halo. Completion
requires a successful native Nether region generation on a thread identified by
DH's own API, plus the normal block/heightmap comparison and client dimension
telemetry. The player returns to the Overworld, saves and reopens, and repeats
against a different cold area. Source retention, the unchanged End and saved
edits in both dimensions are checked on each lifetime.

The initial fixtures reused regions inside Chunky's or Minecraft's generated
surroundings and did not satisfy the fresh-generation assertion. The final
fixture uses a destination beyond Chunky, a 64-chunk DH radius, `CHUNKS_ONLY`
with `FEATURES`, and an above-roof spectator view. It observes actual renderer
requests without clearing production caches or synthesizing DH work. The earlier
`SURFACE_THEN_CHUNKS` attempts are retained as diagnostics, not completed
installed-mode validation. See
[runtime QA](RUNTIME_QA.md#installed-nether-distant-horizons-and-chunky).

Final installed runs on both loaders passed after both lifecycle fixes: 344
runtime events across four integrated-server lifetimes, four completed
625-chunk Chunky selections, and fresh native DH Nether generation on each
lifetime. Saved source definitions, edits and DH databases passed the offline
audit; sampled preview directories were absent after shutdown. Both loaders also
started without optional mods. The transformed Chunky tests, real GPU preview
close/read tests, cache lifecycle checks and host builds passed. These results
cover installed `CHUNKS_ONLY`/`FEATURES` integrated sessions, not an installed
dedicated-server pair or `SURFACE_THEN_CHUNKS` gameplay.

## Cycle 6c: repeatable throughput and memory

The native benchmark now uses exported dimension storage bounds and a biome
from the loaded source. `exportNetherBenchmarkProfile` exports the actual loaded
Nether definition, including compatible preset or dimension overrides, ordered
features, structures and lighting. The Overworld export remains available.

Two sequential twenty-region runs, each after five warmups, produced identical
NBT for 20,480 chunks. On the M4 Max host they measured 91.6 and 90.9 chunks/s,
with 4.557 and 4.555 GiB peak process RSS. The corpus contains all five biomes,
all four Nether structure families and the full storage height. An old/new
harness comparison also preserves 1,024 Overworld chunk records. Loaded custom
exports and focused request-context tests pass.

See [the full workload, counters and limits](NETHER_PERFORMANCE.md). This is a
complete native Nether baseline, not a speedup claim against earlier Overworld
workloads or a Minecraft rendering benchmark.

## Remaining cycles

6. Complete rendered visual QA and the final documentation/requirement audit.
