# Nether performance progress — 25 October 2026

The latest [performance acceptance report](nether-performance-acceptance-2026-10-26.md) records the merged allocator implementation and current complete-region, concurrency, memory and portal results. Earlier measurements below retain their original revision and limits.

Latest current-source measurements and fresh portal checks are recorded in [the current evidence report](nether-current-performance-evidence-2026-10-25.md). The earlier observations below retain their original source and timing limitations.

Status: implementation and correctness progress; overall goal remains open. Timing confirmation was attempted after functional validation; competing test workloads still prevent full acceptance. All surviving test data and worktrees are retained.

## Current merged result

Revision `dbf04f0796e628e22acfda94194621a47b077229` (PR #232), native SHA-256 `80a34fb6ce7ac55303ae62134c4f473171fb13c7bbd9f624855d0f6f842b90ae`. The native library was built from `2aff4c15314f3bba2426cfe2c0d820bae0007ddc`; its native source tree is identical to the merged revision. The source and library hash remained unchanged throughout the run.

Two complete five-profile passes used five warmup regions and twenty measured regions per profile, one caller, sixteen native workers, lighting enabled, zlib MCA publication, and actual shader status 4 for every measured region. The timer includes complete generation and synchronous cleanup.

All 204,800 measured chunk instances matched both the original corpus and the fresh preceding native implementation. These are repeated chunk instances, not 204,800 unique coordinates. The common locate test also passed.

| Profile | Pass | Median ms | p95 ms | Maximum ms | Chunks/s | Peak RSS GiB |
|---|---:|---:|---:|---:|---:|---:|
| nether-main | 0 | 684.8 | 875.8 | 890.6 | 1464.2 | 2.26 |
| nether-second-seed | 0 | 727.0 | 864.1 | 876.8 | 1455.1 | 2.21 |
| nether-custom-bounds | 0 | 522.5 | 563.4 | 581.8 | 1963.1 | 1.95 |
| nether-direct-stem | 0 | 510.0 | 598.4 | 627.5 | 1941.1 | 1.95 |
| overworld | 0 | 224.5 | 259.0 | 270.6 | 4555.2 | 1.70 |
| nether-main | 1 | 684.2 | 890.8 | 910.7 | 1460.4 | 2.28 |
| nether-second-seed | 1 | 715.2 | 871.1 | 904.5 | 1440.4 | 2.15 |
| nether-custom-bounds | 1 | 526.5 | 588.8 | 593.3 | 1923.6 | 1.84 |
| nether-direct-stem | 1 | 512.8 | 568.6 | 621.3 | 1977.2 | 1.92 |
| overworld | 1 | 214.4 | 278.6 | 290.2 | 4629.5 | 1.82 |

The process observations captured a search process at 1,066% CPU and unidentified Python activity. These measurements establish observed post-merge behavior, not uncontended acceptance or a causal speedup for PR #232. No other workload was stopped.

## Starting measurements

The original investigation reported 12,875 ms Nether median and 18,861 ms p95 with one warmup and six measured regions. That exploratory run differs from the later standard suite and must not be used as a matched speedup ratio. The standard suite’s original reference measurements are recorded below; they too retain their original contention limitations.

| Original standard profile | Median ms | p95 ms |
|---|---:|---:|
| nether-main-run0 | 16525.4 | 24083.5 |
| nether-second-seed-run0 | 14329.7 | 17859.7 |
| nether-custom-bounds-run0 | 6168.2 | 7926.7 |
| nether-direct-stem-run0 | 5990.1 | 6297.9 |
| overworld-run0 | 222.6 | 257.0 |

## Architecture and evidence

- PR #171 added complete-output measurement and opt-in attribution. The original standard corpus contains all five Nether biomes, fossils, fortresses, bastions and ruined portals across two seeds, plus custom profiles and Overworld.
- PR #172 introduced job-owned structure-start reuse and batched fossil probes. The recorded diagnostic region reduced repeated structure builds from 8,841 to zero and GPU jobs from 2,343 to 118; those are work counters, not independent wall-time savings.
- PR #173 introduced completed ordered dependency views and column versions. Current code retains synchronous recursive boundary work and completes immutable views only after target callbacks release access.
- PR #174 reused initial terrain and material buffers. PR #232 adds a bounded weak index for exact terrain tiles still owned by active jobs; it does not retain buffers by itself.
- PR #175 addressed boundary-state disposal. Current final planning, distribution and retirement join before generation returns; cleanup has not been moved outside the reported interval.
- PR #176 introduced bounded foreground preference: a waiting background request receives every fifth admission. Later cycles address native contention and ordered evaluation; caller counts remain separate from worker counts.

Current complete-NBT comparison reads every decompressed chunk payload and compares bytes, including all NBT fields. MCA sector layout, padding, timestamps and compression representation are outside the payload comparison. Chunk membership is checked separately. Expected NBT is not rewritten to accommodate a mismatch.

## Fresh correctness and memory validation

The fresh isolated native test build completed 300 serial regions across the five corpus profiles in forward, reverse and permuted order, then 14 mixed-profile regions at 2, 4 and 8 callers. All **321,536 chunk instances matched both the original corpus and the fresh preceding native control**, with complete payload and membership comparisons. The executable hash and source remained unchanged.

The ownership census passed at joined checkpoints. Cave-cache keys stayed within 2,048; tracked external mask and halo references were zero. After engine drop, all 2,048 observed mask references and eight observed version snapshots/interior buffers were released, and the terrain worker stopped. Separate `vmmap` physical-footprint observations were 4.7G, 5.0G and 4.5G after successive serial rounds, peaked at 5.3G during the run and fell to 920.5M after engine drop. These observations support bounded behavior in this tested workload; logical buffer capacities exclude driver allocations and are not summed with process footprint.

The audit of 80 existing ABBA runs confirmed successful completion at 1/2/4/8 callers with 16 workers, 20 measured regions and actual shader readiness 4. Every recorded NBT comparison passed, covering 819,200 candidate chunk instances. Their timing limitations remain unchanged. Independently, all ten post-merge corpus digests match the original committed cycle-1 digests, ruling out expected-output drift for this corpus.

Current `:common:regionPreparationTest` passed actual queued admission, foreground/background fairness, configured budgets 1/3/6/32, promotion, cancellation, failure propagation and concurrent lease closure. Current `:common:netherTerrainTest` passed independent base-column checks at negative/boundary coordinates, all five biome surfaces, six custom-bound/fluid contexts, carvers and vertical biomes. The latter is a terrain-only correctness fixture; complete-region validation comes from the separate NBT comparisons above.

The detailed [validation audit](nether-validation-audit-2026-10-25.json) records source, executable hashes, log paths, checkpoint observations and evidence hashes. All raw data remains under `/Users/cyberpwn/development/retina-benchmark-evidence/completion-audit/`. No performance acceptance or goal-completion claim follows from these functional tests.

## Latest timing confirmation

Three terminal campaigns checked another **819,200 chunk instances across control and candidate runs**, all with exact recorded full-NBT comparisons. The source and native libraries remained unchanged. Each comparable run used five warmups, twenty measured complete regions, one caller and sixteen native workers. Detailed results, evidence paths and assessment hashes are in [the timing confirmation record](nether-timing-confirmation-2026-10-25.json).

The initial full ABBA campaign did not establish acceptance: candidate main and second-seed p95 exceeded one second in one pass, and several cases overlapped known smoke tests or typechecks. Direct-stem throughput appeared 12.7% slower. A focused direct-stem ABBA repeat found no identified competing test overlap and did **not** reproduce that regression: candidate medians were 512.6/511.0 ms and p95 562.7/568.7 ms. This does not establish the cause of the earlier slowdown.

Further focused runs produced these candidate results:

| Profile | Median ms, B / C | p95 ms, B / C | Identified test overlap |
|---|---:|---:|---|
| Main | 670.0 / 705.0 | 862.3 / 908.7 | Candidate C and control A |
| Second seed | 717.0 / 723.3 | 871.2 / 872.6 | Control A only |
| Custom bounds | 529.9 / 555.4 | 603.0 / 611.0 | Control D only |
| Overworld | 249.3 / 312.2 | 307.1 / 409.7 | All four passes |

Second-seed and custom-bounds candidate repeats met the serial target without identified test overlap. Those campaigns did not establish full acceptance; the subsequent follow-ups below add serial main and Overworld evidence. Observations cover case intervals, including comparison time; they identify overlap, not a causal latency contribution. Unfavorable and contended results remain in the evidence. No process was stopped and no test data was deleted.

## Follow-up serial and concurrent confirmation

Eight additional terminal campaigns checked **1,638,400 measured chunk instances across control and candidate variants**. All recorded complete-NBT and membership comparisons passed. Source and libraries remained unchanged. These are repeated chunk instances, not unique coordinates. Every measured region reported shader status 4. The [follow-up record](nether-concurrency-confirmation-2026-10-25.json) contains timing, memory, provenance and evidence digests; complete receipts, worlds and observations remain outside Git.

The focused main-profile serial ABBA had no identified competing test overlap. Candidate medians were 679.8/707.9 ms and p95 894.8/887.5 ms. The focused Overworld serial repeat also had no identified test overlap: candidate medians 225.9/217.8 ms and p95 263.4/251.4 ms. Its aggregate throughput was 1.75% below the averaged controls while control medians drifted 4.41%. Together with the earlier second-seed, custom-bounds and direct-stem candidate pairs, the representative serial Nether corpus has repeated observed sub-second medians and p95 without identified test overlap in those candidate passes. Some corresponding controls overlapped tests; this supports the absolute serial target but does not establish a causal speedup for every profile.

The full concurrent campaigns remain unsuitable for performance acceptance:

| Callers | Main throughput change | Second seed | Custom bounds | Direct-stem | Overworld |
|---|---:|---:|---:|---:|---:|
| 2 | −12.0% | −4.1% | −13.1% | +3.1% | −3.0% |
| 4 | +15.8% | −7.5% | −1.2% | −20.9% | +15.8% |
| 8 | −13.6% | −26.1% | −20.9% | −10.8% | −35.3% |

Percentages compare averaged candidate throughput with averaged preceding-control throughput in each ABBA campaign. They describe the observations; test overlap and control drift prevent treating them as causal changes. Native worker count stayed at sixteen. At eight callers, observed candidate Nether p95 reached 6.5–13.1 seconds and throughput was roughly 1,005–1,473 chunks/s; long tails remain an explicit unresolved finding. Known tests overlapped many eight-caller cases, including both candidate main-profile passes. No competing process was stopped.

Focused follow-ups investigated the material losses rather than discarding them. The main two-caller repeat had no identified test overlap, candidate throughput about 9.0% above the averaged controls, and p95 1.31–1.34 seconds; the earlier 3.48-second candidate tail did not reproduce. The custom-bounds two-caller loss did not consistently reproduce, but one candidate overlapped a known test and controls drifted 23%. The direct-stem four-caller repeat had no identified test overlap and did not reproduce the earlier loss on average; its controls still drifted 16%. These findings do not validate the whole concurrent matrix or prove why the original slowdowns occurred.

The actual Java live-client logs also record admission wait separately from execution. Across 42 completed Nether regions per run, admission p95 was 5.68 seconds without DH and 6.01 seconds with DH. These runs combine cold startup, portal and unload exercises; the DH run includes a paused-menu intervention. They are functional and queueing evidence, not warmed performance or total portal latency. Cancelled/unfinished requests are outside those completed-region summaries.

## Remaining verification and limits

- The repeated serial Nether target is observed as described above. Uncontended sustained concurrent throughput/tail acceptance and a final Overworld regression judgment under concurrent load remain open. All unfavorable and contended results are retained; focused follow-ups do not validate untested combinations.
- The full requirement audit is still in progress. Merged PRs and historical summaries alone do not prove every invariant or every historical cycle’s benchmark requirements.
- Historical raw suite paths for cycles 2–5 are currently absent at their recorded locations. Their committed reports survive. Do not represent those reports as independently revalidated raw evidence. The original reference corpus and recent full-NBT comparisons remain available.
- Recent live portal tests passed with and without DH, including unloads with eight active requests. A paused-menu intervention during the DH run makes its timing unsuitable for performance acceptance.
- Fresh sustained retention and concurrent correctness evidence is recorded above; this does not prove an unlimited memory bound for every possible world profile.
- No final completion claim is made by this report.

## Evidence and reproduction

The adjacent JSON records all per-run timings, comparisons and configuration. Full commands, terminal receipts, binaries, worlds and process observations remain outside Git:

- `/Users/cyberpwn/development/retina-benchmark-evidence/cycle197/job-pins/postmerge-runs.json`
- `/Users/cyberpwn/development/retina-benchmark-evidence/cycle197/job-pins/postmerge-regions/suite.json`
- `/Users/cyberpwn/development/retina-benchmark-evidence/cycle197/job-pins/isolated-builds/receipt.json`
- `/Users/cyberpwn/development/retina-benchmark-evidence/completion-audit/requirements-ledger.json`

Reproduce using the exact command arrays in the terminal receipt, changing only the output directory to a fresh location. Preserve the retained original reference and preceding control directories. For a rebuilt binary, use an isolated Cargo target directory and record its actual source and hash; do not assume the shared Cargo target contains the requested revision.

## Merged delivery inventory

GitHub confirmed the following PRs merged when this report was prepared. Rejected experiments are not included. This inventory records delivery, not blanket performance acceptance.

- [#171](https://github.com/ArcaneArts/Retina/pull/171): Measure Nether dependency costs and preserve a complete output corpus (`34645a375a0f`).
- [#172](https://github.com/ArcaneArts/Retina/pull/172): Reuse job-owned structure starts and batch Nether fossil probes (`b4fc6d3fdd22`).
- [#173](https://github.com/ArcaneArts/Retina/pull/173): Reuse completed ordered dependency state and column versions (`7e0721aed9a4`).
- [#174](https://github.com/ArcaneArts/Retina/pull/174): Reuse job terrain and immutable base-material buffers (`1c12e2ec652c`).
- [#175](https://github.com/ArcaneArts/Retina/pull/175): Reduce Nether boundary-state cleanup cost (`e20ea131ef97`).
- [#176](https://github.com/ArcaneArts/Retina/pull/176): Prioritize portal region preparation without starving background generation (`60a7e3922938`).
- [#177](https://github.com/ArcaneArts/Retina/pull/177): Reduce ordered dependency read allocations and column cache storage (`fbbf5455e748`).
- [#178](https://github.com/ArcaneArts/Retina/pull/178): Reduce contention in ordered dependency cache lookups (`d66caee66cbf`).
- [#179](https://github.com/ArcaneArts/Retina/pull/179): Avoid repeated map lookups for nearby job terrain tiles (`ca2fde8ba1b0`).
- [#180](https://github.com/ArcaneArts/Retina/pull/180): Defer deterministic Nether ore tests to ordered replay (`7a2670a70d80`).
- [#181](https://github.com/ArcaneArts/Retina/pull/181): Bound Nether worker tasks to one source root (`6e125da6f372`).
- [#182](https://github.com/ArcaneArts/Retina/pull/182): Borrow cached Nether columns for scalar reads (`7dde4f1fedff`).
- [#183](https://github.com/ArcaneArts/Retina/pull/183): Preserve height-only Nether dependency queries (`96e1603655f3`).
- [#184](https://github.com/ArcaneArts/Retina/pull/184): Reduce retained Nether command index storage (`1abc51771eca`).
- [#185](https://github.com/ArcaneArts/Retina/pull/185): Skip impossible Nether boundary writers using placement footprints (`38c4ff2fc516`).
- [#186](https://github.com/ArcaneArts/Retina/pull/186): Refine boundary ore footprints to reduce dependency work (`192eeec7c943`).
- [#187](https://github.com/ArcaneArts/Retina/pull/187): Avoid locking repeated prior-column reads in ordered generation (`26878d668d12`).
- [#188](https://github.com/ArcaneArts/Retina/pull/188): Avoid redundant replay of live Nether feature writes (`28a87cdd330b`).
- [#189](https://github.com/ArcaneArts/Retina/pull/189): Borrow terrain references during ordered metadata reads (`5d533eff7742`).
- [#190](https://github.com/ArcaneArts/Retina/pull/190): Attribute terrain work and generation memory in benchmarks (`ea148fd298f4`).
- [#191](https://github.com/ArcaneArts/Retina/pull/191): Reuse exact cached terrain tiles across Nether generation jobs (`e5adbd17ecdd`).
- [#192](https://github.com/ArcaneArts/Retina/pull/192): Index feature-local column reads by spatial position (`74f4e1b7c48e`).
- [#193](https://github.com/ArcaneArts/Retina/pull/193): Reuse completed Nether column states across neighboring regions (`b34d8804aca9`).
- [#194](https://github.com/ArcaneArts/Retina/pull/194): Shrink completed Nether snapshots to region edges (`e50eddd2e97f`).
- [#195](https://github.com/ArcaneArts/Retina/pull/195): Reuse exact completed Nether exterior prefix states (`0132a7435842`).
- [#196](https://github.com/ArcaneArts/Retina/pull/196): Measure Nether material read reuse by feature root (`333afdc1846f`).
- [#197](https://github.com/ArcaneArts/Retina/pull/197): Measure Nether version allocation and retirement costs (`d44156efdc5e`).
- [#198](https://github.com/ArcaneArts/Retina/pull/198): Measure Nether material read backing and live storage (`542f41a19d0a`).
- [#199](https://github.com/ArcaneArts/Retina/pull/199): Measure Nether root CPU phases and worker task occupancy (`60e210d1b0e4`).
- [#200](https://github.com/ArcaneArts/Retina/pull/200): Attribute dependency joins within Nether root tasks (`c05a1b2124a9`).
- [#201](https://github.com/ArcaneArts/Retina/pull/201): Attribute Nether task CPU and terrain service latency (`b3ab2a082163`).
- [#202](https://github.com/ArcaneArts/Retina/pull/202): Attribute Nether cache lookup and feature GPU waits (`0370ac83fb46`).
- [#203](https://github.com/ArcaneArts/Retina/pull/203): Record merged Nether proof and held performance experiments (`fa838f8097ef`).
- [#204](https://github.com/ArcaneArts/Retina/pull/204): Measure Nether terrain overlap and request footprints (`1630ed05d498`).
- [#205](https://github.com/ArcaneArts/Retina/pull/205): Measure cache ownership after completed region generation (`34a2f40ba574`).
- [#206](https://github.com/ArcaneArts/Retina/pull/206): Attribute retained GPU buffers after region generation (`c3a9c24d6d61`).
- [#207](https://github.com/ArcaneArts/Retina/pull/207): Measure ordered Nether column edit workload (`c7872e93f1db`).
- [#208](https://github.com/ArcaneArts/Retina/pull/208): Measure Nether blob output reuse opportunities (`f15d3d2875ec`).
- [#209](https://github.com/ArcaneArts/Retina/pull/209): Measure completed and fallback Nether prefix work (`f0183a1461fb`).
- [#210](https://github.com/ArcaneArts/Retina/pull/210): Document Nether root CPU and memory attribution (`a3eea3e490cc`).
- [#211](https://github.com/ArcaneArts/Retina/pull/211): Attribute Nether dependency work without per-lookup event capture (`cbc6867512f8`).
- [#212](https://github.com/ArcaneArts/Retina/pull/212): Measure unused Nether dependency-column heights (`0945d3cef48b`).
- [#213](https://github.com/ArcaneArts/Retina/pull/213): test: measure live Nether column reuse without changing release code (`86be5825a5e5`).
- [#214](https://github.com/ArcaneArts/Retina/pull/214): test: attribute Nether initializer CPU without nested double counting (`9e5a3209ad67`).
- [#215](https://github.com/ArcaneArts/Retina/pull/215): test: attribute sampled Nether column-loading CPU (`117c1e14ee6f`).
- [#216](https://github.com/ArcaneArts/Retina/pull/216): test: cover direct Nether planner queries in CPU attribution (`fa63bc912e5c`).
- [#217](https://github.com/ArcaneArts/Retina/pull/217): Profile concurrent region requests and expose evaluation join tails (`e6f2e5e30133`).
- [#218](https://github.com/ArcaneArts/Retina/pull/218): Separate root callback admission and completion gaps (`acccb0e0e0ce`).
- [#219](https://github.com/ArcaneArts/Retina/pull/219): Attribute sampled column CPU by lookup and initialization path (`ce3cf5aaa0cd`).
- [#220](https://github.com/ArcaneArts/Retina/pull/220): Reuse material data across ordered generation (`625518882839`).
- [#221](https://github.com/ArcaneArts/Retina/pull/221): Continue ordered Nether roots from their final worker lane (`5df88525fc7d`).
- [#222](https://github.com/ArcaneArts/Retina/pull/222): Measure completed-root contributor storage in test inventory (`31c1d2957b17`).
- [#223](https://github.com/ArcaneArts/Retina/pull/223): Measure live column reuse by provider storage layout (`61b6937c8c6c`).
- [#224](https://github.com/ArcaneArts/Retina/pull/224): Measure live cross-stage column equality without retaining generation state (`55db5eb55403`).
- [#225](https://github.com/ArcaneArts/Retina/pull/225): Measure demand-time availability of unchanged column proofs (`c7024009b4ef`).
- [#226](https://github.com/ArcaneArts/Retina/pull/226): Attribute terrain GPU reply delays to per-job timings (`6e91869f9f0e`).
- [#227](https://github.com/ArcaneArts/Retina/pull/227): Measure host processing within terrain GPU replies (`89d496b81c97`).
- [#228](https://github.com/ArcaneArts/Retina/pull/228): Separate material counting and emission GPU diagnostics (`07a4fa4be7b4`).
- [#229](https://github.com/ArcaneArts/Retina/pull/229): Align concurrent dependency diagnostics on a shared clock (`1a23ace02d8a`).
- [#230](https://github.com/ArcaneArts/Retina/pull/230): Attribute terrain field copying and cache repopulation costs (`2dfddaf54167`).
- [#231](https://github.com/ArcaneArts/Retina/pull/231): Avoid unused biome probes in unrestricted searches (`618f72088d4c`).
- [#232](https://github.com/ArcaneArts/Retina/pull/232): Reuse live exact terrain tiles across generation jobs (`dbf04f0796e6`).

Recent documentation deliveries: [#233](https://github.com/ArcaneArts/Retina/pull/233), [#234](https://github.com/ArcaneArts/Retina/pull/234) and [#235](https://github.com/ArcaneArts/Retina/pull/235) preserve the earlier progress, ownership/correctness audit and timing limitations.
