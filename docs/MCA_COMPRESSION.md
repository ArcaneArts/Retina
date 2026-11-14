# MCA chunk compression

The mod defaults to these settings in `config/retina.toml`:

```toml
mca_parallelism = 4
mca_compression = "zlib"
mca_compression_level = 1
lazy_compression = true
```

YAML uses the same keys and values. Restart after changing settings. Existing
explicit values continue to apply.

Lazy compression publishes raw NBT immediately. One native background thread
re-encodes raw records in idle MCA files. The worker runs at low OS priority and
pauses between work slices. Saved regions take precedence over temporary Distant
Horizons regions. Minecraft closes inactive files from its I/O queue after about
15 seconds without file access. A new read or publication can interrupt a
staged rewrite. Already encoded records and their timestamps are preserved.
Leave enough free space for the raw backlog and one additional staged region.

Both headless CLIs accept `--lazy-compression true|false`,
`--compression none|zlib|lz4`, and `--compression-level N`. They start the worker
as soon as the first published region is free. Generation completion does not
wait for queued compression. Shutdown discards unfinished work and keeps the
original region usable. Re-running the same bounds makes remaining raw chunks
eligible. In-game counters and headless summaries show raw and encoded
chunk writes for the session. Encoded counts include stored zlib level 0.

Set `lazy_compression = false` or pass `--lazy-compression false` to encode as
part of generation. Choose `none` to leave chunks raw. Zlib levels 1–9
compress the chunk. Level 0 stores it inside a zlib stream. Integers clamp to
0–9, and missing or invalid values use 1. Raw NBT and LZ4 ignore the level.
LZ4 requires a 26.3 definition in headless mode. On 1.20.1, selecting LZ4 in
the mod config logs a warning and uses zlib at the configured level.

The Bukkit API plugin hands chunks to the server, which controls their disk
encoding. Keep any `c.*.mcc` files with their region directory when copying a
save.

## Format and save lifecycle

- Type 2 is a zlib stream encoded by libdeflate. Type 4 uses LZ4 1.10.0 acceleration 1,
  independent 64 KiB blocks, the `LZ4Block` Java stream headers, little-endian
  lengths, the Java-compatible 28-bit XXH32 checksum, and a terminal header.
  This is not the generic LZ4 Frame format.
- Each record carries its codec through parallel assembly, lighting, compression,
  and publication. Compressors and scratch buffers are reused within worker batches.
- Type 3 holds raw chunk NBT. When a raw or zlib payload exceeds the inline
  255-sector limit, Retina writes a `.mcc` sidecar with that payload before
  publishing its MCA record. Background compression can remove a raw sidecar
  after its chunk has been saved inline. If the encoded chunk stays oversized,
  the original sidecar is kept.
- Temporary-region promotion copies records with their codec identifiers and
  any sidecars.
- Existing destination records remain opaque and unchanged, including records
  written by Minecraft and external `.mcc` references. Changing the setting does
  not rewrite already generated chunks. Partial repairs can create mixed-codec regions.
- If an LZ4 record exceeds the inline 255-sector limit, that chunk is retried with
  zlib at level 1. If it still does not fit, generation fails before publishing the file.
- The existing single atomic MCA publication, file/directory durability, outer-ring
  lighting policy, and column-cache format are unchanged.

The framing follows the primary [LZ4BlockOutputStream implementation](https://github.com/lz4/lz4-java/blob/master/src/java/net/jpountz/lz4/LZ4BlockOutputStream.java)
and is exercised through Minecraft 26.3's actual `RegionFileStorage` reader.

## Lazy compression benchmark on Metal

The standalone CLI generated 32 Overworld regions per run on an Apple M4 Max
using the 26.3 bundled vanilla definition, seed 123456789, and region bounds
`24,-18` through `31,-15`. Runs used one region at a time and two native workers
(`RAYON_NUM_THREADS=2 RETINA_REGION_THREADS=2`). The table reports medians from
four runs per mode. Region p95 is the median of the per-run p95 values.

| Mode | Generation | Chunks/s | Region median | Region p95 | Disk usage at exit |
| --- | ---: | ---: | ---: | ---: | ---: |
| Raw, lazy off | 17.62 s | 1,877 | 496 ms | 879 ms | 2,118 MiB |
| Zlib 1, lazy on | 17.99 s | 1,837 | 502 ms | 815 ms | 1,194–1,770 MiB |
| Zlib 1, lazy off | 18.24 s | 1,798 | 498 ms | 800 ms | 265 MiB |

Lazy zlib 1 compressed 6–16 regions before exit and reclaimed 349–925 MiB.
The ~2% median throughput difference against raw was smaller than run-to-run
variation on this machine. A separate 32-region lazy zlib 5 run compressed two
regions and reclaimed 121 MiB before generation finished.

## Measurements: 2026-10-19, Apple M4 Max / Metal

Native source checkpoint `d22d01a` includes main `21dcc74`. Both codecs used the
same release library, SHA-256
`73333f7ee298ca66bbde7e30f9830135c71a6398c5589edfe3d21bc72e473308`.
The fixed vanilla/Terralith registry exports, modified profile hashes, complete
coordinates, stage costs, transfers, process RSS, file sizes, and individual region
latencies are in [the raw results](benchmarks/mca-codecs-2026-10-19.json).

Every run used two native CPU workers, reduced priority, one warmup, seed
123456789, and a 30-second process-group deadline. Two-caller runs generated six
adjacent full-height, lit MCA regions; the eight-caller pair generated eight.
The GPU interpreter was explicitly selected to keep asynchronous specialization
out of the comparison. These are native generation-through-publication results,
not specialized-mode, Java activation, DH conversion, or rendering measurements.

| Workload / order | Zlib chunks/s | LZ4 chunks/s | Throughput difference | MCA size difference |
| --- | ---: | ---: | ---: | ---: |
| Vanilla, zlib then LZ4 | 1,513.0 | 1,607.8 | +6.3% | +38.8% |
| Vanilla, LZ4 then zlib | 1,689.0 | 1,693.5 | +0.3% | +38.8% |
| Terralith, zlib then LZ4 | 764.5 | 845.1 | +10.5% | +37.6% |
| Terralith, LZ4 then zlib | 829.7 | 797.0 | −3.9% | +37.6% |
| Vanilla, eight callers, zlib then LZ4 | 1,613.1 | 1,687.7 | +4.6% | +38.9% |

Compression worker time per chunk fell about 41–49% across these pairs. The
complete throughput results are variable; two pairs per profile and one eight-caller
pair are insufficient to claim a universal generation speedup. The larger files
are a consistent cost: the six-region vanilla corpus grew from 51.66 to 71.71 MiB,
and Terralith from 45.49 to 62.60 MiB, including MCA headers and sector padding.

The measured GPU traffic stayed approximately 190 MiB/region for vanilla and
201 MiB/region for Terralith; compression does not remove GPU transfers. Process
peak RSS ranged from 1.62 to 2.23 GiB across runs, including Python's post-timing
corpus decoding/comparison, so it is not an isolated generator-memory comparison.

### Minecraft read cost

Two generated regions (2,048 chunks) per profile were read with Minecraft's actual
`RegionFileStorage`. Two warmups preceded five alternating-order samples. Timing
includes opening, reading, decompression, NBT parsing, and closing files. It uses
warm OS file caches and excludes chunk activation, rendering and lighting updates.

| Profile | Zlib median | LZ4 median | Read-time reduction |
| --- | ---: | ---: | ---: |
| Vanilla | 293.25 ms | 147.30 ms | 49.8% |
| Terralith | 301.70 ms | 155.14 ms | 48.6% |

This makes LZ4 useful to users willing to spend more storage for cheaper chunk
reads. It does not prove that live-world loading will be twice as fast; other
loading stages and cold-disk I/O remain outside this measurement.

## Correctness coverage

Local validation on the native checkpoint passed 92 ordinary Rust tests (36
GPU/manual fixtures remained explicitly ignored), five Python harness tests,
the TOML/YAML configuration test, and the real-Minecraft codec lifecycle test.
The latter executed GPU lighting; the ignored fixtures are not claimed as run.

- Full decompressed NBT equality for 45,056 repeated chunk comparisons across the
  codec matrix, including 8,192 chunks in the eight-caller pair.
- Minecraft reads both complete codec outputs and their mixed-codec combinations.
  The focused fixture compares all 1,024 complete tags, including 900 GPU-lit
  interior chunks and 124 unlit edge chunks per codec.
- Actual Minecraft writes/deletions exercise player-edit preservation, partial
  repair, promotion in both codec directions, and byte-for-byte preservation of
  every pre-existing record. Complete regions are unchanged when requested again
  with a different codec. Eight independent cached-region publishers also complete.
- An unsupported cached codec fails without modifying the destination; the preview
  source is never rewritten. This test does not claim to validate arbitrary damaged
  compressed payloads during promotion.
- Native tests cover empty, raw, compressed, exact-block, multiple-block, and reused
  compressor inputs, plus LZ4-to-zlib size fallback and unrepresentable records.
- Real TOML/YAML round trips cover default/backward-compatible configuration,
  explicit codec selection, invalid types/values, and existing-file preservation.
- The independent Python LZ4 reader checks stream framing and checksums and uses
  separately packaged LZ4/xxHash implementations for benchmark corpus validation.

No game was launched and no GitHub Actions workload was requested. Validation
here covers macOS/Metal; other native targets were not cross-built in this cycle.

## Reproduction

Use the repository's bounded execution policy: two build/runtime workers, nice
+10, 60 seconds for builds and 30 seconds for executions, with process-group
cleanup on timeout. Build once and retain the library for comparisons.

`./gradlew configurationTest mcaCodecTest` runs the configuration and actual
Minecraft save-lifecycle checks. `-PretinaNativePath=/absolute/library/path`
selects a retained native build; exclude `buildNative` and `packageNative` only
when intentionally using that already-built library.

The Python benchmark needs optional `lz4` and `xxhash` packages to validate LZ4
files (tested with 4.4.5 and 4.0.2 respectively). Zlib-only comparisons retain
their standard-library-only dependency set.

```sh
python scripts/native-region-benchmark.py \
  --library /absolute/library/path --definition /absolute/path/retina.dat --dimension minecraft:overworld \
  --out /tmp/codec-zlib --parallel 2 --count 6 --warmups 1 \
  --program-execution interpreter --interpreter-preload disabled \
  --mca-compression zlib
python scripts/native-region-benchmark.py \
  --library /absolute/library/path --definition /absolute/path/retina.dat --dimension minecraft:overworld \
  --out /tmp/codec-lz4 --parallel 2 --count 6 --warmups 1 \
  --program-execution interpreter --interpreter-preload disabled \
  --mca-compression lz4 --compare /tmp/codec-zlib
./gradlew mcaCodecReadBenchmark \
  -PcodecBaseline=/tmp/codec-zlib -PcodecCandidate=/tmp/codec-lz4 \
  -PcodecReadReport=/tmp/codec-reads.json
```

Use fresh output directories and reverse codec order for subsequent samples.
The read benchmark copies the first two measured regions to private temporary
directories, then removes those copies. It does not read or write live saves.
