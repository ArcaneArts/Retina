# Compact final-light output

Metal region lighting now skips packing and reading back section channels whose
exact values can be proved from final material sections. Unknown channels retain
their complete GPU-computed nibble arrays. The NBT writer consumes either form
directly and writes the same chunk records, including both padding sections.

`RETINA_LIGHTING_OUTPUT=dense` selects the previous full output representation;
`RETINA_LIGHTING_OUTPUT=compact` explicitly enables compact output. Other backends
keep dense output by default until measured. The standalone diagnostic lighting
API still returns full arrays. Input compaction has its own independent switch.

## Exact proofs and ownership

The existing region-wide material classification supplies conservative facts:

- Uniform fully opaque sections retain their registered emission and zero sky.
- Uniform maximum-emission sections retain block light 15.
- Sky-disabled profiles have zero sky light. Uniform registered air above only
  air has sky 15 only if its attenuation and downward face pair allow it.
- Block light is zero where no possible source exists in the surrounding
  3×3×3 sections. Mixed sections count as possible sources. The nearest voxel
  in a section two away is at least 17 edges away; positive block light cannot
  travel more than 14 edges. Virtual padding uses registered air properties too.

Each flight owns its section descriptors and compact readback. A small reverse
map is appended to the existing material upload. The packing dispatch visits
only channels requiring dense data; there is no classification readback or extra
CPU/GPU synchronization. Fully constant output uses a four-byte binding placeholder.
NBT omits zero arrays and writes constant nonzero arrays directly into its buffer.

Input/output buffer growth preserves the propagation frontiers and worklists.
Geometric output/readback capacity is capped at the corresponding dense output
size. Two tiles per region remain outstanding; the production eight-region limit,
900 lit interior chunks, Minecraft-lit border ring, final blocks and single MCA
publication are unchanged. Logical byte counters include the small reverse map
and actual readback length, so F3 automatically reports the reduced traffic.

## Matched measurements

[Raw measurements](benchmarks/compact-light-output-2026-10-19.json) retain all
trials, including the first slower prototype, individual region times, hashes,
stage counters, sample tails, process peak RSS and decompressed-NBT comparisons.
The reference is merged PR #119 (`8242418`), with compact lighting input enabled.

Apple M4 Max / Metal; two shared region workers and two Rayon workers; `nice +10`;
seed 123456789; height 384; six measured regions X=-1..1, Z=-1..0, and a separate
warmup at (8,8). Both sides reached mixed shader readiness (status 4). Each
execution had a 30-second process-group deadline; builds had 60 seconds. No
Minecraft launch or GitHub Actions was used.

Native libraries use matched final-crate optimization level 2 with LTO disabled,
as in the preceding cycles, to keep compilation within the deadline. Dependency
and repository release settings are unchanged. Normal-release unit/GPU tests
use the usual optimization level 3/thin LTO. Do not compare these rates directly
with historical normal-release benchmark rates.

| Workload | Dense output, chunks/s | Compact output, chunks/s | Interpretation |
| --- | ---: | ---: | --- |
| Vanilla, one caller, initial prototype | 2,029 | 2,023 | Essentially unchanged |
| Vanilla, one caller, buffer reuse | 2,072 | 2,031 | About 2% lower; no serial benefit established |
| Terralith, one caller, initial prototype | 1,262 | 1,231 | Initial allocation overhead; superseded |
| Terralith, one caller, buffer reuse | 1,262 | 1,266 | Same earlier reference; not another independent pair |
| Terralith, two callers | 1,492 | 1,568 | About 5% higher |
| Terralith, two callers, reversed order | 1,434 | 1,631 | About 14% higher; magnitude varies |
| Vanilla, two callers | 2,630 | 2,600 | About 1% lower; no benefit established |

Rates use completed chunks divided by measured batch wall time, through native
MCA publication. They do not describe Java loading or rendering. Six-sample tail
observations are not stable production p95 estimates. These results support a
concurrent Terralith benefit, not a universal speedup or a prediction for eight
callers. The initial implementation repeatedly recreated propagation buffers as
compact output grew; independent geometric buffer growth replaced that version.

Vanilla total logical traffic falls from **243.14 to 190.30 MiB/region**;
Terralith falls from **252.02 to about 200.25 MiB/region**. Uploads rise by roughly
0.1 MiB for the reverse map; readback falls by approximately 52–53 MiB. This is
logical API payload, not physical memory-bus traffic on the unified-memory GPU.

Packing device time falls from roughly 0.8 to 0.4 ms/region. Concurrent NBT worker
time falls from about 95–99 to 88–91 ms/region in the first pairs. These overlapping
and summed worker spans must not be added together as an overall speedup. Host
lighting spans do not improve consistently; faster consumers can expose more
waiting on the same GPU work.

No process-memory reduction is claimed. Serial vanilla peaks were 1.78–1.86 GiB
for the reference and 1.96–2.04 GiB for the candidates. Serial Terralith was
2.67 versus 2.74–2.90 GiB. Concurrent Terralith was 2.57–2.75 versus 2.98–3.05 GiB;
concurrent vanilla was 2.07 versus 2.01 GiB. The final default-mode confirmation
reached 1,614 chunks/s and 3.25 GiB peak; it is not an additional matched pair.
These process-wide peaks include
shader preparation, caches and overlapping jobs; they do not isolate light
buffers. The higher Terralith observations are retained as a limitation.

## Validation

- 82 normal unit tests, including all 256 constant block/sky NBT-array pairs
  and default/override selection for backend strings containing adapter names.
- Five real-GPU lighting tests. The compact-output comparison covers 48 scenes
  with sky/no-sky, uniform/mixed/opaque/emitting materials, registered faces,
  unusual air emission/attenuation, a boundary emitter, cropping, virtual padding,
  varying sizes/profiles, and reverse completion of pending flights.
- The compact-output comparison also passes with dense propagation selected.
- Region streaming tests exercise all 900 interior and 124 border slots,
  abandoned flights, consumer failure and subsequent reuse.
- Every candidate generation corpus matches all 6,144 decompressed NBT records
  against the retained reference, including final blocks and all saved light data.

Use `scripts/native-region-benchmark.py` with separate output directories,
`--readiness shader-ready`, matched profile/build/worker settings, and the
`RETINA_LIGHTING_OUTPUT` override to reproduce comparisons. The artifact records
exact library/profile hashes and identifies each implementation variant. A default-mode
smoke check initially exposed an exact-string backend comparison that left output
compaction disabled. The shared input/output selector now accepts the real
`Metal: <adapter>` format, with a unit test and a full default-mode corpus check.
That disabled trial is retained separately and is not compact-output evidence.

This completes the evaluated input/output transport changes, not the overall
performance goal. Shared resident halo storage is deferred: it needs new lifetime
and memory ownership across final-block assembly and up to eight region jobs.
The remaining measured targets are material-count synchronization, ore masks,
final-section encoding, and combined pipeline validation.
