# Reuse interval vertices in GPU lake probes

Composed lake scans evaluate conservative density intervals before sampling
individual block positions. The interval evaluator already visits the registered
interpolation field's grid vertices. It now saves the last cell's exact samples
in the [existing invocation-local point cache](GPU_LAKE_POINT_CACHE.md), so later
point queries can reuse that work.

The last two vertices on each axis form the primed cell; descending lake scans
usually query it first. Singleton axes populate only the corners actually visited.
Each store verifies that the sampled coordinate's float bits match the coordinate
the point sampler would use. A different cell, context or rounding result keeps
the original direct fallback. Fully aligned point queries retain their original
path. Input graphs are acyclic, and each invocation has one immutable request.
Neither samples nor validity bits cross requests, profiles, seeds or GPU threads.

The interval evaluator's values, widening, min/max order and interpolation mixing
are unchanged. Unsupported or oversized boxes still return an uncertain interval
and require ordinary GPU point evaluation. Priming adds no cache storage beyond
the existing sixteen declared words per field, no dispatch, binding, transfer or
CPU spatial sampling. This is not a claim about physical registers or spills.

Only the specialized `lake_density` pipeline changes. Other entrypoints, including
the interpolation prepass, retain their original samplers. Both chunk and MCA
requests use this pipeline, including temporary DH regions. Timing ABI 7 and the
F3 **Lake density probes** row continue measuring the selected device pass.

## Selection

Priming defaults on for Metal when the invocation-local lake cache and experimental
density composition are both enabled, interpolation fields exist and specialization
is ready. Composition remains **off by default**. Other backends keep the previous
sampler unless explicitly selected for diagnostics.

`RETINA_LAKE_PRIMED_CORNERS=0/1`, or the benchmark's
`--lake-primed-corners disabled/enabled`, provides a matched comparison. The GPU
captures this selection during initialization. An immutable source marker becomes
part of the compilation job's exact cache identity; the background compiler does
not reread environment variables. Each submission selects one complete pipeline
bundle. Pending or failed compilation retains the GPU interpreter, and existing
saved terrain and world configuration remain unaffected.

## Paired measurements

Apple M4 Max / Metal, seed 123456789, current complete vanilla and Terralith profiles
(56/151 biomes and 172/510 decoration recipes, with structures). Each process
measures twenty adjacent regions after two warmups. Builds and measurements run
sequentially, at reduced priority, with two Cargo jobs. Other desktop activity and
driver-cache warmth are uncontrolled. Two-caller throughput uses whole-run wall
time, rather than the mean of overlapping request latencies.

All rows use the same prototype library, density composition, interpreted terrain
and a ready specialized lake pipeline. The original point cache remains enabled
in both selections; the only variable is whether interval vertices prime it.
Serial repeats reverse the order of the two selections.

| Profile / callers / order | Mean region ms, original → primed | Chunks/s, original → primed | Lake GPU ms/region, original → primed |
| --- | ---: | ---: | ---: |
| Vanilla / 1 / forward | 206.02 → 206.68 | 4,963 → 4,948 | 6.65 → 4.68 |
| Vanilla / 1 / reversed | 205.80 → 204.96 | 4,969 → 4,989 | 6.67 → 4.71 |
| Vanilla / 2 | 333.47 → 339.01 | 5,935 → 6,031 | 6.66 → 5.14 |
| Terralith / 1 / forward | 365.42 → 350.68 | 2,800 → 2,918 | 60.09 → 48.06 |
| Terralith / 1 / reversed | 368.24 → 356.36 | 2,779 → 2,871 | 61.37 → 49.01 |
| Terralith / 2 | 611.77 → 578.85 | 3,211 → 3,394 | 54.74 → 46.48 |

Terralith's repeated serial region time falls about 3–4%, and two-caller throughput
rises 5.7%. Vanilla serial throughput is essentially unchanged; its two-caller
throughput rises 1.6% in this pair despite a higher average request latency. The
lake stage improves consistently, but these shared-machine measurements do not
establish a universal whole-region gain. Worker totals and device times overlap.

Every one of the paired runs' **245,760** decompressed chunk NBT records matches
the retained composed reference. Twenty-region file totals remain 155,099,136 /
132,972,544 bytes. Transfers remain approximately 14.30 / 12.62 MB uploaded and
45.98 / 42.54 MB read back per region; sparse batching causes small variations.

Peak process RSS is 1,161–1,209 MiB vanilla and 2,183–2,230 MiB Terralith with
priming, versus 1,173–1,279 / 2,173–2,331 MiB without it. This includes compiler,
driver, NBT comparison and terrain assembly and shows no clear memory improvement.
Driver-warm compilation is approximately 964–974 / 5,118–5,149 ms with priming and
963–968 / 5,089–5,119 ms without it. Initialization, registration and first forced
specialized region times are also similar. No cold-start speedup is claimed.

## Packaged artifact and validation

The final library captures selection immutably and includes the independent GPU
oracle. Unpaired twenty-region checks of that exact packaged artifact report:

| Profile / mode / callers | Mean region ms | Chunks/s | Lake GPU ms/region |
| --- | ---: | ---: | ---: |
| Vanilla / composed / 1 | 226.70 | 4,511 | 4.76 |
| Vanilla / composed / 2 | 327.59 | 6,242 | 5.29 |
| Terralith / composed / 1 | 347.13 | 2,948 | 47.99 |
| Terralith / composed / 2 | 581.49 | 3,380 | 46.60 |
| Vanilla / production / 1 | 181.90 | 5,621 | 1.24 |
| Terralith / production / 1 | 245.83 | 4,161 | 7.85 |

These six runs compare another **122,880** identical NBT records. They verify the
artifact and unchanged production output, rather than isolating a new speedup.
Composition still trails production, so enabling it by default is not justified.

Automatic-mode checks generate the first native region in 443 / 719 ms without
awaiting specialization. Terralith finishes both measured regions while its bundle
is pending; the diagnostic subsequently waits another 3,305 ms for readiness.
Regeneration after activation is NBT-identical. These checks use driver-warm shader
identities and exclude Java registry export and world loading. They do not resolve
the remaining cold-start requirement.

The independent GPU oracle executes the original bounds implementation through
an uncached interpreter, evaluates primed bounds, then compares every actual graph
root. It covers singleton, multi-cell, fractional, vertically aligned and oversized
boxes, negative and far coordinates, varied seeds, zero and changed material
contexts, partial masks and later point queries. It verifies populated masks, so
the test cannot pass by never exercising priming. The reference bypasses both
private and resident interpolation caches. Root mismatches are counted individually;
an aggregate checksum cannot cancel errors.

Actual vanilla/Terralith profiles plus the nested irregular-grid fixture pass
**2,119,680 root float-bit comparisons** and **706,560 interval endpoint comparisons**.
The fixture includes cell sizes such as 7×5, 3×5 and 5×3. Run it with
`nativeInterpolationPrimedGpuTest`; actual exported profiles use the ignored native
`specialized_roots_match_interpreter_on_real_gpu` test with
`RETINA_PROGRAM_PARITY_PROFILE` and `RETINA_PROGRAM_PARITY_PRIMED_CORNERS=1`.

`build interpolationTest nativeInterpolationGpuTest nativeInterpolationPrimedGpuTest
aquiferTest regionTest previewTest snowTest` passes with composition enabled,
including 54 native unit checks, Minecraft-reference density/aquifer checks, region
decoding and edits, parallel requests, temporary DH promotion/LRU/save isolation,
and bare regular ice. Across the retained candidate's small trials, paired runs,
final artifact runs and automatic activation tests, **382,976** NBT records match.
The JAR's native member is verified identical to the final standalone library.

Evidence is in `build/goal-baseline/primed-lake-corners/`: complete profiles,
`small.py`, `full.py`, `final.py`, raw measurements, retained libraries, root-parity
logs and `evidence.json`. The paired prototype's SHA-256 is
`291ece4f47313a68bc2d104e0e665bb57667931332f9498991fece752e133346`.
The final retained `final.dylib`, production native output and packaged JAR member
are SHA-256 `600de4bbc617b978dc61eb0105556d69aac14a5853b78b8409d68efbfc0c5544`.
Integration output is `build/primed-corners-integration-fixed.log`.

## Rejected larger corner cache

An earlier prototype shared raw interval and point samples in a direct-mapped,
eight-entry cache per field. Exact point/context tags protected correctness, but
the declared state grew from sixteen to thirty-seven words per field. Four small,
counterbalanced processes measured two regions after one warmup and compared
8,192 identical NBT records.

Vanilla's lake stage fell 6.91 → 6.00 ms, but Terralith's rose 78.73 → 97.99 ms and
its region time rose 372.77 → 402.69 ms. That candidate was removed. These small
trials neither prove a universal regression nor identify physical register pressure
as its cause; they provide no reason to retain the larger cache. New shader identity
compilation times are not matched cold-start comparisons.

Evidence is `build/goal-baseline/shared-lake-corners/`, including the source patch,
profiles, four measurement reports and `prototype.dylib`, SHA-256
`f322e9adc38a8a975b753b8dee0080537b26e39bee538f152f5575e218421891`.
Fast production composition, cold shader activation and remaining registered
provider/spatial-feature coverage remain open goal work.
