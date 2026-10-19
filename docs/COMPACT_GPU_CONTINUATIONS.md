# Compact GPU placement continuations

Pending supported vine groups now retain a seed/origin descriptor instead of
their expanded CPU queue. The dense Terralith planner fixture retains **29.6 MiB
instead of 64.0 MiB**, evaluates **2.43 million instead of 4.80 million** CPU
candidates, and measured **331 ms instead of 432 ms** in the final planner pair.
Complete lit-region throughput is mixed; this is **not an established overall
generation speedup**. The change is retained for its bounded checkpoint-memory
and planner-work reductions, an ordered handoff fix, and actionable diagnostics.

The full [post-investigation goal](POST_INVESTIGATION_OPTIMIZATIONS.md) remains open.

## Implementation and correctness

Only the already-proven, independent GPU vine programs can use the descriptor:
two constant counts, supported positions/predicates, and an unshared placement
salt. The queue must still be at the first child of the whole group. Successful
GPU output consumes the descriptor. Sparse batches, unavailable inputs, device
failure, and overflow reconstruct the exact CPU stream from the original seed.
Partially consumed groups cannot restart through this path.

Compaction releases the removed queue capacity before charging the saved state
against the unchanged 64 MiB per-replay limit. The GPU arena remains shared and
bounded at 128 MiB. This does not increase either limit or truncate attempts.

Lazy layer/count expansion could exhaust a preceding group internally and consume
the first candidate of a GPU group before returning to the outer handoff check.
The queue now yields at that internal boundary. The caller checks the initial
head, so the ordinary first iteration does not repeat that eligibility lookup.

Detailed and normal anchor evaluation have separate compiled paths. The new
per-candidate timers are removed from the normal path. No ABI, profile format,
world settings, F3 metric definition, or generation distribution changes.

## Diagnostics

`decoration_plan_benchmark` includes these in its separate detailed pass:

- Queue processing, placement checks, feature construction, and overlay commit
  worker spans. They are nested within evaluation, not additional wall time.
- Resolved/speculative candidate counts at evaluation entry, supported/unsupported
  CPU candidates, and supported candidates evaluated with unresolved inputs.
- Compacted group count, cumulative queue bytes removed, and GPU groups rejected
  by the retained-prefix budget.
- GPU sparse, unresolved-substrate, overflow, and device-failure anchor counts;
  successful prefixes later discarded by the retention limit are separate.
- Substrate/anchor packing, device-lock waiting, encoding/upload, wait/readback,
  decode, and device timestamps. Wait/readback includes execution and queue wait;
  device timestamps overlap it.

Resolved at entry does not guarantee that no later provider will invalidate the
prefix. Resumed-candidate counts describe prefixes reused across resumes, not
unique attempts saved. Cumulative removed bytes are not peak memory savings.
Program setup failures before per-anchor execution still use existing setup
logging; these counters do not attribute every possible initialization failure.

The dense final fixture reports 845 compacted groups and 75.5 MiB cumulatively
removed queue storage. Its remaining 672,000 supported CPU evaluations comprise
144,000 unresolved-input evaluations, 144,000 attempts in 18 sparse anchors, and
384,000 attempts in 48 overflow anchors. There are 1,760,271 unsupported CPU
evaluations. These identify useful next targets without blaming tree construction
alone: the instrumented worker pass spends about 205 ms in queue processing,
150 ms in placement checks, 106 ms in all feature construction, and 21 ms in
overlay commit. Tree time is a nested 12 ms, not an additional cost.

Follow-up [error classification](VINE_GPU_FALLBACK_TRIAL.md) established that the
48 anchors labeled overflow above failed on unavailable material reads. They did
not exhaust queue/output capacity. The corrected diagnostics separate these causes;
the historical raw measurements retain the original counter interpretation.

## Measurements

[Raw samples](benchmarks/compact-gpu-continuations-2026-10-19.json) retain final
and earlier-variant results, library/profile hashes, every region duration,
stage counters, transfers, output sizes, RSS, and comparison results.

Final baseline is `4227744`; candidate native source is `28467ec`. Both include
the automatic generation/Superflat merge. Normal release settings are opt-level 3,
thin LTO, and two codegen units. Apple M4 Max / Metal, two Cargo jobs, two Rayon
workers, two region workers, and nice +10. Builds completed in 41–45 seconds,
within 60-second deadlines. Each execution had a 30-second process-group deadline
with TERM followed by KILL after one second if necessary. No timeout occurred in
the final comparisons. No game or GitHub Actions workload was started.

Native region runs use the exported vanilla/Terralith profiles, seed 123456789,
height 384, twelve regions X=-1..1/Z=-1..2 and a separate warmup at (8,8), two
callers, and shader-ready status 4. Each profile uses baseline/candidate followed
by candidate/baseline. Rates count completed chunks divided by batch wall time
through MCA publication. Startup and NBT comparison are outside that interval.
Java loading, column sidecars, DH/Chunky scheduling and rendering are outside scope.

| Profile / pair | Baseline chunks/s | Candidate chunks/s | Change |
| --- | ---: | ---: | ---: |
| Vanilla A | 2,995 | 3,100 | +3.5% |
| Vanilla B | 3,078 | 3,060 | -0.6% |
| Terralith A | 2,077 | 1,952 | -6.0% |
| Terralith B | 1,985 | 2,038 | +2.6% |

There is no consistent complete-region win in these small desktop samples.
Terralith's overlapping planning counter nevertheless falls from 409/393 ms per
region to 376/379 ms. Those counters cannot establish the critical-path saving.
Earlier versions also had mixed whole-region results and are retained in the raw
report, not substituted for the final version. Do not add historical gains.

Planner runs prepare interpreter terrain before timing, use one warmup and five
timed iterations, then perform a separate detailed pass and full vector comparison.
Vanilla chunk origin is (-32,-32); dense Terralith is (0,0); each field is 32x32.

| Fixture | Baseline median ms | Candidate median ms | CPU candidates, before → after |
| --- | ---: | ---: | ---: |
| Vanilla | 82.8 | 80.4 | 606,820 → 606,820 |
| Dense Terralith | 432.0 | 330.6 | 4,798,256 → 2,432,271 |

The 23.5% dense planner improvement is one final matched pair, not a general speed
guarantee. Earlier candidate versions also reduced dense candidate counts and
retention, with smaller planner timing gains. Vanilla has no eligible vine program.

The eight-caller validation completes twelve Terralith regions at 1,951 chunks/s
with two CPU workers. Average request latency is 3,410 ms and sample p95 6,157 ms;
this is not a matched eight-caller speedup comparison. Native device peak in-flight
remains two. Eight callers do not imply eight active CPU workers or GPU dispatches.
Process peak RSS is 3.25 GiB, including setup and comparison allocations. Across
the two-caller results RSS does not consistently fall; only checkpoint retention
is shown to shrink. Twelve-sample tails are observations, not production estimates.

## Validation and reproduction

- 88 ordinary native tests pass; 35 hardware/benchmark tests remain ignored in
  that invocation. The separate actual-Metal handoff test passes.
- A 5,120-candidate queue fixture compares complete group/recipe/order, RNG seed,
  origin, draws, evaluation range, and final position after reconstruction.
- The Metal fixture compares complete ordered output at chunk origins 0, -17,
  and 1,000,000, including provider replay, zero budget, successful-prefix discard,
  disabled GPU, output overflow and queue overflow.
- Both complete exported planner vectors match the baseline, including all
  chunk boundaries and ordered placement fields. Detailed/normal plans also match.
- Final two-caller comparisons cover 73,728 complete decompressed NBT records
  across repeated runs. The eight-caller run matches another 12,288 records.
  These are repeated comparisons of the same corpus, not that many unique chunks.

Use `scripts/native-region-benchmark.py --count 12 --warmups 1 --parallel 2
--readiness shader-ready --specialization-timeout 15` with pinned libraries,
matching profiles, fresh output directories and `--compare` for the candidate.
The harness now also accepts four/eight callers, independently of the two-worker
environment limit. Use `decoration_plan_benchmark PROFILE 123456789 X Z 5 32 2`
with `RETINA_INTERPRETER_PRELOAD=0`; `RETINA_PLAN_OUTPUT` exports full vectors.
Keep the documented resource limits and deadlines for all reproductions.

One archive baseline build reused stale Cargo outputs because archived source
timestamps were older. It was excluded before measurements; only this package's
outputs in the isolated target were cleaned, then the baseline was rebuilt and
its distinct hash recorded. An initially mistyped exact Rust test filter ran zero
tests; it was excluded and corrected. Earlier implementation assertions exposed
an exact-half capacity comparison and the internal group-boundary bug; both were
fixed before final validation.

Ready-batch scheduling, earlier suspension, additional GPU program coverage,
prepared material layouts, optional LZ4, NBT/GPU section experiments, and combined
save/promotion/failure lifecycle validation remain separate goal work.
