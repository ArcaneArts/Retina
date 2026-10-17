# Bend lighting work reduction

Inspected baseline: `e6f1631` (2026-10-17). The retained complete-region samples
put lighting at 910.24 seconds, or 67.44% of two warm-region latencies. These are
host spans from a shared machine, not a controlled GPU profile. See
[the interrupted comparison](benchmarks/bend-region-interrupted.md).

This inspection did not invoke Bend, a native compiler, the GPU, or Minecraft.
The generic optimization scout mostly surfaced Java test loops, which do not
explain this native lighting cost. The findings below come from the actual
lighting implementation and existing measurements.

## Opportunities

| Opportunity | Evidence and hypothesis | Expected gain | Risk / complexity | Validation |
| --- | --- | --- | --- | --- |
| Skip classification and neighbor work that cannot contribute | `light_volume.bend::classify_at` originally scanned 125 neighbors even when the brick had no passable voxel. `spread_at` fetched all six neighbors before `spread_brick` checked for zero channels. Avoid those reads while retaining seeds in skipped bricks. | Medium; inactive-brick fraction determines the gain. | Low / low; emitted light must remain available to adjacent bricks. | Independent flood fill, including an opaque emitting brick and sealed cavities; later compare device timings. |
| Read scalar metadata and reuse destination attenuation | `optics` and `brick` return records from shared trees. Classification only needs flags; opacity and face checks only need scalar fields. Each passable voxel also fetched its material twice and its opacity once for passability plus once per incoming edge. | Potentially high; reduces tree lookups and avoids ownership of metadata records. No speedup is measured yet. | Low / medium; preserve default values, face orientation and borrow behavior. | All registered optics, partial faces, fluids, emissions and brick boundaries; inspect generated code and then measure a bounded GPU fixture. |
| Build optical bricks by walking column runs once | `block_cells` invokes `MC.material_at` for each voxel; that function scans the run list from its head. Run-heavy carved/ore columns repeat the scan at every height. | High if initialization dominates; its share is not separately measured. | Medium / high; preserve run ordering, padding, missing material behavior and direct sunlight ceilings. | Compare seeded materials and light bytes before propagation on real columns and pathological run layouts. |
| Replace fixed work with a changing-light frontier | `iterations` makes fourteen complete table traversals. Active bricks remain active even after their light values settle. | Potentially high in sparse scenes; uncertain after scheduling/compaction costs. | High / high; a brick that is unchanged now can still receive light later. | Compare all intermediate rounds to an independent reference, then bounded sparse/dense GPU fixtures. |

## Current candidate

The focused source change addresses the first two opportunities:

- Classification returns zero channels immediately for fully opaque bricks.
  The brick and its source light remain intact for neighboring readers.
- Classification reads only scalar brick flags and checks the existing distance
  bound before accessing a neighbor's table entry.
- Propagation reads neighbors only for bricks with enabled channels.
- Optical traits and face IDs are read directly as scalars. Destination material
  and attenuation are computed once per voxel and reused for all six edges.

The same fourteen synchronous passes, halo, face-pair coverage, opacity,
emission, skylight seeds, packed layout, and output interfaces remain in use.
The candidate does not change how many chunks are marked lit or add a save.

For a 384-block world, the current 25 tiles each include a 128×128×416 volume:
332,800 bricks in total. The source previously requested 41,600,000 candidate
neighbor checks for classification and up to 27,955,200 neighbor-brick reads
across the fourteen propagation rounds, including inactive bricks. The existing
distance bound admits 81 of each 125 offsets before tile-boundary clipping.
These are source-derived work counts, not measured device instructions. The
candidate removes the excluded reads, skips opaque classification, and avoids
inactive propagation neighbors; it does not claim a measured speedup.

## Validation status and next run

**The changed Bend source passes the compiled CPU correctness check.** The
explicitly approved run completed in 11.61 seconds including C emission, one
native compilation and all eleven fixtures, within its 30-second deadline.
Both packed light channels match the independent queue-based flood fill across
485,376 voxels. The added fixture contains an entirely opaque 8³ brick with an
emitting block on its boundary; the suite also covers open sky, dark air, roofs,
fluids/leaves, partial shapes, registered optics, virtual sections and brick
boundaries. Python syntax, CLI help and source-hash verification also pass.

[The retained report](benchmarks/bend-lighting-work-cpu.json) records the tested
source commit, compiler/binary/source/profile hashes, commands, per-fixture
output hashes and supervisor result. Logs, emitted C, binary and fixture outputs
are retained outside the worktree at the report's `retained_artifacts` path.
These incidental correctness-run times are not a paired performance comparison.

`scripts/check_bend_lighting.py` prepares an explicitly invoked CPU check with
a 30-second total deadline and up to one second for termination. It emits stock
Bend C, compiles one native fixture binary, then compares two-thread CPU output
to the independent flood fill. Direct native compilation avoids the Bend CLI's
automatic GPU archive build. All subprocesses run at reduced priority. There
is no automatic retry, GPU execution, terrain engine or game launch. The approved
CPU run is complete; additional native work requires separate approval under
the current resource limit.

A separately approved bounded GPU check is still needed to validate device
execution and measure this candidate. Do not restart the multi-minute
complete-region benchmark to decide whether these
local changes are promising. Full-region and complete-backend acceptance remain
outstanding in [the parity inventory](BEND_IMPLEMENTATION.md).
