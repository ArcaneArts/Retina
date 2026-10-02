# Retina

Fabric 26.3 terrain generation with GPU Voronoi biomes, interpolated simplex heights, GPU caves, registry-derived ores, and Rust chunk/MCA assembly.

## Run it

Install JDK 25 and Rust/Cargo (Rust 1.87 or newer). Gradle selects Java 25 and builds
and packages the native library automatically.

```sh
./gradlew build
./gradlew gpuTest regionTest biomeTest geologyTest previewTest
./gradlew runClient -PretinaQa
```

Create a new single-player world and select a Retina world type in the **World** tab:

- **Retina GPU Simplex (MCA)**: the default Retina mode; Rust writes whole regions.
- **Retina GPU Simplex (Chunks)**: the original per-chunk buffer/conversion mode.

Both generate the same biome terrain model in the overworld. Nether and End remain vanilla.
The default biome set includes plains, forests, taiga, snowy plains, desert, savanna,
badlands, jungle, mangrove swamp, windswept hills, jagged peaks and three oceans.
Surfaces include grass/dirt, podzol, sand, red sand/terracotta, mud, snow and stone.
Oceans fill to the registry sea level, with ice in cold biomes; bedrock and deepslate
form the lower layers. Grass, ferns, flowers, tall plants, bushes and biome-specific
wood/leaves are assembled in Rust from vanilla's registered decoration recipes.
Creative mode with commands enabled is useful for testing. Press **F3**, then fly
into unexplored terrain or use `/tp @s 4096 160 4096` to trigger generation.

Development files live in `run/`. The optional `-PretinaQa` logs structured
checkpoints and validates decorated Minecraft heightmaps and paired tall plants. Use a fresh world for the boundary checks.
A plain `./gradlew runClient` omits QA checkpoints.

For a fresh dedicated world, use `level-type=retina:gpu` for MCA, or
`level-type=retina:gpu_chunk` for per-chunk generation. Launch using
`./gradlew runServer`; Minecraft's EULA must be accepted separately.
`./gradlew genSources` generates Minecraft sources for IDE navigation.

## MCA mode

A region is 32 x 32 chunks (512 x 512 columns). The first requested chunk in a
region coordinates generation through Minecraft's serialized chunk I/O queue.
Other concurrent reads wait behind this job, then load the same published file.
Height/base-column and in-memory surface requests use temporary region batches
(described below), and do not publish terrain to the world save.

Rust performs the following work:

1. Read the real MCA header and preserve all existing chunk records.
2. Send one 48-byte region descriptor to the GPU. A small pass creates nearby
   Voronoi sites and samples registered climate noise; a second pass computes
   warped biome assignment, blended height parameters, simplex relief, coherent surface
   borders and soil depth. Site data stays on the GPU. Vegetation threshold noise is included in the same pass.
   A one-chunk halo produces 544 x 544 compact column records (2.26 MiB).
   Two further GPU stages sample 3D cave fields on a global four-block lattice,
   interpolate those fields, apply biome cave/canyon settings and pack a final
   one-bit mask. The 384-block-high mask, including a one-block exposure halo,
   adds 12.09 MiB of readback per region. A 36 KiB surface bitset covers the
   decoration halo to reject unsupported vegetation; the density lattice stays on the GPU.
3. Plan ore veins and vegetation in parallel with global seeded anchors.
   Expand terrain, consume the GPU cavity mask, place ore replacements and then
   settle the sparse decoration overlay. Neighboring anchors can place trees and patches across chunk/region borders.
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

The cache retains up to **1,024 regions on disk**, using LRU eviction. Each file
contains a full 32 x 32 region. Undecorated GPU columns are returned by that same
native generation call and stored in a compressed sidecar for later height and
biome queries. Only 16 regions' column arrays and file handles stay warm in memory
(about 32 MiB of column arrays); cold data reloads from disk without GPU work.
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
its device, two compute pipelines and buffers. Decorated chunk jobs request a 3 x 3
chunk terrain tile (one descriptor, 18 KiB columns plus about 15.5 KiB cave/surface bits); height/biome queries retain
single-chunk batching. Calling threads assemble dense
material-index buffers in Rust. Java converts those bytes into section palettes,
stores the GPU biome IDs, and primes heightmaps.

The byte layout is `((y - min_y) * 16 + z) * 16 + x`, with `0` for air and `1` for
the registered default stone. Remaining material IDs refer to the world profile’s
block states. Synchronous native calls return before their borrowed buffers are released.

Both modes use GPU-produced column records for biome, height and base-column
queries. Rust caches up to 8,192 chunks; Java caches up to 2,048 biome/height tiles.
The cache includes seed, world profile, coordinates, bounds and terrain settings. There is no CPU noise implementation or CPU fallback.
The generator codec exposes `mode` (`mca`, the default, or `chunk`), `min_y`,
`height`, `base_height`, `amplitude`, `frequency` and `biome_source`.
Different GPU backends may produce different terrain for the same seed.

## Biome profile and GPU interpolation

At world startup, Java reads the actual overworld registries once: climate targets
from the multi-noise biome parameter list, temperature/vegetation/continentalness/
erosion noise octaves and amplitudes, the overworld sea level/default blocks, and
26.3 material rules. It probes material rules at representative land, subsurface
and seabed positions to export top/filler/underwater block states. The resulting
small profile is registered once and stays resident on the GPU; requests only send
its handle and the region descriptor.

Vanilla has no per-biome simplex/height parameters. Retina supplies approximate
height offset, relief amplitude and scale styles for each biome, then blends them
on the GPU using smooth weights around Voronoi sites. Climate noise and layered domain
warping select discrete biome IDs and matching surface recipes. Broad, ribbon and
wisp noise bends the borders into continuous curves, without random block scatter.
Fixed-frequency relief fields are blended, avoiding distance-dependent phase
distortion from changing the frequency at global coordinates. All heights,
interpolation, soil depths, snow/ice flags
and surface material choices are finished before readback. Rust expands those
records into vertical layers and packs NBT; it does not generate noise or blend
heights on the CPU.

The biome-source codec exposes `biomes` (registry IDs), `biome_scale` (128–4096,
default 256 blocks) and `blend` (0.1–1, default 0.55). Both Retina world presets
use `retina:voronoi`. Old saves with `minecraft:fixed` retain their original
stone/air model, and existing chunks are preserved. Use a fresh world to see all
biome changes. New terrain meets existing terrain without retroactive blending.

This approximates vanilla biome terrain; it does not implement vanilla's full
density router, every material rule/noise condition, terracotta bands, 3D cave
biomes or structures. Material noise
conditions are evaluated at a representative zero value when extracting recipes.
Climate octaves feed GPU simplex rather than vanilla Perlin. GPU backends may
produce different results, as permitted for this project.

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
natural decay behavior. This adds no GPU dispatch or terrain readback.

Count distributions and feature selectors are projected into mean densities;
noise-provider plant palettes use seeded choices. Vegetation threshold noise runs
on the GPU using simplex, rather than copying vanilla's CPU noise implementation.
Water-adjacent bush anchors use registered water offsets. Aquatic vegetation,
bamboo, fallen trees, cactus/block-column features, tree decorators such as
beehives and leaf litter, and unsupported underground/placement rules are
omitted. The export logs omitted feature kinds. Existing regions are preserved;
use a fresh world to inspect the new decorations.

## Registry-derived ores and GPU caves

Java traverses the actual selected biome features and carvers once per world.
Ore recipes retain their registered vein size, count range, rarity, uniform or
trapezoid height distribution, stone/deepslate replacement rules and air-exposure
suppression. Nested block/tag/height predicates become compact replacement tables
with height bands, including 26.3's height-specific host blocks. Biome membership
preserves extra badlands gold and mountain emeralds/infested stone. Registered
stone, dirt, gravel and tuff replacement veins are included. The default 16-biome
set currently exports 28 ore recipes alongside its vegetation data.

Rust builds ellipsoidal/scattered veins from global chunk anchors. Contained
ellipsoids are pruned and visited voxels use a small bitset. The region's halo
includes neighboring anchors so veins cross chunk and region edges. Planning,
mask consumption, ore placement, palettes, heightmaps and compression share the
persistent region pool of up to 16 cores. Independent Minecraft chunk requests
also assemble concurrently after the persistent GPU worker returns their masks.
Assembly order is base terrain, caves, ores, then vegetation.

GPU cave fields use registered cave cheese, spaghetti, layer, roughness and pillar
noise octaves/amplitudes. Two additional compute stages produce chambers, tunnels
and canyon ribbons using the biome's registered carver probability, center-height
range, thickness and radius parameters. Interpolation and final cavity decisions
happen on the GPU. Rust reads a bit-packed mask and replaces carved blocks with
air, lava below 26.3's global lava boundary, or water below sea level in ocean
biomes. Bedrock stays protected; caves can break through the ground and hillsides.
The GPU also emits one surface bit per halo column, so Rust skips tree and plant
anchors over openings without reading back the halo's full cave volume.

These shapes approximate vanilla carvers/noise caves; they do not replay vanilla
random walks or the full density router/aquifer pressure model. Lush/dripstone
cave biomes and their decorations are not included. Unsupported registry feature
rules are logged rather than guessed. Both MCA and per-chunk generation use the
same geology; temporary DH MCAs contain it and promotion copies it unchanged.
Height/base-column APIs return terrain before carving/features and do not run a
3D GPU pass. Native cavity masks are shared across requests in a bounded 2,048
chunk-entry cache; the larger temporary MCA cache remains on disk.

Run `./gradlew geologyTest` for real-GPU parallel ore/cave and exposure checks.
`biomeTest` checks all 1,024 MCA chunks against independent chunk assembly and
Minecraft's six heightmap predicates. Use a fresh world or unexplored regions
for new geology; existing saved chunk records are preserved.

## F3 metrics

- **chunks/s (5s)**: newly produced terrain chunks over the last five seconds.
  MCA counts all newly produced slots, including temporary preview batches and
  chunks not yet requested by the game. Promotion does not count production twice. Disk cache hits and previously saved chunks do not count as production.
- **ms/chunk**: weighted timings from the last 128 production jobs. In per-chunk
  mode this is request-to-completion latency, including worker queue delay. In
  MCA mode it is region generation/publication time divided by chunks produced,
  explicitly labeled **amortized**. It is not a chunk's individual wait time.
- **Native / Convert**: native time and Java block conversion time per produced
  chunk. MCA native time includes file processing/publication; conversion is zero
  for fresh region chunks. Minecraft's NBT decoding is subsequent work.
- **Temporary regions / Cache hits / Promoted**: preview batches produced, reused
  preview requests, and copies published by real Minecraft chunk loading.
- **Regions / Last region**: regions published to the save and the complete latest region-job
  latency, including initialization, GPU work, CPU assembly and file publication.
- **In flight jobs / Terrain chunks / Failed**: current jobs and lifetime counters.

Timings remain visible while idle. These are terrain production metrics rather
than fully lit/rendered chunk throughput. Dedicated servers send stats to modded
clients once per second.

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
