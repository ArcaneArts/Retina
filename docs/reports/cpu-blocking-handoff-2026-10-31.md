# CPU terrain handoff with small worker pools

The first packaged CPU acceptance runs stalled despite successful native builds
and local fixtures. Running the same assembled JAR with four global Rayon workers
reproduced the problem locally at zero CPU utilization. A native stack sample
showed dependency workers recursively waiting on `TerrainJob` cells while the
terrain lane was idle.

Rayon's cross-pool `install` can execute other work from the caller's pool while
waiting. A feature task initializing a terrain cell could therefore take another
feature task that needed the same unfinished cell, blocking its own initializer.
Increasing worker counts merely hid the scheduling problem.

CPU sampling now transfers an owned job to the bounded terrain lane and waits on
a channel without stealing caller work. Shared profiles and prepared fields remain
`Arc` references; only input batches are copied. Pure kernels retain cooperative
parallel execution within the terrain lane. Panics propagate back to the caller
and do not terminate the worker. Single-chunk dependency planning also uses the
same bounded dependency lane as region generation. GPU execution is unchanged.

Validation passed 62 CPU checks (five hardware-only checks ignored), including a
queued-task reentrancy regression, panic/error forwarding and worker reuse.
A JDK-only classpath fixture assembled from the actual CI JAR and candidate native
image completed both catalog snapshots with `RAYON_NUM_THREADS=2` and a four-worker
CPU budget. It retained the same 24 create/reopen chunk results, definition bytes,
biome/height queries, lighting and zero GPU jobs. This fixture is explicitly
separate from final published-artifact acceptance. CI now uses the same small
global-worker limit on all three platforms and in the driverless container.

A complete CPU Nether region also finished with two global Rayon workers and a
16-worker CPU budget: 15.700 seconds elapsed, 117.509 process CPU seconds and
1.556 GB generation peak RSS. All 1,024 reference NBT records remained identical.
This is a bounded observation under ordinary host activity, not an isolated
performance comparison. The disposable region was removed after validation.

Compact reproduction logs, the native stack sample and acceptance results are in
the CPU goal evidence directory `435af8f5-435b-4b07-88a3-ea8d36758d6e`.
