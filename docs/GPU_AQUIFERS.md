# Local GPU aquifers and registered surface lakes

Retina imports `NoiseGeneratorSettings.aquifers()` from the active world registry.
The resident program contains floodedness, exclusion, fluid-level spread, lava,
barrier and preliminary-surface expressions. An absent aquifer configuration
uses the game's global fluid picker; older diagnostic profiles without an aquifer
field retain their previous cave-fluid behavior. Both chunk and MCA generation
use the same GPU stages. Existing MCA files and promoted previews remain intact.

## GPU classification

Four batched passes precede material-run extraction:

1. Evaluate the registered preliminary-surface function on a globally aligned
   quart grid. `find_top_surface` retains its density, upper bound, lower bound
   and vertical search step; it is not substituted with the final terrain height.
2. Build deterministic centers on the game's 16×12×16 grid. Each center samples
   the thirteen surrounding surface positions, then derives its fluid level and
   fluid type from registered floodedness, exclusion, spread and lava expressions.
3. Sample the registered barrier field on a global 4×4×4 lattice.
4. Classify carved voxels using the twelve neighboring centers, the nearest three
   fluid statuses and Minecraft's distance-weighted pressure equations. Results
   are air, the configured default fluid, lava, or retained solid barriers.

Surface connections use the game's +8 surface adjustment and center ±12 vertical
reach. Underground fluid levels follow its 40-block bands, three-block spread
quantization, flooding thresholds and low-level lava test. Water/lava pressure
and the water-over-global-lava exception apply to actual water. Default-air or
lava settings remain valid. Global lava selection uses `min(-54, seaLevel)`.

Center/status and barrier fields remain resident GPU scratch. Two temporary
fluid bit planes are GPU-only. The existing readback exposure plane now contains
carved **air**, allowing ores to distinguish fluid-filled cavities from exposed
air without transferring another voxel mask. Compact material runs already
transport the final air/fluid/barrier columns. Per-request snapshots include both
fluid planes, so concurrently pending run emission cannot see another request's
fields. No new host synchronization is added beyond the existing count/run
readbacks.

Global cell coordinates, negative-coordinate floor division, quart surfaces and
barrier interpolation make independent chunk/region requests agree. The DH cache
continues to store completed MCA previews and private base-material companions;
its 1024-region capacity, promotion and saved edits retain their existing behavior.
Specialized aquifer passes use direct graph calls, and the material dispatcher
excludes those unreachable aquifer graphs.
[Optional invocation-local column reuse](GPU_AQUIFER_COLUMN_REUSE.md) computes
pure horizontal terrain expressions once per preliminary-surface search.
Registered search semantics and transferred data are unchanged. Concurrent
regressions keep it disabled by default; the measured tradeoffs are documented there.
F3 adds separate **Aquifer fields** and **Aquifer fluids / barriers** device timing
lines, with the same rolling-region denominator and overlap labeling as other
GPU stages. The current native timing ABI is version 7 with 29 stages.

## Registered surface lakes

Rainfall no longer invents surface-water basins. A biome requires an actual
registered `LakeFeature` with a heightmap placement. Water and lava use that
feature's fluid and barrier providers. Separate water/lava barrier IDs share one
resident packed field. Each globally aligned 8×8-chunk candidate approximates
registered rarity as `1 - (1 - 1/rarity)^64`; multiple recipes combine their
probabilities. Lava retains the previously requested quarter-width footprint.

This remains an approximation of feature placement: one basin per eligible cell,
rounded procedural geometry and containment probes replace Minecraft's ordered
per-chunk ellipsoid recipe. Height-range-only underground LakeFeatures are not
projected into surface basins. Spatial feature filters and provider variation
require the broader registered-feature workstream.

## Fidelity limits

The center jitter uses Retina's deterministic GPU hash rather than Minecraft's
positional random factory. Barrier noise is interpolated at 4×4×4 rather than
sampled separately for each voxel. Additional procedural carvers without final
negative density use a small negative density proxy for pressure. These choices
are reported in the profile's approximation list. Registered noise still uses
Retina's existing GPU noise implementation; this is not exact vanilla terrain.

The surface-support bitmap conservatively retains the original carving result,
so pressure-closed surface holes can suppress a plant but cannot support floating
plants over an opening. Generated source fluids do not enqueue bulk fluid ticks;
this avoids large world-opening update workloads but does not reproduce vanilla's
selective aquifer fluid-tick scheduling. Player updates still use normal Minecraft
fluid behavior. Visual inspection of actual flowing entrances remains optional.

## Validation and measurements

`aquiferTest` compares the real GPU with Minecraft's own aquifer implementation,
fixing only its center locations to the GPU hash. Both interpreter and specialized
paths cover negative coordinates, chunk/region edges, dry/partial/full flooding,
spread, exclusion, lava, pressure barriers, ocean connections, a real
`find_top_surface` search, disabled aquifers, and default air/lava settings.
3,072,000 compared voxels match: 1,369,116 air, 1,212,496 water, 397,564 lava and
92,824 retained barriers. A registered inline water/lava feature fixture checks
rarity and distinct barrier export. GPU lake checks verify the barrier floor.

The full release build and `gpuTest biomeTest datapackTest geologyTest
featureTest structureTest shoreTest landscapeTest previewTest regionTest` pass.
These exercise actual vanilla/Terralith registries, negative-region MCA/per-chunk
block parity, final heightmaps, structures/loot, preview LRU eviction, cold
base-material queries, promotion, saved edits, missing slots and cleanup. After
trimming unreachable aquifer branches from material dispatch, the release build,
29 native unit tests and all GPU/bridge/material/aquifer checks pass again.
All 20,480 NBT records per vanilla/Terralith benchmark match before/after that
compiler change. Two-caller runs also match every serial record exactly.

Apple M4 Max / Metal, release library, seed 123456789, two warmups and 20 adjacent
regions including negative coordinates. Actual profiles include 56/151 biomes,
30/58 structures and 105/262 decorations. The matched old-fluid control removes
only the aquifer entry and its five graphs from the same newly exported profile;
registered surface-lake corrections and layered materials remain in both. This
isolates local fluids rather than comparing against the older synthetic basins.
Baseline repeats and final aquifer repeats are shown; every repeat verifies all
20,480 NBT records against its respective earlier run. Desktop load varies,
so small throughput differences are observations rather than optimization claims.

| Profile | Cavity fluids | Callers | Chunks/sec | Mean request ms | Peak RSS MiB | 20 MCA files MiB |
| --- | --- | ---: | ---: | ---: | ---: | ---: |
| Vanilla | Previous cavity fluids | 1 | 5,328 | 191.70 | 863 | 138.47 |
| Vanilla | Local aquifers | 1 | 4,944 | 206.62 | 902 | 141.00 |
| Vanilla | Local aquifers | 2 | 5,723 | 346.31 | 951 | 141.00 |
| Terralith | Previous cavity fluids | 1 | 4,336 | 235.58 | 1388 | 117.62 |
| Terralith | Local aquifers | 1 | 4,142 | 246.68 | 1411 | 119.95 |
| Terralith | Local aquifers | 2 | 4,859 | 407.65 | 1426 | 119.95 |

With two callers, mean request latency includes concurrent queueing; throughput
uses all completed chunks over elapsed time. These are native generation costs,
excluding Minecraft loading, lighting and rendering.

Readback rises from 37.34 to 37.63 MB/region for vanilla, and from 33.68 to 33.97
MB/region for Terralith. Request uploads remain 14.24/12.31 MB respectively,
primarily existing ore masks. Aquifer scratch/fluid planes add no voxel readback;
the increase is additional final material runs plus timing records. Region file
size rises because the completed chunks now contain local fluids and barriers.

The material-dispatch trim reduces compilation paths without claiming a measured
region-throughput gain. Untrimmed first specialized compilation took 32.08 s
vanilla / 156.27 s Terralith; the trimmed runs took 8.93 / 131.51 s, with previously
compiled unchanged field pipelines potentially benefiting from the driver cache.
Fully cached two-caller compilation took 0.79 / 4.13 s. These are not a controlled
fresh-cache compiler speedup measurement.

Cold automatic-mode profiles append identity clamps to registered roots to force
new code while preserving generated terrain. Vanilla's first region returns in
980 ms; its 20-region mean is 1,438 ms at 712 chunks/sec while compilation takes
27.19 s. Terralith's first region returns in 1,022 ms; all 20 regions complete while
compilation is still pending, averaging 2,018 ms at 507 chunks/sec. Compilation
finishes after 166.04 s. Both match all 20,480 reference NBT records, then match
another 1,024 records after specialization. Retina stays on the GPU/Rust path
throughout; substantial cold compilation and interpreter costs remain required
work in the specialization workstream.

Evidence is retained locally under `build/goal-baseline/aquifer-*` and
`build/aquifer-*.log`; benchmark files are private temporary outputs, not live
save folders. The bundled release native SHA-256 is
`00af3d2cd79a2bca7458b347a342020ea8ef437f7e4de782e930eecf09d42c00`.
