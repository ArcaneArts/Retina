# GPU base materials for decoration placement

New registry profiles export `registry_program.material_halo: true`. GPU material
runs cover the existing one-chunk placement halo as well as the generated chunk
or region. Rust's live placement predicates and heightmap modifiers read actual
base blocks there, including carved air, local aquifer fluids, pressure barriers,
floor/ceiling materials and underwater layers. Representative top/filler columns
cannot describe those transitions and previously hid them from placement checks.

The runs share the existing GPU material evaluator and resident profile. No CPU
noise, material-rule simulation or dense per-voxel material transfer is added.
This supplies the ground/air/fluid data needed by underground vegetation patches;
the patch adapter, nested recipes and remaining 3D placement filters are still
required. The exporter continues to report those omissions.

## Coverage and replay

For a region, the GPU evaluates a 36×36 chunk field, with complete runs for the
inner 34×34. The requested 32×32 region and its one-chunk placement halo are thus
covered. The extra outer tile guards height-neighbor slopes, cave interpolation
and aquifer support. A single chunk uses a 5×5 field and 3×3 run span for the same
reason. Existing GPU passes, exact count/prefix/emission format and immutable job
snapshots are reused; there is no additional round trip or shader stage.

The CPU planning field remains 34×34 / 3×3. The outer guard does not introduce
feature anchors or alter count queries, selector order or placement budgets.
Extra column descriptors populate the existing column cache. The packed exposure
plane includes a one-block guard around the expanded run span, while aquifer
fluid planes remain GPU-only. Quart biomes and the surface-carved bitmap retain
their existing full-field coverage.

`Field` shares the immutable `CaveMask` through an `Arc`. Point lookups search the
descending material runs; base heights inspect runs using the six loaded
heightmap predicates. The live overlay remains above that base, so accepted
feature writes and later removals are visible. Cached core chunks retain the
same complete halo rather than returning to representative columns. Ore and
cave-dressing writes applied later are not part of this base snapshot, and
neighboring anchor overlays remain independent for stable parallel replay.

Base assembly and the DH `.materials` companion continue to serialize only the
requested core. Final NBT palettes and heightmaps still describe the final blocks
after features and structures. Existing F3 height, cave, aquifer and material
timers include the expanded GPU work; placement time includes run lookups. No
timing ABI or native FFM ABI change is needed.

## Compatibility and validation

The new flag requires material layers. Profiles without it deserialize to false
and retain the previous coverage and representative placement checks. Existing
generated chunks, DH cache capacity, promotion and saved edits are preserved.
The new path remains Retina GPU → Rust → MCA/chunk generation.

`material_substrate_check` uses actual full vanilla/Terralith profiles. Specialized
execution checks full regions; interpreter execution checks smaller batched
fields. Each compares sixteen independently generated chunks, including halo
corners, negative coordinates, concurrent requests, all base voxels and six
independently scanned heights. A separate GPU engine prevents the independent
requests from borrowing the region mask. Sampled core base voxels also match the
old material path; cached chunk calls add no GPU jobs. Across four checks,
6,291,456 base-voxel comparisons and 98,304 heightmap comparisons pass, with actual
underground air and fluids present. Another 6,291,456 final block comparisons
match full cached/independent generation, including features, ores and structures.

The live-overlay unit check covers carved voids, dirt ceilings, water above ground,
heightmap predicate differences, subsequent leaf placement/removal and build/halo
bounds. The broader suite passes 32 native unit tests, GPU concurrency, 6,680,576
Minecraft material-reference voxels, 3,072,000 Minecraft aquifer-reference voxels,
registered block/feature references, final MCA metadata and DH promotion/edit
checks. The material-reference harness explicitly opts into the new halo.
The separate Terralith datapack-loading test also passes both MCA/chunk modes,
registry import, preset persistence and temporary-region promotion.

At this substrate milestone, the broader `biomeTest` failed its paired-plant assertion. A retained
pre-change library and the new library reproduce the same blocks at seed
123456789, chunk -111/-82, local index 29182 (world -1762/49/-1297): tall seagrass
upper above single seagrass. A later aquatic feature can replace the lower half
without repairing its partner. The game's `SimpleBlockFeature` writes flags 2
and `DoublePlantBlock` relies on shape updates for partner validation; Retina
does not yet reproduce that final update behavior. This is an existing replay
defect, not a substrate regression. The harness now reports position/states,
and the reproduction, profile and failed logs are retained with the evidence.
The subsequent [paired-plant replay repair](PAIRED_PLANT_REPLAY.md) resolves this
defect and passes the broader checks. The timings below describe the substrate
milestone before that repair.

```sh
JAVA_HOME=/Library/Java/JavaVirtualMachines/jdk-25.jdk/Contents/Home \
  ./gradlew build gpuTest blockFeatureTest decorationCountTest previewTest regionTest \
  -PtestPack=run/datapacks/Terralith.zip --no-parallel --max-workers=1
cargo run --release --locked --manifest-path native/Cargo.toml \
  --target-dir build/native-target --example material_substrate_check -- \
  build/registered-columns-vanilla.json specialized 32
cargo run --release --locked --manifest-path native/Cargo.toml \
  --target-dir build/native-target --example material_substrate_check -- \
  build/registered-columns-terralith.json interpreter 2
```

## Measurements

Full-profile benchmark evidence is under `build/goal-baseline/material-substrate/`.
Inputs equal the fresh loaded-registry exports except for the comparison flag.
Metal / Apple M4 Max, seed 123456789, two warmups and twenty adjacent full regions
per run, including negative coordinates. The table compares flag false/true in
the same current release library, with 159 vanilla / 413 Terralith decoration
recipes and all loaded structures, ores, caves, aquifers and material graphs.
No build, test or other benchmark ran alongside measurements.

| Profile / halo | Average region ms | Native chunks/sec | Peak process RSS MiB |
| --- | ---: | ---: | ---: |
| Vanilla / false | 133.08 | 7,681 | 990 |
| Vanilla / true | 134.69 | 7,589 | 909 |
| Terralith / false | 151.52 | 6,747 | 1,622 |
| Terralith / true | 157.82 | 6,479 | 1,615 |

The measured latency increases are 1.2% / 4.2%. This is a fidelity change, not a
speedup. Host/driver variation prevents a precise general overhead claim: the
retained previous library measured 147.76 / 159.14 ms with the old inputs, while
the current library reproduces all 40,960 of its decompressed NBT records exactly.
Process peaks include driver caches and are not isolated substrate allocations.

Two concurrent callers with the new inputs measured 10,617 / 7,924 native
chunks/sec, average request latencies 185.49 / 257.90 ms and peaks 1,019 / 1,646 MiB.
Another 40,960 NBT records match new serial output exactly. These rates measure
native production rather than Minecraft/DH chunk-loading throughput.

Serial initialization / registration / first-region time was
56 / 475 / 642 ms for new vanilla and 43 / 978 / 2,516 ms for new Terralith. These
use a warm driver cache; this milestone does not resolve cold compilation.
Material device time was 16.34 → 17.43 ms/region for vanilla and
21.23 → 24.30 for Terralith. Cave-mask time was 5.18 → 5.94 / 5.15 → 5.96;
aquifer-mask time was 7.46 → 8.29 / 7.84 → 8.98. Device time overlaps host work.
Plant-planning worker time was 11.21 → 9.57 / 11.01 → 11.48 ms/region; that counter
alone does not establish region speed.

Upload remains 14.25 / 12.38 MB per region. Serial readback increases from
37.63 → 41.48 MB for vanilla and 33.98 → 37.82 MB for Terralith, about 3.85 MB
extra for the expanded compact base/biome/exposure payload. The complete sampled
region mask grows from 26.63 → 30.03 MB / 26.70 → 30.11 MB; it is shared rather
than copied per cached core chunk. The GPU-only fluid and support fields also grow.
Twenty output files total 152,641,536 / 129,310,720 bytes, versus
152,604,672 / 129,286,144 before. Actual predicates/heights change decoration
results in 362 / 470 NBT records; core column descriptors remain unchanged.

A separate serial cold-chunk run used five warmups and 64 distinct chunks with
the full profiles. Mean latency increased 8.04 → 8.87 ms for vanilla and
8.38 → 9.56 for Terralith (124 → 113 / 119 → 105 native chunks/sec). Peak RSS was
498 → 503 / 1,363 → 1,469 MiB. All 64 final block hashes per profile match in this
particular near-origin sample; it is not a placement-difference stress workload.
The expanded halo costs proportionally more for individual chunks, while MCA
amortizes it over 1,024 chunks. Raw inputs, startup, stage counters, file totals,
chunk script and comparison counts are retained with the benchmark evidence.

The tested native library SHA-256 is
`be95ee8a31971f4ee777d532730aabcb66911e9d10cce07a96c65852a19fa9e1`;
the built mod JAR contains the identical library.

Vegetation patches and spatial providers remain part of the active goal, alongside
cold graph compilation, expression-level interpolation and
further measured scan work. Standing-tree geometry still has older approximations; complete base
predicates do not reproduce Minecraft's entire neighboring-feature scheduler or
block-update system.
