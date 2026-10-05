# Shared compact GPU interpreter pipelines

Compact interpolation profiles on measured Metal hardware now compile one world
dispatcher and one cave dispatcher, rather than a pipeline for each required
stage. This builds on the [call-graph reuse](GPU_INTERPRETER_PIPELINE_REUSE.md)
selection: compatible stages still use already-loaded base pipelines. Actual
vanilla and Terralith profiles require two additional pipelines instead of ten.

Each selected 64-lane compute entry becomes a regular helper with its original
body. The shared entry switches on a uniform 32-bit immediate value and calls
that helper with the original global invocation ID. Every stage retains its
dispatch geometry, command ordering, registered programs and scratch layout.
The selector travels in the compute command, with no new buffer upload, copy,
readback or CPU spatial calculation. The 256-lane material prefix scan remains
an independent pipeline.

Pipeline selection carries both the pipeline and its selector. Deferred material
emission captures the selector alongside its immutable job buffers, so later
jobs and profile changes cannot select another stage accidentally. Specialized
pipelines and reused base pipelines have no selector. Source-generation failures
also unwind the actual wgpu validation scope.

Default selection enables sharing on Metal adapters advertising the required
immediate capability and at least four immediate bytes. Other backends and wide
1,024-slot interpreters retain independent pipelines. This is execution selection
from actual adapter capabilities, with no profile-readiness gate. An explicit
`RETINA_INTERPRETER_DISPATCH=1` requests the shared compact path; `0` selects the
independent reference. If an explicitly requested GPU capability is unavailable,
the actual device request returns its error. No Retina job falls back to normal
Minecraft generation.

The option is immutable for a GPU owner. Existing generic bundle identity still
includes capacity, interpolation nesting depth, density-composition layout and
the exact reuse plan. The existing background profile-specific compiler and
mixed Metal specialization remain in use. Both chunk and MCA generation select
the same pipelines; saved profiles and previously generated chunks are unchanged.

## First-use compilation

Apple M4 Max / Metal, actual complete vanilla and Terralith profiles, seed
123456789, twenty adjacent regions per process without generation warmups.
Each generic bundle has fresh diagnostic entrypoint identities. Base pipelines
retain their normal identities; these measurements do **not** empty the driver
cache or measure Java registry export and desktop world creation.

| Profile | Pipelines, direct → shared | Bundle compile ms | First native region ms |
| --- | ---: | ---: | ---: |
| Vanilla | 10 → 2 | 3,090.54 → 941.52 | 3,349.58 → 1,206.09 |
| Terralith | 10 → 2 | 3,139.64 → 936.60 | 3,548.16 → 1,342.69 |

Additional compilation time falls 69.5% / 70.2%, saving 2.14 / 2.21 seconds in the
first native region. Earlier two-region and twenty-region pairs gave similar
results. Final device initialization takes 46–50 ms and profile registration
557–558 ms vanilla / 1,083–1,099 ms Terralith; these are separate from region time.
There is still about 0.94 seconds of synchronous generic compilation, plus the
base bundle's own first-use cost. This milestone reduces that stall rather than
claiming complete asynchronous interpreter activation.

## Warm throughput and memory

Final release library versus retained `792495b`, two warmups followed by twenty
regions, ready mixed specialization and production density composition disabled.
Processes run sequentially with reversed order between profiles. Desktop load is
uncontrolled. Request means for concurrent callers include queueing.

| Profile / callers | Direct → shared chunks/s | Direct → shared mean request ms |
| --- | ---: | ---: |
| Vanilla / 1 | 5,545 → 5,448 | 184.40 → 187.69 |
| Vanilla / 2 | 6,612 → 6,768 | 308.87 → 302.07 |
| Terralith / 1 | 4,186 → 4,136 | 244.37 → 247.33 |
| Terralith / 2 | 5,142 → 5,084 | 383.90 → 387.87 |

These small mixed differences establish no steady-generation speedup. The
interpreter-only final cold processes average 250.66 → 256.03 ms vanilla and
507.90 → 527.45 ms Terralith after their first region, a 2.1% / 3.8% increase;
an earlier pair measured approximately 1% increases. Sharing is retained for
its measured startup saving. Background activation of independent generic
pipelines is a possible follow-up if that warm tradeoff can be removed without
extra compiler contention or a large memory cost.

Twenty-region file totals remain 153,845,760 bytes vanilla / 130,899,968 bytes
Terralith. Upload/readback sizes remain about 14.3 / 45.6 MB per region vanilla
and 12.6 / 41.9 MB Terralith. Small differences reflect request batching and
existing optional diagnostics, rather than a new transfer format.

Final cold peak RSS is about 1,450–1,452 MiB vanilla and 1,605–1,606 MiB Terralith.
Warm processes range about 1,030–1,080 MiB vanilla and 1,583–2,141 MiB Terralith.
Those include profiles, compiler state, buffers and comparison readers. There is
no demonstrated memory saving; the existing Terralith process variation remains.

## Validation and reproduction

The final cold, warm and automatic-mode checks compare **258,048 chunk NBT
records**, including regeneration after actual specialization activation. Both
Terralith automatic cases generate two regions while specialization is pending,
in production and experimental composition modes, and match their retained
references before and after activation.

The effective profile-switch fixtures also match **1,966,080 blocks and 5,120
complete column records**. They add nonzero interpolated climate, interpolated
materials for sampled biomes and an interpolated aquifer barrier, using odd 7×5
cells and negative coordinates, then return to the original profile. These
exercise shared material emission as well as different reuse masks; climate and
material fixtures must actually change the generated chunks. An initial run
selected the earlier fixture reference and failed as expected on different
climate input; the corrected comparison uses identical effective fixture JSON.

The final `build`, `nativeGpuTest`, `interpolationTest`, `aquiferTest`,
`regionTest`, `previewTest` and `snowTest` pass. This includes 57 native unit
checks, parallel requests, 10,240 composed-height comparisons, 3,072,000 aquifer
voxels, MCA decoding and edits, temporary-region promotion/concurrent eviction,
and bare frozen-ocean ice versus snowy supported land. Source checks validate
the generated WGSL with Naga, compare unchanged stage bodies and retain the
material prefix scan's original workgroup.

`scripts/native-interpreter-compile-benchmark.py` runs the ignored native
`actual_interpreter_pipeline_compile` diagnostic in two fresh processes with
unique shader identities, records per-pipeline compiler timings and peak RSS,
and compares all decoded chunk NBT. Build the release test executable with the
repository's two-job Cargo configuration, then pass its exact path:

```sh
python3 scripts/native-interpreter-compile-benchmark.py \
  --test-binary build/native-target/release/deps/retina_worldgen-<test-id> \
  --profile /absolute/path/to/exported-profile.json \
  --out /absolute/path/to/fresh-private-output --count 20 --order shared-first
```

`scripts/native-region-benchmark.py --interpreter-dispatch enabled|disabled`
provides the matched warm diagnostic. Existing F3 host command-encoding timing
includes generic first-use compilation; device timestamps retain their stage
attribution. No new GPU work stage or timing ABI is introduced.

Private evidence is retained in `build/goal-baseline/shared-interpreter/`,
including compiler traces, complete MCA output, effective profile switches,
warm serial/concurrent runs, automatic activation checks and `evidence.json`.
The final library, retained benchmark library and JAR native member are identical,
SHA-256 `4dbb8e21ee4d8d4079a00903c26efcf198deb0419d77e13fbec93ae239719a2a`.
Harness log: `build/shared-interpreter-validation.log`.

The subsequent [background preparation milestone](GPU_INTERPRETER_PRELOAD.md)
overlaps a compatible density inventory with native profile parsing, reducing
initialization-through-first-region latency another 32% vanilla / 39% Terralith.
It preserves normal compilation for different dependency masks and documents
remaining waits and cold-start scope.

Full cold startup, fast production density composition and remaining registered
feature/provider approximations remain open parts of the broader generation goal.
