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

## Initial prototype measurements

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
throughput improvement. The resident-query optimization below now removes those
heavy raw-field fallback call graphs for covered requests.

All 8,192 complete NBT comparisons pass across the original/parallel-probe and
interpreted/mixed pairs. Each pair's two measured MCA files total exactly
15,147,008 bytes vanilla or 12,271,616 bytes Terralith. The parallel-probe
interpreter transfers approximately 14.13 / 45.79 MB uploaded/read back per
vanilla region and 11.89 / 42.09 MB per Terralith region. Transfer differences
include existing sparse-query batching. These are experimental terrain outputs,
not an equality claim against production's final-field interpolation.

## Resident density kernels and pressure pruning

Specialized composition now has additional cave-node, cave-mask, exterior,
aquifer-mask and surface-extraction pipelines which read resident field samples
without carrying raw interpolation-input graphs in their call trees. The normal
prepasses still populate those fields with the full GPU samplers. No new GPU
pass, dense output, CPU spatial sampler or readback is added.

The host derives eligibility from the loaded graph and checks the actual uploaded
cache headers for each dispatch. Surface-density queries must retain their XYZ
coordinates. Cave-node eligibility additionally checks every climate/cave root,
including unspecified root slots. All required fields need full fine-grid
coverage, binary cell sizes and representable corner coordinates within
±8,388,608. Node endpoints include the rounded vertical and horizontal grid edges;
surface extraction includes its actual four/six-block probe halo. Partial,
coarse, remapped, nonbinary or distant caches use the ordinary GPU sampler. This
selection never rejects generation. Material, lake and aquifer-field kernels
retain the general sampler for their separate contexts and out-of-tile probes.

The fast variants disconnect unreachable raw input fallbacks. Missing interval
certificates become unbounded, causing evaluation of the actual resident point.
The compiled entry set is part of the profile cache identity; variants are absent
while compilation is pending and the interpreter continues to operate.
`RETINA_CACHED_DENSITY_MASKS=0`, or the benchmark's
`--cached-density-masks disabled`, disables all these variants for diagnostics.
Composition remains off by default, so production compiles no additional variants.

Aquifer pressure now evaluates final point density only if a participating pair
has positive raw pressure. Its density proxy is at most −0.02 and participating
similarities are positive, so nonpositive pressures cannot produce a barrier.
The original multiplication order and barrier decisions are retained. This
pruning also applies to the ordinary production path.

Matched Apple M4 Max / Metal runs use seed 123456789, the complete current vanilla
and Terralith profiles, **twenty measured regions and two warmups** each. Builds,
tests and benchmarks run sequentially; other desktop activity remains uncontrolled.
Both rows below enable the same experimental composition semantics.

| Profile | Original composition mean ms | Resident kernels mean ms | Original chunks/s | Resident chunks/s | Two-caller resident chunks/s |
| --- | ---: | ---: | ---: | ---: | ---: |
| Vanilla | 1,039.73 | 228.58 | 985 | 4,475 | 5,185 |
| Terralith | 2,366.06 | 439.14 | 433 | 2,330 | 2,647 |

Original/candidate serial GPU stage milliseconds per region are 174.65 → 13.78
vanilla and 311.63 → 8.99 Terralith for cave nodes, and 691.02 → 11.88 / 1,719.91 →
14.42 for the aquifer mask. Device stages and worker totals overlap; they are not
additive components of region latency. The small surface-only experiment did not
establish an independent whole-region gain. Remaining costs include interpreted
terrain input/bounds, column/material dispatch and block-position masks.

All **81,920** serial/concurrent chunk NBT comparisons match the original composed
implementation. Twenty-region file totals remain 155,099,136 / 132,972,544 bytes.
Serial uploads/readbacks remain about 14.297 / 45.976 MB per vanilla region and
12.619 / 42.542 MB per Terralith region; a few bytes differ through existing sparse
query batching. Candidate serial/two-caller peak process RSS is 1,084 / 1,158 MiB
vanilla and 1,987 / 2,040 MiB Terralith, including comparison readers and compiler
memory. This is not a memory-reduction claim.

Initialization is driver-warm at 44–48 ms. Profile registration takes 528–549 ms
vanilla or 1,018–1,034 ms Terralith. Forced-specialization first warmups take about
977–997 / 4,469–4,487 ms; measured compilation is 684–694 / 3,700–3,720 ms. They
wait for all optional variants and do not demonstrate a cold-start improvement.
Automatic runs with no warmups produce the first region in 434 / 675 ms while
specialization proceeds asynchronously. Their twenty-region means are 248.03 /
536.16 ms. All 40,960 records match the forced-specialized composition baseline,
and a further 2,048 regenerated records match after compilation becomes ready.
These are driver-warm activation checks; cold generic shader activation and
substantial profile compilation still require further work.
A shorter Terralith check finishes two regions while compiler status is still
pending, then waits 1,966 ms for readiness; all 2,048 initial and 1,024 regenerated
records match. The benchmark now measures that wait separately from regeneration.

Production-default checks against the retained pre-change library also preserve
all **40,960** chunk records. Their means are 175.26 / 230.93 ms (5,833 / 4,429
chunks/s), versus the earlier 170.03 / 224.42 ms runs. These sequential checks
establish output preservation, not a production speedup. Production remains faster
than composition, so the experiment is still opt-in.

Artifacts are `build/goal-baseline/density-composition/`: the original library is
`final.dylib`, the candidate is `cached-surface.dylib`, and matched results are
`{vanilla,terralith}-general-composition-20`, `*-cached-kernels-20-{1,2}` and
`*-production-kernels-20-1`. Two-region pressure/mask/node trials isolate the
largest changes; they are not substituted for the twenty-region table above.
The candidate SHA-256 is
`05b2069e32a785571dd269b265804a2ce3a29d2a0ded233349124077165151d9`.
`resident-kernels-manifest.json` retains all library/profile hashes and run records.

`interpolationTest nativeInterpolationGpuTest aquiferTest regionTest previewTest
snowTest build` passes with composition enabled, including 51 native unit checks,
8,192 Minecraft-reference composition columns and 3,072,000 aquifer voxel
classifications. Checks cover nonbinary fallback, negative chunk/halo boundaries,
MCA metadata, preservation of edits and partial slots, DH temporary caching and
promotion, concurrent edits, failure cleanup and regular-ice snow exclusion.
Unit cases additionally cover missing/coarse/short headers, rounded node endpoints,
four/six-block surface halos, remapped climate/cave operands and far coordinates.
The combined log is `build/density-cached-kernel-validation.log`.

A follow-up experiment baked the immutable registered density/explicit-height
mode and program-presence branches into all specialized kernels, preserving the
dynamic rounding identity. Two-region composition trials matched 4,096 NBT records
but showed no convincing throughput gain: 232.29 / 470.49 ms vanilla/Terralith,
compared with 243.60 / 444.87 ms in the preceding small resident-kernel runs.
The experiment was reverted. Its first shader activation waited 20,420 / 83,907
ms for forced compilation; these new identities were cold relative to the prior
driver-warm pipelines, so those waits are not evidence of a comparative startup
regression. Raw results and `profile-mode.dylib` are retained under the same
artifact directory. The experiment does not change the production generator.

## Direct resident reads and density lattice

Covered point samplers now calculate the resident field base address once and
read its needed corners by X/Z/Y strides. They retain the original global floor,
aligned-axis corner skipping and trilinear mix order. The coverage proof includes
every returned evaluator root and implicit node-zero slot. Fine-grid, binary-cell
and coordinate checks still select the general GPU sampler whenever necessary;
they never prevent generation. Interval lookups keep their checked reader because
an uncertain interval can include queries outside the covered point domain.

The same optional variant now serves density-lattice nodes. Its proof includes
the actual rounded terrain lattice endpoints, vertical top and four/six-block
halo. Prepasses, material rules and out-of-tile lake probes keep their original
samplers. This adds one optional pipeline, with its identity in the compiler key,
but no extra dispatch, buffer layout, dense transfer or CPU spatial evaluation.
Production with composition disabled compiles none of these optional pipelines.

Fresh sequential Apple M4 Max / Metal comparisons use the same complete profiles,
seed 123456789, twenty measured regions and two warmups. Desktop activity is
uncontrolled. The before library is `cached-surface.dylib` (the preceding commit);
the after library is `direct-resident-final.dylib`.

| Profile | Before mean ms | After mean ms | Before chunks/s | After chunks/s | After two-caller chunks/s |
| --- | ---: | ---: | ---: | ---: | ---: |
| Vanilla, composition enabled | 232.08 | 203.87 | 4,407 | 5,017 | 6,120 |
| Terralith, composition enabled | 433.14 | 397.79 | 2,363 | 2,573 | 3,068 |

Cave-mask device time falls from 36.24 to 17.01 ms per region vanilla and 54.78
to 23.15 ms Terralith. Height/climate work falls from 32.64 to 27.69 / 92.91 to
87.91 ms. These aggregate comparisons measure direct reads plus the resident
lattice variant; they do not establish an independent benefit from either alone.
Composition remains slower than production's final-field interpolation. Production
means are 168.78 / 227.35 ms with unchanged NBT; this is an output-preservation
check, not a speedup claim for the disabled path.

All **163,840** complete chunk records match their corresponding retained or fresh
baseline across the eight serial/concurrent/production runs. Composed file totals
remain 155,099,136 / 132,972,544 bytes. Serial uploads/readbacks remain about
14.297 / 45.976 MB vanilla and 12.619 / 42.542 MB Terralith; tiny differences come
from existing sparse batching. Candidate serial/two-caller peak RSS is 1,175 /
1,234 MiB vanilla and 2,046 / 2,119 MiB Terralith, including compilation and
comparison readers. There is no memory or transfer-reduction claim.

Driver-warm candidate initialization takes 43–65 ms; registration takes 500–530 /
998–1,027 ms. Forced-specialization compilation takes 690–714 / 3,683–3,705 ms,
and first warmups take 962–983 / 4,426 ms. The first new pipeline-identity trials
take 1,870 / 4,881 ms compilation and 2,146 / 5,614 ms first warmup, so these
measurements do not establish a cold-start improvement. Automatic two-region
checks produce the first region in 434 / 683 ms. Terralith finishes both while
compilation is pending, then waits 2,049 ms for readiness. All 4,096 initial and
2,048 regenerated NBT records match after activation.

`interpolationTest nativeInterpolationGpuTest aquiferTest regionTest previewTest
snowTest build` passes, including 51 native unit checks, 10,240 Minecraft-reference
composition columns (now including asymmetric contributions from all three axes),
3,072,000 aquifer voxel classifications, MCA decoding, edits, DH temporary
promotion/LRU/save isolation, and bare regular ice. Evidence is
`build/density-direct-resident-validation.log`. Matched runs and rejected trials
are under `build/goal-baseline/density-composition/`; the final library SHA-256 is
`ee6a93599369498240b07085c44f2650dac30f76ab52e0a77b1078a70ed871a9`.

The existing `COLUMNS` timestamp spans lake candidates, lake density, lake nodes
and final columns. F3 now labels it **Surfaces / lake probes**. Its measured
32.77 / 114.98 ms per composed region cannot be attributed to column extraction
alone. A column-only classifier experiment preserved NBT but did not improve this
timer; it was reverted. Routing composed lake-density probes to the compact
interpreter also preserved 4,096 records but increased this timer from 39.31 to
57.93 ms vanilla and 110.23 to 163.79 ms Terralith in two-region trials. It was
reverted. The lattice-only two-region trial preserved 4,096 records but did not
establish a whole-region gain. Raw libraries/results are retained as
`column-pipeline`, `lake-interpreter` and `resident-lattice` diagnostic artifacts.

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
