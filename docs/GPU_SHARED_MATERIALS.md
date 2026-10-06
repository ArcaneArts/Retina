# Shared specialized material scans

The material count and emit passes walk the same column evaluator, with a boolean
selecting whether to write its runs. Ready specialized Metal profiles now use one
64-lane compute pipeline for those two passes. A uniform four-byte immediate selects
count or emit in the GPU command. The count pass still initializes the material
header; the emit pass does not. The separate 256-lane prefix scan and single-lane
prefix-total pipeline retain their original bodies and ordering.

This removes one expensive backend compilation of the registered material graph.
It adds no dispatch, CPU spatial noise, resident buffer, upload, copy or readback.
Returned material runs, cave masks, assembly and final chunk metadata keep their
existing formats. The existing F3 material-run timer includes both scans.

The measured default enables sharing on Metal adapters with the actual immediate
capability and at least four bytes. Other backends retain independent specialized
material pipelines. `RETINA_MATERIAL_DISPATCH=0/1`, or
`scripts/native-region-benchmark.py --material-dispatch disabled/enabled`, selects
the diagnostic alternative. An explicit request adds that feature to the real
device request; unsupported requests receive its actual error.

The choice is captured when the GPU owner is created, passed to compiler jobs and
included in profile pipeline identity. Deferred emission carries the selector with
its own immutable buffers, using the existing selected-pipeline mechanism. Generic
interpreters, absent material graphs and separately cached density-mask modules
retain their earlier handling. Automatic execution still uses the GPU interpreter
while the specialized bundle is pending or unavailable. No job switches to ordinary
Minecraft generation. Both chunk and MCA requests use the same material passes.

## Fresh-identity compilation measurements

Apple M4 Max / Metal, seed 123456789, complete current vanilla and Terralith
profiles including decorations, sediment disks and structures. Each process uses
fresh diagnostic entrypoint names for **every specialized stage**, including the
common stages. This avoids mistaking the previous process's driver-cache warmth
for a material improvement. Names change; shader arithmetic does not. Driver
caches are not cleared. Base and generic pipelines retain normal identities.

Each process forces specialization, generates 22 adjacent regions and measures
the last 20 as warmed requests. The second pair reverses selection order. Native
builds, tests and benchmarks run sequentially at reduced priority; builds use two
jobs. Desktop activity remains uncontrolled. These are additional-profile
compilation measurements, not empty-cache desktop world-creation timings.

| Profile / pair | Material compilation ms, separate → shared | Full bundle ms, separate → shared | First forced region ms, separate → shared |
| --- | ---: | ---: | ---: |
| Vanilla / forward | 1,962.82 → 947.87 | 20,448.41 → 19,403.65 | 20,719.41 → 19,654.35 |
| Vanilla / reversed | 1,814.75 → 935.19 | 19,675.43 → 18,821.84 | 19,961.49 → 19,080.73 |
| Terralith / forward | 128,860.04 → 47,441.07 | 230,529.35 → 179,257.41 | 232,072.17 → 180,572.60 |
| Terralith / reversed | 57,116.57 → 26,711.87 | 179,643.79 → 139,517.67 | 180,277.52 → 140,265.02 |

Material compilation falls 48–52% vanilla and 53–63% Terralith in both orders.
Full bundle compilation falls about 4–5% / 22%, but its absolute time varies
considerably with shared-machine activity. Terralith still spends well over a
minute compiling a completely fresh specialized bundle in these forced runs.
This saving does not resolve cold world startup or all profile compilation costs.

All six nonreference cold runs match **135,168** complete decompressed chunk NBT
records against their profile's first independent-pipeline run. Each comparison
includes both warmups, negative positions and neighboring region boundaries.
The last 20 files total 156,684,288 bytes vanilla / 136,253,440 bytes Terralith,
identically for all selections. Cold peak RSS is about 1,635–1,655 MiB vanilla
and 2,232–2,288 MiB Terralith, including compiler memory. There is no demonstrated
memory improvement. The cold test executable retains all these entrypoint probes
under test-only compilation; production shader identities are unchanged by them.

## Production-library warm measurements

The candidate release library was compared with itself with sharing disabled and
with retained `b32e44a`. Each process generated 20 adjacent regions after two
warmups. The measured strip differs from the cold harness's last-20 strip;
their means should not be treated as an A/B pair. Concurrent throughput uses whole
run wall time rather than the inverse of overlapping request latencies.

| Profile / callers / pair | Mean region ms, separate → shared | Chunks/s, separate → shared | Material GPU ms/region, separate → shared |
| --- | ---: | ---: | ---: |
| Vanilla / 1 / forward | 376.86 → 364.38 | 2,713 → 2,806 | 22.47 → 25.41 |
| Vanilla / 1 / reversed | 307.23 → 310.80 | 3,328 → 3,289 | 16.68 → 16.97 |
| Vanilla / 2 / forward | 529.79 → 667.40 | 3,806 → 3,060 | 18.68 → 18.59 |
| Vanilla / 2 / reversed | 1,789.72 → 1,285.99 | 1,142 → 1,549 | 18.56 → 29.11 |
| Terralith / 1 / forward | 355.09 → 306.75 | 2,881 → 3,335 | 22.90 → 22.90 |
| Terralith / 1 / reversed | 287.17 → 286.76 | 3,562 → 3,567 | 22.35 → 21.94 |
| Terralith / 2 / forward | 467.73 → 490.20 | 4,372 → 4,025 | 22.55 → 23.69 |
| Terralith / 2 / reversed | 471.80 → 477.57 | 4,302 → 4,282 | 23.03 → 22.57 |

Warm results are mixed and establish **no steady-generation speedup**. In the
vanilla concurrent outliers, Rust vegetation planning changes from 0.156 to 0.214
ms/chunk in the first pair and from 0.739 to 0.405 in the reversed pair; NBT,
compression and other CPU stages move substantially too. Those entire-region
differences cannot be attributed to the material flag. The implementation is
retained for the repeatable cold compilation saving, with a small potential
dynamic-branch tradeoff rather than a claim of faster GPU material evaluation.

All 16 candidate warm runs match another **327,680** decoded chunk records
against the old release library. Files total exactly 156,205,056 bytes vanilla /
134,115,328 bytes Terralith. Per-region uploads/readbacks remain approximately
14.295 / 45.58 MB vanilla and 12.843 / 42.00 MB Terralith. Concurrent sparse-query
batching causes small existing transfer differences; the selector itself adds no
buffer transfer. Warm peak RSS is about 1,008–1,072 MiB vanilla / 1,455–1,529 MiB
Terralith. This does not establish a memory reduction.

## Reproduction and integration

Build a release native test executable with the repository's two-job setting,
then pass its actual path. The following tool reverses order on alternate repeats,
records per-stage compilation and RSS, and compares every generated chunk's NBT:

```sh
nice -n 10 python3 scripts/native-material-compile-benchmark.py \
  --test-binary build/native-target/release/deps/retina_worldgen-<test-id> \
  --profile /absolute/path/to/exported-profile.json \
  --out /absolute/path/to/fresh-private-output --count 20 --warmups 2 --repeats 2
```

The ignored native diagnostic's `RETINA_COMPILE_MODE` defaults to `interpreter`,
preserving the existing interpreter benchmark. `specialized` enables these
profile compiler measurements. `RETINA_SPECIALIZED_PIPELINE_TAG` gives test-only
fresh identities independently of `RETINA_GENERIC_PIPELINE_TAG`.

Private evidence is under `build/goal-baseline/material-kernel-sharing/`, including
full profiles, retained libraries, `cold-summary.json`, `warm-summary.json`, MCA
files, traces and sequential runners. The measured opt-in candidate is SHA-256
`107a2a06e89ba4ddeb3c745f7bdc3cde77e4ae70e9d2733b65ba8bd9f2efbd29`;
its retained `b32e44a` reference is
`f67c95cbc38c7d140422a7462c1f3b8a839239a5b640092222df5dfcb5d48a72`.
The final default-policy artifact is validated separately below.

The final `build materialTest regionTest previewTest snowTest aquiferTest
structureTest` passes: 65 native unit checks, 6,680,576 Minecraft-reference material
voxels, 3,072,000 aquifer classifications, negative/MCA/per-chunk boundaries,
final heightmaps and block edits. It also covers temporary DH caching/promotion,
partial regions, parallel eviction/save isolation, failure cleanup, and 483 loaded
snow-support states with 256 bare-ice columns / 256 snowy-land columns and 196,608
MCA/chunk block comparisons. The integration log is
`build/material-kernel-sharing-validation.log`.

Four final twenty-region runs with the default Metal policy preserve another
**81,920** complete NBT records against `b32e44a`. Ready specialized means are
176.04 / 267.08 ms vanilla/Terralith (5,808 / 3,830 chunks/s). They are output checks,
not a matched speedup claim against the much noisier earlier controls. Automatic
mode starts without generation warmups: the first vanilla region and first three
Terralith regions complete while profile compilation is pending. The bundle then
activates, and regenerated first regions match another **2,048** records. Driver-
warm compilation takes 408 / 1,979 ms in those automatic runs; this does not
contradict the fresh-identity cold table or resolve empty-cache world creation.

The committed cold diagnostic also passes an actual one-region pair with fresh
entrypoint identities, matching another **1,024** records. Its short run validates
the tool and is excluded from the twenty-region performance tables. The standalone
final library and native member of `build/libs/retina-0.1.0.jar` are identical,
SHA-256 `1e1349065a8f5e2b15f58d65a5b5a76142cbb8164bfcd9942a0425c3688660cf`.
An independent-option check disables shared interpreter dispatch while explicitly
enabling shared specialized material dispatch. The real device request and complete
region succeed, matching another **1,024** records. Across the completed cold,
warm, activation and option comparisons, **548,864** decoded chunk NBT records
match. No benchmark or test ran alongside another one.

Fast production block-position density composition, full cold desktop startup,
other backend measurements and remaining registered feature/filter coverage are
still required goal work. This milestone does not mark the broader goal complete.
