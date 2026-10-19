# Compact final-material lighting input

Metal lighting now uploads one descriptor for each uniform 16×16×16 section,
with an unchanged dense 16-bit material payload only for mixed sections. Shader
lookups reconstruct exact registered material IDs. Attenuation, face occlusion,
emission, propagation, light padding, section packing and MCA output are unchanged.

`RETINA_LIGHTING_INPUT=dense` selects the previous input representation;
`RETINA_LIGHTING_INPUT=compact` enables the new representation explicitly.
Other backends keep dense input by default until measured. No extra device pass,
readback, synchronization, terrain generation or file write is introduced.

## Implementation

Final sections are classified once per region after all blocks are assembled.
Overlapping tile halos reuse those classifications. A high descriptor bit marks
a constant material ID; other descriptors address the mixed-section words in
the same upload allocation. Uniform air, stone, water or emitting sections all
retain their exact ID. Different material IDs are never merged by appearance or
assumed opacity. The two virtual-air padding sections remain on the GPU.

The uniform scan uses a bitwise reduction so the compiler can compare multiple
IDs per instruction. On one retained 12×12 terrain tile, the initial scalar
short-circuit scan took about 2.3 ms versus 0.3–0.5 ms for the reduction, with
identical classifications. This is an isolated scan result, not the region gain.

The CPU tile buffer is reused. The shared GPU input buffer grows geometrically;
growth confined to that input does not rebuild the light frontiers, worklist or
output buffer. Bind groups are invalidated whenever their input changes. Ordered
queue submission and per-flight readback ownership remain the existing design.
At most two tiles per region are outstanding; the production eight-region
admission limit is unchanged.

Uploads feed the existing logical transfer counters. F3's approximate profile
average, ten-second completed-terrain throughput and last-20 latency statistics
are unchanged. Readback volume is unchanged in this cycle.

## Matched results

[Raw results](benchmarks/compact-light-input-2026-10-19.json) include individual
regions, stage counters, profile/library/source hashes, full decompressed NBT
comparisons, fixture measurements and maximum process RSS.

Apple M4 Max / Metal; reduced priority; two region and Rayon workers; six measured
regions at X=-1..1, Z=-1..0; seed 123456789; height 384; separate warmup at (8,8).
All generation comparisons reached the current mixed shader path (status 4).
Executions had 30-second process-group deadlines; compilation had 60 seconds.
No game client or GitHub Actions was used for validation.

Native comparisons use matched **final-crate optimization level 2, LTO off**
libraries, with unchanged dependency/repository release settings. This continues
the bounded build configuration from the vine cycle; do not compare these rates
with historical normal-release reports. The final library built in 22.44 seconds.
Normal-release unit tests retained optimization level 3/thin LTO and built in
44.19 seconds. Neither build setting was changed in the repository.

| Workload | Dense input, chunks/s | Compact input, chunks/s | Interpretation |
| --- | ---: | ---: | --- |
| Vanilla, one caller | 1,979 / 1,982 | 2,064 / 2,086 | About 4–5% higher in these repeats |
| Terralith, one caller | 1,239 / 1,258 | 1,267 / 1,272 | About 1–3%; modest/noisy |
| Terralith, two callers, first pair | 1,425 | 1,628 | About 14% higher |
| Terralith, two callers, reversed repeat | 1,551 | 1,492 | About 4% lower; combined throughput conclusion is inconclusive |

Candidate repeats include the reduction version and the final input-buffer reuse
version; the artifact identifies their distinct binaries. Sequential pair order
was varied, and the concurrent repeat reversed candidate/reference order. These
are native publication rates, not Minecraft loading/rendering throughput.
Six-sample p95 observations are retained without claiming stable production tails.

Lighting host time, excluding the NBT consumer callback, fell from about 39–42 to
28–29 ms/region for vanilla and 41–46 to 30–31 ms for serial Terralith. In the two
concurrent pairs it fell from 46–47 to 32–41 ms. GPU propagation itself is slightly
slower because mixed-material lookups require an extra descriptor read; the
benefit comes from less host packing/upload work. These spans overlap other
stages and must not be added together as an overall speedup.

Total logical traffic per region falls from **392.92 to 243.14 MiB** for vanilla
and **400.61 to about 252.02 MiB** for Terralith: roughly 37–38% less. The Terralith
reference includes the preceding vine optimization. This is logical API payload,
not measured physical memory-bus bandwidth.

No total-memory reduction is claimed. Serial process peaks were about 1.72–1.86
GiB for baseline vanilla and 1.74–1.87 GiB for compact input; Terralith was about
2.50–2.75 versus 2.90–2.97 GiB. Concurrent peaks were 2.80–2.88 versus 2.91–2.93
GiB. These peaks include shader compilation/caches and cannot isolate transport
allocation savings. Input uploads shrink, but the original final-block arrays,
propagation frontiers and full light readbacks still exist.

## Validation and limitations

- 79 normal unit tests passed, plus four actual-GPU lighting tests.
- CPU decoding checks preserve uniform and mixed material IDs, including 65535,
  single differences at section/vector boundaries and shrinking buffer reuse.
- GPU comparisons cover registered faces, emitters, transparency, sky/no-sky,
  cropping and padding, interleaved sizes/profiles, out-of-order completion,
  abandonment, consumer failure/recovery and all 900 interior/124 border slots.
- All 15 retained Minecraft-derived fixture outputs match the prior library.
- Every candidate/concurrent native corpus matches all 6,144 decompressed NBT
  records against its reference, including all saved light arrays.

The first scalar-scan implementation reduced transfers but increased lighting
preparation cost and provided no established throughput benefit; it was replaced.
The standalone lighting diagnostic API receives preassembled dense blocks, so
compaction adds packing it did not previously perform. Its final saved-tile host
times remain about 5–8% slower, despite byte-identical output. Normal region
generation already packed overlapping dense tiles; its new path reuses section
classifications and avoids that dense copy. The report preserves both results.

Reproduce complete-region comparisons with `scripts/native-region-benchmark.py`,
separate output directories, retained matched libraries and `--compare`. Set
`--readiness shader-ready`, two workers and the documented deadlines. The lighting
fixture script now retains raw samples and input/library hashes; use
`--reference-library`, `--modes reference sparse`, one warmup and five iterations.

Uniform light-output descriptors, output transport, maximum eight-caller stress,
DH/Chunky combined lifecycle validation and the remaining optimization areas are
still pending. See the [cycle tracker](OPTIMIZATION_CYCLES_2026_10.md).
