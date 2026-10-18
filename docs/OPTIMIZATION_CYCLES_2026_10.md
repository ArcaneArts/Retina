# Rust/GPU optimization cycles

Objective: improve complete lit-MCA generation while retaining exact generation
semantics, registered datapack behavior, save compatibility, and the production
eight-region concurrency limit. Each delivery uses an isolated worktree and PR
with squash auto-merge; validation runs locally with two build/CPU workers,
reduced priority, and bounded process-group deadlines.

The starting evidence is [the October 18 triage](PERFORMANCE_TRIAGE_2026_10_18.md).
The baseline is `d4f300f4983f14bee40f7fe73e9646b9ed0cd7fb`; native source is unchanged
from that report. Retained baseline library SHA-256:
`9bc17fe629d2af7f972c7cd7e0c64f8e4ffe958c6e10b0c0ae75d32b2f3c3b77`.

| Area | Status | Required evidence |
| --- | --- | --- |
| Compact/compile decoration programs | [PR #115](https://github.com/ArcaneArts/Retina/pull/115) and [PR #116](https://github.com/ArcaneArts/Retina/pull/116): validated eligible streaming | Dense/sparse planning, exact ordered output/RNG, memory, vanilla/Terralith lit-MCA |
| Complete feature-anchor GPU evaluation | Pending | Meaningful supported programs, exact fallback/overflow/order, end-to-end benefit |
| Compact lighting transport | Pending | Complete light-array parity, uniform/dense payloads, memory and lit-MCA throughput |
| GPU-driven material emission | Pending | Exact runs, overflow/failure/interleaved ownership, synchronization and throughput |
| Tighter ore planning/masks | Pending | Seed/distribution/host/exposure/boundary parity, total planning/application cost |
| Final sections/NBT/compression | Pending | Complete NBT/save parity, memory/copies and whole-region results |
| Combined pipeline integration | Pending | Concurrent jobs, DH promotion, ordinary loading, Chunky, lifecycle checks, final A/B |

Completion requires evidence for every area. Rejected experiments must have
concrete results and retain the proven production path. An isolated kernel win,
smaller transfer buffer, or one merged cycle does not establish goal completion.

## Decoration experiments

A preliminary cache-key allocation experiment replaced the composite parent-cache
key with nested maps allowing borrowed path lookup. On the dense Terralith
32x32-chunk planner fixture (seed 123456789, origin 0,0, two workers), baseline
samples were 826.78, 837.43, 865.59 ms; candidate samples were 822.08, 809.95,
819.50 ms. Ordered placement signature matched `1c50195a12ef29b2`. The roughly
2% median difference is inconclusive. The change was removed before proceeding;
no production benefit is claimed from it.

The first accepted implementation compiles reusable evaluation steps and count
metadata once per placement program. Branches share immutable positional draws,
three-level ordering paths stay inline, and static evaluation suffixes bypass
recursive expansion. [Results and limitations](COMPACT_DECORATION_PROGRAMS.md)
include repeated full lit-MCA comparisons. General candidate streaming and
complete GPU-anchor evaluation remain outstanding; this is an initial delivery,
not completion of the optimization goal.

The streaming follow-up replaces terminal count expansion with ordered cursors,
including random positional/filter suffixes, and keeps common draw traces inline.
Two matched Terralith lit-MCA trials improved throughput another 6–8% against
PR #115 with exact decompressed NBT. The final dense planner comparison was
738 → 603 ms and estimated replay retention fell from 45.7 to 6.7 MiB.
The initial narrow fixed-position implementation had negligible coverage; a
general cursor retaining per-child `Arc` allocations was also superseded by
the final inline-draw version. [Results](STREAMED_DECORATION_CANDIDATES.md)
separate these experiments from the accepted implementation. Complete GPU-anchor
evaluation and the other areas above remain outstanding.
