# Nether performance acceptance — 26 October 2026

Complete standard Nether generation now repeats at **519–580 ms median and 662–684 ms p95**, within the requested sub-second target and 300–700 ms aim. These are full 1,024-chunk regions, including terrain, features, structures, lighting, NBT, zlib publication and synchronous logical cleanup.

Runtime PR [#241](https://github.com/ArcaneArts/Retina/pull/241) is confirmed squash-merged as `39f7a1eac278`; primary main is synchronized. The actual packaged native library is SHA-256 `eef6251eedbc16920335f365aa5d307e548d4fd4c82ac108b2f232e76426b283`, built from `9a8e5ca1802f`, whose native tree `75176b16dd1b` exactly matches merged main. It is separately identified from the isolated-target benchmark library.

## Complete generation after merge

Both passes use five warmups and twenty measured regions per profile, one caller, sixteen native workers, diagnostics off, shader status 4, lighting and zlib. All profile hashes and seeds match the immutable original. Every measured window has process observations with no identified competing test/QA workload. Other OS activity is not claimed absent.

| Profile | Pass 0 median / p95 ms | Pass 1 median / p95 ms |
|---|---:|---:|
| nether-main | 519.0 / 662.5 | 526.0 / 678.3 |
| nether-second-seed | 580.1 / 684.0 | 552.8 / 662.4 |
| nether-custom-bounds | 379.4 / 427.4 | 378.6 / 426.9 |
| nether-direct-stem | 385.0 / 433.7 | 376.8 / 404.8 |
| overworld | 198.7 / 230.2 | 199.7 / 229.3 |

The original exploratory investigation measured 12.88 seconds median with one warmup and six measurements. The original standard reference recorded 16.53/14.33 seconds median for the two standard Nether seeds and 222.6 ms for Overworld. Those historical timings have different contention/sample conditions, so no precise causal before/after ratio is claimed. Original output, source and library identity remain the correctness anchor.

All 204,800 new post-merge chunk instances match the original, preceding merged implementation and pre-merge candidate exactly. Across the allocator cycle, sustained ownership/production streams, profiling and final flagged-case repeats, **6,459,392 accounted chunk instances** pass their recorded full-NBT comparisons. Repeated coordinates are not counted as millions of unique chunks. MCA sector layout, padding, timestamps and compression representation are outside payload comparison; no NBT fields are excluded.

## Architecture delivered

The [merged delivery inventory in the evidence record](nether-performance-acceptance-2026-10-26.json) contains all 71 merged PRs #171–241, their branches, commits and dates. The original six areas are:

1. [#171](https://github.com/ArcaneArts/Retina/pull/171): full-output benchmark/correctness suite and opt-in attribution. Later [#238](https://github.com/ArcaneArts/Retina/pull/238) and [#240](https://github.com/ArcaneArts/Retina/pull/240) reduce diagnostic counter sharing and distinguish whole-process CPU from elapsed time.
2. [#172](https://github.com/ArcaneArts/Retina/pull/172): job-owned structure starts and batched fossil floor/biome probes. Recorded repeated builds fall from 8,841 to zero and GPU jobs from 2,343 to 118 in the diagnostic case; counters are not wall-time savings.
3. [#173](https://github.com/ArcaneArts/Retina/pull/173): ordered completed views, column versions and reusable state, followed by lookup and boundary reuse improvements. Same-root dependencies and the complete boundary fallback remain.
4. [#174](https://github.com/ArcaneArts/Retina/pull/174): initial terrain/material handoff. [#232](https://github.com/ArcaneArts/Retina/pull/232) adds bounded exact live-tile reuse and job pins, with geometry/halo checks and explicit missing coverage.
5. [#175](https://github.com/ArcaneArts/Retina/pull/175): cleanup ownership. Final structure planning was already measured at 0.0069–0.0156 ms per region; [#241](https://github.com/ArcaneArts/Retina/pull/241) addresses remaining allocation/disposal contention using pinned mimalloc. Cleanup still joins before the call returns.
6. [#176](https://github.com/ArcaneArts/Retina/pull/176): bounded foreground preference and admission, followed by bounded root work and tested global budgets. A waiting background request gets every fifth admission.

These are architecture changes, not a reduced feature set or thread-count-only fix. The current source for these mechanisms is unchanged from the preceding audited implementation except allocator wiring. Full-NBT comparisons, order/mixed-context census, independent terrain/halo tests and scheduler/lease tests provide complementary evidence.

## Concurrent requests and Overworld

The full five-profile matrix separately measures 1/2/4/8 callers at sixteen native workers. Three initial cases with broader QA-process observations were repeated after merge using the packaged native versus the retained same-source System build. Every repeat has no identified test/QA overlap, fixed source/libraries, actual shader readiness and exact NBT.

| Case / callers | Candidate chunks/s, B / C | Candidate p95 ms, B / C |
|---|---:|---:|
| nether-main / 2 | 2819 / 2850 | 954.3 / 953.8 |
| nether-second-seed / 4 | 3726 / 3725 | 1399.7 / 1421.8 |
| nether-direct-stem / 8 | 4506 / 4586 | 2285.2 / 2456.8 |

The four-caller second-seed controls drift only -0.9% in median; observed candidate average throughput is 39.6% higher and CPU per region 21.3% lower. Other comparisons have larger control drift, so the report retains their values without precise causal gain claims. Concurrent latency reflects finite shared capacity; eight-caller requests are not claimed below one second. All tested requests completed. The initial eight-caller Nether matrix had a maximum request duration of 4.22 seconds; the final direct-profile eight-caller repeat had a maximum of 2.83 seconds. Both candidate passes in each final repeat complete with higher aggregate throughput than the controls.

The earlier two-caller Overworld short-run tail increase was investigated with standard repeats, separate native/phase profiling and sustained generation. The final 180-region-per-pass ABBA has candidate p95 411.6/448.3 ms versus controls 427.8/437.1 ms, with no identified test/QA in 41–48 observations per batch. One candidate throughput is 2.7% below the first control; the other matches it. The final control has substantial drift. A persistent material sustained Overworld regression is not reproduced; no causal Overworld gain is claimed. All earlier unfavorable and contended measurements remain retained.

## Portal responsiveness, cleanup and memory

Real fresh Fabric portal/light/reopen/unload fixtures pass without DH and twice with DH: 198/204/206 checkpoints, zero failures, unloads with eight requests active, and completed actual DH generation during each cold DH portal.

| Fixture | Cold 4,096-chunk arrival ms | Reopen ms | Completed-request queue median / p95 ms |
|---|---:|---:|---:|
| portal-qa-no-dh | 1716.0 | 160.4 | 1615.2 / 5866.2 |
| portal-qa-dh | 2075.1 | 152.9 | 1889.1 / 6070.6 |
| portal-qa-dh-repeat | 1722.1 | 151.1 | 2189.2 / 8742.2 |

Server ticks advance during cold arrival; maximum observed tick gaps are about 132–175 ms. Queue summaries combine 42 completed cold-portal and deliberate unload-stress requests per fixture and exclude unfinished/cancelled work. Queue delay, native execution and portal arrival are distinct. DH full runs include a setup pause minutes before timed portal entry; portal windows advance normally. These functional observations are not warmed twenty-region portal distributions or a causal portal speedup.

The optimized census runs 300 serial regions across forward/reverse/permuted orders plus fourteen mixed-context regions per allocator. Actual cache caps pass; all 2,048 tracked mask owners and eight version/snapshot owners release, and the worker stops. Higher allocator retention is real: mixed-load peak 8.4G versus 5.9G and after-engine-drop footprint 2.5G versus 1.4G. Extended uninstrumented eight-caller Overworld RSS settles near 4.8 GB versus 4.0 GB. These source bounds and finite streams support bounded tested behavior, not a universal memory bound for arbitrary profiles. No cleanup is moved outside the timed call to hide work.

Both allocator configurations pass 298 ordinary native tests in debug and optimized builds; 75 optional fixtures are ignored in each ordinary run, with the ownership fixture run explicitly. The actual full Gradle build passes for both loaders and all six native targets, and both JARs include the MIT notice. Foreign-platform runtime behavior is not claimed from compilation. Actual client fixtures use the retained native-path override; packaged defaults were separately built, inspected and now benchmarked.

## Evidence, recovery and limits

Exact commands, per-pass median/p95/maximum/total time, throughput, CPU, peak memory, source/library/profile hashes, shader states, full comparisons, workload observations and recovery instructions are in [the evidence record](nether-performance-acceptance-2026-10-26.json). The external requirement audit covers all 49 objective paragraphs, including named invariants and delivery steps. Current binaries, worlds, logs, sources and worktrees are retained; no cleanup is requested.

Historical raw files missing at their recorded paths were not recovered by a read-only scan of Retina-specific roots. Committed cycle results and source history remain, and all 106 available recorded canonical/subset digests match the original corpus for the same region set. Two apparent mismatches were one-region legacy ABI smoke tests, verified directly against original `0.mca`; no expected output was changed. These records establish digest continuity, not recovery or independent replay of every historical raw timing run. Earlier timing deferrals and contention remain explicit.

Final documentation delivery and its post-merge native check are recorded separately under the external `scalable-rust-allocator/final-report-postmerge/` directory and `goal-completion-audit-2026-10-26.json`. Runtime acceptance is demonstrated by the current evidence above; it is not inferred solely from merged PRs or a terrain-only benchmark.
