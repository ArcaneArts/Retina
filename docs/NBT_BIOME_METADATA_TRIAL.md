# Chunk biome metadata trial

The prototype reduced isolated NBT encoding time by 4–6% and full-region NBT
worker time by 2–5%, but did not improve complete generation. It is rejected for
production. The existing encoder remains enabled.

This follows the earlier [section encoding trial](SECTION_ENCODING_TRIAL.md).
That trial combined per-section bulk biome reads with copied lighting-section
classifications. This experiment instead prepared the immutable biome address
metadata once per chunk, unpacked four adjacent samples from two or three packed
words, and reused already-serialized biome compounds for identical consecutive
sections. It did not copy lighting classifications, allocate extra heap storage,
add GPU work, or retain a cache between chunks.

The [complete source/test patch](benchmarks/nbt-biome-view-trial.patch) applies to
`7b82e9c` with `git apply --unidiff-zero`. It is retained as experimental evidence,
not an enabled alternative. [Raw measurements and test/build logs](benchmarks/nbt-biome-view-trial-2026-10-19.json)
include individual region latencies, stage counters, corpus hashes, transfers and
peak process RSS.

## Implementation and correctness

The prepared view stored the quart-plane offset, dimensions, horizontal address,
vertical origin, and sixteen surface fallback samples once. Whole covered sections
used sixteen packed row reads in x/z/y order. Partial coverage retained scalar
queries and the original per-sample fallback. Wide coordinate arithmetic was used
for preparation; the actual world-coordinate sampling semantics were preserved.

The encoder compared each section's 64 biome IDs with the previous section. On
equality it copied the previous serialized biome compound within its existing NBT
buffer. Otherwise it built the original first-occurrence palette and packed data.
The cache was local to one chunk encoding, so profile changes or reused worker
scratch could not reuse obsolete biome names. Block palettes, final heightmaps,
lighting arrays, structure/tick data, compression and publication were unchanged.

Validation:

- 96 ordinary Rust tests passed; 37 GPU/manual fixtures remained explicitly ignored.
- Prepared versus scalar samples matched across partial masks, odd/negative Y,
  both packed-word halves, odd row strides, all axes, and coordinates near ±30M.
- Complete NBT matched with repeated/changing sections, no/full/partial masks,
  changed biome names with the same numeric IDs, and reused scratch after changed
  material palettes. A test-only scalar switch supplied the reference encoder.
- The explicit isolated comparison passed exact output equality after every pair.
- Full decompressed NBT matched for 24,576 chunk comparisons: a six-region vanilla
  pair, a twelve-region reversed-order vanilla pair, and a six-region Terralith pair.
  These full regions include actual GPU lighting and structures, not only selected tags.

## Encoding results

The isolated fixture encoded the same synthetic height-96 unlit chunk 1,024 times
per sample. Two warmup pairs preceded ten alternating-order measured pairs, using
normal optimization-level-3/thin-LTO release settings. These are encoding-only
measurements, not independently generated region rates.

| Section biome samples | Scalar median | Candidate median | Encoding-time reduction |
| --- | ---: | ---: | ---: |
| Repeated between sections | 19.00 ms | 17.82 ms | 6.2% |
| Changed between sections | 18.81 ms | 17.99 ms | 4.4% |

## Complete native generation

Apple M4 Max / Metal, two CPU worker-pool threads and two concurrent callers,
nice +10, seed 123456789, height 384, one warmup region at (8,8), zlib output.
Both libraries used the same normal release settings. The GPU interpreter was
selected explicitly, with interpreter preload disabled, to keep asynchronous
specialization out of the comparison. These results end at atomic MCA publication;
Java activation, DH conversion and rendering are excluded.

| Profile / regions | Order | Reference chunks/s | Candidate chunks/s | Change | NBT worker ms/region, reference → candidate |
| --- | --- | ---: | ---: | ---: | ---: |
| Vanilla / 6 | Reference, candidate | 1,819.8 | 1,698.5 | −6.7% | 67.61 → 65.44 |
| Vanilla / 12 | Candidate, reference | 1,875.7 | 1,769.5 | −5.7% | 69.75 → 66.61 |
| Terralith / 6 | Reference, candidate | 772.8 | 744.7 | −3.6% | 76.57 → 74.74 |

The small NBT savings did not survive the full-pipeline measurement. GPU host
wait/copy time changed substantially (roughly +85 and +103 ms per region in the
vanilla comparisons), while several planning stages became faster. This does not
prove that biome metadata reuse itself caused the whole-generator slowdown; it
does establish that these runs do not support shipping it as a throughput gain.
The decision was already negative, so no additional eight-caller or Java-reader
benchmark was run for this rejected candidate.

Peak process RSS was 1.49 → 1.83 GiB, 2.21 → 2.29 GiB, and 1.70 → 1.84 GiB
respectively. These figures include Python corpus decoding/comparison and do not
isolate the generator's heap. The prototype adds only temporary stack state and
does not claim a generator-memory improvement. Logical transfer counters and
complete corpus details are retained in the raw results; no transport format changed.

## Reproduction and next work

Apply the zero-context patch in an isolated checkout of `7b82e9c`:

```sh
git apply --check --unidiff-zero docs/benchmarks/nbt-biome-view-trial.patch
git apply --unidiff-zero docs/benchmarks/nbt-biome-view-trial.patch
cargo test --release --locked --manifest-path native/Cargo.toml --lib --no-run
```

Run the resulting test executable with two test workers. Run
`biome_metadata_encoding_comparison --ignored --nocapture --test-threads=1` for
the isolated comparison. Use the native benchmark script with `--parallel 2`,
`--warmups 1`, `--program-execution interpreter --interpreter-preload disabled`,
and counts 6 or 12. Match profiles/coordinates, retain each library before rebuilding,
and use `--compare` against the paired corpus for complete decoded NBT equality.

The reference library SHA-256 was
`47aed5223e10387b87c3672cd3e3741718f74bdaf1c5ef3c698d534707010803`;
the candidate was
`7bb9fcbac2ad29596e573e9bb37b5004d1eea597aa449bd50d979b74e3800c5d`.
All jobs ran sequentially with two Cargo jobs/two CPU workers, reduced priority,
60-second build and 30-second execution deadlines plus process-group cleanup.
No execution timed out, no game was launched, and no GitHub Actions was requested.

GPU final heightmaps, palettes and compact final-section output remain separate
required experiments. Those can target the larger final-block scan/packing cost
using data already uploaded for lighting, rather than repeating this metadata-only
approach. The overall performance goal is not complete.

The subsequent [GPU heightmap trial](GPU_HEIGHTMAP_TRIAL.md) implemented exact
GPU scanning and compact NBT heightmap output from lighting's final blocks. It
passed full NBT comparisons but was rejected after dense Terralith regressions;
GPU palettes and block-section packing remain to be evaluated.
