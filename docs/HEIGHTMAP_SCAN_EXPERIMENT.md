# Final heightmap scan experiment

The final NBT heightmap encoder already skips empty sections, tracks the six
unfinished predicates per column and exits when every column is complete. An
experiment replaced its repeated 256-column row loop with four `u64` bitsets of
unfinished columns. This avoided checking completed columns but introduced bit
enumeration and bookkeeping in the remaining inner loop. The experiment was
reverted because it did not demonstrate a consistent region-throughput benefit.

The baseline is commit `40c066a`. Both release libraries received the same full
vanilla/Terralith profiles: 159 / 413 decoration recipes, all loaded structure
templates, caves, ores and layered materials. Metal / Apple M4 Max, seed
123456789, twenty adjacent full regions per run, including negative coordinates.
First runs used two warmups, repeats used five. Baseline/candidate order was
reversed in the repeats. No build, test or other benchmark ran concurrently.

| Profile / run | Baseline mean ms | Bitset mean ms | Baseline median ms | Bitset median ms | Baseline native chunks/sec | Bitset native chunks/sec |
| --- | ---: | ---: | ---: | ---: | ---: | ---: |
| Vanilla / first | 131.50 | 127.28 | 129.09 | 126.59 | 7,774 | 8,031 |
| Vanilla / repeat | 131.69 | 128.82 | 127.09 | 126.85 | 7,761 | 7,935 |
| Terralith / first | 154.81 | 156.51 | 152.83 | 154.88 | 6,604 | 6,533 |
| Terralith / repeat | 153.87 | 151.80 | 152.46 | 152.91 | 6,645 | 6,735 |

Vanilla mean latency improved 2–3%, but median improvement fell from 1.9% to
0.2% on the repeat. Terralith median latency was flat/slower in both runs, with
opposite mean/throughput changes between runs. Host load and pipeline overlap
vary; these results do not support retaining the change as a reliable speedup.

NBT worker counters did decrease: vanilla 0.06754 / 0.06774 to
0.06520 / 0.06462 ms per chunk; Terralith 0.07404 / 0.06909 to
0.06554 / 0.06483. These sum parallel elapsed work rather than region wall time.
Reducing that counter alone is insufficient evidence of faster generation.
The existing occupied-section and completed-column guards remain in production.

All candidate runs and baseline repeats match the first baseline's decompressed
NBT exactly: 122,880 chunk-record comparisons, including final heightmaps,
palettes, blocks and structures. File sizes and GPU transfer volumes are
unchanged apart from small timestamp readback accounting variations. The build,
31 native unit tests, real GPU tests, MCA metadata/decoding and DH cache/promotion
checks pass for the candidate. The production source and bundled native library
are restored to the baseline implementation after the experiment.

Raw profiles, retained release libraries, the candidate patch, startup/RSS/file/
transfer/stage measurements and validation log are under
`build/goal-baseline/heightmap-active/`. Exact reproduction uses
`scripts/native-region-benchmark.py` with the retained libraries and the full
inputs in `build/goal-baseline/fallen-trees/{vanilla,terralith}.json`.
The existing measured cave-dressing bounds and other committed optimizations
remain in place; this experiment narrows the next scan work toward operations
with greater whole-region cost, rather than another completed-column loop.
