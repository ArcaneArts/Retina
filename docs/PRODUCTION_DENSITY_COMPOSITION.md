# Saved production density composition

New Retina MCA and individual-chunk presets enable `density_composition: true`.
The GPU samples each registered interpolation field on its own lattice, then
evaluates the remaining arithmetic and range decisions at block positions.
Terrain, cave and aquifer decisions therefore retain the loaded graph's operator
ordering. Rust consumes the resulting masks and material runs using its existing
parallel assembly and final NBT path.

## Saved-world and datapack behavior

`RetinaChunkGenerator` stores the choice in its codec. An absent saved field
decodes as false, preserving existing worlds' legacy final-field interpolation.
Both new preset files explicitly encode true. `withDatapackGenerator` retains
the selected value while importing the active source/settings, and binding the
world writes that exact choice into its native registry profile. Saved blocks
are not regenerated. Constructor overloads used by existing integrations retain
their false default.

Native profiles without `registry_program.density_composition` also retain their
legacy semantics. Normal runtime selection now follows that profile flag rather
than requiring an environment opt-in. `RETINA_DENSITY_COMPOSITION=0` remains a
benchmark diagnostic disable; setting it to 1 cannot enable a false saved/native
profile. The explicit native profile flag is the authority.

This completes the earlier opt-in [composition development](GPU_DENSITY_COMPOSITION.md).
It is a fidelity change with a measured throughput cost, rather than a claimed
speedup over legacy interpolation. The goal asks to preserve loaded graph
semantics while remaining fast; it does not require every fidelity improvement
to have zero cost. Legacy worlds retain their previous selection.

## Startup and compilation

On measured capable Metal adapters, the existing single compiler thread prepares
both compact depth-one GPU interpreter layouts after the GPU owner and auxiliary
planners are ready. The composed inventory is queued first, then the separate
legacy inventory. Preparation overlaps native registry parsing. The composed
inventory includes actual call paths reaching composition in addition to the
surface/final-density graphs.

Each loaded profile still derives its own dependency mask. Reuse requires the
same scratch capacity, nesting depth, composition layout and a sufficient real
pipeline inventory. Incompatible or wide profiles compile their actual required
GPU interpreter. A real preparation error also attempts normal compilation;
there is no registry-readiness gate or vanilla-generator fallback.

Profile-specific specialization remains asynchronous in normal `auto` mode.
Pending or failed specialization uses the GPU interpreter. Each submission keeps
its captured pipelines through material count/emission and readback; later
compiler readiness cannot change that submission's layout. The two prepared
layouts have separate immutable sources and state. Other backends retain their
existing compatible selection without a new performance claim.

No new noise substitution, dense transfer, GPU computation stage or timing ABI
is introduced by selecting the existing composed path. F3 continues to report
actual stages and compiler progress. Resident atlases, interval bounds, sparse
lake-field stencils and covered samplers keep the extra density work on the GPU.

## Final twenty-region observations

Each warm process has two warmups and twenty measured adjacent regions, including
negative coordinates. The baseline library is `dd15324`; candidate runs omit the
composition environment override and use true/false native profiles. Serial
profile order is reversed between vanilla and Terralith. Two-caller latency
includes overlap and queueing; throughput uses actual total wall time.

| Profile / saved mode / callers | Mean request ms | Wall chunks/s | Peak RSS MiB | Twenty MCA files, bytes |
| --- | ---: | ---: | ---: | ---: |
| Vanilla / legacy / 1 | 241.70 | 4,230 | 963.7 | 156,205,056 |
| Vanilla / composed / 1 | 273.36 | 3,740 | 1,088.0 | 157,732,864 |
| Vanilla / legacy / 2 | 467.27 | 4,375 | 1,035.2 | 156,205,056 |
| Vanilla / composed / 2 | 439.04 | 4,511 | 1,159.5 | 157,732,864 |
| Terralith / legacy / 1 | 416.04 | 2,459 | 1,555.8 | 134,115,328 |
| Terralith / composed / 1 | 420.58 | 2,432 | 2,312.7 | 136,495,104 |
| Terralith / legacy / 2 | 895.66 | 2,263 | 1,557.3 | 134,115,328 |
| Terralith / composed / 2 | 1,034.12 | 1,937 | 2,192.6 | 136,495,104 |

These loaded-machine observations establish neither a universal composition cost
nor a speedup. Vanilla serial composition takes 13.1% longer here; Terralith
serial takes 1.1% longer, with an adverse concurrent Terralith result. This
fidelity selection remains substantially faster than the initial composed
prototype, but percentages from separate historical workloads are not combined.
RSS includes compiler state, GPU resources and comparison readers.

Legacy candidate output matches the retained library in all serial/concurrent
comparisons. Its retained serial observations were 243.78 ms / 4,169 chunks/s
vanilla and 398.96 ms / 2,564 chunks/s Terralith; the differences do not establish
a legacy speedup. Composed candidate output matches the retained composed output
and its own serial output under concurrency. All eight candidate processes match
**163,840 complete chunk NBT records**, including final palettes, heightmaps,
features and structure data.

Average composed uploads/readbacks are 14.297 / 45.938 MB per vanilla region and
12.847 / 42.584 MB per serial Terralith region, versus legacy 14.295 / 45.584 and
12.843 / 41.995 MB. A small Terralith concurrent difference follows sparse-query
batching. Resident fields themselves are not read back; the final material runs
and sparse replay queries account for terrain-dependent output variation.

## Fresh-entry automatic startup

These processes have no generation warmups, twenty regions each, fresh generic
entrypoint identities and normal `auto` specialization. Vanilla runs demand
before preload; the first Terralith pair reverses order and a second Terralith
pair restores it. Startup subprocesses use niceness 10. Base/specialized driver
caches are not cleared, and other desktop load is uncontrolled.

| Profile / pair | First region ms, demand → preload | Initialization + registration + first region ms | Subsequent mean ms, demand → preload |
| --- | ---: | ---: | ---: |
| Vanilla | 2,774.26 → 1,932.96 | 3,712.31 → 2,925.62 | 892.52 → 875.34 |
| Terralith / first | 2,758.94 → 1,481.52 | 4,198.81 → 2,646.29 | 1,757.14 → 1,748.51 |
| Terralith / repeat | 2,186.69 → 2,181.70 | 3,279.04 → 5,124.95 | 1,483.38 → 2,223.10 |

Specialization remains **pending at both the first and twentieth region** in
all six processes. Generation therefore completes without waiting for the large
optional specialized bundle. All startup outputs match each other and their
corresponding ready-specialized warm output. The adverse Terralith repeat is
retained: native registration rises from 1,040.42 to 2,840.84 ms and composed
interpreter compilation from 1,491.96 to 3,562.26 ms. The subsequent unused legacy
inventory takes another 12,523.48 ms on that compiler thread. The first Terralith
preload process also overlaps a short output-comparison read during initialization;
the repeat is recorded separately without that verification overlap.

Preloading overlaps real required compilation with registration, but these
observations do not establish a dependable startup speedup under varying load.
The first request can still await the uncovered part of actual interpreter
compilation. Full empty-driver-cache desktop startup remains outside the evidence.
Eight warm candidate comparisons, three startup pairs and six startup-to-ready
comparisons total **348,160 complete chunk NBT matches**; counts exclude warmups.
All six startup processes end without ready specialization, so this is an output
comparison against separately generated ready data, not a claim that those
processes tested a late activation event. Existing activation/reference harnesses
cover the captured-pipeline transition.

## Integrated checks and reproduction

The normal-default validation is `build/production-composition-validation.log`.
It passes `build gpuTest materialTest aquiferTest regionTest previewTest
structureTest blockFeatureTest datapackTest`, 65 native unit tests and 90 QA pass
events. Actual preset loading verifies true for both new modes, save/reopen true,
absent-field legacy decoding and legacy preservation through datapack import.
The Terralith importer verifies that true reaches its native registry profile.
A separate `registryTest blockFeatureTest shoreTest` run with Terralith loaded
passes 62 QA events: both pack presets, vanilla/Terralith feature references and
the vanilla shoreline oracle. It includes 2,509,927 Terralith survival cases and
65,024 provider/feature reference cases. Its log is
`build/production-composition-terralith-validation.log`.

Density, material and aquifer oracles compare actual Minecraft evaluators with
the GPU. Checks include independent interpolation fields, nested/sliced graphs,
negative and neighbor coordinates, incompatible reuse inventories, final metadata,
MCA/chunk consistency, structures, sparse features, saved edits and DH promotion.
Regular-ice snow exclusion remains validated in both assembly paths.

The complete actual-profile fixtures used below include 56 vanilla biomes,
2,438 material states, 182 decoration recipes and 30 structure definitions;
Terralith has 151 biomes, 3,946 states, 553 recipes and 58 definitions. They retain
templates, ores and cave data. Seed is 123456789, height 384 from Y −64.
Native builds use two Cargo jobs/two release codegen units; all controlled builds,
tests and benchmark processes run sequentially at lowered priority. The warm
runner applies nice twice, giving its region processes niceness 19; native
builds/tests and the startup subprocesses use niceness 10. Desktop load remains
uncontrolled.

Warm measurement reproduction:

```sh
nice -n 10 python3 scripts/native-region-benchmark.py \
  --library build/native-target/release/libretina_worldgen.dylib \
  --profile /absolute/path/to/full-profile.json \
  --out /absolute/path/to/fresh-private-output \
  --count 20 --warmups 2 --program-execution specialized --parallel 1
```

Omitting the composition override exercises normal native selection. False-flag
profiles exercise legacy compatibility. `--parallel 2` compares concurrent
requests; `--compare` performs complete decompressed NBT comparisons.

Fresh generic-entry startup reproduction:

```sh
python3 scripts/native-interpreter-compile-benchmark.py \
  --test-binary build/native-target/release/deps/retina_worldgen-<test-id> \
  --profile /absolute/path/to/full-profile.json \
  --out /absolute/path/to/fresh-private-output --count 20 \
  --comparison preload --density-composition enabled --program-execution auto
```

This records actual generic compile calls, automatic compiler status, first-region
and later latency, process RSS, region bytes and complete output equivalence.
New generic entry identities do not clear the whole operating-system driver cache;
specialized/base pipelines can remain warm. Java export, game loading, lighting
and rendering remain outside these native measurements. Forced specialization is
an explicit diagnostic that waits; its warmup is not normal world startup.

Private evidence is retained under `build/goal-baseline/production-composition/`.
The final native library and the native member of `build/libs/retina-0.1.0.jar`
match exactly, SHA-256
`00bf498693b02b4780e6941408821fc31ac8514a52cd0ba11c9837915975f410`.
