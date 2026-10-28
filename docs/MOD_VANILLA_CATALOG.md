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
| `vanilla` | `vanilla-recompiled` | 4,085,653 | `d34080051d84bf4ca0c2e59d17c294fa22495fc592d212f4ed92d8e0bffc9f17` |
| `vanilla-binary` | `vanilla-binary` | 4,088,819 | `5237034015aedda6ebd06288427bba5ac28f4a1bc72e0c3342bf4661644eb810` |

Each asset has a readable provenance/limitations manifest. Both are below
50 MiB and are ordinary Git assets. The original `normal`,
`normal-no-structures` and `bukkit-api` variants in the recompiled asset are
structurally identical to their previous versions; two bonus-chest variants
were added. The Bukkit plugin continues to consume its existing snapshot and
delivery policy. V2 packing preserves every decoded variant while allowing the
native loader to expand only the selected variant. The runtime ABI and shaders
are unchanged; legacy V1 catalogs remain readable.

Regenerate explicitly from the actual Minecraft runtimes:

```sh
./gradlew :common:exportVanillaCatalog --no-watch-fs
./gradlew :fabric:exportVanillaCatalog --no-watch-fs
```

V2 container validation and performance evidence are tracked in
[the current performance ledger](PERFORMANCE_RELIABILITY_PASS_2026_10.md).

Both accept `-PcatalogOutput=/absolute/output/directory` for independent
reproducibility runs. Independent re-exports of both snapshots and their
manifests were byte-identical to the committed inputs. Ordinary builds only
package those assets.

## Original V1 verification (2026-10-27)

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
