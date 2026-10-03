# Registered shorelines

Both new world presets import the complete registered Overworld multi-noise biome
pool, including `beach`, `snowy_beach` and `stony_shore`. GPU biome selection uses
the source's actual intervals for temperature, humidity, continentalness, erosion,
weirdness and offsets. Surface and underground climate lookups use resident
bounding-box indices; see [the generation upgrade](GENERATION_UPGRADE.md).
Older saved sources retain their explicit biome pool.

Vanilla identifies coastal climate zones through continentalness and selects their
biomes with temperature, humidity, erosion and weirdness. Its main coast interval
is -0.19 to -0.11 continentalness; some combinations choose inland or river biomes
instead. Beaches therefore are not a universal sand ring around every body of
water. Those numbers are read from the registry, not embedded in Retina's shader.
Sand, snowy shore surfaces, gravel and exposed stone come from registered material
rules, including their noise, height, slope and water conditions.

Enabled datapacks and mods supply Minecraft's final merged registries. When a pack
replaces the dimension's generator, Retina retains its own generation mode and
imports that generator's source and settings. Otherwise the registered Overworld
source and noise settings apply. Tagged custom beaches (`minecraft:is_beach`,
`c:is_beach`, `c:is_stony_shores`) and oceans suppress Retina's synthetic inland
water basins. Custom biomes absent from the source still use Retina's approximate
temperature/rainfall niche; tags alone cannot supply missing coastal intervals.

## Surface sediments on the GPU

Some packs use placed features rather than material rules to create shorelines.
Java projects supported soil-replacement disks and noise-counted sediment veins
into each biome's resident material program. For example, Terralith registers
`alpha/sand_beaches`, `sakura/clay_beaches` and `forest/flower/beaches`.

The projection reads replacement predicates, output states, count-provider means,
rarity, ocean-floor heightmaps or registered height ranges, disk radii/half-heights,
and fluid filters. Multiple counts multiply. Noise-based counts retain the game's
`ceil((noise + offset) * ratio)` expression and spatial noise factor, using GPU
noise. Uniform/trapezoid height providers use their registered bounds; exact
provider distributions and random placement order are approximated.

Expected attempts and footprint area become coherent coverage patches, with
spatial correlation following the registered radius. The mask accounts for the
part of the origin-height range that can reach each queried layer; fluid-filtered
origins must fit between the ground and sea level. Replacement predicates still
restrict which materials can change. Ordinary underground ores continue in Rust.

This runs inside the existing column shader. There is no extra dispatch, readback
or per-region CPU vein planning. Columns remain 12 bytes: height, biome/flags and
two 16-bit surface/filler material IDs. The profile lists projected IDs under
`registry_program.shore_features` and labels the coverage approximation in its log.
Material programs retain their uncoated root as root 1 for diagnostics/benchmarks;
root 0 is the actual generation result.

An Apple M4 Max serial six-region comparison measured 902.93 ms with the new masks
versus 898.00 ms using the same new vanilla coastal profile with uncoated material
programs (about 6,805 versus 6,842 chunks/sec). Terralith measured 718.07 versus
722.48 ms (about 8,556 versus 8,504 chunks/sec). This puts mask overhead within
roughly 1% in these runs; it does not isolate the cost of changing the default
biome-selection model or predict client throughput. Region files were temporary,
with one warmup followed by six identical region coordinates, no concurrent builds,
and the same native library. Conditional shoreline noise skips ineligible heights.

This is a fast surface approximation, not exact vanilla feature placement or a
complete block-by-block sediment volume. Conditional/random state providers,
neighbor-dependent predicates, environment scans and other unsupported placement
modifiers are not projected. Vanilla's conditional sand-disk provider remains
unsupported; sandy beach surfaces still come from its material rules. The existing
two-material column model also does not reproduce every deep sandstone transition.

Water conditions now recognize dry columns: vanilla treats a missing water surface
as satisfying its water predicate. A filler query below sea level on dry ground
must not invent a submerged column.

## Validation

`./gradlew shoreTest` loads Minecraft's actual world registries and runs Metal/
Vulkan/DX12 compute. It checks all three beach types against exported registry
intervals, changes an interval to verify GPU selection changes, checks their actual
surface materials, verifies dry/submerged water conditions and tests registered
sediment replacement on soil inputs with height/fluid restrictions.

`./gradlew datapackTest` loads the real Terralith ZIP and verifies its three beach
recipes affect GPU material output. The broader biome and datapack tests compare
chunk output with decoded MCA blocks, including negative region boundaries and
temporary-region promotion. `landscapeTest` checks ocean coverage and biome spacing
with the actual default preset.

Restart the client and create a new world to use the new default coastal source.
Saved worlds retain their serialized biome source and existing terrain. Worlds
already using a pack's imported source receive the new sediment logic in freshly
generated terrain. Existing generated chunks and cached MCA regions retain their
stored materials.
