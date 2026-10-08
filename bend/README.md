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

## Shared registry export

Java's `TerrainProfileData.export` projects the same loaded registry programs,
ordered materials/properties, biome/climate tables, feature recipes, structures
and lighting predicates for either backend. It does not initialize Rust or a
GPU. `BiomeTerrainProfile.register` is the explicit Rust adapter; existing Rust
callers keep their prior behavior. The exported material array and biome list
are snapshots so backend registration cannot change the shared ordering.

```sh
./gradlew registryExportTest registryExportRustTest -PretinaHostOnly --max-workers=2 --no-parallel
./gradlew registryExportTest registryExportRustTest -PretinaHostOnly --max-workers=2 --no-parallel \
  -PtestDatapack=/absolute/path/Terralith.zip -PtestRegistrySupplement
```

The first test exports real Minecraft registries while the native library is
unavailable, then confirms that actually initializing Rust fails. The second
explicitly registers the projection and queries terrain on Metal. Optional
`testDatapack` and `testSupplement` accept pack ZIPs in that order;
`testRegistrySupplement` builds a compatible fixture that adds a biome and
overrides temperature noise. Vanilla, Terralith and the combined fixture stack
produced byte-identical profiles between independent export and Rust registration.
Counts, hashes and the attempted incompatible Lithosphere input are recorded in
`docs/benchmarks/bend-registry-export.json`.

The persistent worker now receives complete profiles through the structural
wire described in [BEND_REGISTRY_WIRE.md](../docs/BEND_REGISTRY_WIRE.md).
Java streams the exported JSON into a packed tape; Bend validates and retains
it, then serves field/index and lossless string queries. Independent checks
compare every transported value with vanilla, Terralith and combined source
profiles. Application interpretation into terrain programs remains required.
The JSON profiles are about 13 MB/30 MB; compact input tapes are about 18 MB/40 MB.
This transport avoids generic object-node allocation, rather than claiming
smaller files or improved region generation throughput.

## Seeded noise kernels

`noise.bend` provides seeded 2D simplex, 3D gradient noise with quintic lattice
weights, registry-style weighted octave stacks and a trilinear primitive. Both
seed words participate in hashing. Octave preparation preserves channel/ordinal
salts, ignores nonpositive modifier weights and rejects invalid/oversized stacks.
Preparation runs once per profile/channel; prepared stacks can feed bulk GPU
sampling. Registered numeric density bytecode is implemented below; cached region interpolation grids remain pending.

Use `world_simplex` / `world_perlin` for integer world coordinates. These accept
signed-i32 coordinate bits and split the scaled coordinate into a lattice cell
and fraction using exact word products. Frequency is quantized to Q32, with
resolution 2^-32 per block; fractional conversion rounds at F32 precision.
Simplex skewing uses the same split representation and unskews only fractions,
avoiding subtraction of large floating coordinates. Lattice cells wrap for
hashing. Low-level axis frequency must be finite in [0, 2^30]; octave preparation
enforces that bound and a maximum of 32 modifiers. The floating-point `simplex`
entry point is for already small noise-space coordinates.

```sh
python3 scripts/test_bend_noise.py --prove-metal
./gradlew bendNoiseTest -PretinaHostOnly --max-workers=2 --no-parallel -PproveMetal
```

The runner independently checks 8,192 rows at one/two CPU workers and GPU,
including signed-i32 extremes, world-border coordinates, negative Y, full seed
words, zero/missing octave weights, interpolation and neighboring distant
samples. Maximum observed scalar error is below 0.000003. Eight malformed
configurations and six seed/stack boundary checks run in each executable.

`--prove-metal` is a macOS diagnostic: it adds one logging statement after the
stock generated runtime waits for a Metal command buffer, without replacing any
algorithm or kernel. It builds that executable's own GPU archive and checks its
output against the normal executable. This confirms actual dispatch; it is not
a production runtime modification or a performance benchmark. The report is in
`docs/benchmarks/bend-noise-correctness.json`. Runtime terrain integration and
complete lit/compressed-region measurements remain required.

## Registered numeric density programs

`density.bend` evaluates the exported numeric density opcodes **0..31** in Bend:
constants, coordinate transforms/gradients, arithmetic, range selection, mapped
functions, registered two-stack Perlin noise, old blended noise, Hermite splines
and recursively nested trilinear interpolation fields. Both seed words and
registered octave/salt/horizontal-scale parameters participate. Missing roots
read node zero, matching the current Rust interpreter.

`precise.bend` retains transformed coordinates as a compensated pair of F32s.
Signed i32 coordinates are split before conversion, arithmetic retains the small
residual, and lattice hashing separates the integer cell from its fraction.
This preserves neighboring positions after transformations beyond 2^24 blocks.
It is not an IEEE F64 implementation: transcendental density operations and final
outputs use F32. Cross-backend bitwise agreement is not a requirement.

Construct immutable tables with `prepared_table`, validate the model with
`valid_model`, and validate each main program with `valid_program` before `run`.
Preparation rejects invalid dependencies/roots, nonfinite parameters, missing
noise/spline/field references, invalid cells and recursive field cycles. Fields
reference earlier fields and have exactly one root. Registered spline locations
may be repeated or nonmonotonic; the evaluator preserves the exported order and
last-matching-location behavior, including Terralith's existing splines.
Each invocation owns its value array. Immutable `Data` input tables can be shared
across the GPU batch; no Rust, Java generation or handwritten kernels are used.

```sh
nice -n 10 ./gradlew bendDensityTest -PretinaHostOnly --max-workers=2 -PproveMetal \
  -PbendDensityProfiles=/absolute/vanilla/profile.json,/absolute/terralith/profile.json,/absolute/combined/profile.json
```

The test-only raw driver copies the registered noise, spline and field graphs;
an independent Python scalar evaluator checks all numeric operations, negative
coordinates, signed-i32 extremes, world-border neighbors, full seed words,
nested interpolation, repeated/reordered requests and empty batches. The actual
vanilla/Terralith/combined climate and two terrain programs run on one/two CPU
workers and Metal. Malformed models and truncated/trailing input must fail.
The optional stock-runtime observer verifies actual command buffers and output
identical to the normal GPU executable. See
`docs/benchmarks/bend-density-correctness.json` for the recorded evidence.

`registry_density.bend` now projects this numeric model from the resident tape.
Worker command 11 prepares it once; command 12 evaluates bounded raw batches
through `density_batch.bend`, retaining the model between requests. Additional
material/aquifer programs stay in the lossless input tape. Java only transports
commands and bytes. Material predicates 40..54, shared GPU lattice caches,
surface extraction and generated regions remain pending.
Per-query interpolation currently reevaluates its corners and stores all node
values; it is deliberately unoptimized. Diagnostic device times are not an
end-to-end benchmark or evidence of a Rust throughput improvement. Region-sized
lattice reuse and live-value reuse need measurement during integration.

```sh
nice -n 10 ./gradlew bendRegistryDensityTest -PretinaHostOnly --max-workers=2 -PproveMetal \
  -PbendDensityRegistryProfiles=/absolute/vanilla.json=/absolute/vanilla.rbp,/absolute/terralith.json=/absolute/terralith.rbp
```

The resident harness independently checks actual registered graphs, full seeds,
signed/distant coordinates, nested interpolation, mixed/packed vectors, default
parameters, maximum 4,096-query batches and typed rejection. It also exercises
repetition/reordering, acknowledged-file deletion, rejected uploads, successful
reload invalidation and independent noise/climate state. Java callers check
concurrent raw queries without Minecraft or Rust initialization. The diagnostic
observer confirms actual Metal commands and normal executable bytes. See
`docs/BEND_REGISTRY_WIRE.md` for schema and protocol limits and
`docs/benchmarks/bend-registry-density.json` for component evidence.

## Climate interval indices

`climate.bend` builds balanced bounding-volume trees for surface, underground and
shore lookup. Validate finite, ordered intervals and U16 biome IDs before calling
`prepare`; original target ordinals must be unique. Surface lookup excludes
underground-only biomes. Coastal lookup ignores continental distance and returns
the selected target's original continental midpoint. Weighted fitness, depth,
offsets, optional ocean mismatch penalties and shore exclusion follow the current
Rust approximator. Equal fitness chooses the earliest original target, including
when finite inputs overflow squared distances to infinity.

Preparation runs once on the CPU. The immutable `Data` model can be shared by
GPU queries. With stock Bend 2.0.36, an IO effect must separate CPU preparation's
forks from the next GPU bang; otherwise that bang continues on the CPU pool.
The fixture driver uses `IO.now()` at that boundary. The diagnostic observer
checks that the batch actually dispatches on Metal.

```sh
python3 scripts/test_bend_climate.py --prove-metal \
  --profile /absolute/vanilla/profile.json --profile /absolute/terralith/profile.json
nice -n 10 ./gradlew bendClimateTest -PretinaHostOnly --max-workers=2 -PproveMetal \
  -PbendClimateProfiles=/absolute/vanilla/profile.json,/absolute/terralith/profile.json
```

The Python harness copies registered targets into raw fixture records and compares
Bend lookup against an independent linear search. CPU one/two-worker and GPU runs
cover all query modes, empty indices, original-order ties, interval endpoints,
non-dyadic floats and overflowing squared distances. Invalid targets, queries,
dimensions and truncated/trailing input must fail without writing output. The
optional Metal observer must produce the normal GPU executable's bytes.

This is an index library, not an integrated biome generator or a throughput
benchmark. It retains surface targets differing only in depth; Rust deduplicates
those. Bounds and traversal preserve selection, but parity of performance is
unproven. `registry_climate.bend` now projects registered intervals/flags from the
resident tape, and `climate_batch.bend` supplies bounded bulk query decoding,
GPU mapping and raw response encoding for worker commands 9/10. Successful profile
uploads invalidate the index; queries require explicit re-preparation. Full schema
and protocol details are in [BEND_REGISTRY_WIRE.md](../docs/BEND_REGISTRY_WIRE.md).
Climate density programs, smooth spatial boundaries and terrain generation remain pending.
Evidence is in `docs/benchmarks/bend-climate-correctness.json`.

```sh
nice -n 10 ./gradlew bendRegistryClimateTest -PretinaHostOnly --max-workers=2 -PproveMetal \
  -PbendClimateRegistryProfiles=/absolute/vanilla.json=/absolute/vanilla.rbp,/absolute/terralith.json=/absolute/terralith.rbp
```

The resident harness checks mixed and packed numeric vectors, default depth,
empty/missing target lists and actual exported profiles. It independently checks
interval selection, repeats bounded batches up to 65,536 queries, rejects 32 typed
profiles and verifies explicit index invalidation. Acknowledged fixture staging
files can be removed; rejected/truncated uploads keep the prior index. The
diagnostic observer records 60 Metal command buffers for the seven-profile run,
with normal GPU bytes unchanged. Java independently exercises concurrent raw
callers on CPU and GPU workers. See `docs/benchmarks/bend-registry-climate.json`;
these are component checks, not generated-world or complete-region measurements.

## Persistent component worker

`engine.bend` runs one stock-runtime process across requests. It caches a
prepared noise stack, samples arbitrary grids up to 512×512 through a GPU bang
call (or explicit Bend CPU execution), and compresses supplied chunk payloads
with the Bend encoder. `reader.bend` checks bounds before reading words and
preserves supplied F32 bit patterns. `transport.bend` handles partial reads,
clean EOF and truncated frames using only stock binary file IO.

`BendWorker.java` is a raw process/transport adapter. It contains no generation,
noise, NBT or compression algorithms. One IO lane serializes frames from
parallel callers; the Bend job itself can run in parallel. The queue holds at
most 16 waiting requests, with a 32 MiB budget for retained cloned request
payloads including the active job. Responses are bounded separately. Canceled
queued jobs are skipped; canceled in-flight responses are drained so subsequent
frames stay aligned. Closing rejects pending requests and terminates the child.
Fatal framing/IO/process failures close the worker. An ordinary rejected command
returns an error while leaving its previous configuration intact.

CPU execution is explicit (`--gpu off`). `GPU_REQUIRED` uses `--gpu on`: the
stock Bend runtime rejects unavailable GPU execution before running this program,
which contains GPU bang calls. There is no Rust/vanilla fallback. The Java bridge
passes matching execution arguments and validates the actual worker handshake.
The current pipe entry points use `/dev/stdin` and `/dev/stdout`, so this worker
is POSIX-specific; a Windows transport and production packaging remain pending.

```sh
python3 scripts/test_bend_engine.py --prove-metal
./gradlew bendWorkerTest -PretinaHostOnly --max-workers=2 --no-parallel -PproveMetal
```

The independent runner completes 127 requests in one process for each of CPU and
GPU, verifies odd-sized/negative/distant grids plus a 512×512 grid, round-trips
four compressed payloads including 1 MiB random data, and rejects five malformed
streams. The diagnostic observer sees ten actual Metal command buffers, with
identical grids to the normal executable. Java tests run four concurrent callers
and verify cancellation, cloned-input ownership, queue/byte limits and budget
recovery, pending-request shutdown, corrupted frames/status, crashes and process
termination within a 256 MiB test heap. These checks do not initialize Rust or
Minecraft. Evidence is in `docs/benchmarks/bend-engine-correctness.json`.

Protocol v1 uses big-endian U32 words. Requests contain `RBND`, payload byte
length, request ID and opcode. Responses add a status word (0 success, 1 error)
after opcode. Opcodes are 0 ping, 1 prepare noise, 2 sample grid, 3 compress and
4 stop, 5 load a staged registry file, 6 query a registry path, 7 read a
registry string, 8 prepare registered noise, 9 prepare resident climate and
10 query climate batches. Noise input contains low/high seed words, frequency/amplitude
F32 bits,
channel, modifier count and modifier F32 bits. Grid input is X, Z, width, height
and integer step; output contains row-major raw F32 bits. Framed requests are
limited to 16 MiB. Large registry uploads use a separately bounded staged file
and leave the IPC queue limit intact; see the registry wire document.

This worker is an independently tested component transport, not yet connected
to world selection or Minecraft generation. Registry inputs are received and
read; complete terrain generation, features, structures, lighting,
region job coalescing/cache integration and
complete-region benchmarks remain required. A noise grid is not an MCA region.


## Resident material rules

`material_program.bend` evaluates the loaded per-biome material DAGs on GPU or
explicit Bend CPU, sharing the numeric/noise/spline evaluator. It implements
conditional material selection, soil depth, water/height/slope predicates,
seeded vertical gradients, temperature, noise counts, correlated feature
coverage, preliminary-surface predicates and registered terracotta bands.
`registry_material.bend` projects and validates programs and metadata from the
resident tape once; `material_batch.bend` supplies a bounded parallel query map.

```sh
nice -n 10 python3 scripts/test_bend_material.py --prove-metal \
  --profile /absolute/vanilla.json /absolute/vanilla.rbp
```

Opcodes 19/20 prepare/query the resident model; see `docs/BEND_REGISTRY_WIRE.md`
for the exact context/response format. Java only transports bytes. Bulk voxel
contexts/layers and final world generation remain required; this does not expose
an incomplete selectable Bend world type.
