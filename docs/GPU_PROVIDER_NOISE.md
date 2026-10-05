# Registered GPU provider noise and ordered feature replay

Retina exports Minecraft 26.3's initialized `NormalNoise` Perlin stacks and uses
resident GPU sampling for registered `noise`, `dual_noise` and `noise_threshold`
block-state providers. Their material choices now reach production simple
blocks, block columns, vegetation patches, mushroom caps/stems and fallen-tree
trunks/decorators, including providers nested inside supported wrappers.

`ProviderNoiseProfile` initializes each stack with its actual registered provider
seed, then exports permutations, offsets, frequencies and float amplitudes.
Initialization happens once during profile export. Java and Rust do not simulate
spatial provider noise during generation. The profile contains four stacks/eight
layers for vanilla and 32 stacks/188 layers for the tested Terralith version.
Terralith exports 490 decoration recipes, up from 463 before spatial providers
were supported. Counts, thresholds, state order and duplicate states come from
loaded feature data.

## Sparse GPU sampling and coordinate semantics

Each query uploads sixteen bytes: signed integer X/Y/Z plus a resident program
ID. Each result is one four-byte float, plus sixteen timestamp bytes per batch
when available. The stack tables upload once per native profile. The provider
and placement-count samplers reuse their point/output/readback buffers, layouts,
timestamp resources and serialized GPU ownership. Active-length bindings prevent
small batches from reading stale points in larger reused buffers.

Fast provider coordinates multiply integer positions by the registered scale in
double precision in Minecraft. Retina folds that scale into each layer frequency
and uses 48-bit modulo phases with forty fractional bits, multiplied by integer
coordinates using WGSL `u32` limbs. Perlin permutations repeat every 256 cells;
Minecraft's normal coordinate wrap period is divisible by 256. This retains far
integer-coordinate fractions without GPU `f64`.

`DualNoiseProvider`'s slow path is different: Minecraft first rounds integer
position times `slow_scale` to a float. The GPU performs that float multiplication,
then decodes the coordinate and double layer-frequency significands and evaluates
their phase product using integer limbs. The full frequency is retained, including
frequencies above 256; removing whole periods before multiplying a fractional
coordinate would be incorrect. Program entries distinguish the two coordinate
modes. Negative coordinates, zero stacks and an actual eighty-layer stack pass
reference checks. Storage-backed loops have no arbitrary 64-layer rejection.

The shader evaluates registered gradients, quintic fade, trilinear interpolation
and the ordered weighted stack sum. Frequency/offset quantization and GPU float
arithmetic remain approximations; very close selection thresholds can differ.
This does not promise Minecraft seed parity or cross-backend bit identity.

## Ordered replay and selectors

Every global anchor owns its placement stream and live overlay. A material lookup
uses an immutable map of resolved GPU values. An unresolved lookup records its
actual position/program and returns a provisional value for query discovery. An
anchor with missing samples publishes no commands. The engine sorts/deduplicates
the union of missing queries across anchors, submits one sparse GPU batch and
retries only unresolved anchors. Completed anchors retain their final command
buffers. The process stops when every executed lookup is resolved; there is no
fixed retry cutoff, fabricated final material or vanilla-generator fallback.

A retry reconstructs that anchor's stream and ordered overlay from its original
seed. This matters for noise-threshold branches, nested patches, nullable rules,
live heightmaps and survival checks: a changed material can change later draws or
placements. Provisional writes remain local to the discarded replay. Indexed
collection retains anchor order for final chunk/region commands. GPU work is
batched across anchors, rather than invoked for each block or attempt.

- `noise` clamps `(1 + noise) / 2` to `[0, 0.9999]` and indexes the loaded state
  list, preserving duplicate entries. It consumes no placement randomness.
- `noise_threshold` uses the loaded threshold, uniform low/high lists,
  `high_chance` and default state. Rust preserves each branch's random draws.
- `dual_noise` derives local variety from the slow field, then uses the fast
  field to choose a possible-state index. Only the slow sample at that chosen
  offset is needed: the other possible-state samples have no side effects or
  random draws. Offset arithmetic retains Minecraft's signed integer wrapping.

The diagnostic feature bridge uses the same provider/replay logic as production.
F3's colored **Provider noise** row now receives actual region/chunk device time.
This milestone used timing ABI 6 (26 stages, a 240-byte snapshot and a 280-byte
detailed region report). [Separate lake timings](GPU_LAKE_TIMINGS.md) subsequently
extend it to ABI 7 with 29 stages and 264/304-byte reports. The legacy 40-byte
region report is unchanged. Device time overlaps host/planning time and is not
added to region latency.

## Reference and compatibility checks

`providerNoiseTest` collects actual configured/inline features and provider holder
references. It compares exported stacks against Minecraft's own `Noise.get`,
including the distinct fast/slow coordinate arithmetic. Fixtures cover four
seeds, three scales, four octave bases, normalization, missing octave weights,
zero stacks and more than 64 layers. Points include adjacent integers beyond
float's exact range and positions to ±30 million blocks. Count/provider batches
alternate through shared buffers, including eight calls from four workers.
Invalid IDs return the actual native error without GPU transfers.

The final reference corpus contains 80,896 vanilla / 140,288 Terralith queries.
Maximum absolute errors were 0.00001896 / 0.00003812, including distant coordinates
and actual 80-layer stacks.

The feature harness compares actual Minecraft output with production Rust logic
using the same controlled random stream and substrate. Registered vanilla and
Terralith features cover simple blocks, columns, nested patches, mushrooms and
fallen trees. Additional mixed-provider fixtures include rotations, nullable
rules and threshold branches, with a randomized tail layer that exposes random
stream differences. Forty fixtures × four terrain/height cases × 64 seeds produce
10,240 comparisons per pack; all 20,480 comparisons pass. Registered feature
checks add 13,376 vanilla / 27,520 Terralith comparisons.

Chunk/MCA checks compare every block and all six final heightmaps in sampled
chunks, including negative coordinates and region edges. A forced mixed-provider
region verifies that the production region report contains provider device time.
The full build, native unit/GPU, count reference, preview and region checks pass,
including DH temporary caching/promotion, parallel requests, concurrent edits,
partial promotion, eviction, save isolation, MCA decoding and failure cleanup.

## Whole-region measurements

Six twenty-region runs used complete profiles with structures/decorations,
including two warmups per run. Runs were sequential at `nice -n 10`, with no
compilation/tests alongside them, on Metal / Apple M4 Max. The GPU interpreter
was forced to keep large density-program compilation out of the measurements.

| Profile | Concurrent calls | Mean region ms | Chunks/s |
| --- | ---: | ---: | ---: |
| Prior vanilla recipes / current replay framework | 1 | 243.55 | 4,200.4 |
| Prior Terralith recipes / current replay framework | 1 | 484.27 | 2,113.4 |
| Spatial vanilla providers | 1 | 245.13 | 4,172.9 |
| Spatial vanilla providers | 2 | 434.82 | 4,702.2 |
| Spatial Terralith providers | 1 | 489.19 | 2,092.2 |
| Spatial Terralith providers | 2 | 912.12 | 2,243.4 |

The prior-profile runs matched all 40,960 retained chunk NBT records. The spatial
concurrent runs matched another 40,960 serial records. Spatial-provider outputs
are intentionally different from the older approximations, so those two recipe
sets are not compared for NBT equality. Region times are observations under the
current system load; this fidelity change does not establish a generator speedup.

Initialization measured 37.5–56.5 ms. Profile registration was 498.2–504.7 ms
vanilla / 989.9–1,037.7 ms Terralith. Spatial-profile first warmup regions took
262.7–337.0 ms vanilla / 545.1–545.8 ms Terralith, including lazy provider-pipeline
setup when used. Peak RSS for spatial runs was 903.0–1,033.7 MB vanilla and
1,022.4–1,169.0 MB Terralith. Twenty spatial region files totaled 153,911,296 and
131,739,648 bytes respectively, identical between serial/concurrent runs.

Spatial serial transfers averaged 14,289,824 bytes upload / 45,622,400 bytes
readback per vanilla region and 12,461,469 / 41,921,215 per Terralith region.
Compared with prior recipes, increases were about 18.1 KB / 4.5 KB vanilla and
76.3 KB / 19.1 KB Terralith per region, including newly supported feature/count
work. Provider device time averaged 0.0076 ms vanilla / 0.0188 ms Terralith per
serial region; it overlapped region planning. Larger concurrent worker timers
reflect contention and summed work, rather than independent wall-time savings.

The measured native SHA-256 was
`c13960c990614cc1e210fb0361ea15f8300e1f837829effa6b6bab105597139a`.
A subsequent profile-validation change removes only the unused 64-layer ceiling
and corrects its scale error message; the expanded stack-reference test verifies
that change. The final tested and packaged native SHA-256 is
`471685465e646edf5e306066f031f3e926600b06574a0312c56a0fe97e620f11`.

Reproduction:

```sh
./gradlew build nativeGpuTest providerNoiseTest decorationCountTest \
  blockFeatureTest previewTest regionTest \
  -PtestPack=/Users/cyberpwn/Downloads/Terralith_v2.6.5+26.3.zip \
  --no-parallel --max-workers=1
```

Full profiles and sampler measurements are in ignored
`build/provider-noise-{vanilla,terralith}*.json`. The six region-run outputs,
measurements and reproduction script are in `build/goal-baseline/provider-replay/`.
The script uses `scripts/native-region-benchmark.py` with `--count 20 --warmups 2`,
`--parallel 1` or `2`, `--program-execution interpreter` and matched MCA comparisons.
The previous sampler-only milestone and its historical measurements are retained
in Git commit `4fe9290`.

## Remaining work

[Property copying](REGISTERED_PROPERTY_COPY.md) now works for supported
nonnullable source programs, including these spatial noise providers. Additional
live/spatial filters and nullable transformation combinations remain unsupported
and are reported. The approximate tree planner
still uses its existing flattened trunk/foliage material palettes. Further
survival adapters, per-expression density interpolation, cold specialization
cost and additional measured Rust scan reductions remain part of the wider goal.
