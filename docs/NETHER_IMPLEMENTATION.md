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

## Remaining cycles

3. Complete Nether feature and decoration coverage.
4. Bastions, fortresses, fossils, and Nether ruined portals with saved metadata.
5. Automatic activation and dimension lifecycle, lighting, and portal integration.
6. Both-loader runtime validation, regression coverage, visual QA, and benchmarks.
