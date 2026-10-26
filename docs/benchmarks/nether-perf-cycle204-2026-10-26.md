# Native allocator contention — 26 October 2026

Default native Rust allocations now use pinned mimalloc with local dynamic TLS. Allocation/freeing beneath ordered Nether state was a remaining measured cost. No terrain, feature, structure, ordering, lighting, thread budget or output semantics change. C libraries and foreign buffers retain their own allocation/free paths; the native library does not override process-wide C malloc. Both loader packages include the MIT notices.

The implementation source is `9a8e5ca1802f`, native tree `75176b16dd1b`. The retained same-source candidate and System controls differ only in the allocator build feature. See [the evidence record](nether-perf-cycle204-2026-10-26.json) for full hashes, commands, observations and recovery paths.

## Repeated complete regions

Five warmups and twenty measured 1,024-chunk regions per profile/pass; one caller, sixteen native workers, diagnostics disabled, actual shader status 4, lighting, zlib publication and synchronous logical cleanup. Every complete decompressed NBT payload and chunk membership matches both the immutable original and preceding merged references.

| Profile | Candidate B median / p95 ms | Candidate C median / p95 ms |
|---|---:|---:|
| nether-main | 528.2 / 676.3 | 523.4 / 683.4 |
| nether-second-seed | 564.0 / 679.1 | 555.4 / 664.8 |
| nether-custom-bounds | 394.7 / 445.8 | 381.9 / 429.1 |
| nether-direct-stem | 374.4 / 420.6 | 369.4 / 400.9 |
| overworld | 213.5 / 253.0 | 204.6 / 241.9 |

The two standard Nether profiles repeat at 523–564 ms median and below 684 ms p95. Custom and direct profiles also remain below 700 ms. Their same-source System controls are slower. Serial confirmation has no identified test/QA overlap; that does not imply an otherwise idle OS. The original 12.88-second exploratory result used different warmup/sample conditions and is not a matched speedup ratio.

## Concurrent generation and Overworld investigation

The full five-profile ABBA matrix covers 1/2/4/8 callers separately from sixteen workers. Observed average Nether throughput increases in every caller/profile pair, roughly 24–75% in this campaign; control drift limits causal percentages. Concurrent request latency still reflects shared finite capacity and is not claimed below one second at eight callers. All per-pass tails and unfavorable results remain retained.

Initial Overworld averages were -3.5% at two callers and -1.3% at eight. Short two-caller p95 increases were investigated through standard repeats, native stack samples, existing per-region phase reports and longer streams. The first longer comparison overlapped known tests and cannot settle the question. The latest 180-region-per-pass ABBA repeats the same twenty coordinates nine times with five warmups and actual shader readiness:

| Pass | Median ms | p95 ms | Maximum ms | Chunks/s |
|---|---:|---:|---:|---:|
| control-a | 291.9 | 427.8 | 531.2 | 6851 |
| candidate-b | 293.6 | 448.3 | 553.9 | 6668 |
| candidate-c | 288.7 | 411.6 | 587.1 | 6874 |
| control-d | 353.7 | 437.1 | 587.2 | 5669 |

There are no identified test/QA workloads in 41–48 measured-window observations per pass. Candidate B throughput is 2.7% below the first control, while candidate C matches it; the final control has substantial median/CPU drift. These results do not establish a causal Overworld gain. They do not reproduce the earlier material short-run tail increase as a sustained p95 regression. The separate standard twenty-region repeat remains mixed and is also retained, including its missing measured-window observation for control A.

The assembly report is a residual elapsed interval that includes final lighting waits. It is not a CPU-only assembly cost. Separate phase runs show variable planning, lighting host and worker intervals in both variants. Thread samples are thread-state observations, not elapsed-time percentages. No speculative scheduler change was added to explain timing variance.

## Memory and integration

Higher memory retention is an explicit tradeoff. In the optimized ownership census the mixed-load peak is 8.4G versus 5.9G, and after engine drop 2.5G versus 1.4G. All 2,048 tracked mask owners and eight version/snapshot owners are released, actual cache caps pass, and the worker stops. Extended uninstrumented eight-caller Overworld RSS settles near 4.8 GB versus 4.0 GB. These finite streams and ownership limits support bounded behavior in the tested workload; they do not establish a universal bound for arbitrary worlds. All amortized allocator work remains visible in repeated generation.

Real Fabric portal/light/reopen/unload fixtures pass without DH and twice with DH: 198/204/206 checkpoints and zero failures. Each unload starts with eight active requests; each cold DH portal overlaps completed actual DH work. Cold destination creation generates 4,096 chunks in 1.716 seconds without DH and 2.075/1.722 seconds with DH. Reopening generates none and takes 160/153/151 ms. Both DH fixtures logged a setup pause minutes before the timed portal; the timed windows advance server ticks. These are functional observations, not warmed portal distributions or a portal speedup claim.

Both allocator variants pass 298 ordinary unit tests in debug and optimized builds (75 optional fixtures ignored per ordinary run). The actual full Gradle build succeeds for both loaders and six native targets; both packages contain the shared notices. Cross-platform compilation is not cross-platform runtime testing. Actual client fixtures use the retained native-path override; packaged native defaults were separately built and inspected.

The accounted full-region/census campaigns total **6,008,832 generated chunk instances**, all exact against their recorded original/preceding comparisons. These repeat coordinates rather than representing millions of unique chunks. Full worlds, binaries, commands, source receipts, logs, memory observations and hashes remain outside Git. The evidence record includes recovery instructions and receipt digests.

Known competing workloads are labeled and their unfavorable results preserved. A separate review corrects a narrow classifier that missed worker-compatibility scripts and explicit `.qa/rust-server` executables. Earlier raw historical paths absent at their recorded locations remain an audit limitation; this report does not claim to recover them. All current data and worktrees are retained.

This cycle is selected for PR delivery. Confirmed merge and a fresh benchmark of the merged native tree are still required. The full goal is not declared complete by this report.
