# Loader runtime QA

Use a fresh, disposable run directory for each loader/world type. These opt-in checks
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

The default selects `minecraft:normal` and exercises automatic activation. Repeat
with `-PretinaClientPresetQa=minecraft:flat` and fresh `fabric-flat` /
`neoforge-flat` directories. `minecraft_automatic_activation` verifies the actual
runtime generators, original saved registry definitions, flat metadata, spawn-height
semantics, distinct Overworld/Nether native profiles and the unchanged End generator
on creation and reopen. `minecraft_dimension_saved_edit` checks saved edits in both
dimensions, including a Nether build at Y=200.

The block/heightmap round-trip probe loads a chunk outside player and spawn
simulation and compares it before gameplay ticks can change it. A player chunk
can already contain flowing lava, so it is not an immutable generation reference.
Round-trip mismatches fail the run.

`./gradlew -PretinaHostOnly :common:netherConcurrencyTest` uses normal worker
counts instead of the smaller fixture pool. It generates a full Nether region,
compares independent/concurrent chunk requests and metadata, and checks that
repeating generation preserves the existing MCA bytes.

### Nether portal and lighting lifecycle

Add `-PretinaNetherLifecycleQa` to the integrated client commands above. Use Normal
worlds for this fixture. It checks both directions of actual portal travel and
dimension telemetry before allowing the ordinary save/reopen harness to proceed.
Portals at the unscaled coordinates act as decoys. On reopening, the fixture
requires the saved portals to remain complete and uses them without rebuilding.
Each connection also makes a second round trip from a remote Overworld portal.
Its first visit requires new native Nether terrain and a vanilla-created exit;
no Nether portal fixture is built at that destination. Destination regions are
prepared asynchronously while the player remains connected. Reopened runs reuse
the saved portals. The client must observe both round trips and current-dimension
telemetry before disconnecting.
The remote return may use a different Overworld exit when vanilla places the
Nether frame at the edge of its search area. The fixture checks the scaled return
area and a complete exit frame; the separate decoy case requires exact linking.

For dedicated coverage, start a disposable localhost server with
`-PretinaQa -PretinaNetherLifecycleQa -PretinaDatapackQa=<server-directory>`, then
connect the matching client with `-PretinaClientGameplayQa`,
`-PretinaNetherLifecycleQa` and `-PretinaClientServerQa=127.0.0.1:<port>`.
Set `server-ip=127.0.0.1`, `online-mode=false`, `white-list=false`,
`enforce-secure-profile=false`, `gamemode=creative`, `level-seed=42`, and
`max-tick-time=-1` in that private fixture's server properties. Accept the Minecraft
EULA separately. The server stops after the client's two completed connections.
Restart it on the same world and run the client again to test process reopening.

The server issues simultaneous Overworld/Nether reads at matching coordinates,
cancels duplicate observers, checks dimension storage bounds, and validates saved
Nether GPU light arrays over 16 interior chunks. Runtime lava, glowstone and
shroomlight fixtures check light levels 15/14/13 across a chunk boundary and zero
remaining light after source removal, with zero skylight throughout.
`nether_lifecycle_complete`, `nether_client_roundtrip`, and the ordinary
`minecraft_client_gameplay_reopen` events must pass. `nether_portal_fixtures`
reports whether saved frames were preserved. The server also checks the saved
Overworld and above-roof Nether edits on subsequent connections.
`nether_cold_portal_arrival` reports newly generated chunk counts and
`nether_cold_portal_return` confirms the remote return trip.

### Live Nether fortress progression

Use `-PretinaNetherProgressionQa` instead of `-PretinaNetherLifecycleQa` with the
integrated or dedicated commands above. Keep `-PretinaQa` and
`-PretinaClientGameplayQa` on the client and use a new disposable run directory.
The dedicated server also needs `-PretinaNetherProgressionQa`; restart it and
reconnect after its first two connections to test a full process reopen.

For example, with the disposable localhost server settings described above and
`server-port=25587`:

```sh
./gradlew -PretinaHostOnly -PretinaQa -PretinaNetherProgressionQa \
  -PretinaDatapackQa="$RETINA_QA_ROOT/fortress-server" \
  :fabric:runServer --args=--nogui
./gradlew -PretinaHostOnly -PretinaQa -PretinaNetherProgressionQa \
  -PretinaClientGameplayQa -PretinaClientServerQa=127.0.0.1:25587 \
  -PretinaClientRunDir="$RETINA_QA_ROOT/fortress-client" :fabric:runClient
```

For NeoForge use `:neoforge:runNeoForgeServer` and `:neoforge:runNeoForgeClient`,
separate directories and the configured server port; omit `--args=--nogui`.
For integrated coverage omit the dedicated-server command and `ClientServerQa`.

The automated player visits a fortress found by the real interactive `/locate
structure minecraft:fortress` command. The harness captures its normal chat
result while preserving the player source and empty result callback, and requires
asynchronous delivery. Observe the player open generated chest loot, activate an
untouched blaze spawner, kill a blaze and collect a rod in survival mode, then
return to the Overworld. It disconnects and repeats after reopening.

`nether_live_fortress_metadata` checks registered starts, every piece's bounds,
references across all intersecting chunks, and loaded chest/spawner block entities.
`nether_live_fortress_loot` requires ordinary survival block interaction to resolve
the original `nether_bridge` loot table, then preserves the opened contents and a
diamond edit across reopening. `nether_live_locate` checks stable command results
before and after generation and reopening.

`nether_live_fortress_spawn_rules` checks the registered weighted spawn override,
Minecraft's Nether-brick fortress rule and its exclusion of passive mobs.
`nether_live_fortress_natural_spawns` drives Minecraft's actual spawn-placement
routine until it creates both a blaze and a wither skeleton on the existing
fortress. These controlled attempts bypass random chunk/height selection and the
mob cap, while retaining spawn-table, player-distance, light, collision and
entity checks. They are not a measurement of normal spawning rates.

The spawner check temporarily disables natural spawning, clears survivors and rod
drops from previous fixture runs, and waits for the generated spawner's normal
ticks. It supplies a sword and defensive effects to the connected survival player,
uses ordinary player attacks and pickup, and requires newly collected blaze rods.
It restores the spawning gamerule and removes the effects afterward. The fortress
blocks and spawner parameters remain unmodified. This is controlled progression
QA, not an unaided survival playthrough or a portal-travel test.

Require two `nether_progression_complete` and `nether_live_blaze_progression`
events per run, plus client `nether_client_progression_return` and
`minecraft_client_gameplay_reopen`. Keep the complete server/client logs and any
screenshots or observations. An exception or early disconnect fails the run even
if Gradle reports success. QA expectations are stored in
`retina-qa-nether-progression.nbt` in the disposable world root.

For legacy-save compatibility, repeat the general client harness without either
Nether scenario flag, using `-PretinaClientQaMode=chunk` and fresh
`fabric-chunk` / `neoforge-chunk` directories. The pass event `minecraft_client_gameplay_reopen`
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
Place it in a fresh dedicated world's `datapacks` directory, use
`level-type=minecraft:normal` and seed `123456789`, and use
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

For automatic activation without a client, create a disposable dedicated directory
with ordinary `server.properties`, `level-type=minecraft:normal` or
`level-type=minecraft:flat`, and a separately accepted EULA. Run the loader's
server task with `-PretinaQa -PretinaAutomaticQa=true
-PretinaDatapackQa=/absolute/qa/directory`. It checks the live source/runtime pair,
writes a diamond-block edit, saves and stops. Run again with
`-PretinaAutomaticReopenQa=true` to verify the saved edit and activation after
reopen. These checks use no existing gameplay saves. For packaged installations,
the properties are `-Dretina.qa.automatic=true` and
`-Dretina.qa.automatic.reopen=true`.

`flatTest` compares all nine built-in presets and custom layers with actual
Minecraft flat base terrain, then checks deferred layers, empty worlds, clipping,
all heightmaps, selected structure sets, global structure disabling, preview/MCA
parity, partial promotion, protected saved records and propagated native errors.
It also compares lake/void feature writes and flat block/sky lighting with Minecraft
references. Run expensive native suites and game launches sequentially.

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

## Overworld/Superflat activation validation — 2026-10-19, before Nether activation

Validated on Java 25 / macOS / Metal (Apple M4 Max), using disposable worlds:

| Check | Result |
| --- | --- |
| Fabric and NeoForge, integrated Normal and Superflat | Creation, source preservation, telemetry, edit, disconnect and reopen passed |
| Fabric and NeoForge, dedicated Normal and Superflat | Ordinary level types, initial save and second-process reopen passed |
| Loader biome modifications | Fabric API addition and NeoForge biome modifier both reached the automatic native profile |
| Source settings | Normal, Large Biomes, Amplified, Single Biome, legacy codecs and Terralith registry checks passed; Debug/custom subclasses/Nether/End retained their source generators |
| Flat terrain | Nine built-in presets plus custom air/fluid/non-solid/empty layers matched configured blocks and Minecraft base terrain |
| Flat integration | Decorated chunk/MCA parity, previews, all heightmaps, partial promotion, saved records, error propagation, clipping and structure switches/overrides passed |
| Flat feature references | 96 controlled lake/void-platform cases matched Minecraft; platform extent also checked across 25 chunks |
| Flat lighting | 294,912 block/sky comparisons matched Minecraft, including imported light data |
| Bounds | A short noise dimension retained its out-of-bounds sea level and Minecraft's empty height-range semantics |
| Terralith | Automatic generation retained the exact 146-biome source pool and passed MCA/promotion checks; legacy import paths also passed |

Successful suites include `build -PretinaHostOnly`, `flatTest`, `gpuTest`,
`regionTest`, `previewTest`, `lightingTest`, `blockFeatureTest`, `structureTest`,
`registryTest` (vanilla and Terralith), `datapackTest` (Terralith),
`regionPreparationTest`, `columnCacheLifecycleTest`, `generationMetricsTest`,
and Fabric tests with the installed Chunky 1.5.3 fixture. Native units passed
86 tests; opt-in GPU tests were run through their corresponding Gradle tasks.
Compilation and expensive test runs used bounded workers and sequential launches.
The final build, flat and block-feature suites also include the concurrently
merged configuration and material-predicate changes. The shared region limiter
and its default of eight concurrent regions remain intact.

The game checks used development loader launches, and both installable host JARs
were built. This change was not separately retested in packaged launcher installs
or on Vulkan/Direct3D. DH coverage here is the real protochunk/temporary-region
compatibility harness; a third-party DH client was not launched for this change.
Existing feature/structure approximations remain as described in the README.

## Nether activation validation — 2026-10-19

Java 25 / macOS / Metal (Apple M4 Max), disposable development-loader worlds:

| Check | Result |
| --- | --- |
| Fabric and NeoForge dedicated Normal worlds, seed 42 | Creation and second-process reopen passed; original Overworld/Nether sources and End generator retained |
| Fabric and NeoForge integrated Normal worlds, seed 123456789 | Creation, strict loaded block/heightmap comparisons, telemetry, disconnect and reopen passed |
| Saved edits | Overworld Y=120 and Nether Y=200 survived reopening on both loaders and server types |
| Dimension identity | Live Nether storage key and distinct native profile passed; packet round-trip/filter tests covered all three dimension keys |
| Compatible settings | Inline noise settings, fixed biome source, no-ceiling dimension, scale 4 and logical height 192 retained; native binding passed |
| Normal-worker regression | 1,024 chunks at region -1,-1; seven independent probes / 458,752 voxels, metadata, separate-profile cold concurrent replay and byte-preserving repeat generation passed |

The original native stall and live-fluid comparison failure were retained as
diagnostic evidence; final runtime logs contain no failed QA events. The strict
comparison now runs before gameplay mutation, rather than accepting mismatches.
At this activation milestone, actual portal travel, live progression and the
complete lighting, integration and performance matrix had not been exercised.

## Nether portal lifecycle validation — 2026-10-20

Java 25 / Minecraft 26.3 / macOS / Metal (Apple M4 Max), default region parallelism:

| Check | Result |
| --- | --- |
| Fabric and NeoForge dedicated, seed 42 | Fresh creation, reconnect, full server restart and reconnect passed |
| Fabric and NeoForge integrated, seed 123456789 | Fresh creation, disconnect and save reopening passed |
| Actual portal travel | Two round trips per connection; scaled decoy selection and vanilla-created remote exits passed |
| Remote terrain | First visits generated exactly 4,096 chunks in four Nether regions; reopened visits generated zero |
| Native light | Sixteen interior chunks per run; emitter strength, adjacent-air propagation and zero skylight passed |
| Runtime light | Lava, glowstone and shroomlight emitted 15/14/13 through glass into air across a chunk boundary; removal reached zero |
| Storage/lifecycle | Seventeen concurrent dimension reads, cancelled duplicate observers, saved portal frames, original sources and Overworld/Y=200 Nether edits passed |
| Bounds regression | Nineteen positive/negative region-boundary cases cover maximum-width existing portal frames |
| Build | Both loader host JARs, scheduling/configuration checks, profile checks, portal bounds and 103 Rust unit tests passed |

The four-region remote preparation took 41.6 s (Fabric dedicated), 56.9 s
(NeoForge dedicated), 41.8 s (Fabric integrated), and 40.3 s (NeoForge integrated).
Clients stayed connected while that work ran asynchronously. These are lifecycle
run observations, not a controlled throughput comparison or a speedup claim.

Failed diagnostic runs reproduced a client timeout and captured the server
movement stack reading a cached supporting-block position from the old dimension.
The final runs clear that cache on dimension transfer and generate no unrelated
regions at those source coordinates.

At this portal milestone, live fortress progression, in-flight unload, the
remaining customization and DH/Chunky matrix, visual inspection and repeatable
performance measurements remained follow-up work. These runs used development loaders on Metal; packaged launcher
installs and other GPU backends were not retested here.

## Live fortress progression validation — 2026-10-20

Java 25 / Minecraft 26.3 / macOS / Metal (Apple M4 Max), default region parallelism:

| Check | Result |
| --- | --- |
| Fabric and NeoForge dedicated, seed 42 | Fresh world plus reconnect, full process restart plus reconnect passed |
| Fabric and NeoForge integrated, seed 123456789 | Fresh creation and save/reopen passed |
| Progression sessions | All 12 completed; every session killed a spawner-produced blaze with ordinary player attacks and collected a new rod |
| Dedicated fortress | 103 pieces, 49 intersecting chunks, five chests and two blaze spawners |
| Integrated fortress | 134 pieces, 180 intersecting chunks across two regions, ten chests and two blaze spawners |
| Live metadata and locate | Registered starts, all piece bounds, chunk references and asynchronous interactive command results remained valid through reopening |
| Loot | Survival interaction resolved the original fortress loot table; opened contents and a diamond edit survived reopening |
| Natural spawn placement | Every session created both a blaze and a wither skeleton through Minecraft's placement routine, using 2–9 controlled attempts |
| Regression checks | Both dimensions' native block/heightmap comparisons, original sources, unchanged End, client telemetry and integrated saved edits passed |
| Build | Both host loader JARs, scheduling/configuration/profile checks, 19 portal-boundary cases and 103 Rust unit tests passed (37 opt-in native tests ignored by this build) |

The initial diagnostic runs exposed two harness issues: lazy block-entity NBT was
omitted from an instantiated-object scan, and a synchronous remote comparison
blocked a dedicated server tick for over 60 seconds. Both were corrected before
the complete fresh/reopened matrix above. The final runs contain no failed QA
events or early disconnections.

The controlled spawning and combat limits are described in the
[scenario instructions](#live-nether-fortress-progression). These results do not
claim an unaided survival playthrough, normal spawning rates, visual inspection,
throughput measurements or installed DH/Chunky coverage. Live checks for the other
Nether structure families and in-flight unload are recorded below. The remaining
final matrix is follow-up work.


### Live bastions, fossils and Nether ruined portals

Use `-PretinaNetherStructuresQa` instead of `-PretinaNetherProgressionQa` with
both the server and client commands above, in fresh disposable directories.
Integrated runs use the same flag and omit the dedicated-server options. Run
both loaders, then restart each dedicated server and repeat its client run.

Each connection uses the real asynchronous interactive `/locate structure` path
for `minecraft:bastion_remnant`, `minecraft:nether_fossil`, and
`minecraft:ruined_portal_nether`. It validates loaded registered starts, saved
piece geometry, adjusted bounding-box references and representative generated
blocks. A ruined portal can legitimately lose its chest to Minecraft's lava
processor. The harness checks that no native chest was lost while loading, then
moves its locate origin to find another natural portal for the loot check.
The selected origin is saved. Its locate result must remain stable after loading
and editing, and after reconnecting or reopening.

`nether_live_structure_loot` opens the original bastion and ruined-portal
containers through ordinary block interaction and requires nonempty source loot.
It uses creative mode for these interactions, adds a diamond, and compares the
opened inventory after reloading. If the lid is covered, the player breaks its
cover through the ordinary block-breaking path; `cover_removed` records that
excavation. It does not build
a survival standing platform. Fortress survival progression is covered separately.

`nether_live_bastion_entities` matches live mobs to the native template UUIDs,
types and persistence. It checks source piglin weapons and applicable hunting
flags. Brutes must initially have their golden axe and home at the generated
position. After inspection, the fixture freezes the mobs and makes them
invulnerable, replaces brute axes with diamond axes, and verifies that equipment
and home survive reopening without another initialization. The pending native
initialization marker must already be absent from the live mob's saved NBT.

The harness temporarily disables natural spawning, restores the original rule,
returns the player to the Overworld, and waits for actual client dimension
telemetry. `nether_structures_complete`, `nether_client_structure_return` and
`minecraft_client_gameplay_reopen` must all pass. Dedicated clients connect
twice; integrated clients close and reopen the world. These checks do not claim
portal travel, visual quality, natural spawn rates or benchmark throughput.

Validation on macOS/Metal, Minecraft 26.3, Java 25:

| Check | Result |
| --- | --- |
| Fabric and NeoForge dedicated, seed 42 | Fresh/reconnect and full process restart/reconnect passed |
| Fabric and NeoForge integrated, seed 123456789 | Creation and save/reopen passed |
| Live sessions | All 12 completed; 455 passing events in ten final client/server logs |
| Dedicated bastion | 174 pieces across 24 chunks/four regions, six chests, 41 mobs including 15 brutes |
| Integrated bastion | 89 pieces across 25 chunks/two regions, eight chests, 32 mobs including eight brutes |
| Fossils and portals | Native blocks, starts, every adjusted-box reference, piece lookup and stable interactive locate passed |
| Loot and entities | Original loot resolved; opened inventories, diamond edits, brute axes and home memories survived reopening |
| NeoForge initialization | 61 unique dedicated and 32 unique integrated spawn-hook UUIDs; zero hooks after dedicated process restart |
| Source references | Eight bastion layouts and 136 portal piece replays passed, comparing 1,200 chunks/78,643,200 voxels for the portal suite |
| Chestless portal | Separate exact seed-123456789 replay confirms Minecraft also removes the submerged chest at chunk (24, 21) |
| Build and regressions | Both host JARs, Nether profiles, shared structure tests, scheduling/configuration, 19 portal-boundary cases and 104 Rust unit tests passed |

The final logs contain no failed QA events or unexpected server exceptions.
Diagnostic runs are retained separately, including the discarded
registration-time initializer's recursive chunk wait. The final implementation
uses the generation region; the matrix above ran with that implementation.

### In-flight Nether world unload

Use fresh disposable run directories with `-PretinaQa -PretinaNetherUnloadQa`.
The dedicated scenario needs no connected player and stops its server after
starting cold region requests. Run each dedicated command twice against the
same directory to check a full process restart:

```sh
./gradlew -PretinaHostOnly -PretinaQa -PretinaNetherUnloadQa \
  -PretinaDatapackQa=build/nether-unload/fabric-server \
  :fabric:runServer --args=--nogui
./gradlew -PretinaHostOnly -PretinaQa -PretinaNetherUnloadQa \
  -PretinaDatapackQa=build/nether-unload/neoforge-server \
  :neoforge:runNeoForgeServer
```

Use seed 42, structures enabled and ordinary world settings. Configure the
disposable servers' EULA and localhost settings as for the dedicated examples
above. For integrated runs, the client creates seed 123456789, disconnects,
reopens the save and disconnects again:

```sh
./gradlew -PretinaHostOnly -PretinaQa -PretinaNetherUnloadQa \
  -PretinaClientGameplayQa -PretinaClientRunDir=build/nether-unload/fabric-integrated \
  :fabric:runClient
./gradlew -PretinaHostOnly -PretinaQa -PretinaNetherUnloadQa \
  -PretinaClientGameplayQa -PretinaClientRunDir=build/nether-unload/neoforge-integrated \
  :neoforge:runNeoForgeClient
```

The scenario writes diamond blocks in both dimensions, including Nether Y=200,
and checks them on reopening. It queues twice the resolved region parallelism
plus two cold regions per dimension, an ordinary read of each first region, and duplicate read
observers that it cancels. It uses the normal native preparation and I/O queues
without replacing or holding generation. These opt-in fixture edits belong only
in disposable saves.

Require `nether_unload_in_flight`, `nether_unload_storage_close`, an actual
`nether_unload_callback` and
`nether_unload_cleaned` for each dimension, and `nether_unload_complete` for each
server lifetime. Pending requests must finish successfully or report an explicit
closed/closing result. The observer waits for actual preview-directory removal
and zero remaining generation or additional generation failures; reopening
checks that the old directories remain absent. Require
`nether_unload_server_drained` after all dimension storage closes. Integrated runs
also require `minecraft_client_gameplay_reopen`.

The server-stopped hook waits for native calls already running to finish before
loader teardown. All dimension I/O queues close first, preventing another
dimension's queued preparations from starting during that wait. The test also
waits for their temporary data to be released.
The stronger failure-counter check exposed a NeoForge cold-restart race where
the module closed before a native result first loaded `ColumnCache`; the final
shutdown drain addresses that actual failure. Initial cleanup-only results are
diagnostic evidence, not the final validation matrix.

The final run on 2026-10-20 used Minecraft 26.3, Java 25 and Metal on an Apple
M4 Max. Dedicated saves used seed 42; integrated saves used seed 123456789.

| Loader and environment | Fresh events | Reopen events | Result |
| --- | ---: | ---: | --- |
| Fabric dedicated | 39 | 23 | Pass |
| NeoForge dedicated | 39 | 23 | Pass |
| Fabric integrated | 82 combined | Included | Pass |
| NeoForge integrated | 84 combined | Included | Pass |

These six invocations covered eight server lifetimes and 290 passing events.
All 304 owned requests terminated: five completed before storage close and 299
returned explicit close results. All sixteen dimension cleanups reported zero
additional generation failures. The final four saves retained their edits and
source definitions, and their recorded preview directories were absent. Event
ordering and actual saved region files confirmed that no requested cold region
was published after its dimension storage closed. Three Overworld regions
legitimately completed before close during the integrated fresh runs.

The same source passed common cache lifecycle, preparation/scheduling and
configuration checks, both host builds, and Fabric's native
`RegionAheadPublicationTest`. Installed `ChunkySelectionIntegrationTest` coverage
was skipped without its jar; this cycle makes no installed Chunky claim.

This checks ordinary shutdown during generation; it does not claim
abrupt-process-crash recovery, visual quality or benchmark throughput.

### Nether source and partial-save compatibility

Run `./gradlew -PretinaHostOnly :common:netherCompatibilityTest` for the five fixed
biomes and normal climate source. It checks decorated native/preview parity,
source biome palettes, disabled structures, zero skylight, promotion, and partial
repair with byte-preserved existing records. It also exports a datapack under
`build/nether-compatibility/datapack`. `--args=--pack-only` exports only the fixture.

Package that folder as `nether-compatibility.zip` and place it in each fresh
custom world's datapacks folder before creation. For dedicated runs, enable
`vanilla,file/nether-compatibility.zip` in `initial-enabled-packs`, set
`generate-structures=false` and use the ordinary `minecraft:flat` level type.
Launch with `-PretinaQa -PretinaAutomaticQa=true -PretinaNetherCompatibilityQa=custom`
and the appropriate `retinaDatapackQa` directory and server task. Add
`-PretinaAutomaticReopenQa=true` on the second process launch.

For integrated runs, use:

```sh
./gradlew -PretinaHostOnly -PretinaQa -PretinaClientGameplayQa \
  -PretinaClientNoStructuresQa=true -PretinaNetherCompatibilityQa=custom \
  -PretinaClientPresetQa=minecraft:flat \
  -PretinaClientDatapackQa=nether-compatibility.zip \
  -PretinaClientRunDir=build/nether-compatibility/fabric-custom-integrated \
  :fabric:runClient
```

Use a separate directory and `:neoforge:runNeoForgeClient` for NeoForge.
The client automatically creates, closes and reopens its disposable world.
For vanilla Normal-world coverage, use `retinaNetherCompatibilityQa=vanilla`,
omit the datapack and Flat preset options, and retain structures disabled.

Require `nether_loaded_compatibility` with the expected scenario, flat flag, and
fresh/reopened state for each server lifetime. The actual loaded datapack must
retain fixed Warped Forest, noise Y=16..111, sea level 40, ceiling=false, logical
height 192 and scale 4, while keeping storage Y=0..255. Native previews must
contain lava and solid terrain, empty output outside those noise bounds, no
skylight, and no structure starts. Live chunks retain the fixed biome at the
tested heights. Source snapshots and profiles are written in the world root;
the standard activation and saved-edit checks also run.

The source comparison serializes both sides before comparing JSON numbers:
Gson's in-memory Float and parsed decimal values can compare differently even
when the serialized definitions are byte-identical. This avoids a false failure
without relaxing comparison of the serialized world settings.

Final results on 2026-10-20, Minecraft 26.3 / Java 25 / Metal on Apple M4 Max:

| Loader and environment | Normal/default Nether events | Flat/custom Nether events | Server lifetimes |
| --- | ---: | ---: | ---: |
| Fabric dedicated, fresh + process restart | 33 + 8 | 33 + 8 | 4 |
| NeoForge dedicated, fresh + process restart | 33 + 8 | 33 + 8 | 4 |
| Fabric integrated, create + reopen | 51 | 52 | 4 |
| NeoForge integrated, create + reopen | 51 | 52 | 4 |

All 370 runtime events passed. The eight saves retained identical serialized
source definitions on reopen, original Overworld/Nether generators, the unchanged
End, disabled structures and edits in both dimensions. Native previews retained
the configured bounds and fluids. The separate six-source native suite passed
393,216 independent block comparisons and 6,138 byte-preserved existing records
across six partial repairs.

Client runs used VSync disabled, render distance 4, simulation distance 5 and
30 FPS. The first attempt with default VSync stalled in macOS OpenGL presentation
before world creation; its thread/native samples are retained as diagnostics.
The final integrated matrix ran with VSync disabled. A separate initial source
comparison failure retained byte-identical JSON and led to the serialization
comparison correction above.

Final regression checks passed for vanilla registry/automatic sources, nine
native Superflat presets, 294,912 Minecraft reference light comparisons, 96 flat
feature fixtures, Nether profile/height contexts and cache lifecycle. Both host
builds passed. Fabric's native region publication, I/O/locate mixins and both
actual Chunky 1.5.3 selection/mixin tests passed; the mutually exclusive
absent-Chunky startup case was skipped. This is not an installed DH/Chunky
gameplay result. The unchanged native library was reused; this Java/QA cycle
does not claim a new Rust rebuild or Rust-unit run.
