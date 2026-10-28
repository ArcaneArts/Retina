# Persistent Bukkit biome cache

CraftBukkit and Spigot compute stronghold ring positions through the delegated
BiomeProvider even when Retina disables structure placement. The original
bounded Java cache already served more than 98% of these callbacks, but a
restart still filled 8,192 distant tiles through synchronous native queries.
Those answers now survive world saves and restarts.

## Behavior and bounds

Sparse queries retain their original 32-by-32-block tiles, quart Y coordinates,
64 exact native answers and four default concurrent loaders. The hot cache still
retains 256 tiles by default. A separate LRU backing cache retains at most
16,384 tiles per dimension, including restored tiles. `biome-disk-cache` sets a
smaller capacity; `0` disables it. A full backing cache holds 4 MiB of raw integer
IDs plus bounded array/map overhead. It releases its entries when the owner
closes. Two shared daemon writers handle persistence; native code and GPU
shaders are unchanged.
Full 3D grids, fitted storage and single-biome behavior retain their existing
paths.

Each dimension has a gzip-compressed file under the logical definition root's
`retina-cache/`, named with the dimension identifier's SHA-256. Its RBC1 header
contains SHA-256 hashes of the complete `retina.dat` and the selected native
library image, plus the dimension identifier. A development native override is
hashed too. The payload contains integer tile coordinates and 64 unsigned-short
local palette IDs per tile. Changing the tile encoding requires a format bump.
Java-only updates can reuse a cache when both identities and its format match.

World-save events enqueue changed caches without waiting for compression or disk
I/O. Repeated events share one queued task per cache; only the two active writers
copy snapshots. Each snapshot records its revision so concurrent updates remain
dirty. Owner shutdown drains generation and waits for the final cache checkpoint
before releasing native resources. Writes use a temporary file and atomic
replacement, with ordinary replacement when atomic moves are unsupported. A cache is published in memory only after its entire bounded
payload, palette IDs and gzip checksum pass. Mismatched identities or formats
are ignored; payload corruption and I/O failures warn. All these cases retain
normal native query behavior. Nothing replaces or modifies `retina.dat`.
Deleting `retina-cache/` with the server stopped is safe. Large workloads can
evict old answers; cold worlds, invalidated caches and missing tiles still pay
for normal generation. A crash before an asynchronous checkpoint completes can lose newly cached work.
This disposable cache does not control persistence of world/chunk data.

The save-path measurements and concurrency validation are recorded in
[the performance implementation ledger](PERFORMANCE_RELIABILITY_PASS_2026_10.md).

## Focused actual-server measurements

Measured on 2026-10-27: CraftBukkit 4664-Bukkit-e89124d, Minecraft 26.3,
Java 25.0.1, macOS 27.0.1, Apple M4 Max/Metal. Each dedicated server used
`-XX:ActiveProcessorCount=4 -Xmx2G`, no GUI, the real bundled native image and
seed `-9223372036854775767`. Two independent cold JVM/world runs each had a
separate materializer-disabled restart. There were deliberately no JVM warmups.

| Workload | Original samples (seconds) | Persistent-cache samples (seconds) |
| --- | --- | --- |
| First Overworld forceload, nine requested chunks | 81.38, 90.64 | 130.94, 123.39 |
| Same world's next launch to server ready | 97.63, 99.00 | 7.73, 8.21 |

Restart median fell from **98.32 to 7.97 seconds** (91.9% lower). The two-sample
nearest-rank p95 is merely the maximum: 99.00 versus 8.21 seconds. This is a
bounded observation with a small sample, not a latency guarantee or sustained
terrain rate. During later measurements a Minecraft client and other desktop
work became active, changing GPU/CPU contention. The first-generation comparison
is therefore inconclusive; this change claims no cold-generation acceleration.

The causal cache evidence is independent of that timing variation: both cached
restarts restored 8,241 Overworld tiles, served all 8,192 sparse cache loads from
those answers, and recorded **zero misses**. Original restarts performed those
8,192 native fills again. Across all three dimensions, the derived files totaled
113,084 and 113,186 bytes (about 110.5 KiB). The cache has no NMS dependency and
does not suppress or approximate a server biome search.

An earlier 64-by-64-block parallel-prefetch experiment reduced tile misses but
sampled more points and did not demonstrate a reliable speedup; it was removed.
Its two cold samples were 98.41/105.60 seconds and restarts 101.47/119.64 seconds.
The retained implementation uses the original fill geometry and scheduling.

## Correctness and reproduction

Each cold and restart measurement independently matched native output across
12 public-API snapshots: 917,504 blocks, 14,336 quart biomes, three player edits
and all ten persisted End pillar crystals. Decompressed snapshot bytes also
matched across the original, prefetch and persistent runs: SHA-256
`71a3d69e8c28563c13a6f1e53d2c450c515ecc3dbf807786c23a172644017c55`.
All definitions retained SHA-256
`5667be6d081a1270a66aa963a83a17e0d1bd5cb72253c59b863846f8eb4b73c4`;
the host native image retained SHA-256
`83fd7bf0c42f84636bbc430414e9ab1d98aa3ec6c67b695943508507c14be2a2`.

```sh
./gradlew :bukkit:test -PretinaNativePath=/absolute/native/library --no-watch-fs
./gradlew :bukkit:shadowJar :bukkit:serverProbeJar --no-watch-fs
python3 scripts/bukkit-biome-benchmark.py \
  --java /absolute/jdk-25/bin/java \
  --server /absolute/craftbukkit.jar \
  --plugin /absolute/retina-bukkit.jar \
  --probe /absolute/retina-bukkit-probe-server-test.jar \
  --output /absolute/new-benchmark-directory --iterations 2 --label candidate
```

The benchmark uses disposable worlds and explicitly accepts their Minecraft
EULA. It retains console logs, separate cold/restart captures, independent native
parity results, RSS samples, cache statistics, hashes and per-phase timings.
`--disk-cache 0` measures the same build without persistence. Its server watchdog
is 300 seconds; individual operations remain bounded and actual callback errors
fail immediately. Use an idle host and more iterations for a controlled
cold-performance comparison.

Local evidence is retained outside the temporary implementation worktree at
`/Users/cyberpwn/.cache/retina/bukkit-biome-cache-20261027/`: `baseline/report.json`,
`candidate/report.json` (discarded prefetch), `persistent/report.json`,
`comparison.json`, the individual JARs/worlds/captures and
`restart-comparison.md`. The baseline is main `89c382c` plus the same cheap cache
counters used in the candidates. The focused persistent benchmark precedes a
final shutdown change that also clears retained backing arrays; final server
acceptance exercises that cleanup.

## Final universal-build acceptance

All 16 Bukkit tests passed, including native grid parity, zero-query reopen,
identity invalidation, bounded eviction, disabled persistence, CRC/truncation,
invalid counts/IDs/duplicates, real I/O failures, save-before-close and stale
query rejection with backing-array release. The same universal candidate then
passed the full actual-server suite on all three implementations:

| Server | First Overworld request (seconds) | Restart to ready (seconds) | Result |
| --- | --- | --- | --- |
| craftbukkit-26.3.jar | 96.02 | 4.91 | Pass |
| paper-26.3-226.jar | 9.57 | 24.31 | Pass |
| spigot-26.3.jar | 107.79 | 4.93 | Pass |

These runs exercised independent seeds, all dimensions, imported mod snapshots,
metadata persistence, concurrent unload/recreation, native-owner closure and five
startup failures per server. Every server matched 917,504 blocks and 14,336
quart biomes, preserved three player edits and all ten End crystals, and kept its
definitions unchanged. Paper's timings also varied under the shared host load;
this table records acceptance, not a controlled Paper performance comparison.
The runtime/native inputs and six packaged native images were reused unchanged
from the initial acceptance. Foreign images were packaged and inspected, not
runtime-tested on this Mac. The candidate is 28,222,436 bytes, SHA-256
`db7fc379b90096779755ab2bc3a9fa52345209e37e92781326761f2782abb045`.

Commands used `:bukkit:test :bukkit:shadowJar :bukkit:serverProbeJar` and
`:bukkit:serverTest -PbukkitServerJar=/absolute/server.jar`, with
`--no-watch-fs`. Actual unchanged native build outputs were reused; the
unchanged eight mod-exported definition fixtures were copied from the initial
acceptance and `:common:worldDefinitionCompileTest` was skipped. No native
override was supplied to the dedicated servers. Raw acceptance runs and logs
are retained in the evidence directory's `server-acceptance/`.
