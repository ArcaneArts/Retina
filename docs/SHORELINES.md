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

### Aligning the climate coast with the generated coast

The approximate GPU density graph can put sea-level crossings away from the
continentalness graph's coastal interval. A climate-only lookup consequently
produced inland beach patches and grassy physical shorelines. Density-based
registered profiles now align coastal selection with the resident GPU height
field before choosing the material program.

A second resident climate index contains the source's tagged shore targets,
deduplicated with the same registry-order ties as the surface index. It searches
temperature, humidity, erosion, weirdness and offset, retaining each target's
original continentalness interval. Near physical water, the GPU projects only
continentalness into the chosen interval's interior and repeats the full source
lookup. That lookup can still choose a river or another registered coastal
alternative. The shader embeds no vanilla continentalness interval, biome name,
temperature cutoff or sand material ID. Unreferenced shore tags do not create
inland temperature/rainfall niches.

Physical eligibility is an approximation: ground between sea level minus two and
plus six blocks, with water beside dry ground or land beside shallow water in the
uncarved density height field, using neighbor probes four, eight and sixteen blocks
away. Flat low inland ground and open shallow ocean plateaus do not qualify.
The GPU height/density halo
extends to sixteen blocks, including diagonal probes at negative region edges.
Synthetic lake carving and cave openings cannot create eligible water. This is
not an ocean-connectivity flood fill; naturally low inland water can receive a
registered coastal transition. Explicit-height and older non-registry profiles
retain their previous selection. Packs without registered shore placements do
not acquire manufactured beach biomes, and registered inland sand features or
deserts remain intact.

All added height samples and the coastal index stay on the device. The existing
12-byte column format and per-region readback layout are unchanged. Sparse biome
queries now compute the needed GPU height field for these profiles, still returning
only one record per requested center. They skip synthetic lake probes and match
cached queries and the MCA path.

Complete material runs use this same per-block surface biome in the upper twelve
blocks. Previously they used the cached 4×4×4 biome grid instead: a coastal quart
could paint sand onto inland grass columns, or an inland quart could leave square
grass holes in a beach. This was a sampling-resolution mismatch, not a loss of
floating-point precision with distance. The correction runs inside the existing
GPU material pass, adds no dispatch/readback, and retains the quart field for
underground rules. Sand/gravel sediment masks and registered coastal targets are
still evaluated from their loaded data.

On an Apple M4 Max, twenty full regions with five warmups and actual structures /
decorations measured 103.05 ms average vanilla and 109.62 ms Terralith (9,912 and
9,316 native chunks/sec). Diagnostic runs disabling coastal tags to retain the
previous climate-only selection measured 107.16 and 102.29 ms. These fidelity
changes alter biome, decoration and structure workloads; this single comparison
does not isolate a speedup. The observed change was about -4% vanilla / +7%
Terralith region time. GPU height work increased from 0.00332 to 0.00385 ms/chunk
vanilla and 0.00823 to 0.01161 Terralith. GPU column work increased from 0.00615
to 0.00772 and 0.01355 to 0.01518 ms/chunk respectively.
Readback stayed approximately 20 MB per region, with no new transferred fields.
Serial peak RSS was 881 MiB vanilla / 1,423 MiB Terralith. The final profiles retain
registered features and structures, and generated file totals were 135,274,496
and 112,930,816 bytes. This is deliberately different terrain, so exact old/new
NBT identity is not a validation criterion for the coastline change.

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
unsupported; sandy beach surfaces still come from its material rules. This initial
sediment milestone used two representative materials; [complete GPU material
runs](GPU_MATERIAL_LAYERS.md) now evaluate registered deeper transitions as well.

Water conditions now recognize dry columns: vanilla treats a missing water surface
as satisfying its water predicate. A filler query below sea level on dry ground
must not invent a submerged column.

## Validation

`./gradlew shoreTest` loads Minecraft's actual world registries and runs Metal/
Vulkan/DX12 compute. It checks all three beach types against exported registry
intervals, changes an interval to verify GPU selection changes, checks their actual
surface materials, verifies dry/submerged water conditions and tests registered
sediment replacement on soil inputs with height/fluid restrictions.
Its sloping-density fixture deliberately disagrees with the climate coastline:
coastal and inland climate inputs both select a registered beach beside water,
while disconnected high ground does not. The previous native implementation fails
the inland-ground assertion. The actual Terralith test runs the same fixture with
its loaded material rules and climate intervals. A Rust real-GPU test compares
cold sparse queries, both execution paths, individual chunks and four neighboring
region tiles across negative coordinates. The real vanilla landscape sampling
also verifies that selected shore biomes stay near sea level.

The diagonal-coast regression fixture crosses quart boundaries at four subcell
phases with both gradient directions, including negative chunk coordinates.
It compares the upper four material
layers with each column's selected surface program in interpreter and specialized
GPU modes, for both loaded vanilla and Terralith registries. The previous library
fails at X=25, Y=68, Z=0: the column requests grass while the material run writes
sand. The corrected implementation checks 32,768 surface voxels per profile.
For each profile it also creates a full negative-coordinate MCA and compares all
884,736 blocks in nine edge/diagonal chunks with independent chunk generation.
Actual Minecraft material-reference checks cover another 6,680,576 voxels, and
temporary-region promotion, cold base columns and concurrent edits pass.

`./gradlew datapackTest` loads the real Terralith ZIP and verifies its three beach
recipes affect GPU material output. The broader biome and datapack tests compare
chunk output with decoded MCA blocks, including negative region boundaries and
temporary-region promotion. `landscapeTest` checks ocean coverage and biome spacing
with the actual default preset.

### Surface-resolution correction measurements

The final release library, actual full vanilla/Terralith profiles including
structures and decorations, seed 123456789, two warmups and twenty regions per
run measured the following on Metal / Apple M4 Max. Runs were sequential, with no
concurrent builds/tests; other user applications remained active.

| Profile / callers | Mean request ms | Native chunks/sec | Peak process RSS MiB |
| --- | ---: | ---: | ---: |
| Vanilla / 1 | 126.66 | 8,009 | 1,005 |
| Terralith / 1 | 153.27 | 6,670 | 1,633 |
| Vanilla / 2 | 191.98 | 10,277 | 984 |
| Terralith / 2 | 245.93 | 8,300 | 1,624 |

Concurrent throughput uses actual elapsed time rather than overlapping request
latencies. Each concurrent output matches all 20,480 decompressed chunk records
from its serial run. This correction intentionally changes surface materials, so
old/new NBT identity is not expected. These results do not isolate a speedup.

Serial upload/readback averages were 14.25 / 37.63 MB per vanilla region and
12.38 / 33.98 MB per Terralith region. Twenty-file totals were 152,584,192 and
129,253,376 bytes. The correction adds no dispatch, GPU buffer or transfer field;
the existing variable material-run payload changes with the corrected materials.
Native initialization / profile registration took 501 / 477 ms vanilla and
36 / 974 ms Terralith. First regions took 2.99 / 33.40 seconds in deliberately
forced specialized mode with the changed shader identity; warmed concurrent runs
started in 0.67 / 2.65 seconds. These include driver compilation/cache effects.
Production automatic mode retains the interpreter while specialization is pending;
this fix does not resolve the broader cold-compilation workstream.

Evidence is in `build/goal-baseline/shore-resolution/*/measurements.json`,
`validation.log` and `final-shore-validation.log`. The tested/packaged native SHA-256 is
`4131791b3ad92901c6a72b71c91cee0a25ef2c8303f920ac453437e7b705eefe`.

Restart the client and create a new world to use the new default coastal source.
Saved worlds retain their serialized biome source and existing terrain. Worlds
already using a pack's imported source receive the new sediment logic in freshly
generated terrain. Existing generated chunks and cached MCA regions retain their
stored materials.
