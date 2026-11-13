# GPU interpreter spline selection

Retina now binary-searches long, nondecreasing spline knot tables in the GPU
registry interpreter. The native registry upload verifies ordering once. Short
and unsorted tables use the existing scan. The binary search chooses the last
knot that passes the original comparison, including ties, and keeps knot zero
when no comparison passes. Evaluation still uses the same interpolation and
extrapolation arithmetic. No saved definition or GPU buffer grows.

## Reproduce

Use the 768-block Iris definition from the [investigation](gpu-rust-investigation-2026-11-13.md),
SHA-256 `96a1cc33d8dc6dda511be96a62dec6d915063504648745867242e4344dc8f527`.
Build baseline and candidate with the locked release profile before measuring.
Run independent processes on an Apple M4 Max, alternating A/B/B/A.

```sh
RAYON_NUM_THREADS=2 python3 scripts/native-region-benchmark.py \
  --library /absolute/path/to/libretina_worldgen.dylib \
  --definition /absolute/path/to/tall.dat --dimension minecraft:overworld \
  --backend metal --program-execution interpreter --readiness driver-warm \
  --parallel 1 --assembly-threads 4 --origin-x -1 --origin-z 0 \
  --warmups 1 --count 4 --scope lit-mca --lighting enabled \
  --mca-compression zlib --out /absolute/path/to/fresh-output
```

Use `--compare` with the first process's output for byte-exact decompressed NBT
and MCA membership. In separate diagnostic runs, `--gpu-interval --diagnostics`
captures valid Metal timestamps and transfer lengths. The figures in the
[receipts](perf-484-486-iris-interpreter.json) include the host's load, power mode,
and OS thermal observations.

## Attribution

The loaded Iris definition contains 9,788 eligible spline instructions. Over
one evaluation of every eligible instruction, the original scan makes 784,165
knot comparisons. The binary search makes at most 64,561. This is a graph
inventory, since different kernels reach the graphs at different frequencies.
The actual per-region effect is established with Metal timestamp pairs.

The short identical-binary controls ranged from 70.17 to 75.39 chunks/s.
The instrumented two-region pair moved from 71.63 to 102.11 chunks/s, and
cave-node device time fell from 4.50 to 2.91 s per region. Material counting
and emission stayed near 2.7 s/region. Host GPU wait fell from 13.71 to 9.51
s/region. Measured upload and readback lengths stayed exactly 115,226,070 and
137,491,168 bytes per region. The shader uses the same registry buffer and
live-value capacity, adds scalar search state, and allocates no extra GPU
scratch buffer. The benchmark does not measure hardware register occupancy.

The shorter uninstrumented A/B/B/A moved from a median 72.60 to 104.47
chunks/s. After the Mac restarted during the original screen, both libraries
were rebuilt and every headline pair was rerun with the same frozen inputs.

The four-region repeat confirmed 72.16 to 102.52 chunks/s (+42.1%). Median
cave-node device time fell from 4.39 to 2.88 s/region (−34.4%), process CPU fell
from 1.55 to 1.34 s/region (−13.4%), and the host GPU wait fell from 13.32 to
9.28 s/region. Material device time remained close at 2.62 to 2.66 s/region.
Every repeated candidate matched the reference in all 4,096 chunk records.
Retained buffers and transfer sizes did not grow. No Vulkan or DX12 timings
were collected.

The one-region Iris lighting audit checked all 1,024 chunk statuses. Both
libraries saved lighting for the 900 interior chunks and left 124 outer-ring
chunks for Minecraft to light. There were 25,764 saved sky-light arrays and
917 block-light arrays on each side. The complete NBT was byte-identical.

The modern and 1.20.1 guards each matched 2,048 chunks and reopened the first
region with all 1,024 records intact. The first short legacy pair had
conflicting wall and device timings. The three-region guard repeats matched
all 3,072 chunks per candidate process. Median legacy throughput moved from
156.55 to 165.13 chunks/s and CPU from 6.23 to 5.98 s/region. Modern moved
from 1,067.63 to 1,144.72 chunks/s with comparable process CPU. These guards
check for a regression, and the tall fixture supplies the throughput claim.

The first native test build exposed seven stale test calls to the tree
generator after its rooted-azalea change. The test calls now supply an empty
tree-site argument. The release binaries used for measurement were built
without these test modules.
