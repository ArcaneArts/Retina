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

The playable backend currently uses Base and local modules, without bend-kit
dependencies. [The package evaluation](BEND_PACKAGES.md) pins and tests bytes and
pure zlib on real generated NBT in an isolated fixture. Packed growable buffers
are promising; the package codec makes smaller output but measured slower.
Production adoption and complete NBT/region benchmarking remain separate work.

## Playable terrain preview

The current integration exposes **Retina Rust** and **Retina Bend** in the world
type selector. Rust keeps the existing `retina:gpu` preset and remains the default
Retina selection. The old chunk preset still decodes existing saves but is no
longer offered in the selector. Bend uses `retina:bend`, with its own saved `bend`
mode; registry/datapack dimension import preserves that mode.

The Bend Overworld preview combines registered density graphs, GPU interpolation,
climate/biomes, coastal materials, lakes, material layers, cave fields, cave biomes,
aquifers, carving, ores, cave floor/ceiling decoration and surface vegetation,
including trees, followed by registered snow/freezing. Bulk terrain and the final
weather pass execute on the selected Bend backend; irregular
surface placement and neighboring-anchor composition execute on the Bend CPU.
Chunk NBT, zlib compression and initial MCA sector assembly also execute in Bend.
Java extracts the effective Minecraft registries and handles
requests, decoding and atomic publication of opaque MCA sectors. Rust is never a
fallback for this mode. Nether/End retain the preset's vanilla generators.

Structures and Bend lighting are still pending. Preview chunks
explicitly have `isLightOn=false`; Minecraft lights them.
F3 names the backend and stage, labels the preview limitations and reports the
last 20 completed **region** latencies, derived chunks/second and amortized
chunk time. These unlit figures must not be compared to lit Rust regions as
equivalent workloads.

The preview generates globally aligned 32x32-chunk regions, with resident
intermediate fields and one private MCA per cached region. All generation and
encoding remain in Bend. The streamed writer consumes eight independent chunk
encodes/compressions through file IO before constructing the next batch; only
the small region header remains live across all output batches. The Java bridge
installs Bend's opaque header before publication, without rewriting chunk bodies.

Large ore cores use the identical ordered Bend calculation on two CPU workers.
The original whole-region GPU ore step exhausted the default 2GiB heap, while
the same CPU calculation completed with the stock CPU span. Moving this stage
to CPU within a GPU worker still hit its shared 2GiB bound. Full-region GPU
workers therefore use stock `--gpu 4GB`; no runtime/kernel constants are changed.
Verified cores of at most 128x128 blocks retain GPU ore execution. Terrain, interpolation, lakes, caves, aquifers, carving, cave
dressing and snow/freezing still use the selected Bend backend; irregular
vegetation, NBT, compression and MCA output use CPU Bend.

Cached whole regions service DH and chunk/height requests and promote through
the existing I/O queue. Publication preserves completed chunks, player edits and
external records, replacing only unfinished terrain records. Minecraft's wide
status-probe ring reads existing files without generating terrain. Preliminary
biome-only holders use small Bend climate queries; real terrain requests replace
them with final saved 3D cave biomes. Those holders never overwrite completed
neighbors. The cache limit is 1024 complete regions in Bend mode as well as Rust.
Whole-region coalescing also applies at negative coordinates. Generation remains
slow and experimental; this is a memory/correctness change, not a claim of Rust
performance parity. Custom dimensions remain subject to the documented finite
lattice and voxel-work bounds.

### Running the preview

On the prepared development host, use `./gradlew runClient` from the repository
root and select **Retina Rust** or **Retina Bend** while creating a world. This
launches Fabric only. Gradle reuses the installed precompiled host worker at
`~/.local/share/retina-bend/2.0.36/playable/engine` and its adjacent `engine.gpu`;
no extra launch flags or Bend compiler invocation are needed. Gradle defaults to
two workers without parallel project execution; Cargo remains limited to two jobs.

Build the stock worker once, then explicitly package it; ordinary Java builds do
not start Bend compilation. On this Apple Silicon host the pinned runtime is
`~/.local/share/retina-bend/2.0.36/bin/bend`. The worker binary and adjacent
`engine.gpu` archive are copied as platform resources by:

```sh
CARGO_BUILD_JOBS=2 ./gradlew :fabric:build :neoforge:build \
  -PretinaHostOnly -PretinaBendExecutable=build/bend/engine \
  --max-workers=2 --no-parallel
```

Install the corresponding jar, create a fresh Creative world and select **Retina
Bend**. Start with a small render distance. Initial runtime/profile preparation
and generation are slower than Rust; F3 and `Bend stage ...` log entries show the
active work. Existing Rust saves remain Rust saves. The packaged preview is
host-specific; it does not claim a tested runtime for other operating systems.
An explicit worker can be supplied with `RETINA_BEND_EXECUTABLE` or JVM property
`retina.bend.executable`. GPU is required by default; explicit CPU experiments use
`-Dretina.bend.execution=cpu` and two worker threads. Full-region GPU mode
requires a device that can address the 4GiB stock runtime span; only the Apple
M4 Max/Metal host is verified here. The advanced `retina.bend.gpuMemory` JVM
property changes that stock runtime argument. Invalid/unsupported requests
report actual runtime errors; they never select Rust or vanilla.

Packaged workers extract to a content-addressed directory under
`~/.cache/retina/bend-2.0.36/`. Metal's source-library cache is tied to executable
location, so a new binary's first GPU start can take several minutes even with
the packaged pipeline archive; subsequent starts reuse that location. The worker
startup timeout allows this compilation and world shutdown can cancel it. Local
preview delivery warms this cache before the gameplay test. The packaged runtime
includes the [pinned Bend 2.0.36 license](https://github.com/bendlang/bend/blob/v2.0.36/LICENSE).

Focused local integration checks (using an already compiled worker):

```sh
nice -n 10 python3 scripts/test_bend_region_stream.py
nice -n 10 python3 scripts/test_bend_full_region.py \
  --profile build/bend/profiles/profile-0.json --gpu
./gradlew :common:bendWorldIntegrationTest -PretinaHostOnly \
  --max-workers=2 --no-parallel
```

The integration harness deliberately makes Rust unavailable. It checks combined
terrain loading, base-column agreement, unlit feature-status chunks, whole-region
coalescing/promotion, unfinished-probe repair and saved edit preservation. Component harnesses
add independent CPU/Metal voxel and NBT/zlib validation. These checks do not prove
full generator parity or a completed Bend goal.

An actual Fabric 26.3 client entered a fresh Bend world, received the Bend F3
report, placed an edit at negative coordinates, saved and reopened successfully.
The final client check also read generated terrain blocks before and after reopen
and confirmed the edit survived. Fresh spawn preparation took approximately
11 minutes under concurrent host load; the subsequent saved-world client check
completed in 22 seconds. Treat this as a slow experimental preview, not a
replacement for Rust performance. A prepared local example save avoids waiting
for that first spawn while inspecting the existing terrain.

Before the parallel lake-bank change, the combined functional test on this shared
Apple M4 Max observed 16.6/17.0 s
for two CPU batches and 25.7/23.3 s for two GPU batches. GPU lake geometry alone
accounted for about 14 s in the second batch. These are functional-test timings,
not an isolated or equivalent-workload Rust/Bend benchmark. GPU execution was
slower in those runs; a single language does not imply faster generation.

### Next integration work

The effective registry exports already contain the data needed for surface
features: the inspected vanilla profile has 182 decoration recipes (63 trees),
and Terralith has 553 (289 trees). The count/offset, build-relative height-provider
and registered spatial-count noise libraries now project the original instructions
in Bend. Typed ordered programs and terrain-dependent checks now preserve full
placement salts, selector groups, predicates, heightmaps and biome memberships.
A persistent live block overlay provides current materials/heights to those checks.
The lazy ordered walker and shared selector/count budgets now have a checked
component implementation, as do all ten registered block-provider families and
their initialized spatial noise stacks. Simple-block, block-column and disk
writers now have checked implementations against the live world overlay. An
ordered patch-to-run merger, final plant support/pair repair and core cropping
now connect accepted feature overlays to the chunk serializer. All 17 currently
registered feature families and their trunk/foliage/decorator subsets join the
neighboring-anchor coordinator in the playable worker. Retain that registered
placement behavior while adding structures and final GPU lighting.
The final repair pass uses the loaded plant floor masks and signed pair IDs;
it must also run after future structure writes.

For lighting, the exported `lighting` section supplies sky, optical state,
blocked-face and face-shape tables. `bend/light_rules.bend` now implements local
opacity/emission, paired face occlusion, direct sunlight seeds, monotone
attenuating block/sky edges and Minecraft nibble packing. The independent
`scripts/test_bend_light_rules.py --profile <loaded-profile.json>` harness checks
65,536 cases on CPU and GPU-required execution using all 2,438 optical state rows
and 42 face shapes from the inspected vanilla registry; both output hashes match.
This library is not yet imported by the world worker. The next implementation
must consume those tables and final decorated block runs, converge the resident
light field and handle neighbor boundaries before chunk serialization.
Keep `isLightOn=false` until Bend computes and validates the required light
arrays and boundary policy; the current preview intentionally relies on the
Minecraft light engine.

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
| Shared loaded registry export, including palette properties and climate intervals | `TerrainProfileData.java`, `RegistryGpuProgram.java`, `native/src/profile.rs` | Export decoupled from Rust initialization; actual vanilla, Terralith and Terralith+supplement registries pass with native library unavailable. Explicit Rust adapter receives byte-identical data and passes real Metal queries. Full structural profiles now stream into a resident Bend word tape with lossless field/array/string access and independent full-value checks. Loaded climate/ridge stacks now resolve schema keys and convert numeric parameters entirely in Bend; numeric terrain DAGs now project and execute in the resident worker; material predicates now project and execute on CPU/GPU; GPU-derived material contexts and exhaustive compact vertical block runs implemented; playable terrain preview integration implemented; full feature/light parity pending |
| Noise stacks, density bytecode, splines and GPU interpolation | `native/src/program.rs`, `program/`, `program.wgsl`, `noise3.wgsl`, `simplex.wgsl` | Reusable Bend seeded simplex/3D gradient kernels, weighted octave stacks and trilinear primitive implemented and independently checked on CPU and actual Metal. Integer/fraction coordinate handling checked through signed-i32 extremes. Numeric density opcodes 0..31, registered noise, ordered Hermite splines and nested trilinear fields implemented and independently checked with actual vanilla/Terralith/combined climate and terrain graphs on CPU/Metal. Compensated transformed coordinates preserve distant neighbors. Resident typed model projection and bounded persistent CPU/GPU queries implemented with reload invalidation; bounded top-level GPU lattices now retain samples for independently checked trilinear queries. GPU surface-height scans now consume the resident lattice; GPU-derived compact material layers implemented; resident lattice caching and playable preview integration implemented; full parity pending |
| Climate targets, biome selection, smooth boundaries and underground biomes | `climate.rs`, `climate.wgsl`, `RetinaBiomeSource.java` | Pure Bend balanced interval indices and surface/underground/coastal lookup implemented, checked against independent linear search with actual registered intervals on CPU and Metal. Typed resident-tape projection and persistent query commands implemented with explicit reload invalidation. GPU spatial climate fields and surface biome selection now consume resident density tiles; compact material layers implemented; interpolated terrain and selectable preview integration implemented; full parity pending |
| Coastlines, shore materials, rivers and material predicates/layers | `ShoreMaterialProfile.java`, `column_program.rs`, `materials.wgsl` | Pure Bend resident per-biome material DAGs and predicates (40..54) implemented with shared numeric evaluation, registered bands/sea/layer mode and bounded CPU/GPU query batches. GPU context/compact block runs implemented. Resident GPU coastal finalization now uses exact generated occupancy, six-block disk probes and registered climate alternatives before material generation; independent CPU/Metal checks cover inland/ocean exclusion, distinct materials and cache/coordinate rules. Playable terrain and 3D cave-biome integration implemented; inland river/ocean parity and full material parity remain required |
| Caves, ravines, rare surface entrances and cave decoration | `GeologyProfile.java`, `geology.rs`, `features.rs`, `caves.wgsl` | Typed geology, cached globally aligned GPU/CPU cave fields, rounded noisy ravines and registered column carving implemented; independent voxel/serialized-region and actual Minecraft decoder checks pass. GPU/CPU depth-dependent cave-biome volumes now select registered carvers and serialize actual 3D quart IDs, retaining finalized surface biomes near the roof. Resident aquifers now supply carved cavity materials. Registered floor/ceiling cave dressing, vines, blossoms, dripstone and sparse plant survival now execute on the selected CPU/GPU backend. Playable terrain integration implemented; composed-density/exterior refinements and ordered surface decorations remain pending |
| Lakes, aquifer fields and fluid barriers | `TerrainFeatureProfile.java`, `program/lake_sparse.rs`, `aquifers.wgsl` | Typed aquifer graph projection and GPU/CPU flooding, erosion, spread, lava, barrier and preliminary-surface evaluation implemented. Globally hashed resident fluid centers, trilinear barrier fields and nearest-center pressure now place registered cavity fluids while preserving protected/pressure-barrier materials. Registered lake budgets/materials, globally seeded candidates, five bank probes, bounded fluid levels and noisy basin/rim placement implemented on CPU/GPU before material coating. Independent block/NBT/zlib checks cover fluids, barriers, overlap and signed/distant coordinates; lake exclusions now use finalized pre-carving coast selection, including global probes outside the resident tile. Out-of-tile bank vertices now execute in bulk with exact prior geometry and material bytes, improving measured GPU lake-stage latency by 10.1–14.6×. Playable terrain integration implemented; full parity and performance work remain required |
| Ore height/count/replacement/discard rules and GPU rasterization | `geology/`, `ore.wgsl` | Registered recipe projection, packed membership/material tables and selected-backend ordered replacement/exposure policy implemented. Registered count/rarity/height attempts and local sphere-chain/scattered geometry now execute on CPU/GPU. Local sphere-union masks and ordered scattered points now calculate in the same selected-backend evaluation as geometry. Spatial surface/cave membership filtering, stable neighboring contribution buckets and ordered resident-block replay now run in one selected CPU/GPU evaluation, with immutable exposure halo checks and independent voxel/NBT/zlib validation. Playable terrain integration implemented; full parity and performance work remain required |
| Ordered decoration, registered counts, provider noise and placement modifiers | `DecorationProfile.java`, `decoration/placement*`, `counts/`, `provider_noise/` | Registered recursive integer providers, count/offset projection, build-relative height distributions and actual-permutation spatial count noise independently checked on CPU/Metal. Typed ordered-program projection, exact placement-salt grouping, recursive predicates, live block/heightmap overlay and terrain-dependent checks now independently checked on CPU/Metal. Lazy ordered count/layer/cuboid execution and shared selector budgets now have an independent CPU/Metal component harness. All ten block-provider families, full 64-bit weighted decisions, current-state property transforms and initialized permutation/offset noise now independently checked on CPU/Metal. Simple-block, block-column, disk, ordinary plant, aquatic and bamboo writers now independently replay live material changes and RNG state on CPU/Metal. Ordered accepted FEATURE overlays now merge into resident runs with final plant repair, core cropping and independently decoded MCA checks; all 17 registered feature families now join canonical neighboring-anchor replay in the playable CPU vegetation stage; full-region parity remains pending |
| Trees and decorators, giant mushrooms, fallen trees, disks, vegetation patches, block columns, bamboo, aquatic plants and attachments | `decoration.rs`, `decoration/`, `tree_shapes.rs` | Simple-block, block-column, disk, ordinary plant, aquatic and bamboo writers and registry projections independently checked on CPU/Metal, including nested recipes and accepted writes through MCA serialization. Registered tree trunks/crowns, weighted axis/distance states, connected leaves, roots, vines and cocoa now have pure Bend writers and ordered role replay. Resident anchor coordination now combines registered placement streams with the ten implemented writer families, preserving live heightmaps, cached parent queries, recipe identities, bounded resumption and explicit unavailable-family outcomes; original-profile replay and a complete ten-family fixture are validated on CPU; earlier nine-family Metal controls retain their historical hashes. Registered huge mushrooms and fallen logs now preserve cap-face/axis properties, ordered live providers and decorators on CPU/Metal. Registered vines/multiface attachments now have independent CPU/Metal writer checks and join the ten-family CPU coordinator. Registered vegetation patches and all nested selector/composite/placement programs now have independent CPU voxel/RNG checks and join the complete 17-family CPU coordinator. Accepted-command journals preserve write roles and transient writes through canonical neighboring-anchor replay, final repair and MCA encoding on CPU. The persistent playable worker now executes all 17 families with coherent neighboring terrain before cropping; full-region parity remains pending |
| Jigsaw pools/templates/processors, structures spanning regions and locate queries | `StructureProfile.java`, `structures.rs`, `structure_processors.rs`, `queries.rs` | Pure Bend structure-set candidates, frequency/exclusions, weighted biome selection and full jigsaw/standalone layouts implemented and independently checked against original vanilla/Terralith/combined profiles. Rotated template block replay and ordered processors now write the resident terrain overlay and emit serialized block-entity updates. Terrain supports and serialized start/reference records now have independently checked Bend implementations. Whole-start replay/caching, locate and playable integration remain pending |
| Structure entities/block entities/loot and attachment rotations | `nbt.rs`, `structures.rs`, `structure_processors.rs` | Processor tag changes, block-entity coordinates/keepPacked/loot seeds, and lossless typed-NBT output implemented and independently decoded. Template entity transforms, deterministic UUIDs and attachment rotations now serialize into actual generated chunk NBT. Whole-region coordination and live Minecraft loading remain pending |
| Snow, freezing, plant support and path/gravel restrictions | `LightingProfile.java`, profile material/survival tables, `region.rs` | Final registered plant floor-mask and signed-pair cleanup implemented in the overlay merger and independently checked on CPU/Metal, including paths and crop/build boundaries. Registered final snow/freezing now projects loaded temperatures, feature/precipitation flags, layer IDs, height masks and support rules in Bend. Independent per-column run scans execute on the selected CPU/GPU backend after vegetation and before MCA serialization; snow avoids ordinary frozen-ocean ice and active lake columns. Complete structure-stage placement restrictions remain pending |
| Full 64-bit seeds, signed/distant coordinates and repeatable ordering | `ChunkRequest`, native hash/random routines | Exact word-pair add/subtract/multiply/bit operations implemented; 3,689 operations independently verified. Typed i64/f64-to-F32 rounding independently verifies 131,072 scalar conversions per CPU/GPU run, including distant-value midpoint cases. Integration and coordinate/order checks pending |
| Block/biome palettes, bit-packed long arrays, heightmaps, modified UTF-8 NBT and current data versions | `region.rs`, `nbt.rs` | Pure Bend palette remapping (49,664 indices), final-block heightmaps and complete fixture chunk encoding implemented. Minecraft 26.3 (data version 5023) reads/reopens mixed and prelit fixture chunks through actual region, palette and SerializableChunkData decoders. Resident generated base/material columns now encode into current-version NBT/zlib with exact state properties, quart surface biomes and final-block heightmaps; sparse generated-region/runtime integration implemented and checked in a running Fabric world |
| LZ77, fixed-Huffman DEFLATE, zlib/Adler-32 and stored fallback | `region.rs` uses libdeflater | Implemented in `bend/compression.bend`; independent fixture tests pass |
| Independent parallel chunk compression | Rayon chunk assembly in `region.rs` | Implemented Bend fork/join API; generated MCA staging now compresses eight independent chunk tasks per batch; final voxel/light integration pending |
| GPU lighting, interior-chunk validity, boundary handling and one final region write | `lighting.rs`, `lighting.wgsl`, `region.rs`, `LightSeams.java` | Serialization of supplied light arrays/padding and completion flags validated with Minecraft. Local optical/face/seed/attenuation/nibble rules independently checked on CPU/Metal. Full field computation and interior/boundary validity remain pending; preview uses Minecraft lighting |
| MCA sector tables, external records, preserving existing chunks and atomic publication | `region.rs` | Pure Bend new-file inline planning/streaming now writes whole generated base/material regions from resident snapshots; independent and actual Minecraft decoders cover all 1024 chunks. Sparse batch merging, completed/external-record preservation and atomic publication implemented; Minecraft edit/reopen tests pass |
| Persistent engine, bounded scheduling/singleflight, cleanup and actionable failure propagation | `NativeTerrain.java`, `RegionCoordinator.java`, `TerrainQueries.java` | Persistent Bend component worker and bounded raw Java transport implemented: resident noise stacks, GPU grids, Bend compression, 16 queued requests/32 MiB retained request payloads, cancellation, shutdown and fatal protocol/process failure checks. Full registry file loading and input queries implemented; actual sparse region commands, coalescing, lifecycle and cancellation integrated in the playable preview |
| DH surface/height requests generate temporary whole regions, 1024-region cache, eviction and promotion | `TemporaryRegions.java`, `RegionCoordinator.java` | Selected-backend coordinator/cache now stores 1024 complete 32x32 regions. Overlapping/distant same-region requests coalesce and promotion preserves edits and completed terrain. Full performance/lighting parity remains pending |
| Backend selection survives datapacks, codecs, save/reopen and server lifecycle | `RetinaChunkGenerator.java`, preset/platform mixins | Bend codec/preset and registry-import preservation implemented; vanilla/Terralith registry checks and actual Fabric save/reopen pass. Full datapack gameplay combinations remain required |
| Exactly two selectable world types; Rust default; legacy chunk-save migration aliases | `world_preset/gpu*.json`, world preset tag, language keys and generator codec | Exactly two selectable presets implemented; Rust remains default, legacy chunk aliases decode existing saves, Bend explicitly labels its preview limitations |
| Color-coded stage report and last-20-region derived throughput | `GenerationMetrics.java`, `TerrainDebugReport.java`, `timings.rs` | Bend preview reports backend/stage and last-20 full-region latencies with actual 1024-chunk counts; detailed stage distribution and full lighting parity remain pending |
| Reproducible pinned runtime, packaging, licensing and supported-loader builds | `gradle/native.gradle`, Fabric/NeoForge modules, CI | Pinned stock 2.0.36 host-worker packaging and license implemented; Fabric/NeoForge host builds pass. Other host binaries and production integration remain pending |
| Independent format tests, region/concurrency/query/cache tests, actual Minecraft loading, edit persistence and datapack combinations | shared unit/GPU tests and QA harnesses | Primitive tests and Minecraft decoder/reopen tests of complete fixture chunks implemented. Actual generated-world terrain, F3 telemetry, edit persistence and save/reopen pass on Fabric. Full feature/light parity, DH gameplay and NeoForge gameplay remain required |
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


### Parallel lake bank density probes

Lake-bank probes outside the resident density tile previously built a small
`2 x ny x 2` density lattice serially inside each bank task. Those probes are
necessary because a lake's center and banks can lie beyond the requested batch.
The worker now evaluates their exact density vertices in one balanced Bend
fork tree, then reduces the resident samples into the same five bank heights.
Covered probes still use the existing terrain lattice, and inactive candidates
skip density evaluation. Candidate selection, coordinates, interpolation,
integer height rules, fluid levels and material decisions are unchanged.

The additional vertex cache is local to one lake preparation. It is limited to
1,048,576 entries; larger custom workloads use the existing direct bank routine.
The common path adds one stock selected-backend dispatch, with no extra Java
transport or explicit intermediate readback. On the tested Metal runtime the
vertex cache uses the existing shared memory corpus. Both paths remain pure Bend.

`scripts/benchmark_bend_lakes.py` alternates preserved and candidate workers with
identical exported registry profiles, two CPU workers and reduced priority.
Preparation and final block queries are outside the timed command-43 interval.
It compares all bank geometry and final material-column bytes before and after
repeated construction. Its optional stock-runtime Metal observer also checks
the candidate's output and actual device commands outside measured intervals.
This is lake-stage evidence, not an equivalent complete lit Rust/Bend region
benchmark. Compilation and other task-owned tests must finish before measuring;
other applications on the shared host remain uncontrolled.

Measured warm lake-stage medians on this Apple M4 Max (66x66 columns):

| Backend | Profile | Before (ms) | After (ms) | Before / after |
| --- | --- | ---: | ---: | ---: |
| CPU (2 workers) | vanilla | 586.86 | 611.39 | 0.96x |
| CPU (2 workers) | terralith | 625.81 | 647.84 | 0.97x |
| CPU (2 workers) | combined | 645.34 | 636.60 | 1.01x |
| Metal | vanilla | 11334.38 | 1120.16 | 10.12x |
| Metal | terralith | 19603.34 | 1386.89 | 14.13x |
| Metal | combined | 19693.82 | 1349.94 | 14.59x |

Each profile has five warmups and five measured alternating runs. GPU medians
improved 10.1–14.6x. CPU medians ranged from 4.2% slower to 1.4% faster; their
mixed results and shared-host variation do not establish a CPU speedup. All bank
geometry and all 4,356 final material columns are byte-identical to the preserved
worker within each backend, before and after repeated preparation.

Independent CPU and GPU regressions retain the previous aggregate hashes for
1,490 bank heights, 1,160 final columns and three NBT/zlib chunks, plus the
shoreline suite's 1,694 material columns and 795 finalized surface columns.
The host-only observer uses the exact normal GPU archive, matches normal output
on all three real profiles and observes 12 Metal commands per benchmark workload.

Minecraft decoder, height-query, promotion, saved-NBT-edit/reopen and unfinished
probe replacement checks pass with Rust unavailable. Fabric and NeoForge host
builds pass. The packaged worker's cold start took 167.9 s before measurements;
the warmed start took 305 ms, and startup cancellation passes. The current
functional GPU integration generated its two 16-chunk batches in 11.94/12.51 s
under concurrent test load; these are functional timings, not a whole-pipeline
A/B comparison. The installed precompiled worker and its warmed content-addressed
cache are ready for plain `./gradlew runClient`.

Source/input/executable hashes, raw samples, p95 values, actual command traces,
validation and limitations are in
`docs/benchmarks/bend-lake-bank-parallel.json`. Oversized direct fallback workloads
are not included in this benchmark. Surface decorations, structures, snow/freezing,
field lighting, full 1024-chunk batches and equivalent complete lit Rust/Bend
region benchmarks remain required.

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


The ore-attempt/local-geometry cycle merged in
[PR #52](https://github.com/ArcaneArts/Retina/pull/52), commit `b3b1be9`.
Both actual push and PR CI builds passed. Its preserved artifact snapshot is
identified by source commit `1a69916`.

### Compact local ore union masks

`bend/ore_raster.bend` now consumes registered geometry inside one selected
CPU/GPU dispatch. It removes contained spheres, computes local integer bounds
and precomputes reciprocal radii. Balanced parallel leaves each own one U32
word of 32 voxel-union bits in Y/Z/X order. Overlaps produce a single bit and
padding remains zero. Geometry never crosses the host bridge between shape
construction and rasterization. Scattered deposits retain their original point
order and exposure random words, with local bounds for later chunk bucketing.

Read-only command 49 and the Java bridge expose raw diagnostics of these reusable
Bend routines. Spatial biome filtering, stable regional contribution buckets and
final resident-block replay remain required. This component alone places no ore
in a running world and establishes no complete-region performance advantage.

The independent harness checks registered sphere/scatter generation, conservative
bounds, local half-block voxel centers, every mask bit outside a recorded narrow
floating boundary band, scatter ordering, padding, repeated/reordered batches,
maximum batches, malformed requests, surface preparation, reload and worker
shutdown. It accepts the same actual vanilla/Terralith/combined registry pairs
and has an observed-Metal mode; Java performs raw transport only.

Validation covers seven controls plus actual vanilla, Terralith and combined
profiles: 693 veins, 243,008 mask voxels, 71,277 occupied voxels and 309 scattered
points per backend. There are zero ambiguous boundary voxels in these inputs.
CPU and GPU mask bytes match, and the normal GPU aggregate matches the observer
build with all 94 expected Metal command buffers recorded. The largest exercised
response is 60,416 bytes, including a full 256-row batch. This is correctness
evidence, not a region throughput measurement. Source, input, executable and
compiler evidence is in `docs/benchmarks/bend-ore-masks-correctness.json`.

The real Java CPU/GPU worker concurrency, reload, malformed-request, surface
preparation and shutdown checks pass. The full Fabric/NeoForge host build passes
with 64 native tests passed and 11 existing explicit tests ignored. Earlier ore
attempt/geometry regression aggregates remain unchanged across 87,680 attempts
and 125,409 geometry pieces per backend, including all three actual registry
profiles. Compilation used one Bend compiler and two Rust jobs at reduced
priority, separately from measurements.


### Resident spatial ore placement

Command 50 now modifies the resident generated material snapshot entirely in
Bend. Globally anchored chunk/recipe/attempt contributions retain stable Z/X
priority. Registered count, rarity and height rules feed surface/cave-biome
membership filtering, local geometry and union masks inside the same selected
CPU/GPU evaluation. Per-target-chunk buckets contain only intersecting masks from
the surrounding 3x3 anchor chunks. Different output columns replay in parallel;
within a column, ordered recipes read the current host material before applying
registered height bands and exposure-discard rules.

The core must contain whole chunks and have a resident one-block exposure halo.
Exposure reads the immutable pre-ore snapshot, so earlier ore replacements cannot
change later exposure decisions. Only core columns change; halo columns, material
context, cave biomes and the reusable profile stay intact. Compensated coordinates
preserve local geometry at signed-i32 limits. Regular-vein exposure draws derive
from attempt seed and local mask index; scattered points retain their ordered
geometry draws. Final first-free heights are recomputed for custom recipes that
replace air. Generated NBT/zlib and MCA serialization consume the resulting block
runs through the existing Bend encoders.

The Java bridge transports only a raw core descriptor and acknowledgement. A
complete region coordinator still needs to serialize each build/application
sequence as one lease; independent Java method calls alone do not provide that
lease. Rebuild the pre-ore snapshot before repeating an application, since custom
replacement chains need not be idempotent. This component is not yet a selectable
Bend world, a lighting implementation or a complete-region throughput result.

The density field should also cover neighboring anchor/quart biome probes and
the six-block coastal sampling disk. Actual-profile QA uses a 23-block density
halo around the ore core, while the material exposure halo remains one block.
Outside-resident density probes are correct but can repeatedly rebuild height
lattices and become very expensive. Descriptor bounds must still respect the
signed world limits. A single Bend `calculate!` evaluation may advance through
several stock-runtime GPU frontier turns: controlled command-50 probes observed
three Metal command buffers, rather than one device dispatch. No application
geometry or mask round trip through Java is needed between those turns.


Independent placement checks passed for eight controlled profiles and actual
vanilla, Terralith and combined registry snapshots on CPU, GPU and an instrumented
Metal observer. Each run checked 633,856 voxels, 6,888 eligible attempts, 30,103
candidate writes and 14,019 successful writes, including 2,487 contributions from
neighboring anchors. Four generated chunks per run passed independent NBT,
heightmap, 3D-biome and zlib checks. A 32x32 core matched four separately generated
16x16 cores requested in reverse order. Repeat builds, malformed requests, seed
mismatches and halo rejection preserved the expected snapshot. The observer
recorded 276 actual Metal command buffers for 216 expected minimum commands;
its final voxel hash matched the normal GPU and CPU runs exactly.

The Gradle Java worker task and Fabric/NeoForge host build pass; native tests
reported 64 passed, zero failed and 11 existing explicit ignores. Ore-mask
regressions retained their earlier hashes across all actual profiles. Compiler,
input, source, executable and validation evidence is recorded in
`docs/benchmarks/bend-ore-placement-correctness.json`. QA ran under concurrent
load; these timings do not establish full-region performance.


### Registered resident cave decoration

The cave component projects cave recipes and plant survival metadata directly
from the shared registry export in Bend. It scans compact block runs, excludes
surface air and fluids, and applies independently selected cave floor/ceiling
styles. Lush fallback coatings, clay, floor plants, hanging vines/blossoms,
registered dripstone states and other registered cave floors stay in their own
columns and run on the selected Bend CPU/GPU backend. Registered patch/vine
suppression preserves ownership for the later ordered feature stage.

Only core columns change. Source context and three-dimensional cave biomes
survive through NBT/zlib encoding. Hashes include the complete seed and signed
world coordinates. Sparse plant support/pair repair removes unsupported or
orphan floor plants, with final repair after all later stages still required.
The Java bridge carries only a raw core descriptor and acknowledgement.

Validation runs locally at reduced compilation priority. Rust builds stay at two
jobs; delivery commits and squash merge messages use `[skip ci]` following the
user's request to avoid GitHub Actions costs. PR/worktree/squash auto-merge delivery
remains required. This cave component does not complete ordered surface features,
structures or Bend lighting. The combined terrain preview described above uses
it while those components remain pending.

### Playable terrain composition and lighting foundation

The current cycle adds the selectable preview, serialized per-world Bend build
lease, sparse 4x4 batch publication, bounded temporary batch cache, promotion,
actual Minecraft terrain-stage loading, preliminary-status save protection,
backend F3 reporting and explicit stock-runtime packaging described above.
Local checks cover independent CPU/Metal cave dressing and sparse MCA outputs,
actual vanilla/Terralith registry loading, Minecraft decoding and saved edits,
packaged runtime reuse/cancellation, both host-loader builds and actual Fabric
client join/save/reopen. The separate lighting rules library begins the next
component; surface trees and a complete lighting implementation remain required.


### Registered decoration count and offset providers

`registry_decoration_counts.bend` projects counts and offsets from the original
ordered recipe programs, retaining their recipe/modifier/slot addresses. It
also distinguishes `count_on_every_layer` from ordinary counts; the later walker
must resume that modifier against its live block overlay. This component does
not independently multiply flattened selector branches or place any feature.
The shared selector budget and final world-worker integration remain pending.

`registry_int_provider.bend` parses the registered constant, uniform, biased to
bottom, trapezoid, clamped, clamped normal and recursive weighted-list forms.
Signed bounds and modifier-specific count/layer limits are checked in Bend.
Sampling returns both its value and the advanced random state. Seeds and random
words retain all 64 bits, including weighted totals above 2^32; constants consume
no random draws. Zero-weight entries remain inactive. F32 normal sampling is an
approximation, consistent with the absence of cross-backend determinism.

Spatial count rules use the exported, validated 256-entry BIOME_INFO_NOISE
permutation and its twelve gradients, rather than the terrain noise hash or a
fixed average. Noise-threshold and noise-based counts retain loaded levels,
factors, offsets and signed ratios. Integer/fraction coordinates preserve local
variation at negative, distant and signed-i32-edge positions; negative noise
scales reverse the compensated axes. Scales whose reciprocal exceeds the
coordinate kernel's 2^30 limit are explicitly unsupported by this component.

The independent local harness checks 97,056 samples per execution over full
vanilla, Terralith and combined input profiles plus controlled providers, and
rejects 21 malformed profiles on each backend. Each profile also passes reversed
evaluation order and CPU/GPU equality. The diagnostic stock-runtime observer
records a real Metal command buffer and matches the normal output hash. The
inputs are transported unchanged through the test-only wire encoder; all typed
projection and sampling decisions execute in Bend. Evidence is recorded in
`docs/benchmarks/bend-decoration-count-correctness.json`.

The observer initially caught that merely selecting `--gpu on` did not dispatch
this new fixture. The harness now invokes an explicit Bend `rows!` calculation
after host preparation. The earlier lighting-rules harness had the same omission;
it now uses an explicit `batch!` and offers `--prove-metal` to check actual command
buffers. These are component correctness checks, not complete-region benchmarks
or surface-feature/lighting completion claims.

The lighting harness now loads the 19,504 optical words as registry data before
its compute evaluation, rather than embedding them as shader constants. This
avoids the exceptionally slow Metal compilation encountered with the earlier
fixture. All 65,536 transitions pass independent CPU/Metal checks and the
observer confirms actual device execution with identical output. Its observer
reuses the stock GPU archive from the same source, since only host logging
changes. Evidence is in `docs/benchmarks/bend-light-rules-correctness.json`.


### Registered placement-height providers

`placement_height.bend` implements absolute, above-bottom and below-top anchors,
constant/uniform/biased/very-biased/trapezoid height distributions and recursive
weighted lists. `registry_placement_height.bend` reads their original schema from
the resident registry tape. `registry_decoration_heights.bend` retains each
height-range instruction's recipe/modifier address in the ordered program.
Projection never substitutes an average height or clips a provider to sea level.
These libraries are not yet imported by the playable world worker. The typed
program and live-overlay libraries described below now supply the next layer;
ordered walker execution and feature integration remain required.

All sampling uses integer arithmetic and exact 64-bit random words. The returned
random state preserves sequential modifier draws; weighted totals can exceed
2^32. Providers resolve against each actual build interval at invocation, so a
prepared profile can serve different minimum Y/height contexts. Empty/equal
ranges retain their lower endpoint without a random draw. Full signed-i32 uniform
ranges avoid a wrapped modulo-zero span. Relative anchor arithmetic saturates at
signed-i32 bounds, as does the very-biased intermediate upper endpoint for extreme
custom inputs. Ordinary registry ranges retain the current approximation's draw
contract; exact vanilla or Rust seed agreement is not required. Build clipping
belongs to the final placement walker rather than the provider.

Run `scripts/test_bend_decoration_heights.py --profile <profile.json>` with each
loaded registry snapshot (repeat `--profile` for multiple inputs). Add
`--prove-metal` to check actual stock-runtime command buffers, not just a GPU
selection flag. Compilation uses one reduced-priority native compiler. The
observer only adds host timing diagnostics and reuses the normal program's exact
GPU archive.

Independent Python integer/random-state checks passed for 89,600 sample records
per backend across actual vanilla, Terralith and combined profiles plus controls.
They cover eight build intervals, signed-i32 extremes, saturation, nested weighted
lists, totals above 2^32 and chains of one through four sequential samples. CPU
and GPU bytes agree; reversed evaluation order and a one-thread CPU run also
agree. All 53 malformed profiles reject on both backends. The diagnostic observer
records four actual Metal command buffers across the three loaded profiles and
all provider controls, with matching normal-program hashes. These are component
correctness checks, not complete-region benchmarks. Source/input/executable
hashes and results are in
`docs/benchmarks/bend-decoration-height-correctness.json`.

### Typed placement programs and live terrain checks

`registry_placement.bend` projects the original ordered modifier arrays without
collapsing them into a biome density. It joins the existing count/offset and
height-provider projections by recipe/modifier address, parses cuboid providers,
and retains square/layer/count expansion instructions, selectors, rarity/chance,
heightmap/height-range placement, offsets, recursive block predicates, water-depth
filters, relative thresholds, biome filters and environment scans. Full signed
64-bit placement salts group flattened selector branches by their first recipe
address; original recipe and modifier order remain intact. Missing/null placement
programs are inactive, and an explicit empty program remains active.

`registry_placement_policy.bend` retains registered material flags, all six
heightmap occupancy masks, biome recipe memberships, fluid/bedrock IDs and the
optional 3D-decoration-biome setting. Registry-tape projection runs in Bend on
the host; the resulting programs/policies are reusable by selected CPU/GPU
evaluations. `placement_world.bend` provides a persistent
treap of final block overrides over compact resident material runs. Rewrites
replace an existing key, earlier writes affect later material/height/ground
queries, and the original terrain snapshot remains unchanged. The write primitive
checks resident-domain and palette bounds; feature-specific replacement/support
rules still belong to the future feature writers.

`placement_checks.bend` implements terrain-dependent modifiers against that live
view. Recursive predicates retain unknown results for probes outside resident
terrain, with decisive all/any branches handled explicitly. Environment scans
check initial search permission, build bounds and the final blocked target.
Water-depth and relative thresholds use signed 64-bit intermediates. Offset
probes reject unrepresentable coordinates. Surface-biome restrictions use the
finalized column biome; enabled 3D biome queries apply below the existing
12-block surface band and fall back to the column biome when the cave sample is
unavailable. Layer ground queries find empty-to-solid transitions, excluding
registered bedrock.

Run `scripts/test_bend_placement_programs.py --profile <profile.json>` for each
original loaded registry snapshot, with `--prove-metal` to observe actual Metal
commands using the identical normal GPU archive. The independent reference
compares projected instruction order and parameters, full salts/groups,
provider sample values/random states, nested predicates, every policy table and
1,024 live-world queries per profile. Controls include every modifier variant,
inactive/empty programs, nested boolean predicates, all six heightmaps, fluid
depth, layer grounds, surface/cave biome restrictions, repeated overlay writes,
immutable source queries, out-of-domain probes and signed/distant coordinates.

Vanilla, Terralith, their combined profile and controls pass 8,903 modifier
instruction comparisons and 5,120 live-world queries per backend. CPU/Metal
output agrees, as do reversed evaluation order and one-thread CPU controls.
All 44 malformed profiles reject in both runtime configurations. The diagnostic
observer records five actual Metal command buffers across the loaded profiles
and controls, with output matching the normal program and an identical GPU
archive. These command times include fixture construction/serialization and
are correctness evidence rather than generation benchmarks.

This is a component library, not an integrated surface feature generator. The
following placement-walker component adds lazy expansion and shared parent
checks. Feature writers and playable worker integration remain required. No
complete-region speed or memory claim follows from these correctness checks.
Evidence is in `docs/benchmarks/bend-placement-program-correctness.json`.


### Lazy ordered placement cursor

`placement_walk.bend` executes the projected modifier programs against the live
resident `placement_world.bend` view. `new` takes a prepared model, selected
recipe IDs, an anchor position and the full world seed. Invalid IDs and duplicate
IDs are ignored; missing/null programs remain inactive. Roots use signed chunk
coordinates and full 64-bit placement salts. A persistent leftist heap orders
work by group, expansion path, task role and recipe ID, independently of input
order. Each anchor owns its world overlay and persistent parent-result cache.

`next(cursor, model, world, fuel)` returns one candidate, an exhausted cursor or
a paused cursor. Fuel limits task work per call and never discards remaining
candidates. Callers may apply a successful feature's block writes to the live
world before asking for the next candidate. The cursor's wrapping 32-bit task
counter is diagnostic; the per-call fuel is independent of that counter.

Count and cuboid families retain one continuation per active family instead of
allocating every future candidate. Count children inherit independent full-word
random seeds. Cuboid dimensions sample height, width and length, traverse the
inclusive X/Y/Z volume and retain the registered edge/interior rules. Layer
attempts resample their count provider at every loop test, including termination,
and resolve shared selector grounds before either branch can write. Later attempts
observe earlier block edits. Terrain checks before a future expansion cache their
parent result, including rejection; checks after the expansion observe the live
world separately for each child. Final candidate Y bounds use the loaded build
height. Offset probes reject signed coordinate overflow.

Run `scripts/test_bend_placement_walker.py --profile <profile.json>` for original
loaded snapshots; add `--prove-metal` to observe command buffers with the same
GPU archive as the normal program. The independent Python interpreter uses native
wide integers, dictionaries and `heapq`, and compares candidates, full random
states, paths, queue/cache counts, task counts and live height queries. Controls
also assert expected outcomes for live versus parent heights/filters, shared
selector budgets, layer-provider resampling, cuboid faces/edges/interiors, signed
coordinate overflow, final build-height clipping, inactive/empty programs and
invalid/duplicate recipe IDs. The fixture starts with a zero-fuel pause and checks
one-task resumptions against larger work slices. Its 64-candidate and 8,192-task
limits explicitly report prefixes and do not apply to the production cursor.

The 4,096-by-4,096 nested-count control represents 16,777,216 candidates. Producing
its first 64 uses 131 task units, two queued continuations and no parent-cache
entries. This is a bounded-queue correctness result, not a whole-region memory
or generation-speed benchmark. Test setup uses balanced column tables and tail
loops to avoid deep sequential setup stacks in the stock GPU runtime.

Vanilla, Terralith, their combined profile and the two controls compare 818
emitted candidates across 165 anchor cases per backend. Of those cases, 156
exhaust their streams and nine explicitly report a bounded prefix. CPU/Metal
bytes agree, as do reversed input/anchor order, one-task pauses and the
single-thread CPU control. The observer records five actual Metal command buffers
with matching normal-program output and an identical GPU archive. Their times
include fixture setup and queries; they are correctness evidence, not a
placement-stage or generator performance comparison.

This component does not place actual trees/surface features and is not yet
imported by the playable worker. Registered block providers and their noise are
now covered by the following component. Feature writers, ordering across
neighboring anchor contributions and world integration remain required.
Evidence is in `docs/benchmarks/bend-placement-walker-correctness.json`.


### Registered block providers and provider noise

`registry_block_provider.bend` projects the shared export into immutable typed
providers. `block_provider.bend` implements state, weighted, randomized integer,
rule-based, rotated, property-copy, random-block, noise, dual-noise and
noise-threshold selection. It consumes the existing live `placement_world` view
and returns the updated complete 64-bit random state beside an optional material.
`optional` preserves nullable results so matching rules may fall through;
`sample` performs the registered current-substrate fallback. Rotation consumes
its direction draw before the nested source, absent/noninteger properties consume
no value draw, and property copies use the current overlay material. Validation
checks palette/program references, transform coverage, integer-property ranges,
predicate shapes, replacement ordering and nullable weighted children before
sampling. Declared integer rows must cover their provider range even when the
source may read the current substrate.

`registry_provider_noise.bend` retains original f64 frequency bits, initialized
256-entry permutations, offsets and F32 amplitudes from
`decoration_provider_noises`. `provider_noise.bend` evaluates those actual
Minecraft gradient stacks without substituting the seeded hash-based terrain
noise. Integer coordinates multiply rounded Q40 coefficients modulo 256;
scaled dual-noise coordinates first round to F32, then multiply the original
f64 significand using integer limbs. Both paths retain far and signed phases
without native f64 arithmetic. Dual providers select the slow-noise sample after
the registered wrapping coordinate shift; compensated arithmetic preserves the
variety-count integer boundary. No Rust, Java computational work, native codec
or handwritten shader is involved in these library algorithms.

Run `scripts/test_bend_block_providers.py --profile <loaded-profile.json> --prove-metal` for the independent Python rational-phase/F32 interpreter and
CPU/actual-Metal comparisons. Original vanilla, Terralith and combined profiles
are transported unchanged. The fixture traverses every provider in their
original decoration subtree, including nested sources and rules; it does not
substitute representative biome densities or normalized provider recipes.
Together with synthetic rare-provider and empty controls, 1,109 providers make
35,488 state/RNG decisions and 4,864 direct noise queries per backend. The
independent output matches exactly on the captured host, including the direct
F32 noise values; CPU/Metal bytes and the single-thread CPU control agree.
There are 544 explicit draw-count controls, and 29 malformed projections fail
without producing output. Actual Metal command buffers are observed with normal
program output and an identical GPU archive. Signed/distant coordinates, full
64-bit weight sums, nullable fallthrough, live property copying, fixed/random
rotations, threshold draws, scaled dual noise, negative frequencies, subnormal
frequencies and Q40 midpoint phases are exercised. These are component
correctness checks, not a feature-stage or whole-generator speed comparison.
Evidence is in `docs/benchmarks/bend-block-providers-correctness.json`.

The playable worker is unchanged by this cycle. Surface feature writers, trees,
neighboring contribution ordering, structures, snow/freezing, full GPU lighting,
full-region caching and equivalent complete-region benchmarks remain required.


### Registered surface feature writers

`surface_feature.bend` now performs actual registered feature writes against the
persistent `placement_world.World`. Each call returns the updated world, full
64-bit RNG state and feature success result. Later checks and provider samples
see earlier writes immediately. Independent recipes may execute in parallel;
ordered writes inside a recipe retain their dependency on current block state.
The current component does not yet change the playable worker.

`registry_surface_feature.bend` projects original simple-block, block-column, disk,
ordinary plant, aquatic and bamboo recipes, including their recursive block/int providers and predicates. It
also projects the optional palette-indexed `simple_current_states` table; null
survival rows remain unsupported rather than being assumed safe. Unknown feature
kinds fail this parser. The component harness explicitly selects the six
implemented families while traversing the original decorations subtree, including
nested feature recipes; it does not claim to implement the other families.

Simple blocks honor optional provider results, registered survival predicates,
upper-space restrictions and separate dry/wet variants for both halves. Block
columns sample every layer height before checking the *next* position, truncate
from the base or tip according to `prioritize_tip`, and use the live world for
subsequent provider decisions. Their original success semantics and random draws
are retained even when an obstruction removes all retained blocks or the actual
build domain clips writes. Disks retain Z/X/descending-Y replay order, circular
bounds, target predicates and nullable providers. All writers publish only
resident columns and valid build heights, and reject coordinate overflow before
shifting. Final plant partner/support cleanup still belongs to the forthcoming
complete decoration pipeline.

Run locally with unchanged captured registry profiles:

```sh
python3 scripts/test_bend_surface_features.py \
  --profile <vanilla-profile.json> \
  --profile <terralith-profile.json> \
  --profile <combined-profile.json> --prove-metal
```

The independent Python implementation uses integer coordinates, a dictionary of
voxels and its own feature loops. It checks every written position/material,
feature success and RNG state across two successive calls in 16 contexts per
recipe. Fixtures cover soil, water, obstacles, missing/neighboring columns,
build limits, negative/distant coordinates and signed-coordinate boundaries.
Explicit controls cover wet pairs, upper-space rejection, unsupported shared
survival, base/tip truncation, nullable providers, live disk dependencies and
provider draws for writes clipped out of build height.

The original inputs contain 96/237/237 applicable recipes, respectively. With
44 synthetic recipes and an empty control, each backend checks **19,648 writer
calls**. Two-thread CPU, one-thread fixture execution and GPU-required outputs
agree byte for byte. Forty-three malformed recipes/tables reject before output.
The observer uses identical stock GPU pipeline archives and records actual Metal
command-buffer completions alongside matching normal-program output hashes.
Sources, inputs, executables and results are recorded in
`docs/benchmarks/bend-surface-features-correctness.json`.

These are correctness checks of isolated writers on constructed terrain, not a
complete terrain benchmark or evidence of a GPU speedup. The installed world
worker, neighboring anchor scheduling, remaining surface features/trees,
structures, snow/freezing, lighting, full 1024-chunk regions and equivalent
complete Rust/Bend performance measurements remain required.


### Ordered overlay merge and final plant repair

`bend/placement_merge.bend` connects accepted feature overlays to the compact
resident block runs consumed by the existing chunk encoder. `apply` takes an
immutable source grid, a table of anchor patches in global anchor order and
registered material flags, plant floor masks and signed pair IDs. Later patches
replace earlier values, including explicit air. Completion order and treap shape
do not choose the winner. Columns fork independently, prune irrelevant branches
of the coordinate-ordered treaps and edit coalesced runs without allocating a
whole region of expanded voxels.

This merger currently accepts unconditional `FEATURE` writes. Tree/plant roles
with conditional replacement still need their corresponding final replay policy;
passing those plans directly as unconditional patches would be incorrect. The
module is a component of the upcoming coordinator, not a substitute for it.

The final two-pass repair first removes plants whose actual supporting material
fails the registered floor mask, then removes mismatched or missing partners.
A signed pair ID identifies a pair family; its sign chooses the neighboring
block, and its magnitude must match the opposite half. Runs containing checked
plants are scanned individually; other terrain remains proportional to run count.
The same repair covers packs that use checked plants as base materials. Invalid
columns and writes outside resident/build bounds are left untouched or clipped.
Terrain metadata and the existing cave-biome lattice are carried through unchanged.

`crop` extracts resident core columns after all halo contributions and repairs;
nonresident or empty rectangles return error 766. It retains the original cave
lattice for subsequent biome queries. Final serialized heightmaps come from the
merged block runs rather than the preserved original surface-height metadata.

Run the component validation locally:

```sh
python3 scripts/test_bend_placement_merge.py \
  --profile <vanilla-profile.json> \
  --profile <terralith-profile.json> \
  --profile <combined-profile.json> --prove-metal
```

The harness adds constructed anchor patches to copies of the captured profiles;
it leaves their recipes, material flags, floor masks and pair tables unchanged.
Independent Python dense voxels check overlapping anchors, reversed ordering,
empty patches, duplicate/air writes, missing columns, negative/distant/signed-limit
coordinates, floor/build edges, paths, mismatched pair families and core crops.
Original and merged cave/surface biome probes agree. Actual Bend block-column,
ordinary plant, aquatic pair and bamboo writers are included in a larger merge
with synthetic stone states, then saved through Bend NBT, zlib and MCA;
an independent decoder checks every block, quart biome and heightmap, along with
coordinates, sectors and metadata. The explicit merge GPU boundary is observed
using stock Metal command buffers and matching normal/diagnostic pipeline archives.
Serialization in this component harness runs on the Bend CPU path.

Across vanilla, Terralith and combined profiles, each backend checks 147,456
voxels, including three serialized chunks; the small merge fixtures remove 588
unsupported or unmatched plants. Two-thread CPU and Metal outputs match, as does
the one-thread vanilla control. The observer records 30 Metal command buffers.
Evidence and source/input/executable hashes are recorded in
`docs/benchmarks/bend-placement-merge-correctness.json`. These are component
correctness results, not full-region or generator performance measurements.
The installed playable worker is unchanged. The complete coordinator, remaining
surface features/trees, structures, snow/freezing, lighting, full-region caching
and equivalent complete Rust/Bend benchmarks remain required.


### Ordinary plants, aquatic plants and bamboo

`bend/surface_plants.bend` supplies three more feature families through the shared
surface dispatcher and registry projection. Their original weighted state lists,
low/high decoration bands, material IDs and predicates cross the existing registry
transport unchanged. Selection uses Bend's existing full-state RNG and F32
probabilities; it does not promise Rust seed agreement. Eligible weights are
normalized by their maximum before summing, avoiding overflow for large finite
weights. Empty bands consume a selection draw and place nothing. Values outside
finite positive F32 range reject explicitly rather than becoming broken weights.

Ordinary plants require the registered normal/dry soil flag, an air target and,
for paired states, a second air block inside build bounds. Repeated calls see
previous plants and reject occupied space. Aquatic states instead use their
registered survival and upper-space predicates before replacing water or other
allowed targets; both predicates run before either half is written.

Bamboo validates its origin before drawing height/chance, honors the registered
podzol probability and predicates, and retains its three crown states. Its podzol
circle and trunk-obstruction probes see the pre-feature world while writes are
queued in a new overlay. Later features see the completed overlay. This also
preserves behavior for unusual pack materials that change heightmap membership.
Horizontal probes reject signed overflow and unknown columns; publication clips
to the resident build domain.

The expanded surface harness independently checks all six writer families on the
original vanilla, Terralith and combined profiles and constructed terrain. New
controls distinguish dry soils, low/high bands, large/small weights, empty-band
RNG consumption, aquatic pair rejection, nonair aquatic replacement, bamboo
chance-zero RNG, obstruction, complete crowns and snapshot podzol scans. Every
written voxel, success flag and complete RNG state agrees on two-thread CPU and
actual Metal, with a one-thread fixture control. The observer uses the same
compiled GPU archive and records command-buffer completions. The final merge
harness independently decodes the new families' accepted writes through final
block runs, all six heightmaps, NBT, zlib and MCA.

These remain component checks. Ordinary plants are conditionally accepted against
the supplied world; their patches must not bypass final role replacement when
combining independent anchors. The complete neighboring-anchor coordinator,
remaining feature/tree families and playable vegetation wiring are still pending.
The installed world worker is unchanged, and these correctness checks make no
whole-generator performance claim.


### Registered trees, connected foliage and decorators

`bend/registry_tree.bend` prepares the original exported tree recipes in Bend.
The shared surface dispatcher now accepts `tree` beside the six earlier writer
families. It retains height/radius/offset intervals, weighted log-axis and
leaf-distance tables, soil/root states, root offsets and registered trunk-vine,
leaf-vine and cocoa decorators. Shape names select the current Rust
approximation's supported trunk/foliage families; the remaining exported placers
retain its simple trunk or general crown approximation. This is not an exact
vanilla tree-placer interpreter.

`tree_geometry.bend` builds straight/giant, forked/branching, fancy, dark-oak and
mega-jungle trunks and attachment crowns. Branch math uses local F32 offsets;
world positions and full random states remain integer words. Checked additions
prevent probes or writes from wrapping across signed coordinate limits.
`tree_foliage.bend` implements the corresponding layered crown rules, including
dark-oak upper rows and the tapered 2x2 jungle crown. Radius/height distributions
and conditional corner draws advance the same Bend random stream as placement.

`tree.bend` validates the complete trunk against resident terrain, water levels
and earlier feature writes before publishing anything. Six-neighbor breadth-first
connectivity starts at unique trunk cells and emits registered leaf states for
distances 1..6, without persistent-leaf substitution. Soil, logs, leaves, cocoa
and vines retain separate roles in a `Plan`; ordered replay checks their actual
replacement/support rules against the live world. The accepted overlay can then
use the existing compact-run merger. Raw plans from independent anchors must
still pass role replay before they become unconditional merged patches.

`tree_decorators.bend` sorts/deduplicates attachment positions by Y/X/Z and uses
full-word coordinate-derived random streams for vines and cocoa. Cocoa ages use
the registered directional states. Hanging vines require adjacent registered
support or the matching vine above. Unknown columns and signed overflow stop
attachments, and publication clips to the actual resident/build domain.

Large write lists use reverse-and-prepend concatenation. A diagnostic run found
that Base's ordinary `List.append` exhausted the stock Metal evaluator's 2048-word
continuation stack on fancy/large trees. The Bend algorithm now keeps these joins
tail-recursive. Tall-trunk validation also short-circuits with a tail-recursive
walk rather than retaining one conjunction per wood cell. The compiler/runtime
and computational kernels are unmodified.
The correctness harness streams bounded batches of all recipe/context pairs,
rather than retaining every profile's tree results inside the default 2 GiB
GPU heap at once.

Run the checks locally with the original captured profiles:

```sh
python3 scripts/test_bend_trees.py --prove-metal \
  --profile <vanilla-profile.json> \
  --profile <terralith-profile.json> \
  --profile <combined-profile.json>
python3 scripts/test_bend_tree_mca.py --prove-metal \
  --profile <vanilla-profile.json> \
  --profile <terralith-profile.json> \
  --profile <combined-profile.json>
```

The tree harness compares every accepted voxel, success flag and full RNG state
against independent Python replay. It traverses nested recipes in each unchanged
registry export and also exercises constructed shape combinations, large/small
weights, roots, obstruction, snow/soil, water, build limits, missing columns,
negative/distant/signed-limit coordinates and repeat placement. The MCA harness
uses actual registered fancy, dark-oak and mega-jungle writers at a chunk corner,
then checks all four serialized chunks' blocks, properties, biomes, six
heightmaps, zlib framing and MCA sectors with independent decoders. Its files are
explicitly unlit component fixtures; they do not satisfy the required complete
lit-region performance comparison.

The installed playable worker still omits surface decorations. Neighboring-anchor
coordination and integration of these writers into that worker remain required,
along with mushrooms, fallen trees, vegetation patches, attachments, structures,
snow/freezing, lighting, full-region caching and final equivalent Rust/Bend
benchmarks. No full-generator speed improvement is claimed by this cycle.


The tree correctness run checks 17,544 writer calls and 1,563,611 accepted-overlay
voxels per backend across 63 vanilla, 289 Terralith, 289 combined and 90 synthetic
recipes. All CPU/Metal outputs match, including a one-thread CPU control; 36
malformed recipes reject explicitly. The separate connectivity control checks
48,104 leaves, and 64 jungle seeds produce 64 trunk variants. The stock-runtime
observer records 185 actual Metal command buffers. Source/input/executable hashes
and full results are in `docs/benchmarks/bend-trees-correctness.json`.

The MCA run independently checks 3,538,944 final voxels per backend across 36
serialized chunks. Actual tree placement and compact-run merging share a selected
CPU/Metal evaluation; NBT/zlib/MCA serialization remains on Bend CPU. Its observer
records 18 Metal command buffers, with byte-identical normal/observer GPU archives
and identical CPU/Metal MCA files. A real IO boundary separates host registry
preparation from the dispatched computation; an earlier fixture without that
boundary executed on CPU despite its GPU flag, and the observer correctly rejected
that as GPU evidence. Results are in
`docs/benchmarks/bend-tree-mca-correctness.json`.

`bendTreeMcaDecodeTest` reads the generated sparse fixtures through Minecraft's
actual region storage, palette codecs, registered biome IDs/heightmap predicates
and `SerializableChunkData`, opening each file twice and checking that reads do
not change its bytes. Run it after `scripts/test_bend_tree_mca.py`:

```sh
./gradlew :common:bendTreeMcaDecodeTest --max-workers=2 --no-parallel
```

Use `-PbendTreeMcaProfiles=trees-1-1,trees-1-2,trees-1-3` and `-PtestDatapack=<path>`
for the Terralith fixtures, or `trees-2-1,trees-2-2,trees-2-3` plus
`-PtestRegistrySupplement` for the combined capture. This decoder test does not
need a Rust library; `-x buildNative -x packageNative` permits that deliberate
Bend-only test configuration. The host's `cc` launcher currently fails because
Xcode requires license acceptance, so this cycle uses that configuration rather
than changing the host's license or the Rust implementation.

The column metadata prerequisite for surface coordination is now corrected.
`MD.Column.count` is the serialized run count. The merger recomputes it after
ordered writes and plant support/pair repair, preserving invalid columns and
geometric/cave metadata. Its test now also uses the actual
`material_columns.encode` query response, including crops, misses and repeated
hits; the independent reader checks every framed run and consumes the stream to
EOF. Vanilla, Terralith and combined profiles pass 672 queries per backend,
alongside the dense voxel, plant repair, cave-biome, NBT/MCA and heightmap checks.

Legacy flattened plant bands no longer interpret the run count as native flag
bits. They sample compensated world-coordinate simplex noise in Bend, using the
existing native approximation's fixed frequency/seed/threshold. Modern registered
noise-threshold block providers continue to evaluate their own registered noise
programs and parameters. This does not introduce a native shader or a GPU
readback. The surface fixture now has 20 contexts with real low/high noise
locations near the origin and around 30 million blocks, plus the existing signed
extrema, unknown columns and placement restrictions; its column counts contain
only the eight actual source runs.

The expanded six-writer suite passes 24,560 calls per backend and rejects 43
malformed recipes. CPU two-thread, CPU one-thread controls and actual Metal
outputs agree. Observers record 30 merge-suite and 81 surface-suite Metal command
buffers, with byte-identical normal/observer GPU archives. Updated source hashes and
results are in `docs/benchmarks/bend-placement-merge-correctness.json` and
`docs/benchmarks/bend-surface-features-correctness.json`. Compilation used one
compiler at nice +10; runtime checks used two CPU threads. These are component
correctness checks; the installed preview worker is unchanged. The surface
coordinator, remaining feature families, structures, snow/freezing, lighting,
full-region scheduling and equivalent complete-region benchmarks remain required.


All three actual Minecraft decoder configurations pass: 9 MCA files / 36 chunks,
two opens per chunk and 7,077,888 block decodes. The final vanilla MCA bytes match
the files from its earlier successful reader run. Datapack files, tested source
and input-file hashes are captured in
`docs/benchmarks/bend-tree-minecraft-decoding.json`.


## Resident vegetation anchor coordination

`bend/registry_vegetation.bend` prepares a typed placement/feature model directly
from the original registry tape. Top-level recipe indices and placement-salt
sharing are preserved. Unsupported families retain explicit `None` entries; an
actually reached unsupported candidate returns `Unavailable` with its recipe,
position and RNG state. It never aliases that feature to another writer or falls
back to Rust/Java generation.

`bend/vegetation.bend` owns one persistent placement cursor and live overlay per
anchor. Each accepted feature is visible to subsequent heightmap, survival and
placement queries. A work slice pauses the same cursor without truncating its
remaining count/layer tasks. Feature RNG remains separate from forked placement
streams. Anchor recipe membership samples the global four-block surface lattice
and, when requested, the cave-biome volume; results are a sorted union of the
registered recipe IDs.

The coordinator is deliberately a CPU phase. An initial Metal run of the same
Terralith anchor produced matching results but took about 91.86 seconds between
two result writes, versus about 0.25 seconds on CPU. This is one preliminary
result-write interval including host/runtime waiting and transport, with
uncontrolled host load; it does not separate device time or measure a complete
generator. The full-profile GPU run was deliberately stopped. Forcing this
serial live walk onto the GPU would conflict with the measured-work-placement
requirement. See `docs/benchmarks/bend-vegetation-gpu-pilot.json`.

`scripts/test_bend_vegetation.py` independently replays heap tasks and feature
writes. Complete streams of the implemented writers pass against vanilla,
Terralith, combined and synthetic inputs: 2,044 CPU candidate applications across
those cases. GPU controls cover 423 candidate applications through bounded
original-profile prefixes and a complete nine-family fixture, with matching CPU
outputs and 36 observed Metal command buffers. Reverse/duplicate input IDs and
slice sizes of 1, 7 and 64 preserve completed results. The fixture distinguishes
live child heightmaps from shared cached parent heightmaps; a 4096-by-4096 count
stream pauses at the same 64-task prefix under three slice sizes instead of
allocating or truncating its 16,777,216 potential candidates. Unavailable features,
zero fuel, empty input, signed extrema and three-dimensional biome membership are
also checked. Existing Python reference defaults were compared with the parent
commit on all three original profiles before reusing them for the coordinator.

A completed single-anchor overlay passes ordered merge/plant repair, palettes,
all six heightmaps, NBT, Bend zlib and MCA assembly. The independent decoder checks
every serialized voxel. Minecraft's actual sparse-region decoder then opens all
four completed CPU fixtures twice: 4 files, 16 chunks and 3,145,728 block decodes,
with registered block/biome codecs and unchanged MCA bytes. These fixtures are
unlit and do not claim complete-region or cross-anchor generation. Results and
source/input hashes are in the vegetation coordinator, reference compatibility
and Minecraft decoding reports under `docs/benchmarks/`.

The installed preview worker remains unchanged. Before enabling playable
vegetation, implement the remaining feature families and retain write roles
through deterministic neighboring-anchor merging: leaves must not overwrite logs
from another anchor, and plant pairs/support must survive final replay. Halo/core
composition, snow/freezing, structures, GPU lighting, full-region scheduling and
equivalent complete lit-region benchmarks remain required by the full goal.

## Registered huge mushrooms and fallen logs

`forest_data`, `registry_forest`, `huge_mushroom` and `fallen_tree` add typed pure
Bend writers for the loaded `huge_mushroom` and `fallen_tree` recipes. The shared
registry exporter and original profiles are unchanged. Cap-face tables, log-axis
states, block/int providers, support/clearance/replacement predicates and all three
fallen-log decorators come from the registered recipe. No biome-name heuristic or
native generation call supplies those values.

Mushrooms preserve the native approximation's height draws, red/brown cap shape,
individual cap properties and stem order. Providers observe earlier accepted
feature writes while replacement predicates retain the pre-feature snapshot.
Fallen logs preserve the stump when horizontal placement fails, settle within the
registered clearance/support rules, reject unsupported gaps, and replay attached
blocks, trunk vines and shelf mushrooms in the native decorator order. Equal-Y
logs use the stable Java HashSet bucket order used by the Rust approximation.
Coordinate offsets are checked at signed-i32 boundaries, and unknown columns and
out-of-height writes remain clipped to the caller's available world.

The independent Python voxel/RNG replay passes 3,120 calls and 88,084 accepted
patch voxels per backend across unchanged vanilla, Terralith and combined profiles
plus 45 synthetic recipes. CPU two-thread, CPU one-thread control and Metal output
bytes agree. All 35 malformed recipes reject explicitly. Controls exercise face
masks, axis transforms, current-state and weighted providers, zero-length logs,
probabilities, obstruction, retained stumps, repeated writes, missing columns,
world ceilings and signed/distant coordinates. The stock-runtime observer records
35 actual Metal command buffers with an identical normal/observer GPU archive.
See `docs/benchmarks/bend-forest-features-correctness.json`.

These two families also join the resident vegetation coordinator. The coordinator
continues to run on CPU for complete loaded placement streams; Metal correctness
checks use bounded original-profile prefixes and a complete nine-family fixture.
This preserves the measured decision against dispatching a serial live placement
walk wholesale to Metal. The independent forest writers themselves remain usable
in selected-backend bounded parallel batches.

The installed playable worker is unchanged in this library cycle. Attachments,
vegetation patches and nested feature dispatch, role-aware neighboring-anchor
merging, playable vegetation, structures, snow/freezing, GPU lighting and full
1024-chunk region execution/benchmarks remain required by the goal.

The expanded coordinator check passes 2,044 complete CPU candidate applications
and 423 matched GPU-control candidate applications, with 36 observed Metal command
buffers. The complete nine-family fixture executes both forest writers alongside
trees and the six prior surface writers. Its final registered blocks, including
cap properties and fallen-log axes, survive Bend NBT/zlib/MCA serialization.
Minecraft opens/reopens all four fixtures (16 chunks, 3,145,728 block decodes)
without changing their file bytes; vanilla, Terralith and combined codec checks
all pass. The coordinator and Minecraft decoding reports contain current source
and fixture hashes.


## Registered vines and multiface attachments

`attachment_data`, `registry_attachment` and `attachment` implement the loaded
`attachment_growth` family in pure Bend. The original vanilla, Terralith and
combined registry captures pass unchanged. Registered direction order, face masks,
dry/waterlogged initial states and all face-transition tables drive vines, glow
lichen and sculk veins. Support, placed-on, source-water, spread-replaceability,
spread-support and sturdy-face predicates remain loaded data rather than assumed
block classes. A small shared Fisher-Yates helper preserves the native direction
shuffle and RNG stream.

Growth preserves the first-allowed-neighbor rule, existing-face exclusion, live
face accumulation and registered same-position/same-plane/around-corner spread
order. Sculk's extra support/replacement/sturdy restrictions are applied in that
order. A valid spread type whose write is outside the build height skips the
remaining types for that direction, preserving the native boundary behavior.
Coordinate shifts are checked, writes crop to the available resident world and
vines consume no RNG. The native approximation collapses repeated immutable
origin-relative search probes to one evaluation after each shuffle; Bend preserves
that behavior and still validates the loaded search range.

The attachment writer has independent voxel/RNG checks on CPU and observed Metal;
the report records its synthetic bounds, ordering and wet-state controls. The
resident vegetation coordinator remains a CPU phase, as selected by the earlier
serial-walk GPU measurements. `test_bend_vegetation.py --cpu-only` now validates
complete registered streams without compiling or running that coordinator's
large, unused Metal archive. Individual attachment GPU checks remain separate.
The ten-family fixture first places a stone column, then uses the unmodified
registered sculk-vein recipe against that new support; registered face states must
reach the final MCA. CPU-only reports explicitly omit GPU coverage and archive
hashes. Earlier nine-family Metal reports retain their historical tested hashes.

This library cycle does not replace the installed playable worker. Vegetation
patches and their nested placement/selector/composite programs, role-aware global
anchor merging and playable vegetation integration are next. Structures,
snow/freezing, GPU lighting, full 1024-chunk regions and equivalent complete lit
Rust/Bend benchmarks remain required by the objective.

The attachment correctness run passes 2,464 writer calls and 1,234 patch voxels per
backend across four vanilla, sixteen Terralith and sixteen combined recipes, plus
41 fixture recipes (four original and 37 synthetic). Thirty-eight malformed
recipes reject explicitly. CPU one/two-thread and Metal bytes match; the stock
observer records 28 command buffers using an identical GPU archive. Full source,
input and executable hashes are in
`docs/benchmarks/bend-attachment-features-correctness.json`.

The current ten-family coordinator's complete CPU replay and MCA checks are in
`docs/benchmarks/bend-attachment-coordinator-correctness.json`. This CPU-only run
retains the zero-fuel, unavailable-family, 3D membership, live/cached heightmap,
reverse/duplicate ordering, one-thread and 16,777,216-potential-candidate pause
controls. It makes no claim about executing the current coordinator on Metal.

The complete CPU streams contain 2,942 candidate applications across unchanged
vanilla, Terralith, combined and ten-family fixture inputs. All final MCA voxels,
registered properties, palettes and six heightmaps match independent replay.
Minecraft's actual reader passes four files / sixteen chunks, opening each twice
and checking 3,145,728 block decodes without changing the saved bytes. All three
registry configurations pass; hashes and logs are recorded in
`docs/benchmarks/bend-attachment-minecraft-decoding.json`. These are still unlit
single-anchor component fixtures, rather than full-region or gameplay evidence.


## Registered vegetation patches and nested feature programs

`vegetation_patch`, `placement_set_order`, `nested_feature`,
`registry_nested_placement` and `registry_nested_feature` add the remaining
registered surface feature families. The surface dispatcher and resident CPU
coordinator now accept all 17 exported families, including patches, sequence and
overlay composites, random/simple/boolean/weighted selectors. Their typed
registry preparation consumes the original vanilla, Terralith and combined
profiles; no fixture normalization substitutes different registered recipes.

Nested placement deliberately shares its feature RNG and live overlay. Counts,
count-on-every-layer, cuboids, offsets, height providers, scans and predicates do
not borrow the top-level planner's fork/cache semantics. Layer counts are sampled
on every loop condition; singleton simple selectors consume no draw while a
weighted singleton still consumes its full 64-bit draw. Sequence failure retains
prior writes and stops; overlays continue after failed children. Child heightmaps
see earlier child writes, while the outer placement cursor retains its shared
parent cache.

Patches sample X/Z radii independently, scan floor or ceiling support, evaluate
live block providers and preserve nullable/same-block cursor behavior and partial
fills. A stable merge sort reproduces the native approximation's Java HashSet
bucket order without scanning every bucket for every position. Water pools
filter all enclosure predicates before flooding, reorder the retained surface
with its new set capacity, and only apply registered wet-state replacements after
successful vegetation placement. Default omitted offset components remain zero;
loaded integer-provider bounds match the native nested placement subset.

Reusable callback functions receive their model/context as explicit data. Copying
a callback that captured that context caused a stock Bend reference-count
fail-stop during selector-list parsing. Noncapturing function references now
preserve the generic writer/parser architecture without changing generated
runtime code. The actual crashing selector case and unchanged full profiles pass
with the ordinary compiler/runtime.

Verification uses two CPU threads, one compiler at a time and reduced priority.
The independent writer replay passes **3,232 calls and 2,122,639 voxel
comparisons**, across the three original profiles and 69 recursively collected
fixture features. Sixteen coordinate/build-height contexts and repeated writes
cover signed limits, dense radius/depth maxima, RNG order, dynamic layers,
nullable providers, incomplete fills, enclosure and waterlogging. **50 malformed
recipes** reject before output publication. One-thread and two-thread original
vanilla results match. See
`docs/benchmarks/bend-nested-features-correctness.json`.

The complete 17-family coordinator independently passes **2,986 candidate
applications** and final MCA/heightmap checks. It retains reverse/duplicate recipe
ordering, one-thread execution, zero fuel, unavailable future families, 3D biome
membership, live/cached heights and the bounded 64-task pause of a
16,777,216-potential-candidate stream. See
`docs/benchmarks/bend-nested-coordinator-correctness.json`. This remains the CPU
phase selected by the earlier serial-walk measurements; no current Metal
execution or new throughput gain is claimed for this cycle.

Minecraft's actual region reader opens and reopens all four completed CPU MCA
fixtures: **4 files, 16 chunks and 3,145,728 block decodes**, including the complete
17-family fixture and unchanged vanilla, Terralith and combined registry inputs.
Registered block properties, biome palettes, all six heightmaps and unchanged MCA
bytes pass. These are unlit, four-chunk, single-anchor component fixtures; they
do not claim full-region gameplay or cross-anchor acceptance. See
`docs/benchmarks/bend-nested-minecraft-decoding.json`.

Reproduce the CPU checks locally:

```sh
nice -n 10 python3 scripts/test_bend_nested_features.py \
  --profile build/bend/profiles/profile-0.json \
  --profile build/bend/profiles/profile-1.json \
  --profile build/bend/profiles/profile-2.json
nice -n 10 python3 scripts/test_bend_vegetation.py --cpu-only \
  --profile build/bend/profiles/profile-0.json \
  --profile build/bend/profiles/profile-1.json \
  --profile build/bend/profiles/profile-2.json \
  --report docs/benchmarks/bend-nested-coordinator-correctness.json
```

The installed preview worker remains unchanged. Role-aware global anchor merging
and playable vegetation composition are next; structures, snow/freezing, GPU
lighting before the final write, full 1024-chunk regions, DH full-region caching
and equivalent complete lit Rust/Bend benchmarks remain required. Single-anchor
component tests do not prove those requirements.


## Ordered neighboring-anchor command replay

`placement_world` now keeps a persistent reverse journal beside the live material
overlay. The journal records every accepted write, including explicit air and a
generic feature writing the same material already used by a tree. A final block
map or a material-to-role lookup cannot retain those distinctions. Capturing an
anchor reverses its journal once; writers append in constant time and earlier
writes remain visible through the existing sparse overlay.

`placement_replay` sorts anchors by signed X/Z/Y coordinates before replaying
their chronological command streams. Worker completion order cannot select the
winner of a collision. Duplicate anchor origins and malformed role/material/side
metadata fail explicitly. Replay preserves soil, trunk, leaf, cocoa, vine, plant
and unconditional feature rules. Leaves cannot erase another anchor's trunk;
logs may replace leaves; transient air can expose terrain before a conditional
write. Tree attachment checks see the merged halo. Ordinary double plants remain
one atomic command, while aquatic writes retain their unconditional feature
semantics. Final floor and plant-pair repair runs before compact columns,
heightmaps, NBT, Bend zlib and MCA assembly. The global replay discards its own
short-lived journal entries instead of retaining a second full command stream.

The vegetation coordinator's completed anchors now use this replay path before
serialization. This is a CPU composition stage, consistent with the earlier
irregular-walk measurements. The installed preview worker is still unchanged:
playable integration must supply coherent halo terrain and the complete anchor
set before cropping. Full regions, structures, snow/freezing, GPU lighting,
DH full-region scheduling and complete equivalent Rust/Bend benchmarks remain
required. No new GPU or generator-throughput claim follows from these component
checks.

The independent CPU fixture checks **129 cases and 14,155,776 voxels** across
vanilla, Terralith and combined profiles. Each profile compares all 24 completion
permutations of four colliding anchors, signed anchor-coordinate order, transient
air, same-material role changes, soil/trunk/leaf restrictions, attachment support,
paired plant obstruction and final floor/pair repair. **21 malformed command or
duplicate-anchor cases** reject. A direct journal probe invokes the actual tree
and plant write paths, checks all seven roles, and verifies that rejected writes
are absent and paired plants remain single commands. One- and two-thread vanilla
outputs, including MCA bytes, match. See
`docs/benchmarks/bend-anchor-replay-correctness.json`.

The complete 17-family coordinator passes **2,986 candidate applications** through
capture, role replay and final MCA checks in
`docs/benchmarks/bend-anchor-coordinator-correctness.json`. The 3,232-call nested
writer regression retains 2,122,639 voxel comparisons and 50 malformed-input
rejections with the journal-bearing world; its refreshed source hashes are in
`docs/benchmarks/bend-nested-features-correctness.json`. Six affected fixture
entry points additionally compile/type-check: trees, forest features,
attachments, surface features, placement merge and placement walker. These checks
run serial compiler processes at reduced priority and do not launch Minecraft UI
or GitHub Actions.

Reproduce the new CPU replay check with the pinned compiler:

```sh
nice -n 10 python3 scripts/test_bend_anchor_replay.py \
  --profile build/bend/profiles/profile-0.json \
  --profile build/bend/profiles/profile-1.json \
  --profile build/bend/profiles/profile-2.json
```

Minecraft's actual reader opens and reopens **7 MCA files, 28 chunks and
3,932,160 block decodes**, combining the neighboring-anchor fixtures with the
real 17-family coordinator under vanilla, Terralith and combined registries.
Registered block-state/biome decoding, six heightmaps and byte preservation pass.
These files are unlit four-chunk components, not the required complete lit-region
acceptance workload. See `docs/benchmarks/bend-anchor-minecraft-decoding.json`.


## Playable vegetation composition

The persistent worker now accepts opcode **53**, after ores and cave dressing,
and before the single final MCA write. It projects the unchanged loaded decoration
recipes, placement programs, integer/block providers and survival policy in Bend.
The playable Java bridge removes only the still-unimplemented structure field;
it no longer removes decorations. No Rust or Java feature generation is called.
Typed vegetation projection currently repeats for each batch; retaining it across
batches remains an optimization opportunity.

`vegetation_region` independently plans all core chunk anchors plus one neighboring
anchor ring against the same pre-decoration snapshot. Explicit fork/join exposes
independent anchors to the two CPU workers. A second terrain ring supplies complete
feature footprints for the neighboring anchors. The playable 64x64 core therefore
uses a 130x130 source, including the existing one-block exposure margin. Density
samples additionally retain the previous coastal/ore stencil. These are actual
terrain fields, not a border filled with guessed air. Plans resume bounded cursor
slices until complete rather than truncating candidates. Their accepted journals
then replay in canonical signed-coordinate order, run registered plant/pair repair
and crop to the core while preserving its cave-biome volume.

This coordinator deliberately has no GPU bang. Earlier measurements showed the
serial live-placement stream is unsuitable for the stock GPU scheduler. Bulk
terrain/noise/interpolation/caves/ores retain their selected-backend execution;
vegetation is an explicit CPU stage in the gameplay log. This is not a claim that
all Bend logic automatically runs efficiently on both devices.

The independent partition fixture checks all output blocks and final MCA palettes
and six heightmaps for unchanged vanilla, Terralith and combined recipes. Separate
adjacent 16x16 cores, with different terrain windows, equal a combined 32x16 core at
distant signed coordinates. Nine cases cover **90 anchors and 1,179,648 voxels**;
all three profile partitions pass. See
`docs/benchmarks/bend-vegetation-regions-correctness.json`.

The actual persistent worker stage sequence also passes on CPU and the ordinary
Metal worker. An analytic forest density/climate control preserves all registered
vegetation/palette data and isolates observable placement: **9 anchors, 1,024
candidates and 252 successful applications**, with 451 tree/plant voxels reaching
the final one-chunk MCA. Wrong-seed and missing-halo requests reject without
replacing the earlier snapshot; repeated serialization preserves bytes. This
controlled geometry is separate from the original-registry partition test and
actual vanilla Minecraft world integration. See
`docs/benchmarks/bend-vegetation-worker-cpu.json` and
`docs/benchmarks/bend-vegetation-worker-gpu.json`. The host-only diagnostic logger
observes **21 Metal command buffers** across terrain stages and **zero** for all
vegetation calls. Its generated host code differs by exactly one logging line,
uses the unchanged GPU archive and produces byte-identical final MCA output.

Minecraft also opens and reopens **11 MCA files, 14 chunks and 2,752,512 block
decodes** with the actual vanilla, Terralith and combined registries. Block-state
properties, biome palettes, all six heightmaps and file bytes pass. See
`docs/benchmarks/bend-playable-vegetation-minecraft.json`.

The actual vanilla Minecraft world harness passes the new combined 4x4 pipeline,
height-query/block agreement, sparse promotion, neighboring batch merge and saved
edit preservation with the Rust library deliberately unavailable. This is headless
Minecraft decoding/integration; no client UI is launched. It is not a complete
lit-region throughput measurement. The larger vegetation terrain halo currently
adds appreciable work, especially in ores, carving and base-material generation.
Full-region execution must amortize this border rather than treating the current
16-chunk batching as the final implementation.

The pinned Bend 2.0.36 source/runtime is unchanged. Apple Clang 21 actually crashed
while compiling this larger generated worker (`live register clobbered by inserted
prologue instructions`). The normal worker and diagnostic host logger build with
installed LLVM **23.1.2**, at the same `-O3`, with the explicit Xcode SDK and linker.
The existing `RETINA_BEND_CC` override selects that compiler; no generated C,
application algorithm or GPU kernel is rewritten. Use one compiler at nice +10:

```sh
RETINA_BEND_CC=/opt/homebrew/opt/llvm/bin/clang \
  nice -n 10 python3 scripts/test_bend_engine.py
RETINA_BEND_CC=/opt/homebrew/opt/llvm/bin/clang \
  nice -n 10 python3 scripts/test_bend_vegetation_regions.py \
  --profile build/bend/profiles/profile-0.json \
  --profile build/bend/profiles/profile-1.json \
  --profile build/bend/profiles/profile-2.json
nice -n 10 python3 scripts/test_bend_vegetation_worker.py \
  --profile build/bend/profiles/profile-0.json --gpu
```

Both local host-loader builds/checks pass, with identical Bend worker/archive
bytes verified in the Fabric and NeoForge jars. The actual content-addressed
packaged worker starts twice and supports cancellation during startup. Its first
Metal start took 60.90 s; cache reuse took 72.88 ms. These are startup observations,
not generator benchmarks. The installed worker and warmed extracted path make
plain `./gradlew runClient` available without launch flags after this cycle merges.
See `docs/benchmarks/bend-playable-vegetation-runtime.json`.

Structures, their entities/metadata, snow/freezing, computed GPU lighting before
the one final write, full 1024-chunk region generation/cache/timings and equivalent
complete lit Rust/Bend benchmarks remain required. The selectable vegetation
preview is progress toward that end state, not a completed backend.


## Final registered snow and freezing

The playable worker now runs opcode **54** after CPU vegetation composition and
before the single final MCA write. `registry_snow` reads the existing effective
registry tape: sea level, water/ice/lava and snow-layer palette IDs, biome base
temperatures, registered snow/freezing-feature flags, precipitation, motion-blocking
height masks and snow-support predicates. No Java block generation or block-name
heuristic is added. Typed projection currently repeats per command; caching it
with the other prepared world models remains a performance opportunity.

`snow` processes independent compact columns on the selected CPU/GPU backend.
It shares the existing altitude/temperature noise predicate, excludes active
surface-lake geometry, freezes exposed default water just below sea level and
places snow only above the highest eligible motion-blocking surface. Loaded
support rules keep ordinary frozen-ocean ice clear and allow canopy snow where
registered. Covered/unsupported surfaces do not expose lower soil. Build-height
limits, invalid columns, geometric metadata and cave-biome volumes are preserved.
Run counts and all serialized heightmaps reflect the final blocks. Reapplying
weather is idempotent. Future structure writes must precede this final pass.

The original vanilla, Terralith and combined profiles pass **306 CPU/GPU cases
and 28,950,528 independent voxel comparisons**. Controlled columns cover every
loaded material and biome row, exposed/covered/support surfaces, sea water,
signed/distant positions, upper/lower build limits and invalid columns. A resident
basin fixture exercises the actual production lake lookup and exclusion. CPU and
GPU output bytes match; 18,216 snow layers and 1,344 frozen-water writes exercise
both operations. **11 malformed profiles** reject before output publication;
nonfinite temperatures reject in the shared registry transport. See
`docs/benchmarks/bend-snow-correctness.json`.

The actual persistent CPU and normal Metal workers combine terrain, caves,
aquifers, ores, cave decoration, vegetation, weather and one final MCA write.
Analytic geometry and a cold-forest temperature control isolate observable
weather: 245 snow layers on eligible land/canopy surfaces, 256 exposed frozen-ocean
water blocks and no snow on that ice. Warm forest receives no weather writes.
Malformed body/support inputs preserve the prior snapshot; repeat application
preserves output. These controls retain loaded recipe/material/support tables
and are separate from the unmodified-profile component checks. See
`docs/benchmarks/bend-snow-worker-cpu.json` and
`docs/benchmarks/bend-snow-worker-gpu.json`.

A host-only diagnostic logger observes one actual Metal command buffer for each
successful weather application, including its repeated idempotence check. The
logger differs from normal compiler-generated host code by exactly one line,
uses the unchanged GPU archive and produces identical MCA output. No custom
computational C/Metal kernel or runtime modification is shipped.

Minecraft's actual readers open and reopen **306 MCA files and 306 chunks**,
validating **57,901,056 block decodes**, registered properties/biomes and all six
heightmaps across vanilla, Terralith and combined registries. The combined
headless world lifecycle also passes on both CPU and GPU, including column/block
agreement, temporary-cache promotion, neighboring batch merge and saved edit
preservation with Rust deliberately unavailable. See
`docs/benchmarks/bend-snow-minecraft.json`. No game UI is launched.

Using a built pinned worker, reproduce the independent checks locally:

```sh
RETINA_BEND_CC=/opt/homebrew/opt/llvm/bin/clang \
  nice -n 10 python3 scripts/test_bend_snow.py \
  --profile build/bend/profiles/profile-0.json \
  --profile build/bend/profiles/profile-1.json \
  --profile build/bend/profiles/profile-2.json
nice -n 10 python3 scripts/test_bend_snow_worker.py \
  --profile build/bend/profiles/profile-0.json --gpu
```

The normal worker and fixtures still use stock Bend 2.0.36 and the existing LLVM
23.1.2 compiler override at -O3 with the explicit Xcode SDK/linker. Compilation
remains serial at nice +10; Cargo/Gradle retain two workers. These correctness
runs and stage observations are not equivalent complete lit-region benchmarks.
Structures/entities/metadata, computed GPU lighting, full-region execution and
DH caching/timings, final performance work and the complete Rust/Bend comparison
remain required. Preview chunks remain unlit feature-status records for Minecraft
until Bend's actual lighting field is integrated and verified.

Both local host-loader builds/checks pass, and each jar contains byte-identical
normal worker/archive resources. The updated installed worker is warmed at its
content-addressed extraction path. Packaged Metal startup took 75.07 s initially
and 74.09 ms on reuse; startup cancellation passes. These startup observations
do not measure world-generation throughput. Plain `./gradlew runClient` remains
the Fabric-only development command after this cycle merges. See
`docs/benchmarks/bend-snow-runtime.json`.


## Full-region execution and bounded MCA output

The playable Bend path now generates a full 512x512-block core (1,024 chunks)
for each globally aligned MCA coordinate. Chunk, surface and height requests
share that cached region. Temporary-cache keys, promotion, and the last-20
latency window use the same region boundaries, including negative coordinates.
The existing cache capacity now means 1,024 complete regions for both backends.
Status-only Minecraft probes still avoid terrain generation.

Command 55 replaces the playable writer's retained whole-region record list.
It forks eight independent chunk encoding/compression tasks and consumes each
group through stock file IO before constructing the next group. Bend constructs
all NBT, zlib, MCA record framing, sector locations and timestamps. The stock
2.0.36 file API lacks positional writing, so the Java bridge installs an opaque
8KiB header returned by Bend at offset zero in the private file. It does not
truncate or rewrite the chunk bodies. There is one generated MCA representation;
computed lighting remains pending and no separate lit copy is introduced.

The original full-region request exceeded the old density-lattice capacity. The
bounded maximum is now 2,097,152 vertices, checked without overflowing products.
Core geometry accepts the existing 32-block neighboring-feature context. A full
GPU ore pass exhausted the stock 2GiB runtime heap. Large cores now execute the
same ordered calculation on Bend CPU workers; the shared GPU-worker heap still
needed the stock 4GiB span. Full vanilla, Terralith and combined-pack pipelines pass with this
configuration. These are measured resource limits, not device preflight checks.
The two-worker CPU fallback also passes full-region world integration.

Independent container fixtures cover all 1,024 slots, sparse/multisector, empty
and last-slot files, six invalid records and four lattice-bound cases. They
match the existing planner byte for byte. Full original-profile terrain tests
parse every palette/biome index in every chunk and compare 884,736 sampled block
values plus nine independent compressed chunk commands to the resident snapshot.
Malformed requests, wrong seeds and out-of-range regions fail before opening an
output file. Sampled worker RSS and incremental body-file growth are recorded;
worker RSS does not include all GPU memory. The initial 64-block runs and first
full CPU run retain their historical source/binary hashes; final full GPU runs
and both Minecraft world tests exercise the delivered worker.

Minecraft's actual readers open and reopen the full CPU vanilla and full GPU
vanilla/Terralith/combined files, validating 805,306,368 block decodes. CPU and GPU world
lifecycle tests run with Rust unavailable: overlapping first requests coalesce;
far corners share one cache entry; promotion copies all 1,024 chunks; repeat
promotion preserves them; an unfinished probe is repaired while 1,023 completed
chunks and a saved edit remain intact. Height queries agree with loaded blocks.
F3 reports one completed region and the actual 1,024-chunk count. Raw-worker tests
also cover concurrent output requests, queued cancellation, real file-open
failure, cleanup and nontruncating opaque-header installation. No game UI runs.

Both host-loader builds pass with byte-identical normal worker/archive resources.
The packaged Metal worker starts in 70.67 seconds at its new extraction path and
69.50 milliseconds on reuse; startup cancellation also passes. The production
worker contains no diagnostic logger. Compilation remains serial at nice +10,
with two Cargo/Gradle workers and two runtime CPU workers. Existing
`./gradlew runClient` still selects Fabric and packages the installed worker.

Evidence: `docs/benchmarks/bend-region-stream*.json`,
`docs/benchmarks/bend-full-region*.json`. These functional runs shared the host
with other work, and their output is unlit. They do not establish equivalent
lit-region throughput or Rust performance parity. Structures, entities/loot and
locate metadata, computed GPU lighting, detailed Bend stage distributions and
the complete lit Rust/Bend performance comparison remain required.


A separate host-only logger observes actual Metal dispatches for density,
surface climate, shoreline selection, lakes, block columns, cave fields, aquifer
fields, carving, cave dressing and weather across a complete 1,024-chunk region.
Large ores, irregular vegetation and streamed serialization execute on CPU as
intended. The observer differs from compiler-generated host code by one timing
print after the stock command-buffer wait, and its GPU archive is byte-identical
to production. Its final MCA matches the normal worker byte for byte. See
`docs/benchmarks/bend-full-region-metal-observed.json`. All three original
vanilla/Terralith/combined profiles complete with the delivered 4GiB worker.
The verified normal worker/archive pair is installed for plain `runClient`;
the prior installation is retained with local artifacts.


### Structure candidates and jigsaw layout

The structure planning library now consumes the shared registry export in pure
Bend. It implements random-spread candidates, uniform/triangular spread, all four
frequency methods, recursive exclusion zones, weighted biome rejection and
reach-based region halos. Seeds use paired-U32 arithmetic throughout. Negative
coordinates use floor division, and the Java 48-bit structure RNG preserves
rejection draws, signed nextLong halves and Fisher–Yates ordering. Frequency
comparisons retain the source F64 probability; legacy reciprocal selection
includes its rounding before integer conversion. A reciprocal above the signed
nextInt range uses unbiased 32-bit rejection sampling; copying the native
31-bit loop for that exceptional bound would never terminate. Normal pool and
frequency streams retain their existing draw order.

The immutable planning view retains template and processor references into the
original owned registry tape. Large palettes, block lists, tags, processors and
entities are not copied into the layout graph. The original document must remain
alive for later block replay. Schema validation checks the consumed geometry,
references, direction ranges, positive weights and aggregate weight limits; it
does not claim to validate opaque NBT/entity/processor payloads for placement.

Jigsaw layouts support weighted root and fallback pools, empty terminators,
rotated connectors, aligned/rollable attachment, stable selection/placement
priorities, nested collision contexts, internal expansion space, start-jigsaw
names and direct/random/group pool aliases. Terrain matching takes a supplied
height tile; it does not compute CPU noise. Standalone templates use the same
rotation and projection contract. Height tiles and per-definition biome samples
will come from the Bend terrain backend during integration. The caller currently
must supply the appropriate height tile for projected structures and no tile for
underground starts, and a biome sample for each definition during selection.

Two local fixtures run on the pinned stock Bend 2.0.36 CPU runtime with two
threads, one compiler at a time, at reduced priority. No Minecraft UI or GitHub
Actions runs. The layout fixture tests all 30 vanilla and 58 Terralith/combined
definitions against an independent Python reference derived from the native
planning behavior. It uses flat, sloped and uneven tiles at zero, negative and
near-world-border coordinates, plus full-width seeds. All 438 layouts and 8,954
pieces agree, including their final RNG states, positions, rotations, boxes,
palette selections, collision contexts, depths and priorities. Original template
and pool payloads are preserved, not simplified to make layouts pass.

The candidate fixture exercises the original three profiles, synthetic frequency
boundaries, true exclusion cycles, unknown exclusions, weighted biome rejection,
region halos, rejection-sampling bounds and malformed schemas. Independent
reference calculations check every returned candidate and region-start list.
Reports include executable, source and input hashes:
`docs/benchmarks/bend-structure-plans.json` and
`docs/benchmarks/bend-structure-candidates.json`. Recorded driver durations include
registry loading and fixture output; they are not generator performance results.

Reproduce with the complete original registry captures in
`build/bend/profiles/profile-{0,1,2}.json` (vanilla, Terralith, combined), or pass
`--profiles DIRECTORY` to each command:

```sh
RETINA_BEND_CC=/opt/homebrew/opt/llvm/bin/clang python3 scripts/test_bend_structures.py --build
RETINA_BEND_CC=/opt/homebrew/opt/llvm/bin/clang python3 scripts/test_bend_structure_candidates.py --build
```

This cycle is a planning component, not completed structure generation. The
playable bridge still omits structures until ordered template/processor block
replay, terrain adaptation, entity/block-entity/loot records, structure metadata
and locate integration are implemented and checked. No installed worker or Rust
behavior is changed. Computed GPU lighting and complete lit-region Rust/Bend
benchmarks also remain required by the full goal.


### Registered template block replay, processors and typed metadata

The next pure Bend structure component now applies a rotated template to the
resident `placement_world` overlay. `structure_template.bend` projects the chosen
template/palette from the original registry tape, rotates block coordinates,
uses the exported rotated state IDs, substitutes jigsaw final states and skips
structure markers. Invalid dimensions, palette references, coordinates, tag
indices and jigsaw output IDs reject the template before it produces writes.
This is selected-template validation, not validation of every unused registry
entry or arbitrary processor extension.

`structure_processors.bend` implements the native approximation's ordered lists,
block rot, protected blocks, first-matching replacement rules and nested capped
processors. It supports block, block-state and tag predicates, random
probabilities, Manhattan/axis distance predicates, rotated outputs, replacement
block-entity defaults, clear/append modifiers and loot-table assignments. Unknown
processor types remain no-ops and unknown predicates never match, following the
existing Rust approximation. Static probabilities compare the original registry
F64 with the exact Java 24-bit random draw; no F32 conversion changes those
thresholds. Linear distance/chance interpolation uses F32, so it approximates the
native F64 interpolation near numerical boundaries. Tests cover both prefixed
and unprefixed processor identifiers.

Capped processors count actual material/tag changes or rejection. Identical
metadata at a different registry address does not spend a quota; an empty append
creates a compound when no tag existed. Numeric signed-zero equality follows the
native JSON comparison while the NBT writer preserves the stored sign bit.
`structure_blocks.bend` visits the entire template for capped rules, including
outside blocks with the same stone lookup used by the native approximation,
then crops accepted writes to the supplied resident 16x16 chunk. Terrain-matching
pieces use the supplied height tile. Callers must supply that tile from GPU
terrain data, and supply each chunk's bounds when replaying a region. The block
primitive does not synthesize terrain noise.

Every accepted write produces an ordered block-entity update, including removals
when later blocks overwrite earlier tags. Surviving tags receive world x/y/z,
keepPacked and deterministic loot seeds. `structure_nbt.bend` writes the typed
registry NBT directly, including exact longs/doubles, rounded float values,
modified UTF-8, arrays, nested lists and compounds. Reserved internal dictionary
IDs represent generated metadata names absent from the loaded dictionary; they
are emitted as ordinary NBT strings, never numeric IDs. This permits loot
processors even when the original profile contains no LootTable field.

Local validation used stock Bend 2.0.36, one compiler at a time at nice +10, and
two runtime CPU workers with GPU execution disabled. These are ordered CPU
components, with no handwritten native algorithm and no Rust/Java generator
calls. The untouched vanilla, Terralith and combined profile exports are inputs;
additional fixture queries reference their original materials, templates and
processor bodies. The recorded checks are:

- 120,984 processor placements, comparing every keep/reject result, material,
  complete metadata value, final RNG state and capped counter with independent
  Python. This includes all 25 distinct vanilla and 31 distinct Terralith/combined
  registered processor programs, plus targeted synthetic forms and thresholds.
- 849 template replays producing 247,447 accepted block writes and 1,258
  block-entity writes. Every write and metadata update matches an independent
  mutable-world reference. Cases include all four rotations, alternate palettes,
  negative/distant coordinates, chunk cropping, terrain projection, protection,
  capped rules, overwrites/removals and generated metadata keys. All resulting
  nonempty block-entity tags also pass independent binary NBT decoding.
- 7,030 independently decoded typed-NBT roots, including every original template
  block/entity tag from the three profiles, processor outputs, all tag kinds,
  signed-zero/64-bit rounding controls and Unicode limits. Fifteen malformed NBT
  payloads and fourteen malformed template cases are rejected.

Reports with source/executable/input/output hashes are
`docs/benchmarks/bend-structure-processors.json`,
`docs/benchmarks/bend-structure-blocks.json` and
`docs/benchmarks/bend-structure-nbt.json`. Their wall durations include fixture
setup/transport and are not complete-generator performance measurements.
Reproduce sequentially with:

```sh
RETINA_BEND_CC=/opt/homebrew/opt/llvm/bin/clang python3 scripts/test_bend_structure_processors.py --build
RETINA_BEND_CC=/opt/homebrew/opt/llvm/bin/clang python3 scripts/test_bend_structure_nbt.py --build
RETINA_BEND_CC=/opt/homebrew/opt/llvm/bin/clang python3 scripts/test_bend_structure_blocks.py --build
```

The installed playable worker and Rust backend are unchanged in this cycle.
Whole-start ordering/caching, terrain supports, entity transforms and UUIDs,
structure starts/references/locate, final plant cleanup and wiring these writes
into the playable region stream remain required. The primitive retains original
template entities but does not place them yet. No live Minecraft/Gradle build
was run for this library-only cycle; actual structure-bearing MCA loading and
save/reopen checks must run when the region pipeline is connected. GPU lighting,
backend stage distribution and equivalent complete lit-region benchmarks also
remain part of the full goal.


### Structure entity placement, foundations and chunk records

The next component adds pure Bend entity placement and rigid-piece foundations,
and connects generated structure metadata to the existing chunk serializer.
This remains a library component: the installed preview worker still omits
structures. The whole-start/region coordinator must connect these operations
before they become visible in newly generated worlds.

`structure_entities.bend` rotates and translates the original template entities,
preserves their typed NBT, generates stable UUIDs, and updates hanging-entity
anchors, frame/painting facing and yaw. Chunk ownership uses the exported
normalized blockPos when present, otherwise the floor of the final position.
`float64.bend` implements the needed binary64 addition/subtraction and integer
conversion over paired U32 words. This preserves sub-block entity positions near
the world border instead of rounding positions through F32. Nonfinite final
positions are omitted. This is a small arithmetic component, not a general
replacement for every floating-point operation in the generator.

`structure_supports.bend` extends eligible rigid template floors down by at most
12 blocks, stopping at solid terrain and cropping writes to the resident chunk.
It uses the source terrain column's subsurface material, with registered biome
filler fallback and the badlands filler exception. Air, fluids and replaceable
vegetation can be filled; logs/leaves/plants and jigsaw markers are not used as
foundation floor blocks. The preparation pass reads only biome filler/flags and
material flags. It does not project each biome's large feature definitions.
Support writes and their block-entity removals feed `structure_blocks.place_after`
before normal template replay.

`structure_metadata.bend` carries each start's seed, height tile and ordered
pieces together. It produces owner-chunk start records and intersecting-chunk
references, including signed packed chunk coordinates and the registered
retina:template child records. Ordered block-entity updates use last-write-wins
replacement/removal and signed x/y/z ordering. The named fields are encoded with
the resident registry string dictionary, then passed to
`generated_chunk.encode_metadata` and the normal chunk serializer. Bounds checks
and upstream NBT failures propagate through the existing encoder result.

The future coordinator must derive foundation eligibility from each definition's
terrain_adaptation, retain canonical start/piece/part order, supply the original
GPU-derived height tile, and reverse each block replay's update journal before
applying metadata updates. It must cache selected templates/programs rather than
reprojecting them per block, replay the final plant-support cleanup, and integrate
structure queries and Minecraft loading. These callers are not implemented by
this component. No installed worker, Java integration or Rust code changed.

Validation ran locally with stock Bend 2.0.36, one compiler at a time at nice +10,
and two CPU runtime workers with GPU disabled:

- 45,444 binary64 operand pairs: 90,888 arithmetic results and 181,776 integer
  conversions agree with independent host calculations, including subnormals,
  ties, cancellation, signed zero, infinities and distant coordinates.
- 20,024 entity placement cases emit 24,048 independently decoded and compared
  entities. Inputs include every entity-bearing template in the original
  vanilla, Terralith and combined profiles, all rotations, chunk cropping,
  distant positions, attachment faces, malformed wrappers and Unicode.
- 7,420 foundation/template cases produce 169,733 verified writes across every
  loaded biome in the three profiles, including water/lava, badlands filler,
  disabled foundations, terrain matching, boundaries and original templates.
- 4,948 actual generated chunk NBT encodes verify 503 start records and 9,008
  references, plus entity/block-entity ordering, removals and metadata failures.
  Existing palette, heightmap and trailing chunk fields remain readable.
- Regressions pass for 849 template block replays, 7,030 typed-NBT roots, and
  the original chunk fixture's 20 palettes, two complete chunks, six heightmaps
  and 27 invalid inputs.

The source/executable/input/output hashes and fixture results are in
`docs/benchmarks/bend-float64.json`, `bend-structure-entities.json`,
`bend-structure-supports.json`, `bend-structure-metadata.json` and
`bend-structure-chunk-regression.json` in that directory. Fixture timings include
setup and transport; they do not measure complete generation performance.
Reproduce sequentially using the original profile captures:

```sh
RETINA_BEND_CC=/opt/homebrew/opt/llvm/bin/clang python3 scripts/test_bend_float64.py --build
RETINA_BEND_CC=/opt/homebrew/opt/llvm/bin/clang python3 scripts/test_bend_structure_entities.py --build
RETINA_BEND_CC=/opt/homebrew/opt/llvm/bin/clang python3 scripts/test_bend_structure_supports.py --build
RETINA_BEND_CC=/opt/homebrew/opt/llvm/bin/clang python3 scripts/test_bend_structure_metadata.py --build
```

No game UI, Gradle build or live structure-bearing MCA loading was run in this
cycle. Whole-region structure integration, locate/save/reopen checks, GPU
lighting and equivalent complete lit-region benchmarks remain required.
