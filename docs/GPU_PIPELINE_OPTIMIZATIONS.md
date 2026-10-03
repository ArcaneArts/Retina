# Generator optimization steps

## Shared jigsaw entries

Pool entries and accepted pieces share immutable `Arc<Element>` values. Weighted
shuffles borrow those handles, retaining the original expanded slot order,
Fisher–Yates draws and fallback order. Processor programs remain compiled once;
pool shuffles and piece copies no longer deep-copy their input JSON or part lists.

Apple M4 Max/Metal, release build, full vanilla `structure-profile.json`, seed
123456789, three warmups and 20 adjacent regions: the first candidate run reached
6,510 native chunks/s with 14.53 ms/region in structure planning. Two concurrent
callers reached 8,457 chunks/s, with 27.76 ms/region in that phase under contention.
The preceding review's serial baselines reached 3,806–4,360 chunks/s and
99–114 ms/region in structure planning. These are native measurements, excluding
Minecraft loading, lighting and rendering; system activity is uncontrolled.

Both serial and concurrent candidate runs matched all 40,960 decompressed chunk
NBT records against the retained baseline. Twelve native unit tests and the real
Metal parallel chunk/height-query test passed. A dedicated shuffle test verifies
shared identity, weighted order and subsequent random state.

Reproduce with `scripts/native-region-benchmark.py`, `--count 20 --warmups 3`,
then repeat with `--parallel 2`. Preserve each release library before rebuilding
and use `--compare` for behavior-preserving changes. Local results are in
`build/optimization-steps/`; they are intentionally not committed.

## Independent ore attempts and whole-vein culling

Ore layout 2 samples positions from the anchor/recipe stream and gives each vein
attempt its own geometry stream. Rejected attempts no longer shift later veins.
Bounds reject attempts outside the requested tile, vertical range, replacement
bands, or all possible terrain/fluid hosts before sphere construction. Sky culling
is disabled if any recipe can create materials from air, preserving replacement
chains across anchors. Bounds include scattered offsets and f32 rounding margins.
Vanilla/datapack registry exports identify the layout explicitly. Existing saved
chunks remain intact; new ore distributions change. Temporary MCA previews are
process-local and cannot survive a restart into the new layout.

On the original real GPU field, 178,753 of 279,773 eligible attempts were culled
(64%), leaving 7,808,716 ordered candidates. A complete 20-region run measured
34.84 ms/region in ore planning and 7,232 native chunks/s, versus 50.58 ms/region
and 6,510 chunks/s after the jigsaw change. System load varies; these are samples,
not a guaranteed speedup. The focused benchmark now reports culling counts.

Thirteen native unit tests passed, including uncullled-versus-culled final block
replay and individual-chunk/region parity for scattered and regular veins, air
hosts and water hosts. Actual vanilla registry geology, cave/exposure rules,
temporary MCA preview/promotion, and structure MCA/chunk parity suites passed.
GPU rasterization needs a compact per-vein output and stable replay order; simply
appending all 12-byte candidates would still transfer about 90 MiB on this field.

## Sparse structure queries

Cold surface biome probes run the registered climate lattice and biome selection
for one center point, without a density lattice, soil predicates, lakes or caves.
Underground probes preserve the original quart coordinates and lake-adjusted
ground height, then execute the same biome selector without a cavity volume.
Cached quart data includes halos. Projected height tiles preserve ground and
WORLD_SURFACE fluid/lake levels, skip soil material programs, and retain a separate
bounded height cache so incomplete material records cannot enter terrain generation.

The same workload reached 8,394 native chunks/s serially and 10,344 with two
callers; serial structure planning measured 9.36 ms/region. Both runs matched
40,960 decompressed chunk records against the ore-layout-2 baseline. Native unit,
actual Metal sparse/full query parity, and vanilla structure MCA/chunk tests
passed. Sparse tests cover legacy, explicit height and 3D density profiles,
unaligned vertical bounds, negative/distant coordinates, water-surface heights,
and cache/halo reuse without additional dispatches.
