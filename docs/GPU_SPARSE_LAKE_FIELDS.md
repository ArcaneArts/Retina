# Sparse GPU interpolation stencils for lake probes

Experimental block-position density composition evaluates each registered
interpolation field before composing the density graph. Fractional lake probes
can fall outside the ordinary resident terrain atlas. Previously those probes
repeated the input graphs while scanning their vertical cells, even with the
invocation-local corner cache and interval priming.

A new compute pass fills GPU scratch stencils around the actual seeded probe
positions. Each primary field retains its registered grid size. The stencil
contains the corners needed by both the fractional point and its original
terrain-cell interval, from the rounded bottom density node through the rounded
top. The writer skips ineligible lakes, unused capacity and corners already in
the ordinary atlas. It samples uncovered corners through the original specialized
GPU input graphs, including their nested interpolation operators.

The subsequent probe shader uses the ordinary exact-coordinate atlas check, then
the probe stencil. Global floor arithmetic, aligned-axis handling, interpolation
mixing, root evaluation and interval widening remain unchanged. Primary-field
input fallback call graphs are removed from that hot shader. The raw writer and
the ordinary fallback probe pipeline remain available separately.

Probe coordinates, field samples and stencils stay on the GPU. There is one extra
compute pass, no CPU noise calculation, new binding or dense readback. Host work
allocates optional scratch and sets a request flag. The existing F3 **Lake density
probes / cache** row measures the writer and probe passes together, so the cache's
cost is included. Timing ABI 7 is unchanged.

## Selection and fallback

The cache defaults on for Metal when experimental density composition is enabled
and specialization is ready. `RETINA_LAKE_SPARSE_FIELDS=0/1`, or the benchmark's
`--lake-sparse-fields disabled/enabled`, controls diagnostics. Other backends
retain their earlier default. Composition itself remains **off by default**:
this milestone alone does not justify changing production terrain semantics.

Eligibility comes from the loaded graph. Primary interpolation operands must be
the original XYZ coordinates, inputs must be context-independent and retained by
the field cache plan, and primary/terrain cell sizes must be binary. Fixed stencil
capacity is derived from those registered sizes. Actual requests must have matching
terrain steps, bounded representable coordinates and sufficient real device buffer
and dispatch capacity. Unsupported profiles, far coordinates, partial batch
eligibility and pending or failed compilation select the existing GPU sampler.
They do not reject world generation or switch to Minecraft's CPU generator.

The exact generated helper source participates in pipeline identity. Configuration
is captured before the background compilation job; the compiler does not reread
environment variables. Each submission chooses a complete sampler and scratch
layout. Both chunk and MCA paths use this selection, including temporary DH regions.
Existing saved terrain, the temporary cache capacity and promotion behavior retain
their existing handling.

## Correctness and validation

An independent GPU interpreter bypasses both the rectangular and sparse caches.
The oracle compares every returned density root and both interval endpoints,
using actual seeded lake probes at every tested block Y, fractional Y and all
original horizontal cell corners. It checks that sparse lookups really occur.
Cases cover three seeds, negative/nonaligned origins, coordinates around two
million blocks, rounded negative vertical bounds, and absent/full/reduced atlases.
An additional fixture exercises nested fields with different binary grid sizes.

The initial nested oracle used a 1,024-slot reference scratch array and hit a
repeatable Metal compiler XPC failure. It now uses the registered live-register
capacity in the uncached interpreter and bounds evaluator. The same nested graph
and comparisons then pass. This is a diagnostic shader change, not a production
backend workaround or evidence of a cold-start improvement.

Actual vanilla, actual Terralith and the nested fixture pass **5,601,960 root
float-bit comparisons** and **1,867,320 interval endpoint comparisons**. The
Gradle `nativeLakeSparseGpuTest` derives the nested binary-grid fixture from the
registered interpolation fixture and is included in `gpuTest`. Its final rerun
passes in `build/sparse-lake-fixture-final-validation.log`. Native checks cover rounded rows, mismatched steps,
far-coordinate fallback, remapped inputs, context-dependent inputs, nonbinary
fields and world-only shader helper isolation.

`build interpolationTest nativeLakeSparseGpuTest aquiferTest regionTest previewTest
snowTest` passes with composition enabled. It includes 61 native unit checks,
Minecraft-reference density/aquifer checks, region decoding and preservation of
edits, parallel requests, temporary DH promotion/LRU/save isolation and bare
regular ice. The frozen-ocean check verifies 256 exposed ice blocks with no snow,
256 snowy land columns and 196,608 matching MCA/chunk blocks. Integration evidence
is `build/sparse-lake-fields-validation.log`.

## Paired region measurements

Apple M4 Max / Metal, seed 123456789, complete current vanilla and Terralith
profiles including structures and decorations. Each process measures **20
adjacent regions after two warmups**. The same retained library, interpreted
terrain stages and ready specialized world stages are used throughout. The only
variable is `RETINA_LAKE_SPARSE_FIELDS`. Serial repeats reverse selection order.
All builds, tests and measurements run sequentially at reduced priority; native
compilation uses two jobs. Other desktop activity and driver-cache warmth remain
uncontrolled. Concurrent throughput uses whole-run wall time, rather than the
inverse of overlapping request latencies.

| Profile / callers / order | Mean region ms, off → on | Chunks/s, off → on | Lake GPU ms/region, off → on |
| --- | ---: | ---: | ---: |
| Vanilla / 1 / forward | 211.42 → 209.53 | 4,837 → 4,881 | 4.72 → 1.53 |
| Vanilla / 1 / reversed | 211.53 → 207.27 | 4,835 → 4,934 | 4.74 → 1.51 |
| Vanilla / 2 | 361.79 → 355.01 | 5,648 → 5,692 | 5.56 → 5.03 |
| Terralith / 1 / forward | 409.38 → 373.03 | 2,500 → 2,743 | 47.24 → 10.51 |
| Terralith / 1 / reversed | 412.82 → 375.74 | 2,479 → 2,723 | 48.74 → 10.32 |
| Terralith / 2 | 667.97 → 649.15 | 2,947 → 3,152 | 48.12 → 12.60 |

Terralith's repeated serial region time falls about 9%, with about 10% higher
throughput; its two-caller throughput rises 6.9%. Vanilla serial throughput rises
0.9–2.1% and its two-caller throughput rises 0.8%. The lake stage improves in all
pairs, but substantially less in the concurrent vanilla pair. These shared-machine
results do not establish a universal speedup or justify enabling density composition
in production. The cache writer's time is included in the lake column above.

All **204,800** paired decompressed NBT comparisons match. Twenty-region files
remain exactly 157,827,072 bytes vanilla and 136,658,944 bytes Terralith. Serial
transfers remain approximately 14.297 / 45.977 MB uploaded/read back per vanilla
region and 12.622 / 42.543 MB per Terralith region. Concurrent batching causes small
existing query-transfer differences; field stencils add no dense readback.

Candidate peak process RSS is 1,145–1,244 MiB vanilla and 2,398–2,973 MiB Terralith,
versus 1,134–1,232 / 2,268–2,360 MiB without stencils. This includes compiler,
driver, comparison readers and assembly. There is no memory-improvement claim;
the optional extra pipelines and GPU scratch have a cost.

Driver-warm compilation increases 978–986 → 1,149–1,158 ms vanilla and
5,189–5,225 → 6,019–6,047 ms Terralith. Initialization remains 51–54 ms; profile
registration takes 659–673 / 1,227–1,266 ms. First forced-specialization warmups
increase 1,238–1,250 → 1,416–1,430 ms vanilla and 5,854–5,888 → 6,573–6,583 ms
Terralith. The ready-pipeline benchmark waits for all optional variants. These
measurements neither describe empty-driver-cache startup nor establish a startup
speedup.

## Final artifact checks and remaining work

Production-default checks, with composition disabled, preserve all **40,960**
records against the retained previous library's twenty-region outputs. Their
means are 190.03 / 293.47 ms vanilla/Terralith, at 5,380 / 3,486 chunks/s. They
establish output preservation, not an isolated production optimization gain.
Production remains faster than composition in these workloads, so composition
stays disabled by default.

Automatic-mode checks start generating without awaiting specialization. First
regions take 450 / 716 ms vanilla/Terralith. Both profiles finish two measured
regions while compilation is still pending, then wait another 203 / 4,097 ms
before checking activation. All initial and regenerated NBT matches the paired
composed baseline: **6,144** additional records. These are driver-warm native
checks, exclude Java registry export and world loading, and do not resolve the
remaining cold-start requirement.

Two-caller individual-chunk comparisons against the previous native library also
preserve **2,365,440 block values** and **6,144 column records**. They reverse request
order and include chunk/region edges, nonaligned vertical bounds, varied seeds,
negative positions, positions around two million blocks and requests beyond the
sparse cache's coordinate limit, which retain the ordinary GPU sampler.

Across paired runs, exploratory small trials, production checks and activation
checks, **256,000** NBT records match. The standalone final library and the JAR's
native member are SHA-256
`d9d0b0d16aac1cb3f613fa93cae9fca85463dcc19631322df79e8a219950f907`.
Artifacts are `build/goal-baseline/sparse-lake-fields/`: `final.dylib`, retained
`before.dylib`, full profiles, per-run measurements, `paired.py`, `final-checks.py`,
`chunks.py`, root-oracle logs and `evidence.json`. The retained previous library
is SHA-256 `d7446b06dc149a8d7a1f0007ad72939ebadc44edec724c72bc3cb0e28da82a8b`.

Fast production density composition, cold shader activation, substantial profile
compilation and remaining registered recipe/provider/filter coverage are still
required goal work. Keeping the experiment disabled does not complete them.
