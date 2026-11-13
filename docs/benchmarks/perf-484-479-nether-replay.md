# Nether ordered replay at low parallelism

The baseline is `26e1a4ca4`. Every generation run used an Apple M4 Max on AC power with normal power mode, one headless region caller, four assembly workers, and `RAYON_NUM_THREADS=2`. Each process wrote complete lit zlib MCA regions to a fresh output directory. Coordinates start at `(-1, 0)` and include adjacent regions across zero. The [measurement receipts](perf-484-479-nether-replay.json) contain the fixture and library hashes, host conditions, coordinates, counters, and exact comparisons. The [investigation](gpu-rust-investigation-2026-11-13.md) describes how to recreate the definitions.

Two isolated changes used only immutable writer outputs published at the planner's existing root barrier.

- **Direct writer indexing.** A joined root already retains a rectangular array of target anchors in Z-then-X order. The candidate looks up those outputs by their coordinates before taking the existing map path for exterior or unfinished anchors. This bypassed 2,472,192 of 9,001,116 ready-writer map lookups in the two-region trace. The 6,226,563 column builds and 5,342,476 prefix evaluations were unchanged.
- **Empty structure output.** A structure root is bounded by its own anchor chunk. When that root and its empty writer output are already published, an unlimited boundary-column read can use the preceding prefix directly. The candidate retains the original path for unknown anchors, roots with writes, and limited reads. In the two-region diagnostic, this proof skipped 1,257,905 reads and removed 785,466 builds and prefix evaluations. Neighbor archive hits were unchanged. The archive exported 6.2 MB more state, consistent with making some earlier prefixes directly visible to later reads.

The three-region identical-binary control varied from 232 to 439 chunks/s, with 2.97 to 3.08 seconds of process CPU per region. Process CPU measures all native and driver host threads during the region batch. It does not include device execution. The two-region diagnostic runs enabled the native counters separately from A/B timing.

| Metal timing run | Regions per process | Baseline → candidate process CPU, ms/region | Baseline → candidate chunks/s |
| --- | ---: | ---: | ---: |
| Direct writer indexing | 6 | 3,274 → 3,231 | 421 → 471 |
| Direct writer indexing, repeat | 12 | 2,776 → 2,844 | 531 → 532 |
| Empty structure output | 6 | 3,116 → 3,085 | 532 → 505 |
| Empty structure output, repeat | 12 | 2,802 → 2,823 | 445 → 473 |

Rows are medians of two processes per variant in A/B/B/A order. Neither change reduced process CPU or improved wall throughput in both batch sizes. Both were discarded. The two candidates matched 77,824 decompressed chunk NBT records across their diagnostic and timing runs, with identical MCA membership.

The next experiment should attribute the remaining boundary builds by exact writer set and allocation lifetime. A proof that avoids fewer allocations but adds another map access to each read can cost as much as it saves. Prior large-boundary proofs showed this tradeoff across seeds, and the empty-structure probe reproduces it at the current worker budget.

## Correctness follow-up

The empty-structure candidate matched another 2,048 modern Nether chunks on CPU and 2,048 chunks on Metal with the saved `bounds--64-384-0-128` custom definition. The custom case exercises the storage/generation geometry and is lighter than the registry-heavy vanilla fixture. The first region in each run preserved all 1,024 records after closing and reopening the world definition. In a separate repair test on each backend, both builds regenerated slot 528 with identical NBT and preserved all 1,023 preexisting records. A first attempt to prepare the Metal repair source used shader-ready with zero warmups and exited at status 0 before any generation. The repair check used driver-warm to start actual generation.

The CPU pair measured 23.48 versus 27.66 chunks/s and 83.19 versus 82.95 seconds of process CPU per region. This was a two-region correctness check with one process per variant. It supplies no repeatability evidence for the wall-time change.
