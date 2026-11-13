# Region admission on Metal

Two native callers increase complete Nether region throughput at the same
four-worker budget and raise peak process RSS. The foreground biome query
trace also sees longer outliers. This experiment measures the tradeoff and
tests a host-side limit on simultaneous ordered decoration planners.

## Reproduce

Use the modern normal snapshot at seed 12345, SHA-256
`bcce2aa8b88499fcaddbbc2ecffb7fc45806608ced4cc61ad154a0582aef4a68`,
on an M4 Max. Run the locked release library in independent processes and
alternate 1/2/2/1 callers with identical assembly and Rayon limits.

```sh
RAYON_NUM_THREADS=2 python3 scripts/native-region-benchmark.py \
  --library /absolute/path/to/libretina_worldgen.dylib \
  --definition /absolute/path/to/modern.dat --dimension minecraft:the_nether \
  --backend metal --parallel 1 --assembly-threads 4 --readiness shader-ready \
  --origin-x -1 --origin-z 0 --grid-width 3 --warmups 2 --count 8 \
  --scope lit-mca --lighting enabled --mca-compression zlib \
  --out /absolute/path/to/fresh-output
```

Use `--compare` for exact decompressed NBT and MCA membership. Repeat with
`--foreground-biome-probes --diagnostics --gpu-interval` in separate
processes. The fixed remote-coordinate biome corpus is verified by the
harness before and during generation. The [receipts](perf-484-480-region-admission.json)
record request durations and dispatch waits, separate GPU queue time and
sampled RSS at 250 ms intervals. Foreground latency includes the native call
and its queueing. It does not measure client frame time.

## Baseline

| Metal Nether, eight regions | 1 caller | 2 callers |
| --- | ---: | ---: |
| Generation chunks/s | 1,039 | 1,613 |
| Generation peak RSS MiB | 2,312 | 3,320 |
| Generation sampled median RSS MiB | 2,146 | 2,712 |
| Generation request p95 ms | 1,195 | 1,466 |
| Process CPU ms/region | 2,092 | 1,797 |
| Foreground run chunks/s | 770 | 929 |
| Foreground run peak RSS MiB | 2,352 | 3,260 |
| Foreground run request p95 ms | 1,560 | 2,593 |
| Foreground query p95 ms | 7.38 | 8.34 |
| Foreground query p99 ms | 14.18 | 16.07 |
| Foreground query maximum range ms | 18.86–19.47 | 36.09–46.47 |

During foreground runs, two callers raised ordered decoration planning
from 1.20 to 1.88 s/region and assembly pool entry wait from about 0.01
to 9.44 ms/region. The dependency pool wait rose to 39–42 ms/region.
Summed GPU worker queue time per region stayed near 83–85 ms. These
timers overlap and the GPU figure includes the foreground requests.
The increased region time and RSS support testing planner overlap.
They do not establish which allocation owns every extra resident page.

## Ordered planner limit

The prototype took an engine-local permit on the caller before ordered
decoration planning and released it after the planner's teardown.
Terrain field requests, final assembly, lighting, and foreground biome
queries were still eligible to overlap. It did not put a waiting permit
inside the GPU worker or assembly pool.

In the first eight-region A/B/B/A at two callers, peak RSS fell from
3,112 to 2,388 MiB. Throughput fell from 1,166 to 966 chunks/s and native
request p95 rose from 2,070 to 2,537 ms. Process CPU decreased from 2,623
to 2,393 ms/region. All compared chunks matched byte for byte.

The 12-region reverse-order repeat toggled the policy within the same
native build. Peak RSS fell from 3,386 to 2,410 MiB, and sampled median
RSS fell from 3,093 to 2,319 MiB. Median throughput fell from 1,029 to
987 chunks/s, with the control processes spanning 956–1,103 chunks/s
and both candidate processes near 987. Native request p95 rose from
2,490 to 2,631 ms. The previous, larger throughput loss did not repeat
at the same size.

In the separate eight-region foreground A/B/B/A, the planner wait added
0.90–1.13 seconds per region. Peak RSS fell from 3,181 to 2,457 MiB.
Throughput moved from 835 to 667 chunks/s. The per-process foreground
query p95 ranged from 7.73–8.54 ms in controls and 6.40–13.14 ms with
the limit. Maximum query time was 37.19–41.88 and 38.39–46.16 ms,
respectively. The limit reduced peak memory and provided no repeatable
foreground tail improvement.

All foreground answer hashes matched. Every A/B comparison preserved
decompressed chunk NBT and MCA membership. Reopen in the foreground
admission runs preserved all 1,024 records in the first region. The
locked release build and the Java region preparation suite passed,
including queued cancellation, an active permit surviving owner closure,
and shutdown draining. Those Java tests exercise the existing caller
contract. The native benchmark let entered region calls drain.

The prototype was discarded. A follow-up can measure the live bytes of
each ordered planner through its allocation and teardown phases and
test a smaller budget only during the high-water interval. A full-phase
limit left too little overlap in this workload. Windows remains unmeasured.
