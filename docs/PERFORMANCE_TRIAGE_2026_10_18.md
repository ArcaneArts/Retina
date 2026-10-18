# Current pipeline performance triage

Decoration candidate expansion/evaluation is the strongest next optimization
target in these samples. Datapack-derived shader specialization already helps
substantially. Transfer volume is worth tracking, but reducing bytes alone does
not establish a throughput gain.

This is an investigation of commit `07f4698e773514756dd1d976b04861634c8b29fc`,
after the October performance changes. No generation algorithm changed in this
pass. The accompanying implementation adds F3 transfer averages.

## Workload and limits

[Measurements](benchmarks/native-triage-2026-10-18.json) retain individual region
times, stage counters, profile/library hashes, readiness, startup, transfer
volume, memory observations and correctness comparisons. They use the existing
`scripts/native-region-benchmark.py` harness, which times generation separately
from startup and full-NBT comparison, rather than timing the entire process.

- Apple M4 Max, Metal, release build; two shared region workers, two Rayon
  workers, reduced process priority (`nice -n 10`). These are desktop-friendly
  measurements, not maximum CPU throughput.
- Loaded vanilla and Terralith exported profiles, seed `123456789`, full height
  384, density composition/material layers enabled. Six measured regions at
  X=-1..1, Z=-1..0, with one separate warmup at (8,8).
- Default runs wait for shader readiness and report status 4: interpreted terrain
  combined with specialized stages. Interpreter comparisons explicitly disable
  profile specialization. Each invocation has a 30-second process-group deadline.
- Native library rebuild was up to date; rebuilding the planner example took
  21.71 seconds with two Cargo jobs under a 60-second deadline. No game launch or
  GitHub Actions run was needed.
- Scope is native lit-MCA generation and writing. Java column sidecars, loader
  throughput, DH/Chunky scheduling and rendering are outside these measurements.
  The production eight-region limit is unchanged; this pass tests one/two callers.
- Six-sample p95 values describe these samples, not stable production tails.
  Caller-count and unlit comparisons have one trial per configuration. Only the
  Terralith mixed/interpreter comparison was repeated twice per configuration.

## Whole-region results

Rates below are generated chunks divided by measured batch wall time. They are
not inverses of region latency.

| Profile / execution | Concurrent callers | Chunks/s | Median region ms | Sample p95 ms |
| --- | ---: | ---: | ---: | ---: |
| Vanilla, current mixed | 1 | 1,979 | 493 | 664 |
| Vanilla, current mixed | 2 | 2,274 | 866 | 962 |
| Terralith, current mixed | 1 | 1,026 | 944 | 1,473 |
| Terralith, current mixed, repeat | 1 | 1,030 | 942 | 1,479 |
| Terralith, current mixed | 2 | 1,241 | 1,438 | 2,631 |
| Terralith, interpreter only | 1 | 592 | 1,834 | 2,397 |
| Terralith, interpreter only, repeat | 1 | 601 | 1,818 | 2,317 |

Two callers improve batch throughput in these samples while increasing each
request's latency. This does not establish further gains from eight callers on
two workers. Comparing serial and concurrent output matched all 6,144 chunks for
each profile, including full decompressed NBT. Each Terralith interpreter/mixed
repeat also matched all 6,144 chunks against the current mixed reference.

Current mixed shader execution was roughly 71–74% faster in batch throughput
than interpreter-only execution in the two Terralith trials. This is an existing
implementation benefit, not a new speedup from the F3 change. See
[`gpu.rs`](../native/src/gpu.rs) (`Gpu::new`, profile compiler request) and
[specialization](GPU_PROGRAM_SPECIALIZATION.md). Generating one enormous shader
for every stage is not an established improvement over the current hybrid.

## Where time goes

Serial Terralith average counters per region were approximately:

| Counter | ms / region | Interpretation |
| --- | ---: | --- |
| Decoration planning | 551 | Wall time, includes its CPU/GPU work |
| Terrain GPU work/readback | 222 | Includes actual GPU execution, not pure transfer overhead |
| Structure planning | 106 | Wall time, also issues terrain probes |
| NBT encoding | 79 | Summed CPU worker time |
| Compression | 84 | Summed CPU worker time |
| Ore planning | 39 | Wall time, includes CPU preparation/GPU masks |
| Lighting host | 39 | Excludes consumer encoding; overlaps device work |

These are not additive percentages of the critical path. GPU work overlaps
planning, and worker counters sum across CPU threads.

The existing detailed planner benchmark was run on two 32x32 Terralith fields
with one warmup and three timed iterations. It additionally checked identical
ordered placements across iterations and between instrumented/uninstrumented
planning:

| Chunk origin | Median plan ms | Evaluated candidates | Ordered block writes | Replay wall ms | GPU sampling wall ms |
| --- | ---: | ---: | ---: | ---: | ---: |
| (-32,-32) | 101.8 | 678,515 | 174,286 | 82.2 | 2.1 |
| (0,0) | 814.9 | 8,666,606 | 484,709 | 786.6 | 3.3 |

Diagnostic spans come from a separate instrumented pass and need not sum to the
un-instrumented median. In the dense field, tree construction accounted for
14.5 ms of worker time, versus 976 ms evaluating candidates and 576 ms expanding
them. Tree time is nested within evaluation, not additional. Three checkpoints
hit the existing 64 MiB replay retention cap. The other 181 resumed anchors
reused their prefixes. Increasing that cap blindly would multiply memory pressure
across eight region jobs; these data do not show that it would fix the main cost.

## Transfer volume and the F3 metric

Default runs measured about **392.92 MiB/region** for vanilla and **386.32
MiB/region** for Terralith. Terralith split into 249.70 MiB upload and 136.62 MiB
readback. Counters cover instrumented logical host/device payloads across terrain,
features, ores and lighting; they are not physical PCIe or GPU-memory bandwidth
measurements and do not count GPU-to-GPU snapshot copies.

A diagnostic Terralith run with lighting disabled measured 51.91 MiB/region and
1,104 chunks/s. That is about 87% less transfer volume but only about 7% higher
throughput than current lit generation. It also skips lighting computation and
light-array NBT/compression, so this is not a transfer-only A/B or a proposal to
remove lighting. It demonstrates why byte counts need to be read alongside wall
throughput and stage times.

The source explains the large lighting payload: nine 10x10-core tiles each upload
a 12x12 halo of 16-bit final blocks, about 243 MiB at height 384. Their 900 output
chunks read back 91.4 MiB of packed sky/block light including padding sections.
See [`lighting.rs`](../native/src/lighting.rs), `Gpu::submit` and `region`.

F3 now displays separate colored upload, readback and total logical MiB per region.
It divides native profile-lifetime bytes by native generated chunks / 1,024, not
Java publication counts or the last-20 timing window. Partial generation is
weighted by chunks; reopening the world, promotions and cache hits do not dilute
the denominator. Setup, probes and in-flight work remain in the numerator, so
the header explicitly marks this as an approximate profile average. Initial
transfers without completed terrain show a waiting label. Sampling reuses CPU
counters at the existing telemetry cadence; there is no new GPU readback.

## Ranked opportunities

### 1. Compile and compact placement programs

**Opportunity — expected_gain: high; risk: medium; complexity: medium.**

The strongest measured CPU cost is candidate expansion/evaluation, not tree
geometry or noise sampling. `expand_from` in
[`placement.rs`](../native/src/decoration/placement.rs) clones action/order
vectors into candidates; [`layers.rs`](../native/src/decoration/placement/layers.rs)
sorts/orders them and retains checkpoint state. `evaluate_anchor` in
[`decoration.rs`](../native/src/decoration.rs) resolves the resulting program
against a live overlay.

Compile immutable recipe operations, selector paths, suffix noise requirements
and predicates once per loaded profile. Let candidates reference that compact
program instead of allocating repeated action vectors. Stream eligible fixed
placement sequences without materializing all branches when their original
ordering can be preserved. Static rejection must preserve RNG consumption and
must not skip predicates affected by previous writes.

Validate with both planner fixtures, allocation/peak-memory measurements, ordered
placement and RNG parity tests, then matched full-MCA comparisons. The 815 ms
dense plan is the baseline to improve; no gain percentage is promised yet.

### 2. Run complete independent feature anchors on GPU

**Opportunity — expected_gain: high potential; risk: high; complexity: high.**

Extend profile compilation to supported complete placement/provider programs,
with each independent anchor maintaining its sequential overlay/order locally.
Keep counts, filters and providers resident through evaluation and compact final
writes for Rust. This targets the measured CPU loop rather than adding GPU
round trips around individual operations. See `plan_decorations_measured` in
[`lib.rs`](../native/src/lib.rs) and `Replay` in
[`decoration.rs`](../native/src/decoration.rs).

The current dense fixture spends only 3.3 ms sampling the GPU, so merely fusing
those sampling calls has limited upside there. Whole-anchor offload needs exact
RNG/order, bounded storage and an equivalent fallback for oversized or unsupported
programs. Prior leaf-only offload was slower; it is not evidence for this design.
Validate end-to-end planning, transfer volume and final MCA bytes, including
negative coordinates, cross-chunk features and sequential substrate dependencies.

### 3. Compress lighting transport without reducing lighting fidelity

**Opportunity — expected_gain: medium; risk: medium; complexity: medium/high.**

Send section descriptors for uniform air/solid sections and dense payloads only
where needed; reconstruct exact material IDs on GPU. Return constant-light
section descriptors instead of full zero/15 arrays, expanding only where the NBT
writer needs bytes. Another option is sharing resident final blocks across tile
halos, provided memory remains bounded with eight concurrent regions.

Measure packing/unpacking cost, logical bytes, memory and whole-region time.
Compare complete light arrays against the current path and Minecraft fixtures,
including transparent faces, emitters, height padding and the unlit border ring.
The unlit experiment limits expectations: removing most bytes alone is unlikely
to produce a multi-fold speedup on this measured M4 workload.

### 4. Remove the material count/readback/emission dependency

**Opportunity — expected_gain: medium; risk: high; complexity: high.**

[`gpu.rs`](../native/src/gpu.rs) currently snapshots job inputs, reads exact run
counts, allocates pooled output, submits `emit_materials`, then reads runs back.
Continuations already overlap other jobs. A bounded GPU output arena with prefix
offsets/indirect emission could complete both passes without the intermediate CPU
allocation decision. It needs an explicit overflow continuation, exact packed-run
ordering and per-job ownership so interleaved regions cannot overwrite data.

Measure host submission counts, device copies, retained memory, map waits and full
region throughput. Do not count the entire 222 ms work/readback span as removable
overhead; much of it is GPU execution. Test normal, overflow and failure paths
against the existing continuation parity checks.

### Lower-priority watchlist

- Tighter conservative ore output bounds may reduce masks/readback, but current
  serial ore planning is about 39 ms/region. **expected_gain: low/medium; risk:
  medium; complexity: medium.** Validate total planning plus application with the
  existing exact ore tests; a smaller buffer is not sufficient evidence.
- NBT/compression still consume worker time, but level-1 libdeflate, uniform
  palette shortcuts and reused buffers already exist in
  [`region.rs`](../native/src/region.rs). **expected_gain: medium; risk: medium;
  complexity: medium.** Any reuse of final-section summaries or alternate
  encoding must beat the complete lit-MCA baseline and preserve parsed NBT and
  heightmaps. Do not lower fidelity or silently change save compatibility.

Start with opportunity 1, then benchmark whole-anchor GPU execution and lighting
transport independently. Keep whichever wins complete generation with exact
output; don't add historical stage gains together.

## Display validation

`generationMetricsTest` passed locally in nine seconds with two JVM processors
and no native runtime/game execution. Coverage includes large counters, the
upload/readback sum, partial regions, startup, promotions/session independence,
colored F3 rows and packet round trips. Native algorithms and ABI are unchanged;
the telemetry packet carries the additional chunk denominator, so server/client
should use the same build as with existing telemetry changes.
