# Retina

**Retina accelerates Minecraft world generation with a native Rust engine,
preferring your GPU and automatically using CPU when needed.** It generates terrain, biomes, supported decorations and structures in
large batches so Minecraft can spend more time loading and displaying the world.

Retina currently targets **Minecraft 26.3** and **Java 25**. The
[latest release](https://github.com/ArcaneArts/Retina/releases/latest) contains
**one universal JAR** for Fabric, NeoForge, Bukkit servers and command-line generation.

Retina is a generator, not a rendering mod. It does not promise higher FPS or
identical vanilla terrain for a given seed. Overworld and Nether noise generation
is approximate, and arbitrary mod or datapack worldgen code is not automatically
supported. Existing saved chunks and player edits are preserved; newly generated
terrain can have seams where it meets terrain from a different generator.

[Support](#support-at-a-glance) · [Install](#install-and-play) ·
[Datapacks](#use-a-custom-datapack-on-a-server) · [Configuration](#configure-retina) ·
[Headless generation](#generate-offline-from-the-terminal) · [Development](#build-and-develop)

## Support at a glance

| Installation | How Retina is selected | Requirements / limits |
| --- | --- | --- |
| Fabric client or dedicated server | Automatically accelerates supported world generators | Fabric Loader 0.19.5+, Fabric API, Java 25 |
| NeoForge client or dedicated server | Automatically accelerates supported world generators | NeoForge 26.3.0.51-beta+, Java 25 |
| CraftBukkit, Spigot or Paper server | Select `Retina` in Bukkit's world-generator configuration | Matching 26.3 server, Java 25; public Bukkit API limitations below |
| Headless command line | Run the universal JAR with `java -jar` | Java 25; no Minecraft installation or mod loader needed |
| Other Minecraft versions | Not currently supported | Require a separate port |

The modded client overlay is optional for server play; generation runs on the
server. Other installed mods or datapacks may impose their own client requirements.

| System | Packaged architectures | GPU backend |
| --- | --- | --- |
| macOS | ARM64 | Metal or native CPU |
| Windows | x64 | DirectX 12, Vulkan or native CPU |
| Linux | x64 | Vulkan or native CPU |

Packaged natives do not mean every device/driver combination has been tested.
A usable permitted GPU and working drivers enable GPU acceleration. Without one,
Retina uses native CPU generation, including on dedicated servers. Disabling all
three graphics APIs also selects CPU and keeps Retina active. CPU execution needs
no graphics driver or software graphics renderer. OpenGL/OpenGL ES is not included.

| World generation | Fabric / NeoForge | Bukkit |
| --- | --- | --- |
| Normal Overworld, Nether and End | Automatic | Bundled vanilla definition, selected per world |
| Large Biomes, Amplified, Single Biome, Superflat | Supported adapters | Requires a matching imported definition; Bukkit preset strings do not select Retina presets |
| Datapack generation | Captured automatically on first world creation, within adapter coverage | Import a matching mod-created `retina.dat` |
| Custom dimensions / mod worldgen | Only compatible generator families and supported rules | Also requires host-resolvable block states, biomes and matching dimension geometry |
| Structures and generation metadata | Supported native structures and Minecraft storage integration | Structures disabled in the default catalog; public API cannot faithfully deliver all metadata |
| Unsupported generator families | Keep their original generator and report the missing adapter | Not automatically translated |

End terrain, features and cities are supported; Minecraft retains dragon-fight
progression and gateway destination selection. Feature coverage is not universal:
unsupported primitives, features and structure processors are reported. See
[feature coverage](docs/GENERATION_GOAL_AUDIT.md),
[Nether implementation](docs/NETHER_IMPLEMENTATION.md) and
[End implementation](docs/END_IMPLEMENTATION.md).

## Install and play

### Fabric or NeoForge

1. Install the matching Minecraft 26.3 loader and Java 25.
2. Put the release `retina-26.3-<version>.jar` in `mods/`.
   Fabric also needs Fabric API; NeoForge does not need Fabric API.
3. Add `--enable-native-access=ALL-UNNAMED` to the launcher's JVM arguments.
   For NeoForge, also add `--illegal-native-access=allow`.
4. Create a world using Minecraft's usual world types and settings. There is no
   special Retina world type to select. Enable any datapacks before creating it.

For a dedicated server, install the same JAR in the server's `mods/` directory.
Use normal `level-type`, `level-seed` and `generator-settings` values in
`server.properties`. Accept Minecraft's EULA separately and start the server
using its loader's launch script, with the JVM arguments above.

Retina also activates for new terrain in existing supported worlds. Back up an
existing world before changing its generator or generation inputs.

### Bukkit / CraftBukkit / Spigot / Paper

1. Put the same release JAR in `plugins/`.
2. Use Java 25 and add `--enable-native-access=ALL-UNNAMED` to the server JVM.
3. Set your seed in `server.properties` and select Retina in `bukkit.yml` before
   creating the worlds. For `level-name=world`:

```yaml
worlds:
  world:
    generator: Retina
  world_nether:
    generator: Retina
  world_the_end:
    generator: Retina
```

For normal vanilla settings, no modded installation or export step is needed.
Retina creates a seed-specific definition from its bundled vanilla data.

This is a regular public-API plugin, including on CraftBukkit. It does not use
NMS acceleration or a launch wrapper. Bukkit owns lighting, saving and normal
runtime gameplay. The default `bukkit-api` catalog disables structures and
scheduled spring-fluid roots because the API cannot faithfully deliver their
metadata. Imported snapshots can encounter additional delivery limitations.

See the [Bukkit guide](docs/BUKKIT.md) for world mappings, tested server builds,
metadata support, caching and troubleshooting. Restart the server after changing
the plugin or its configuration; hot reload is not supported.

## How generation works

1. **Capture the world once.** Retina creates `<world-root>/retina.dat`, a
   compressed NBT definition containing the seed and supported generation rules
   for all dimensions. Eligible normal vanilla worlds use data bundled in the
   JAR; modded or datapack worlds use the loaded game's registries.
2. **Generate from that file.** Rust reads the saved definition. GPU or native CPU
   execution handles density, climate, surfaces and other calculations; Rust handles
   feature planning, block assembly and metadata. CPU, GPU and disk all matter.
3. **Deliver chunks to the host.** The mod/headless path writes standard Anvil
   region files (`.mca`). Bukkit delivers blocks and supported metadata through
   its API and lets the server light and save them.

A region is **32 × 32 chunks**, or **512 × 512 blocks** horizontally. The mod
integration prepares regions in batches, including terrain outside your immediate
view. It preserves existing chunk records when publishing into partial saves.
Fresh complete mod/headless regions can prelight their interior; Minecraft still
handles boundary lighting and later block changes.

`retina.dat` is the saved generation definition, not a disposable cache. Keep it
with world backups. Existing definitions remain authoritative after restarts,
Retina upgrades or datapack changes. Replacing or deleting one can change future
terrain; it does not regenerate existing chunks. See the
[world-definition format and lifecycle](docs/WORLD_DEFINITION.md).

## Use a custom datapack on a server

### Fabric / NeoForge server

For a new world, place the pack ZIP or directory under the intended
`<level-name>/datapacks/` folder **before the first world startup**, and ensure
Minecraft enables it. Packs requiring a custom preset or dimension configuration
must use their documented Minecraft setup as well.

For example, with `level-name=world`:

```text
server/
  mods/retina-26.3-<version>.jar
  world/datapacks/my-worldgen-pack.zip
  server.properties
```

Start the server with Retina installed. Minecraft loads the packs, then Retina
captures supported rules into `world/retina.dat` automatically. There is no
export command to run. Check the startup log for definition creation and any
unsupported worldgen reports before committing to a long-lived world.

You can also create a world in a matching Fabric/NeoForge client, enable packs
in the world-creation screen, then stop the game and transfer the complete save
(including `retina.dat` and `datapacks/`) to a matching server.

**Adding a pack to a world that already has `retina.dat` does not update its saved
generation rules.** Test a new world for a new pack set. Keep packs installed
where Minecraft needs their biome/dimension registries, loot, functions or other
runtime content; the definition replaces generation input, not every datapack
feature.

### Bukkit server with a custom definition

Bukkit cannot export the same worldgen registry data through its public API.
Use a matching modded installation to prepare it:

1. Create a temporary Fabric or NeoForge world with the same Minecraft/Retina
   version, intended seed, datapacks, preset and dimension settings.
2. Let Retina create `retina.dat`, inspect compatibility messages, then stop the
   game/server. For strict Bukkit delivery, disable structures and bonus chest;
   other unsupported metadata may still need to be addressed.
3. With the Bukkit server stopped, copy that file to the target **logical world
   root** before its first generation. For standard `level-name=world` mapping,
   that is `world/retina.dat`; the three standard dimensions share it.
4. Install the datapacks needed by the Bukkit server to register the referenced
   biomes/dimensions and runtime content. Set the exact same seed and matching
   bounds, select Retina as shown above, then start the server.

The server must resolve every referenced block state and biome. A definition
cannot make mod-only blocks available on Bukkit. Other world names or custom
layouts need explicit mappings in `plugins/Retina/config.yml`; see the
[Bukkit files and dimensions guide](docs/BUKKIT.md#files-and-dimensions).

Strict metadata delivery is the default. `allow-metadata-loss: true` explicitly
allows reported omissions from imported snapshots, but does not provide full
structure gameplay or raw chunk-NBT parity. It does not fix missing block/biome
registries or incompatible world geometry. Test custom packs on disposable worlds
first; a successful export is not a guarantee that every pack feature is supported.

## Configure Retina

### Fabric / NeoForge settings

Retina creates `config/retina.toml` on first startup. Edit it and restart:

```toml
allow_directx = true
allow_metal = true
allow_vulkan = true
mca_parallelism = 8
mca_compression = "zlib"
debug = false
f3_overlay = true
```

| Setting | Default | What it controls |
| --- | --- | --- |
| `allow_directx` | `true` | Allow DirectX 12 when selecting a GPU backend |
| `allow_metal` | `true` | Allow Metal |
| `allow_vulkan` | `true` | Allow Vulkan |
| `mca_parallelism` | `8` | Maximum simultaneous native region generations across ordinary requests, DH, Chunky and repairs |
| `mca_compression` | `"zlib"` | New chunk compression: `zlib` for smaller files, `lz4` for faster compression with larger files |
| `debug` | `false` | Detailed startup, driver and generation diagnostics; preparation progress and errors remain visible when off |
| `f3_overlay` | `true` | Retina statistics on the local modded client's F3 screen |

Leave several backends allowed for automatic selection, or allow just one to
choose it. For example, Windows DirectX-only means `allow_directx = true` and
the other two `false`. Retina uses its native CPU backend if no usable permitted
hardware GPU can initialize. Set **all three to `false`** to select CPU directly,
without graphics initialization. Supported worlds keep using Retina in both cases.
CPU and GPU numerical output may differ; switching execution backends can create
terrain discontinuities where new chunks meet existing chunks. Saved definitions
and existing chunks remain authoritative.

For `mca_parallelism`, positive values are fixed limits; `0` and `-1` use Java's
available CPU count; other negative values divide that count by their absolute
value, rounded up. For example, `-2` on 16 processors permits eight region jobs.
More concurrent jobs do not guarantee more throughput and can increase resource
pressure. Compression changes affect new records, not existing chunks.

YAML is also supported at `config/retina.yaml` or `config/retina.yml`, using
`key: value` syntax. If multiple files exist, priority is TOML, YAML, then YML.
Missing settings use defaults; invalid selected files report an error.

For either headless interface, `RETINA_GPU_BACKEND=cpu` explicitly selects CPU.
The executable JAR also accepts `java -Dretina.gpuBackend=cpu -jar retina.jar ...`.
Other override values are `auto`, `vulkan`, `dx12`, and `metal`. Overrides respect
the allowed API set. Invalid configuration and shader errors still report errors.
See [CPU backend implementation and validation](docs/CPU_BACKEND.md).

### Bukkit settings

Edit `plugins/Retina/config.yml` and restart. The three `allow_*` flags and
`debug` work here too. With all graphics APIs disabled, Retina keeps its generator
and biome provider active and uses native CPU execution.

Bukkit has its own delivery/cache settings; the mod's MCA parallelism,
compression and F3 settings do not configure Bukkit's server-owned chunk saves.

| Setting | Default | Purpose |
| --- | --- | --- |
| `catalog-variant` | `bukkit-api` | Rules used when creating a missing definition |
| `chunk-cache` | `64` | Complete chunks retained per bound dimension |
| `biome-cache` | `256` | Biome cache capacity per bound dimension |
| `biome-disk-cache` | `16384` | Persistent sparse biome tiles; `0` disables this cache |
| `concurrent-loaders` | `4` | Concurrent loaders per cache |
| `allow-metadata-loss` | `false` | Explicitly permit reported metadata omissions in imported definitions |
| `worlds` | `[]` | Explicit world → logical root → dimension mappings |

See the [shipped Bukkit configuration](bukkit/src/main/resources/config.yml).

## Distant Horizons, Chunky and performance

On Fabric/NeoForge, Distant Horizons preview requests share Retina's region
pipeline. Temporary generated regions can later be promoted into the real save
without generating them again. Chunky has a region-ahead scheduler that feeds
Retina while Chunky continues loading/finalizing chunks. Neither mod is required.
See [Chunky scheduling](docs/CHUNKY_REGION_SCHEDULING.md) for integration details.
Verified Chunky releases can also use the bundled vanilla definition; see
[supported artifacts and datapack fallback](docs/CHUNKY_VANILLA_CATALOG.md).

Press **F3** on a modded client to see Retina's backend, region latency,
completed-terrain throughput, in-flight jobs and stage timings. Completed terrain
is newly generated chunks over a ten-second wall-clock window. Cached/promoted
terrain is not counted again. Region latency and Minecraft's loaded/rendered
chunks per second measure different things, so they need not agree with Chunky
or DH's counters.

GPU startup includes pipeline preparation. Progress logs show completed
and pending work; a waiting driver call does not have a reliable percentage or
ETA. If startup stalls, enable `debug`, restart, and include the complete log,
Retina version, GPU and selected backend in your report. See
[Windows startup diagnostics](docs/WINDOWS_STARTUP.md).

Performance depends on the world rules, GPU backend/driver, CPU, disk and how
much work is queued. For comparisons, use the same seed, packs and workload,
and check power-saving modes; macOS Low Power Mode can substantially reduce
throughput. Published native benchmarks exclude Minecraft loading/rendering:
see [performance evidence](docs/POST_INVESTIGATION_INTEGRATION.md) and
[MCA compression measurements](docs/MCA_COMPRESSION.md).

## Generate offline from the terminal

The universal JAR is also a headless generator. These commands require Java 25;
`init` and `inspect` need no generation backend. `generate` selects GPU or native
CPU automatically; use `-Dretina.gpuBackend=cpu` to select CPU explicitly:

```sh
java -jar retina-26.3-<version>.jar init --world ./world --seed 12345
java -jar retina-26.3-<version>.jar inspect --world ./world
java -jar retina-26.3-<version>.jar generate --world ./world \
  --dimension minecraft:overworld --region-radius 1
```

Radius 1 selects a 3 × 3 square of regions centered at 0,0. For custom generation,
use a mod-created definition instead of `init`:

```sh
java -jar retina-26.3-<version>.jar generate --world ./output \
  --definition /path/to/retina.dat --dimension minecraft:the_end \
  --region-min=-1,-1 --region-max=1,1
```

Both ends of the range are inclusive. Use an empty output directory or matching
world data, with the server stopped. Existing records are preserved. This writes
generation data and region files, **not `level.dat` or a complete playable save**.
Run `--help` for options. See [headless JAR usage](docs/UNIVERSAL_RELEASE.md#executable-headless-jar)
and [the standalone Rust runner](docs/WORLD_DEFINITION.md).

## Build and develop

For local development, install JDK 25, Rust/Cargo 1.87+ and a platform C compiler
(Clang, GCC or MSVC). Use `gradlew.bat` instead of `./gradlew` on Windows.

| Command | Result |
| --- | --- |
| `./gradlew runClient --no-watch-fs` | Fabric development client |
| `./gradlew runServer --no-watch-fs` | Fabric dedicated development server |
| `./gradlew runNeoForgeClient --no-watch-fs` | NeoForge development client |
| `./gradlew runNeoForgeServer --no-watch-fs` | NeoForge development server |
| `./gradlew build -PretinaHostOnly --no-watch-fs` | Local mod/plugin builds with this host's native library |
| `./gradlew buildBukkit -PretinaHostOnly --no-watch-fs` | Bukkit development plugin |
| `./gradlew universalJar -PretinaHostOnly --no-watch-fs` | Universal JAR for this host |
| `./gradlew publish --no-watch-fs` | Advance the release branch to fetched `origin/main` and trigger GitHub Actions |

Development module JARs are separate from the universal release artifact. The
release workflow builds the universal distribution with all six native targets,
checks it on the platform runners and publishes the JAR. Its version is
`<major>.<minor>.<Git commit count>`, with major/minor from `version.json`.
The developer Windows GPU probe is not a regular release asset.

See [universal builds and release instructions](docs/UNIVERSAL_RELEASE.md),
[loader runtime QA](docs/RUNTIME_QA.md), and
[the manual PR workflow](docs/MANUAL_CHANGE_PROTOCOL.md).

| Source directory | Responsibility |
| --- | --- |
| `common/` | Shared Minecraft integration and registry export |
| `core/` | JDK native bridge, definition ownership and CLI |
| `fabric/`, `neoforge/` | Mod loader adapters |
| `bukkit/` | Public-API server plugin |
| `native/` | Rust generation engine, shaders and region writer |
| `data/` | Vanilla catalog generation |
| `universal/` | Combined release JAR |

[Report an issue](https://github.com/ArcaneArts/Retina/issues) with your version,
loader/server, OS, GPU/backend, relevant packs and full log.

## License

Copyright (c) 2026 Arcane Arts.

Retina is licensed under the GNU General Public License version 3 only
(`GPL-3.0-only`). See [LICENSE](LICENSE) for the full terms. Third-party components
retain their respective licenses and notices.
