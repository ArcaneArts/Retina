# Coalesced driver-cache persistence

The prior path copied driver cache data, hashed it, wrote the envelope and
`fsync`ed a replacement after every completed pipeline. The new path keeps
snapshots on the serialized compiling lane, captures progress and milestone
checkpoints, and sends full snapshots to one bounded writer per lane.

The writer serializes atomic file replacement, retains one latest pending update
while a write runs, and drains on normal shutdown. Snapshotting never races with
pipeline creation. Foreground and specialization lanes still own distinct driver
caches. Disk I/O does not hold the driver/queue mutex. Each persisted snapshot
retains the existing 256 MiB limit; a lane may hold an active and pending snapshot,
plus a transient incoming snapshot while replacing the pending one.

## Component benchmark

Explicit native test `persistence_foreground_benchmark`, local M4 Max, 64 rapid
completed updates followed by a final milestone:

| Snapshot payload | Prior foreground work | New foreground work | Snapshots before → after |
| --- | ---: | ---: | ---: |
| 4 KiB | 341.681 ms | 0.032 ms | 64 → 9 |
| 1 MiB | 483.275 ms | 0.285 ms | 64 → 9 |
| 8 MiB | 1835.297 ms | 3.009 ms | 64 → 9 |

This models a driver snapshot with an owned byte-vector copy, then runs the real
checksum/envelope/atomic-file writer. It does **not** call `vkGetPipelineCacheData`,
measure Windows filesystems, or predict world-loading time. New foreground time
includes snapshot copies and enqueueing; persistence finishes on the worker and
is flushed/verified before the test passes. These are single local observations,
not a statistical performance guarantee.

Slow compiles still checkpoint on the next completion after two seconds. The
first completed pipeline, bundle/region tails, and shutdown preserve partial
progress. Forced termination may lose work since the previous checkpoint. A
shutdown concurrent with a driver compile flushes the prior completed checkpoint
instead of waiting for that compile.

Validation: 398 native unit tests pass, including a deliberately blocked writer,
100 coalesced replacements, concurrent producers, a failed write followed by
recovery, and final queue drain. Java core compilation and native CLI checks pass.
NVIDIA runtime results remain pending the Windows retest.

Reproduce:

```sh
cargo test --manifest-path native/Cargo.toml --lib persistence_foreground_benchmark \
  -- --ignored --nocapture
```
