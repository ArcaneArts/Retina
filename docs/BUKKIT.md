# Bukkit plugin

The Bukkit target is a regular JavaPlugin using public Bukkit APIs. It does not
load Minecraft classes, CraftBukkit internals, a mod loader, or mixins. It shares
Retina's Rust engine and JDK-only `core` runtime with the other distributions.

## Build and install

```sh
./gradlew :bukkit:build
./gradlew buildBukkit
./gradlew :bukkit:build -PretinaHostOnly
```

The installable file is `bukkit/build/libs/retina-bukkit-<minecraft>-<version>.jar`.
The sources JAR is not a plugin. Universal builds use the existing six-platform
native packaging pipeline; `retinaHostOnly` packages the current host's library
for development. Ordinary builds consume committed vanilla catalogs without
launching Minecraft or regenerating data.

The release workflow builds and stages Bukkit alongside Fabric and NeoForge.
`scripts/stage-release.py <minecraft> <version>` reads each built JAR's six native
entries and checks the plugin's actual descriptor, runtime classes, catalog bytes
and manifest hash. Helper/probe JARs and bundled server/API classes are excluded.
The tested universal 0.399 build produced a 28,212,017-byte plugin; Mach-O, ELF and
PE images were inspected for both architectures. Its packaged host library
generated all three dimensions on a JDK-only classpath without Minecraft or an
external native override. Foreign images were cross-compiled, not GPU-tested.

Use Java 25, a supported GPU/driver, and enable FFM native access:

```sh
java --enable-native-access=ALL-UNNAMED -jar server.jar --nogui
```

Place the plugin in `plugins/`, then select it in `bukkit.yml` before creating
the worlds. For `level-name=world` in `server.properties`:

```yaml
worlds:
  world:
    generator: Retina
  world_nether:
    generator: Retina
  world_the_end:
    generator: Retina
```

Set the desired seed through the server's ordinary configuration or a public
`WorldCreator`. No export, external catalog, or modded installation is needed
for the bundled normal vanilla settings. Selecting Retina for an existing
world affects future chunks; it does not convert already generated terrain.

## Files and dimensions

On the first generation-dependent callback, including biome enumeration, the
plugin attempts to open the logical root's `retina.dat`. Only actual absence
selects a bundled catalog, materializes the world's complete 64-bit seed,
atomically publishes the file, and reopens it with the normal Rust loader.
Present files bypass materialization. Malformed files and incompatible seeds,
bounds, block states, or biomes produce concrete errors without replacement.

The three standard worlds named by the actual `server.properties` level-name
share one definition in the Overworld root. Their mappings are saved explicitly
in `plugins/Retina/worlds.yml`. Other selected worlds default to independent
logical roots; names are not grouped by removing suffixes. Override mappings
in `plugins/Retina/config.yml`, for example:

```yaml
worlds:
  - world: adventure
    root: adventure
    dimension: minecraft:overworld
  - world: adventure_hell
    root: adventure
    dimension: minecraft:the_nether
```

Roots resolve against Bukkit's world container; absolute roots are supported.
These are logical definition locations. Paper 26.3 can store multiple Bukkit
worlds beneath one physical level folder; independent definition roots still
remain separate, as recorded by the explicit mappings.
When the server uses a custom properties-file path or overrides the level name
on its command line, configure these mappings explicitly; automatic grouping
reads the standard `server.properties`.

An explicit generator ID `Retina:overworld`, `Retina:nether`, or `Retina:end`
must agree with the saved mapping. Additional saved dimension identifiers can
be mapped explicitly where the host's actual seed and storage geometry match.

To import mod-created data, copy the complete `retina.dat` into the logical
root before creating/loading the host worlds. Use its saved seed and matching
dimension bounds. Public registry resolution must support its canonical block
states and biomes. The bundled catalog cannot discover server datapack changes;
custom generation rules require a matching mod-created snapshot. Catalog and
plugin upgrades do not change an existing definition. The default catalog supports
normal vanilla settings. `WorldCreator.type()` and generator-settings strings do
not select a different Retina preset: use an exact supplied snapshot for those
rules. A snapshot with bonus-chest setup or unsupported structure metadata is
rejected in strict mode; export a compatible definition with those features
disabled, or explicitly choose the documented degraded mode.

## Delivery policy

New worlds default to the `bukkit-api` catalog variant. It disables structures
and decoration roots requiring scheduled spring fluid updates before the
definition is created, and persists those choices as executable rules and
omission reports. Full native/mod generation retains those features.

| Data | Public API behavior |
| --- | --- |
| Block states and 3D biomes | Canonical saved palettes resolved once; negative coordinates and heights retained |
| Height queries | Saved native heightmaps supply generator queries |
| Lighting, persisted heightmaps, chunk saves | Owned and recomputed by the server |
| End gateways | Age, exact teleport and optional exit position through limited-region block states |
| End crystals | Position, rotation, bottom, invulnerability and beam target; server assigns runtime UUIDs |
| Structure starts/references, locate, raw tick/post-processing queues | No faithful public delivery; strict mode reports actual unsupported requirements |
| Containers | Chest/trapped chest, barrel, shulker box, hopper, dispenser and dropper inventories through snapshot slots; item identities/counts and components through ItemFactory |
| Lazy loot | Public loot table identity and exact signed 64-bit seed; applied after inventory changes to avoid an early loot roll |
| Spawners | Spawn snapshot, weighted potentials, light rules, delays, count and range through CreatureSpawner; equipment and unknown fields are reported |
| Other entities | EntityFactory snapshot creates an unspawned entity; LimitedRegion publishes it. Physical fields are checked through public getters; snapshot-only attributes are attempted with warnings where public observation cannot verify them |
| Other block entities and opaque fields | Strict mode reports concrete unsupported requirements; no raw server NBT injection |
| Nether/End gameplay | The server owns portal routing, dimension transitions, dragon-fight state and normal ticking; these global runtime records are not imported from a generation snapshot |
| Initial vanilla terrain, surface, decorations, structures and mobs | Suppressed to prevent a second generation pass; normal runtime spawning remains server-owned |

`allow-metadata-loss: true` explicitly permits reported omissions when importing
a snapshot. This mode does not rewrite its rules or file; it places available
blocks and supported metadata while logging unsupported categories. It does
not provide complete structure gameplay or generic chunk-NBT parity. A public
ItemMeta or EntitySnapshot diagnostic string is not a complete persistence
contract; a missing field in that observation emits a warning instead of blocking
otherwise valid API delivery. Server entity UUID allocation and normal ticking
can transform metadata. Supported categories are verified with persisted and
reloaded public observations plus independent saved-region decoding; arbitrary
entity component parity is not claimed. CraftBukkit 26.3's spawner getter reports
the maximum block light as the minimum. Delivery uses the correct setters and
the acceptance test checks the actual saved nondefault range.

Generation uses bounded caches and coalesced jobs. Defaults retain 64 complete
chunks and 256 biome grids/tiles per bound dimension, with four concurrent
loaders per cache. Sparse horizontal searches use the existing native point
query path; multiple queried heights switch to full quart grids. Exact sparse
answers also have a bounded persistent backing cache under the logical root's
`retina-cache/`. The default `biome-disk-cache: 16384` retains up to 16,384
32-by-32-block tiles per dimension (4 MiB of raw biome IDs plus map/array
metadata). Set it to `0` to disable this backing cache. Files are gzip-compressed,
checked against the complete `retina.dat` and selected native image SHA-256,
and saved atomically on world save and owner shutdown. Changed identities and
invalid files use normal native queries; cache I/O failures only emit warnings.
Deleting `retina-cache/` is safe while the server is stopped. Existing hot cache
and loader limits remain unchanged. Whole callback
leases prevent native closure during delivery. Unload/disable stops admission
and drains work before releasing the logical owner. Plugin hot reload is not
supported; restart the server when updating the plugin or native runtime.
Disabling the plugin while its worlds remain loaded closes its native owners;
subsequent queued server callbacks are rejected explicitly. Stop the server
instead of disabling a live generator.

## Verification tools and current evidence

```sh
./gradlew :common:exportVanillaCatalog --no-watch-fs
./gradlew :common:worldCatalogBridgeTest -PretinaNativePath=/absolute/native/library
./gradlew :bukkit:test -PretinaNativePath=/absolute/native/library
./gradlew :bukkit:serverProbeJar -PretinaHostOnly
./gradlew bukkitServerTest -PbukkitServerJar=/absolute/path/to/server.jar
# Host-only development verification:
./gradlew :bukkit:serverTest -PretinaHostOnly -PbukkitServerJar=/absolute/path/to/server.jar
```

`serverProbeJar` creates a disposable observer plugin, separate from the release.
`bukkitServerTest` / `:bukkit:serverTest` launches disposable dedicated servers with
only the produced plugin and that observer. It tests first creation, all standard
dimensions, independent seeds, an actual mod-exported snapshot, metadata delivery,
concurrent callbacks during unload, closed native owners, stale generator
rejection, restart with materialization disabled, preserved edits and file hashes,
and five concrete startup failures without vanilla fallback. It retains console
logs, public chunk captures, parity results, RSS samples and timings under
`build/bukkit-server-test/run-*/`. Supply the server artifact yourself; it is not
bundled or downloaded by this task. Accepting the Minecraft EULA is required to
run the server; this explicitly invoked disposable test writes `eula=true` in its
own test directory.
On a disposable matching server with the three named test worlds, the console
command `retinaprobe` captures complete public-API block/biome snapshots for
independent comparison. `retinaprobe create-end` creates a separate seed-42 End;
`retinaprobe reopen-end` unloads it and reopens it with materialization disabled.
Do not install the observer on a production server.

Compare its capture with native output using `scripts/bukkit-probe.py` and the
`--probe`, `--definition`, `--library`, and `--report` paths. The comparator reads
every block and quart biome through an independent Python NBT decoder. Its
optional `--expect-test-edits` fixture expects diamond at Overworld `(1,100,1)`,
emerald at Nether `(-1,130,-1)`, and gold at End `(1,100,1)`.

Actual CraftBukkit 4664-Bukkit-e89124d, Spigot 4664-Spigot-bed90fc-e89124d,
and Paper 26.3-226 passed creation, all three dimensions, independent seeds,
mod-exported snapshot import, concurrent unload/recreation, native-owner closure,
and materializer-disabled restart. Each matched 917,504 block positions and
14,336 quart biomes across twelve snapshots with three preserved player edits.
All ten persisted End pillar crystals matched native attributes. Controlled
inventory/components, exact 64-bit loot seeds, spawner ranges, gateways and entity
attributes passed public checks and independent saved-region decoding before and
after restart. Five concrete startup failure cases per server preserved their
supplied definitions and did not fall back to vanilla generation.

The tests ran on Java 25.0.1, macOS 27.0.1 and Apple M4 Max/Metal using the same
universal JAR with no external native override. The catalog tested in that
acceptance run was 2,706,404 bytes and exported byte-identically twice. The
current 5,274,690-byte catalog also includes bonus-chest variants for the mods;
its original three variants, including `bukkit-api`, are structurally unchanged.
See [mod catalog verification](MOD_VANILLA_CATALOG.md) for the new asset checks
and [the acceptance report](BUKKIT_ACCEPTANCE.md)
for artifact hashes, exact commands, timings, sampled memory, retained evidence
and the distinction between host runtime verification and foreign cross-builds.

CraftBukkit/Spigot perform vanilla stronghold-ring biome searches even when
structures are disabled. First-time generation still pays for those exact
queries. The persistent cache avoids repeating native point queries on later
launches when the saved answers fit its capacity and the definition/native image
have not changed. See [the biome-cache report](BUKKIT_BIOME_CACHE.md) for the
before/after measurements, cache sizes and parity checks.
On Spigot, increase `settings.timeout-time` in `spigot.yml` to cover cold
generation on the host: the 60-second default can terminate valid slow
requests. The acceptance test and focused cold benchmark use 300 seconds for
that server watchdog. Both remain bounded and fail on actual callback errors. The
previously tested Paper build did not incur the long stronghold delay. These
are bounded end-to-end observations, not sustained engine rates. One-entry
saved biome palettes return their sole canonical biome directly.
