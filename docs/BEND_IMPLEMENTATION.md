# Independent Retina Bend implementation

The goal is two usable world types: **Retina Rust**, preserving the existing
MCA generator, and **Retina Bend**, independently executing generation, lighting,
NBT, compression and MCA assembly in Bend. Java may share registry extraction,
scheduling, raw transport and file lifecycle. No Bend execution may silently
delegate computational work to Rust, Java generation/compression or handwritten
native/shader algorithms. Generated Bend code and its stock runtime are allowed.

This checklist tracks the full goal across separately reviewed and merged PR
cycles. A checked library component does not imply a usable Bend generator.
Exact vanilla or cross-backend seed agreement is not required.

## Inspected starting point

Initial upstream: `be76395` (2026-10-08). The primary checkout contains user-owned
lighting changes and is intentionally left untouched. No `.codegraph` directory
or repository-local `AGENTS.md` existed; supplied workspace instructions and
`docs/MANUAL_CHANGE_PROTOCOL.md` apply. An unrelated lighting worktree exists and
must not be modified or removed.

The current implementation is substantially larger than the earlier noise demo:
over 20,000 lines in the top-level native and Java worldgen files alone, with
additional decoration, shader and processor modules. Parity includes the actual
registered programs and feature families below, not just representative terrain.

## Parity inventory and acceptance evidence

All unmarked items remain required. Update this inventory as code is inspected
more deeply; retain the original requested scope.

| Requirement | Current Rust/shared source | Bend implementation / completion evidence |
| --- | --- | --- |
| Shared loaded registry export, including palette properties and climate intervals | `BiomeTerrainProfile.java`, `RegistryGpuProgram.java`, `native/src/profile.rs` | Pending: decouple export from immediate Rust registration, parse once in Bend |
| Noise stacks, density bytecode, splines and GPU interpolation | `native/src/program.rs`, `program/`, `program.wgsl`, `noise3.wgsl`, `simplex.wgsl` | Pending: reusable runtime kernels; existing experiment proves only simplex feasibility |
| Climate targets, biome selection, smooth boundaries and underground biomes | `climate.rs`, `climate.wgsl`, `RetinaBiomeSource.java` | Pending |
| Coastlines, shore materials, rivers and material predicates/layers | `ShoreMaterialProfile.java`, `column_program.rs`, `materials.wgsl` | Pending |
| Caves, ravines, rare surface entrances and cave decoration | `GeologyProfile.java`, `geology.rs`, `features.rs`, `caves.wgsl` | Pending |
| Lakes, aquifer fields and fluid barriers | `TerrainFeatureProfile.java`, `program/lake_sparse.rs`, `aquifers.wgsl` | Pending |
| Ore height/count/replacement/discard rules and GPU rasterization | `geology/`, `ore.wgsl` | Pending |
| Ordered decoration, registered counts, provider noise and placement modifiers | `DecorationProfile.java`, `decoration/placement*`, `counts/`, `provider_noise/` | Pending |
| Trees and decorators, giant mushrooms, fallen trees, disks, vegetation patches, block columns, bamboo, aquatic plants and attachments | `decoration.rs`, `decoration/`, `tree_shapes.rs` | Pending; port actual supported recipe variants, not only grass/tree examples |
| Jigsaw pools/templates/processors, structures spanning regions and locate queries | `StructureProfile.java`, `structures.rs`, `structure_processors.rs`, `queries.rs` | Pending |
| Structure entities/block entities/loot and attachment rotations | `nbt.rs`, `structures.rs`, `structure_processors.rs` | Pending |
| Snow, freezing, plant support and path/gravel restrictions | `LightingProfile.java`, profile material/survival tables, `region.rs` | Pending |
| Full 64-bit seeds, signed/distant coordinates and repeatable ordering | `ChunkRequest`, native hash/random routines | Pending: explicit word-pair representation in Bend |
| Block/biome palettes, bit-packed long arrays, heightmaps, modified UTF-8 NBT and current data versions | `region.rs`, `nbt.rs` | Pending |
| LZ77, fixed-Huffman DEFLATE, zlib/Adler-32 and stored fallback | `region.rs` uses libdeflater | Implemented in `bend/compression.bend`; independent fixture tests pass |
| Independent parallel chunk compression | Rayon chunk assembly in `region.rs` | Implemented Bend fork/join API; four streams verified at 1/2/4 workers; region integration pending |
| GPU lighting, interior-chunk validity, boundary handling and one final region write | `lighting.rs`, `lighting.wgsl`, `region.rs`, `LightSeams.java` | Pending |
| MCA sector tables, external records, preserving existing chunks and atomic publication | `region.rs` | Pending |
| Persistent engine, bounded scheduling/singleflight, cleanup and actionable failure propagation | `NativeTerrain.java`, `RegionCoordinator.java`, `TerrainQueries.java` | Pending |
| DH surface/height requests generate temporary whole regions, 1024-region cache, eviction and promotion | `TemporaryRegions.java`, `RegionCoordinator.java` | Pending: share coordinator/cache with selected backend |
| Backend selection survives datapacks, codecs, save/reopen and server lifecycle | `RetinaChunkGenerator.java`, preset/platform mixins | Pending |
| Exactly two selectable world types; Rust default; legacy chunk-save migration aliases | `world_preset/gpu*.json`, world preset tag, language keys and generator codec | Pending; do not advertise an incomplete Bend backend |
| Color-coded stage report and last-20-region derived throughput | `GenerationMetrics.java`, `TerrainDebugReport.java`, `timings.rs` | Pending: backend-neutral timing transport |
| Reproducible pinned runtime, packaging, licensing and supported-loader builds | `gradle/native.gradle`, Fabric/NeoForge modules, CI | Pending: pinned 2.0.36 experiment installer available; production integration incomplete |
| Independent format tests, region/concurrency/query/cache tests, actual Minecraft loading, edit persistence and datapack combinations | shared unit/GPU tests and QA harnesses | Compression tests implemented; all generation/runtime acceptance checks pending |
| Complete lit/compressed region comparison: startup, warm latency, throughput, memory, output sizes and load-in | existing native benchmark scripts | Pending; simplex experiment and file-driver timing are insufficient |

## Implementation cycles

1. **Compression core and tracked parity inventory.** Pure Bend zlib encoder,
   bounded stored fallback, independent format-boundary tests and parallel chunk
   stream API. No world selection or Rust execution changes. PR/merge evidence
   is available through the Git history and attached PR.
2. **Binary/NBT/MCA foundation.** Word pairs, compact buffers, complete typed NBT,
   paletted section packing, region records and independent parser validation.
3. **Persistent engine and shared registry transport.** Separate exported data
   from Rust registration, define backend-neutral contracts, process lifecycle
   and request coalescing without exposing a misleading ready world type.
4. **GPU terrain and registry programs.** Port numerical kernels, climate,
   interpolation, materials, caves, fluids and surface/query consistency.
5. **Registered features and structures.** Port ordered decorations, geology,
   jigsaw/templates/processors and metadata with cross-region tests.
6. **GPU lighting and integration.** Interior prelighting, cache/promotion,
   selectable backend persistence, two world types and safe legacy migration.
7. **Runtime QA, packaging and performance.** Supported loaders, vanilla/Terralith/
   combined profiles, saved edits, complete-region benchmarks and final audit.

These are work areas, not a restriction to seven PRs. Split substantial work
into coherent independently validated cycles as needed. Each cycle uses a new
isolated worktree/branch, commit, ready PR and observed squash auto-merge. Fetch
after merge; never reset or pull over the dirty primary checkout. Preserve any
needed ignored benchmark artifacts before archiving owned worktrees.

## Completion audit

Completion is currently **unproven and incomplete**. The noise experiment and
compression component do not satisfy the generator goal. Before completion,
inspect current code and runtime evidence for every row, all original objective
requirements and each final deliverable. Do not substitute green narrow tests,
fallback execution or documentation for a complete independent backend.
