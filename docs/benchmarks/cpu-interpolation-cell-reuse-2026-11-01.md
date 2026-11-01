# CPU current interpolation cell reuse — 2026-11-01

A bounded per-evaluator cache retains the eight vertices of the most recent interpolation cell for each of 32 direct slots. Repeated queries avoid hashing the same corners and checking the same context eight times.

Only the bit-exact positive-zero context uses this cache. Full field identity and lower-coordinate float bits identify entries; seed, profile, sea level and bands are immutable for the evaluator lifetime. Other contexts, including negative zero, retain complete-key corner maps. Slot collisions only evict reuse. Copying entries across recursive misses prevents aliasing nested fields. Storage is at most 2 KiB per active evaluator, allocated lazily; existing bounded maps remain unchanged.

Each query starts with fresh zero-valued unused vertices and keeps the exact-zero-alpha early return. Previously sampled extreme unused vertices could otherwise introduce overflow/NaN even at zero weight. The eager test reference bypasses this cache.

## Complete lit MCA measurements

Portable release on the local M4 Max, vanilla seed 123456789, region (0, 0), global Rayon 2. Baseline is the preceding material-zero build. Eight alternating trials per Overworld budget, four for Nether. All 1,024 chunks include lighting, features, encoding, compression and saved output.

| Case | Median wall seconds | Median process CPU seconds |
| --- | ---: | ---: |
| overworld-16 | 8.945 → 8.275 | 43.221 → 41.198 (-4.7%) |
| overworld-4 | 16.134 → 15.706 | 34.628 → 33.143 (-4.3%) |
| nether-16 | 19.655 → 20.918 | 129.962 → 126.216 (-2.9%) |

Overworld CPU work falls at both budgets. Nether CPU work falls slightly, but its wall median is worse: one candidate received fewer scheduled cores (5.71 versus the other runs' 6.40–6.69). No Nether latency gain is claimed. Timing confidence intervals overlap zero; CPU work and diagnostic work reduction support retaining this small optimization. Samples are descriptive, not production p95 estimates. Four-worker generation still does not repeatably meet 15 seconds.

One diagnostic region records 56,462,852 cell lookups, 43,816,051 cell hits and 245,150,774 reused vertices. Corner-map lookups fall from 376,885,303 to 131,734,529 (65.0%). Primitive VM noise3 calls rise by only 120 to 189,580,560 because bounded-cache access/clear order changes incidental reuse. Output remains exact.

## Validation and remaining costs

73 CPU tests pass; five opt-in tests excluded. New tests cover partial masks, negative/far points, signed-zero/nondefault contexts, 33 fields colliding in 32 slots, recursive collisions and extreme unused vertex overflow. Actual-program and all-opcode eager comparisons remain independent.

All timed trials compare exact decompressed NBT. Diagnostic output matches original source output; custom pack, negative-coordinate second seed, central End and outer End match original corpora. Reopen preserves all 1,024 records. Actual backend is CPU, zero GPU jobs, no shader wait. GPU evaluation is unchanged.

Raw scripts (`cycle8-screen.py`, `cycle8-parity.py`, `profile-cycle8.py`), profiles, hashes, images and trial results remain under `~/.cache/retina/cpu-optimization-20261101/`. No power settings, focus or unrelated processes changed. Reprofiling still shows evaluator/noise work and late lighting. Lighting currently seeds propagation by visiting every voxel, including large full-sun interiors; a conservative initial-frontier pass is the next experiment.
