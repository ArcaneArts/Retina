# Registered ground layers and dark-oak crowns

`count_on_every_layer` now uses the loaded integer provider and actual live block
columns. Minecraft 26.3 samples that provider at each loop condition, chooses
horizontal positions within the 16-block footprint, and searches for the next
empty-to-solid ground transition below the motion-blocking heightmap. Air
(including cave air), water and lava are empty for this search; bedrock cannot
provide an eligible floor. A layer with no successful ground samples ends the
search. There is no synthetic cave-height or biome-name substitution.

Rust expands these placements lazily. Ordinary candidates remain in a sorted
vector; only resumed layer attempts and their children enter an ordered heap.
Flattened selector branches share the resolved parent ground and attempt budget.
Downstream predicates and placements see preceding writes in the same anchor.
Ground lists are cached by column and invalidated when that column changes.
Nested vegetation-patch placements use the same floor search and provider loop.

Counts after a layer search can depend on the resulting XYZ position. The existing
ordered GPU replay now gathers those missing count queries as well as missing
block-provider queries. It batches sparse queries into the resident samplers and
retries unresolved anchors, retaining completed anchors. Device work remains in
the existing feature-count/provider-noise timing stages. No extra full-region
readback or Java/native ABI change is required.

Loaded recipes with otherwise unsupported geometry remain reported as omissions.
Supporting this modifier does not substitute unrelated geometry for those recipes.

## Dark-oak tops

The generic foliage loop grew down from the attachment, whereas registered
dark-oak foliage has rows above it. Clamping its valid zero radius to one also
lost the intended crown shape. This produced exposed trunk tops in dense forests.

Dark-oak trunks now retain their full 2×2 final row, lean and short side branches.
Their main foliage rows have radii `r+2`, `r+3`, `r+2` at attachment offsets
`-1`, `0`, `+1`, with the optional upper row and registered corner exclusions.
Single-trunk side attachments use the corresponding two-row crown. Radius zero
is preserved. Existing leaf-distance bookkeeping, terrain clipping and ordered
canopy placement still apply. Other tree shapes retain their current adapters;
this change does not promise seed parity with Minecraft.

## Validation

The 128-seed dark-oak regression first failed on the old implementation with an
exposed trunk tip. It now checks leaves above each of the highest trunk blocks,
including a tree placed across a chunk boundary. Ground tests cover registered
cave air, water, bedrock rejection, live-write invalidation, selector budgets and
downstream GPU count discovery.

The block-feature integration harness compares placements with Minecraft's
actual modifiers across flat, submerged, rugged and cavern fixtures. It exercises
loaded and synthetic constant, uniform, biased and weighted layer counts, both
alone and following a horizontal offset, at negative coordinates. Its region
checks compare independent chunks with decoded MCA records, final heightmaps and
registered dark-oak crown coverage for vanilla and Terralith. A cavern fixture
also runs layer placement followed by GPU noise-based counts through production
region replay.

All 187,904 placement comparisons pass (59,904 vanilla and 128,000 Terralith),
as do 43,520 registered feature comparisons, provider replay, region decoding and
DH temporary caching/promotion, including partial promotion and concurrent edits.
There are 43 passing native unit tests and three passing real-GPU integration
tests; two additional GPU fixtures are intentionally ignored in the unit command.
Final-block crown checks allow legitimate leaning elbows below the crown, and
check neighboring chunks for trunk continuation before identifying an exposed tip.
The build passes with the two-job Cargo limit and lowered native-build priority.

Logs are `build/layer-placement-{final,integration}-validation.log`. The first
contains the passing build/unit/GPU checks and an overly broad canopy assertion;
the second contains the corrected canopy assertion and all passing integration
checks. The loaded exports contain 172 vanilla recipes and 510 Terralith recipes,
up from 490 Terralith recipes. Twenty new flattened branches retain their loaded
layer budgets, including thermal-cave dripleaf/pickles and jungle-cave vegetation.

## Measurements

Six twenty-region runs use full actual vanilla/Terralith profiles (structures,
ores, decorations, material graphs and aquifers), Metal on Apple M4 Max, seed
123456789, two warmups, adjacent regions including negative coordinates, and the
GPU interpreter. There are no simultaneous builds or GPU tests. This is a
fidelity change, so old/new NBT equality is not expected. Concurrent new output
matches all 20,480 serial NBT records for each profile.

| Profile / library and input / callers | Mean region ms | Native chunks/sec | Peak process RSS MiB |
| --- | ---: | ---: | ---: |
| Vanilla / previous / 1 | 256.43 | 3,988 | 848 |
| Vanilla / new / 1 | 254.06 | 4,026 | 1,118 |
| Vanilla / new / 2 | 463.43 | 4,413 | 1,037 |
| Terralith / previous / 1 | 505.58 | 2,024 | 1,050 |
| Terralith / new / 1 | 521.62 | 1,962 | 989 |
| Terralith / new / 2 | 1,012.11 | 2,022 | 1,016 |

These single-run measurements do not establish a speedup. The expanded Terralith
input adds about 3.2% serial region latency in this sequence. Plant-planning worker
time changes from 30.6 to 45.6 ms/region for Terralith and 22.2 to 22.7 for vanilla;
parallel worker and device counters cannot be summed into region wall time.
Native rates exclude Java loading, lighting and rendering. Process/driver RSS
variation is not an isolated allocation measurement.

Vanilla serial upload/readback remains 14.29/45.62 MB per region. Terralith changes
from 12.46/41.92 to 12.59/41.95 MB as new recipes request sparse spatial samples.
Twenty output files total 153,931,776 bytes for vanilla and 131,764,224 for Terralith,
versus 153,911,296 and 131,739,648 previously. New serial warm-cache initialize /
native profile registration / first region is 42.7/507.0/279.0 ms for vanilla and
42.0/1,020.7/590.6 ms for Terralith. Java export and cold specialization are separate.

Retained libraries, profiles, scripts and raw results are in ignored
`build/goal-baseline/layer-placement/`. The tested native SHA-256 is
`fe5bb2190bdeca8349a9e3d8b57ca4c673b08187668352f50b1a5d4913bb6702`.
