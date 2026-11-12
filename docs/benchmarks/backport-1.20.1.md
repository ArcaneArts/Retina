# Minecraft 1.20.1 backport evidence

## Minecraft 26.3 baseline

The published `v1.1.511` universal JAR from `d71239ac` is the baseline for the
Java transport work. Its SHA-256 is
`1a57f1e56751ecf099913c42c97534833bc9427d6580f984db89b6a0e7c3e783`.
The packaged macOS ARM64 library SHA-256 is
`d349119235ea0f6d44e6a71844b2d819ece3187cd1186852ee377c5e60743119`.

The Java 25 packaged CPU test exercises both vanilla snapshots and checks two
chunks per dimension, queries and lighting. It reopens the same definitions
and checks the chunks again in reverse order. The 26.3 release packaging
change in [PR #430](https://github.com/ArcaneArts/Retina/pull/430) reproduced
all twelve chunk hashes and both definition hashes with the same native
libraries.

The headless timing sample uses a fresh world per run, seed `12345`, one zlib
region at `0,0` in the End, four Retina region threads and two Rayon threads.
The machine was an Apple M4 Max on AC power running macOS 27.0.1 and JDK 25.0.1.
The CPU and Metal runs each had two warmups and five timed launches. Timings
include process startup, catalog materialization, output hashing and temporary
world cleanup. They are specific to this small, mostly empty End region.

| Backend | Published median | Published p95 |
| --- | ---: | ---: |
| CPU | 1,361.8 ms | 1,388.8 ms |
| Metal | 1,351.4 ms | 1,422.8 ms |

Representative CPU and Metal runs each generated 1,024 new chunks. The Metal
run reported `GPU worker ready: Metal: Apple M4 Max`.

To reproduce generation, use an empty disposable world directory for each run:

```sh
RETINA_REGION_THREADS=4 RAYON_NUM_THREADS=2 java -Dretina.gpuBackend=metal \
  -Djava.awt.headless=true -jar "$jar" generate \
  --world "$world" --seed 12345 --dimension minecraft:the_end --region-radius 0
```

Use `-Dretina.gpuBackend=cpu` for the CPU sample. Run the packaged native and
CPU checks using the [local acceptance commands](../UNIVERSAL_RELEASE.md).
Windows GPU execution remains unverified for this backport.

## Shared definition transport

The Minecraft-independent definition classes in `core/src/shared/java` own
handle lifetime and caller validation. The modern FFM transport still calls
the same Rust functions. A Java 17 JNI provider can implement
`DefinitionTransport` using the same owner code.

The Gradle CLI test and packaged native/CPU smoke checks passed with the
refactored JAR. The CPU smoke matched the published JAR on all twelve chunk
hashes and both definition hashes. Both packaged artifacts used identical
macOS native library bytes.

Five alternating Metal pairs measured 1,376.7 ms published and 1,356.6 ms
refactored median process time, with median generation times of 104 and
102 ms. All twelve Metal launches, including the warmup pair, reported a
ready Metal worker and generated 1,024 new chunks.

CPU process times varied with concurrent host activity. The first five
alternating pairs measured 1,385.5 and 1,464.6 ms median process time. A
separate ten-pair confirmation measured 1,509.2 and 1,552.3 ms median
process time, with median generation times of 154 and 156 ms. The median
paired CPU process-time change was +6.9%, and the median paired generation
change was −1.5%. Individual process-time changes ranged from −19.4% to
+15.4%. The startup result remains inconclusive. Recheck it with the JNI
comparison before final qualification.

## Java 17 native transport

`:core17:jar` compiles the shared definition owner and JNI provider with
`--release 17`. It uses the root build's JDK 25 compiler. The Java 17 runtime
probe uses Temurin 17.0.21. FFM remains the modern provider. Both use the
same library extraction, backend configuration, cache flush and Rust C ABI.

Build and probe from the repository root with the actual paths to both JDKs
and the compiled library:

```sh
cargo build --manifest-path native/Cargo.toml --locked --release --lib
./gradlew :core:jar :core17:jar :core:cliTest
catalog=assets/world-definitions/26.3/vanilla-binary/retina-catalog.dat
export RETINA_REGION_THREADS=4 RAYON_NUM_THREADS=2
"$java17" -Xcheck:jni -Dretina.gpuBackend=cpu "-Dretina.native.path=$library" \
  -cp "$core17Jar" scripts/DefinitionTransportSmoke.java "$catalog" --ascii-path
"$java25" --enable-native-access=ALL-UNNAMED -Dretina.gpuBackend=cpu \
  "-Dretina.native.path=$library" -cp "$coreJar" \
  scripts/DefinitionTransportSmoke.java "$catalog" --ascii-path
```

The probe uses a real catalog and writes a disposable saved definition. It
checks missing and malformed files, backend configuration after initialization,
concurrent chunk and biome calls, reopen, idempotent close, and preservation of
all 1,024 records when generating into an existing zlib region. CPU transport
receipts matched with the same macOS ARM64 library bytes. Java 17 also loaded
the library from a packaged resource and completed the probe on CPU and Metal.

The ASCII-path `-Xcheck:jni` run produced no warnings. The Unicode-path run
also passed. The diagnostics printed during Unicode file operations reproduce
in a Java-only `Files.createTempDirectory` and `Files.writeString` test on
this JDK and host.

The release-mode macOS ARM64 native image had SHA-256
`ba2435fbf281235362e0ea86d25d335fe1db16cf7a0d09c0b47e6d569f5e85c4`.
A rotating comparison used one warmup launch and five measured launches per
provider. All eighteen runs emitted identical output receipts. The point
measurement includes the JNI or FFM call, the warm native query and checking
1,024 returned biome indices.

| Provider | Median warm batch | Median whole probe |
| --- | ---: | ---: |
| FFM / Java 25 | 27.7 µs | 7,587 ms |
| JNI / Java 25 | 21.9 µs | 7,481 ms |
| JNI / Java 17 | 21.6 µs | 7,613 ms |

The Java 25 JNI run helps separate transport costs from JVM differences.
This benchmark covers warm biome queries and a small headless CPU workload.

Separately, the cached vanilla 1.20.1 server JAR ran its actual report
generator on Java 17 using `-DbundlerMainClass=net.minecraft.data.Main`
and `--reports`. It produced five reports, including block and registry
inventories and Overworld/Nether biome parameters. Those reports describe
vanilla inputs. Effective loader registries and the 1.20.1 Retina definition
still need runtime extraction and validation.

The modern `universalJar` build packaged the rebuilt macOS image. Its local
native and CPU smoke tests passed. The twelve chunk hashes and both saved
definition hashes matched the published 26.3 baseline.

## Shared structural definition encoding

The Java 17 sources in `definition/src/main/java` own schema metadata, ordered
material and biome palettes, and structural encoding of normalized profiles.
The 26.3 adapter still creates its native NBT objects, including exact
negative-zero doubles.

Local `:common:worldDefinitionCompileTest` passed eight definition cases,
including vanilla, flat and datapack exports and typed NBT values. Local
`:data:generateCatalogs` reproduced both committed 26.3 catalogs byte for byte.

| Catalog | SHA-256 |
| --- | --- |
| Recompiled | `d34080051d84bf4ca0c2e59d17c294fa22495fc592d212f4ed92d8e0bffc9f17` |
| Binary | `5237034015aedda6ebd06288427bba5ac28f4a1bc72e0c3342bf4661644eb810` |

Java 17 compiled the shared source against the Gson 2.10 library from the
1.20.1 server distribution. Its only dependencies are Gson and `java.base`.

## Minecraft 1.20.1 Flat export and region round trip

`:minecraft1201:exportFlat` runs the 1.20.1 server's world loader on Java 17.
It selects the effective Flat preset after loading the new world's datapacks
and writes the result through the shared definition schema. The native engine
uses the actual saved DataVersion 3465 to encode `Name` and `Properties` in
chunk block palettes and to require zlib. The saved definition keeps the same
format and canonical identities as the 26.3 definitions.

The initial exporter covered Flat terrain with biome decoration, lakes and
structure generation disabled. The native Flat model handled ordinary layers
and deferred fill layers. Later cycles added noise profiles, loader
installation and the Flat features and structures described below.

For a disposable world, point Gradle at a Java 17 installation and a native
library from this checkout. Leave out `-PretinaVerifyFlat` to export just the
definition. The verification option generates a region and writes a test edit.

```sh
world=$(mktemp -d)
mkdir -p "$world/datapacks"
cp -R minecraft1201/src/datagen/fixtures/flat-pack "$world/datapacks/retina-flat"
RETINA_REGION_THREADS=4 RAYON_NUM_THREADS=2 ./gradlew :minecraft1201:exportFlat \
  "-Porg.gradle.java.installations.paths=$jdk17" "-PretinaNativePath=$library" \
  "-PretinaWorld=$world" -PretinaSeed=-9223372036854775791 \
  -PretinaGpuBackend=cpu -PretinaVerifyFlat
```

Use a fresh directory without the fixture for vanilla. Use
`-PretinaGpuBackend=metal` for the local macOS Metal run. The ordinary
26.3 build does not configure the 1.20.1 Loom project.

The local verifier reopened each exported definition with Java 17 JNI, then
generated region `-1,0`. The 1.20.1 region reader and palette codecs decoded
all storage sections and checked the block layers, biome identities, six
heightmaps, light data and chunk status. The test saved a diamond block
through the same 1.20.1 region writer and verified that native generation
preserved the edit and all 1,024 records on reopen. Unsupported LZ4 was
rejected, and the same handle then bound zlib successfully. A second export
into the same world failed with `FileAlreadyExistsException` and preserved
the saved bytes.

| Runtime input | Backend | Export including pack load | Generated | Preserved |
| --- | --- | ---: | ---: | ---: |
| Vanilla Plains Flat | CPU | 695 ms | 1,024 | 1,024 |
| Datapack Desert Flat with a deferred torch layer | CPU | 678 ms | 1,024 | 1,024 |
| Same datapack | Metal | 686 ms | 1,024 | 1,024 |

The standalone Rust binary also generated the datapack region from the same
saved definition. Its CPU run reported 1,024 generated chunks in 0.981 s.
Reopening the destination reported 1,024 preserved chunks. The recorded seed
was `-9223372036854775791` and the version was 3465.

```sh
RETINA_GPU_BACKEND=cpu RETINA_REGION_THREADS=4 RAYON_NUM_THREADS=2 \
  "$retina" generate --definition "$world/retina.dat" --dimension minecraft:overworld \
  --region-min=-1,0 --region-max=-1,0 --output "$output"
```

The 26.3 world-definition suite passed all eight cases and regenerated both
catalogs byte for byte after sharing the Flat and lighting table compilers.

The modern universal JAR also passed its packaged CPU smoke test using the
rebuilt native image. All twelve chunk hashes and both reopened definition
hashes matched the published 26.3 baseline.

## Minecraft 1.20.1 Overworld and Nether terrain preview

The Java 17 export reads actual 1.20.1 density functions, noise amplitudes,
surface rules, biome intervals and generation bounds. Both game adapters use
the same program compiler and terracotta band recipe. The 1.20.1 band recipe
uses the configured random source and its world-seeded clay-band stream.

The preview runs the Overworld and Nether terrain, surface material and
aquifer programs. The saved profile reports the approximations and omissions
through `retina inspect`. Configured carvers, placed features, ore generation
and structures are outside this cycle. The next section covers the End.
Preview definitions are development inputs for headless generation.

Run the following command in a disposable world using Java 17 and the shared native library.

```sh
world=$(mktemp -d)
RETINA_REGION_THREADS=4 RAYON_NUM_THREADS=2 ./gradlew :minecraft1201:exportNoiseTerrain \
  "-Porg.gradle.java.installations.paths=$jdk17" "-PretinaNativePath=$library" \
  "-PretinaWorld=$world" -PretinaSeed=12345 -PretinaGpuBackend=cpu -PretinaVerifyNoiseTerrain
```

Use `-PretinaGpuBackend=metal` for the macOS Metal run. To exercise the
datapack, create the world's `datapacks` directory, copy
`minecraft1201/src/datagen/fixtures/noise-pack` into it and select
`-PretinaPreset=retina:noise_probe`. Its direct multi-noise source and custom
terrain surface rule produced Desert and smooth sandstone in the decoded
Overworld region.

Each run generated 1,024 records per dimension in region `-1,0`. The 1.20.1
reader decoded the block and biome palettes, sections, heightmaps and status
of three sampled chunks per dimension. Reopen preserved all 1,024 records.
These are individual local timings on an Apple M4 Max, including the first
native open and bind for each dimension.

| Input | Backend | Export | Overworld | Nether |
| --- | --- | ---: | ---: | ---: |
| Vanilla normal | CPU | 971 ms | 3,427 ms | 4,717 ms |
| Vanilla normal | Metal | 991 ms | 280 ms | 178 ms |
| Datapack | CPU | 833 ms | 1,994 ms | 4,685 ms |

The standalone Rust binary generated the datapack Overworld from the
saved definition in 1.89 s and preserved all 1,024 records on a second
invocation. The vanilla Flat round-trip also passed after moving it onto the
same registry bootstrap. The 26.3 definition suite passed all eight cases
and both catalog outputs matched the committed bytes.

## Minecraft 1.20.1 End terrain preview

The saved End descriptor selects the 1.20.1 arithmetic in the shared
native End generator. It uses the game's double-precision blended noise,
Y/X/Z interpolation order and signed island-distance arithmetic. Existing
End definitions default to the 26.3 arithmetic. The 1.20.1 adapter compares
the effective density and biome graphs with the supported vanilla graphs
before selecting this descriptor.

Generate the native comparison fixture from the actual 1.20.1 server on Java 17.

```sh
./gradlew :minecraft1201:probeEndTerrain \
  "-Porg.gradle.java.installations.paths=$jdk17" \
  "-PretinaEndReference=$reference"
cargo test --manifest-path native/Cargo.toml --locked --release --lib 'end::'
```

The reference covers 57 columns across seeds `0`, `12345` and
`-9223372036854775791`. Both native point and tile terrain matched all
1.20.1 occupied blocks, biome IDs and erosion samples, including negative
coordinates and far coordinates with the original distance overflow.
All 26 filtered Rust tests passed, including the 26.3 End fixtures.

The noise export verification now generates two End regions alongside
the Overworld and Nether. The 1.20.1 reader compares sampled End blocks
and biomes to the same runtime's base-column and biome queries. All four
regions passed on CPU and in the Metal-selected process. Every region
generated and preserved 1,024 records. A datapack replacing the End's
referenced density function produced an explicit unsupported-dimension
reason.

| End region | CPU | Metal-selected process |
| --- | ---: | ---: |
| Central `-1,0` | 216 ms | 184 ms |
| Outer `-3,-1` | 479 ms | 217 ms |

These are single local region observations. The End terrain lattice uses
native CPU code in both runs. Placed features, structures and server
lifecycle integration are still outside the preview.

The standalone Rust CLI generated the outer region from the saved definition
in 0.54 s and preserved all records on a second invocation.

A 26.3 comparison used seed `12345`, the binary vanilla catalog, End region
`0,0`, zlib and alternating process order. One warmup pair and nine timed
pairs ran per backend. Every run reproduced the same digest of all 1,024
uncompressed chunk records. The median paired process time changed by
−1.06% on CPU and −0.46% on Metal. The median paired generation time changed
by −1.73% and +0.87%, respectively. The repeat did not reproduce the larger
Metal process-time variation seen in the initial three-pair sample.

## Minecraft 1.20.1 ore and carver profiles

The 1.20.1 adapter reads loaded biome generation settings, including placed
ore and scattered-ore features, placement height distributions and block,
state and tag replacement rules. It reserves all resulting block states
before building the replacement tables. The same native ore planner
executes those recipes on CPU and Metal. Both versions share the
height-provider normalization.

Normal vanilla export recorded 29 Overworld and 10 Nether ore recipes,
plus 159 Overworld and five Nether carver entries across the biome
profiles. The saved diagnostics record the shared carver approximations
and omission of the separate large ore veins from the legacy noise router.
The 1.20.1 region reader decoded ore blocks in both dimensions.

The existing `retina:noise_probe` datapack now changes a placed diamond
recipe to replace stone with emerald blocks. The CPU and Metal region
runs both decoded that block, the pack's smooth sandstone surface and
its Desert biome. All four region round trips preserved their 1,024
records on reopen. The standalone Rust binary also generated the
datapack Overworld using just a copy of its `retina.dat`.

Use the noise export verification commands above to reproduce these
runs. The structured receipts report ore recipe and carver entry counts
directly from the saved profiles. All eight 26.3 definition cases passed
and both regenerated 26.3 catalogs matched the committed bytes.

## Seed-independent 1.20.1 terrain catalog

The vanilla catalog exporter uses the 1.20.1 server bootstrap. It removes
the capture seed and replaces the terracotta table with a recipe tied to
the version's positional random stream. The ordinary Rust catalog
materializer recreates that table and publishes an authoritative
`retina.dat`. The 26.3 recipe retains its existing stream.

```sh
./gradlew :minecraft1201:exportTerrainCatalog \
  "-Porg.gradle.java.installations.paths=$jdk17" "-PretinaNativePath=$library" \
  "-PretinaCatalog=$catalog" -PretinaVerifyCatalog
```

The compressed preview was 238,349 bytes and repeated export reproduced
its bytes. All five variants matched
live 1.20.1 NBT exports at seeds `0`, `12345` and
`-9223372036854775791` after normalizing the equivalent byte and integer
band arrays. All fifteen materialized definitions reopened through the
ordinary native loader. A second reference check compared the older
Java LCG positional stream against the actual 1.20.1 random source for
the same three seeds.

The standalone CLI materialized `bukkit-api`, generated 1,024 records
for outer End region `-3,-1` and preserved all records on reopen. A
before-and-after materialization of the 26.3 binary catalog at the
negative seed produced byte-identical saved definitions. The seven enabled
native catalog tests and the modern End random-stream regression test passed.

The catalog is a development artifact. It still reflects the placement
and structure coverage of the current 1.20.1 exporter. The installed
Bukkit variant also needs the API delivery policy as decoration support
lands.

## Bukkit 1.20.1 on Java 17

`./gradlew buildBukkit1201` assembles the plugin in `bukkit1201/build/libs`.
The build embeds the 1.20.1 catalog and uses the Java 17 JNI bridge. The
26.3 plugin uses the same world ownership, caching and delivery code with
its own compiled API adapters and FFM bridge. The local validation embedded
the same macOS ARM64 native image in both JARs.

Run the disposable acceptance server with an actual Spigot 1.20.1 server
JAR and the saved definition from the noise datapack export above. The
output directory must be new or empty.

```sh
./gradlew :bukkit1201:serverTest \
  "-Porg.gradle.java.installations.paths=$jdk17" \
  "-Pbukkit1201ServerJar=$spigot1201" \
  "-PretinaImported1201=$definition" \
  "-PretinaBukkit1201TestOutput=$output" \
  -PretinaGpuBackend=metal
```

The test starts the packaged plugin on Java 17, opens all three dimensions
from one root definition, compares sampled chunks against the native
output, imports the emerald ore and Desert datapack definition, checks
that its unloaded generator rejects callbacks, and verifies that edits
survive a restart with catalog materialization disabled. The server
fixture also exercises public API delivery for a gateway, chest items
with legacy NBT, a loot table with a signed 64-bit seed, a single-type
spawner and an armor stand. The saved 1.20.1 chunk and entity region
records retain the fixture fields and the armor stand UUID across restart.

Spigot `3871-Spigot-d2eba2c-3f9263b` ran on Java 17 on an M4 Max.
CPU and Metal each matched the 917,504 block states and 14,336 quart
biomes sampled from 12 chunks at initial generation and after restart.
The saved definitions kept their original digests and all three edits
survived. The process reached startup in 120.2 s on CPU and 71.3 s on
Metal for the cold world, then in 6.9 s and 7.1 s on restart. A sample
during the CPU cold start found vanilla structure-ring placement querying
biomes through Retina's Bukkit provider.

The 26.3 Bukkit unit suite passed all 24 tests. The 26.3 universal JAR
also passed its packaged FFM smoke test with both modern catalog variants.

The Java 17 build uses only the public Bukkit API. It reports unsupported
registry identities, structure metadata, weighted or customized spawns
and entity fields outside its supported set. The bundled catalog retains
the feature and structure coverage of the current 1.20.1 exporter.

## Fabric 1.20.1 dedicated-server terrain

`./gradlew buildFabric1201` produces a remapped mod JAR in
`fabric1201/build/libs`. The JAR bundles the 1.20.1 catalog, Java 17
JNI bridge, shared native image and the configuration parsers. The
server mixins open one saved definition before dimensions are
constructed, keep each original `LevelStem` in world settings, and
install the saved generator only for supported dimensions.

The adapter decodes native sections with the 1.20.1 game codecs and lets
the game own lighting, chunk-status transitions and region writes. It
decodes a separate mutable palette for each live section because the
1.20.1 palette copy can retain the old resize callback. Chunk generation
and base queries share the same bounded cache. The runtime currently
installs one chunk at a time and uses the placement and structure
coverage of the exported definition.

Run the headless acceptance with the official Fabric server launcher
for Minecraft 1.20.1 and the host library embedded in the mod JAR.

```sh
python3 scripts/minecraft1201-server-test.py \
  --java "$jdk17/bin/java" --server "$fabric_launcher" --mod "$fabric_mod" \
  --library "$library" --pack minecraft1201/src/datagen/fixtures/noise-pack \
  --output "$output" --backend metal
```

On an M4 Max and Java 17, Fabric Loader 0.16.14 ran on Metal and
0.20.1 ran on CPU. Both runs compared 688,128 blocks, 10,752 quart
biomes and 24 unedited heightmaps across nine chunks at each stage.
All three edits persisted, and the world kept its original definition.
A separate server loaded the noise datapack, compiled its effective
registries and saved 229 emerald ore blocks and 4,608 Desert quart
biomes in three sampled chunks in each run. The process reached startup
in 16.6 s on Metal and 23.4 s on CPU for the cold vanilla world, then in
8.3 s and 5.1 s on restart. These are single observations that include
the launcher and use different Fabric Loader versions.

## Forge 1.20.1 dedicated-server terrain

`./gradlew buildForge1201` runs ForgeGradle 6.0.54 under the pinned
Gradle 8.8 wrapper on Java 17. ForgeGradle rejected a direct run under
Gradle 9.7.1. The root build prepares the catalog, JNI classes and native
resources once. The Forge build consumes them and reobfuscates the
Minecraft adapter shared with Fabric. Its installable JAR is in
`forge1201/build/libs`.

`./gradlew build1201` assembles the three 1.20.1 JARs.
`./gradlew buildSupported` also assembles the 26.3 universal JAR.
All four artifacts built locally, the 24 modern Bukkit tests passed,
and the 26.3 universal JAR opened both catalogs through its embedded
FFM transport. The three 1.20.1 artifacts carried the same catalog and
the same macOS ARM64 native image.

Use the headless acceptance with a Forge server directory prepared by
the official installer.

```sh
python3 scripts/minecraft1201-server-test.py \
  --java "$jdk17/bin/java" --forge-install "$forge_server" --mod "$forge_mod" \
  --library "$library" --pack minecraft1201/src/datagen/fixtures/noise-pack \
  --output "$output" --backend cpu
```

On an M4 Max and Java 17, Forge 47.4.28 ran on Metal and Forge 47.2.0 ran
on CPU. Both matched the native blocks and biomes across nine sampled
chunks in all three dimensions and retained all three player edits.
Startup and restart took 15.2 s and 8.1 s on Metal, and 24.1 s and 7.0 s
on CPU. These are single process observations using different Forge
versions. The bundled catalog was selected for the plain server.
The datapack run exported the live registries after Forge applied its
biome modifiers, then saved 226 gold blocks from the modifier, 229
emerald blocks from the noise fixture, and 4,608 Desert quart biomes.

Live exports from the packaged Forge mod matched the bundled data in
all three dimensions for seeds `0`, `12345` and `-9223372036854775791`,
with structures enabled for seed `0` and disabled for the other seeds.
The comparison normalized the equivalent terracotta byte and integer
arrays and omitted the datapack provenance list. An empty datapack
forced the live path for two runs.

## Release-line qualification

`./gradlew release-1.20` promotes fetched `origin/main` to the legacy
publication branch. `release-26`, `publish` and `releaseRetina` target
the latest line. The real Gradle tasks passed against a disposable
local Git remote, including initial branch creation, independent
advancement, no-op repeat and divergent-ref rejection.

Both release build commands ran locally with one staged directory of
native binaries and their byte manifests. macOS ARM64 used the existing
build of the same native source tree. Linux x64 and Windows x64 were
cross-compiled locally with Cargo Zigbuild and Cargo Xwin. The stage
checker accepted three Java 17 JARs for 1.20.1 and one universal JAR for
26.3. The 1.20.1 catalog was identical in the three legacy artifacts
and each artifact held the same bytes for each native target.

```sh
./gradlew build1201 "-PretinaNativeDirectory=$native_stage" \
  "-Porg.gradle.java.installations.paths=$jdk17"
python3 scripts/stage-release.py 1.20.1 "1.20.1-$(git rev-list --count HEAD)" --line 1.20
./gradlew universalJar "-PretinaNativeDirectory=$native_stage"
python3 scripts/stage-release.py 26.3 "26.3-$(git rev-list --count HEAD)" --line 26
```

Archive verification caught a missing Forge mixin refmap on the first
clean cache restore. Declaring the annotation processor mappings as
compiler outputs restored them alongside the Java classes. A clean
cache replay then staged a byte-identical Forge JAR, and that JAR
passed the dedicated-server save, restart and biome-modifier tests.
Workflow lint, the local release suites and the staged 26.3 native
smoke test passed. Windows GPU execution remains unmeasured.

## End features and metadata

The 1.20.1 registry export now includes the four vanilla End placed
features. Retina reads pillar layouts, chorus placement, small islands,
and return gateways from the effective registries. The shared engine
replays the features and writes 1.20.1 block positions and entity fields
when encoding DataVersion 3465 chunks. Minecraft places fire above the
legacy pillars during crystal ticks. The older saved definitions remain
authoritative for their worlds. End cities require structure export.

Use the Java 17 catalog and the native CLI to generate the seed 12345
acceptance corpus in a fresh directory.

```sh
./gradlew :minecraft1201:exportTerrainCatalog -PretinaVerifyCatalog \
  "-PretinaNativePath=$native" "-Porg.gradle.java.installations.paths=$jdk17"
retina init --catalog minecraft1201/build/terrain-catalog/retina-catalog.dat \
  --world "$world" --seed 12345
RETINA_GPU_BACKEND=metal retina generate --definition "$world/retina.dat" \
  --dimension minecraft:the_end --region-min=-1,-1 --region-max=0,0 --output "$world"
RETINA_GPU_BACKEND=metal retina generate --definition "$world/retina.dat" \
  --dimension minecraft:the_end --region-min=3,0 --region-max=3,0 --output "$world"
```

Add `--end-reference "$world/DIM1/region"` to the Fabric and Forge
server acceptance commands. This reads the native headless corpus,
loads the same chunks through the packaged mods, and verifies the ten
crystal UUIDs and the outer gateway exit after a save and restart.
The corpus also checks chorus flowers, pillar cap and body blocks, and
an island block at `1548,54,0`. The 1.20.1 `probeEndTerrain` Java
reference reports air at that point before decoration.

For a datapack export, copy
`minecraft1201/src/datagen/fixtures/end-pack` into a fresh world's
`datapacks` directory and run `exportNoiseTerrain` with
`-PretinaWorld="$world"`. Headless CPU generation of the same five
regions produced ten invulnerable crystals with beam target
`-8,95,12` and a gateway with exact exit `16,88,-32`. The four
central regions took 0.76 s on Metal for the vanilla catalog and
1.21 s on CPU for the datapack definition, including definition load.
Those timings come from different definitions.

The catalog passed all 15 live-versus-bundled cases. The Java 17 Fabric
0.16.14 and Forge 47.4.28 server runs retained all ten crystals and the
gateway on both boots, and the Forge biome-modifier datapack produced
its 226 gold markers in the sampled chunks. Spigot retained all ten
generated crystals with their server-assigned UUIDs, and its metadata
fixture saved the legacy gateway exit and crystal beam target across
restart. Each Spigot snapshot compared 917,504 blocks and 14,336 biome
samples. One crystal tick added fire in each snapshot. The check
allows that update only at the native crystal position.

Both regenerated 26.3 catalog variants were byte-identical to the
checked-in assets. The 2,048 decoded chunk records from the modern End
Metal corpus had identical hashes before and after the change. The
26.3 universal JAR also generated a complete End region on CPU.

## Nether features and scheduled updates

The 1.20.1 exporter records twelve loaded Nether roots for springs,
glowstone, crimson and warped huge fungi, basalt and blackstone blobs,
and deltas. These use the shared decoration engine and the generated
cave materials. Placement anchors resolve against the 128-block
generation volume in the vanilla Nether, which has 256 blocks of
chunk storage. Standalone vines, vegetation and fire patches, basalt
pillars and basalt columns still require adapters.

```sh
RETINA_GPU_BACKEND=metal ./gradlew :minecraft1201:exportNoiseTerrain \
  -PretinaVerifyNoiseTerrain "-PretinaWorld=$world" \
  "-PretinaNativePath=$native" "-Porg.gradle.java.installations.paths=$jdk17"
```

At seed 12345 the check reads regions `-1,0` and `0,-1` through the
1.20.1 region reader. It finds glowstone and shroomlight and round-trips
12,264 native spring ticks across 1,763 chunks through the game's
`ProtoChunkTicks` and Retina's installation code. Synthetic records
also exercise block ticks and packed post-processing positions through
the same installer.

For a live server check, add `--spring-reference "$world/DIM-1/region/r.-1.0.mca"`
to the Fabric and Forge commands. It checks that a native spring flows
into an adjacent block that was air in the headless output and that
the flowing lava is still present after restart. The chunk comparisons
record flowing lava in previously empty cells when the native chunk
contains scheduled lava. Other sampled block differences still fail.

The `nether-spring-pack` fixture under `minecraft1201/src/datagen/fixtures`
changes the open spring's fluid to water. Export it with
`exportNoiseTerrain` from a fresh world containing the pack and run
headless generation for Nether region `-1,0`. The CPU corpus contained
24 water ticks and 4,201 lava ticks. In the bundled `bukkit-api` variant
the same region retained glowstone and produced zero scheduled fluid
ticks. The Spigot suite passed the generation, imported-definition and
save/restart checks with that catalog.

Fresh headless generation of region `-1,0` on the M4 Max took
2.86 s on CPU and 0.42 s on Metal with the previous terrain-only
definition. The new definition took 11.75 s on CPU and 2.31 s on Metal,
including the cave reads, feature placement, lighting and MCA output.

The 15 live-versus-bundled catalog checks passed. Both regenerated
26.3 catalogs matched their checked-in bytes. The decoded records of
the sampled 26.3 Nether region matched before and after the change on
Metal. The local Rust suite passed 485 tests, with 117 opt-in tests
skipped.

## Remaining vanilla Nether placements

The 1.20.1 normal definition now exports all 27 non-ore Nether roots.
The additional recipes cover fire and mushroom patches, forest
vegetation, twisting and weeping vines, basalt pillars, and small
and large basalt columns. The exporter uses the loaded configured
and placed features. Ore roots still use the geology exporter.

The legacy column layout samples the clustered and dispersed forms
and permits lava replacement through sea level. In the region
`-1,0` comparison, positions such as `-512,23,47` changed from lava
to basalt. Vine lengths follow the distinct upward and downward
source loops. Nether mushroom survival uses the darkness of the
generation stage. Normal fire checks a sturdy floor, as used by the
bundled patch's netherrack predicate. Custom support on an adjacent
flammable face is outside this rule.

Use the preceding `exportNoiseTerrain -PretinaVerifyNoiseTerrain`
command with a fresh world. It verifies all 27 roots, the generated
fire, soul fire, mushrooms, vegetation and vines, and the 12,264
scheduled spring ticks across the two vanilla Nether regions.

The `nether-biomes-pack` fixture adds fixed-biome Nether dimensions
for Warped Forest and Soul Sand Valley. Copy it to `$world/datapacks`,
run `exportNoiseTerrain` in that world, and generate the two regions.

```sh
for dimension in warped_forest soul_sand_valley; do
  RETINA_GPU_BACKEND=cpu retina generate --definition "$world/retina.dat" \
    --dimension "retina:$dimension" --region-min=0,0 --region-max=0,0 --output "$world"
done
```

The Java 17 export contained five dimensions. The CPU output contained
twisting vine heads and stems, Nether sprouts, and warped roots in the
Warped Forest, and basalt and soul fire in the Soul Sand Valley. The
1,024-chunk regions took 15.38 s and 14.82 s, respectively, on the M4 Max.

The 15 live-versus-bundled catalog checks passed. Both regenerated 26.3
catalogs and the 1,024 decoded 26.3 Nether chunk records matched the
baseline. Across three alternating cold Metal runs per revision, the
26.3 region took 1.43–1.57 s before and 1.24–1.81 s after, excluding
definition loading. All six runs produced the same decoded records.
The native library suite passed 485 tests with 117 skipped.

`buildSupported` packaged identical native bytes across the four JARs
and identical 1.20.1 catalogs across the three legacy JARs. The Java 17
Fabric and Forge server probes each matched 688,128 blocks and 10,752
biome samples per boot, including 44 expected live lava-flow updates,
and preserved three player edits across restart. The live datapack and
Forge biome modifier checks passed as well.

Spigot completed its generation, imported-definition, and save and
restart probes. The cold run timed out at 240 s alongside other tests,
so the isolated retry used `--startup-timeout 480`. Each boot matched
917,504 blocks and 14,336 biome samples after counting one live fire
tick update at the generated End crystal's position. The restart
preserved three player edits and all ten generated End crystals. The
comparator was corrected for fire age and rerun on the saved snapshots.

## Overworld plants, disks, and springs

The normal 1.20.1 Overworld now exports 59 of the 121 non-ore roots in
its loaded biome source. It preserves feature order and biome membership.
The recipes cover flower and grass patches, cactus and sugar cane columns,
disks, bamboo, springs, moss and clay patches, cave vines, and spore
blossoms. The definition lists the other 62 roots by name, including trees,
most aquatic features, and specialized terrain features. For skylit
dimensions, mushroom patches require the loaded mushroom-growing block tag.

Run `exportNoiseTerrain -PretinaVerifyNoiseTerrain` as above. At seed
12345 the 1.20.1 reader verifies all 1,024 records in region `-1,0`,
1,437 paired plants, and 192 scheduled water and lava ticks. The existing
Nether and End profiles were identical to the prior revision. All 15
bundled-versus-live catalog cases passed.

Copy `minecraft1201/src/datagen/fixtures/overworld-flower-pack` to the
disposable world's `datapacks` directory and repeat the verified export.
The Flower Forest dimension checks every one of 82,524 placed markers
against the live 1.20.1 provider at its actual position. The Lush Caves
dimension checks moss, cave vines, spore blossoms, and dripleaf in 16
headless chunks.

The single local M4 Max CLI runs for 1,024 fresh Overworld chunks took
4.89 s on Metal and 42.53 s on CPU. The prior terrain-and-geology profile
took 2.00 s and 6.05 s, respectively. These runs include the extra
material reads, feature placement, and MCA output for the new catalog.
The CPU and Metal outputs differed at 75 block positions in this region.
The same comparison with the prior definition differed at 72 positions.

The packaged Spigot probe matched 917,504 blocks and 14,336 biome
samples per boot, accounted for the live End crystal fire update, and
preserved all three edits. It also loaded the imported datapack
definition. Its `bukkit-api` catalog variant reports and omits the
scheduled spring roots because the public API cannot install their ticks.

The packaged Java 17 Fabric and Forge probes compared 688,128 blocks,
10,752 biome samples, and 24 heightmaps per boot. Both retained all
three player edits, verified the Nether spring after restart, and
matched two flowing water blocks connected to the saved Overworld
spring. The initial comparison was rerun against the captured saves
after adding the flow check. The live datapack checks matched 229
emerald markers on both loaders and 226 Forge biome modifier markers.
Concurrent cold tests took about 178 s
for each mod server and 297 s for the Spigot process, which exercised
all three spawn areas. These are acceptance timings under shared load.

## Asynchronous 1.20.1 region preparation

Fabric and Forge read Minecraft's current chunk first, including pending
stores. A missing chunk joins preparation for its region. The shared
Java scheduler bounds the work, coalesces requests, and releases each
temporary MCA file after the queued reads finish. Minecraft's I/O worker
closes its cached handle and publishes missing slots through the shared
native merge operation. Existing compressed records stay intact.
The storage queue reads the requested record after publication and
reports it if Minecraft still cannot decode it.

The publication bridge works through JNI on Java 17 and FFM on Java 25.
`exportNoiseTerrain -PretinaVerifyNoiseTerrain` writes a partial region
with the game codec, fills 1,023 missing slots, reopens all 1,024 slots,
and checks the saved record and negative chunk coordinates. The Java 25
smoke test also confirmed that a malformed MCA header remains unchanged
when native publication rejects it.

To exercise a partial region on the dedicated servers, use the native CLI
to generate seed 12345 Overworld region `0,0` from the verified export.
Pass that MCA file as `--partial-overworld-region` to
`scripts/minecraft1201-server-test.py`. The probe seeds one existing
record and checks the live I/O publication of 1,023 missing slots, the
saved marker, and the three player edits after restart.

The packaged Java 17 Fabric and Forge tests passed this check, the live
Nether and Overworld spring checks, and the live datapack checks. In the
final concurrent run, the Fabric and Forge processes reached startup
in 32.8 s and 25.8 s, respectively, including their loaders.
Loading a new region writes the entire MCA region, including chunks
beyond the immediate spawn or travel request.

In a separate sequential Fabric A/B run on the same M4 Max, fresh
process startup dropped from 57.9 s to 27.8 s. The game's timing
dropped from 44.9 s to 13.1 s. These are single runs.

The local lifecycle probe sent SIGTERM while four unpublished
previews were active. The process saved `level.dat`, removed the
preview directories, and reopened the unchanged `retina.dat`.
A corrupt-compression fixture on the packaged Fabric server surfaced
an unreadable-region error and retained the original record bytes.
`./gradlew :common:regionPreparationTest` passed the shared admission,
foreground promotion, cancellation, and lease release checks.
Three alternating 26.3 Nether Metal runs per library generated the same
1,024 decoded records. Region times ranged from 1.25 to 1.61 s before
and from 1.09 to 1.55 s after, excluding definition loading and binding.

## Overworld trees and giant mushrooms

The normal 1.20.1 Overworld now exports 82 of 121 loaded non-ore
placed roots. This adds 23 roots, including dark forest, mushroom
fields, birch, jungle, savanna, and old-growth taiga vegetation.
Tree and mushroom branches share the loaded root's placement seed,
order, and biome membership. Each selector branch applies its own
placement modifiers and writes through the shared native overlay.

The exporter records approximate tree geometry and clearance and
reports the omitted beehive and altered-ground decorators. Cherry,
mangrove, and rooted azalea remain explicit root omissions. Previously
saved definitions keep their original decoration recipes.

Run the verified noise and catalog exports as above. The 1.20.1
reader found oak and birch trees, 1,409 paired plants, and 192 fluid
ticks in the 1,024-chunk vanilla region. All 15 catalog parity cases
passed. Copy `minecraft1201/src/datagen/fixtures/overworld-tree-pack`
into a fresh world's `datapacks` directory and repeat the verified
noise export. Its 128 headless chunks confirm a dark-oak trunk
override, several connected leaf distances, and both giant
mushroom caps and their stems. The 1.20.1 Nether and End profiles
and resolved palettes are unchanged.

Using the same native CLI on the M4 Max, seed 12345 region `-1,0`
went from 4.35 s to 4.59 s on Metal and 31.34 s to 32.05 s on CPU.
These are single fresh-region runs. CPU and Metal agreed on the
uncompressed NBT of 1,007 of 1,024 chunks before and after. The
26.3 catalogs and macOS arm64 native library stayed byte-identical
to the preceding build.

`buildSupported :bukkit:test :bukkit1201:serverProbeJar` succeeded
locally. The three installable Java 17 JARs contained the same
365,442-byte version-isolated 1.20.1 catalog with five variants.
All four JARs contained identical native bytes.

## Cherry and mangrove trees

The 1.20.1 exporter now records the registered cherry branch and
canopy parameters, mangrove branch and leaf scatter programs, and
mangrove root geometry and materials. The native engine keeps the
existing geometry for saved profiles without these optional fields.
Mangrove roots use the loaded replacement sets and distinguish dry,
waterlogged, and muddy states. Beehive and hanging propagule
decorators remain reported approximations.

Selector branches now share the placement predicate objects until
their material IDs are bound. A copied, still-empty sapling survival
predicate previously suppressed some of the exported tree choices.
The fixed-biome tree pack verifies 256 generated chunks, including
the stripped cherry override on all three log axes, connected cherry
and mangrove leaves, and all three mangrove root cases.

Run the verified noise and catalog exports above, using a fresh world
with `minecraft1201/src/datagen/fixtures/overworld-tree-pack` for the
datapack check. Compare existing profiles through the same Java 25
FFM transport with both native libraries, using fresh destination
files for every region.

The normal Overworld exports 84 of 121 non-ore roots. All 15 catalog
parity cases passed, and all three Java 17 distributions carry the
same five-variant catalog. The locally built 26.3 distribution and
the three 1.20.1 distributions share the same native binary.

Region `-1,0` from a saved 1.20.1 profile produced 1,024 identical
decoded chunks with the old and new libraries. On the M4 Max,
three alternating runs of the same tree-bearing 26.3 region
produced 1,024 identical decoded chunks per pair. Baseline Metal
times were 687–782 ms and candidate times were 681–761 ms.

## End cities

The 1.20.1 adapter exports the loaded End city biome membership,
weighted random-spread sets and all 19 resource templates into the
shared native city program. The structure-enabled normal catalog
variants include cities. The structure-disabled and default Bukkit
variants omit them. Fabric and Forge read and install the saved city
starts and references through the 1.20.1 structure codecs.

Generate a fresh seed 12345 world using
`./gradlew :minecraft1201:exportNoiseTerrain -PretinaGenerateStructures -PretinaVerifyNoiseTerrain`
with the Java 17 toolchain and host native library as above.
The verifier derives a city with a ship using the actual 1.20.1
planner, generates the containing region, and reloads the saved
pieces with Minecraft's structure reader. The vanilla case at chunk
`-198,-38` matched all 59 piece tags and included 48 references,
four loot chests, one elytra frame, and 25 shulkers. Reopening
preserved all 1,024 records.

Copy `minecraft1201/src/datagen/fixtures/end-city-pack` into another
world's `datapacks` directory and repeat. The loaded salt of 700131
selected a different city at chunk `-176,85` with 60 matching pieces
and 28 references. Seed 12345 vanilla Metal and CPU output matched
all 1,024 uncompressed records in region `-7,-2`. Single local runs
took 244 ms on Metal and 481 ms on CPU.

Pass the generated vanilla `DIM1/region/r.-7.-2.mca` to
`scripts/minecraft1201-end-city-test.py --reference`. The script
uses the packaged Java 17 mod, a local Fabric launcher or Forge
installation, and the bundled structure-enabled definition. It
checks the loot table and elytra through live server commands,
compares the saved start and reference, and reopens the entity
storage after restart with the same frame UUID. In the concurrent
local run, Fabric reached startup in 23.3 s and Forge in 19.3 s.
Their restarts took 8.1 s and 9.0 s, respectively.

All 15 catalog cases passed. The four local JARs carry the same
native binary. A separate 26.3 Metal comparison of region `-7,-2`
matched all 1,024 decoded records, including city data and the
ship frame. Single region runs took 162 ms with each library.

The 1.20.1 structure exporter covers End cities in this cycle.
Placement exclusions, entities embedded in custom city templates,
and the native structure locator remain open.

## Nether fossils

The structure-enabled 1.20.1 catalogs now contain all 14 loaded
Nether fossil templates and their registered random-spread placement.
The End city and fossil exporters share the registry-to-placement
translation and typed template NBT. Nether fossil height anchors
resolve against the generator's 128-block volume. Native placement
uses the structure context random stream and the generated Retina
terrain for the cavern floor. Saved starts use Minecraft's
`minecraft:nefos` piece codec. Dried ghasts remain available for
profiles that supply their material states.

Run `./gradlew :minecraft1201:exportNoiseTerrain -PretinaGenerateStructures -PretinaVerifyNoiseTerrain`
with the Java 17 toolchain and host native library as above.
Seed 12345 Nether region `1,0` contained four fossil starts and
41 rotated bone blocks. The reader verified each start and the
region reopened with all 1,024 records preserved.

Copy `minecraft1201/src/datagen/fixtures/nether-fossil-pack` into a
fresh world's `datapacks` directory and repeat. The loaded
salt of 7310027 and fixed Soul Sand Valley biome produced 68 starts
and 867 rotated bone blocks. Of those blocks, 197 crossed a chunk
boundary and retained the reference to their start. The 47 starts
accepted by both the reference planner and Retina agreed on
horizontal origin, rotation, and template. One also matched the
exact floor height. The stock Nether sample's four starts used
Retina cavern and biome locations outside the reference placements
in that region.

CPU and Metal matched all 1,024 structure compounds in the
fixed-biome region and 1,021 of its complete uncompressed records.
The verified region runs took 1.26 s on Metal and 22.49 s on CPU
under separate local loads. On the 26.3 Nether region `0,0`,
both libraries produced all 1,024 decoded records identically,
including 15 fossils. The Metal runs took 869 and 851 ms.

The packaged Java 17 Fabric server generated and reopened the
stock-catalog fossil in chunk `46,18`. Forge exported the loaded
datapack and reopened the start at `-20,4` and its cross-chunk
reference in `-20,3`. In both cases the live server verified the
bone block and retained the saved definition through restart.
All 15 catalog parity cases passed. The four local JARs share
the same native library. The default Bukkit catalog remains
structure-disabled.

## Structure lookup

The 1.20.1 mod looks up supported structure sets from the world
definition. For each candidate, loaded or saved starts take
precedence over the native plan. A read-only locate leaves an
unexplored region absent on disk. Explorer maps claim a selected
start through Minecraft and persist its reference count.

Run `scripts/minecraft1201-end-city-test.py` with the packaged mod
and the seed 12345 headless End region `-7,-2`. On local Java 17
Fabric and Forge servers, `/locate` found the city at block
`-3168,-608` before its region existed, then found it again after
restart. Its 59 saved pieces, cross-chunk reference, ship frame,
and chest loot matched the headless output. With
`--explorer-map-pack minecraft1201/src/datagen/fixtures/structure-locate-pack`,
Forge returned a filled map on the first call and an empty map
on both the repeat call and restart. The city's saved reference
count was one. A separate Fabric run with an `INVALID` start
already saved in that chunk found another city 336 blocks away.
The single-record test region retained its original membership.

Java 17 datagen verified a 64-candidate JNI batch and the native
invalid-index error. A Java 25 FFM probe returned the same End city
and Nether fossil starts for the exported 1.20.1 definition.
All 15 catalog parity cases passed. On 26.3, a before/after Metal
run in End region `-7,-2` matched all 1,024 decoded chunks.

## Optional F3 telemetry

On 1.20.1 Fabric and Forge, open F3 in a Retina client with
`f3_overlay = true`. It shows the current dimension's completed
terrain throughput, recent region latency, in-flight work,
prepared and published regions, and failures. Publication
counts are separate from terrain throughput. A server answers
only clients requesting the overlay. It uses the same rolling
throughput and latency calculations as 26.3.

Run `scripts/minecraft1201-telemetry-test.py` with the packaged
Fabric server launcher or a Forge server installation, Java 17,
and the matching mod JAR. A protocol 763 client with no loader or
Retina negotiation logged in and received terrain on both
servers. It received no unsolicited reply, a malformed request
was ignored, and 12 immediate valid requests received one
reply. After a console teleport to the Nether, the next reply
identified the Nether. On both runs the Overworld and Nether
samples reported four prepared regions, four publications,
4,096 completed chunks, and no failures.

A Java 17 Fabric client pre-launch probe loaded the packaged
client mixins and exited before graphics initialization. The
Forge package contains reobfuscated injection mappings for
both client mixins. Graphical rendering was not exercised.
The 26.3 `generationMetricsTest` verified the rolling statistics,
packet round trip, and F3 formatting after the shared types
moved into the Java core.

## Chunky 1.3.146

Run the packaged Java 17 server probe with a fresh output directory:

```sh
python3 scripts/minecraft1201-chunky-test.py --java "$JAVA17" \
  --server "$FABRIC_SERVER" --fabric-api "$FABRIC_API" \
  --mod "$RETINA_FABRIC_1201" --chunky "$CHUNKY_FABRIC_1201" \
  --output "$FRESH_OUTPUT" --backend metal
```

On Forge, replace `--server` and `--fabric-api` with
`--forge-install "$FORGE_INSTALL"` and use the Forge Retina and
Chunky JARs. The Fabric run used Loader 0.16.14 and Fabric API
0.92.12+1.20.1. The Forge run used 47.4.28. Both used the published
Chunky 1.3.146 artifacts. See the [Chunky integration notes](../CHUNKY_REGION_SCHEDULING.md#minecraft-1201).

Both servers started, paused, resumed and cancelled real Chunky
tasks. On a new nine-chunk selection in MCA region `-28,27`,
Retina's ahead request finished before Chunky reported all nine
chunks complete. After restart, the same selection completed
without native preparation and preserved a diamond-block edit.
After retaining only that edited record in the fixture, both
servers reported 1,023 generated records and one preserved
record, then read the diamond block through Minecraft. Each
shutdown cleaned up all its temporary Retina region previews.

Observed selection completion on Metal was 7.60 s on Fabric
and 7.83 s on Forge with no destination file, and 0.15 s and
0.14 s on the saved region. These concurrent smoke runs are
functional timings. Forge without Chunky also joined and
generated terrain in the existing headless telemetry probe.
The shared region-preparation suite and Bukkit unit tests passed.

## Distant Horizons

The 1.20.1 adapter serves detached Distant Horizons generation from
the same saved definition and region scheduler as live Minecraft
generation. Detached work uses background priority and retains up
to eight temporary MCA regions per dimension, bounded by the
configured region limit. Later Minecraft loads can publish a
retained region on the owning I/O queue. Closing the world
releases the temporary files.

Run the headless server fixture with the published
[DH 2.4.5-b](https://modrinth.com/mod/distanthorizons/version/lC6CwqPp)
or [DH 3.3.3](https://modrinth.com/mod/distanthorizons/version/6UnEfsRQ)
JAR:

```sh
python3 scripts/minecraft1201-distant-horizons-test.py --java "$JAVA17" \
  --server "$FABRIC_SERVER" --fabric-api "$FABRIC_API" \
  --mod "$RETINA_FABRIC_1201" --dh "$DISTANT_HORIZONS" \
  --chunky "$CHUNKY_FABRIC_1201" --output "$FRESH_OUTPUT" --backend metal
```

On Forge, replace `--server` and `--fabric-api` with
`--forge-install "$FORGE_INSTALL"` and use the Forge mod JARs.
For the DH 2.4.5 comparison, run the old Retina JAR with
`--baseline`, then pass its world directory to the new run as
`--reference-world`. Use `--check-chunky` with DH 3.3.3 to
exercise the interaction with Chunky 1.3.146.

The 32-chunk End request touched nine Retina regions on Java 17
Fabric and Forge. DH populated its own database and the request
left the Minecraft region directory untouched. A subsequent
Minecraft force-load reused the preview for `-29,28`, publishing
all 1,024 slots without another native preparation. Both
loaders preserved all 257 detailed LOD records from the 2.4.5
baseline, including the payload, generation steps and mapping.
Foreground chunk loads can add separate LOD records. Shutdown
removed all temporary previews.

DH 3.3.3 previously attempted to bind a Chunky listener while the
provider was still starting, which crashed both test servers.
Both now start with Chunky 1.3.146 installed and complete the
detached and foreground phases. Each real Chunky test task
caused DH to pause generation and then re-enable it.
The shared region-preparation tests, Bukkit tests and targeted
26.3 Fabric storage and Chunky startup tests passed locally.

In the final concurrent smoke runs, DH 2.4.5 pregen took
7.08 s on Fabric and 14.80 s on Forge. DH 3.3.3 took 8.50 s
and 18.32 s, respectively. These timings include local load.
The repeat suite also covered the shared biome palette lookup
after removing Minecraft's mutable memoizer from the decoder.

## Overworld landmarks

The structure-enabled 1.20.1 catalogs include desert pyramids,
jungle temples and swamp huts. The exporter captures the actual
1.20.1 pieces and the pyramid archaeology pass against a
controlled surface. Each family has one deterministic captured
layout. The shared native planner projects and rotates those
templates, uses the saved registry placements and biome rules,
and writes the resulting starts, references, and metadata.
Fabric registers the piece before the built-in registries freeze.
Forge registers it through its registry event.

Copy `minecraft1201/src/datagen/fixtures/landmark-pack` to
`<fresh-world>/datapacks/retina-landmarks` and run:

```sh
./gradlew :minecraft1201:exportNoiseTerrain -PretinaGenerateStructures \
  -PretinaVerifyNoiseTerrain "-PretinaWorld=$FRESH_WORLD" \
  "-PretinaNativePath=$RETINA_NATIVE" -PretinaGpuBackend=metal
```

The seed 12345 fixture checks actual random-spread placements
from the loaded pack and generates Overworld region `-2,2`.
The reader found three starts and nine references, six brushable
pyramid blocks, four pyramid chests, two temple chests, two
temple dispensers, and both swamp hut residents. Reopening
preserved all 1,024 records. A CPU run matched all 1,024 structure
compounds and 1,011 complete decoded records. Region generation
took 2.55 s on Metal and 15.13 s on CPU in separate local runs.
All 15 bundled catalog parity checks passed.

Run `scripts/minecraft1201-landmarks-test.py` with the headless
MCA as `--reference` and the fixture directory as `--pack`.
Packaged Java 17 servers on Fabric Loader 0.16.14 and Forge
47.2.0 and 47.4.28 found all three starts before opening the
destination region, then loaded the loot metadata and entities
through Minecraft. The test repeated the same reads after
restart and compared the saved starts with the headless
records. A separate stock Fabric world materialized the bundled
catalog and located and saved a desert pyramid at `128,-1520`.
The native library and the 26.3 catalogs were byte-identical
to the preceding build.

### Nether fortresses

The structure-enabled 1.20.1 catalog and live registry export now
capture all 13 fortress piece families, their orientations, chest
variants, supports, ticks and end filler geometry from the target
game. The shared native planner assembles the fortress. The
`nether_complexes` set keeps the loaded weights and biome
eligibility for fortress and bastion; bastions remain unavailable
in this cycle. The Nether fossil kit shares the structure table and
retains its own placement and templates.

To reproduce, copy `minecraft1201/src/datagen/fixtures/nether-fortress-pack`
to `$RETINA_WORLD/datapacks/retina-nether-fortress` in a fresh world
directory, then run:

```sh
./gradlew :minecraft1201:exportNoiseTerrain -PretinaGenerateStructures \
  -PretinaVerifyNoiseTerrain "-PretinaWorld=$RETINA_WORLD" \
  "-PretinaNativePath=$RETINA_NATIVE" -PretinaGpuBackend=metal
```

Seed 12345 generated Nether region `-1,0` with three starts, 299
pieces, 257 references to those starts, 12 loot chests, five blaze
spawners and three immediate lava ticks at the castle entrances.
One other candidate did not produce a start. The 1.20.1 region reader
opened all 1,024 chunks; regenerating preserved them all. A separate
CPU run matched all structure compounds and 1,021 complete decoded
chunk records. Initial local region generation took 1.19 s on Metal
and 8.61 s on CPU; both backends passed again with the entrance
tick locations asserted explicitly.

Run `scripts/minecraft1201-fortress-test.py` with that headless
MCA as `--reference`. Java 17 dedicated servers on Fabric Loader
0.16.14 and Forge 47.4.28 located all three starts before the
region was generated, loaded the chest loot table and spawner
data, and retained the same saved starts after restart. Stock
headless End-city and Nether-fossil checks also passed. All 15
catalog parity cases and Bukkit tests passed; all four locally
built JARs used identical macOS arm64 native bytes, with the 26.3
catalogs and native bytes unchanged from the previous cycle.

### Jigsaw structures

The 1.20.1 exporter binds villages, bastions, ancient cities, trail
ruins and pillager outposts to the shared native planner. It reads
template pools, blocks, connectors, entities and processors from the
loaded game. It preserves outpost placement exclusions, the weighted
fortress and bastion set, and the stable order of structure sets
and template indices. Feature pool elements are reported as omissions,
as in the current native jigsaw support.

Run the stock export and region verifier on a fresh directory:

```sh
./gradlew :minecraft1201:exportNoiseTerrain -PretinaVerifyJigsaws \
  "-PretinaWorld=$RETINA_WORLD" "-PretinaNativePath=$RETINA_NATIVE" \
  -PretinaGpuBackend=metal
```

The seed 12345 run checked a 119-piece snowy village, an 80-piece
ancient city, an 18-piece trail ruin, a 20-piece outpost and a
53-piece bastion. The target reader opened all 5,120 chunks and
native reopen preserved them. CPU and Metal runs agreed on all
5,120 structure compounds. The weighted Nether fixture produced
three fortress starts and one bastion start. Separate stock Java
processes exported identical structure tables for all dimensions.

For the live processor fixture, copy
`minecraft1201/src/datagen/fixtures/jigsaw-pack` into a new world's
`datapacks/retina-jigsaw` and repeat the export command. The fixture
substitutes gold blocks for packed ice in the snowy town center.
Run `scripts/minecraft1201-jigsaw-test.py` with the village region
`r.-1.0.mca` and bastion region `r.0.0.mca` from headless generation.
Use `--pack` with the fixture reference. The packaged Fabric server
loaded the fixture's gold block, and a packaged Forge server used
the stock bundled catalog. The servers located the structures and
saved and reopened the same bastion brute with its initialized
golden axe. A local Forge listener canceled the structure spawn
and the mob stayed absent after save and reopen.

All 15 catalog parity cases, the four local JAR builds and Bukkit
unit tests passed. The native library and 26.3 catalogs were
byte-identical to the previous build.

### Nether ruined portals

The 1.20.1 adapter exports the loaded Nether setup weights, all 13
portal templates with their mirror and rotation data, and the
processor, shape and fluid tables consumed by the shared replay.
The exported set retains all seven weighted slots. Overworld
portal setups remain explicit omissions, matching the current
native planner's Nether support.

Use a fresh directory for each run:

```sh
./gradlew :minecraft1201:exportNoiseTerrain -PretinaVerifyPortals \
  "-PretinaWorld=$RETINA_WORLD" "-PretinaNativePath=$RETINA_NATIVE" \
  -PretinaGpuBackend=metal
```

For the custom case, copy
`minecraft1201/src/datagen/fixtures/nether-portal-pack` to
`$RETINA_WORLD/datapacks/retina-nether-portals` first. Seed 12345
generated one stock start and chest in region `-2,0`, and four
starts and four chests in custom region `-1,0`. The custom setup
also produced jungle leaves and vines. Both backends preserved
all 1,024 records on reopen. The custom region matched across CPU
and Metal on all structure and portal loot compounds and 1,021
complete decoded chunks. Local region generation took 1.48 s on
Metal and 13.55 s on CPU.

Run `scripts/minecraft1201-portals-test.py` with the headless region
as `--reference`. Use `--pack` for the fixture. Packaged Fabric
used live registry export and Forge used the stock bundled
catalog. The servers found starts before publishing the region,
read the exact loot table and seed from Minecraft, and kept the
same starts and loot after restart.

All 15 catalog parity cases, Bukkit unit tests and the four
local JAR builds passed. The native library and 26.3 catalogs
match the preceding build byte for byte.

### Vines, glow lichen and sculk veins

The 1.20.1 exporter now binds the loaded vine and multiface
configurations to Retina's shared attachment planner. It uses
the target game's face support, allowed block holders, spread
order and waterlogged states. The stock Overworld inventory
exports 88 of 121 placed roots, including both vine roots, glow
lichen and sculk veins.

Copy `minecraft1201/src/datagen/fixtures/attachment-pack` into a new
world's `datapacks/retina-attachments` and run:

```sh
./gradlew :minecraft1201:exportNoiseTerrain -PretinaVerifyAttachments \
  "-PretinaWorld=$RETINA_WORLD" "-PretinaNativePath=$RETINA_NATIVE" \
  -PretinaGpuBackend=metal
```

Seed 12345 generated 1,941 vines, 3,406 glow lichen blocks and
8,548 sculk veins in custom dimension region `-1,0`. The target
reader checked 14,646 attachment faces, including 564 that
crossed a chunk boundary, 1,935 waterlogged blocks, and the
fixture's wall and support tag restrictions. Reopen preserved
all 1,024 chunks. CPU and Metal agreed on all attachment states
and 1,018 complete decoded chunks. The region took 0.95 s on
Metal and 11.46 s on CPU in separate local runs.

Run `scripts/minecraft1201-attachments-test.py` with the generated
`dimensions/retina/attachment_probe/region/r.-1.0.mca` as
`--reference`. Packaged Fabric and Forge generated the custom
dimension and preserved the exact sampled vine, waterlogged
lichen and waterlogged sculk vein states after restart.

All 15 bundled catalog parity cases, the stock headless
round trips, Bukkit unit tests and the four local builds passed.
The 26.3 catalogs and shared macOS arm64 native bytes match the
previous build.

The full Java 17 Spigot acceptance run on Metal matched 917,504
block states and 14,336 quart biomes across 12 chunks both
before and after restart. The imported definition, metadata
and entity probes passed, and all three player edits survived.

### Flat features, lakes and structures

The 1.20.1 adapter now exports the game's adjusted Flat biome settings
and selected structure sets. The base and deferred layers still come
from the selected preset. Default Flat uses Retina for its villages
when structures are enabled. Strongholds remain a reported omission
because the shared planner does not implement concentric-ring placement.
The lake recipe records the 1.20.1 horizontal origin. Older definitions
keep the existing centered origin. The shared lake model covers
excavation, fluid and barriers. The 1.20.1 variant also applies its
registered water-lake freezing step.

Run the stock preset in a fresh directory:

```sh
./gradlew :minecraft1201:exportFlat -PretinaVerifyFlatWorldgen \
  "-PretinaWorld=$RETINA_WORLD" "-PretinaNativePath=$RETINA_NATIVE" \
  -PretinaGpuBackend=metal
```

For the custom case, copy
`minecraft1201/src/datagen/fixtures/flat-worldgen-pack` into a fresh world's
`datapacks/retina-flat-worldgen` first. Seed 12345 produced a 96-piece
stock plains village and a custom Desert pyramid, 7,419 lake lava
blocks, 261,592 deferred torches and 1,024 blocks from the selected
decoration. The fixture's Void dimension generated its spawn platform.
CPU and Metal matched all 3,072 decoded chunks across the three
regions, and native reopen preserved every chunk. The stock region
took 94 ms on Metal and 894 ms on CPU. The decorated Desert region
took 335 ms on Metal and 1.50 s on CPU in separate local runs.

Run `scripts/minecraft1201-flat-worldgen-test.py` with the generated
Overworld `r.-1.0.mca` as `--reference`. Use `--pack` for the Desert
and Void fixture. Packaged Fabric located the stock village and
packaged Forge located the custom pyramid. Forge read the sampled
lake, torch, decoration and Void platform through the game. Both
servers preserved the structure records and a player edit after
restart and kept the original saved definition.

The original Flat layer round trips, focused native Flat test,
Bukkit tests, all 15 catalog parity cases and all four local builds
passed. All four JARs contain the same rebuilt macOS arm64 library.
The 26.3 catalogs match the preceding build, and the old and new
native libraries produced identical NBT for all 1,024 chunks in a
26.3 Overworld region.

### Surface snow and ice

New 1.20.1 definitions carry the loaded `freeze_top_layer` placement,
the biome temperature modifiers, the temperature noise tables and
the snow support and snowy block states. The shared engine applies
the feature in registry order against the live motion-blocking
heightmap. It freezes exposed source water and places single-layer
snow with the corresponding snowy ground state. This runs during
decoration, ahead of native region lighting. Existing saved
definitions retain the feature data they were created with.

Run the headless verifier in a fresh directory:

```sh
./gradlew :minecraft1201:exportFlat -PretinaVerifySurfaceFreeze \
  "-PretinaWorld=$RETINA_WORLD" "-PretinaNativePath=$RETINA_NATIVE" \
  -PretinaGpuBackend=metal
```

The verifier loads a real datapack with five Flat dimensions. It
compares every column's generated block states with the vanilla
1.20.1 biome temperature checks. The cases cover cold and warm grass,
the altitude threshold, the frozen biome temperature modifier over
water, and the same cold biome with Flat features disabled.

Seed 12345 matched vanilla's temperature decisions for all 1,310,720
columns on both CPU and Metal. The two backends produced identical
NBT for all 5,120 chunks. The frozen-water case produced 122,367 ice
columns, and the altitude case produced 132,355 snowy grass columns.
The stock Overworld verifier now accounts for 89 supported and 32
omitted non-ore roots and observed snow in its generated region.

### Concentric-ring structure placement

The 1.20.1 Overworld and Flat adapters can export a concentric-ring set
when the native engine supports its structure family. The saved data
includes the distance, spread, count, preferred biome membership,
frequency, locate offset and exclusion dependencies. Rust computes and
caches the seeded positions. The Java locator reads the same positions
through the native interface. Flat follows the 1.20.1 zero ring seed,
and the noise generator uses the world seed.

Run the headless verifier in separate fresh directories for CPU and Metal:

```sh
./gradlew :minecraft1201:exportNoiseTerrain -PretinaVerifyRings \
  "-PretinaWorld=$RETINA_WORLD" "-PretinaNativePath=$RETINA_NATIVE" \
  -PretinaGpuBackend=cpu -PretinaSeed=12345
```

The fixture uses a supported pyramid family with registered rings and
checks five sets in Flat, fixed-biome and multi-noise dimensions.
It covers an active exclusion, an inapplicable exclusion set and
preferred biome relocation. Seeds 12345 and -129834789 produced the
same native ring coordinates on CPU and Metal. The preferred-biome
subset matched all 18 relocations when the 1.20.1 reference algorithm
used Retina's CPU biome samples. Vanilla's own climate matched 8 of
those 18 positions. The Metal biome samples agreed on 17 of 18.
Ring placement always uses the CPU biome source.

The verifier also reads the saved pyramid start and its reference with
the 1.20.1 region reader and confirms that reopening preserves all
1,024 chunks. Native stronghold piece generation remains a separate
omission.
