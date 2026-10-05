# Separate lake and column GPU timings

The previous `COLUMNS` timestamp surrounded lake candidate classification,
density probes, level reduction and final surface-column extraction. It could
not distinguish a slow density program from slow column extraction. Each pass
now receives its own pair of device timestamps in the existing command buffer:

| Stage | Index | F3 label |
| --- | ---: | --- |
| Final column extraction | 5 | Column surfaces |
| Lake candidate classification | 26 | Lake candidates |
| Registered lake density probes | 27 | Lake density probes |
| Shared lake level extraction | 28 | Lake level reduction |

MCA reports copy the three new counters from the actual terrain job, rather than
subtracting a shared session snapshot that concurrent requests could contaminate.
F3 uses the same twenty-region window and colored percentages for all four rows.
Per-chunk mode reports the separate lake stages as well. Device times overlap
host wait/planning times; they are not added to the wall-time accounting.

## ABI and transfer cost

Timing ABI 7 has 29 stages, a 264-byte snapshot and a 304-byte detailed region
report. Indices 0–25 retain their meanings except that `COLUMNS` now measures only
columns. The ordinary 40-byte region report and terrain request ABI are unchanged.
Java and Rust must come from the same build, as must client/server stats packets.
This does not change saved chunk or world formats.

Each readback slot reserves twelve timestamp pairs; the largest terrain batch
uses eleven. The aligned resolve buffer is still 256 bytes. Splitting the four
passes adds 48 bytes of timestamp readback per registered terrain submission;
it adds no dispatch, GPU/CPU round trip, CPU spatial computation or terrain data.
Backends without timestamps retain host timings and the existing unavailable
device-timing display. The benchmark reader accepts retained ABI 6 libraries and
records `timing_version` and `columns_include_lakes` to distinguish old reports.

## Actual-profile measurements

Apple M4 Max / Metal, seed 123456789, full current vanilla/Terralith profiles
(56/151 biomes, 172/510 decoration recipes, structures and loaded snow support),
twenty adjacent regions with two warmups, one caller. Experimental composition
preserves arithmetic after individual interpolated fields; production retains
its current final-field interpolation. These are profiling measurements, not a
speedup claim. Desktop activity and shader/driver cache state are uncontrolled.

| Profile / mode | Region mean ms | Columns ms | Candidates ms | Density probes ms | Level reduction ms |
| --- | ---: | ---: | ---: | ---: | ---: |
| Vanilla / production | 179.20 | 5.48 | 1.58 | 1.29 | 0.19 |
| Vanilla / composition | 215.11 | 5.55 | 1.61 | 18.84 | 0.14 |
| Terralith / production | 245.86 | 6.64 | 5.88 | 8.40 | 0.54 |
| Terralith / composition | 409.10 | 6.67 | 5.67 | 113.59 | 0.44 |

All **81,920** decompressed chunk NBT records match the corresponding retained
pre-telemetry implementation. Production whole-run throughput is 5,705 / 4,160
chunks/s; composition is 4,754 / 2,501. The density-probe pass explains much of
the experimental Terralith regression, while column extraction changes little.

Production/composed peak process RSS is 1,002 / 1,117 MiB vanilla and
1,533 / 1,899 MiB Terralith. Process RSS includes the benchmark's NBT comparisons,
driver and compiler. Production dynamic upload/readback is 14.295 / 45.623 MB per
region vanilla and 12.615 / 41.955 MB Terralith; composition is
14.297 / 45.977 MB and 12.619 / 42.542 MB. Twenty production MCA files total
153,845,760 / 130,899,968 bytes, composition 155,099,136 / 132,972,544 bytes.

Initialization is driver-warm at 53–56 ms. Registration is 556–604 ms vanilla and
1,156 ms Terralith. First forced-ready production/composed warmups take
794 / 1,110 ms vanilla and 3,106 / 5,132 ms Terralith. These await specialization;
they do not prove cold or automatic world-startup performance. The existing
startup and fast production-composition requirements remain outstanding.

## Rejected paired resident/general probe trial

A prototype checked actual fractional lake points against resident interpolation
field headers on the GPU. Covered points ran through a direct resident sampler;
uncovered points retained the existing general sampler in a second pass. It
added no readback or host noise and preserved 4,096 complete chunk NBT records.

Matched two-region runs with one warmup measured baseline/prototype region means
of 208.37 / 224.60 ms vanilla and 378.91 / 455.11 ms Terralith. Lake density itself
was 25.67 / 25.96 ms and 137.91 / 146.89 ms. This small trial establishes no gain;
the extra pass and coverage test were removed. It does not identify whether
coverage checks, register pressure or general-pass invocation overhead dominate.

## Validation and evidence

`build gpuTest regionTest previewTest` passes, including 51 native unit checks,
the 29-stage network packet, rolling F3 percentages, actual measured lake times
in DH region/session reports, both generator modes, MCA decode/edits, concurrent
requests, temporary-region promotion, the 1,024-region LRU and save isolation.
The suite also retains the regular-ice snow regression and existing GPU density,
material and aquifer reference checks. The real DH test reports positive
candidate/probe/reduction device durations (395,875 / 330,125 / 117,833 ns).

Evidence: `build/lake-timing-final-validation.log` and
`build/goal-baseline/density-composition/{vanilla,terralith}-lake-timing-{production,composed}-20/measurements.json`.
Matched rejected trials are `*-lake-{timing-matched,resident}-small-2`.
The retained telemetry library `lake-timing.dylib` has SHA-256
`9aceff7774588406dbbda17487a201a8df1b97eeda4d01db7211bbf7c6601f01`.
