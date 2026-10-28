# Performance and reliability implementation pass

This ledger tracks the eleven opportunities authorized on 2026-10-28. Each
cycle uses its own worktree, PR and squash auto-merge. Diagnostic findings do
not establish a speedup; unresolved opportunities remain open. Current
baseline: `24a1ad4` (Fabric/NeoForge bundled vanilla catalogs).

| Goal item | Status | Acceptance still needed |
|---|---|---|
| 1. Buffer biome-cache serialization | Merged: [PR #321](https://github.com/ArcaneArts/Retina/pull/321), `ab0248f3` | Complete |
| 2. Persist outside shared locks | Merged: [PR #322](https://github.com/ArcaneArts/Retina/pull/322), `c4bd97d8` | Complete |
| 3. Retire world GPU resources | Validated; PR pending | Merge resource retirement cycle |
| 4. Cancel abandoned admissions | Pending | Queue/close races, fairness, shutdown timing |
| 5. Reuse concurrent Nether dependencies | Pending | 1/2/4/8 callers, ordering, CPU/latency/memory, full NBT |
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
