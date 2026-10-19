# Compact ore uncertainty-mask trial

Two implemented mask encodings preserve exact ore placement and reduce readback,
but the measurements do not establish a reliable complete-generator speedup.
Production retains its existing seeded geometry and dual occupancy/uncertainty
masks. This cycle adds evidence and reproducible experiments, not runtime switches
or an enabled alternative.

[Raw results](benchmarks/ore-word-flags-trial-2026-10-19.json) include thirteen
six-region lit-MCA runs, isolated ore-stage comparisons, hashes, individual timing
samples, peak RSS and validation/build logs. Apply either the
[global-atomic patch](benchmarks/ore-word-flags-global-2026-10-19.patch) or the
[workgroup-local patch](benchmarks/ore-word-flags-local-2026-10-19.patch) to a separate
checkout of `187136bac1c6950f17fa4e121ab2e548234a22ab`. Both apply cleanly to that base.

## Exact encoding and application

Existing masks store a candidate bit and a floating-point uncertainty bit for
every voxel in each vein's box. The experiment stores candidate OR uncertainty
bits, followed by one uncertainty flag per 32-voxel word. A flagged word makes
Rust check all its candidate voxels against the original CPU spheres. Unflagged
words keep the direct-mask fast path. Possible hits with a zero occupancy bit are
retained through the OR, so conservative repair covers both false positives and
false negatives.

The eight-word header, exact GPU geometry/pruning, RNG consumption, attempt order,
replacement bands and chunk contribution order are unchanged. Exposure-sensitive
and scattered recipes keep their CPU traversal. Outside the validated seed domain,
the previous CPU-geometry/GPU-mask path still applies. CPU repair spheres remain
batch-owned and initialized lazily once per vein.

The first variant clears and atomically updates uncertainty flags in GPU storage.
A refinement replaces those global atomics with 32 workgroup-local flag words,
then writes each flag word once after a barrier. Boxes exceeding 1,024 voxel words
retain the dual-mask representation; this bounds local storage without dropping
attempts. The allocation bound uses conservative box volume, while the flag-plane
position uses the actual GPU-returned volume. Active flags are initialized for
every dispatch, including reuse after a larger batch.

An initial host implementation made a second pass to remap offsets; the final
versions compute the chosen layout during the existing ordered merge. Neither
variant adds a dispatch, mapping operation or CPU/GPU round trip. The eight-word
headers and conservative geometry boxes remain, so total readback reduction is
less than the approximately 48% reduction of the two raw bit planes alone.

## Ore planning plus application

Apple M4 Max / Metal, two workers, nice +10, seed 123456789, height 384 and retained
vanilla/Terralith profiles. Each fixture has two warmup pairs and seven timed pairs
with alternating format order. Timing covers preparation, submission, readback,
merge and application to carved terrain. Base generation/cloning and final output
comparison are outside the timed span. Every pair compares every resulting block;
the initial pair also validates exact geometry headers and the production selector.

Final workgroup-local variant, normal release build (optimization level 3/thin LTO):

| Profile / side in chunks | Dual masks median ms | Word flags median ms | Readback before → after, bytes |
| --- | ---: | ---: | ---: |
| Vanilla / 4 | 1.823 | 1.776 | 452,536 → 266,892 |
| Vanilla / 8 | 4.394 | 4.393 | 1,251,944 → 743,632 |
| Vanilla / 32 | 57.742 | 58.774 | 15,122,912 → 9,075,412 |
| Terralith / 4 | 1.325 | 1.240 | 212,848 → 139,216 |
| Terralith / 8 | 3.506 | 3.622 | 595,960 → 395,724 |
| Terralith / 32 | 46.291 | 45.712 | 7,561,568 → 5,107,652 |

The smaller scenes use (0,0) and (-33,-33); the 32×32 fixture uses (-33,-33).
Readback includes sixteen timestamp bytes. Both formats use the same refined
shader in these isolated comparisons. The whole-generation comparisons below
instead use the unchanged production library as reference.

Readback falls about 33–41%. Complete ore-stage time is roughly unchanged for
full-region fixtures: vanilla is about 2% slower and Terralith about 1% faster.
Smaller-scene differences vary in direction. No ore-stage speedup is claimed.
Global-atomic prototypes and the first host-remapping prototype are retained in
the artifact separately, not combined with the final measurements.

## Complete lit-MCA generation

Each batch generates six full regions, X=-1..1/Z=-1..0, with two concurrent callers
and a separate warmup at (8,8). Both libraries use matched final-crate optimization
level 2 without LTO to fit the bounded build deadline; dependency/repository release
settings are unchanged. All measured regions reached shader-ready mixed execution
(status 4). The reference is PR #120's production library; PR #121 changed only docs.

| Profile / comparison | Reference chunks/s | Local flags chunks/s | Change |
| --- | ---: | ---: | ---: |
| Vanilla, reference then candidate | 2,555 | 2,576 | +0.8% |
| Vanilla, candidate then reference | 2,432 | 2,551 | +4.9% |
| Terralith, candidate then reference | 1,549 | 1,615 | +4.2% |
| Terralith, reference then candidate | 1,614 | 1,547 | −4.1% |

Rates are completed chunks divided by batch wall time through MCA publication.
They do not measure Minecraft loading or rendering. Opposite Terralith outcomes,
variable reference rates and nearly unchanged isolated ore work make the throughput
benefit inconclusive. The artifact retains all earlier global-atomic runs too.
Do not sum these differences with prior optimization reports.

Vanilla total logical transfers decrease from **190.30 to 184.33 MiB/region**;
Terralith decreases from approximately **200.25 to 197.82 MiB/region**. These are API
payload counters, not physical bus traffic. The production F3 counters would
naturally account for the smaller payload, but production is unchanged because
the experiment is not enabled.

Process peak RSS does not establish a consistent memory reduction. Final vanilla
pairs were 2.073 → 2.044 GiB and 2.222 → 2.091 GiB; Terralith was 2.947 → 2.906 GiB
and 2.887 → 3.041 GiB. The isolated harness retains maximum buffer capacities after
running both formats on one device, so its retained-buffer counter cannot prove
an independent peak-memory saving for the candidate. Smaller logical output and
readback allocations are proven; lower overall process memory is not.

## Validation and reproduction

- 82 ordinary unit tests pass.
- Three actual-GPU fixtures each compare 34 chunks, covering CPU geometry, seeded
  dual masks and seeded word flags. They exercise replacement chains, air-host
  replacement, exposure/scattered recipes, sizes 1/2/16/32/64, negative coordinates,
  the float precision boundary, near ±30-million coordinates and the out-of-domain
  CPU-geometry fallback. Large/small jobs share retained buffers.
- Forced uncertainty turns every candidate into a CPU repair check, including
  deliberately wrong GPU occupancy. Exact final blocks still match.
- Isolated stage comparisons include exact GPU pruning/bounds, every final block,
  and the normal planner selector on real carved vanilla/Terralith terrain.
- Every one of the thirteen six-region corpora matches all 6,144 decompressed NBT
  records against its retained reference, including final blocks, features and
  saved light. No density, attempt count or generation quality was reduced.
- Both preserved patches pass `git apply --check`; production source, shaders and
  benchmark harness are restored. The shared ignored library output is restored
  to the retained production reference as well.

With either patch applied, `RETINA_ORE_MASKS=dense` selects the old format; the
candidate defaults to word flags. For isolated comparison, use the release test
`seeded_ore_planning_comparison` with `--ignored --nocapture --test-threads=1`,
`RETINA_ORE_PROFILE=<exported-profile>` and `RETINA_ORE_COMPARE_WORD_FLAGS=1`.
Add `RETINA_ORE_REGION_FIXTURE=1` for the local patch's full-region ore fixture.
The raw artifact distinguishes shader-override probes from subsequently rebuilt
embedded-shader tests.

Whole-generation comparisons use `scripts/native-region-benchmark.py`, matched
profile/library settings, `--readiness shader-ready --count 6 --warmups 1 --parallel 2`
and `--compare` against the baseline corpus. Use fresh output directories. Builds
used two Cargo jobs and a 60-second process-group deadline; executions used two
workers and a 30-second deadline. Expensive tasks ran sequentially. No game launch
or GitHub Actions was used.

This evaluates mask transport without introducing speculative production
complexity. Final-section/NBT processing and combined pipeline integration remain.
