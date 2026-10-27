# Bukkit pathway acceptance

Verified on 2026-10-27 against Minecraft 26.3, JDK 25.0.1, macOS 27.0.1
(build 26A434), Apple M4 Max and the Metal backend. Dedicated servers ran with
four active Java processors, a 2 GiB Java heap and no GUI. Their only plugins
were the actual universal Retina distribution and the separate test observer.
No native override, external catalog, mod loader or Minecraft library was
provided to Retina.

## Build and artifacts

```sh
./gradlew :bukkit:build
./gradlew buildBukkit
./gradlew :bukkit:build -PretinaHostOnly
./gradlew :common:exportVanillaCatalog --no-watch-fs
./gradlew :common:worldCatalogBridgeTest -PretinaNativePath=/absolute/native/library
./gradlew bukkitServerTest -PbukkitServerJar=/absolute/path/to/server.jar
```

Install the regular plugin as described in [BUKKIT.md](BUKKIT.md). Catalog
regeneration and actual-server verification are explicitly invoked tasks;
ordinary plugin builds consume the committed assets.

The tested universal `retina-bukkit-26.3-0.399.jar` is **28,212,017 bytes**,
SHA-256 `b89a9aada3d5423be60d7aa45b4e925c4cf58a354bd892695ab7215b0d01542e`.
Its six actual native images were inspected; the host image is SHA-256
`83fd7bf0c42f84636bbc430414e9ab1d98aa3ec6c67b695943508507c14be2a2`.
Foreign Mach-O, ELF and PE images were cross-compiled and packaged, not GPU-tested.
The first complete universal build of all three distributions took 8m02s.
Subsequent Java-only validation reused those unchanged native outputs.

The committed 26.3 vanilla catalog is **2,706,404 bytes**, SHA-256
`c2146b791bbe07ccea51d683e684ec4cac96e865ef366257af0ae37d22e53f63`.
Its manifest is 8,812 bytes. The binary asset is below 50 MiB and exported
byte-identically twice. The API variant preserves both existing full variants.
Catalog materialization uses the existing Rust loader after publishing the
complete immutable world file; it is not another runtime profile path.

## Actual server results

| Server | Verified version | Create/restart/parity/metadata/unload | Failure cases |
| --- | --- | --- | --- |
| craftbukkit | 4664-Bukkit-e89124d | Pass | 5/5 |
| spigot | 4664-Spigot-bed90fc-e89124d | Pass | 5/5 |
| paper | 26.3-226 (4f4904928fb8) | Pass | 5/5 |


Every listed server passed creation and restart, 917,504 block positions and
14,336 quart-biome samples across twelve complete chunk snapshots, three
preserved player edits, and independent saved/native comparisons of all ten
End pillar crystals. Yaw normalization is compared modulo 360; runtime crystal
UUIDs belong to the server. Normalized default block properties come from actual
public API observations rather than an assumed property table.

The primary definition is 141,544 bytes, SHA-256
`5667be6d081a1270a66aa963a83a17e0d1bd5cb72253c59b863846f8eb4b73c4`.
All five supplied/materialized definition files remained byte-identical across
restart with `retina.qa.catalog.failMaterializer=true`. Standard dimensions share
one logical-world file. Independent seeds include `-9223372036854775767`, End
seed `42`, Nether seed `9223372036854775770`, and an actual mod-exported procedural
snapshot with seed `-9223372036854775785`, generated range `0..64` fitted into
Overworld storage `-64..320`.

Controlled population fixtures passed public API checks and independent saved
region decoding before and after restart: gateway age/exact exit, inventory item
identity/count/name/custom data containing `9223372036854775791`, lazy loot table
and exact negative 64-bit seed, spawner timing/count/ranges/weighted entity and
nondefault light rules, and an armor stand's position/rotation/marker/size/
invisibility/invulnerability/gravity attributes. CraftBukkit's minimum block-light
getter returns the maximum; the actual saved `2..12` range is correct and is
checked directly. Both native string and compound block palettes are supported.
Entity loading is observed through `Chunk#getEntities`, which waits for the
server's separate entity-storage load.

Each server exercised concurrent native callbacks during unload, closure of the
old native owner, materializer-disabled recreation, rejection of an earlier
lifetime's generator, and closure of all four active native owners on disable.
Spigot completed eight of 32 callbacks and correctly rejected the remaining 24
after unload. Existing definitions are opened before palette/bounds binding;
incompatible host geometry is reported without changing the file.

Each server also rejected malformed gzip, a missing material, a consistently
referenced missing biome, a seed mismatch, and unsupported full structure
metadata. All five supplied files stayed unchanged, and no failing startup
reached the server's ready marker. Spigot's positive suite passed before a
negative-fixture reference mismatch was repaired; its five negative cases were
then rerun separately and combined with that unchanged positive evidence.
Raw failed-run artifacts remain available alongside the completed reports.

## Measurements and limits

| Server | First ready (s) | Restart ready (s) | OW 9 chunks (s; requested/s) | Nether 9 (s; requested/s) | End 81 (s; requested/s) | Sampled first/restart RSS (GiB) |
| --- | ---: | ---: | ---: | ---: | ---: | ---: |
| craftbukkit | 7.01 | 103.68 | 100.29; 0.09 | 1.89; 4.76 | 1.78; 45.39 | 2.85 / 1.09 |
| spigot | 6.40 | 85.66 | 86.74; 0.10 | 2.03; 4.43 | 1.30; 62.29 | 2.67 / 1.70 |
| paper | 8.05 | 5.29 | 2.95; 3.05 | 1.29; 6.98 | 1.96; 41.35 | 2.72 / 1.59 |


Times include actual JVM/server work. Forceload rates count requested full chunks,
while the server also generates required neighbors. These are bounded end-to-end
observations, not sustained Rust terrain benchmarks. RSS is sampled process
resident memory, including Java/native/driver allocations, not GPU VRAM.

CraftBukkit/Spigot cold Overworld work includes vanilla stronghold-ring biome
searches even though structures are disabled; restart repeats this delegate work.
Normal public Bukkit APIs do not suppress it. The tested Paper build did not
incur that long delay; this is a measured server difference, not a Retina engine
optimization claim. Spigot tests use `settings.timeout-time: 120` and
`restart-on-crash: false`; its 60-second default can terminate valid slow loads.
No performance improvement is claimed from these measurements.

New worlds use the persisted `bukkit-api` policy: structures and scheduled spring
roots are disabled before materialization. Lighting, persisted heightmaps, saves,
portal routing and dragon-fight runtime records remain server-owned. Structure
starts/references/locate state, raw tick/post-processing queues and arbitrary
opaque block-entity data have no faithful delivery here. Imported snapshots retain
their rules and either report actual unsupported requirements or use the explicit
reported `allow-metadata-loss` mode. Public snapshot APIs can attempt additional
entity/item payloads; complete arbitrary chunk-NBT parity is not claimed.

The platform-neutral core, arbitrary-seed catalogs, existing snapshot loader and
six-platform packaging are shared with the mod/headless pathways. Root `check`
passed with Rust **353 passed / 84 ignored**, eight direct mod export fixtures,
JDK-only bridge/materialization checks, interrupted-write verification and both
mod modules compiling. Nine focused Bukkit tests passed, including real GPU
chunks and sparse/full biome parity. Earlier unchanged-native verification also
covered 90 semantic catalog/reference chunks, five full-width seeds and both full
catalog variants, twenty End chunks across five combined cases, and 4,096
standalone decoded chunks. No new GUI instance was launched.

CraftBukkit/NMS acceleration and a launch-wrapper distribution are deferred.
This delivery is the API-only JavaPlugin, its bundled vanilla data and Gradle
build/release pathway.

## Delivery

- [PR 314](https://github.com/ArcaneArts/Retina/pull/314): platform-neutral Java core.
- [PR 315](https://github.com/ArcaneArts/Retina/pull/315): deterministic arbitrary-seed catalogs and assets.
- [PR 316](https://github.com/ArcaneArts/Retina/pull/316): regular Bukkit generator and API-safe policy.
- [PR 317](https://github.com/ArcaneArts/Retina/pull/317): universal distribution and release staging.
- Metadata adapters, lifecycle fixes and this server acceptance suite are delivered
  together on `codex/bukkit-metadata-and-server-qa` through its own ready PR and
  squash auto-merge.

Retained local reports, logs, definitions, captures, saved worlds and the exact
tested plugin are in `~/.cache/retina/bukkit-server-acceptance-20261027/`.
