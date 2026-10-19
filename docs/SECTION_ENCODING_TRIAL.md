# Final-section and NBT encoding trial

Reusing final lighting classifications and fetching biome samples in bulk reduced
NBT worker time consistently, but did not establish faster complete generation.
Production retains the existing encoder. The experiment is preserved for future
CPU-focused work; it is not an enabled alternative or a claimed throughput gain.

[Raw measurements](benchmarks/section-encoding-trial-2026-10-19.json) contain all
twelve whole-generation runs, individual region/stage times, hashes, transfer
counters, peak RSS, build/test logs and the bounded runner commands. The
[final source patch](benchmarks/section-encoding-reuse-2026-10-19.patch) applies to
`0ae460453205edfe2ad2cdd0e8792bcbc44f7c53` and includes its correctness tests.

## Implemented experiment

The lighting transport already classifies each final section as a uniform material
or mixed. The candidate copies these descriptors into each consumed tile's chunk
order. The NBT encoder skips its redundant uniform scan when this classification
is available, while retaining the original palette order and packed data. The
900 lit interior chunks reuse metadata; the 124 outer-ring chunks and paths
without descriptors retain the scalar encoder. At height 384, this adds 9,600
bytes of CPU metadata per 10×10 tile (86,400 bytes copied per region), without
another GPU transfer or dispatch. Final buffers reserve their exact capacity.

A separate section biome fetch calculates bounds and packed-plane offsets once
for all 64 quart samples. It reads sixteen short rows in Minecraft's x/z/y order.
Partial coverage uses the original per-sample surface-biome fallback. Palette
packing, heightmaps, block entities, structures, compression and publication are
unchanged. A test-only switch compares complete NBT with the previous encoder.

The initial candidate used geometric collection growth for the tile metadata.
The final version reserves its exact size and adds partial-mask NBT coverage.
Earlier measurements remain labeled separately; only the final source patch is
retained. This is not a compression change: production already uses level-1
libdeflate, reusable scratch buffers and parallel encoding as lighting tiles
complete. Moving ownership of compressed scratch buffers would sacrifice their
reuse; no unmeasured ownership or compression variant is enabled.

## Encoding and complete generation

The isolated fixture encodes 1,024 synthetic height-64 lit chunks, with two warmup
pairs and ten alternating timed pairs, in the normal optimization-level-3/thin-LTO
release test build. Exact bytes match after every pair. Final median encoding time
is **22.41 → 18.28 ms** (18% lower). This excludes generation, compression and I/O
and does not represent 1,024 independently generated chunks.

Whole-generation comparisons use Apple M4 Max / Metal, two shared CPU workers,
two concurrent callers, nice +10, seed 123456789, height 384, a separate warmup at
(8,8), and shader-ready mixed execution (status 4 for every measured region).
Both native libraries use final-crate optimization level 2 without LTO to fit the
build deadline; dependency/repository release settings are unchanged. The retained
reference is PR #120's runtime; PRs #121 and #122 changed only documentation.

| Profile / batch | Order | Reference chunks/s | Candidate chunks/s | Change | NBT worker ms/region, reference → candidate |
| --- | --- | ---: | ---: | ---: | ---: |
| Vanilla / 6 regions, initial | Reference, candidate | 2,490 | 2,601 | +4.5% | 91.86 → 71.24 |
| Vanilla / 6 regions, final | Candidate, reference | 2,602 | 2,513 | −3.4% | 89.40 → 70.55 |
| Vanilla / 12 regions, final | Reference, candidate | 2,760 | 2,636 | −4.5% | 88.76 → 67.38 |
| Terralith / 6 regions, initial | Candidate, reference | 1,629 | 1,586 | −2.7% | 93.61 → 70.63 |
| Terralith / 6 regions, final | Reference, candidate | 1,591 | 1,506 | −5.4% | 93.65 → 72.32 |
| Terralith / 12 regions, final | Reference, candidate | 1,708 | 1,864 | +9.1% | 95.59 → 71.66 |

Six-region batches cover X=-1..1/Z=-1..0; twelve-region batches extend Z through 2.
Rates divide completed chunks by batch wall time through MCA publication, excluding
startup and correctness comparison. NBT counters sum worker time and are not
additive wall-time savings. The reliable 20–25% reduction in NBT work does not
establish higher full-pipeline throughput: other stages, including decoration and
GPU waits, vary substantially, and multiple final vanilla samples are slower.
The larger Terralith result alone is insufficient to accept the change. This does
not prove the technique can never help; it leaves the production decision unchanged.

Sample tails are unstable. Twelve-region p95 latency changes from 1,128 to 1,694 ms
for vanilla and 2,449 to 1,804 ms for Terralith. These small samples do not establish
stable production tail latency. Process peak RSS also has no consistent reduction:
the final six-region pairs are 2.082 → 2.027 GiB (vanilla) and 2.857 → 2.882 GiB
(Terralith); twelve-region pairs are 2.292 → 2.536 and 3.176 → 3.280 GiB.

Logical GPU payload is unchanged by the encoding algorithm. Six-region vanilla
traffic is 190.30 MiB/region and Terralith approximately 200.26 MiB; twelve-region
figures are 190.55 and approximately 203.00 MiB. Small Terralith differences reflect
dynamic work scheduling, not a new transfer format. These counters describe
logical API payloads, not physical GPU bandwidth.

## Correctness and reproduction

- 84 ordinary unit tests pass. Bulk-biome tests cover odd and negative minimum Y,
  distant X/Z, packed-word halves, all axis ordering, and partial/out-of-bounds
  section coverage against scalar samples.
- Complete NBT comparisons exercise mixed and uniform sections, material
  properties, scratch reuse after palette changes, light arrays, full/partial/no
  biome masks and lit/unlit output.
- The real-GPU streaming fixture verifies all 900 chunks' classifications against
  final blocks and light-array equality, consumer-error recovery, GPU lock release
  and border coverage.
- Eight six-region corpora each match all 6,144 decompressed NBT records against
  retained references. Both twelve-region candidate corpora match all 12,288
  records against their paired references. Membership is identical, including
  structures and lighting; the complete corpus hashes are retained.
- The final normal-release test build took 44.91 seconds; its ordinary tests,
  isolated encoding comparison and streaming GPU test took 0.14, 0.48 and 0.16
  seconds respectively. The bounded native comparison build took 22.36 seconds.
  An initial test build failed on constructor visibility; the corrected build and
  failure log are retained. No execution timed out.

Apply the patch in a separate worktree. Build with two Cargo jobs and run normal
unit tests plus `section_encoding_comparison` and
`streamed_region_matches_sequential_tiles_and_recovers_from_consumer_error` using
`--ignored --nocapture --test-threads=1` for the two explicit fixtures. The latter
uses an actual Metal device. Whole-generation comparisons use
`scripts/native-region-benchmark.py --readiness shader-ready --count 6 --warmups 1
--parallel 2`, or count 12, with matched profiles and `--compare` against the paired
reference corpus. Preserve each native library before rebuilding. Build with
`cargo rustc --release --locked --manifest-path native/Cargo.toml --lib --crate-type
cdylib -- -C lto=off -C opt-level=2` for the comparison settings used here.

All expensive jobs ran sequentially, with 60-second compilation and 30-second
execution process-group deadlines plus one second to terminate. No game launch
or GitHub Actions was needed. Production source and the shared ignored native
library are restored; the preserved patch passes `git apply --check`. Combined
pipeline, lifecycle and final baseline comparison remain outstanding.
