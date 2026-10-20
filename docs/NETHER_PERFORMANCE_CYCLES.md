# Nether performance cycles

The target is complete, lit, saved 1,024-chunk Nether regions below one second at
both median and p95 on the development M4 Max, approaching the Overworld's
300–700 ms range. Source ordering, random streams, features, structures, lighting
and complete chunk data must remain unchanged. Each cycle has its own worktree,
PR and confirmed squash auto-merge. Merging the cycle list is not sufficient to
declare the performance goal complete.

## Baseline and retained evidence

The investigation at `4ff9fbc` found a 12.88-second Nether median against a
385 ms Overworld median. Its component-removal and unordered variants are
diagnostics with different generation semantics, never candidate implementations.

The implementation reference is `58f6496be2b456a127b68682bab3b49901ec8076`,
which includes the independently merged decoration replay optimization (#168).
Its native tree is `653f5b12041a208ce841d17e09479f3ca68970f2`. A clean Git archive
was built before instrumentation edits. The retained release library SHA-256 is
`bafc696984f3f0b66bf06163f516d5379c12283b8a65cfad57048cc70abee31c`.

Local large artifacts are retained under:

```
/Users/cyberpwn/.codex/retina-nether-evidence/nether-performance-goal-2026-10-20/
```

`reference/` contains the original binary and provenance; `profiles/` contains
loaded-profile exports and their SHA-256 manifest. `reference-suite/` contains
the immutable original output corpus. Never overwrite that directory or replace
its expected output to accommodate a mismatch.

The loaded profiles come from the earlier `cycle6c-d900665` evidence:

| Suite profile | Original export |
| --- | --- |
| `nether.json` | `benchmark-nether-profile.json` |
| `overworld.json` | `benchmark-vanilla-profile.json` |
| `custom.json` | `measurements/custom-profile.json` |
| `direct-stem.json` | `measurements/direct-stem-profile.json` |

The two custom exports are byte-identical native profiles reached through
different Minecraft export routes; they do **not** represent two distinct native
configurations. They use fixed Warped Forest, storage `[0,256)` and terrain
`[16,112)`. The corpus exercises that configuration with two seeds.

To recover the reference binary, build the recorded revision with
`cargo build --release --locked --manifest-path native/Cargo.toml --lib`.
Record the resulting hash and compiler/environment if rebuilding; do not claim a
rebuilt binary is the retained one without comparing hashes. The profiles require
the original loaded exports or re-exporting the same Minecraft definitions.

## Corpus and repeatable commands

`qa/nether-performance-corpus.json` defines two normal Nether seeds, the custom
bounds under both exported routes/seeds, and an Overworld control. Each case uses
five warmups and twenty measured regions spanning positive/negative coordinates
and adjoining region boundaries.

```sh
python3 scripts/nether-performance-suite.py \
  --library /absolute/path/to/libretina_worldgen.dylib \
  --library-revision COMMIT_OR_RETAINED_BUILD_ID \
  --profiles /absolute/path/to/profiles \
  --out /absolute/path/to/new-cycle-suite \
  --compare /absolute/path/to/reference-suite \
  --compare /absolute/path/to/previous-cycle-suite \
  --repeat 2
```

Omit `--compare` when first creating the original corpus. Use `--case` to select
individual cases during diagnosis. Each new output directory must be fresh.
`--workers` controls native assembly workers; `--parallel` controls concurrent
region calls. These are separate axes. The default is sixteen workers, one
caller, shader-ready generation and zlib.

`--request-order reverse` and `--request-order shuffled` change scheduling while
preserving output names and coordinates, allowing exact comparisons against the
forward corpus. The shuffle seed is the case's world seed. Run these variants for
dependency/cache changes and concurrency validation; do not mix their timings
with a forward-order baseline when claiming an isolated optimization speedup.

The suite retains exact commands, process snapshots, hashes, actual program
readiness, full benchmark results, corpus comparisons and generated-NBT coverage.
Timestamped process observations every ten seconds also capture workloads that
start during a run. They retain processes using at least 10% CPU, named benchmark
executables, and the benchmark child. These observations are not GPU-utilization
measurements or proof that every possible competitor was captured.
Check process snapshots and runtime observations for competing workloads; these
are evidence to interpret timings, not speculative admission checks. Never stop
another agent's workload without authorization.

`corpus_coverage` counts actual generated biome membership and deduplicated
structure starts. Inspect the results to confirm all five Nether biomes and
required structure families occur. Add real cases if coverage is missing; a
profile declaring support is not evidence that a generated corpus exercises it.

Headline comparisons use the same readiness mode. Measure cold startup and
driver-warm operation separately from shader-ready steady-state generation.
Small drift is inconclusive until repeated. Complete-NBT comparisons include all
uncompressed payload bytes; only MCA container timestamps/compressed encodings
are outside that comparison. Changes in membership fail, too.

## Opt-in diagnostics

`--diagnostics` enables `RETINA_PERFORMANCE_DIAGNOSTICS=1` before native
initialization. Use separate runs for diagnosis; its clocks and uniqueness sets
deliberately add overhead. Normal runs allocate no diagnostic tracking sets.

The additive `retina_performance_snapshot` API returns UTF-8 JSON owned by the
caller, released with `retina_free_bytes`. It does not change the existing timing
snapshot ABI. The benchmark records measured-interval differences, excluding
warmup counters.

Counter interpretation:

- `dependency_evaluation_nanos`, `final_structure_planning_nanos`,
  `distribution_nanos`, and `teardown_nanos` separate sequential planner phases.
  Aggregates across concurrent requests still overlap in wall time.
- Memo and column lock durations include acquisition and lookup bookkeeping.
  Join durations include successful ready reads and waits on another initializer,
  excluding execution of the current call's own initializer. Recursive durations
  overlap and must never be summed as elapsed-time percentages.
- Unique structure candidates/builds and terrain keys are profile-lifetime sets.
  Interval deltas count keys first observed in that interval; warmup keys remain
  known. A repeated structure build means the same complete key was built before.
- `structure_overlapping_builds` counts builds begun while another build of the
  same complete key is still active. A lifetime guard tracks completion, including
  failure, so sequential rebuilding is distinguished from concurrent duplication.
- `structure_pending_evictions` counts cells uninitialized at eviction. Such a
  cell may be queued or actively building; it is not proof of an active build.
- Terrain tile/chunk counts describe submitted material tiles including halos.
  They are not counts of unique output chunks.
- `region_pool_wait_nanos` measures native assembly-pool admission. Existing GPU
  queue timings measure a different queue. Java/portal queue instrumentation
  belongs to the concurrency cycle.

## Delivery and completion audit

| Cycle | Scope | Required evidence |
| --- | --- | --- |
| 1 | Attribution and reproducible corpus | Actual coverage; disabled/enabled diagnostic parity; current reference and candidate benchmarks |
| 2 | Shared structure discovery and batched fossils | Fewer duplicate builds/probes/submissions; exact structure and chunk parity |
| 3 | Batched ordered dependency state | Reduced reconstruction/waiting; full dependency closure and request-order parity |
| 4 | Terrain and intermediate-state reuse | Reduced redundant tiles/transfers/allocation; bounded memory and isolation |
| 5 | Final planning, distribution and teardown | Separate phase costs; no hidden deferred work; measured resolution of remaining costs |
| 6 | Concurrent requests and portal responsiveness | 1/2/4/8 caller tests; separate worker tuning; real portal/DH and lifecycle checks |

Every cycle compares complete output against the original corpus and the previous
merged cycle, includes Overworld regression measurements, and benchmarks the
merged result. Preserve large evidence outside Git and commit concise reports.
Later cycles must record merged PR identities and measured results in this audit.

Final acceptance additionally requires repeated sub-second median/p95 across the
representative warmed Nether corpus, cross-boundary/concurrent correctness,
successful portal preparation with and without background work, stable sustained
memory use, and no material Overworld regression. Until those are directly
verified, the overall goal remains incomplete.

## Cycle 1 implementation result

The disabled-diagnostics candidate matches all 102,400 original measured chunks
across the five cases. A separate enabled-diagnostics region matches another
1,024 chunks. Native unit tests pass (110 passed, 37 ignored); benchmark helper
tests pass (10 passed, one optional LZ4 test skipped; this corpus uses zlib).

The diagnostic region records 98,555 anchor builds, 16,830,129 column builds and
10,206 structure builds from 1,365 unique candidates. Of those structure builds,
8,841 repeat an earlier build, while only six overlap another active build.
This distinguishes eviction-driven rebuilding from simultaneous duplication.
The dependency phase took 13.98 s in that profiled run, final structure planning
839 ms, distribution 78 ms and teardown 615 ms. These are diagnostic observations,
not a performance comparison.

Both the reference repeat and candidate corpus ran with observed competing
workloads. At the user's direction, implementation continues while quiet timing
confirmation is deferred. No instrumentation speedup or regression is established.
The concise report is `docs/benchmarks/nether-perf-cycle1-2026-10-20.json`;
raw measurements and process observations remain in the retained evidence root.
`cycle1/validated-corpus` combines completed output directories for NBT comparisons
only; it must not be represented as one timing run. The overall performance goal
still requires repeated uncontended timings and post-merge validation.
