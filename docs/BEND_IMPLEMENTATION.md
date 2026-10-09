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
| Shared loaded registry export, including palette properties and climate intervals | `TerrainProfileData.java`, `RegistryGpuProgram.java`, `native/src/profile.rs` | Export decoupled from Rust initialization; actual vanilla, Terralith and Terralith+supplement registries pass with native library unavailable. Explicit Rust adapter receives byte-identical data and passes real Metal queries. Full structural profiles now stream into a resident Bend word tape with lossless field/array/string access and independent full-value checks. Loaded climate/ridge stacks now resolve schema keys and convert numeric parameters entirely in Bend; numeric terrain DAGs now project and execute in the resident worker; material predicates now project and execute on CPU/GPU; GPU-derived material contexts and exhaustive compact vertical block runs implemented; final terrain integration pending |
| Noise stacks, density bytecode, splines and GPU interpolation | `native/src/program.rs`, `program/`, `program.wgsl`, `noise3.wgsl`, `simplex.wgsl` | Reusable Bend seeded simplex/3D gradient kernels, weighted octave stacks and trilinear primitive implemented and independently checked on CPU and actual Metal. Integer/fraction coordinate handling checked through signed-i32 extremes. Numeric density opcodes 0..31, registered noise, ordered Hermite splines and nested trilinear fields implemented and independently checked with actual vanilla/Terralith/combined climate and terrain graphs on CPU/Metal. Compensated transformed coordinates preserve distant neighbors. Resident typed model projection and bounded persistent CPU/GPU queries implemented with reload invalidation; bounded top-level GPU lattices now retain samples for independently checked trilinear queries. GPU surface-height scans now consume the resident lattice; GPU-derived compact material layers implemented; per-field caching and runtime integration pending |
| Climate targets, biome selection, smooth boundaries and underground biomes | `climate.rs`, `climate.wgsl`, `RetinaBiomeSource.java` | Pure Bend balanced interval indices and surface/underground/coastal lookup implemented, checked against independent linear search with actual registered intervals on CPU and Metal. Typed resident-tape projection and persistent query commands implemented with explicit reload invalidation. GPU spatial climate fields and surface biome selection now consume resident density tiles; compact material layers implemented; blended boundaries and world integration pending |
| Coastlines, shore materials, rivers and material predicates/layers | `ShoreMaterialProfile.java`, `column_program.rs`, `materials.wgsl` | Pure Bend resident per-biome material DAGs and predicates (40..54) implemented with shared numeric evaluation, registered bands/sea/layer mode and bounded CPU/GPU query batches. GPU context/compact block runs implemented. Resident GPU coastal finalization now uses exact generated occupancy, six-block disk probes and registered climate alternatives before material generation; independent CPU/Metal checks cover inland/ocean exclusion, distinct materials and cache/coordinate rules. Inland rivers, ocean remapping, 3D biome material assignment and final integration remain required |
| Caves, ravines, rare surface entrances and cave decoration | `GeologyProfile.java`, `geology.rs`, `features.rs`, `caves.wgsl` | Typed geology, cached globally aligned GPU/CPU cave fields, rounded noisy ravines and registered column carving implemented; independent voxel/serialized-region and actual Minecraft decoder checks pass. GPU/CPU depth-dependent cave-biome volumes now select registered carvers and serialize actual 3D quart IDs, retaining finalized surface biomes near the roof. Resident aquifers now supply carved cavity materials. Composed-density/exterior refinements, decorations and running-world integration remain pending |
| Lakes, aquifer fields and fluid barriers | `TerrainFeatureProfile.java`, `program/lake_sparse.rs`, `aquifers.wgsl` | Typed aquifer graph projection and GPU/CPU flooding, erosion, spread, lava, barrier and preliminary-surface evaluation implemented. Globally hashed resident fluid centers, trilinear barrier fields and nearest-center pressure now place registered cavity fluids while preserving protected/pressure-barrier materials. Registered lake budgets/materials, globally seeded candidates, five bank probes, bounded fluid levels and noisy basin/rim placement implemented on CPU/GPU before material coating. Independent block/NBT/zlib checks cover fluids, barriers, overlap and signed/distant coordinates; lake exclusions now use finalized pre-carving coast selection, including global probes outside the resident tile. Complete running-world integration remains pending |
| Ore height/count/replacement/discard rules and GPU rasterization | `geology/`, `ore.wgsl` | Registered recipe projection, packed membership/material tables and selected-backend ordered replacement/exposure policy implemented. Registered count/rarity/height attempts and local sphere-chain/scattered geometry now execute on CPU/GPU. Spatial membership filtering, sparse rasterization and final block replay remain pending |
| Ordered decoration, registered counts, provider noise and placement modifiers | `DecorationProfile.java`, `decoration/placement*`, `counts/`, `provider_noise/` | Pending |
| Trees and decorators, giant mushrooms, fallen trees, disks, vegetation patches, block columns, bamboo, aquatic plants and attachments | `decoration.rs`, `decoration/`, `tree_shapes.rs` | Pending; port actual supported recipe variants, not only grass/tree examples |
| Jigsaw pools/templates/processors, structures spanning regions and locate queries | `StructureProfile.java`, `structures.rs`, `structure_processors.rs`, `queries.rs` | Pending |
| Structure entities/block entities/loot and attachment rotations | `nbt.rs`, `structures.rs`, `structure_processors.rs` | Pending |
| Snow, freezing, plant support and path/gravel restrictions | `LightingProfile.java`, profile material/survival tables, `region.rs` | Pending |
| Full 64-bit seeds, signed/distant coordinates and repeatable ordering | `ChunkRequest`, native hash/random routines | Exact word-pair add/subtract/multiply/bit operations implemented; 3,689 operations independently verified. Typed i64/f64-to-F32 rounding independently verifies 131,072 scalar conversions per CPU/GPU run, including distant-value midpoint cases. Integration and coordinate/order checks pending |
| Block/biome palettes, bit-packed long arrays, heightmaps, modified UTF-8 NBT and current data versions | `region.rs`, `nbt.rs` | Pure Bend palette remapping (49,664 indices), final-block heightmaps and complete fixture chunk encoding implemented. Minecraft 26.3 (data version 5023) reads/reopens mixed and prelit fixture chunks through actual region, palette and SerializableChunkData decoders. Resident generated base/material columns now encode into current-version NBT/zlib with exact state properties, quart surface biomes and final-block heightmaps; final generated-region/runtime integration remains pending |
| LZ77, fixed-Huffman DEFLATE, zlib/Adler-32 and stored fallback | `region.rs` uses libdeflater | Implemented in `bend/compression.bend`; independent fixture tests pass |
| Independent parallel chunk compression | Rayon chunk assembly in `region.rs` | Implemented Bend fork/join API; generated MCA staging now compresses eight independent chunk tasks per batch; final voxel/light integration pending |
| GPU lighting, interior-chunk validity, boundary handling and one final region write | `lighting.rs`, `lighting.wgsl`, `region.rs`, `LightSeams.java` | Serialization of supplied light arrays/padding and completion flags validated with Minecraft. Lighting computation, interior/boundary policy and actual generation integration remain pending |
| MCA sector tables, external records, preserving existing chunks and atomic publication | `region.rs` | Pure Bend new-file inline planning/streaming now writes whole generated base/material regions from resident snapshots; independent and actual Minecraft decoders cover all 1024 chunks. Existing/external-record preservation and atomic publication pending |
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
   Registered material programs now project into a separate resident model and
   execute in one GPU batch (or explicit Bend CPU). Numeric nodes reuse the
   existing evaluator; material nodes handle conditional selection, stone/water/
   height/slope rules, seeded gradients, freezing, terracotta bands and correlated
   patches. Material flags/salts have separate dependency validation. Typed
   visitors retain immutable programs and numeric data around GPU execution.
   Commands 19/20 expose bounded component queries, with numeric reprepare/reload
   invalidation and independent surface/lattice cache retention. Bulk material
   contexts/layers and final voxel placement remain required.
   Independent checks validate 7,116 queries per CPU/GPU run across synthetic
   contexts and all 56 vanilla, 151 Terralith and 152 combined-profile programs;
   maximum normalized error is 1.15e-6. Thirty malformed material profiles plus
   five focused node/root rejection cases pass on both backends. The observer
   sees all 85 expected Metal command buffers with normal GPU bytes unchanged.
   Concurrent Java transport checks and the full host loader build pass.
   Evidence is in `docs/benchmarks/bend-material-programs.json`.
   Merged in [PR #35](https://github.com/ArcaneArts/Retina/pull/35), commit
   `2aff1a6`; both CI builds passed.

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

Derived material columns now consume the resident surface/density snapshots and
apply per-biome material programs inside Bend. One-column halos supply integer
slope context; cached bilinear density layers avoid eight full lattice reads
per voxel. Independent CPU/Metal checks validate compact exhaustive runs and
raw voxel queries, including floating solid intervals and context/state rules.
Each CPU/GPU run validates 7,641 voxel queries across 11 synthetic profiles and
actual vanilla, Terralith and combined inputs, rejects 18 malformed projections
and five invalid evaluated results, and handles 1,602 requests in one process.
All 311 expected Metal command buffers were observed; diagnostic and normal
GPU output is identical. Maximum normalized context error is 7.21e-7. Existing
material-rule regression checks (7,116 queries per backend), concurrent Java
voxel queries and the full host Fabric/NeoForge build also pass. Compile deadlines
were extended without increasing worker counts after a loaded build timed out.
The worker reports palette/biome failures after actual generation and keeps old
results on failure. This is still a component: caves, fluids beyond the base sea,
coast remapping, underground biome changes and generated-region integration
remain on the parity checklist. Evidence is recorded in
`docs/benchmarks/bend-material-columns.json`.

The material-column cycle merged in [PR #36](https://github.com/ArcaneArts/Retina/pull/36),
commit `6312a55`; both actual push/PR CI builds passed.

Generated base/material chunk serialization now projects registered block
names/properties, biome IDs and six heightmap predicate masks into a resident
Bend catalog. It expands cached compact runs into sections, samples surface
quart biomes and calls the pure Bend palette/NBT/zlib writers. Commands 25..27
accept only raw metadata, retain catalogs across tiles and reject missing,
misaligned or uncovered data without modifying successful generation results.
This is an intermediate unlit generated chunk, not a finished world: final
caves/fluids/features, underground biomes, light and region publication remain.
Independent checks validate 442,368 generated blocks per CPU/GPU run across
six synthetic and actual vanilla/Terralith/combined profiles. All 54 expected
Metal command buffers were observed, with diagnostic and normal output bytes
identical. Fourteen invalid catalogs plus metadata/coverage/alignment rejection
cases pass; each worker handles 470 requests. Minecraft decodes/reopens 22
outputs twice and checks 1,703,936 blocks using its actual heightmap predicates.
Java raw transport checks twelve concurrent chunk requests per backend. The full
host Fabric/NeoForge build and existing material-column regressions pass.
Evidence is recorded in `docs/benchmarks/bend-generated-chunks.json`.

The generated-chunk cycle merged in [PR #37](https://github.com/ArcaneArts/Retina/pull/37),
commit `bb46a65`; both actual push/PR CI builds passed.

Whole generated base/material regions now use the pure Bend chunk encoder,
parallel compression and MCA planner in one staged-file command. Batches retain
compressed records while releasing expanded chunk arrays; final bytes stream
through stock Bend file IO. The Java bridge carries raw metadata/path and owns
staging lifetime only. The command never reads or overwrites a game save and
does not claim final cave/feature/structure/light parity. Existing/external
record preservation, atomic publication, scheduling/coalescing, cache promotion,
world selection and actual gameplay save/edit tests remain required.

Independent checks decode 7,168 chunk streams across two synthetic CPU/GPU
regions and full vanilla/Terralith/combined GPU-required regions. Selected chunks
match raw/compressed chunk commands and every queried block/heightmap; repeated
whole writes are identical. The observer confirms all nine expected actual Metal
commands for three complete files (synthetic plus vanilla), with byte-identical
normal GPU output. Minecraft reads all seven files twice, checking 671,088,640
blocks against its actual palette and heightmap predicates without changing file
bytes. Concurrent Java writers, queued cancellation, snapshot reuse, actual file
open failure and worker cleanup pass. Existing generated-chunk CPU/GPU catalog,
coordinate and cache regressions also pass, along with the full host loader
build and 64 native unit tests. Evidence is in
`docs/benchmarks/bend-generated-regions.json`.

These checks expose a material scaling problem rather than a performance win.
Full loaded-profile construction took roughly 168–310 seconds in the normal
correctness run; encoding/compression/writing took 17–19 seconds. A separate
observed vanilla run spent about 66 seconds in density, 49 seconds in material
columns and 2 seconds in surface/climate. Host load is uncontrolled and these
are component response times, not complete lit/featured region comparisons.
Profile density-field reuse and bulk material evaluation before production
integration; do not hide this cost behind the earlier small-query results.

The generated-region cycle merged in [PR #38](https://github.com/ArcaneArts/Retina/pull/38),
commit `cdd0bd7`; both actual push/PR CI builds passed.

Registered trilinear fields now evaluate only contributing corners. Exact
vertices return one sample directly; edges/faces evaluate two/four rather than
eight. Fractional interpolation keeps its formula, while endpoints deliberately
ignore unused nonfinite samples instead of multiplying them by zero. CPU and
actual Metal checks cover all alignment combinations, nested/transformed fields,
full seeds and signed/far coordinates. The prior compiled PR38 worker is the A/B
baseline; both executables use two CPU workers, nice +10, one warmup and five
serial alternating timed repetitions, with compilation completed first.

On the Apple M4 Max, loaded-profile GPU-required lattice host response improves
by 10.28–13.12x, and CPU lattice host response by 6.65–8.73x. Mixed-query GPU gains
range from 1.03–1.88x; do not generalize lattice gains to arbitrary calls or the
complete generator. All 3,757 cached vertices per profile/backend comparison
match byte-for-byte, with independent arbitrary probes also passing. The stock
runtime observer confirms 18 Metal commands for the alignment/overflow checks.
Evidence is recorded in `docs/benchmarks/bend-interpolation-corners.json`.

Whole generated-region regression checks independently decode 7,168 records and
compare queried blocks/heightmaps. All seven full MCA file hashes are identical
to PR38, including vanilla, Terralith and combined full-height outputs; repeated
writes are also identical. Existing lattice boundary/cache checks pass on both
CPU and GPU, with their aggregate hashes unchanged from the recorded baseline.
Minecraft reads/reopens all seven files twice, checking 671,088,640 blocks.
Concurrent Java region writers, queued cancellation, actual file-open failure
and worker cleanup also pass. The full host Fabric/NeoForge build and 64 native
unit tests pass. Full-tile correctness response times remain tens to hundreds
of seconds, so material-stage scaling still needs substantial improvement.

Neighboring contributing samples still repeat field work. Per-field reuse,
bulk material evaluation and the original feature/runtime parity items remain
required; this change does not expose a partial Bend world type.

The interpolation-corner cycle merged in [PR #39](https://github.com/ArcaneArts/Retina/pull/39),
commit `488cf4c`; both actual push/PR CI builds passed.

Bulk material generation now has a pure Bend branch-selected scalar evaluator.
It prepares immutable random-access instruction tables once per dispatch,
memoizes shared dependencies within each voxel, and follows only the selected
children of conditional/first-material rules. Numeric operations and material
predicates still use the established evaluator. Raw six-root queries remain
eager; generated columns need only the first root. An explicit operation list
keeps the machine stack independent of graph depth. A recursive trial failed
on a valid 1,024-node GPU rule; the final work-list evaluator passes that case
without weakening validation or falling back to CPU.

CPU/GPU differential checks compare 8,832 solid voxels per backend against
eager queries across four profiles, including layered/legacy and empty bands.
Independent equations check 4,224 predicate voxels per backend. All numeric
dependency shapes, spline children, shared DAGs, empty root lists and ignored
overflowing branches are covered. The diagnostic runtime confirms 24 actual
Metal commands and identical output. Existing material-column checks additionally
verify 7,641 voxel queries per backend, including actual vanilla, Terralith and
combined registries, distant/signed coordinates, overlapping tiles, malformed
inputs, invalidation and selected nonfinite-result rejection.

All seven complete unlit base/material MCA files match PR39 byte-for-byte;
7,168 records independently decode, and repeated full writes match. Minecraft
reads/reopens each chunk twice and checks 671,088,640 blocks. Concurrent Java
region writes, queued cancellation, actual file-open failure and worker cleanup
pass. The Fabric/NeoForge host build, 64 native unit tests and the dedicated
Gradle lazy-material task also pass. Feature/runtime/lighting parity and the
complete Rust-versus-Bend comparison remain pending.

Warm alternating A/B material-column calls against the compiled PR39 worker
use two CPU workers, nice +10, one warmup and five repetitions, with compilation
and correctness runs completed first. At 64×64 columns on the M4 Max, CPU
medians change from 2,607 to 1,206 ms for vanilla (2.16x), 10,430 to 1,859 ms for
Terralith (5.61x), and 13,326 to 2,339 ms for combined (5.70x). GPU-required
medians change from 843 to 858 ms for vanilla (0.98x), 2,129 to 1,409 ms for
Terralith (1.51x), and 2,143 to 1,423 ms for combined (1.51x). The smaller 32×32
workloads show 1.95–5.74x CPU ratios, 1.56–1.60x datapack GPU ratios and a 0.96x
vanilla GPU ratio. Vanilla GPU response is slightly worse in this run; there
is no universal GPU speedup claim. Every column's bytes match before and after
timing. These are host response times for one component, including preparation
and transport; other host load is uncontrolled. Evidence is recorded in
`docs/benchmarks/bend-lazy-materials.json`.

The branch-selected material cycle merged in [PR #40](https://github.com/ArcaneArts/Retina/pull/40),
commit `7362dd5`; both actual push/PR CI builds passed.

Coastal finalization now runs as two pure Bend GPU steps over the
resident surface/density snapshot. It scans the same integer solid voxels used
by material columns, excludes shore targets inland, and requires nearby terrain
on the opposite side of sea level before consulting registered coastal targets.
Six-block disk probes and a narrow height band reproduce the existing Rust
shore approximation without adding biome-name/material heuristics. A six-column
density halo supports matching decisions at tile boundaries; only the central
columns are materialized and serialized. Sources without referenced shore
targets retain the smaller coverage contract. The bridge sends an empty command
and receives only dimensions; no bulk terrain IPC transfer intervenes.

Independent CPU/GPU checks each verify 3,342 surface columns and 158,080 material
voxels across thirteen synthetic profiles and actual vanilla, Terralith and
combined inputs. They cover shallow open ocean, low inland plains, distinct
registered shore materials, floating intervals, explicit height mode, negative
sea levels, full seeds, signed/far coordinates, overlapping tiles, idempotence,
rejected requests and dependent cache invalidation. All 253 expected Metal
command buffers are observed, with diagnostic and normal output identical.
This is a coastal component; inland rivers, ocean remapping, final cave/feature/
structure/light behavior and running-world integration remain required.

The direct-scan coastal draft repeatedly built whole vertical density columns
for neighboring probes. A full vanilla Metal run spent 31,356 ms in that stage.
The final implementation first caches integer heights, reusing the resident
continuous heights as hints and validating adjacent voxel signs. Ambiguous
crossings retain the full scan on the selected backend; clipped upper islands
and very small density values have independent regression fixtures. A second
GPU step performs only cached probes and climate lookups. The observed full
vanilla cache/selection steps take 149/3,674 ms (3,823 ms combined). These are
single correctness-run device measurements under uncontrolled external load,
not a complete Rust-versus-Bend benchmark.

All nine whole MCA files match the direct-scan coastal version byte-for-byte,
including full vanilla, Terralith and combined profiles. Independent readers
decode 9,216 chunk records and compare 2,465,792 queried blocks/heightmaps.
Repeated files match. The full-tile observer confirms 20 Metal command buffers
for the synthetic and vanilla files, with identical normal output. Minecraft
reads/reopens this identical nine-file output set twice, checking 721,420,288
blocks with its actual palettes/heightmap predicates. Concurrent Java writers,
queued cancellation, actual file-open failure and worker cleanup pass. The host
Fabric/NeoForge build, 64 native tests, surface regressions and dedicated Gradle
shoreline task pass. The controlled coastline retains 97.65625% inland biome
samples; its shore strip occupies 2.34375% of the region.

The ordinary correctness run builds full loaded-profile base/material tiles in
roughly 57/73/75 seconds (vanilla/Terralith/combined), then writes compressed
MCA in 14–15 seconds. Material construction remains the largest observed GPU
stage. Final features, lighting, runtime integration and a complete comparison
against Retina Rust remain required. Evidence is recorded in
`docs/benchmarks/bend-shorelines.json`.

Warm alternating shoreline-only A/B timings compare the final cache against the
unmerged direct-scan coastal prototype from this cycle, rather than PR40 or
Rust. At 64×64 and 128×128 columns, all vanilla/Terralith/combined cases retain
identical surface bytes and sampled material runs. Five timed repetitions after
one warmup give 7.07–16.71x CPU and 4.09–10.62x GPU-required median ratios. For
128×128 vanilla columns, host response changes from 995 to 129 ms on CPU and
644 to 157 ms on GPU. The timed command includes transport and both shoreline
steps, excludes lattice/surface/material preparation, and runs with two CPU
workers at nice +10 after compilation and other tests finish. External host load
is uncontrolled. This component improvement does not establish full-generator
performance; the report retains executable/input hashes and all samples.

The coastal-surface cycle merged in [PR #41](https://github.com/ArcaneArts/Retina/pull/41),
commit `b873ffe`; both actual push/PR CI builds passed.

On GPU, material columns now separate immutable geometry from material evaluation
in two resident Bend steps. The first builds compact solid/fluid/air spans
and exact heights for the central tile plus its existing one-column slope halo.
The second reuses central spans and four neighboring cached heights when deriving
material context. No extra halo is serialized, no intermediate terrain crosses
Java IPC, and the cache is released with the command. The independent scalar
height path remains available to coastal finalization. CPU execution preserves
the prior one-step generation path after a two-step trial regressed small CPU
workloads; both paths share context arithmetic and the material/coating logic.

Independent CPU/GPU checks verify 8,613 material voxels per backend across eleven
synthetic and three loaded registry profiles, including one-column/thin-strip
tiles, floating islands, explicit heights, distant/signed coordinates, invalid
materials and cache retention/invalidation. The diagnostic worker observes all
520 expected Metal commands and identical normal output. Lazy/eager evaluation
regressions retain their bytes and observe 32 Metal commands. Shoreline checks
retain their PR41 aggregate hashes and observe 290 Metal commands.

All nine complete MCA files remain byte-identical to PR41; independent readers
decode 9,216 records and compare 2,465,792 block/heightmap queries. Twenty-four
full-region Metal commands are observed, including both material stages.
Minecraft reads/reopens every chunk twice and checks 721,420,288 blocks. Real
Java concurrent writes, queued cancellation, actual file-open failure and
shutdown pass, as do the Fabric/NeoForge host build and 64 native unit tests.

The ordinary loaded-profile correctness runs complete base/material generation
in roughly 42/58/58 seconds for vanilla/Terralith/combined, versus PR41's earlier
57/73/75-second observations. Short analytic GPU regions regress from roughly
4–5 seconds to 8–11 seconds; the additional immutable-table lookup/kernel cost
is a tradeoff, not a universal speed improvement. These uncontrolled observations
are separate from formal A/B measurements. Peak cache memory is not established
by this component run; the complete runtime/memory/Rust comparison remains
pending along with feature, cave, structure and lighting parity.

Final warm alternating A/B compares this implementation with the compiled PR41
worker at 32×32 and 64×64 columns for vanilla/Terralith/combined inputs, plus a
256×256 vanilla GPU tile. Every column's bytes match before and after measurement.
One warmup and five repetitions give GPU material-stage median ratios of
1.13–1.49x on the small tiles and 1.33x on the large tile (9,430 to 7,079 ms).
Final CPU ratios range from 0.959 to 1.061, close to the original path rather
than the forced-cache trial's 0.772–0.996. These command timings include geometry,
materials and transport; they exclude earlier density/surface/coastal stages.
Compilation and correctness tests finished first, and both workers use two CPU
threads at nice +10; other host load remains uncontrolled. This is not a complete
Rust-versus-Bend comparison. Raw samples, executable/GPU/source/input hashes,
discarded-trial provenance and actual Minecraft evidence are recorded in
`docs/benchmarks/bend-material-geometry.json`.


Registered cave inputs now project entirely in Bend from the resident registry
snapshot. Six noise stacks, up to four carvers per biome, signed height/provider
ranges, canyon shape fields, carveable material flags and lava/world bounds are
retained as immutable numeric tables. Both packed and ordinary registry boolean
arrays are supported. Optional canyon fields preserve the exporter defaults.
Preparation rejects invalid values without discarding a previous valid model;
successful preparation invalidates dependent generated columns.

A bounded sampling command executes the six cave channels on the selected Bend
CPU/GPU backend. It retains only positive octave weights at or below the
four-block lattice Nyquist limit and normalizes those retained weights. A weighted
mean avoids intermediate multiplication overflow for large finite weights.
Integer/fraction coordinates and both seed words are preserved. Only the six
noise tables enter this GPU dispatch; the registry tape and carver metadata stay
resident on the host. A separate bounded diagnostic command verifies the typed
carver records without recomputing registry projections in Java.

Independent checks verify 59,508 cave-noise scalars and all 1,086 carver records
per backend across six synthetic profiles and actual vanilla, Terralith and
combined registries. Fifty-seven invalid typed profiles are rejected. CPU, normal
GPU and observed Metal outputs have identical aggregate hashes; the observer
confirms all 74 expected device commands. Maximum reference error is
2.6823e-7. Reordered/repeated batches, signed/i32-extreme coordinates, complete
seeds, Nyquist filtering, packed flags, large finite weights and state retention/
invalidation are covered. An independently decoded 16,384-block generated chunk
keeps identical raw NBT and valid compressed output with resident geology.

Complete synthetic MCA regression runs retain this model through generation and
serialization on CPU, GPU and observed Metal. Each independently decodes all
1,024 chunk streams and compares 57,344 blocks/heightmap queries. The full files
match the prior independently validated PR42 output by SHA-256; six actual Metal
commands are observed. The dedicated Gradle geology task and full host Fabric/
NeoForge build pass, including 64 native tests. Evidence, source/input/executable
hashes and correctness-run device observations are recorded in
`docs/benchmarks/bend-geology-correctness.json`.

This cycle prepares cave data and samples noise; it does not carve terrain.
Resident cave lattices/masks, 3D cave biomes, ravines/rare entrances, fluids,
decorations and the remaining full-generator requirements are still pending.
No complete generator or Rust-versus-Bend performance claim follows from these
component checks.

The next cave component consumes that typed geology in globally aligned 4³
resident fields, then carves the actual material-column snapshot on the selected
Bend CPU/GPU backend. It evaluates registered program-2 chamber density, paired
anisotropic tunnel stacks and finite rounded ravines with registered center/
shape ranges, seeded curvature and noisy walls. Global lattice coordinates and
compensated local distances preserve overlap coherence and distant neighbors.
Only layout/count acknowledgements leave the engine between these stages.
Carving honors material flags, carver height/probability/shape inputs, protected
foundations/submerged floors and registered lava level; sparse coherent entrance
domains relax near-surface tunnel suppression. Resulting runs are coalesced,
surface queries are recomputed, and existing Bend NBT/zlib/MCA writers consume
the carved snapshot and derive its final heightmaps.

Six controlled fixtures and actual vanilla/Terralith/combined registries pass
on CPU, GPU and a stock-runtime Metal observer. Each run independently verifies
4,392 field scalars and checks all 884,736 carved voxel classifications against
separate rules, including analytic chambers, protected materials, positive
ravines, lava and submerged roofs. Maximum relative field error is 9.251e-7.
Tests cover overlapping/reordered tiles, one-column/narrow tiles, signed-i32
extremes, distant coordinates, both seed words, bounded/reordered batches,
malformed commands, repeated carving and cache replacement. All nine serialized
sample chunks match between CPU and GPU; ravine field F32 roundoff differs, as
permitted by the goal. Ordinary GPU and observer aggregate bytes match, and all
448 expected Metal commands are observed. Java callers verify concurrent field
queries, actual carved NBT/zlib, errors, cache invalidation and worker shutdown.

Three complete 32-block-high analytic MCA runs (CPU/GPU/observer) each independently
decode 1,024 records and compare 57,344 blocks/heightmap queries. A chamber spans
multiple chunks, changes an interior probe to air and retains the foundation,
roof and exterior probes. The three file hashes match. Minecraft's actual
region/palette/SerializableChunkData readers reopen the two retained files,
reading every chunk twice and verifying 33,554,432 blocks. The dedicated Gradle
cave task passes, as do the previous geology checks and the full host Fabric/
NeoForge build, including 64 native tests (11 explicit device/fixture tests
remain ignored). Evidence is in `docs/benchmarks/bend-caves-correctness.json`.

This remains an unlit component using surface biome carvers. Underground biome
assignment, aquifers/lakes, composed-density/exterior refinements, cave
decorations, the remaining feature/structure/light pipeline and usable world
types are still required. Correctness-run response times are observational;
they do not establish final generator throughput or a Rust-versus-Bend result.


Underground biome selection now joins the resident four-block cave lattice. When
registered cave targets exist, each underground node evaluates climate program
0 at its full XYZ position and searches the six-dimensional registered target
index. Nodes in the top twelve blocks retain the finalized surface biome of the
actual column, including coastal remapping. Without registered cave targets,
all nodes inherit that surface biome rather than inventing underground biomes.
Discrete containing-cell IDs and interpolated cave fields are separate values.

The surface selection step reuses the material pass's exact integer terrain
height logic through `terrain_height.bend`. Thin and nonaligned tiles can put a
quart anchor outside the resident density bounds; those few anchors evaluate
identical globally aligned cells in a small local lattice on the selected
backend. They do not clamp to neighboring columns or extrapolate missing data.
The two cave dispatches remain two dispatches: height/carver probes first,
then fused density/tunnel/ravine/biome nodes. Only layout acknowledgements cross
the host boundary. Carving selects each voxel's registered biome carvers from
the resident quart ID. Rounded ravine nodes also use the selected cave biome.

A carved material snapshot retains the immutable biome volume that produced it.
NBT, compressed chunks and MCA serialization consume that snapshot, preserving
vertical quart ordering across negative sections. Rebuilding diagnostic cave
fields does not change previously carved chunk bytes; block regeneration
restores the original surface-only snapshot and clears dependent fields.
Command 36 provides bounded raw ID queries for independent diagnostics; Java
performs no climate search, biome assignment, carving or palette computation.

Validation uses vertical plains/lush/dripstone/deep-dark strata with distinct
carvers, non-power-of-two terrain cells, explicit heights and absent cave
targets, plus a coastal window containing both water and land. Independent
interval search and integer density tests check IDs, while
separate rules check every carved voxel and independently decoded NBT/zlib.
Complete MCA fixtures check every saved quart ID and all 1024 records, followed
by actual Minecraft region/palette/SerializableChunkData loading and reopening.
The four strata fixtures plus actual vanilla/Terralith/combined profiles check
811,920 biome IDs and 811,008 carved voxels per CPU/GPU/observer run; all 656
expected Metal commands are observed. The separate coastal fixture preserves
remapped shores above the lush cave layer on CPU/GPU/observer. Three complete
MCA runs each contain 49,152 lush-cave quart entries and 81,920 plains entries,
with identical file hashes and nine observed Metal commands. Minecraft reopens
the two retained files and decodes 33,554,432 blocks. Java concurrency/snapshot
tests and the full host Fabric/NeoForge build pass, including 64 native tests
(11 explicit device/fixture tests remain ignored). Material-column regression
hashes are unchanged. Evidence is in
`docs/benchmarks/bend-cave-biomes-correctness.json`.
These are still unlit component checks. Aquifers/lakes, cave decorations,
composed-density/exterior refinements, the remaining feature/structure/light
pipeline, usable world types and complete comparative benchmarks remain pending.


Aquifer preparation now projects the registered five-graph range into an
immutable Bend cache alongside the three primary terrain graphs. The resident
worker evaluates flooding/erosion, fluid spread, lava, pressure-barrier noise
and preliminary surface height on the selected CPU/GPU backend. Surface search
uses the registered bottom, step and explicit-height mode, with density searches
bounded to physical world layers. Distant coordinates retain their compensated
residuals; both seed words remain independent inputs. No graph work or surface
search is implemented in Java.

Preparation validates the actual referenced programs, including root counts,
numeric opcodes, dependencies and finite parameters. Missing aquifer metadata
means disabled. Catalog preparation, cave-noise queries and saved material/cave
snapshots retain the immutable aquifer inputs; re-preparing geology or loading a
new profile requires explicit aquifer preparation. Raw field queries and
re-preparation do not mutate already generated chunk bytes.

These field checks were merged in
[PR #46](https://github.com/ArcaneArts/Retina/pull/46), commit `fd96650`.
Both actual push and PR CI builds passed. The following resident stage now
consumes these fields; lakes and running-world integration remain required.

Eight controlled profiles and actual vanilla/Terralith/combined registries pass
on CPU, GPU and the stock-runtime Metal observer. Each run checks 3,630 field
scalars against independent equations and requires exact serialized preliminary
heights; maximum relative error is 1.044e-6. Nineteen malformed settings/graph
fixtures reject explicitly, with non-finite wire values rejected by the loader.
Tests cover empty/repeated/reordered/4096-query batches, both seed words, signed
extremes, non-power search steps, world-ceiling bounds, cache retention and
saved chunk byte stability. All 118 expected Metal commands are observed and
observer output hashes match the ordinary GPU executable. Concurrent Java
callers, shutdown, existing geology/cave-biome regressions and the full host
Fabric/NeoForge build pass, including 64 native tests (11 explicit tests remain
ignored). Evidence is in
`docs/benchmarks/bend-aquifer-fields-correctness.json`.

### Resident aquifer geometry and cavity fluids

The worker builds preliminary surface heights, randomized fluid centers and
pressure-barrier vertices in three selected-backend dispatches. Center cells
are globally aligned to 16 × 12 × 16 blocks, seeded with both world-seed words.
Thirteen cached surface probes and the loaded flood/erosion/spread/lava graphs
determine each center's fluid level and material. Four-block-aligned barrier
vertices remain resident alongside the centers; preliminary heights are
discarded after construction.

Carving examines the nearest three of twelve centers, interpolates the cached
barrier field and applies the current Rust approximator's pressure curve.
Eligible cavity voxels become air, registered default fluid or registered lava;
pressure barriers retain the original material. The existing foundation,
underwater-floor and non-carveable-material rules still apply. Pressure uses
the interpolated cave chamber density clamped to at most -0.02, matching the
negative-carver proxy rather than reevaluating terrain programs per voxel.
Explicitly disabled or absent aquifer settings, once prepared, use the global
fluid picker. No fluid calculation is delegated to Java, Rust or custom shaders.

Resident caches cover both interpolation endpoints and all twelve center
candidates. Globally aligned layouts and compensated positions preserve shared
values across overlapping tiles and distant/negative coordinates. Queries
require matching coverage/seed, and enabled carving rejects stale or missing
fluid geometry. Surface/shore rebuilding clears old geometry while retaining
typed aquifer inputs. Cache rebuilding or input re-preparation leaves saved
material/cave snapshots and encoded chunks unchanged.

Eight controlled profiles plus actual vanilla/Terralith/combined exports pass
on CPU, ordinary GPU execution and a stock-runtime Metal observer. Each run
independently checks 332,060 substance decisions and 442,512 carved voxels,
including air, default-fluid, lava and solid-pressure outcomes. Complete fixture
chunk NBT, zlib round trips, final-block heightmaps and 3D biome IDs are decoded;
chunk hashes match across all three runs. All 524 expected Metal commands are
observed. Additional cases cover overlapping/reordered tiles, thin tiles,
negative/distant coordinates near signed-i32 limits, seed mismatches, malformed
queries and snapshot/cache invalidation. Concurrent Java callers, rebuilding
and shutdown pass, as do host Fabric/NeoForge builds and 64 native tests
(11 existing explicit tests remain ignored). Reproducible inputs, source hashes
and results are in `docs/benchmarks/bend-aquifer-placement-correctness.json`.

This remains unlit component work. Lakes, full composed-density refinements,
decorations, structures, lighting and the production world backend remain
required before advertising a usable Retina Bend world type.

The resident aquifer placement cycle merged in
[PR #47](https://github.com/ArcaneArts/Retina/pull/47), commit `5bb167d`.
Both actual push and PR CI builds passed.

### Registered surface-lake budgets and sparse candidates

Geology projection now retains each biome's exported total/lava placement
chances and barrier material IDs. Missing recipes mean zero budgets; malformed
chances and invalid palette references reject preparation explicitly. No
rainfall or biome-name heuristic replaces the loaded data.

One selected-backend Bend dispatch plans seeded candidates in global 128-block
cells. The candidate center evaluates the registered climate graph and target
index, then uses that biome's loaded recipe budgets and barrier materials.
Lava has priority and uses quarter-radius geometry, following the existing Rust
approximation. Compensated center coordinates and both seed words preserve
negative/distant positions and repeatable overlapping queries. Raw settings and
candidate queries do not mutate generated chunk snapshots. Java only transports
the raw batches and manages worker lifetime.

Six controlled profiles and actual vanilla/Terralith/combined exports pass on
CPU, ordinary GPU and a stock-runtime Metal observer. Each run checks 2,331
candidate rows and 389 loaded settings against independent hashing, climate
search and placement equations. All 77 expected Metal commands are observed,
with identical result hashes. Cases cover maximum batches, empty indices,
water/lava/zero/partial budgets, 23 malformed profiles, both seed words,
negative/distant coordinates, reordered/repeated requests and unchanged saved
chunk bytes. Actual Java callers verify concurrency, reload, shutdown and
snapshot retention. Host Fabric/NeoForge builds pass, including 64 native tests
(11 existing explicit tests remain ignored). Cave-biome regression checks
175,104 voxels and 175,806 biome IDs per backend, with unchanged hashes and all
304 expected Metal commands observed. Resident aquifer regression covers
332,060 substance queries and 442,512 carved voxels per backend across controlled
and actual registry profiles, with unchanged hashes and all 524 expected Metal
commands observed. Evidence is in
`docs/benchmarks/bend-lake-candidates-correctness.json`.

This is candidate planning, not lake placement: bank probes, contained fluid
levels, basin carving and material/fluid application remain required. The
production Bend world type, remaining feature/light pipeline and complete
Rust-versus-Bend region benchmarks remain pending.

### Resident lake basins and material integration

The lake cache now retains five exact bank heights per global candidate and a
bounded waterline (`max(world_min, min(banks) - 2)`). The selected CPU/GPU runs
candidate planning, bank probes, level reduction and surface-climate biome flags
as four bulk dispatches. Fractional centers and bank probes use compensated
coordinates; probes outside the density tile evaluate the same globally aligned
lattice, avoiding tile-edge clamping. One neighboring 128-block cell on each side
covers the full noisy ellipse and rim. Resident metadata is packed in Bend into
one word table, keeping the stock compiler's captured arity below its limit.

Command 43 prepares the immutable plan; command 44 is a bounded read-only bank
query. Building/rebuilding plans preserves generated snapshots. Command 22 uses
the plan, if present, to reshape raw geometry before registered surface material
rules run. It searches neighboring candidates in a fixed order, chooses the
nearest eligible basin, adds seeded simplex edge noise, and smooths a short rim.
Water/lava volumes, two-block solid floors and optional loaded barrier materials
are included in the final block runs. Ordinary columns keep their existing
geometry. CPU execution with a lake cache uses the same two-pass geometry and
coating arrangement as GPU execution so slope neighbors see the reshaped ground.
No intermediate block data leaves the Bend worker.

The cache clears with a successful profile/model, density, surface or geology
replacement; cave/aquifer preparation retains it where geometry is unchanged.
Bad commands retain state. Empty climate indices, excluded biome flags, small
world heights, low waterlines and distant/negative coordinates are covered.
The per-column lake exclusion flags now use the same pre-carving shoreline
selection as command 29, even when a caller has only run raw surface command 16.
The separate shoreline cycle below records that integration. This component
does not expose a finished Bend world preset, light regions or establish complete
Rust-versus-Bend performance.

Reproduce independent bank, material and serialization checks with
`scripts/test_bend_lake_basins.py` / `bendLakeBasinTest`; the existing
`bendLakeWorkerTest` also checks actual Java concurrent bank callers, plan
rebuilds, generated snapshots, cache invalidation and process shutdown.

The independent oracle checks 1,490 bank heights and 1,160 final columns per
backend across 13 controlled profiles plus vanilla, Terralith and their combined
registry. CPU, normal GPU and observed Metal output agree, including three full
chunk NBT/zlib round trips per run. All 715 expected device commands were
observed. Cases include lakes spanning a region edge, reversed/repeated queries,
seed changes, negative/distant coordinates, exclusion flags and empty/thin worlds.
The Java worker lifecycle checks and Fabric/NeoForge host build pass (64 native
tests, 11 ignored). Evidence is recorded in
`docs/benchmarks/bend-lake-basins-correctness.json`.

Existing candidate output is unchanged (2,331 candidate queries and 389 metadata
rows per backend). Cave-biome output matches all eight prior profile records,
now checked together across 838,656 carved voxels and 839,673 biome IDs. Aquifer
placement retains its earlier hash across 332,060 substance queries and 442,512
carved voxels. Observed Metal runs account for all expected commands in each
regression suite. These counts describe component validation, not generation
throughput.

The lake-basin cycle merged in
[PR #49](https://github.com/ArcaneArts/Retina/pull/49), commit `5bc3817`.
Both actual push and PR CI builds passed.

### Lake exclusions follow finalized pre-carving coasts

The lake flag pass now shares shoreline selection with command 29. Shore targets
are excluded from inland climate selection when an inland target exists; real
land/water transitions within the existing six-block probe disk select the
registered coastal alternative. Shore-only profiles retain their existing
fallback. Candidate-center recipe budgets and five-bank waterlines remain
unchanged. A high lake waterline alone cannot classify an individual column as
inland: the original terrain may dip to a genuine coast near the basin rim.

All heights and climate selection execute inside the existing Bend flag
dispatch. Probes beyond a small resident density tile evaluate the same globally
aligned density cells in Bend, retaining signed/distant coordinates instead of
clamping. Profiles without shore targets keep the raw lookup path. No extra
dispatch, intermediate upload/readback or Java terrain calculation is added.
Shoreline's existing integer-height fallback now calls its underlying shared
height module directly, avoiding a module dependency cycle.

The new regression fixture reproduced the old bug before the source change:
a raw beach target allowed a lake in columns that finalized to an excluded
swamp target. Independent integer-height and climate-search checks cover both
orders (16/43/22 and 16/29/43/22), coastal gullies, inverse coastal exclusions,
small/overlapping density tiles, shore-only and no-shore inputs. The independent
material oracle also now accounts for neighboring lake slopes in untouched
columns and preserves their original below-sea fluids. Those cases exposed
previous oracle omissions, rather than requiring production material changes.

Reproduce with `scripts/test_bend_lake_shorelines.py` /
`bendLakeShorelineTest`. The Java lake-worker test checks that successful
shoreline finalization invalidates both block and lake caches and that rebuilding
them preserves the generated chunk bytes. Component lakes/shorelines remain
unlit; complete terrain composition, vegetation, structures, lighting and the
production backend/world presets are still required.

Validation covers six controls plus actual vanilla, Terralith and combined
profiles on CPU, GPU and observed Metal: 1,694 material columns and 795 finalized
surface columns per backend, identical result hashes and all 548 expected device
commands observed. The existing shoreline aggregate is unchanged across 3,342
columns/158,080 material voxels (290 observed commands). Basin output is unchanged
across 1,490 bank heights, 1,160 columns and three NBT/zlib chunks per backend
(715 observed commands). The Gradle control task, actual Java lifecycle test and
Fabric/NeoForge host build pass, including 64 native tests (11 existing explicit
tests ignored). Source, input and executable hashes are retained in
`docs/benchmarks/bend-lake-shorelines-correctness.json`. The new harness accepts
`--output` so a registry run can retain its report separately from the Gradle
control run.


### Registered ore recipes and GPU replacement policy

Geology preparation now projects registered ore IDs, count/rarity and height
parameters, ordered replacement bands, air-exposure discard chances and sparse
biome memberships entirely in Bend. Optional absent ore lists remain empty.
The resident profile retains the packed ore/material/flag table across read-only
queries and surface rebuilding; successful reload or model replacement clears
it. CPU and GPU batches select the first matching inclusive band, preserve zero
replacement and apply the loaded exposure rule. Java carries raw query bytes.
Sparse vein planning, rasterization and replay into generated blocks remain
required; this cycle does not yet create ore in a usable Bend world preset.

Array projection keeps ordinary Data parameters across entries instead of
copying a linear parser closure, which the stock runtime rejects. Geology
preparation retains the prior worker state as an opaque owned value while its
registry continuations run, reducing captured argument expansion. Existing
carveable material flags share the packed store and retain their previous
semantics. No Rust generator or encoder is used by the Bend component.

The current macOS generated program exposes an Apple Clang 21 code-generation
failure. The harness supports an explicit `RETINA_BEND_CC` override; LLVM Clang
23.1.2 builds the unmodified generated code at `-O3` with the Xcode SDK/linker.
A separate observer adds only stock Metal command-buffer timing diagnostics.
See `docs/BEND_REGISTRY_WIRE.md` for reproducible compilation commands. Compiler
and test evidence are recorded with the component correctness report.

Validation checks 93 registered recipes, 7,079 membership entries and 511,250
replacement queries per backend across five controls plus actual vanilla,
Terralith and combined profiles. All 27 malformed schemas reject cleanly.
Normal CPU, GPU and observed Metal outputs agree; the observer accounts for all
190 expected device commands. Concurrent Java queries, malformed requests,
surface rebuilding, snapshot retention, profile reload and process shutdown
pass through the Gradle worker task. The Fabric/NeoForge host build passes,
including 64 native unit tests (11 existing explicit tests ignored).

Cave checks cover 884,736 carved voxels per backend. Cave-biome and aquifer
regression aggregates retain their earlier hashes across 838,656 cave-biome
voxels/839,673 biome IDs and 332,060 aquifer substance queries/442,512 carved
voxels respectively. Lake-basin aggregates also retain their earlier hashes,
including final columns and three full chunk NBT/zlib round trips per backend.
Lake/shoreline composition retains its earlier aggregate across 1,694 material
columns and 795 finalized surface columns per backend. These regression runs
use the normal CPU/GPU engines; the new 190-command observer proof applies to
the ore suite. Evidence is in
`docs/benchmarks/bend-ore-inputs-correctness.json`.


The registered ore-input cycle merged in
[PR #51](https://github.com/ArcaneArts/Retina/pull/51), commit `702e698`.
Both actual push and PR CI builds passed.

### Registered ore attempts and local GPU vein geometry

Pure Bend planning now reads each registered recipe's rarity, inclusive count
range, uniform or triangular height range and plateau. SplitMix64 state and
sign-extended global chunk anchors use exact word-pair arithmetic. Both accepted
ore-layout schemas use independent per-attempt geometry streams in Bend;
geometry draws cannot move a later attempt when a candidate is clipped. Attempt
outputs use balanced trees and exact additive random-state jumps, so individual
attempts can evaluate in parallel without a deep returned list. Exact
Rust seed matching is not required by the approximation contract.

Sphere-chain veins and scattered deposits execute on the selected CPU/GPU
backend. Sphere centers use local offsets, retaining fine shape at distant
world coordinates. Strict sphere containment pruning preserves the union;
scattered deposits retain bounded offsets and per-point exposure random words.
The same reusable Bend routines will feed resident rasterization and replay.
Commands 47/48 currently expose raw diagnostic batches; Java only transports
bytes. They neither change existing caches nor generate ore in a running world.
Spatial biome filtering, region clipping, sparse raster masks and ordered final
block replay remain required, alongside the rest of the full parity checklist.

Reproduce component checks with `scripts/test_bend_ore_planning.py` or
`bendOrePlanningWorkerTest`. The independent Python reference checks full seeds,
negative/distant anchors, signed height extremes, count/rarity rules, geometry,
repeat/reordered batches and malformed input. These correctness checks do not
establish complete-region throughput or a usable Bend preset.

Validation covers ten controls plus actual vanilla, Terralith and combined
profiles: 87,680 attempts and 125,409 geometry pieces per backend. The exact
attempt aggregate matches CPU/GPU. Maximum GPU geometry error against the
independent reference is 0.00000095367431640625 blocks; geometry repeats exactly
within each backend. All 153 expected Metal commands are observed with the
single-line diagnostic program. An earlier deep returned attempt list failed
with a stock-runtime memory fault on a 741-attempt GPU result; balanced trees
and additive random-state jumps pass that case and the registered maximum of
1,024 attempts. Evidence and source/input/executable hashes are retained in
`docs/benchmarks/bend-ore-planning-correctness.json`.

The real Java CPU/GPU worker concurrency/reload/shutdown checks and full host
Fabric/NeoForge build pass (64 native tests passed; 11 existing explicit tests
ignored). Normal CPU/GPU ore-input, cave-biome and lake-basin regressions retain
their previous aggregate hashes with all three actual registry profiles. These
cover 511,250 ore replacement queries, 838,656 cave-biome voxels/839,673 biome
IDs, and 1,490 lake bank heights/1,160 final columns plus three serialized chunk
round trips per backend. Compilation ran separately at reduced priority, with
one Bend native compiler and two-job Rust builds.
