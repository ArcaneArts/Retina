# Current Nether performance evidence — 25 October 2026

A later [allocator cycle report](nether-perf-cycle204-2026-10-26.md) records the next implementation and its Overworld/memory investigation. The measurements below retain their original revision and limitations.

The architecture and current correctness checks are delivered. Complete serial Nether generation repeatedly meets the numerical one-second target. Sustained concurrent throughput and tail acceptance remain open. All current test data and worktrees are retained.

Current main is `8c4810e71373`, native tree `03ebc41dcac7`. The actual native build is `a8c023026a5a`, SHA-256 `db0060feb49b1df19a5f5bec0221187ea76417435ad244b427f682eca01208fa`. Its native tree matches main. PR [#238](https://github.com/ArcaneArts/Retina/pull/238) reduces optional diagnostic counter contention; it does not claim a default generation speedup.

## Complete serial regions

Both passes use five warmups and twenty measured 1,024-chunk regions per profile, one caller, sixteen native workers, GPU lighting, zlib publication and synchronous cleanup. Every measured shader status is 4. Full decompressed chunk NBT and membership match the original and current reference data.

| Profile | Post-merge median / p95 ms | Repeat median / p95 ms |
|---|---:|---:|
| nether-main | 694.8 / 884.1 | 679.1 / 863.6 |
| nether-second-seed | 724.9 / 886.7 | 729.0 / 880.8 |
| nether-custom-bounds | 530.5 / 606.8 | 532.8 / 579.6 |
| nether-direct-stem | 538.9 / 592.4 | 503.9 / 559.6 |
| overworld | 225.8 / 265.9 | 216.7 / 243.9 |

The original exploratory investigation reported 12,875 ms median and 18,861 ms p95. It used one warmup and six measurements and had different contention limits. It is context, not a matched speedup ratio. The original standard profile timings and initial six architecture deliveries remain in [the earlier progress report](nether-performance-progress-2026-10-25.md). The original native reference binary is still present and its actual hash matches retained provenance.

No script/flag-classified test overlaps were found in these serial case intervals. Broader observations include filesystem/desktop activity and a native server process. These are repeated numerical target observations; the evidence does not establish an otherwise idle machine. Zero classifier hits never proves that no native test or other workload exists.

## Concurrent requests

The new full-corpus ABBA campaigns use two, four and eight callers separately from the fixed sixteen workers. Controls are the actual C198 build (`2dfddaf54167`); candidates are the current build. These revisit earlier unresolved C199 throughput comparisons. All **1,331,200 measured chunk instances** in the serial repeat and concurrent campaigns match original and current reference NBT exactly. The preceding post-merge pass adds 102,400 instances. Sources and library hashes stay fixed.

Observed aggregate throughput changes compare averaged candidate B/C against control A/D. They are not causal performance judgments.

| Callers | Main | Second seed | Custom bounds | Direct-stem | Overworld |
|---:|---:|---:|---:|---:|---:|
| 2 | -17.0% | -13.3% | -5.3% | +7.0% | +16.8% |
| 4 | -6.3% | -17.8% | -11.7% | -6.4% | +11.7% |
| 8 | +5.3% | -19.8% | -3.8% | +10.8% | +19.6% |

The unfavorable results remain unresolved. Candidate second-seed throughput is lower on average at every caller count, and some eight-caller p95 values exceed ten seconds. Repeated passes of the same candidate also differ sharply: four-caller main throughput is 2,721 versus 1,465 chunks/s, and eight-caller second-seed throughput is 1,112 versus 2,121 chunks/s. Known test overlap, native/Node activity and large control drift are retained. In one two-caller Overworld comparison, control medians drift by 49%. These observations neither prove the change caused each loss nor justify discarding the losses as noise. Full concurrent performance acceptance remains open.

Per-case observations show native server/deps executables, Node peaks above 300% CPU, and filesystem/desktop activity. Windows include comparison time and do not identify precise per-region causation. No competing process was stopped. All per-pass latency, total time, throughput, RSS and readback observations are retained in [the evidence record](nether-current-performance-evidence-2026-10-25.json).

## Actual portal and unload checks

The existing Fabric client fixture was rerun with the current native library in fresh retained worlds. Both runs pass real portal entry/return, destination creation, light edits, reopening and unloads with eight active requests. There were **202 checkpoints without DH and 208 with DH, zero failures, zero pause logs and no UI intervention**.

| Fixture | Cold arrival | Reopened arrival | Cold server ticks / max gap |
|---|---:|---:|---:|
| No DH | 1449.3 ms | 153.0 ms | 30 / 134.6 ms |
| DH | 1802.0 ms | 154.3 ms | 34 / 168.0 ms |

Each cold arrival generates its four destination regions (4,096 chunks); reopening generates none. The cold DH portal window overlaps one successful actual DH generation. The full DH test takes about 5 minutes 48 seconds because setup waits for background work; `portal_ms` starts at portal entry and does not include that setup. These client observations are not twenty-region warmed portal distributions.

Actual admission queue delay is recorded separately from execution. Across 42 completed Nether regions per fixture, queue p95 is 5.57 seconds without DH and 6.05 seconds with DH. Those samples combine cold setup, portals and deliberate unload load; unfinished/cancelled requests are outside the completed-region summaries.

## Attribution, preservation and remaining work

The earlier enabled-counter profile overemphasized shared atomic updates and clocks. PR #238 addresses counter storage, with exact concurrent-count tests and separate diagnostic/default measurements. Its storage microbenchmark is not a region speedup. Disabled-counter stack samples also locate allocation/freeing beneath completed command lists and version cleanup. Existing inline and flat contributor prototypes already implement the obvious representation changes and remain unmerged; fewer allocations alone do not establish a useful whole-region gain.

The [current validation audit](nether-validation-audit-2026-10-25.json) retains order/concurrency, cache-isolation, independent terrain, scheduling and sustained ownership evidence. Final planning is already a small measured cost; distribution and synchronous teardown remain explicit costs. The original six architecture areas and merged delivery inventory are in the earlier progress report. Later deliveries [#237](https://github.com/ArcaneArts/Retina/pull/237) and [#238](https://github.com/ArcaneArts/Retina/pull/238) add boundary eligibility observations and lower diagnostic interference.

Current binaries, worlds, logs, command receipts and worktrees are retained. No test data was removed. Historical raw paths missing at their recorded locations remain an audit limitation; this report does not claim to recover those runs. The original reference corpus and binary remain available.

The remaining performance gap is reproducible concurrent throughput/tail acceptance with reliable surrounding controls. A matched causal before/after conclusion cannot be recovered from the variable runs above. The overall goal remains open.

Exact commands, hashes and recovery paths are in the adjacent JSON and external `completion-audit/final-acceptance-2026-10-25/`. Replay with fresh output directories and preserve the original reference and current corpora.
