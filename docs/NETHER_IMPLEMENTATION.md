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

## Remaining cycles

2. Native density clipping, enclosed terrain, fluids, biome and surface behavior.
3. Complete Nether feature and decoration coverage.
4. Bastions, fortresses, fossils, and Nether ruined portals with saved metadata.
5. Automatic activation and dimension lifecycle, lighting, and portal integration.
6. Both-loader runtime validation, regression coverage, visual QA, and benchmarks.
