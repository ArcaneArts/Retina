# CPU demand-driven program evaluation

Date: 2026-10-31. Same optimized native CPU definition, machine, diagnostic
settings and one-region Nether corpus as the
[density certificate comparison](cpu-density-certificates-2026-10-31.md).
There are no GPU jobs or timestamps and no client was launched. Compilation and
another agent's benchmarks may overlap these diagnostic runs; final timing
confirmation remains separate from these optimization observations.

| Measurement | Density certificates | Demand-driven execution |
| --- | ---: | ---: |
| Complete lit region call, seconds | 119.320 | 61.137 |
| Process CPU seconds | 178.629 | 97.072 |
| Average scheduled cores | 1.497 | 1.588 |
| Summed mask/material-call seconds | 183.675 | 90.900 |
| Peak RSS, GiB | 1.553 | 1.617 |
| Saved chunks | 1,024 | 1,024 |

All 1,024 decompressed chunk NBT records are identical, with corpus SHA-256
`2d98866a9abf049e77c4bdd7a438dc93c0493bd40c5456392301a844b8dba225`.
The original eager CPU baseline before either optimization took 132.055 seconds
and 211.032 process CPU seconds. Summed concurrent/recursive call durations are
not wall-time shares, and a single region is not a percentile estimate.

The evaluator prepares immutable dependency lists/indexes once. Each sampling
frame reuses its value, stamp and explicit-stack buffers. Epoch stamps avoid
clearing the value array for each point. Range choices, boolean rules and spline
knots select only needed dependencies. Constant and horizontal-cache hits return
without walking child expressions. This changes scheduling, not opcode math or
the saved definition format. Test-only eager scheduling retains an independent
forward-order reference using the existing scalar/kernel implementations.

Validation compares all 47 opcodes against unfurled eager execution, variable
conditional/spline branches, all loaded Overworld/Nether vanilla programs across
near/far coordinates and material contexts, a 5,000-node variable graph, epoch
wrap and buffer reuse. The focused actual Metal fixture again matches 163,840
material/cavity/biome values. The full CPU region retains all saved records.
Mask/material work and low CPU parallelism still warrant further optimization;
the CPU horizontal-field plan currently excludes material programs, so reused
column noise is a concrete next candidate to measure separately.
