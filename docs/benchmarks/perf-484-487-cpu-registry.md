# Prepared CPU material rules

The CPU prepares supported material graphs as short-circuit branches with
constant material leaves. This avoids the generic dependency stack and
frame for those evaluations. Preparation checks all six outputs,
including implicit node zero. It preserves predicate order, arithmetic,
seed and program identity. Unknown nodes and graphs that exceed the
preparation budget use the existing evaluator. The CPU material-zero proof
and the GPU interpreter are separate paths.

## Reproduce

Use the modern normal definition at seed 12345, SHA-256
`bcce2aa8b88499fcaddbbc2ecffb7fc45806608ced4cc61ad154a0582aef4a68`.
Build independent locked release libraries. Use one caller and four native
CPU workers, split into one dependency/assembly worker and three terrain
workers, with global Rayon set to two.

```sh
RAYON_NUM_THREADS=2 python3 scripts/native-region-benchmark.py \
  --library /absolute/path/to/libretina_worldgen.dylib \
  --definition /absolute/path/to/modern.dat --dimension minecraft:the_nether \
  --backend cpu --parallel 1 --assembly-threads 4 \
  --origin-x -1 --origin-z 0 --grid-width 3 --warmups 1 --count 4 \
  --scope lit-mca --lighting enabled --mca-compression zlib \
  --out /absolute/path/to/fresh-output
```

Alternate A/B/B/A in fresh processes and use `--compare` to verify strict
MCA membership and decompressed NBT. Use a separate `--diagnostics`
capture for node attribution. [Run receipts](perf-484-487-cpu-registry.json)
record exact settings, host conditions, and corpus hashes.

## Attribution

The unmodified CPU evaluator ran about 246.6 million registry calls for
one complete Nether region and its generated neighbors. Of the roughly
8.15 billion stack visits, 7.01 billion (86.0%) came from material rules,
1.05 billion from the three terrain programs, and 85 million from nested
interpolation fields. The same Overworld capture had 670 million material,
620 million terrain, 606 million field, and 16 million auxiliary visits.

The five Nether material graphs have 39–54 nodes each, with duplicate
material roots and implicit node-zero outputs. Of their 230 instructions,
113 are short-circuit AND or OR. Their branch path handled all 173.66
million material calls in the measured region. It preserves the exact
seeded vertical chances and shares their predicate implementation with
the reference evaluator. The initial instrumented comparison finished
the region in 16.1 seconds versus 27.5 seconds and matched 1,024 records.
The final capture replaced 7.01 billion material stack visits with
1.06 billion prepared branch steps for the same 173.66 million calls.
The steps and visits represent different operations. Total registry
noise calls stayed near 27.9 million, and the final capture again
matched every chunk.

## Full-region results

| Uninstrumented CPU Nether | Chunks/s A → B | Wall s/region A → B | Process CPU s/region A → B | Peak RSS MiB A → B |
| --- | ---: | ---: | ---: | ---: |
| Two-region screen | 41.64 → 57.99 | 24.73 → 17.81 | 63.78 → 41.49 | 1,555 → 1,575 |
| Four-region repeat | 38.30 → 49.47 | 26.89 → 20.71 | 68.88 → 45.19 | 1,764 → 1,761 |

Both candidate processes beat both control processes in each repeat.
The four-region wall time fell 23.0% and the process CPU fell 34.4%.
Every candidate record matched its reference. The original investigation's
52.6-second region was recorded under a different host load. The matched
control here was 26.89 seconds. The prepared path approaches 15 seconds
in the short run, with a longer-repeat median of 20.71 seconds.

The locked release build and 19 CPU evaluator tests passed. The
reference checks exercise all registered opcodes, original lazy
and eager scheduling, real vanilla graphs, signed-zero outputs,
implicit roots, unsupported graph fallback and scratch reuse.

The modern vanilla and Terralith Overworld guards each matched 1,024
decompressed chunk records and all slots, including lighting arrays.
Each reference and candidate reopened its definition and preserved the
first region. The candidate recorded 106 versus 99 chunks/s on vanilla
and 87 versus 84 on Terralith. These single-region guards check output
and provide descriptive timings. The Terralith saved definition SHA-256
is `f8bef017cbb08e030768973cc439d79c29409a300b3ba39f60ff2a2f1aabc679`.
