# Registered giant mushrooms

Retina exports the active Minecraft 26.3 red/brown giant mushroom features into
the ordered Rust decoration planner. Vanilla now exports 136 recipes (four giant
mushroom branches); Terralith exports 392 (eight branches). These include the
registered dark-forest and mushroom-island selectors, and Terralith's scattered
and enchanted mushrooms. Counts, selector weights, rarity, biome restrictions,
heightmaps and predicates retain their existing placement programs.

Java exports the configured foliage radius, support predicate and complete cap
and stem providers. Atomic, weighted and randomized integer-property providers
are supported. Cap property variants remain in the resident palette; Rust samples
the provider and selects the actual west/east/north/south/up face combination.
The configured down property is preserved. Brown caps retain their provider's up
property, matching the game's feature implementation.

Rust follows Minecraft's 4–6-block height with the rare doubled 8/10/12-block
height, build-height limits, clearance checks, cap geometry and per-block
replaceability. It builds the cap before the stem, and evaluates later recipes
against the accepted overlay. Minecraft 26.3's red feature currently calls its
clearance-radius helper with `(-1, -1)`, which produces a trunk-axis clearance
test; individual cap blocks still have replaceability checks. Retina preserves
that behavior rather than substituting a wider test.

This adds no new GPU dispatch, output texture or full-region readback. Existing
sparse GPU placement counts determine candidate budgets; branching mushroom
geometry and live checks run in Rust. Both chunk and MCA entry points share this
planner, including DH temporary generation and promotion. Existing saved chunks
and player edits retain their existing output.

## Validation

`blockFeatureTest` calls Minecraft's actual loaded feature code against the same
controlled random stream and GPU-generated substrate as the Rust sampler. The
expanded suite checks 5,952 vanilla and 7,680 Terralith cases across all registered
column, bamboo, aquatic and giant mushroom recipes. Six fixtures cover dry land,
shallow/deep water, two build roofs and varying GPU terrain. All six mushroom
heights occur; the rugged fixture exercises 64 accepted / 64 rejected vanilla
mushrooms and 128 / 128 Terralith mushrooms. Final block-state writes match the
Minecraft implementations, including cap face properties.

Five biome fixtures per profile cover bamboo jungle, desert, ocean, mushroom
fields and dark forest. All 1,024 MCA slots decode, with 12 independently generated
chunks per fixture compared block-for-block and against all six final heightmaps:
120 chunk comparisons in total, including negative region boundaries. Mushroom
features appear in 1,022 / 882 vanilla mushroom-field / dark-forest chunks and
1,023 / 1,024 Terralith chunks respectively.

The release build, 31 native unit tests, real GPU tests, registered count sampler,
chunk metadata and DH temporary-cache/promotion/edit tests pass. Full validation
evidence is `build/registered-mushroom-validation.log`. The controlled SplitMix
stream checks feature semantics; it does not reproduce Minecraft's Java worldseed
RNG or neighboring-chunk scheduling.

## Measurements

Measurements and exact inputs are retained under
`build/goal-baseline/huge-mushrooms/`. Full vanilla and Terralith exports include
all structure templates, ores, caves, material programs and decorations. The
comparison input removes only giant mushroom recipes and remaps biome recipe
indices, retaining the same palette and all other loaded data. Both inputs use
the same release library. The comparison output is also checked against the
previous shoreline milestone's actual full-profile MCA records.

Metal / Apple M4 Max, release library, seed 123456789, two warmups followed by
twenty adjacent full regions per run, including negative coordinates:

| Profile / input | Average region ms | Native chunks/sec | Plant planning ms/region |
| --- | ---: | ---: | ---: |
| Vanilla without giant mushrooms | 131.53 | 7,733 | 10.33 |
| Vanilla with giant mushrooms | 127.95 | 7,989 | 8.83 |
| Terralith without giant mushrooms | 154.35 | 6,624 | 11.44 |
| Terralith with giant mushrooms | 166.41 | 6,144 | 15.69 |

With two concurrent callers, the new vanilla / Terralith profiles deliver
9,370 / 8,441 native chunks/sec, with mean request latencies 210.97 / 242.08 ms.
These are native production rates, not Minecraft or DH loaded-chunk rates.
The input adds fidelity rather than implementing an optimization. Host load and
driver caching vary; the differing serial results do not establish a speedup or
a general overhead bound. The region sample is not a mushroom-biome stress test.
Plant planning remains covered by the existing F3 vegetation planning/placement
counters; no separate GPU geometry stage is introduced.

Initialization / registration / first-region time for the new vanilla profile
was 35 / 469 / 607 ms; Terralith was 36 / 970 / 2,492 ms. These are warm driver-cache
measurements, not cold-compilation results. No build, integration test or other
benchmark ran alongside these measurements. Cold compilation remains a separate
outstanding workstream.

Twenty new region files total 152,604,672 / 129,269,760 bytes, versus
152,584,192 / 129,253,376 without mushrooms. Peak serial process RSS was
863 / 1,642 MiB, and concurrent RSS 977 / 1,728 MiB. These are whole-process peaks,
including driver caches and NBT readers in comparison runs, not isolated feature
memory costs. The comparison runs' peaks were 1,239 / 1,662 MiB.

Vanilla upload/readback averages remain 14.25 / 37.63 MB per region. Terralith
averages 12.38 / 33.98 MB: 16 extra upload bytes and about 758 extra readback bytes
per region in this workload. Resident inputs and existing sparse queries are
reused; no additional full-region feature texture is transferred.

The two comparison inputs reproduce the previous milestone's 40,960 decompressed
NBT records exactly. New concurrent output matches its serial output for another
40,960 records, including final blocks, structures and metadata. Exact inputs,
stage timings, transfer counts and file sizes are in each run's `measurements.json`.
The tested native library SHA-256 is
`3cafe9cb331c10772d088e8c8a2f9957b87052c7cd1f647dce97871e6bd690d1`,
and the built mod JAR contained that same library at this milestone.

## Remaining approximations

Spatial noise/rule block providers and radii exceeding the current 16-block
horizontal halo are explicit export omissions. The planning overlay uses
representative GPU columns for its base predicate substrate rather than all
material runs/carved voxels at every halo position. Neighboring anchor overlays
remain independent for stable parallel chunk/MCA output. Custom light conditions,
block updates/ticks and Minecraft's neighboring-chunk feature scheduling are not
fully simulated. Fallen trees are covered by the subsequent
[fallen-tree milestone](REGISTERED_FALLEN_TREES.md). Patches, other provider/decorator families and
the remaining graph/scan workstreams are still outstanding.
