# Native generation concurrency sweep

The concurrency runner starts a fresh native process for every cell, fixes the
definition, seed and coordinates, and compares every completed MCA chunk against
the first successful corpus. It records completed chunks, region median/p95,
process CPU usage, configuration, exit status and optional stage diagnostics.
Hashing and NBT comparison happen outside measured batches. A failed or timed-out
cell is preserved and stops the sweep; it cannot improve the summary.

```sh
python3 scripts/generation-concurrency-benchmark.py \
  --library /path/to/retina_worldgen.dll --definition /path/to/retina.dat \
  --backend vulkan --out /path/to/new-results \
  --region-concurrency 2 4 8 --assembly-threads 1 2 4 \
  --compiler-threads 1 2 --repeats 2 --count 20 --warmups 5 \
  --readiness shader-ready --lighting disabled
```

Use `--readiness driver-warm --lighting enabled --diagnostics` in a separate
diagnostic sweep to include lighting and observe background compilation. The
`shader-ready` sweep isolates steady generation: changing compiler concurrency
there is a control, not a measurement of compilation contention. The application
cache is private to the sweep and retained between processes; driver cache state
is uncontrolled. This is native generation, not Java preparation or rendering.

## Local observations

Apple M4 Max / Metal, release native build, 20 measured 1,024-chunk regions after
five warmups, two passes in reversed order: all 36 processes completed and each
20-region measured corpus passed the same structural chunk comparison. One assembly worker achieved roughly
1,600–2,000 chunks/sec across the cell medians, two roughly 2,800–3,900, and four
roughly 4,400–5,100. With four assembly workers and one compiler worker, two,
four and eight concurrent callers had cell-median throughput 4,697 / 5,144 /
4,439 chunks/sec and median p95 region latency 739 / 2,446 / 4,981 ms.

These are observations, not a scheduling recommendation. CPU-heavy unrelated
processes overlapped the latter part of this sweep; several reverse-pass trials
slowed materially. Power remained AC/normal. Results do not establish a portable
optimal budget, NVIDIA performance, or a gain over the production default.

A separate four-cell diagnostic sweep used two/eight callers, four assembly
workers, one/two compiler workers, one warmup and five lit MCA regions. All four
passed the same strict chunk comparison. These short warmup runs showed about
1,050–1,555 chunks/sec while background optimization was still active. Device
timestamps were available, and the first submissions spent about 287–295 ms in
the host wait/readback/material-continuation interval. That interval includes
work outside the GPU and must not be interpreted as pure device execution.

## Scheduling decision

Keep the shared native assembly pool and explicit `RETINA_REGION_THREADS`
override, the bounded required/background compiler admission from the earlier
cycle, and the existing GPU pipeline depth. Do not multiply the assembly budget
by the number of regions. Do not automatically lower a user's explicit Java
`mca_parallelism` setting or increase GPU depth because Chunky has eight callers.
The local results justify neither a new machine-wide budget nor a deeper queue.
Matched Windows measurements with Java and rendering active should choose those
defaults. This cycle provides that reproducible comparison instead of adding an
unmeasured scheduler that might regress Metal or starve an active world.
