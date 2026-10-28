# Bundled vanilla catalogs in Fabric and NeoForge

The mod JARs package reusable vanilla type/generation data for Minecraft 26.3.
For a new normal vanilla world, Rust materializes `retina.dat` using the actual
world seed. Generation then reads that saved file exactly as on subsequent
starts. Structures and bonus chest each support both values. All three standard
dimensions are included; the End activation and saved-file upgrade paths remain
unchanged.

An existing `retina.dat` is opened before inspecting catalog eligibility. Its
bytes remain authoritative, including when packs or mods have changed. Additional
mods, selected data packs, non-normal presets and custom dimension settings use
the existing live registry compiler on first creation. The mod does not require
a vanilla-only setup to generate a world; that setup only selects the cache.

Fabric recognizes Retina, the loader, Java/Minecraft, MixinExtras and official
Fabric API modules, including Loom's separate development containers. NeoForge
recognizes Minecraft, NeoForge and Retina. Unknown mod environments use live
compilation. Selected packs must belong to the recognized framework, and every
serialized dimension stem must equal the normal world preset. A resource or
native materialization error on a selected catalog reports its actual error.

## Two exact runtime snapshots

Minecraft 26.3's binary `BitRandomSource.nextDouble` uses double arithmetic.
The recompiled source uses float arithmetic before widening the result to a
double. This changes provider-noise offsets and must not be rounded away in a
parity test. The mods package both snapshots and observe three values from the
running game's RNG to select the exact implementation. Unknown implementations
use live compilation. Loader name alone does not select the snapshot.

| Source directory under `assets/world-definitions/26.3/` | JAR directory under `catalogs/26.3/` | Gzip bytes | SHA-256 |
| --- | --- | ---: | --- |
| `vanilla` | `vanilla-recompiled` | 5,274,690 | `1d36669962947ee6fce5ddfb7029d0fa06eda32647297227b8f9116bbb8dc150` |
| `vanilla-binary` | `vanilla-binary` | 5,275,111 | `e357b33fa93c757a80e8aa122343fc899c357b9120961376036b2a9008046e63` |

Each asset has a readable provenance/limitations manifest. Both are below
50 MiB and are ordinary Git assets. The original `normal`,
`normal-no-structures` and `bukkit-api` variants in the recompiled asset are
structurally identical to their previous versions; two bonus-chest variants
were added. The Bukkit plugin continues to consume its existing snapshot and
delivery policy. Native source, ABI and shaders did not change.

Regenerate explicitly from the actual Minecraft runtimes:

```sh
./gradlew :common:exportVanillaCatalog --no-watch-fs
./gradlew :fabric:exportVanillaCatalog --no-watch-fs
```

Both accept `-PcatalogOutput=/absolute/output/directory` for independent
reproducibility runs. Independent re-exports of both snapshots and their
manifests were byte-identical to the committed inputs. Ordinary builds only
package those assets.

## Executed verification

`BundledVanillaDefinitionTest` compares complete native metadata and every
dimension's full diagnostic generation profile against independent live Java
exports for all four structures/bonus-chest combinations, using seeds
`-9223372036854775791`, `-1`, `9223372036854775796` and `0`. Comparisons are exact,
including floating-point noise offsets. It also checks custom packs, unknown
mod environments, flat/large-biomes/amplified/single-biome presets and extra or
missing dimensions select the live compiler. The existing resource-authority
integration test passes alongside these three tests.
All sixteen Bukkit tests pass with the expanded recompiled asset. The JDK-only
native catalog bridge passes all four mod variants and verifies exact seed and
option metadata, saved-file reopen and preservation of a malformed destination.

Actual dedicated Fabric and NeoForge development servers each passed:

1. Fresh normal world creation with the live compiler disabled. Fabric selected
   `vanilla-binary`; NeoForge selected `vanilla-recompiled`.
2. End activation, automatic acceleration in the standard dimensions, player
   edits, world save and native-owner closure after draining work.
3. Restart with both compiler and catalog materializer disabled, preserved
   player edits and byte-identical `retina.dat`.
4. Fresh creation with a real selected, generation-neutral data pack and the
   catalog materializer disabled, proving live compilation still executes.

An independent ctypes reader opened the resulting cached and live-exported
files through the production Rust ABI. On each loader, all three full generation
profiles match exactly; only exporter provenance differs in native metadata.
Fabric used seed `-9223372036854775791`; NeoForge used `9223372036854775796`.
These are host runtime checks on macOS 27.0.1, Apple M4 Max/Metal and Java 25.0.1.

Both universal mod JARs were assembled and inspected: each contains exactly
these two catalogs and their manifests, plus all six previously built native
images. Every native image is byte-identical to the preceding universal Bukkit
artifact because native source is unchanged. Foreign platforms were packaged,
not executed in this host run.

Evidence is retained outside the temporary implementation worktree under
`~/.cache/retina/mod-vanilla-catalog-20261027/`, including server logs,
`runtime-report.json`, `runtime-profile-parity.json` and the generated worlds.
The native image SHA-256 is
`83fd7bf0c42f84636bbc430414e9ab1d98aa3ec6c67b695943508507c14be2a2`.
