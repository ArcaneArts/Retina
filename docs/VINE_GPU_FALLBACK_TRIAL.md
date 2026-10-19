# GPU vine fallback diagnosis and rejected guard-input trial

The dense Terralith fixture's 48 reported “overflow” anchors were **unresolved
material reads**, not exhausted queue or output capacity. The diagnostic now
distinguishes unknown materials, queue overflow, output overflow, and incomplete
candidate execution. Ordinary CPU fallback behavior and generated output are
unchanged. No GPU buffer limits were increased.

An implemented prototype supplied available terrain guard columns to GPU vine
programs. It removed those 48 fallbacks and 384,000 CPU candidate evaluations,
but did not improve matched complete-region throughput. **The expanded-input
optimization is rejected and is not enabled in production.**

## Diagnosis and implementation

Previously every rejected device result incremented `overflow_anchors`. The
shader also encoded unknown reads and queue failure as the same Boolean error.
Separate error bits now survive through gather/readback. `overflow_anchors`
counts true queue/output capacity failures; `unresolved_material_anchors`,
`column_queue_overflow_anchors`, `output_capacity_overflow_anchors`, and
`incomplete_candidate_anchors` expose the causes. Reasons can overlap within an
anchor and must not be added as disjoint counts. `fallbacks` remains the aggregate.

The prototype compiled the largest horizontal predicate offset per supported
program. It included that footprint from already-generated material/column guards,
rounded to the biome grid's four-block alignment and limited to available data.
It added no feature anchors or generated terrain. Missing guards still used the
existing CPU continuation. Overlay entries inside the included guard retained
precedence over immutable terrain, and the 128 MiB shared arena limit was unchanged.

An earlier predicate-pruning variant also tested ignoring unknown reads dominated
by known Boolean results. It did not remove the fixture's missing-input fallbacks
and was discarded before the expanded-input experiment. Its diagnostic sample is
retained in the raw evidence, not presented as a speedup.

## Measurements

[Raw evidence](benchmarks/gpu-vine-guard-trial-2026-10-19.json) includes individual
region/planner samples, profiles and binary hashes, transfers, RSS, output sizes,
comparison results, and excluded initialization failures.

Baseline native source: `6ec3197`, including the first native Nether terrain merge.
Normal release settings: opt-level 3, thin LTO, two codegen units. Apple M4 Max /
Metal, two Cargo jobs, two Rayon/region workers, nice +10. Builds completed within
60 seconds; every execution had a 30-second process-group deadline with up to
one second for termination. Heavyweight invocations ran sequentially.

The dense planner uses seed 123456789, chunk origin (0,0), 32x32 target chunks,
one warmup and five measured iterations, with interpreter terrain prepared first.
The separate detailed pass is outside the timing samples.

| Dense Terralith planner | Baseline | Guard-input prototype |
| --- | ---: | ---: |
| Median planning ms | 336.5 | 358.8 |
| CPU candidates evaluated | 2,432,271 | 2,048,271 |
| Missing-material GPU fallbacks | 48 | 0 |
| Sparse CPU anchors | 18 | 18 |
| Vine upload bytes | 28,476,356 | 29,277,688 |
| Vine readback bytes | 4,248,096 | 4,248,096 |
| Shared vine arena bytes | 69,501,772 | 70,296,144 |

Cold specialization exceeded the initial readiness limits on the new baseline.
A separate bounded warmup let vanilla complete one specialized pair (2,666 →
2,872 chunks/s), but Terralith still did not reach specialization readiness within
25 seconds. These attempts were not extended indefinitely or used as completed
Terralith throughput samples.

The complete matched matrix therefore uses **forced GPU interpreter mode**, with
preloading disabled, program status 0, and one driver warmup. It has no background
specialization. This measures complete lit-MCA publication, but is not a comparison
of the normal specialized production mode. Each run contains six regions,
X=-1..1/Z=-1..0, height 384, seed 123456789, and two callers. Order is A/B then B/A
for each profile. Startup and NBT comparison are outside measured batch wall time.

| Profile / pair | Baseline chunks/s | Prototype chunks/s | Change |
| --- | ---: | ---: | ---: |
| Vanilla A | 1,803.6 | 1,688.9 | -6.4% |
| Vanilla B | 1,830.0 | 1,683.2 | -8.0% |
| Terralith A | 829.2 | 813.2 | -1.9% |
| Terralith B | 821.2 | 788.3 | -4.0% |

Small desktop samples and unrelated machine load limit precision. Even so, the
planner and matched matrix do not justify shipping this implementation. Fewer CPU
evaluations did not establish a critical-path improvement. Do not infer a general
cost for guard reads from this trial, or add the isolated specialized vanilla pair
to the interpreter results. Java activation, sidecars, DH/Chunky and rendering were
not measured.

## Correctness and reproduction

The prototype passed 89 ordinary native tests and actual Metal tests for guard
coverage, missing-input fallback, forced output/queue overflow, disabled devices,
retained-prefix discard, and complete CPU/GPU Terralith planning. Guard fixtures
cover origins 0, -17 and 1,000,000, unavailable/insufficient guards, quart alignment,
and earlier overlay writes. Complete cross-build ordered planner files match.
Region runs match 49,152 decompressed NBT records across repeated corpora, not that
many unique chunks. Save lifecycle and promotion were not validated by this trial.

The [implemented prototype patch](benchmarks/gpu-vine-guard-trial.patch) applies to
`6ec3197`. It includes the corrected diagnostics and guard tests. Apply it with
`git apply --unidiff-zero` only to an isolated checkout of that revision. Use the existing planner example with
`RETINA_INTERPRETER_PRELOAD=0` and `RETINA_PLAN_OUTPUT` for full vector comparison.
For complete regions use `scripts/native-region-benchmark.py --count 6 --warmups 1
--parallel 2 --program-execution interpreter --interpreter-preload disabled
--readiness driver-warm`, pinned libraries/profiles, fresh output directories,
and `--compare`. Preserve the resource limits above.

An initial broad hardware-test filter also selected the registered-profile test
without its required environment variable; it failed before running that fixture.
The correctly configured test then passed. Similarly, `shader-ready` excludes
forced-interpreter status 0, so that setup-only attempt was excluded and the fixed
interpreter matrix used `driver-warm`. These are not correctness failures hidden
inside the successful comparison count.

The production PR retains only corrected diagnostics and focused regression tests.
It is integrated with the subsequent Nether feature merge; fresh tests on that
combined source are recorded in the PR. The broader optimization goal remains open.
