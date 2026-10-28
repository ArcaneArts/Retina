# Performance and reliability implementation pass

This ledger tracks the eleven opportunities authorized on 2026-10-28. Each
cycle uses its own worktree, PR and squash auto-merge. Diagnostic findings do
not establish a speedup; unresolved opportunities remain open. Current
baseline: `24a1ad4` (Fabric/NeoForge bundled vanilla catalogs).

| Goal item | Status | Acceptance still needed |
|---|---|---|
| 1. Buffer biome-cache serialization | Implemented and validated; PR pending | Merge |
| 2. Persist outside shared locks | Pending | Coalescing, revision/failure/close races, actual server latency |
| 3. Retire world GPU resources | Pending | Resource census, safe retirement, lifecycle/memory plateau |
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
