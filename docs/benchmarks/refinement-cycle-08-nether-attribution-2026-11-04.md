# Refinement cycle 8: actual Nether dependency attribution

Retain an **opt-in measurement tool**, not a terrain speedup. Real CPU and Metal captures find no raw count/provider sampling batches in this vanilla Nether workload. They instead observe millions of column reconstructions and sparse per-view caches. The next O2 cycle will test reducing view-cache allocation while preserving exact-key fallback. O2 is not complete from telemetry alone.

The [machine-readable record](refinement-cycle-08-nether-attribution-2026-11-04.json) pins source, binary hashes, inputs, commands, trial results, correctness, bounded-capture semantics and external logs. Baseline is current main `ed27dbcf`; its native source tree matches the frozen library built at `50c375c1`. Candidate v2 library SHA-256 is `775bb1317e26f7aa17459dedb91fc030093ab4354c76ea250b53a1e0df548597`. Rust/build settings and exact source-file hashes are in the JSON.

## What actually ran

- 34 native processes completed 82 fresh regions including 28 commanded warmups. The measured outputs contain 55,296 generated records and 46,080 strict same-backend decompressed NBT comparisons.
- Nether captures use vanilla retina.dat SHA-256 `98e8cf1a2e3e5e049192b0b5245b58aa44a8001f3b00a6ddeb51a619ad375b4e`, seed 123456789, region (0,0), profile 3, min Y 0/height 256. Existing production binding and complete lit/decorated/compressed MCA paths are used.
- CPU and actual Metal generation match their respective baseline outputs. Additional CPU/Metal Overworld output matches; that vanilla Overworld path bypasses this ordered planner and emitted no capture. Requested diagnostics do not imply available coverage.
- Two actual CPU outer-End cases point diagnostics at an existing file: generation still succeeds with exact output, and the file is unchanged.
- Six focused release unit tests pass; the Python suite passes 12 cases with four optional integration skips. The actual native runs above provide separate integration evidence.

## Measured work, not an assumed sampling bottleneck

CPU and Metal summary captures, and the Metal detailed capture, have identical aggregate view/prefix counts:

| Observation per Nether region | Count |
| --- | ---: |
| Replay polls | 26,496 |
| Detailed terrain-field calls | 596,211 |
| Detailed column-cell calls | 4,693,205 |
| Detailed column builds | 3,807,652 |
| Raw count queries / provider queries / sampling batches | 0 / 0 / 0 |
| Views / views allocating fixed slots | 26,567 / 16,764 |
| Cumulative requested slot payload | 411,992,064 B (392.91 MiB) |
| Filled slots / fallback entries at view drop | 1,142,642 / 2,560 |
| Mean filled slots per allocated 1,024-slot cache | 68.16 |
| Unchanged / changed successful unlimited prefixes | 3,053,302 / 265,780 |

About 91.99% of successful reconstructed prefixes preserve the prior column. This is a lead, not proof that those computations may be skipped. Existing interior version skipping, per-view memoization and edited-prefix rules still apply.

Most fixed caches are sparsely filled. For example, 1,415 ore-magma views use at most four slots each, while requesting 33.16 MiB of cumulative slot payload. Some fire/glowstone views fill hundreds, so a smaller fixed cache can increase collisions. A follow-up must measure fallback pressure and full-region effects rather than accepting a smaller allocation on byte counts alone.

Historical source review found the unmerged lazy-spatial-page prototype `0b0940e` and a separate material-read simulation `9121e6c`/historical cycle 54. No PR or committed performance verdict was found for lazy pages; the material-read simulation concerns XYZ overlay caches, not this fixed column-slot width. They are not established new wins or evidence that a particular slot width should ship.

## Disabled-probe controls

Ten interleaved AB/BA processes per backend use one warmup and two measured fresh Nether regions each, native budget 16/global Rayon 2, identical readiness, lighting and zlib compression. Detailed diagnostics are off. Comparison/hash work is outside generation timing.

| Default-off candidate versus baseline | Throughput change | Descriptive bootstrap interval | Process CPU change |
| --- | ---: | ---: | ---: |
| CPU | +3.81% | -8.82% to +12.04% | -3.97% |
| Metal | -0.46% | -1.13% to +1.43% | +0.92% |

CPU is inconclusive; Metal's measured difference is below 1%. Neither is a speedup claim. Individual timings, medians, variance, descriptive request p95, RSS, output sizes, startup, and process CPU work are recorded. Competing host workloads and process activity were captured; nothing unrelated was stopped and power settings were unchanged. These controls do not establish Windows performance or a full Java-delivery improvement.

## Capture scope and reproduction

`RETINA_DEPENDENCY_TRACE_DIR` opts into planner-local JSON. The default summary observes replay/sample spans, bounded root-level successful-prefix outcomes, and cache occupancy published at view drop. `RETINA_DEPENDENCY_TRACE_MODE=detailed` additionally times terrain/column/build spans. Detailed mode adds hot-path clocks and capture locking; its timings must stay separate from performance acceptance.

For the benchmark, use `--dependency-trace /absolute/path` to enable capture only after warmup/readiness and disable it after measured generation. For example, with a freshly built candidate library and the saved definition:

```sh
RAYON_NUM_THREADS=2 python3 scripts/native-region-benchmark.py \
  --library /absolute/path/libretina_worldgen.dylib \
  --definition /absolute/path/retina.dat --backend metal \
  --dimension minecraft:the_nether --assembly-threads 16 \
  --warmups 1 --count 1 --readiness shader-ready \
  --dependency-trace /absolute/path/traces --out /absolute/path/fresh-output
```

The schema caps events at 768 per kind, exact raw query keys at 65,536 per kind, and root aggregates at 512 buckets (511 is explicit overflow). It caps the metadata inventory and recipe names too. Drops and untracked keys are explicit. In summary mode, unobserved kinds have zero totals **because they were not measured**. Raw sampling is observed in both modes; its zero query count here is actual evidence of absence for this workload. Positive raw query-key/abort behavior is unit-tested, but an actual nonempty GPU sampling batch was not exercised by these captures.

Spans can nest and overlap across workers; totals are not elapsed-time shares or GPU utilization. Device timestamps have a separate clock and may be unavailable. Slot bytes are cumulative requested payload, excluding allocator overhead and column storage; they are not peak RSS. Closure of a capture does not prove successful generation. Diagnostic directory/write failure only reports an unavailable capture and never prevents generation or truncates an existing file.

No generation, concurrency, backend default, shader, definition format, or release changes are included. Next: a separate PR/worktree cycle must benchmark actual work reduction using these observations.
