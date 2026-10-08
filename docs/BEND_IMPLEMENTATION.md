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
| Shared loaded registry export, including palette properties and climate intervals | `TerrainProfileData.java`, `RegistryGpuProgram.java`, `native/src/profile.rs` | Export decoupled from Rust initialization; actual vanilla, Terralith and Terralith+supplement registries pass with native library unavailable. Explicit Rust adapter receives byte-identical data and passes real Metal queries. Full structural profiles now stream into a resident Bend word tape with lossless field/array/string access and independent full-value checks. Loaded climate/ridge stacks now resolve schema keys and convert numeric parameters entirely in Bend; numeric terrain DAGs now project and execute in the resident worker; materials and terrain integration pending |
| Noise stacks, density bytecode, splines and GPU interpolation | `native/src/program.rs`, `program/`, `program.wgsl`, `noise3.wgsl`, `simplex.wgsl` | Reusable Bend seeded simplex/3D gradient kernels, weighted octave stacks and trilinear primitive implemented and independently checked on CPU and actual Metal. Integer/fraction coordinate handling checked through signed-i32 extremes. Numeric density opcodes 0..31, registered noise, ordered Hermite splines and nested trilinear fields implemented and independently checked with actual vanilla/Terralith/combined climate and terrain graphs on CPU/Metal. Compensated transformed coordinates preserve distant neighbors. Resident typed model projection and bounded persistent CPU/GPU queries implemented with reload invalidation; bounded top-level GPU lattices now retain samples for independently checked trilinear queries. GPU surface-height scans now consume the resident lattice; per-field caching, material predicates and runtime integration pending |
| Climate targets, biome selection, smooth boundaries and underground biomes | `climate.rs`, `climate.wgsl`, `RetinaBiomeSource.java` | Pure Bend balanced interval indices and surface/underground/coastal lookup implemented, checked against independent linear search with actual registered intervals on CPU and Metal. Typed resident-tape projection and persistent query commands implemented with explicit reload invalidation. GPU spatial climate fields and surface biome selection now consume resident density tiles; blended boundaries, materials and world integration pending |
| Coastlines, shore materials, rivers and material predicates/layers | `ShoreMaterialProfile.java`, `column_program.rs`, `materials.wgsl` | Pending |
| Caves, ravines, rare surface entrances and cave decoration | `GeologyProfile.java`, `geology.rs`, `features.rs`, `caves.wgsl` | Pending |
| Lakes, aquifer fields and fluid barriers | `TerrainFeatureProfile.java`, `program/lake_sparse.rs`, `aquifers.wgsl` | Pending |
| Ore height/count/replacement/discard rules and GPU rasterization | `geology/`, `ore.wgsl` | Pending |
| Ordered decoration, registered counts, provider noise and placement modifiers | `DecorationProfile.java`, `decoration/placement*`, `counts/`, `provider_noise/` | Pending |
| Trees and decorators, giant mushrooms, fallen trees, disks, vegetation patches, block columns, bamboo, aquatic plants and attachments | `decoration.rs`, `decoration/`, `tree_shapes.rs` | Pending; port actual supported recipe variants, not only grass/tree examples |
| Jigsaw pools/templates/processors, structures spanning regions and locate queries | `StructureProfile.java`, `structures.rs`, `structure_processors.rs`, `queries.rs` | Pending |
| Structure entities/block entities/loot and attachment rotations | `nbt.rs`, `structures.rs`, `structure_processors.rs` | Pending |
| Snow, freezing, plant support and path/gravel restrictions | `LightingProfile.java`, profile material/survival tables, `region.rs` | Pending |
| Full 64-bit seeds, signed/distant coordinates and repeatable ordering | `ChunkRequest`, native hash/random routines | Exact word-pair add/subtract/multiply/bit operations implemented; 3,689 operations independently verified. Typed i64/f64-to-F32 rounding independently verifies 131,072 scalar conversions per CPU/GPU run, including distant-value midpoint cases. Integration and coordinate/order checks pending |
| Block/biome palettes, bit-packed long arrays, heightmaps, modified UTF-8 NBT and current data versions | `region.rs`, `nbt.rs` | Pure Bend palette remapping (49,664 indices), final-block heightmaps and complete fixture chunk encoding implemented. Minecraft 26.3 (data version 5023) reads/reopens mixed and prelit fixture chunks through actual region, palette and SerializableChunkData decoders. Registry/runtime generation integration remains pending |
| LZ77, fixed-Huffman DEFLATE, zlib/Adler-32 and stored fallback | `region.rs` uses libdeflater | Implemented in `bend/compression.bend`; independent fixture tests pass |
| Independent parallel chunk compression | Rayon chunk assembly in `region.rs` | Implemented Bend fork/join API; four streams verified at 1/2/4 workers; region integration pending |
| GPU lighting, interior-chunk validity, boundary handling and one final region write | `lighting.rs`, `lighting.wgsl`, `region.rs`, `LightSeams.java` | Serialization of supplied light arrays/padding and completion flags validated with Minecraft. Lighting computation, interior/boundary policy and actual generation integration remain pending |
| MCA sector tables, external records, preserving existing chunks and atomic publication | `region.rs` | Pure Bend new-file inline planning/streaming validated with 1024 full, 3 sparse and empty record containers. Existing/external-record preservation and atomic publication pending |
| Persistent engine, bounded scheduling/singleflight, cleanup and actionable failure propagation | `NativeTerrain.java`, `RegionCoordinator.java`, `TerrainQueries.java` | Persistent Bend component worker and bounded raw Java transport implemented: resident noise stacks, GPU grids, Bend compression, 16 queued requests/32 MiB retained request payloads, cancellation, shutdown and fatal protocol/process failure checks. Full registry file loading and input queries implemented; actual region commands, coalescing and Minecraft lifecycle integration pending |
| DH surface/height requests generate temporary whole regions, 1024-region cache, eviction and promotion | `TemporaryRegions.java`, `RegionCoordinator.java` | Pending: share coordinator/cache with selected backend |
| Backend selection survives datapacks, codecs, save/reopen and server lifecycle | `RetinaChunkGenerator.java`, preset/platform mixins | Pending |
| Exactly two selectable world types; Rust default; legacy chunk-save migration aliases | `world_preset/gpu*.json`, world preset tag, language keys and generator codec | Pending; do not advertise an incomplete Bend backend |
| Color-coded stage report and last-20-region derived throughput | `GenerationMetrics.java`, `TerrainDebugReport.java`, `timings.rs` | Pending: backend-neutral timing transport |
| Reproducible pinned runtime, packaging, licensing and supported-loader builds | `gradle/native.gradle`, Fabric/NeoForge modules, CI | Pending: pinned 2.0.36 experiment installer available; production integration incomplete |
| Independent format tests, region/concurrency/query/cache tests, actual Minecraft loading, edit persistence and datapack combinations | shared unit/GPU tests and QA harnesses | Primitive tests and Minecraft decoder/reopen tests of complete fixture chunks implemented. Actual generated-world loading, edits and all runtime acceptance checks remain pending |
| Complete lit/compressed region comparison: startup, warm latency, throughput, memory, output sizes and load-in | existing native benchmark scripts | Pending; simplex experiment and file-driver timing are insufficient |

## Implementation cycles

1. **Compression core and tracked parity inventory.** Pure Bend zlib encoder,
   bounded stored fallback, independent format-boundary tests and parallel chunk
   stream API. No world selection or Rust execution changes. Merged as
   [PR #18](https://github.com/ArcaneArts/Retina/pull/18), commit `b622d8b`.
2. **Binary/NBT/MCA foundation.** Word pairs, compact buffers, complete typed NBT,
   paletted section packing, region records and independent parser validation.
   Exact word arithmetic, growable chunk buffers and typed NBT are implemented
   in [PR #19](https://github.com/ArcaneArts/Retina/pull/19), commit `2788730`.
   [PR #20](https://github.com/ArcaneArts/Retina/pull/20), commit `6d5d555`, adds
   packed local indices and streamed new-file MCA containers. Both build checks
   on each of PRs #18/#19/#20 passed. [PR #21](https://github.com/ArcaneArts/Retina/pull/21),
   commit `11f4ec8`, adds palette remapping, final-block heightmaps and complete
   fixture chunk serialization, validated by Minecraft's own decoders. Both CI
   builds passed. Existing-record publication and actual generated
   world integration remain required.
3. **Persistent engine and shared registry transport.** Separate exported data
   from Rust registration, define backend-neutral contracts, process lifecycle
   and request coalescing without exposing a misleading ready world type.
   `TerrainProfileData` now owns the unchanged projection; `BiomeTerrainProfile`
   explicitly registers it in Rust. Shared export and Rust regression checks
   pass for vanilla, Terralith and a compatible supplemental pack. The downloaded
   Lithosphere pack was also actually attempted but fails Minecraft 26.3's own
   registry loader because its data uses older formats. Evidence is recorded in
   `docs/benchmarks/bend-registry-export.json`. Bend transport and persistent
   region execution remain required. Shared extraction merged in
   [PR #22](https://github.com/ArcaneArts/Retina/pull/22), commit `a90f33d`; both CI
   builds passed.
   The component worker now reuses one stock Bend process for cached noise
   configurations, GPU sample grids and compression. Raw Java scheduling is
   bounded by queue count and retained payload bytes. Independent framing,
   partial-read/EOF, CPU/GPU numerical and zlib checks pass; actual Java callers
   verify concurrency, queued/in-flight cancellation, budget recovery, shutdown,
   corrupted responses and worker crashes. No Minecraft or Rust initialization
   is involved in this transport test. Merged in
   [PR #24](https://github.com/ArcaneArts/Retina/pull/24), commit `7eb15b9`;
   both CI builds passed.
   Complete registry input now uses a streaming Java structural encoder and
   pure Bend decoder/accessors, preserving i64/f64 bits, UTF-16 strings and
   packed homogeneous arrays. Actual vanilla/Terralith/combined profiles are
   independently compared value by value; CPU/GPU-required workers retain
   the validated tape across input queries, rejected/truncated/missing uploads,
   noise dispatches and deletion of the staging file. Java encoding passes
   within 256 MiB heap. Merged in
   [PR #25](https://github.com/ArcaneArts/Retina/pull/25), commit `05fefc3`.
   Both initial CI builds failed during parallel Gradle classpath resolution;
   [PR #26](https://github.com/ArcaneArts/Retina/pull/26), commit `d71afe5`,
   repairs task-time resolution and configuration-cache reuse. Both repair CI
   builds passed, along with the complete host Fabric/NeoForge build and 64
   native unit tests. Typed program interpretation and actual region
   commands/coalescing remain pending.
4. **GPU terrain and registry programs.** Port numerical kernels, climate,
   interpolation, materials, caves, fluids and surface/query consistency.
   `bend/noise.bend` now provides reusable seeded simplex, 3D gradient noise,
   weighted octave preparation and trilinear interpolation. Independent scalar
   equations validate 8,192 sample rows / 49,152 scalar values per run on one/two
   CPU workers and GPU. A diagnostic stock-runtime observer confirms an actual
   Metal command buffer; the observed executable produces identical fixture
   bytes. This is numerical component evidence, not a region benchmark or an
   integrated terrain generator. See `docs/benchmarks/bend-noise-correctness.json`.
   Merged in [PR #23](https://github.com/ArcaneArts/Retina/pull/23), commit `5f0f837`;
   both CI builds passed.
   Loaded climate/ridge octave preparation now resolves schema keys and converts
   exact i64/f64 parameters inside Bend, reusing the persistent GPU sample path.
   Numeric tests check 131,072 conversions per CPU/GPU run; actual vanilla,
   Terralith and combined input tests check 16,100 independent samples per backend,
   18 rejected typed profiles and unchanged resident stacks. Diagnostic observers
   confirm actual Metal work and matching normal-executable output. This prepares
   registered octave stacks; loaded density DAGs, climate target selection and
   full terrain integration remain pending. Evidence is in
   `docs/benchmarks/bend-numeric-correctness.json` and
   `docs/benchmarks/bend-registry-noise.json`. Merged in
   [PR #27](https://github.com/ArcaneArts/Retina/pull/27), commit `6ddcf4e`;
   both CI builds passed.
   Climate interval indices now prepare balanced surface, underground and coast
   trees in Bend. GPU lookup preserves registered interval fitness, optional
   weights/ocean penalties, shore filters and original ordinal ties. Independent
   linear searches cover actual vanilla/Terralith/combined intervals, empty
   indices, rounded boundaries and overflowing distances. A required IO boundary
   separates CPU index-building forks from the GPU batch; the diagnostic observer
   verifies actual Metal dispatch rather than inferring it from `--gpu on`.
   This component accepts typed targets; resident registry-tape projection and
   climate/terrain fields remain pending. See
   `docs/benchmarks/bend-climate-correctness.json`. Merged in
   [PR #28](https://github.com/ArcaneArts/Retina/pull/28), commit `c3870d7`;
   both CI builds passed.
   Resident climate projection now resolves the registered target table, numeric
   vectors and biome flags inside Bend and retains the index across repeated GPU
   batches. Successful uploads invalidate old indices; rejected uploads preserve
   them. Component tests include actual vanilla/Terralith/combined profiles,
   maximum-size query batches, malformed typed schemas and Java concurrent callers.
   Registered density programs, spatial climate fields and integrated terrain
   remain required. See `docs/benchmarks/bend-registry-climate.json`. Merged in
   [PR #29](https://github.com/ArcaneArts/Retina/pull/29), commit `e140655`;
   both CI builds passed.
   The numeric density evaluator now implements opcodes 0..31, registered Perlin
   and old blended noise, Hermite splines and nested global trilinear fields.
   Compensated F32 pairs retain transformed signed/distant coordinates through
   lattice conversion. Independent scalar checks exercise actual vanilla,
   Terralith and combined climate/terrain graphs on CPU and confirmed Metal;
   malformed graphs, cycles, cells, references and transport must fail. Terralith's
   duplicate and nonmonotonic spline locations retain their exported semantics.
   This is a typed library with raw QA fixtures: resident tape projection,
   material predicates, GPU lattice caching and actual terrain generation remain
   required. Per-query corner recomputation is an explicit performance follow-up,
   not a claimed region throughput improvement. Evidence is in
   `docs/benchmarks/bend-density-correctness.json`. Merged in
   [PR #30](https://github.com/ArcaneArts/Retina/pull/30), commit `4189169`;
   both CI builds passed.
   The persistent worker now projects numeric climate/surface/final-density
   programs directly from the resident tape in Bend, validates the typed model
   and reuses it for bounded CPU/GPU query batches. Successful profile uploads
   clear both climate and density caches; rejected uploads retain them. Tests
   cover actual vanilla/Terralith/combined inputs, malformed typed models,
   maximum batches, signed/distant coordinates, full seeds, reordered requests,
   independent noise/climate settings and Java concurrent callers. Material
   programs remain resident for a future projection. Cached lattices and actual
   terrain generation remain required. Evidence is in
   `docs/benchmarks/bend-registry-density.json`. Merged in
   [PR #31](https://github.com/ArcaneArts/Retina/pull/31), commit `2173223`;
   both CI builds passed.
   The density hot path now uses direct tuple helpers rather than per-node and
   per-operand continuation closures. Typed visitors evaluate immutable program
   and point tables without returning shared subtrees. A pure batch wrapper
   retains the model outside the GPU call and reconstructs worker state afterward;
   this avoids ownership changes from capturing the model in an IO continuation.
   Noise/field ownership overhead and repeated corner evaluation still remain.
   Warmed, serial A/B tests with byte-identical output measured 2.27–3.11× faster
   CPU and 5.20–5.74× faster Metal batches on the three actual profiles (24 queries
   each, two CPU workers, three repeats). These are component measurements, not
   complete-region throughput. Independent scalar checks, resident reload/error
   tests, actual Metal command observations and the full host build passed.
   Evidence and the reproducible harness are in
   `docs/benchmarks/bend-density-borrows.json` and
   `scripts/benchmark_bend_density.py`. Merged in
   [PR #32](https://github.com/ArcaneArts/Retina/pull/32), commit `28fd388`;
   both CI builds passed.
   A bounded top-level density lattice now runs the selected resident numeric
   graph at globally aligned vertices and retains the results for subsequent
   GPU interpolation batches. It preserves full seeds, signed/distant coordinates
   and inclusive endpoint coverage; different programs/seeds and uncovered points
   keep direct Bend evaluation. Successful numeric preparation/profile reload
   invalidates it; rejected requests retain it. One grid holds at most 1,048,576
   vertices. This samples the original graph, including its registered fields;
   per-field caching and integrated terrain/surface/cave consumers remain required.
   Independent reference checks cover 5,661 queries per CPU/GPU run across
   synthetic, analytic and actual vanilla/Terralith/combined inputs (max normalized
   error 1.12e-6). All 132 successful GPU batches were observed as actual Metal
   commands; diagnostic bytes matched the normal executable. A warmed component
   benchmark gives 0.85–1.14 ms for 24 cached Metal vertex queries versus
   337–671 ms direct, after a separately measured 317–449 ms construction of
   405 vertices. Outputs at vertices are byte-identical; between vertices the
   lattice intentionally approximates the original graph. This is not complete
   region throughput. Evidence is in `docs/benchmarks/bend-density-lattice.json`.
   The resident lattice cycle merged in
   [PR #33](https://github.com/ArcaneArts/Retina/pull/33), commit `3544b98`;
   both CI builds passed.

   The GPU surface consumer now reuses that lattice.

   Bend resolves world limits and interpolation cells from the resident registry,
   emits the required density descriptor, and scans the resident grid on GPU into
   continuous first-free surface heights. It interpolates densities before
   finding the highest solid interval, handles explicit-height profiles, and
   evaluates spatial climate plus registered surface biome selection in the same
   pass. Computed columns stay resident for bounded queries. Shared Java only
   transports descriptors and query bytes. Cache replacement/reload and rejected
   requests have explicit retention rules.

   Independent scalar checks cover disconnected layers, solid/empty terrain,
   global negative cells, distant/signed-i32 endpoint X/Z, overlapping tiles,
   malformed profiles, actual vanilla/Terralith/combined data, and a 512x512
   analytic tile. The stock Metal runtime is instrumented only to observe actual
   command buffers. This is a surface component, not complete region generation:
   composition-mode interval bounds, material predicates/layers, biome blending,
   caves/fluids, features, lighting and Minecraft world integration remain.
   Each CPU/GPU run checks 6,296 queries and rejects 29 malformed configurations;
   207 actual Metal commands were observed with diagnostic output matching the
   normal executable. The existing lattice regression suite and full host loader
   build also passed. Evidence is recorded in
   `docs/benchmarks/bend-terrain-surface.json`.
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
