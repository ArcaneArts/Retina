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
A local universal 0.398 build produced a 28,197,199-byte plugin; Mach-O, ELF and
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
An explicit generator ID `Retina:overworld`, `Retina:nether`, or `Retina:end`
must agree with the saved mapping. Additional saved dimension identifiers can
be mapped explicitly where the host's actual seed and storage geometry match.

To import mod-created data, copy the complete `retina.dat` into the logical
root before creating/loading the host worlds. Use its saved seed and matching
dimension bounds. Public registry resolution must support its canonical block
states and biomes. The bundled catalog cannot discover server datapack changes;
custom generation rules require a matching mod-created snapshot. Catalog and
plugin upgrades do not change an existing definition.

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
| Other block entities, inventories, loot, spawners or entity payloads | Strict mode reports unsupported data; no arbitrary NBT injection |
| Initial vanilla terrain, surface, decorations, structures and mobs | Suppressed to prevent a second generation pass; normal runtime spawning remains server-owned |

`allow-metadata-loss: true` explicitly permits reported omissions when importing
a snapshot. This mode does not rewrite its rules or file; it places available
blocks and supported metadata while logging unsupported categories. It does
not provide complete structure gameplay or generic chunk-NBT parity.

Generation uses bounded caches and coalesced jobs. Defaults retain 64 complete
chunks and 256 biome grids/tiles per bound dimension, with four concurrent
loaders per cache. Sparse horizontal searches use the existing native point
query path; multiple queried heights switch to full quart grids. Whole callback
leases prevent native closure during delivery. Unload/disable stops admission
and drains work before releasing the logical owner. Plugin hot reload is not
supported; restart the server when updating the plugin or native runtime.

## Verification tools and current evidence

```sh
./gradlew :common:exportVanillaCatalog --no-watch-fs
./gradlew :common:worldCatalogBridgeTest -PretinaNativePath=/absolute/native/library
./gradlew :bukkit:test -PretinaNativePath=/absolute/native/library
./gradlew :bukkit:serverProbeJar -PretinaHostOnly
```

The last task creates a disposable observer plugin, separate from the release.
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

The adapter cycle verified CraftBukkit 26.3 (4664-Bukkit-e89124d), JDK 25.0.1,
macOS/Metal on Apple M4 Max. Creation/reopen and actual forced generation of
Overworld, Nether and End chunks succeeded. Restart with materialization
disabled preserved the definition hash and three edited blocks. Twelve complete
public-API chunk snapshots matched 917,504 native blocks and 14,336 quart biomes,
apart from those three expected edits. A separate seed-42 End passed actual
unload/reopen with materialization disabled; its persisted crystal matched the
native attributes with Bukkit's equivalent normalized yaw and server-owned UUID.
The 2,706,404-byte catalog exported byte-identically twice; both earlier variants
remained structurally identical. The host-only plugin was 6,634,524 bytes.

Cold Overworld loading took about 70 seconds while the server computed distant
stronghold-ring biome searches despite structures being disabled. Loading
persistent chunks repeated that work on restart (55–70 seconds in this run).
Normal Bukkit APIs do not suppress that delegate work. These results are
preliminary; Spigot/Paper acceptance and
distribution CI integration are tracked separately before full delivery.
