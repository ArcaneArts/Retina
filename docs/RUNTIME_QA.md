# Loader runtime QA

Use a fresh, disposable run directory for each loader/mode. These opt-in checks
create a world named `retina-qa-mca` or `retina-qa-chunk`, save an edit, reopen it,
and shut down the client. Do not point them at an existing development run.

Set `RETINA_QA_ROOT` to an absolute directory outside your normal game directory.
Run each client separately:

```sh
./gradlew runFabricClient -PretinaQa -PretinaClientGameplayQa \
  -PretinaClientRunDir="$RETINA_QA_ROOT/fabric-mca"
./gradlew runNeoForgeClient -PretinaQa -PretinaClientGameplayQa \
  -PretinaClientRunDir="$RETINA_QA_ROOT/neoforge-mca"
```

Repeat with `-PretinaClientQaMode=chunk` and fresh `fabric-chunk` /
`neoforge-chunk` directories. The pass event `minecraft_client_gameplay_reopen`
follows received telemetry, F3 formatting, disconnect reset, saved generator
settings and a retained diamond-block edit at (-17,120,-17).

On macOS, the QA runs used `enableVsync:false` in `options.txt` and NeoForge
`earlyWindowControl = false` in `config/fml.toml` after an actual OpenGL stall.
These settings are confined to the isolated QA directories.

## Effective biome modifications

Package the supplied registry fixture and place it in the fresh world's datapack
directory before launch:

```sh
mkdir -p "$RETINA_QA_ROOT/fabric-registry/saves/retina-qa-mca/datapacks"
jar --create --file "$RETINA_QA_ROOT/fabric-registry/saves/retina-qa-mca/datapacks/registry.zip" \
  -C qa/datapacks/registry .
./gradlew runFabricClient -PretinaQa -PretinaClientGameplayQa \
  -PretinaClientRunDir="$RETINA_QA_ROOT/fabric-registry" \
  -PretinaClientDatapackQa=registry.zip -PretinaBiomeModificationQa \
  -PretinaExpectedFeatureQa=retina:qa_extra_grass \
  -PretinaProfileOutputQa="$RETINA_QA_ROOT/fabric-profile.json"
```

For NeoForge, use its client task and a fresh directory, omitting
`-PretinaBiomeModificationQa`: its fixture supplies a `neoforge:add_features`
biome modifier. `minecraft_effective_registry_feature` asserts that the actual
loaded biome settings and native profile both contain the added feature.
`retina.qa.profileOutput` writes the effective profile, including on reopen.

The `qa/datapacks/structures` fixture forces a registered village at chunk
(320,320) using loaded vanilla templates and a cobblestone-to-gold processor.
Place it in a fresh dedicated world's `datapacks` directory, select
`level-type=retina:gpu` (or `retina:gpu_chunk`) and seed `123456789`, and use
`-PretinaQa -PretinaStructuresQa` with the loader's server task. Add
`-PretinaStructuresReopenQa` on the second launch of the same world. The
`minecraft_live_rust_structures` check compares every block, checks decoded
native pieces and block entities, and shuts down normally after saving.

The `qa/datapacks/entities` fixture places a stone support wall with an item
frame, glow item frame and one-block painting at the same forced start chunk.
Its current 26.3 template intentionally retains distant capture-world
`block_pos` values; normalized wrapper `blockPos` coordinates supply the real
placement anchors. Put this pack alone in a fresh dedicated QA world's
`datapacks` directory and use `-PretinaQa -PretinaEntitiesQa` with the same
seed and either generation mode. Repeat with `-PretinaEntitiesReopenQa`.
`python3 qa/entity_fixture.py` regenerates its deterministic compressed NBT;
the template palette uses the current 26.3 compound `id` representation.
The `minecraft_live_template_entities` checkpoint checks actual loaded UUIDs,
attachment positions, directions and survival against the native metadata,
then saves and stops normally. The packaged equivalents are
`-Dretina.qa.entities=true` and, on reopen,
`-Dretina.qa.entities.reopen=true`. Keep entity QA separate from the structure
and timing checks, which can stop the dedicated server earlier.

## Dedicated connections and packaged artifacts

Start an isolated matching dedicated server. With a fresh client run directory,
add `-PretinaClientServerQa=127.0.0.1:<port>` to a gameplay QA client command.
The client receives a real packet, invokes the loaded F3 entry, disconnects,
checks cleared statistics, reconnects, receives fresh telemetry, then exits.
The QA follows Minecraft's quit flow by closing the connection before level
teardown, then waits for the loader's disconnect callback.
`minecraft_client_gameplay_reopen` records `dedicated:true`. Select a matching
`retinaClientQaMode` when the server uses individual-chunk generation.

Repeat these checks using the built mod jars in ordinary loader installations.
Use `retina-fabric-26.3-<version>.jar` with Fabric API, or
`retina-neoforge-26.3-<version>.jar` with NeoForge. Do not install the common
helper or either sources jar. Configure Java 25 native access as described in
the README. JVM flags for the equivalent packaged checks are:

```text
-Dretina.qa=true
-Dretina.qa.client.gameplay=true
-Dretina.qa.client.mode=mca
-Dretina.qa.client.datapack=registry.zip
-Dretina.qa.expectedFeature=retina:qa_extra_grass
-Dretina.qa.profileOutput=/absolute/path/to/profile.json
```

Fabric's modified-biome check also needs
`-Dretina.qa.biomeModification=true`. Dedicated connections use
`-Dretina.qa.client.server=127.0.0.1:<port>` and omit datapack/feature checks on
the client; validate the effective registry on the server with the existing
timing QA. Preserve logs, profiles and worlds before removing the QA directory.

The packaged connection checks used offline QA accounts on servers bound only
to `127.0.0.1`. Configure their whitelist for those QA identities or disable it
in that isolated test server; the normal account resolver can return an online
UUID that differs from the offline login UUID. Authentication and Realms errors
from the deliberately offline client account are separate from local gameplay
and payload validation.

## Distant Horizons and Chunky

The matching 26.3 fixtures use Distant Horizons 3.3.4 (its Fabric/NeoForge jar),
Chunky Fabric 1.5.3 or Chunky NeoForge 1.5.4. Install those jars alongside the
packaged Retina artifact. The extended client options
`retina.qa.client.holdSeconds` and `retina.qa.client.commands` keep each world
open while background generation runs and execute commands on the integrated
server. Commands are separated by `|`; they run once on the fresh world.

For a disposable integrated MCA QA world, use:

```text
-Dretina.qa.client.holdSeconds=15
-Dretina.qa.client.commands=gamerule random_tick_speed 0|chunky start minecraft:overworld square -1024 -1024 128
```

Quote the entire command-property argument when passing it through a shell.
The Gradle equivalents are `-PretinaClientHoldSecondsQa=15` and
`-PretinaClientCommandsQa=<commands>`. Random ticks are frozen for the terrain
snapshot comparison: otherwise lava ignition and other normal world updates
can change blocks before the saved-world comparison. Normal gameplay keeps its
normal rules. The client still checks received statistics, the saved edit,
effective modified registry and reconnect.

On the isolated dedicated server, exercise normal console commands:

```text
chunky start minecraft:overworld square -1024 -1024 256
chunky start minecraft:overworld square -8192 -8192 512
chunky pause
chunky continue
```

Wait for the first job to finish before starting the second. Verify actual
progress, paused/resumed state and completion in the log. DH should disable its
generation during Chunky work and re-enable it afterward. Confirm generated
MCA files and nonempty DH `FullData` rows in the saved SQLite database after
normal shutdown. The integrated log should also show DH world-gen workers
consuming Retina temporary MCA chunks before and after reopening.
