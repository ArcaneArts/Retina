# CPU worker budget

Date: 2026-10-31. Same optimized native CPU settings, definition, seed and
one-region lit Nether corpus as the
[parallel tile comparison](cpu-tile-parallelism-2026-10-31.md). One caller, no
warmups, diagnostics enabled, Apple M4 Max/macOS ARM64. No client or GPU resources.
These bounded diagnostic observations do not establish production percentiles;
compilation/checks may overlap earlier runs.

A three-second process sample found 13 of the 16 shared assembly workers almost
entirely waiting on `OnceLock` terrain initialization. The region still matched
all 1,024 saved NBT records and took 22.217 seconds, consistent with the earlier
22.302-second sample. Terrain producers need workers independent of those waits.

Split one total budget into two global pools: floor(total/4), at least one, for
dependencies/assembly; the remainder for terrain. The total is clamped to 2–16,
using the existing environment control or available parallelism. Optional preview
material serialization uses the selected assembly pool too. GPU execution keeps
its existing pool. This avoids multiplying a full pool by region concurrency or
changing the terrain-cell publication/ownership protocol.

| Measurement | Shared pool, 4 | Split 1+3 | Shared pool, 16 | Split 4+12 |
| --- | ---: | ---: | ---: | ---: |
| Region call, seconds | 28.489 | 27.454 | 22.302 | 9.950 |
| Initial terrain phase, seconds | 2.560 | 3.273 | 1.214 | 1.169 |
| Process CPU seconds | 67.353 | 67.383 | 75.930 | 73.674 |
| Average scheduled cores | 2.364 | 2.454 | 3.404 | 7.403 |
| Peak RSS, GiB | 1.433 | 1.407 | 1.511 | 1.551 |

Both final split runs retain every decompressed chunk record, corpus SHA-256
`2d98866a9abf049e77c4bdd7a438dc93c0493bd40c5456392301a844b8dba225`.
They perform the same 10,851 dependency terrain chunks for 1,024 saved chunks.
An even budget split was also measured: 38.551 seconds at total4 and 13.780 at
total16. The terrain-heavy split avoids that small-budget regression.

Validation: 59 CPU-related checks, including total-budget bounds, terrain progress
while every dependency worker is blocked, one/four-worker packed-output equality,
global field overlap, actual registered graphs, 163,840 exact Metal comparisons,
and complete-region equality at both final budgets. Optimized native build passes.
Actual native startup counts identify the lanes; these are not GPU dispatches.

The original CPU diagnostic took 132.055 seconds and 211.032 process CPU seconds.
The cumulative changes reach 9.950 seconds and 73.674 CPU seconds in this bounded
diagnostic. Final acceptance still requires all-dimension measurements without
diagnostics, installed loaders, native platforms, actual no-GPU fallback and the
published universal artifact. Compact evidence is retained; disposable regions
are deleted after extracting reports and hashes.
