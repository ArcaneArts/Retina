# GPU interpolation reuse within lake probes

The experimental block-position density path now reuses interpolation corners
inside each GPU lake probe. A probe tests several block Y positions within the
same interpolation cell. Previously each test loaded or evaluated those corners
again, including expensive registered input graphs outside the resident atlas.
The new sampler retains the corners already used by that invocation.

Each registered field has an exact lower-coordinate key, an exact material-context
key, an eight-bit validity mask and eight float values in WGSL private storage.
Only required corners are computed. A later query can add newly required corners;
changing cell or context invalidates the mask. Fully aligned queries retain their
original direct path, and interpolation keeps its original mixing order and f32
boundaries. Requests remain immutable within an invocation, so values never cross
seeds, profiles, GPU threads or region requests. The declared state is sixteen
32-bit words per field, not a measurement of physical registers or spills.

This changes only the specialized `lake_density` entrypoint. Its pipeline replaces
the ordinary lake pipeline in that profile's bundle; the unused ordinary copy is
not compiled or retained. Other entrypoints keep their existing samplers. There
is no new dispatch, buffer binding, GPU/CPU round trip or spatial CPU calculation.
The same path serves chunk and MCA requests, including temporary DH regions.
Timing ABI 7 and its 29 stages remain unchanged; F3's **Lake density probes** row
measures the actual selected kernel.

## Selection and compatibility

Reuse defaults on for Metal only when experimental density composition is enabled,
the profile contains interpolation fields and its specialized bundle is ready.
Composition itself remains **off by default**. Production final-field interpolation
compiles no new pipeline. Unmeasured backends retain the previous lake sampler.
`RETINA_LAKE_POINT_CACHE=0/1` or the benchmark's `--lake-point-cache disabled/enabled`
provides a matched diagnostic override.

The pipeline cache identity includes this selection. Each submission selects one
complete bundle before encoding; completion of background compilation cannot
change an in-flight request. Automatic mode retains the interpreter while the
bundle is pending or compilation fails. Existing saved blocks and world settings
are unaffected.

## Measurements

Apple M4 Max / Metal, seed 123456789, complete current vanilla/Terralith profiles
(56/151 biomes, 172/510 decoration recipes, structures and loaded snow support).
Every run measures twenty adjacent regions after two warmups, including negative
coordinates. Builds, tests and benchmarks run sequentially at reduced priority.
Other desktop activity and shader-cache warmth are uncontrolled. Concurrent
throughput uses whole-run wall time, rather than overlapping request latency.

The initial two-pipeline prototype established the sampler's effect. Counterbalanced
repeats use the same candidate library with the cache enabled/disabled. The final
bundle removes the unused pipeline; its last two pairs use that final library for
both selections. All entries below enable the same density-composition semantics.

| Profile / callers / trial | Mean region ms, uncached → cached | Chunks/s, uncached → cached | Lake GPU ms/region, uncached → cached |
| --- | ---: | ---: | ---: |
| Vanilla / 1 / initial | 213.94 → 206.52 | 4,781 → 4,951 | 18.52 → 6.82 |
| Vanilla / 1 / reversed order | 214.52 → 203.85 | 4,767 → 5,017 | 18.59 → 6.80 |
| Terralith / 1 / initial | 413.81 → 365.76 | 2,473 → 2,797 | 109.55 → 62.93 |
| Terralith / 1 / reversed order | 411.49 → 424.25 | 2,487 → 2,412 | 110.45 → 68.88 |
| Vanilla / 2 / initial | 337.95 → 359.65 | 5,986 → 5,630 | 18.88 → 7.31 |
| Terralith / 2 / initial | 818.36 → 674.95 | 2,397 → 3,031 | 120.96 → 61.47 |
| Vanilla / 2 / repeat 1 | 361.41 → 345.54 | 5,488 → 5,917 | 17.30 → 7.08 |
| Vanilla / 2 / repeat 2, reversed | 346.52 → 330.10 | 5,896 → 6,148 | 18.28 → 7.36 |
| Terralith / 1 / repeat 1 | 405.47 → 361.92 | 2,524 → 2,827 | 108.43 → 61.76 |
| Terralith / 1 / repeat 2, reversed | 406.75 → 361.12 | 2,516 → 2,833 | 109.14 → 61.06 |
| Vanilla / 1 / final bundle | 300.78 → 258.78 | 3,401 → 3,952 | 24.43 → 8.07 |
| Terralith / 1 / final bundle, reversed | 471.35 → 441.26 | 2,171 → 2,319 | 129.40 → 71.06 |

The isolated lake stage falls consistently. Whole-region gains are smaller and
variable: two original pairs regress despite the faster stage. In those runs,
other GPU/CPU stages and queue delays also increase; these shared-machine
observations do not isolate their cause. Repeated Terralith serial gains are about
12%, and repeated two-caller vanilla gains are about 4–8%. The final serial pairs
also improve, but no universal throughput gain or cold-start improvement is claimed.
Device stages and worker totals overlap and must not be added as wall latency.

All **491,520** decompressed chunk NBT records from the twenty-four composed runs
match the retained composition reference, including metadata and structures.
Twenty-region MCA totals remain 155,099,136 / 132,972,544 bytes. Dynamic transfers
stay around 14.30 / 12.62 MB uploaded and 45.98 / 42.54 MB read back per region;
small variations reflect existing sparse batching, not cache data transfer.
The full measurements retain RSS, every stage, startup and transfer counters.

The preliminary cached runs use 1,131–1,247 MiB peak RSS vanilla and 2,100–2,155 MiB
Terralith, versus 1,094–1,178 / 1,885–2,004 MiB uncached. Those totals include the
driver, compiler and NBT reader. Smaller repeated evaluation does not establish
a process-memory improvement. Driver-warm compilation is also higher in the
prototype: 975–998 / 5,092–5,170 ms versus 796–831 / 4,285–4,368 ms. Removing the
unused ordinary entry is a compiler cleanup, not a measured startup speedup.

The final production checks remain output-identical with composition disabled:
221.10 / 271.59 ms per region, 4,625 / 3,766 chunks/s. These are preservation
checks, not a speedup over the previous production build. Final composition at
258.78 / 441.26 ms is still slower, so fast production composition remains open.

## Startup and validation

Automatic final-bundle tests generate their first native region in 503 / 740 ms
without awaiting specialization. Terralith completes both measured regions while
compilation is pending, then the diagnostic waits another 3,306 ms for readiness.
Regenerating the region after activation preserves its complete NBT. These tests
use driver-warm identities and exclude Java registry export/world loading; they
do not resolve the remaining cold-start requirement.

An ignored `actual_profile_source_preparation` diagnostic separately times actual
WGSL preparation before enqueueing compilation. Five runs measure 3.76–4.39 ms
vanilla (1,143,845 bytes) and 18.49–20.68 ms Terralith (5,982,659 bytes), with 9/23
horizontal fields. This synchronous step is too small to explain multi-second
startup stalls in these profiles; merely moving it to another thread would not
establish a meaningful startup fix.

Independent GPU reference tests compare **2,055,168 root float bits** across every
actual profile graph. Each invocation makes 24 successive queries, varying aligned
axes, fractional points, cell changes, newly required corners, material contexts,
negative/far coordinates and request seeds. The reference interpreter bypasses
both caches and checks every root separately, preventing checksum cancellation.

`build interpolationTest nativeInterpolationGpuTest aquiferTest regionTest
previewTest snowTest` passes, including native unit checks, Minecraft-reference
density and aquifer checks, both generator modes, region decoding/edits,
parallel requests, DH temporary promotion/LRU/save isolation and bare regular ice.
Four production runs compare another 81,920 NBT records. Four automatic tests
compare 8,192 initial and 4,096 regenerated records across compilation activation.
The final formatting-only rebuild passes the same checks and compares another
40,960 composed chunk records. Its unpaired twenty-region means are 202.06 / 358.96
ms, with 6.70 / 59.07 ms in lake density; these artifact checks do not isolate a
speedup from formatting or replace the paired measurements above.

Evidence and retained profiles/libraries are in `build/goal-baseline/lake-point-cache/`.
`full.py`, `repeat.py` and `final.py` reproduce the paired workloads;
`*-bundle-final/measurements.json` records the final artifact. The source-preparation
logs are `build/source-preparation-{vanilla,terralith}.log`. Paired final-bundle
measurements use retained `lake-point-cache.dylib`, SHA-256
`bcb0d7b639b91e03e2f2b4f62faa61a730a5c028aef967b88ec081959755e3dc`.
The current packaged native library and retained `lake-point-cache-formatted.dylib`
are SHA-256 `7118b1d454b9091d8addf2d0faaf8a02b4607258072a195e4d1b041f95190924`.
