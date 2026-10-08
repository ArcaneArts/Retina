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
boundaries. Nine invalid-input fixtures check error propagation. This does not
yet prove Minecraft chunk schema, palette packing or MCA-file compatibility;
those remain separate required integration work.
