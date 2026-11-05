# Refinement cycle 10: codec scratch and actual Minecraft decoding

Cycle PR: [#423](https://github.com/ArcaneArts/Retina/pull/423).

Retain the production-source codec benchmark; **discard the runtime scratch change**. Five paired codec runs show a small 1.10% benefit, about 1 ms per 1,024 records. Five paired complete-region trials per backend do not establish a generation improvement. The production encoder, compact record copy, compressor lifetimes and codec defaults remain unchanged.

The [machine-readable record](refinement-cycle-10-codec-scratch-2026-11-04.json) includes all individual measurements, source/native/compiler identities, commands, variance, descriptive p95, CPU work, RSS, output sizes, actual decoder reports and validation scope. Large NBT/MCA corpora, JFR recordings, patches and binaries remain outside Git.

## Actual workload and reuse model

Fresh saved-definition Metal generation produced zlib and LZ4 versions of the same vanilla Overworld region (0,0), seed 123456789, min Y -64 / height 384. The 1,024 decompressed chunks match exactly. Extracting their unchanged encoded NBT yields 70,303,233 B of real codec input.

The new `native/examples/codec_benchmark.rs` compiles the production Encoder source directly and selects the same conditional allocator as production. It retains compressed Records, checks every measured/warmup result against its first encode, and writes a binary record dump for comparison across binaries. External checks compare those dumps to actual published MCA payloads byte-for-byte. Initial system-allocator observations are excluded from the production-allocator timing evidence.

This is codec-only attribution. It excludes NBT construction, terrain, lighting, publication, decoding and Record destruction. Batch 8/32/1024 is an explicit reuse model; it is not a count of actual Rayon scratch objects. The real lit-MCA path already uses `with_min_len(8).map_init(ChunkScratch::default)` per lighting batch.

| Production-allocator model | Batch 8 median | Batch 32 median | Batch 1,024 median |
| --- | ---: | ---: | ---: |
| zlib | 97.928ms | 98.431ms | 97.573ms |
| LZ4 | 50.198ms | 50.095ms | 50.153ms |

Each case has five warmups and 20 measured iterations. This exploratory result does not justify worker-local pooling, reentrancy handling or unsafe Send changes. Packed payload totals are 7,196,315 B (zlib) and 11,047,733 B (LZ4), much smaller than raw NBT. Removing the existing compact copy by handing each Record a bound-sized buffer could increase retained memory; it is not accepted as an obvious optimization.

## Rejected grow-only scratch prototype

The original zlib path truncates scratch to the compressed length, then resizes/zero-fills most of its raw-input bound for the next chunk. The prototype retained initialized scratch, returned the actual written size, copied only that prefix, and preserved the LZ4 fallback and inline sector limit. Capacity was already retained by Vec before the change; this was a proposed reduction in fill work, not a demonstrated RSS reduction.

For the batch 8 input model, logical fill lengths fall from 64,066,309 B to 10,020,650 B. These are source-derived Vec operations over actual payloads, not observed DRAM bytes or an elapsed-time percentage.

Ten interleaved AB/BA processes, each with five warmups and 20 codec samples, show median codec latency **-1.10%**, descriptive bootstrap interval **-1.58% to -0.53%**. All five process-pair deltas are negative; all 10,240 explicit dumped payloads match published MCA bytes. This establishes a small codec-phase effect.

The required complete lit/decorated/encoded/zlib MCA trials use frozen baseline 775bb131... versus candidate 31381abe..., one warmup, two measured regions (0,0)/(1,0), native budget 16 / global Rayon 2, one caller, final shader readiness and five matched process pairs per backend:

| Full-MCA trial | Throughput change | Descriptive bootstrap interval | Process CPU change |
| --- | ---: | ---: | ---: |
| CPU | -1.65% | -46.70% to +110.53% | -0.83% |
| Metal | +2.75% | -18.10% to +28.80% | +0.14% |

Both complete-region results are inconclusive under changing shared-host load. Process CPU work does not establish an overall saving either. The roughly 1 ms codec effect does not justify retaining a production change under this pass's acceptance rule. The implementation and its added codec test are restored; no generation improvement is claimed.

## Actual Minecraft read/decode compatibility

The existing RegionDecodeBenchmark ran on both baseline and candidate zlib/LZ4 MCA copies through Minecraft RegionFileStorage and SerializableChunkData parsing, JDK 25.0.1, eight active processors, 2 GiB heap and JFR profile settings. Each file completes six 1,024-chunk passes, always 900 lit interiors and 26,376 sections. Input MCA files remain unchanged. The two JVM runs total 24,576 read/parse operations over repeated fixtures.

The baseline's five warmed passes have median total/read/chunk-parse timings of 346.23/183.59/157.31 ms for zlib and 255.96/89.62/161.42 ms for LZ4. MCA files are 9,326,592 B versus 13,271,040 B. This single ordered, JFR-instrumented exploratory run illustrates a decoding/size tradeoff; it does not justify changing codec defaults. Candidate timings are a different load/JVM window and cannot be treated as a controlled speed comparison. Candidate compressed payloads are byte-identical to baseline for all 2,048 checked records across the two formats.

JFR stack attribution separates bootstrap/registry setup from read/decompression/NBT and chunk parsing. Across the baseline's twelve passes, sampled allocation weights are approximately 2.72 GB in read/decompression/NBT and 3.49 GB in chunk parsing. These are weighted estimates, not exact allocation counts or RSS. Chunk-parse samples include DataResult/Optional/array allocations and block-count scans; they are leads for host profiling, not evidence about live server activation or Bukkit delivery. Missing/truncated stacks remain explicit. The full recording is rendered with `jfr print --stack-depth 256`; the renderer's default shallow stacks are insufficient for this attribution.

## Validation and remaining scope

23 actual native generator processes complete 66 fresh MCA generations including 23 warmups: 44,032 measured generated records and 38,912 strict same-backend NBT comparisons. The prototype also passes four codec tests and four storage tests, including large/small mixed-codec transitions, LZ4 oversize fallback, partial payload/timestamp preservation, cached promotion and failure atomicity. The retained benchmark is rebuilt against the restored original encoder and checked against published zlib/LZ4 payloads.

The two exploratory formats, native micro work and complete-region trials are distinct evidence categories. No shaders, concurrency budgets, world definitions, Java delivery implementations, backend defaults, release artifacts or JARs are changed. Native palette/NBT construction attribution, bounded overlap, actual Bukkit delivery and real Windows transport qualification remain separate outstanding work.

## Reproduce the retained tool

First generate a private lit MCA with the production saved-definition harness. Use the existing `scripts/benchmark_support.py:mca_records` helper to extract unchanged NBT files and a manifest shaped as `{"records":[{"file":"0000.nbt"}]}`. Full input provenance/hashes are in this cycle's external manifest. Then:

```sh
cd native
cargo build --release --locked --example codec_benchmark
./target/release/examples/codec_benchmark \
  /absolute/path/nbt-inputs /absolute/path/codec-result.json 8 20 5 zlib
```

Use a new output path for each run. Replace zlib with lz4 to test that existing format. Compare the emitted `.records` dumps between variants and against actual MCA records outside timing. Use complete-region and actual consumer checks before retaining runtime changes; a smaller scratch byte count is insufficient.
