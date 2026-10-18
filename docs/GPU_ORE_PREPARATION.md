# GPU ore preparation

Regions of at least 4×4 chunks now upload compact seeds/endpoints for regular,
zero-discard ore veins. A fused compute pass expands spheres, performs the
original ordered containment pruning, and rasterizes masks. Smaller batches keep
the previous path. Exposure-sensitive and scattered recipes retain CPU traversal,
random draws and replacement ordering. Layout-1 profiles remain unchanged.

## Data flow and fidelity

Rust still selects attempts using registered count, rarity, height and biome rules,
including cave-biome membership and conservative rejection. Indexed collection
preserves anchor/recipe/attempt order. Each supported vein uploads a 48-byte
descriptor and 48-byte seed/endpoint record instead of CPU-built spheres. Endpoint
trigonometry stays in Rust; a 33,280-byte table of CPU interpolation/sine
coefficients is uploaded once per ore device for registered sizes 1–64.

The GPU uses exact two-word integer RNG operations. Integer implementations of
nearest/even finite-normal f32 addition/multiplication preserve center and radius
rounding. Ordered pruning uses ordinary arithmetic away from its decision boundary
and exact operations when the squared-radius/distance gap is within
`1e-5 * (radius_squared + distance + 1)`. This margin exceeds accumulated
rounding/contraction error in the short sum of squares. Early breaks are preserved.

Direct shader arithmetic was rejected: despite matching RNG, the initial probe
changed 11,246 centers, 7,944 radii and 890 pruning decisions. WGSL allows different
floating-point evaluation/rounding choices; see the
[numerical rules](https://www.w3.org/TR/WGSL/#floating-point-accuracy).

The bit helpers handle finite normal inputs/results and zero, not general NaNs,
infinities or subnormals. Seeded preparation is restricted to field extents within
±30,001,024 blocks and registered ore heights within ±1,000,000. Generated geometry
in this domain does not require subnormal arithmetic. Outside it, the existing
CPU-geometry/GPU-mask path remains in use.

Rust reserves conservative boxes using first/last interpolated centers and maximum
radius. The GPU returns tighter bounds in an eight-word header followed by the
existing occupancy/uncertainty masks. It also records the original survivor bitmap
for exact validation. No separate geometry readback or submission is needed.
Uncertain voxels lazily reconstruct original CPU spheres once per vein using
`OnceLock`; replacement and boundary repair then use the existing rules.

## Measurements

Apple M4 Max / Metal, two CPU workers, seed 123456789, height 384, retained vanilla
and Terralith profiles. Each fixture has two warmup pairs and seven measured pairs
with alternating order. Both paths include preparation, merge, GPU submission and
readback, and applying ores to real carved terrain. Base generation/cloning is
outside timing. Every pair compares every resulting block; first warmups also
check exact pruning/bounds and the normal `TerrainEngine::plan_ores` selector.

Final median milliseconds; tail is the maximum of seven samples:

| Profile / chunks | Baseline | Seeded | Reduction | Tail before → after |
| --- | ---: | ---: | ---: | ---: |
| Vanilla / 4×4 | 2.390 | 1.838 | 23.1% | 2.525 → 1.918 |
| Vanilla / 8×8 | 5.801 | 4.909 | 15.4% | 5.953 → 5.041 |
| Terralith / 4×4 | 1.564 | 1.510 | 3.5% | 1.684 → 1.582 |
| Terralith / 8×8 | 4.523 | 4.111 | 9.1% | 4.721 → 4.154 |

The earlier guarded-pruning run measured approximately 15% on both vanilla sizes,
4% on small Terralith and 13% on larger Terralith. Small Terralith gains are modest;
host load affects absolute times. These are complete **ore-stage** measurements,
not whole-region generation or chunks/sec gains. Device timings overlap host time.

The first integrated variant used exact bit operations for every pruning comparison
and regressed all four fixtures; it was revised before shipping. Rejected,
intermediate and final samples are retained in
[gpu-ore-preparation.json](benchmarks/gpu-ore-preparation.json), with initial float
rejection evidence in [gpu-ore-seed-probe.json](benchmarks/gpu-ore-seed-probe.json).

## Memory and transfers

Larger vanilla steady-state uploads drop from 1,052,608 to 507,264 bytes. Conservative
allocation increases readback from 584,088 to 1,251,944 bytes, including timestamps.
Shared reusable ore buffers plus coefficients retain 3,589,744 bytes after both
variants. Only 87 of 5,284 veins need CPU repair, retaining 78,640 bytes of sphere
capacity until batch drop. Larger Terralith retains 2,265,952 GPU-buffer bytes and
23,200 repair bytes for 32 veins. No queue or worker count grows.

Both modes share descriptor/geometry/output/readback allocations with active binding
lengths. Capacity for the largest batch remains until engine drop; CPU masks and
repairs are batch-owned. These are fixture measurements, not a fixed global ceiling.
Process RSS includes both variants, profiles, base blocks and driver. The time
wrapper's peak-footprint counter cannot establish GPU peak memory. Larger readback
is an explicit tradeoff included in the end-to-end measurement.

## Diagnostics and validation

`RETINA_ORE_DIAGNOSTICS=1` emits JSON per GPU plan: parallel preparation, merge,
lock wait, upload/encoding, wait/readback, output copy, device time and transfers.
Default operation avoids JSON formatting. F3's device row becomes **Ore preparation
/ masks**, retaining the timing slot, packet ABI, rolling mean/p95 and overlap label.

Local validation used two compiler jobs at nice 10, 60-second build deadlines and
30-second runtime deadlines with process-group termination:

- 71 native unit tests passed; 26 explicit GPU/diagnostic tests remain ignored by default.
- Expanded actual-GPU probe: 2,560 inputs, all 64 sizes, eight seeds, near/negative,
  precision-boundary and ±30-million coordinates. All 83,200 centers, radii and
  pruning decisions match. Another 2,192 arithmetic pairs cover signed zeros,
  cancellation, rounding and random normal values.
- Two actual-GPU mask fixtures each check 26 chunks, replacement chains,
  exposure/scattered recipes, far coordinates, buffer reuse and forced CPU repair.
  Exact seeded bounds and survivor bits match CPU results.
- Retained-profile A/B checks cover the production selector and final ore blocks.
- Java `generationMetricsTest` and the release native library build pass.
  No Minecraft or full-region stress benchmark was run.

Reproduce with the release native test binary: select `seed_geometry_probe` with
`RETINA_ORE_ASSISTED=1`, `real_gpu_` mask checks, or
`seeded_ore_planning_comparison` with `RETINA_ORE_PROFILE` pointing at a retained
profile. Use `--ignored --nocapture` and the bounds above. Evidence JSON retains
profile hashes and samples. Test-only shader directory overrides support iteration;
production embeds the shader sources.
