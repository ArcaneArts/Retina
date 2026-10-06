# Specialized GPU interpolation input writers

Metal's mixed execution now specializes the registered interpolation-field
writers independently of the remaining terrain stages. Previously those writers
used the compact interpreter even after their profile's compiled graphs were
ready. Terralith's four-block vertical input grid doubles their work in accurate
density composition, making them a useful separate target.

The compiler includes the existing `interpolation_nodes_<level>` entrypoints in
the ready mixed bundle. Each submission selects those compiled writers when
available, retaining compact interpreted density/height/climate and surface
extraction. Pending or unavailable specialization selects the existing GPU
interpreter. Fully interpreted profiles remain fully interpreted. Other backends
retain their prior defaults; `RETINA_SPECIALIZED_INTERPOLATION=0/1` selects the
independent diagnostic choice. Metal defaults on.

The selection is captured by the GPU owner, compiler job and source identity.
The host chooses the complete stage set before encoding. The existing horizontal
writer still precedes input fields; child field depths precede their parents;
density and surface consumers follow them. Registered cell sizes, exact-coordinate
cache checks, raw out-of-atlas evaluation and f32 graph boundaries are unchanged.
There is no new spatial CPU calculation, dispatch, binding, scratch field, upload
or readback. The existing F3 **Height / climate** timer includes these writers;
the ready mode remains mixed. Both individual chunks and MCA generation use the
same selection. Existing saved blocks and generator configuration are unchanged.

## Whole-region measurements

Apple M4 Max / Metal, seed 123456789, complete current vanilla and Terralith
profiles including registered decorations, sediment disks and structures. Every
process generates 20 adjacent measured regions after two warmups. Serial repeats
reverse order; concurrency uses two callers. All builds, tests and benchmarks run
sequentially at reduced priority, with two Cargo jobs. Desktop activity remains
uncontrolled. Both selections use the same retained candidate library.

Production final-field interpolation:

| Profile / callers / pair | Mean region ms, interpreted → specialized writers | Chunks/s, interpreted → specialized writers | Height/climate GPU ms/region |
| --- | ---: | ---: | ---: |
| Vanilla / 1 / forward | 182.62 → 183.72 | 5,599 → 5,565 | 16.03 → 11.24 |
| Vanilla / 1 / reversed | 203.53 → 190.78 | 5,024 → 5,359 | 16.49 → 10.91 |
| Vanilla / 2 | 318.84 → 300.43 | 6,201 → 6,796 | 13.75 → 8.16 |
| Terralith / 1 / forward | 331.03 → 302.47 | 3,090 → 3,382 | 25.82 → 16.40 |
| Terralith / 1 / reversed | 385.16 → 369.07 | 2,656 → 2,772 | 28.27 → 16.59 |
| Terralith / 2 | 567.87 → 511.22 | 3,479 → 3,855 | 27.82 → 16.22 |

Terralith's observed serial throughput improves 4.3–9.4% and its two-caller
throughput improves 10.8%. Vanilla's serial results are mixed (-0.6% / +6.7%);
its two-caller throughput improves 9.6%. Height/climate device time falls in all
pairs, but the whole-region changes also include CPU variation. These are measured
shared-machine results, not a universal speedup claim. Concurrent throughput uses
whole-run wall time, rather than the inverse of overlapping request latency.

Experimental accurate block-position composition, enabled on both sides:

| Profile / callers / pair | Mean region ms, interpreted → specialized writers | Chunks/s, interpreted → specialized writers | Height/climate GPU ms/region |
| --- | ---: | ---: | ---: |
| Vanilla / 1 / forward | 196.56 → 190.04 | 5,202 → 5,381 | 22.38 → 16.64 |
| Vanilla / 1 / reversed | 198.21 → 192.77 | 5,159 → 5,305 | 21.78 → 16.72 |
| Vanilla / 2 | 334.63 → 315.91 | 6,109 → 6,471 | 19.41 → 14.32 |
| Terralith / 1 / forward | 340.08 → 434.03 | 3,009 → 2,357 | 58.29 → 44.24 |
| Terralith / 1 / reversed | 363.13 → 321.42 | 2,818 → 3,183 | 62.58 → 42.51 |
| Terralith / 2 | 645.75 → 632.96 | 3,168 → 3,181 | 59.38 → 40.92 |

Vanilla composition improves 2.8–3.4% serial / 5.9% concurrent. Terralith's
device stage improves consistently, but its whole-region results are mixed.
In its first specialized-writer run, vegetation planning rises from 0.107 to
0.175 ms/chunk and NBT work from 0.088 to 0.147; in the reversed pair both are
much closer. The data does not establish a reliable Terralith composition
throughput gain. Accurate composition remains off by default: reducing this
one prepass does not resolve the remaining per-block mask and aquifer costs.

All **491,520** complete decompressed chunk NBT comparisons match their own
`61468c5` baseline, including twenty-region negative/neighbor seams, serial order
reversal and concurrent requests. Production files total exactly 156,205,056 /
134,115,328 bytes vanilla/Terralith. Composed files total 157,732,864 / 136,495,104
bytes on both sides; composition intentionally differs from production terrain.

Per-region production uploads/readbacks remain about 14.295 / 45.584 MB vanilla
and 12.843 / 41.995 MB Terralith. Composition remains about 14.297 / 45.938 and
12.847 / 42.584 MB. Small concurrent differences follow existing sparse-query
coalescing; writer selection adds no transfers. Specialized-writer peak RSS is
966–1,030 MiB vanilla / 1,497–1,553 MiB Terralith in production, and about
1,123–1,161 / 2,089–2,173 MiB with composition. These observations do not establish
a memory improvement.

Driver-warm initialization takes 52–60 ms; registration takes about 646–700 /
1,190–1,272 ms. Ready-bundle compilation normally takes about 442 / 1,886–1,978 ms
with production interpolation, or 1,007 / 4,629–4,757 ms with composition. The
first newly observed production writer identities take 1,447 / 3,975 ms, versus
435 / 1,897 without them. Their first forced warmups take 1,704 / 4,431 ms. This
has a compiler cost; it is not a cold-start optimization or empty-cache world
creation measurement. Automatic mode continues generating while compilation is
pending. Fresh-identity measurements and final activation checks are recorded below.

## Fresh specialized pipeline identities

One sequential pair per complete profile gives every specialized entry a fresh
identity, keeps shared material dispatch enabled, and varies only input-field
specialization. The ignored release diagnostic forces readiness, then generates
two warmups and twenty measured regions. Existing generic pipelines remain
driver-warm; no cache or save is deleted. These pairs quantify compilation cost
and parity, not an empty-cache world-opening time or a counterbalanced speedup.

| Profile | Bundle compile ms, interpreted → specialized writers | Added writer compile ms | Forced first region ms | Mean warm region ms |
| --- | ---: | ---: | ---: | ---: |
| Vanilla | 18,736 → 20,117 | 963 | 18,998 → 20,370 | 177.46 → 158.48 |
| Terralith | 70,498 → 72,315 | 1,953 | 70,845 → 72,652 | 248.19 → 258.23 |

The added writer is about one/two seconds in these fresh-identity cases; unrelated
pipeline compilation still dominates each bundle. The single-pair warm results
are mixed and do not supersede the repeated measurements above. Peak RSS is about
1.61–1.62 GiB vanilla / 2.22 GiB Terralith. Measured files total exactly
156,684,288 / 136,253,440 bytes on both sides of each profile's pair. All **45,056**
complete decompressed chunk NBT comparisons match, including both warmups.
Kernel probes confirm one compiled interpolation level only in each candidate;
both actual profiles have depth one. Reports are retained in
`vanilla-cold-fields/comparison.json` and `terralith-cold-fields/comparison.json`.

## Final default activation and integration

The packaged default library is SHA-256
`781e3cca5d630fdb808b6773ecc29cfd6a26e18d93af719e1be105140e1b0656`;
the embedded macOS native in `retina-0.1.0.jar` matches it. Eight further complete
twenty-region runs cover both actual profiles, production/composed sampling and
automatic/forced specialization. All **163,840** complete chunk NBT comparisons
match their corresponding `61468c5` baseline. Four further regenerations after
compilation compare **4,096** complete chunk records with the first automatic
request. All match.

Automatic production switches from pending to ready after the first measured
vanilla region and third Terralith region; composed sampling switches after two
and four respectively. Generation proceeds through the existing GPU interpreter
until the compiled bundle becomes available. Every forced run reports ready
mixed execution throughout. These driver-warm observations demonstrate safe
activation, rather than empty-cache startup latency.

`build gpuTest regionTest previewTest structureTest blockFeatureTest datapackTest`
passes with accurate density composition enabled. It includes 65 native unit
checks and 90 passing QA events: Minecraft interpolation/density oracles, GPU
aquifer classification, material layers, registered features/structures, chunk/MCA
parity, shoreline boundaries, final metadata, temporary-region promotion,
parallel cache/save isolation and preserved edits. The loaded snow-support oracle
checks 483 palette states, 256 bare regular-ice columns, 256 snowy land columns
and 196,608 matching chunk/MCA blocks.

The final integration log is `build/specialized-interpolation-validation.log`;
`final-artifact.json` and `final-validation.json` retain its QA events and the
eight actual-profile measurements under the private evidence directory.
Across the repeated, final-activation and fresh-identity comparisons, **704,512**
complete chunk NBT records match. The integration suite additionally covers nested
interpolation, coordinate slicing, distinct cell sizes and negative chunk halos
against Minecraft's actual density samplers.

## Reproduction

`scripts/native-region-benchmark.py --specialized-interpolation enabled|disabled`
records the choice and supports independent density composition and terrain-stage
selection. To measure input-field specialization without specializing the rest:

```sh
nice -n 10 python3 scripts/native-region-benchmark.py \
  --library build/native-target/release/libretina_worldgen.dylib \
  --profile /absolute/path/to/exported-profile.json \
  --out /absolute/path/to/fresh-private-output --count 20 --warmups 2 \
  --program-execution specialized --terrain-execution interpreter \
  --specialized-interpolation enabled --density-composition enabled
```

The fresh-identity compiler diagnostic now also supports
`scripts/native-material-compile-benchmark.py --comparison interpolation`. It
holds shared material dispatch on in both cases and varies only input writers,
with unique identities for all specialized entries. Its default material-sharing
comparison explicitly holds input-field specialization off, preserving the earlier
material experiment. No driver cache or live save is cleared.

Private evidence is under `build/goal-baseline/composition-production/`, including
full profiles, retained libraries, all MCA output, `baseline.json`,
`fields-summary.json` and sequential runners. The measured opt-in candidate is
SHA-256 `56b7a7510cc5c15611b4b9988da2eaac367aaff136e1f5e24ec20605fce5a8c1`;
its retained `61468c5` baseline is
`1e1349065a8f5e2b15f58d65a5b5a76142cbb8164bfcd9942a0425c3688660cf`.

Fast production density composition, full cold desktop startup, other backend
measurements and remaining feature/filter coverage remain required goal work.
