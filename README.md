# Retina

Fabric 26.3 terrain generation with GPU registry density programs, biome climates, surface materials, caves, registry-derived features and Rust chunk/MCA assembly.

## Run it

Install JDK 25, Rust/Cargo (Rust 1.87 or newer), and a platform C compiler
(Clang, GCC or MSVC) for the vendored libdeflate compressor. Gradle selects Java 25
and builds and packages the native library automatically; no separate libdeflate
runtime installation is needed.

```sh
./gradlew build
./gradlew gpuTest regionTest biomeTest geologyTest featureTest previewTest structureTest shoreTest
./gradlew runClient -PretinaQa
```

Create a new single-player world and select a Retina world type in the **World** tab:

- **Retina GPU Simplex (MCA)**: the default Retina mode; Rust writes whole regions.
- **Retina GPU Simplex (Chunks)**: the original per-chunk buffer/conversion mode.

Both generate the same biome terrain model in the overworld. Nether and End remain vanilla.
The default biome set includes plains, forests, taiga, snowy plains, desert, savanna,
badlands, jungle, mangrove swamp, windswept hills, jagged peaks, three oceans,
sandy/snowy beaches and stony shores. New presets select these biomes using the
registered Overworld climate intervals. Enabled packs can replace those intervals,
material rules and shoreline sediment recipes; the GPU evaluates them in both modes.
See [shoreline generation](docs/SHORELINES.md) for the projection and its limits.
Surfaces include grass/dirt, podzol, sand, red sand/striped terracotta, mud, snow and stone.
Underground, GPU-selected lush caves, dripstone caves and deep dark contain moss,
cave plants, hanging vines, spore blossoms, stalactites/stalagmites and sculk.
Curved ravines and water/lava lakes share the native region pipeline. Lava lakes
use one quarter of the original radius/diameter; water lake size is unchanged.
New presets use `biome_scale: 128`, halving the default horizontal climate
wavelengths while retaining datapack noise/spacing ratios. Existing saves without
an imported registry source retain their original Voronoi selection.
Oceans fill to the registry sea level, with ice in cold biomes; bedrock and deepslate
form the lower layers. Grass, ferns, flowers, tall plants, bushes and biome-specific
wood/leaves are assembled in Rust from vanilla's registered decoration recipes.
Counts, rarity, selectors, offsets, heightmaps and soil/air filters retain their
registered placement order. A live canopy overlay rejects covered planting sites
instead of treating each attempted tree as a successful tree. See
[decoration placement](docs/DECORATION_PLACEMENT.md) for supported rules and limits.
Registered noise-based counts and arbitrary threshold counts are evaluated in
[sparse GPU batches](docs/GPU_FEATURE_COUNTS.md), while branching geometry and
ordered canopy/survival checks remain in Rust.
[Registered block features](docs/REGISTERED_BLOCK_FEATURES.md) add bamboo, cactus,
sugar cane, kelp, seagrass and lily pads with loaded heights, providers and support
predicates.
[Ore preparation batching](docs/GPU_ORE_BATCHING.md) removes per-vein allocations;
F3 measures the ore mask device pass separately from complete planning wall time.
Creative mode with commands enabled is useful for testing. Press **F3**, then fly
into unexplored terrain or use `/tp @s 4096 160 4096` to trigger generation.

Development files live in `run/`. The optional `-PretinaQa` logs structured
checkpoints and validates decorated Minecraft heightmaps and paired tall plants. Use a fresh world for the boundary checks.
A plain `./gradlew runClient` omits QA checkpoints.

For a fresh dedicated world, use `level-type=retina:gpu` for MCA, or
`level-type=retina:gpu_chunk` for per-chunk generation. Launch using
`./gradlew runServer`; Minecraft's EULA must be accepted separately.
`./gradlew genSources` generates Minecraft sources for IDE navigation.

## Rust structures

Java exports the effective structure sets, biome tags, jigsaw pools, processors and
NBT templates once when the world binds. Datapack and mod overrides use the same
registries and template manager. Rust selects random-spread candidates, applies
registered frequency/exclusion rules, chooses weighted pieces, rotates blocks and
connectors, resolves aliases, expands the graph and rejects collisions. Starts are
shared between concurrent callers; per-region chunk placement and compression run
in parallel. The GPU supplies terrain heights and underground biomes; this branching
work runs on the CPU. Rust is not automatically faster than Java; no comparative
Java/Rust structure benchmark has been performed.

The generic jigsaw path covers villages, pillager outposts, abandoned camps, ancient
cities, trial chambers, trail ruins and compatible custom jigsaw structures. Bastion
pools are supported and tested by the native assembler; the normal Retina preset
still leaves the Nether and End on their vanilla generators. Desert pyramids, jungle
temples and swamp huts use recipes captured once from their vanilla procedural
pieces, then rotated and placed in Rust. Temple traps/loot and hut mobs are retained.

Both MCA and individual chunk modes save starts, references, block entities, loot,
spawner/vault data and template entities. A small `retina:template` piece records
bounds for Minecraft's normal structure queries and saves. DH previews contain the
same records and promotion copies them without running Java structure generation.
Minecraft owns lighting, entity activation and loot resolution. Existing chunks are
preserved; test new structures in fresh terrain.

This is an approximation, not a complete port of every vanilla structure algorithm.
Terrain matching and short floor supports approximate vanilla terrain adaptation;
supports extend each column's GPU-selected underlying soil, rather than repeating
grass, paths or the structure's floor block down the sides of a platform.
village street expansion reserves space for attached houses, and WORLD_SURFACE
projection includes water rather than placing buildings on the seabed. Liquid handling
and some processors can differ. Pool feature
elements and non-jigsaw assemblers such as strongholds, mineshafts, fortresses,
mansions, monuments, end cities, shipwrecks and ruined portals are currently omitted
and logged. Unsupported custom element/processor types are reported at export.

`./gradlew structureTest` loads the real 26.3 registries and templates, compares
candidate/frequency decisions against Minecraft, assembles multipart structures and
temples concurrently, decodes saved starts, checks loot/entities/spawners, and compares
MCA blocks and metadata with the chunk path across negative region boundaries.
It also verifies complete houses on hilly terrain and water-surface placement.
Only supported random-spread sets are advertised to Minecraft, avoiding unused
vanilla stronghold-ring biome searches during world startup.

## MCA mode

A region is 32 x 32 chunks (512 x 512 columns). The first requested chunk in a
region coordinates generation through Minecraft's serialized chunk I/O queue.
Other concurrent reads wait behind this job, then load the same published file.
Height/base-column and in-memory surface requests use temporary region batches
(described below), and do not publish terrain to the world save.

Rust performs the following work:

1. Read the real MCA header and preserve all existing chunk records.
2. Send one 64-byte region descriptor to the GPU. Registry programs stay resident.
   A density prepass samples the registered solid field on a global 3D lattice
   using imported cell sizes (vanilla: 4 x 8 x 4). A climate pass samples the
   climate lattice. A surface pass interpolates density before finding each
   column's surface zero crossing, including a four-block slope halo. Shared lake
   probe densities are sampled in parallel and cached once per lake cell.
   The column pass reads those GPU caches,
   interpolates climate values,
   selects biome intervals and evaluates surface material predicates, soil depth,
   lake basins and terracotta offsets. Legacy profiles retain warped Voronoi sites.
   Intermediate data stays on the GPU. Vegetation threshold noise shares the column pass.
   A one-chunk halo produces 544 x 544 compact column records (3.39 MiB).
   Further GPU stages reuse the cached registered density and sample 3D cave fields on a global four-block lattice,
   select underground quart biomes, interpolate those fields, apply each 3D
   biome's cave/canyon settings and pack a final
   one-bit mask. The 384-block-high mask, including a one-block exposure halo,
   adds 12.09 MiB of readback per region. A 36 KiB surface bitset covers the
   decoration halo to reject unsupported vegetation. Two bytes per 4x4x4 biome
   sample add 3.39 MiB for the complete halo, in the same readback (about 18.90 MiB
   total including columns). Density fields and ravine distance fields stay on the GPU.
3. Plan ore veins and vegetation in parallel with global seeded anchors.
   Expand terrain, consume the GPU cavity mask, place ore replacements, dress
   cave floors/ceilings and settle the sparse surface decoration overlay. Neighboring anchors can place trees and patches across chunk/region borders.
   Assemble Minecraft 26.3 block and biome palettes, heightmaps,
   chunk metadata and zlib-compressed NBT across a persistent pool of up to 16 cores.
4. Write a sibling temporary file, synchronize it, then atomically replace the
   destination MCA file in the world's `region/` folder.

Before Rust touches the file, the mod closes Minecraft's cached region handle,
including cached misses. Publication occurs on that storage's I/O queue, serialized
with Minecraft reads and writes. Existing chunks and external `.mcc` records are
preserved; missing slots are filled. Pending Minecraft saves subsequently write
through the normal queue. A complete region is left byte-for-byte unchanged.
The region is remembered for the rest of that world session.

The files contain `minecraft:features` protochunks. Terrain is already assembled;
Minecraft reads its normal disk format and finishes lighting, spawning and chunk
activation. There is no dense Rust-to-Java block buffer or Java block-by-block
conversion for these new region chunks. Minecraft still pays disk, NBT decoding,
lighting and rendering costs. Existing unfinished protochunks can finish through
the temporary MCA adapter. Actual MCA/GPU/load errors are surfaced; a failed
read does not silently generate new terrain over an unreadable region.

MCA accepts the Retina GPU biome source or a legacy fixed biome source, with
generation bounds matching the actual dimension. Default overworld bounds are -64 through 319. Whole-region
production generates chunks outside the current view and consumes disk space
accordingly (about 5 MiB per region in the decorated biome test).

## Temporary MCA previews / Distant Horizons

In MCA mode, generator biome/height/base-column queries and `buildTerrain` calls
on in-memory protochunks share a per-world temporary region cache. This includes
Distant Horizons' surface generation. The first request for a region invokes the
same Rust GPU/decorations/MCA writer, in an OS temporary directory. Concurrent
requests share that job. Terrain is decoded directly into Minecraft section
palettes; there is no per-chunk GPU dispatch or dense block-buffer conversion.
Biome and terrain futures finish synchronously on the calling generation thread,
as required by DH's surface sampling.

Biome searches such as `/locate biome` use separate GPU query caches. They do not
assemble temporary MCA files: the old behavior could run a large region spiral
on the server thread and block loading/promotion for minutes.

The cache retains up to **1,024 regions on disk**, using LRU eviction. Each file
contains a full 32 x 32 region. Undecorated GPU columns are returned by that same
native generation call and stored in a compressed sidecar for later height and
height queries. Quart biome queries decode the cached MCA's biome palettes,
without generating individual GPU chunks. Only 16 regions' column arrays and file handles stay warm in memory
(about 48 MiB of column arrays); cold data reloads from disk without GPU work.
Active reads/generation pin their entries until complete, so limits may be
briefly exceeded by concurrent jobs. Files and sidecars are removed on eviction
and normal world close. Cache roots are isolated per world/profile/seed session.

A real Minecraft chunk read still uses the world's serialized I/O queue. If a
complete preview is cached, Rust copies its compressed chunk records into missing
save slots without GPU dispatch, terrain assembly or NBT recompression. This
handles absent destinations, zero-length MCA placeholders left by DH, and partial
regions. Existing chunk records and player edits stay intact. The combined file
is published atomically after syncing it to disk; saved edits do not alter the
temporary snapshot. DH previews themselves do not write saved terrain; DH may
create empty region placeholders and retain its own LOD data independently.
Per-chunk world mode retains its original pipeline.

## Per-chunk mode

Concurrent Minecraft terrain jobs call Rust through Java 25's Foreign Function
and Memory API. A persistent `wgpu` worker batches pending GPU requests and reuses
its device, compute pipelines and buffers. Decorated chunk jobs request a 3 x 3
chunk terrain tile (one descriptor: 27 KiB columns, about 15.5 KiB cave/surface
bits and 27 KiB quart biomes). Height/base-column queries retain single-chunk
batching. Quart biome queries reuse the shared native mask or sample the same
3x3 tile on a cold cache. Calling threads assemble dense
material-index buffers in Rust. Java converts those 16-bit indices into section palettes,
stores the GPU biome IDs, and primes heightmaps.

The 16-bit element layout is `((y - min_y) * 16 + z) * 16 + x`, with `0` for air and `1` for
the registered default stone. Remaining material IDs refer to the world profile’s
block states. Synchronous native calls return before their borrowed buffers are released.

Both modes use GPU column records for surface biome, height and base-column
queries. Underground queries use GPU quart IDs. Rust caches up to 8,192 column
chunks and 2,048 shared cave-mask chunk entries; Java caches up to 2,048 height
tiles and 2,048 small quart biome arrays.
The cache includes seed, world profile, coordinates, bounds and terrain settings. There is no CPU noise implementation or CPU fallback.
The generator codec exposes `mode` (`mca`, the default, or `chunk`), `min_y`,
`height`, `base_height`, `amplitude`, `frequency` and `biome_source`.
Different GPU backends may produce different terrain for the same seed.

## Datapacks, biome climate and GPU programs

Retina preserves the selected MCA or per-chunk generator when an enabled pack
replaces the Overworld dimension generator. It imports that generator's biome
source, noise settings and dimension bounds. Both are retained in the saved
codec, so reopening keeps Retina and its selected mode. Minecraft's final merged
registries include mods, their built-in packs and enabled datapacks. Normal pack
priority still applies when multiple packs replace the same registry key.

At binding, Java compiles registered density expressions into resident GPU
instruction tables: constants, noise with registered octave/amplitude parameters
and coordinate shifts, gradients, arithmetic, unary operations, thresholds,
interval selection, interpolation and nested Hermite splines. The GPU evaluates
actual temperature, humidity, continentalness, erosion, weirdness and depth
expressions. Imported multi-noise sources retain their full parameter intervals,
including offsets and cave depths; they are not reduced to one average per biome.
These fields and intervals control biome spacing, distribution and river placement.
Custom biomes without climate placement receive an approximate niche based on
registered temperature/rainfall; missing placement data cannot be recovered.
Nether/End-tagged additions stay outside the Overworld pool.

Registered noise keeps its octave modifiers, normalization mode and two sample
stacks with Minecraft's frequency ratio. The exported layer weights are checked
against Minecraft's actual noise-stack metadata; GPU hashes still differ.

Minecraft uses trilinear interpolation inside 3D density cells, and cubic Hermite
splines for some terrain-shaping expressions. Retina already evaluates the exported
Hermite splines on the GPU. When the registry supplies `find_top_surface`, a GPU
prepass caches the actual final solid density on a global lattice. A surface pass
interpolates density in X/Z at each Y level, then locates its interpolated zero
crossing in Y. It no longer blends already-extracted corner heights; that loses
the density gradients which shape slopes. The same cached field feeds caves.
Extracted per-block surfaces and shared lake-bank probes also stay on the GPU,
so surface rules reuse slope samples without repeating the vertical density scan.
See [GPU density interpolation validation](docs/GPU_DENSITY_INTERPOLATION.md)
for correctness checks and measured GPU phase costs.

Java imports `cell_size_xz` and `cell_size_y` from the dominant registered
`interpolated` expression; vanilla uses 4 x 8 x 4. In
[26.3](https://feedback.minecraft.net/hc/en-us/articles/48394701938573-Minecraft-Java-Edition-26-3-Snapshot-10),
these sizes belong to density expressions rather than the noise settings object.
Density nodes align to global coordinates even when a cell size does not divide
16, and distant lake-bank probes evaluate the same GPU nodes instead of clamping
to a request boundary. The lattice stays resident for the dispatch, with no extra
CPU/GPU transfer. Climate fields still use a four-block bilinear lattice, with
global GPU sampling for shared probes outside the cached request.
Vanilla's conservative preliminary surface probe
is intended for aquifers; treating it as the terrain ceiling depressed land and
produced excessive ocean. Explicit datapack height expressions retain the separate
four-block height lattice and bilinear interpolation.
The statistical GPU test compares sea coverage to vanilla's actual final density
at sea level rather than its preliminary probe.
The registered final-density expression supplies the cavity field on the 3D
lattice. Java exports material-rule programs specialized by biome; the GPU samples
registered material noises and evaluates thresholds, height/water/stone-depth
conditions, slope, temperature and ordered rule sequences. It chooses actual
16-bit top/filler block IDs. Registered surface and clay-band noises also drive
soil depth and terracotta offsets. Rust receives twelve bytes per column:
height, a 16-bit biome ID plus depth/flags/band offset, and two 16-bit material IDs.
No intermediate spline, climate or density field crosses back to Rust.

Block and biome palettes support 65,536 materials and 65,535 biomes. Minecraft
still reads its normal named NBT palettes. A density/material program supports
up to 1,024 DAG nodes. Profiles without an imported climate source keep Retina's
coherent warped Voronoi assignment; legacy fixed-biome saves keep their original
stone/air model. Registry profiles use GPU height programs in both generation modes.

This remains an approximation: GPU noise differs from Minecraft's CPU sampler,
legacy blended noise is projected onto GPU noise, and nested interpolation wrappers
are collapsed onto a shared final-density lattice at the first registered cell
size (4 x 8 x 4 if none is present). Different nested resolutions and operations
outside those wrappers are therefore approximated. Unsupported structure kinds
and arbitrary mod-defined feature code are not reproduced. [Local GPU aquifers](docs/GPU_AQUIFERS.md) approximate the loaded
fluid/pressure settings with resident fields and compact material-run output. Unsupported
custom density primitives use their registered range midpoint and are identified
in the export log; unsupported material/feature kinds are also logged. Ore-vein
material rules are approximated by Rust's registered ore recipes. Registered material rules
now evaluate full vertical runs, including floor/ceiling depths and fluid context;
see [GPU material layers](docs/GPU_MATERIAL_LAYERS.md). Retina stays
on its GPU/Rust path. Existing saved chunks are preserved; these changes affect
new terrain and temporary DH regions, with no retroactive terrain blending.

The generator codec includes optional registry `settings`; the biome-source codec
includes optional `registry_source`. `biome_scale` controls horizontal climate wavelengths as well as Voronoi spacing;
`blend` controls legacy Voronoi blending. Imported multi-noise intervals and
registered noise ratios remain authoritative. Existing saves retain their stored
scale; the denser default applies to new presets.
Different GPU backends may produce different results for the same seed.

## Registry-derived surface decorations

The same startup export traverses each selected biome's `VEGETAL_DECORATION`
placed features and their feature selectors. It sends Rust the actual registered
block-state providers, selector probabilities, placement counts/rarities, patch
spreads, trunk dimensions, foliage dimensions, root/soil materials and supported
tree decorator probabilities/states. Block tags
provide vegetation substrates and heightmap predicates. This is a single profile
upload, with no Java calls for individual decorations.

Rust places grass/ferns, flowers, double-height plants, dry bushes, berry bushes,
and oak, birch, spruce, acacia, jungle and mangrove variants. Giant jungle trees
use vanilla-style branch heights/angles and separate smaller crowns on lateral
branches. Jungle, blob, bush and fancy foliage use their own shape rules, including
correct distance to 2 x 2 trunks; fancy oaks have distributed branching crowns.
Jungles combine giant trees, smaller jungle trees, branched oaks and low bushes
using the registered selector weights. These are procedural Rust shapes, rather
than a fixed collection of tree templates. Other tree placers remain
approximations parameterized by vanilla's recipes. Global anchor seeds and an
outer terrain ring keep both assembly modes independent of request order and
preserve crowns across boundaries. Trees settle before plants; double plants are
placed atomically as a pair. Natural leaves carry log distances and can decay
when logs are removed. All six stored heightmaps are calculated from the final
blocks using Minecraft's exported predicates. Base-height/base-column APIs still
return terrain before carvers, ores and decorations. Registered trunk vines, hanging leaf vines
and cocoa decorators are replayed with exported attachment directions, cocoa
ages and probabilities. Cocoa checks for jungle-log support; leaves retain their
natural decay behavior. Tree geometry/decorators add no terrain readback;
registered spatial count modifiers use compact, separate GPU queries.

Biomes with the registered snow feature receive snow layers after vegetation.
The GPU determines cold columns from the registered biome temperature and the
sea-relative snow line. Rust places snow over the final ground or canopy instead
of replacing the material-rule ground with solid snow blocks.

Registered count providers and selectors preserve their budgets and placement
order. Noise-based and threshold counts use the game's exported permutation on
the GPU. Rust builds the geometry and tracks accepted blocks in the live overlay.
Common aquatic vegetation, bamboo, cactus, sugar cane, fallen trees and giant mushrooms now use
their registered feature recipes. Giant mushrooms retain loaded cap/stem providers,
face states, radius and terrain-clearance rules; see [registered mushrooms](docs/REGISTERED_MUSHROOMS.md).
Fallen trees retain configured lengths, sideways log states, mushroom attachments,
stump vines and shelf mushrooms; see [registered fallen trees](docs/REGISTERED_FALLEN_TREES.md).
New registry profiles supply [complete GPU base materials](docs/GPU_DECORATION_SUBSTRATE.md)
across the placement halo, so live predicates and heightmap modifiers see carved
air, cave floors/ceilings and local fluids. Existing profile behavior is retained.
Vegetation patches, spatial block
providers, additional tree decorators and unsupported placement rules still need
adapters; export logs identify omissions. Existing regions and edits are preserved.
Use fresh terrain after restarting to inspect new decorations.

## Registry-derived ores and GPU caves

Java traverses the actual selected biome features and carvers once per world.
Ore recipes retain their registered vein size, count range, rarity, uniform or
trapezoid height distribution, stone/deepslate replacement rules and air-exposure
suppression. Nested block/tag/height predicates become compact replacement tables
with height bands, including 26.3's height-specific host blocks. Biome membership
preserves extra badlands gold, mountain emeralds/infested stone and the
underground biome's own replacement veins. Registered
stone, dirt, gravel and tuff replacement veins are included. The default 16 surface
biomes plus three underground biomes export 30 ore recipes and more than 200
block states alongside their vegetation data.

Rust builds ellipsoidal/scattered veins from global chunk anchors. Contained
ellipsoids are pruned and visited voxels use a small bitset. The region's halo
includes neighboring anchors so veins cross chunk and region edges. Planning,
mask consumption, ore placement, palettes, heightmaps and compression share the
persistent region pool of up to 16 cores. Independent Minecraft chunk requests
also assemble concurrently after the persistent GPU worker returns their masks.
Assembly order is base terrain, caves, ores, cave decorations, then surface vegetation.

The [ore planning investigation](docs/ORE_PLANNING_PERFORMANCE.md) includes a
focused real-field benchmark, rejected micro-optimizations and the constraints on
skipping whole veins or moving their expansion to the GPU.

GPU cave fields evaluate the registered final-density program. Legacy profiles use
registered cave cheese, spaghetti, layer, roughness and pillar noise octaves/amplitudes. Additional compute stages produce chambers, tunnels
and finite curved ravines using the biome's registered carver probability, center-height
range/distribution, thickness, axis ranges and radius parameters. Ravines use a
rounded 3D cross-section with tapered ends, vertical bends, and procedural wall
roughness controlled by the registered width smoothness. Their roofs follow
their sampled center heights and radii, rather than extending to every terrain
column's surface. Interpolation and final cavity decisions
happen on the GPU. Rust reads a bit-packed mask and replaces carved blocks with
air, lava below 26.3's global lava boundary, or water below sea level in ocean
biomes. Bedrock stays protected. Within 24 blocks of the surface, ordinary cave
fields gradually narrow except inside sparse coherent entrance domains. The
deeper chamber and tunnel decisions remain unchanged; mouths still break through
the ground and hillsides. Entrance strengths are cached per column on the GPU.
The GPU also emits one surface bit per halo column, so Rust skips tree and plant
anchors over openings without reading back the halo's full cave volume.

When heights come from registered final density, a GPU-only exterior pass separates
the sky-connected negative interval from underground cavities. Surface extraction
and cave chambers share the density lattice; exterior air must still not strip
the selected topsoil. Biome carvers can still open caves and ravines through the
surface. The scratch limits and entrance fields add no readback bytes and their time is included in
F3's GPU cave-mask stage.

These shapes approximate vanilla carvers/noise caves; they do not replay vanilla
random walks. Local aquifer pressure uses the game's equations with GPU center
jitter and interpolated registered barrier noise. Unsupported registry feature
rules are logged rather than guessed. Both MCA and per-chunk generation use the
same geology; temporary DH MCAs contain it and promotion copies it unchanged.
For layered registry profiles, height/base-column APIs use GPU material runs
before decorations and structures, including caves and local fluids. Legacy
diagnostic profiles retain their earlier column behavior. Native cavity masks
are shared across requests in a bounded 2,048 chunk-entry cache; the larger temporary MCA cache remains on disk.

Run `./gradlew geologyTest` for real-GPU parallel ore/cave and exposure checks,
including sparse entrances, identical deeper cave networks under different roofs,
bounded ravine heights, tapered cross-sections, and registry height/roughness changes.
`biomeTest` checks all 1,024 MCA chunks against independent chunk assembly and
Minecraft's six heightmap predicates. Use a fresh world or unexplored regions
for new geology; existing saved chunk records are preserved.

## Cave biomes, ravines, lakes and badlands

The world profile includes the registered lush-caves, dripstone-caves and deep-dark
biomes as underground-only entries. They participate in `possibleBiomes`, Minecraft
quart biome queries, F3 biome names and persisted section biome palettes, while
surface selection excludes them. Imported profiles evaluate the actual six climate
expressions and full target intervals, including depth, on the GPU. Legacy profiles
retain approximate 3D climate/depth mapping. The first
cave pass writes packed quart IDs; the second uses those IDs to select the correct
registered carver tables. No extra compute pass or CPU noise sampling is required.
Ore anchors also use these underground biome IDs.

Java walks the selected cave feature graphs and block-state providers once. Rust
uses their moss/clay/plant/vine materials, dripstone height/density parameters,
up/down taper states and sculk material. Actual replacement tags become palette
bit tables after all ore materials are exported. This prevents cave dressing from
replacing ores that vanilla excludes. Floors and ceilings are supported by solid
blocks; vertical features use globally seeded column anchors and run independently
in each chunk's assembly task. Dripstone is an approximation of vanilla clusters;
large pillars, sculk spreading/entities, Warden spawning and ancient cities are
not implemented.

Ravines use globally anchored, finite, bent centerlines sampled once in the 2D
part of the density lattice. Their distance field stays on the GPU; GPU voxel
classification applies the registered canyon width/height/probability parameters,
including openings through the surface. Chunk/region dispatch boundaries do not
change their shape.

The existing column pass shapes rounded, noise-warped water/lava basins, gives
each lake a fixed level sampled from its banks and forms a smoothed rim. Registered
surface lava-lake placement rarity and fluid/barrier providers drive lava lakes.
Modern vanilla does not register general water-lake features, so Retina no
longer creates water basins from rainfall. Explicit registered heightmap lake
features retain their rarity, fluid and water/lava barrier materials. Lakes are
above sea level in low-relief land biomes.
GPU cavity classification protects the top four blocks beneath lake beds.
Lake depth/fluid flags fit in the twelve-byte column record, so no extra
lake buffer crosses the GPU or Java bridge. Full vanilla lake placement checks,
height-range-only underground LakeFeatures remain unprojected. Local aquifer
fluid levels and pressure barriers are implemented on the GPU; see
[GPU aquifers](docs/GPU_AQUIFERS.md) for approximations and measurements.

Badlands use the 26.3 MaterialSystem's 192-layer recipe and seven registered
terracotta colors, with a world-seeded table built once during registry export.
The registered clay-band noise frequency/amplitude drives spatial offsets on the
GPU. Badlands reuse the filler byte for the signed offset; Rust and the cached
Java base-column reader only index the resident table. Red-sand caps, colored
walls and surrounding terrain remain continuous across generation boundaries.

`./gradlew featureTest` checks all seven colors, GPU band offsets, 16-way cave
biome/decorations requests, flat lake levels across chunk borders, solid lake
floors after carving, cached MCA biome decoding and base-column parity. The full
`biomeTest` additionally compares all 1,024 stored chunks, every quart biome and
all six heightmaps against independent native chunk generation. `previewTest`
checks temporary MCA generation, cold disk reloads, promotion, edits and LRU limits.
These changes apply to new terrain in both modes and temporary DH regions.

## F3 metrics

MCA timing and speed use the **last 20 newly generated regions**, including temporary
DH preview batches. The window starts with available samples and remains visible
while idle. Promotion, disk cache hits and already saved regions do not enter it.

- **Average region**: mean generation/publication latency over that window, with
  the current sample count. Temporary-region latency also includes storing its
  GPU column cache.
- **Chunks/sec / Amortized chunk**: total newly produced slots divided by total
  window latency, and its reciprocal in milliseconds. With full 1,024-chunk
  regions this is `1024 * 1000 / averageRegionMs` and `averageRegionMs / 1024`.
  Partial regions use their actual chunk count. This is latency-derived native
  production capacity; overlapping regions, Minecraft loading, lighting and
  rendering are separate work.
- **Timing stages**: one colored line per stage, expressed as a percentage of the
  same average region time. GPU host, Rust and device timings have separate
  headers; larger percentages change from green to yellow, orange and red.
  CPU worker estimates are marked `~`, and device timing overlap is explicit.
- **Temporary regions / Hits / Promoted**: preview batches produced, preview
  requests reused, and copies published by real Minecraft chunk loading.
- **Regions / In flight / Terrain chunks / Failed**: publication and job counters.

Per-chunk mode retains its five-second production rate and last-128-job mean
request latency, including worker queue delay, plus native/conversion accounting.
These are terrain production metrics rather than fully lit/rendered throughput.
Dedicated servers send stats to modded clients once per second.

## Tests and packaging

`./gradlew gpuTest` runs the real GPU shader, seed variation, negative coordinates,
vertical bounds, 256 chunks across 16 Rust callers, 128 concurrent Java/Rust requests
and native error propagation.

`./gradlew regionTest` writes a real 1024-chunk MCA region with Rust and reads every
chunk with Minecraft's region reader, NBT decoder, block palette codec and packed
heightmap storage. It checks all blocks against GPU heights. It also verifies
complete-region reuse, negative region coordinates, missing-slot refill, player
block/custom-data preservation, and that corrupt headers report an error without
being overwritten. Both integration commands require a compute device.

`./gradlew biomeTest` reads real vanilla registries, exercises 128 concurrent GPU
biome chunks, checks reachable biomes/materials and distant-coordinate smoothing,
and decodes all 1,024 chunks in a biome MCA. It compares every block, biome sample
and heightmap against the per-chunk native output and Minecraft predicates, and
checks negative chunk/region boundaries. It also validates registry-derived trees,
flowers, natural leaf properties and both halves of tall plants, and checks all
six stored heightmaps including the no-leaves map.

`./gradlew previewTest` performs actual GPU/MCA generation through DH-style
protochunk calls. It checks synchronous completion, exact decorated blocks,
base-column semantics, parallel region sharing, cold sidecar reload, LRU file
removal, promotion into absent/empty/partial regions, saved edits, mutable-section isolation, native failures,
and world-close cleanup while region generation is still running.

`./gradlew geologyTest` validates 64 parallel chunks against actual registry ore
families, cave air and lava, biome-specific ore lists, GPU parameter changes,
canyon-only settings, flooded ocean caves, surface entrances and ore exposure
suppression. Rust unit tests also cover ore height distributions and unique vein
voxels within the neighboring chunk halo.

`./gradlew datapackTest -PtestDatapack=/absolute/path/Terralith.zip` loads
real pack registries with Minecraft, validates dimension-selection retention and
codec reopening in both modes, samples actual GPU biomes, promotes a temporary
MCA, and compares decoded blocks/quart biomes with Rust. `-PtestSupplement=...`
adds a second higher-priority pack. The QA fixtures cover noise/material/density
controls and more than 255 merged biomes.

For an isolated dedicated-server load check, use `-PretinaQa -PretinaPromotionQa`
and `-PretinaDatapackQa=/path/to/fresh/server-directory`. The opt-in promotion
check creates a distant temporary region, loads a chunk through Minecraft's
actual chunk I/O path, verifies promotion and compares all 98,304 block states.
Do not enable this opt-in check on an existing test world whose terrain was edited.

The current ore/cave/decoration tests passed on Metal / Apple M4 Max, including
all 1,024 MCA chunks and temporary-region promotion. One sample region with
surface entrances took about 695 ms (23.5 ms GPU, 655.8 ms assembly and 15.1 ms
publication), excluding Minecraft loading, lighting and rendering. These are
sample measurements, not a controlled throughput benchmark. The first geology
client passed 30,760 loaded underground block comparisons and its heightmaps;
the user confirmed caves/ores worked, then requested natural surface openings.
The follow-up removes the roof cutoff and skips vegetation over entrances.

`./gradlew build` runs Rust unit tests and packages the build host's native library
in `build/libs/retina-0.1.0.jar`. Build on each target OS/architecture for its native
artifact. The source jar includes Rust and WGSL source. GitHub Actions builds a
Linux artifact; GPU tests require hardware and are run separately.

Development runs enable `--enable-native-access=ALL-UNNAMED`; add this JVM argument
to a normal launcher/server using the packaged mod. Fabric Loader 0.19.5+ and
Fabric API are required. `-Dretina.native.path=/absolute/library/path` selects an
externally built native library. `wgpu` enables Metal, Vulkan and Direct3D 12;
Vulkan and Direct3D 12 still need runtime testing.

References: [Fabric 26.3](https://www.fabricmc.net/2026/09/15/263.html),
[wgpu](https://docs.rs/wgpu/30.0.1/wgpu/), and
[Java's native linker](https://docs.oracle.com/en/java/javase/25/docs/api/java.base/java/lang/foreign/Linker.html).

## Native stage timings

Each generated region returns a local timing snapshot with its report (an extra
192 bytes, with no additional GPU readback). Concurrent region jobs keep their
measurements separate. The client aggregates the same 20-region window used for
latency and speed, rather than displaying cumulative session worker milliseconds.

Queue, GPU command encoding/readback, serial planning and file I/O record wall
latency. Rayon worker stages record CPU task elapsed time, then distribute the
measured parallel phase proportionally across terrain assembly, ores, cave
features, vegetation, structures, snow, NBT and compression. These are **estimated
wall shares**, marked `~`, not exact critical-path costs. Java column-cache writing
is measured separately; other/waiting includes bookkeeping and work outside those
measured phases. Structure planning includes GPU probes. Promotion does not contribute generation timings.

When the adapter exposes timestamp queries, GPU passes record hardware begin/end
timestamps for heights/climate, biome sites, columns/materials, cave density and
cave masks. Results use the existing submission/readback, adding at most 80 bytes
and no extra round trip. Device percentages use average region time but overlap
the GPU host phases; do not add them a second time. Adapters without timestamps
keep generating and report host timings.

Raw per-profile cumulative CPU/GPU counters remain available to QA/benchmarks.
The Java session subtracts a world-bind baseline when a resident profile is reused.
`gpuTest` checks rolling eviction, partial regions, promotion exclusion, formatting
and packet round trips; `regionTest`, `biomeTest` and `previewTest` check actual
job-local native measurements, GPU timestamps and concurrent isolation. An opt-in
server check uses `-PretinaQa -PretinaTimingsQa` and exits after validating loaded
chunks and the actual F3 payload.

## Native region performance

Rust now recycles chunk/NBT, level-1 libdeflate zlib compressor, output and ore
scratch storage, skips uniform palette index work, compiles structure processors
and indexes template blocks by chunk, and combines base assembly with GPU cave-mask consumption. New-region load requests
can prepare two temporary MCAs concurrently while the owning Minecraft I/O queue
continues to serialize save publication. Background work never changes live save
files, and publication preserves already stored chunks and player edits.

The matched Metal benchmark retained every feature and identical chunk NBT,
improving native throughput from about 2,525 to 4,418 chunks/s with two concurrent
regions. This measures native generation rather than loaded/rendered client
throughput. Concurrent jobs can have longer individual latency while producing
more chunks per second. See [the performance report](docs/NATIVE_PERFORMANCE.md)
for stage results, correctness checks and the repeatable benchmark commands.

MCA records retain standard type-2 zlib compression. Level 1 was already used by
the old encoder; the new compressor trades less CPU work for the same compatible
format. NBT uses retained palette index storage, specialized bit packing, an ASCII
string fast path with Java modified-UTF-8 fallback, and avoids unused heightmaps.
The 20-region matched encoding benchmark preserved all 20,480 decoded chunks;
see [the measured encoding results](docs/NATIVE_PERFORMANCE.md#encoding-and-timing-window-update).
