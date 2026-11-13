# Shared lighting halo material

The Metal path stages mixed material sections shared by more than one tile
once per region. A region-owned buffer has an immutable halo and a prefix
for tile-local sections and descriptors. The descriptors use the same
absolute word offsets the existing shader reads. Tile submissions update
only the prefix in queue order. Each overlapping region owns its backing.
Dense input and compact input beyond the actual buffer or binding limit
continue through the tile transport. Vulkan and DX12 input policy is unchanged.

## Reproduce

Use the 26.3 normal seed 12345 definition, SHA-256
`bcce2aa8b88499fcaddbbc2ecffb7fc45806608ced4cc61ad154a0582aef4a68`,
and locked release libraries. The main repeat used one caller, four
assembly workers, Rayon two and independent processes.

```sh
RAYON_NUM_THREADS=2 python3 scripts/native-region-benchmark.py \
  --library /absolute/path/to/libretina_worldgen.dylib \
  --definition /absolute/path/to/modern.dat --dimension minecraft:overworld \
  --backend metal --parallel 1 --assembly-threads 4 --readiness shader-ready \
  --origin-x -1 --origin-z 0 --grid-width 3 --warmups 2 --count 8 \
  --scope lit-mca --lighting enabled --mca-compression zlib \
  --out /absolute/path/to/fresh-output
```

Use `--compare` for strict MCA membership and decompressed NBT parity.
Use `--diagnostics --gpu-interval` in separate processes for host packing,
submission, transfer and device timing. Setting
`RETINA_LIGHTING_SHARED_INPUT=0` selects the previous tile-local transport.
The [receipts](perf-484-482-lighting-halos.json) include fixture and library
hashes, generation-window RSS samples summarized at 250 ms intervals, and
host observations.

## Results

The first prototype shared all mixed sections within the region. The
selected variant shares only sections needed by multiple tiles. Both
reduce the same transfer bytes in these regions. The previous compact
tile input buffer reserved 16 MiB in the instrumented same-binary control.
Other lighting buffers, such as frontiers and readbacks, are separate.

| Three-region Metal attribution, per region | Overworld | Nether |
| --- | ---: | ---: |
| Material upload, baseline → shared | 107.75 → 84.94 MB | 73.85 → 58.46 MB |
| Total native upload, baseline → shared | 115.31 → 92.51 MB | 73.91 → 58.52 MB |
| Entire-region backing → halo-only backing | 84.85 → 29.30 MB | 58.39 → 19.99 MB |
| Whole-region pack + submit, tile control → shared | 25.29 → 15.52 ms | 17.55 → 12.44 ms |
| Halo-only pack + submit | 20.91 ms | 12.54 ms |

In the 12-region reverse-order same-binary repeat, the whole-region
variant moved Overworld lighting-host time from 37.14 to 29.17 ms per
region and throughput from 2,363 to 2,663 chunks/s. Peak process RSS rose
46 MiB. Nether throughput was 1,034 to 1,049 chunks/s with a 37 MiB rise
in peak RSS.

The next eight-region run interleaved baseline, whole-region, halo-only,
halo-only, whole-region and baseline. The accepted halo-only variant
preserved the Overworld lighting-host improvement with a smaller
logical device buffer. The Nether total rates were close, with
substantial host load and planning differences within the cohort.

| Metal workload | Chunks/s | Process CPU ms/region | Lighting host ms/region | Peak RSS MiB |
| --- | ---: | ---: | ---: | ---: |
| Overworld baseline | 2,573 | 834 | 34.54 | 1,505 |
| Overworld whole-region | 2,856 | 712 | 26.25 | 1,530 |
| Overworld halo-only | 3,048 | 661 | 26.03 | 1,531 |
| Nether baseline | 822 | 2,419 | 45.73 | 2,335 |
| Nether whole-region | 822 | 2,553 | 29.62 | 2,391 |
| Nether halo-only | 829 | 2,545 | 28.67 | 2,329 |

Overworld diagnostic wall throughput also moved in both directions and
its device times changed little. The supported gain is lower lighting
host work and fewer actual transfers, with higher whole-region rates
in the alternating Overworld repeats. Terrain planning times also
changed, so the entire wall-rate difference cannot be assigned to
lighting. The Overworld halo-only peak RSS was 26 MiB higher in the
eight-region repeat.

The final same-binary repeat again lowered Overworld lighting-host time
from 40.37 to 29.54 ms/region. It recorded 2,172 to 2,850 chunks/s and
1,516 to 1,535 MiB peak RSS. The shared runs individually recorded 3,504
and 2,197 chunks/s, which illustrates the variability of the full-region
rate. Nether ran at 1,151 to 1,163 chunks/s and 25.89 to 25.24 ms/region
in the lighting host stage. Its device propagation stayed near 28 ms.

The six-region instrumented two-caller guard showed lower throughput.
In a separate uninstrumented eight-region reverse-order repeat, the final
build moved from 3,556 to 3,600 chunks/s, from 48.34 to 37.46 ms/region of
lighting host time, and from 1,680 to 1,736 MiB peak RSS. The generation
window's sampled median RSS increased from 1,571 to 1,583 MiB. Each
process used the same four assembly workers.

## Parity and fallback

The guarded final release build matched all measured chunk NBT and MCA
slots for dense material input, the 768-block Iris fixture, Minecraft
1.20.1, central and outer End, and concurrent callers. The guards verified
the 900 lit interior and 124 unlit outer chunks in each region. Reopening
each definition preserved all 1,024 records in the first region. Central
End had no repeated mixed sections and used the tile transport. The
outer End and Iris guards used a shared backing.

A Metal test requested a real 128 KiB storage-binding limit, confirmed
that a larger halo returns the tile path without changing descriptors,
and compared a valid shared volume with the tile output byte for byte.
The existing interleaved slot and abandonment check and all three
material transport tests also passed.
