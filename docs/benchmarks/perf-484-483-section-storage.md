# Final material section storage

Whole-region assembly now converts final blocks to the section storage already
used by the End. A uniform section keeps one material ID. A mixed section
keeps its original 4,096 IDs. The full chunk vector returns to assembly
scratch before region lighting begins. Compaction runs after terrain,
decoration, structure, and storage-height changes. Lighting transport and MCA
encoding still consume the same section contents.

## Reproduce

Build the baseline and candidate with the locked release profile. Use the
26.3 normal snapshot at seed 12345, SHA-256
`bcce2aa8b88499fcaddbbc2ecffb7fc45806608ced4cc61ad154a0582aef4a68`.
Run independent processes in A/B/B/A order on the M4 Max.

```sh
RAYON_NUM_THREADS=2 python3 scripts/native-region-benchmark.py \
  --library /absolute/path/to/libretina_worldgen.dylib \
  --definition /absolute/path/to/modern.dat --dimension minecraft:overworld \
  --backend metal --parallel 1 --assembly-threads 4 --readiness shader-ready \
  --origin-x -1 --origin-z 0 --grid-width 3 --warmups 1 --count 8 \
  --scope lit-mca --lighting enabled --mca-compression zlib \
  --out /absolute/path/to/fresh-output
```

Use `--compare` with the first run's output and repeat with
`minecraft:the_nether`. Run the two-region `--diagnostics` attribution
separately. Set `RETINA_LIGHTING_INPUT=dense` for the dense input control.
The [receipts](perf-484-483-section-storage.json) record exact commands, power
mode, host load, thermal observations, and saved corpus hashes. RSS columns
use the process high-water mark through generation and the median of
process-resident samples taken every 250 ms during the measured window.

## Results

The short identical-binary controls ranged from 2,444 to 2,784 chunks/s.
The Overworld memory reduction repeated across both benchmark lengths.
Both candidate processes peaked below both baselines in each Overworld
comparison. Nether's smaller final block allocation had less effect on the
process working set.

| Workload | Chunks/s A → B | Process CPU ms/region A → B | Peak RSS MiB A → B | Sampled median RSS MiB A → B |
| --- | ---: | ---: | ---: | ---: |
| Overworld, 3 regions | 1,688 → 1,738 | 1,086 → 1,121 | 1,484 → 1,348 | 1,342 → 1,219 |
| Overworld, 8 regions | 2,827 → 2,805 | 700 → 766 | 1,640 → 1,499 | 1,488 → 1,394 |
| Nether, 3 regions | 964 → 959 | 2,238 → 2,250 | 1,963 → 1,958 | 1,778 → 1,753 |
| Nether, 8 regions | 962 → 975 | 2,210 → 2,211 | 2,295 → 2,269 | 1,986 → 1,980 |
| Overworld, dense input, 2 regions | 2,017 → 1,999 | 874 → 867 | 1,441 → 1,295 | 1,294 → 1,188 |
| Overworld, two callers, 6 regions | 2,741 → 2,505 | 932 → 1,021 | 1,872 → 1,644 | 1,712 → 1,523 |
| Overworld, two callers, 12 regions | 2,986 → 3,139 | 893 → 830 | 2,051 → 1,773 | 1,917 → 1,658 |
| Overworld, CPU, 2 regions | 103 → 102 | 23,577 → 23,913 | 1,096 → 1,047 | 1,014 → 939 |
| Iris, forced interpreter, 1 region | 95 → 102 | 1,366 → 799 | 3,194 → 2,817 | 2,940 → 2,628 |

In the instrumented screen, retained material bytes per region fell from
192 to 82 MiB in the Overworld and 128 to 55 MiB in the Nether, including
section descriptor storage. Worker time spent scanning and copying sections
was about 15 and 10 ms per region, respectively. Those times can overlap.
Packed lighting material upload bytes matched exactly on each side. The
retained payload counters and short timings came from the initial prototype.
The eight-region repeats used the final implementation with the obsolete
chunk-wide dense representation removed.

The Overworld eight-region wall rate differed by 0.8%. Its process CPU median
increased 9.4%, with individual baseline runs spanning 624–777 ms per region
and candidate runs spanning 751–781 ms. The short Overworld diagnostic pair
also ran slower with compaction amid changing host load. The evidence
supports a repeatable memory saving and leaves the precise CPU cost uncertain.
Nether showed no consistent throughput change.

The CPU one-region wall result changed substantially with host load,
mostly in terrain evaluation before compaction. The two-region A/B/B/A
had similar median rates and lower candidate RSS. The two-caller screen
and six-region A/B/B/A showed lower throughput with compaction. The
12-region reverse-order B/A/A/B did not reproduce that loss and peaked
278 MiB lower. The two-caller wall results span both directions, so the
accepted result is the repeated RSS saving. Local qualification covered
Metal and CPU. Vulkan and DX12 timing remain open.

## Parity

The short and long Metal comparisons matched every decompressed chunk record
and MCA slot. Dense input, the Iris Overworld, central and outer End, CPU
Overworld, and the separate two-caller Overworld guard matched as well.
Each guard reopened the first region with 1,024 preserved records. The
lighting audit checked every interior and outer-ring status and recorded
sky and block light section counts. Metal and CPU each repaired the same
missing Nether slot, yielding the same repaired chunk as their corresponding
reference build and preserving the other 1,023 records byte for byte.
The release build and all five material storage and transport tests passed.
