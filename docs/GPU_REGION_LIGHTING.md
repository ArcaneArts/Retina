# GPU region lighting

Fresh complete Retina MCA regions precompute lighting for their interior 30×30
chunks: 900 of 1024 chunks (87.9%). The existing outer ring supplies neighboring
blocks and remains at the `features` status, so Minecraft lights it normally. No
extra terrain is generated beyond the requested 32×32 region.

Rust finishes terrain, ores, plants, structures and survival checks first. It then
uploads final material IDs to the GPU, computes block/sky light, encodes the light
arrays alongside the block data, compresses each chunk once and publishes one MCA
file atomically. Lighting does not add a second MCA rewrite.

Temporary DH regions use the same path. Promotion copies their compressed records,
including lighting, without running generation again. Existing chunks and player
edits are preserved. Partial repairs, legacy profiles without lighting metadata and
individual chunk mode retain Minecraft lighting. Worlds whose light-only padding
sections cannot fit Anvil's signed-byte section coordinates also retain normal
lighting.

## Loaded block properties

Java exports each final palette state's light emission, dampening and six
occlusion faces. Identical faces share indices; an exact paired-face occlusion
bitset comes from Minecraft's own `LightEngine` and `Shapes` helpers. These loaded
properties include modded/datapack-used blocks in the palette. Sky light follows
the actual dimension's `hasSkyLight` flag.

Metadata stays resident per native profile. The lighting pass transfers final
16-bit material IDs and returns one byte per voxel (block and sky nibbles). It does
not read noise data back for another CPU lighting calculation.

## GPU solve and Minecraft handoff

The compute shader seeds emissions and direct sky columns, then runs fourteen
ping-pong propagation passes over six neighbors with registered attenuation and
face occlusion. Light levels range from 0 to 15; every positive propagation path
from a seeded source spans at most fourteen edges. Separate compute passes provide
storage visibility. Packed integer operations avoid floating-point precision or
backend determinism issues.

The region is tiled into 10×10 output cores with a one-chunk halo under normal
storage-buffer limits. Smaller cores are selected from actual device limits when
necessary. The 16-block halo covers the maximum 14-block light influence. One
virtual-air section above and below the world preserves Minecraft's light-only
padding sections; these are not generated terrain.

Interior records contain `BlockLight` / `SkyLight` nibble arrays, `isLightOn=1` and
`Status=minecraft:light`. The outer ring has `isLightOn=0`, no saved light arrays and
`Status=minecraft:features`. Minecraft can import interior arrays and skip initial
propagation there. A targeted hook in its normal lighting task lets unlit ring
chunks pull existing neighbor light across shared faces. Later block updates,
torches and skylight changes use Minecraft's regular engine.

GPU failures propagate as actual generation errors. There is no CPU propagation
fallback masquerading as GPU work. For A/B measurements, setting
`RETINA_GPU_LIGHTING=0` before launch disables prelighting for newly generated
regions; it does not rewrite already saved chunks.

## Costs and timings

F3 includes a host row for lighting preparation/upload/GPU/readback and separate
hardware timestamp rows for sky/sources, propagation and packing. Device time
is contained in the host row and should not be added to it. Host time includes
waiting for the shared lighting mutex. These rows use the existing rolling region
average. The Java/native timing ABI is version 8 with 33 stages.

Lighting needs the region's final blocks available together. At a 384-block world
height, retaining those blocks uses about 192 MiB and retained interior light
results use about 91 MiB, in addition to existing generation fields and reusable
GPU tile buffers. Lighting submissions share the existing wgpu device/queue and
serialize through their own mutex; parallel Rust chunk assembly remains enabled.

This moves initial lighting work out of Minecraft's load path, which is especially
useful when generation happens ahead of loading through DH. It is not a guaranteed
net speedup for synchronous fresh terrain: the GPU solve and extra light NBT add
work before the MCA becomes available.

## Validation

Run `./gradlew lightingTest` on a machine with an actual supported GPU. The test
uses the production shader and Minecraft 26.3's real lighting engine:

- 1,769,472 block/sky comparisons across caves, water, leaves, emission and all
  loaded slab/stair/trapdoor states, including skyless volumes;
- mixed saved/unlit seams, torch placement/removal and skylight updates;
- actual MCA parsing, 900 lit / 124 unlit records, top/bottom light-only sections,
  negative region coordinates and 60,000 sampled imported-light comparisons;
- partial repair preserves player edits and delegates repaired-chunk lighting;
- rolling F3 percentages and an alternating lighting-phase load benchmark.

The small flat 64-height fixture reduced the measured Minecraft lighting phase
from about 28 ms to 15 ms per region in one local run, while GPU preparation took
about 53 ms. That demonstrates reduced loading work, not an end-to-end generation
speedup; the test prints current measurements on every run.

A separate replay of an actual 384-height region generated by the live Fabric
server measured about 481 ms for Minecraft propagation and 129 ms when importing
the interior light (about 73% less lighting-phase load time). Its 200,000 sampled
block/sky values matched recomputation. The server's four-region startup measured
about 159 ms of GPU device lighting and 294 ms of total lighting host time per
region, including concurrent queue/mutex waits and cold preparation. These are
separate measurements, not a matched end-to-end A/B claim. Hardware and other
applications affect these results. A subsequent busier startup run measured
about 225 ms device / 749 ms host per region, illustrating how queue contention
and CPU scheduling can change the generation cost.

To repeat the saved-region comparison, pass
`-PlightingRegionDirectory=<private complete region directory>` to `lightingTest`.
It reads `r.0.0.mca` at Overworld bounds (-64, 384), recomputes the outer ring and
benchmarks four alternating runs. Only use private test copies of saves.

For a fresh dedicated Retina world, `runServer -PretinaQa -PretinaTimingsQa
-PretinaLightingQa` exercises the actual Fabric seam hook and timing packet. Use
`-PretinaDatapackQa=<private run directory>` to isolate this smoke test from saves.
