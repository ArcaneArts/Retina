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

Thirteen native unit tests passed, including unculled-versus-culled final block
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

## Bounded GPU submission/readback ring

The device owner submits up to two batches before waiting on the oldest fence.
Each slot owns its column/mask readback buffers, query set and timestamp resolve
buffers. GPU scratch buffers can remain shared because queue submissions order
their reads/writes and copy results into independent slots before reuse. Mapping
guards own cancellation/unmap cleanup; failed maps do not attempt to unmap idle
buffers. A region response transfers its vector directly instead of copying it
again to split a one-job batch.

`gpu_pipeline_benchmark` measures the field and readback path with two callers,
excluding output hashing. On twelve full vanilla GPU regions, one slot took
383.90 ms (31.26 regions/s) and two took 362.35 ms (33.12 regions/s), about 6%
more throughput. Device gaps dropped from 20.95 to 1.91 ms; device spans were
362.31 and 359.45 ms. Both output signatures were `faf7a6e57fe14512`. Gaps include
unmeasured copies/commands and are not a pure idle counter. Unavailable/stale Metal
timestamp pairs are reported separately and excluded from gap arithmetic.

Complete native generation sampled 8,132 chunks/s serially after this change;
the first two-caller run reached 10,176 chunks/s, versus a matched preceding
query-only repeat at 9,805 chunks/s. Whole-generator differences vary with load;
the focused result isolates the ring's effect. Serial/concurrent full-region
checks match 40,960 decompressed NBT records against the query-only baseline.

Fourteen native tests and two real GPU tests passed, including mapping
cancellation/failure/reuse and byte-for-byte one-slot/two-slot fields with changing
mask sizes. `./gradlew build gpuTest regionTest biomeTest geologyTest featureTest
previewTest datapackTest structureTest` passed on Metal, including Terralith,
temporary promotion and the actual timing packet. Packaged native bytes match the
release library. Vulkan/DX12 runtime behavior still needs hardware testing.

Reproduce the focused comparison with:

```sh
cargo build --manifest-path native/Cargo.toml --release --locked \
  --target-dir build/native-target --example gpu_pipeline_benchmark
build/native-target/release/examples/gpu_pipeline_benchmark build/structure-profile.json 1 12
build/native-target/release/examples/gpu_pipeline_benchmark build/structure-profile.json 2 12
```

Run measurements sequentially. `ring-final-parallel` and the first focused repeat
files overlap another benchmark and are excluded from performance conclusions;
`ring-final-parallel-clean` is the independent replacement. The original focused
one/two-slot comparison above was sequential.
