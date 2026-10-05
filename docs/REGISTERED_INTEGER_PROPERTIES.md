# Integer-property provider passthrough

Minecraft 26.3's `RandomizedIntStateProvider` first samples its source, then looks
for an integer property with the configured name on that sampled state. If the
property is absent, boolean or an enum, it returns that state unchanged and does
not sample the value provider. This applies independently to each state in a
mixed source. Retina previously omitted the whole native provider when any source
state lacked the property.

The exporter now emits a passthrough row for those source states. Rust keeps the
sampled state and skips the value draw; compatible integer states retain their
existing lookup tables and value-provider sampling. The optional row flag
defaults to false for previously exported profiles. Noise sources still use the
resident GPU sampler and ordered replay; passthrough itself adds no dispatch,
query or readback. Its work remains within plant planning.

This change applies to ordered native block-feature providers. Tree material
palettes and legacy flattened plant recipes retain their existing approximations.
[Nullable source transformations](REGISTERED_NULLABLE_PROVIDERS.md) now close
sparse tables over the live palette. Arbitrary-current SimpleBlock survival
remains an explicit omission.

## Validation

Six additional provider fixtures use Minecraft's actual codecs and feature code:
absent names, noninteger axis/lit properties, mixed water/stone sources, spatial
noise sources and copying around integer randomization. Uniform value draws and a
subsequent randomized column layer reveal missing/extra draws. All 6,144 additional
reference cases pass across vanilla and Terralith. The complete provider harness
now compares 35,840 cases over 70 column/simple-block features per stack, plus
8,064 targeted property-copy cases. Forced MCA regions decode all 1,024 slots;
twelve independent chunks match their blocks and all six final heightmaps.

The build passes with 53 native unit tests (three benchmark tests ignored).
Native GPU, DH temporary regions/promotion/concurrent edits/save isolation,
serialized MCA and frozen-ocean snow checks also pass. Commands:

```sh
./gradlew build blockFeatureTest \
  -PtestPack=/Users/cyberpwn/Downloads/Terralith_v2.6.5+26.3.zip \
  --no-parallel --max-workers=1
./gradlew nativeGpuTest previewTest regionTest snowTest \
  --no-parallel --max-workers=1
```

Logs: `build/integer-property-validation.log` and
`build/integer-property-integration.log`. Tested release and packaged macOS
native SHA-256: `343407d651b9370eb09aa20e9f859e66cace03af0d2d3e866a2358167579238d`.

## Whole-profile compatibility measurements

Eight sequential Apple M4 Max / Metal processes compared the retained `a5fcfb2`
library with this change, seed 123456789, two warmups then twenty measured regions
per process. Vanilla ran before/after and Terralith after/before at nice 10.
Compilation used two jobs/code-generation units; runtime workers were unchanged.
Full vanilla/Terralith profile values remain identical (56/151 biomes, 172/510
decorations, 2,156/3,673 material states). They do not exercise these newly accepted
missing-property combinations; the reference fixtures do. These measurements
check existing production workloads, rather than estimate new pack feature cost.

| Profile / callers | Before mean region ms | After mean region ms | Before chunks/s | After chunks/s | Before / after peak RSS MiB |
| --- | ---: | ---: | ---: | ---: | ---: |
| Vanilla / 1 | 179.13 | 178.87 | 5,708 | 5,716 | 999 / 1,015 |
| Vanilla / 2 | 290.56 | 290.22 | 6,979 | 6,986 | 1,055 / 1,055 |
| Terralith / 1 | 244.80 | 244.07 | 4,178 | 4,191 | 1,528 / 1,549 |
| Terralith / 2 | 398.62 | 406.41 | 4,955 | 5,031 | 1,585 / 1,557 |

Concurrent throughput uses total batch time; per-region latency includes overlap.
Single pairs on the shared host show no reliable throughput improvement or
regression. RSS includes the driver/compiler, GPU buffers and comparison reader.

| Run | Initialize ms | Register ms | First warmup ms |
| --- | ---: | ---: | ---: |
| Vanilla / 1 before | 51.6 | 591.0 | 801.0 |
| Vanilla / 1 after | 59.1 | 571.0 | 795.0 |
| Vanilla / 2 before | 51.8 | 594.2 | 820.9 |
| Vanilla / 2 after | 50.8 | 609.4 | 813.0 |
| Terralith / 1 before | 51.1 | 1,154.1 | 3,121.6 |
| Terralith / 1 after | 51.6 | 1,200.9 | 3,175.1 |
| Terralith / 2 before | 52.5 | 1,172.8 | 3,109.2 |
| Terralith / 2 after | 52.6 | 1,159.3 | 3,156.4 |

Mixed specialization was forced ready with interpreted terrain and composition
disabled. First warmup includes specialized shader compilation; initialization
benefits from shared driver caches. These figures do not establish a cold-start
scheduling improvement.

All 163,840 decompressed NBT records match the retained serial baseline,
including concurrent orderings. Twenty-region files remain exactly 153,845,760
bytes vanilla and 130,899,968 bytes Terralith. Per-region transfers stay about
14.295 MB upload / 45.623 MB readback vanilla and 12.613–12.615 / 41.937–41.955 MB
Terralith. Existing profile rows have no added serialized field or spatial query.
Evidence, libraries, profiles, reproduction script, measurements and hashes are
under ignored `build/goal-baseline/integer-property/`, including `manifest.json`.
Broader nullable/spatial support and production density/compile work remain open.
