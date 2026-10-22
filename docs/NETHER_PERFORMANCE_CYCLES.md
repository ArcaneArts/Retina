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

PR #176 (`60a7e39`) merged this foreground-admission follow-up. Its actual merged
build passes the scheduler checks and native tests. The rebuilt native library is
byte-identical to the full-corpus candidate; all 5,120 post-merge smoke chunks
match the original, preceding cycle and candidate. Quiet timing confirmation
remains pending.


## Dependency storage follow-up (evidence cycle 8)

Point-column reads now stream potential writer coordinates in the same Z/X order
without allocating a temporary vector. Completed anchor outputs are borrowed from
the immutable view; pending outputs retain an owning reference. Original bounds
and empty-range failure behavior remain covered by the writer tests.

Rare column errors are stored behind one pointer. The actual arm64 layout probe
reduces a column cache slot from 32 to 24 bytes, without adding allocations to
successful builds. This is a 25% slot-size reduction, not a 25% process-memory
claim. Failed results still retain and return their original error text.

The implementation passes 116 native tests and matches all 102,400 full-corpus
chunks against the original, Cycle 5 and Cycle 10 references. A reverse-order run
with two simultaneous callers also matches all 20,480 chunks. Its latency tails
remain substantial and are not resolved by this storage change.

An initial Overworld peak-memory increase was investigated with four adjacent
control/candidate runs. Control peaks span 1.73–2.43 GB and candidate peaks span
1.76–1.84 GB; the increase did not consistently reproduce. An enabled diagnostic
run confirms zero calls into the changed ordered planner for this profile.
Quiet timing confirmation remains deferred. See
`docs/benchmarks/nether-perf-cycle8-2026-10-20.json` for commands, retained evidence
and provisional results.

PR #177 (`fbbf545`) merged this storage follow-up. Its actual merged build passes
116 native tests and is byte-identical to the validated candidate. Post-merge
smoke generation matches all 5,120 chunks against all four references. Quiet
timing confirmation remains pending.


## Partitioned dependency lookup follow-up (evidence cycle 13)

The job-owned anchor, column-tile, biome-selection and structure lookup maps now
partition their lookup locks by key. Each dependency retains exactly one shared
`OnceLock` initializer. Recursive generation starts after the lookup lock is
released, and completed roots still publish their immutable maps at the same
joined barrier. Final command order, error propagation and measured cleanup stay
unchanged.

The diagnostic region retains the same 98,555 anchor builds, 10,685,959 column
builds and 4,373,070 memo requests. Aggregate measured lookup-lock durations fall
from 1,558 to 268 ms for columns and from 1,103 to 216 ms for memo lookups in the
retained diagnostic runs. These overlapping worker durations are not wall-time
shares, and these observations do not establish an uncontended timing claim.

The cache concurrency checks verify one initializer per key, cached failure text,
exclusive ownership at the completion barrier, and cleanup after lock poisoning.
All 102,400 full-corpus chunks and 20,480 reverse-order two-caller chunks match
the original and preceding references. Two-caller p95 remains about nine seconds;
this follow-up does not resolve native request tails. Full-region output and
provisional measurements are recorded in
`docs/benchmarks/nether-perf-cycle13-2026-10-20.json`. Quiet timing confirmation
remains deferred; this change does not establish sub-second Nether generation.


PR #178 (`d66caee`) merged the partitioned lookup follow-up. Its actual merged
build passes 118 native tests and is byte-identical to the validated candidate.
All 5,120 post-merge smoke chunks match every reference; quiet timing acceptance
remains pending.

## Direct terrain lookup follow-up (evidence cycle 14)

Each terrain job now has 64 directly indexed tile cells around the request.
Coordinates outside that index continue through the original growable map. Both
paths use the same initializer and retain one result per normalized tile key;
the index does not define or truncate generation coverage. Seed, dimension and
profile data remain job-owned.

In the initial diagnostic region, all 1,242,137 terrain lookups use direct slots
and no lookup acquires the fallback map lock. Generated terrain remains 34 tiles,
and anchor/column work is unchanged. This is an attribution result, not a quiet
wall-time speedup claim. The coordinate test covers negative boundaries, key
uniqueness, and fallback selection outside the direct index, including integer
extremes.

All 102,400 full-corpus chunks and 20,480 reverse-order two-caller chunks match
the original and preceding merged references. The initial full run had known
external VM CPU load. Adjacent control/candidate runs have similar medians and
higher p95, especially for the first seed, so timing acceptance remains deferred.
An enabled Overworld diagnostic confirms zero terrain-job and ordered-planner
calls for this corpus profile; its output also matches all three references. Combined full-output
validation and provisional observations are recorded in
`docs/benchmarks/nether-perf-cycle14-2026-10-20.json`. Quiet timing confirmation
remains deferred, and the sub-second goal remains open.

PR #179 (`ca2fde8`) merged the direct terrain lookup follow-up. The actual merged
build passes 119 native tests and is byte-identical to the validated candidate.
All 5,120 post-merge smoke chunks match every reference. Quiet timing acceptance
and the sub-second target remain pending.

## Deterministic ore replay follow-up (evidence cycle 19)

Eligible ore roots now emit conditional replacement commands instead of reading
prior materials for every candidate voxel. Ordered dependency and final replay
perform those material checks in source order. Eligibility requires a uniform,
deterministic replacement across the entire root, material-independent placement,
zero air-discard probability, and preservation of all six heightmap masks. Nested
selectors, probabilistic rules and other ineligible features retain eager reads.
Geometry and RNG consumption remain unchanged; overlapping uniform replacements
are idempotent. Reference feature sampling retains the eager path.

The initial diagnostic region reduces reconstructed columns from 10,685,959 to
7,253,869 and evaluated anchors from 98,555 to 88,417. Eight eligible roots emit
2,089,170 conditional candidates. These are work counts, not elapsed-time speedup
claims. The implementation passes 122 native tests, including eager/deferred RNG
continuation and overlapping-anchor parity. All 102,400 full-corpus chunks and
20,480 reverse-order two-caller chunks match the immutable original, Cycle 5 and
preceding merged Cycle 14 outputs.

Provisional main-seed medians are 2.02 and 2.33 seconds. Concurrent latency remains
unresolved: the reverse-order run includes a 41.9-second region while other
requests continue completing. This change does not establish a concurrency or
no-regression acceptance result. Quiet timing confirmation remains deferred by
the user; the sub-second median/p95 goal remains open. The full measurements,
coverage, library provenance, limitations and reproduction commands are in
`docs/benchmarks/nether-perf-cycle19-2026-10-20.json`.

PR #180 (`7a2670a`) merged the deterministic ore replay follow-up. Its actual
merged build passes 122 native tests and is byte-identical to the validated
candidate. All 5,120 post-merge smoke chunks match every reference. Quiet timing
acceptance and the sub-second target remain pending.

## Bounded root dispatch follow-up (evidence cycle 22)

An assembly worker waiting for one region could steal another region's entire
remaining generation loop. The request's caller now dispatches one joined source
root at a time. Root evaluation and completion remain in the existing bounded
pool, as do final planning, distribution, assembly and cleanup. Failed root
results also release planner state in that pool. The worker count and global
request budget are unchanged.

A deterministic one-worker test demonstrates the mechanism: the old task makes
a cooperative wait inherit all three simulated roots; separate phase dispatch
lets it resume after one. All 123 native tests pass. The tested library is
byte-identical to the matrix candidate. The five-case corpus matches 102,400
complete chunks, and the additional 2/4/8-caller runs match another 61,440, against
the immutable original and preceding merged references.

The provisional two-caller maximum is 5.81 seconds, compared with the previous
41.92-second outlier. Eight-caller p95 is still 21.47 seconds, with a 25.01-second
maximum and 9.66 GB peak RSS. These are observations, not quiet timing acceptance.
Second-seed and custom-profile serial runs were slower alongside higher external
VM CPU activity; the direct-stem median remained similar. The full Overworld run
also had a higher memory peak, while its targeted probe confirms zero calls into
the changed planner path. These limits and the raw measurements are retained in
`docs/benchmarks/nether-perf-cycle22-2026-10-20.json`. Quiet no-regression, sustained
memory and sub-second acceptance remain outstanding.

PR #181 (`6e125da`) merged bounded root dispatch. Its actual merged build passes
123 native tests and is byte-identical to the validated candidate. All 5,120
post-merge smoke chunks match every reference. Quiet timing, memory and the
sub-second target remain pending.

## Borrowed scalar column reads (evidence cycle 24)

Cached material and height queries now borrow the immutable column while holding
its existing per-view mutex, avoiding a temporary owning reference for each
scalar read. Only the scalar access runs under the mutex; recursive planning and
terrain work remain outside it. Newly loaded columns move into the cache without
an extra clone. Optional diagnostics accumulate locally and publish once when
the view is released, avoiding a shared atomic update on every read.

The diagnostic region records 48,628,027 cache hits and 2,588,203 loads. Both counts
match the earlier per-read instrumentation exactly. Anchor, column and terrain
work counts are unchanged. All 123 native tests pass, and all 102,400 full-corpus
plus 20,480 reverse-order two-caller chunks match the original and preceding
merged references.

Provisional standard-Nether medians are 1.97 and 2.22 seconds; the two-caller p95
is 5.49 seconds. Overworld and direct-stem observations are mixed relative to the
preceding run, so quiet timing and no-regression acceptance remain pending.
These measurements do not establish a speedup or the sub-second objective.
Details and reproduction commands are retained in
`docs/benchmarks/nether-perf-cycle24-2026-10-20.json`.

PR #182 (`7dde4f1`) merged borrowed scalar column reads. Its actual merged
build passes 123 native tests and is byte-identical to the validated candidate.
All 5,120 post-merge smoke chunks match every reference. Quiet timing and the
sub-second target remain pending.

## Height-only dependency views (evidence cycle 25)

An untouched feature overlay now returns the field's already-computed height
without reading its top material again. Every overlay write, including air,
records column membership; touched columns retain the existing downward scan.
Height queries also bypass consecutive ore roots whose eligibility proof
preserves all six heightmap bits. Material queries keep their original prefix,
and earlier material states never enter the current view's cache.

This combination removes dependency work that the redundant material check
previously restored. The diagnostic region reconstructs 6,015,978 columns instead
of 7,253,869 (17.1% fewer) and evaluates 79,959 anchors instead of 88,417 (9.6%
fewer). All 124 native tests pass, including untouched, cleared, replaced and
rolled-back height cases. The full 102,400-chunk corpus and 20,480 reverse-order
two-caller chunks match every original and preceding merged reference.

Provisional standard-Nether medians are 1.73 and 1.85 seconds, with p95 values of
2.17 and 2.54 seconds. These are not quiet speedup or no-regression acceptance
results. The sub-second objective remains open. Complete measurements, coverage,
proof constraints and reproduction commands are in
`docs/benchmarks/nether-perf-cycle25-2026-10-20.json`.

PR #183 (`96e1603`) merged height-only dependency views. Its actual merged
build passes 124 native tests and is byte-identical to the validated candidate.
All 5,120 post-merge smoke chunks match every reference. Quiet timing and the
sub-second target remain pending.

## Grouped command indices (evidence cycle 29)

Completed roots now store one anchor reference per written column and reuse the
anchor's existing command-index list. This preserves anchor order, global command
order and support-read prefix limits. The diagnostic region replaces 7,964,621
duplicated command pairs with 861,753 group references: about 115 MiB less index
element payload on this machine, before vector capacity and map overhead.

All 125 native tests pass. The full 102,400-chunk corpus, 20,480 reverse-order
two-caller chunks and 4,096 sampled-generation chunks match the original and
preceding merged output. Standard-Nether peak RSS is about 251–270 MB lower in
the provisional full runs. This change reduces retained index storage; it does
not establish a latency improvement.

The two standard seeds have provisional median/p95 values of 1.67/2.16 and
1.85/2.52 seconds. Concurrent, custom-tail and Overworld observations are mixed or
slower. External CPU activity is recorded, but it does not prove the cause of
those differences. Quiet timing, no-regression and sub-second acceptance remain
pending. Exact measurements, provenance, commands and limitations are retained in
`docs/benchmarks/nether-perf-cycle29-2026-10-21.json`.

PR #184 (`1abc517`) merged grouped command indices. Its actual merged build
passes 125 native tests and is byte-identical to the validated candidate. All
5,120 post-merge smoke chunks match four references. Quiet timing and the
sub-second target remain pending.

## Deterministic boundary footprints (evidence cycle 33)

Before replaying an uncomputed boundary anchor, the ordered planner now checks
whether its deterministic placement positions and exported feature reach can
write the requested column. Per-job bitmaps reproduce positional random draws
and conservatively ignore live rejection and vertical-only movement. Requested
target anchors still run. Shared placement groups, live layer/noise counts,
wide masks and expensive programs keep the original evaluation path; the
optimization's limits never truncate generation.

All 128 native tests pass. The complete 102,400-chunk corpus and 20,480
reverse-order, two-caller chunks match the original, cycle 5 and merged cycle 29
references. In the diagnostic region, evaluated anchors fall from 79,959 to
65,357 and intermediate columns from 6,015,978 to 3,854,516. Generated terrain
chunks fall from 13,303 to 8,301. These are workload observations for that region,
not elapsed-time percentages. Footprint state is retired inside generation.

The standard seeds have provisional median/p95 values of 1.38/1.59 and 1.49/2.07
seconds, compared with cycle 29's 1.67/2.16 and 1.85/2.52 seconds. Their peak RSS
falls to 2.19/2.23 GB from 2.42/2.51 GB. The first direct-stem and Overworld
observations were slower than their earlier references, so both binaries were
run sequentially again. That pair measured 1.07/1.16 versus 0.93/1.01 seconds
for direct-stem and 373/429 versus 342/397 ms for Overworld. A diagnostic
Overworld run confirms zero ordered-anchor, column and footprint builds.

External VM and other CPU activity is recorded. These observations investigate
the apparent regression but do not establish causation or replace quiet timing
acceptance. Full provenance, commands, comparisons and all timing observations
are in `docs/benchmarks/nether-perf-cycle33-2026-10-21.json`. The sub-second target,
quiet no-regression checks, sustained concurrency and final portal integration
remain open.

Cycles 30–32 remain unmerged experiments: pending-column caching, shared feature
output caches and saved anchor replay checkpoints did not demonstrate an overall
benefit in their preliminary runs. Their owned worktrees and external evidence
remain preserved; none is included in cycle 33.


PR #185 (`38c4ff2`) merged boundary footprints. Its actual merged build passes
128 native tests and is byte-identical to the validated candidate. All 102,400
chunks in a fresh five-case run (five warmups and twenty measured regions per
case) match four references. Standard-seed provisional median/p95 observations
are 1.48/1.73 and 1.56/2.02 seconds. Quiet timing and final acceptance remain
pending; every post-merge case observation is retained in the cycle 33 evidence.

## Ore footprint refinement (evidence cycle 34)

Boundary ore masks now use the existing vein sphere calculations instead of a
maximum-reach square around every placement. The shared calculation keeps the
terrain rejection between the original RNG draw stages. Scattered ore uses its
sampled attempt count to bound all possible offsets, including when replacement
rules consume extra random draws. Live rejection only removes possible writes.
Shared placement groups and live-height programs retain the earlier conservative
mask; geometry work counts against the optional index budget.

All 132 native tests pass, including actual emitted-write coverage under
probabilistic replacement, air discard, nested counts, negative coordinates and
custom height bounds. The complete 102,400-chunk corpus, 20,480 reverse-order
two-caller chunks and 4,096 sampled chunks match the original and preceding
merged output. In the diagnostic region, anchors fall from 65,357 to 63,012 and
intermediate columns from 3,854,516 to 3,536,253; generated terrain chunks remain
8,301. These work counts are not elapsed-time shares.

Provisional standard-seed median/p95 observations are 1.28/1.67 and 1.45/1.75
seconds, versus merged cycle 33's 1.48/1.73 and 1.56/2.02. Both custom profiles
measure about 0.79/0.85 seconds; reverse two-caller latency is 1.91/2.69 seconds.
Overworld latency falls to 311/352 ms, but peak RSS rises from 1.659 to 1.798 GB.
An actual diagnostic run records zero ordered-anchor/column/footprint builds,
and the retained Overworld profile contains no ore primitives using the changed
geometry. The RSS difference remains recorded without a causal claim.

A fresh stack/root sample still identifies dependency evaluation and waiting as
the next area to investigate, with basalt pillars/blobs and several ore roots
among the longest intervals. Sampling includes observer overhead; it is not a
quiet benchmark. Full measurements, provenance, commands and limitations are in
`docs/benchmarks/nether-perf-cycle34-2026-10-21.json`. Quiet no-regression,
sub-second performance, sustained concurrency and final integration acceptance
remain pending.


PR #186 (`192eeec`) merged ore footprints. Its actual merged build passes 132
native tests and is byte-identical to the validated candidate. All 102,400 chunks
in a fresh five-case run match four references. Standard-seed provisional
median/p95 observations are 1.44/1.91 and 1.74/2.10 seconds, slower than the same
binary's pre-merge observations. Every case is retained in the cycle 34 evidence;
quiet performance and final acceptance remain pending. The owned temporary
worktree was removed after preserving its branch and external evidence.


## Held initial-terrain coverage experiment (evidence cycle 35)

This unmerged prototype shares one immutable initial field and resolves dependency
chunks by its actual valid coverage. It preserves the separate full-tile contract
for fossil masks and the guard required by older profiles. All 134 native unit
tests pass. A real-GPU test compares four loaded profiles, two seeds, positive
and negative origins, request sides 1/7/16/32, materials, biomes, heightmaps and
fossil mask corners against independently generated tiles. Complete output from
the five-case corpus and reverse-order two-caller runs matches all references.

The diagnostic region reduces terrain-cache requests from 722,607 to 559,481,
but still generates 8,301 terrain chunks, 63,012 anchors and 3,536,253 intermediate
columns. Initial copies and lookups are reduced; later demand still needs the
same terrain tiles. Same-session standard-Nether median/p95 observations are
1.49/2.05 seconds for the preceding merge and 1.38/1.82 then 1.56/1.95 for the
candidate. Two-caller repeats are 2.44/3.04 versus 2.45/2.94 seconds. These mixed
results do not establish an overall gain, so the prototype remains unmerged.
Quiet timing confirmation remains deferred. Full evidence and commands are in
`docs/benchmarks/nether-perf-cycle35-2026-10-21.json` and the external cycle folder.


## Immutable per-view column slots (evidence cycle 36)

Ordered material and height reads now check immutable column slots before the
view's map. Full coordinate keys distinguish collisions, and the original map
handles overflow without limiting dependency reach. The 1,024-slot array is
allocated on the heap only when queried. No slot initializer or map lock spans
recursive prior-column construction. Height queries that skip proven
height-preserving roots retain their existing separate prefix. All state retires
with its view inside generation.

All 136 native tests pass, including collisions, extreme coordinates, concurrent
misses, recursive loads, failures and lifetime isolation. The complete
102,400-chunk corpus and 20,480 reverse-order two-caller chunks match the original,
cycle 5 and preceding merged cycle 34. Diagnostic workload remains 63,012 anchors,
3,536,253 intermediate columns. The selected pilot records 8,399 generated
terrain chunks; the earlier cache variants record 8,301. Of 36,665,808 cached
view reads, 31,563,068 (86.1%) bypass the mutex/map path. This count is not a
wall-time percentage.

Initial standard-seed median/p95 observations are 1.31/1.63 and 1.48/1.76 seconds.
A sequential repeat pair measures 1.55/1.99 seconds for the preceding merge and
1.49/1.86 for this candidate. Overworld repeats are 353/413 versus 357/424 ms.
Reverse two-caller generation measures 2.41/3.15 seconds against a preceding
repeat of 2.44/3.04. The 32-, 256- and 1,024-slot experiments and every timing and
memory observation are retained in
`docs/benchmarks/nether-perf-cycle36-2026-10-21.json`. Quiet timing confirmation
remains deferred; these observations do not establish settled speedup,
no-regression or sub-second acceptance. Sustained concurrency and final portal
integration remain open. The held cycle 35 implementation is not included.


PR #187 (`26878d6`) merged immutable view-column slots. Its actual merged build
passes 136 native tests and is byte-identical to the validated candidate. All
102,400 chunks in a fresh five-case run match four references. Standard-seed
provisional median/p95 observations are 1.41/1.82 and 1.66/1.90 seconds. A custom
profile tail increase was investigated with another baseline/merged pair:
825/916 versus 809/861 ms. Overworld's full-suite peak RSS rose to 1.679 GB;
an actual diagnostic records no execution of the changed ordered-view paths.
The difference remains retained without a causal claim. Quiet timing, sustained
memory/concurrency, sub-second performance and final integration remain open.


## Held first-query preparation experiment (evidence cycle 37)

Two prototypes prepare each eligible anchor's exact first required prior-state
read before normal replay: blob material reads, then vein-height reads using the
original height prefix. Original placement queues, source ordering and RNG remain
unchanged. The variants pass 138 and 140 native tests; all standard-seed measured
chunks match the original, cycle 5 and preceding merged cycle 36.

Fresh sequential repeat median/p95 observations are 1.249/1.594 seconds for the
preceding build, 1.284/1.558 for blobs and 1.286/1.669 for blobs plus veins. These
mixed results do not establish a complete-region gain. Both prototypes remain
unmerged, with their branches, binaries and raw evidence preserved. They have
not passed the full custom-profile/concurrency acceptance corpus. Quiet timing
confirmation remains deferred. Evidence and commands are recorded in
`docs/benchmarks/nether-perf-cycle37-2026-10-21.json`.


## Avoid redundant live-overlay recommits (evidence cycle 38)

Direct Nether primitives already apply each accepted ordinary command to the
live feature overlay. Their common end-of-feature replay was reading and
inserting the same blocks again. These primitives now retain their live state;
pillars with nested caps and all other feature kinds keep the existing path.
Ordinary commands retain their original order, root metadata and ticks. Deferred
ore commands remain unapplied to the local overlay, as before. The exhaustive
primitive match requires future variants to choose their behavior explicitly.

All 139 native tests pass. Added coverage compares the retained overlay with the
original recommit after repeated and air writes, ticks, rejected boundary writes,
all six heightmaps, ground queries and rollback snapshots. Actual vein/scattered
ore tests cover ordinary and deferred commands. The five-case 102,400-chunk
corpus and reverse-order two-caller generation match the original, cycle 5 and
preceding merged cycle 36 in complete decompressed NBT and membership.

The diagnostic region skips 195,138 repeat commit calls and 10,365,063 ordinary
block writes. It still builds 63,012 anchors and 3,536,253 intermediate columns;
this change removes repeated local work without trimming dependency closure.
Initial standard-seed median/p95 observations are 1.23/1.47 and 1.35/1.59 seconds.
A fresh sequential standard-Nether pair measures 1.47/1.94 seconds for the prior
build versus 1.36/1.69 for the candidate. Overworld in that pair measures 387/481
versus 323/386 ms. All timings remain provisional: the user deferred quiet timing
confirmation, and process observations record background CPU activity. Raw runs,
hashes, commands, memory and concurrency observations are retained in
`docs/benchmarks/nether-perf-cycle38-2026-10-21.json` and the external evidence.
Sub-second standard-Nether performance, final sustained concurrency and real
portal/DH acceptance remain open. The held cycle 37 prototype is not included.

The initial two-caller run was slower than cycle 36's earlier observation
(2.59/3.43 versus 2.41/3.15 seconds median/p95). A fresh sequential reverse-order
pair measures 1.85/2.40 for the preceding build and 1.68/2.08 for the candidate,
with all 20,480 chunks in each run matching all three references. The initial
observation remains in the report; the repeat does not establish quiet acceptance.

The initial Overworld peak RSS was 2.298 GB. In the fresh pair it was 1.642 GB
for the preceding build and 1.656 GB for the candidate. The elevated initial peak
did not recur in that pair; its cause is not established. All observations remain
recorded, and sustained-memory acceptance is still pending.


PR #188 (`28a87cd`) merged this change. The actual merged rebuild passes 139
native tests and is byte-identical to the validated candidate. A fresh full
102,400-chunk corpus matches four references. Standard-seed median/p95 observations
are 1.21/1.55 and 1.39/1.64 seconds; custom profiles are 756/808 and 757/856 ms,
and Overworld is 258/303 ms. These remain provisional observations.

The merged full run repeated the elevated Overworld whole-process peak (2.366
GB). Direct probes around output hashing measure generation-before-hashing
peaks of 1.560 GB for the preceding binary and 1.550 GB for the merged binary,
then whole-process peaks of 1.681 and 1.577 GB after hashing. Both probes generate
20,480 chunks exactly matching all three original references. Validation can
increase the reported peak, but these probes do not explain the earlier elevated
observations. Sustained-memory acceptance remains pending.

A fresh eight-region stack/root sample matches all 8,192 chunks to four
references. Basalt pillars, basalt blobs and gravel ore have the longest root
intervals (112, 89 and 60 ms median in this diagnostic run). Prior-state material
reads and dependency scheduling remain prominent. Overlay::commit is absent
from the retained top 40 self-sample list. Sample counts are not elapsed-time
percentages; this profile includes observer overhead. Evidence is retained in
the external cycle 38 folder.


## Held material-run cache experiments (evidence cycle 39)

Two prototypes cache the last exact material interval: one in the shared column,
then one per anchor view. A single atomic word keeps bounds and material coherent
for concurrent readers. The variants pass 141 and 142 native tests, including air,
run boundaries, full encoded starts, large queries and concurrent interval
changes. Standard-seed generated chunks match the original, cycle 5 and merged
cycle 38 in every measured run.

Diagnostic hits are 30.75 million of 38.43 million reads for shared caches and
30.33 million for view-local caches. Those hit rates do not establish a complete
region benefit. Fresh sequential median/p95 observations are 1.220/1.568 seconds
for the prior build, 1.335/1.636 for shared caches and 1.383/1.616 for view-local
caches. Both remain unmerged and preserved. The full custom-profile, Overworld
and concurrent corpus was not run for these held prototypes. Quiet timing
confirmation remains deferred. Evidence and commands are in
`docs/benchmarks/nether-perf-cycle39-2026-10-21.json`.


## Borrow existing terrain references (evidence cycle 40)

Anchor metadata and biome reads now borrow the View-owned base terrain reference
when the original tile predicate succeeds. Fallback reads still fetch the same
chunk and retain the returned owning reference until the read completes. This
preserves the existing tile/guard coverage and failure behavior while removing
2,909,747 base-reference clone/drop pairs in the diagnostic region. It records
207,002 successful fallback reads and unchanged anchor/column/terrain work.

All 141 native tests pass, including positive/negative tile boundaries, guards,
exact pointer identity, unchanged base ownership, fetched-reference retirement,
original failures and concurrent borrow lifetimes. The selected five-case
102,400-chunk corpus and reverse-order two-caller generation match the original,
cycle 5 and preceding merged cycle 38 in full decompressed NBT and membership.

Fresh sequential standard-Nether median/p95 observations are 1.206/1.524 seconds
for the preceding build and 1.182/1.473 for this candidate. Overworld observes
250/300 versus 248/310 ms. Reverse two-caller latency is 1.704/2.041 versus
1.731/1.950 seconds, with similar total throughput. Timings remain provisional;
quiet confirmation is deferred. The held cycle 39 run-cache code is excluded.

The Overworld pair's whole-process peak RSS is 1.733 versus 2.365 GB; the preceding
binary already recorded a 2.366 GB peak in cycle 38. An actual Overworld diagnostic
records zero calls through the changed read paths. The legacy peak also includes
post-generation output validation. Its elevated observations remain unattributed,
and sustained-memory acceptance remains pending. A direct 512 MiB parent-process
launch experiment did not reproduce inherited child peak RSS, so that hypothesis
does not justify changing the launcher. All observations, commands and hashes
are retained in `docs/benchmarks/nether-perf-cycle40-2026-10-21.json` and the
external evidence. Standard-Nether sub-second latency and final acceptance remain
open.


Cycle 40 post-merge proof: PR #189 squash-merged as
`5d533eff7742518ffcbbf364faf806b1766207d6`. A forced native rebuild passed all
141 tests (37 ignored), produced the identical validated library hash, and
actually generated the five-case corpus with 102,400 chunks matching the original,
cycle 5, preceding merged cycle 38 and candidate outputs in full NBT and membership.
Merged median/p95 observations: main Nether 1,195.554/1,456.984 ms, second seed
1,356.888/1,609.548 ms, custom 767.369/813.651 ms, direct stem 765.755/823.301 ms,
Overworld 261.397/294.201 ms. Timings remain provisional; final performance,
concurrency, memory and integration acceptance is still open. Evidence:
`cycle40/post-merge-proof.json` and `borrowed-terrain-merged.py` under the retained
evidence root. Only the owned cycle 40 worktree is cleaned up after preserving
this proof; held prototypes remain intact.


## Cycle 41 — Dependency tile-size experiments (held)

The job terrain cache used aligned 16-chunk squares. Compared aligned 8- and
32-chunk squares, with matching fossil batches and View bounds that follow each
field's actual interior. Kept all GPU/material guards, the unbounded fallback
cache, per-fossil RNG and prepared order unchanged. Both variants passed 141
native tests and an actual GPU parity test spanning four profiles, two seeds,
initial sides 1/7/8/16/32, negative/positive boundaries, materials, metadata,
biomes, heights and fossil-mask corners.

Each variant also generated 20 measured complete regions after five warmups;
all 20,480 chunks matched the original, cycle 5 and merged cycle 40. Separate
1,024-chunk diagnostics matched those references. Neither variant is accepted:
merged 16-chunk median/p95 was 1,195.554/1,456.984 ms; 8 chunks measured
1,496.041/1,872.310 ms; 32 chunks measured 1,433.970/1,736.663 ms. The diagnostic
terrain count grew from 8,301 to 12,501 and 13,420 respectively, with the same
63,012 anchors and 3,536,253 intermediate columns. Smaller tiles increased
readback from about 229 to 306 MB per measured region. Timings remain provisional.

Preserve both native variants, binaries and evidence. No implementation PR is
opened for these held experiments. No complete custom/Overworld/concurrency
acceptance is claimed. The next measurement refinement will retain per-tile
work attribution and distinguish generation peak memory from later validation.
See `docs/benchmarks/nether-perf-cycle41-2026-10-21.json` and the external evidence.


## Cycle 42 — Tile work and memory attribution

Aggregate terrain counts hid which tile geometry was repeated, and the legacy
peak RSS included later output validation. Preserve exact per-tile generation
counts in the existing opt-in tracking and report a generation-completion memory
high-water mark alongside the unchanged final whole-process peak. Snapshot
subtraction retains repeated warmup keys and excludes warmup-only work. Full cache
identity is preserved; older native snapshots remain supported. Generation and
cache behavior are unchanged; neither held tile-sizing variant is included.

Passed 142 native tests and 11 benchmark-helper tests (one optional LZ4 test
skipped; retained runs use zlib). The full five-case corpus's 102,400 chunks match
the original, cycle 5 and merged cycle 40 in complete NBT and membership.
Diagnostic and older-library compatibility runs also match. The diagnostic sums
reconcile exactly: Nether generated 8,399 chunks (14 side-22, two side-7, one
side-38 and one side-9 tile), covering 5,508 distinct GPU chunk coordinates.
That overlap is not proof that every guard can be removed or reused. Previous
8,301-chunk diagnostics lacked the additional two side-7 query tiles; no causal
attribution is claimed for that cache-dependent variation.

Complete-region median/p95 observations remain provisional: standard Nether
1,159.203/1,457.024 ms, second seed 1,319.428/1,623.512 ms, custom
770.549/815.149 ms, direct stem 772.963/828.614 ms, Overworld
258.951/323.522 ms. A fresh old/new Overworld pair measured
276.085/343.413 versus 271.929/323.237 ms, with exact output. This is measurement
work, not a speedup claim. Overworld's full-run peak grew from 1,581,891,584 bytes
at generation completion to 1,653,784,576 after validation. Historical elevated
peaks remain recorded and unattributed. Final timing, sustained-memory,
concurrency and portal/DH acceptance remain open.

Evidence: `docs/benchmarks/nether-perf-cycle42-2026-10-21.json`, external
`cycle42/attribution-check.json` and `work-attribution-*.py`.


Cycle 42 post-merge proof: PR #190 squash-merged as
`ea148fd298f48c5675cad58261ca88e4b49f640d`. A forced merged rebuild passed 142 native tests
and 11 benchmark-helper tests (one optional LZ4 skip), and produced the same
validated native-library hash. The merged code actually generated all five cases;
102,400 chunks match the original, cycle 5, preceding merged cycle 40 and
validated candidate in complete NBT and membership. Merged provisional median/p95:
nether-main-run0 1204.847/1412.456 ms; nether-second-seed-run0 1404.029/1613.859 ms; nether-custom-bounds-run0 769.724/815.255 ms; nether-direct-stem-run0 790.850/842.229 ms; overworld-run0 304.457/393.943 ms.
The merged Overworld observation increased despite an identical candidate library and no native/harness source difference. Process logs show VM and photo-analysis activity; causation is unproven. Retain the increase and defer timing acceptance; see `cycle42/post-merge-timing-review.json`.
Both generation-completion and whole-process peaks are retained in
`cycle42/post-merge-proof.json`. Final timing, sustained memory, concurrency and
portal/DH acceptance remain open. Preserve the cycle 41 prototypes; clean up only
the owned cycle 42 worktree after retaining this proof.


## Cycle 43 — Retain exact cached terrain tiles

A job can now retain matching cached dependency tiles before global eviction.
Reuse requires the full world/seed/profile/bounds/parameter key and identical
patch-halo geometry, metadata crop and material coverage. Retention is limited to
the existing 64 nearby slots; initial-field coverage keeps precedence and misses
generate normally. Fresh and reused fields publish the same normal query-cache
coverage using the existing bounded insertion code. No feature ordering, RNG,
structures, lighting or cleanup changes.

Passed 145 native tests and an actual GPU parity test across four profiles,
two seeds and tile/guard boundaries, including concurrent reads and cache eviction.
All 102,400 chunks in the five-case complete corpus, plus 20,480 reverse-order
two-caller chunks, match the original, cycle 5 and merged cycle 42 in full NBT
and membership. The earlier direct-only and unpublished-retention variants remain
preserved separately.

Across four adjacent diagnostic regions, generated terrain falls from 33,270 to
31,432 chunks, with four retained tile uses and identical anchor/column work.
Normal cache publication removes one extra small query, though two remain versus
the baseline. The selected 20-region main run reads back 216.7 MB per region
versus 229.4 MB in the preceding merged run. This is a 5.5% work/readback reduction,
not a wall-time percentage.

Candidate complete-region median/p95 observations: nether-main 1075.126/1353.917 ms; nether-second-seed 1216.942/1443.962 ms; nether-custom-bounds 719.564/789.367 ms; nether-direct-stem 708.344/755.481 ms; overworld 250.731/305.848 ms.
Fresh baseline/direct/job repeats are close on latency. Main generation peak is
2.318 GB versus 2.266 GB in the fresh preceding repeat; sustained-memory acceptance
is unproven. Quiet timing confirmation remains deferred by user instruction.
Standard Nether remains above one second, and final Overworld, concurrency,
sustained-memory and portal/DH acceptance remain open. Evidence and reproduction
commands: `docs/benchmarks/nether-perf-cycle43-2026-10-21.json`.


Cycle 43 post-merge proof: PR #191 squash-merged as
`e5adbd17ecdd47eda7e005bbd2f3ef6b5e59ddd5`. A forced rebuild passed 145 native tests
(38 ignored) and produced the same validated native library. Actual merged
generation produced 102,400 chunks matching the original, cycle 5, preceding
merged cycle 42 and candidate outputs in full NBT and membership.
Provisional merged median/p95: nether-main 1092.490/1363.392 ms; nether-second-seed 1244.848/1469.377 ms; nether-custom-bounds 783.476/844.249 ms; nether-direct-stem 776.069/846.864 ms; overworld 242.122/312.993 ms.
Quiet timing confirmation, sustained memory, final 1/2/4/8 concurrency and
portal/DH acceptance remain open. Evidence: `cycle43/post-merge-proof.json`
and `exact-cache-merged.py` under the external evidence root. Preserve variant
branches and evidence; remove only the owned cycle 43 worktree after saving
this proof.


## Cycle 44 — Spatial column-view slots

Hash collisions sent many repeated prior-column reads through a mutex-backed
map. Keep the existing 1,024 immutable per-view slots and index them by block
coordinates modulo 32 on each axis. Every 32-by-32 window has distinct slots;
exact coordinate checks and the original unbounded map retain wider/disjoint
reads. Publication, recursive loads, errors, allocation size and view lifetime
are unchanged. The smaller four-way hashed prototype is preserved but excluded.

Passed 146 native tests, including full windows across negative/positive/extreme
coordinates, fallback collisions, recursive reads, errors, concurrent access and
retirement. The five-case corpus's 102,400 chunks and 20,480 reverse-order
two-caller chunks match the original, cycle 5 and preceding merged cycle 43 in
complete NBT and membership.

The single-region diagnostic increases fixed-slot hits from 86.08% to 99.11%,
avoiding 4,776,137 map lookups with identical column loads and anchor/intermediate
column counts. This is lookup attribution, not a wall-time share. The smaller
256-slot four-way prototype had exact output and lower diagnostic peak memory,
but no clear complete-run improvement; its branch and evidence remain preserved.

Provisional candidate median/p95 observations: nether-main 1048.774/1303.255 ms; nether-second-seed 1258.839/1465.203 ms; nether-custom-bounds 728.220/811.878 ms; nether-direct-stem 719.737/846.997 ms; overworld 231.113/281.208 ms.
Overworld recorded an elevated 2.331 GB generation peak in the full corpus.
A fresh old/new pair measured 1.570/1.567 GB and 245.311/285.204 versus
242.024/284.505 ms median/p95. Both actual diagnostics recorded zero reads
through the changed View cache. Retain the earlier peak as unattributed; this
is not sustained-memory acceptance. See `cycle44/overworld-memory-review.json`.

Main generation peak is 2.303 GB versus 2.289 GB in the preceding merged run.
Quiet timing confirmation remains deferred. Standard Nether remains above one
second, and final Overworld, concurrency, sustained-memory and portal/DH
acceptance remain open. Evidence, the held variant and commands are retained in
`docs/benchmarks/nether-perf-cycle44-2026-10-21.json`.


Cycle 44 post-merge proof: PR #192 squash-merged as
`74f4e1b7c48e31fe4d98a9648e8b20e842d65db2`. A forced rebuild passed 146 native tests
(38 ignored) and produced the identical validated native library. Actual merged
generation produced 102,400 chunks matching the original, cycle 5, preceding
merged cycle 43 and candidate outputs in full NBT and membership.
Provisional merged median/p95: nether-main 1174.419/1460.245 ms; nether-second-seed 1449.892/1734.223 ms; nether-custom-bounds 866.820/973.859 ms; nether-direct-stem 904.248/977.208 ms; overworld 352.269/384.605 ms.
The merged run is slower despite the identical library. Process records show
external VM/Spotlight CPU activity, without proving its causal contribution.
Retain these observations and defer timing acceptance; see
`cycle44/post-merge-timing-review.json`. Quiet timings, sustained memory,
final 1/2/4/8 concurrency and portal/DH acceptance remain open.
The fresh execution sample and complete-output comparisons are retained under
`cycle44/steady-execution-sample`; sample counts are not wall-time shares.
Preserve the hashed prototype branch and evidence. The owned cycle 44 worktree
is removed only after saving this proof and its documentation patch.


## Cycle 45 — Overlay coordinate filters (held)

Tested a 256-bit XZ filter, an added 64-bit Y filter, and the combined filter
without new per-read diagnostics. Filters only bypass impossible exact block-map
lookups; collisions retain the original map, and writes/snapshots/undo preserve
conservative membership. All three passed 148 native tests (38 ignored), and
each matched 20,480 main-corpus chunks plus 1,024 diagnostic chunks against the
original, cycle 5 and preceding merged cycle 44 in complete NBT and membership.

The instrumented combined filter bypassed 16,145,000 nonempty-map reads, plus
6,726,731 empty-map reads. These counts cover Overlay reads through ordered
Views only, are not wall-time shares, and were not measured in the plain binary.

Fresh sequential baseline/XZ/combined median/p95 observations were
1135/1384, 1108/1431 and 1173/1421 ms. The plain combined filter recorded
1142/1446 ms. Neither latency nor generation peak established a clear overall
benefit. Preserve all variants and evidence without merging. Quiet timing
confirmation remains deferred; other profiles, Overworld and concurrency were
not tested for this held candidate. Details and commands are in
`docs/benchmarks/nether-perf-cycle45-2026-10-21.json`.


## Cycle 46 — Stop resolved heightmap scans (held)

Column reconstruction checked all six heightmaps through every material run,
even after finding their heights. Track unresolved bits, assign matching maps
in the original top-down order, and stop once every map is resolved. Preserve
the min_y sentinel, stored runs, ownership and all feature/material behavior.

Passed 148 native tests (39 ignored), including an independent dense-block
heightmap oracle with all 256 masks, distinct map heights, empty/air/partial
columns, tall input and safe extreme vertical bounds. An ignored same-process
A/B microbenchmark alternates baseline/candidate order for five warmups and 25
measured pairs across six synthetic patterns. Every measured pair improved;
this measures height reconstruction only, not complete-region speedup.

The five-case corpus's 102,400 chunks and 20,480 reverse-order two-caller chunks
match original, cycle 5 and preceding merged cycle 44 in complete NBT and
membership. Diagnostic anchor, intermediate-column, prefix and prior-read
counts are identical. The candidate diagnostic generated two extra side-7
terrain tiles (98 chunks); retain this observed difference without claiming
unchanged GPU work or attributing it conclusively to scheduling/cache order.

Provisional median/p95 observations: nether-main 1155.670/1463.324 ms; nether-second-seed 1332.162/1490.491 ms; nether-custom-bounds 766.459/818.547 ms; nether-direct-stem 770.585/815.232 ms; overworld 252.645/292.376 ms.
The initial reverse two-caller run recorded 1667/2306 ms median/p95. A fresh
old/candidate pair recorded 1680/2083 versus 1811/2255 ms, with throughput
1220 versus 1099 chunks/s. VM CPU activity was observed but its contribution
was not isolated. Hold the candidate without a PR: faster local scans did not
establish a whole-region benefit. Quiet timing acceptance remains deferred. Standard Nether remains
above one second. Final sustained memory, Overworld, 1/2/4/8 concurrency and
portal/DH acceptance remain open. Evidence and commands are retained in
`docs/benchmarks/nether-perf-cycle46-2026-10-21.json`.


## Cycle 47 — Attribute root phases and base-material demand (held)

Extend the existing opt-in `RETINA_DEPENDENCY_DIAGNOSTICS=1` output with JSON
records for queue, target enumeration, joined anchor evaluation and publication.
Keep original progress lines. Diagnostic-only job IDs separate concurrent runs.
After the final root barrier, inspect successful base slots without loading or
changing cells. Deduplicate interior/boundary coordinates and report per-chunk
base-column counts. There is no new per-read tracking, production cache or
changed generation behavior; disabled runs emit neither new record type.

Passed 148 native tests (38 ignored). The five-case corpus's 102,400 chunks,
20,480 reverse-order two-caller chunks, 8,192 phase-run chunks and 2,048 parallel
diagnostic chunks match original, cycle 5 and preceding merged cycle 44 in full
NBT and membership. Parallel records reconcile independently by job/request.

Across eight main-seed regions after five warmups, median per-region totals are
0.770 ms queue, 0.178 ms enumeration, 778.112 ms evaluation and 61.724 ms
publication. Evaluation includes nested terrain/synchronization; these are not
CPU-time shares. Publication alone cannot close the standard Nether gap.
Base demand spans 430,020–515,937 columns in 2,108–2,398 chunks. The separate
pilot reports 461,038 columns, exactly matching its shared-material counter.
This excludes pure biome/metadata reads and later final structure work; it
does not prove that any other terrain or guard is unnecessary.

Provisional complete-run median/p95: nether-main 1246.401/1485.842 ms; nether-second-seed 1441.031/1673.141 ms; nether-custom-bounds 852.861/928.901 ms; nether-direct-stem 849.256/901.208 ms; overworld 332.544/407.359 ms.
Fresh old/new observations and actual external CPU activity are retained in
`cycle47/repeat-review.json`. No speedup, quiet timing, Overworld non-regression
or sustained-memory acceptance is claimed. Old/candidate/old Overworld medians
were 279/345/243 ms. Both actual path diagnostics recorded zero dependency
work and no root output; the elevated candidate timing remains unexplained.
Hold the source without a PR while retaining its attribution evidence.
The broader goal remains open. Details and reproduction are in
`docs/benchmarks/nether-perf-cycle47-2026-10-21.json`.


## Cycle 48 — Bound exact tile residency independently of chunk aliases (held)

The 2,048 chunk-key cache can evict a complete 16-by-16 dependency tile after
only eight disjoint full tiles. Retain exact canonical tiles separately, capped
at 24 entries and 256 MiB of conservatively counted backing allocation capacity
per engine across profiles. Feed the existing exact job snapshot, preserving
all full keys, geometry, guards, fallback coverage and normal chunk/column cache
semantics. Jobs own their snapshot references independently of later eviction.
The cap bounds this optional index, not total process memory.

Passed 149 native tests (39 ignored) and two real-GPU tests covering four profiles,
two seeds, materials, biomes, heightmaps, guards, ownership and forced alias
churn. The full five-case corpus's 102,400 chunks and each 20,480-chunk run with
2, 4 and 8 callers match the original, cycle 5 and preceding merged cycle 44 in
complete NBT and membership. Separate adjacent and row-transition diagnostics
also match all three references.

Across eight measured regions after five warmups, generated chunk-cell work
falls from 71,177 to 59,996 (15.7%) and readback from 217.2 to 189.3 MB per region
(12.8%). Exact cached-tile uses rise from 7 to 30; anchor and intermediate-column
counts are identical. A 48-entry experiment under the same byte cap produced
no additional exact tile reuse. Preserve it separately and keep the lower limit.

Provisional full-corpus median/p95: main 1184.824/1471.000 ms; second seed
1311.085/1594.692 ms; custom bounds 878.928/922.973 ms; direct stem
844.946/912.907 ms; Overworld 324.081/380.651 ms. An Overworld follow-up records
319.273/395.994 ms for the preceding version and 315.236/389.156 ms for the
candidate. The candidate's earlier 2.32 GB generation peak did not repeat
(1.69 GB in the follow-up); its cause remains unproven.

Hold without a PR: two/four-caller aggregate throughput falls from 1076/1073 to
987/973 chunks/s despite better p95. Eight-caller throughput is 1130 versus
1118 chunks/s, but the candidate maximum remains 17.8 s. These are provisional
observations under external activity, not quiet acceptance or proof of its
causal contribution. Preserve the coherent source and evidence for later quiet
validation; investigate dense intermediate-column expansion/recompression next.
Standard Nether, sustained memory and portal/DH acceptance remain open. Detailed
provenance and commands are in `docs/benchmarks/nether-perf-cycle48-2026-10-21.json`.


## Cycle 49 — Sparse intermediate-column edits (held)

A single accepted material write expands a full column and scans it again to
encode material runs. Keep up to eight distinct latest point writes in a local
buffer, reading those before immutable prior runs. On finish, stream sorted
edits with existing runs, coalesce adjacent equal materials and preserve the
canonical descending encoding. The ninth distinct position expands the prior
column once and replays all buffered values before continuing the original
dense path. Role predicates, support queries, command order, heightmap
calculation, identity-state Arc reuse and cleanup scope remain unchanged.

Passed 150 native tests (38 ignored), including dense voxel/heightmap oracles,
run boundaries, repeated/reverted writes, dense transition, tall columns and
shared base immutability. The five-case corpus's 102,400 chunks, initial 20,480
main chunks, 20,480 reverse-two chunks and 1,024 diagnostic chunks match original,
cycle 5 and preceding merged cycle 44 in complete NBT and membership.

The one-region diagnostic records 189,599 sparse finishes and 192,867 dense
finishes. Thus about half of edited-column reconstructions avoid full-column
expansion. Anchor, column-build, prefix, terrain and prior-read counts are
identical to the control. These are counts of reconstruction work, not a
proportional elapsed-time or peak-memory saving.

Provisional complete-corpus median/p95: main 1140.414/1449.578 ms; second seed
1270.727/1443.796 ms; custom bounds 761.997/816.008 ms; direct stem
732.720/788.519 ms; Overworld 261.401/289.845 ms. The fresh main control records
1130.840/1410.202 ms, so no whole-region gain is established. Reverse-two
throughput falls from 1162 to 1023 chunks/s (about 12%), with median/p95
1642/2421 versus 1938/2464 ms. Hold without a PR; preserve the prototype and
full evidence. Quiet timing confirmation remains deferred. The unchanged
preceding binary also reached a 2.35 GB Overworld generation peak here, so
that peak is not exclusive to candidate binaries; its cause remains unproven.

The next unimplemented hypothesis concerns successful immutable boundary-column
versions from neighboring completed jobs. It needs explicit context-independence,
writer-closure, lifecycle and retained-memory proofs before implementation.
Details, source review and reproduction are in
`docs/benchmarks/nether-perf-cycle49-2026-10-21.json`. Standard Nether, final
concurrency, sustained-memory and portal/DH acceptance remain open.

## Completed-region column snapshots (evidence cycle 50)

Neighboring jobs often reconstruct the same before-root boundary columns. This
cycle retains successful completed interior states in a shared bounded index and
captures nearby snapshots once when a fresh region job starts. A hit supplies the
exact immutable material runs and six heightmap values for the requested root.
Missing or uninitialized latest versions follow the existing dependency path.
All target anchors, metadata planning, source ordering and dependency reach remain
unchanged. Single-chunk, ad-hoc and partial-file paths do not opt in.

The selected representation packs column indexes, root markers and unique states
into flat arrays. Returned columns retain only the shared material-run buffer.
Original planner allocations retire during export; successful snapshots publish
after durable MCA publication. Failure cleanup and evictions finish within the
native generation call. The resident index has eight entries and a 512 MiB
counted-backing limit across all contexts; active job pins and temporary export
buffers are additional. This is not a total-process RSS limit.

A prior mutable-version representation is preserved unmerged as
`codex/nether-held-version-objects`. Packing roughly halves its retained snapshot
backing and removes expensive later destruction of nested planner objects.

Validation passed 151 native tests and two real GPU tests. Twenty-four independent
neighboring job pairs compared 12,531,389 naturally available before-root states
with zero material, height or run-encoding differences. The five-case 102,400-chunk
corpus, reordered 2/4/8-caller runs, diagnostics and repeat controls match complete
NBT against the original, cycle 5 and preceding merged cycle 44 references.

Eight-region diagnostics show 29,316,083 -> 21,163,126 reconstructed columns
(27.8% fewer), 70,981 -> 59,866 terrain cells (15.7% fewer), and
508,876 -> 467,944 anchors (8.0% fewer). Five snapshot evictions occurred;
publication including eviction took 3.29 ms across the eight outputs. Export
(including packing and original allocation retirement) took 1,024 ms across the
eight outputs. These durations overlap other phase counters and are not additive
wall time. Diagnostics are separate from headline timing runs.

Each binary also generated 240 fresh regions in one process across four seed/profile
combinations, revisited in forward, reverse and shuffled order. The three supplied
profile files normalize to two distinct native registrations. All 245,760 chunks
per binary match each reference. Generation-end current RSS was 2,446 MiB for the
preceding binary and 2,750 MiB for the candidate; generation peaks were 2,605 and
2,987 MiB. Both finite runs grew, with smaller final-pass increments. This does not
establish an indefinite RSS plateau.

Actual Fabric portal/reopen fixtures pass with and without DH. Cold arrivals
produced four regions in 2.95/3.15 seconds while 59/60 server ticks continued;
maximum observed tick gaps were 163/210 ms. One actual DH native region completed
during the cold wait. Reopened arrivals generated zero chunks and preserved the
saved fixtures. Fabric and NeoForge both pass two in-flight unload/reopen cycles,
including preview cleanup and zero late generation failures. The Java native
concurrency fixture also matches independent chunk probes to a full region.

Candidate standard-Nether observations span roughly 1.01–1.15 s median and
1.35–1.62 s p95 across the full and repeated runs. Repeat p95 is not uniformly
better. An earlier slow Overworld observation did not repeat in adjacent
old/new/old controls (256/241/258 ms medians) with the same candidate binary.
Four-caller throughput improved in the observed run; 2/8-caller differences are
small and eight-caller tails remain long. Quiet timing confirmation is deferred
by the user. Subsecond p95, final no-regression, sustained-memory and concurrency
acceptance remain open; the overall goal is not complete.

Details and recovery paths: `docs/benchmarks/nether-perf-cycle50-2026-10-21.json`.
The external `cycle50/report.json`, `retention-review.json`, `runtime-review.json`
and `overworld-investigation.json` retain complete comparisons and commands.

Cycle 50 merged as PR #193 (`b34d880`). The forced merged rebuild passes
151 native tests and is byte-identical to the selected candidate. Its actual
five-case corpus matches all 102,400 chunks against the original, cycle 5,
preceding cycle 44 and selected candidate. Provisional merged median/p95:
main 960/1315 ms; second seed 1012/1356 ms; custom bounds 682/823 ms;
direct stem 659/850 ms; Overworld 250/298 ms. Subsecond p95 and final quiet
performance acceptance remain open. Full proof: `cycle50/post-merge-proof.json`.

## Cycle 51 — retain useful edges of completed snapshots

A test-only observer recorded 3,081,141 successful reads from completed region
snapshots across four Nether cases, five warmups and twenty measured regions
per case. Every observed read was within 25 blocks of the source region edge.
All 81,920 chunks matched the original, cycle 5, cycle 44 and merged cycle 50.
This observation selects a retention policy; it does not bound valid feature
reach. Requests outside the retained band always use the original full planner.

The candidate retains a 32-block edge band in compact row-major arrays. A full
region archives 61,440 column positions instead of 262,144. Every retained
column preserves all of its root markers, missing latest cells, exact material
runs and six heightmaps. Context isolation, global entry/byte caps, publication
after durable MCA completion and synchronous cleanup are unchanged. No observer
or observation call site is compiled into normal native libraries.

In the eight-region diagnostic, exported backing fell from 503,968,864 to
110,039,688 bytes (78.17%). All 294,798 cache hits were preserved; both variants
built 21,163,126 columns, 18,024,027 prefixes and 467,944 anchors. Export including
mandatory original-planner retirement took 969.9 versus 817.8 ms total, about
19.0 ms less per region in this diagnostic. These counters are separate from
headline timing runs, and overlapping durations must not be added as wall time.
The saved bytes are cumulative exported backing, not a process-RSS measurement.

The candidate passes 153 native tests and two real-GPU context/publication
tests. Full NBT matches the four references for the five-case 102,400-chunk
corpus, 2/4/8-caller reordered runs, repeated main/seed2/Overworld runs, and all
245,760 chunks from the same-process 240-region retention run. The Java native
concurrency fixture compares 458,752 voxels from seven independent probes.

The first four-caller sample was 7.8% slower; the repeated candidate reached
1504 chunks/s between controls at 1225 and 1442. Slower Overworld samples were
also investigated. Adjacent old/new/old median/p95 observations were 293/369,
258/330 and 251/295 ms, with the candidate between controls. No consistent
material regression reproduced; this is still provisional evidence. The full
candidate median/p95 observations in ms were main 906/1272, second seed
1068/1445, custom 626/781, direct 615/781 and Overworld 245/312.

Smaller snapshot backing does not imply equally lower process RSS. The finite
240-region candidate ended at 2723 MiB versus 2750 MiB in the retained preceding
run, but generation peak RSS was higher: 3267 versus 2987 MiB. Current memory
fell again between passes; the last pass median was 2680 MiB versus 2714 MiB.
The eight-caller peak also rose from 6526 to 6882 MiB. These observations include
allocator retention, other caches, GPU resources and active-job overlap. No
cause for the peak differences or indefinite RSS plateau is established.

The actual Fabric portal/DH fixture generated four Nether regions in 3.23 s
while 62 server ticks continued, with a 179 ms maximum observed tick gap. Two
real DH jobs completed during the wait. Reopening generated zero chunks and
preserved fixtures and lighting. NeoForge passed both unload/reopen cycles
with eight native jobs active, preview cleanup and zero late generation
failures. Both clients' actual native mapping paths and hashes match the
candidate. These are integrated-client fixture results, not a new dedicated
server or unaided survival acceptance run.

Selection is based on the reduced optional archive cost, exact generation and
validated lifecycle behavior. Quiet timing confirmation remains deferred by
the user. Subsecond standard-Nether p95, final no-regression, long-term memory
and concurrency-tail acceptance remain open. This cycle does not complete the
overall goal.

Full report: `docs/benchmarks/nether-perf-cycle51-2026-10-21.json`. Raw measurements,
commands, unchanged binaries and worlds are retained under external `cycle51/`.

Cycle 51 merged as PR #194 (`e50eddd`). The forced merged rebuild passes all
153 native tests and is byte-identical to the selected candidate. Its full
102,400-chunk corpus matches the original, cycle 5, preceding merged cycle 50
and selected cycle 51. Provisional merged median/p95 in ms: main 942/1303;
second seed 930/1288; custom 644/783; direct 629/771; Overworld 246/305.
Quiet acceptance remains deferred and standard-Nether p95 still exceeds one
second. Full proof: external `cycle51/post-merge-proof.json`.


## Held direct snapshot lookup experiment (evidence cycle 52)

The completed-snapshot candidate selects one captured region through a local
nine-position array. Exact context, prefix, negative-coordinate, missing-state
and planner-fallback behavior remains unchanged. Across four observed Nether
cases, snapshot visits fall from 406,804,116 to 3,081,141 with identical queries
and hits. The observer adds overhead; this count is not an elapsed-time gain.

The final source passes 155 native tests. Full five-case, reordered two/four/eight
caller and repeated standard-seed/Overworld runs match four complete-NBT
references. Timings are mixed: two-caller throughput falls about 22%, four-caller
rises about 20%, and eight-caller falls about 1%. Repeated standard-seed medians
change by about 1%; no consistent overall gain has been established. External
CPU activity was recorded. The candidate remains unmerged, with source and
evidence preserved. Final quiet timing confirmation is deferred by the user.
No candidate runtime or actual merged validation is claimed.

See `docs/benchmarks/nether-perf-cycle52-2026-10-21.json`; full raw output and
provenance remain in its external evidence directory.

## Cycle 53 — reuse completed exterior prefix states

An observer found 12,048,015 exact state matches between naturally computed
neighboring regions across four Nether cases, with no mismatch. A separate
test cleared CPU and completed-region caches between each side of 24 region
pairs: all 5,755,113 overlapping prefix states matched. These are observations
of exact prefixes; missing prefixes cannot be inferred from equal later states.

The candidate adds a 64-block exterior band to completed snapshots. It copies
only successful, initialized full-prefix cells after all source roots join.
Consecutive observed prefixes may share an interval only when they have the
same live state owner. Every gap remains a miss. Material runs and six heights
are copied; original planners and prior snapshots are not retained. Existing
context isolation, full-region eligibility, durable publication and fallback
to the complete planner remain unchanged. Interior and exterior backing share
the existing eight-entry/512 MiB cache limit.

The selected exporter groups tile references by coordinate and prefix, then
builds each column's intervals directly. A paired experiment on four identical
natural planner inputs reduced export-and-destruction medians from 119–185 ms
to 30–44 ms, with 20/20 faster pairs per case. It checked every interval,
material run, height and hole. This compares two C53 export algorithms; it is
not a 73–76% improvement in complete generation over C51.

In the eight-region diagnostic, prefix evaluations fell from 18,024,027 to
16,595,926 (7.92%), anchors from 467,944 to 461,321, and column cells from
21,163,126 to 19,849,768. The candidate served 473,399 exterior hits. Combined
cumulative exported backing increased from 110,039,688 to 242,498,224 bytes.
Instrumented whole-run duration increased from 13.99 to 20.08 seconds in the
selected diagnostic, despite reduced work. This result is preserved alongside
ordinary repetitions; overlapping counters are not summed as wall time.

Ordinary main-seed ABBA repetitions favored the candidate: 1306/1794 and
1415/1801 ms median/p95 versus surrounding controls at 1528/2033 and
1514/1987 ms. Other results remain mixed. Four-caller throughput rose about
18% with about 1.05 GB more peak RSS; eight-caller throughput fell about 5%
while p95 improved. An initial two-caller tail regression and an Overworld
tail were investigated with old/new/old repetitions. Candidate throughput,
latency and peak RSS then lay between the controls. Actual enabled Overworld
diagnostics recorded zero dependency-cache work. No final uncontended
performance or no-regression acceptance is claimed.

A separate current-binary run used existing root timestamps with per-read
counters disabled. The dependency endpoint median was 902 ms within a 1409 ms
whole-region median. Larger root intervals included initial basalt pillars and
setup (96.7 ms), fire (62.6 ms), basalt blobs (54.3 ms) and brown mushrooms
(53.2 ms). These intervals include nested terrain/GPU work, synchronization and
publication; they are not isolated CPU feature times, and medians are not added.
All 20,480 chunks again matched the four references. This identifies current
work for further profiling without importing the older held C47 instrumentation.

All 157 native tests pass, with 44 optional tests ignored. Actual publication
failure/retry and existing-file skip tests pass. Complete NBT matches the
original, cycle 5 and merged cycles 50/51 for the five-case 102,400-chunk
corpus, reordered 2/4/8 callers, all repetitions, and 245,760 chunks from the
same-process 240-region retention run. The final normal library is byte-identical
to the library used for the full corpus.

The retention run ended at 3007 MiB current RSS versus 2723 MiB in the retained
C51 run; peak RSS was 3156 versus 3267 MiB. These were not adjacent controls.
Candidate pass medians were 2421, 2841 and 2930 MiB. Memory fell between some
batches, but this finite run does not establish an indefinite plateau. The
archive cap does not bound active-job ownership, temporary export allocations,
other caches or total process RSS.

Loader/common tests, the JNI concurrency fixture and all three integrated
client fixtures pass. Cold portal travel generated four regions in 4.91 s
without DH and 6.06 s with DH; 101/116 server ticks continued, with observed
maximum gaps of 182/306 ms. Two actual DH jobs completed during the latter
trip. The DH fixture first waited about 2.5 minutes for background work to
start; an idle-worker thread dump and this unresolved scheduling delay are
retained in the evidence. Reopening generated zero chunks. NeoForge completed
two unload/reopen passes with eight native jobs active, preview cleanup and
zero late generation failures. JNI and all clients mapped the selected native
library hash. These are integrated fixtures, not dedicated-server acceptance.

Selection is based on exact reusable state, reduced prerequisite work and
validated publication/lifecycle behavior. Quiet timing confirmation remains
deferred by the user. Standard-Nether subsecond p95, final concurrency-tail and
long-term memory acceptance remain open; this cycle does not complete the goal.

Full report: `docs/benchmarks/nether-perf-cycle53-2026-10-21.json`. Raw commands,
immutable libraries, generated worlds, the failed test-only build attempt and
all measurements remain in the external `cycle53/` evidence directory.

Cycle 53 post-merge confirmation: PR #195 merged as
`0132a7435842d5ab4f060bee6ac107841a120cd3`. The forced rebuild passed 157 tests
and produced the exact selected library SHA-256
`dd00145de6ea2916a17efbb703c05e0a210becec24a8403e44139a2dabed161b`.
All 102,400 measured chunks matched the original, cycle 5, merged cycle 51 and
selected cycle 53. Actual merged median/p95 observations were 1481/2186 ms
(main), 1122/1527 (second seed), 914/1032 (custom), 799/999 (direct stem), and
347/422 (Overworld). These remain provisional. The external post-merge proof
is now included in the committed cycle 53 report; the goal remains open.

## Cycle 54 — measure material reads by feature root

Two fresh CPU samples of merged cycle 53 placed `Overlay::material`, Nether
`Feature::place`, `ColumnState::material` and `Planner::prefix` among the leading
native leaf symbols. Leaf sample counts are not wall-time shares. Before adding
another cache, this cycle observes exact-coordinate reuse during real generation.

The observer always performs the original lookup and returns its actual result.
Separate simulations retain full xyz keys, compare every hit, account for writes,
clear on context changes and never remember missing values. Snapshot/undo state
starts cold. Local counters flush when each trace is dropped; the fixture verifies
that every started trace was flushed. Root metadata labels each ordered View by
its root index and first recipe. Unknown providers and mixed contexts remain
unattributed. Object addresses are observation keys, not a production stability
contract.

Both the initial three-capacity inventory and expanded five-capacity/root
inventory generated five warmups plus twenty complete lit regions in each of
five cases. Each repeated 102,400-chunk measured corpus matches the original,
cycle 5 and merged cycles 51/53; the expanded inventory additionally matches the
initial observer. All simulated hits match actual reads, with zero context resets
or missing material reads in these observed cases. No cache is used for generation.

| Nether case | Material reads per region | 1-entry hits | 16-entry hits | 256-entry hits |
| --- | ---: | ---: | ---: | ---: |
| nether-main | 26.82M | 16.37% | 17.68% | 23.13% |
| nether-second-seed | 26.38M | 16.19% | 17.50% | 22.92% |
| nether-custom-bounds | 12.50M | 0.54% | 1.93% | 6.04% |
| nether-direct-stem | 12.19M | 0.54% | 1.93% | 5.95% |

The main seed concentrates 24.8% of reads in delta features, 24.8% in basalt
blobs, 22.6% in large basalt columns and 6.2% in small basalt columns. Delta and
blob exact-coordinate reuse is low. In contrast, a single entry matches 58.1%
of large-column and 47.4% of small-column reads; the second seed repeats this
pattern at 58.4% and 48.1%. Together those groups offer about 4.3 million
potentially avoided reads per standard-Nether region in the main seed. These
are call counts, not a predicted elapsed-time reduction.

This evidence does not justify a general Overlay cache: it would burden every
miss, especially in custom profiles with little reuse. The next hypothesis is a
single entry scoped to one ColumnCluster evaluation, with an explicit stable
prior-world contract, invalidation before any write, and original fallback for
other providers. The broader observer spans features and seeds entries on writes;
its hit count is an opportunity estimate for that narrower design. No production
memo is implemented or accepted here.

All 159 ordinary native tests pass, with 45 optional tests ignored. The GPU
inventory was run explicitly twice and passed both times. Observer fields, hooks,
root metadata and fixtures use `cfg(test)`; the normal library has no observer
symbols. Its hash differs from cycle 53, so byte identity is not claimed. The
ordinary rebuilt library also passes the five-case 102,400-chunk corpus against
five references, including the expanded observer. Normal timing uses the suite's
shader-ready policy; the report retains median, p95, maximum, total, throughput,
peak RSS and host observations. No speedup or final no-regression acceptance is
claimed for this diagnostic-only cycle.

All measured Nether observer regions reported shader status 4. Overworld observer
regions span statuses 1 and 4 after five warmups; its counts are not a shader-ready
comparison. Instrumented elapsed time and memory include observation overhead and
are never used as production performance results. Quiet timing confirmation
remains deferred by the user, and the subsecond goal remains open.

Report: `docs/benchmarks/nether-perf-cycle54-2026-10-21.json`. External `cycle54/`
evidence retains immutable executables/libraries, full MCA output, both observer
reports, complete comparisons, host samples and the initial import-only compile
failure. Commands and the next-cycle hypothesis are recorded there.


Cycle 54 post-merge confirmation: PR #196 merged as
`333afdc1846f5c4740f83ecddb12025413ba3230`. The forced rebuild passed 159 native
tests and produced the exact selected normal-library SHA-256
`b756e6d33ded4bc7ef8558be954dd1e0498268124eaea8ed94dd0a517fd6dc71`.
All 102,400 measured chunks matched five references. Actual merged median/p95
observations were 1445/2209 ms (main), 1426/2212 (second seed), 1396/1975 (custom),
1050/1381 (direct stem) and 550/784 (Overworld). All measured regions reported
shader status 4. These remain provisional, and the performance goal remains open.
The owned cycle 54 worktree was removed after preserving its branch and evidence.

## Cycle 55 — held single-entry basalt-column reader

The prototype scopes one exact xyz/material value to a single ColumnCluster
invocation. Only the immutable ordered View opts into stable successful reads;
other PriorWorld implementations retain original calls. Missing values are not
cached, and every possible write clears the entry before provider sampling.
The entry stays on the stack and ends before another feature, snapshot or undo.

All 161 native tests pass. New tests compare every command, overlay material,
return value, RNG state and undo across three origins, three height ranges,
three seeds and temporarily missing values. A stateful-provider test verifies
that two reads of the same coordinate can return different values and reject
placement before consuming RNG.

The live inventory removes 4,312,862 original material lookups per main-seed
region (16.078%) and 4,194,184 in the second seed (15.900%). Only the two basalt
column roots change; other root counts, custom profiles and Overworld counts
remain identical. Each full measured corpus matches the original and preceding
references: 102,400 observer chunks, 327,680 chunks across ordinary ABBA runs,
and 122,880 in the paired follow-up. These repeat the agreed coordinates.

Ordinary ABBA did not establish a useful gain. The same baseline binary drifted
from 1025/1235 to 1458/1732 ms main median/p95 and from 339/406 to 740/951 ms for
Overworld. External build/browser workloads were recorded. Candidate first-leg
slowdowns remain in the report; they are not dismissed as noise.

A closer experiment kept separate baseline/candidate processes resident and
alternated the first engine for each full-region pair. Each engine had five
warmups and its own identical twenty-region history. Only one generated at a
time. All measured regions reported shader status 4; generation finished before
full-NBT comparison. Both workers exited successfully in every case.

| Case | Baseline median/p95 ms | Candidate median/p95 ms | Baseline/candidate chunks/s | Candidate faster pairs |
| --- | ---: | ---: | ---: | ---: |
| Main | 1117 / 1653 | 1132 / 1647 | 905 / 897 | 10 / 20 |
| Second seed | 1712 / 3040 | 1591 / 3548 | 588 / 564 | 8 / 20 |
| Overworld | 277 / 340 | 281 / 347 | 3652 / 3599 | 8 / 20 |

The main paired median ratio is 0.9947; the second seed is 1.0534. The favorable
second-seed median does not outweigh its worse aggregate throughput and tail.
Host activity still limits attribution, and two resident engines differ from a
single-engine acceptance run. No quiet regression or speedup is claimed.

**Held, not selected.** Fewer lookups do not establish a useful whole-region
improvement. Preserve the isolated worktree and immutable libraries for recovery
or a later quiet retest. The prepared concurrency driver was not run; additional
acceptance work is unwarranted until a benefit is demonstrated. Next inspect
version-marker allocation/retirement and prefix-history lookup shape before
choosing another layout change. This remains a hypothesis, not an established
bottleneck, and the held cycle 26 column-state arena must not be reapplied blindly.

Report: `docs/benchmarks/nether-perf-cycle55-2026-10-21.json`. The complete
assessment, paired harness snapshot, process logs, worlds and immutable binaries
remain under external `cycle55/`. Native source is
`7157282f0a9a50cbdbb94b700c25cb6a86067f38`; normal library SHA-256 is
`eb7139784bfe97d83f046febc924a9cc33a0af644240599d1c9cfccbc3f51b67`.


## Cycle 56 — version-marker allocation and retirement inventory

A test-only observer measures actual per-column marker vectors, growth requests,
unused capacity and the number of newer markers skipped by each original prefix
lookup. It neither initializes missing cells nor changes the selected cell. A
joined scan records missing/successful/error markers, and four clock reads split
the existing interior export, exterior export and synchronous Versions drop.
Every hook and observer field is excluded from ordinary builds.

Across five warmups and twenty measured regions per case:

| Case | Allocated vectors/region | Unused marker capacity MB | Queries skipping no newer marker | Interior / exterior / retirement median ms |
| --- | ---: | ---: | ---: | ---: |
| nether-main | 234,228 | 14.51 | 79.8% | 14.6 / 17.4 / 33.7 |
| nether-second-seed | 234,686 | 14.49 | 79.0% | 12.9 / 16.8 / 31.1 |
| nether-custom-bounds | 234,426 | 14.71 | 72.2% | 17.7 / 21.2 / 38.5 |
| nether-direct-stem | 233,418 | 14.76 | 72.4% | 17.9 / 20.8 / 39.1 |

Capacity uses decimal MB and excludes pointees and allocator metadata. The outer
vector headers and base-cell arrays each add 6,291,456 bytes per full region.
Latest-query shares include empty lists. Observed historical queries skipped at
most six newer markers; this is evidence, never permission to truncate history.
About 57% of markers remain uninitialized in the two main-seed cases, compared
with 36–38% in the custom profiles. These missing latest markers are semantically necessary and cannot select older values.

The 31–39 ms median retirement includes ColumnState/Arc destruction, not only
vector metadata. It does not establish allocation as the dominant remaining
bottleneck. A separate page-backed marker-index prototype is justified as a
memory/allocation opportunity, with whole-region benefit unproved. It must retain
original state ownership, strict root ordering, missing cells, all historical
reach, error propagation and ascending snapshot export. C26's held state arena
and C55's held material reader are not selected by this diagnostic cycle.

All 161 native tests pass; 46 optional tests are ignored and the GPU inventory is
explicitly run and passes. The observer's 102,400 chunks match four references;
the ordinary library's 102,400 chunks match five, including the observer. Every
measured region in both runs reports shader status 4. The ordinary binary has no
version_layout_probe symbol. Its hash differs from the preceding build, so byte
identity is not claimed. Timings include external host activity; no speedup,
quiet no-regression verdict or subsecond acceptance is claimed.

Report: `docs/benchmarks/nether-perf-cycle56-2026-10-21.json`. External `cycle56/`
retains source/library hashes, the immutable test executable, normal library,
observer output, original and preceding full-NBT comparisons, ordinary timings,
host samples, reproducible driver and a scoped next-opportunity assessment.


Cycle 56 post-merge confirmation: PR #197 merged as
`d44156efdc5e5adf58e2d1c7f0736516ba1034bc`. A clean ordinary native rebuild
produced the exact selected SHA-256
`a8b40e9d7b8066bb4567b653a7d6b7d9c5e48bf015d19a207f3c72215a9ca007`.
The native tree is identical to the source already covered by 161 passing tests;
those unit tests were not duplicated. Actual merged generation compared all
102,400 measured chunks against the original, preceding merged and selected
ordinary corpora, with exact membership/NBT and shader status 4 throughout.
Provisional median/p95 was 1747/2005 ms (main), 1744/2459 (second seed), 980/1275
(custom), 1014/1307 (direct stem), and 572/770 (Overworld). Same-binary drift from
the earlier 4265 ms custom median illustrates why quiet timing remains deferred.
The performance goal remains open. External `cycle56/post-merge-proof.json`
preserves the source/build/merge proof and full results.


## Cycle 57 — held paged version-marker index

The isolated prototype replaces one marker Vec per column with 1024-entry pages
and per-column usize head indices. Each node retains the original root, inline
ColumnCell and previous-marker index. Append still occurs only at joined root
barriers. Historical lookup has no depth limit and returns the exact qualifying
cell, including missing or failed cells. One reused scratch vector preserves
ascending archive order. Arc<ColumnState> ownership and synchronous retirement
remain unchanged.

All 164 native tests pass; 46 optional tests remain ignored and the GPU inventory
is explicitly run and passes. New tests cover exhaustive small histories, strict
prefix bounds, failed/missing cells, more than 64 historical markers, page edges,
cell-address stability and synchronous release. The inventory's 102,400 chunks
match four complete-NBT references. Marker counts, readiness counts, per-root
counts and query-shape histograms match cycle 56 exactly in all four Nether cases.
These are workload counts; exact output is independently checked.

The marker index saves 14.2–15.0 MB per region. Main-seed marker-vector allocation
and growth requests fall from about 250,897 to 538 physical page allocations.
The page-index Vec's own growth calls are excluded from that count, but its spare
capacity is included in the backing-byte comparison. State backing and allocator
metadata are excluded. This local reduction is well below 1% of roughly 2 GB
process peaks and does not establish lower whole-process retention.

A separate ordinary-library comparison keeps two isolated engines resident and
alternates the first engine for each pair. Each gets five warmups and twenty
identical measured regions per case. Only one generates at a time. All generation
finishes before NBT comparison. All 204,800 measured chunks match the original,
cycle 56 merged, and prototype observer outputs. These repeat the agreed corpus
coordinates. Both workers exit successfully in every case; all measured statuses
are 4. The observer Overworld before-statuses include 1 and 4; its timings are
not used as ordinary performance.

| Case | Baseline median/p95 ms | Prototype median/p95 ms | Baseline/prototype chunks/s | Prototype faster pairs |
| --- | ---: | ---: | ---: | ---: |
| nether-main | 2462 / 3475 | 2389 / 3846 | 393 / 399 | 10 / 20 |
| nether-second-seed | 1816 / 2514 | 1704 / 2256 | 573 / 586 | 11 / 20 |
| nether-custom-bounds | 1447 / 2205 | 1341 / 2212 | 690 / 714 | 13 / 20 |
| nether-direct-stem | 1084 / 1699 | 1070 / 1565 | 868 / 888 | 12 / 20 |
| overworld | 886 / 1114 | 819 / 1138 | 1184 / 1243 | 10 / 20 |

**Held, not selected.** Nether throughput shifts are only 1.5–3.5%, all paired
median ratios remain within 5%, and the main-seed p95 worsens. The Overworld path,
which performs no version-index work in this corpus, shifts 5% in throughput.
Known host activity and two resident engines limit attribution. Preserve every
regression and favorable result; neither proves the prototype cannot help in a
later quiet retest. Process peaks also rise in some cases despite lower live index
backing. No sustained retention or final no-regression claim is made.

The linked historical traversal is linear in skipped markers, unlike the original
binary search. Correctness is tested for long histories, but the observed six-marker
maximum is not a universal performance bound. Further concurrency, retention and
portal acceptance work is deferred until a useful ordinary gain is established.

Return next to the material-read hotspot from the CPU samples. Measure actual
Owned/Shared/Cached read proportions and live dense-versus-RLE storage before
choosing a representation. A dense representation has not been implemented or
selected. Do not keep tuning metadata layouts without whole-region evidence.

Report: `docs/benchmarks/nether-perf-cycle57-2026-10-21.json`. Preserve the owned
`codex/nether-paged-version-markers` branch/worktree and external `cycle57/`
libraries, executable, full worlds, paired harness/logs and assessment. Native
implementation is `d47f6f350f3b541435b9d9bfcd482e33cd32d23b`; normal SHA-256 is
`67f27da6fc599827ad1f63e37b0961057901c17a2bff4cb69d5687bbbb47fdb3`.
Quiet timing confirmation remains deferred and the subsecond goal remains open.


## Cycle 58 — material read backing and live storage inventory

A test-only observer counts every `ColumnState::material` lookup by Owned,
Shared terrain and Cached archive backing. Original RLE results still drive
all generation. Thread-local registered counters record call weights and run
lengths; a joined scan counts existing ready states, distinct backing slices,
mask/packed-run owners and hypothetical full-height dense payloads. It never
initializes missing cells or retains state owners. All hooks, state and symbols
are excluded from ordinary builds; normal ColumnState layout is unchanged.

Five warmups precede twenty measured complete regions per case:

| Case | Material reads/region, million | Owned | Shared terrain | Cached archive | Added Shared dense payload, mean MB |
| --- | ---: | ---: | ---: | ---: | ---: |
| nether-main | 31.18 | 27.4% | 61.6% | 11.1% | 162.7 |
| nether-second-seed | 29.43 | 28.6% | 62.0% | 9.4% | 165.5 |
| nether-custom-bounds | 20.40 | 25.2% | 61.5% | 13.3% | 183.3 |
| nether-direct-stem | 18.03 | 26.9% | 59.7% | 13.5% | 181.3 |

Shared columns contain 8.3–9.0 run words on average (weighted by calls), versus
11.9–12.6 for Owned and 9.7–9.8 for Cached. Shared terrain is the largest read
category, making an Owned-only dense optimization insufficient to address most
calls. Call fractions are not CPU or elapsed-time fractions.

Dense estimates use decimal MB and add one full request-height u16 array per
unique live state while retaining RLE. They exclude extra metadata/allocator
costs. Joined state excludes transient and engine-wide memory. Shared mask
capacity includes metadata, and Cached backing arrays can contain unused spans.
The Overworld inventory records no ColumnState calls or joined planners.

A compact immutable run-boundary index is a next hypothesis: it could replace
repeated binary searches without a full dense array. It must preserve exact RLE
semantics, including uncommon tall layouts, all run boundaries and wrapped or
out-of-range query heights. Index construction, metadata, retirement and cache
behavior must be included in whole-region tests. No index, dense representation
or new cache is implemented or selected here. Held C55 and C57 remain separate.

All 163 native tests pass; 47 optional tests are ignored and the GPU inventory
is explicitly run and passes. The observer's 102,400 measured chunks match four
references. The ordinary library's 102,400 match the immutable original, preceding
C56 merged corpus and observer output. Every measured region reports shader
status 4 in both runs. The ordinary library contains no material_storage_probe
or version_layout_probe symbols. It differs in bytes from C56; no binary identity
or production speedup is claimed. External host work remains visible, so quiet
performance and no-regression confirmation remain deferred.

Report: `docs/benchmarks/nether-perf-cycle58-2026-10-21.json`. External `cycle58/`
retains immutable executables, libraries, source hashes, original and preceding
full-NBT comparisons, per-thread/per-kind counts, backing inventories, ordinary
timings and host observations. This branch also carries C56's actual merged
proof and the held C57 assessment as documentation only.


Cycle 58 post-merge confirmation: PR #198 merged as `542f41a19d0a69023e7cdf94dd88af9de4a9592e`.
A forced actual merged ordinary rebuild has native tree `e4af459f7a1db66578c123b831ce6688754f7464`,
identical to the already tested 163-pass source, and SHA-256
`76cd0a87a3bc589bb8bc3b720a599924018e12f6b30b0047170ebf3a4c264723`, identical to the selected ordinary library.
Native unit tests were not repeated for identical native source. The actual
merged library did generate a fresh complete 102,400-chunk corpus, matching the
original, preceding C56 and selected normal outputs. Every measured status is 4.
Timings remain provisional under recorded host activity; the performance goal
remains open. External `cycle58/post-merge-proof.json` preserves full metrics,
references and rebuild provenance.


## Cycle 59 — held compact material-run rank index

A prototype embeds a 40-byte immutable run-start bitmap/rank index in each
ColumnState. It returns materials from the original runs and retains the original
partition search for unrepresentable layouts and out-of-range heights, including
the existing u32 conversion. Owned, Shared and reconstructed Cached states all
use the index. Archives retain their original packed data; no dense voxel array,
additional per-column allocation or new owner lifetime is introduced.

All 166 native tests pass (47 optional ignored), including exhaustive boundary
pairs, word edges, sparse/dense source materials, tall layouts and wrapped query
heights. The explicit GPU inventory passes. Its 102,400 measured chunks match
the original and C58 merged references. Every observed Nether material lookup
uses the index, and all material-call/run-length counters match C58 exactly.
The joined metadata increase is 24.9–27.2 MB per region, before allocator effects;
it is not process peak or sustained retention.

A separate ordinary comparison alternates baseline/candidate complete regions,
with five warmups and twenty measured regions per engine/case. Both engines
remain resident, but generation is serialized. All measured before/after shader
statuses are 4. All 204,800 measured chunks match three references (original,
C58 merged, and inventory) in full decompressed NBT and membership.

| Case | Median old/new ms | p95 old/new ms | Aggregate throughput change | New faster pairs |
| --- | ---: | ---: | ---: | ---: |
| nether-main | 1061 / 1027 | 1420 / 1479 | +0.06% | 12/20 |
| nether-second-seed | 1316 / 1323 | 1959 / 1883 | -0.55% | 10/20 |
| nether-custom-bounds | 1310 / 1255 | 2303 / 2376 | -2.01% | 8/20 |
| nether-direct-stem | 1982 / 2007 | 2699 / 2692 | -3.04% | 7/20 |
| overworld | 649 / 664 | 852 / 909 | -1.78% | 9/20 |

Hold this version without a PR or merge. Standard-seed throughput is essentially
unchanged; custom profiles show lower throughput and some tails worsen. The
unaffected Overworld control also drifts, limiting attribution without dismissing
those regressions. No useful whole-region improvement or quiet acceptance is
established, and no additional concurrency/lifecycle acceptance run is selected.

A separate synthetic benchmark imports the exact Index source and compares it
with original partition_point reads over six alternating repetitions. For nine
or thirteen run words, indexed lookup medians are roughly 1.4–3.4 ns versus
3.5–7.4 ns across 1/256/32,768 resident columns. One-run lookups are slower, but
none occur in this measured Nether material-read corpus. The benchmark excludes
construction, actual access locality, production object layout and other region
work. Scalar lookup gains therefore do not establish region gains or identify
which other costs offset them.

Next, measure process CPU work versus wall time using the unchanged C58 library.
Prior C53 worker stacks contain substantial Rayon sleep/join observations and
some dependency OnceLock waits; these are not elapsed-time shares or proof of a
scheduler defect. Root-resolved occupancy and long dependency tails require
measurement before choosing another architectural change. Avoid another lookup
micro-optimization based only on leaf samples.

Report: `docs/benchmarks/nether-perf-cycle59-2026-10-21.json`. Preserve
`codex/nether-material-rank-index`, its worktree and external `cycle59/` evidence.
Native implementation is `ca226565a9a025373ac7ac043c3fe7e9ac141417`; normal SHA-256
is `a84b2fc525667f7bbd1b24d2d7cc1db9845c344d6fd44935c7edf96fc59ccf4e`.
Quiet timing confirmation remains deferred and the performance goal stays open.


Cycle 59 resource follow-up: an external copy of the native benchmark adds two
process getrusage snapshots around each complete region. The unchanged C58
ordinary library generates two standard Nether seeds and an Overworld control,
with five warmups and twenty measured regions each. All 61,440 measured chunks
match original and C58 output; measured shader statuses are 4.

Mean process CPU work per region is 4,457 ms for the main seed, 4,269 ms for seed
42 and 1,232 ms for Overworld. Median CPU-time/wall ratios are 2.70, 2.88 and 2.96.
These include native service threads and are not percentages of available cores,
per-root CPU accounting or proof of insufficient parallelism. External activity,
GPU/I/O waits and serial work can all affect the ratio. Reclassified C53 samples
also contain many worker sleep/join stacks, but those are not wall-time shares.

The next diagnostic should combine per-root process CPU intervals with the
in-flight intervals of dispatched anchor tasks, including their last completions.
Count overlapping intervals on one worker only once. This can distinguish a
small set of long remaining tasks from many occupied but blocked/preempted
workers before an architectural scheduling change is selected. External
`cycle59/baseline-cpu-summary.json` and `prior-worker-wait-evidence.json` retain
the evidence and limitations. No production scheduling change is selected.


## Cycle 60 — root CPU phases and dispatched worker occupancy

A test-only observer measures pre-dispatch queueing, target enumeration, joined
anchor evaluation, publication and return handoff. Each phase records wall time
and an optional process CPU delta. Each worker records top-level task spans;
joined summaries union overlapping/nested intervals by physical thread before
integrating occupancy. No collector lock spans generation. Collector buffers
are emptied at every root and retain no planner, world-state or GPU owners.
Missing clocks/phases remain null; anomalous intervals are not silently clipped.

All observer hooks, state and libc clock calls are excluded from ordinary builds.
libc is a dev dependency already present transitively in the lockfile; no package
version changed. Production generation order, errors, publication and layout
remain unchanged. Four tests cover idle gaps, equal/nested spans, root rebinding,
failed roots and missing clocks. All 167 native tests pass (48 optional ignored),
and the explicit GPU inventory passes.

Five warmups and twenty full lit regions per case produce these instrumented
observations. The last column integrates disjoint root evaluation windows:

| Case | Evaluation sum/region, median ms | Publication sum/region, median ms | Evaluation time with at most 2 workers carrying tasks |
| --- | ---: | ---: | ---: |
| nether-main | 902.2 | 56.3 | 35.6% |
| nether-second-seed | 981.4 | 57.6 | 37.6% |
| nether-custom-bounds | 420.8 | 48.7 | 21.1% |
| nether-direct-stem | 580.3 | 58.6 | 28.2% |

Standard-seed brown-mushroom longest tasks are outside the requested region in
20/20 regions per seed. Their median duration is 90–94% of the root evaluation
interval and their median start is within its first 5–7%. Large ancient-debris
longest tasks are also exterior in every measured standard-seed region. Many
other long roots have exterior tails. This points toward recursive exterior
prior-state work; simply starting these already-early tasks sooner is unlikely
to remove most of their cost. Previous broad frontier/graph/checkpoint attempts
must be considered before choosing a new exact dependency design.

Basalt pillars show a different pattern: about 13.6 workers carry tasks while
process CPU/wall is about 0.8. This is consistent with waiting, but it does not
separate GPU, dependency blocking, preemption or observer effects. Occupancy is
not actual CPU execution. Process CPU also includes native service/shader threads.
Task/CPU interval sums must not be presented as elapsed-time percentages.

Observer summaries add median 15–16 ms per standard-seed region and 9–10 ms for
custom profiles; they remain inside whole-region elapsed time. Setup and final
structure/distribution/retirement are outside the root phase sums. The observer
records no ordered roots in this Overworld control.

The instrumented 102,400 measured chunks match the immutable original and C58
merged corpus. A separate ordinary library generates another 102,400 matching
the original, C58 and observer output. All measured shader statuses are 4. Normal
symbol inspection finds no worker_phase_probe, material_storage_probe or
version_layout_probe. Normal library bytes differ from C58; no binary identity,
production speedup or quiet acceptance is claimed. External work remains visible.

Report: `docs/benchmarks/nether-perf-cycle60-2026-10-21.json`. External `cycle60/`
retains full per-root summaries, top-eight long tasks, occupancy histograms,
clock/phase data, immutable binaries, provenance, full NBT and ordinary timings.
The branch also carries C58's actual merged proof and held C59 measurements as
documentation only. The held material-rank index is not included. No production
scheduler change is selected in this cycle. The performance goal remains open.


Cycle 60 post-merge confirmation: PR #199 merged as `60e210d1b0e459955aec2007d8231ee4358bed88`.
The forced actual merged rebuild has native tree `ad1cf17fb7670493243cba71adfc6d74d1653161`,
identical to the tested 167-pass source, and ordinary SHA-256
`15f8f4ef043ed5a963b52c74a2a60963dc570c8ae19f10b560cdf4757240eb27`, identical to the selected library.
Native unit tests were not repeated for identical source. The actual merged
library generated a fresh complete 102,400-chunk corpus matching original, C58
and selected normal output in full NBT and membership; all measured statuses
are 4. Same-binary timing drift is retained explicitly, including a custom-bound
median of 2,156 ms versus 976 ms earlier. This cannot establish a code effect or
identify the cause. Quiet timing and the subsecond goal remain open. External
`cycle60/post-merge-proof.json` and `same-binary-timing-drift.json` retain evidence.


## Cycle 61 — held exterior simple-block column preparation

This prototype discovers exact exterior columns read by a simple-block root's
terminal material filters, then prepares their original before-root state in
parallel before ordinary anchor replay. Eligibility requires SimpleBlock-only
writes, unique placement salts, a pure placement prefix and one final material
predicate with zero offset. Original expansion, lazy Queue and position logic
are reused; the helper stops before the filter and supplies no dummy materials.
A column's first in-height candidate must query prior state before any local
simple-block write can satisfy a later filter. Optional work is bounded at 256
anchors and 32,768 candidates; all other generation retains its full fallback.

All 170 native tests pass (48 optional ignored). Three new tests cover actual
prior-read equality with live/paired writes, duplicate and streamed candidates,
biome rejection, missing materials, clipping, negative coordinates, budgeted
subsets, complete bounds and ineligible shapes. An initial test fixture used a
four-chunk field with one-anchor IDs; it was corrected to the production
single-anchor geometry, with the failed log and source retained.

The explicit GPU observer passes and its 102,400 measured chunks match original
and C60 merged output. Separate counter controls compare 10,240 measured chunks
with both references. Ordinary alternating runs use five warmups and twenty
measured regions per variant/case. All 204,800 measured chunks match original,
C60 and observer full NBT and membership. Every measured shader status is 4.

| Case | Median old/new ms | p95 old/new ms | Aggregate throughput change | New faster pairs |
| --- | ---: | ---: | ---: | ---: |
| nether-main | 1079 / 1028 | 1358 / 1356 | +3.79% | 14/20 |
| nether-second-seed | 1331 / 1369 | 1814 / 1951 | -4.78% | 7/20 |
| nether-custom-bounds | 903 / 864 | 1669 / 1472 | +6.92% | 15/20 |
| nether-direct-stem | 1075 / 1096 | 1400 / 1242 | +2.84% | 10/20 |
| overworld | 551 / 537 | 619 / 645 | +0.20% | 9/20 |

Hold this prototype without a PR or merge. The main seed's apparent throughput
gain is smaller than the gain in an inactive custom-profile control, while the
second seed loses throughput and worsens p95. These results do not justify
production selection, and external activity must not be used to dismiss them.

Mushroom anchor replay itself falls to about 3 ms, but preparation absorbs the
work. Counting both phases, brown mushrooms measure 63.21 ms versus 58.74 ms in
the earlier main-seed observer and 45.85 versus 45.33 ms for seed 42. These are
separate instrumented runs, not matched ordinary timing. The new preparation
phase remains inside complete-region elapsed time.

The counter controls preserve every anchor, column and prefix build count.
Main prepares 7,814 columns; seed 42 prepares 7,906. Seed 42 generates 8,627
terrain chunks versus 8,529 in its control, so scheduling can alter cache work.
Custom profiles and Overworld prepare zero columns. Overlapping wait counters
cannot be summed or converted into elapsed-time shares.

The remaining hypothesis is that many column requests converge on an earlier
anchor whose prerequisites still run serially. A bounded queue of exact missing
prerequisites, with helpers restricted to strictly earlier dependency levels,
could expose that work without replay or arbitrary task stealing. This requires
explicit single-owner, failure/panic, lost-wakeup, cycle, completion-ownership
and memory validation. It is not implemented or selected here.

Report: `docs/benchmarks/nether-perf-cycle61-2026-10-21.json`. Preserve
`codex/nether-simple-block-column-preparation` and external `cycle61/` evidence.
The branch also carries C60's actual merged proof as documentation only. Quiet
acceptance and the performance goal remain open; extra concurrency/lifecycle
acceptance is not run for this unselected prototype.


## Cycle 62 — held cooperative dependency queue

C62 exposes exact unfinished writer siblings through a job-owned queue of at most 4,096 keys. A worker waiting on a column or anchor can help with strictly earlier anchors; results are still consumed in source order. Cells retain one initializer and cache the original result/error. Completion, new work and abandoned initializers wake waiters through the same queue mutex. Helpers run synchronously; queued keys retain no cell owners and root completion still joins before publication. No halo, feature replay, worker-count or generation-semantic change was introduced.

The prototype passed **174 native tests**, including seven queue/ownership/wakeup/panic tests, and the explicit GPU terrain-parity test. Full decompressed NBT and membership matched across **317,440 measured chunk instances**: 102,400 observer, 10,240 counter-control and 204,800 paired ordinary instances. The original corpus and actual C60 merge were used throughout; ordinary pairs also matched C62 observer output. All measured program snapshots were ready (status 4). Repeated coordinates are not unique-world coverage. The initial test compile required an explicit panic-fixture result type; the failed log and source remain preserved.

Anchor, column, prefix and column-tile build counts stayed exactly unchanged in all five counter controls. The queue picked up roughly 9,000–11,000 descriptors per Nether case; those include already-ready/in-flight keys. Column cells grew **24 → 32 bytes**, adding about 38–41 MiB for the boundary slots alone in these cases, plus interior/version/anchor storage. Joined helper execution is included in join counters, so those counters cannot be read as idle time.

Paired ordinary libraries used five warmups and twenty measured regions per case, alternating which process generated first. Only one generated at a time. Known external load makes these provisional comparisons; quiet confirmation remains deferred.

| Case | Old/new median ms | Old/new p95 ms | Throughput change | New faster pairs |
|---|---:|---:|---:|---:|
| nether-main | 1122.02 / 1100.61 | 1804.64 / 1542.67 | +3.39% | 10/20 |
| nether-second-seed | 1497.19 / 1466.41 | 1922.85 / 2344.73 | -8.22% | 8/20 |
| nether-custom-bounds | 1019.44 / 980.44 | 1344.09 / 1229.98 | +5.01% | 13/20 |
| nether-direct-stem | 927.41 / 973.61 | 1114.12 / 1222.88 | -3.62% | 9/20 |
| overworld | 482.02 / 475.21 | 651.53 / 615.96 | +1.37% | 9/20 |

**Held, unmerged.** The second seed and direct-stem case regress, so these results do not select the scheduler or its larger cells. Main remains C60. No reverse/concurrent/portal/retention acceptance was added for this unselected prototype.

A source-level gap remains: only contending workers help. Finished outer tasks return their workers to Rayon, so they cannot drain this queue during a final long anchor. The next distinct experiment can expose bounded scoped drain tasks to idle workers, preserving lifetime/ownership and joined failure behavior. Permanently waiting helper loops are unsuitable because nested pool joins can starve unstarted child tasks. The test observer must count helper spans separately and union them by physical thread. This is a hypothesis to test, not a claimed speedup.

Report: `docs/benchmarks/nether-perf-cycle62-2026-10-21.json`. Preserve `codex/nether-dependency-work-queue` and external `cycle62/` evidence, including the held binary and exact reproduction drivers.

## Cycle 63 — held scoped dependency helpers

C63 lets idle pool workers drain exact queued prerequisites. A per-root Rayon scope joins all helpers before publication or failure cleanup. Typed Evaluation references preserve planner/scope lifetimes without unsafe casts. Permits bound queued/running drain tasks to the existing pool size; enqueue and last-helper retirement use the same mutex. Drains return on no eligible work. A per-thread stack of job identities and owned dependency levels prevents a helper inherited at a nested join from executing its own suspended ancestor. Temporary writer batches are bounded to 32 positions, with every original writer still consumed in source order.

The prototype passed **181 native tests**, including 13 queue/scoping/ownership/wakeup/panic tests, and explicit GPU terrain parity. Full decompressed NBT and membership matched across **317,440 measured chunk instances**: 102,400 observer, 10,240 counter-control and 204,800 paired ordinary instances. All compare to the immutable original and actual C60 merge; pairs also compare to C63 observer output. Observer/pairs persisted ready snapshots before and after every measurement; counter measurements persisted ready post-snapshots. Repeated coordinates are not unique-world coverage. An initial test-only mpsc Receiver ownership compile error was corrected; its source/log remain preserved.

Anchor, column, prefix and column-tile build counts remain exactly unchanged. Scoped tasks processed 5,401–6,965 descriptors per Nether counter case in 3,265–4,628 tasks. Those counts include ready/in-flight keys and do not establish how many new initializers helpers owned. The second seed generated 8,529 → 8,627 terrain chunks (20 → 22 tiles); other case terrain counts were unchanged. Column cells remain **32 bytes versus 24** in accepted C60.

The test observer now counts target/helper spans separately, unions both by physical thread, and keeps target completion percentiles/longest targets separate from helper spans. Brown mushroom roots still had median tails of about 80 ms and 57 ms after 95% of targets finished. More tasks in flight can be more blocked waiters, not more CPU execution. Independent observer runs and process-CPU sums are not causal performance comparisons.

Paired ordinary libraries used five warmups and twenty measured regions per case, alternating first generator with only one generating at a time. Known host load makes these provisional; quiet confirmation remains deferred.

| Case | Old/new median ms | Old/new p95 ms | Throughput change | New faster pairs |
|---|---:|---:|---:|---:|
| nether-main | 2313.34 / 2078.87 | 4030.89 / 4641.19 | +5.04% | 11/20 |
| nether-second-seed | 2669.22 / 2714.01 | 4120.17 / 4302.09 | -3.48% | 6/20 |
| nether-custom-bounds | 1574.66 / 1649.05 | 2352.31 / 2584.17 | -5.25% | 6/20 |
| nether-direct-stem | 1841.12 / 1915.62 | 3360.17 / 2775.44 | +10.78% | 12/20 |
| overworld | 575.76 / 547.25 | 673.75 / 693.01 | +4.76% | 12/20 |

**Held, unmerged.** Main has a worse p95 despite a lower median; the second seed and custom-bounds case lose throughput. These observations do not select the change. Main remains C60; no reverse/concurrent/portal/retention acceptance was added for this unselected prototype.

Optional drains currently wait on already-owned keys through the ordinary anchor path. The next distinct candidate should try nonblocking optional claims and directly count ready/busy/owned outcomes. Original consumers must still resolve every writer, and newly claimed helpers retain necessary lower-level dependency waits. This also tests whether unnecessary optional waiters amplify the shared wake/recheck cost; that cause is currently a source-grounded hypothesis, not a measured wall-time share.

Report: `docs/benchmarks/nether-perf-cycle63-2026-10-21.json`. Preserve `codex/nether-scoped-dependency-help`, external `cycle63/`, and the held native binary/source. The full performance goal remains open.
