# RTX 3060 compilation baseline

The supplied `vulkan.txt` and `directx.txt` use an RTX 3060, NVIDIA 621.01,
an i7-6700K, and Retina 1.1.452. Both select the same generation policy.

| Completed observation | Vulkan | DirectX |
| --- | ---: | ---: |
| Native initialization | 0.145 s | 0.508 s |
| Required world interpreter | 95.332 s | 13.172 s |
| Required cave interpreter | 53.185 s | 7.637 s |
| Interpreter bundle | 148.560 s | 20.845 s |
| First complete region | 157.113 s | 36.672 s |
| Minecraft preparation | 162.109 s | 41.527 s |
| Initial four terrain job wait/readback mean | 3285.381 ms | 1402.351 ms |

Stages nest and overlap: these rows must not be added. Vulkan's bundle used
146.594 seconds of calling-thread CPU. Its two SPIR-V translations took 3 ms
each; driver pipeline creation dominates. Its application caches start cold,
and completed snapshots are persisted successfully. Neither excerpt includes
completed specialization, and later throughput reports do not include its
readiness state. Terrain wait/readback includes queue time, execution, host
readback and material continuation; it is not device execution time.

DirectX includes Chunky while Vulkan's startup does not. This causes live
registry export on DirectX (18.750 s) versus bundled definition materialization
on Vulkan (3.719 s). Matching generated-source lengths do not prove identical
definitions, seeds, binaries, or driver-cache state.

`scripts/startup_report.py` parses the original logs without summing nested
stages. `scripts/backend-startup-benchmark.py` runs independent headless processes
with a new private application-cache directory, then retains it across repeats.
It compares actual chunk NBT between processes. Driver caches remain uncontrolled.
The existing region benchmark records definition/library hashes, seed, coordinates,
readiness, CPU use and stage counters; use debug=false runs for headline throughput.

With debug enabled, `Region GPU timings` and `Region generation timings` contain
compact JSON records. They expose existing counters, including timestamps and
CPU planning/publication phases, without per-chunk logging or a new profiler.
The native-region record's worker shares use the same scaling as its established
profiled ABI. Device durations overlap host durations and zero is not a validity
signal. Diagnostic serialization and allocation occur only when debug is enabled.

Validation: 398 native unit tests passed (103 opt-in tests ignored), the release
library built successfully, three parser/portable-counter tests passed, and nine
existing benchmark-helper tests passed (one optional LZ4 test skipped). Two real
Metal processes generated two regions each; the second compared all 2,048 chunk
NBT records successfully. Both emitted two GPU and two complete-region JSON
records. This validates instrumentation and repeatability, not NVIDIA speed.
