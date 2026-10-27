# Saved-definition region benchmark

`native/examples/world_region_benchmark.rs` binds a real `retina.dat` with
`WorldSession` and runs the production 1,024-chunk region writer using ZLIB.
It accepts supported dimensions in format 1 or 2 snapshots; it does not export
registries, replace definitions, or enable an unsupported adapter.

```sh
cargo build --release --manifest-path native/Cargo.toml --example world_region_benchmark
RETINA_REGION_THREADS=8 RETINA_BENCH_REVISION=<tested-revision> \
  native/target/release/examples/world_region_benchmark \
  /absolute/path/retina.dat minecraft:the_end void /tmp/end-void 8 2
```

Arguments are snapshot, dimension ID, workload, output directory, measured
sample count (default 8), and warmup count (default 2). `void` and `heavy`
contain eight distinct seed-123456789 End region coordinates. `islands` and
`control` traverse consecutive regions from (8,8). Workload labels describe
the intended reference inputs, not a guarantee about arbitrary seeds or
custom definitions. Output counts expose actual non-air sections, entities,
block entities, and structure starts. The heavy catalog covers four known
city regions and the four regions sharing the central arena. It does not
measure cold portal arrival.

The timer covers the synchronous region call, including required terrain and
decoration dependencies, lighting, NBT, compression, publication, and its
generation-owned cleanup. Snapshot reading and engine startup are reported
separately. Record decoding, hashing, and deletion happen after that timer.
Each MCA is created in a unique disposable subdirectory, decoded into a
length-prefixed raw-record SHA256, and immediately removed. Existing worlds
and region files are never selected for cleanup. An interrupted or failed run
may leave its uniquely named disposable directory for diagnosis.

The JSON report records per-region and aggregate latency, throughput, stage
timings, process peak RSS, definition and executable hashes, seed, data version,
backend, worker environment, and revision label. Capture machine/OS and any
known contention alongside the JSON. Worker stages are scaled wall shares;
device stages overlap host work and must not all be added together. Peak RSS
includes driver/startup state and is not an allocation counter. With eight
samples p95 equals the largest observation and gives limited tail confidence.

Use the same executable settings, snapshots, coordinates, and worker budget for
baseline/candidate comparisons. Keep diagnostic environment variables off for
latency measurements; `RETINA_PERFORMANCE_DIAGNOSTICS=1` and
`RETINA_DEPENDENCY_DIAGNOSTICS=1` are separate attribution probes. No region
coordinate is repeated within the measured End catalogs. Neighboring regions
can still reuse bounded generation caches, as in production. Raw-record hashes
compare two Retina implementations; they do not establish vanilla parity.

The initial full-feature End measurements at generation revision 234347cc
(seed 123456789, Metal, 8 workers, 1 caller, 2 warmups/8 samples) were:

| Workload | Median ms | Maximum/p95 ms |
| --- | ---: | ---: |
| Mostly void | 854.7 | 871.8 |
| Outer islands | 856.5 | 890.7 |
| Cities/central arena | 806.0 | 844.9 |

These are development baselines, not final performance acceptance or certified
uncontended results. Initial terrain preparation and decoration dependency
terrain dominate: mostly-void stage medians were 240.5 ms and 582.1 ms
respectively, versus 12.1 ms lighting and 13.7 ms publication I/O. A separate
instrumented probe attributes most dependency cost to surrounding End terrain
tiles. Later optimizations must retain complete feature, structure, and lighting
coverage and compare their output against the dense baseline.
