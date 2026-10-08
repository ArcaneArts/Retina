# Bend registry input transport

`BendProfileWire` streams the shared `TerrainProfileData.json()` projection into
structural binary data. It does not generate terrain or encode Minecraft NBT,
MCA, lighting or compression. `bend/registry.bend` alone validates and reads that
data. The worker owns one validated tape alongside its prepared noise stack.
Loaded climate/ridge octave stacks now have a typed Bend projection. Density
programs, full biome selection and terrain generation remain pending.

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

A successful opcode 5 upload invalidates the prepared climate index. Call 9
before querying the replacement. Structurally rejected/missing/truncated uploads
retain the old tape and index. A typed preparation failure is reported explicitly;
it does not restore or silently use the previous profile's targets. Production
atomic full-profile validation/reload remains future integration work.

`bend/numbers.bend` implements nearest/ties-to-even i64/f64-to-F32 conversion
using integer word pairs. It covers signed zero, subnormals, underflow, overflow
and carry into the next exponent, without double-rounding split integer halves.
Raw registry numbers stay exact until a typed F32 parameter is required. The
noise validator rejects conversions that overflow its finite parameter bounds.

Automatic production staging lifetime/reload policy remains part of Minecraft
backend integration, along with the remaining typed registry programs and region
jobs. These octave stacks do not execute the loaded density DAG or constitute a
complete datapack terrain implementation.

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
