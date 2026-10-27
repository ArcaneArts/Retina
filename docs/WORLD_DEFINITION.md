# Persistent world definitions

`<world-root>/retina.dat` is the contract for persistent, seed-bound generation
input. One file contains shared types and every dimension's definition. The
Rust `world_definition` module reads it without initializing Minecraft, loading
datapacks, or creating a GPU device.

Implementation is being delivered in separate cycles. The format, reader,
Java compiler, atomic file writer, native session binding and JDK-only bridge
and automatic Fabric/NeoForge startup integration are implemented. Normal
server generation opens the saved definition. The standalone runner uses the
same loader and generation session. Final legacy tooling migration and the
broader frozen-dependency/feature audit remain subsequent cycles.

## Wire format and storage geometry

The file is a single gzip member containing a Java-edition named-root NBT
compound. Writers use high compression. The root's name is not significant.
Field names are case-sensitive and use snake_case. NBT numbers are big-endian.

| Root field | NBT type | Meaning |
| --- | --- | --- |
| `format_version` | Int | `2` for new files; reader also supports version `1` |
| `generation_version` | Int | `1`; generation semantics implemented by the engine |
| `minecraft_version` | String | Source Minecraft version |
| `data_version` | Int | Output chunk DataVersion |
| `exporter` | String | Source Retina build/provenance |
| `seed` | Long | Exact signed 64-bit world seed; all bits are preserved |
| `generate_structures` | Byte | Boolean `0` or `1` |
| `bonus_chest` | Byte | Boolean `0` or `1` |
| `datapacks` | List of Compound | Ordered pack provenance: `id` string and optional `digest` string |
| `shared_types` | Compound | Shared material and biome dictionaries |
| `dimensions` | Compound | Dimension identifier -> dimension compound |

Pack identities and digests describe the source. They are not a substitute for
actually decoding and binding a definition. Execution settings such as output
compression, concurrency and GPU diagnostics are not generation identity.

`shared_types.materials` is a List of Compound. Each entry has an `id` String
and a `properties` Compound mapping property names to String values.
`shared_types.biomes` is a List of String identifiers. Both dictionaries are
file-local; their indices have no relationship to server registry IDs.

Each dimension contains:

| Field | NBT type | Meaning |
| --- | --- | --- |
| `parameters` | Compound | Dimension and generator parameters listed below |
| `material_palette` | IntArray | Local native material index -> shared material index |
| `biome_palette` | IntArray | Local native biome index -> shared biome index |
| `profile` | Compound, optional | Complete native generation profile |
| `unsupported_reason` | String, optional | Explicit reason generation cannot execute |

A dimension must contain exactly one of `profile` and a nonempty
`unsupported_reason`. An unsupported dimension does not disable other
dimensions. No profile is synthesized for it.

`parameters` contains String `generator`; native generation Int `min_y`, `height`,
`generator_depth`, `sea_level`, `spawn_height`, `logical_height`; boolean Byte
`skylight`, `ceiling`; Double `coordinate_scale`; and Float `base_height`,
`amplitude`, `frequency`. The last three preserve existing procedural request
parameters during migration. Requests take their seed and settings from the
loaded definition and add coordinates plus a registered native profile handle.
The writer preserves these floats' exact bits, including negative zero and
subnormal values; native metadata and requests retain the saved values.

Format 2 also requires `parameters.storage`, a Compound with Int `min_y` and
`height` for dimension storage. Legacy chunk-mode generators can evaluate a
smaller or offset range inside that storage. Their private generation bounds
must survive compilation; substituting the dimension range changes terrain.
Format 1 files retain their original meaning, with storage defaulting to their
saved native range. The semantics version remains 1; the new format makes the
additional required geometry visible to older readers as an unsupported format.

`generator_depth` is independent of both ranges. For example, an End definition
can retain generator_depth 128 and native/storage height 256, leaving room for
features above the density terrain. The adapter does not substitute depth for
native request height. When ranges differ it uses chunk delivery. Both chunk
and region delivery fit final native blocks into storage with air padding or
clipping and encode storage-shaped sections, 3D biomes and heightmaps. Lighting
uses the same final storage-shaped block volume. Generation itself still uses
the saved native range and seed.

## Structural profile encoding

The profile carries the existing generation model, including optional End,
flat, structure and feature data. It omits `materials`, which is resolved from
the shared palette, and the execution options `mca_compression` and `program_execution`. Its
biome definitions retain their complete generation data and identifiers; the
identifiers must agree with the dimension's `biome_palette`.

The existing dynamic model includes nullable values, mixed tuples and embedded
typed NBT. The following structural encoding preserves those values while
respecting NBT's homogeneous lists:

| Logical value | NBT representation |
| --- | --- |
| Object | Compound with the original field names |
| String | String, using Java-edition modified UTF-8 |
| `null` | Byte `-1` |
| Boolean | Byte `0` or `1` |
| Signed integer | Int where it fits, otherwise Long |
| Floating point | Double (readers also accept Float) |
| Integer array with all entries in 0..255 | ByteArray; bytes decode as unsigned values |
| Other integer array fitting i32 | IntArray |
| Other signed integer array | LongArray |
| String, boolean or double array | Homogeneous NBT List of that primitive type |
| Empty array | Empty NBT List |
| Other array | List of Compound; every entry has exactly one `v` field encoding the element |

The last rule handles arrays of objects, nested arrays, nulls and mixed tuples
without reserving any keys inside the original objects. An original object
with its own `v` field is wrapped like any other array element. Numeric arrays
retain full signed integer precision; mixed integer/double arrays must not
coerce large integers through floating point. Scalars and arrays with
non-finite floating point values are rejected.

Embedded template NBT retains its existing typed-tag representation inside
this structure. Tag IDs, signed arrays, numeric values, list kinds and string
contents are preserved through decoding and native structure compilation.
No profile JSON text is embedded in the file or parsed by the NBT loader.

New registry profiles retain the exporter's `decoration_omissions`,
`geology_omissions` and `simple_current_omissions` string lists, plus
`structures.omissions` and `registry_program.approximations`. These describe
encountered unsupported rules and existing approximations; they do not promise
complete vanilla generation. Empty lists are meaningful, including legitimate
biomes with no generation features. A disabled feature category can also have
an empty report because no rules were selected for execution.

The native adapter exposes these saved reports under each dimension's
`limitations` object as `decorations`, `geology`, `simple_block_survival`,
`structures` and `approximations`. It reads them from the file without looking
up current registries. A report absent from an older artifact stays absent;
absence does not establish that nothing was omitted. Minimal procedural
profiles describe only their built-in air/stone kernel and do not carry
registry-export reports. Adding reports does not rewrite an existing file.

The decoded profile enters the same native validation and initialization used
by the existing generation engine. The loader resolves local/shared palette
references before initialization. A SHA-256 fingerprint of the uncompressed
file plus the dimension key identifies the resident profile; it is stable
when reopening the same artifact and includes its seed and shared palettes.

## Fixed-biome procedural definitions

The original fixed-biome Retina generator uses the built-in air/stone simplex
kernel. Its saved profile contains `terrain_kind: "procedural"`, `sea_level`,
and one `biomes` entry containing the fixed biome identifier. Its local material
palette must resolve to air followed by stone. The world seed and procedural
base height, amplitude, frequency and geometry remain in the ordinary saved
world/dimension fields. Generation semantics version 1 identifies the existing
kernel's rules, so no loaded registry programs or biome generation settings are
needed for this terrain kind. Extra generation fields fail decoding instead of
being silently ignored.

The loaded world still owns a native profile handle and palettes. Internally the
GPU uses its shared procedural program; the world handle continues to distinguish
cache/lifecycle ownership and output DataVersion/compression. This preserves the
original generator's terrain and metadata rather than converting it to registry
noise terrain. Java reconstructs the saved fixed biome and air/stone palette
through the same saved-definition adapter as other supported dimensions.

## Reader verification

Run from `native/`:

```sh
cargo test --lib world_definition -- --nocapture
```

The tests read actual compressed NBT, compare native GPU tables and material
encoding against the existing profile loader, generate flat block buffers,
preserve optional End data, and verify numeric/template NBT fidelity. They
also cover shared palettes, explicit unsupported dimensions, actual decode
and reference errors, truncated files, and preserving the source after a
failed read. They do not initialize a GPU or claim mod/headless integration.

Fresh-process tests reproduce mod-captured regions from only `retina.dat`,
including legacy format 1 files and format 2 cropped native ranges. Remaining
cycles finish the hidden-dependency audit and retire the legacy JSON tooling path. The startup cycle adds an explicit
compilation path for serialized Retina biome-source generators, including their
procedural parameters and density-composition setting. Legacy fixed-biome
procedural Retina generators also compile to an explicit saved terrain kind.
Ordinary fixed biome noise generators retain their registry-profile terrain.
Replacing an already supported definition is an explicit generation change.
Previously deferred End records are the one support-upgrade exception: startup
compiles only entries carrying the historical End-adapter omission reasons. It
appends new shared types without changing old indices, preserves all supported
dimension definitions and root metadata, validates the actual candidate with the
native reader, and atomically publishes it with an exact original gzip backup.
Unsupported implementations/graphs retain their original generator. An already
supported End definition is never recompiled on reopen.

## Java compilation and cross-language verification

`WorldDefinitionCompiler.compile(server)` gathers effective loaded dimension
stems, the world's options, structure-template resources, and the loaded pack
order. It exports supported dimensions and records the existing adapter's
reason for unsupported dimensions. Material and biome dictionaries are shared
across all profiles. This compilation does not initialize the native library
or a GPU. The seed and seed-derived data are captured together.

`WorldDefinitionFile.writeNew` writes a temporary file in the world directory,
finishes high-compression gzip, flushes the file, and atomically moves it to
`retina.dat`. The caller must own world initialization and serialize creation.
Opening a present destination raises `FileAlreadyExistsException`, preserving
even a damaged file. Failed writes remove their temporary file. The writer
uses Minecraft's strict NBT entry point; the usual `NbtIo.write` helper can
silently replace overlong strings with empty values and is unsuitable here.
The structural encoder also bypasses `DoubleTag.valueOf`'s zero cache to
preserve negative zero. Published loader JARs include an Implementation-Version
manifest attribute for exporter provenance; classes run directly in development
are identified as a development build.

Generate real registry-backed fixtures without building a native library:

```sh
./gradlew :common:worldDefinitionCompileTest -x :buildNative -x :packageNative
```

This writes `build/world-definition-fixtures/{vanilla,flat,datapack,legacy,procedural,cropped,cropped_registry}/retina.dat`
and legacy JSON baselines used only for migration comparison. The datapack
case loads an actual additional biome resource and selects it as the fixed
Overworld biome, with no decoration features. Every case includes all three
loaded dimensions and follows the adapter's current support boundaries.
Additional tests cover signed long extremes, mixed values, Unicode, a failed
strict write, Java NBT round-tripping, and refusing to overwrite a present file.

From `native/`, independently read those Java-produced files in Rust:

```sh
RETINA_WORLD_DEFINITION_FIXTURES="$PWD/../build/world-definition-fixtures" \
  cargo test --lib java_compiled_definitions_preserve_complete_profiles -- --ignored --nocapture
```

This explicit fixture test fails if its inputs are absent. It compares the
complete structural profile, GPU tables, climate tables and material NBT
against the legacy exports, and compares flat terrain buffers. Each fixture
receives `compilation.json` and `rust-validation.json` receipts with compressed
size, compilation/load timing, and the scope of verification. Neither test
claims mod startup integration or headless region-generation parity.

The exact structural comparison parses baseline decimal number lexemes with
Rust's correctly rounded `f64` conversion. The legacy JSON parser's default
fast conversion can round some decimals one ULP differently from Java. The
file preserves Java's binary doubles exactly; GPU/material comparisons still
use the unchanged legacy runtime parser as their independent baseline.

## Native sessions and host bridge

`WorldDefinition::read` remains the single reader. The opaque-handle API and
the Rust `WorldSession` both consume its immutable result. Definition open,
metadata, diagnostics and close do not initialize a GPU. Binding a supported
dimension starts the shared engine and registers its saved profile. All
supported dimensions share the same loaded definition and engine.

The C ABI exposes:

- `retina_open_world_definition`: read the actual file; handle zero means only
  an absent file, while malformed or inaccessible files fail.
- `retina_world_definition_metadata`: compact host metadata from the loaded
  definition, including palettes, parameters, and query/preview information.
- `retina_bind_world_dimension`: bind a dimension with an output codec
  (`2` for zlib, `4` for LZ4Block).
- `retina_world_dimension_request`: populate the existing 40-byte request
  from the saved seed/settings plus supplied chunk coordinates.
- `retina_world_definition_profile_json`: explicit diagnostic output of the
  complete decoded profile, with its material palette resolved.
- `retina_world_dimension_chunk`: generate storage-shaped uncompressed chunk NBT
  from a world/dimension lease and coordinates, using the same encoder as regions.
- `retina_close_world_definition`: release the host's world ownership after
  it has stopped admitting work and drained generation.

The JSON returned by metadata/diagnostic calls is host-facing output. It is
not a second input to the saved-definition binder. Ordinary metadata omits
the density graphs and full generation resources. Neither call rereads the
file or consults a Minecraft registry.

`NativeWorldDefinition` uses only JDK classes and FFM. `NativeLibrary` loads a
single library image shared with `NativeTerrain`, keeping definition I/O
separate from GPU initialization. The Java bridge owns a native handle,
provides saved requests, and closes idempotently. Platform palette resolution
and server/world lifecycle integration use this bridge directly.

Rust dimension leases retain their session and definition while work runs.
The profile registry holds weak references for saved profiles; active jobs
hold strong references. Heavy parsed profile data is released after the last
owner/job, and stale requests cannot resolve it. IDs are never reused for
different worlds, so existing cache keys cannot alias a reopened world.
The process-wide engine and its existing GPU caches remain shared.

Output compression is a session setting rather than saved generation data.
It does not mutate the definition. Region output obtains DataVersion from a
saved binding even when a legacy caller supplies a different host value.
The existing writer preserves complete regions and validates existing data.

## Runtime verification

The normal `world_definition` tests include opaque handle behavior and profile
ownership. Run the isolated test below to prove definition I/O does not
initialize the shared engine:

```sh
cargo test --lib definition_io_does_not_initialize_the_gpu -- --ignored --nocapture
```

After generating the Java fixtures, these explicitly named tests require a
real compute device:

```sh
RETINA_WORLD_DEFINITION_FIXTURES="$PWD/../build/world-definition-fixtures" \
  cargo test --lib saved_file_generates_regions_and_keeps_active_dimension_leases_alive -- --ignored --nocapture
RETINA_WORLD_DEFINITION_FIXTURES="$PWD/../build/world-definition-fixtures" \
  cargo test --lib saved_overworld_and_nether_regions_match_legacy_generation -- --ignored --nocapture
```

The first generates a complete flat region, compares flat chunk data against
the existing path, checks negative region coordinates and saved DataVersion,
preserves a complete region byte-for-byte, and exercises lease lifetime.
The second generates saved/legacy Overworld and Nether regions and compares
the complete decoded NBT of every chunk (2,048 comparisons). Native generation
content is compared independently of MCA header timestamps and compound-key
serialization order. This includes every field emitted in those chunks;
targeted rare-feature and cross-boundary coverage remains part of end-to-end QA.

The Java bridge tests compile and run with a JDK-only classpath:

```sh
./gradlew worldDefinitionBridgeTest
./gradlew worldDefinitionBridgeGpuTest
```

They check vanilla/flat/datapack metadata, exact seeds, missing and malformed
files, frozen data after a source-file change, close behavior, and dimension
binding/request layout. The first needs no GPU initialization; the second
binds a real native engine. `-PretinaNativePath=/path/to/library` and
`-PretinaDefinitionFixtures=/path/to/world-definition-fixtures` can select
existing test artifacts. Missing inputs fail their actual open/load operations.

## Automatic mod startup

Before `MinecraftServer.createLevels` constructs any dimension, one
`WorldDefinitions` owner opens `<world-root>/retina.dat` through Rust. An absent
file triggers full-world Java compilation and atomic publication; that result
is discarded and the published file is opened through Rust. A present file
skips compilation. Read/decode errors preserve the file and stop initialization.
Changing datapacks does not rewrite it; replacing the generation definition is
an explicit world-generation change. Copying the file retains its exact seed
and supported dimension data; there is no seed override.

Runtime generators enumerate the saved biome palette and use saved geometry,
sea/spawn heights, requests, structure candidates and search placements.
Registry lookups resolve saved block states, biome holders and Structure
identities. Missing identifiers/properties and incompatible dimension storage
bounds/skylight fail during actual binding. Execution compression comes from
Retina configuration and is absent from generation identity. The server keeps
its original LevelStem for ordinary Minecraft serialization/gameplay; the
runtime Retina generator does not retain it as a generation source.

Minecraft normally filters and builds structure placements using current
Structure biome rules. The saved adapter installs its already resolved search
metadata directly, including saved exclusion dependencies, and native queries
perform generation selection. Full profile output is requested only by explicit
QA. World shutdown closes queries and queues, drains region generation, then
closes the native world handle.

Run `:common:worldDefinitionBindingTest` with the same native/fixture properties
as the bridge tests to resolve saved palettes and execute biome queries while
all generation-registry lookups deliberately fail. It also exercises concrete
missing-biome, missing-block and dimension-binding failures without rewriting
inputs.

Disposable dedicated-server runs use `-PretinaQa -PretinaAutomaticQa=true`.
On reopen add `-PretinaAutomaticReopenQa=true
-PretinaDefinitionFailCompilerQa`: any compiler call then throws. The startup
QA verifies saved request ownership and saved edits in both supported dimensions.
`-PretinaDefinitionCaptureQa=/absolute/isolated/output` also captures untouched
native Overworld and Nether regions at region coordinate (-2,1), for complete
mod/headless content comparisons. Use `-PretinaDatapackQa=/absolute/run-directory`
and `-PretinaNativePath=/absolute/native-library` for isolated local runs.
The equivalent tasks are `:fabric:runServer --args=--nogui` and
`:neoforge:runNeoForgeServer`.

The broader frozen-template decoder audit and removal of the old direct
registration helpers remain subsequent work; startup passing alone does not
complete the world-definition goal.

Startup verification on Metal / Apple M4 Max used separate disposable Fabric
(seed 731) and NeoForge (seed 9007199254740993) worlds. Both created the file,
reopened it, generated Overworld/Nether regions, preserved edits after restart
with compilation forced to fail, and left the definition's SHA-256 unchanged.
The control run with no file failed with the intentional compiler exception.
Both stopped and closed the native world after drain. Captured (-2,1) regions
contain 1,024 untouched chunks per supported dimension, retained for CLI parity.

The resulting files measured 2,387,049 bytes (Fabric) and 2,387,017 bytes
(NeoForge). Create/write/reopen took 7.029 s and 5.906 s respectively; later
native file opens took 1.274 s and 1.227 s. These are local development-build
observations, not a release performance guarantee. Rust independently verified
complete data/GPU/material parity for the registry and legacy compiler fixtures, including the
legacy generator with nondefault procedural parameters. The binding test and
existing preset registry integration test also passed.

## Standalone executable

Build from the repository root:

```sh
cargo build --manifest-path native/Cargo.toml --release --bin retina
./native/target/release/retina --help
./native/target/release/retina generate \
  --definition ./retina.dat \
  --dimension minecraft:overworld \
  --region-min=-4,-4 --region-max=4,4 \
  --output ./generated-world
```

The executable is `retina.exe` on Windows. Both range endpoints are inclusive,
using region coordinates (32x32 chunks). The example selects 81 regions. Rows
advance in Z, then X within each row. There is no seed override: copying the
file reproduces its exact seed-bound supported generation. A supported GPU
compute adapter remains required; neither Minecraft, a JVM nor datapack files
are runtime dependencies. Unsupported records report their saved reason when
selected. Historical files with deferred End records first need the one-time
adapter upgrade when their world opens in the mod; the CLI has no live source
registries with which to compile those formerly missing definitions.

Output paths are `region/` for the Overworld, `DIM-1/region/` for the Nether,
`DIM1/region/` for the End, and `dimensions/<namespace>/<path>/region/` for
other supported dimensions. Unsafe filesystem components cannot form output
paths. Newly compiled supported End definitions execute through the same saved
definition path as the other dimensions.
Use `--compression zlib` (default) or `--compression lz4` to select output
compression independently of the snapshot. `RAYON_NUM_THREADS` and
`RETINA_REGION_THREADS` control existing CPU execution pools.

Progress and errors go to stderr; completed generation reports a JSON summary
on stdout with counts, backend, seed, DataVersion and elapsed time. Exit codes
are 0 for completion, 1 for a runtime/read/generation error, 2 for invalid
arguments, and 130 for cancellation. Ctrl-C (and SIGTERM on Unix) finishes and
publishes the active region before stopping; completed regions remain usable.
GPU calls are not interrupted midway through publication.

Use an empty output directory or region data belonging to this definition.
The shared writer preserves every existing chunk record and generates missing
slots only. It preserves complete regions byte for byte; malformed header or
record locations fail without replacing the input. Existing chunks retain their
original generation identity, so filling a directory from another snapshot can
mix terrain. Stop any server using that output first. An OS advisory lock on
`.retina-generation.lock` serializes CLI writers to the output root; the file
can remain after exit/crash because only the actual OS lock controls ownership.
Minecraft does not use this CLI lock. The CLI produces region data, including
native metadata/ticks, rather than a fully initialized playable save with
`level.dat`, spawn setup or server metadata.

## Fresh-process headless proof

CPU process tests exercise the real executable's help, argument failures,
missing/malformed files, unsupported schemas, unknown dimensions and explicit
unsupported generation records. Run them and the CLI unit tests with:

```sh
cargo test --manifest-path native/Cargo.toml --bin retina --test headless
```

For the GPU proof, first capture untouched mod regions with the startup QA
option above, then write an orchestration JSON array such as:

```json
[
  {"name":"fabric","definition":"/absolute/fabric/world/retina.dat","regions":"/absolute/fabric/captured"},
  {"name":"neoforge","definition":"/absolute/neoforge/world/retina.dat","regions":"/absolute/neoforge/captured"}
]
```

```sh
RETINA_HEADLESS_PROOFS=/absolute/proofs.json \
  cargo test --manifest-path native/Cargo.toml --test headless \
  copied_definition_matches_mod_regions_in_fresh_processes -- --ignored --nocapture
```

Each child starts in a fresh directory containing only a copy of `retina.dat`.
The harness clears its environment apart from execution thread counts, starts
the compiled Rust executable directly, and compares every decoded NBT field in
all 1,024 chunks of region (-2,1), for both dimensions of each mod world.
The orchestration JSON supplies paths to the test; it is not a generation input
to the executable. Nether output uses LZ4 while the mod captures use zlib.

Executed on Metal / Apple M4 Max, the two mod-created definitions (seeds 731 and
9007199254740993) matched all 4,096 chunks. Full-region repeat runs preserved
bytes, partial-region runs generated one missing slot and preserved 1,023, and
damaged output failed unchanged. An actual SIGINT during generation finished
one complete region, left no unpublished region file and exited 130. CPU CLI
unit/process tests and the existing native unit suite also passed. These checks
establish Retina-to-Retina equality on this backend, not new vanilla parity or
cross-GPU determinism. The remaining goal still includes targeted rare-feature
coverage, frozen runtime-template binding and legacy JSON tooling migration.

Cropped chunk delivery installs native section data and saved heightmaps directly;
its feature stage restores those saved heightmaps after ordinary Minecraft
initialization. It does not rebuild authoritative heightmap masks from current
registry rules. A bounded cache retains pending maps; a miss regenerates the
same saved chunk through Rust. Closing a generator stops further chunk admission.
The JDK-only bridge transfers binary NBT without requiring Minecraft classes;
only the platform adapter decodes that output for installation.

## Saved structure pieces

Native structure starts use the saved child-piece NBT and bounding boxes on
initial installation and saved MCA reopen. The platform resolves structure and
piece-type identifiers for runtime identity, while the shared metadata reader
avoids current template, pool and processor loaders. Start bounding boxes use
the saved terrain-adaptation margin. End city ECP rotation and position handling
is retained. These pieces cannot place blocks; native generation owns placement.
A missing saved structure or piece identity raises a concrete binding error.

The loader-transformed structure test covers nine non-End structure identities
and the real End city source fixtures. To additionally read both complete
captured Overworld/Nether MCA regions without a template manager, run:

```sh
./gradlew :fabric:test --tests '*EndCitySavedMetadataMixinTest' \
  -PretinaNativePath=/absolute/path/to/libretina_worldgen.dylib \
  -PretinaDefinitionMetadataQa=/absolute/path/to/captured-server-directory
```

The input directory contains `world/retina.dat` and the actual mod captures
`captured/region/r.-2.1.mca` and `captured/DIM-1/region/r.-2.1.mca`. The test checks
all saved starts and children in those regions, including their complete NBT,
reference counts and saved adjustment bounds.

## Migrating benchmark inputs

The registry compiler and benchmark fixtures share `WorldDefinitionAssembler`
for structural profiles, shared type dictionaries, palettes and world headers.
The pipeline, retained-column and ore-planning/raster tools open actual files through
`WorldDefinition::read` and `WorldSession`; their request seed and parameters
come from the selected dimension. They accept no seed override. Other legacy
benchmark and integration fixtures remain scheduled for migration.

Compile an Overworld fixture without loading the native library or GPU:

```sh
./gradlew :common:exportBenchmarkDefinition -PbenchmarkSeed=9007199254741001
```

This creates `build/benchmark-definition/overworld/retina.dat`. For Nether use
`:common:exportNetherBenchmarkDefinition`, whose default output directory is
`build/benchmark-definition/nether`. `-PbenchmarkPack=/absolute/pack.zip` selects
a datapack and `-PbenchmarkDefinitionRoot=/absolute/new-directory` selects a
fresh fixture directory. Present definitions are preserved; use another
directory when compiling a different seed or configuration. These are test
fixtures containing the selected benchmark dimension. Ordinary world startup
still gathers all dimensions automatically.

The native tools can also consume a file copied from a real mod world:

```sh
gpu_pipeline_benchmark ./retina.dat minecraft:overworld 1 2
column_cache_fixture ./retina.dat minecraft:the_nether ./columns.bin
ore_plan_benchmark ./retina.dat minecraft:overworld 0 0 2 3
ore_raster_benchmark ./retina.dat minecraft:overworld 3 0 0 bits
```

Pipeline depth and sample counts remain execution inputs. Fixture export
calculates seed-dependent data for `benchmarkSeed` before writing the file.

## Integration fixture construction and dispatch options

`WorldDefinitionAssembler` builds shared dictionaries from resolved canonical
material names/properties and biome names. Registry-backed compilation projects
those names before assembly; literal integration programs can use the same
structural writer without resolving platform objects. Runtime palette binding
remains a separate adapter step. Existing compiler fixture files retain exactly
the same compressed bytes.

The test-only `DefinitionFixture` writes a complete seed-bound world file, opens
that published file through `NativeWorldDefinition`, and obtains generation
requests from the loaded dimension. It owns a real world handle; there are no
virtual profile IDs or JSON-upload substitutes. Tests declare their seed and
native/storage geometry before publication and close their bindings after jobs
finish. The MCA codec test opens one file with zlib and LZ4 bindings. Material
rule tests run both dispatch modes from the same file for each program.

Rust callers may use `WorldSession::bind_with_execution` with an optional
`runtime::ProgramExecution::{Auto,Interpreter,Specialized}`. Java exposes the
matching `NativeWorldDefinition.ProgramExecution` binding overload, backed by
`retina_bind_world_dimension_execution` (execution IDs 0, 1, 2 respectively).
A binding's dispatch preference is fixed once initialized. A later unspecified
bind reuses it; a conflicting explicit preference fails. Default bindings reuse
the original loaded profile. An explicit differing preference gets an owned
immutable dispatch copy; generation tables and the loaded definition stay
unchanged, and outstanding dimension leases retain the copy until they finish.

New files omit dispatch preferences, and format 2 rejects `program_execution`
in a profile with an actionable binding-option error. Format 1's historical
profile hints remain readable for compatibility. Choosing a GPU implementation
is separate from choosing generation rules and does not rewrite `retina.dat`.

### Decoration and material diagnostics

`decoration_plan_benchmark retina.dat minecraft:overworld X Z ITERATIONS SIDE WORKERS`
and the optional `decoration_replay_benchmark retina.dat minecraft:overworld SIDE PAIRS X Z`
load the saved seed and geometry, then bind interpreter execution. Neither accepts a
seed override or rewrites generation rules. Existing ordered-output and detailed-plan
comparisons still run outside measured intervals.

`material_substrate_check HALO/retina.dat BASELINE/retina.dat minecraft:overworld specialized 32`
compares two explicit generation snapshots. Compile them separately with
`:common:exportBenchmarkDefinition -PbenchmarkDefinitionRoot=HALO -PbenchmarkMaterialHalo=true`
and the corresponding `BASELINE` / `false` options; use the same `benchmarkSeed` and
`benchmarkPack` for both. The default exporter retains its normal halo setting.
The checker loads each file unchanged and binds the selected interpreter/specialized
dispatch preference, including a second independent engine for cold chunk comparisons.
It takes geometry and all requests from the files, including out-of-range probes.
These are benchmark fixtures, not a way to change the seed or rules of an existing world.

### Python region and material campaigns

`scripts/native-region-benchmark.py --library LIB --definition retina.dat --dimension KEY --out NEW_DIRECTORY`
uses the same native file loader and saved requests through `scripts/saved_definition.py`.
The adapter transports FFI handles and metadata; it does not decode NBT or compile
another profile. It closes handles after concurrent callers drain, including on failure.
The region harness keeps its existing timing counters, decoded chunk comparisons,
readiness selection, output codec options and disposable output ownership.

The A/B campaign, End campaign and Nether performance suite pass definitions through
the same runner. End campaigns default to `--readiness driver-warm`; choose
`shader-ready` explicitly when measuring a registered shader workload. No profile
filename or metadata guess prevents a workload from running. The Nether suite's
version 2 manifest names a separate snapshot and dimension for each case.

`scripts/native-material-continuation-check.py --baseline LIB --candidate LIB --definition retina.dat --dimension KEY --out REPORT`
uses interpreter bindings to that same file in both libraries. Its height, seed and
other generation parameters come from saved requests. Use separate snapshots to
compare geometry; the script no longer has a runtime height or seed override.
Libraries used as references must expose the saved-definition API. Historical
JSON-era libraries and measurements remain useful archived evidence, but cannot
be passed to this current interface. Reports identify the definition path/hash,
saved seed, native/storage geometry, dimension, DataVersion and load/bind duration.
The main region report uses schema version 2 for this definition identity and
`startup.load_and_bind_ms`, replacing JSON profile identity/registration timing.

Run the Python bridge integration with `RETINA_NATIVE_LIBRARY=LIB` and
`RETINA_DEFINITION_FIXTURES=build/world-definition-fixtures` using
`python3 -m unittest discover -s scripts -p 'test_*.py'`. These tests execute the
actual Rust decoder/binding and use the GPU; missing optional LZ4 Python packages
are reported as a skipped decoder test. Rust's headless decoder checks LZ4 too.

### Lighting fixtures

`NativeLightingIntegrationTest` publishes complete saved definitions for each
lighting-rule/native-geometry variant, then gets its requests from the loaded file.
The shared test fixture helper can own a temporary definition directory and removes
it after closing its native handle. Caller-owned definition files remain untouched.
The MCA lighting tests also get DataVersion from the loaded metadata.

With `RETINA_LIGHTING_EXPORT=FRESH_DIRECTORY`, the test copies the same published
file alongside each raw block volume. The small JSON manifest names its dimension,
relative `CASE/retina.dat`, block file and volume side; it carries no generation
profile or independent seed/height. Repeated boundary cases have distinct names.
The full-height saved-region path includes all states observed in its block volumes
and publishes their lighting/type rules the same way.

`scripts/lighting-benchmark.py` uses the common Python world-handle adapter for
both current/reference libraries, and closes each case after synchronous lighting
jobs complete. Dense/sparse/reference output comparisons retain their existing
scope; raw blocks are explicit lighting inputs, while rules come only from the file.

### Analytic terrain fixtures

Coordinate scopes, interpolation/density composition, aquifer equations and
snow/MCA integration use complete saved fixtures. Registry compilation uses the
fixture's declared seed before writing. Every generated chunk, column query and
region request then comes from the loaded dimension; snow MCA DataVersion also
comes from loaded metadata.

The test-only `DefinitionFixture.snapshot(...)` owns one published file without
starting a binding. Tests can open interpreter and specialized sessions to that
same file, close each session, and finally remove the temporary file. They assert
that dispatch comparisons leave the file unchanged. Density-composition baseline
and composed programs are separate explicitly compiled generation definitions.
`create(..., execution)` supports cases that need one selected dispatch binding.
These helpers all use the production assembler, file writer and Rust loader.

The coordinate/interpolation JSON diagnostic artifacts remain temporary inputs
to the existing low-level GPU shader comparison probes; those probes' fixture
migration is separate. They are not uploaded by these Java generation tests.

### Frozen density semantics and compilation diagnostics

`registry_program.density_composition` selects generation semantics from the saved
file. `RETINA_DENSITY_COMPOSITION` no longer disables or enables that rule. This
preserves normal output when the old environment override was unset, and makes
a copied definition authoritative even in a process carrying that legacy variable.
The adapter's registry-program metadata reports the actual saved choice.

For comparisons, compile separate files with
`:common:exportBenchmarkDefinition -PbenchmarkDefinitionRoot=NEW_DIRECTORY -PbenchmarkDensityComposition=true|false`.
That property changes the compiler's model before publication; it never rewrites
an existing definition. Region benchmarks no longer accept a density-composition
runtime override and report the saved value instead.

The ignored `actual_interpreter_pipeline_compile` probe and its interpreter/material
Python campaign scripts take `--definition` and `--dimension`. The probe opens the
actual file, binds its chosen execution preference, and uses loaded requests,
DataVersion and biome identity for every measured region. Reports distinguish
GPU initialization from `load_and_bind_ms`, and include saved seed and composition
semantics. Use a separately compiled false-flag definition to repeat the historical
legacy-composition workloads. Execution/cache/dispatch comparisons continue to use
the same immutable definition.

The opt-in headless test `saved_density_semantics_ignore_environment_overrides`
takes `RETINA_DENSITY_DEFINITIONS` pointing to a JSON object with `composed` and
`baseline` file paths. Each must be compiled independently with the corresponding
flag. It launches fresh, environment-cleared processes using only a copied file,
compares all decoded chunks across absent/0/1 legacy environment values, and also
requires the two explicit generation variants to produce different terrain.

### Embedded NBT fidelity

Template and entity payloads retain their declared NBT numeric widths. Loading
rejects byte/short/int values and byte/int array elements outside their signed
width, and float values that would become infinite when written as a float.
The error identifies the definition, dimension and affected structure template;
the existing file remains untouched. Valid snapshots keep their current behavior
without a format or semantics migration.

Physical-file tests cover numeric boundaries, 64-bit values beyond double's exact
integer range, subnormal floats/doubles, negative zero, arrays, nested compounds
and Java modified UTF-8 names. The Java codec fixture additionally exports actual
Minecraft tags through the template compiler and writes a separate Minecraft NBT
reference. Rust compares the resulting typed binary payload with that reference.

### Flat source fixtures

The flat preset, feature and preview tests publish complete definitions before
binding native generation. Their fixture helper uses the production parameter
compiler and assembler, then borrows runtime palette metadata from the loaded
file. Native bounds, storage bounds and the source generator's depth remain
distinct; requests and DataVersion come from that file. Preview caches drain
before its native handle closes.

Coverage retains all nine presets, empty/custom layers, Minecraft flat terrain
and feature references, clipped dimensions, preview/region parity, player edits,
heightmaps, lighting, and single-biome/shallow noise generators. Structure tests
also check saved set selection and the saved global structures flag. Bare-layer
fixture artifacts are `build/flat-profiles/<preset-or-custom-case>/retina.dat`;
they can be supplied directly to the standalone generator.

### Nether component fixtures and empty generation

The bounds/fluid, carver and vertical-biome checks publish definitions before
native generation. Each interpreter/specialized comparison opens the same file
with a different execution preference and checks that the file stays unchanged.
Bounds fixtures use actual Minecraft noise and dimension codecs, the production
parameter compiler, and palettes resolved from the loaded metadata. Their six
copyable snapshots are `build/nether/bounds-<storage-min>-<storage-height>-<noise-min>-<noise-height>/retina.dat`.

Zero generator depth and zero logical height are valid source settings. The
reader accepts and preserves them while still rejecting negative values.
Storage bounds remain positive and independent: an empty noise volume can still
produce a storage-shaped chunk containing air. This fixes a reader restriction;
it changes neither the file format nor existing valid generation semantics.
The zero-depth fixture was checked against Minecraft's codecs and the prior
native registration path before removing that temporary comparison.

### Saved structure fixtures

`./gradlew :common:structureTest` writes resolved structure fixtures under
`build/structure-definition-fixtures/<structure>/retina.dat` and generates through
the saved-file loader. It checks authentic pools/templates, attachment entity NBT,
placement, concurrent requests, and decoded blocks, 3D biomes, heightmaps, structures,
entities, block entities and ticks across region boundaries. Chunk output defers
lighting until the host has neighbors. Corresponding `region/` directories can be
supplied to the ignored
`copied_definition_matches_structure_regions_in_fresh_processes` headless test
with `RETINA_STRUCTURE_PROOFS`: a JSON array of `name`, `definition`, and `regions`
paths. That test copies only each definition into an empty directory, launches
fresh Rust processes without a JVM or datapacks, and compares every decoded chunk
including lighting and status.

### Interrupted publication

`./gradlew :common:worldDefinitionInterruptedWriteTest` kills a child JVM during
an actual compressed temporary-file write. Rust must reject those partial bytes,
and `retina.dat` must still be absent. The test then publishes the original valid
definition while the orphan remains present and verifies the complete NBT and
Rust-loaded world metadata. It uses the real writer and loader without a GPU;
root `check` includes it.

### Nether terrain and feature fixtures

`netherTerrainTest` and `netherFeatureTest` now publish definitions before native
generation. Terrain, fixed-biome, ore, ordering, spring, survival and fungus
fixtures obtain requests from the saved file. Source generators retain their
actual generation depth and dimension storage; synthetic replay fixtures save
their declared geometry. Interpreter selection for changing replay programs is
an execution binding option.

With `--args=--region`, each saved region directory contains its own copied
`retina.dat`, including the mixed-biome and fixed forest feature cases. Region
output uses the saved seed and DataVersion, checks independent chunk content and
active fluid ticks, and preserves the definition and existing region on repeat.
The terrain fixture's definition is also retained at `build/nether/retina.dat`.
These tasks accept `-PretinaNativePath=/absolute/path/to/native/library`.

`netherStructureTest --args=--contracts-only` isolates saved-file weighted
selection, exclusion dependencies, feature/structure ordering and template
processors. Every tested world seed owns a definition and its native lifetime.
Ordering tests open the same file independently for their cold concurrent pass;
processor and ordered-region captures retain a copy of the definition and use
its DataVersion. Exclusion search bindings use metadata reconstructed from the
loaded definition.

`netherStructureTest --args=--families-only` covers fortress, bastion, fossil and
ruined-portal reference generation plus complete mixed-feature Nether regions.
Each world seed binds its own saved definition, including baseline terrain for
Minecraft's reference pieces. Cold concurrent passes independently open the same
file. Region captures retain that file and use its DataVersion for output.

### End fixtures

The End terrain, platform, island, chorus, spike, gateway, city execution, city
placement and combined-generation tasks now publish structural `retina.dat`
fixtures before native generation. Each world seed owns its saved definition;
requests and runtime palettes come from that file. The independent vanilla
reference fixtures remain separate from native loading. Synthetic feature
variants also go through the same writer and loader.

`endProfileTest` with `-PretinaNativePath=/absolute/path/to/native/library`
retains full and terrain-only definitions under `build/end/native-profile`.
`endRegionPublicationTest` uses the normal world compiler, opens its published
file independently for direct and adapter generation, and checks all four
regions through Minecraft's chunk codecs. Its binding provider permits only
biome and structure identity resolution. The check also preserves a player edit
while repairing one missing chunk. It removes its explicitly disposable MCA
files after validation; saved definitions and receipts remain in its output.

### Registry program and biome fixtures

`biomeTest` and `shoreTest` publish their loaded registry projections before
native generation. Variant noise, climate, material and shore models each get
an explicit seed-bound file. Density tests with different native bounds publish
separate files while retaining the original aligned storage bounds. Shader
execution choice remains a binding option: the specialization comparison opens
one file independently for interpreted and specialized execution.

The biome test retains `build/registry-biome-definition/retina.dat`; the shore
test retains `build/shore-definition/retina.dat`. Full profile JSON is diagnostic
output. `datapackProgramTest -PtestProgramDefinition=/absolute/retina.dat` loads
the saved definition for its program comparisons. These tasks accept
`-PretinaNativePath=/absolute/path/to/native/library`.

The independent region-boundary check opens the same file in a separate world
handle. A controlled saved flat substrate exercises removal of an orphaned tall
seagrass upper half while preserving its replacement single seagrass. The
broader biome test still checks plant partners across generated chunks and the
complete decoded region. `--args=--contracts-only` selects program contracts in
`biomeTest`, or climate, water and sediment contracts in `shoreTest`.

`geologyTest`, `featureTest` and `landscapeTest` also use saved definitions for
ore, cave, ravine, lake, badlands and climate variants. Explicit cave roof,
frequency and amplitude cases have separate definitions. Landscape comparisons
recompile seed-derived data for each world seed before publishing its file.
Lake previews bind their runtime palette from the loaded definition.

`decorationCountTest` and `providerNoiseTest` first publish a retained source
snapshot under `build/decoration-count-*` or `build/provider-noise-*`, then read
that actual file for their synthetic sampling models. Their concurrent seed
independence checks create distinct saved worlds rather than changing a request
seed after loading. Provider-local noise seeds remain part of the saved data.
All five tasks accept `-PretinaNativePath=/absolute/path/to/native/library`.
