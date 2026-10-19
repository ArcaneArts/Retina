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
| Complete feature-anchor GPU evaluation | [Complete registered vine programs](GPU_VINE_PLANNING.md) integrated on Metal; other program classes remain CPU | Mixed-anchor handoff, exact fallback/overflow/order, end-to-end benefit |
| Compact lighting transport | [Uniform material input](COMPACT_LIGHT_INPUT.md) and [constant-light output](COMPACT_LIGHT_OUTPUT.md) validated on Metal; resident halo sharing deferred | Complete light-array parity, uniform/dense payloads, memory and lit-MCA throughput |
| GPU-driven material emission | [Two bounded-arena prototypes evaluated and rejected](MATERIAL_ARENA_TRIAL.md); existing continuation retained | Exact runs, overflow/failure/interleaved ownership, synchronization and throughput |
| Tighter ore planning/masks | [Word-level uncertainty masks evaluated](ORE_WORD_FLAGS_TRIAL.md); smaller transfers but speed inconclusive, production retained | Seed/distribution/host/exposure/boundary parity, total planning/application cost |
| Final sections/NBT/compression | [Section metadata reuse and bulk biome reads evaluated](SECTION_ENCODING_TRIAL.md); NBT work falls but whole-generation benefit is inconclusive, production retained | Complete NBT/save parity, memory/copies and whole-region results |
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

## Complete GPU program experiment

The recipe census attributes 6,904,000 of the dense fixture's 8,664,271 candidate
evaluations to the registered highlands vine-spread program. A test-only GPU
implementation now supports full plant/vine programs, with an independently
ordered column path when the loaded predicates prove horizontal independence.
It preserves ordered output on seeded cascades, prior feature overlays, negative
and distant coordinates, and rejects whole outputs on overflow. Repeated batch
comparisons include packing and transfers; results are in the
[trial report](GPU_FEATURE_ANCHOR_TRIAL.md). This remains an isolated-program
result. It does not complete area 2 or establish a whole-generator speedup.

## Complete vine-program integration

The planner now hands eligible complete recipe groups and their prior overlays to
a bounded shared GPU arena, then resumes Rust in exact order. Dense batches use
independent ordered columns; sparse/unsupported/overflowing cases retain CPU
continuation. Full native lit-MCA comparisons matched 6,144 decompressed chunks
per corpus for vanilla, Terralith and two callers. Terralith samples improved
roughly 5–8% serially and 4% with two callers, using matched optimization-level-2,
non-LTO comparison libraries. Normal-release planner tests also preserve ordered
output. [Evidence and limitations](GPU_VINE_PLANNING.md) explicitly separate the
initial inconclusive trial, transfer/memory costs and reduced build settings.

This completes production integration of the measured supported subset, not the
overall optimization goal. Compact lighting transport is next; material emission,
ore/section processing and combined lifecycle validation remain outstanding.


## Compact lighting material input

Uniform final sections now upload exact material descriptors; mixed sections
retain their full IDs. Region-wide classification is reused by overlapping halos,
and a SIMD-friendly scan replaces the slower scalar prototype. Input-only GPU
buffer growth preserves existing frontiers and worklists. All tested light arrays
and decompressed NBT match the preceding production path.

[Results](COMPACT_LIGHT_INPUT.md) show about 37–38% less total logical traffic and
4–5% higher serial vanilla throughput in matched bounded builds. Serial Terralith
changes are small, and reversed two-caller comparisons are inconclusive. The
standalone diagnostic API remains slower because its inputs were already packed;
that limitation and process RSS observations are retained in the report.

This advances area 3's input transport. Constant-light output representation,
GPU-driven material emission, ore/section processing and final combined lifecycle
validation remain outstanding. No earlier stage gains are added to these rates.

## Compact lighting output

Conservative final-material proofs now omit constant channels from GPU packing
and readback. The NBT encoder consumes constants directly; unknown channels keep
their complete arrays. There is no extra synchronization. Buffer reuse fixes the
initial prototype's varying-size allocation overhead. [Results](COMPACT_LIGHT_OUTPUT.md)
retain that rejected prototype and the accepted variant separately.

Logical traffic falls by another 52–53 MiB/region. Reversed two-caller Terralith
comparisons improve 5–14%, while vanilla and serial results establish no throughput
benefit. Process-wide memory peaks do not show a consistent reduction. Exact
GPU/region/NBT comparisons pass, including constant arrays and registered air
properties. Resident halo sharing remains deferred because it would require
broader final-block ownership and memory changes; the useful transport changes
are implemented without it. Material emission, ores, final sections and combined
integration remain outstanding.

## Bounded material-output arena

Two implemented variants preserved exact material and full-NBT output, including
overflow and interleaved ownership. Staging first-pass runs shortened material
device time by about 42–43%, but six-region results showed no reliable Terralith
throughput gain and a slower vanilla sample, with more transfer and memory.
[Evidence and reproducible patches](MATERIAL_ARENA_TRIAL.md) are retained; neither
prototype remains in production. This evaluates area 4 without claiming a runtime
speedup. Ore masks, final-section processing and combined integration remain.

## Ore uncertainty word flags

Two implemented mask formats reduce readback by 33–41% without changing ore
blocks. The final workgroup-local variant preserves all 6,144 NBT records per
whole-generation corpus, but full-region ore-stage time is essentially unchanged
and repeated Terralith throughput moves in opposite directions.
[Raw measurements, limits and both patches](ORE_WORD_FLAGS_TRIAL.md) are retained;
the existing production masks remain. Final sections/NBT and combined lifecycle
validation are the remaining areas.


## Final-section/NBT reuse

The prototype reuses lighting's final uniform/mixed classifications and fetches
quart biomes section-wise, preserving fallback coverage and exact NBT. Normal
release synthetic encoding is 18% faster and full-region NBT worker time is
20–25% lower. Six- and twelve-region complete-generation results disagree,
including slower final vanilla samples; no reliable overall benefit is established.
[Measurements, tests and the final patch](SECTION_ENCODING_TRIAL.md) are retained.
Production source and the shared ignored native library are restored. Combined
pipeline/lifecycle validation and the final starting-baseline comparison remain.
