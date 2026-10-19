# Complete feature-program GPU trial

Subsequent [production integration](GPU_VINE_PLANNING.md) now offloads the
validated dense vine subset on Metal. The report below describes the original
test-only experiment and its measurements.

This cycle adds an opt-in recipe census and a test-only GPU implementation of
complete registered plant/vine programs. Normal generation still uses the
existing Rust planner. A useful independent-column vine result is a reason to
continue integration, not a claim of faster whole-region generation.

## What the census changed

The dense Terralith 32x32-chunk fixture at chunk (0,0), seed 123456789, evaluated
8,664,271 candidates and emitted 484,709 ordered writes. Of those evaluations,
6,904,000 belonged to `terralith:highlands/vines_spread`; only 23 passed placement
filters. The preceding `vines_seed` recipe evaluated 293,420 candidates and had
7,655 accepted positions. Accepted positions are not emitted block counts.

`worker.recipe_candidates` records `[evaluated, positioned]` by registered recipe
index in the detailed planner only. Normal generation does not allocate these
counters. Their sum is checked against the existing evaluation count. There is
no timing ABI or F3 change.

This evidence shifts the experiment from small grass programs to the loaded
vine-spread program. It does not authorize skipping its attempts: earlier
features and earlier candidates can provide the vines required by later ones.

## Implemented experiments

The test module compiles supported registered modifiers, integer/height
providers, material predicates, plant choices and vine support rules into GPU
data. SplitMix operations use two-word integer arithmetic. Probability boundaries
retain the CPU's 53-bit draw comparisons. Each independent recipe/anchor owns its
live state, with bounded output and a whole-anchor failure result on overflow.

Three paths are retained for comparison:

1. One invocation interprets a complete program, preserving count forks, modifier
   order, parent caches, live material reads, and ordered writes. This is too
   slow on the dense vine program.
2. Parallel preparation computes all candidates for the exact two-constant-count,
   in-square, supported-height, predicate, biome shape. A conservative predicate
   pass rejects only permanent failures; survivors commit sequentially. This
   improves on the scalar interpreter but still has an expensive serial loop.
3. Independent columns execute candidates in order within each column. Loaded
   predicates must prove that every horizontal read is invariant under all
   possible air-to-vine writes. Programs failing that proof retain the scalar
   path. Atomic column queues are sorted by original candidate index; the final
   parallel prefix compaction restores the original order across columns.

The third path is specialized by program shape and registered material rules,
not by a hard-coded biome or recipe name. The fixture selects the expensive
recipe by name for reproducibility. It keeps the same RNG forks, heights,
positions, feature predicates, support priority, and block states.

The bounded column queue holds 128 candidates per column. Overflow rejects the
entire anchor, never a partial result. Initial per-anchor overlays are separate
from concurrent writes so earlier features can supply or remove supports. This
is a handoff interface experiment; the normal planner has not been connected to
it yet.

## Measurements and validation

Results and exact test coverage are recorded in
[`benchmarks/gpu-feature-anchor-trial-2026-10-18.json`](benchmarks/gpu-feature-anchor-trial-2026-10-18.json).
Measurements use Apple M4 Max / Metal, release builds, two CPU workers and reduced
priority. Compilation is bounded to 60 seconds with two Cargo jobs; each execution
is bounded to 30 seconds. GPU initialization/shader compilation occurs before the
reported repeated batch timings, but within the execution deadline.

The repeated fixture includes one unmeasured warmup pair, three alternating
CPU/GPU pairs, and exact ordered-write comparison after every sample. GPU times
include substrate/program packing, allocation, upload, dispatch, readback and
decoding. The seeded synthetic substrate contains walls and initial vines so
the measured recipe produces real writes and sequential cascades.

| Anchors / attempts | Ordered writes | CPU median, trials A / B | GPU total median, trials A / B |
| --- | ---: | ---: | ---: |
| 16 / 128,000 | 149 | 6.15 / 5.66 ms | 3.36 / 2.74 ms |
| 64 / 512,000 | 650 | 25.57 / 22.04 ms | 6.85 / 6.35 ms |

Trial B follows an arithmetic-bound restriction in the probe compiler; the
registered fixture programs are unchanged. Both trials retain three alternating
pairs. GPU samples vary, particularly just after warmup. These measurements show
a promising supported subroutine, not a stable estimate of production speedup.

At 64 anchors, the current implementation uploads 2,562,956 bytes and reads back
2,622,512 bytes, including 48 timestamp bytes. Its explicitly allocated GPU
buffer payload totals about 47.3 MiB. The corresponding 16-anchor figures are
956,556 bytes upload, 655,664 bytes readback, and 12.1 MiB of buffers. Those totals
exclude driver allocations, staging resources and the CPU field/profile. The
output allocation is deliberately conservative and is not yet a production
memory or transport optimization.

Device timestamp triples report preparation, column evaluation, and ordered
gather. Parallel compaction replaced a serial gather that measured around 2.4 ms
in several earlier samples; the final 64-anchor gather samples are around
0.11–0.16 ms. One earlier hardware-timestamp sum exceeded its enclosing host
wall time. Raw readings remain in the artifact, but are not treated as an
additive critical-path partition or used to calculate the headline comparison.

The final binary passed 76 ordinary release unit tests; 12 diagnostics were
ignored and 18 GPU tests were filtered from that ordinary invocation. Separate
GPU runs checked 31 vanilla programs (496 recipe/anchor pairs, 189 writes) and
66 Terralith programs (1,056 pairs, 19 writes). The seeded vine cases checked
ordered writes at positive, negative and near-world-boundary coordinates,
including two newly enabled cascade writes, changed prior overlays, zero-output
capacity and column-queue overflow. The mutated horizontal-dependency fixture
rejects column parallelization. The bounded runner's success, nonzero-exit,
timeout-cleanup and cached-build paths were exercised.

These are independent-program timings, not full planning or lit-MCA timings.
There is no full-generator performance claim from this cycle. The general
plant/vine checks on generated terrain provide additional parity evidence but
have few writes and first-run timings; they are not useful throughput evidence.

## Remaining integration work

- Suspend a planner anchor only at an eligible, unshared recipe-group boundary.
  Preserve prior overlays, selector sharing, cached predicates, missing-provider
  checkpoints and exact continuation state.
- Batch those jobs and reuse packed substrate/program data instead of uploading
  an entire field per small recipe. Measure memory with concurrent regions.
- Resume CPU suffixes only after committing complete successful GPU outputs.
  Unsupported, oversized or failed jobs must replay the original CPU group.
- Compare complete ordered plans and lit-MCA NBT on vanilla and Terralith before
  enabling the path in production. Include boundary, interleaving and failure
  tests, actual transfer counts and matched whole-region timing.

The full GPU-anchor objective remains pending. Lighting transport, material
emission, ore transport, final-section processing, and combined integration also
remain tracked in [the cycle tracker](OPTIMIZATION_CYCLES_2026_10.md).

## Reproducing the diagnostic

Use the exported profile files identified by SHA-256 in the result artifact. The
bounded runner builds once when `--binary` is omitted:

```sh
python3 scripts/feature-anchor-trial.py --profile /path/to/terralith.json --test live --output /tmp/anchor-live.json
```

For subsequent checks, add `--binary` with the executable from that report's
build output. The runner uses two workers, reduced priority, a 60-second build
deadline and a 30-second execution deadline, with process-group cleanup. Choose
one `--test` value:

- `recipes` (`gpu_complete_plant_anchor_probe`): all supported plant/vine recipes on a 4x4
  generated field; use vanilla and Terralith separately.
- `census` (`gpu_anchor_workload_census`): complete dense planner recipe census.
- `live` (`gpu_vine_anchor_live_probe`): positive, negative and near-world-boundary seeded
  vines, live cascades, earlier feature writes and output-overflow recovery.
- `batch` (`gpu_vine_anchor_batch_probe`): repeated 16/64-anchor comparisons and deliberate
  column-queue overflow.

Run expensive checks sequentially. These tests are ignored by default and do not
run a game, generate region files, or activate a new production backend.
