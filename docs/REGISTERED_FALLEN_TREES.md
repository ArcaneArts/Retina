# Registered fallen trees

The exporter now follows loaded `FallenTreeFeature` recipes in the active biome
selectors. Vanilla exports 159 decoration recipes, including 23 fallen branches;
Terralith exports 413, including 21 fallen branches. These cover oak, birch,
spruce, jungle and poplar, with vanilla's tall-birch log-length variant. Their
counts, rarity, selector weights, offsets, biome filters and survival predicates
retain the existing ordered placement programs rather than a new density rule.

Java exports the complete trunk provider and log-length integer provider, along
with palette mappings for sideways X/Z states. Rust places the stump, runs its
decorators, chooses the direction and requested length, and searches down for
ground. It validates the entire horizontal run before placing it, allowing at
most two consecutive unsupported positions. A failed run leaves the stump,
matching the game. The registered length includes the stump/space subtraction;
it is not interpreted as the horizontal run length directly.

Fallen-tree decorators support `attached_to_logs`, `trunk_vine` and
`shelf_mushroom`. Loaded probabilities, providers, attachment directions and
decorator order are retained. Rust follows the game's shuffle and iteration
rules, including Java's small `BlockPos` hash-set bucket order for equal-height
logs. Shelf mushrooms check replaceability, neighboring water and already placed
shelf mushrooms, and retain sampled ages and facing states. This adapter applies
to fallen-tree contexts; standing-tree shelf decorators remain an omission in
the older standing-tree adapter.

Earlier writes within one feature are visible through a local view over its
small output list, layered over the existing anchor overlay. This avoids cloning
that overlay for every fallen log. The shared predicate evaluator accepts this
material sampler while retaining its existing short-circuit/unknown behavior.
Stump, log and decorator writes use the ordered feature role, so later recipes
and final metadata see the accepted output. Both chunk and MCA paths share the
planner, including DH temporary generation and promotion.

There is no new GPU geometry pass or full-region texture. Existing sparse GPU
count queries supply candidate budgets; conditional geometry and live checks
remain in parallel Rust. F3 attributes this work to vegetation planning and
placement. Loaded lengths above 15, unsupported spatial providers or unsupported
fallen decorators are explicit export omissions rather than silently shortened
or dropped pieces. The length limit keeps geometry within the existing one-chunk
anchor halo; the game's common registered recipes fit it.

## Validation

The actual Minecraft feature reference harness now checks 10,368 vanilla and
11,712 Terralith cases, including 4,416 / 4,032 fallen-tree cases. Six fixtures
cover dry terrain, water, two build-height limits and sloping GPU terrain, with
32 controlled seeds per recipe. Final writes match Minecraft's feature code.
Horizontal run lengths 2–13 occur in vanilla and 2–9 in Terralith, with both axes,
decorated logs and stump-only rejections. The steep fixture exercises accepted
and rejected runs. The controlled SplitMix stream checks feature semantics rather
than equivalence with Minecraft's Java worldseed RNG or neighboring scheduling.

Eight biome fixtures per profile decode all 1,024 MCA slots and compare twelve
independently generated chunks per fixture, including negative region boundaries:
192 chunk comparisons, all blocks and all six final heightmaps. New forest,
dappled-forest and old-growth-birch fixtures contain fallen features in
151 / 774 / 200 vanilla region chunks and 234 / 712 / 212 Terralith chunks.
The common column, aquatic, bamboo and giant-mushroom fixtures remain covered.

The release build, 31 native unit tests, real GPU tests, registered count sampler,
MCA decoding/metadata and DH cache/promotion/edit checks pass. Reference chunk
objects record the game's post-processing requests; those requests do not replace
or bypass the actual block/terrain checks. Evidence is
`build/registered-fallen-validation.log`.

## Measurements

Actual full-profile inputs and measurements are retained under
`build/goal-baseline/fallen-trees/`. The comparison removes only fallen recipes
and remaps their biome indices, retaining the complete palette, giant mushrooms,
structures, ores, caves and material graphs. Both inputs use the same release
library; comparison output is checked against the previous giant-mushroom
milestone's MCA records.

Metal / Apple M4 Max, release library, seed 123456789, two warmups followed by
twenty adjacent full regions per run, including negative coordinates:

| Profile / input | Average region ms | Native chunks/sec | Plant planning ms/region |
| --- | ---: | ---: | ---: |
| Vanilla without fallen trees | 128.19 | 7,886 | 9.28 |
| Vanilla with fallen trees | 130.69 | 7,821 | 8.99 |
| Terralith without fallen trees | 168.37 | 6,073 | 11.94 |
| Terralith with fallen trees | 151.79 | 6,736 | 11.04 |

Two concurrent callers measured 9,959 / 8,391 native chunks/sec for new vanilla /
Terralith, with mean request latencies 202.21 / 243.59 ms. These measure native
production, not Minecraft/DH loaded-chunk rates. The added recipes change
fidelity; varying host load and driver state prevent a speedup or general overhead
claim from these timings. The tested region sample is not a fallen-tree stress
workload. No build, test or other benchmark ran alongside these measurements.

Initialization / registration / first-region time for new vanilla was
43 / 485 / 607 ms; Terralith was 36 / 1,020 / 2,491 ms. These are warm driver-cache
measurements. Cold compilation remains outstanding. Peak serial process RSS was
906 / 1,592 MiB, versus 941 / 1,664 MiB in the comparison runs. Concurrent peaks
were 996 / 1,699 MiB. Process peaks include driver caches and, for comparisons,
NBT readers; these are not isolated feature allocations.

New twenty-file totals are 152,604,672 / 129,286,144 bytes, versus
152,604,672 / 129,269,760 without fallen trees. Vanilla transfers remain
14.25 MB uploaded / 37.63 MB read back per region; Terralith remains
12.38 / 33.98 MB. The small Terralith readback difference is about 26 bytes per
region in this workload. There is no added full-region GPU feature output.

Removing only fallen recipes reproduces the previous milestone's 40,960
decompressed NBT records exactly. Adding fallen recipes changes 348 vanilla and
190 Terralith chunk records in these measured regions. Another 40,960 records match between new serial
and concurrent runs, including final blocks, features, structures and metadata.
Exact inputs, stage timings, transfers and file sizes are in each run's
`measurements.json`. The tested native library SHA-256 is
`8d6e1edc14a727ae762ad2d66c28540793a9b73960e197f776ffb0371be8d759`,
and the built mod JAR contains that same library at this milestone.

## Remaining approximations

Sturdy faces are projected from loaded palette states in an empty block context;
custom context-dependent support shapes are not fully simulated. Base predicates
use representative GPU columns rather than every material run/carved voxel at
every halo position. Neighboring anchor overlays remain independent for stable
parallel output. Minecraft block updates/ticks and neighboring-chunk feature
scheduling are not reproduced. Vegetation patches, spatial/rule providers,
additional standing-tree decorators and remaining placement filters are still
outstanding, along with the broader graph-compilation and Rust-scan workstreams.
