# Experimental block-position density composition

The production generator still interpolates its final density lattice for
terrain classification. An experimental GPU path now evaluates arithmetic and
range choices at block positions after sampling each registered interpolation
field on its own lattice. It retains the loaded graph's operator ordering rather
than interpolating an already-composed result. No CPU noise substitute or normal
Minecraft generator is involved.

This path requires both the exported `registry_program.density_composition`
capability and `RETINA_DENSITY_COMPOSITION=1`. New registry exports include the
capability; the environment switch is off by default. Old profiles retain their
previous behavior. The benchmark's `--density-composition enabled` explicitly
selects the experiment. It is not a saved-world option or the production default:
measured whole-region regressions still need to be resolved before enabling it.

## GPU execution

The existing resident interpolation atlas retains all relevant vertical samples
for composition queries, including finer fields within coarser terrain cells.
The three-float terrain-node layout carries the legacy density and a lower/upper
interval for each cell. Supported arithmetic propagates intervals; interpolation
intervals use surrounding field vertices. Unsupported operations, nonfinite
values, large coordinates and oversized vertex ranges remain unbounded and use
the existing GPU point evaluator. Rounded endpoints are widened; near-zero cells
remain uncertain. Bounds accelerate classification without replacing the loaded
density values. These numerical certificates remain experimental, with sampled
enclosure tests rather than a proof for every possible registered graph/backend.

Surface extraction scans descending vertical cells, skipping certified empty
cells and directly testing block positions in uncertain cells. Cave and aquifer
classification share point composition and cell certificates. Cached samples
retain the existing exact-coordinate validation and raw GPU fallback. The
interval atlas and individual field samples stay on the GPU; there is no dense
density readback.

Out-of-tile lake probes initially performed a complete vertical scan in one
invocation. They now compute interval-guided, per-layer maxima in parallel and
reduce those results across the existing probes. This greatly reduces that
experiment's serial probe work while preserving its complete chunk output.
The old lake extraction remains selected for production interpolation.

Interpreter pipelines are keyed by scratch capacity, interpolation depth and
composition selection. Specialized cache identities include the exact source
after the same selection. Disabled composition is a compile-time false branch,
so its additional point-evaluation call graph need not inflate register pressure
in production kernels. Compilation finishing during a request cannot change its
layout or stage selection.

## Prototype measurements

Apple M4 Max / Metal, seed 123456789, complete vanilla and Terralith profiles
including structures and decorations. These exploratory runs have **two measured
regions and one warmup**, run sequentially; they are not twenty-region performance
claims. Desktop activity was uncontrolled. All rows enable composition.

| Profile / prototype | Mean region ms | Chunks/s | Peak process MiB |
| --- | ---: | ---: | ---: |
| Vanilla / serial lake probes, interpreter | 6,103.98 | 167 | 576 |
| Vanilla / parallel lake probes, interpreter | 419.77 | 2,345 | 569 |
| Vanilla / parallel lake probes, mixed stages | 1,044.50 | 979 | 764 |
| Terralith / serial lake probes, interpreter | 17,289.32 | 59 | 762 |
| Terralith / parallel lake probes, interpreter | 818.93 | 1,249 | 782 |
| Terralith / parallel lake probes, mixed stages | 2,265.60 | 452 | 1,590 |

These remain slower than the production mixed path documented in
[GPU stage selection](GPU_STAGE_SELECTION.md). In the vanilla mixed experiment,
the aquifer mask alone takes about 687 ms per region, despite faster material and
column kernels. Moving composition onto the GPU has not demonstrated a net
throughput improvement. Removing heavy raw-field fallback call graphs from
provably resident point queries is a possible next step, not an implemented gain.

All 8,192 complete NBT comparisons pass across the original/parallel-probe and
interpreted/mixed pairs. Each pair's two measured MCA files total exactly
15,147,008 bytes vanilla or 12,271,616 bytes Terralith. The parallel-probe
interpreter transfers approximately 14.13 / 45.79 MB uploaded/read back per
vanilla region and 11.89 / 42.09 MB per Terralith region. Transfer differences
include existing sparse-query batching. These are experimental terrain outputs,
not an equality claim against production's final-field interpolation.

## Validation and remaining work

After separating the disabled branch at shader compilation, eight production
checks each generate twenty regions with two warmups. Freshly exported profiles
contain 56 / 151 biomes, 172 / 510 decoration recipes, full structures and
2,156 / 3,673 palette-aligned snow-support entries. Composition remains disabled.

| Production profile | Serial mean ms | Serial chunks/s | Two-caller mean latency ms | Two-caller chunks/s |
| --- | ---: | ---: | ---: | ---: |
| Vanilla, loaded snow support | 170.03 | 6,013 | 280.72 | 7,281 |
| Terralith, loaded snow support | 224.42 | 4,557 | 379.83 | 5,324 |

Concurrent throughput uses total measured wall time, not the inverse of
overlapping request latency. Serial/concurrent peak process RSS is 980 / 1,077
MiB vanilla and 2,252 / 1,717 MiB Terralith. Comparison readers, native profile,
compiler and driver memory are included; this is not a memory-reduction claim.
Dynamic serial transfers are 14.295 / 45.622 MB uploaded/read back per region
vanilla and 12.615 / 41.954 MB Terralith. Concurrent Terralith readback is
41.925 MB/region from existing query coalescing. Twenty-region MCA totals are
153,845,760 / 130,899,968 bytes with actual support rules.

Four additional diagnostics use the previous complete profiles and an explicit
all-true support table to isolate the GPU change from intentional snow removal.
Their serial means are 167.81 / 228.48 ms; two-caller throughput is 7,346 / 5,480
chunks/s. All 40,960 serial chunk records match retained pre-change NBT, and all
81,920 additional serial/concurrent chunk comparisons pass across both diagnostic
and loaded-support profiles. These comparisons validate disabled-path output and
request-order independence. They do not establish a speedup from the new shader
branch or compare changed snow blocks as if they were a semantics-preserving fix.

Loaded-profile initialization is driver-warm at 44–49 ms; registration is
516–524 ms vanilla and 1,021–1,026 ms Terralith. First forced-specialization
warmups are 689–712 / 2,699–2,797 ms. Earlier diagnostic requests with newly
activated shader identities take 1,849 ms initialization and 18,773 ms first
warmup vanilla, or 44 ms initialization and 60,525 ms first warmup Terralith.
Forcing ready specialization waits for compilation; these are not automatic
interpreter startup measurements. They also demonstrate that cold shader costs
are still substantial, not resolved by the warm runs.

The retained final native library SHA-256 is
`cc6e196fbf018284a7b25525b5b38e9ccd8fbdc58b4c7f1d411d199f94cc8d6c`.
The artifact manifest records actual profile hashes. Each run's `measurements.json`
records stages, uploads/readback, startup, RSS, output sizes and NBT comparisons.

`interpolationTest` enables the experiment and compares 8,192 columns with actual
Minecraft 26.3 analytic density samplers. It exercises post-interpolation
nonlinearity, range choices, mismatched horizontal/vertical lattices, negative
coordinates and chunk halo boundaries in interpreted and specialized paths.
1,024 columns distinguish the previous final-field interpolation. Original scope,
nested-slice and independent eight-corner GPU noise checks also pass.

The actual vanilla and Terralith primary graphs each pass 512 finite cell-bound
enclosure checks in both compact interpreted and specialized execution: 2,048
finite enclosures in total. The specialized fixture populates both horizontal
and interpolation caches, matching its real prepass dependencies. The direct
reference bypasses the interpolation cache. A further 512 far-coordinate checks
exercise unbounded certificates and the direct point fallback. This is sampled
evidence on Metal; arbitrary graphs and other backends remain unproven.

`build gpuTest regionTest previewTest structureTest blockFeatureTest datapackTest`
passes with the production default, including 49 native unit checks, the new
[snow-support regression](SNOW_SUPPORT.md), biome/material/aquifer/provider
oracles, both modes, negative boundaries, serialized metadata, DH temporary
promotion, existing edits and save isolation. The combined log is
`build/snow-density-integrated-validation.log`. Analytic diagnostic evidence is
`build/density-composition-diagnostic-validation.log`; bounds logs are
`build/density-composition-{vanilla,terralith}-bounds.log`.

Raw exploratory measurements and retained libraries/profiles are under
`build/goal-baseline/density-composition/`. Fast production block-position
composition, cold generic pipeline activation and remaining substantial profile
compilation are still required goal work. Keeping this experiment disabled does
not complete those requirements.
