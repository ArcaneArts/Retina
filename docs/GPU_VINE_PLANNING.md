# Complete vine programs in the region planner

Metal generation now evaluates sufficiently large batches of supported registered
vine programs on the GPU. `RETINA_GPU_VINES=0` retains the CPU planner;
`RETINA_GPU_VINES=1` also permits testing on other backends. Other backends keep
the CPU default until measured. Vanilla's current export has no eligible programs.

This integrates the [complete-program trial](GPU_FEATURE_ANCHOR_TRIAL.md) into
normal region planning. It is a supported subset, not a replacement for all
vegetation or a new placement distribution. No recipe names or biome IDs select
the production path.

## Eligibility and order

The compiler accepts two constant counts, in-square positions, constant/uniform
height, material predicates and biome filtering, followed by registered vine
attachment. It requires 1,024–16,384 attempts, a bounded height range, a supported
build height, and a globally unshared placement salt. All predicates are compiled
from loaded data. A horizontal material read must have the same truth value for
all possible air-to-vine transitions; otherwise the original CPU path remains.

Preparation calculates the original integer SplitMix forks and positions in
parallel. Each independent column sorts its surviving candidate indices and
commits them sequentially, including vertical support created by earlier vines.
A final compaction restores original order across columns.

The Rust replay pauses before advancing a recipe's first cursor. Each anchor
supplies its preceding feature overlay. Successful output removes exactly that
recipe group, updates the live overlay and resumes the remaining queue. Pending
spatial providers cannot publish speculative prefixes. Unsupported inputs,
queue/output overflow, failed mapping and unavailable GPU state keep the complete
CPU continuation. An oversized retained result discards its checkpoint and
performs a full CPU replay with offload disabled for that anchor.

## Bounded work and transport

A shared mutex owns one reusable device arena, independent of the number of
region callers. Batches contain at most 64 anchors, 128 candidate indices per
column and 256 output writes per anchor. Overflow rejects the entire anchor;
it never truncates a feature. Both active and retained arena sizes are capped at
128 MiB. The existing per-replay 64 MiB prefix limit is unchanged.

Sparse batches stay on CPU when their candidate count is below twice the field's
column count. The first complete implementation packed about 26 MiB of substrate
even for two anchors; this cost outweighed their CPU evaluation. The heuristic
changes execution only, not attempts, RNG or output. Eligible profiles compile
the three GPU kernels at their first planning request, rather than delaying
compilation until the first dense batch.

Uploads/readbacks feed the existing profile transfer counters and F3 averages.
`RETINA_VINE_DIAGNOSTICS=1` prints batch counts, CPU continuations, substrate
packing time, driver wall time, valid device spans and arena bytes. Worker wall
and device spans overlap; invalid timestamp pairs are excluded. The continuation
count includes deliberately CPU-routed sparse batches.

## Measurements

[Raw results](benchmarks/gpu-vine-planning-2026-10-18.json) include library/profile
hashes, individual region timings, transfer counters, process maximum RSS and
exact NBT comparisons. Apple M4 Max / Metal, reduced priority, two Cargo jobs,
two Rayon/region workers, seed 123456789, height 384, six full regions
X=-1..1/Z=-1..0, with a separate warmup at (8,8). Measured runs reached shader
status 4: the existing mixed pipeline. Throughput is completed chunks divided
by batch wall time through atomic MCA publication, with lighting enabled.

The native library comparisons use **the final crate at optimization level 2
with LTO disabled**, identically for CPU/GPU modes. Dependencies and repository
release settings are unchanged. Three earlier attempts to build the default
shared-library configuration exceeded the 60-second cap; the reduced build
finished in about 24 seconds. These results must not be compared numerically
with earlier reports using the normal release library. Release unit-test builds
retained the normal optimization/LTO settings and completed in about 45 seconds.

| Workload | CPU vines, chunks/s | GPU vines, chunks/s | Interpretation |
| --- | ---: | ---: | --- |
| Terralith, one caller | 1,133 / 1,160 | 1,223 / 1,255 | Roughly 5–8% against the adjacent 1,160 repeat; small samples |
| Terralith, two callers | 1,393 | 1,446 | About 4%; one matched pair |
| Vanilla, one caller | 1,963 | 2,029 | Inconclusive variation; no eligible programs |

Every GPU corpus and the concurrent CPU corpus matched all 6,144 decompressed
chunk NBT records against its CPU reference, including complete light arrays.
A final run with no override confirmed Metal default selection and matched all
6,144 chunks at 1,258 chunks/s using the same comparison build settings.
These are native generation results, not loaded/rendered game throughput.
Six-sample p95 observations are recorded, not claimed as stable production tails.

The normal-release dense Terralith planner fixture measured 627–630 ms on CPU
versus 474–477 ms with GPU vines, with identical complete ordered placement
vectors. This roughly 24% planner reduction is not the whole-region speedup.

The first integrated trial improved complete throughput only about 0.7%, which
was inconclusive. It included first-use kernel compilation in measurement,
whole-field packing for sparse batches and 2,048-write output slots. The accepted
batching/256-write version removes those costs without changing results.

Transfers rise from approximately 386.32 to 400.61 MiB per Terralith region
because the offload introduces substrate uploads; the first trial used 434.69
MiB. The dense fixture's readback fell from roughly 21 to 2.6 MiB. Vanilla's
392.92 MiB remains unchanged. This is a throughput improvement, not a claim that
GPU offloading always reduces transfers. Lighting transport remains a separate
next target.

The observed shared arena reached about 68 MiB. Serial process maximum RSS ranged
from roughly 2.35–2.52 GiB for CPU mode to 2.65–2.78 GiB for the revised GPU mode;
the two-caller observations were 2.85 versus 2.40 GiB. These process-wide peaks
include shader compilation/caches and cannot isolate an allocation saving.
Additional host substrate packing is transient per active region; the arena is
shared and bounded. No memory reduction is claimed.

## Validation and reproduction

Local tests cover the independence/shared-salt classifier, merged queue/retry
order, missing provider inputs, prior features, negative and distant coordinates,
zero prefix budget, discarding a successful oversized prefix, disabled-device
CPU continuation, output overflow, queue overflow and pooled-buffer reuse.
The full registered planner compares ordered vectors with offload enabled and
disabled for vanilla and Terralith. Native corpus runs cover two concurrent callers.

Use the existing `scripts/native-region-benchmark.py` with separate fresh output
directories, the same retained library, exported profile, worker settings and
`--readiness shader-ready`. Compare `RETINA_GPU_VINES=0` and `=1` with `--compare`.
Use `--parallel 2` for the concurrent pair. Keep the documented 30-second process
group execution deadline and two-worker limits.

The ignored Rust tests are `decoration::vine_gpu::tests::gpu_vine_planner_handoff`
and `decoration::vine_gpu::tests::gpu_vine_registered_planner`; the latter takes
`RETINA_ANCHOR_PROFILE`. Run actual-GPU checks sequentially under the same limits.

A temporary unit-binary MCA harness encountered cold specialization beyond its
bounded readiness windows, including one 60-second timeout. Those attempts are
not performance evidence and were removed; complete-region results use the
existing native-library harness, which reached the actual mixed pipeline.

Remaining scope: additional feature-program classes require measured benefit
and dependency proofs. Vulkan/DX12 runtime validation, maximum eight-caller
stress, DH/Chunky combined lifecycle validation, and the other optimization areas
remain in the [cycle tracker](OPTIMIZATION_CYCLES_2026_10.md). No game was launched.
