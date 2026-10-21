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

## Cycle 2 implementation

Structure discovery now uses job-owned candidate tiles for each structure set.
Each candidate belongs to one tile; the job retains its start cell through
ordered placement and final metadata planning. A weak index lets overlapping jobs
share an active cell after it leaves the bounded recent cache, without retaining
the job's starts indefinitely. Discovery remains inline inside dependency cells
to avoid yielding a Rayon worker into a task that depends on its initializer.

Single-entry fossil sets batch floor/material columns and actual-floor biome
queries. They consume the same weighted-selection, position, height, rotation
and template draws as before. Mixed weighted sets retain the original selector
and retries. Portals still prepare their writes at their original source root,
where their prior-state reads belong.

For the same diagnostic region, repeated structure builds fall from 8,841 to zero,
GPU completions from 2,343 to 118, and logical readback from 1,692,711,744 to
670,327,556 bytes. Ownership tiles evaluate some additional unique candidates;
the gain in work counts comes from avoiding repeated discovery and probes.
The remaining 16.8 million intermediate column builds motivate Cycle 3.
These counters establish reduced repeated work, not a latency speedup. Quiet
latency and memory confirmation remain deferred at the user's direction.

Cycle 1 merged as PR #171 (`34645a3`). Its rebuilt library is byte-identical to
the validated candidate. Actual post-merge generation across all five smoke cases
matches 5,120 chunks against both references (one warmup and one measured region
per case); full quiet timing confirmation remains deferred. Cycle 2 passes all
102,400 full-corpus chunk comparisons and 112 native tests (37 ignored).

## Cycle 3 implementation

Completed source roots publish immutable anchor outputs and ordered column-command
indexes. Interior columns keep versions only for roots with actual commands and
share the preceding changed version across identity roots. Direct version slots
avoid planner-wide column-map locking. Completed boundary tiles also use immutable
map lookups, while their unresolved slots retain the original demand-driven
closure. No fixed halo replaces cross-region dependencies.

Within-root support checks still apply the original `(anchor Z, anchor X, write
index)` prefix limit, including vines and cocoa. Raw commands are indexed before
acceptance, so rejected writes cannot incorrectly classify a changed column as an
identity transformation. Command references are released inside the measured
interval before final output consumption; teardown includes that release.

The diagnostic region preserves all 1,024 chunks and reduces memo requests from
42.6 million to 5.6 million, column builds from 16.8 million to 10.7 million, and
uses completed boundary slots for 7.97 million reads. The remaining boundary
column reconstruction is still substantial. These work counts support continued
optimization; they do not replace the deferred quiet timing confirmation.

Cycle 2 merged as PR #172 (`b4fc6d3`). Its rebuilt library is byte-identical to
the full-corpus candidate. Actual post-merge smoke generation matches all 5,120
chunks across the five cases. Quiet timing confirmation remains pending.

Cycle 3 also matches 20,480 chunks in reverse request order with two concurrent
callers and 16 native workers. That run has wide latency tails (provisional p95
28.8 s despite a 4.0 s median). Exact output passes; scheduling fairness remains
unresolved and must be compared with the preceding version in Cycle 6.

## Cycle 4 implementation

A job-owned terrain store now serves both ordered planning and fossil probes.
It reuses the initial region when the complete requested tile and its halo are
present, and generates missing coverage explicitly. The store's fixed request
context isolates seeds, profiles and height bounds. Profiles whose material masks
are separate from their fields retain the normal generation path for those tiles.
No dependency area is clipped to the initial region.

Base-column states retain immutable ranges of the shared material buffer instead
of copying each run list. Changed columns still own their accepted state. The
shared buffer remains owned until its last consumer finishes. Terrain cache wait
counters remain explicit after moving those operations out of generic memoization.

The diagnostic region matches all 1,024 chunks. Four initial tiles are reused;
generated terrain tiles fall from 66 to 34, generated tile-chunks from 31,292 to
15,804, and logical readback from 670,327,668 to 355,465,896 bytes. The same run
shares material buffers for 859,315 base columns. These are work/allocation changes;
quiet latency and memory confirmation remain deferred. Boundary prefix rebuilding
still accounts for 10.7 million column-state builds and needs further attention.

Cycle 3 merged as PR #173 (`7e0721a`). Its rebuilt library is byte-identical to
the full-corpus candidate; all five post-merge smoke cases match 5,120 chunks.
Cycle 4 matches all 102,400 full-corpus chunks and passes 115 native tests
(37 ignored). Quiet timing confirmation remains outstanding.

Cycle 4 merged as PR #174 (`1c12e2e`). Its rebuilt library is byte-identical to
the validated candidate; actual post-merge smoke generation matches 5,120 chunks
across all five cases. Quiet timing confirmation remains deferred.

## Cycle 5 implementation

Boundary-column versions are now released in coordinate order after all consumers
finish. Versions of one column retain the same state across identity roots;
spatial ordering keeps those reference releases together. Sorting and destruction
remain inside the complete-region interval. Generation, final publication and
error unwinding retain their existing behavior.

Separate cleanup counters identify command views, interior versions, unused
outputs, terrain, boundary columns and remaining planner state. The attributed
control spent 238 ms on boundary columns, 101 ms on interior versions and 79 ms
on unused outputs. Coordinate-ordered release measured 155 ms for the boundary
columns and 373 ms overall cleanup versus 454 ms in that control. These are
single-region diagnostic observations, not confirmed headline improvements.
Both outputs match all 1,024 original chunks.

Parallel cleanup and grouped parallel cleanup were rejected after measuring
892 ms and 786 ms teardown versus a fresh 469 ms preceding-library control.
An alias-cell experiment also preserved output but saved only about 35 ms of
cleanup while adding 1.3 million lookups and increasing dependency time. None of
those approaches is in the final implementation. Their evidence is retained.

Final structure planning is already about 13 microseconds after Cycle 2; there is
no remaining rediscovery change to make in this phase. Distribution is about
104 ms. The full five-case candidate suite matches 102,400 chunks against both
original and preceding references; 115 native tests pass (37 ignored).

Quiet timing confirmation remains deferred. This run's Overworld median is
281 ms versus the preceding 259 ms, and the second Nether seed is 3.71 s versus
3.52 s. Overworld does not use the changed ordered planner, but these differences
still require matched quiet repeats; no net speedup or no-regression claim is made.
See `docs/benchmarks/nether-perf-cycle5-2026-10-20.json` for retained measurements.


Cycle 5 merged as PR #175 (`e20ea13`). Its rebuilt library is byte-identical to
the validated candidate. Actual post-merge smoke generation matches 5,120 chunks
across all five cases; quiet timing confirmation remains outstanding.

## Foreground admission follow-up (evidence cycle 10)

Portal preparation and ordinary reads now receive foreground admission to the
existing shared native-region budget. They can promote an already queued DH or
bulk preview without generating it twice. Admission remains FIFO within each
priority, with one waiting background request admitted after at most four
foreground admissions. Running native jobs are not preempted.

Preparation uses cached platform threads, while each loader retains its bounded
admission queue and native work retains the configured global limit. The measured
Java 25 FFM probe rejected virtual preparation threads: eight one-second native
calls with two available carriers executed only two at a time. Cached platform
preparers executed all eight concurrently. This is an executor test, not a Nether
speedup measurement.

The actual Fabric portal fixture passes both without DH and with installed DH.
The latter overlaps three completed native DH region jobs with cold portal
preparation. Both modes generate the four destination regions, keep server ticks
running, preserve saved edits and portal frames, and reuse the destination after
reopening. Fabric and NeoForge also pass in-flight unload and reopen, including actual
preview-directory removal and no additional native failures. Queue delay is
reported separately from native execution. Runtime
observations and the complete validation record are retained in
`docs/benchmarks/nether-perf-cycle10-2026-10-20.json`.

The native generator is unchanged from PR #175. A fresh five-case run (five warmup
and twenty measured regions per case) matches all 102,400 complete chunks against
both the immutable original and preceding merged corpus. Quiet timing confirmation remains
deferred, and this follow-up does not establish sub-second Nether generation or
resolve native concurrent-request tails. The experimental native scheduling and
boundary-version variants remain unmerged while their regressions are evaluated.
