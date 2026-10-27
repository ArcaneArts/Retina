# Post-investigation optimization integration — October 20, 2026

The implementation workstreams are closed with measured production changes or
implemented, tested rejections. The final combined comparison shows **3.1–6.1%
more native vanilla throughput** in two reversed-order eight-caller pairs.
Terralith is **−2.4% / +4.2%**, an inconclusive complete-throughput result.
NBT worker time is approximately halved in both workloads. No universal speedup,
whole-process memory reduction, or live-game loading-rate improvement is claimed.

The retained changes are compiled material predicates, compact GPU continuations,
corrected fallback diagnostics, optional LZ4 MCA output, Metal GPU section palettes,
and bounded Metal vine replay with immutable substrate reuse. Zlib remains the
default. No terrain settings, feature attempts, random streams, lighting coverage,
or generation quality were reduced to obtain these results.

[Raw evidence](benchmarks/post-investigation-final-2026-10-20.json) includes all
samples, pinned binary/profile hashes, complete-output comparisons, test logs,
failed invocations, resource-limit runners, and merged-PR/Actions audit results.
The [workstream tracker](POST_INVESTIGATION_OPTIMIZATIONS.md) links each experiment.

## Combined reference and measurement boundary

Source revision is `93b886646a528f020f422a7b5033002dcbcb180c`. Its production native
sources are identical to `58f6496`; the intervening cycle archived a rejected
evaluator and included tick IDs in ordered-plan benchmark exports.

The reference is a **current-source counterfactual**, not the historical starting
revision. The [reference patch](benchmarks/final-performance-reference.patch)
restores binary-search material membership and expanded pending GPU queues.
Runtime overrides disable GPU palettes, ready batching, and substrate reuse:

```sh
RETINA_GPU_PALETTES=0
RETINA_DECORATION_READY_BATCH=0
RETINA_DECORATION_SUBSTRATE_REUSE=0
```

Both sides retain current correctness fixes, the queue-boundary handoff fix,
material interning, diagnostics, unrelated Nether/automatic-generation changes,
and current codec code. Both use zlib unless explicitly labeled LZ4. Thus this
comparison does not measure every earlier change, including interning, against
its original implementation. Per-cycle reports provide those comparisons.

Native libraries use optimization level 3, thin LTO and two codegen units. The
planner executables disable only their final executable LTO; optimized native
dependencies match each library variant. Test-only compilation used an
unoptimized root crate with optimized dependencies and is not a performance build.

Apple M4 Max / Metal; two Cargo jobs, two Rayon/region CPU workers, nice +10;
heavy experiments run sequentially. Eight callers still share two CPU workers
and the existing GPU submission limits. Every execution has a 30-second deadline;
native builds have a 60-second deadline, with TERM and then KILL after one second.
These settings do not cap driver/compiler-internal threads at two cores.

Each pair generates eight regions with eight callers, seed 123456789, minimum Y
−64, height 384, grid width four starting at region (−1,−1), and a separate warmup
at (8,8). Vanilla auto mode reaches shader-ready status 4 before measurement.
Terralith explicitly uses interpreter mode with interpreter preload disabled.
No Terralith shader-ready throughput claim is made.

Rates are completed chunks divided by batch wall time through native MCA
publication. Startup, post-run comparison, Java column-cache work, Minecraft
loading, DH conversion, and rendering are outside this boundary. Region latency
is reported independently; dividing 1,024 by concurrent region latency is not
the throughput calculation. Process RSS includes the Python comparison harness.

## Final results

Pair A runs reference then candidate; pair B reverses that order. All 32,768
paired chunk comparisons match complete decompressed NBT bytes. This counts
repeated comparisons of fixed corpora, not 32,768 distinct terrain chunks.

| Profile / pair | Reference chunks/s | Candidate chunks/s | Change | Mean region ms, before → after | P95 region ms, before → after |
| --- | ---: | ---: | ---: | ---: | ---: |
| Vanilla A | 2,634.8 | 2,715.5 | +3.1% | 2,331 → 2,133 | 3,094 → 2,950 |
| Vanilla B | 2,668.3 | 2,831.9 | +6.1% | 2,235 → 2,199 | 3,033 → 2,858 |
| Terralith A | 641.5 | 626.3 | −2.4% | 9,925 → 10,362 | 12,583 → 12,894 |
| Terralith B | 622.3 | 648.7 | +4.2% | 10,366 → 10,076 | 13,044 → 12,455 |

| Profile / pair | Peak process GiB, before → after | Logical transfer MiB/region, before → after | NBT worker ms/region, before → after |
| --- | ---: | ---: | ---: |
| Vanilla A | 2.067 → 2.146 | 191.09 → 213.86 | 70.2 → 35.7 |
| Vanilla B | 2.256 → 2.092 | 191.09 → 213.95 | 71.9 → 37.1 |
| Terralith A | 1.991 → 2.276 | 200.01 → 223.19 | 74.7 → 39.0 |
| Terralith B | 1.805 → 1.710 | 200.02 → 223.19 | 76.2 → 38.6 |

The additional approximately 23 MiB is primarily palette/packed-block readback.
It exchanges GPU transfer for less CPU encoding. Logical bytes are not measured
physical bus traffic on unified-memory hardware. Memory varies in both directions.
Sector-padded output is identical within each zlib pair: 71,233,536 bytes for the
eight vanilla regions and 63,922,176 for Terralith.

Planning wall counters include contention: vanilla changes 640.4 → 636.8 and
440.2 → 415.3 ms/region; Terralith changes 1,047.9 → 1,093.8 and 645.6 → 1,088.8.
These overlapping counters do not establish an isolated CPU cost. Interpreted
terrain GPU wait/readback remains substantial in Terralith. Component gains from
other reports must not be added to the complete-throughput percentages above.

### Ordered planning and causes

A final isolated planner comparison uses one warmup, three measured plans, then
separate normal/detailed plan comparison and binary export. Vanilla starts at
chunk (−32,−32), Terralith at (0,0); both cover 32×32 chunks plus the planner halo.
Actual terrain is prepared before timing. Interpreter preload is disabled.

| Fixture | Median planning ms, before → after | CPU candidate evaluations, before → after | Ordered placements |
| --- | ---: | ---: | ---: |
| Vanilla | 88.79 → 84.32 | 606,828 → 606,828 | 246,425 |
| Dense Terralith | 430.52 → 344.21 | 4,835,932 → 2,412,278 | 485,663 |

Every chunk's vector matches byte-for-byte, including index, material, upper
material, role and tick. Detailed instrumentation also preserves every placement.
These are one pair per planner fixture, not a substitute for the complete-region
results. The dense fixture's saved-prefix peak drops from 64.0 to 18.45 MiB, but
the candidate additionally retains 25.45 MiB of immutable substrate within the
same 64 MiB combined budget. It is not a claim that all process memory shrank.

Decoration planning was expensive partly because expanded pending GPU groups
were evaluated/replayed on the CPU. Predicate checks and ordered queue handling
also matter; it was not simply tree geometry or one block lookup. The corrected
diagnostics identify 48 remaining missing-material anchors in the final dense
candidate, with zero queue/output overflow anchors. Increasing output buffers
would not resolve those missing inputs. The rejected guard-input and evaluator
trials document why their additional work was not enabled.

### Optional LZ4 integration

A further eight-caller Terralith LZ4 run matches all **8,192** complete NBT records
against the current zlib corpus, bringing final full-NBT comparisons to **40,960**.
Files increase from 63,922,176 to 88,113,152 bytes, **37.8% larger**. Observed
compression worker time is 0.0831 versus 0.0465 ms/chunk. The runs are separated in
time, so their 648.7 versus 638.4 chunks/s is not a matched throughput conclusion.
The [codec cycle](MCA_COMPRESSION.md) contains matched generation and Minecraft
read/decompression measurements. Zlib remains default; `mca_compression = "lz4"`
is available for users accepting larger saves.

## Current-source validation

- 107 ordinary Rust tests pass; 37 opt-in hardware/manual cases are excluded from
  that count. Separate runs pass six actual-Metal lighting/palette tests, the
  actual-Metal vine handoff test, and missing-material/overflow classification.
- The handoff test covers origins 0, −17 and 1,000,000, live prior writes,
  providers, windows 1/4/9, zero/default retention, discarded successful prefixes,
  disabled device handling, output/queue overflow, interleaved substrates and
  lost buffers. Material-set tests cover all u16 IDs and unknown-input semantics.
- Minecraft lighting checks compare **4,128,768 light values**, including dynamic
  edits and prelit seams. A region is published once with 900 lit interior chunks
  and 124 unlit boundary chunks. The fixture's tiny load timing is not advertised
  as general world-load performance.
- Minecraft's real MCA reader validates both codecs, complete NBT equality,
  mixed-codec promotion in both directions, edits, partial repair, unsupported
  cache-codec atomicity, and eight concurrent cache callers. Region and column
  lifecycle tests cover shutdown, lease/reload behavior and real I/O failures.
- DH-style preview integration checks full temporary MCA generation, surface
  queries, promotion without another GPU generation, mixed requests, LRU,
  placeholders/partial promotion, saved edits and shutdown/failure cleanup.
- Five tests against installed Chunky-Fabric 1.5.3 pass without skips, including
  its real selection iterators, GenerationTask mixin and region I/O. Two separate
  startup mixin tests pass. Published MCA records exist before queued loads.
- Scheduler/configuration/F3 tests pass: eight-region admission, deduplication,
  cancellation, shutdown, codec configuration and ten-second wall-window metrics.
- Actual Terralith loading passes automatic adaptation/promotion, registry and
  climate-index parity (32 requests, three seeds), and terrain distribution plus
  MCA/save-reopen checks (151 biomes, 901 materials, wide IDs). The decoration
  phase checks registered density and sediment replacement rules.
- The standalone exported-profile program task passes all original 24 requests
  and three seeds for vanilla, with 2,780 nodes emitted by actual specialization.
- All nine Python benchmark-support tests pass in the codec-enabled environment.
  The initial system-Python run skipped its optional LZ4 case and is retained.

### Deadline limits and phase selection

The monolithic Terralith task exceeded 30 seconds after climate parity. Splitting
it initially still bundled forced program specialization with registry checks;
that run also timed out. The standalone full Terralith specialization/parity run
did not finish within 30 seconds. A combined decorations/shore run passed density
and sediments but timed out during shore checks. These are **incomplete checks**,
not passes. We do not claim a complete fresh run of the full Terralith specialized
shader/analytic shore suite. No production shader-specialization or shoreline
algorithm is changed by this optimization series' final integration cycle.

`datapackTest` now accepts `-PtestDatapackPhase=automatic`, `registry`, `program`,
`decorations`, `shores`, or `terrain`. The default `all` retains every original
assertion, sample and their order. The independent phases repeat common registry,
mode and saved-profile assertions; they do not shorten individual fixtures.
`datapackProgramTest -PtestProgramDefinition=/absolute/retina.dat` runs the
program comparison from a saved definition without reloading Minecraft registries. The raw logs preserve
the failed attempts and successful phase results separately.

The failed forced-specialization/shore checks do not establish a new regression
or a pass. Preservation of the changed optimizations is supported by complete
baseline/candidate NBT, ordered plans, actual loaded-pack generation, and targeted
CPU/GPU fallback tests. The comparison remains limited to the exercised profiles,
coordinates and backends. Vulkan/D3D12 performance, live rendering, and Nether
source-root planning speed are outside these Overworld measurements.

## Requirement audit and delivery

| Objective requirement | Implementation disposition and inspected evidence |
| --- | --- |
| 1: candidate/queue/predicate/construction/distribution diagnostics; useful/replayed work; CPU/GPU fallback reasons and timings | `decoration/diagnostics.rs`, `decoration/vine_gpu.rs`, benchmark detailed-vs-normal equality; [compact continuations](COMPACT_GPU_CONTINUATIONS.md) and [fallback classification](VINE_GPU_FALLBACK_TRIAL.md) |
| 1: compact retained state, scheduling, bounded memory | Compact seed/origin continuations ship; 512-anchor Metal vine windows and immutable substrate share the unchanged 64 MiB budget; GPU arena stays bounded. [Early suspension](EARLY_DECORATION_SUSPENSION_TRIAL.md) was implemented/tested/rejected. Current eight-caller RSS and actual cache-interleaving tests are above. |
| 2: prepared material metadata, run reuse, biome membership, compiled predicates, allocation/dispatch | [Compiled material sets](MATERIAL_PREDICATE_OPTIMIZATION.md) ship with tiny direct comparisons, bounded bitsets and 256 weak interning entries. [Prepared layouts/run views/membership](PREPARED_PLACEMENT_LOOKUP_TRIAL.md) and [predecoded evaluation](COMPILED_PLACEMENT_EVALUATION_TRIAL.md) were implemented/tested/rejected; patches and negative evidence are archived. |
| 2–3: overlay precedence, unknown/boundary semantics, exact ordering, counts, RNG, unsupported/failed GPU fallback | Current unit/actual-GPU tests, complete ordered vectors and full NBT comparisons; no attempt count or accepted write is discarded. |
| 3: additional effective GPU coverage, immutable substrate reuse, compact output | Guard-input extension was implemented/tested/rejected; [ready batches and substrate identity](READY_DECORATION_BATCHES.md) ship only on the measured Metal/vine workloads. Other backends and unsupported inputs retain exact existing continuation. |
| 4: optional LZ4 framing/checksum/end marker, codec identity, reusable scratch, configuration, loading/storage tradeoff | `region/codec.rs` and [codec implementation/measurements](MCA_COMPRESSION.md); current real Minecraft reader, mixed records, repair/promotion tests and full-height LZ4 corpus. Zlib stays default; overlarge LZ4 records retry zlib, then fail if still too large. |
| 4: one final atomic publication, durability and edits | `region.rs` preserves existing records, completes encoding/lighting, writes a sibling file, syncs it, renames once and syncs the directory; current corruption/edit/promotion/partial-save tests. |
| 5: biome view, immutable metadata and scratch reuse, uniform/occupancy/palette scans | [NBT metadata reuse](NBT_BIOME_METADATA_TRIAL.md) was implemented/tested/rejected after reviewing [the earlier section trial](SECTION_ENCODING_TRIAL.md). Existing scratch reuse remains; [GPU palettes](GPU_SECTION_PALETTES.md) reuse lighting classification/final-block uploads and bypass CPU encoding for supported sections. |
| 5: all final chunk tags, six heightmaps, lights, structures/entities/ticks, partial generation | Complete NBT bytes match for 40,960 comparisons; ordered exports include ticks; actual Minecraft decoding, light reference and lifecycle tests. |
| 6: implemented GPU palette/heightmap prototypes, compact output and evaluator specialization; cost and fallback | GPU palettes ship on Metal with uniform/over-capacity CPU fallback. [All-six GPU heightmaps](GPU_HEIGHTMAP_TRIAL.md) and predecoded placement evaluation were implemented/tested/rejected. Extra transfer and memory costs are explicitly reported. |
| 7: Rust/GPU MCA path, eight callers, DH/Chunky, temporary promotion, lighting, registry fidelity and determinism | Current whole-region comparisons and lifecycle matrix above. Main's automatic adapters and legacy Retina save codecs are preserved; unrelated world-selection changes are not reverted. |
| 7: matched builds/order/readiness, raw samples, full outputs, wall rates, latency/memory/transfer/storage/stage costs | Final four matched pairs, explicit counterfactual patch, pinned hashes, raw artifact and complete-vector comparisons. LZ4 validation and cold-specialization limitations are separately labeled. |
| Resource and delivery requirements | Two build/CPU workers, reduced priority, bounded sequential heavy jobs and process-group cleanup; no game launch. Each cycle used its own PR/worktree and squash auto-merge. Main/feature PRs do not trigger the release-only Actions workflow. |

Merged implementation/disposition PRs:
[127](https://github.com/ArcaneArts/Retina/pull/127),
[129](https://github.com/ArcaneArts/Retina/pull/129),
[132](https://github.com/ArcaneArts/Retina/pull/132),
[135](https://github.com/ArcaneArts/Retina/pull/135),
[141](https://github.com/ArcaneArts/Retina/pull/141),
[143](https://github.com/ArcaneArts/Retina/pull/143),
[148](https://github.com/ArcaneArts/Retina/pull/148),
[153](https://github.com/ArcaneArts/Retina/pull/153),
[157](https://github.com/ArcaneArts/Retina/pull/157),
[168](https://github.com/ArcaneArts/Retina/pull/168), and
[169](https://github.com/ArcaneArts/Retina/pull/169).
The final audit records merged states and zero Actions runs at every full head
and merge SHA. This report and the phase-selectable harness form the final
integration PR; its delivery state is verified separately after auto-merge.

## Reproduction

Use separate checkouts of the recorded source revision. Apply the archived
reference patch only to the reference checkout. Build and pin each native library
explicitly with `cargo build --release --lib --manifest-path native/Cargo.toml`;
building only an example does not refresh the root native library. Build planner
examples with matching optimized dependencies and final `-C lto=off` on both sides.
Record SHA-256 hashes before switching sources. The raw artifact embeds the
deadline runner and Gradle native-path/worker configuration.

Run `scripts/native-region-benchmark.py` with pinned library/profile paths,
`--parallel 8 --count 8 --grid-width 4 --warmups 1`. For vanilla
use `--program-execution auto --readiness shader-ready --specialization-timeout 8`;
for Terralith use `--program-execution interpreter --interpreter-preload disabled`.
Use the reference environment above only on its side. Supply `--compare` on the
second run of each pair and alternate order. For codec validation supply
`--mca-compression lz4` with a codec-enabled Python environment.

Planner arguments are `<profile> 123456789 -32 -32 3 32 2` for vanilla and
`<profile> 123456789 0 0 3 32 2` for Terralith. Set `RETINA_PLAN_OUTPUT` to distinct
files and compare the complete binary vectors. Neither reduced sample counts nor
selected-field checksums substitute for this output comparison.

Use the normal `./gradlew runClient` when manually testing. This goal did not
launch the client or change that command.
