# Retina

Fabric 26.3 terrain generation with GPU Voronoi biomes, interpolated simplex heights, and Rust chunk/MCA assembly.

## Run it

Install JDK 25 and Rust/Cargo (Rust 1.87 or newer). Gradle selects Java 25 and builds
and packages the native library automatically.

```sh
./gradlew build
./gradlew gpuTest regionTest biomeTest
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
form the lower layers.
Creative mode with commands enabled is useful for testing. Press **F3**, then fly
into unexplored terrain or use `/tp @s 4096 160 4096` to trigger generation.

Development files live in `run/`. The optional `-PretinaQa` logs structured
checkpoints and validates real loaded surface blocks, GPU height queries,
base columns and Minecraft heightmaps. Use a fresh world for the boundary checks.
A plain `./gradlew runClient` omits QA checkpoints.

For a fresh dedicated world, use `level-type=retina:gpu` for MCA, or
`level-type=retina:gpu_chunk` for per-chunk generation. Launch using
`./gradlew runServer`; Minecraft's EULA must be accepted separately.
`./gradlew genSources` generates Minecraft sources for IDE navigation.

## MCA mode

A region is 32 x 32 chunks (512 x 512 columns). The first requested chunk in a
region coordinates generation through Minecraft's serialized chunk I/O queue.
Other concurrent reads wait behind this job, then load the same published file.
Height/base-column queries also request the region through this storage path.

Rust performs the following work:

1. Read the real MCA header and preserve all existing chunk records.
2. Send one 48-byte region descriptor to the GPU. A small pass creates nearby
   Voronoi sites and samples registered climate noise; a second pass computes
   warped biome assignment, blended height parameters, simplex relief, coherent surface
   borders and soil depth. Site data stays on the GPU. One 2 MiB readback
   contains 512 x 512 compact column records.
3. Assemble Minecraft 26.3 block and biome palettes, heightmaps,
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
the original terrain adapter. Actual MCA/GPU/load errors are surfaced; a failed
read does not silently generate new terrain over an unreadable region.

MCA accepts the Retina GPU biome source or a legacy fixed biome source, with
generation bounds matching the actual dimension. Default overworld bounds are -64 through 319. Whole-region
production generates chunks outside the current view and consumes disk space
accordingly (about 4 MiB per region for the current biome terrain).

## Per-chunk mode

Concurrent Minecraft terrain jobs call Rust through Java 25's Foreign Function
and Memory API. A persistent `wgpu` worker batches pending GPU requests and reuses
its device, two compute pipelines and buffers. Calling threads assemble dense
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
biomes, caves, trees, ores, structures or biome decoration yet. Material noise
conditions are evaluated at a representative zero value when extracting recipes.
Climate octaves feed GPU simplex rather than vanilla Perlin. GPU backends may
produce different results, as permitted for this project.

## F3 metrics

- **chunks/s (5s)**: newly produced terrain chunks over the last five seconds.
  MCA counts all newly written slots, including chunks not yet requested by the
  game. Disk cache hits and previously saved chunks do not count as production.
- **ms/chunk**: weighted timings from the last 128 production jobs. In per-chunk
  mode this is request-to-completion latency, including worker queue delay. In
  MCA mode it is region generation/publication time divided by chunks produced,
  explicitly labeled **amortized**. It is not a chunk's individual wait time.
- **Native / Convert**: native time and Java block conversion time per produced
  chunk. MCA native time includes file processing/publication; conversion is zero
  for fresh region chunks. Minecraft's NBT decoding is subsequent work.
- **Regions / Last region**: regions produced and the complete latest region-job
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
checks negative chunk/region boundaries.

The biome integration tests passed on Metal / Apple M4 Max. One measured full
region took about 34 ms (1.1 ms GPU, 19 ms assembly, 13 ms publication), excluding
Minecraft loading, lighting and rendering. These are sample measurements, not a
controlled throughput benchmark. The prior stone/air create/explore/save/reopen
test also passed with player edits preserved. The updated biome client passed
loaded-block/heightmap checks, and the user confirmed that coherent borders and
the distance distortion fix looked correct.

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
