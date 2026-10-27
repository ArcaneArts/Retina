# End implementation

The End implementation uses Minecraft 26.3 as its independent reference. Supported
End generators now activate automatically through the saved definition, alongside
Overworld and Nether. Historical cycle sections below retain their original scope;
final shipping-default performance and deliverable verification are tracked separately.

## Automatic activation and existing saves

The adapter checks the actual loaded End graph, material rule and biome source.
Unsupported implementations retain their original generator. Fresh definitions
include all three supported vanilla dimensions without a special world type.

Older files carrying the explicit deferred-End reason receive a one-time upgrade.
Only those omitted entries are compiled, using the frozen seed and structures flag.
Existing supported dimensions, metadata and shared type indices stay unchanged. The
actual candidate is validated before atomic publication and an exact original backup
is retained. Subsequent reopen uses only the supported saved definition.

`endDefinitionUpgradeTest` checks real old/new files, frozen Overworld/Nether native
profiles and chunk NBT, fresh/upgraded End chunk NBT, unsupported actual graphs,
duplicate shared IDs, failed candidate publication and concurrent file changes.
The activation scenario also checks actual loader construction and saved edits.

## Cycle 1: compact vanilla references

`EndReferenceFixtures` loads the actual vanilla world registries and constructs
`NoiseBasedChunkGenerator`, `TheEndBiomeSource`, and `RandomState` directly. It does
not call Retina's density compiler, native library, or terrain generator.

Run the reference capture without building native resources:

```sh
./gradlew --no-daemon -PretinaHostOnly :common:endReferenceTest \
  -x :packageNative -x :buildNative
```

Output is `build/end/reference/source.json` and `reference.json`. The capture uses
seeds 123456789 and 42 and generates **zero full regions**. It records:

- 952 horizontal sample positions and 14,280 raw float density samples, including
  negative coordinates, cell/chunk/region edges, the central biome boundary, and
  coordinates outside f32's exact-integer range;
- all five biome identities for both seeds, 16 complete base-column occupancy
  samples, and all six source heightmap queries for those columns;
- 20 world-seeded spike descriptors, including guarded variants;
- five ordered biome feature roots with their loaded placement definitions;
- fourteen direct feature captures: small islands, chorus plants, both gateway
  configurations, arrival platforms, and active/inactive podium geometry;
- world-seeded city candidates and actual vanilla city assembly, including cities
  with and without ships and typed saved piece data.

Gateway captures include full block-entity data, including configured exits. Feature
origins cross chunk and region boundaries. Compound keys are sorted for hashing;
array/list order and typed NBT payloads are retained. No generated terrain or feature
data is normalized away.

The reference payload is about 2.1 MiB. Its SHA-256 on the initial completed capture
is `e2179090a9b6143fb2fec1b81ce036d7d6ae695d05dce30ee791d0d9a677f501`;
the loaded settings/dimension/biome-source hash is
`c71b8a4c57b19fd75356197fa8537d224e90ce8b5a5e343d4171c54d7f5c0d35`.

These captures establish the oracle, **not native End parity**. Pure feature geometry
uses a recording level; spike crystal spawning, marker-spawned city entities,
progression, portal travel, final lighting and actual saved-world behavior require
later native and live-world comparisons. City fixtures call vanilla's generation
context with the actual world seed, biome eligibility, and vanilla terrain queries;
they do not inject Retina's approximate structure selection seed.

## Bounded region campaigns

`scripts/end-region-benchmark.py` wraps the existing real native region benchmark.
Its default timing campaign uses three independently warmed four-region trials.
Supplying a baseline library selects four ABBA trials by default. Each process
records startup separately and includes full native generation, lighting, NBT,
compression, publication and generator cleanup in the existing measured scope.

Example, after an actual supported world definition has been compiled:

```sh
python3 scripts/end-region-benchmark.py \
  --library /absolute/path/libretina_worldgen.dylib \
  --library-revision BUILD_COMMIT \
  --definition /absolute/path/retina.dat --dimension minecraft:the_end \
  --category outer-islands --origin-x 4 --origin-z 4 \
  --out /absolute/path/new-campaign
```

The category is a label, not proof of terrain coverage: establish the category using
the actual generated terrain/features before using it for acceptance. The example
coordinates do not promise a particular category for arbitrary seeds/profiles.
To exercise eight concurrent callers, use at least eight timing regions per trial
(for example, `--callers 8 --regions 8 --trials 2`). The receipt records the maximum
possible active caller count separately from the requested executor size.

All measured decompressed chunk NBT is hashed. ABBA also compares decompressed
bytes directly to its first small baseline corpus. Successful trial MCA files are
deleted after comparison; the first ABBA corpus stays until its consumers finish.
Logs, commands, full measurements, manifests and disposal receipts remain. Failed
outputs remain available. Deletion uses only explicit numeric and warmup filenames
inside newly created trial directories; it never scans or removes older evidence.

The cleanup smoke test used the unchanged shipping host library against itself:
two one-region trials plus one warmup each. All 2,048 measured chunk records matched;
four newly generated MCA files totaling 32,571,392 bytes were removed. No MCA files
remain in the smoke campaign. Its two timing samples are harness validation, not
End performance results or a performance acceptance distribution.

`python3 -m unittest discover -s scripts -p 'test*benchmark*.py'` validates the MCA
comparison helpers and the cleanup ownership/evidence behavior. The optional LZ4
dependency case may skip when the codec packages are unavailable.

## Terrain cycle

### Cycle 2a: seeded island primitive

`native/src/end.rs` implements the 26.3 outer-island and central-island field and
the five-biome selector. It reuses the existing Java-compatible 48-bit random
generator without changing its behavior for existing callers. Island setup retains
all 17,292 discarded random draws, the six draws for zero-scale simplex offsets,
and the actual permutation shuffle. Simplex arithmetic remains f64; island sizes,
distance arithmetic and field outputs retain vanilla's f32 rounding. Island
coordinates use truncating division/remainder; biome chunk coordinates use floor.

Additional independent source capture:

```sh
./gradlew --no-daemon -PretinaHostOnly :common:endIslandReferenceTest \
  -x :packageNative -x :buildNative
cargo run --release --locked --manifest-path native/Cargo.toml \
  --example end_reference_compare -- build/end/island-reference.json
```

The example can also consume cycle 1's `reference.json`. It checks exact field
float bits and biome identities, plus raw outer-field and simplex seed-center bits
when present. The added capture has 1,792 points across seven seeds, including
negative seeds and both signed-64-bit extremes, with world-border and f32-boundary
coordinates. Its reference payload hash is
`c1534f951f8f3631835c84cfcbc8fa5a1e9d6ca52f79e8684e3fb84025dde192`.
Both corpora match: 952 original samples plus 1,792 expanded samples. A compact
37-point extract of the actual captures is checked into the ordinary Rust unit
suite to prevent regression; it retains both capture hashes and the exact bits.
The optimized native unit suite passes 301 tests with zero failures; 75 optional
fixtures remain ignored. These primitive comparisons do not initialize a GPU or
generate full regions.

This primitive is not yet wired into production generation. Blended noise,
density shaping/interpolation, native profile export, and complete floating-island
terrain remain in cycle 2. No End performance or automatic activation is claimed.

### Cycle 2b: exact blended noise and dense graph reference

`native/src/end/blended.rs` initializes the actual three smeared Perlin stacks
(16 lower, 16 upper, 8 main) from the world seed and registered noise parameters.
It retains the offsets, permutations, octave order/weights, coordinate wrapping,
vertical smear, and scalar/bulk arithmetic orders. Actual 26.3 bytecode for
`BitRandomSource.nextDouble` uses `l2f`, `fmul`, `f2d`: the result is float-rounded
before widening to double. End noise preserves that rounding locally. Existing
Overworld/Nether structure random behavior is unchanged.

The independent capture checks seven seeds and three parameter sets, including
codec-edge values and far coordinates. All **4,200 scalar** and **5,145 bulk**
values match native float bits exactly. The observed source scalar/bulk values
are identical at shared points; the harness records that observation rather than
requiring an assumed difference. Reference payload:
`6c95425895d2b88b954cfcb93b9bf0a2df8f416fe942c1c8fd59309d3b5bef9b`.

`native/src/end/density.rs` adds the dense reference for the vanilla End graph:
islands plus seeded noise, both vertical gradients, endpoint-preserving blends,
0.64 scaling, 8×4×8 interpolation and squeeze. Scalar queries use vanilla's scalar
interpolator; bulk columns use Z/X interpolation and accumulated Y steps. All
14,280 original scalar density bits match, as do the original sixteen columns.

The additional terrain reference uses **four complete chunks**, covering central
terrain, void and positive/negative outer-island coordinates over two seeds. All
131,072 bulk density float bits, 262,144 stored base-block decisions and 6,144
height queries match. The 128-block noise range leaves upper dimension storage air.
Reference payload:
`65d50e96f5d129b0809a7ba262c5f5586b2427d178d10259c8a35d26d65261c7`.

Reproduce the additional reference captures and comparisons:

```sh
./gradlew --no-daemon -PretinaHostOnly \
  :common:endBlendedReferenceTest :common:endTerrainReferenceTest \
  -x :packageNative -x :buildNative
cargo run --release --locked --manifest-path native/Cargo.toml \
  --target-dir build/native-target --example end_blended_compare \
  -- build/end/blended-reference.json
cargo run --release --locked --manifest-path native/Cargo.toml \
  --target-dir build/native-target --example end_density_compare \
  -- build/end/terrain-reference.json
```

Compact extracts keep 147 blended sample records, 120 scalar graph values and
sixteen columns in the normal native regression suite. The optimized unit suite
passes 304 tests with zero failures; 75 optional fixtures remain ignored. The
island comparisons still match after sharing permutation initialization.

These are CPU reference primitives and independent source comparisons. They do
not generate MCA files or initialize the GPU. Native profile/region integration,
GPU acceleration, features, structures, gameplay, automatic activation and full
End performance acceptance remain unfinished. `VanillaDensity` explicitly models
the vanilla graph; it is not a silent substitute for arbitrary custom graphs.

### Cycle 2c: shared dense terrain lattice

`VanillaDensity.tile` constructs each required 8×4×8 density corner once for a
rectangle of block columns. It also shares each horizontal island value across
all 33 vertical corners. `DenseTile` is immutable and can serve independent
columns from bounded generation workers without locks. Unaligned rectangles and
negative coordinates preserve the same corner alignment and accumulated-Y bulk
interpolation as the independently validated column reference.

An aligned 512×512-block rectangle stores 65×65×33 float values: **557,700 bytes**
of density payload. Interpolated block densities are returned one column at a
time rather than retained as a 128 MiB region-sized float array. No empty-space
classification or feature suppression occurs in this dense baseline.

The comparison example checks tiled columns directly against the independent
vanilla capture: all 131,072 density float bits and 262,144 stored block decisions
match across four chunks. All 14,280 original scalar samples and sixteen original
columns still match. The optimized native suite passes 306 tests with zero failures
(75 optional fixtures ignored). Tests cover unaligned tiles, negative/far
coordinates, edge exclusion and invalid dimensions.

A bounded density-stage probe is available:

```sh
cargo run --release --locked --manifest-path native/Cargo.toml \
  --target-dir build/native-target --example end_density_benchmark
```

It alternates the reference and tiled evaluator on one 16×16-block rectangle, then
measures tiled 64×64 and 512×512 rectangles. Construction, all density evaluation
and hashing every density bit are included. It creates no region files. These are
single-threaded CPU density-stage measurements, not complete-region timings or
End performance acceptance. The bounded probe measured 12.18 ms median for the
independent one-chunk evaluator and 0.141 ms for its tiled counterpart (four paired
trials). Three tiled 512×512 density-only samples were 84.82–85.17 ms, with a
median of 84.91 ms. These include hashing 128 MiB of density bits and exclude
all required later generation stages; they do not establish the region targets.

Production profile/query/material-mask integration,
CPU/GPU comparison, features, cities and gameplay remain unfinished.

### Cycle 2d: explicit profiles and native material/query integration

`EndTerrainProfile` exports loaded End or fixed biome IDs, the constant loaded
terrain material and actual dimension lighting independently of terrain strategy.
`EndTerrainSupport` resolves the loaded density references and compares their
expressions with the supported standard graph, decoded through Minecraft's own
codec. Registry names and settings hashes do not select a substitute graph.
Changed graphs, unsupported noise bounds/random sources/fluids and nonconstant
material rules remain unsupported rather than silently receiving vanilla terrain.
The source-derived graph descriptor is `retina/end/standard-terrain-26.3.json`.

Native `end::Profile` uses the shared dense lattice to produce floating-island
columns, packed quart biomes, air exposure and the existing complete material-run
substrate. It retains normal decoration guard columns and populates existing
terrain caches. Chunk and region assembly decode those exact runs; point queries,
base-column queries, height tiles and biome-volume queries use explicit End paths.
End terrain stays within world Y=0..127 while dimension storage remains separate.
No Overworld bedrock, ocean fill or cave noise is used. Opt-in performance counters
record native End columns, queries and terrain work as host work rather than GPU
density timestamps. Existing GPU lighting and region publication remain in use.

Reproduce profile and public Java/native API checks:

```sh
cargo build --release --locked --manifest-path native/Cargo.toml \
  --lib --target-dir build/native-target
./gradlew --no-daemon -PretinaHostOnly \
  -PretinaNativePath=/absolute/path/libretina_worldgen.dylib \
  :common:endProfileTest -x :packageNative -x :buildNative
```

Without `retinaNativePath`, the task checks export only and reports that distinction.
The native check registers both full exported tables and a clearly named
terrain-only profile. Four chunks across central terrain, void and positive/negative
outer islands match vanilla for 262,144 generated block states, all base columns,
6,144 height checks, cold height/biome point queries, cached columns and all quart
biome volumes. Fixed-biome registration and native volumes pass. The ordinary
optimized native suite passes 308 tests, zero failures, with 75 optional fixtures
ignored. Storage clipping, air planes, material-run assembly and halo reuse also
have focused native coverage.

A two-trial publication smoke used one measured region and one warmup per process.
Both 1,024-record decompressed-NBT corpora hash identically. All four new MCA files
(16,809,984 bytes) were removed after evidence capture. Measured lit terrain-only
region times were 397.13 and 400.93 ms; these two samples establish neither tail
latency nor final performance acceptance. Features and cities were explicitly
absent. One process's measured peak RSS was about 480 MiB including driver/cache
state. The historical region-report `gpu_ms` field includes terrain preparation
wall time here; it is not a CPU density device timestamp.

The bounded runner uses actual generation warmups for profiles without a registry
shader rather than demanding a shader-ready status for a nonexistent program.
The benchmark helper suite passes twelve tests, with one optional codec test skipped.

**Production End activation remains disabled.** Export still reports omitted End
features (islands, chorus, spikes, gateways and fixed placement). Cities, progression,
CPU/GPU island-field comparison, sparse execution, final compatibility and complete
End performance/gameplay acceptance remain required. Custom graphs beyond this
explicitly recognized standard shape also remain outside the current adapter.

### Cycle 2e: shared island candidates and CPU/GPU selection

`IslandNoise.candidate_field` tests every unique center in the required rectangle
once, preserving the seeded f64 simplex predicate and f32 size arithmetic. The
immutable field retains accepted centers and computes the original contribution
only for accepted centers within each query's 25×25 neighborhood. The finite-value
maximum is independent of traversal order; contribution arithmetic is unchanged.
Density lattice corners and loaded biome selection share this table. This does not
classify finished chunks or suppress any terrain, feature or structure work.

The standalone `end_island_field_benchmark` compares this CPU evaluator with an
actual-device WGSL implementation receiving the same exact center table. GPU
measurement includes allocations, table/point uploads, dispatch, synchronization,
readback, decoding and per-batch resource cleanup. Device startup and the shared
candidate-table construction are recorded separately. GPU results are compared
against independent source bits and CPU results, including negative and far
coordinates; mismatches are reported rather than hidden by tolerance.

```sh
cargo run --release --locked --manifest-path native/Cargo.toml \
  --target-dir build/native-target --example end_island_field_benchmark \
  -- /absolute/path/island-reference.json
```

The first dense-table CPU implementation passed all source values, but the Metal
WGSL candidate differed in 574 of 1,792 independent combined-field float values.
Differences were small (up to a few f32 ULPs); this does not prove a particular
terrain block changed, but it does not establish exact generation parity. The
experimental shader remains outside production. The CPU evaluator is selected;
no approximate GPU field is substituted into End terrain.

On the M4 Max, the accepted-center CPU evaluator measured 0.21–0.28 ms for
5,929 outer-field points, versus 0.51–0.54 ms for GPU upload/dispatch/readback
(eight alternating samples per case). Shared table construction added about
0.03 ms. The three outer cases retained 43–56 accepted centers out of 3,969 unique
candidates, avoiding millions of repeated simplex predicates. All 1,792 independent
combined and outer CPU field values remain exact. The native suite passes 310
tests with zero failures (75 optional fixtures ignored), and the four-chunk public
Java/native comparison still passes all block, column, height and biome checks.

An eight-region ABBA comparison used two retained baseline regions, four measured
samples per build, one warmup per process, one caller and sixteen workers. All
6,144 compared chunk records matched baseline decompressed NBT exactly. Median
lit terrain-only region time was 394.80 ms baseline versus 382.52 ms candidate;
process-to-process variation was substantial, so this is a modest observed change,
not a final performance or tail-latency claim. Features and cities remain absent.
All twelve new MCA files (50,429,952 bytes), including warmups, were removed after
comparison. Reports, reference/binary hashes and disposal receipts remain.

## End features

### Cycle 3a: fixed placement and the arrival platform

The placement interpreter now supports loaded `fixed_placement` positions. It
filters against the current input point's signed chunk coordinates, retains list
order and duplicates, and replaces all three coordinates including absolute Y.
Anchored expansion and nested replay both support the modifier. Dependency bounds
include its possible within-chunk displacement rather than assuming zero movement;
platform reach comes from the actual two-block geometry.

`EndPlatformFeature` exports actual obsidian/air palette IDs and runs through the
existing ordered decoration pipeline. Native geometry follows the source Z/X/Y
write order, puts the floor at originY−1, clears the three layers above it, and
avoids writes when the desired block is already present. This feature and fixed
placement consume no random draws. Other dimensions' random streams are unchanged.
Runtime platform recreation during travel remains Minecraft's responsibility.

```sh
cargo build --release --locked --manifest-path native/Cargo.toml \
  --lib --target-dir build/native-target
./gradlew --no-daemon -PretinaHostOnly \
  -PretinaNativePath=/absolute/path/libretina_worldgen.dylib \
  :common:endPlatformTest :common:endProfileTest -x :packageNative -x :buildNative
```

The independent platform fixture constructs the loaded vanilla placed feature,
actual `FeatureSorter` step/index and `WorldgenRandom` decoration/feature seeds.
Its recording level reads vanilla base columns and uses the real seeded
`BiomeManager` for the biome filter. Four generated chunks over two seeds match
all 262,144 block states across the Z=0 boundary. Additional checks compare negative
fixed positions, duplicate ordering, absolute heights, and obstructed-room clearing.
The terrain/query regression fixture also passes. Native tests: 312 passed, zero
failures, 75 optional fixtures ignored.

A separate publication check generated exactly two region files at (0,−1)/(0,0),
read their NBT back, and verified all 100 platform cells: 25 obsidian blocks at Y=48
and 75 air cells at Y=49..51. Both boundary chunks retain the existing incomplete
boundary-lighting flag; this check does not claim final chunk readiness. All 2,048
chunk records were hashed before the two disposable MCA files (8,404,992 bytes)
were removed. No benchmark throughput claim is made by this correctness smoke.

Export coverage now reports one supported End root and four omitted feature kinds:
islands, chorus, spikes and gateways. Random End features require an explicit
vanilla decoration stream: 26.3 uses `WorldgenRandom` backed by Xoroshiro for
`ChunkGenerator.applyBiomeDecoration`, not Retina's existing SplitMix stream and
not the terrain's legacy random source. That gap remains to be implemented without
changing Overworld/Nether seed behavior. Production End activation remains disabled;
cities, progression, sparse execution and full performance acceptance remain open.

### Cycle 3b: exact decoration streams and small islands

End random features now have a separate `WorldgenRandom`-over-Xoroshiro128++
implementation. It preserves signed-low-word `nextLong`, bound-one draws,
31-bit integer rejection sampling and 26.3 float rounding. Existing Overworld and
Nether decoration RNGs remain unchanged. End export uses the original source
biome order for `FeatureSorter` and embeds each root's actual per-step feature
index, rather than seeding from the cumulative replay order or a hashed recipe ID.

The End island root embeds its supported loaded placement program. One mutable
vanilla RNG traverses count branches depth first and runs terminal features in
emission order; branches are never given substitute independent streams. Exact
seeded block-biome zoom uses SHA-256 seed obfuscation and vanilla's fiddled nearest
quart selection. The End-only root discovery path reads surrounding 3×3 chunk
biomes as vanilla does; checking only the anchor chunk would omit successful roots
near biome boundaries. Unsupported modifier/provider kinds are explicitly reported.

Small-island geometry follows the actual EndIslandFeature radius, integer loops,
shrinking layers and random draw order. Writes use the existing ordered overlay,
dependency assembly, lighting and publication. Horizontal reach is derived from
the loaded modifiers and actual maximum island radius; no arbitrary clipping halo
is imposed. Source feature bounds remain distinct from dimension storage height.

```sh
./gradlew --no-daemon -PretinaHostOnly :common:endDecorationRandomTest \
  -x :packageNative -x :buildNative
cargo test --release --locked --manifest-path native/Cargo.toml \
  --lib --target-dir build/native-target
cargo build --release --locked --manifest-path native/Cargo.toml \
  --lib --target-dir build/native-target
./gradlew --no-daemon -PretinaHostOnly \
  -PretinaNativePath=/absolute/path/libretina_worldgen.dylib \
  :common:endIslandTest :common:endPlatformTest :common:endProfileTest \
  -x :packageNative -x :buildNative
```

Independent validation captures 42 actual world-seeded RNG streams across seven
signed seeds, plus 42 biome-zoom selections, in the ordinary native golden suite.
All 313 native tests pass (75 optional fixtures ignored). Whole vanilla placed
island roots match for 25 targeted anchors over two seeds: 17 accepted, 8 rejected,
two emitted islands sharing the same stream, negative coordinates and cross-region
writes. A successful root at (31,3841) has a Midlands anchor biome and requires
neighbor-biome discovery. Five generated chunks match all 327,680 stored states
against vanilla base terrain plus all contributing source island anchors. No
injected native random stream is used as the oracle. Platform and terrain/query
regression checks pass; the original independent reference payload hash is unchanged.

Two published regions at (0,6)/(1,6) contain all 334 source island cells from anchor
(31,213), spanning four chunks across the region edge. All 2,048 saved records were
hashed, then both disposable MCA files (8,404,992 bytes) were deleted. This is a
publication correctness check, not final End throughput or readiness acceptance.

Coverage now has two supported End roots, with chorus, spikes and gateways still
omitted. Production activation remains disabled. Cities, progression, sparse
execution, compatibility and complete End performance/gameplay acceptance remain
unfinished. The End-specific stream and placement context are reusable foundations
for those remaining random features.

### Cycle 3c: chorus and live End placement reads

`EndPlacementProfile` and the shared native End placement walker now support the
loaded heightmap modifier, including vanilla's empty-column rejection. A root
retains one exact world-seeded RNG and a live overlay between emitted features.
Small islands use the same walker; other dimensions retain their existing paths.

Chorus follows `ChorusPlantFeature` and `ChorusFlowerBlock.generatePlant`: support
tag checks, recursive stem/branch growth, north/east/south/west selection order,
maximum spread, explicit connection updates, and terminal age-five flowers.
Export retains all 64 connected stem states, all six flower ages, loaded support
tag members and air states. Each recursive write enters the live overlay before
subsequent reads. Heightmaps see earlier plants in the same placement stream.
The conservative read reach is eight blocks around the feature origin plus the
loaded placement displacement (23 blocks for the standard root). Writes use
normal dimension storage bounds rather than the 128-block base-noise range.

```sh
./gradlew --no-daemon -PretinaHostOnly \
  -PretinaNativePath=/absolute/path/libretina_worldgen.dylib \
  :common:endChorusTest :common:endIslandTest :common:endPlatformTest \
  :common:endProfileTest :common:endReferenceTest -x :packageNative -x :buildNative
```

Independent source roots use actual vanilla terrain columns, live vanilla
heightmap predicates, source feature indices, decoration seeds and biome filters.
All 32 targeted chorus roots over two seeds match exact final block states:
20 accepted and 12 rejected. Two duplicate-column fixtures place the same root
three times and verify updated heightmap reads after the first plant. Two complete
generated chunks match 131,072 states against vanilla base terrain plus supported
source features from contributing neighboring anchors. Island, platform and
terrain/query regressions pass. The original source reference hash is unchanged.
The optimized native suite passes 313 tests with zero failures (75 optional
fixtures ignored).

A saved-publication check covers 93 source chorus cells from anchor (31,90),
including every connected-state property, across four chunks in regions (0,2) and
(1,2). All 2,048 saved records were hashed; both disposable region files totaling
8,404,992 bytes were removed after comparison. This is correctness evidence for
the currently supported feature set, not full End readiness or performance
acceptance. Export now supports three roots; spikes and gateways remain omitted.
Production End activation stays disabled.

### Cycle 3d: spikes, cages and crystal metadata

The End spike recipe retains loaded descriptors and exports the ten standard
center coordinates using the source Java arithmetic. Empty descriptor lists use
the actual request seed in native code: legacy random `nextLong() & 65535`, then
the source Fisher–Yates shuffle. The existing random implementation is reused;
only method visibility changes, preserving other dimensions' seed behavior.

Native geometry preserves signed center-chunk selection, the source X/Y/Z loop
order, obsidian radius predicate, air clearing above Y=65, explicit iron-bar
connections, bedrock and fire caps. Crystal yaw consumes the End feature stream.
Crystal tags travel through the existing ordered per-chunk metadata events and
ordinary ProtoChunk entity installation, without fake structure starts. Each
spawn has a stable native UUID generated independently of the feature RNG;
vanilla creates runtime UUIDs outside that stream. All other saved fields,
including yaw, position, beam target and invulnerability, match source NBT.

The current spike adapter supports independent top-level roots whose modifiers
retain the anchor's horizontal position. Nested/cross-chunk placement programs
are reported unsupported rather than dropping entity ownership. Configured
radii outside 0..46340 and cap heights outside 0..254 are also reported unsupported;
they require overflow or boundary fire-state handling beyond this adapter.
Standard vanilla spikes and the tested configured tall spike are supported.

```sh
./gradlew --no-daemon -PretinaHostOnly \
  -PretinaNativePath=/absolute/path/libretina_worldgen.dylib \
  :common:endSpikeTest -x :packageNative -x :buildNative
```

The independent fixture invokes actual vanilla placed roots and entity
constructors/save codecs. Its entity-only level bypasses ServerLevel startup and
provides only entity IDs, feature flags and registries; it does not simulate
server ticking or prove live dragon gameplay. Twenty roots across two seeds
match all 102,618 final source states, including four guarded cages and twenty
crystals (the sole source/native NBT difference is runtime UUID). Four generated
chunks match 262,144 stored states. A configured guarded spike at (-1,511), height
134, crosses both region axes and verifies space above the base-noise range,
invulnerability and a beam target. All native crystal tags also round-trip through
actual Minecraft entity load/save codecs. Re-reading metadata does not duplicate
crystals. Compact native golden fixtures match all ten descriptors for seven
signed seeds, including both long extremes. Native suite: 314 passed, zero failures,
75 optional fixtures ignored. Island, chorus, platform and terrain/query regressions
pass, and the original independent source reference hash is unchanged.

Four published boss-area regions at (-1,-1), (0,-1), (-1,0), (0,0) preserve all
51,309 source spike/cage/cap cells for seed 123456789. Their 4,096 saved records
contain exactly ten crystals in the correct owner chunks; every saved field,
including native UUIDs, matches native metadata already compared with source.
All four disposable files (16,809,984 bytes) were removed after hashing/comparison.
This validates saved output, not live gameplay, final readiness or completed-End
performance. Coverage now supports four roots; natural gateways remain omitted.
Production End activation remains disabled.

### Cycle 3e: natural gateways and saved exit data

The shared exact End walker now samples loaded offset providers in X/Y/Z order,
using the existing source integer-provider semantics. Horizontal dependency reach
includes offset bounds. Metadata-producing roots additionally prove that terminal
positions remain in their anchor chunk; unsupported nested/cross-chunk programs
are reported rather than silently losing block entities. Spike ownership checks
also cover the newly supported offset operation.

Gateway export uses an actual `TheEndGatewayBlockEntity` and Minecraft's full save
codec to retain the loaded exit, exact-teleport flag, age and components. Native
geometry preserves the source X/Y/Z loop order, bedrock arrangement and air
clearing, then patches each prototype's coordinates. Writes enter the live overlay
between leaves. Later overlapping writes remove earlier gateway metadata, and
final tags use the existing ordered per-chunk metadata event path. The delayed
exit-search variant retains the absence of a destination. Progression-controlled
central gateways remain owned by Minecraft's dragon fight; this recipe implements
loaded generation features, not that runtime progression.

```sh
./gradlew --no-daemon -PretinaHostOnly \
  -PretinaNativePath=/absolute/path/libretina_worldgen.dylib \
  :common:endGatewayTest -x :packageNative -x :buildNative
```

Thirty-three targeted actual world-seeded roots over two seeds match: seventeen
accepted gateways and sixteen rejected attempts. Natural placements cross region
edges at positive and negative coordinates. Six configured fixtures cover delayed
search, known exits with both exact modes, repeated positions and an overlapping
placement that clears a previous gateway's block entity. These fixtures also use
Y=134 above the base-noise range and span both region axes. Every native gateway
tag round-trips through Minecraft's actual block-entity load/save codec.

Two complete boundary chunks match 131,072 states and block entities against
independent vanilla feature replay. The source neighborhood includes the complete
chain of earlier feature reads, bounded by the sum of the loaded root reaches.
All existing End regression fixtures pass, and the original vanilla reference
hash is unchanged. Native suite: 315 passed, zero failures, 75 optional fixtures
ignored. Initial cheap RNG discovery is retained as evidence; ordinary reruns use
only the thirty-three recorded anchors instead of repeating that search.

Two published regions at (0,585)/(1,585) retain all forty-five source gateway cells
across four chunks from anchor (31,18721). The saved block entity exactly retains
exit (100,50,0), exact teleport, age zero and components. All 2,048 chunk records
were hashed, then both disposable files (8,404,992 bytes) were removed. This is
saved-generation correctness evidence, not live gateway travel or performance
acceptance.

All five vanilla placed End feature roots now export without omissions. Cities,
ships, progression/travel, sparse execution and final performance/compatibility
acceptance remain unfinished. Production End activation stays disabled.

## End city planning

### Cycle 4a: exact seeded recursive plans

`structures::cities` implements the actual structure-context seed independently
of random-spread placement and Retina's generic weighted selection hash. It
performs the End/fixed-source column biome check, draws the source rotation,
queries the four rotated first-occupied corner heights around chunk offset (7,7),
and rejects minima below Y=60. Existing Overworld and Nether selection streams
are unchanged.

The city-specific recursive assembler preserves house/tower/fat-tower/bridge
choices, depth limits, bridge/stair offsets, ship selection, and piece order.
Collision testing uses the first intersecting existing piece and its generation
group, as the source does. The per-city ship flag and consumed random draws survive
rejected branches. Plans retain each template position, rotation, overwrite-air
flag, signed generation-group tag and complete source bounding box. Their saved
piece tags match the actual EndCityPiece fields, including the ECP identifier.

The conservative horizontal bound is derived from the source's eight-level
recursion, four bridge segments, maximum ship offset and loaded template extents.
For the shipped nineteen-template catalog it is 265 blocks from the exact start
position; discovery must additionally account for the start's seven-block offset
from its chunk origin. This bound is not a clip on generated pieces. All captured
plans fit within it.

```sh
./gradlew --no-daemon -PretinaHostOnly :common:endCityReferenceTest \
  -x :packageNative -x :buildNative
cargo run --release --locked --manifest-path native/Cargo.toml \
  --target-dir build/native-target --example end_city_compare \
  -- build/end/city-reference/reference.json
```

The independent fixture invokes actual vanilla structure contexts and template
loading. Across seven signed seeds, including both long extremes, all 112
random-spread candidates, column biomes and generation decisions match. Sixteen
otherwise eligible candidates fail the height threshold. Twenty-one valid cities
match all 1,398 ordered full saved piece tags and the next RNG value after assembly;
eleven have ships. All four rotations occur. The standalone comparison uses the
actual native End terrain/biome evaluators and verifies all 148 captured rotated
corner heights rather than injecting source random streams or source heights.
A compact source extract also protects candidate/assembly behavior in the ordinary
native suite using captured corner queries. Native tests: 316 passed, zero failures,
75 optional fixtures ignored. The original independent End reference hash is
unchanged. No complete regions are generated by this cycle.

This is the complete native city-planning component, not production city support.
Connecting these plans to resident template execution, chest/shulker/elytra markers,
structure starts/references, locate and saved-world loading remains in cycle 4.
Production End activation remains disabled; gameplay and performance acceptance
remain unproven.

## Remaining delivery sequence

1. End cities, ships, markers and saved structure metadata.
2. Conservative occupancy classification and sparse execution through publication.
3. Dragon progression and asynchronous portal/gateway preparation.
4. Automatic activation and loader/world/preview compatibility.
5. Measured tuning, gameplay verification and final merged-build acceptance.

Each cycle uses a separate worktree and PR with squash auto-merge. Complete region
performance targets are 50–100 ms median / below 150 ms p95 for void, 100–200 ms /
below 300 ms for ordinary islands, and 200–350 ms / below 500 ms for dense or
structure-heavy regions. These remain unproven targets until measured on the
completed implementation. Existing Overworld and Nether controls must remain intact.
