# Conservative End noise bounds

The production solidity lattice can avoid exact coarse noise values when the
complete interpolation cell is provably uniform. The public dense lattice
and scalar/volume density APIs still evaluate exact values. Neither a biome
nor a handful of block samples establishes occupancy.

## Bounds

`end/blended.rs` bounds the actual smeared Perlin implementation:

- Every gradient has two unit components. X/Z local coordinates lie in [0,1].
  Smeared Y lies in approximately [-1.1e-7,1]: a nonzero floor requires its
  divisor to be at most 1/(1-epsilon), limiting the epsilon overshoot.
  Each gradient dot therefore has magnitude below 2.000001.
- The rounded fade polynomial differs from [0,1] by less than 1e-5. A forward
  error budget below 50 float epsilons covers its bounded intermediates.
  Three rounded lerps of the gradient dots remain below magnitude 2.001.
  A deliberately loose layer bound of 4 leaves substantial margin.
- The actual lower/upper stack amplitudes sum to less than one. A global
  magnitude bound of 4.01 covers all rounded products, sixteen source-order
  additions, and the final clamped-alpha blend. Main noise only determines
  alpha in [0,1], so its value need not be evaluated for these bounds.
- A tighter interval evaluates only the four largest-amplitude layers of each
  limit stack. Their actual float products are summed in double precision.
  The omitted amplitudes times 4 bound the remaining terms. An additional
  0.001 in each direction covers product/accumulation and final-blend rounding;
  endpoints are rounded outward. The union of the two stack intervals covers
  every possible alpha. This does not reorder the exact evaluator's sum.

The same source-order vertical expression propagates the noise endpoints.
Within the End lattice range (0..128), its fixed lerp factors and final scale
are nonnegative, so the finite float operations are monotone in noise.
Cells use the minimum lower endpoint and maximum upper endpoint of all eight
corners. The existing interpolation-rounding margin applies to that complete
range. Near-zero, nonfinite, or otherwise uncertain ranges receive exact work.

## Exact fallback and ownership

The first pass bounds every required corner, sharing the island candidate
field with exact fallback and biome selection. It classifies complete cells,
then marks all eight corners of every uncertain cell. Those marked corners
are evaluated once in the original source order. Their magnitudes matter even
when an individual corner's sign was already known. The masks are refined
after exact evaluation.

The private `SparseTile` exposes biome and solidity queries only. Its skipped
corner slots cannot escape as approximate density values. The dense API uses
the same geometry and candidate-field machinery while retaining every exact
corner. Bounds and the corner-needed bitset are temporary, and all cleanup is
inside terrain generation. Diagnostic counters distinguish bound time, mask
time, partial probes, exact/skipped coarse noise corners, and temporary bytes.

These are base-terrain decisions. Decoration dependencies, cross-boundary
features, city pieces, entities, lighting, and valid saved empty chunk records
retain their existing production paths.

## Shared column evaluation

Both partial bounds and exact fallback evaluate selected lattice heights in
one column. Each layer shares its wrapped X/Z coordinates, fade weights, and
horizontal permutation work. Its horizontal gradient contributions remain
cached while the vertical noise cell is unchanged. Vertical wrapping, smear,
fade, and interpolation still use each point's original operations. Each
height accumulates layers in source order; batching changes traversal across
independent points, not the arithmetic within a point. Exact lower/upper work
also respects alpha's original early branches.

Tests compare batched values bit-for-bit against the exact point evaluator,
and batched intervals against the pointwise interval implementation. Coverage
includes full and sparse height selections, codec extremes, negative/far
coordinates, and vertical wrapping. Independent Minecraft fixture bits are
also checked through the batched path.

## Validation scope

Independent Minecraft noise fixtures must lie inside the intervals, and their
scalar/volume bits must remain exact. Additional tests exercise codec limits,
the Y-smear floor boundary, negative and wrapped coordinates, independent End
solidity columns, and dense/sparse comparisons across unaligned rectangles.
Complete-region hash comparisons cover four representative regions; bounded
timings include the full production writer and synchronous cleanup.

After integration with the saved-definition execution-binding change, the
native suite passes 341 tests, with zero failures and 82 optional tests
ignored. Four complete raw-record hashes match the original dense baseline:
(1,0) mostly void, (8,8) islands, (-2,-3) city, and (0,0) arena. This establishes
exact Retina output preservation separately from the independent source
fixtures; it is not new gameplay or production activation evidence.

Development timing uses seed 123456789, the same saved definition, Metal,
ZLIB, eight workers, one caller, two warmups, and eight measured regions per
category. A coordinated window excludes the known Bukkit workloads, without
claiming that every process on the machine was controlled.

| Workload | Previous median ms | Batched bounds median ms | Batched p95/max ms |
| --- | ---: | ---: | ---: |
| Mostly void, fresh matched baseline | 295.9 | 162.8 | 180.7 |
| Outer islands, previous cycle baseline | 361.2 | 228.3 | 234.7 |
| Cities/central arena, previous cycle baseline | 318.3 | 162.9 | 222.1 |

The table uses conventional medians (the mean of the central two samples for
even counts), recomputed directly from saved timing rows. The runner now uses
that definition too; earlier raw reports labeled nearest-rank p50 as median.
The p95/max values remain unchanged. The previous void baseline varied from
its earlier measurement, so
use the fresh matched comparison for this cycle's claim. An intermediate
pointwise-bound version improved void latency but added too much repeated
horizontal work; its reports and binary remain retained. Batching improved
that cost while keeping the same exact masks and output hashes.

One separate instrumented mostly-void region, including all required halo
tiles, skips 809,455 of 937,099 exact coarse noise evaluations (86.4%). It still
performs 623,837 partial probes; the skipped percentage is not a claim that
86.4% of all noise arithmetic vanished. Bound construction totals 29.46 ms
additive across 13 tiles, mask work 6.02 ms, and temporary interval/needed arrays
8,222,288 bytes cumulatively. These are not peak residency or wall-time shares.
Timed process peak RSS is approximately 900–936 MB and includes startup and
driver state. GPU transfers and final chunk transport are unchanged.

Mostly-void stage p50 values are about 23 ms initial terrain, 108 ms decoration
planning, 13 ms lighting, and 11 ms publication I/O. A separate root probe
still spends about 31 ms scanning for city stair-shape updates. Required halo
terrain and that scan are the next measured costs to address. Void and ordinary
island medians still miss the final targets; this is not final acceptance.
