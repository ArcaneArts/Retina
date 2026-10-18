# Retina

Retina is a Rust and GPU terrain generator for **Minecraft 26.3**, available for
**Fabric and NeoForge**. It reads Minecraft's loaded worldgen registries, including
supported datapack and mod additions, and produces complete Anvil (`.mca`) regions
with terrain, biomes, decorations, structures and interior lighting.

Select **Retina** when creating a world. The Overworld uses the Rust/GPU generator;
the Nether and End retain their normal generators. Retina approximates the loaded
worldgen rules: it does not reproduce vanilla's terrain for the same seed.

## Try it

For development, install **JDK 25**, **Rust/Cargo 1.87 or newer**, and a platform C
compiler (Clang, GCC or MSVC). Then run:

```sh
./gradlew runClient
```

This launches **Fabric only**, builds and packages the host native library, and
uses the root `run/` directory. Create a new world and choose **Retina** in the
**World** tab. Enable datapacks through the normal world-creation screen. Press
**F3** to see generation throughput and stage timings.

For a new dedicated world, set `level-type=retina:gpu` in `server.properties` and
run `./gradlew runServer`. Minecraft's EULA must be accepted separately.

| Task | Purpose |
| --- | --- |
| `runClient` / `runServer` | Fabric development client/server |
| `runFabricClient` / `runFabricServer` | Explicit Fabric aliases |
| `runNeoForgeClient` / `runNeoForgeServer` | NeoForge client/server |
| `build -PretinaHostOnly` | Build both loader JARs with this host's native library |
| `build` | Build both loader JARs with the native platforms supported by the build host |
| `genSources` | Generate Minecraft sources for IDE navigation |

Run these tasks with `./gradlew`. NeoForge uses `run/neoforge-client/` and
`run/neoforge-server/`. `-PretinaClientRunDir=/absolute/directory` selects a separate
client directory. Task-specific arguments such as `--args` and `--tests` require
the project task, for example `:fabric:runClient` or `:fabric:test`.

### Packaged installations

Install the JAR matching the loader; do not install the common module or sources JAR:

- Fabric: `fabric/build/libs/retina-fabric-26.3-<version>.jar`, Fabric Loader
  **0.19.5+**, and Fabric API. The development build uses Fabric API
  `0.161.0+26.3`.
- NeoForge: `neoforge/build/libs/retina-neoforge-26.3-<version>.jar`, with
  **26.3.0.51-beta+** for Minecraft 26.3.

Both require Java 25 and a compatible GPU compute adapter. For a packaged Fabric
launcher/server, add `--enable-native-access=ALL-UNNAMED` to the JVM arguments.
For NeoForge, also add `--illegal-native-access=allow`, since FML creates the mod's
named module after JVM startup. Development tasks already supply these arguments.

The engine enables Metal, Vulkan and Direct3D 12 through `wgpu`. GPU generation
has been exercised on Metal/Apple M4 Max; Vulkan and Direct3D 12 still need runtime
validation. There is no vanilla/CPU terrain fallback when GPU initialization or
generation fails. Minecraft releases after 26.3 require a separate port.

## Generated world features

| Area | Current implementation |
| --- | --- |
| Terrain and biomes | GPU density programs, registered noise and splines, climate intervals, biome distribution, rivers and globally aligned interpolation fields |
| Surfaces and shores | GPU material rules for complete vertical layers, underwater surfaces, soil depths, badlands terracotta bands, sandy/snowy beaches, stony shores and supported sediment features |
| Underground | Lush caves, dripstone caves and deep dark, cave/canyon carving, curved ravines, surface entrances, cave decorations, local aquifer fluids and pressure barriers |
| Lakes and ores | Registered water/lava lake settings and biome ore recipes, with GPU lake probes and supported ore geometry/masks |
| Vegetation | Biome-specific trees, mushrooms, grass, flowers, bushes, bamboo, cactus, sugar cane, kelp, seagrass, vines and other supported registered features |
| Structures | Rust jigsaw assembly and supported procedural templates, including villages, outposts, ancient cities, trial chambers, trail ruins, desert pyramids, jungle temples and swamp huts |
| Snow and lighting | Loaded snow-support rules and GPU block/sky lighting for the interior of fresh complete regions |

Vegetation uses registered counts, rarity, selectors, heightmaps, offsets and
placement predicates in their original order. Accepted plants and trees update a
live placement overlay, so later attempts see the canopy and actual support
materials. Counts are attempts, not a forced density of successful trees. Ordered
survival checks keep plants off invalid surfaces such as village paths; snow uses
the game's support tags and leaves ordinary frozen water as exposed ice.

Supported adapters also cover fallen trees, giant mushrooms, vegetation patches,
sediment disks and composite features. Registered block-state providers, integer
distributions, scans and spatial filters feed the same replay. Tree geometry,
branching and canopy interactions run in Rust; compatible count/provider noise
queries and regular ore-vein preparation run in sparse GPU batches. See
[feature coverage](docs/GENERATION_GOAL_AUDIT.md),
[provider noise](docs/GPU_PROVIDER_NOISE.md) and
[GPU ore preparation](docs/GPU_ORE_PREPARATION.md).

Coastal placement follows registered shore targets and material rules, aligned to
the GPU's physical coast. Surface material selection uses per-block biome values
rather than the coarse underground biome grid. Beaches are not a universal sand
ring around every lake or coastline. See [shorelines](docs/SHORELINES.md),
[decoration placement](docs/DECORATION_PLACEMENT.md),
[registered block features](docs/REGISTERED_BLOCK_FEATURES.md) and
[snow support](docs/SNOW_SUPPORT.md).

## Datapacks and world compatibility

Retina imports Minecraft's **final merged registries**: vanilla data, enabled
datapacks, mod-provided packs and supported biome modifications. Pack priority
still determines which definition wins when multiple packs replace the same key.
Terralith is covered by the project's registry and generation tests.

If a pack replaces the Overworld generator, Retina retains the selected Retina
generator and imports the replacement's biome source, noise settings and dimension
bounds. These settings survive save/reopen. Supported density, surface, aquifer,
feature and structure data continue through Rust and the GPU; enabling a datapack
does not switch the world back to ordinary Java generation.

New Retina worlds enable **density composition**. Each registered interpolation
field retains its own global lattice and cell sizes; arithmetic and range choices
outside interpolation are evaluated at block positions. GPU programs also retain
coordinate shifts, noise parameters and Hermite splines. Climate intervals drive
biome selection rather than one averaged climate point per biome. Complete
material runs preserve multiple floors, ceilings and underwater layers.

GPU interpretation remains available while optional profile-specific shader
specialization compiles. Compatible programs and resident fields are reused;
submissions retain their own pipeline/data selection through readback. See
[production density composition](docs/PRODUCTION_DENSITY_COMPOSITION.md),
[GPU programs](docs/GPU_PROGRAM_SPECIALIZATION.md),
[material layers](docs/GPU_MATERIAL_LAYERS.md) and [aquifers](docs/GPU_AQUIFERS.md).

Compatibility has limits:

- GPU noise and some terrain, feature and structure behavior are approximations.
  Matching seeds do not imply matching vanilla layouts, and GPU backends may
  differ. Unsupported custom primitives/features/processors are reported during
  export; arbitrary mod-defined worldgen code is not automatically translated.
- Biomes absent from the imported climate source can receive an approximate
  temperature/rainfall niche. Missing placement data cannot be recovered from a
  biome name or tag alone.
- Structures support compatible random-spread/jigsaw sets and the procedural
  families listed above. Non-jigsaw assemblers such as strongholds, mineshafts,
  fortresses, mansions, monuments, end cities, shipwrecks and ruined portals remain
  unsupported. Bastion pools are supported by the assembler, but the standard
  Retina preset leaves the Nether on its normal generator.
- Existing saved blocks and player edits are preserved. Generation changes affect
  new terrain; old saved settings retain their legacy behavior where encoded,
  including worlds without the newer density-composition flag.

**Retina is the only visible world type.** The Bend runtime has been removed.
Old Bend preset identifiers redirect new generation to Rust while preserving saved
chunks. Hidden per-chunk identifiers remain readable for existing saves and
compatibility tests; they are not an alternative new-world option. Transitions
from older generator output can produce terrain seams.

## Region generation and parallelism

One MCA region holds **32 × 32 chunks**: 1,024 chunks covering 512 × 512 columns.
Retina allows **up to eight native region generations globally at all times**,
shared fairly between ordinary loads, Distant Horizons and Chunky. Requests for
the same region share preparation. Eight jobs share the existing GPU submission
slots and Rust worker pool; they do not create eight separate worker pools.

Fresh-region preparation happens **outside Minecraft's serialized I/O queue**.
Ready reads can proceed while other regions generate. The pipeline:

1. Java exports the effective registry profile and palette properties for native
   use. GPU programs and supporting fields remain resident.
2. GPU passes evaluate density, climate, surface materials, cave biomes, carving,
   aquifers and lake fields. Sparse batches handle supported feature counts,
   provider noise and ore preparation. Rust consumes compact masks and material
   runs instead of receiving intermediate density grids.
3. Rust performs ordered feature replay, jigsaw planning, block assembly,
   heightmaps, palettes and metadata. Shared scratch storage and batched/resumable
   queries reduce allocation and repeated work. The region assembly pool defaults
   to available parallelism capped at 16 threads; `RETINA_REGION_THREADS` can
   select a smaller runtime pool.
4. GPU lighting uses the final blocks. Ready lighting tiles feed parallel NBT
   encoding and level-1 libdeflate zlib compression with reused buffers.
5. Publication runs on the owning Minecraft I/O queue. It closes cached region
   handles, preserves existing chunk records and edits, and atomically publishes
   the completed file. Pending Minecraft writes retain their normal ordering.

Existing partial saves retain the serialized repair/merge path. Unreadable MCA
files and real generation errors are surfaced rather than silently replaced.
Whole-region generation also produces chunks outside the current view or exact
pregeneration boundary.

Minecraft reads normal chunk NBT from disk and completes the remaining generation,
entity activation and loading work. Structure starts/references, template entities,
block entities, loot and spawner data are retained; Minecraft handles activation
and loot resolution. Disk reads, NBT decoding, remaining lighting and rendering
still contribute to load time.

### GPU lighting

Fresh complete regions prelight their **30 × 30 interior chunks**: 900 of 1,024
chunks, or 87.9%. The outer chunk ring supplies neighboring terrain and is left for
Minecraft to light normally. Lighting uses registered emission, attenuation and
face occlusion, with sparse GPU worklists that skip stable or unreachable areas.

Interior light arrays are encoded before the region is saved, so lighting does
not add a second MCA rewrite. Partial repairs and compatibility paths without
usable lighting metadata keep Minecraft lighting; later block changes also use
Minecraft's normal engine. See [GPU region lighting](docs/GPU_REGION_LIGHTING.md).

## Distant Horizons and Chunky

### Distant Horizons

DH surface generation, in-memory terrain requests and height/base-column queries
share Retina's temporary MCA path. They do not fall back to individual chunk GPU
generation. The cache uses the same terrain, decoration, structure and lighting
pipeline as saved regions.

- Up to **1,024 temporary regions** are retained on disk with LRU eviction.
- Up to **16 regions** keep column arrays and file handles warm in memory. Cold
  data reloads from compressed sidecars and cached MCA records.
- Active jobs and reads pin entries until completion, so active entries can
  temporarily exceed cache limits. Files are cleaned up on eviction/world close.
- When Minecraft requests real terrain, cached compressed records are **promoted**
  into missing save slots without repeating GPU generation, NBT encoding or
  compression. Existing edits survive promotion into absent, empty or partial files.

Temporary previews do not themselves publish terrain to the world save. DH may
retain its own LOD data or create empty MCA placeholders independently.

### Chunky

Starting or resuming a Chunky task starts an independent Retina region pump. It
uses Chunky's own selection shape and a separate resumed iterator, generating
intersecting MCA regions **ahead of Chunky's chunk requests**. Up to eight region
jobs remain in flight, refilling when publication finishes.

Chunky continues to load/finalize chunks and report its own progress. Pause,
cancellation or task exit stops new ahead admissions; already-admitted jobs drain.
No extra command or launch flag is required. Chunky's reported chunks/sec can
remain load-bound even when Retina has already generated the terrain to disk.

The shared eight-region allowance also applies when Chunky is absent or paused.
See [scheduling and publication](docs/CHUNKY_REGION_SCHEDULING.md) for lifecycle,
concurrency and integration checks. An optional compatibility mixin also retries
DH's Chunky listener registration when their startup order requires it.
Recorded loader compatibility checks cover DH 3.3.4 and Chunky Fabric 1.5.3 /
NeoForge 1.5.4; the ahead-selection fixture uses the actual Chunky Fabric 1.5.3 JAR.

`/locate biome` uses batched GPU queries; `/locate structure` reads saved starts
and queries the Rust planner for unknown candidates. These searches do not need
to assemble whole terrain regions. See [locate queries](docs/GPU_LOCATE_QUERIES.md).

## F3 metrics

- **Completed terrain** is actual newly generated chunks per **10-second wall-clock
  window**. During startup it uses elapsed observation time and displays that
  duration. Concurrent completions count together; promotion, hits, failures and
  already-saved slots do not count as new terrain. The rate decays to zero when idle.
- **Region latency** shows mean and p95 over the **last 20 newly generated regions**,
  including temporary regions. These samples remain visible while idle. Latency
  is not converted into a second chunks/sec or amortized-chunk figure.
- **Timing stages** have one colored line each for GPU host work, native planning,
  assembly, NBT, compression, I/O, column caching and GPU device passes. Percentages
  use average region latency. Worker estimates carry `~`; GPU device time overlaps
  host time and should not be added again.
- **Regions, temporary regions, hits, promotions, terrain chunks, failures and
  in-flight jobs** distinguish generation from cache reuse and publication.
- **GPU diagnostics** show backend, program execution/compilation state, graph
  sizes and cached horizontal fields. **GPU transfers / region** shows upload,
  readback and total logical MiB, normalized to 1,024 newly generated chunks over
  the native profile's lifetime. It reuses existing counters at the telemetry
  cadence, with no GPU readbacks added for measurement. Promotions and cache hits
  do not increase the denominator. This is an estimate: setup, probes and work
  still in flight contribute bytes, and partial regions count by generated chunks.
  Logical transfer volume is not physical PCIe traffic on unified-memory GPUs.

Dedicated servers send these stats to modded clients once per second. Terrain
production is distinct from Minecraft's fully loaded/rendered throughput.
Performance evidence and workload-specific limits are collected in the
[Rust/GPU performance report](docs/PERFORMANCE_PASS_2026_10.md); historical stage
benchmarks are not a promise of current end-to-end chunks/sec.
The [current benchmark triage](docs/PERFORMANCE_TRIAGE_2026_10_18.md) compares
vanilla/Terralith, concurrent callers, specialization and transfer costs, and
ranks the next optimization opportunities. The first follow-up
[compacts decoration programs](docs/COMPACT_DECORATION_PROGRAMS.md): two matched
Terralith trials improved native lit-MCA throughput by 11–14% with identical chunk
NBT. Vanilla's smaller measured difference is inconclusive. The next follow-up
[streams decoration candidates](docs/STREAMED_DECORATION_CANDIDATES.md), improving
two matched Terralith trials by a further 6–8% against that implementation and
reducing dense replay retention from about 46 to 6.7 MiB. These are separate
comparisons, not an additive overall speedup. The
[cycle tracker](docs/OPTIMIZATION_CYCLES_2026_10.md) records remaining work.

## Building and testing

Gradle's native tasks use **two Cargo jobs**, two code-generation units and lower
process priority (`nice +10` on macOS/Linux). Gradle defaults to two workers with
parallel project execution disabled. These compile limits are separate from the
runtime region/assembly limits. The C compiler builds vendored libdeflate; no
separate libdeflate runtime installation is needed.

Use `./gradlew build -PretinaHostOnly` for local loader JARs. A full `build`,
`assemble` or distributable `jar` also packages cross-compiled libraries according
to the build host:

| Build host | Native platforms in a full JAR |
| --- | --- |
| macOS ARM64 or x64 | macOS, Linux and Windows, each ARM64 and x64 |
| Linux ARM64 or x64 | Linux and Windows, each ARM64 and x64 |
| Windows | Host Windows architecture |

Development runs and verification tasks use the host library. Cross builds run
sequentially and require the release tools below. The source JAR includes Rust and
WGSL sources without native binaries. `-Dretina.native.path=/absolute/library/path`
selects an externally built native library.

Choose focused validation tasks rather than running every GPU suite for a small
change. Ordinary builds include Rust unit tests and loader/registry checks; the
explicit GPU integration suites require a compute device.

| Tasks | Coverage |
| --- | --- |
| `regionPreparationTest`, `generationMetricsTest` | Scheduling, leases, eight-region limits, ten-second throughput, F3 formatting and packet transport |
| `test` | Fabric loader/mixin tests; use `:fabric:test --tests ...` to select a fixture |
| `gpuTest` | Native/GPU concurrency, coordinate and interpolation checks, materials, aquifers and related shader fixtures |
| `regionTest`, `previewTest` | Real MCA decoding, partial saves, saved edits, temporary cache and promotion |
| `biomeTest`, `geologyTest`, `featureTest`, `blockFeatureTest` | Registered biomes, ores, caves, vegetation and supported feature recipes |
| `structureTest`, `shoreTest`, `snowTest`, `lightingTest` | Structures/metadata, coastal selection, snow support and GPU lighting against Minecraft |
| `datapackTest -PtestDatapack=/absolute/path/Terralith.zip` | Actual pack loading, Retina retention, save/reopen and native output; `-PtestSupplement=...` adds a higher-priority pack |

See [loader runtime QA](docs/RUNTIME_QA.md) for isolated gameplay, dedicated-server,
reconnect and saved-world checks. `-PretinaQa` enables structured checkpoints;
plain `runClient` does not launch automated world tests. Profile export and
Minecraft classpath comparisons are described in the
[multiloader report](docs/MULTILOADER_MIGRATION.md).

### Release cross-compilation tools

For the full six-platform JAR on macOS:

```sh
brew install llvm@20 lld
python3 -m pip install --user cargo-xwin==0.23.1 cargo-zigbuild==0.23.4 ziglang==0.13.0
rustup target add aarch64-apple-darwin x86_64-apple-darwin aarch64-unknown-linux-gnu x86_64-unknown-linux-gnu aarch64-pc-windows-msvc x86_64-pc-windows-msvc
export PATH="$(brew --prefix llvm@20)/bin:$(brew --prefix lld)/bin:$(python3 -m site --user-base)/bin:$PATH"
```

Linux cross builds need LLVM/Clang, the same Cargo cross tools, and both Linux and
Windows Rust targets above. Windows host-only builds need no cross tools.
`cargo-xwin` obtains the Microsoft SDK/CRT under its license and embeds the C
runtime. `cargo-zigbuild` targets glibc 2.17 for Linux. Use the listed versions with
the vendored libdeflate sources; Linux ARM64 cross builds disable its optional
SHA3 checksum implementation for Zig 0.13 compatibility. Missing compiler/tool
errors propagate from the actual build.

## Source layout

- `common/`: shared Java integration, client telemetry, resources and
  Minecraft-reference tests. Standalone compilation uses ModDevGradle/NeoForm.
- `fabric/`, `neoforge/`: loader adapters, development runs and distributable JARs.
- `native/`: the shared Rust engine, WGSL shaders, native tests and benchmarks.
- `gradle/`: shared native build, packaging and verification tasks.
- `qa/`, `docs/`: fixtures, validation procedures and detailed implementation reports.

Manual changes follow [the PR/worktree protocol](docs/MANUAL_CHANGE_PROTOCOL.md).
