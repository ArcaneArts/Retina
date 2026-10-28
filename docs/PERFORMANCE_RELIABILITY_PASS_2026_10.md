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
| 5. Reuse concurrent Nether dependencies | Validated; PR pending | Merge delivery |
| 6. Optimize material counting | Pending | Device and whole-region timing, exact terrain, backend scope |
| 7. Reduce catalog overhead | Pending | Phase/RSS measurements, variant/seed fidelity, packaged assets |
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
