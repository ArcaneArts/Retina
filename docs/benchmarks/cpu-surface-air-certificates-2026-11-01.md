# CPU surface-height air certificates — 2026-11-01

Composed registered surface-height queries previously evaluated final density at every block Y from the top of the dimension down to the first solid sample. One complete Overworld region made 138.5 million terrain evaluator calls. Reusing conservative terrain-cell bounds removes most of these calls without changing the resulting height.

## Change and safety

The CPU scan now skips a cell only when an existing conservative certificate is finite and its upper bound is nonpositive. It verifies that the box encloses the skipped samples. Uncertain cells retain the original descending integer-block evaluations and first-positive crossing. Nonfinite roots, unsupported graphs, unsuitable far coordinates and unsafe rounding fall back to scalar evaluation. Bounds are job-local, retain their existing total entry limit, and carry the same immutable profile/seed context. Fractional horizontal samples remain supported.

The existing noncomposed final-field crossing calculation is unchanged. The End uses a separate kernel. No GPU sampling, numerical precision, noise algorithm, features, compression or dependency coverage changes. Three opt-in counters record height scalar samples, proven-air samples skipped, and certificate requests.

## Matched complete lit MCA results

Portable ordinary release, vanilla normal seed 123456789 at region (0, 0), global Rayon 2; one newly generated region in each fresh process, A/B/B/A, zero warmups. Baseline is the preceding merged CPU counters build. Native budgets shown in the case names. All comparisons include lighting, metadata, NBT, zlib compression and saved output.

| Case | Median wall seconds | Wall change | Median process CPU seconds | CPU change |
| --- | ---: | ---: | ---: | ---: |
| overworld-16 | 21.413 → 12.374 | -42.2% | 136.176 → 67.037 | -50.8% |
| overworld-4 | 47.778 → 24.384 | -49.0% | 114.493 → 55.412 | -51.6% |
| nether-16 | 22.040 → 19.680 | -10.7% | 135.968 → 137.102 | +0.8% |

The Overworld result consistently halves CPU work at both budgets. The Nether has no demonstrated CPU-work improvement; its wall improvement varies with scheduling and should not be attributed to this change. Two samples per variant are insufficient for production p95 or a reliable small-change estimate. This is a local Apple M4 Max on AC/normal power mode, with unrelated desktop processes active; it does not establish the user's 15-second target on unspecified hardware. The 4-worker workload still needs substantial improvement.

## Work removed

A separate diagnostic region matches all 1,024 original source-baseline chunk records. It skips 121,840,984 proven-air integer samples and retains 2,822,375 scalar height samples. Total terrain VM calls drop from 138,458,578 to 16,617,594; all VM calls from 189,825,213 to 70,194,560; corner lookups from 2,944,869,619 to 376,885,303. Registered primitive noise3 calls increase from 181,285,742 to 190,111,772 because certificates inspect complete bounding vertices. These counts explain the win and show why remaining climate/noise/material work should be re-profiled rather than assuming the original bottleneck still dominates.

## Validation and reproduction

66 CPU tests passed; 5 optional hardware comparisons ignored. New reference comparisons cover two seeds, both registered dimensions, fractional/negative/far coordinates, clipped/expanded height ranges, thin islands, exact zero, tiny positive density, unusual non-power-of-two cells and invalid returned roots. Existing pool handoff/panic recovery, worker-order/material equality, profile retirement, full-feature chunk and lighting tests passed.

Every baseline/candidate trial compares complete decompressed NBT against the first baseline: blocks, biomes, heightmaps, light, block entities, structures and metadata remain identical. Additional full-region checks match retained original custom-datapack, negative-coordinate second-seed, central End and outer End outputs with native budget 8/global Rayon 1 and reverse request order. All tested outputs preserve 1,024 records on same-process definition reopen, with zero GPU jobs. Reopen is not a cold-process test.

Raw commands/results/profile evidence live under `~/.cache/retina/cpu-optimization-20261101/`: `cycle4-screen.py`, `cycle4-parity.py`, the `cycle4-*-command.json` files, diagnostics, trial measurements and NBT corpus hashes. The adjacent compact JSON retains medians, complete trial summaries, work counts, hashes and parity markers. Generation peak RSS is separately retained there; process-lifetime peaks in the generic A/B summary also include later validation. Use `--backend cpu` and `--diagnostics` only for diagnostic runs, not headline timing.
