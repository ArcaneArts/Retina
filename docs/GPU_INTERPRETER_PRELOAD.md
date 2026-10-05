# Background preparation of compact density pipelines

The measured Metal path now starts preparing its compact, depth-one density
interpreter after the GPU owner and auxiliary planners are ready. The existing
compiler thread performs that work while Rust parses the loaded registry profile.
The first compatible request captures the resulting pipelines and the actual
profile's reuse mask. This reduces startup latency without changing shader
arithmetic, dispatch order, scratch layout, registered data or GPU transfers.

The initial experiment compiled every world/cave stage. It matched chunk output
but increased fresh compilation from about 0.94 to 1.36 seconds, leaving much less
overlap benefit for vanilla. The retained implementation prepares only call paths
reaching the registered surface/final density graphs, using the actual WGSL
dependency analysis. This keeps compilation at about 0.93–0.95 seconds.

## Selection and lifecycle

The prepared inventory is a set of available pipelines, rather than a profile
default. Rust derives it from the real shader call graph. Surface and final density
occupy their defined registry-program ABI slots; unknown dynamic calls retain the
conservative complete path. Every actual loaded profile still receives its own
dependency plan. A prepared bundle is usable only when every stage it omitted
is also reusable by that profile, and capacity, nesting depth and layout match.

Profiles with interpolation in climate, material or aquifer graphs that need
additional stages receive the existing on-demand compilation. Wide interpreters,
other nesting depths and experimental composed layouts also retain their
existing paths. No biome parameters, source data or loaded graph results are
substituted. Returning to a previous profile keeps its cached selection.

The first compatible request may await the real preparation result. There is no
profile-readiness gate, temporary invalid terrain or fallback to Minecraft
generation. An actual compiler error is logged; that request then attempts the
normal on-demand GPU compilation. A test-only invalid WGSL injection verifies
this recovery with real shader API errors and complete region output.

Preparation starts after the GPU startup response and auxiliary planner creation,
so the background API calls cannot hold up that initialization sequence. It uses
the existing single compiler thread and is queued before profile-specific
specialization. Specialization still runs asynchronously, with the interpreter
available while pending. Each submission retains its captured pipelines through
its deferred material pass and readbacks. The prepared state lives with its GPU
owner; it adds neither a detached per-profile compiler nor repeated region work.

The default enables preparation when shared compact dispatch is enabled and
the production, non-composed layout is selected. That currently means measured capable Metal
adapters; unmeasured backends retain their earlier selection. Diagnostic
`RETINA_INTERPRETER_PRELOAD=0` disables preparation. The existing
`RETINA_INTERPRETER_DISPATCH` option remains separate. Generated chunks and saved
profiles retain their previous interpretation.

## Startup measurements

Apple M4 Max / Metal, actual complete vanilla and Terralith profiles, seed
123456789. Twenty adjacent regions per process, no generation warmups, fresh
generic compute entrypoint identities and reversed process order between profiles.
Base pipelines keep normal identities; these are not emptied-driver-cache runs.

| Profile | Native initialization ms, demand → preload | Registration ms | First region ms | Initialization through first region ms |
| --- | ---: | ---: | ---: | ---: |
| Vanilla | 46.49 → 46.34 | 544.27 → 541.80 | 1,221.96 → 647.74 | 1,812.71 → 1,235.88 |
| Terralith | 47.21 → 47.74 | 1,092.63 → 1,065.59 | 1,347.14 → 407.86 | 2,486.97 → 1,521.19 |

The summed native latency falls 31.8% / 38.8%, saving 577 / 966 ms. Input-file
parsing by the diagnostic before initialization, Java registry/template export,
world loading, lighting and rendering are outside this measurement. Both modes
compile two additional pipelines; the benefit comes from overlap rather than
moving uncounted work elsewhere. An earlier two-region pair measured similar
reductions, 31% vanilla and 40% Terralith.

The first vanilla request still awaits roughly the uncovered portion of its
0.94-second compilation. Terralith's longer native registration largely covers
that work. This is reduced dependency latency, rather than a claim of zero cold
startup cost. The base bundle's own cold activation and uncommon on-demand
layouts remain separate startup work.

## Warm generation and resource use

Final library versus retained `19e8d93`, two warmup regions followed by twenty
measured regions, ready mixed specialization, production composition disabled.
Runs are sequential, with reversed order between profiles; desktop load is
uncontrolled. Concurrent request means include queueing.

| Profile / callers | Demand → preload chunks/s | Demand → preload mean request ms |
| --- | ---: | ---: |
| Vanilla / 1 | 5,531 → 5,549 | 184.85 → 184.25 |
| Vanilla / 2 | 6,527 → 6,783 | 304.15 → 297.34 |
| Terralith / 1 | 4,080 → 4,011 | 250.71 → 254.99 |
| Terralith / 2 | 4,700 → 4,966 | 419.99 → 411.55 |

These differences establish no reliable steady-generation speedup. Interpreter-only
cold processes average 253.72 → 252.03 ms vanilla and 515.84 → 535.18 ms Terralith
after the first region; that mixed result also prevents such a claim. The
compatible prepared shader inventory and arithmetic match the previous shared
on-demand bundle.

Twenty-region file totals remain exactly 153,845,760 bytes vanilla and
130,899,968 bytes Terralith. Average input/readback sizes remain about 14.3 / 45.6
MB per vanilla region and 12.6 / 41.9 MB Terralith, with small existing batching
and diagnostics differences. No new data format or round trip is introduced.

Final cold peak RSS is about 1,450–1,459 MiB vanilla and 1,406–1,516 MiB Terralith.
Warm runs range 1,017–1,060 MiB vanilla and 1,618–1,738 MiB Terralith, including
profiles, compiler state, GPU buffers and comparison readers. These variations
do not establish a memory saving.

## Validation and reproduction

Final cold, warm, automatic activation and failure-recovery checks compare
**262,144 chunk NBT records**. Effective profile switches also match **1,966,080
blocks and 5,120 complete column records**, including nonzero interpolated climate,
interpolated materials for sampled biomes, aquifer-barrier interpolation, odd 7×5
cells, negative coordinates and return to the original profile. Incompatible
inventories correctly use normal compilation.

Two real background WGSL failures are injected in test builds. The following
four regions contain **4,096 matching chunk records**, confirming real recovery
instead of merely checking an error flag. Terralith automatic cases generate
while specialization is pending in both production and experimental layouts,
then match again after actual activation.

`build`, `nativeGpuTest`, `interpolationTest`, `aquiferTest`, `materialTest`,
`regionTest`, `previewTest` and `snowTest` pass. Checks cover 58 native units,
parallel requests, 10,240 composed-height comparisons, 3,072,000 aquifer voxels,
6,680,576 registered material voxels, MCA decoding and edits, temporary-region
promotion/concurrent eviction and bare frozen-ocean ice. The inventory unit check
also exercises actual compatible, climate/material/aquifer, wide and composed
dependency masks.

The ignored native `actual_interpreter_pipeline_compile` diagnostic and
`scripts/native-interpreter-compile-benchmark.py --comparison preload` run two
fresh processes, trace actual compilation and compare complete decoded chunks:

```sh
python3 scripts/native-interpreter-compile-benchmark.py \
  --test-binary build/native-target/release/deps/retina_worldgen-<test-id> \
  --profile /absolute/path/to/exported-profile.json \
  --out /absolute/path/to/fresh-private-output --comparison preload \
  --count 20 --order candidate-first
```

`scripts/native-region-benchmark.py --interpreter-preload enabled|disabled`
provides the warm diagnostic. Both scripts record the chosen option; the compiler
diagnostic also reports the sum of initialization, registration and first-region
latency. The invalid-source injection, `RETINA_PRELOAD_SOURCE_ERROR=1`, is compiled
out of production.

F3 retains the existing stage attribution. First-request host encoding includes
any residual interpreter wait; profile-specific compilation remains separately
reported. Preparation adds no GPU compute work stage or timing ABI.

Evidence is retained in `build/goal-baseline/interpreter-preload/`, including
complete MCA output, fresh compiler traces, retained libraries, effective switches,
serial/concurrent reports, automatic activation, error logs and `evidence.json`.
The final library, JAR native member and retained benchmark library are identical,
SHA-256 `d4c75f5345bfc03d4ec375419ab87e32114bab32accea10008511c7333a9a0bf`.
Final harness log: `build/interpreter-preload-validation.log`.

Fast production density composition and remaining registered feature/provider
approximations remain required parts of the broader goal, along with its final
integrated audit. Full empty-cache desktop startup remains unmeasured.
