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

Historical tables here retain the nearest-rank p50 reported at capture. The
runner now averages the central pair for an even-count median; see
[the noise-bound comparison](end-noise-bounds.md) for recomputed medians from
the original timing rows. P95 and maximum values are unchanged.

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

## Conservative cell occupancy and compact columns

The next End-only implementation retains every coarse noise corner and the
dense density-query API. It classifies each complete 8×4×8 interpolation cell
from all eight corners, with a conservative floating-point rounding margin.
Empty and solid cells avoid voxel interpolation; uncertain cells retain the
exact bulk evaluator. A 128-bit base-terrain column replaces repeated scans of
256 stored heights when constructing material runs and height columns. The air
plane starts filled and clears occupied voxels, with padding bits kept zero.
No finished chunk, decoration root, structure, entity, biome, or lighting work
is skipped based on the base-terrain mask.

Matched development batches used the same saved definition, coordinates,
Metal backend, ZLIB, one caller, eight workers, two warmups, and eight samples:

| Workload | Dense median ms | Compact median ms | Compact p95/max ms |
| --- | ---: | ---: | ---: |
| Mostly void | 854.7 | 332.1 | 341.7 |
| Outer islands | 856.5 | 358.7 | 368.5 |
| Cities/central arena | 806.0 | 297.8 | 361.7 |

Four complete raw-record hashes match the dense baseline exactly: regions
(1,0), (8,8), (-2,-3), and (0,0). These cover mostly void, outer islands, a city,
and one central-arena quadrant. Independent source fixtures, unaligned and far
coordinates, near-zero classification, and clipped material-run encoding also
pass. The native suite reports 336 passed, zero failed, 81 optional ignored.

A separate instrumented region (1,0), including all surrounding terrain tiles,
records 862,486 empty cells, 34,538 solid cells, and 31,232 uncertain cells.
That avoids 229,638,144 voxel interpolations across the complete required
neighborhood. Mask construction costs 3.12 ms additive and 928,256 allocated
mask bytes cumulatively across 13 tiles; these are not peak residency or wall
time. The masks do not yet avoid coarse noise evaluations. Peak process RSS
is approximately 892–912 MB across the timed categories and includes startup,
driver caches, and all generation state; no process-memory reduction is claimed.

Mostly-void stage medians fall to 68.9 ms initial terrain and 224.9 ms decoration
planning. Required surrounding terrain remains the primary cost. Void and
ordinary-island latency still miss the final goal targets; this is one measured
optimization, not final shipping/defaults/gameplay acceptance.

The normal-world Overworld control has a 233.0 ms candidate median versus
417.3 ms initially; this unrelated variation is not attributed to End code.
An initial Nether subprocess guard failed during overlapping Rust compilation.
Both the unchanged baseline and candidate slowed, so that failed evidence is
retained as contaminated. After the other workload ended, an alternating ABBA
check used four measured region samples per binary (two matched coordinates
per fresh process): baseline median 784.3 ms/max 945.6 ms, candidate median
759.7 ms/max 804.9 ms. The region-timer regression comparison passes 5%; the
3.1% median difference is inconclusive. This small check resolves the local
regression concern, not the final multi-caller or shipping-default acceptance.
