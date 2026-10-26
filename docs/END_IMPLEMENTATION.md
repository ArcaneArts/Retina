# End implementation

The End implementation uses Minecraft 26.3 as its independent reference. Production
End activation remains deferred while the native terrain, features, cities, sparse
execution, and progression integration are implemented. Overworld and Nether
generation are unchanged by the reference foundation.

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

Example, after an actual supported profile has been exported:

```sh
python3 scripts/end-region-benchmark.py \
  --library /absolute/path/libretina_worldgen.dylib \
  --library-revision BUILD_COMMIT \
  --profile /absolute/path/profile.json \
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

## Remaining delivery sequence

1. End profile, exact seeded terrain, floating-island queries and biome selection.
2. Native End features and initial arena.
3. End cities, ships, markers and saved structure metadata.
4. Conservative occupancy classification and sparse execution through publication.
5. Dragon progression and asynchronous portal/gateway preparation.
6. Automatic activation and loader/world/preview compatibility.
7. Measured tuning, gameplay verification and final merged-build acceptance.

Each cycle uses a separate worktree and PR with squash auto-merge. Complete region
performance targets are 50–100 ms median / below 150 ms p95 for void, 100–200 ms /
below 300 ms for ordinary islands, and 200–350 ms / below 500 ms for dense or
structure-heavy regions. These remain unproven targets until measured on the
completed implementation. Existing Overworld and Nether controls must remain intact.
