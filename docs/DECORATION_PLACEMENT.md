# Registry-derived decoration placement

Java exports the effective biome vegetation lists and their placed features once
when a world binds. Vanilla, datapack and mod registry overrides take the same
path. Rust samples the registered count providers and executes placement modifiers
in order; the old averaged `density`/`tries` fields are retained only for legacy
profiles. Counts after a heightmap remain nested attempt counts, not a second
independent per-chunk density. Heightmaps and filters preceding a nested count
execute once for the parent; child attempts inherit that result.

Random, simple, boolean and weighted selectors share their parent's seeded attempt
stream. Only one branch is eligible for each parent attempt. Nested counts retain
that branch's budget. Candidate replay keeps parent attempts together so one tree
variant cannot fill the whole canopy before the next variant gets its turn.

Each anchor chunk maintains a sparse local decoration overlay. Heightmaps include
previous accepted decorations using the game's exported heightmap predicates.
Block, block-tag, fluid and replaceability filters read this overlay over the GPU's
terrain columns. Boolean predicate combinations and basic vegetation survival-soil
checks are projected to palette IDs. Count, rarity, square scatter, XYZ offset,
heightmap, water-depth and biome modifiers retain their registered order.

This matters especially for Terralith's dark forest: its tree placement requests
155 attempts per chunk, with WORLD_SURFACE and a grass/podzol/dirt soil filter.
Checking only bare terrain let nearly every attempt succeed. Checking the live
canopy rejects attempts above earlier leaves while preserving the requested 155
attempts. There is no global density reduction or tree-count cap.

Registered noise-based counts and arbitrary threshold counts now use sparse
GPU batches with Minecraft's actual placement permutation. Signed ratios,
frequency/factor, offsets and the configured below/above counts are retained.
See [sparse GPU feature counts](GPU_FEATURE_COUNTS.md) for ordering, transfers,
precision and validation. Older native profiles without that permutation keep
the existing -0.8 vegetation threshold bit.
[Registered block features](REGISTERED_BLOCK_FEATURES.md) add bamboo, cactus,
sugar cane, kelp, seagrass and lily pads, plus sturdy faces and their support/fluid
predicates. New profiles replay complete feature commands in order; legacy JSON
profiles keep their previous global role replay. Removed blocks lower the live
heightmap before subsequent features.
Unsupported placement/feature kinds are still reported during export. Survival checks approximate soil
tags; they do not simulate every custom block's light or environmental conditions.
Tree shapes retain the native approximation. Canopy interactions between different
anchor chunks are independent to preserve parallel generation and request-order
stability; this is not a complete reproduction of vanilla's decoration scheduling.
Terrain and spatial count sampling stay on the GPU. Count batches return one
integer per unique sampling point, rather than a dense region noise grid.

Validation uses `./gradlew nativeUnitTest biomeTest datapackTest --no-parallel`:
shared selector budgets, nested counts, zero counts, rarity order, live canopy
rejection, selected heightmaps, offsets and water depth; actual vanilla and
Terralith registry export and Metal/native assembly. A forced Terralith dark-forest
fixture compares independently generated chunks with MCA blocks at negative region
boundaries. Existing biome checks also cover tree diversity and paired plants,
and datapack checks cover preview promotion and save/reopen.

The eight-chunk flat Terralith fixture produced 564 ground-level trunk columns
versus 1,796 with legacy placement, about 69% less trunk coverage. This measures
accepted trunk footprint, not the number of trees or a target density.
Changes apply to newly generated terrain after restarting the client.
