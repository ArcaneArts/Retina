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

The current exporter covers Flat terrain with biome decoration, lakes and
structure generation disabled. The native Flat model handles ordinary layers
and deferred fill layers. Other generator types and unsupported options have
explicit unsupported reasons in the saved file. Noise profiles and loader
installation are subsequent implementation work.

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
through `retina inspect`. Configured carvers, placed features, ore generation,
structures and the End remain implementation work. Preview definitions are
development inputs for headless generation.

Run on a disposable world using Java 17 and the shared native library:

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
