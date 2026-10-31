# CPU column preparation

A complete CPU Overworld region exposed a remaining serial stage: preparing the
38-by-38 chunk column halo took 37.477 seconds of 42.753 seconds elapsed. The
process averaged 1.601 scheduled cores despite a 16-worker budget. Material
production was already using the bounded terrain lane.

Column preparation now schedules chunk-row strips on that same terrain pool.
Each task owns its sampler and caches; output slices retain the existing chunk,
row and column order. There is no additional pool or per-column sampler. Sparse
point queries retain their existing execution. Arithmetic and generation rules
are unchanged.

One matched diagnostic per build used an Apple M4 Max, seed 123456789, the actual
binary 26.3 definition, region 0,0, full lit MCA output and 16 total workers:

| Observation | Serial columns | Parallel columns |
| --- | ---: | ---: |
| Complete region elapsed | 42.753 s | 10.175 s |
| Column preparation elapsed | 37.477 s | 4.896 s |
| Material preparation elapsed | 3.011 s | 3.006 s |
| Process CPU time | 68.433 s | 85.091 s |
| Average scheduled cores | 1.601 | 8.362 |
| Generation peak RSS | 1.123 GB | 1.146 GB |

Reduced cross-strip cache reuse and parallel work cost more CPU time while
substantially reducing latency. These bounded diagnostics include normal host
activity and are not production percentiles or an isolated quiet-machine claim.
CPU stage intervals and process CPU seconds have different scopes.

All 1,024 decompressed Overworld records retained corpus SHA-256
`8634e04bfa736a0537dbb2148f3b93ade710d92a31272a6597c2f7595880e8dc`.
The CPU suite passed 60 tests, including exact serial/one-worker/four-worker
columns for both registered and legacy Overworld/Nether profiles and both dense
column modes. The hardware fixture retained exact agreement for 163,840 Metal
material/cavity/biome samples. The complete Nether region also retained all
1,024 reference NBT records. Disposable generated region files were removed after
extracting results.

Compact commands, hashes, logs and measurements are in the CPU goal evidence
directory `435af8f5-435b-4b07-88a3-ea8d36758d6e`; final downloaded-artifact
all-dimension measurements are recorded separately.
