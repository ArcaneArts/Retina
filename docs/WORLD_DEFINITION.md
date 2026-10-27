# Persistent world definitions

`<world-root>/retina.dat` is the contract for persistent, seed-bound generation
input. One file contains shared types and every dimension's definition. The
Rust `world_definition` module reads it without initializing Minecraft, loading
datapacks, or creating a GPU device.

Implementation is being delivered in separate cycles. This first cycle adds
the format and reader. Automatic Java compilation, mod startup integration,
and the standalone runner follow; ordinary worlds still use the existing
profile registration path until that integration is merged.

## Version 1 wire format

The file is a single gzip member containing a Java-edition named-root NBT
compound. Writers use high compression. The root's name is not significant.
Field names are case-sensitive and use snake_case. NBT numbers are big-endian.

| Root field | NBT type | Meaning |
| --- | --- | --- |
| `format_version` | Int | `1` |
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

`parameters` contains String `generator`; Int `min_y`, `height`,
`generator_depth`, `sea_level`, `spawn_height`, `logical_height`; boolean Byte
`skylight`, `ceiling`; Double `coordinate_scale`; and Float `base_height`,
`amplitude`, `frequency`. The last three preserve existing procedural request
parameters during migration. Requests take their seed and settings from the
loaded definition and add coordinates plus a registered native profile handle.

## Structural profile encoding

The profile carries the existing generation model, including optional End,
flat, structure and feature data. It omits `materials`, which is resolved from
the shared palette, and `mca_compression`, which is an execution option. Its
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

The decoded profile enters the same native validation and initialization used
by the existing generation engine. The loader resolves local/shared palette
references before initialization. A SHA-256 fingerprint of the uncompressed
file plus the dimension key identifies the resident profile; it is stable
when reopening the same artifact and includes its seed and shared palettes.

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

The remaining delivery cycles must prove that normal mod creation writes the
file and immediately reopens it through Rust; reopening skips compilation;
all generation inputs come from the saved definition; and a fresh Rust
process reproduces generated regions with only `retina.dat` available.
