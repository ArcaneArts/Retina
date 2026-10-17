# Compression of identical finished chunk data

The current Bend worker compresses valid finished chunk NBT, but is substantially
slower and produces larger streams than Rust's codec on this corpus. This is a
**serial request comparison**, including framing and pipe transport. It does not
measure codec-only time or complete region throughput.

On Apple M4 Max, 36 unchanged NBT payloads from six saved vanilla-profile regions
total 2,550,371 bytes. Twenty-four chunks are lit interiors and twelve are unlit
boundaries. Four source regions were produced by Rust and two by Bend; **both
codecs receive all 36 exact same byte sequences**. Each region contributes fixed
slots 0, 33, 264, 528, 792 and 1023. Source file, payload, executable, harness and
output hashes are in [the full report](bend-compression-corpus.json).

One warmup precedes three measured batches in each persistent process. Startup,
compilation, independent validation and saving results are outside those times.
The processes run sequentially at reduced priority. Bend has two CPU workers and
GPU execution disabled; the Rust endpoint uses the production libdeflater
1.26.1/libdeflate 1.26.1 codec at level 1 with reused scratch storage.

| Result | Bend 2.0.37 | Rust |
| --- | ---: | ---: |
| Mean time for 36 requests | 3,145.69 ms | 4.20 ms |
| Mean request time per chunk | 87.38 ms | 0.117 ms |
| Input throughput including transport | 0.773 MiB/s | 579.19 MiB/s |
| Total zlib bytes | 310,519 | 222,494 |
| Fraction of raw input size | 12.18% | 8.72% |
| Post-run driver + worker RSS | 114.70 MiB | 35.55 MiB |

Bend's output is **39.6% larger**. The approximately 749-fold request-time gap
must not be presented as a codec-only ratio or projected onto region throughput.
Three measured repetitions are limited evidence, and other host activity was not
controlled. Rust finishes before the 500 ms memory sampler's first tick, so its
peak RSS is **unknown**, not zero. Post-run RSS is a lower bound, includes the
Python driver, and may double-count shared pages. Bend's sampled peak was
114.33 MiB; a slightly larger post-run snapshot illustrates missed peaks too.

## Transport control and next optimization target

A separate check sends each original NBT payload to the empty-only handshake
opcode. The unmodified engine reads the entire frame, returns expected error 603,
and remains available for a valid handshake afterward. This takes **1,254.20 ms
per batch**, about 40% of the compression-request time. It includes input
allocation, list-to-array conversion, disposal, pipe IO and a small error reply.
It lacks normal compressed-output transport, so subtracting it would not give
an exact codec measurement.

`transport.bend` receives byte lists and appends them into a growable array;
`binary.bend` grows that array by copying bytes. `compression.bend` separately
scans the checksum and uses byte-valued U32 arrays for matching/output. The next
useful experiment is bounded, resident-input measurement of complete NBT/codec
batches, followed by buffer allocation/copy reduction or packed buffers. Compare
before/after on the saved corpus and preserve decoded bytes. The earlier
[package evaluation](../BEND_PACKAGES.md) found that adopting package zlib alone
traded speed for size; this result does not justify switching codecs blindly.

Region generation constructs NBT inside Bend and compresses independent chunks
in batches; it does **not** send all raw NBT through opcode 3. The ingress result
diagnoses the request benchmark and large-frame consumers, not 40% of region
generation. The production encoder and installed worker are unchanged.

## Correctness and bounded execution

All 288 compressed streams (36 chunks × four batches × two codecs) round-trip
byte-for-byte through Python's independent zlib decoder, consume their complete
streams, and have stable output hashes. The transport control checks 144 expected
rejections and successful subsequent handshakes. Five small Python tests cover
corpus extraction, provenance and interrupted-run handling.

The original Bend generation run remains interrupted. Only its two subsequently
audited complete regions are used. Corpus preparation accepts that separate audit
without mutating or relabeling the generation report; unfinished `current_region`
checkpoints are excluded. Each selected MCA is read and checked against its
recorded hash, then sampled NBT is decoded to EOF and version/coordinates checked.

The previously unbuilt Rust endpoint had a compilation error because
`CompressionLvlError` does not implement `std::error::Error`. Constant level
initialization now uses an explicit expectation, matching production's known
valid level. Only this small endpoint and codec dependencies were compiled, at
two jobs and reduced priority, separately from measurements. Both the failed and
corrected build are recorded. No full worker build, GPU work, terrain generation,
Minecraft launch or GitHub Actions were run.

Every measured/control run had a 30-second process-group deadline and cleanup.
All completed: Bend 13.3 seconds, Rust 0.4 seconds, transport 5.1 seconds. There
were no timed-out benchmark retries. The report's `retained_artifacts` directory
contains the corpus, outputs, small Rust binary/build manifest, logs and sources.

Reproduce with retained data, already built workers and fresh output paths:

```sh
nice -n 10 python3 scripts/benchmark_compression_corpus.py prepare \
  --regions "$saved_regions/rust-two" \
  --saved-validation docs/benchmarks/bend-saved-output-audit.json \
  --corpus build/compression-corpus/corpus
nice -n 10 python3 scripts/benchmark_compression_corpus.py run \
  --backend bend --artifact "$bend_engine" --corpus build/compression-corpus/corpus \
  --out build/compression-corpus/bend --warmups 1 --repeats 3 --max-seconds 30
nice -n 10 python3 scripts/benchmark_compression_corpus.py run \
  --backend rust --artifact "$rust_codec" --corpus build/compression-corpus/corpus \
  --out build/compression-corpus/rust --warmups 1 --repeats 3 --max-seconds 30
nice -n 10 python3 scripts/check_bend_corpus_transport.py \
  --artifact "$bend_engine" --corpus build/compression-corpus/corpus \
  --out build/compression-corpus/transport --max-seconds 30
```

`saved_regions`, `bend_engine` and `rust_codec` refer to source data and executables
identified in the report. Relocated source reports need corresponding audit
provenance updated; preserve the original evidence.
