# End acceptance — 27 October 2026

Supported vanilla Overworld, Nether and End generators activate Retina automatically. The End includes terrain and biomes, decorative islands, chorus, the arena and crystals, natural gateways, and cities with ships. Vanilla owns dragon progression, dynamic gateways, respawning and the exit podium.

The installable host builds are `retina-fabric-26.3-0.378.jar` and `retina-neoforge-26.3-0.378.jar`, built from merged `f944e8c9d9dc`. Both contain the same macOS arm64 native image, SHA-256 `b555a6a45fb194ca432e8a0d8fe0ccf646c4f9e92fe1bae48bb2932071470a0f`. Their full hashes, retained artifact paths, source identities, raw result paths and merged PRs are in the adjacent JSON. These are host-only builds; Windows, Linux and Intel macOS are outside this execution claim. Subsequent main commits #297–298 change test fixtures and verification/docs, with no production source changes.

## Actual deliverable performance

The final headless Java probe calls the actual packaged `NativeTerrain.generateRegionColumns` with the saved normal-world definition. There is no external native override or development product-classes directory. The timer includes complete 1,024-chunk MCA generation, terrain, features, structures, required lighting, NBT, production zlib publication, synchronous generation cleanup, base-material cache creation, column-cache compression/write and Java column decoding. Sidecars are checked present. Hash comparison and disposable-output deletion happen afterward. Minecraft chunk activation and boundary-light readiness are separate gameplay measurements below.

Apple M4 Max, Metal, Minecraft 26.3/data version 5023, seed 123456789, JDK 25, one caller, shipping defaults, diagnostics off. Each category has two warmups and eight measured regions. The other known benchmark owner confirmed a quiet window; full OS isolation is not claimed.

| Category | Median ms | p95 / maximum ms | Chunks/s | Target |
|---|---:|---:|---:|---|
| Void / mostly void | 66.60 | 81.44 | 14,810 | Met |
| Ordinary outer islands | 121.27 | 136.93 | 8,220 | Met |
| City / boss area | 81.12 | 124.73 | 11,807 | Met |

The heavy catalog alternates the actual city region (-2,-3) and boss region (0,0); its label does not imply it must be slower than every outer-island workload. With eight observations, nearest-rank p95 is the maximum and tail confidence is limited. Cold definition binding/GPU startup is reported separately: 1,006 / 964 / 1,028 ms for the three processes. Column-cache writes cost about 2.2–2.6 ms median. All 24 measured outputs and six warmups match retained exact decompressed-record hashes; no terrain or bookkeeping normalization is used for that comparison. Disposable MCA and companion files are deleted incrementally after capture.

Earlier isolated native-writer figures of 63/109/82 ms excluded preview companions. They are retained as narrower measurements and superseded by the packaged Java results above for preview acceptance. Actual GUI observations of 364–499 ms overlapped other GPU work and game load; they are not uncontended benchmark evidence.

## Correctness and memory

Independent fixtures use the actual loaded Minecraft 26.3 source and registries. They cover seven signed seeds for island primitives, exact density/solidity and floating-island height queries, all five biomes, world-seeded features, spike layouts and crystal tags, naturally generated gateway metadata, city candidates, rotated terrain checks, recursive plans and ships. The CPU island field was selected after the GPU experiment failed exact floating-point parity. The implementation preserves 128-block base terrain, 256-block storage and the loaded End skylight flag.

Combined vanilla/native generation compares 20 targeted chunks across five contexts: 1,310,720 stored block states with zero differences, 46 block entities and 39 entities. Additional city-only comparisons cover 19 chunks, 58 block entities and 69 entities, including cross-region pieces and loot. Runtime entity UUID is the documented source/native identity difference; placement, blocks, loot and lighting are never normalized away. Exact Retina dense-versus-sparse comparisons preserve every decompressed record, including native UUIDs, in four full regions. Partial-region repair preserves 1,023 existing slots and repairs the missing slot.

Sparse bounds classify uncertain cells through exact evaluation. Features and city dependency coverage still replay into air terrain. Uniform final material sections avoid allocating dense arrays for untouched air through lighting transport and NBT. The four-region plus one-chunk allocation proof records 28,483,840 bytes versus 537,001,984 dense-equivalent bytes (94.70% fewer final-material bytes), with 62,203 uniform sections. This is a stage allocation counter, not a claim of 94.7% lower total process memory. Full correctness-run peak RSS was 825.6 MB; separate native category runs used about 897–1,002 MB. The city planning workspace still materializes actual structure chunks as needed.

## Gameplay, loaders and lifecycle

Retained actual Fabric and NeoForge gameplay evidence covers normal portal entry, all four central-arena regions, ten crystals/two cages, vanilla dragon damage and death, egg and active exit podium, credits/return, unknown gateway search, pearl ownership/cooldown, reciprocal travel, four real EndCrystalItem uses, respawning and save/reopen with the same dragon UUID and persisted exits. Cold normal arrival was 418 ms, including all four arena regions (249 ms native publication). Unknown gateway travel was 835 ms from held pearl to owner arrival, with four destination regions published in 354 ms. These are individual live observations, not p95 claims or warmed single-region substitutes.

Actual vanilla fallback creates a destination island: 427 End-stone blocks/472 writes, all covered by preparation; reciprocal/source metadata round-trips, and a known exit adds no writes. Fabric and NeoForge dedicated shutdown runs each close storage with two jobs pending; all three request observers close, generation drains without failure, temporary folders disappear and the saved-definition handle closes afterward. Existing saves, historical omitted-End upgrades and compiler-disabled immutable reopen pass.

Distant Horizons 3.3.4 and Chunky 1.5.3 run in an actual Fabric client. A bounded 625-chunk End job pauses/resumes, native DH generation runs, and foreground arrival while one DH request is active takes 251 ms. The world reopens afterward. This does not claim every version of either mod is supported.

Final actual-JAR Fabric cold city/reopen validation loads four light-correct target chunks, all 120 city pieces, three working loot chests, one elytra frame and 13 shulkers. Console and interactive /locate work. A passive observer confirms the executing Java class bytes and loaded native image equal the shipped JAR resources. Both loader JARs also pass JDK-only packaged-native execution in all three dimensions. The final host build passes 43 tasks and 349 native tests (82 optional tests ignored, zero failures), plus loader and bridge checks. Final NeoForge full gameplay was not repeated after the compact-material optimization; its earlier gameplay and final packaged bridge/build results are distinct evidence.

The canonical GUI instance is reused with narrator disabled, accessibility onboarding disabled and master volume zero. No further full gameplay campaign is required by this acceptance report.

## Concurrency and controls

A fixed eight-new-region corpus is measured with sixteen region workers and sixteen global Rayon workers at every caller count; no per-caller worker multiplication. These native FFI measurements include MCA completion and cleanup but exclude preview companions and Java decode, unlike the packaged category measurements above.

| Callers | Batch ms | Median request ms | p95 request ms | Maximum ms | Chunks/s | Peak generation RSS MB |
|---:|---:|---:|---:|---:|---:|---:|
| 1 | 1,365.76 | 160.68 | 244.53 | 272.52 | 5,998 | 1,182 |
| 2 | 648.99 | 160.67 | 192.68 | 204.16 | 12,623 | 1,244 |
| 4 | 530.12 | 258.52 | 264.48 | 267.54 | 15,453 | 1,417 |
| 8 | 422.23 | 389.52 | 416.16 | 416.24 | 19,402 | 1,646 |

Concurrent p95 uses linear interpolation; category p95 uses nearest rank. Eight observations do not establish long-run tails. All 8,192 records match across caller counts. Throughput improves while individual request latency rises under shared capacity.

Matched Overworld/Nether controls use ABBA ordering, two warmups and two measured regions per process, four samples per variant/dimension, shipping defaults and matching final GPU program status. Overworld median is 233.57 ms baseline versus 228.60 ms final; Nether is 649.87 versus 643.19 ms. Exact complete NBT corpora match. These small differences are within noise; no material regression is observed and no causal speedup is claimed.

## Delivery and retained evidence

All implementation cycles are merged through isolated `codex/` branches and PRs. Worktrees remain available. The report PR changes documentation only and does not require rebuilding identical production code. Evidence root on the benchmark host: `/Users/cyberpwn/.codex/retina-end-evidence/cafb2ddd-d0c4-460f-94b7-9075fa3d8bb0`. It includes per-cycle fixtures/logs, exact source and binary identities, commands, timing rows, process observations, retained mismatch/failure history and packaged execution proofs. Historical failed or contended attempts are not relabeled as passing. The unrelated-process signaling mistake previously disclosed to the user remains recorded in the work notes; no process interference is part of the final validation method.

- [#243](https://github.com/ArcaneArts/Retina/pull/243) `codex/end-reference-fixtures`: Establish compact vanilla End references and bounded benchmarks (merged `8b52bc148960`).
- [#244](https://github.com/ArcaneArts/Retina/pull/244) `codex/end-island-density`: Implement vanilla-seeded End island density primitives (merged `506bed2878a7`).
- [#245](https://github.com/ArcaneArts/Retina/pull/245) `codex/end-blended-density`: Implement exact seeded End density and dense terrain reference (merged `0614769eda7f`).
- [#246](https://github.com/ArcaneArts/Retina/pull/246) `codex/end-dense-lattice`: Share exact End density corners across dense terrain tiles (merged `250979fcbaed`).
- [#247](https://github.com/ArcaneArts/Retina/pull/247) `codex/end-native-profile`: Integrate explicit End profiles with native terrain and queries (merged `e27d10955fed`).
- [#248](https://github.com/ArcaneArts/Retina/pull/248) `codex/end-shared-island-field`: Share exact End island candidates and select measured CPU execution (merged `b14acfe462b5`).
- [#249](https://github.com/ArcaneArts/Retina/pull/249) `codex/end-feature-foundation`: Add fixed placement and native End arrival platform (merged `35cd4acbc18d`).
- [#250](https://github.com/ArcaneArts/Retina/pull/250) `codex/end-island-features`: Generate End islands with exact world-seeded decoration (merged `f56906e92f32`).
- [#251](https://github.com/ArcaneArts/Retina/pull/251) `codex/end-chorus`: Implement exact End chorus generation and live placement reads (merged `50db0d368adf`).
- [#252](https://github.com/ArcaneArts/Retina/pull/252) `codex/end-spikes`: Generate vanilla-compatible End spikes, cages and crystal entities (merged `2bd2539e74f4`).
- [#253](https://github.com/ArcaneArts/Retina/pull/253) `codex/end-gateways`: Generate natural End gateways with exact placement and exit data (merged `169a2421ce10`).
- [#254](https://github.com/ArcaneArts/Retina/pull/254) `codex/end-city-planning`: Implement exact world-seeded End city and ship plans (merged `c12b284d5311`).
- [#256](https://github.com/ArcaneArts/Retina/pull/256) `codex/end-city-integration`: Export resident End city templates and source marker NBT (merged `77ac043e7204`).
- [#260](https://github.com/ArcaneArts/Retina/pull/260) `codex/end-city-execution`: Integrate native End city execution, loot and saved structure data (merged `9f59d05c7fe5`).
- [#262](https://github.com/ArcaneArts/Retina/pull/262) `codex/end-city-frozen-metadata`: Preserve saved End city geometry without live templates (merged `3a9fcfbf4463`).
- [#263](https://github.com/ArcaneArts/Retina/pull/263) `codex/end-combined-generation`: Reconcile End city stair shapes across chunk boundaries (merged `60b3d155040e`).
- [#264](https://github.com/ArcaneArts/Retina/pull/264) `codex/end-region-publication`: Validate saved End definitions through complete MCA publication (merged `c17e18d1aca7`).
- [#267](https://github.com/ArcaneArts/Retina/pull/267) `codex/native-host-thread-allocator`: Fix macOS native world reopen after host-thread exit (merged `0f2915e01936`).
- [#268](https://github.com/ArcaneArts/Retina/pull/268) `codex/end-live-lifecycle`: Validate live End city loading, loot, locate and world reopening (merged `8f7e4354c3f8`).
- [#272](https://github.com/ArcaneArts/Retina/pull/272) `codex/end-region-profile`: Measure complete saved-definition regions and preserve bounded benchmark evidence (merged `413fbcf0d442`).
- [#273](https://github.com/ArcaneArts/Retina/pull/273) `codex/end-cell-occupancy`: Reduce End terrain work with conservative cell masks and compact columns (merged `862f8debad84`).
- [#276](https://github.com/ArcaneArts/Retina/pull/276) `codex/end-noise-bounds`: Skip provably uniform End noise cells and batch exact column evaluation (merged `064f6a57a05e`).
- [#283](https://github.com/ArcaneArts/Retina/pull/283) `codex/end-substrate-reuse`: Reuse End substrate across decoration dependencies (merged `cdb3d3c9a715`).
- [#284](https://github.com/ArcaneArts/Retina/pull/284) `codex/end-portal-preparation`: Prepare End portal entry and return before vanilla transitions (merged `9d1ef7a18785`).
- [#285](https://github.com/ArcaneArts/Retina/pull/285) `codex/end-gateway-preparation`: Prepare End gateways and preserve pending pearl travel (merged `e7bd16d6625a`).
- [#286](https://github.com/ArcaneArts/Retina/pull/286) `codex/end-gateway-fallback-proof`: Verify dynamic End islands and pending dedicated shutdown (merged `28a735395320`).
- [#288](https://github.com/ArcaneArts/Retina/pull/288) `codex/end-test-bootstrap`: Initialize shared Fabric End test registries once (merged `227e6bff362b`).
- [#289](https://github.com/ArcaneArts/Retina/pull/289) `codex/end-automatic-activation`: Activate supported End generation automatically (merged `24108d705112`).
- [#290](https://github.com/ArcaneArts/Retina/pull/290) `codex/end-installed-integrations`: Verify installed End integrations and foreground work during DH (merged `8d906550e458`).
- [#292](https://github.com/ArcaneArts/Retina/pull/292) `codex/end-untouched-stair-columns`: Avoid untouched End column allocations in city shape updates (merged `a299997f11f0`).
- [#296](https://github.com/ArcaneArts/Retina/pull/296) `codex/end-compact-final-sections`: Keep End air sections compact through lighting and publication (merged `f944e8c9d9dc`).
