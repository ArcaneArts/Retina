# Refinement cycle 12: bounded overlap and foreground fairness

Retain the opt-in measurement tooling; **discard the early-refill runtime prototype**. Five matched primary Metal pairs show median throughput -0.42%, descriptive paired bootstrap interval -10.87% to +4.25%. Process CPU, request latency and memory do not establish an efficiency win either. No generation implementation, thread budget, queue depth, publication path or backend default changes.

The [machine-readable record](refinement-cycle-12-region-overlap-2026-11-04.json) includes individual successful processes, commands, native/source identities, complete-region latency, CPU work, high-water RSS, sizes/transfers, output hashes, correctness, interrupted-trial disposition and host-load observations. Large MCA corpora, samples, native builds, timeline events and rejected patches remain outside Git.

## Hypothesis and decision

The existing GPU lighting region path admits at most two readbacks, finishes one, copies its result into owned CPU memory, unmaps/recycles the slot, then synchronously encodes that lighting tile. It refills at the next loop iteration. The private prototype refilled the recycled slot immediately before encoding, keeping at most two readbacks per region. Its CPU path returns before this code.

Initial ABBA screens at one/two/four Metal callers suggested two-caller throughput +9.37% by ratio of medians, but its adjacent pair changes were +25.25% and -2.74%. The one-caller apparent +30% was driven by a slow last baseline; four callers were neutral. These were screening observations, not qualification.

A fresh baseline and prototype use the same lane, Rust 1.93.0 / LLVM 21.1.8, locked dependencies, default scalable-allocator/spirv-tools features, release ThinLTO/two codegen units and build budget two. Frozen SHA-256 identities are 7c7dec86... versus a943f403...; full hashes and patch hash are in JSON. Neither a mutable build output nor a different compiler is treated as the control. The old frozen 775bb131... is used only as the initial source-equivalent golden reference.

Five primary two-caller Metal pairs, in AB/BA/AB/BA/AB order, each complete one warmup plus four measured lit, decorated, NBT-encoded, zlib-compressed and atomically published MCA regions:

| Metric | Median paired change | Descriptive paired bootstrap interval |
| --- | ---: | ---: |
| Throughput | -0.42% | -10.87% to +4.25% |
| Process CPU / region | -0.55% | -7.66% to +1.79% |
| Generation high-water RSS | -1.14% | -4.72% to +1.43% |
| Per-request native-call p95 | -2.79% | -9.13% to +10.94% |

Pair throughput changes are -0.42%, +4.25%, +3.48%, -10.87%, -2.90%. No real win survives confirmation. All measured output matches the existing same-backend NBT golden byte-for-byte. The runtime patch is restored completely; no unqualified Windows change is shipped. Broader runtime error/publication/dimension testing is unnecessary for discarded code, and is not claimed as done for this prototype.

Four CPU screening processes and three additional complete CPU controls are retained as observations. The added confirmation series is stopped during its fourth CPU process after the primary decision, as load exceeds 90 on 16 logical processors; its partial output is excluded. A two-second sample of that owned process shows actual terrain/cave work, not a demonstrated deadlock. The controller and child ownership were verified before terminating only that process group. Nothing unrelated was stopped and no power setting was changed. A single CPU pair is not a CPU performance verdict.

## Reproducible measurement boundaries

Every full-region process uses production SavedDefinition loading and world-bound requests: vanilla retina.dat SHA-256 98e8cf1a..., seed 123456789, Overworld generator/storage -64/384, measured square (0,0)/(1,0)/(0,1)/(1,1), warmup (8,8), assembly budget 16/global Rayon 2, shader-ready readiness and unchanged production encoding/compression/publication. Startup, warmup, readiness, output hashing and strict NBT comparison are outside headline generation timing. Initial baseline sweeps use one/two/four callers and reverse the order for their second trial; these are not five-pair concurrency recommendations.

New per-request dispatch_wait_ms measures time from offering the entire batch to executor admission. Existing ms remains the native generation call; return_since_batch_start_ms includes that call and the existing program-snapshot observation. This distinguishes queueing from request service without inventing per-request CPU attribution.

## Foreground stress harness

The optional --foreground-biome-probes workload calls the production native read-only biome-query API concurrently with region generation. It has one outstanding query, eight points per call and 10 ms think time after completion. Its deterministic 16,384-point remote-coordinate corpus exceeds the current 8,192-entry query cache. It is a substantial uncached stress load, not an ordinary player, FPS, Java decoding or rendering workload.

A bounded 1,024-point API batch reference produces the same full answer SHA as the earlier small-call reference on CPU and Metal. Native subdivides those API calls into its own 64-point batches. The reference is followed by 256 small idle calls on the same query thread, checking batch-shape invariance. Concurrent queries start beyond the just-warmed small-call prefix and every answer is checked. At most 16,384 latency samples are retained by deterministic reservoir sampling; exact count, minimum, maximum, mean and all correctness checks remain complete. Quantiles are marked sampled when appropriate.

Legacy pipeline.completed counts completed GPU terrain/query samples, not physical submissions. A sample can contain several submissions; CPU/constant shortcuts may legitimately count zero. These are observations and never admission gates.

Reference/idle setup precedes all before-counter snapshots. The MCA wall/process-CPU window stops when all generation requests complete; any admitted foreground call then drains before the world owner can close. Native timing/profile snapshots and optional GPU interval end after that drain, explicitly including it. Foreground tail is reported separately. Those device spans must not be divided by the shorter MCA-only wall as a GPU utilization percentage. Lifetime high-water RSS also includes startup/warmup/reference setup, not isolated scratch allocation.

| Metal callers | MCA chunks/s | Native-call p95 | Generation high-water RSS | Idle query median | Concurrent query p95 | Concurrent calls |
| --- | ---: | ---: | ---: | ---: | ---: | ---: |
| 1 | 302.9 | 6442.3ms | 1641.3 MiB | 55.72ms | 150.31ms | 212 |
| 2 | 1382.0 | 2431.4ms | 1843.3 MiB | 8.91ms | 47.55ms | 98 |
| 4 | 153.7 | 26437.9ms | 1840.4 MiB | 48.01ms | 246.43ms | 210 |


The same four-region foreground workload completes at one/two/four Metal callers. All within-backend answers and NBT match. The additional CPU foreground sweep is stopped during its first partial trial when host load exceeds 170; CPU two/four foreground cases are not run. Existing complete CPU one/two/four generation-only sweeps and CPU foreground correctness smokes remain separate evidence. No complete CPU foreground sweep is claimed. Shared load changes sharply, so these are diagnostic stress observations, not baseline/candidate speedups or a reliable ranking of budgets. No increased default concurrency or queue depth follows from them.

## Validation and reproduce

49 complete native generator processes produce 236 fresh MCA files including 49 warmups, 187 measured regions, and 183,296 strict same-backend NBT comparisons. The two stopped partial CPU processes are excluded. The initial reference, ABBA screen, primary confirmation, original/batched foreground smokes, final retained-harness smoke and Metal fairness cases are distinct evidence groups; their timing samples are not pooled into a speedup claim.

Sixteen Python checks pass and four native-gated checks are skipped across the foreground, benchmark support, concurrency, interval, CPU and End harness modules. Checks cover waiting for admitted foreign calls before returning ownership, interrupted thread creation admitting no native work, query corruption propagation after draining, and bounded retention with exact aggregates. A final actual Metal smoke also exercises the retained admission gate. No public library/ABI or generator output changes.

Example, after building or extracting a pinned native library:

```sh
RAYON_NUM_THREADS=2 python3 scripts/native-region-benchmark.py \
  --library /absolute/path/frozen-native.dylib \
  --definition /absolute/path/retina.dat --dimension minecraft:overworld \
  --backend metal --assembly-threads 16 --parallel 2 \
  --origin-x 0 --origin-z 0 --grid-width 2 --count 4 --warmups 1 \
  --readiness shader-ready --scope lit-mca --lighting enabled \
  --foreground-biome-probes --gpu-interval --out /absolute/path/new-result
```

Repeat with one/four callers and CPU or appropriate Windows hardware, preserving budgets, fixture, native hash and fresh output directories. Add --compare pointing at a matched same-backend corpus. Omit foreground/interval flags for generation-only headline timing. The A/B runner passes through the foreground flag and supports one/two/four/eight callers without changing production settings.

Native palette/NBT construction and real Windows compact transport qualification remain separate work; this cycle does not close those areas or publish a release.
