# Resuming decoration anchors

Missing spatial provider/count samples previously caused an unresolved anchor to
repeat its entire expansion and ordered placement pass. Completed anchors were
already retained; this change also retains the valid prefix inside an unresolved
anchor.

## Behavior

An anchor owns its expanded queue, completed block commands, live overlay, and
shared placement-evaluation cache. At the first missing provider it snapshots the
state before that feature. Feature-local writes are journaled until the feature
finishes, so a nested sequence that writes before sampling can restore its exact
starting overlay. The journal handles repeated writes and all six heightmaps.
Derived ground-transition caches can be recomputed from the restored overlay.

Lazy floor/count expansion checkpoints its queue and evaluation cache at the
first unresolved count, before installing partial children. It retains the RNG
state and selected ground of that attempt. Retrying an already selected candidate
takes precedence even over candidates with equal sort keys.

The current speculative pass continues after the checkpoint. It collects the
same union of provider/count queries as full replay, including provisional
branches; it never publishes those provisional writes. The next round resumes
the saved prefix against the enlarged sample cache. Completed earlier features
and their live-canopy effects do not need to be rebuilt. An unresolved initial
expansion still uses full replay because there is no valid ordered prefix yet.

Each replay accounts at most 64 MiB of retained prefix data across parallel
anchors. Accounting includes vector capacities, hash storage, nested candidate
paths and evaluation-cache keys, with allowance for table/allocation overhead.
It is not a process-RSS cap: active workers can transiently hold a working anchor
and its checkpoint, and normal output blocks/GPU resources are separate. When a
checkpoint does not fit, the anchor uses the original full replay with the same
sample results. Scheduling can affect which checkpoints fit, but cannot affect
queries or generated output. Reservations are released when their anchors resume.

Normal F3/FFI timing layout is unchanged. The opt-in planner report adds evaluated
candidates, resumed anchors/candidates, rejected checkpoints, and accounted peak
retention. These explain CPU work avoided without summing overlapping host,
worker and GPU times.

## Bounded measurements

M4 Max/Metal, two workers, height 384, seed 123456789. Each row uses a retained
real GPU field, one warmup pair, then five alternating full-replay/resume pairs.
The diagnostic full-replay reference uses the same implementation with prefix
retention disabled. Complete ordered placements are compared outside timing on
every iteration; a separate detailed plan must also match.

| Profile / chunk origin / field | Full replay median | Resume median | Full replay max | Resume max |
| --- | ---: | ---: | ---: | ---: |
| Terralith / (0,0) / 4×4 | 3.451 ms | 3.005 ms | 3.648 ms | 3.200 ms |
| Terralith / (0,0) / 8×8 | 7.928 ms | 7.158 ms | 8.683 ms | 7.470 ms |
| Vanilla / (0,0) / 4×4 | 2.779 ms | 2.756 ms | 2.932 ms | 2.912 ms |
| Terralith / (-32,-32) / 8×8 | 6.182 ms | 5.962 ms | 6.306 ms | 6.241 ms |

The two Terralith origin samples reduced planner latency by 12.9% and 9.7%.
They reused 3,894 and 10,288 completed candidate evaluations across 8 and 21
retried anchors, with accounted peaks of 162 and 334 KiB. The other two fields
needed no retries and retained no checkpoints; their small timing differences
are not evidence of a speedup from resuming. Whole process maximum RSS ranged
from 121 to 362 MiB, including GPU setup, field data and both paths' reference
outputs. These are not paired memory measurements, do not include all device
allocations, and do not establish stable p95 or whole-region throughput gains.

The separate retained pre-change library check compares final block and column
bytes for eight uncached chunks per vanilla/Terralith profile, including two
concurrent callers. Both profiles match. This checks the integrated generation
path; it is not a region-save, lighting, or publication benchmark.

Raw samples, library/profile hashes and output signatures are in
[`benchmarks/decoration-anchor-resume.json`](benchmarks/decoration-anchor-resume.json).

## Correctness checks

All 68 native unit tests pass. New tests compare full replay and resumed replay
at zero, negative and distant origins with several seeds. They compare every
placement and every sorted GPU query batch over multiple rounds, including:

- trees/leaf distances and paired plants before the first missing provider;
- a nested sequence that writes, samples dual noise, overwrites the same block,
  then samples a provider chosen from that provisional material;
- provider-dependent query positions that require additional rounds;
- shared selector budgets and lazy per-floor counts against the live canopy;
- repeated overlay writes, heightmap restoration and equal-order queue entries;
- zero and partial retention budgets, including exact full-replay fallback.

Actual vanilla and Terralith GPU providers are also exercised by the A/B example
and retained-library checks. No changes were made to region publication, player
edit handling, compression, lighting, cache formats or the Java bridge.

Build the optional example with `--features decoration-replay-benchmark --example
decoration_replay_benchmark`. Arguments are `<profile.json> [side=4] [pairs=5]
[seed=123456789] [chunk_x=0] [chunk_z=0]`. Run it at reduced priority with two
workers, `RETINA_INTERPRETER_PRELOAD=0`, and a 30-second process-group deadline.
The example forces interpreter terrain so it does not start background shader
specialization. Builds used two jobs/codegen units and 60-second deadlines,
separately from runtime tests. Minecraft was not launched.

Compatible GPU query submission batching is covered in
[DECORATION_QUERY_BATCHES.md](DECORATION_QUERY_BATCHES.md). Worker scratch reuse
and the tree/leaf GPU investigation remain separate follow-up work.
