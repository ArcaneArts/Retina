# CPU optimization baseline

Source/release: `60c6f7e196b14ea5fae1747211240a7d5d5a7d69`, published 1.1.481.
The downloaded JAR SHA-256 was checked against its published digest. Its embedded
Mac native image and an independently built portable Cargo release were retained.
Both produced identical decompressed NBT for all 1,024 Overworld chunks. These
are local Apple M4 Max observations, not the user's unidentified hardware/workload.
The actual user-reported target remains approximately 50 → 15 seconds per region.

All ordinary cases use full lit MCA output, Zlib, native CPU, and two global Rayon
workers. The separate native budget is shared across dependency/assembly and
terrain lanes. Definition materialization uses the actual binary vanilla catalog,
seed 123456789, `normal` with structures. A second seed and an independently
exported representative datapack are included. The benchmark transports saved
definitions through production native APIs; it does not recreate registry data.

## Initial observations

| Case | Native budget | Callers | Regions | Median region seconds | Completed chunks/sec |
| --- | ---: | ---: | ---: | ---: | ---: |
| Published Overworld, diagnostic | 16 | 1 | 1 | 27.94 | 36.60 |
| Source Overworld | 16 | 1 | 1 | 25.55 | 40.08 |
| Nether, neighboring regions | 16 | 1 | 2 | 26.51 | 38.62 |
| End central | 16 | 1 | 1 | 0.56 | 1,802.87 |
| End outer | 16 | 1 | 1 | 3.88 | 263.65 |
| Overworld, small budget | 4 | 1 | 1 | 60.46 | 16.93 |
| Overworld, warm process, fresh neighboring coordinates | 16 | 1 | 2 | 20.57 | 49.78 |
| Overworld, concurrent regions | 16 | 2 | 2 | 43.93 | 40.20 |
| Overworld, second seed, negative region | 16 | 1 | 1 | 22.58 | 45.34 |
| Representative datapack, negative region | 16 | 1 | 1 | 36.42 | 28.12 |

The rows differ in terrain and process history; they are not A/B speedup claims.
There is only one process per table row. Desktop CPU activity is present and no
production p95 estimate is justified. Headline batches did not overlap this task's
builds or other benchmarks. Raw commands, measurements, native images and process
samples are retained under `~/.cache/retina/cpu-optimization-20261101/`. Compact
identities, CPU work, memory, corpus hashes and counts are in the adjacent JSON.

Every case recorded zero GPU jobs. The source cases also closed and reopened the
definition, generated zero replacement chunks and retained all 1,024 existing
records. This preservation check is outside generation timing and is explicitly
same-process definition reopen, not a cold-process startup measurement. The
opt-in real-native Python test additionally exercised two native workers, one
global Rayon worker, an overridden GPU environment request and both shader-wait
options; CPU execution never awaited GPU readiness.

## Profile and next experiments

A separate three-second sampling pair during a two-region diagnostic showed the
terrain workers spending substantial CPU time in interpolation `corner`, program
`evaluate`, and `interpolate`. The early sample's collapsed top frames included
9,609 corner, 8,947 evaluator and 3,236 interpolation observations across worker
threads. These are sample counts, not shares of region elapsed time. Later stacks
also include climate search, cave evaluation and aquifers.

The first diagnostic spent about 16.18 summed seconds in CPU columns and 6.92 in
masks/materials. Lighting used about 4.16 summed host seconds. Summed concurrent
or recursive intervals are not a disjoint elapsed-time breakdown. The source's
small-budget region averaged 2.14 scheduled cores; extra region callers at budget
16 did not establish better aggregate throughput in this initial pair.

Ranked opportunities, to be measured against retained builds:

1. **Interpolation lookup locality.** `cpu/program.rs::Evaluator::corner` hashes
   a wide key with all surface/detail context bits even for the common exact-zero
   context. Compact exact-zero keys may reduce hashing and cache footprint. Expected
   gain is potentially material given the stacks, but unquantified. Complexity/risk
   are low to moderate: preserve signed-zero bits, contextual values and the shared
   cache bound. Validate full Overworld/Nether NBT and mixed-context/eviction tests.
2. **Repeated cell lookups.** `Evaluator::interpolate` repeatedly retrieves the same
   vertices for nearby samples. An exact bounded last-cell cache may remove hash
   visits without changing interpolation math. Expected gain is potentially high;
   complexity/risk are moderate because partial/zero-weight corners, contexts and
   nested fields must remain correct. Profile and compare complete regions.
3. **Evaluator dispatch and data layout.** Prepared indices, dependency stacks and
   scalar dispatch remain visible after demand pruning. Expected gain is medium
   and uncertain; complexity/risk are moderate. Keep independent fresh/eager
   references, all opcodes and deep conditional/spline fixtures.
4. **Climate/caves, lighting and dependency reuse.** These become follow-up targets
   after reprofiling. Their current upside is unquantified. Measure actual query,
   hit/miss, dependency and allocation costs before changing coverage or scheduling.

## Reproduction

```sh
RAYON_NUM_THREADS=2 python3 scripts/native-region-benchmark.py \
  --backend cpu --library /path/to/retained-native-library \
  --definition /path/to/retina.dat --dimension minecraft:overworld \
  --out /path/to/new-output --assembly-threads 16 --parallel 1 \
  --count 1 --warmups 0 --origin-x 0 --origin-z 0 \
  --lighting enabled --preservation-check

RAYON_NUM_THREADS=2 python3 scripts/native-region-benchmark-ab.py \
  --backend cpu --baseline-library /path/to/baseline \
  --candidate-library /path/to/candidate --definition /path/to/retina.dat \
  --dimension minecraft:overworld --out /path/to/new-abba \
  --assembly-threads 16 --parallel 1 --trials 4 --count 2 --warmups 1 \
  --lighting enabled --preservation-check
```

Use diagnostics in separate processes. CPU readiness is identified from the actual
selected backend. `terrain_host_ms` names the host phase correctly; legacy
`gpu_ms` remains an alias for compatibility and is not GPU execution time. The
concurrency sweep accepts `--backend cpu` and defaults to one/two callers with
4/8/16 native budgets; compiler concurrency is explicitly inapplicable to CPU.
