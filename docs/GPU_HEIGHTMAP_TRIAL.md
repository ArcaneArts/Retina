# GPU final-heightmap trial

The prototype generated and packed all six final heightmaps on the GPU using the
blocks already uploaded for lighting. It preserved every compared chunk's NBT,
and reduced NBT worker time in most measurements. It is **rejected for production**:
vanilla throughput improved in two comparisons, but dense Terralith throughput
fell in both orders. The existing CPU heightmap encoder remains enabled.

This implements the heightmap portion of the final-section GPU experiment. GPU
palette construction and compact block-section output remain separate work; this
trial does not close those requirements or the overall optimization goal.

The [source and test patch](benchmarks/gpu-heightmap-trial.patch) applies to
`c4d2e44` using `git apply --unidiff-zero`. The
[raw evidence](benchmarks/gpu-heightmap-trial-2026-10-19.json) includes individual
region samples, stage and transfer counters, hashes, build/test logs, and a
same-process diagnostic harness. No experimental runtime switch or shader is
installed in production by this documentation change.

## Implemented approach

The loaded profile's exact six-bit heightmap predicates occupied the unused word
of each resident lighting material state. This preserved arbitrary material
semantics and required no extra material upload or shader binding.

A GPU invocation scanned one final column from top to bottom, resolving all six
predicates independently. Compact material input allowed uniform sections to be
handled with one descriptor; mixed sections retained exact material reads. A
second pass packed the results into Minecraft's non-straddling long arrays and
NBT big-endian byte order using two `u32` halves per long. No native shader `u64`
support was required.

The raw and packed heights occupied disjoint tails of the lighting output buffer.
Pass boundaries provided visibility between scanning and packing. Only the packed
heightmaps returned with the existing light readback; there was no additional
host synchronization or terrain upload. Each pending tile retained its own
heightmap offsets. Rust copied the packed arrays directly into NBT and skipped
its final-block heightmap scan for the 900 GPU-lit interior chunks. The outer 124
chunks and non-lighted paths retained the original CPU encoder.

At world height 384, each interior chunk returned 1,776 packed heightmap bytes:
**1,598,400 bytes / 1.52 MiB per region**. The standard 100-chunk tile needed
614,400 raw scratch bytes plus 177,600 packed bytes on the device, before buffer
capacity rounding. The prototype added two passes per tile (18 per standard
region). The existing lighting-pack device timestamp span included these passes;
lighting host and logical readback counters also included the additional work.

## Correctness

- 97 ordinary Rust tests passed; 37 explicit GPU/manual tests remained ignored.
- The new real-GPU correctness test passed 12 payload variants: six fixtures
  with both dense input/output and compact input/output. Heights were 16, 32,
  80, 256, 384 and 512. Cases included empty and uniform sections, mixed
  sections, predicate combinations distinct from opacity, material ID 65,535,
  registered air matching height predicates, and top/bottom boundary hits.
- All 40 returned chunk-heightmap sets matched an independent scalar reference
  and independent `u64` packing. Expanded light arrays matched the original
  lighting output byte for byte. Cropping, profile changes, buffer growth and
  reverse completion of pending flights were exercised.
- Four baseline/candidate full-region comparisons matched **30,720 complete
  decompressed NBT records**, including final blocks, all six heightmaps, biomes,
  structures and lighting. These were actual generated vanilla/Terralith regions
  including negative coordinates and minimum Y −64.
- A separate same-process CPU/GPU toggle matched another **6,144 complete NBT
  records**, bringing the total to **36,864**.

No eight-caller stress, Java reader/lifecycle check, or in-game test was run after
the production decision became negative. No claim of those checks is made.

## Complete generation measurements

Apple M4 Max / Metal, normal optimization-level-3/thin-LTO release builds, two
CPU workers and two concurrent region callers, nice +10. Seed 123456789, height
384, zlib output, one warmup at (8,8). Measured regions started at (−1,−1), with a
three-region row width. The GPU interpreter and disabled interpreter preload
kept asynchronous specialization out of the matched comparisons. These are
interpreter-mode native MCA-publication measurements, not default specialized
mode, Java activation, or rendering measurements.

| Workload | Order | Reference chunks/s | GPU heights chunks/s | Change | NBT worker ms/region, reference → GPU |
| --- | --- | ---: | ---: | ---: | ---: |
| Vanilla, 6 regions | Reference, GPU | 1,718.4 | 1,834.1 | +6.7% | 67.17 → 58.47 |
| Vanilla, 12 regions | GPU, reference | 1,774.6 | 1,848.2 | +4.1% | 67.42 → 59.52 |
| Terralith, 6 regions | Reference, GPU | 820.8 | 767.6 | −6.5% | 71.65 → 67.86 |
| Terralith, 6 regions | GPU, reference | 809.2 | 746.2 | −7.8% | 72.41 → 73.41 |

The vanilla NBT savings were approximately 12–13%. Terralith's first NBT result
improved 5.3%, but the repeat was 1.4% slower. Full-pipeline timings moved more
than the heightmap work: vanilla GPU host wait/copy fell by about 122 and 40 ms
per region; Terralith lighting host time rose by about 95 and 11 ms. Decoration
planning also varied, especially in the second Terralith pair. This evidence does
not isolate the heightmap kernel as the cause of every throughput change. It does
not justify enabling the prototype across supported workloads.

The same-process toggle used CPU/GPU/GPU/CPU order over two matched coordinate
sets. Its later visit to each set reused structure and GPU-field caches, producing
large unrelated stage changes. Those rates are retained as diagnostic data, not
as a valid matched-cache throughput comparison. Both coordinate pairs still
supply exact NBT correctness evidence. Future same-process throughput tests must
control those caches before interpreting rates.

Peak process RSS in the four separate-process pairs was respectively
1.75 → 2.07, 2.29 → 2.11, 1.64 → 2.03 and 1.99 → 1.62 GiB. These peaks include
Python corpus validation; they do not isolate native allocation. No process-memory
improvement is claimed. Logical readback increased by the expected 1.52 MiB per
region, with small timestamp-accounting variation; material upload sizes and MCA
output sizes were unchanged.

## Reproduction

Apply the retained patch to an isolated checkout of `c4d2e44`. Build the reference
before applying it, and retain both libraries separately. Use normal release
settings and the shared two-worker limits. Enable only the candidate using
`RETINA_GPU_HEIGHTMAPS=1`.

Build tests with `cargo test --release --locked --manifest-path native/Cargo.toml
--lib --no-run`, then run the produced executable with two test workers. Run
`gpu_final_heightmaps_match_all_predicates_packing_and_lighting --ignored
--nocapture --test-threads=1` explicitly for actual GPU correctness.

Run `scripts/native-region-benchmark.py` with `--parallel 2 --count 6` (12 for the
second vanilla pair), `--warmups 1 --program-execution interpreter
--interpreter-preload disabled`, the same exported profile on both sides, separate
output directories, and `--compare` against the paired corpus. Library/profile
hashes and every region coordinate are retained in the raw evidence.

The reference library SHA-256 was
`374aff36354a94073db71fee93639faf837c303c8b51ee95cb917ffa499d6315`;
the candidate was
`ec9d0236eaa9c5db9b9a3829c95385ad49aff6559ed250aa1ae2bf6a1483deb8`.
Native jobs used two Cargo jobs/two CPU workers, reduced priority, 60-second build
and 30-second execution deadlines, with process-group cleanup on timeout. No run
timed out. No game or GitHub Actions was launched.
