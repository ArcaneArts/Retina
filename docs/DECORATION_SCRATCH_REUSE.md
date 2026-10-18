# Decoration worker scratch reuse

Tree planning previously allocated leaf positions, an occupancy grid, a BFS
queue, and a log-membership set for every tree. Decorators additionally allocated
placed-leaf positions, occupied positions, and a sorted position vector for each
decorator.

A planner job now owns a mutable `TreeScratch` workspace. Rayon `map_init` supplies
independent scratch to each parallel job; the serial path owns one for its anchor
loop. It is reused across trees and anchors processed by that job, then dropped.
Rayon may split a worker's work into several jobs, so this is not a promise of
exactly one allocation per physical thread. No global/thread-local cache or new
worker pool is introduced.

Leaf positions, dense occupancy bytes, the BFS queue and membership set retain
capacity. The positions vector is reused for actual placed leaves before running
decorators; the membership set then becomes decorator occupancy. A separate
position vector is reused between decorators. Every membership/grid state is
cleared before its next use. Sparse crowns still use sparse membership instead
of allocating huge empty boxes. Dense/sparse/empty transitions preserve the same
log exclusions and leaf discovery order.

Scratch contains no random stream, candidate queue, overlay or resumable anchor
state. The six-neighbor BFS, geometry functions, decorator sorting and RNG calls
are unchanged. No blocks, leaves or decorators are skipped. Trunk/crown geometry
vectors retain their existing allocation behavior.

After each completed tree, scratch exceeding an accounted 2 MiB retention limit
is discarded. Oversized custom trees still generate normally; their active
working data may transiently exceed that limit. Accounting uses vector capacities
and a conservative hash-table allowance. This bounds retained scratch, not total
process RSS or allocator overhead. Completed outputs and anchor replay checkpoints
have their separate existing lifetimes and limits. Scratch never survives its
planner job or occupies a checkpoint.

## Bounded A/B results

Release build on Apple M4 Max, CPU only, 64 independent trees per batch, five
warmup pairs and 21 alternating measured pairs per case. Both paths use the
same tree code; the reference drops scratch after every tree and the candidate
reuses it. Every ordered block command and final RNG state is compared outside
timing. Each side starts with fresh job scratch, so first-use growth is included.

| Case | Initial fresh / reuse median | Follow-up fresh / reuse median | Accounted retained scratch |
| --- | ---: | ---: | ---: |
| Oak | 0.398 / 0.348 ms | 0.339 / 0.299 ms | 9.7 KiB |
| Jungle with vines/cocoa | 5.254 / 5.004 ms | 4.480 / 4.202 ms | 109.5 KiB |
| Six mixed tree shapes | 1.734 / 1.507 ms | 1.367 / 1.261 ms | 140.4 KiB |

Oak medians fell 11.8–12.7%, mixed trees 7.8–13.1%, and jungle 4.8–6.2%.
The initial mixed reuse run had a 5.984 ms maximum versus 3.106 ms fresh despite
lower median and sampled p95. That prompted one bounded repeat, retained alongside
the original: maxima were then 1.278 ms reuse and 1.452 ms fresh. The tail spike
did not recur, but these checks do not establish production p95. Different absolute
times between the two runs also show why their within-pair comparisons matter.

Initial process maximum RSS was 18.7 MiB, including both paths, fixture data and
comparison outputs. It is not a paired memory measurement. The accounting values
above describe actual retained workspace capacities, not RSS savings.

A separate retained pre-change release-library check compared complete block and
column bytes for eight uncached chunks per vanilla/Terralith profile with two
callers. All outputs matched. Batch medians were 67.272 -> 68.761 ms for vanilla
and 95.868 -> 94.632 ms for Terralith. This mixed result does not demonstrate a
whole-generator speedup; it excludes region encoding, lighting and publication.

Raw samples, profile/library hashes and memory observations are in
[`benchmarks/decoration-scratch-reuse.json`](benchmarks/decoration-scratch-reuse.json).

## Correctness and resource checks

All 70 native unit tests pass, including the existing leaf-distance/hash-set
reference, closed dark-oak and jungle crowns, boundary invariance, live overlays,
and resumed/full-anchor replay comparisons. New checks exercise six shapes in
alternating order at origins 0, -33 and 700000, clipping at field edges, exact block
commands and RNG tails, dense/sparse/empty occupancy resets, log-state clearing,
and retention-limit recycling.

Typechecking, the release library/example build, formatting and diff checks pass.
The ignored `tree_scratch_benchmark` test prints the raw A/B samples. Runtime
checks used 30-second process-group deadlines at reduced priority; builds used
two jobs/codegen units with 60-second deadlines. Planner checks used two workers.
No Minecraft or full-region benchmark was launched.

This completes the worker-scratch portion of decoration planning. The GPU
geometry/leaf-distance investigation is documented in
[GPU_TREE_LEAF_EVALUATION.md](GPU_TREE_LEAF_EVALUATION.md); its measured GPU paths
were slower and remain diagnostic-only. GPU ore preparation and Java column-cache
optimization follow separately.
