# Retina

Retina is a Rust and GPU terrain generator for **Minecraft 26.3**, available for
**Fabric, NeoForge, and Bukkit**. The mod integrations compile loaded worldgen registries and supported
datapack/mod additions into `<world-root>/retina.dat`, then generates complete
Anvil (`.mca`) regions from that saved definition with terrain, biomes,
decorations, structures and interior lighting.

The [Bukkit plugin](docs/BUKKIT.md) bundles reusable vanilla data and automatically
creates `retina.dat` for the chosen seed. Its public-API delivery policy disables
structures and scheduled spring roots; it uses server lighting and persistence.
Select it through Bukkit's generator configuration. CraftBukkit/NMS acceleration
and a launch wrapper are separate follow-up targets.

On Fabric and NeoForge, install Retina and select your usual world type. Supported Overworld, Nether and End
generators automatically use Retina when creating or reopening a world; there is no disable
switch. Normal, Large Biomes, Amplified, Single Biome and Superflat are supported.
The Nether uses its effective noise settings and fixed or multi-noise biome source,
including compatible datapack changes. New portal destinations are prepared
asynchronously before Minecraft selects or creates the exit, keeping server ticks
and network connections active during generation. End terrain, biomes, features and
cities match the loaded Minecraft 26.3 rules; Minecraft retains dragon progression
and gateway destination selection. Debug and unsupported custom
generators retain their original implementation, with a diagnostic identifying the missing adapter. Retina
approximates Overworld and Nether noise worldgen rules rather than reproducing their
vanilla terrain for the same seed. Undecorated Superflat uses the configured layers exactly.

The [Nether delivery audit](docs/NETHER_GOAL_AUDIT.md) links the implementation
cycles and validation matrix. [Inspected game captures](docs/NETHER_VISUAL_QA.md)
show the five biomes, structures, lighting and sampled region boundaries.

## Try it

For development, install **JDK 25**, **Rust/Cargo 1.87 or newer**, and a platform C
compiler (Clang, GCC or MSVC). Then run:

```sh
./gradlew runClient
```

This launches **Fabric only**, builds and packages the host native library, and
uses the root `run/` directory. Choose a world type and customize it through the
normal **World** tab. Enable datapacks through the world-creation screen. Press
**F3** to see generation throughput and stage timings.

For a dedicated world, use ordinary `level-type` and `generator-settings` values
in `server.properties` (for example, `level-type=minecraft:normal` or
`level-type=minecraft:flat`) and run `./gradlew runServer`. Minecraft's EULA must
be accepted separately.

| Task | Purpose |
| --- | --- |
| `runClient` / `runServer` | Fabric development client/server |
| `runFabricClient` / `runFabricServer` | Explicit Fabric aliases |
| `runNeoForgeClient` / `runNeoForgeServer` | NeoForge client/server |
| `:bukkit:build` / `buildBukkit` | Build the installable Bukkit plugin with bundled vanilla data |
| `build -PretinaHostOnly` | Build mod and plugin JARs with this host's native library |
| `build` | Build mod and plugin JARs with the native platforms supported by the build host |
| `genSources` | Generate Minecraft sources for IDE navigation |

Run these tasks with `./gradlew`. NeoForge uses `run/neoforge-client/` and
`run/neoforge-server/`. `-PretinaClientRunDir=/absolute/directory` selects a separate
client directory. Task-specific arguments such as `--args` and `--tests` require
the project task, for example `:fabric:runClient` or `:fabric:test`.

### Packaged installations

Install the JAR matching the platform; do not install helper modules, probe plugins, or sources JARs:

- Fabric: `fabric/build/libs/retina-fabric-26.3-<version>.jar`, Fabric Loader
  **0.19.5+**, and Fabric API. The development build uses Fabric API
  `0.161.0+26.3`.
- NeoForge: `neoforge/build/libs/retina-neoforge-26.3-<version>.jar`, with
  **26.3.0.51-beta+** for Minecraft 26.3.
- Bukkit: `bukkit/build/libs/retina-bukkit-26.3-<version>.jar`. See the
  [installation, tested servers and API limitations](docs/BUKKIT.md).

All require Java 25 and a compatible GPU compute adapter. For packaged Fabric or Bukkit
launcher/server, add `--enable-native-access=ALL-UNNAMED` to the JVM arguments.
For NeoForge, also add `--illegal-native-access=allow`, since FML creates the mod's
named module after JVM startup. Development tasks already supply these arguments.

The engine enables Metal, Vulkan and Direct3D 12 through `wgpu`. GPU generation
has been exercised on Metal/Apple M4 Max; Vulkan and Direct3D 12 still need runtime
validation. There is no vanilla/CPU terrain fallback when GPU initialization or
generation fails. Minecraft releases after 26.3 require a separate port.

## Standalone region generation

Copy a world's `retina.dat` to an otherwise empty directory. A Rust executable
uses the same loader, engine and region writer without Minecraft, Java or
external datapacks. It still requires a supported GPU compute adapter.

```sh
cargo build --manifest-path native/Cargo.toml --release --bin retina
./native/target/release/retina generate \
  --definition /path/to/retina.dat \
  --dimension minecraft:overworld \
  --region-min=-4,-4 --region-max=4,4 \
  --output /path/to/generated-world
```

Both ends of the region range are inclusive. The file fixes the seed and all
supported dimension generation data. Existing chunk records are preserved;
use empty output or regions from the matching definition, with any server
stopped. Ctrl-C finishes the active region and exits with status 130.
This produces region data; creating `level.dat` and initializing a playable
save remain outside the executable's scope. See [world definitions and headless
usage](docs/WORLD_DEFINITION.md) for layouts, compression, errors and validation.
The [implementation acceptance report](docs/WORLD_DEFINITION_ACCEPTANCE.md)
records merged PRs, executed mod/headless comparisons and file size/load measurements.

## Configuration

Fabric and NeoForge create `config/retina.toml` on first startup. Restart the
game or server after editing it. The defaults are:

```toml
allow_directx = true
allow_metal = true
allow_vulkan = true
mca_parallelism = 8
mca_compression = "zlib"
debug = false
f3_overlay = true
```

`allow_directx` (DirectX 12), `allow_metal`, and `allow_vulkan` restrict GPU
selection to the enabled backends. Leave one enabled to select it; leave several
enabled for automatic selection among those backends. Unsupported choices fail
at GPU initialization. Existing files default missing flags to `true`; add the
keys above to change them. Restart after editing.

Set all three to `false` to disable automatic Retina worldgen. The mods keep
Minecraft's original generators and skip opening/creating `retina.dat` and GPU
startup. Existing chunks and `retina.dat` are retained. Legacy saves that explicitly
serialize a Retina-only generator cannot recover an original Minecraft generator
and report that they require a backend instead. Switching generators can create
seams in newly generated terrain.

Bukkit supports these same keys in `plugins/Retina/config.yml`; all false makes
Retina return no custom generator or biome provider, leaving selection to Bukkit.
The diagnostic `-Dretina.gpuBackend=...` override (or `RETINA_GPU_BACKEND`) further
narrows the allowed set; it cannot enable a disabled backend. A conflicting
override fails with a no-allowed-backend error. The JVM property takes precedence
over the environment variable.

`mca_parallelism` limits simultaneous native MCA region generations globally,
including ordinary loads, Distant Horizons, Chunky and partial-save repairs:

| Value | Limit |
| --- | --- |
| Positive, such as `3` | Exactly that maximum, independent of CPU count |
| `0` or `-1` | Available CPU processors reported by Java |
| Negative, such as `-2` | CPU count divided by the absolute value, rounded up, minimum 1 |

On a 16-processor system, `0` and `-1` resolve to 16, `-2` to 8, and `-3` to 6.
On a four-processor system, `-32` resolves to 1. This controls region concurrency;
the shared native assembly pool and GPU submission slots retain their own limits.

`mca_compression` selects `"zlib"` (default, smaller saves) or `"lz4"` (faster
compression, larger saves) for newly generated MCA chunks. LZ4 uses Minecraft's
type-4 LZ4Block format. Existing chunks keep their codec, and temporary-region
promotion copies compressed records without recompressing them. A region can
contain both codecs. Changing this setting requires a restart and does not
rewrite existing terrain. A chunk that exceeds the inline MCA size limit with
LZ4 is retried with zlib. Column-cache sidecars keep their existing format.
See [MCA compression measurements](docs/MCA_COMPRESSION.md) for the measured
tradeoff and validation commands.

`debug` enables extra startup and per-region generation diagnostics in the log.
Normal lifecycle/registry summaries, compatibility reports, warnings, errors and
explicitly enabled QA checkpoints remain available with debug off.
Native compiler progress is always logged; see [Windows startup diagnostics](docs/WINDOWS_STARTUP.md).
`f3_overlay` controls Retina's F3 information on the local modded client. Servers
ignore this display preference and continue supplying telemetry to clients.

YAML uses the same keys and values. To use it, replace the TOML file with
`config/retina.yaml` or `config/retina.yml`:

```yaml
mca_parallelism: -2
mca_compression: "zlib"
debug: false
f3_overlay: true
```

If several files exist, selection order is TOML, YAML, then YML. Missing settings
use their defaults; existing files and comments are never rewritten. An invalid
selected file reports its path and parse/setting error instead of silently using
defaults. The settings model and codec interface have no loader dependencies;
a future Bukkit adapter can select its own file location and YAML codec.

## Generated world features

| Area | Current implementation |
| --- | --- |
| Terrain and biomes | GPU density programs, registered noise and splines, climate intervals, biome distribution, rivers and globally aligned interpolation fields |
| Superflat | Native ordered layers, selected biome, adjusted feature/lake options, structure overrides, void platform, and shared preview, region and lighting pipeline |
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

After packs resolve, Retina adapts the effective supported generator before the
server constructs its chunk system. The original generator definition remains in
the save, including flat-world metadata and customization. Its biome source,
noise settings, bounds and effective feature settings are compiled once into
`<world-root>/retina.dat`. Even that first startup reopens the published file
through Rust. Later startups read it directly, so changing datapacks does not
silently change saved generation rules. Runtime registries resolve the saved
identifiers; missing required types cause concrete binding errors.
Single Biome and Superflat retain exactly their selected biome. Normal, Large
Biomes, Amplified and Terralith retain their actual source biome pools and noise
programs. This applies to both loaders and integrated or dedicated servers.

Superflat fills constant layers in Rust without density evaluation, then uses
the shared feature, lighting, NBT, compression and publication machinery.
Non-motion-blocking layers that Minecraft defers to fill-layer features are
applied after decoration, preserving existing non-air feature blocks. Air,
fluids, empty worlds and dimension clipping are supported. Feature/lake switches,
global structure generation and absent versus empty structure overrides are
preserved. Optional features and structures retain Retina's approximation and
coverage limits; they do not invoke vanilla terrain generation.

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
- Legacy Retina definitions that import additional biomes can assign an
  approximate temperature/rainfall niche to them. Automatically adapted worlds
  use only the selected source's biomes. Missing placement data cannot be
  recovered from a biome name or tag alone.
- Structures support compatible random-spread/jigsaw sets and the procedural
  families listed above. Nether bastions, fortresses, fossils and Nether ruined
  portals are supported. Other assemblers such as strongholds, mineshafts,
  mansions, monuments, end cities, shipwrecks and Overworld ruined portals remain
  unsupported.
- Existing saved blocks and player edits are preserved. Generation changes affect
  new terrain; old saved settings retain their legacy behavior where encoded,
  including worlds without the newer density-composition flag.
  Existing worlds can have seams where saved terrain meets newly generated Retina
  terrain. Nether storage retains its full build height, including builds above
  the roof; noise generation uses the source's separate terrain bounds.

**The world-type selector contains Minecraft's ordinary choices.** Legacy Retina,
Bend and per-chunk preset identifiers and codecs remain readable. Existing Retina
generators are not wrapped again, and retain encoded settings. Unsupported
generator families pass through only because an adapter is not yet implemented;
initialization or generation errors in supported Retina worlds surface normally.

Existing non-Retina saves also activate Retina for newly generated supported
terrain. Saved chunks and player edits remain intact. Transitions from vanilla,
older Retina output or changed datapacks can produce terrain seams.

## Region generation and parallelism

One MCA region holds **32 × 32 chunks**: 1,024 chunks covering 512 × 512 columns.
Retina defaults to **up to eight native region generations globally**. The
`mca_parallelism` limit is shared fairly between ordinary loads, Distant Horizons
and Chunky. Requests for
the same region share preparation. Jobs share the existing GPU submission
slots and Rust worker pool; increasing the limit does not create separate pools.

Fresh-region preparation happens **outside Minecraft's serialized I/O queue**.
Ready reads can proceed while other regions generate. The pipeline:

1. Rust opens the world's saved `retina.dat` and binds its dimension profiles.
   Java compiles it only when absent, then reopens that file. GPU programs and
   supporting fields remain resident.
2. GPU passes evaluate density, climate, surface materials, cave biomes, carving,
   aquifers and lake fields. Sparse batches handle supported feature counts,
   provider noise and ore preparation. Rust consumes compact masks and material
   runs instead of receiving intermediate density grids.
3. Rust performs ordered feature replay, jigsaw planning, block assembly,
   heightmaps and metadata. Shared scratch storage and batched/resumable
   queries reduce allocation and repeated work. The region assembly pool defaults
   to available parallelism capped at 16 threads; `RETINA_REGION_THREADS` can
   select a smaller runtime pool.
4. GPU lighting uses the final blocks. On Metal, the same upload also supplies
   [final section palettes and packed block data](docs/GPU_SECTION_PALETTES.md);
   large palettes and other backends retain CPU encoding. Ready tiles feed
   parallel NBT encoding and the configured zlib or LZ4 compression with reused buffers.
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
intersecting MCA regions **ahead of Chunky's chunk requests**. Region jobs remain
in flight up to the configured limit (eight by default), refilling when publication finishes.

Chunky continues to load/finalize chunks and report its own progress. Pause,
cancellation or task exit stops new ahead admissions; already-admitted jobs drain.
No extra command or launch flag is required. Chunky's reported chunks/sec can
remain load-bound even when Retina has already generated the terrain to disk.

The shared configured allowance also applies when Chunky is absent or paused.
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
The [combined optimization report](docs/OPTIMIZATION_INTEGRATION_2026_10.md) compares
the accepted changes against the starting lit-MCA baseline on Apple M4 Max / Metal:

| Workload | Native throughput change | Logical transfer MiB/region, before → after |
| --- | ---: | ---: |
| Vanilla | +7–9% | 393 → 191 |
| Terralith | +25–32% | 387 → 203 |

These are two matched, reversed-order twelve-region comparisons with two CPU
workers/two callers and optimization-level-2, non-LTO native comparison builds.
All candidate chunk NBT matches the baseline. Rates include native MCA writing,
exclude Minecraft loading/rendering, and are measured from batch wall time.
Process peak memory is slightly higher in some runs; the report includes full
latency, memory, build and hardware limits. Historical stage gains are not added
together to produce these results.

The accepted changes [compact placement programs](docs/COMPACT_DECORATION_PROGRAMS.md),
[stream candidates](docs/STREAMED_DECORATION_CANDIDATES.md), run eligible complete
[registered vine programs on the GPU](docs/GPU_VINE_PLANNING.md), and compact
[lighting inputs](docs/COMPACT_LIGHT_INPUT.md) and
[constant light outputs](docs/COMPACT_LIGHT_OUTPUT.md). Unsupported feature programs
retain exact CPU continuation. The current mixed shader path remains faster than
forced interpretation in the measured Terralith diagnostic.

[GPU section palettes](docs/GPU_SECTION_PALETTES.md) subsequently reduced NBT
worker time by about half. Two matched eight-caller vanilla comparisons improved
native throughput 9–10% on Metal, at approximately 23 MiB more logical readback
per region. Two-caller results were mixed; these gains are separate from the
earlier table. `RETINA_GPU_PALETTES=0` restores CPU palette encoding.

[Early decoration suspension](docs/EARLY_DECORATION_SUSPENSION_TRIAL.md) reduced
speculative CPU work but did not reliably improve full generation, so the
existing suspension policy remains enabled.
[Bounded replay batches with immutable substrate reuse](docs/READY_DECORATION_BATCHES.md)
subsequently improved two matched eight-caller Terralith runs by 1.3–4.9%.
They are enabled for Metal profiles with eligible GPU vine programs; vanilla
profiles without those programs retain whole-field scheduling because forced
batching regressed a two-caller comparison. The source-root dependency planner
is unchanged. Cache storage shares the existing retained-state budget.
[Predecoded placement evaluation](docs/COMPILED_PLACEMENT_EVALUATION_TRIAL.md)
was implemented and rejected after repeated vanilla throughput regressions;
the existing evaluator remains in production. Ordered-plan benchmark exports
now include tick IDs as well as block writes.
The [post-investigation integration report](docs/POST_INVESTIGATION_INTEGRATION.md)
checks the combined retained changes with two reversed-order eight-caller pairs.
Vanilla throughput improves **3.1–6.1%**; Terralith is **−2.4% / +4.2%**, so its
combined throughput result is inconclusive. NBT worker time is approximately
halved. Logical transfers increase by about 23 MiB/region for GPU palettes,
and peak process memory varies in both directions. These current-source
counterfactual comparisons use normal optimization-level-3/thin-LTO builds and
are separate from the earlier table and individual component measurements.
The final audit includes 40,960 complete NBT comparisons, exact ordered plans,
current lifecycle tests, and explicit deadline limits for the full Terralith
specialization/analytic-shore checks.
The [completed workstream tracker](docs/POST_INVESTIGATION_OPTIMIZATIONS.md)
separates shipped improvements from implemented, tested rejections.

The [cycle tracker](docs/OPTIMIZATION_CYCLES_2026_10.md) also records experiments
that were not enabled: [material arenas](docs/MATERIAL_ARENA_TRIAL.md),
[ore-mask compaction](docs/ORE_WORD_FLAGS_TRIAL.md), and
[section/NBT metadata reuse](docs/SECTION_ENCODING_TRIAL.md). Their smaller buffers
or faster isolated stages did not establish a reliable complete-generator gain.
Current validation covers Minecraft lighting/NBT reference behavior, DH-style
preview/promotion, saved edits and partial saves, real Chunky selection/I/O,
eight-region admission, and F3 accounting. No live-game throughput or other-GPU
speed claim is made. Earlier evidence remains in the
[performance report](docs/PERFORMANCE_PASS_2026_10.md) and
[starting triage](docs/PERFORMANCE_TRIAGE_2026_10_18.md).

The [Nether baseline](docs/NETHER_PERFORMANCE.md) measures full native generation,
lighting and durable MCA publication at 91.6 and 90.9 chunks/s in two matched
20,480-chunk runs on an M4 Max, with 4.56 GiB peak process RSS. Complete chunk
NBT matches across both runs. This is separate from the historical Overworld
optimization comparisons and excludes live Minecraft loading and rendering.

## Building and testing

### Releases

GitHub Actions runs only when the `release` branch advances. Pushes to `main`,
feature branches and pull requests do not run CI. To release the latest pushed
`main` revision, run:

```sh
./gradlew releaseRetina
```

The task is defined in [gradle/release.gradle](gradle/release.gradle). It fetches
`origin/main` and pushes that revision to `origin/release`, creating the release
branch on its first invocation. It does not check out a branch, commit local
changes or build locally. Git rejects a divergent release branch normally; there
is no force push. Running it again without new main commits does not start a new
release. Merge your changes into main through the normal PR workflow first.

The [release workflow](.github/workflows/release.yml) builds every supported
Minecraft target on macOS, including Fabric, NeoForge, Bukkit and all six native
libraries (macOS/Linux/Windows, each x64/ARM64) inside each installable JAR. It
runs the normal `build` checks, inspects the built JAR contents, and publishes all
variants together in one GitHub release. The plugin also receives matching
catalog assets, descriptor and private runtime dependencies. Sources, probe plugins and helper JARs are
not release assets. This packages the supported native backends; the runtime
validation limits described above still apply.

The distribution version is `0.<git rev-list --count HEAD>`: 207 reachable commits produce
version `0.207`, tag `v0.207`, and files such as
`retina-fabric-26.3-0.207.jar`. Actions checks out the exact release commit with
full history, and all targets use that count. Local builds use the same rule;
use a full Git clone for matching version numbers. No version-bump commit is
needed. Avoid GitHub's `[skip ci]`/`[ci skip]` markers in commits being released;
main no longer needs those markers.

Supported versions live in [gradle/minecraft](gradle/minecraft). The current
target is **26.3**. Each `<minecraft-version>.properties` file pins its matching
loader/API dependencies and automatically becomes a release-matrix entry. After
porting the shared source to another Minecraft version, add its property file;
`-Pminecraft_version=<version>` selects it locally. The default development
version and build-plugin versions remain in `gradle.properties`.

### Local builds

Gradle's native tasks use **two Cargo jobs**, two code-generation units and lower
process priority (`nice +10` on macOS/Linux). Gradle defaults to two workers with
parallel project execution disabled. These compile limits are separate from the
runtime region/assembly limits. The C compiler builds vendored libdeflate; no
separate libdeflate runtime installation is needed.

Use `./gradlew build -PretinaHostOnly` for local mod and plugin JARs. A full `build`,
`assemble`, or Bukkit `shadowJar` also packages cross-compiled libraries according
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
| `configurationTest` | TOML/YAML loading, defaults, invalid input and CPU-relative parallelism |
| `regionPreparationTest`, `generationMetricsTest` | Scheduling, leases, configured region limits, ten-second throughput, F3 formatting and packet transport |
| `test` | Fabric loader/mixin tests; use `:fabric:test --tests ...` to select a fixture |
| `gpuTest` | Native/GPU concurrency, coordinate and interpolation checks, materials, aquifers and related shader fixtures |
| `regionTest`, `previewTest` | Real MCA decoding, partial saves, saved edits, temporary cache and promotion |
| `netherCompatibilityTest` | Five fixed Nether biomes and climate-source previews, decorated block parity, partial-save repair and an exported runtime datapack fixture |
| `flatTest` | Native built-in/custom layers against Minecraft, flat bounds/options, lakes and void platform, previews/promotion, heightmaps and reference lighting |
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
- `core/`: dependency-free JDK native bridge and saved-definition ownership.
- `fabric/`, `neoforge/`: loader adapters, development runs and distributable JARs.
- `bukkit/`: public Bukkit plugin, bundled catalogs and disposable server observer.
- `assets/world-definitions/`: versioned reusable catalogs and reproducible manifests.
- `native/`: the shared Rust engine, WGSL shaders, native tests and benchmarks.
- `gradle/`: shared native build, packaging and verification tasks.
- `qa/`, `docs/`: fixtures, validation procedures and detailed implementation reports.

Manual changes follow [the PR/worktree protocol](docs/MANUAL_CHANGE_PROTOCOL.md).

## License

Copyright (c) 2026 Arcane Arts.

Retina is licensed under the GNU General Public License version 3 only
(`GPL-3.0-only`). See [LICENSE](LICENSE) for the full terms. Third-party components
retain their respective licenses and notices.
