# Reusable vanilla catalogs

`assets/world-definitions/<minecraft-version>/vanilla/retina-catalog.dat` is
compressed structural NBT containing registry-derived generation rules and
explicit seed derivation recipes. It is reusable input for creating a world.
The world's `<world-root>/retina.dat` remains an immutable, seed-bound snapshot.
The two files have distinct schemas; the normal world loader cannot execute a
catalog as a snapshot.

The Bukkit plugin and the Fabric/NeoForge mods consume these catalogs to create
ordinary vanilla snapshots. See [mod catalog selection](MOD_VANILLA_CATALOG.md)
for the mods' environment selection and the second runtime-precision snapshot.

## Publication and runtime

`NativeCatalog.create(catalog, variant, seed, worldRoot)` calls the shared Rust
materializer, which rebuilds derived values, validates complete output with
`WorldDefinition`, writes high-compression gzip, flushes/syncs the temporary file,
and atomically publishes `retina.dat` without replacing an existing destination.
Publication uses an actual same-filesystem hard link followed by temporary-file
removal. A filesystem that cannot perform that operation reports its real error.
No filename, timestamp or provenance guess substitutes for opening the input.

The Java API then reopens the published file through `NativeWorldDefinition`.
Generation never uses a catalog-backed alternative profile. Reopening an
existing world bypasses catalog materialization. Catalog upgrades do not rewrite
existing snapshots, and a malformed present snapshot is preserved.

Materialization needs neither Minecraft nor a GPU. Binding and generation retain
the existing GPU requirement. Canonical compound-key ordering and zero-timestamp
gzip make repeated native materialization byte-identical for identical inputs;
NBT widths, arrays, lists, floating-point values and embedded data remain typed.

## Schema and variants

New exports use Int `catalog_version: 2`, Int `materialization_version: 1`,
and Compound `compressed_variants`. Each named value is a ByteArray containing
an independently gzip-compressed unnamed NBT compound. The outer catalog remains
gzip-compressed NBT. Runtime loading expands only the selected variant; unrelated
variant payloads remain compressed. A malformed selected payload fails normally.

Legacy V1 catalogs with Compound `variants` remain readable. Both containers
use the same decoded variant schema. Each variant contains:

- `template`: a complete world-definition envelope without `seed`; derived
  fields are absent rather than carrying values from a capture seed.
- `seed_derivations`: dimension identifiers mapped to explicit recipes.

Version 1's `retina:badlands_bands/1` recipe contains an IntArray `materials`
identifying the seven canonical terracotta states in the dimension's local
palette. Native materialization verifies their actual references, generates the
192 bands using the existing 48-bit Java RNG semantics and selected world seed,
and inserts the resulting array before normal definition validation.

The vanilla asset contains `normal`, `normal-no-structures`, their
`-bonus-chest` variants, and `bukkit-api`. All include Overworld, Nether and End.
The structures-on/off profiles are separately compiled: setting a root
boolean on a structures-enabled profile would leave executable native structure
data behind. Bonus-chest variants preserve those profiles and set the world
option retained in the saved definition. Other presets,
options and content mods are not implicitly approximated by this catalog.

`bukkit-api` copies `normal-no-structures` and removes biome selection of
decoration roots containing scheduled fluid springs. The public API cannot
publish native tick queues. Recipe IDs, salts, the order of remaining roots,
and the complete type palette stay intact; the disabled roots are recorded in
the saved omission reports. This policy runs during catalog export, before
materialization, and does not rewrite existing snapshots. Other variants and
mod/native generation retain springs and structure metadata.

The readable `manifest.json` records the source versions, exporter, actual pack
inventory, variants, omission reports, compressed size and SHA-256. Source data
comes from the actual vanilla resource manager and loaded Minecraft registries
in the Retina mod development runtime, without additional content mods. No game
JAR or generated world belongs in this asset directory.

## Seed audit for Minecraft 26.3

| Exported data | Seed source and handling |
| --- | --- |
| Badlands bands | World seed; removed from templates and regenerated natively |
| Terracotta palette reservation | Fixed complete type order before band draws, preventing world-seed-dependent ID assignment |
| Provider-noise permutations | Provider-local saved seed; captured unchanged |
| Decoration simplex permutation | Fixed exporter seed 2345; captured unchanged |
| Geology, ore and shoreline predicates | Fixed exporter seed 0; captured unchanged |
| Structure/template captures | Fixed capture recipes/seeds, including 1945; captured unchanged |
| Fortress end filler and End spike shuffle | Existing native generation recipes consume the loaded world seed |
| Terrain/climate noise, feature and structure placement | Existing native generation consumes the loaded world seed |

`TerrainFeatureProfile`, `ProviderNoiseProfile`, `DecorationProfile`,
`GeologyProfile`, `OreFeatureProfile`, `ShoreMaterialProfile`, `StructureProfile`,
`FortressProfile`, `EndTerrainProfile`, `EndSpikeProfile`, `ClimateNoiseProfile`
and `RegistryGpuProgram` were inspected for world-seed inputs. New seed-dependent
export calculations need an explicit derivation recipe and corresponding parity
coverage before catalogs can reuse them.

The fixed palette reservation affects future exports only. Existing snapshots
retain their palettes. Before making the reservation change, direct exports
were retained for comparison against materialized and new direct exports.

## Export and verification commands

Explicitly refresh the committed asset with the actual registry compiler:

```sh
./gradlew :common:exportVanillaCatalog --no-watch-fs
# Minecraft binary snapshot used by the mods when its RNG matches the runtime:
./gradlew :fabric:exportVanillaCatalog --no-watch-fs
```

Ordinary builds consume committed assets and do not run this exporter. Use
`-PcatalogOutput=build/catalog-repro` for an independent reproducibility run.

Produce independent reference worlds for five signed/full-width seeds and both
variants, then compare complete loaded metadata, structural profiles and GPU
tables:

```sh
./gradlew :common:vanillaCatalogFixtures --no-watch-fs
RETINA_CATALOG_FIXTURES="$PWD/build/vanilla-catalog-fixtures" \
  cargo test --manifest-path native/Cargo.toml --lib \
  java_catalog_matches_all_direct_exports -- --ignored --nocapture
```

The default native unit suite checks deterministic output, actual version and
recipe errors, complete publication and preservation of an existing malformed
destination. `worldCatalogBridgeTest` executes creation/reopen through the real
FFM bridge on a classpath containing only the core JAR and JDK test classes:

```sh
./gradlew :common:worldCatalogBridgeTest \
  -PretinaNativePath=/absolute/path/to/libretina_worldgen.dylib
```

The opt-in `materialized_catalog_preserves_previous_generation` GPU test accepts
`RETINA_CATALOG_BASELINE` in addition to the fixtures variable. The baseline is
the retained direct-export directory from before palette reservation changed.
It compares 90 complete chunks across five seeds, both variants, three dimensions
and three coordinates. Section blocks are compared through canonical decoded
state content, accounting for palette-ID order; every remaining NBT field is
compared directly. Catalog, current direct export and previous export passed on
Metal / Apple M4 Max. This establishes Retina parity on that backend rather than
new vanilla parity or cross-GPU determinism.

## Repacking an existing snapshot

`./gradlew :common:repackWorldCatalog -PcatalogInput=<existing-directory>
-PcatalogOutput=<new-directory> --no-watch-fs` changes the container to V2 without
loading Minecraft registries or recapturing generation data. The tool verifies
every decoded variant against the input and updates the manifest version, byte
count and hash. Use separate output directories for the binary and recompiled
snapshots; their numerical content must remain distinct. Existing destination
files are not overwritten. This operation does not change any world's `retina.dat`.
