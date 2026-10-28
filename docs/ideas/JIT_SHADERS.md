# JIT shaders for registered world-generation operations

Status: proposal, not an implemented optimization or a measured speedup.

## Objective

Extend Retina's existing shader specialization to compile compatible sequences
of registered noise, masks, placement modifiers, material predicates, and simple
features into reusable GPU programs. Reduce CPU interpretation, repeated work,
intermediate transfers, and CPU/GPU synchronization while preserving the current
generator's output and datapack semantics.

The strongest initial opportunity is generated decoration kernels. More elaborate
noise shaders alone are not necessarily faster: the existing Metal defaults keep
some terrain stages interpreted because that measured better.

## Existing foundation

Retina already generates profile-specific WGSL from validated density/material
graphs. It folds constants, removes unreachable expressions, shares graph bodies,
and evaluates conditional arms lazily. Expensive X/Z-only expressions can become
GPU-resident column fields reused by later calculations. A background compiler
and source-keyed cache allow automatic generation to continue through the GPU
interpreter while specialized pipelines are pending or unavailable.

Relevant implementation and evidence:

- [Shader specializer](../../native/src/specialize.rs)
- [GPU stage selection and dispatch](../../native/src/gpu.rs)
- [Column dependency analysis](../../native/src/column_program.rs)
- [Placement programs](../../native/src/decoration/placement.rs)
- [Existing GPU vine programs](../../native/src/decoration/vine_gpu.rs)
- [Registered GPU specialization](../GPU_PROGRAM_SPECIALIZATION.md)
- [Decoration planning diagnostics](../DECORATION_PLANNING_PERFORMANCE.md)
- [Bounded batches and substrate reuse](../READY_DECORATION_BATCHES.md)
- [Previous optimization integration and limitations](../POST_INVESTIGATION_INTEGRATION.md)

The vine path demonstrates a complete GPU feature program, but accepts a specific
recipe shape. The proposed compiler would recognize more combinations of loaded
operations without a separately handwritten shader for every combination. Existing
legacy replay measurements do not establish a benefit for the source-root
dependency planner; both paths need separate coverage where applicable.

## Proposed execution model

An eligible recipe could execute as:

```text
Generate candidates
  -> sample required noise and height
  -> apply biome, water-depth, and material filters
  -> select block states
  -> compact accepted placements
  -> return ordered results to Rust
```

This may be one kernel or several GPU passes sharing resident buffers. The goal
is to remove unnecessary host round trips, not force everything into one shader.
Global synchronization, shared intermediates, and differing sampling domains can
justify separate passes. A workgroup barrier cannot synchronize unrelated
workgroups; those dependencies need an appropriate pass boundary or algorithm.

Rust would continue managing generation, resource lifetime, unsupported operations,
ordered application where required, NBT, and MCA publication. This proposal does
not replace the generator, add a world type, or change feature densities.

## Compiler requirements

### Operation representation and dependency analysis

Extend the existing graph and placement representations with explicit information
about inputs, outputs, coordinate dependencies, random-number consumption, block
read/write footprints, and dependence on prior placements. Distinguish pure noise
and predicates over immutable input from operations observing mutable world state.

Build programs from resolved registry data rather than biome names or assumptions
about particular datapacks. Unknown operations retain their existing execution
path. Classify support from actual operations and dependencies, not a heuristic
that prevents otherwise valid generation from running.

### Selective fusion and reuse

Group operations that consume the same candidates and can share intermediate
values. Fold static values, eliminate unused branches, and reuse common expressions
without changing arithmetic or conditional semantics. Keep expensive column fields
separate when many voxel or candidate evaluations reuse them.

Bound generated source size, temporary storage, and variant count. Choose between
generated code, existing interpreted GPU programs, and CPU execution using measured
costs. A large shader can increase register pressure, spill temporary values, reduce
occupancy, and compile slowly enough to outweigh its execution savings.

### Deterministic placement and sparse output

Preserve random streams, draw order, rejection behavior, and the order of competing
writes. Earlier decorations can change later survival or support checks; evaluating
every feature against the original terrain would change the world.

Start with independent or read-only stages whose dependencies can be established.
Carry stable candidate identities through GPU compaction and retain the original
ordering when applying accepted results. GPU scheduling and atomic arrival order
must not choose the winning block. Handle cross-chunk/region footprints and halos
without clipping valid placements or duplicating them.

Keep immutable substrate and intermediate fields resident where useful, scoped to
their actual profile, region, and data version. Budget memory across all eight
in-flight regions. Overflow, unsupported inputs, and device failure must use an
exact continuation or replay path without partially applying duplicate writes.

### Compilation, caching, and activation

Compile reusable program shapes for the loaded registry, not a new shader for each
chunk, coordinate, or seed. Keep changing world inputs in buffers unless a measured
specialization justifies a separate variant. Include every code-affecting option
and compiler/schema version in cache identity, with backend/device compatibility
for compiled artifacts.

Continue generation using existing paths while compilation runs. Select an immutable
pipeline bundle at a batch boundary so a completed compile cannot change a running
batch's scratch layout. Bound background compilation and cache retention; discard
obsolete queued work after world closure. One application compiler thread does not
guarantee that the graphics driver's internal compiler uses only one CPU core.

Persistent machine-code caching requires backend-specific investigation. The wgpu
30 documentation lists its explicit pipeline cache as Vulkan-only; it is not a
ready-made Metal disk cache. Cache source/analysis separately from native pipeline
artifacts and treat missing or incompatible artifacts as normal cache misses.

## Candidate priorities

| Opportunity | Expected upside, unmeasured | Risk / complexity |
| --- | --- | --- |
| Generate common candidate/filter/simple-placement kernels | Potentially high where CPU evaluation and replay dominate | High: RNG, dependencies, ordering, and fallback |
| Keep intermediate results resident across compatible stages | Potentially high where transfer and synchronization dominate | High: lifetime, versioning, and concurrent memory use |
| Fuse compatible noise and mask expressions | Medium: fewer repeated samples and intermediate writes | Medium: compile cost, numerical fidelity, and register pressure |

Begin with candidate generation, height lookup, biome/material filtering, and
simple block placement. Expand to more complex features only after demonstrating
both output equivalence and complete-generation improvement. Trees, structure
assembly, iterative lighting, and arbitrary stateful features are not automatic
extensions of a pure expression compiler.

## Validation and performance decision

First capture current phase costs and eligible recipe frequency for representative
vanilla and datapack profiles. Separate CPU planning, replay, preparation/upload,
GPU execution, readback, compaction, and ordered application. Overlapping GPU/CPU
timings are not additive percentages of elapsed generation time.

Compare the existing path, generated kernels, and generated kernels with resident
intermediates independently. Use identical seeds, profiles, regions, concurrency,
and build settings with alternating run order. Measure cold compilation and first
region separately from warm throughput. Include sparse and dense decoration, tiny
requests and full regions, and up to eight concurrent region requests.

Record completed chunks per wall-clock second, region mean/tail latency, CPU work,
GPU time, transferred bytes per region, peak memory, compile time, cache hits,
eligible/actual GPU coverage, and fallback reasons. Judge complete generation
through MCA publication; report Java loading and rendering separately if measured.

Correctness checks must compare complete ordered placements, including metadata,
and decompressed chunk NBT. Cover random rejection paths, overlapping writes,
negative/far coordinates, region edges, changed registries, overflow, cancellation,
interleaved region buffers, and compilation failure. Preserve existing floating-point
boundaries: reassociation or contraction can change threshold and biome decisions.

Use local, bounded benchmarks with reduced priority and two CPU build/runtime
workers initially. Do not launch the game or GitHub Actions for this investigation.
Retain raw measurements and rejected experiments. Ship only variants demonstrating
a repeatable end-to-end benefit at acceptable startup and memory cost; do not claim
a speedup from shader timing alone.

## Suggested implementation sequence

1. Inventory high-cost recipe chains and add only the diagnostics needed to identify
   CPU work, synchronization, and transferable intermediates.
2. Extend dependency metadata and build one generated simple-placement prototype
   with exact ordered-output comparison against the existing path.
3. Add bounded resident intermediates and stable output compaction; measure their
   contribution independently of shader specialization.
4. Add background compilation, immutable activation, cache lifecycle, and explicit
   fallback diagnostics by extending existing infrastructure.
5. Validate complete worlds/regions and concurrent requests, then enable only the
   supported backend/workload combinations with demonstrated gains.

Each implementation cycle should follow the repository's
[manual change protocol](../MANUAL_CHANGE_PROTOCOL.md): isolated worktree, scoped
PR, local validation, squash automerge, and cleanup. This document itself authorizes
no implementation or benchmark run beyond the requested documentation change.

## External references

- [Apple: Optimize GPU renderers with Metal](https://developer.apple.com/videos/play/wwdc2023/10127/)
  describes specialization and background compilation with a generic fallback.
- [wgpu: PipelineCache](https://wgpu.rs/doc/wgpu/api/pipeline_cache/struct.PipelineCache.html)
  documents cache persistence, device compatibility, and backend limitations.

External API details were reviewed on October 28, 2026; recheck backend support
against the dependency version used when implementing this proposal.
