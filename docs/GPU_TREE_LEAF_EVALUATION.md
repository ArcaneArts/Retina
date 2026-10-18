# GPU tree and leaf evaluation

Keep the production tree planner in Rust. Two actual-GPU leaf-distance probes
preserve the ordered outputs but lose to the existing two-worker CPU traversal,
including transfers and output ordering. In these fixtures they also take longer
than constructing the entire trees on the CPU. The probes are ignored tests behind
`cfg(test)`; ordinary builds contain neither their Rust code nor their shaders.

This closes the optional GPU tree/leaf investigation in the performance pass.
The accepted decoration improvements remain the deterministic parallel placement
distribution, resumable anchors, combined provider/count sampling, and bounded
[worker scratch reuse](DECORATION_SCRATCH_REUSE.md).

## Why order matters

`decoration::tree` uses a six-neighbor breadth-first traversal from logs, visiting
leaves through distance six. Leaf distance selects the registered block state.
The discovery order also determines subsequent placement and decorator inputs.
Returning the right set of leaves in arbitrary GPU completion order is insufficient.

The probe assigns each reachable leaf a key containing its distance, original log
ordinal and lexicographic direction path. Six minimum-key relaxations find the
same winning path as the CPU traversal. Sorting reached keys after readback
recovers the exact output order. Original log ordinals survive cropping, and
duplicate logs keep their first ordinal.

Two implementations were measured:

- Six global ping-pong dispatches over compact per-tree bounds.
- One workgroup per tree with a 16 KiB atomic minimum-key array and six barriers.
  Trees exceeding 4,096 padded cells use the global implementation in that batch.

Both reuse device buffers, submit once per batch, and map one output. Geometry is
prepared on the CPU. Neither changes RNG, substitutes approximate leaf states, nor
drops disconnected/unreachable leaves differently from the CPU reference.

## Bounded comparisons

Release build, Apple M4 Max / Metal, two Rayon workers, reduced priority. Each
case has two warmup triples and seven measured triples with rotating order:
CPU leaves, GPU leaves, and complete CPU trees. Checks run outside the timings.
The CPU comparator includes scratch reset/growth and parallel-job scheduling.
The GPU total includes input preparation, upload, submission, wait/readback,
copying and ordered decoding; it excludes one-time pipeline creation.

Recipes come from retained vanilla and Terralith registry exports: straight/blob,
fancy oak, mega jungle, dark oak, giant/mega pine, and forking/acacia. Recipe source
IDs and profile hashes are retained in the raw data. These are independent trees
on flat soil, with the same recipes and seeds on every path. This is deliberately
favorable to batching and is not a complete live-anchor or region benchmark.

| Profile | Trees | CPU leaves | Full CPU trees | Global GPU leaves | Workgroup GPU leaves |
| --- | ---: | ---: | ---: | ---: | ---: |
| Vanilla | 16 | 0.045 ms | 0.103 ms | 0.404 ms | 0.832 ms |
| Vanilla | 64 | 0.133 ms | 0.274 ms | 0.587 ms | 1.018 ms |
| Vanilla | 256 | 0.475 ms | 1.073 ms | 1.696 ms | 1.861 ms |
| Terralith | 16 | 0.050 ms | 0.106 ms | 0.390 ms | 0.939 ms |
| Terralith | 64 | 0.153 ms | 0.327 ms | 0.597 ms | 1.049 ms |
| Terralith | 256 | 0.506 ms | 1.181 ms | 1.729 ms | 1.497 ms |

Values are medians. The displayed CPU columns are from the global-GPU runs.
Workgroup runs have their own paired CPU values in the raw data: at 256 trees,
0.547/1.229 ms for vanilla leaves/full trees and 0.508/1.185 ms for Terralith.
Both GPU approaches lose within their own comparisons; comparing independent
GPU runs does not establish a reliable ranking between the two shader designs.

At 256 trees, the global GPU host phase alone is about 1.21–1.22 ms, plus
0.41–0.46 ms for ordered decoding and 0.06 ms for preparation. Device execution
is about 0.62 ms and overlaps host time. Workgroup device spans vary considerably;
lower device time does not remove readback and ordering costs. Nonpositive
timestamp spans are unavailable (`null`), not zero-cost work.

Across the four 256-tree runs, GPU maxima are 1.65–1.97 ms versus 1.13–1.31 ms
for complete CPU trees. Seven samples do not establish production p95. No
whole-generator throughput gain or regression is inferred from this experiment.

## Geometry and dependency assessment

The complete CPU-tree comparator runs production geometry, survival checks, leaf
processing and registered decorators, comparing every ordered block command.
The fixture worker counters show leaf traversal occupies roughly 42% of the
tree construction work in these samples. Offloading just that portion already
costs more than all of tree construction on two CPU workers.

The source investigation also found sequential 64-bit random streams, branch
decisions and f64 geometry in `tree_shapes.rs`. Production trees consult the live
anchor overlay and update it before later features. A full GPU geometry pipeline
would need equivalent random/numerical operations, survival queries, ordered
decorators and dependency handling, rather than batching arbitrary trees from
the same anchor as if they were independent.

No GPU geometry implementation is claimed here. The measured leaf offload is
rejected; the complete CPU-tree comparison bounds the current opportunity.
This does not prove that a future pipeline keeping whole independent anchors
resident on the GPU cannot win. It would be a separate architecture experiment,
not justification to ship the slower incremental path tested here.

## Memory and validation

The probe caps batches at 256 trees, 1,048,576 padded cells per tree, and
4,194,304 total cells. Four device buffers use 16 bytes per cell, plus 4 KiB
of descriptors (at most 64 MiB plus descriptors/timestamp buffers). Registered
256-tree batches use 3.45 MiB vanilla and 3.61 MiB Terralith for these buffers.
Capacity is reused within a test and dropped when it ends. This is diagnostic
memory and creates no retained production cache.

Process maximum RSS was 188–189 MiB vanilla and 53 MiB Terralith; macOS peak
footprints were 341–342 MiB and 206 MiB. These include profiles, fixtures, both
paths and GPU runtime allocations, so they are not paired memory savings.

All ordered leaf positions and distances match the CPU reference. Their distance
materials match production leaf commands, and complete CPU-tree commands match
the retained construction. Additional actual-GPU edge checks cover competing and
duplicate logs, duplicate leaves, holes, disconnected cells, paths beyond six,
and coordinates around zero and +/-30 million. Each origin has a small grid and
a large grid, exercising local workgroups and the fallback together.

All 70 normal native unit tests pass (23 opt-in tests remain ignored). The final
release test build, formatting and diff checks pass. Tests used 30-second
process-group deadlines; builds used two jobs/codegen units with 60-second
deadlines. No game, region benchmark or GitHub Actions run was needed.

Run `ordered_gpu_leaf_edge_cases` explicitly for the small GPU correctness check.
Run `ordered_gpu_leaf_evaluation` with `RETINA_LEAF_PROFILE` pointing to a complete
registry export; `RETINA_LEAF_LOCAL=1` selects the workgroup variant. Use the above
resource bounds rather than the older long region-benchmark recipes.

Raw samples and metadata:
[gpu-tree-leaf-evaluation.json](benchmarks/gpu-tree-leaf-evaluation.json).
GPU ore preparation and Java column-cache optimization remain separate cycles.
