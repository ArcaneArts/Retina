# Compact CPU interpolation corner keys

The initial CPU profile identified wide interpolation-corner lookups as a major
consumer of worker CPU time. The common exact-zero material context now uses a
coordinate/field key, while other contexts retain every surface/detail bit.
Negative zero remains contextual. Both tables share the existing 32,768 live-entry
limit and clear together. No sample, instruction, precision or generation rule
changes. Samplers still own their caches; no cross-world or worker sharing is added.

## Matched full-region comparisons

Apple M4 Max, normal AC mode, portable Cargo release, seed 123456789, vanilla
binary catalog, normal structures, full lit Zlib MCA, region 0,0. A/B/B/A uses
four fresh processes with one measured region and no warmups in each. CPU is
explicit; global Rayon workers remain two. Source baseline is `60c6f7e`.

| Case | Baseline median wall | Candidate median wall | CPU seconds, baseline → candidate | Generation RSS change |
| --- | ---: | ---: | ---: | ---: |
| Overworld, 16 native workers | 33.78 s | 22.71 s | 140.82 → 125.73 (-10.7%) | -4.6% |
| Overworld, 4 native workers | 57.03 s | 53.03 s | 123.98 → 113.58 (-8.4%) | -1.2% |
| Nether, 16 native workers | 29.79 s | 30.42 s | 116.72 → 114.16 (-2.2%) | -1.7% |

CPU work decreased in both Overworld trials at both budgets. Wall latency is
noisier: desktop activity overlapped the runs and the first 16-worker baseline
was particularly slow. Nether is effectively unchanged within this small sample.
The wall reductions are observations, not a portable speedup claim or production
p95 estimate. The four-worker result remains far from the 15-second goal.

All replayed baseline and candidate records matched exactly, including lighting,
features and metadata. Additional complete custom-datapack and second-seed regions
at negative coordinates each matched 1,024 baseline records. Every candidate
definition reopen generated zero replacements and preserved all 1,024 chunks.
The 63 CPU checks pass (five hardware-only comparisons ignored), covering all
opcodes, context changes, the cache bound, nested interpolation, deep graphs,
worker-count output parity, and small-pool handoffs. The new context fixture also
compares reused evaluation against fresh evaluators.

## Next profile

A separate two-region candidate diagnostic still shows evaluator dispatch and
corner lookups dominating column work. The early collapsed top-frame sample had
8,765 evaluator, 6,941 corner and 2,560 interpolation observations across workers.
Later samples add climate search and cave evaluation. Sampling conditions differ
from the original run, so these counts are not a quantitative before/after cost
breakdown. They justify measuring exact evaluator visits and cache reuse before
choosing the next optimization. Stronger exact-cell reuse and reduced repeated
dispatch remain opportunities; they are not implemented by this change.

Compact results are adjacent JSON. Native images, commands, raw measurements,
profiles and reference MCA records remain in
`~/.cache/retina/cpu-optimization-20261101/`. Reproduce with the existing CPU-aware
`scripts/native-region-benchmark-ab.py`; both builds must use the same native
budget, definition, seed, coordinates, lighting and compression.
