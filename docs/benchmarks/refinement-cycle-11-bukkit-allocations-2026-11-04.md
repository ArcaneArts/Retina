# Refinement cycle 11: Bukkit delivery allocations

Cycle PR: [#424](https://github.com/ArcaneArts/Retina/pull/424).

Retain the two local palette changes and the paired delivery probe. **Live transfer allocates 13,160 fewer Java bytes per chunk, about 5.08%, with neutral measured latency.** This is an allocation improvement; it does not establish faster native generation, startup, client rendering or full-chunk activation.

The [machine-readable record](refinement-cycle-11-bukkit-allocations-2026-11-04.json) includes individual samples, pair deltas, descriptive p95/variance, hashes, commands, host load, profiles, incomplete experiments and lifecycle results. Large recordings, NBT/MCA fixtures and binaries remain under `~/.cache/retina/refinement-implementation-20261104/` outside Git.

## Change and scope

Baseline is `4cebcbb0829aaa3992ddc70b704bfc79ab48ff38`. Both tested plugin JARs contain exactly the same native library, SHA-256 `775bb1317e26f7aa17459dedb91fc030093ab4354c76ea250b53a1e0df548597`; its production source tree matches current main. The control/candidate plugin hashes are `becbbad9...` / `9cb032f5...`, recorded in full in JSON.

`ChunkSnapshot.state` returns the existing ID immediately for an empty property compound, avoiding a temporary TreeMap. Nonempty properties retain their sorted canonical representation and existing errors. Installation binds each section's BlockData array with a direct loop, preserving lookup order and the existing uniform-section/run installation paths while removing stream scaffolding.

Bindings still belong to DimensionContext. Snapshot bounds, world ownership, unloading and cache capacities retain their existing contracts. There is no additional binding cache or retained palette representation.

The disposable server probe now supports live calls through the reference implementation loader, five paired A/B and B/A comparisons with two stable loaders, and current-thread CPU time beside wall time and allocation. Each paired command closes both loaders when it finishes, including failure paths. These facilities are confined to serverTest.

## Measurements

Host: Apple M4 Max, macOS 27.0.1, JDK 25.0.1, CraftBukkit 26.3. The server has four active processors and a 2 GiB heap; native global Rayon is two and the requested backend is Metal. Native bytes and backend settings are identical on both sides. The actual saved definition uses seed `-9223372036854775767`.

The primary fixture is Overworld chunk (-1,-1). Fixed transfer copies captured native bytes, parses NBT, constructs palettes, validates metadata, installs into real Bukkit ChunkData and reads all 256 heights. Live transfer obtains bytes through the actual native chunk call before those same steps. Native region data is warmed; this is not fresh-region generation. Installation-only trials exclude decoding. Each trial has two warm rounds and five measured rounds, with 100 fixed/installation iterations or five live iterations per round. Ten interleaved trials form five matched pairs per mode.

| Primary workload | Control bytes/chunk | Candidate bytes/chunk | Paired allocation change | Paired wall change |
| --- | ---: | ---: | ---: | ---: |
| Fixed transfer | 301,552 | 288,128 | -4.45% | -1.61% |
| Live native transfer | 259,040 | 245,880 | -5.08% | +0.14% |
| Fixed installation only | 74,680 | 69,880 | -6.43% | -1.21% |
| Live installation only | 69,464 | 64,664 | -6.91% | -0.61% |

Byte columns are medians of individual observations; changes are medians of five matched trial ratios. They need not equal ratios of the displayed medians. All five pairs reduce allocation. Live latency's descriptive paired bootstrap interval is **-2.22% to +2.02%**. Current-thread CPU's live change is -0.02%, interval -1.57% to +1.90%. Small timing effects remain inconclusive; retain on the clear allocation saving and neutral live behavior.

ThreadMXBean measures allocated bytes exactly for the calling Java thread. CPU time covers that thread, including synchronous FFI work, but excludes native workers. The shared JVM's sampled high-water RSS is about 1.06 GiB; it cannot establish a per-variant retained-memory saving. There is no RSS claim.

A broader nine-fixture series covers Overworld, Nether and End at (0,0), (-1,-1), and (12,-13). All ten fixed-data commands complete with 900 sample rows and matching full block/biome/height digests. An early live series completes only two commands before a real 300-second timeout. A subsequent activity-controlled broad attempt also becomes slow and is stopped. Those timings are unqualified, and no interrupted command counts as a completed pair. The final stable-loader primary series completes all 200 required sample rows and all parity checks.

Host load changed substantially. The final process held an explicit macOS user-initiated activity token inside the JVM, with idle system/display sleep allowed, using a private external Java agent; its source and binary hashes are recorded. The parent held its own token. No system setting changed, and the agent is not shipped. The slower activity-controlled attempt prevents attributing the scheduling problem to App Nap or claiming this token fixed it. Stable loader identities also remove repeated class loading/JIT as a comparison confounder. These changes and the different load window are not separable causal experiments.

## Actual callback profiling and correctness

Before the candidate, fixed and live transfer recordings ran inside CraftBukkit. Render with `jfr print --json --stack-depth 128`; the renderer's default shallow stacks lose important callers. The fixed recording has 2,410 installation execution samples, versus five in canonical state construction. These are sampling observations over warmups, measurements and untimed work, not elapsed-time percentages. Parsing and installation dominate visible allocation weights; a larger canonical-state cache or zero-copy API redesign is not justified by this evidence.

The transfer probe uses Bukkit `createChunkData`, which returns OldCraftChunkData on this server, and a warmed probe HashMap binder rather than DimensionContext's immutable map. Therefore its timings cannot represent the complete production callback or activation path.

Separate baseline and candidate production recordings exercise the real generator and confirm **CraftChunkData.setBlock/setRegion** on Worker-Main threads, including ProtoChunk installation. The candidate's create recording contains 95 installation samples and substantial biome-provider allocation. The baseline provider samples include Long boxing, captured suppliers, tile objects and read-lock bookkeeping, plus cold runtime setup. These are follow-up leads, not counts of avoidable allocations or proven benefits from a new cache.

One cold baseline forced-load batch takes 62.53s; the candidate lifecycle run's first batch takes 76.43s. These are separate, ordered, JFR-instrumented fresh JVM observations under variable load, not a qualified startup A/B test. A captured worker dump shows native chunk/biome calls while the main thread waits for forced loading. This cycle does not claim to fix that cold path or explain its full delay.

The complete candidate lifecycle suite passes using the exact measured JAR:

- Create/restart, shared authoritative definition, imported cropped storage, separately created Nether/End, unload/reopen, plugin disable and preserved edits.
- 917,504 block comparisons, 14,336 quart-biome comparisons across 12 chunks, three player edits, and ten persisted End crystals.
- Gateway, inventory components, lazy loot, spawner and entity metadata before/after restart; five definitions preserved.
- Malformed definition, missing material, missing biome, seed mismatch and unsupported metadata all fail as intended while preserving their supplied files.
- Bukkit unit suite: 21 passed, three native-gated tests skipped; plugin and probe builds pass. Actual native execution is covered by the server suite.

The first lifecycle controller had an external import-path error after completing its gameplay commands. It is recorded as incomplete. The corrected controller reruns the entire suite and passes. No production fix is inferred from that harness error.

## Reproduction and remaining work

Build the plugin and disposable probe with `:bukkit:shadowJar :bukkit:serverProbeJar`. Keep frozen baseline/candidate JARs and use TransferInventory to capture real chunk NBT fixtures, or reuse the recorded fixture bytes/hashes. Run the probe only on an owned test server:

```text
retinaprobe transfer-paired-benchmark <fixture-directory> <control.jar> <candidate.jar>
retinaprobe transfer-live-paired-benchmark <fixture-directory> <control.jar> <candidate.jar>
```

`RETINA_TRANSFER_PAIR_BEGIN` identifies each trial; `RETINA_TRANSFER_PAIR_PASS` marks complete execution. SAMPLE columns are fixture, round, wall ns/op, allocated Java bytes/op, and current-thread CPU ns/op (-1 when unsupported). Compare every emitted full-value digest. Use the existing `scripts/bukkit-server-test.py` lifecycle suite for actual callback/thread/lifetime behavior and persisted output.

Actual tall Bukkit hosts and client FPS/TPS remain untested. Expanded/cropped storage and multiple seeds are covered by the server suite. O5 bounded overlap/fairness, native O6 palette/NBT construction and real Windows O4 qualification remain separate mandatory goal work. No backend default, native generator, release artifact or public release is changed here.
