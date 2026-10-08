# Bend registry input transport

`BendProfileWire` streams the shared `TerrainProfileData.json()` projection into
structural binary data. It does not generate terrain or encode Minecraft NBT,
MCA, lighting or compression. `bend/registry.bend` alone validates and reads that
data. The worker owns one validated tape alongside its prepared noise stack.
Loaded climate/ridge octave stacks, climate indices and numeric density
programs and per-biome material predicates now have typed Bend projections.
Bulk material context/layer generation, full biome blending and world integration
remain pending.

Streaming avoids allocating millions of Gson nodes. Complete vanilla, Terralith
and Terralith plus the compatible registry-supplement profiles encode within a
256 MiB Java heap. Wire sizes are 18,054,360 / 40,435,520 / 40,456,820 bytes,
respectively. These are input transport checks, not region benchmarks; the tape
is larger than the original JSON. Packed arrays avoid one Bend node per template
coordinate or predicate bit and allow direct element access.

## Format v1

All words are unsigned, big endian, 32 bits. The header contains five words:
`RBP1` (`0x52425031`), version 1, dictionary entry count, dictionary word count,
and tape word count. File length must equal four times the sum of the header,
dictionary and tape word counts. Trailing bytes are rejected.

Each dictionary entry contains its UTF-16 code-unit count followed by pairs of
code units packed high/low into words. An odd final code unit has zero low-half
padding. This preserves supplementary characters, NUL and unpaired surrogates.
String IDs start at zero and follow first appearance. Java rejects duplicate
object field names; exported registry JSON already has unique fields.

The tape is one root value. Every node starts with its tag and total span in
words, including its header. Child spans permit skipping nested values without
building a generic object tree. Containers preserve source order.

| Tag | Remaining node words |
| --- | --- |
| 0 | none: null |
| 1 / 2 | none: false / true |
| 3 | exact signed i64 high / low bits |
| 4 | exact finite IEEE f64 high / low bits |
| 5 | dictionary string ID |
| 6 | child count followed by that many complete value nodes |
| 7 | field count followed by dictionary key ID / complete value node pairs |
| 8 | element count followed by signed i32 bit patterns |
| 9 | element count followed by signed i64 high / low word pairs |
| 10 | element count followed by finite f64 high / low word pairs |
| 11 | element count followed by boolean bits, least significant bit first |

Homogeneous arrays use tags 8–11. Empty and mixed arrays use tag 6. Integer/real
lexical distinction and negative zero are retained. Unused high bits in the last
boolean word must be zero. Bend validates every node, span, count, string ID,
finite real and padding before replacing the resident profile. Its rolling word
hash covers all dictionary/tape words; it is a test witness, not a security hash.

Bounds are 32 million words (128 MiB) for the file/working Java tape, one million
strings and 128 nested levels beneath the root. These bounds are separate from
the existing 16 MiB IPC frame/chunk buffer limits. Bend loads words through
64 KiB pieces into a bounded owned array. During replacement, old and new tapes
can coexist until validation succeeds; native runtime overhead exceeds raw wire
size. This is not a claim that process memory is limited to 128 MiB.

## Persistent worker commands

The existing `RBND` IPC framing remains version 1. Additional opcodes:

- **5 — load profile:** payload is path code-point count and Unicode scalar U32
  values. Paths contain 1–4096 scalars, excluding NUL and surrogate code points.
  Bend opens the staged file, validates it and acknowledges word count,
  dictionary count, tape starting word and rolling hash. Failure retains the old
  snapshot. Missing, truncated, unreadable and malformed files reject the upload
  without breaking IPC alignment or discarding resident noise settings.
- **6 — query input:** payload is step count (0–128), followed by step-kind/key
  pairs. Kind 0 selects an object field by dictionary key ID; kind 1 indexes an
  array. An empty path selects the root. Output is four words: value tag, first
  scalar/count/string-ID word, second scalar word and node position. Direct
  packed-array elements use scalar tags 1–4 and position zero. Missing fields,
  wrong container types and out-of-bounds indices return an ordinary error.
- **7 — string data:** payload is a dictionary ID. Output is code-unit count and
  packed UTF-16 words, with the same padding rule. Output remains bounded by the
  16 MiB Bend response encoder; excessively large strings return an error.
- **8 — prepare profile noise:** payload is seed low word, seed high word and
  channel (0–3 climate, 4 ridge). Bend resolves the corresponding loaded noise
  object by schema key, converts its exact numeric values to F32, validates the
  frequency/amplitude/modifiers and prepares the reusable GPU octave stack.
  Success returns an empty body; opcode 2 then samples the existing GPU grid
  path. No profile returns error 708, invalid command shape/channel 603,
  missing/wrongly typed schema values 710 and invalid numerical bounds 501.
  A rejected selection preserves the prepared stack and resident profile.
- **9 — prepare profile climate:** empty payload. Bend resolves `biomes.flags`
  and `climate_targets` from the resident tape, converts numeric intervals and
  builds reusable surface, underground and coastal indices. Success returns
  target count and biome count (two U32 words). Missing targets mean an empty
  index; missing depth defaults to `[0,0]`, matching the Rust input schema.
  Biomes are bounded to 1..65,535 and targets to 65,536. Invalid types, dimensions,
  interval ordering, finite bounds or biome references return 713; absent profile
  returns 708 and a nonempty command body returns 603.
- **10 — climate batch:** payload is a U32 query count (0..65,536), then each
  query has six raw F32 words (temperature, humidity, continentalness, erosion,
  weirdness, depth) and a U32 mode. Mode bits are weighted fitness=1,
  underground/depth=2, ocean mismatch penalty=4, exclude shores=8 and coastal
  projection=16. Coastal mode ignores the other bits. Output is five U32 words
  per query: present, biome ID, original target ordinal, fitness F32 bits and
  selected interval midpoint F32 bits (continentalness for coast, depth otherwise).
  Missing results are `[0,0xffffffff,0xffffffff,0,0]`. Invalid lengths, nonfinite
  queries or modes above 31 return 603. No prepared index returns 714. Lookup
  runs in one GPU batch, or on explicit Bend CPU execution, using the resident
  index without retransmitting the target table.
- **11 — prepare profile density:** empty payload. Bend resolves
  `registry_program.noises`, `points`, optional `interpolations` and the first
  three `programs` (climate, surface, final density), then validates numeric
  opcodes 0..31 and retains a typed model. Success returns four U32 counts:
  programs (3), noises, spline points, interpolation fields. Numeric vectors
  accept mixed or packed i32/i64/f64 arrays; IDs require unsigned integer
  values, salts require signed i32. Missing horizontal scale defaults to 1;
  missing interpolation lists default to empty. Limits: 8,192 noises with
  1..32 coefficients, 65,536 spline points, 128 ordered interpolation fields,
  1..1,024 instructions/program and 0..6 output roots. Fields have one root
  and may reference earlier fields; cyclic/forward references fail. Additional
  material/aquifer programs remain in the tape for future projections.
  Typed invalid models return 715; absent profile returns 708 and nonempty
  command body returns 603.
- **12 — density batch:** U32 query count (0..4,096), followed by six U32 words
  per query: program ID (0..2), signed-i32 X/Y/Z bit patterns, seed low and
  seed high. Output is six raw F32 words per query (24 bytes); absent output
  roots read node zero, as in the numeric library. Both full seed words and
  compensated transformed coordinates participate. Invalid length/count/ID
  returns 603; no prepared model returns 716. One GPU batch (or explicit Bend
  CPU execution) uses the prepared model without resending registry data.
- **13 — build density lattice:** eleven U32 words: program ID (0..2), inclusive
  minimum X/Y/Z, inclusive maximum X/Y/Z (signed-i32 bit patterns), horizontal
  step, vertical step, seed low and high. Bend aligns the origin down to the
  global cell grid and includes the upper interpolation vertices. Each axis
  span is at most 4,096 blocks; cell steps are 1..4,096. The grid has at least
  two vertices/axis and at most 1,048,576 total vertices. Invalid/truncated
  descriptors return 717, leaving the previous grid intact; missing prepared
  model returns 716. Success returns four U32 words: X/Y/Z vertex counts and
  total count. Sample order is X, then Y, then Z. The original program, including
  registered interpolation fields, executes at each vertex on the selected
  backend. Values stay in the worker; no sample array crosses the Java bridge.
- **14 — lattice density batch:** same request/response layout and query limit
  as opcode 12. Matching program/seed queries inside the resident grid interpolate
  its six channels on GPU (or explicit Bend CPU), in X/Y/Z order. Program, seed
  or coverage misses evaluate the original Bend graph directly, preserving the
  cache. Missing numeric model returns 716; prepared model with no lattice
  returns 718. Invalid batches return 603. Successful opcode 11 or 5 invalidates
  the lattice. Rejected commands/uploads preserve it. This top-level lattice is
  an approximation between vertices, not a replacement for the registered op28
  fields' semantics within the sampled graph.
- **15 — prepare surface profile:** empty request after opcode 11. Bend reads
  signed `geology_min_y`, `sea_level`, positive `geology_height`, and registered
  `surface`/`terrain_cell`. Six U32 acknowledgement words contain minimum Y,
  height, sea level, horizontal/vertical cells and explicit-height mode. Invalid
  typed configuration returns 719; no numeric preparation returns 716. World
  height and cells are bounded to 4,096; the signed maximum Y must fit the wire.
  A successful preparation clears previous computed columns.
- **18 — plan surface density:** six U32 words: signed minimum X/Z, width,
  depth, seed low/high. Width/depth are 1..1,024 and inclusive maximum coordinates
  must fit signed i32. Bend returns the 44-byte opcode-13 descriptor using
  profile cell spacing and world Y limits (Y=0 for explicit-height profiles).
  Invalid tiles return 720; missing surface preparation returns 722. Planning
  does not replace any cache. The surface probe's exported minimum/step remain
  raw profile data; the final-density grid uses world limits and `terrain_cell`.
- **16 — build surface columns:** same tile request as 18. Requires prepared
  climate (714 when absent), a density lattice (718 when absent), and compatible
  program 1, seed, cells and complete bounds (721 on mismatch). One GPU call
  (or explicit Bend CPU) scans bilinearly interpolated density layers, finds the
  highest solid-to-air crossing, evaluates six spatial climate channels at Y=0,
  and selects registered surface biome IDs. Explicit-height profiles instead
  interpolate program 1 at Y=0 and clamp height+1. Acknowledgement contains
  width/depth/column count. The computed tree remains resident: no density or
  column array needs to cross the bridge between stages. This top-level density
  approximation also applies to composition-mode profiles; exact interval-aware
  composition and downstream materials/caves remain implementation work.
- **17 — query surface columns:** U32 count (0..4,096), then four U32 words per
  query: signed X/Z, seed low/high. Each 36-byte row is U32 presence, F32 height,
  six F32 climate channels and U32 biome ID. Covered queries return presence 1;
  coordinate/seed misses return presence 0, seven zero F32s and biome 0xffffffff.
  An empty registered surface target table also yields biome 0xffffffff for
  present columns. Invalid batches return 603; no computed columns returns 723.
  Querying/encoding cached rows is host-side Bend. Heavy surface computation is
  opcode 16 on the selected backend; query transport is not a GPU benchmark.
  Successful 5/11/13/15 invalidates columns; rejected requests retain them.

- **19 — prepare material programs:** empty request after opcode 11. Bend walks
  the programs immediately following the first three numeric programs, one per
  registered biome, and projects `terrain_features.bands`, signed `sea_level`
  and the required boolean `registry_program.material_layers`. Acknowledgement
  is four U32 words: program count, band count, sea level bits, layer flag.
  Biomes/materials are bounded to 65,535; bands to 4,096 with each ID in the
  material palette. Programs have at most 1,024 nodes/six roots. Numeric
  dependencies reuse opcode-11 validation; material opcodes 40..54 validate
  their own scratch dependencies, immediate flags and parameters. Unsupported
  opcodes, missing/wrongly typed fields and invalid references reject with 724.
  No numeric preparation returns 716. Successful preparation retains density
  and surface caches; opcode 5/11 success invalidates this model.
- **20 — material batch:** U32 query count (0..4,096), then six U32 words per
  query: zero-based biome/program ID, signed X/Y/Z, seed low/high; followed by
  eight F32 context values: stone depth above, surface depth, local slope,
  terracotta offset, stone depth below, secondary surface noise, water height,
  preliminary surface height. Output is six F32 DAG roots (24 bytes/query);
  absent roots use node zero as in numeric queries. Material selection uses
  palette ID + 1, with zero meaning no selected material. Inputs require finite
  context values and an in-range program. Invalid batches return 603, no numeric
  preparation 716, no material preparation 725. A single GPU bang evaluates
  the bounded batch, or explicit Bend CPU execution. Model and caches remain
  resident; rejected commands retain them. Contexts are supplied component
  inputs at this stage; bulk voxel context/layer generation is still pending.


`BendWorker.loadProfile` transports only the path. Its caller owns the staged
file until acknowledgement. If a load is canceled while active, retain the file
until worker shutdown because the raw transport may still drain that operation.
After acknowledgement the file may be deleted: queries read the owned snapshot.
`BendWorker.prepareProfileNoise(seed, channel)` transports the two seed words
without a floating-point conversion. Schema key lookup and numeric projection
run once during preparation; the per-coordinate GPU kernel only receives the
small prepared stack. Known schema keys are ASCII; unrelated strings remain
lossless UTF-16 in the tape.

`BendWorker.prepareProfileClimate()` only sends the empty command. All climate
projection/index algorithms run in Bend. Known field keys resolve once;
biome/target list spans are walked once, avoiding repeated scans from the start
of the full target table. Numeric vectors support mixed nodes and packed
i32/i64/f64 arrays. Biome references and flags require unsigned integer values.
The preparation acknowledgement's IO write separates CPU index-building forks
from the following GPU batch in stock Bend's scheduler.

`BendWorker.prepareProfileDensity()` sends only the empty command. Bend resolves
schema keys, walks container spans once, converts numeric values and validates
programs before acknowledging. Query requests carry no model data. Preparing
noise, climate or density preserves the other prepared settings; no query
mutates the resident input or numeric model.

The numeric query entry point keeps model ownership on the host through a pure
batch wrapper, returning the model with the computed rows before encoding the
reply. Typed program/point visitors and direct evaluation helpers reduce shared
subtree ownership and continuation allocation. This does not change opcodes or
wire output. Noise/field ownership and repeated interpolation corners remain
performance work; measurements are in `docs/benchmarks/bend-density-borrows.json`.

`BendWorker.prepareDensityLattice(...)` transports only the descriptor. Bend
validates/aligns bounds and builds a balanced vertex tree, with short serial
runs per fork task on large grids. Small grids expose every vertex; grouping
aims for roughly 16,384 tasks as the grid grows. A pure wrapper retains the model on the host; later query
batches retain both model and grid. Endpoints use compensated coordinates, so
global cells can extend beyond signed-i32 endpoints without wrapping. One worker
retains one grid with the vertex bound above; replacing it releases the old one.
The runtime's boxed vertex storage is larger than packed raw F32 arrays. Full
region generation/performance and per-field caches remain future work. The
surface consumer below now reuses the resident density grid.

A successful opcode 5 upload invalidates both prepared climate and density
models. Call 9/11 before querying the replacement. Structurally rejected,
missing or truncated uploads retain the old tape and both models. A typed
preparation failure is reported explicitly; it does not restore or silently use
the previous profile's targets. Production
atomic full-profile validation/reload remains future integration work.

`bend/numbers.bend` implements nearest/ties-to-even i64/f64-to-F32 conversion
using integer word pairs. It covers signed zero, subnormals, underflow, overflow
and carry into the next exponent, without double-rounding split integer halves.
Raw registry numbers stay exact until a typed F32 parameter is required. The
noise validator rejects conversions that overflow its finite parameter bounds.

Automatic production staging lifetime/reload policy remains part of Minecraft
backend integration, along with the remaining typed registry programs and region
jobs. Numeric worker commands execute loaded density DAGs, but material rules,
shared lattice caches and integrated datapack terrain remain pending.

## Verification

```sh
nice -n 10 ./gradlew bendRegistryWireTest -PretinaHostOnly --max-workers=2 --no-parallel
nice -n 10 ./gradlew bendRegistryWireTest -PretinaHostOnly --max-workers=2 --no-parallel \
  -PbendRegistryProfiles=/absolute/vanilla.json,/absolute/terralith.json,/absolute/combined.json
```

The explicit task compiles Java transport classes without initializing Minecraft
or Rust and compiles one stock Bend worker at reduced priority. Python checks
all source values independently, including every packed template coordinate;
workers then query scalars/containers, return every dictionary string and retain
profiles across rejected uploads and GPU noise dispatches. Tests include signed
integer extremes, f64 subnormals/large values/negative zero, malformed nesting,
spans, counts, IDs and padding, missing/directory/truncated files, Unicode paths
and deletion of an acknowledged staging file. The existing worker harness still
covers raw transport concurrency, cancellation, bounded scheduling and shutdown.

`docs/benchmarks/bend-registry-wire.json` records actual results. These checks
establish complete input access, not a generated world or feature-parity claim.

Typed projection checks:

```sh
nice -n 10 ./gradlew bendNumericTest bendRegistryNoiseTest -PretinaHostOnly --max-workers=2 -PproveMetal
# Optional actual exported inputs: comma-separated JSON=RBP file pairs.
nice -n 10 ./gradlew bendRegistryNoiseTest -PretinaHostOnly --max-workers=2 \
  -PbendNoiseProfiles=/absolute/vanilla.json=/absolute/vanilla.rbp,/absolute/terralith.json=/absolute/terralith.rbp
nice -n 10 ./gradlew bendRegistryClimateTest -PretinaHostOnly --max-workers=2 -PproveMetal \
  -PbendClimateRegistryProfiles=/absolute/vanilla.json=/absolute/vanilla.rbp,/absolute/terralith.json=/absolute/terralith.rbp
```

The numeric harness independently checks 131,072 scalar conversions per run on
one/two CPU workers and GPU, including every double exponent and exact rational
integer midpoint checks above 2^53. Its diagnostic runtime observer records a
real Metal command buffer, with identical output bits. The loaded-noise harness
checks mixed and packed fixture arrays plus actual vanilla/Terralith/combined
snapshots: 16,100 independent samples per backend, all five channels, both seed
words, signed-i32 coordinate extremes and repeatable grids. It also checks direct
versus loaded preparation within each backend and 18 rejected typed profiles
without losing the previous stack. CPU/GPU floating-point byte equality is
reported, not required. A diagnostic observer confirms 319 actual Metal command
buffers for these loaded inputs and matches the normal GPU executable's output.
See `docs/benchmarks/bend-numeric-correctness.json` and
`docs/benchmarks/bend-registry-noise.json`. These are component correctness
results, not full-region speed measurements.

Resident numeric program checks:

```sh
nice -n 10 ./gradlew bendRegistryDensityTest -PretinaHostOnly --max-workers=2 -PproveMetal \
  -PbendDensityRegistryProfiles=/absolute/vanilla.json=/absolute/vanilla.rbp,/absolute/terralith.json=/absolute/terralith.rbp
```

The harness compares original registered programs with independent scalar
reference equations, including nested interpolation, ordered splines, both seed
words, signed extremes and world-border neighbors. It checks mixed/packed
vectors, missing optional values, extra material programs, maximum batches,
reordered/repeated/empty queries, typed rejection, explicit reload invalidation
and independent resident noise/climate state. Java tests concurrent raw callers
on CPU and GPU. Diagnostic stock-runtime observation must see actual Metal
commands and match normal executable bytes. Evidence is in
`docs/benchmarks/bend-registry-density.json`; preparation/query wall times are
component diagnostics, not complete-region benchmarks.

Resident density lattice checks:

```sh
nice -n 10 ./gradlew bendDensityLatticeTest -PretinaHostOnly --max-workers=2 -PproveMetal \
  -PbendDensityRegistryProfiles=/absolute/vanilla.json=/absolute/vanilla.rbp,/absolute/terralith.json=/absolute/terralith.rbp
```

Independent scalar reference equations sample the original graphs at global
vertices and apply trilinear interpolation. The harness checks nonaligned and
negative bounds, distant neighbors, signed-i32 endpoints, non-power-of-two cells,
seed/program/coverage misses, overlapping tile seams, reordered/repeated/empty
and 4,096-query batches, invalid capacity/geometry/lengths, cache invalidation
and retained caches after rejected uploads. The diagnostic observer must match
normal GPU bytes and observe the actual successful dispatches. Java concurrent
callers also exercise resident interpolation and direct Bend misses.

The construction/query component benchmark runs after compilation, alternates
direct/cached order, and checks byte-identical results at the same graph vertices:

```sh
nice -n 10 python3 scripts/benchmark_bend_lattice.py \
  --profile /absolute/vanilla.json /absolute/vanilla.rbp \
  --profile /absolute/terralith.json /absolute/terralith.rbp
```

Construction cost is separate from warm query medians. Between vertices the
lattice is an approximation, so these measurements do not establish complete
region latency/throughput or full generator parity. CPU/GPU validation and the
query measurements are recorded in `docs/benchmarks/bend-density-lattice.json`.

Resident surface checks (add actual JSON=RBP profiles with the same property used
by `bendDensityLatticeTest`):

```sh
nice -n 10 ./gradlew bendSurfaceTest -PretinaHostOnly --max-workers=2 -PproveMetal
```

`prepareProfileSurface`, `surfaceDensityDescriptor` and `generateSurfaceColumns`
are raw Java transport helpers. A scheduler must serialize the descriptor/build/
scan sequence against competing cache replacements; these helpers alone are not
region singleflight or world-generation integration. A worker retains one bounded
density grid and one bounded column tile. Tile overlap and signed/distant X/Z
queries are checked independently; a full 512x512 analytic tile exercises sizing.


Resident material-program checks:

```sh
nice -n 10 ./gradlew bendMaterialTest -PretinaHostOnly --max-workers=2 -PproveMetal \
  -PbendDensityRegistryProfiles=/absolute/vanilla.json=/absolute/vanilla.rbp,/absolute/terralith.json=/absolute/terralith.rbp
```

The scalar reference independently checks all material predicates (40..54),
legacy/layered contexts, signed terracotta offsets/band wrapping, full seeds,
distant integer coordinates, sparse patch coverage and mixed numeric/noise DAGs.
Actual exported vanilla/Terralith/combined material programs use the same path.
The worker retains its material model across numeric/lattice/surface queries;
accepted numeric preparation invalidates it, while rejected commands retain it.
Java checks concurrent callers on both CPU and GPU. Stock Metal command-buffer
observation verifies actual execution with output matching the normal binary.
These are component checks; material context generation, final voxel placement
and complete-region performance are not established by them.

### Derived material columns (component commands 21..24)

Command **21** takes an empty payload and projects the base stone/water palette
IDs and `registry_program.surface_noises` (exactly three registered noise IDs)
in Bend. Numeric and material preparation must already exist. Palette entry
zero must be `minecraft:air`; IDs and references are checked against the actual
resident arrays. The six acknowledgement U32s are stone, water, palette count,
depth-noise ID, secondary-noise ID and band-noise ID. Invalid projection returns
726 and preserves prior state. Numeric/material preparation errors remain
716/725. Success drops only derived block runs.

Command **22** takes an empty payload and generates compact vertical material
runs for the current surface tile on the explicitly selected CPU/GPU backend.
The matching density lattice must cover one extra column on all four X/Z sides
for central-difference slopes. Request a descriptor with command 18 for that
expanded tile, submit it to 13, and generate the central surface tile with 16
before 22. Signed bounds, full seeds and world limits must match. Work is bounded
to 134,217,728 central voxel positions per tile; larger worlds can use smaller
tiles. The geometry cache also scans the existing one-column slope halo.
The acknowledgement is width, depth, column count and total run count. Missing
prepared blocks/surface data returns 727, incompatible halo/seed/spacing or
oversized work returns 728, and an actual invalid material result/biome returns
729 without replacing an existing result. No Java terrain evaluation occurs.

Bend first caches each column's bilinear density layers and interpolates Y to
find all solid/fluid/air spans for the central tile plus its one-column halo.
On GPU, one step retains those compact spans and exact integer heights. A second
GPU step applies materials only to the central tile and uses four cached
neighbor heights for slopes, avoiding repeated vertical density scans. Both
steps stay within command 22, without an intermediate Java/IPC terrain payload.
The cache is immutable and scoped to that command; accepted/rejected state
replacement and material validation retain their existing behavior. A complete
region scans 514×514 columns (0.783% more than its central area); narrow tiles
have proportionally more halo work. CPU execution retains the original single
step: generate central spans and directly scan neighboring heights for slopes.
That choice avoids the measured CPU cache overhead and shares the context and
material algorithms; neither path delegates generation to another language.
It derives registered noise-based soil depth,
secondary depth noise, terracotta offsets, integer-height slopes, preliminary
surface height, top/bottom stone depth and water context before evaluating the
per-biome material DAG. Solid intervals and floating islands are preserved.
Runs are exhaustive, coalesced and bottom-up, with half-open offsets relative
to the world minimum Y. Each material is an exported palette ID. Queries reuse
the stored runs rather than reevaluating terrain or materials.

Command **23** takes U32 count (0..4096), then five U32s per query: signed X/Y/Z
bits and low/high seed words. Each response is `present, material` (two U32s);
a coordinate/seed/world-height miss returns `0, 0xffffffff`.

Command **24** takes U32 count (0..256), then four U32s per query: signed X/Z bits
and low/high seed words. A miss is one zero U32. A present result is U32 one,
signed geometric first-free Y, biome ID, five F32s (soil depth, slope, band
shift, secondary noise, preliminary surface), U32 run count, then that many
`startOffset, endOffset, material` U32 triples. Geometric height describes the
input density, before decorations or later voxel transformations; final NBT
heightmaps still need to be assembled from final block data. The query bound
keeps even 4096 one-block runs per column below the frame byte limit.

Successful density-lattice, surface-config or surface-tile replacements drop
derived runs while retaining material programs and block configuration.
Successful material/numeric/profile replacements invalidate their dependent
state. Rejected commands preserve it. These component commands do not yet
implement cave masks, aquifers, lake barriers, coastline remapping, underground
biome changes, decorations or complete generated MCA output.

```
nice -n 10 ./gradlew bendMaterialColumnsTest -PretinaHostOnly --max-workers=2 -PproveMetal \
  -PbendDensityRegistryProfiles=/absolute/vanilla.json=/absolute/vanilla.rbp,/absolute/terralith.json=/absolute/terralith.rbp
```

Independent scalar checks cover every queried block and context, floating
intervals, strict zero-density boundaries, empty/solid/height-mode worlds,
submerged columns, distant/signed coordinates, invalidation and malformed
inputs. Java callers also exercise concurrent resident voxel queries. Diagnostic
stock-runtime timestamps verify real Metal dispatch and identical normal-worker
output. These are component checks; host timings include preceding lattice and
surface construction and are not full-region performance measurements.

### Generated base/material chunks (component commands 25..27)

Command **25** takes an empty payload and projects the loaded `materials`,
`biomes[].id` and exact `heightmap_masks` into a reusable Bend catalog. It
requires the block configuration from command 21. Materials retain string IDs
or object IDs/properties; property values remain strings. Sequential tape
visitors avoid repeatedly walking large arrays. UTF-16 code units are preserved
for the existing modified-UTF8 NBT writer. The acknowledgement is two U32s:
material count and biome count. Invalid typed catalog data returns **730**;
missing block preparation returns **727**. Rejection preserves prior state.

Commands **26** (NBT) and **27** (zlib-compressed NBT) take exactly five U32s:
chunk X, chunk Z (signed bits), low/high world-seed words and the running game's
DataVersion. The whole 16x16 chunk must be covered by the resident block tile;
its origin need not equal the tile origin. Missing generated data/catalog returns
**731**, a tile/full-seed miss **732**, and coordinates outside signed-i32 block
space or non-section-aligned world limits **733**. These are serializer errors;
the existing block generator still handles nonaligned limits independently.
Truncated/trailing metadata returns **603**.

Bend expands the cached compact runs in Y/Z/X section order, samples 4x4 quart
surface biomes, packs both palettes, derives all six heightmaps from the actual
serialized blocks and exported Minecraft predicates, writes NBT and optionally
compresses it with its own DEFLATE/zlib encoder. The bridge transports only the
raw metadata and result. Output has `minecraft:features` status, no supplied
light arrays and `isLightOn=0`; it does not claim prelighting. Structure/entity
metadata is empty at this component stage. Underground biome reassignment,
final features/caves/fluids/light and actual region publication remain required.

Catalogs survive density/surface tile replacements and same-profile command 21
preparation, while block runs invalidate as before. Profile/numeric/material
model replacements invalidate the dependent catalog. Serialization is read-only
with respect to retained generation data; repeated raw/compressed requests and
parallel Java callers return the same bytes.

```sh
nice -n 10 ./gradlew bendGeneratedChunkTest -PretinaHostOnly --max-workers=2 --no-parallel \
  -PbendDensityRegistryProfiles=/absolute/vanilla.json=/absolute/vanilla.rbp
```

The fixture task checks every serialized block, biome and heightmap with
independent readers. `-PproveMetal` observes the actual generation commands;
encoding/compression itself is CPU Bend in this cycle. Minecraft's own codecs,
heightmap predicates and `SerializableChunkData` additionally decode and reopen
the outputs. Select pack-profile folders with `-PbendGeneratedChunkProfiles=terralith,combined`
and supply their registry packs using `-PtestDatapack`/`-PtestSupplement`.
`-PbendSkipBuild` reuses an explicitly already-built worker during local checks.
These are generated-chunk component checks, not running-world save/edit tests
or complete-region benchmarks.

### Resident shoreline finalization (component command 29)

Command **29** takes an empty payload after numeric/climate/surface preparation
and a successful surface build (16). It computes each column's integer
first-free height using the same solid-voxel predicate as block generation,
preserves its six sampled climate axes, and selects a final registered biome.
The acknowledgement is three U32s: width, depth and column count. Surface rows
remain queryable through 17; success invalidates dependent block runs, retaining
material preparation and the chunk catalog. Rejected requests preserve both.

Where the loaded climate source references shore targets, request 18 for a tile
expanded by **six columns per X/Z side**, generate that lattice with 13, build
only the central surface tile with 16, finalize with 29, then build blocks with
22. For a complete region the descriptor covers 524x524 columns; material output
and MCA output still cover exactly 512x512. The halo contains density samples,
not extra serialized chunks. Sources without referenced shore targets need only
central coverage for 29 (22 still needs its existing one-column slope halo).

Inland selection excludes shore targets, falling back to all targets only when
the source has no inland alternatives. Columns from sea-2 through sea+3 probe
the pre-cave terrain in eight directions at radii 2/4/6; diagonals stay within
the disk. Land requires nearby submerged terrain; submerged columns require
nearby land. A low inland plain or an entirely shallow ocean cannot manufacture
a shoreline. A coastal hit remaps continentalness to the nearest registered
shore interval's midpoint, then lets the complete climate source choose its
beach, river or other alternative. Per-biome material rules use that result;
there are no hardcoded sand, gravel, snow or biome-ID choices in the algorithm.
This ports the existing Rust coastal approximation, not exact vanilla biome
placement. Rivers away from shores and final ocean remapping remain separate
parity work. Cave/lake cuts do not influence this pre-cave snapshot.

Two bulk steps first cache integer heights for the expanded tile, then select
coastal biomes through bounded cache lookups. Existing continuous surface
heights are hints: adjacent voxel signs verify the integer answer with the
material generator's interpolation order. Exact-zero crossings are corrected;
ambiguous, tiny-density or clipped-island cases retain the full voxel scan.
Explicit-height profiles, nonfinite hints and world Y ranges beyond sub-block
F32 precision also retain that scan. Coastal probes never rescan neighbors.

The stages run on the explicitly selected Bend CPU/GPU backend. Java submits
only an empty command; no bulk height/biome IPC download or Java recomputation
occurs between the resident surface and material stages. The stock Bend runtime
owns device data movement and synchronization. Missing numeric preparation
returns **716**, missing climate **714**, missing resident surface/density
**734**, incompatible six-column halo or signed coordinate bounds **735**, and
trailing payload **603**. World seeds and globally aligned density cells retain
their existing contracts. Integer first-free heights refer to actual generated
occupancy, not exact arithmetic or cross-backend floating-point agreement.

```sh
nice -n 10 ./gradlew bendShorelineTest -PretinaHostOnly --max-workers=2 --no-parallel \
  -PbendSkipBuild -PbendDensityRegistryProfiles=/absolute/vanilla.json=/absolute/vanilla.rbp
```

`-PproveMetal` observes actual Metal command buffers. The independent oracle
scans integer voxels, searches climate intervals linearly and checks every
material voxel in selected columns. It covers inland/ocean rejection, distinct
shore materials, explicit heights, floating intervals, no-shore/shore-only
sources, negative sea levels, far/signed coordinates, overlaps, idempotence,
malformed requests and dependent cache invalidation. The whole-region fixture
pipeline now invokes 29 before 22, including a synthetic narrow coastline and
actual supplied vanilla/datapack profiles.

### Whole generated MCA staging (component command 28)

Command **28** takes seven U32s followed by Unicode scalar words:
`regionX, regionZ, seedLow, seedHigh, DataVersion, timestamp, pathLength`, then
`pathLength` U32 code points for a private output staging path (1..4096 scalars,
no NUL/surrogates/out-of-range values). Region coordinates are signed bits;
the request must cover a complete 512x512 region in signed-i32 block space.
All 1,024 chunks must already be covered by the resident generated block tile
with the same full seed and section-aligned world limits. The existing chunk
metadata errors remain **603**, **731**, **732** and **733**; missing numeric
preparation remains **716**. Invalid requests preserve the generation snapshot
and do not create an output file.

Bend encodes/compresses eight independent chunks per fork/join batch, retaining
compressed streams rather than expanded region blocks/NBT. It plans sector
locations and timestamps with `region.bend`, then writes the header and padded
records sequentially using stock file IO. Only the success acknowledgement,
`chunkCount=1024, sectorCount`, returns through IPC. Seeds, coordinates,
DataVersion, timestamps and paths are raw Java metadata; all format computation
and output bytes remain Bend. CPU Bend performs serialization/compression even
when the resident terrain was produced by GPU. No extra lighting rewrite occurs.

**Staging ownership is mandatory.** File mode `w` creates/truncates the provided
path. This component must never receive an existing saved region path. The
caller owns the private staging file until successful acknowledgement and later
publication. On actual open/write failure the stock IO error terminates the
worker and reaches pending Java callers as a transport failure. A partial staged
file may exist after a write failure; it is never acknowledged as complete.
In-flight cancellation drains the response and does not cancel the write, so
retain staging ownership until completion or worker shutdown before cleanup.
Queued cancellation skips the request. Atomic publication, existing/external
record preservation and Minecraft lifecycle integration are later work.

`bendGeneratedRegionTest` independently decodes whole generated files and then
reads/reopens all chunks through Minecraft. `bendGeneratedRegionWorkerTest`
exercises real concurrent Java writes, queued cancellation, unchanged snapshots,
an actual missing-parent open failure and worker shutdown. Add actual profiles
with `-PbendDensityRegistryProfiles`; select Minecraft decoder inputs using
`-PbendGeneratedRegionProfiles=ramp,islands,coastal,vanilla,terralith,combined` and load
the corresponding packs with the existing test pack arguments. These are unlit
base/material regions; final generation and running-world acceptance remain
explicitly incomplete.

`-PproveMetal` additionally instruments the stock runtime to observe complete
GPU command buffers for both synthetic full tiles and the first supplied actual
registry profile. Observed output files must match the normal GPU-required run.
The report lists the exact observed profiles; other supplied profiles still run
the normal independent checks, without a claim of additional device observation.

### Registered cave component (commands 30–32)

These commands prepare and exercise subsurface inputs. Commands 33–35 consume
them to generate cached fields and carve the resident block snapshot. 3D cave
biomes, aquifers/decorations and final world integration remain required.

- **30 — prepare geology:** empty payload, after numeric/material/block
  preparation (11/19/21). Bend resolves six `cave_noises`, each biome's `carvers`,
  `carveable`, lava ID/level and world minimum/height from the resident tape.
  The acknowledgement is eight U32 words: noise count, biome count, total
  carver count, material count, lava ID, lava level, minimum Y and world height.
  Signed values retain i32 bits. Both ordinary and packed boolean carveable
  arrays are supported. Carver IDs retain dictionary references; height ranges,
  triangle/plateau values, cave parameters and optional canyon/ravine ranges
  become immutable typed records. Optional shape fields default to zero,
  matching the shared export schema. Successful preparation drops generated
  block columns, retaining the numeric/surface inputs and chunk catalog.
  Rejected preparation preserves the old state. Missing block dependencies
  return **727**, invalid typed geology **736**, and a nonempty body **603**.
- **31 — cave noise batch:** payload is count (0..4096), followed by five raw
  U32 words per query: X, Y, Z, seed low, seed high. Output is six F32 words per
  query, in registered cave-channel order. Bend samples the registered 3D
  gradient stacks on the explicitly selected CPU/GPU backend. It retains signed
  coordinate fractions through i32 extremes, combines both seed words, ignores
  nonpositive weights and filters octave frequencies above 0.125 (the
  four-block cave lattice's Nyquist limit). Only retained weights normalize the
  result; no climate-stack gain or clamp is added. A weighted mean avoids
  overflowing the intermediate noise-times-weight product for large finite
  weights. The original 0.0001 denominator floor is retained for tiny weights.
  The GPU receives the six immutable noise definitions, not the registry tape
  or per-biome carvers. Missing geology returns **737**; malformed batches
  return **603**. Sampling does not mutate block columns or prepared models.
- **32 — biome carver batch:** count (0..256), then biome IDs. Each output starts
  with its carver count. Each carver has six U32 words (dictionary ID, kind,
  minimum Y, maximum Y, triangle flag, plateau) and 21 F32 words: probability;
  thickness/horizontal/vertical/count; floor/room/width-smoothness/vertical-default;
  thickness and horizontal ranges; distance range/vertical-center/zero padding;
  vertical and rotation ranges. This bounded host diagnostic reads typed
  records without traversing the registry tape again. Missing geology returns
  **737**; invalid IDs or framing return **603**.

Related surface/lattice/material-column generation retains the prepared cave
model. Successful density/material replacement and profile upload invalidate
its dependents. Failed uploads and rejected queries retain it. Java's new
methods only submit these raw commands; no cave/noise computation moves to Java.

```sh
nice -n 10 ./gradlew bendGeologyTest -PretinaHostOnly --max-workers=2 --no-parallel \
  -PbendSkipBuild -PproveMetal \
  -PbendDensityRegistryProfiles=/absolute/vanilla.json=/absolute/vanilla.rbp
```

The independent Python reference checks all six noise channels and every
projected carver field, using both synthetic and supplied loaded registries.
Tests cover octave filtering, zero/negative weights, large finite weights,
negative/distant coordinates, full seeds, reordered and repeated batches,
maximum counts, invalid schemas, malformed uploads and cache invalidation.
`-PproveMetal` observes actual stock-runtime Metal command buffers and compares
its output with the ordinary worker. These checks establish a cave input/noise
component, not complete-region performance or usable Bend world generation.

### Resident cave fields and carving (commands 33–35)

These commands run application logic entirely in Bend. The Java bridge submits
raw metadata and acknowledgements. No handwritten kernel or Rust/Java generation
path participates.

- **33 — cave lattice:** empty payload, after numeric, climate, surface, geology
  preparation and surface generation. Bend derives a globally aligned 4×4×4
  lattice covering the surface tile and world height. It dispatches surface
  climate/carver selection and four-channel 3D node generation separately on the
  selected Bend CPU/GPU backend. The channels are registered program-2 density,
  two anisotropic registered tunnel stacks with roughness, and finite rounded
  ravine distance. Cave-noise octave retention follows command 31. Ravines use
  registered probability, center heights/plateau and shape ranges, with seeded
  curvature and noisy walls. Compensated coordinates preserve signed and distant
  local distances. Intermediate node tables remain resident; the acknowledgement
  is eight U32 words: origin X/Y/Z (signed i32 bits), X/Y/Z node counts, total
  nodes and channel count (4). Each axis has at least two nodes, including thin
  tiles. The current cap is 2,097,152 nodes. Missing prerequisites return **739**,
  an oversized layout **738**, malformed input **603**, and nonfinite computed
  fields **743**. Rejected generation retains the previous state; successful
  field generation retains the independently generated block snapshot.
- **34 — cave field batch:** count (0..4096), then five U32 words per query:
  X/Y/Z/seed-low/seed-high. Output is presence U32 and four F32 values per query.
  Bend trilinearly interpolates the cached fields on the selected backend.
  Queries outside the padded node bounds or with a different seed return
  presence=0 and four zeros. Missing fields return **740**; invalid framing
  returns **603**. Diagnostics do not mutate fields or generated blocks.
- **35 — carve resident columns:** empty payload after material columns (22)
  and fields (33). Bend forks work over columns, reads the cached fields and
  applies registered carveable flags, carvers and lava level. Coherent sparse
  entrance domains relax the near-surface tunnel/roof suppression; the bottom
  five blocks and top four submerged-floor blocks are protected. Air/lava
  replacements become compact coalesced runs. The solid surface query is
  recomputed from the carved result, and later NBT/zlib/MCA commands consume
  these resident runs and derive final heightmaps. The acknowledgement is four
  U32 words: width, depth, column count and total runs. Missing prerequisites
  return **741**, incompatible actual cached bounds/seeds/world heights **742**,
  and malformed framing **603**.

Surface/lattice/material/geology replacement clears dependent cave fields.
Block regeneration clears fields and restores the base snapshot; the chunk
catalog and ordinary queries retain them. A caller must finalize coasts and
material columns before generating fields/carving. This component uses surface
biome carvers; underground biome assignment, aquifer barriers, exact composed
density/exterior handling and cave decorations still require implementation.
It emits FEATURES status without precomputed light. It is not the finished
Retina Bend world type.

```sh
nice -n 10 ./gradlew bendCaveTest bendCaveWorkerTest -PretinaHostOnly \
  --max-workers=2 --no-parallel -PbendSkipBuild -PproveMetal \
  -PbendMetalProbe=/absolute/path/to/current-source-metal-observer \
  -PbendDensityRegistryProfiles=/absolute/vanilla.json=/absolute/vanilla.rbp
nice -n 10 ./gradlew bendCaveRegionTest -PretinaHostOnly --max-workers=2 \
  --no-parallel -PbendMetalProbe=/absolute/path/to/current-source-metal-observer
```

The region fixture task uses an already compiled `build/bend/engine`; compile
the current source once with `bend bend/engine.bend -o build/bend/engine` before
using it. Only the optional observer modifies generated code, to log stock
runtime command completion; computational code is unchanged. CPU and Metal
transcendental roundoff may differ. Tests require independent numerical and
voxel correctness and repeatability within each backend, plus identical normal
GPU/observer output; cross-backend byte determinism is not required. See
`docs/benchmarks/bend-caves-correctness.json` for source/input/binary hashes,
actual Metal observations and independent/Minecraft decoding evidence.
