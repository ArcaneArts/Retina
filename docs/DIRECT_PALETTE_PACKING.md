# Direct packing of final block palettes

Mixed sections previously built a 4,096-element `u32` palette-index buffer,
then traversed that buffer to produce the packed NBT long array. The final block
IDs and reusable section lookup already contain everything needed for packing.

The native encoder now builds the palette in the same first-occurrence order
without writing that index buffer. Its packing pass reads final `u16` block IDs
and resolves each through the section lookup. Common 4-, 5- and 6-bit widths
retain specialized loops. Uniform sections still emit a single palette entry
and no data array. Quart-biome palettes retain their existing small index buffer.

This removes the mixed-block index allocation and its write/read traversal.
It does not move palette construction ahead of features or maintain speculative
metadata. Heightmaps still consume the final blocks after plants, structures,
snow and grass-support repair. Palette ordering, unused packed bits, NBT bytes,
compression level, save durability and all existing-record behavior are unchanged.
There is no new GPU dispatch, readback record, timing stage or Java/native ABI.
The existing NBT timer includes the changed work.

## Encoding measurements

Apple M4 Max; release Rust build with two Cargo jobs and two codegen units.
Actual final blocks from the full vanilla and Terralith profiles, including
ores, caves, decorations and structures. Four separated chunks from each of
20 measured regions provide 1,920 sections per profile.

The isolated encoder benchmark uses five warmups and 25 paired iterations,
alternating variant order each iteration. It measures palette construction,
material compounds and packed data emission with recycled scratch. Every section
matches the indexed encoder byte for byte before measuring.

| Profile | Indexed median ms | Direct median ms | Direct wins |
| --- | ---: | ---: | ---: |
| Vanilla | 3.919 | 3.601 | 25 / 25 |
| Terralith | 4.008 | 3.689 | 25 / 25 |

This is about an 8% reduction in the exercised **section encoding** time. It is
not an 8% improvement to whole-region generation.

## Complete-region measurements

Baseline is `bf0549e`. Both retained libraries use identical full profiles,
seed 123456789, negative and adjacent region coordinates, Metal and the compact
interpreter. Each run produces 20 regions after two warmups. The second pair
reverses baseline/candidate order. Builds and tests do not overlap benchmarks;
other desktop activity remains active.

| Profile / pair | Before mean region ms | After mean region ms | Before chunks/s | After chunks/s | Before NBT worker ms/chunk | After |
| --- | ---: | ---: | ---: | ---: | ---: | ---: |
| Vanilla / 1 | 256.49 | 252.41 | 3,988 | 4,053 | 0.07090 | 0.06875 |
| Vanilla / 2 | 267.48 | 268.81 | 3,824 | 3,805 | 0.08719 | 0.08277 |
| Terralith / 1 | 546.44 | 519.69 | 1,873 | 1,969 | 0.08551 | 0.07344 |
| Terralith / 2 | 567.51 | 566.41 | 1,803 | 1,807 | 0.08875 | 0.08296 |

NBT worker time decreases in every pair. These are summed parallel worker
elapsed times, not fractions that can be subtracted directly from region latency.
Whole-region throughput varies and the reversed vanilla pair is slightly slower.
The evidence supports less encoding work, **not a reliable overall speedup**.

Two concurrent candidate callers produce vanilla at 4,294 chunks/s with 476.37 ms
mean request latency, and Terralith at 1,849 chunks/s with 1,075.69 ms mean latency.
Concurrent latency includes overlap and is not the reciprocal of throughput.
These runs verify concurrency and output equivalence; they do not establish a
concurrent speedup against the old encoder.

Region file totals remain exactly 153,911,296 bytes for vanilla and 131,751,936
for Terralith in each run. Dynamic upload/readback remains approximately
14.29/45.62 MB per vanilla region and 12.59/41.95 MB per Terralith region;
small timestamp accounting differences remain. No data-plane saving is claimed.

Across serial runs, process initialization takes 40.6–60.9 ms. Profile registration
takes 509.7–590.7 ms for vanilla and 1,001.6–1,127.5 ms for Terralith. First-region
latency is 280.8–285.6 ms and 585.6–616.6 ms respectively. These are fresh processes
with previously compiled GPU shaders; this encoding change does not address cold
profile-specific shader compilation.

Peak RSS ranges from 833–1,456 MiB in the serial vanilla runs and 999–1,082 MiB
for Terralith; concurrent candidate RSS is 866/1,073 MiB. The removed index buffer
is 16 KiB per block-palette scratch instance. Overall RSS is dominated by other
pipeline data and its measured variation; no process-memory reduction is claimed.

## Validation and reproduction

There are 163,840 strict decompressed-NBT comparisons across serial repeats and
concurrent output. All final palettes, blocks, six heightmaps, biome sections,
structure metadata, entities and block entities agree with the baseline.
The unit regression covers every section-reachable palette bit width, high
noncontiguous material IDs, first-occurrence order, last-word padding and recycled
mixed/uniform sections.

The full build, 45 native tests, Minecraft MCA decoding, structure and block-feature
checks pass. Chunk/MCA parity and DH temporary caching, promotion, partial saves,
concurrent saved edits, 1,024-region LRU behavior and failure cleanup pass.
The isolated section benchmark is an additional explicitly invoked ignored test.

Local raw evidence is under `build/goal-baseline/direct-palette/`: before/after
libraries, profiles, all MCAs, timing JSON, section fixtures, paired microbenchmark
JSON, `run-measurements.py`, `checks.json` and `validation.log`. Candidate native
SHA-256 is `0cd94790155967e1721ef182181abae9d17e68148b792ba5f49a82256245d644`.

Extract section fixtures from private benchmark regions:

```sh
python3 scripts/native-palette-fixtures.py \
  --profile build/goal-baseline/direct-palette/vanilla.json \
  --regions build/goal-baseline/direct-palette/vanilla-1-before-1 \
  --output build/goal-baseline/direct-palette/vanilla-sections.bin
# Repeat with Terralith paths.
RETINA_PALETTE_FIXTURES="$PWD/build/goal-baseline/direct-palette" \
  nice -n 10 cargo test --manifest-path native/Cargo.toml --release --locked \
  --target-dir build/native-target --lib actual_section_palette_encoding_benchmark \
  -- --ignored --nocapture
```

Complete runs use `scripts/native-region-benchmark.py` with `--count 20 --warmups 2`,
`--program-execution interpreter`, separate before/after libraries, and
`--compare` against the retained baseline. Use `--parallel 2` for concurrency.
Output directories must be fresh; the harness writes private files only.
