# CPU saved biome-grid reuse — 2026-11-01

The previous cycle's profile showed substantial underground-biome and nearest-climate-target work after accelerating height scans. CPU materials already produce the complete saved quart biome plane, but subsequent cave/material workers used independent hash caches and re-evaluated some of those exact points. Reuse the completed data for this same terrain tile.

## Implementation

After constructing the existing packed biome plane, copy it once into an immutable shared `Arc<[u32]>`. Subsequent workers read the exact packed ID for in-grid quart coordinates. Outside queries use the existing bounded cache and scalar sampler. Scope is one build with an immutable profile, seed, dimension, request and column halo; nothing is cached globally or across worlds. Native profile validation already limits biome IDs to the saved 16-bit representation.

This is one backing allocation per tile, not per worker. The vanilla measured plane uses 4,435,968 bytes; the trace measures 0.348 ms preparation. Storage scales with the already-generated output plane and drops after construction. No terrain, graph arithmetic, feature rules, compression or GPU execution changes.

## Matched complete lit MCA results

A/B/B/A portable release comparison against the preceding air-certificate library, normal vanilla seed 123456789, region (0, 0), one region per fresh process, no warmups, global Rayon 2. Worker budgets appear in the case names. Output includes lighting, NBT, compression and publication.

| Case | Median wall seconds | Median process CPU seconds | Generation peak RSS MiB |
| --- | ---: | ---: | ---: |
| overworld-16 | 13.071 → 11.336 | 68.145 → 57.339 (-15.9%) | 1030 → 1026 |
| overworld-4 | 27.005 → 23.833 | 58.004 → 49.266 (-15.1%) | 927 → 932 |
| nether-16 | 26.726 → 25.727 | 138.679 → 134.978 (-2.7%) | 1518 → 1517 |

The Overworld CPU-work reduction repeats at both budgets. The smaller Nether difference needs more samples before attribution. Desktop load caused wide wall-time variation; all unrelated processes remained running. Two trials per variant are not production p95 estimates. This local Apple M4 Max result does not prove the target on the user's unspecified hardware, and the 4-worker case remains above 15 seconds. Generation RSS includes startup through generation, excluding later NBT validation; the generic A/B summary also retains a broader process-lifetime peak.

A diagnostic run matches all 1,024 original source-baseline records. Terrain-category VM calls fall from 16,617,594 to 15,852,476. Primitive VM noise3 calls fall only from 190,111,772 to 189,580,440: the measured benefit primarily avoids extra biome-target search and hash-lookup work, rather than substantially reducing noise sampling. Material VM calls remain 39,923,712, warranting further profiling.

## Correctness and reproduction

68 CPU tests passed, with five opt-in tests excluded from that invocation. The separate full CPU chunk acceptance test then passed for Overworld, Nether, central End and outer End, requiring valid feature NBT and no graphics engine. Four GPU comparisons remain unexecuted. Earlier reports mislabeled all five exclusions as hardware-only; their descriptions are corrected in this PR.

New tests compare shared-grid and scalar biome decisions across two registered dimensions, two seeds, negative coordinates, aligned/unaligned minimum Y and reversed query order. In-grid reads never populate the resampling map; outside reads retain it. A separate packed-layout test covers odd record counts, both half-words, every block in multiple negative-coordinate quarts, boundaries, extreme outside coordinates, missing data and shared backing identity. Existing serial/parallel and legacy material tests pass.

Every full-region A/B trial has exact decompressed NBT equality against the first baseline. Additional custom datapack, negative-coordinate second seed, central End and outer End cases match original retained corpora. Same-process definition reopen preserves all 1,024 records, and actual CPU selection, zero shader-readiness waits and zero GPU jobs are checked. No Windows runtime or GPU performance improvement is claimed.

Use the retained `cycle5-screen.py`, `cycle5-parity.py`, command JSON, per-trial measurements and diagnostic/profile artifacts under `~/.cache/retina/cpu-optimization-20261101/`. The adjacent compact JSON contains hashes, medians, trial results, work counts and parity markers. Diagnostics remain separate from headline timings.
