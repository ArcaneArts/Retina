# Registered GPU interpolation scopes

Registered `interpolated` density expressions now retain their own input graph
and X/Z and Y cell sizes. The Java exporter interns those fields and places child
fields before their callers. Resident bytecode contains the field descriptors and
input programs; Rust and Java never evaluate spatial noise for these operators.

Both GPU evaluators floor coordinates onto the field's global lattice, evaluate
the input at its surrounding corners, and interpolate X, Y and Z in that order.
An aligned grid node evaluates the child once. Aligned axes skip duplicate upper
corners. Negative coordinates use floor rather than truncation. Nested operators
retain separate lattices, including mismatched cell sizes. A slice outside an
operator changes its lookup position; a slice inside it changes the child input.
Arithmetic outside an operator runs after interpolation.

The compact interpreter generates acyclic evaluator levels for the imported
nesting depth. Specialized WGSL emits a function for each input graph and field.
Reusable horizontal expressions inside field inputs participate in the existing
GPU column cache, including sharing with other field inputs and primary graphs.
Queries between cached columns or outside their extent evaluate the same graph
directly. Each batch selects its pipelines and scratch layout before submission.
[Resident field lattices](GPU_INTERPOLATION_CACHE.md) subsequently add reuse of
the individual field samples; the measurements below describe the original
direct-sampling milestone.

Old saved profiles without the new field table still parse with an empty table.
The bytecode header is internal and is rebuilt from each profile; public request,
readback and timing layouts are unchanged. The direct-sampling milestone adds no
GPU dispatch or host readback for interpolation. Subsequent field caching adds
GPU-only prepasses measured in the existing height/climate stage.

## Scope and remaining approximation

Direct registered graph samples preserve these operators. The terrain pipeline
still samples the complete final field on its existing shared density lattice,
then interpolates that lattice for surface extraction and cave classification.
This can approximate arithmetic outside wrappers and miss intermediate nodes of
a finer child lattice. It is not yet per-voxel composition from independent,
resident interpolation-field lattices. That and their efficient reuse remain
required work; this milestone supplies the operators and correct scoped graphs.

Subsequent [experimental block-position composition](GPU_DENSITY_COMPOSITION.md)
preserves that arithmetic in analytic terrain checks. It is disabled by default
until its whole-region performance is suitable for production.

Vanilla exports five distinct fields with 4 x 8 x 4 cells. Terralith exports seven,
including a 4 x 4 x 4 field. The default shared final lattice remains 4 x 8 x 4.
The benchmark coordinates retain identical final chunk data because the imported
operators are evaluated on aligned nodes there; this does not establish equivalent
results for arbitrary datapacks or off-grid queries. GPU registered noise remains
an approximation of Minecraft's noise implementation.

Generic interpreter pipelines are cached by scratch capacity and nesting depth.
The first use of a new combination compiles synchronously; later profiles reuse
it. Profile specialization continues on the existing compiler thread, with the
interpreter used while pending. Cold compilation cost remains an open performance
requirement and must be reported separately from warmed throughput.

## Validation

`interpolationTest` uses actual Minecraft 26.3 density samplers for analytic
fields, arithmetic after interpolation, inner/outer slices and three nested,
mismatched lattices. All 64 analytic samples distinguish the first interpolated
field from flattening. It checks 2,048 generated columns across negative and
chunk-boundary coordinates, both GPU execution modes, and chunk halo consistency.

`nativeInterpolationGpuTest` compares specialized and interpreter roots, and
checks interpolated GPU noise against an independent eight-corner noise reference.
These tasks are dependencies of `gpuTest`. Native tests verify field dependency
ordering, invalid references/cells, compact-register liveness and horizontal reuse
inside child graphs without treating material-context programs as cacheable.

The full build and GPU, material, aquifer, region, preview, structure,
block-feature and actual Terralith suites pass. Native unit validation passes
46 tests, with three separately invoked GPU/fixture tests ignored by default.
Full-profile root checks pass with the compact interpreter and specialized column
cache, including negative coordinates and odd cache spacing beyond f32's exact
integer range. The parity harness now invokes the production aquifer entrypoints
directly; those graphs intentionally remain outside material dispatch.

Across baseline, repeat, concurrent, pending-specialization and fully specialized
runs, 247,808 complete decompressed chunk NBT comparisons pass. This includes
2,048 chunks regenerated after background specialization completed. Preview checks
cover the 1,024-region temporary cache, partial/full promotion, existing edits,
parallel requests, serialized metadata and save isolation. The sampler fixtures
establish fidelity changes that the unchanged default MCA outputs do not reveal.

## Measurements

Local raw artifacts are under `build/goal-baseline/interpolation/` and
`build/interpolation-validation.log`. The baseline native library is `06af2e9`;
its profiles predate the field table. New profiles come from
`exportBenchmarkProfile`, including actual structures and decorations. Use each
library with its corresponding profile. The common benchmark settings are Metal
on Apple M4 Max, seed 123456789, two warmups and twenty adjacent regions.

```sh
python3 scripts/native-region-benchmark.py \
  --library build/native-target/release/libretina_worldgen.dylib \
  --profile build/interpolation-vanilla.json \
  --out build/interpolation-benchmark --count 20 --warmups 2 \
  --program-execution interpreter
```

The runner records first initialization, profile registration, first-region
latency, mean region time, actual throughput, worker/device stages, peak RSS,
transfers and file sizes. Concurrent throughput uses measured total wall time;
it is not calculated from mean overlapping request latency.

| Profile / interpreter run | Before mean region ms | After mean region ms | Before chunks/s | After chunks/s |
| --- | ---: | ---: | ---: | ---: |
| Vanilla, first comparison | 244.50 | 256.19 | 4,184 | 3,993 |
| Vanilla, repeat / final build | 246.09 | 249.07 | 4,157 | 4,107 |
| Terralith, first comparison | 500.12 | 502.65 | 2,047 | 2,036 |
| Terralith, repeat / final build | 501.08 | 504.55 | 2,043 | 2,029 |

These measurements show a small interpreter cost for the added operator semantics,
not a throughput improvement. Background desktop load was uncontrolled. The first
comparison precedes restoration of child horizontal caching, which is used only
by specialization; the final build includes that restoration and all validation.

The final library's execution modes have these separate twenty-region results:

| Profile / execution / callers | Mean region ms | Actual chunks/s |
| --- | ---: | ---: |
| Vanilla / interpreter / 1 | 249.07 | 4,107 |
| Vanilla / interpreter / 2 | 436.26 | 4,553 |
| Terralith / interpreter / 1 | 504.55 | 2,029 |
| Terralith / interpreter / 2 | 943.67 | 2,158 |
| Vanilla / warmed specialized / 1 | 204.42 | 5,003 |
| Vanilla / warmed specialized / 2 | 309.99 | 6,541 |
| Terralith / warmed specialized / 1 | 333.92 | 3,064 |
| Terralith / warmed specialized / 2 | 563.06 | 3,568 |

The specialized rows compare execution modes of this build, not an optimization
gain over the previous build's specialization. Nine vanilla / twenty-three
Terralith horizontal fields remain selected, including fields inside interpolation
inputs. File totals remain exactly 153,911,296 / 131,751,936 bytes. Dynamic
upload/readback remain approximately 14.29 / 45.62 MB per vanilla region and
12.59 / 41.95 MB per Terralith region. Readback formats are unchanged; small
transfer differences reflect sparse-query coalescing and one-time resident inputs.

Final interpreter serial peak RSS was 925 / 1,010 MiB, versus 897 / 1,007 MiB in
the repeated baseline. Concurrent interpreter runs peaked at 972 / 1,083 MiB.
Warmed specialized serial processes peaked at 1,013 / 1,751 MiB; their warm driver
cache profile compilation took 444 / 2,288 ms. These figures include compilation
and native/profile memory, rather than only the extra interpolation metadata.

The first changed-source process measured 2,842 ms native initialization and a
4,897 ms first region, including synchronous generic depth-one pipeline creation.
Later initialization took about 40 ms and interpreter first warmups 310 / 616 ms.
This observed cache warmth must not be described as eliminating cold startup.

No-warmup automatic runs produced their first regions in 286 / 467 ms with warm
generic shaders. Profile registration was a separate 530 / 1,008 ms. They generated
all twenty regions while specialization was pending, at 3,901 / 1,981 chunks/s
(262.24 / 516.73 ms mean). Background compilation took 24.04 / 100.98 seconds;
the harness then awaited readiness and verified regenerated NBT. Peak process RSS
including this cold profile compilation reached 1,054 / 1,916 MiB. These are
observed changed-profile runs after other validation warmed shared driver code,
not measurements from an empty system shader cache.

Tested final native SHA-256:
`c7b4c20148daeb466182166da1266114cedc90536452c41f44e74492a8932fc8`.
The local `manifest.json` records both retained libraries and profile hashes;
`results-summary.json` combines the individual measurements. No consistent
end-to-end speedup is claimed for this fidelity milestone.
