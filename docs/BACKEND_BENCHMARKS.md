# Complete-region backend comparison

`scripts/benchmark_backends.py` runs the production generation stage sequence
through a closed, compressed MCA file. Both modes consume the same unmodified
loaded registry profile, seed 123456789, build height, and region coordinates.
Every region contains 1024 chunks; 900 interior chunks have generated light and
124 boundary chunks retain normal Minecraft lighting. Noise-only timings and
analytic/flat fixture terrain are not substitutes for this workload.

Compile and export before timing. Keep native compilation at two jobs and run
backends sequentially. The profile exporter supports `-PtestDatapack=/path/pack.zip`.

```sh
JAVA_TOOL_OPTIONS=-XX:ActiveProcessorCount=2 CARGO_BUILD_JOBS=2 \
  nice -n 10 ./gradlew :common:regionBenchmarkProfile -PretinaHostOnly \
  --max-workers=2 --no-parallel

nice -n 10 python3 scripts/benchmark_backends.py --backend rust \
  --artifact build/native-target/release/libretina_worldgen.dylib \
  --profile build/backend-benchmark/profile.json --out build/backend-benchmark/rust

nice -n 10 python3 scripts/benchmark_backends.py --backend bend \
  --artifact "$RETINA_BEND_EXECUTABLE" \
  --profile build/backend-benchmark/profile.json --out build/backend-benchmark/bend
```

Use the corresponding native library filename on other hosts. The Bend artifact
must be the compiled worker with its adjacent `.gpu` archive. The harness uses
the stock GPU-required execution mode and a 4 GiB heap, with no Rust or CPU-only
fallback. Only the current Apple Silicon/Metal host has been validated. An
already warmed executable location avoids recompiling Metal libraries during
startup. The exact artifact path, hashes and cache policy are recorded.

The Rust lane sets `RETINA_REGION_THREADS=2` for assembly and
`RAYON_NUM_THREADS=2` for its global planning pool. Bend uses `--threads 2`.
These are application-worker budgets, not CPU affinity or limits on graphics
drivers and shader compiler threads. `RETINA_REGION_THREADS` is optional: unset,
zero and invalid values preserve the existing available-CPU default (2–16);
positive overrides are capped at 16. Normal gameplay defaults are unchanged.

Each process reports initialization/registry preparation separately, then the
first region at (8,8), then three warm regions at (-1,0), (0,0), (1,0). It reuses
the same runtime and registry between regions. New output directories prevent
cache hits from masquerading as generation. Existing OS and GPU driver caches
are retained: startup is process-cold, not a cleared whole-machine cache.
The default three warm samples give a descriptive median and p95, not a reliable
tail estimate; increase `--count` for a longer run. Bend currently makes these
full workloads expensive, so sample count must accompany every comparison.

`report.json` and append-only `events.jsonl` retain completed samples and stage
progress. RSS is sampled every 500 ms from the driver and engine processes; its
sum may double-count shared pages and does not measure GPU allocations. Host
load ranges are recorded. The measurement boundary excludes independent Python
verification and Minecraft loading. Both encoders are allowed to produce
different terrain, compression ratios and valid representations of zero light.

After generation, independent zlib/NBT/MCA checks validate all slots, sector
ownership, coordinates, current data version, registered palette entries,
packed indices, heightmap names and interior/boundary light metadata. A failed
validation can be rerun against the original files with `--validate-only`; this
never substitutes new timings or regenerates terrain. It requires matching
profile and artifact hashes.

Measure Minecraft's own IO/decompression/NBT and chunk/palette decoding in a
separate process, after the generation benchmarks have stopped:

```sh
JAVA_TOOL_OPTIONS=-XX:ActiveProcessorCount=2 nice -n 10 ./gradlew \
  :common:regionDecodeBenchmark -PretinaHostOnly --max-workers=2 --no-parallel \
  -PbenchmarkRegions=build/backend-benchmark/bend
```

This reads each complete file six times using `RegionFileStorage` and
`SerializableChunkData`, checks statuses, and verifies reading leaves the files
unchanged. The preservation snapshot pre-reads each file, so even pass zero has
a warm file cache. Registry/bootstrap time is excluded. The output is
`minecraft-decode.json` in the input directory. Supply the same `testDatapack`
when decoding a datapack corpus.

This decoder measurement is **not live chunk activation**: it excludes scheduling,
outer-ring lighting, entities entering a level and rendering. Actual dedicated
server load-in measurements and the final Rust/Bend results belong in the
comparison report once both runs and live loading have completed.

## Live vanilla load-in

After completing the generation measurements, `qa/backend_load_world.py` copies
the three warm files into a new disposable world using their original seed and
the selected Retina preset. It writes only QA metadata and server settings;
the region files must match their recorded SHA-256 hashes. The world starts at
the center of the existing (0,0) region. This fixture is for the vanilla corpus;
datapack gameplay needs the corresponding packs and dimension settings as well.

```sh
python3 qa/backend_load_world.py build/backend-benchmark/bend build/backend-load-bend
JAVA_TOOL_OPTIONS='-XX:ActiveProcessorCount=2 -Dretina.qa.loadBenchmark=true -Dretina.native.path=/retina-qa/no-rust-library' \
  ./gradlew :fabric:runServer --args nogui -PretinaHostOnly \
  -PretinaDatapackQa=build/backend-load-bend --max-workers=2 --no-parallel
```

The dedicated check requests 12 complete chunks, including both sides of two
region seams, verifies active light and terrain, places a diamond-block edit,
and saves/exits. Generation counters must stay zero. Repeat with
`-Dretina.qa.loadBenchmark.reopen=true` to verify the saved edit and measure
reloading. Results are saved under `world/retina-load-benchmark-first.json` and
`world/retina-load-benchmark-reopen.json`; require the actual
`minecraft_backend_load_benchmark` pass event. The JVM uptime includes bootstrap
and normal spawn activation; per-request timings isolate synchronous full chunk
activation. These are headless measurements, not rendering/FPS measurements.

Repeat using the Rust output and a separate destination, removing the deliberate
missing-native-library override. Compile both loaders before measuring, keep
worker limits identical, and do not run servers concurrently with generation
benchmarks. No interactive client needs to be launched.
