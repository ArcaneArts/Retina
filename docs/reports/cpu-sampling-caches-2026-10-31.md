# CPU sampling caches

Date: 2026-10-31. Same optimized native CPU build settings, seed, saved definition
and one-region lit Nether diagnostic as the preceding
[program scheduling comparison](cpu-demand-evaluation-2026-10-31.md).
One caller, no warmups, opt-in diagnostics, Apple M4 Max/macOS ARM64. No client or
graphics resources. This table compares the final candidate followed by a fresh
run of the retained preceding build; only one sample of each was collected.

| Measurement | Previous build, repeat | Mixed sampling caches |
| --- | ---: | ---: |
| Complete region call, seconds | 58.364 | 38.065 |
| Process CPU seconds | 92.474 | 60.905 |
| Average scheduled cores | 1.584 | 1.600 |
| Summed mask/material-call seconds | 86.126 | 55.386 |
| Summed column-call seconds | 3.195 | 2.319 |
| Peak RSS, GiB | 1.583 | 1.533 |
| Saved chunks | 1,024 | 1,024 |

Both runs match all 1,024 decompressed chunk NBT records, with corpus SHA-256
`2d98866a9abf049e77c4bdd7a438dc93c0493bd40c5456392301a844b8dba225`.
Summed concurrent calls are not elapsed-time shares. A bounded diagnostic pair
does not establish production percentiles or uncontended throughput. Compared
with the original 132.055-second CPU baseline, the cumulative changes materially
reduce work, but low CPU parallelism remains a major gap.

The process sample showed substantial SipHash work in interpolation vertices,
density certificates and cavity caches. Extract the existing internal spatial
hasher to a common module, retaining its integer-placement behavior exactly.
Bounded CPU field/cave/program caches use a final avalanche to distribute the
long repeated mantissa-bit patterns in f32 coordinate keys. Caches retain normal
key equality, bounds and eviction, and never iterate to choose terrain. Registry
parsing and arbitrary text maps retain their existing hashing.

Two alternatives were measured and excluded. Extending horizontal caching to all
material programs reduced a column fixture from 660 to five noise calls, but its
region took 65.898 seconds versus 61.137 previously. Reusing the integer hasher
without final mixing reduced mask work but increased column work to 20.380 summed
seconds, producing no overall improvement (61.615 seconds). These observations
support retaining the existing cache scope and using mixed sampling keys.

Validation: 56 CPU-related checks, actual vanilla graphs and context fixtures,
deep dependency scheduling, globally aligned field/cavity overlap checks, a
163,840-value exact Metal comparison, and complete-region NBT equality. Tests
compile after rebasing over the latest main. Compact logs, samples, measurements,
the excluded experiment patches and native comparison images are retained in the
goal evidence directory. Disposable candidate regions are deleted after extraction.
