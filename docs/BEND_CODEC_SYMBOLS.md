# Direct DEFLATE length and distance codes

Each LZ77 match previously constructed and searched two lists for its length
code and another two for its distance code. `bend/compression.bend` now derives
the symbol, extra-bit value and width directly from the integer group.

The grouping follows [RFC 1951 section 3.2.5](https://www.rfc-editor.org/rfc/rfc1951#section-3.2.5):
after the initial literal-length ranges, four length codes share each extra-bit
width; distances have two codes per power-of-two interval. Length 258 keeps its
dedicated terminal code. LZ77 matching, Huffman bit order, checksums, fallback,
parallel batches and resulting bytes are unchanged.

## Validation

`scripts/check_bend_compression_codes.py` compiles two small CPU fixtures using
the pinned stock 2.0.37 compiler, one native compiler at reduced priority, and
at most two runtime threads. One 30-second process-group deadline covers
compilation and all checks. It performs no terrain generation, GPU work, warmup
or repeated performance measurement.

The completed run checks:

- Every legal length (256 values) and distance (32,768 values), emitted from the
  actual production functions and independently compared with the RFC tables.
- 46 stream payloads, including empty, all-byte, overlapping, mixed, repetitive
  and incompressible inputs, through an independent zlib decoder.
- 312 explicit backreferences across every length and all distance boundaries.
- Eight independent streams using one and two workers, with identical results.
- Seven invalid requests, including an oversized chunk payload.
- All 36 saved finished-NBT chunks from the prior comparison, once each. Input
  and golden-output hashes are checked, both streams independently decoded,
  and new compressed bytes compared exactly with the retained worker output.

The run completed in 4.50 seconds including compilation and validation. This is
a correctness-run duration, not a codec performance result. It proves output
equivalence and removal of the source-level list searches; it does not establish
a measured throughput improvement or whole-generator parity.

[The recorded result](benchmarks/bend-codec-symbols.json) includes source,
compiler, native artifact and output hashes. Full artifacts are retained at the
path recorded there. The playable engine is unchanged; this source change and
the frame-buffer allocation change will be included in a later full-worker
build rather than recompiling the large engine after each local edit.

Run with a fresh output directory. The optional `--corpus` names the retained
comparison directory containing `corpus/manifest.json` and `bend/report.json`:

```sh
python3 scripts/check_bend_compression_codes.py \
  --output build/bend/codec-symbol-check --max-seconds 30 \
  --corpus /path/to/compression-corpus-884a2c8
```
