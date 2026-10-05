# Registered sediment disks and ordered composites

Loaded `DiskFeature` recipes now run in Rust against the GPU-generated material,
fluid, cave and heightmap substrate. This replaces the coherent material-coverage
approximation for successfully exported disks. The exporter runs before material
program compilation and retains the actual `PlacedFeature` objects that emitted
a native disk. Identity-based ownership also covers inline placements; unsupported
disks retain their previous handling and explicit omission reports.

Each disk retains its integer radius distribution, half-height, target predicate,
nullable block-state provider and ordered placement modifiers. Radius is sampled
once per attempt. The footprint follows Minecraft 26.3's circle test, advances X
before Z and visits each column from top to bottom. Providers run only when the
live target predicate succeeds. Their preceding writes, nullable results, random
draws and registered tag/state choices remain visible to subsequent positions.
The adapter accepts Minecraft's codec bounds: radii zero through eight and
half-heights zero through four.

Disks in other generation steps participate in their biome's existing registered
feature order, alongside the vegetation and attachment adapters. No biome-name
rule or invented disk density is introduced. The existing GPU sparse feature-count
planner handles top-level spatial counts. Registered spatial provider noise uses
the existing resident GPU programs and unresolved-anchor replay, with integer
XYZ queries and compact results. Constant and contextual providers need no added
GPU round trip.

`SequenceFeature` and `OverlayFeature` now retain child placed-feature programs
instead of flattening them into unrelated attempts. All children share the parent
random stream and live overlay. A sequence stops at its first failed child,
returns failure and preserves preceding writes. An overlay visits every child
and returns whether any child succeeded. Nested sequences, overlays, selectors,
patches, columns, simple blocks, attachment growth and disks can participate.
A child that produces positions through a count/cuboid/layer modifier succeeds
when any actual feature invocation succeeds.

Composite horizontal reach is the maximum complete child reach, including its
placement offsets and geometry. The existing extended GPU substrate halo supplies
these reads. Complete reach above fifteen blocks and unsupported nested recipes
remain explicit omissions. In particular, Terralith's tree/shelf-mushroom
sequences still require a nested tree and shelf-survival adapter; this change
does not silently drop a child to make those sequences appear supported. Nested
biome filters and spatial count modifiers retain their existing omission behavior.

Both individual chunks and MCA requests use the same planner, including DH
cached regions. Older standalone profiles retain their existing GPU coverage
programs and feature lists. Saved generated chunks retain their blocks. Runtime
registry profiles acquire the new recipes when the client restarts. Rust's final
palette and heightmaps are computed from the final blocks. Disk postprocessing
notifications are not queued as Minecraft feature-stage neighbor work; Retina
continues its existing completed-chunk loading and lighting path.

F3 now labels this combined work `Decoration planning (CPU + GPU)` and
`Vegetation / sediments`. It remains in the existing timing slots, with no ABI
change or extra timing overhead.

## Reference validation

The reference harness invokes the actual Minecraft 26.3 `DiskFeature`,
`SequenceFeature`, `OverlayFeature` and `FeaturePlacer` implementations from
the Loom runtime. It compares final writes with Rust under the same controlled
random stream and substrate; it does not claim Minecraft seed-RNG equivalence.

- Actual vanilla export: 56 biomes, 2,438 materials and 182 decoration recipes,
  including five disks. Terralith: 151 biomes, 3,946 materials and 553 recipes,
  including 29 disks. Supported disks no longer appear in `shore_features`;
  Terralith's unrelated `alpha/sand_beaches` ore-based projection remains.
- Registered feature comparisons pass 15,936 vanilla and 41,344 Terralith cases,
  including every exported disk. Deep disk checks read the real coordinate's
  GPU soil layers, rather than copying a single column across a flat fixture.
- Each profile passes another 7,680 comparisons across 40 disk/composite recipes.
  These cover radius bounds zero/eight, uniform radii, half-height zero/four,
  constant/weighted/nullable/GPU-noise providers, flat/submerged/rugged/carved
  substrates and negative chunk boundaries. Each run includes 3,072 composite
  cases, 1,152 spatial-provider cases and 384 shared-RNG cases with repeated
  children and many weighted provider draws. Both failed and successful
  placements are nonvacuous.
- The forced-biome integration checks compare complete blocks and six final
  heightmaps between individual chunks and decoded MCA records. Additional
  checks preserve snow support, local aquifers, structure metadata, DH cache
  promotion/partial promotion and actual datapack save/read/promotion behavior.

Commands:

```sh
JAVA_HOME=/Library/Java/JavaVirtualMachines/jdk-25.jdk/Contents/Home nice -n 10 \
  ./gradlew blockFeatureTest datapackTest shoreTest --no-parallel --max-workers=1 \
  -PcargoExecutable=/Users/cyberpwn/.cargo/bin/cargo \
  -PtestPack=run/datapacks/Terralith.zip
JAVA_HOME=/Library/Java/JavaVirtualMachines/jdk-25.jdk/Contents/Home nice -n 10 \
  ./gradlew check build gpuTest regionTest previewTest structureTest shoreTest \
  --no-parallel --max-workers=1 \
  -PcargoExecutable=/Users/cyberpwn/.cargo/bin/cargo \
  -PtestPack=run/datapacks/Terralith.zip
```

Logs are in `build/registered-disks-reference.log`,
`build/registered-disks-rng-reference.log` and
`build/registered-disks-integration.log`. Native compilation remains limited to
two jobs at lowered priority. Reference work and region benchmarks run
sequentially.

## Full-profile measurements

Metal / Apple M4 Max, seed `123456789`, twenty adjacent regions spanning
negative coordinates, two warmups, all exported structures/decorations/ores,
384-block height. These runs use specialized material/cave programs, the normal
compact terrain interpreter, density composition disabled and optional aquifer
column reuse disabled. Each benchmark executes in a separate process at lowered
priority; no builds or other tests run concurrently.

| Profile and input | Concurrent requests | Mean region latency, ms | Wall chunks/s | Peak RSS, MiB | Mean MCA, MiB |
| --- | ---: | ---: | ---: | ---: | ---: |
| Vanilla, prior library/profile | 1 | 184.18 | 5,551 | 968.3 | 7.457 |
| Vanilla, new library/prior profile | 1 | 183.60 | 5,569 | 1,005.6 | 7.457 |
| Vanilla, registered disks | 1 | 182.72 | 5,595 | 1,006.5 | 7.448 |
| Vanilla, registered disks | 2 | 297.78 | 6,865 | 1,086.1 | 7.448 |
| Terralith, prior library/profile | 1 | 286.13 | 3,575 | 1,600.4 | 6.405 |
| Terralith, new library/prior profile | 1 | 294.26 | 3,477 | 1,740.6 | 6.405 |
| Terralith, registered disks | 1 | 276.73 | 3,696 | 1,518.2 | 6.395 |
| Terralith, registered disks | 2 | 430.98 | 4,634 | 1,574.4 | 6.395 |

Concurrent latency measures overlapping calls; wall throughput counts completed
chunks over the total workload duration. This single sequence does not establish
a statistically repeatable speedup. The main change is fidelity, with similar
warm throughput in these samples. Both old-profile compatibility runs match all
20,480 uncompressed chunk NBT records exactly. Both two-request runs match their
respective serial runs across all 20,480 records.

Uploads/readback per region are 13.633/43.472 MiB for updated vanilla, versus
13.633/43.509 MiB previously. Updated Terralith uses 12.248/40.050 MiB versus
12.033/40.012 MiB; its additional recipes add a modest sparse-query cost. Shader
source shrinks from 1,143,877 to 972,004 bytes for vanilla and from 5,982,691 to
4,859,062 bytes for Terralith as supported disk coverage expressions disappear.
Serial GPU material work falls from 0.01871 to 0.01505 ms/chunk for vanilla and
0.02534 to 0.02013 for Terralith. Serial decoration planning measures 0.03038
and 0.09576 ms/chunk respectively. Worker/device stage times overlap and are
not additive wall times.

Cold compilation remains material. The first updated forced-specialization
run took 5,670 ms for vanilla and 56,163 ms for Terralith; measured compilation
accounted for 5,404 and 55,678 ms. Subsequent processes with the same sources
took 693 and 2,601 ms for their first regions. Warm averages exclude both
warmup regions and must not be presented as startup latency.

Separate normal `auto` mode runs use zero warmups and twenty regions. With the
driver cache already warm, updated vanilla and Terralith produce their first
regions in 257 and 439 ms while status remains pending. Their twenty-region
means are 192.93 and 357.34 ms (5,300 and 2,863 chunks/s), compared with
198.66 and 362.28 ms for the previous library/profiles. The post-compilation
regeneration checks match 1,024 chunks per run. A separate full comparison
matches all 20,480 NBT records against each corresponding warmed compiled run,
for both old and new inputs. This validates the existing pending interpreter
path, but does not certify cold-driver startup as solved. Cold compilation,
production density composition/interpolation costs and broader unimplemented
features remain part of the active generation goal.

Artifacts, library/profile hashes, stage timings and reproduction arguments are
under `build/goal-baseline/registered-disks/`: `measure.py`, `measure-auto.py`,
`summary.json`, `summary-auto.json`, `auto-parity.json` and `hashes.json`.
The prior native library SHA-256 starts `d7cf64dc6733eab1`; the tested final
library starts `f67c95cbc38c7d14`. Benchmarks never write to live saves.
