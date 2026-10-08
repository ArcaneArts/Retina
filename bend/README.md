# Retina Bend backend

This is the production-bound Bend implementation under development. It is **not
yet a selectable world type or a complete generator**. The existing Rust backend
continues to serve every current world. Progress and the complete parity target
are tracked in [the implementation checklist](../docs/BEND_IMPLEMENTATION.md).

## Toolchain

The initial implementation pins **Bend 2.0.36**, the release already used by the
simplex experiment. No compiler, runtime binary or generated C is checked in.
The pinned official macOS arm64 installer is available through:

```sh
python3 experiments/bend-simplex/bench.py --install
```

It installs into `~/.local/share/retina-bend/2.0.36`, verifies the release SHA256
and leaves the shell PATH unchanged. This installer currently covers macOS
arm64; production packaging and other supported targets remain outstanding.
Bend currently exposes Metal and CUDA GPU targets; we have measured Metal only.
Rust's broader platform coverage remains independent of this implementation.

## Compression

`compression.bend` implements LZ77, fixed Huffman codes, bit emission, zlib
framing and Adler-32 in Bend, following [RFC 1951](https://www.rfc-editor.org/rfc/rfc1951)
and [RFC 1950](https://www.rfc-editor.org/rfc/rfc1950). It uses a 16,384-slot
single-candidate hash table, a 32 KiB match window and matches up to 258 bytes.
Poorly compressible inputs use correctly framed stored blocks of at most 65,535
bytes, when those are smaller than the fixed-Huffman stream.

`compress_batch` accepts an owned balanced tree of chunk payloads and compresses
independent leaves with Bend CPU fork/join calls. Each leaf has an independent
dictionary and zlib stream, as required by MCA records. Balance trees by expected
payload work. GPU compression is not enabled or claimed here.

The `Bytes` contract requires byte-valued U32 elements, an accurate used length,
sufficient array capacity and payloads no larger than 16 MiB. Array access wraps
in Bend, so callers must maintain those bounds. The current test driver obtains
bytes from standard binary file IO. Production transports will validate their
own framing before handing buffers to the encoder.

The encoder uses `@unsafe` on bounded U32 loops and mutually recursive helpers
to waive Bend's structural termination proof. This does not introduce foreign
compression code, unchecked shared arrays or an alternate execution backend.
Successful native compilation checks types and ownership; it is **not** a formal
termination or codec-correctness proof. `--check-only` rejects these unproven
definitions and is not the test command.

```sh
python3 scripts/test_bend_compression.py
```

The runner compiles one native executable at nice +10, then tests it. It uses
standard file IO only; no handwritten C effects implement encoding. The harness
independently inflates every output with Python zlib, checks complete stream
consumption, framing, Adler-32 and exact bytes, and exercises every legal match
length, both endpoints of every distance-code interval, large/random inputs,
stored-block boundaries and all four leaves of parallel batches at 1/2/4 workers.

The report goes to ignored `build/bend/compression-tests.json`. Its process wall
times include startup, byte-list transport and disk IO; they are not codec-only
timings or a comparison with complete Rust generation. Real MCA compression and
full-region benchmarks remain required before the backend is complete.

The current byte buffers use one Bend U32 per byte. Compact packing, memory
limits and reusable per-worker buffers will be evaluated with actual NBT data.

## Binary and NBT primitives

`word64.bend` represents exact 64-bit values as two U32 words, with wrapping
addition, subtraction, multiplication, bitwise operations and Java-compatible
masked shifts. These values preserve Minecraft seed bits and packed NBT longs.

`binary.bend` provides a growable big-endian chunk buffer with a 16 MiB limit.
`nbt.bend` encodes all twelve NBT payload types, including homogeneous lists,
compounds and typed arrays. Long/double values and floating NaN payloads retain
their supplied bits. Strings use Java modified UTF-8, including NUL, supplementary
surrogate pairs and isolated UTF-16 surrogates. Oversized strings, invalid list
types/counts, out-of-capacity arrays and excessive nesting return errors instead
of wrapping array indices or producing a successful partial buffer.

```sh
python3 scripts/test_bend_binary.py
```

The independent Python reader checks every NBT type and exact full-buffer
consumption. It also compares 527 rows of seven 64-bit operations (3,689 results)
with Python integer arithmetic, covering all shift counts and carry/borrow/sign
boundaries. Nine invalid-input fixtures check error propagation. These primitive tests alone do not prove Minecraft chunk schema or MCA-file
compatibility; the chunk integration tests below exercise those separately.

## Packed indices and MCA container planning

`packed.bend` implements Minecraft's modern non-spanning long-array layout:
`floor(64 / bits)` values per long, with low bits first and unused high bits
zero. It accepts already local palette indices, 1..16 bits and up to 4,096
entries, and rejects invalid widths, capacities and overflowing values. Mapping
global IDs into each section's local palette is implemented in `palette.bend`.

`region.bend` turns independently compressed records into an owned stream of
an 8 KiB header and sector-aligned record buffers. It writes location/timestamp
tables, big-endian record lengths and the standard zlib type byte, and supports
unordered/sparse input slots. Duplicate slots and invalid inline limits fail.
Each record is bounded to the current Rust writer's 255-sector inline limit.

This planner is for **new files only**. It is not connected to game saves and
must not be used to replace existing files; preservation of loaded records,
external `.mcc` entries, atomic publication and cache promotion remain required.
Streaming avoids constructing one giant Bend byte-list for an entire region.

```sh
python3 scripts/test_bend_formats.py
```

An independent Python reader checks 144 packing cases (142,320 indices), every
record in a permuted 1,024-slot MCA, a sparse MCA containing a three-sector
record, an empty MCA, exact zlib/NBT consumption, sector coverage/non-overlap,
zero padding and timestamps. Nine invalid-input fixtures fail as expected.
These MCA fixtures deliberately contain `minecraft:empty` test metadata and
`DataVersion=1`; they do not claim current Minecraft chunk-schema or terrain
compatibility. Real generated-region loading remains an explicit acceptance test.

## Chunk serialization

`palette.bend` maps profile-local U16 material/biome IDs to deterministic local
palettes in first-occurrence order. A bounded open-addressed table sized to the
section avoids clearing the entire global ID domain. Uniform palettes omit
`data`; block palettes use at least four bits and biome palettes at least one.

`material.bend` defines registry-supplied block IDs, properties and six heightmap
predicate bits. `heightmaps.bend` scans final blocks, including tree/structure
tops, into all six maps. Values are relative to world minimum Y and include the
top block plus one. It uses the packed-array implementation rather than GPU
surface estimates or block-name heuristics.

`chunk.bend` validates dimensions, section counts/capacities and profile IDs, then
encodes current chunk fields, block/biome sections, heightmaps, structure
references, entities and block entities. It accepts already computed 2048-byte
light arrays and emits the two light-only padding sections when lighting is
complete. Missing/inconsistent lighting or malformed nibble arrays fail instead
of producing a successfully marked-lit chunk. It **does not compute lighting**.
The caller must establish the supplied lighting's correctness. Without lighting,
the chunk remains at `minecraft:features` for normal Minecraft lighting.

```sh
python3 scripts/test_bend_chunks.py
./gradlew bendChunkTest -PretinaHostOnly --max-workers=2 --no-parallel
```

The independent runner verifies 20 local-palette cases (49,664 indices), every
block and quart biome in two 24-section chunks, all six final heightmaps, exact
loot seed bits, signed section extremes, complete zlib/MCA framing and 27 invalid
inputs. The fixture includes water, leaves, non-default log/chest properties and
a block at the world ceiling. The separate Minecraft harness supplies the actual
runtime data version and reads/reopens the MCA through `RegionFileStorage`,
`PalettedContainer`, `SimpleBitStorage` and `SerializableChunkData`, comparing
heightmaps with Minecraft's actual predicates. Java only drives and decodes test
fixtures; Bend produces the bytes, compression and container.

These are complete **serialized fixture chunks**, not generated worlds. The
profiles and blocks are constructed in Bend test code. Registry transport, real
terrain/features, lighting computation, existing-file preservation, runtime
integration and actual client gameplay remain incomplete. The last independent
fixture report is in `docs/benchmarks/bend-chunk-correctness.json`.
