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
to 134,217,728 voxel positions per tile; larger worlds can use smaller tiles.
The acknowledgement is width, depth, column count and total run count. Missing
prepared blocks/surface data returns 727, incompatible halo/seed/spacing or
oversized work returns 728, and an actual invalid material result/biome returns
729 without replacing an existing result. No Java terrain evaluation occurs.

Bend caches each column's bilinear density layers once and interpolates Y while
finding all solid intervals. It derives registered noise-based soil depth,
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
