# Bend package evaluation

Retina's playable Bend backend currently imports Base and local modules. It does
not use bend-kit packages for generation, integer arithmetic, NBT or compression.
The package imports in `bend/tests/package-evaluation.bend` are isolated benchmark
dependencies. This cycle changes no generator behavior or installed worker.

## Candidates

| Package | Assessment |
| --- | --- |
| [bytes](https://github.com/paymog/bend-kit/tree/main/bytes) | Useful candidate for binary buffers. Four octets share each U32 slot. Its public append API grows geometrically and copies whole words; positional/cursor reads and writes include bounds handling. |
| [zlib](https://github.com/paymog/bend-kit/tree/main/zlib) | The pure String encoder is compatible and valid on the tested payloads. It uses LZ77 hash chains and fixed Huffman coding. Its packed-word IO compression API calls native libz and does not meet the pure Bend backend requirement. |
| [int](https://github.com/paymog/bend-kit/tree/main/int) | The author's documentation warns that Word(64n)-based U64/I64 arithmetic is slow and should not be a hash accumulator. Keep Retina's paired-U32 seed/packing arithmetic until a focused comparison justifies changing it. No integer performance benchmark was run here. |
| [json](https://github.com/paymog/bend-kit/tree/main/json) | Useful for future JSON tooling, but the worker currently receives a typed binary registry projection. JSON parsing would add a second representation and would not replace Minecraft-specific NBT/MCA encoding. Not benchmarked here. |

The author repository snapshot inspected was
`paymog/bend-kit@2da39c00250d09cc7bb88791a2fc187693585018`. Published package contents
matched that snapshot's bytes/zlib source. The benchmark pins actual immutable
package content, rather than trusting a mutable README or name alone:

| Published version | Content import | Source SHA-256 |
| --- | --- | --- |
| bytes 0.3.2.0 | `0x185ae03c75e3e75be1171471f68b43cb/bytes.bend` | `07a046795be85c891390055013bca17bb6e4e88a91e836eec0df4a9e1dc5cabe` |
| zlib 0.2.0.1 | `0xcc180113489c489d3f5cdc852e6806f9/zlib.bend` | `4ee0558b7f987c788c7c78f8697b060ff55396f98b4a7c81f7c005278bfd045a` |

Bytes imports int by content hash; zlib imports hash. Other bend-kit packages can
use different bytes content hashes, which define different module types. Preserve
those identities when combining packages. No third-party source is vendored in
this change. The author repository contains an Apache-2.0 LICENSE; the downloaded
bytes/zlib package roots contain no LICENSE. Resolve the published distribution's
license metadata before vendoring or shipping it in a release.

## Local CPU results

Measured on Apple M4 Max/macOS arm64 with the stock Bend 2.0.36 compiler/runtime,
two CPU threads and nice +10. One compiler ran at a time. Compilation, file reads,
first output writes and process startup are outside the internal intervals.
Five warmup samples precede 20 measured samples per input/lane; every sample
performs 16 serial operations. Fresh input cloning and complete output checksum
consumption are included. All 16 checksums contribute to the result, preventing
unused pure repetitions from disappearing. IO.now's 1 ms clock is divided over
16 operations; small differences remain quantized. This is CPU evidence, with
GPU execution explicitly disabled, and is not a generator throughput benchmark.

The four input snapshots are actual pure Bend-generated chunk NBT from vanilla,
Terralith, combined profiles and cave-biome fixtures, with data version 5023.
They are early unlit terrain snapshots without surface trees/structures, not a
representative final lit-region corpus. Reproducible compressed fixture snapshots
and provenance are in `scripts/fixtures/bend-package-nbt.json`; the compression
there only reduces the repository fixture size. Timed input is the original NBT.

Compression starts and ends in the same byte-valued U32 representation consumed
by Retina's MCA assembler. Package String conversions are included. Median ms
per operation and final compressed sizes:

| Input | NBT bytes | Local zlib ms | Package zlib ms | Local output | Package output |
| --- | ---: | ---: | ---: | ---: | ---: |
| Vanilla | 22,344 | 1.0625 | 1.4375 | 2,732 | 2,018 |
| Terralith | 22,388 | 1.3125 | 1.6250 | 2,847 | 2,092 |
| Combined | 22,388 | 1.2500 | 1.5313 | 2,847 | 2,092 |
| Cave biomes | 6,203 | 0.3125 | 0.3750 | 669 | 627 |

The package saves about 26% on the larger snapshots and 6% on the cave snapshot,
while the observed warm medians are 20–35% slower. The separate perf guard run
(5 warmups/20 measured driver invocations, each executing 128 compressions of
each input) reports **550.620 → 661.823 ms median (+20.20%)**, and
**634.394 → 708.271 ms p95 (+11.65%)**. Those guard figures include Python/process
startup, file IO and validation, so they corroborate the adapter tradeoff rather
than measuring the codec alone. The candidate fails the 5% adoption threshold;
that is an expected evaluation result, not a broken production test.

The package always emits fixed-Huffman blocks. The independently decoded random
65,536-byte control produces 69,078 bytes, versus 65,552 bytes from Retina's stored
fallback. Retaining a bounded expansion fallback matters for MCA sector limits.

Byte replay exercises the writer's actual per-byte primitive but does not
construct tags, palettes, heightmaps or complete chunks. Presized lanes are
best-case controls given the final length up front; growable lanes are the useful
comparison for normal NBT writing. Median ms per operation:

| Input | Local growable | Local presized | Packed presized | Packed presized + adapter | Packed growable | Packed growable + adapter |
| --- | ---: | ---: | ---: | ---: | ---: | ---: |
| Vanilla | 1.0625 | 0.6250 | 0.2500 | 0.6875 | 0.5000 | 0.9375 |
| Terralith | 1.1250 | 0.6250 | 0.3125 | 0.6875 | 0.4688 | 0.9375 |
| Combined | 1.1250 | 0.6875 | 0.3125 | 0.7500 | 0.5000 | 0.8750 |
| Cave biomes | 0.3125 | 0.1875 | 0.0625 | 0.1875 | 0.1250 | 0.2500 |

Packed buffers use one quarter as many payload array slots on these inputs
(8,192 vs 32,768 for the larger snapshots), not one quarter of overall memory.
Whole-process peak RSS is recorded separately for every lane, including input
transport and the runtime. Some p95 tails are noisy, especially the vanilla
growable+adapter lane (1.9375 ms vs its 0.9375 ms median). Other host work was not
controlled; recorded load averages were approximately 8.7–9.8 during this run.

## Decision and verification

Keep the current production codec. Bytes is the best adoption candidate, with
roughly 53–60% lower growable byte-replay medians, or 12–22% lower after adapting
back into today's unpacked buffers. Before adoption, compare complete NBT chunk
encoding and compression, including growth/error limits, large payloads and
current caller access to Writer fields. The baseline presized control also shows
that reducing growth/copies can help without adding a dependency. No overall
performance improvement is claimed from this microbenchmark.

The package/local encoders independently round-trip all 15 control payloads and
four chunk snapshots through Python zlib: empty, all 256 octets, overlapping
repetition, random bytes and 32/64 KiB boundaries. Thirty-eight saved compressed
streams are independently decoded, with EOF, trailing-data and Adler checks.
Every timed repetition's checksum and length are verified; raw writer outputs
match the original bytes. Packed-only lanes are checked by their complete Adler
checksum, with byte-for-byte conversion checks in the corresponding adapter
lanes. Each real NBT fixture is independently parsed to EOF and its data version
and section count checked. Source/executable/input hashes, all samples, p95s,
RSS and the guard artifacts are in
`docs/benchmarks/bend-package-evaluation.json`.

Reproduce locally, without launching Minecraft or triggering CI:

```sh
python3 scripts/benchmark_bend_packages.py --build
```

The separate skill guard uses the same manifest/executable with
`--guard-lane local --warmups 0 --repeats 8` and
`--guard-lane package --warmups 0 --repeats 8`. Run them sequentially with
`perf_guard.py capture` (default 5 warmups/20 iterations), then compare with
`--threshold-pct 5`. A package slowdown is expected for this captured baseline.

Surface feature writers, neighboring placement ordering, structures, snow,
lighting, full regions and equivalent complete Rust/Bend benchmarks remain
required by the backend parity goal.
