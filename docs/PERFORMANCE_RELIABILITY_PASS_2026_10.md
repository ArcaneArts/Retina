# Performance and reliability implementation pass

This ledger tracks the eleven opportunities authorized on 2026-10-28. Each
cycle uses its own worktree, PR and squash auto-merge. Diagnostic findings do
not establish a speedup; unresolved opportunities remain open. Current
baseline: `24a1ad4` (Fabric/NeoForge bundled vanilla catalogs).

| Goal item | Status | Acceptance still needed |
|---|---|---|
| 1. Buffer biome-cache serialization | Merged: [PR #321](https://github.com/ArcaneArts/Retina/pull/321), `ab0248f3` | Complete |
| 2. Persist outside shared locks | Merged: [PR #322](https://github.com/ArcaneArts/Retina/pull/322), `c4bd97d8` | Complete |
| 3. Retire world GPU resources | Merged: [PR #324](https://github.com/ArcaneArts/Retina/pull/324), `9d674fb5` | Complete; integrated platform acceptance remains below |
| 4. Cancel abandoned admissions | Merged: [PR #325](https://github.com/ArcaneArts/Retina/pull/325), `1ea0b77f` | Complete |
| 5. Reuse concurrent Nether dependencies | Merged: [PR #326](https://github.com/ArcaneArts/Retina/pull/326), `456257d3` | Complete |
| 6. Optimize material counting | Resolved: trials rejected; [PR #327](https://github.com/ArcaneArts/Retina/pull/327), `382a4e84` | No production optimization selected; evidence and boundary regression validated |
| 7. Reduce catalog overhead | 7a merged [PR #328](https://github.com/ArcaneArts/Retina/pull/328), `841cc5b2`; 7b merged [PR #329](https://github.com/ArcaneArts/Retina/pull/329), `da267c93`; 7c pending | Repeated validation/reopening investigation |
| 8. Separate registry tooling/build inspection | Pending | Actual task execution and Fabric/NeoForge/Bukkit JAR inspection |
| 9. Improve Bukkit chunk transfer | Pending | Phase/allocation profile, public API and lifecycle fidelity |
| 10. Improve mixed-section installation | Pending | Actual server timing and every-block/biome comparisons |
| 11. Bound MCA publication buffering | Pending | Partial/preview/resume/failure/durability and memory evidence |
| Integrated acceptance | Pending | Relevant Fabric/NeoForge/Bukkit/CLI paths in all dimensions |

## Cycle 1: buffered biome-cache serialization

An 8 KiB buffer between `DataOutputStream` and `GZIPOutputStream` batches the
roughly one million two-byte writes in a full 16,384-tile cache. The wire format,
atomic replacement, synchronization, failure behavior and loader are unchanged.
Moving persistence outside locks belongs to cycle 2.

The actual baseline and candidate source classes were compiled separately and
run in ABBA order. Each process used five warmups and twenty measurements for
each workload: 40 measured saves per implementation/workload. Both use the same
full cache, deterministic seed 42, 64 biome IDs, JDK 25, 128/256 MiB initial/max
heap, and two active processors. Timing includes temporary-file creation,
serialization/compression, closing, and atomic replacement. Every output was
compared byte-for-byte after decompression with an independently encoded RBC1
payload. Each process also reopened each workload and compared every tile.

| Workload | Baseline median / p95 ms | Candidate median / p95 ms |
|---|---:|---:|
| Uniform ID within each tile | 210.688 / 216.458 | 28.360 / 28.891 |
| Random IDs within each tile | 306.567 / 313.701 | 118.661 / 120.908 |

This is a serialization microbenchmark on an Apple M4 Max, not a server tick,
chunk throughput or GPU speedup claim. Background Finder/FSEvents CPU activity
was present. No game/server was stopped and no GPU workload was launched.

Validation: `./gradlew :bukkit:test -x :packageNative -PretinaHostOnly
--no-watch-fs --console=plain` passed 13 tests; three explicitly optional native
integration tests were skipped. Native packaging was excluded because this
cycle changes only JDK serialization. Existing restart, corrupt-file, capacity,
read/write failure and close tests passed. `git diff --check` passed.

Evidence is retained outside Git at
`/Users/cyberpwn/.cache/retina/performance-pass-20261028/cycle1/`:
`run.py`, `BiomeSaveBenchmark.java`, baseline/candidate source and class files,
raw JSONL runs, `results.json` (source hashes and environment), test XML and
Gradle log. Reproduce with `python3 <evidence-directory>/run.py` from this
repository. No native image is involved in this benchmark.

Later cycles must preserve current End support, generation ordering, numerical
behavior, saved definition authority, player edits and metadata fidelity.
Performance acceptance requires representative overall benefit, not only a
faster isolated stage. An opportunity rejected by experiments needs an explicit
negative-evidence record; it must not disappear from this ledger.

## Cycle 2: asynchronous checkpoints

Save events enqueue a coalesced checkpoint outside the repository monitor.
Two daemon writers snapshot data only when running, so there are at most two
full snapshots and one queued task/receipt per cache. Repeated requests share a
read-only completion stage without accumulating future observers. A successful
write records the captured revision; updates during it remain dirty. Close
rejects new updates, requests the final checkpoint, waits without the cache
monitor, and coordinates concurrent closers. World owner closure still drains
its caches before closing native resources. Optional I/O failures warn and leave
an open cache dirty for a later retry; shutdown makes its final best-effort flush.

New unit coverage includes 10,000 coalesced events, requests during a blocked
checkpoint, observer cancellation, worker/scheduling failures, retry after an
actual filesystem failure, concurrent updates, final restoration, and concurrent
close. The initial JDK-only run passed 19 tests with three optional native tests
skipped. The final run with `-PretinaNativePath` pointing to the retained host
image passed all 22 tests with zero skips/failures, including actual native chunk
and biome parity and reopening tests. `:bukkit:shadowJar :bukkit:serverProbeJar`
built successfully, reusing the unchanged native image with `-x :buildNative
-PretinaHostOnly --no-watch-fs`. `git diff --check` passed. Actual JAR inspection
confirms no server-test/QA classes ship in the plugin.

### Actual CraftBukkit observations

The final control/candidate JARs contain identical resources and host native
image; actual entry comparison confines differences to Bukkit class files.
Control restores the pre-cycle Java adapter classes and compiles the buffered
cache from `ab0248f3`. Native Rust sources are unchanged since the retained
validated image: macOS ARM64 SHA-256
`83fd7bf0c42f84636bbc430414e9ab1d98aa3ec6c67b695943508507c14be2a2`.
This cycle's candidate was built in host-only mode with that image reused,
without claiming a fresh six-platform native build.

Four fresh dedicated JVMs ran ABBA against separate copies of the same existing
world, seed `-9223372036854775767`, JDK 25, Apple M4 Max/macOS, four active
processors and 2 GiB max heap. Each process ran five warmups and twenty measured
iterations with 8,241 actual Overworld biome tiles. The disposable probe dirties
an existing tile without changing its answers, invokes the real save listener
while another thread performs 1,000 provider callbacks, awaits checkpoint
completion, and separately measures `World.save()` with a dirty cache. Each
label therefore has forty measured iterations. New terrain generation is not
part of these latency measurements.

| Scope | Control median / p95 ms | Candidate median / p95 ms |
|---|---:|---:|
| Save listener callback | 20.965 / 21.519 | 0.011 / 0.017 |
| Worst biome callback in each trial | 20.970 / 21.523 | 0.002 / 0.023 |
| World.save return | 21.498 / 21.900 | 0.553 / 0.766 |

The persistence work still occurs; these measure reduced caller stalls, not
increased terrain throughput or equivalent reductions in CPU/I/O work. All
checkpoints were explicitly drained between measurements. Six actual-server
runs (four final timing runs, pending-write lifecycle, then restart) preserved
identical public terrain captures, covering 917,504 blocks and 14,336 quart
biomes across all three dimensions, including the template's player edits.
Decompressed capture SHA-256:
`71a3d69e8c28563c13a6f1e53d2c450c515ecc3dbf807786c23a172644017c55`.

For the lifecycle test, the observer occupies both save workers, queues an
actual cache save, then unloads a custom End world or disables the plugin. A
separate delayed release permits the writers to proceed; returning from close
before release fails the probe. Both cases passed. Concurrent End callbacks
completed or were correctly rejected during unload (13 completed, 19 rejected),
stale generators were rejected after recreation, and native owners closed.
Restart with materialization disabled restored 8,242 Overworld, 49 Nether,
100 default-End and 16 custom-End tiles. Overworld startup reused 8,192 tiles
with zero backing-cache misses. Every server exited normally.

Evidence: `/Users/cyberpwn/.cache/retina/performance-pass-20261028/cycle2/`.
Final ABBA samples and exact artifact entry hashes are in `aligned/`; earlier
exploratory runs remain separately in the parent directory. Lifecycle/restart
JSON, logs, world copies, original artifacts and unit-test XML are retained.
`SaveProbe` is server-test-only and absent from the installable plugin. Commands
are `retinaprobe save-benchmark`, `retinaprobe reopen-end pending-save`, and
`retinaprobe disable-retina pending-save`; use only disposable test worlds.
The retained Python drivers document complete launch/clone/command sequences.

## Cycle 3a: world-close resource characterization

Merged as [PR #323](https://github.com/ArcaneArts/Retina/pull/323), `ba64632a`.
Cycle 3b implements retirement in its own worktree.

The explicit ignored `closed_world_resource_inventory` GPU test binds the actual
vanilla catalog at seed 42 twelve times on one engine. Each cycle queries an
Overworld chunk's columns, drops the session while retaining a dimension lease,
verifies identical columns and a live profile, then releases the lease and
verifies that stale profile access fails. IDs never repeat. The existing GPU
buffer census records logical owners after completed submissions; this does not
estimate physical device residency. No production code changes in this subcycle.

Actual Metal baseline at `c4bd97d8`: registration slots rise from 3 to 36, timing
entries from 1 to 12, and logical profile buffers from 6 to 39. Their bytes rise
from 1,113,964 to 13,357,184: exactly 1,113,020 additional bytes per reopening,
including a constant shared default profile. These remain after world closure.
RSS stays approximately 574–575 MiB in this short run; a proportional physical
memory increase is not established. Interpreter mode isolates profile ownership
from per-profile specialized shader compilation. The explicit GPU test passed
and the entire native test crate compiled; the unrelated full suite was not run.

Evidence and the immutable baseline test binary are retained in
`/Users/cyberpwn/.cache/retina/performance-pass-20261028/cycle3/baseline/`.
`identity.json` records source base, binary hash, backend and exact invocation;
`inventory.json` contains all twelve logical buffer inventories and RSS samples.
The VM-map report records actual process memory after the final close.

Cycle 3 remains incomplete. The implementation must retire registration entries,
GPU profile buffers/plans, feature-count and lighting profiles, diagnostics and
CPU cache entries after their owners/jobs drain. Shared compiler/interpreter
reuse must remain bounded, partial-binding failure must clean up, and tests must
cover multiple live worlds, in-flight leases, reopening and memory stabilization.
This characterization is not acceptance of resource retirement.

### Cycle 3b initial retirement evidence (unmerged)

The initial implementation removes registrations and per-profile CPU/GPU state
after the last world lease releases, using a GPU-worker drain barrier. Diagnostic
reads no longer recreate retired timing entries. The native test crate compiles.
The twelve-cycle interpreter probe passes (1.75 seconds): each close leaves zero
registrations and timing entries, and only the three shared default profile
buffers totaling 944 bytes, versus 39 buffers / 13,357,184 bytes at the final
baseline cycle. Candidate RSS rises from 583,824 to 585,152 KiB in this short run;
this is not evidence of a proportional physical-memory saving.

Evidence, exact uncommitted implementation patch, binary and hashes are retained
in `cycle3/candidate-initial/` beneath the evidence root above. This is preliminary
interpreter-path evidence only. Bounded compiler/interpreter reuse, multiple live
worlds, partial initialization, concurrent generation, auxiliary-resource coverage
and full generation parity remain required before merging this cycle.

The next candidate bounds each shared pipeline cache to eight recently used
strong references, with exact-key weak lookup preserving sharing for live world
owners. Evicted compilation jobs hold weak ownership and are skipped when no
owner remains. Two cache eviction/ownership tests pass. The native crate compiles,
and both explicit Metal lifecycle tests pass (2.22 seconds): repeated closure,
lease-held generation after host-owner release, unaffected generation in a second
world with another seed, decoded-definition survival, and cleanup after exhausting
profile handles partway through binding. Artifacts and source identities are in
`cycle3/candidate-cache/`. Full chunk parity, specialized compilation lifecycle,
auxiliary-resource coverage and broader native tests remain pending.

After making the FFI timing/performance snapshots non-creating as well, the full
ordinary native suite passed: 355 passed, zero failed, 86 explicitly ignored
(2.86 seconds after compilation). Log: `cycle3/native-suite.log`. This does not
substitute for the remaining opt-in GPU and complete generation acceptance.

Full generation checks now pass on Metal. Eighteen complete chunk NBT captures
(two locations in each dimension, reopening in interpreter/automatic/specialized
modes) match the retained baseline library byte-for-byte. Three full-region
reopen cycles cover all dimensions: all 9,216 decompressed chunk records match
the baseline exactly, without normalizing metadata. Lighting allocates six
world-owned buffers and releases all six on every close; feature/provider world
buffers also return to zero. Shared compiler entries stay at two across cycles.
The full-region test passed in 4.44 seconds; this is correctness evidence, not
a matched throughput claim. Existing rejected-specialization GPU coverage also
passes. Evidence: `cycle3/full-chunk-{baseline,candidate}/`,
`cycle3/full-region-{baseline,candidate}/`, both `*-comparison.json` files and
the retained ctypes drivers. Production-library build and matched performance
acceptance are still pending before this cycle's PR.

### Cycle 3b production validation

The production macOS ARM64 library builds successfully. Bukkit tests against
that exact image pass all 22 tests with zero failures/skips, including native
chunk/biome parity and world reopening. Native image SHA-256: `329573fdbe9c2f740439ac797222293c66788219400bb9086692618f31e384a8`.

Four fresh ctypes host processes ran baseline/candidate/candidate/baseline. Each
kept one world open, with two warmup and four measured full regions per dimension
(eight measurements per implementation/dimension). The native call includes
region generation and publication; parsing, binding and output comparison are
outside that timer. Seed 42, automatic execution, zlib, shipping thread defaults,
Apple M4 Max/Metal. All 55,296 compared decompressed chunk records are identical.

| Dimension | Baseline median ms | Candidate median ms |
|---|---:|---:|
| Overworld | 182.324 | 183.125 |
| Nether | 518.124 | 524.093 |
| End | 89.737 | 91.551 |

These are memory-lifecycle changes, not a throughput improvement. Median shifts
are +0.4%, +1.2% and +2.0%; the first candidate run matches or improves its
adjacent control while both later runs slow down. The small sample and host
variation do not establish a systematic throughput regression or a speedup.
Detailed process-level samples, native identities and complete MCA files are in
`cycle3/region-abba/`; the serial driver is `cycle3/run_region_benchmark.py`.
No other backend is claimed validated. Broad installed-platform validation
remains part of final integrated acceptance.

## Cycle 4: queued-work cancellation (in progress)

The existing preview close path retires entries but leaves their admission
tickets queued; those waiters then generate regions before shutdown can finish.
The first implementation adds cancellation before a ticket is claimed, removes
queued tickets, wakes waiters, and preserves claimed permits until their owner
releases them. Preview closure cancels its tickets. Cancelled preparation now
completes its future and releases its lease without incrementing native failure
metrics; the final drain excludes expected cancellation from generation errors.

`regionPreparationTest` and `compileJava` pass. Focused coverage includes
pre-queue cancellation, promotion followed by cancellation, surviving requests,
idempotent release/cancel and 200 cancellation/dispatch races. One observed
queued cancellation completed in 0.117 ms while the active permit remained held;
this is a scheduler observation, not a server shutdown benchmark. Evidence:
`/Users/cyberpwn/.cache/retina/performance-pass-20261028/cycle4/initial-tests.log`.
The unload observer now accepts explicit cancellation as an expected close result.
Direct publication admission, individual background-task cancellation with shared
foreground interest, and actual server unload/shutdown validation remain open.
This cycle is not ready to merge.

Direct publication requests now use an owner-scoped admission group. I/O-worker
close stops new/queued admission before waiting for its serialized shutdown
barrier, while claimed permits drain normally. Tests verify that closing one
scope cancels its waiter and rejects new requests without freeing its running
permit or affecting another world's request. Scheduler tests and common Java
compilation pass (`cycle4/scope-tests.log`). The metrics test also passes: expected
cancellation balances activity without counting terrain or failures
(`cycle4/metrics-tests.log`). The existing Fabric dedicated unload scenario has
been launched in the disposable `cycle4/fabric-unload/` world; its result is not
yet recorded. Individual background-task/shared-interest cancellation remains
unimplemented and required before this cycle can merge.

Fabric dedicated unload and process-reopen runs both pass. Each lifetime closes
storage with eight native jobs active, settles 19 request observers per tested
dimension (Overworld/Nether), removes preview directories, records zero generation
failures, and closes the saved-definition owner only after native work drains.
The second lifetime verifies the fixture's saved edits. Logs and extracted QA
events: `cycle4/fabric-unload-{1,2}.log` and corresponding `*-events.json`.
`world-close-candidate.patch` and `world-close-identity.json` preserve the exact
intermediate source/native identity. These runs establish orderly close/reopen
behavior, not a measured reduction in unload latency; comparative cancellation
counts/timings and shared background-task cancellation remain required.

Shared preparation now tracks observer interest separately from publication
ownership. Cancelling the last observer signals its preview admission; cancelling
a background observer leaves a shared foreground request active. Capacity remains
owned until an abandoned preparer actually stops, and already-enqueued reads keep
their leases until completion. Preview entries count live interests separately
from physical lease pins; a new caller waits for cancelled-entry cleanup before
reusing its paths. The ahead pump cancels outstanding observers when stopped and
does not report expected cancellation as a generation failure.

Scheduler and compile checks pass (`cycle4/shared-tests.log`). The actual Metal
preview integration test also passes (`cycle4/shared-preview-integration.log`):
with every native permit held, abandoned preview admission disappears without
generating terrain; a concurrent same-region replacement safely regenerates once;
and shared foreground interest survives background cancellation and generates
exactly once. Existing preview coalescing, eviction, promotion, player-edit
preservation, active-close and failure-cleanup tests remain passing. NeoForge
dedicated unload validation of this new candidate is running; no result claimed
yet. Comparative unload/admission timing and final loader checks remain open.

The first NeoForge dedicated shutdown run passes with eight native jobs active
at storage close, both dimensions' observers settled, zero generation failures,
preview cleanup and saved-definition release after drain. The process-reopen
check is now running. Source identity and patch are retained in
`cycle4/shared-cancellation-identity.json` and `shared-cancellation-candidate.patch`
(with the new `RegionCancellation.java` stored alongside).

NeoForge process reopening also passes: both saved edits survive, eight native
jobs drain at shutdown, both preview directories are removed and no generation
failures occur (`cycle4/neoforge-unload-2.log` and extracted events). Comparative
cancellation timing and final loader checks of this completed implementation
remain before PR submission.

### Cycle 4 comparative shutdown evidence

Four fresh Fabric processes ran baseline/candidate/candidate/baseline against
seed-42 disposable worlds, using the same QA observer, JDK 25, Apple M4 Max/Metal,
shipping concurrency settings, and unchanged cycle-3 production native library.
At the first close callback, each run had eight running jobs and 24 queued
admissions. Both controls completed 32 regions after closure began; both
candidates completed only the eight already running, cancelling the 24 waiters.
There were zero generation failures in all four runs.

The maximum dimension callback-to-cleanup duration was 6,626/7,022 ms for the
controls and 1,409/1,525 ms for candidates: medians 6,824 versus 1,467 ms.
This measures this saturated shutdown workload, not total JVM shutdown or terrain
throughput. There are only two samples per implementation; no p95 is claimed.
Raw events, commands, logs and results are in `cycle4/unload-abba/` under the
evidence root; `run_unload_abba.py` retains the serial driver.

Initial End runs exposed a QA assumption, not a generation exception: the small
workload could finish before shutdown, and the larger workload could drain after
the loader unload callback but before storage closure. The observer now records
both boundaries and still requires active generation at at least one of them.
The End scenario queues a larger real workload; no generator delays or stubs are
introduced. The external driver rejects any failed QA event and requires both
lifecycle completion and final drain, rather than trusting exit status alone.
Original failed attempts are retained alongside the corrected runs. Final End
loader outcomes and PR delivery remain to be recorded.

Final End acceptance passes for fresh creation and process reopening on both
Fabric and NeoForge. Each run settles 131 observers, drains eight preview regions
already running after the unload callback, removes its preview directory and
records zero generation failures or failed QA events. Fabric observes overlap at
loader unload; NeoForge observes it at both hooks. Saved-edit checks pass on
reopening. Evidence: `cycle4/*-end-unload-boundary-{1,2}.log` and corresponding
extracted event files, driven by `run_end_loaders_boundary.py`.

The final lightweight scheduler/race tests and metrics tests pass
(`cycle4/final-scheduler-tests.log`), as does `git diff --check`. Earlier actual
Metal preview and Overworld/Nether loader acceptance remains applicable: the
final changes only correct the opt-in End QA workload and overlap observation.
No Rust or shader source changes occur in this cycle.

## Cycle 5: concurrent dependency reuse (in progress)

The cycle-202 investigation and prototype `de51a97` remain relevant on current
main: a fresh region captures completed neighbor snapshots once, before feature
roots execute. A trial applies the prototype to `1ea0b77f`, refreshing only
between joined roots in both planner paths. It tries the cache lock without
waiting, adds only completed snapshots with matching context and root count,
retains existing owners, and bounds captured snapshots to eight / 512 MiB.
No partial planner state is published or shared and dependency misses retain the
original recursive path. This is an experimental candidate, not an accepted
optimization or measured gain.

The initial validation compiles the native cache unit tests, including late
neighbor capture, owner lifetime, context/root/neighborhood filtering and capture
bounds. A saved-definition-backed driver is prepared for adjacent-region batches
at 1/2/4/8 callers. It records whole-process CPU, wall time, individual call
latencies, peak RSS and existing dependency counters; outside the timing interval
it hashes every decompressed chunk payload, including metadata. Evidence lives
at `/Users/cyberpwn/.cache/retina/performance-pass-20261028/cycle5/`.
Build/test results and comparative generation acceptance remain pending.

The release-mode cache unit suite passes all ten tests, including both new refresh
tests and existing exact-before-root, missing-state, context and retention checks
(`cycle5/refresh-unit.log`). Production-library compilation is in progress;
no generation or speedup acceptance is claimed yet.

The production macOS ARM64 library builds successfully (`refresh-build.log`).
The serial driver has started the 1/2/4/8-caller diagnostic ABBA matrix against
the retained cycle-3 native baseline; source/native identities and the exact
trial patch are retained in `cycle5/identity.json` and `refresh-candidate.patch`.
Results remain unaccepted until complete payload comparisons and measurements
are inspected. Ordinary runs without diagnostic counters and the broader
seed/order/bounds corpus are still required.

Initial diagnostic ABBA completes at 1/2/4/8 callers (eight measured regions per
process). Every full decompressed chunk payload matches its control: 98,304
compared chunk instances across the twelve non-reference runs. At two callers,
mean dependency-column builds fall from 13,194,357 to 12,387,740 (about 6.1%);
at four/eight callers the reduction is about 1.9%/1.6%. Serial work counters are
identical. These counts establish some avoided work, not an accepted speedup.
Wall times vary substantially (one four-caller control takes 10.74 seconds, the
other 4.34 seconds), alongside observed FSEvents and Node CPU activity. The
ordinary matrix now uses the repository benchmark harness, explicit shader-ready
observation, five warmups and twenty measured regions with diagnostics disabled.
Custom-bound fixtures preserve encoded NBT except explicitly recorded numeric
fields; their acceptance still requires the real native loader and generation.

The first ordinary ABBA matrix completes: all sixteen processes reach shader
status 4, and all fifteen comparisons against the first serial control preserve
complete NBT and region membership (307,200 measured chunk instances). Each
process uses five warmup regions and twenty measured regions, seed 42, zlib and
lighting enabled, automatic execution, shipping worker settings, Apple M4 Max /
Metal. The existing repository harness records CPU, latency and RSS scopes.

| Callers | Control / trial median batch ms | CPU/region change |
|---|---:|---:|
| 1 | 9281 / 9199 | -5.0% |
| 2 | 5937 / 5755 | -1.8% |
| 4 | 5707 / 5572 | +1.7% |
| 8 | 6951 / 6302 | -6.6% |

These preliminary medians do not establish a repeatable gain. In particular the
eight-caller control batches vary from 6,106 to 7,797 ms, and serial counters in
the separate diagnostic run show no avoided work despite timing differences.
Four-caller CPU cost rises slightly even though batch time falls slightly. Raw
samples, individual latency distributions, memory high-water marks and source/
library identities remain in `cycle5/ordinary-matrix/`; aggregate values are in
`ordinary-summary.json`. A second timing pass is required before acceptance.

The correctness corpus is now running second-seed and custom-bound Nether,
Overworld and End cases at all four caller counts and forward/reverse/shuffled
orders, each compared with a serial baseline's complete chunk payloads. The
custom fixture is synthetic: native terrain bounds 16..112, storage bounds
0..256, sea level 40; its exact NBT field edits are retained separately. This
validates bound-sensitive reuse, not a claim of Minecraft datapack parity.

The complete correctness corpus passes: 52 processes cover second-seed Nether,
custom-bound Nether, Overworld and End. For each case, all candidate runs at
1/2/4/8 callers and all three request orders match the serial control's complete
NBT and membership, totaling 393,216 compared chunks. End uses ordinary warmup
because its separate GPU path reports program status 0; an initial baseline
attempt with the generic shader-ready option stopped at the harness check and
is retained. No End generation failure occurred. Other cases reached status 4.
Evidence is in `cycle5/correctness-corpus/` and `cycle5/end-corpus/`, including
individual commands, logs, immutable measurements and MCA files.

The ordinary native test binary passes all 357 tests; 88 explicitly opt-in tests
remain ignored (`cycle5/native-suite.log`). The repeat ordinary ABBA matrix is
running before the performance acceptance decision. No executable generator code or
compiled native image has changed during these measurements; only the module
description was corrected to describe refresh.

The ordinary repeat passes all 307,200 complete-NBT comparisons and reaches
shader status 4 in all sixteen processes. Repeat control/trial median batches
are 9,032/8,870 ms (one caller), 5,939/5,772 ms (two), 4,503/4,385 ms (four), and
4,402/4,152 ms (eight). CPU/region falls about 2.1%, 1.7%, 1.9% and 4.3%.
Serial work counters remain identical, so serial timing differences are not
attributed to avoided dependencies. At two callers, the repeat's per-process
p95 is 743/695 ms for controls and 750/779 ms for trials, despite better batch
throughput. A longer two-caller ABBA sample is running to resolve that tail
latency concern before acceptance.

Memory results are high-water observations, not proof of physical residency or a
memory saving. First-pass eight-caller trials peaked at 7,066–7,447 MiB versus
6,585–6,962 MiB for controls; repeat trials peaked at 6,804–6,994 MiB versus
6,890–7,023 MiB. Captured snapshot owners remain bounded to eight / 512 MiB per
active job, but refreshing can pin additional completed owners until that job
finishes. Raw distributions and both pass summaries are retained in
`cycle5/acceptance-summary.json` and each process's `measurements.json`.

### Cycle 5 acceptance

The extended two-caller ABBA check (five warmups and sixty measured regions per
process) resolves the tail concern on this workload. Median batch time falls
from 17,679 to 16,361 ms (7.5%); median CPU cost falls from 1,733 to 1,677 ms per
region (3.2%). The two controls have p95 777/780 ms and maxima 886/879 ms; the
trials have p95 720/700 ms and maxima 837/805 ms. Peak RSS is 3,497–3,555 MiB for
controls and 3,493–3,503 MiB for trials. All 184,320 compared chunk instances are
identical, including metadata. Evidence: `cycle5/two-caller-tail/` and
`run_two_caller_tail.py`.

Accept bounded refresh between joined roots. It reduces demonstrated duplicate
work and improves whole-batch throughput in both ordinary matrices and the
longer targeted check. The change does not wait for unfinished neighbors, share
partial decoration, alter feature ordering or expand the existing per-job pinning
limit. Ten cache tests and the full 357-test ordinary native suite pass. Actual
GPU coverage is Metal only; platform integration remains part of final acceptance
for the overall pass. No uniform serial speedup, universal latency reduction or
memory saving is claimed. Original prototype, trial patch, native images, full
MCA outputs, commands and all measurements remain outside Git at the recorded
evidence root. PR delivery remains pending.

## Cycle 6: material counting (in progress)

Current device characterization at `456257d3` confirms the historical target.
The existing test-only worker-phase inventory sampled 212 root-zero GPU replies:
median counting 2.817 ms, emission 0.0138 ms, and counting share 99.50% of their
sum. These are overlapping device-stage observations, not total-generation
shares. The observer validates all twenty complete region generations. Evidence:
`/Users/cyberpwn/.cache/retina/performance-pass-20261028/cycle6/baseline-phases/`
and `baseline-stage-summary.json`; the original baseline test binary is retained.

The first trial skips vertical ranges that `material_voxel` guarantees are air:
outside generation bounds and above both the column and its fluid surface. It
preserves top/bottom air runs explicitly and retains normal per-voxel evaluation
inside the remaining interval. Column noise was already hoisted and counted runs
already retained; neither is presented as new work. Shared WGSL parsing and
validation pass, as does the existing actual-GPU fit/overflow/interleaving test
with retained-run budgets zero, one and thirty-two. A new real-GPU boundary test
covers entirely out-of-generation requests above and below the range, including
one-block heights, with all three retention budgets. Its result, production
build and full terrain/performance acceptance are still pending. No speedup is
claimed for this candidate.

The new real-Metal boundary test passes: entirely out-of-generation requests on
both sides, heights one and thirty-two, retention budgets zero/one/thirty-two,
all produce valid single-air runs for every column (`cycle6/air-boundary-test.log`).
The production library builds successfully and its identity is recorded in
`cycle6/identity.json`. A matched candidate device-stage inventory is running;
full production baseline/candidate ABBA generation is prepared but has not yet
run. The candidate is not ready for merge.

The candidate device inventory passes. Both samples contain 212 root-zero replies;
median counting changes from 2.817 to 2.653 ms (about 5.8% lower), emission from
0.0138 to 0.0132 ms. All twenty complete regions match the baseline, including
20,480 full chunk-NBT payloads and membership. This is one diagnostic pair, not
a whole-region speedup claim. `stage-comparison.json` and
`phase-corpus-comparison.json` retain the comparison. Production ABBA runs are
now active for Nether, Overworld, custom bounds and End, using saved definitions
and the existing repository benchmark with full-output comparisons.

### Air-range trial: not selected

Production ABBA passes all 245,760 complete-NBT comparisons, but does not
establish useful overall performance. Nether median batches are 9,007/9,010 ms
(control/trial); Overworld is 3,657/3,755 ms. Custom and End runs drift strongly
between surrounding controls, so their apparent changes are not accepted as
speedup evidence. Raw samples are in `air-range-abba/` and
`air-range-summary.json`. The shader change is removed; its patch and native
image remain retained. The useful out-of-bounds GPU regression test remains.

### Invocation-local surface values: new trial

The existing X/Z dependency analysis can select pure surface subgraphs, excluding
Y-dependent and material-context operations. The next candidate lazily retains
those values within one material-column invocation. It scopes the cache to
material count/emission scans, resets at their entry, bounds private storage to
32 fields, and leaves other surface callers and larger/unsuitable programs on
their original evaluator. It changes specialized execution only; interpreter
behavior is unchanged. This targets repeated surface noise rather than the
already-hoisted column noise or retained emission runs. Actual-profile WGSL
validation is compiling; no performance or terrain-parity result is claimed.

The invocation-local candidate passes actual-profile WGSL validation for Nether
(eight fields), Overworld (twenty-six fields), custom and direct-stem fixtures.
All 357 ordinary native tests pass (90 opt-in tests ignored). The actual Metal
worker-phase probe passes, and all 20,480 complete chunk-NBT payloads and region
memberships match the retained baseline exactly. Median root-zero material
counting is 2.608 ms versus 2.817 ms in the earlier baseline (212 replies each).
Concurrent compilation elsewhere on the machine makes this diagnostic pair
unsuitable for a performance acceptance claim. Logs and the exact corpus
comparison are retained under `cycle6/material-columns-*`. Production-library
compilation and subsequent alternating whole-region comparisons remain pending.

The production macOS ARM64 library now builds successfully. Its hash and source
patch are retained in `cycle6/material-columns-identity.json`. The out-of-range
material-run GPU regression also passes against the restored generic shader.
Production ABBA comparison has started for Nether, Overworld, custom bounds and
End, using five warmups and twenty measured regions per process, with complete
NBT comparison. Results remain pending; this cycle is not ready for merge.

### Invocation-local trial: first production comparison

The sixteen-process ABBA matrix completes with 245,760 identical complete-NBT
chunk comparisons across Nether, Overworld, custom Nether bounds and End.
Control/trial median twenty-region batches are 11,080/10,824 ms (Nether),
5,485/5,040 ms (Overworld), 10,586/10,753 ms (custom), and 1,432/1,428 ms (End).
The Overworld candidate processes differ substantially (4,602 and 5,478 ms),
and its generation RSS is 1,992–2,013 MB versus 1,780–1,810 MB for controls.
These results are provisional, not acceptance: inspect the extra specialized
graph/code footprint and repeat targeted measurements before selecting a final
implementation. `material-columns-abba-summary.json` includes CPU, p95, maxima,
material-stage timing and RSS for each process; original commands and outputs
remain in `material-columns-abba/`. Other-project test CPU, Finder/FSEvents and
GeForceNOW activity were observed; none was stopped or modified.

Two focused synthetic tests now cover cross-biome X/Z reuse while excluding
Y/material-context nodes, and empty/oversized-plan fallback including the
thirty-two-field boundary. Their test build is in progress; results pending.

The two focused synthetic cache tests pass. A follow-up candidate removes the
reachable duplicate uncached surface dispatcher: all surface callers use the
same generated graph, while its field helper reads/writes the local cache only
during material-column scans. Other callers evaluate fresh values. The first
candidate sources are retained in `material-columns-first.rs` and
`specialize-first-material-columns.rs`; this is a compiled-code-footprint
hypothesis, not an established explanation of the observed RSS difference.
Focused tests are compiling for the revised candidate.

The revised emitter also deduplicates identical generated surface graph bodies
across biome programs, preserving each original program argument when dispatching.
The first candidate had lost the original emitter's graph sharing. This source
change is included with the unified evaluator in the next candidate; its
focused test verifies both sharing and program identity. Full ordinary tests,
four actual-profile WGSL validations, and the production build are running
sequentially before any further timing work.

The unified/deduplicated evaluator passes all 359 ordinary native tests (90
opt-in tests ignored). All four actual-profile WGSL fixtures validate: Nether
uses eight fields/five shared graphs, Overworld twenty-six/twenty-four, and
custom/direct-stem two/one each. The production build is still running. Longer
ABBA comparison is prepared in `cycle6/run_material_columns_dedup_abba.py` but
has not started; runtime parity and performance acceptance remain required.

The revised production library builds successfully; artifact/source identities
are recorded in `material-columns-dedup-identity.json`. The sixty-region ABBA
matrix is now starting. Its full NBT comparisons and timing/memory results are
pending, so this candidate remains unaccepted and unmerged.

The existing independent interpreter/specialized GPU oracle now has an opt-in
material-column case. It primes a different biome at the same X/Z with changed
Y/context, then compares all roots; alternating invocations prime another X/Z
and disable caching before comparison to verify fresh non-material evaluation.
This is test-only, has not run yet, and does not alter the production library
being benchmarked. `cycle6/validate_material_column_parity.py` prepares ordinary
tests and cold/cached oracle runs for Nether, Overworld and custom fixtures
after the active timing matrix completes.

### Selection decision

Reject the invocation-local surface cache. The longer revised trial's Overworld
candidates take 21,757/22,514 ms versus 13,321/20,553 ms for controls; both
candidates are slower than both controls and retain the roughly 200 MiB extra
generation-memory footprint. Nether candidates (27,824/27,949 ms) are bracketed
by 29,489/25,647 ms controls, so they do not establish a whole-region benefit.
Even though device material time can improve, the overall and memory evidence
does not justify shipping either surface-cache variant. Custom/End comparison
is still completing against immutable libraries and will be recorded separately.

Production shader/emitter changes are removed. The useful generic all-air
boundary regression remains. Candidate code, libraries, commands and outputs
are retained outside Git, including `material-columns-final-trial.patch` and
`material-columns-final-trial.rs`. The additional cross-biome GPU oracle was
prepared but never run; it is retained only with the rejected experiment and
is not claimed as validation. This resolves the investigated opportunities
without asserting that material counting cannot be optimized in future.

The longer matrix is complete: all 737,280 compared complete chunk-NBT payloads
and memberships match. Custom control/trial median batches are 30,434/29,420 ms;
End is 5,019/5,248 ms. Overworld generation RSS is 1,708/1,772 MiB for controls
and 1,915/2,000 MiB for candidates. Timing drift limits causal claims, but these
results do not meet whole-region/memory acceptance. The final summary is
`cycle6/material-columns-dedup-summary.json`; all sixteen process outputs and
artifact identities remain retained.

Cycle 6 resolves as experimentally rejected optimization candidates, not an
implemented speedup: air-range clipping, lazy per-column surface values, and
the unified/deduplicated refinement were investigated with actual GPU execution
and full terrain comparisons. No production evaluator or shader change ships.
Only the all-air generation-boundary regression and this evidence ledger ship.
The additional retained-run/interleaving coverage exercised during investigation
also remains unchanged. Validation backend is Metal on Apple M4 Max only.
Final ordinary suite and GPU boundary regression are rebuilding against the
restored baseline before PR delivery.

Final restored-baseline validation passes: 357 ordinary native tests, zero
failures, 89 explicitly opt-in tests ignored. The new opt-in Metal air-boundary
regression passes independently. `git diff --check` passes. Final logs are
`cycle6/final-native-tests.log` and `cycle6/final-boundary-test.log`.

## Cycle 7: catalog loading and materialization (in progress)

An opt-in native inventory separately measures catalog decompression, full
variant parsing, selected-template cloning, canonical encoding, compression,
production validation, reopening, and inclusive materialization. Stage probes
are independent operations and must not be summed with the inclusive timer.
Each of four fresh processes uses two warmups and ten samples, varying seeds
across zero, minus one and both signed-width extremes. Both recompiled and
binary bundled snapshots are retained unchanged as baseline inputs.

Both catalogs expand to 37,808,508 bytes. Median decompression is 42–44 ms and
full parsing 104–117 ms. Normal-variant cloning is 18–20 ms, encoding 37 ms,
compression 353–355 ms, validation 686–698 ms and reopening 682–689 ms; inclusive
materialization is 1,113–1,133 ms. Bukkit variants are smaller: cloning 5 ms,
encoding 8–9 ms, compression 32–35 ms, validation/reopening 30–33 ms and inclusive
materialization 83–90 ms. Probe peak RSS is roughly 663–1,041 MiB and includes
probe-only intermediates; it is explicitly not production startup RSS.

Evidence: `/Users/cyberpwn/.cache/retina/performance-pass-20261028/cycle7/`,
including `baseline-catalogs.json`, `baseline-phase-summary.json`, immutable
`baseline-probe-tests`, four inventory JSON files/logs and retained V1 catalogs.
The probe confirms identical canonical encoding/recompression and successful
production reopening for every sample. No GPU is initialized by this probe.

The first implementation adds a V2 container with independently gzip-compressed
variant NBT payloads, selected before decoding. V1 remains readable, and the
materialized world-definition and seed-recipe formats are unchanged. The runtime
FFI now selects its variant during loading. Focused tests cover exact materialized
bytes across full-width seeds, legacy loading, missing variants, and a corrupt
unselected payload that must remain undecoded. Tests are compiling. Java export,
packaged assets, format documentation, production startup/memory comparisons,
all-variant fidelity and installed-platform acceptance remain pending. Larger
normal-world compression and repeated validation costs remain separate work
within this cycle; selective decoding alone does not resolve those costs.

The initial four catalog unit tests pass (two existing opt-in integration tests
remain ignored). Java compilation passes. The Java exporter now packs V2
containers, and `:common:repackWorldCatalog` successfully repackages both existing
registry snapshots into separate evidence directories. It rereads every packed
variant through Minecraft NBT and compares it with its original compound; all
variants match in both snapshots. No registry recapture or production asset
replacement has occurred yet. Gradle used `-x :packageNative -PretinaHostOnly
--no-watch-fs`; registry-tool dependency narrowing is still cycle 8 work.

A native full-definition comparison is now compiling/running: all five variants
in both snapshots, each with seeds zero, minus one, signed minimum/maximum and
123456789, must materialize byte-identical gzip world definitions. The candidate
production library will build after those checks. Raw logs are retained under
`cycle7/`; exporter fidelity, runtime measurements and packaged/platform
acceptance remain incomplete.

Native V1/V2 comparison passes all fifty complete world-definition checks
(two snapshots × five variants × five full-width seeds), including byte-identical
gzip output after production validation. Four ordinary catalog tests also pass.
The verified V2 assets and their manifests now replace the bundled V1 containers;
the originals remain in the evidence directory. Recompiled/binary V2 sizes are
4,085,653/4,088,819 bytes, with separate hashes and preserved snapshot content.
The production library is compiling. A new reusable FFI startup benchmark
measures real publication, reopening, close, CPU cost and process peak RSS;
comparison and hashing occur outside the timed interval. No startup speedup
is claimed until its matched runs complete.

The selective-decoding production library builds successfully and is retained
as `cycle7/selective-libretina_worldgen.dylib` with source/artifact identities.
Sixteen fresh-process ABBA startup runs are active (normal/Bukkit variants of
both snapshots, two warmups and ten measured creations per process). The
baseline uses the immutable cycle-5 library, whose production code is unchanged
by cycle 6; candidate output is compared byte-for-byte with each baseline world.
Results, JVM/platform packaging checks and additional materialization work
remain pending.

### Selective container measurements and Java integration

The sixteen-process startup ABBA matrix passes 144 byte-exact complete-file
comparisons. Recompiled normal-world median process times are 2,009/2,008 ms
for controls and 1,913/1,911 ms for candidates; binary normal is 2,005/2,021
versus 1,885/1,931 ms. Bukkit recompiled is 288/309 versus 141/141 ms, and binary
is 290/305 versus 139/142 ms. These include actual native publication, validation,
fsync, reopening and close; no GPU or JVM is initialized. Normal process peak
RSS falls from roughly 1,039–1,061 to 773–784 MiB; Bukkit from 637–643 to 184 MiB.
These are process high-water measurements, not device-memory estimates.
`cycle7/startup-summary.json` and all commands/raw process samples are retained.

The JDK native catalog bridge, three Fabric bundled-definition tests and all
22 Bukkit tests pass with zero skips/failures. Host-only Fabric, NeoForge and
Bukkit JARs build successfully. Actual ZIP inspection verifies exact V2 catalog
bytes, manifest hashes/versions and the candidate macOS ARM64 native image in
each; exporter/repack QA classes are absent. This is not a six-platform release
build. JARs and inspection results are retained in `cycle7/artifacts/` and
`jar-report.json`. The first Gradle invocation used the unavailable `remapJar`
task and failed before execution; the corrected actual `:fabric:jar` build
passed (`java-tests-jars-corrected.log`).

Actual dedicated Fabric/NeoForge fresh-world, saved-file restart and datapack
fallback acceptance is running in disposable `cycle7/runtime/` worlds, with
the live compiler or materializer deliberately disabled for the relevant case.
Bukkit dedicated-server acceptance and final native suite remain pending.

All six dedicated mod-server cases pass: Fabric and NeoForge fresh vanilla
creation with the compiler disabled, restart with both compiler/materializer
disabled, and a real neutral datapack with materialization disabled. End
activation, saved player edits, orderly drain/close and byte-identical saved
definitions on reopen pass. Independent native reads compare all three full
dimension profiles between cached and live-exported worlds exactly on both
loaders; only exporter provenance differs. Results and commands are retained
in `cycle7/runtime/`. The ordinary native suite passes 359 tests, with 91
explicit opt-ins ignored and zero failures. Dedicated CraftBukkit acceptance
is now running; this selective-container delivery is not yet ready to merge.

### Cycle 7a acceptance

Dedicated CraftBukkit acceptance passes, including normal creation/restart,
imported-world and metadata paths and preservation/rejection of malformed,
missing-material, missing-biome, seed-mismatched and unsupported-metadata
definitions. The real Gradle server suite completed successfully in 2m 3s.
Its full worlds/logs/reports are preserved under `cycle7/craftbukkit-acceptance/`;
independent Java-exported fixtures are retained under `cycle7/mod-fixtures/`.

Accept the V2 independently compressed variant container as cycle 7a. It preserves
all fifty full-definition reference cases, 144 FFI startup comparisons, saved
world authority, both runtime snapshots and all Java/platform checks described
above, while lowering actual startup time and peak process memory. No generation
algorithm changes. Host runtime coverage is macOS ARM64/Metal; foreign native
images are not claimed built or executed in this cycle. Full-goal integrated
acceptance remains pending. Cycle 7b will address the measured template copying,
compression and repeated validation costs separately; cycle 7 is not yet closed.


## Cycle 7b: faster materialization with fewer copies

The runtime FFI path consumes the selected catalog template instead of cloning
it. Borrowed materialization remains available for repeated use. Materialization
uses the existing libdeflater dependency at its default level 6 and releases the
template and raw serialized buffer before validation. The production loader still
validates the actual gzip bytes, publication still uses the existing synced
no-replace hard link, and Java still reopens the saved file. No generation,
catalog schema, seed recipe, or existing-world authority changes.

An opt-in compression inventory compared flate2 best with libdeflate levels
1/3/6/9/12 on real definitions. Each setting includes compressor construction and
output allocation, with two warmup sweeps and ten measured sweeps in alternating
order. Every output was decompressed and compared byte-for-byte, and every
setting passed the production world loader. Level 6 gave these results:

| Definition | flate2 best median / bytes | libdeflate level 6 median / bytes |
|---|---:|---:|
| Binary normal | 402.25 ms / 2,473,802 | 65.35 ms / 2,557,008 |
| Binary Bukkit | 42.46 ms / 141,582 | 9.44 ms / 150,534 |

The tradeoff is 3.4% larger normal definitions and 6.3% larger Bukkit definitions.
These are compression-only measurements; full startup results follow.

### Production publication, validation, reopen and close

Sixteen fresh-process ABBA runs compare the merged cycle-7a library with the
candidate, using identical V2 catalogs, two warmups and ten measured samples per
run. Both snapshots use seeds 0, -1, signed-64 minimum/maximum, and 123456789.
Every compared file matched every decompressed NBT byte: 144 comparisons including
warmups. Timings include actual publication/fsync, validation, reopening and close;
no GPU or Minecraft initialization is included.

| Snapshot / variant | Control medians (ms) | Candidate medians (ms) |
|---|---:|---:|
| Recompiled normal | 1914.7 / 1905.9 | 1601.1 / 1616.8 |
| Binary normal | 1913.2 / 1906.7 | 1609.6 / 1611.1 |
| Recompiled Bukkit | 140.1 / 140.4 | 110.1 / 108.0 |
| Binary Bukkit | 144.2 / 143.6 | 111.4 / 112.7 |

This is approximately 16% lower normal startup latency and 22% lower Bukkit
startup latency relative to selective loading alone. Normal process high-water
RSS ranged 766–892 MiB for controls and 575–602 MiB for candidates. RSS includes
Python and comparison buffers outside timing, not just native memory. Bukkit
RSS showed no consistent improvement (controls 183–255 MiB, candidates 179–195
MiB). Measurements used the Apple M4 Max/macOS host with desktop background CPU
activity present. Benchmark comparisons explicitly allow different gzip encodings
while checking every decompressed NBT byte.

### Validation and retained evidence

A separate cross-library comparison passed all 50 definitions: two snapshots,
five variants (all structures/bonus-chest combinations and Bukkit), and five
full-width seeds. Every complete decompressed NBT payload matched the merged
baseline and every output was reopened by the production loader.

The final ordinary native suite passed 359 tests, with 92 explicit optional
fixture/GPU/probe tests ignored. Regression coverage checks borrowed/owned
materialization parity, missing variants, unsupported versions, deterministic
output, and preservation of existing malformed destinations. The Java catalog
bridge passed, as did all three bundled-vanilla tests and all 22 Bukkit tests
with zero skips. Host-only Fabric, NeoForge and Bukkit 0.410 JARs built; actual
ZIP inspection verified expected V2 catalog resources/manifests/hashes, the exact
measured macOS ARM64 native image, and absence of exporter/repacker test classes.
No cross-platform native release build is claimed.

Evidence root:
`/Users/cyberpwn/.cache/retina/performance-pass-20261028/cycle7/materialization/`.
It contains compression inventories, `candidate-identity.json`, immutable library
and source patch, `startup-abba/`, `startup-summary.json`, `all-variants/`, build
and test logs, `jar-report.json`, and retained `artifacts/`. The compression probe
is `world_definition::catalog::loading_probe::definition_compression_inventory`,
using `RETINA_COMPRESSION_INPUT` and `RETINA_COMPRESSION_OUTPUT`.

All six dedicated Fabric/NeoForge runs passed: bundled vanilla creation with the
compiler disabled, unchanged saved-file restart with compiler/materializer
disabled, and neutral-datapack registry export with materializer disabled. End
activation, save/player-edit/reopen observations and drained close passed.
Independent native reads found exact profiles in all three dimensions between
cached and datapack definitions (only exporter metadata differed). Reports and
logs are retained under `runtime/`.

The full dedicated CraftBukkit server suite passed in 2m 7s, including first
creation/restart, metadata persistence, all dimensions, player edits, and
malformed/missing-material/missing-biome/seed-mismatch/unsupported-metadata
failure preservation. Its parity checks covered 12 chunks, 917,504 blocks and
14,336 quart biomes. The report, worlds and logs are preserved under
`craftbukkit-acceptance/run-er44alo0/`, with Java exports in `mod-fixtures/`.
These correctness runs are not additional performance claims.

Repeated validation/reopening remains measured but unchanged and requires
follow-up before goal item 7 is closed.


## Cycle 7c: reuse the definition loaded from the published file

The additive `retina_create_world_definition` API replaces Java's
materialize/discard/reopen sequence. Rust writes and syncs a temporary file,
rewinds it and loads it through the ordinary `WorldDefinition` reader, then
publishes that same file with the existing no-replace hard link. Only after
publication succeeds is the parsed definition registered as a native owner.
Java adopts the owner and closes it if adapter metadata retrieval fails. The
legacy publication-only API, validated byte-returning materialization and ordinary
saved-file open remain available. No registry-backed generation path or file
format change is introduced.

### Measurements

Sixteen fresh-process ABBA runs used the immutable merged cycle-7b library as
control, two warmups and ten measured iterations per process. Both snapshots used
identical catalogs, seeds 0/-1/signed-64 minimum/maximum/123456789 and recorded
native-library identities. Every one of the 144 comparisons matched complete
decompressed NBT. Timings include actual-file validation, fsync and owner closure,
but not Minecraft/GPU startup. Baseline uses publication plus reopen; candidate
uses the combined API. Host: Apple M4 Max/macOS, with desktop activity present.

| Snapshot / variant | Control medians (ms) | Candidate medians (ms) |
|---|---:|---:|
| Recompiled normal | 1651.7 / 1605.6 | 898.0 / 885.4 |
| Binary normal | 1608.4 / 1622.0 | 895.4 / 887.5 |
| Recompiled Bukkit | 111.6 / 114.0 | 80.6 / 80.5 |
| Binary Bukkit | 108.1 / 108.8 | 78.3 / 76.4 |

This is approximately 45% lower normal creation latency and 29% lower Bukkit
creation latency relative to cycle 7b. RSS includes Python/comparison buffers and
did not consistently decrease; no memory improvement is claimed for this cycle.
A separate matrix matched all 50 complete compressed files byte-for-byte across
two snapshots, five variants and five full-width seeds.

### Validation and evidence

The final ordinary native suite passed 362 tests, with 92 optional fixture/GPU
probes ignored. New tests cover invalid gzip/schema cleanup before publication,
competing creators with exactly one winner matching the saved file, null output,
zeroed failed outputs and returned-handle closure. Existing malformed destination
preservation remains covered.

The strengthened JDK-only Java bridge passed comparisons of created/reopened
metadata and all dimension profiles, variant flags and existing-file preservation.
All three bundled vanilla tests and 22 Bukkit tests passed with zero skips.
Host-only Fabric/NeoForge/Bukkit JAR inspection verified expected catalog counts,
V2 manifest hashes/sizes, the exact measured native library and absence of
exporter/repacker QA classes. No six-platform native rebuild is claimed.

All six dedicated Fabric/NeoForge create/reopen/datapack runs passed, including
End activation, save/player-edit/reopen observations and drained owner closure.
Independent native reads confirmed exact full profiles in all three dimensions
between bundled and neutral-datapack exports; only exporter metadata differed.
The full CraftBukkit server acceptance suite also passed: all dimensions,
creation/restart, persisted metadata, player edits and five invalid-input
preservation cases. Its parity checks covered 12 chunks, 917,504 blocks and
14,336 quart biomes. The report confirms the exact measured native image.
Worlds, logs and reports are preserved under `craftbukkit-acceptance/`, with
Java fixtures in `mod-fixtures/`. These runtime checks are correctness evidence,
not additional performance claims.

Evidence root:
`/Users/cyberpwn/.cache/retina/performance-pass-20261028/cycle7/open/`.
It contains `candidate-identity.json`, immutable native image/source patch,
`startup-abba/`, `startup-summary.json`, `all-variants/`, test/build logs,
`acceptance.py`, `acceptance-report.json`, `jar-report.json`,
`java-test-report.json`, retained `artifacts/`, and all `runtime/` fixtures/logs.
