# Decoration planning phases and distribution

This cycle adds opt-in planning diagnostics and parallelizes final distribution
for large placement plans. It does not yet retain intermediate state inside an
unresolved anchor or move tree construction/leaf distances to the GPU.

## Diagnostic scope

`TerrainEngine::plan_decorations_detailed` returns the usual ordered placements
plus a `decoration::diagnostics::Planning` report. Normal generation retains the
existing F3 timing ABI and does not read a clock per tree or per anchor.

The report separates anchor selection, count expansion, GPU count/provider
sampling (including waits and insertion), CPU replay, and final distribution.
These are disjoint wall spans. Worker expansion and evaluation times overlap
replay wall time. Tree time is a subset of worker time and includes leaf-distance
time; these nested values must not be added to the wall spans. Legacy recipes
can build trees during expansion, whereas placement-program recipes build them
during evaluation. Candidate counts describe initial expansion, before lazy
floor-layer candidates are resumed. Query counts are deduplicated per round.

The example now defaults to a 4×4-chunk field, two workers, one warmup and three
measured plans. It forces the existing GPU interpreter to avoid starting
asynchronous specialization compilation. A separate instrumented plan must equal
an uninstrumented plan at every ordered placement, including role and upper-half
material. Timed plans also retain their full ordered signature. Its maximum
sample is labeled `max_ms`, not presented as a statistically stable p95.

Small retained registry profiles on M4 Max/Metal, seed 123456789, origin (0, 0),
height 384, two workers:

| Profile | Median plan | Output writes | Replay rounds | Retried anchors | Count/provider queries |
| --- | ---: | ---: | ---: | ---: | ---: |
| Vanilla | 2.54 ms | 5,739 | 1 | 0 | 2 / 0 |
| Terralith | 3.35 ms | 749 | 2 | 8 | 29 / 23 |

Vanilla worker expansion/evaluation measured 0.90/2.55 ms, with 0.34 ms in trees
including 0.17 ms in leaf distances. Terralith measured 2.02/2.48 ms, with
0.10/0.05 ms in trees/leaves. These are a separate instrumented sample, not a
decomposition of the median above. They identify repeated expansion/evaluation
as a worthwhile next target; these two small locations do not establish the
cost of dense forests or all registered features.

## Ordered parallel distribution

The original loop walks anchors in source order, clips writes, and appends them
to target chunks. Large plans now partition contiguous anchor ranges into at
most two tasks per Rayon worker, with at least 64 anchors per task. Each task
uses the original clipping/index rules. Indexed collection preserves range
order. Target chunks then concatenate their nonempty buckets in that same order,
in parallel. Scheduling cannot choose the winner of overlapping writes.

The direct serial path remains for one worker, targets at most two chunks wide,
fewer than 128 anchors, or fewer than 262,144 source writes. Sparse plans lost
time to task and bucket overhead in the experiment. A middle-sized fixture was
near parity with worse maximum latency, so it also remains serial. Temporary
bucket metadata is bounded by task count and the existing target grid; source
writes are moved, not cloned. Merging can temporarily retain input buckets and
the growing result, so this is not a claim of lower peak memory than serial.

Release CPU replay, one warmup pair and seven alternating pairs, two workers:

| Source writes | Accepted writes | Serial median | Parallel median | Production choice |
| ---: | ---: | ---: | ---: | --- |
| 36,992 | 26,624 | 0.188 ms | 0.334 ms | Serial |
| 221,952 | 190,464 | 0.781 ms | 0.758 ms | Serial |
| 887,808 | 762,880 | 2.548 ms | 1.826 ms | Parallel |

The dense fixture reduced distribution time by 28.4%. Its largest measured
sample was 2.674 ms serial versus 2.177 ms parallel. The whole replay process
peaked at 90.3 MiB RSS, including retained source/reference/results for both
paths; this does not attribute memory savings to either path. Samples are too
few for a stable p95. This is synthetic placement distribution, not complete
region throughput or a game-load benchmark.

## Validation and evidence

- All 65 normal native unit tests pass in the final release test binary.
- Distribution tests compare complete vectors against the serial reference at
  target widths 1, 2, 3, 8, 16 and 32. They include negative origins, halo writes,
  vertical clipping, empty anchors, overlapping positions, all roles and upper
  halves. The large case exercises the production parallel selection.
- Both real registry profiles produce identical detailed/normal plans.
- Retained library comparisons against `3418ade` match final block and column
  bytes for eight uncached chunks per profile (one warmup pair and three measured
  pairs, two callers, height 384). This exercises the common planning code;
  individual chunks use serial distribution. Batch medians were mixed:
  vanilla 66.34→69.46 ms and Terralith 100.03→97.93 ms. These checks establish
  output equality, not a whole-generator speedup.
- Formatting, the final example type check and `git diff --check` pass. No Java,
  save/publication, lighting or timing-packet behavior changes in this cycle.

Raw samples, phase snapshots and retained-library checks are in
[`benchmarks/decoration-planning-stages.json`](benchmarks/decoration-planning-stages.json).
The phase executable was built before the final sparse threshold adjustment;
its 4×4 fields and the retained-library one-chunk checks always use serial
distribution. The final distribution samples and unit tests use the final
release test build. This distinction prevents treating the phase snapshots as
evidence of the dense parallel speedup.

To reproduce the small planner diagnostic, build
`decoration_plan_benchmark` with `cargo build --manifest-path native/Cargo.toml
--release --locked --example decoration_plan_benchmark -j 2`, then pass
`<profile.json> 123456789 0 0 3 4 2`. Set `RETINA_INTERPRETER_PRELOAD=0` and
`RAYON_NUM_THREADS=2`. The CPU-only comparison is the ignored library test
`decoration::distribution::tests::distribution_replay_benchmark` with
`--ignored --nocapture --test-threads=1` in a release build. Run either under a
30-second process-group deadline at reduced priority, separately from builds.
The recorded builds used two jobs, two codegen units and 60-second deadlines;
child processes were subject to the same deadline. Minecraft was not launched.

## Remaining decoration work

The subsequent [anchor-resume cycle](DECORATION_REPLAY_RESUME.md) implements
bounded prefix retention and validates unchanged query batching. The remaining
items below describe the next opportunities identified by this first cycle.

Prefix retention now preserves lazy count expansion, live overlays,
provider-dependent branches and random streams. Investigate sharing worker
scratch and additional compatible query batching.
Tree/leaf GPU offload still needs representative dense-tree measurements and
exact-order/dependency validation before a production decision. The phase
diagnostics are evidence for those follow-up cycles, not completion of them.
