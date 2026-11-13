# GPU and Rust performance investigation

Headless Metal on an Apple M4 Max, with native CPU comparisons on the same host. The measured output is a complete saved zlib MCA region. It includes terrain, features, interior lighting, encoding, compression and publication. The 124 outer-ring chunks use the normal Minecraft lighting handoff. These measurements use the Rust engine directly from a saved definition.

## Measurement setup

Baseline source is `a4e96fa8dd3136284353a27c20a3689d5fd0d8e1`, native tree `790c44d48102fb96bcc5a04fd486f8768d2a4d42`. The locked release build used rustc 1.93.0, LLVM 21.1.8 and wgpu 30.0.1. Baseline dylib SHA-256 is `ad4053b5758078698138fefa4fe0440ed5a7adf14cfaaaff3a4335e25923c371`.

Each throughput run started a fresh process and output directory, with one region caller, `--assembly-threads 4` and `RAYON_NUM_THREADS=2`. The CPU log confirms one assembly/dependency worker and three terrain workers. A separate Metal process sample confirms four active MCA assembly threads, plus the GPU and shader compiler owner threads. Builds and generation ran sequentially. Most Metal timing rows have two warmup regions at (8,8) and (9,8) and six measured adjacent regions starting at (−1,0). The separate two-caller test keeps the same worker limits. Timestamp and replay captures, foreground probes and fresh-process startup timings are separate workloads.

The Mac reported AC power, power mode 0, and no OS thermal or performance warning. Virtual machines and other active processes competed for CPU time. The per-run record includes load and CPU snapshots. Four identical-binary repeats per modern dimension produced exactly equal records but a wide range of wall times. Process CPU time was substantially steadier. Short device-pass comparisons and cumulative work counters inform candidate selection. Claims about throughput require longer matched repeats.

| Input | Provenance | SHA-256 |
| --- | --- | --- |
| Modern | 26.3 normal vanilla catalog, seed 12345, DataVersion 5023 | `bcce2aa8b88499fcaddbbc2ecffb7fc45806608ced4cc61ad154a0582aef4a68` |
| Legacy | 1.20.1 vanilla normal exporter with structures disabled, seed 12345, DataVersion 3465 | `c48d34d4b3af5549b729e665c4a7266f7374eee81f4e9d97363a0fd1f8987657` |
| Tall | 26.3, 768-block Overworld, seed 123456789, saved Iris pack export from the large-registry investigation | `96a1cc33d8dc6dda511be96a62dec6d915063504648745867242e4344dc8f527` |

The [machine-readable measurements](gpu-rust-investigation-2026-11-13.json) include the exact commands, individual regions, host and process counters, successful NBT comparisons, and failed runs. `<repo>` and `<artifacts>` represent the frozen checkout and local run directory. Exact definitions, disposable patches, commands and logs are retained locally under `~/.cache/retina/gpu-rust-investigation-2026-11-13`. Generated MCA corpora and Cargo intermediates are removed after the comparisons.

## Baseline and startup

| Workload | Completed chunks/s | Notes |
| --- | ---: | --- |
| 26.3 Overworld, Metal | 830–1,701, median 1,224 | Median region 715 ms, median of four descriptive p95 values 1,367 ms |
| 26.3 Nether, Metal | 486–672, median 577 | Median region 1,725 ms, median of four descriptive p95 values 2,205 ms |
| 26.3 central End, Metal | 9,718 | Two regions, from (0,0), CPU substrate and Metal lighting |
| 26.3 outer End, Metal | 5,576 | Two regions, from (−4,2), CPU substrate and Metal lighting |
| 1.20.1 Overworld, Metal | 98 | Two regions, sample includes substantial ordered replay |
| 26.3 Overworld, CPU | 56 | Two regions, about 18.4 s average |
| 26.3 Nether, CPU | 19 | Two regions, about 52.6 s average |

The Overworld and Nether identical-binary controls matched 36,864 decompressed chunk NBT payloads and their MCA membership. The End and 1.20.1 runs each closed and reopened the definition and preserved the first region's 1,024 records. Their rates are descriptive for the selected coordinates. Actual NBT confirms fortress, bastion and fossil starts in the Nether, trial chambers and ancient cities in the Overworld, and an End city in the central End sample. The outer End sample covers four biomes with no structure starts. In each full region, 900 chunks carry saved lighting and the outer 124 retain the Minecraft handoff. The 1.20.1 fixture has structures disabled.

Two fresh Metal processes per dimension produced first-region times of 1.47–1.60 s in the Overworld and 3.07–3.33 s in the Nether, measured after definition load and bind. Definition load and bind took 1.64–2.37 s across the four runs. Overworld specialization ran for 2.68–3.13 s and completed 0.63–1.03 s after the first region. Nether specialization ran for 0.27–0.39 s and was ready by the end of the first region. Re-generating each first region after readiness matched 1,024 NBT records. The Metal driver reported that wgpu's application pipeline cache feature was unavailable. System-level driver cache temperature is unknown.

The 768-block fixture generated its first warmup MCA with automatic execution, then remained in specialization status 1 through the harness's 180 s wait. The progress log recorded 35 of 45 discovered pipeline calls complete, ten queued and none active. Prior work measured an approximately 127 MB specialization source on this pack. The existing [large-registry report](../LARGE_REGISTRY_PROGRAMS.md) documents the export and interpreter path. The timed-out run cannot supply shader-ready throughput. Forced interpreter execution saved both measured regions and preserved the first on reopen. The first uninstrumented interpreter control completed about 66 chunks/s. Its stage observations put cave-node evaluation at about 5.0 s and material runs at about 3.1 s per region. This makes the pack a substantially different GPU workload from the modern vanilla controls.

## Where the time and bytes go

| Metal trace, per measured region | 26.3 Overworld | 26.3 Nether |
| --- | ---: | ---: |
| Terrain preparation wait/copy stage | 287 ms | 355 ms |
| Decoration planning | 181 ms | 2,238 ms |
| Zlib compression attribution | 320 ms | 121 ms |
| Lighting host stage, excluding encoding | 105 ms | 60 ms |
| Valid GPU pass union across captured submissions | 150 ms | 166 ms |
| Count plus emit material passes, device | 19.8 ms | 63.2 ms |
| Cave node and cave mask passes, device | 39.5 ms | 45.5 ms |
| Upload bytes, all observed native generation | 116.2 MB | 72.5 MB |
| Readback bytes, all observed native generation | 127.3 MB | 226.0 MB |

Each trace covers two complete regions with diagnostics on. It contains 33 Overworld and 134 Nether fully contained submissions, with no dropped records and four and six invalid timestamp pairs, respectively. The observed device pass union covers about 14.1% and 6.7% of the traced wall window. This is a lower bound on observed device work, since uncovered and invalid intervals exist. Host spans can overlap, and some assembly stages are estimates scaled from worker counters. The table cannot be added into a region duration. Transfer counters are logical copy lengths, including unified-memory Metal transfers.

**Nether replay.** The two traces recorded 7.71 million column requests, 6.23 million column builds, and 18.38 million interior versions skipped. Per region, between 15,132 and 16,928 views allocated a 1,024-slot cache, requesting 355–397 MiB of cumulative slot payload. Only 58–64 slots per allocated view were filled on average. About 92–93% of successful unlimited prefixes returned the prior column. The trace observed zero raw count or provider sampling queries across both regions. These counts support investigating exact column work and allocation. The prior [smaller-cache experiment](refinement-cycle-09-view-cache-size-2026-11-04.md) had high fallback pressure. The lazy-page variant preserves the same indices and per-view lifetime. A separate five-second Metal host sample also found Nether feature placement, prefix evaluation, material lookup and column writer resolution among the leading native leaf samples. These are sampled stack counts, including waiting threads, so they cannot be read as elapsed shares.

**CPU backend.** A five-second Nether sample found 3,053 leaves in `cpu::program::Evaluator::evaluate`, followed by cave-node evaluation (470), carving (343) and primitive noise (318). This supports the existing prepared-execution lead and gives a concrete low-worker reproduction. Metal and CPU can have different bottlenecks on the same region. In the separate RSS samples between the first and last published files, Metal's median resident set was 1,829 MiB and sampled peak was 2,025 MiB across 34 half-second samples. The CPU run recorded 485 MiB median and 848 MiB sampled peak across 77 samples. These are sampled process RSS, including host allocations. The process high-water metric remains separate and neither measurement apportions device residency.

**Lighting copies.** Nine standard tiles each cover a 10×10 output core and one-chunk input halo. Their 1,296 input chunk incidences include 272 repeats across the 1,024 input chunks. Lighting uploaded 108.5 MB of material per Overworld region and 72.4 MB per Nether region, so the duplicate halo is worth measuring in actual bytes. Reconstructing the upload from final section palettes reproduces the logged bytes exactly. It attributes 22.83–22.85 MB per Overworld region and 13.98–15.91 MB per Nether region to repeated halo chunks. One region-level material backing with tile-local descriptors could avoid repacking and reuploading repeats. This would trade retained storage for fewer transfers and needs separate evidence from the two-slot pipeline and packed-shader work already in the project. In addition, the lighting completion path copies the mapped byte range into a Vec before handing it to the synchronous encoder. Borrowed mapped views could eliminate that copy but may keep a readback slot busy longer.

**GPU work.** The cave node kernel has six unconditional source-level `registry_noise` calls for initial cave channels. Registered density overwrites the cheese, layer and pillar result, and an enclosed profile overwrites the remaining three calls' results. Moving those calls into the corresponding branches removes up to six source calls per node. A `registry_noise` call can execute multiple octave samples. The full-tile geometry from the capture gives about 13.6 million Overworld and 120.2 million Nether source calls affected across two regions each, assuming the corresponding profile branch. This counts call sites times dispatch geometry and excludes sparse biome probes. Actual instruction removal depends on the backend compiler. The material loop above the verified air bound repeatedly produces the same air run. The prototype starts at the bound while seeding the original air run and preserving the count/emit overflow path.

**Concurrency.** Two short AB/BA Nether pairs at one caller recorded 457 and 563 chunks/s. Two callers recorded 882 and 743 chunks/s, all at the same worker limits and with exact output. Peak process RSS rose by 471–901 MiB in the paired launches. Process CPU was 3.23–3.41 s per region across the four runs. With separate foreground biome probes, one versus two callers measured 10.26 versus 10.61 ms probe p95 and 23.24 versus 52.38 ms maximum. Generation p95 increased from 2.09 to 2.96 s in the foreground comparison. Six region timings per run make the request tail descriptive. These results motivate controlling overlap when latency or memory matters.

## Prototype screens

Every screen alternates A/B/B/A and compares each successful process after the first with the original complete MCA corpus. The prototype library does not change the format, and output checks are outside the timed interval.

- **Skip fallback cave noise after branch selection.** Overworld cave-node device time changed from 16.25 to 15.52 ms per region. Nether changed from 6.68 to 6.94 ms. All 24,576 candidate chunk payloads and region membership matched. The measured shift is small and the short throughput screen does not support further implementation. The six source calls represent potential dynamic work. The proposed placement restricts calls to branches that consume the result. Naga 30.0.1 MSL output for a standalone reference cave module preserves both conditional triplets. That check establishes branch placement in the intermediate source. The actual Metal compiler may already sink pure work, and final hardware instruction counts are unavailable. This prototype has a different target from resident cached-density masks.
- **Remove lighting payload zero-fill before a full overwrite.** An aligned bulk append replaces resize-and-copy of the mixed 8 KiB section payload. The original copy path handles unaligned input. Overworld throughput was effectively flat, with slightly higher lighting-host time. Short Nether runs moved from 671 to 771 chunks/s and 52 to 36 ms lighting-host time, while unrelated decoration planning and cave work also moved. A single-thread synthetic pack kernel isolated the aligned append at 0.22 versus 0.57 ms per 1,500 mixed sections. Two small central and outer End guards matched another 4,096 candidate records, including the central city's lighting. The short whole-region timings did not establish a repeatable speedup, so the experiment is closed.
- **Allocate existing 32×32 view slots in 8×8 pages.** This adapts the historical `0b0940e9` experiment and keeps exact-key fallback and per-view ownership. The first Nether screen's controls ran at 806 and 283 chunks/s and the candidate ran at 712 and 679. The slower control's host load rose sharply. Candidate process CPU was 2.98–3.00 s/region versus 2.67–3.19 for baseline. The targeted two-region capture reduced cumulative requested slot payload from 751 to 253 MiB (66.3%). Filled slots and the 5,138 fallback entries matched exactly. The evidence qualifies the byte reduction. The short runs do not establish lower CPU time or faster generation.
- **Skip the top air prefix of GPU material runs.** This is narrower than the October [air-range trial](../PERFORMANCE_RELIABILITY_PASS_2026_10.md#air-range-trial-not-selected), which also skipped below the generation floor and failed whole-region acceptance. In the short screen, combined material device time fell from 18.69 to 12.78 ms in Overworld and from 56.86 to 53.44 ms in Nether. Candidate chunk NBT and membership matched all 24,576 records. The 1.20.1 guard matched all 2,048 candidate records and preserved the first region on reopen. Its material device time fell from 112 to 82 ms per region. The 768-block interpreter guard matched another 2,048 candidate records and preserved the first on reopen. Its material time moved from 3.06 to 3.16 s, with similar throughput at 66.2 and 66.8 chunks/s. The longer modern Overworld A/B run doubled the measured workload to twelve regions per process and repeated the device shift, 17.71 to 12.16 ms per region (31% lower). Median complete-region rate was 1,798 to 1,724 chunks/s (−4.1%, descriptive bootstrap interval −9.8% to +2.1%). Process CPU was 1,192 to 1,232 ms per region. Both candidate processes matched 12,288 chunks each. Keep this as measured shader work reduction and reject it as a current throughput change.

## Existing shader path controls

The first explicit interpreter tests used the specialization-readiness check and failed with status 0 after completing their warmups. Forced interpreter execution has no specialization to await. Repeating with `--readiness driver-warm` produced exact output. The modern Overworld instrumented A/B/B/A recorded medians of 2,177 chunks/s with specialization and 939 with the interpreter. Overworld device cave-mask time was 20.3 versus 147.2 ms per region, material passes 18.4 versus 69.1 ms, and lake density 3.2 versus 67.7 ms. Nether specialization versus interpreter measured 729 versus 378 chunks/s, with cave-mask device time at 36 versus 468 ms and materials at 60 versus 410 ms per region. All 24 measured regions across the two dimensions matched the default same-backend reference. The modes select different groups of kernels, including resident reuse, so the comparison does not isolate a single WGSL transformation.

The opt-in aquifer column reuse A/B/B/A moved Overworld aquifer-field time from 5.84 to 3.21 ms/region. Short throughput controls varied from 959 to 1,528 chunks/s, and the candidate ran at 1,627–1,649. Other host and device stages moved too. The [previous qualification](../GPU_AQUIFER_COLUMN_REUSE.md) found a concurrent regression, and this run leaves the default-off policy as the appropriate baseline. The one-process lake-sparse and cached-mask toggle screens matched the reference corpus in both dimensions. Their small timing shifts are insufficient to revise either policy.

Across the investigation, 92 native processes completed 440,320 timed chunks. The harness made 335,872 strict NBT and membership comparisons, including regeneration after specialization, plus 12,288 saved-record checks after reopening. Three incomplete readiness runs are retained in the evidence. Twelve Python benchmark checks passed with four opt-in skips, followed by both native interval tests with actual Metal and CPU fixtures.

## Reproduce a single low-parallelism run

Build on the baseline revision, before starting generation. Point the harness at a fresh output path. Use the recorded definition hashes and keep the cache and readiness policy fixed across compared runs.

```sh
CARGO_BUILD_JOBS=3 cargo build --manifest-path native/Cargo.toml --release --locked --lib
RAYON_NUM_THREADS=2 python3 scripts/native-region-benchmark.py \
  --library /absolute/path/to/libretina_worldgen.dylib \
  --definition /absolute/path/to/modern.dat --dimension minecraft:the_nether \
  --backend metal --assembly-threads 4 --parallel 1 --warmups 2 --count 6 \
  --origin-x -1 --origin-z 0 --grid-width 3 --readiness shader-ready \
  --scope lit-mca --lighting enabled --mca-compression zlib \
  --out /absolute/path/to/fresh-output
```

Use a separate process and output directory for `--diagnostics --gpu-interval --dependency-trace /absolute/path/to/fresh-trace`. Use `--compare /absolute/path/to/baseline-output` for decompressed NBT and membership comparison. `--preservation-check` closes and reopens the definition in the same process and checks stored records. To reproduce a first-region run, use `--count 1 --warmups 0 --readiness driver-warm --startup-debug --await-specialization`. Reopen and NBT comparisons run outside the measured generation interval.

The modern definition can be materialized using the [universal CLI catalog](../UNIVERSAL_RELEASE.md). The legacy input comes from `:minecraft1201:exportNoiseTerrain -PretinaSeed=12345` with Java 17 available to Gradle. The tall definition uses the [actual supplied pack](../LARGE_REGISTRY_PROGRAMS.md) and the saved exporter output.

## Follow-up

The [tracking issue](https://github.com/ArcaneArts/Retina/issues/484) records the investigation's priorities.

| Priority | Candidate | Basis |
| --- | --- | --- |
| 1 | [Reduce ordered Nether column reconstruction](https://github.com/ArcaneArts/Retina/issues/479) | Replay dominates the measured low-budget Nether path. Prove a prefix can reuse an existing exact value before skipping a build. |
| 2 | [Attribute large-profile specialization](https://github.com/ArcaneArts/Retina/issues/481) | The 768-block pack can start generation while its optional specialization remains unresolved. |
| 3 | [Reduce tall-profile interpreter sampling](https://github.com/ArcaneArts/Retina/issues/486) | Cave and material kernels consume about 6.9–8.0 seconds of device time per region on the tall pack. |
| 4 | [Reduce dense final material storage in Rust](https://github.com/ArcaneArts/Retina/issues/483) | Normal 26.3 assembly retains 192 MiB and 128 MiB of raw final block IDs per Overworld and Nether region. About 110 MiB and 72–75 MiB of those bytes represent uniform sections in the four sampled regions. |
| 5 | [Reuse lighting halo uploads](https://github.com/ArcaneArts/Retina/issues/482) | The exact byte model matches the actual transport counters. Test buffer limits and extra retention against the saved copies. |
| 6 | [Measure region admission tradeoffs](https://github.com/ArcaneArts/Retina/issues/480) | Overlap increases throughput alongside working set and request latency. A bounded admission experiment needs a longer foreground and memory trace. |
| 7 | [Reduce CPU registry evaluator work](https://github.com/ArcaneArts/Retina/issues/487) | Low-worker CPU Nether averaged 52.6 s/region. A separate CPU sample identifies the registry evaluator as the leading native leaf. |

The material and lighting candidates would reduce shared work or bytes when the same path executes. Vulkan and DX12 use dense lighting transport by default and can choose different shader dispatch configurations. Subsequent Windows work should measure startup and device execution separately on both backends, compare exact output within each backend, and record transfer and residency costs on the target adapter. The Rust replay and storage paths run across native backends.
