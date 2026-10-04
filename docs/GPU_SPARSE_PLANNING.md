# Sparse GPU ore masks and faster feature planning

MCA generation now rasterizes bulk, zero-discard ore/material veins on the same
GPU device and queue as terrain. Rust derives attempts, heights, biome membership,
spheres and containment pruning from the registered recipes. One GPU workgroup
per surviving vein produces compact union bits. Parallel Rust chunk assembly
consumes those bits directly, preserving anchor/recipe/attempt priority and
registered host replacement bands. It no longer builds, merges and copies
millions of 12-byte candidate records for these recipes.

Ores with air-exposure discard rules and scattered ores retain their CPU geometry,
random sequence and ordered replay. CPU and GPU contributions are indexed by
their intersecting target chunks in original order, including the anchor halo.
Individual chunk generation and retained ore-layout-1 profiles keep the CPU
planner. Both paths produce identical final blocks and MCA metadata; temporary
DH regions automatically use the MCA path and promote normally. No save/profile
version change or temporary-cache invalidation is needed.

## Numerical bounds and resource reuse

Each output word has a companion uncertainty word. The GPU marks distances
within 0.0001 of the unit-sphere boundary; Rust resolves these rare voxels using
the original radius, f32 expression and per-sphere integer bounds. An unambiguous
interior sphere can finish the GPU test early. Uncertainty can repair both
false-positive and false-negative mask bits.

At distant coordinates, several integer block centers round to the same float.
The GPU additionally preserves the CPU's original per-sphere traversal box there,
preventing an interior test from adding neighboring integers the CPU never visits.
The regression covers coordinates near ±30 million, host replacement chains,
air-replacing recipes, exposure, scattered ores and chunk edges. It also forces
every mask voxel through CPU repair and alternates large/small GPU jobs.

Buffers are reused under the ore submission lock. Bindings expose each job's
active lengths, rather than retained capacity, so rounded dispatch groups cannot
process stale vein descriptors. Completed masks belong to their region job and
remain immutable while other requests reuse the GPU buffers. Errors propagate
through the native bridge. Transfer counters include these additional uploads
and readbacks.

F3 calls the combined stage **Ore planning (CPU + GPU)** under **Native**. It
includes Rust descriptor preparation, lock/queue waiting, GPU execution and
readback. Ore application remains a separate worker estimate. Existing GPU
device rows measure terrain/climate/cave passes; they do not separately time the
new ore pass. Their sum is not total device utilization.

## Why plants remain in Rust for this step

The plant-planning timer includes trunk/crown construction, six-neighbor leaf
distance propagation, ordered placement modifiers and the live canopy overlay.
Replacing it with a noise threshold would change registered counts, selector
weights and survival rules, and miss heightmap changes from earlier features.

Crowns now use a bounded local byte grid for foliage membership and visited
state. The original breadth-first traversal still determines leaf distances and
write order. Live canopy, placement-cache and tree-decoration lookups use cheap
hashing for internally generated coordinate keys, retaining ordinary key equality
and deterministic placement order. The change preserves registered feature
density, tree geometry, leaf decay, vines, cocoa and canopy interactions.
A checked allocation limit retains sparse membership for unusually distant crown
coordinates, so registered offsets cannot require allocating the empty gap.

Focused vanilla/Terralith planner measurements preserve the complete ordered
placement signature, not just tree counts. Full-region comparisons check final
NBT, including all later vegetation/structure interactions.

## Measurements and reproduction

Release build, Apple M4 Max / Metal, real vanilla and Terralith registry profiles
with structures enabled. A separate game was active throughout these measurements;
absolute throughput is not comparable with earlier quiet-machine reports. Runs
are sequential; each full-region workload has five warmups and twenty measured
regions. Baseline is `9ead77a`. Raw local evidence is under
`build/goal-baseline/sparse-*` and `decoration-{spatial,ter}-*`.

The exploratory direct-mask benchmark, before adding production boundary guards,
alternates CPU and GPU planning/application on one fixed real carved field. Base
copies and block hashing are outside both timers. Twenty measured iterations plus
five warmups at each of three fields matched 76,800 final chunk block arrays.
Median complete ore planning/application was 139.94 → 74.19 ms at vanilla (0,0),
165.48 → 81.28 ms at vanilla (-32,-32), and 88.58 → 73.92 ms at Terralith (32,0).
These are focused prototype results, not whole-generator speedups.

The production mask uses two bits per voxel; the prototype used one. Final
production comparisons and stage/transfer measurements are recorded below.

| Profile / workload | Before chunks/sec | After chunks/sec | Before average region ms | After average region ms |
| --- | ---: | ---: | ---: | ---: |
| Vanilla serial, first integration | 2,750 | 3,162 | 369.84 | 322.72 |
| Vanilla serial, final / reversed order | 2,232 | 3,119 | 457.95 | 327.29 |
| Vanilla two callers | 2,255 | 2,699 | 899.00 | 732.51 |
| Terralith serial, first integration | 2,734 | 2,874 | 372.78 | 355.53 |
| Terralith serial, final | 2,538 | 1,751 | 402.44 | 582.92 |
| Terralith serial, stability repeat | 2,517 | 2,534 | 405.96 | 403.19 |
| Terralith two callers | 2,442 | 2,722 | 805.51 | 738.96 |
| Vanilla serial, packaged build confirmation | — | 3,141 | — | 325.27 |
| Terralith serial, packaged build confirmation | — | 2,498 | — | 408.62 |

Vanilla improves in both serial comparisons and with concurrent callers.
Concurrent Terralith improves about 11%; **serial Terralith has no reliable gain**
in this environment. Its large adverse outlier is retained in the table, not
excluded as a performance proof. These are native generation rates, excluding
Minecraft loading, lighting, rendering and Java column-cache work. Average latency
with two callers includes time sharing resources; throughput uses actual wall time.

For concurrent vanilla, ore planning wall time falls 349.24 → 184.70 ms/region;
for concurrent Terralith it falls 184.70 → 105.78. Worker stage sums overlap and
must not be added to region latency. The earlier focused feature comparison
preserves ordered signatures and measures 58.00 → 21.10 ms vanilla and
66.22 → 42.30 ms Terralith; its gains do not uniformly reproduce in full-region
plant wall time under contention. No universal whole-generator speedup is claimed.

Across the whole-region candidate comparisons, **184,320 decompressed chunk NBT
records match** the retained CPU build. Twenty-region totals stay 135,274,496
bytes vanilla and 112,930,816 bytes Terralith. Final concurrent peak process RSS
is approximately 1,100 → 819 MiB vanilla and 1,302 → 1,240 MiB Terralith; process
figures include profiles, buffers, caches and the comparison reader.
The final packaged build contributes 40,960 of these comparisons. The mod JAR's
bundled native library also matches the tested release library byte for byte.

This offload adds actual GPU traffic: average dynamic upload is about 13.58 MiB
per vanilla region and 11.74 MiB per Terralith region. Total region readback,
including terrain/caves, is about 25.86 and 22.19 MiB, versus roughly 18.96 and
19.11 MiB before. The performance benefit comes from avoiding CPU rasterization
and large candidate buffers, not from reducing total GPU traffic relative to the
former CPU-only ore planner. Moving sphere preparation to the GPU is the next
transfer/work reduction opportunity.

Native initialization during these loaded runs ranged roughly 120–715 ms, versus
120–188 ms for the baseline runs. The changed static ore shader compiles
when the terrain device is created; profile-specific density specialization still
runs asynchronously. Registration cost and background activity also varied. These
measurements do not establish a clean cold-start or discrete-GPU performance result.

```sh
cargo build --manifest-path native/Cargo.toml --release --locked \
  --target-dir build/native-target --example decoration_plan_benchmark
build/native-target/release/examples/decoration_plan_benchmark \
  build/goal-baseline/vanilla.json 123456789 0 0 40

cargo build --manifest-path native/Cargo.toml --release --locked \
  --target-dir build/native-target --features ore-raster-benchmark \
  --example ore_raster_benchmark
build/native-target/release/examples/ore_raster_benchmark \
  build/goal-baseline/vanilla.json 20 123456789 0 0 compact

./gradlew build gpuTest regionTest geologyTest featureTest previewTest structureTest
python3 scripts/native-region-benchmark.py \
  --library build/native-target/release/libretina_worldgen.dylib \
  --profile build/goal-baseline/vanilla.json --out build/sparse-measurement \
  --count 20 --warmups 5 --program-execution specialized \
  --compare build/goal-baseline/sparse-van-before
```

`gpuTest` also runs `nativeOreGpuTest`, the actual-device regression above.
Vulkan/DX12 use the same WGSL but still need hardware runtime validation.

## Next GPU opportunities

Descriptor generation and sphere pruning are still CPU work. Moving them to a
seed/recipe-driven GPU pass would reduce the repeated sphere upload and CPU work,
but needs measured output sizing/compaction and preserved ordering/float semantics.
The current stage is an offload of voxel rasterization, not all ore planning.

For sparse plant candidates, use seeded per-chunk/recipe attempts or stratified
cells with registered count/rarity/height/biome rules. Registered spatial noise
can influence a count where the actual recipe requests it. Compact candidate
packets are preferable to scanning every world voxel. Live heightmaps, ordered
canopy filters and variable tree geometry require careful batching; independently
sampling a noise threshold is not an equivalent replacement for those rules.
