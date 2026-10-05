# Reusing loaded GPU interpreter pipelines

The generic interpreter previously compiled every world/cave entrypoint again
when a profile declared interpolation fields. Actual vanilla and Terralith
profiles only use interpolation instructions in their two density graphs. Their
climate, material and aquifer graphs can use the compact pipelines already loaded
by the GPU owner. This milestone reduces first-profile compilation; it does not
claim faster steady generation or resolve all startup work.

## Selection and compatibility

Rust parses the actual base WGSL with wgpu's Naga parser once and follows every
function call, including calls in loops, conditionals and switches. Density and
interval evaluators identify the registered graphs they evaluate; material and
aquifer dispatches resolve against the loaded profile. A compact base pipeline
is reusable only when none of those graphs contains an interpolation instruction.
Unknown graph arguments conservatively include all graphs and interpolation
inputs. Parsing failure retains the complete interpreter and lets real GPU
compilation proceed. Wide profiles keep their full 1,024-slot pipelines.

The analysis also follows `density_composed`. That switch changes scratch strides
and offsets as well as evaluation semantics. A stage reading a composed scratch
layout must retain the composed pipeline even if its graphs have no interpolation.
The composition integration fixture caught this dependency during implementation;
the corrected selection passes all 10,240 analytic height comparisons. Experimental
composition remains opt-in.

Dependency plans are cached per immutable profile. Interpreter bundle identity
includes scratch capacity, nesting depth, composition selection and the exact
reuse mask. Profiles with interpolated climate, materials or aquifer graphs thus
cannot receive an incompatible sparse bundle. Generated interpolation prepasses
always retain their own pipelines. Specialized bundles remain complete for their
selected stages; missing required pipelines still produce a real error.

No WGSL arithmetic, registry bytecode, spatial noise, request ABI, GPU scratch
layout or transfers are added by reuse. Both chunk and MCA requests use the same
selection. A batch captures its pipeline selection before dispatch, including
while specialization is pending. Saved profiles and generated terrain keep their
existing interpretation.

## First-profile measurements

Apple M4 Max / Metal; actual complete vanilla and Terralith profiles, seed
123456789, twenty adjacent regions per process, no generation warmups. The
test-only probe assigns fresh compute entrypoint names to the depth-specific
generic bundle. These are fresh shader identities, **not an emptied system driver
cache**. Already-loaded base pipelines remain unchanged. Device initialization
took 45–46 ms and profile registration 549–566 ms vanilla / 1,082–1,102 ms Terralith;
neither is included in the first-region column below.

| Profile | Additional pipelines, before → after | Bundle compile ms | First native region ms |
| --- | ---: | ---: | ---: |
| Vanilla | 21 → 10 | 6,265.78 → 3,155.13 | 6,517.78 → 3,410.41 |
| Terralith | 21 → 10 | 6,240.72 → 3,072.35 | 6,654.87 → 3,472.13 |

This final pair cuts additional compiler time by 49.6% / 50.8%. An earlier
four-process pair produced similar reductions, 6,313 → 3,057 ms vanilla and
6,249 → 3,116 ms Terralith. All 81,920 final cold-run chunk NBT records match the
retained reference. Module/layout creation was a small fraction of the cost;
per-entrypoint pipeline creation dominated it.

The ignored native `actual_interpreter_pipeline_compile` diagnostic measures
actual engine initialization, profile registration, every region and each
pipeline API call. `RETINA_COMPILE_PROBE=1` enables trace rows;
`RETINA_GENERIC_PIPELINE_TAG` gives fresh diagnostic entrypoint identities;
`RETINA_COMPILE_REUSE=0` selects the full bundle in test builds only. Set
`RETINA_PROGRAM_PARITY_PROFILE` to an exported profile and `RETINA_COMPILE_OUT`
to a fresh private directory. It defaults to twenty regions; the optional
`RETINA_COMPILE_REGIONS` changes the workload. All trace/tag/override code is
compiled out of the production library. F3's existing host command-encoding
time includes first-profile interpreter compilation; device timestamps do not.

## Warm generation and memory

Two warmup regions followed by twenty measured regions, forced ready mixed
specialization, production composition disabled. Before is the retained
`84f5333` library; after is the final cached-plan build. Processes run sequentially
and order is reversed between profiles. Desktop activity is uncontrolled.

| Profile / callers | Before → after chunks/s | Before → after mean request ms |
| --- | ---: | ---: |
| Vanilla / 1 | 5,708 → 5,595 | 179.11 → 182.73 |
| Vanilla / 2 | 6,803 → 6,859 | 296.74 → 297.74 |
| Terralith / 1 | 4,262 → 4,304 | 239.98 → 237.68 |
| Terralith / 2 | 4,860 → 5,016 | 394.29 → 407.72 |

These small mixed differences support essentially unchanged warm throughput,
not a sustained speedup claim. A repeated Terralith serial pair measured
258.52 → 263.19 ms. With concurrent callers, request means include queueing and
need not move in the same direction as total batch throughput.

MCA output size is unchanged: 153,845,760 bytes vanilla / 130,899,968 bytes
Terralith per twenty-region workload. Upload/readback volume remains at roughly
14.3/45.6 MB per region vanilla and 12.6/42.0 MB Terralith; small differences come
from request batching. Process peak RSS varies. The initial final Terralith
serial pair measured 1,601 → 2,187 MiB; the repeat measured 1,642 → 1,775 MiB.
Vanilla measured about 1,010–1,122 MiB across these cases. There is no demonstrated
RSS saving; the Terralith peak-memory variation remains follow-up work.

## Verification and evidence

The final build's cold, warm and automatic-mode cases compare **299,008 chunk
NBT records** with retained generation, including regeneration after real
specialization activation. Terralith automatic cases generate while compilation
is pending in both production and experimental modes, then compare again after
activation. No automatic case silently changes to Minecraft generation.

`scripts/native-interpreter-reuse-parity.py` registers five profile variants on
one engine: original, nonzero interpolated temperature, interpolated material
rules for the actually sampled biomes, interpolated aquifer barrier, and original
again. It uses odd 7×5 cells and negative coordinates. Climate and material
fixtures must visibly change their chunks, preventing vacuous parity. Comparisons
with the retained library pass **1,966,080 blocks and 5,120 complete column records**
across vanilla and Terralith. Returning to the original profile also preserves its
output. Unit tests exercise altered dependencies, layout selection, unknown graph
arguments, new entrypoints and wide-profile fallback.

`build`, `nativeGpuTest`, `interpolationTest`, `aquiferTest`, `regionTest`,
`previewTest` and `snowTest` pass on the final native build. Earlier in this
milestone, `nativeCoordinateGpuTest` and `materialTest` also pass. Checks cover
MCA edits/error preservation, temporary-region promotion and concurrent eviction,
registered material runs, cave/fluid classification and regular-ice snow exclusion.
The final library, packaged JAR native member and retained benchmark artifact are
byte-identical, SHA-256
`5279b81a6f7618dc7eb1065b1ae7b8d2e4114bf57c22c57c578a580a910fd0e8`.

Private evidence is in `build/goal-baseline/interpreter-reuse/`: actual profiles
via the primed-corner baseline, retained libraries, compiler traces, full MCA
outputs, effective switching fixtures and per-process reports. `evidence.json`
records hashes and final comparison totals. Final harness log:
`build/interpreter-reuse-final-validation.log`.

The subsequent [shared interpreter milestone](GPU_SHARED_INTERPRETER.md) reduces
the ten required generic pipelines to two on measured compact Metal profiles,
cutting another roughly two seconds from first-use compilation. It documents
the remaining synchronous cost and small warm-throughput tradeoff. Remaining
startup work includes generic activation and the already-loaded base bundle's
own first-use cost. This measurement does not cover
an empty-cache desktop launch or Java world creation. Efficient production
density composition and remaining feature/provider approximations also remain
open parts of the broader generation goal.
