# Column cache encoding and native compression

Private `.columns.z` sidecars now use Retina's existing libdeflater compressor
before the native column buffer is released. Rust packs big-endian records and
writes the compressed result directly, avoiding a second traversal through Java
arrays and per-integer stream writes. Java still decodes the native columns once
for the warm cache. Cold reloads inflate into a reusable 48 KiB batch and decode
triples from that batch instead of making three stream reads per column.

The sidecar remains zlib containing exactly 262,144 big-endian triples of height,
packed flags/biome and material IDs. No format/version change is needed. Both old
and new readers consume the new output, and the new reader consumes old output.
Different compressed bytes are expected; decoded integers remain identical.

## Measured alternatives

Retained GPU columns from vanilla and Terralith, seed 123456789, region (-1,-1),
height 384, each 3 MiB uncompressed. The fixture extractor only samples columns;
it does not generate features, lighting or MCA records. The timed comparison is
CPU-only and invokes the production FFM helper without initializing a GPU.

Each mode has two warmups and seven samples, rotating execution order. Median
write time includes encoding, compressor construction/compression, FFM overhead
where applicable, and the real file write. Inputs are prepared before timing:
production already owns the native column buffer, and the old implementation
already owns its Java arrays. Output is verified outside the write timer.

| Fixture | Old Java stream | Bulk Java alternative | Native path | Native reduction |
| --- | ---: | ---: | ---: | ---: |
| Vanilla | 14.363 ms | 9.425 ms | 4.493 ms | 68.7% |
| Terralith | 15.162 ms | 10.139 ms | 4.616 ms | 69.6% |

Maximum write time across those seven samples changes from 19.475 to 4.737 ms for
vanilla and 16.090 to 4.938 ms for Terralith. The first comparison had a native
Terralith disk-write outlier of 11.953 ms; all samples are retained rather than
filtered. Seven samples do not establish a production p95.

On the same native-compressed file, median cold-read time changes from 9.504 to
3.867 ms (vanilla) and 9.674 to 4.386 ms (Terralith). This includes allocating the
three final arrays. Native files are also smaller: 447,882 → 350,798 bytes and
472,451 → 371,689 bytes, respectively. The bulk Java alternative preserves the old
compressed sizes and remains only in the comparison test.

This is a roughly 10 ms saving in the **column-cache stage** for these fixtures,
not a 69% whole-generator improvement. Filesystem writes use the normal OS cache,
without fsync, matching previous behavior. Raw samples, native encode/compress/I/O
breakdowns, profile/fixture hashes and process resource counters are in
[column-cache-encoding.json](benchmarks/column-cache-encoding.json).

## Bounds, lifetime and errors

The native ABI accepts at most one region of columns, checks null inputs and
returns errors through the existing native error boundary. It borrows the Java
arena's native buffer only for the duration of the call. Each call owns a 3 MiB
encoding buffer at full size, an output buffer sized by libdeflater's compression
bound, and its compressor. All are released before warm-cache array decoding.
There is no retained native cache, extra queue, or new worker pool. The existing
two generation permits bound concurrent production writes to two.

Cold decoding adds a 48 KiB raw batch and 64 KiB compressed-input buffer per reload;
it does not allocate a second full decoded region. The existing warm-entry limits,
lease pinning, LRU eviction and shutdown rules remain in `TemporaryRegions`.
The final comparison's whole-JVM maximum RSS was 157,466,624 bytes and peak
footprint 134,120,384 bytes, including all variants and fixture arrays. These are
not a paired memory reduction claim; native compression uses more transient
scratch than the original streaming Java writer.

`NativeTerrain.generateRegionColumns` writes the sidecar before returning its
columns. Only then does `TemporaryRegions` publish its completed future. Failed
writes remain I/O failures and release/remove the failed entry and its MCA.
Eviction deletes the same sidecars; promotion still copies the original MCA
records, preserves player edits and fills only missing slots. No additional MCA
save, terrain dispatch or change to outer-ring lighting is introduced.

The existing region-cache timing now includes the native call. F3 labels it
**Column cache**, retaining the blue category, rolling denominator and packet
format. `-Dretina.columnCacheDiagnostics=true` prints encoding, compression, file
write, bytes and total call timings for optional inspection.

## Validation and reproduction

All checks ran locally at reduced priority with two compiler jobs/JVM processors,
60-second build deadlines and 30-second runtime deadlines with process-group
cleanup. No client or server was launched.

- `columnCacheTest`: empty/small/batch-edge payloads, arbitrary 32-bit fields,
  Unicode paths, old/new compatibility, truncated/checksum/excess data, invalid
  native pointer/count, real I/O failure, independent concurrent writers and cleanup.
- `columnCacheLifecycleTest`: headless Minecraft classes plus actual native MCA
  generation on four small 16-block-tall stone regions. Tests warm/cold columns,
  no regeneration on reload, LRU deletion, exact decoded NBT on promotion, partial
  saves and player edits, close with a pinned lease, and a forced sidecar write
  failure after MCA creation. All temporary test files are removed.
- `generationMetricsTest`, common Java compilation, native release library/example
  build, `cargo fmt --check`, and `git diff --check` passed.

Use `./gradlew :common:columnCacheTest :common:columnCacheLifecycleTest` for focused
checks. An already-built library can be supplied with `-PretinaNativePath=...`.
Run under the bounds above. For A/B reproduction, `column_cache_fixture` takes a
`retina.dat`, a namespaced dimension and an output path; the `ColumnCacheTest` main accepts the native library
path followed by retained fixture paths. It compares both Java alternatives with
the production native helper. The normal `./gradlew runClient` task is unchanged.
