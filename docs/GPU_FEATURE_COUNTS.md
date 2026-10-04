# Sparse GPU placement counts

Registered `noise_based_count` and arbitrary `noise_threshold_count` modifiers
now retain their loaded parameters. The former was previously omitted; the
latter accepted only -0.8 and used an unrelated hash-simplex threshold bit.
Actual Terralith export increases from 262 to 352 decoration recipes, including
158 noise-based count operations and six threshold operations. Vanilla retains
105 recipes with six threshold operations. Its noise-count aquatic recipes still
need the aquatic feature adapter described below.

Java exports the actual permutation produced by Minecraft 26.3's placement
sampler (`SimplexNoise`, legacy seed 2345, discarded offsets). The GPU evaluates
that simplex function at the rule's coordinates, then applies:

- threshold count: `noise(x/200,z/200) < level ? below : above`;
- noise-based count: `max(0, ceil((noise(x/factor,z/factor) + offset) * ratio))`.

Negative ratios are meaningful and retained. Omitted offsets default to zero.
Count noise is independent of the world seed, as in Minecraft; the world seed
still controls the existing anchor/attempt streams. Sampling uses float precision
on the GPU. Near a threshold or integer boundary, this can differ from Minecraft's
double intermediate arithmetic. No CPU noise simulation or backend emulation is
used to generate terrain or choose these budgets.

## Batching and ordering

Rust first walks placement prefixes to discover the points needing a count. It
does not construct crowns or modify the canopy during discovery. Queries with
identical coordinates and rule parameters are deduplicated across selector
branches. One region batch uploads 16 bytes per unique point (integer X/Z,
resident rule ID, padding), and reads back one four-byte integer count. Permutations
and count descriptors remain resident. Buffers are reused with active-length
bindings, so a smaller job cannot evaluate old descriptors from a larger job.

Resolved counts expose any subsequent nested count after an offset/scatter or
earlier spatial count; those points use the next sparse batch. Most vanilla
programs need one batch; nested pack programs may need more. Discovery stops
after the last spatial count and avoids constructing discarded candidate records.
It therefore exchanges the actual sparse query set rather than a full noise grid
for every factor across the region.

The final Rust walk preserves the original count/rarity/selector random forks,
positions, branch budgets and ordered parent/child replay. Heightmaps, biome,
soil/air/water predicates, survival, branching trunks/crowns, leaf distances and
the live canopy overlay retain their existing Rust implementation. GPU sampling
does not turn every requested attempt into an accepted tree. Neighboring anchors
remain independent, preserving parallel chunk/MCA generation and request order.

Both native chunk generation and MCA assembly call the same count-aware planner.
DH previews use it through temporary MCA generation; cached promotion reuses the
already generated blocks. No new save/cache sidecar is required. Native JSON
profiles without `decoration_noise` retain their old -0.8 bit-field behavior;
newly exported profiles use the registered permutation. Existing generated MCA
records and edits are unchanged.

## Timings and validation

F3 labels **Plant planning (CPU + GPU)** as a mixed planner. It includes discovery,
GPU lock/queue waiting, dispatch/readback and Rust geometry. **Feature counts**
under GPU device timing measures the count shader alone and overlaps that planner
time. It must not be added to region latency. Device timestamps are job-local for
concurrent region reports; optional timestamp readback adds 16 bytes per batch.
The matched bridge uses timing ABI 4: 24 stages, 224-byte snapshots and 264-byte
detailed region reports. The legacy 40-byte region API is unchanged.

`decorationCountTest` exports full vanilla/Terralith benchmark profiles and checks
the production sampler against `Biome.BIOME_INFO_NOISE`. It exercises 10,240
vanilla-profile and 50,688 Terralith-profile queries, including signed/zero ratios,
offset defaults, arbitrary thresholds and negative positions. Three and 27 count
differences respectively occur only within the recorded float boundary tolerance.
Small/large/empty batches, resident-input reuse, concurrent callers, seed
independence, transfer counters and device timing across the ABI also pass.

Native unit coverage verifies nested noise queries, shifted points, RNG forks
and exactly one selector branch per parent budget. Full biome/datapack, geology,
feature, structure, region and preview suites pass. These check independent
chunks against MCA blocks/final metadata, actual templates/loot, cache eviction,
parallel requests, promotion, cold base columns, reopen data and preserved edits.
The original-profile 20-region comparisons match all 40,960 decompressed chunk
NBT records from the retained aquifer build.

## Measured region workloads

Release Metal / Apple M4 Max, seed 123456789, two warmups and 20 measured adjacent
regions per run, with actual structure templates, caves, ores and decorations.
Builds/tests finish before measurement. Raw evidence is under
`build/goal-baseline/counts-*`; the new exported inputs are
`build/decoration-count-{vanilla,terralith}.json`.

| Profile / callers | Chunks/sec | Mean request ms | Plant planning ms/region | Count shader ms/region |
| --- | ---: | ---: | ---: | ---: |
| Original vanilla input / 1 | 7,643 | 133.68 | 7.91 | 0 |
| New vanilla input / 1 | 7,715 | 132.49 | 7.93 | 0.0045 |
| Original Terralith input / 1 | 6,660 | 153.51 | 8.00 | 0 |
| New Terralith input / 1 | 6,646 | 153.82 | 9.82 | 0.0080 |
| New vanilla input / 2 | 10,031 | 197.16 | — | — |
| New Terralith input / 2 | 8,138 | 251.07 | — | — |

The same candidate library generated both original and new inputs. The new
inputs change feature fidelity, so these are cost measurements, **not a
semantics-preserving CPU-versus-GPU speedup experiment**. Whole-region throughput
is roughly level while retaining more registered rules; no dramatic speedup is
claimed for this count pass. Plant planning still mostly executes geometry and
ordered replay in Rust. Serial/concurrent new-profile runs match another 40,960
decompressed NBT records exactly, including metadata and structures.

Average additional traffic is about 5.54 KB upload / 1.40 KB readback per vanilla
region and 61.01 KB upload / 15.15 KB readback per Terralith region. Total dynamic
uploads are 14.25 / 12.37 MB and readbacks 37.63 / 33.98 MB respectively; most
traffic still belongs to terrain, material runs and ores. Totals include occasional
resident-rule growth and timestamps. Twenty new region files occupy 141.01 MiB
vanilla and 120.68 MiB Terralith, versus 141.00 / 119.95 MiB with original inputs.
Serial peak process RSS is about 898 / 1,580 MiB; two callers peak at
1,036 / 1,772 MiB. These process figures include profiles, buffers, caches and
the comparison reader, rather than just the count sampler.

Native initialization with warm driver caches is about 35–37 ms. Registration
takes about 454–471 ms vanilla and 929–962 ms Terralith. The first specialized
request still takes about 620–630 / 2,485–2,554 ms, including density/material
specialization of about 443–453 / 2,237–2,301 ms with warm caches. This does not
resolve the much larger cold graph-compilation cost documented in
[material layers](GPU_MATERIAL_LAYERS.md) and [aquifers](GPU_AQUIFERS.md).
Java registry/template export is separate. Vulkan/DX12 use the same shader but
still need hardware runtime measurements.

## Remaining feature work

This implements spatial **count** sampling, not all tree geometry or all ore
planning. Bulk zero-discard ore rasterization already uses the GPU;
[ore descriptors and exposure-sensitive recipes](GPU_SPARSE_PLANNING.md) still
include CPU work. Providers such as the current `noise`/`dual_noise` codecs,
bamboo, fallen trees, vegetation patches, aquatic growth, huge mushrooms,
block-column features and remaining spatial filters still require adapters.
Their export omissions remain visible in the registry log. Existing provider
approximations and Rust survival/canopy limitations remain documented in
[decoration placement](DECORATION_PLACEMENT.md).

```sh
JAVA_HOME=/Library/Java/JavaVirtualMachines/jdk-25.jdk/Contents/Home \
  ./gradlew build gpuTest decorationCountTest previewTest regionTest \
  -PtestPack=run/datapacks/Terralith.zip --no-parallel --max-workers=1

python3 scripts/native-region-benchmark.py \
  --library build/native-target/release/libretina_worldgen.dylib \
  --profile build/decoration-count-terralith.json --out build/counts-measurement \
  --count 20 --warmups 2 --program-execution specialized
```
