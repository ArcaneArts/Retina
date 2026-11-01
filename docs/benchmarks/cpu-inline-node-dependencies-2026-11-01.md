# CPU compact inputs and inline dependency selection — 2026-11-01

After removing redundant height/biome/material/light work, profiles point back to VM dispatch. An early bounded profile of the 768-block-height pack records dependency selection among its largest sampled functions (1,001 leaf samples versus 1,159 for the evaluator and 122 for noise). This is a phase sample, not a whole-region CPU percentage.

## Change

Store ordinary instruction inputs directly in the prepared execution step as three u32 indices and a count. Every supported non-spline opcode has at most three inputs. Spline dependencies remain selected directly from the full original knot table; reachability and constant analysis still use the complete dependency list. Preserve input order, conditional demand, all six output checks and deep-graph stacks.

The 64-bit execution-step layout shrinks from 40 to 32 bytes. Nonempty dependency lists no longer require their own retained heap allocation. This is a per-step reduction, not a claim of 20% lower total process memory. Preparation still uses temporary dependency vectors for analysis.

Inlining dependency selection into the evaluator then permits the compiler to optimize their shared branch/operand handling together. No arithmetic, sampling, feature selection, worker policy or GPU algorithm changes. The initial storage-only image is retained separately; its CPU benefit was modest (about 0.9%, 1.8%, 3.3% across the cases below). The selected refinement adds `inline(always)` to the same selector.

## Complete lit MCA measurements

Portable release, local M4 Max, vanilla seed 123456789, region (0, 0), global Rayon 2. Baseline is the preceding lighting-frontier build. Eight alternating trials per Overworld budget, four for Nether. Each trial saves all 1,024 chunks with all normal generation and lighting stages.

| Case | Median wall seconds | Median process CPU seconds |
| --- | ---: | ---: |
| overworld-16 | 6.689 → 6.246 | 39.252 → 34.944 (-11.0%) |
| overworld-4 | 13.971 → 12.420 | 31.261 → 27.590 (-11.7%) |
| nether-16 | 18.329 → 15.941 | 124.175 → 105.084 (-15.4%) |

All 25 VM counters match the baseline diagnostic exactly, including node visits, computed nodes, noise calls and caches. This improves execution overhead rather than reducing generation work or sampling. Trials are descriptive, not production p95 estimates. Desktop scheduling and frequency remain variable; paired results and process CPU work are both retained.

## Validation and remaining work

75 CPU tests pass (five opt-in tests excluded). New coverage checks every real opcode's exact ordinary input order and capacity. A 32-knot spline compares all six output bit patterns against the independent eager reference at 81 interior/exterior positions, ensuring no knot truncation. Existing all-opcode, deep graph, actual-program, context and worker tests remain active.

Every timed trial compares strict decompressed NBT. Original-source vanilla, custom pack, negative-coordinate second seed, central End and outer End corpora match exactly; reopen preserves all 1,024 records. The full 768-height large pack also matches every original record in a separate complete diagnostic/profile run. That sampled run is excluded from headline latency claims. Earlier far and expanded-storage original corpora remain available for final acceptance.

Actual backend is CPU, zero GPU jobs, no shader-readiness wait. The previous cycle executed six exact real-Metal integer lighting comparisons. GPU code is unchanged here; final shared-path Metal coverage remains part of goal delivery.

Raw commands, prototype/refined images, hashes, profiles and machine-readable results remain under `~/.cache/retina/cpu-optimization-20261101/` (`cycle10-inline-screen.py`, `cycle10-inline-parity.py`, `profile-cycle10-inline.py`, `profile-large-complete.py`). No client, power or unrelated process changes were used. Reprofile before selecting another CPU change: remaining scalar graph/noise work differs substantially between vanilla and the large pack.
