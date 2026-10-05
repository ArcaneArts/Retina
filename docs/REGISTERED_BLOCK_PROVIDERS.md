# Registered positional block-state providers

Retina now exports Minecraft 26.3's `rule_based`, `rotated` and `random_block`
providers for its native block features. Simple blocks containing these providers
use the provider program instead of flattening their possible outputs into a
weighted plant list. Registered holder references, block tags, rule order,
fallbacks and rotation properties participate in the exported recipe.

Rust provider sampling now receives the actual block position and a live material
reader. Rule predicates use the existing GPU terrain substrate plus earlier
ordered writes. Columns, mushroom caps/stems and fallen-tree decorators can see
their own earlier blocks as well as earlier features. Patch ground and nested
vegetation use their existing live overlay. Out-of-build-height column writes
remain clipped and cannot become predicate inputs for later blocks.

Rules try their branches in order. A matching branch whose optional provider
returns no state continues to the next rule. An absent final result skips
`SimpleBlockFeature`, matching `getOptionalState`; callers using `getState`
retain the current block. In patch ground this same-block result keeps the
cursor in place. An empty random-block set also returns no optional state and
consumes no random draw. Nonempty sets retain their registered order and select
uniformly, including tag membership.

Random rotations draw one of Minecraft's six directions before sampling their
nested provider. A fixed direction consumes no direction draw. Java exports the
actual resulting AXIS/FACING/HORIZONTAL_FACING states, preserving other
properties; Rust selects those palette entries. Rule predicates do not consume
random draws themselves. Existing atomic, weighted and integer-property providers
keep their sampling behavior. [Integer-property passthrough](REGISTERED_INTEGER_PROPERTIES.md)
now also preserves sampled states when the configured property is absent or
noninteger, without consuming a value draw.

These operations do not add a GPU dispatch or readback. Spatial predicate inputs
already come from the GPU; short ordered branches and local writes remain in
parallel Rust. Recipe tables upload with the world profile. Their work remains
included in F3's plant-planning stage, with no timing ABI change.

## Validation

The production exporter is invoked for 26 additional column/simple-block fixtures
per registry stack, parsed with Minecraft's own feature/provider codecs. The
reference harness invokes Minecraft's actual `Feature.place` implementations
with the same controlled terrain and random stream as Rust. It compares 13,312
cases across vanilla and Terralith, including underwater material predicates,
negative chunk boundaries, matching nullable branches, fallback behavior, tag
sets, random/fixed rotations and build-height clipping. A randomized tail layer
detects extra or missing provider draws. All cases pass, including 1,024 absent
simple-block placements.

The new recipes also generate full forced-biome MCA regions. All 1,024 slots are
decoded for each stack; twelve independent chunks per region, including negative
edges, match their MCA blocks and all six final heightmaps. Existing feature,
placement and paired-plant reference cases continue to pass. Native unit tests
cover optional fallthrough and random-draw order directly.

The validation command and its output are recorded under
`build/registered-state-providers-validation.log`:

```sh
./gradlew build nativeGpuTest blockFeatureTest previewTest regionTest \
  -PtestPack=/Users/cyberpwn/Downloads/Terralith_v2.6.5+26.3.zip \
  --no-parallel --max-workers=1
```

The packaged macOS native library matches the tested library: SHA-256
`bf01c1843fc03ca9e0f29dfbeb184fffdff13c35e84a76eb5204c4e4e4f0eb88`.
The full build, native unit/GPU tests, registry/preset checks, DH temporary-region
generation and promotion, concurrent edits, partial promotion, cache eviction,
save isolation, shutdown/failure cleanup and serialized region checks pass.

## Full-profile overhead measurements

Eight sequential benchmark processes used the actual exported vanilla and
Terralith profiles, seed `123456789`, two warmup regions and twenty measured
adjacent regions each. Both builds use two compiler jobs/code-generation units;
runtime generation workers are unchanged. The interpreter was forced to exclude
background specialized-shader compilation from this comparison. Processes ran
at nice priority 10 on the shared Apple M4 Max host, with other applications
active. Vanilla ran before then after; Terralith ran after then before.

| Profile / simultaneous region requests | Before mean region ms | After mean region ms | Before chunks/s | After chunks/s | Before / after peak RSS MiB |
| --- | ---: | ---: | ---: | ---: | ---: |
| Vanilla / 1 | 496.91 | 521.59 | 2,056.6 | 1,960.8 | 858 / 834 |
| Vanilla / 2 | 837.00 | 828.85 | 2,443.0 | 2,450.9 | 965 / 925 |
| Terralith / 1 | 938.50 | 948.35 | 1,090.6 | 1,079.3 | 1,016 / 947 |
| Terralith / 2 | 1,797.48 | 1,784.89 | 1,136.5 | 1,144.6 | 1,084 / 1,075 |

Concurrent throughput uses total batch elapsed time; each region's latency
includes its queue/overlap time. These measurements do not establish a speedup.
Vanilla's serial run was about 5% slower and Terralith's about 1% slower, while
concurrent throughput changed by less than 1%. Plant planning measured
0.063 → 0.080 ms/chunk for serial vanilla, 0.098 → 0.082 for concurrent vanilla,
0.030 → 0.031 for serial Terralith and 0.056 → 0.051 for concurrent Terralith.
Shared-host variation and one pair per workload do not establish tight bounds
on the adapter's overhead.

| Run | Initialize ms | Register profile ms | First warmup region ms |
| --- | ---: | ---: | ---: |
| Vanilla / 1 before | 101.5 | 1,591.5 | 697.3 |
| Vanilla / 1 after | 100.8 | 1,621.8 | 546.6 |
| Vanilla / 2 before | 121.7 | 1,404.0 | 494.5 |
| Vanilla / 2 after | 101.8 | 1,340.7 | 462.0 |
| Terralith / 1 before | 49.2 | 2,609.8 | 923.8 |
| Terralith / 1 after | 115.1 | 2,737.8 | 980.6 |
| Terralith / 2 before | 164.3 | 2,259.0 | 862.6 |
| Terralith / 2 after | 113.1 | 2,585.4 | 906.0 |

All six output comparisons pass: 122,880 complete decompressed NBT chunk records
match across old/new libraries and serial/concurrent orderings. Twenty-region
files total 153,907,200 bytes for vanilla and 131,649,536 for Terralith in every
run. Average transfer volume is approximately 14.27 MB uploaded / 45.62 MB read
back per vanilla region, and 12.39 MB / 41.90 MB per Terralith region. Counter
traffic and scheduling differ by less than 100 bytes per region between paired
runs; the new providers add no terrain transfer or GPU stage.

These full profiles have the same exported values as before this milestone;
they measure existing-recipe framework cost, while the additional Minecraft
codec fixtures exercise the newly supported recipes. Measurements, scripts,
libraries, exported profiles and MCA files are under the ignored
`build/goal-baseline/state-providers/` directory. Before native SHA-256:
`6c15af1f0156f8013fefb08cf5b929c13cedcd63582599bfd706bb336b2ce17e`.
The after SHA-256 is the packaged/tested hash above.

## Remaining provider work

Spatial `noise`, `noise_threshold` and `dual_noise` programs now consume the
[GPU sampler through ordered replay](GPU_PROVIDER_NOISE.md), including nested
block-feature providers. The approximate tree planner retains flattened material
palettes. [Property-copy providers](REGISTERED_PROPERTY_COPY.md) now preserve
live compatible properties for supported nonnullable sources; their reference
checks and current full-profile measurements are documented separately.

Contextual rules support the exported material/tag/fluid/survival predicates;
unknown predicates remain logged omissions. Nullable providers inside weighted,
rotated or integer-property wrappers, nullable mushroom caps, and nullable fallen
trunks still need complete transformations of arbitrary existing substrate
states. Those combinations are explicitly reported rather than sampled through
incomplete rotation/property tables. This milestone does not complete the wider
feature, interpolation, compiler-startup or scan workstreams.
