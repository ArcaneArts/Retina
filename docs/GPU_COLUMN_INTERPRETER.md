# Resident X/Z reuse in the compact GPU interpreter

Metal's mixed stage selection now lets compact interpreted density and climate
programs consume the existing specialized horizontal atlas. Previously the
horizontal prepass ran, but these interpreted stages still evaluated the same
X/Z expressions at every Y layer. The optimization applies to regular final-field
sampling and experimental block-position composition. Composition remains off by
default; its remaining region cost and graph-fidelity work are separate concerns.

## Execution and semantics

The registry's existing horizontal-expression plan identifies expensive, pure
X/Z subgraphs, including equivalent expressions shared between primary graphs
and interpolation inputs. Rust appends small per-program schedules to resident
bytecode, directly after the terracotta bands. Each entry retains the original
instruction ID and either requests its existing opcode or loads a horizontal
slot. Traversal stops at a cached expression and removes its otherwise-unused
dependencies. Original descriptors, roots, spline point IDs, noise records and
compact register assignments retain their meaning. Missing output slots still
read original node zero.

For the measured profiles, the climate schedule shrinks from 118 / 167 to nine
instructions for vanilla / Terralith, and the heavy interpolation input shrinks
from 337 / 562 to 144 / 241. The complete resident footer adds 1,780 / 3,332 bytes;
the existing 9 / 23 horizontal fields are unchanged.

A submission sets request bit 24 only after it selects a ready specialized
bundle, allocates its horizontal atlas and schedules the atlas writer before
consumers. Pending/failed specialization, interpreter-only profiles and explicit
height profiles retain raw evaluation. The compact evaluator uses the shortened
schedule only for an exact represented X/Z grid point inside that submission's
atlas. Fractional or uncovered queries use the original instruction sequence.
This also applies within nested interpolation helpers and coordinate scopes;
there is no CPU noise approximation. Context-dependent material expressions are
excluded by the horizontal plan.

The atlas writer already preserves each f32 expression boundary. Keeping the
original IDs and register map avoids remapping spline operands or altering
last-use behavior. Shared interpreter source now includes the cache reader, so
specialization becoming ready does not require another interpreter compilation.
Source identities continue to include the actual compiled source. The existing
base-pipeline reuse analysis and fixed per-submission selection remain in effect.

`RETINA_INTERPRETER_COLUMNS=0/1` selects the diagnostic path. Metal defaults on;
Vulkan and DX12 default off because this implementation has not been measured on
those backends. Profiles and saved world configuration do not change. Both modes
use Retina GPU → Rust generation. F3's existing height/climate stage includes the
horizontal writer and the shortened interpreted passes; no timing ABI changes.

The schedule is uploaded once with the profile. It adds no spatial dispatch,
GPU binding, dense readback or CPU/GPU round trip. Existing horizontal scratch
allocation and field counts are unchanged. Process RSS is not reduced uniformly,
so this change makes no memory-reduction claim.

## Matched whole-region measurements

Apple M4 Max / Metal, actual complete vanilla and Terralith profiles including
structures and decorations, seed 123456789, two warmups and twenty adjacent
regions per process. All 24 processes ran sequentially at nice priority 10;
other desktop activity was uncontrolled. Serial order is reversed in the second
pair. The baseline is the retained `0630131` library, and the candidate is the
final library with resident column schedules enabled.

Regular production interpolation:

| Profile / pair | Before mean ms | After mean ms | Before chunks/s | After chunks/s |
| --- | ---: | ---: | ---: | ---: |
| Vanilla / forward | 193.60 | 184.24 | 5,281 | 5,549 |
| Vanilla / reversed | 191.28 | 182.90 | 5,346 | 5,590 |
| Vanilla / two callers | 291.32 | 293.04 | 7,011 | 6,969 |
| Terralith / forward | 302.18 | 291.21 | 3,386 | 3,513 |
| Terralith / reversed | 306.04 | 285.65 | 3,343 | 3,581 |
| Terralith / two callers | 477.48 | 462.28 | 4,140 | 4,279 |

These serial pairs show 4.6–5.1% / 3.8–7.1% higher throughput for vanilla /
Terralith. Vanilla's concurrent result is essentially unchanged (-0.6%); the
Terralith concurrent pair improves 3.4%. Device height/climate time falls from
25.55–25.79 to 17.95–18.29 ms vanilla and 46.75–47.40 to 28.96–29.01 ms Terralith
in the serial pairs. These device savings do not imply equivalent whole-region
or concurrent improvements.

Experimental accurate composition, enabled on both sides:

| Profile / pair | Before mean ms | After mean ms | Before chunks/s | After chunks/s |
| --- | ---: | ---: | ---: | ---: |
| Vanilla / forward | 211.93 | 203.94 | 4,826 | 5,014 |
| Vanilla / reversed | 212.65 | 206.47 | 4,809 | 4,953 |
| Vanilla / two callers | 344.56 | 346.89 | 5,933 | 5,816 |
| Terralith / forward | 432.46 | 329.40 | 2,366 | 3,106 |
| Terralith / reversed | 407.86 | 334.27 | 2,509 | 3,061 |
| Terralith / two callers | 727.06 | 588.20 | 2,780 | 3,393 |

Composition's serial throughput improves 3.0–3.9% vanilla and 22–31% Terralith;
Terralith's concurrent pair improves 22%. Vanilla concurrency is 2% lower in this
pair, so a general concurrent speedup is not established. Terralith composition
height/climate drops from 106.11–112.06 to 60.59–61.50 ms in the serial pairs.
Concurrent throughput is based on total measured wall time; overlapping request
latency is not inverted to claim throughput.

Initialization and profile registration remain approximately 51–60 ms and
647–681 / 1,242–1,279 ms for vanilla / Terralith across these driver-warm runs.
Profile shader source is unchanged. Warm profile compilation is 493–507 /
2,597–2,650 ms with production interpolation and 1,128–1,153 / 5,955–6,067 ms
with composition. These are not empty-driver-cache startup measurements; cold
shared activation and substantial profile compiler work remain required work.

Peak process RSS spans 964–1,089 → 1,040–1,056 MiB vanilla and 1,691–1,736 →
1,647–1,783 MiB Terralith in production. Composition spans 1,112–1,179 →
1,188–1,324 MiB vanilla and 2,348–2,427 → 2,499–2,587 MiB Terralith. RSS includes
compiler, driver and comparison-reader memory; adding the schedule does not
establish lower total memory use.

Dynamic upload/readback per region remains about 14.295 / 45.623 MB vanilla and
12.618 / 41.955 MB Terralith in production, or 14.297 / 45.977 and 12.622 / 42.543 MB
with composition. Tiny readback differences reflect existing sparse-query
coalescing. All twenty-region file totals match exactly: 156,385,280 /
134,328,320 bytes in production and 157,827,072 / 136,658,944 bytes with composition.
All 409,600 complete NBT comparisons pass across the matched processes, covering
palettes, biomes, final heightmaps, structures and decorations.

Four additional automatic twenty-region runs have no warmups and match the
corresponding baseline NBT, including a regenerated region after specialization
finishes. Production first native regions take 272 / 474 ms vanilla / Terralith;
twenty-region means are 197 / 357 ms at 5,189 / 2,869 chunks/s. Composition first
regions take 448 / 736 ms, with means 245 / 544 ms at 4,182 / 1,881 chunks/s.
These include background profile compilation during generation and are not
paired speedup measurements or full Java world-opening times. They use a warm
driver cache and do not establish a cold-start improvement.

The benchmark now records actual program status after each completed region,
outside that region's measured call time. Four separate no-warmup two-region
checks observe status 1 (pending) on their first region. Vanilla production
reaches status 4 on its second region; the other checks wait 100–4,094 ms after
measurement for readiness. Every regenerated status-4 region matches all 1,024
records from its pending counterpart. Including these checks, the twenty-region
automatic runs and the preliminary three-region pairs, this milestone preserves
514,048 complete NBT comparisons.

## Validation and reproduction

The independent GPU oracle compares the shortened interpreter with an uncached
compact evaluator using the original bytecode. It also requires actual cache
hits. Actual vanilla, Terralith and a nested interpolation fixture pass 387,072
root float-bit comparisons, including negative/nonaligned origins, fractional Y,
uncached fractional X/Z, out-of-atlas points, multiple seeds, coordinates near
±2 million and ±16.8 million, nonbinary grid steps, and unflagged submissions.
A unit check verifies dependency pruning, implicit node-zero outputs, spline
children and preservation of the original register lifetimes.

Direct before/after chunk checks compare 4,730,880 blocks and 12,288 column
records in both sampling modes, across actual vanilla/Terralith profiles. They
reverse request order with two callers, vary seeds and vertical dimensions
(-64/384 and -67/386), and include negative region boundaries, coordinates near
±2 million and ±9.6 million. All outputs match.

`build`, followed by `gpuTest regionTest previewTest structureTest blockFeatureTest
datapackTest` with composition enabled, passes in the final build. The suite
checks registered materials and aquifers, both generator modes, complete chunk
metadata, full/partial/placeholder DH promotion, parallel LRU/save isolation,
existing edits, cleanup, loaded structures/features and bare freezing ice. The
62 native unit checks pass. Logs are `build/column-interpreter-final-build.log`
and `build/column-interpreter-integration.log`.

`nativeColumnInterpreterGpuTest` is included in `gpuTest` and recreates its nested
fixture through the existing interpolation/lake harness. Actual-profile checks
can be reproduced with:

```sh
RETINA_PROGRAM_PARITY_PROFILE=/absolute/path/to/terralith.json \
  nice -n 10 cargo test --release --locked --jobs 2 --manifest-path native/Cargo.toml \
  --target-dir build/native-target --lib column_interpreter_roots_match_raw_gpu \
  -- --ignored --nocapture

python3 scripts/native-region-benchmark.py \
  --library build/native-target/release/libretina_worldgen.dylib \
  --profile build/goal-baseline/sparse-lake-fields/terralith.json \
  --program-execution specialized --terrain-execution interpreter \
  --interpreter-columns enabled --count 20 --warmups 2 --out build/column-benchmark
```

Retained libraries, paired scripts, measurements and full MCA outputs are under
`build/goal-baseline/column-interpreter/`, with hashes and counts in `evidence.json`.
The final native library and packaged JAR native entry both hash to
`77335d04e22b496840ba869c6831a91a7d28ff68bb187d627791b23950331808`.
The baseline native hash is
`d9d0b0d16aac1cb3f613fa93cae9fca85463dcc19631322df79e8a219950f907`.
