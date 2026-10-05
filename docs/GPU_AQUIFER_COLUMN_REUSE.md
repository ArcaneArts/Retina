# Local horizontal reuse in aquifer surface searches

The registered aquifer preliminary-surface search repeatedly evaluates its density
at one X/Z location while descending through Y. Specialized shaders now calculate
expensive, pure horizontal expressions once per invocation. The actual vanilla
graph retains three values; Terralith 2.6.5 retains seven. These include nested
terrain splines and the horizontal upper bound.

`column_program::Plan::for_programs` uses the existing dependency, coordinate and
semantic-sharing analysis for this single graph. The compiler emits a scalar
struct, its initializer and a density evaluator reading those scalar members.
The initializer uses the same opcode expressions, parameters, spline knots and
`graph_round` boundaries. It shares dominating dependencies between cached roots.
Y-dependent expressions, context operations and registered interpolation queries
remain in the evaluator. Explicit coordinate slices cache only when their actual
dependencies replace Y. Missing output slots continue to read node zero.

Only the initial bound evaluation and the descending loop in `aquifer_surface`
use this struct. Every invocation initializes its own values with its own X/Z and
request, including seed and profile. It needs no resident-atlas coverage test,
global mutable cache, extra dispatch, buffer binding, allocation or host round trip.
It also covers the aquifer's wider surface halo and fractional represented
coordinates. The registered lower bound, upper bound, search step and final level
are unchanged. Rust still receives the existing compact material runs.

An explicit registered height has no repeated vertical search and retains the
original graph. Disabled aquifers and graphs without suitable expressions also
retain it. Pending or unsupported specialization uses the existing GPU
interpreter. The exact generated source remains part of pipeline-cache identity.
Finishing compilation does not switch pipelines within an already encoded batch.

This reuse remains **off by default on every backend**. Serial results show small
benefits for some workloads, but a final repeat regressed concurrent throughput.
The diagnostic environment variable `RETINA_AQUIFER_COLUMNS=0/1`, or benchmark
argument `--aquifer-columns disabled/enabled`, selects the alternative. This changes execution
strategy, not saved world configuration or registered generation data.

F3's existing **Aquifer fields** line measures the same preliminary-surface,
center and barrier passes. There is no additional output format or timing ABI.
MCA and chunk generation select the same evaluator, including DH previews.

## Matched measurements

Apple M4 Max / Metal; seed 123456789; full actual vanilla and Terralith profiles
including structures and decorations; two warmups and twenty adjacent regions
per process. Baseline is `30e5ea4`, native SHA-256
`77335d04e22b496840ba869c6831a91a7d28ff68bb187d627791b23950331808`.
The retained cache prototype and final implementation generate the same cached
WGSL. Builds, tests and benchmarks ran sequentially at reduced priority. Other
desktop activity was uncontrolled. Serial selection order is reversed in the
repeat. Two callers' latency overlaps; their throughput uses whole-run wall time.

| Density mode / profile / run | Before mean ms | Reuse mean ms | Before chunks/s | Reuse chunks/s |
| --- | ---: | ---: | ---: | ---: |
| Production / vanilla / first serial | 180.83 | 178.63 | 5,654 | 5,723 |
| Production / vanilla / reversed serial | 179.25 | 178.21 | 5,704 | 5,738 |
| Production / vanilla / two callers | 293.54 | 297.03 | 6,750 | 6,771 |
| Production / Terralith / first serial | 281.49 | 271.95 | 3,634 | 3,762 |
| Production / Terralith / reversed serial | 275.69 | 271.63 | 3,711 | 3,766 |
| Production / Terralith / two callers | 444.22 | 455.02 | 4,493 | 4,493 |
| Composition / vanilla / first serial | 198.33 | 198.83 | 5,155 | 5,143 |
| Composition / vanilla / reversed serial | 200.43 | 203.89 | 5,102 | 5,016 |
| Composition / vanilla / two callers | 347.69 | 334.48 | 5,877 | 6,112 |
| Composition / Terralith / first serial | 330.74 | 329.43 | 3,094 | 3,106 |
| Composition / Terralith / reversed serial | 325.50 | 325.63 | 3,143 | 3,142 |
| Composition / Terralith / two callers | 648.77 | 500.49 | 3,133 | 3,947 |

These initial pairs improve production serial throughput
**0.6–1.2% vanilla / 1.5–3.5% Terralith**.
There is no meaningful production concurrent gain. Serial aquifer fields fall
from 20.45–20.97 to 19.34–19.65 ms for vanilla, and 26.26–26.38 to 23.87–24.36 ms
for Terralith. This is a modest optimization, not a dramatic throughput increase.
Composition serial results are neutral or slower despite lower field time. Its
single concurrent Terralith pair improves substantially, but that does not
establish a general improvement or justify changing its default.

The final packaged candidate was then measured in twelve further production
processes, with the same profiles, twenty regions, two warmups and reversed serial
order. Its native SHA-256 was
`6928f782707bd7a36fb62e94166a0a2e39bab89722a69b7f7a6c2bc053578e57`.

| Profile / final repeat | Before mean ms | Reuse mean ms | Before chunks/s | Reuse chunks/s |
| --- | ---: | ---: | ---: | ---: |
| Vanilla / first serial | 185.91 | 183.55 | 5,499 | 5,571 |
| Vanilla / reversed serial | 180.92 | 183.13 | 5,651 | 5,583 |
| Vanilla / two callers | 289.62 | 298.83 | 7,056 | 6,838 |
| Terralith / first serial | 284.66 | 282.34 | 3,594 | 3,623 |
| Terralith / reversed serial | 282.31 | 280.46 | 3,623 | 3,647 |
| Terralith / two callers | 433.19 | 454.44 | 4,560 | 4,415 |

Final serial vanilla is mixed (+1.3% / -1.2%); Terralith improves 0.7–0.8%.
Concurrent throughput falls 3.1% / 3.2%. The candidate's default was therefore
changed back to **disabled** before the final build. The explicit enabled/disabled
strategies and generated graph arithmetic were retained. This is a measured
optional execution strategy, not an across-the-board production speedup.

Warm driver-cache profile compilation rises from 488–492 to 550–558 ms for
production vanilla and 2,577–2,593 to 2,661–2,710 ms for Terralith. Composition
compilation rises from 1,127–1,141 to 1,291–1,316 ms and 5,920–5,970 to
6,223–6,259 ms. Initial new-source probes cost 1.26 / 3.65 seconds in production.
These are native compiler measurements, not cold-cache desktop world-opening
times. No startup gain is claimed.

Peak RSS is mixed: production vanilla 975–1,047 → 1,022–1,124 MiB; Terralith
1,658–1,941 → 1,717–1,751 MiB. Composition vanilla 1,092–1,259 →
1,205–1,208 MiB; Terralith 2,414–2,671 → 2,408–2,684 MiB. No uniform memory
reduction is claimed. Per-region uploads/readbacks remain about
14.295/45.623 MB vanilla and 12.618/41.955 MB Terralith in production;
14.297/45.977 and 12.622/42.543 MB with composition. Small concurrent differences
come from batching. Twenty MCA files total 156,385,280 / 134,328,320 bytes in
production and 157,827,072 / 136,658,944 with composition, unchanged within pairs.

Artifacts are in ignored `build/goal-baseline/aquifer-columns/`: retained native
libraries, scripts, logs, full MCA outputs and detailed measurement JSON. Complete
NBT matches in every matched comparison; output equivalence is checked independently
of timing.

## Rejected additional experiment

A second prototype evaluated aquifer exclusion before floodedness, calculating
floodedness only when exclusion was nonpositive. Raw GPU comparisons retained
all observed values; a synthetic Y exclusion exercised both branches. Sampled
actual profiles contained no positive exclusions, so the experiment did not
demonstrate avoided floodedness work in those samples.

Sixteen further twenty-region processes compared this prototype with horizontal
reuse alone, reversing serial order. Vanilla production changed +0.8% / -0.8%;
Terralith changed +0.5% / +1.6%. Composition results were mixed and aquifer field
time did not consistently fall. The additional production code was removed.
Its source, libraries, oracle results and benchmark records remain in ignored
artifacts. They are not evidence of a shipped floodedness speedup.

## Validation and remaining work

`nativeAquiferColumnsGpuTest` exports an actual vanilla benchmark profile, then
compares six cached root outputs against a separate, raw compact GPU interpreter
at 64 heights, six requests and 256 X/Z positions per request. It covers varying
seeds/context, negative and fractional coordinates, roughly two million blocks,
and both signs around 16.8 million blocks. All 589,824 roots per actual profile
match float bits; both vanilla and Terralith were tested. Unit checks cover
explicitly replaced Y, actual 3D fields, context exclusions, implicit roots and
the exact search call sites. No CPU spatial-noise oracle is substituted.

The full `check build gpuTest regionTest previewTest structureTest blockFeatureTest
datapackTest` run passed with density composition and aquifer reuse explicitly
enabled (3m 56s, 88 structured Java QA events). It covered registered material
layers, Minecraft aquifer equations, structures, feature placement/survival,
bare frozen oceans, chunk/MCA consistency, full/partial/placeholder DH promotion,
saved edits, cache capacity/isolation and cleanup. After disabling the default,
`build` passed again, including 64 native unit checks and registry lifecycle tests.
The shipping native binary and packaged JAR contain the same SHA-256:
`d7cf64dc6733eab10d08285087d3df3a36a182281cbe46883862cd6a791bbdc5`.

Final direct chunk checks use both density modes, both actual profiles, two
callers and reversed request order. They include varied seeds, negative boundaries,
arbitrary vertical alignment and roughly two million / 9.6 million block locations.
**4,730,880 final blocks and 12,288 columns match** the baseline. Across all
matched benchmark candidates, rejected experiments and final checks,
**1,093,632 complete chunk NBT records match**; this aggregate includes the
rejected floodedness prototype, not just the shipping binary.

Four final automatic runs generated twenty regions without warmups. Production
used the shipping default (reuse disabled); composition explicitly enabled the
optional reuse. Initial region status was genuinely pending in all four runs,
then changed to ready mixed execution. A regenerated first region matched all
1,024 original NBT records in every case after compilation finished.

| Automatic run | First region ms | Mean region ms | Chunks/s |
| --- | ---: | ---: | ---: |
| Production vanilla / default | 278.93 | 197.34 | 5,181 |
| Production Terralith / default | 465.56 | 356.16 | 2,873 |
| Composition vanilla / optional reuse | 450.66 | 235.49 | 4,343 |
| Composition Terralith / optional reuse | 730.63 | 549.97 | 1,861 |

These include interpreter-to-specialized transitions and are startup observations,
not paired speedups or full desktop opening times. No user visual response is
needed to finish this measured milestone.

This milestone does not finish the broader generation goal. Production still
uses final-field density interpolation; block-position composition remains
opt-in and slower. Cold startup/compiler work, further registered feature support
and the existing fluid-tick approximations remain documented requirements.
