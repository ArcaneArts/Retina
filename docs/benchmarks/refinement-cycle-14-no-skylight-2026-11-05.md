# Refinement cycle 14: no-skylight CPU lighting initialization

Retain the emission-only CPU initialization for profiles whose authoritative `sky` field is false. Five fresh production-path matched Nether pairs show **lighting host elapsed time −21.16%**, descriptive paired bootstrap interval **−22.59% to −9.22%**. All five pairs improve. The medians are 668.23 → 546.57ms per complete region. Whole-region throughput, process CPU work and RSS remain inconclusive; no overall chunks/s, FPS or Windows speedup is claimed.

Branch: `codex/refinement-sky-rows`, base `e10c9ee4`. Actual PR/merge linkage is maintained in the cumulative implementation ledger. The [machine-readable record](refinement-cycle-14-no-skylight-2026-11-05.json) contains every completed trial's commands/identities, measurements, load samples, exact-output checks and rejected alternatives. Large binaries, patches, profiler data and MCA corpora remain outside Git.

## Scoped implementation

A no-skylight profile never initializes direct sunlight. Its production frontier path now walks the dense material/light arrays contiguously, extracts emission and records whether propagation-capable emitters exist. It skips the unused sky frontier allocation and seeding walk. Material staging, palette validation, padding, block-light seeding order, attenuation, occlusion, propagation, cropping and encoding are unchanged. No new scratch state, parallelism, APIs, shaders or backend defaults are introduced.

All sky-lit profiles retain the original column-major walk. `run_impl::<false>` retains the original full-source implementation as an independent reference for both profile types. The actual exported saved-definition flags are Overworld=true, Nether=false and End=true. An early assumption that this End fixture had no skylight was wrong; the source respects the saved flag and the End screen is not evidence of this fast path.

## Qualification and guard results

| Five matched pairs | Median paired change | Descriptive paired bootstrap interval |
| --- | ---: | --- |
| CPU Nether lighting host elapsed | −21.16% | −22.59% to −9.22% |
| CPU Nether complete-MCA throughput | +2.18% | −2.80% to +11.71% |
| CPU Nether process CPU / region | −0.94% | −2.18% to +1.21% |
| CPU Nether generation high-water RSS | +0.32% | −1.54% to +2.19% |
| CPU Overworld complete-MCA throughput | +0.93% | −4.20% to +5.87% |
| Metal Overworld complete-MCA throughput | +0.04% | −3.56% to +5.19% |

Each pair uses separate fresh baseline/candidate processes in alternating AB/BA order, the production SavedDefinition reader, vanilla seed 123456789, measured region(0,0), completed warmup(8,8), native budget 16/global Rayon 2, one caller, shader-ready state, and full lit/decorated/NBT/zlib/atomic-MCA completion. Hash/comparison work is outside generation timing. Both binaries use Rust 1.93.0/LLVM 21.1.8, locked default features, release ThinLTO/two codegen units and no RUSTFLAGS. The frozen source-equivalent baseline is `f2d38a66…`; candidate E is `64824006…`; full identities are in JSON.

`LIGHT_HOST` is the actual CPU region lighting elapsed interval excluding synchronous tile consumption/encoding. It lies outside the scaled assembly-worker timer range. It is a complete production lighting-pass observation, not an isolated component extrapolation, process CPU time or utilization. Metal's identically named field is lighting-host time rather than this CPU kernel. The retention claim is deliberately limited to the consistently faster CPU no-skylight pass, with exact output and no demonstrated end-to-end guard regression. A source-level Vec omission does not establish allocator, DRAM or RSS savings.

Shared-host load varies and is retained per trial. The same scoped activity is applied to each actual benchmark owner, not assumed inherited from a controller. Nothing unrelated is stopped and no power setting changes. Small-sample p95/intervals are descriptive; native timing excludes Minecraft activation/rendering/packets. No deferred-startup or disk-size tradeoff is introduced.

## Rejected alternatives

Six cheap extracted-kernel probes compare the original walk, sky row ordering, a bool-plane boundary-once variant, hybrid variants and an identity control. They use three synthetic heights(384/768/256), the system allocator, private phase clocks and an independent old full-source reference, with 120 recorded samples and 150 fixture-output checks per process. They are attribution/screening, not production allocator or complete-MCA performance evidence. Same-code identity bias is about 1.7/3.2/2.3%, so small differences are not accepted.

Combined candidate D looked promising in the component probe, but five production Overworld CPU pairs leave throughput(+2.02%), process work(+0.67%) and lighting host(−6.73%) inconclusive. Its Metal observations are noisy and do not validate the CPU sky walk. A four-process outer-End screen also provides no benefit and uses `sky=true`. All sky-lit reorderings, extra bool-plane state and private instrumentation are discarded. Only the separately rebuilt E no-skylight path qualifies; D measurements are never pooled with E.

## Correctness and compatibility

E's 30 qualification/guard processes and 11 broad cases complete 73 fresh MCAs including warmups, with **41,984 strict same-backend decompressed-NBT chunk comparisons**. Broad coverage includes CPU Overworld/Nether/central and outer End, another seed, negative/far coordinates, custom data, expanded storage, Metal Overworld/outer End and the actual 768-height datapack. Each of those 11 cases closes/reopens its definition in the same process and preserves all 1,024 records.

A separate native process then opens the completed E Nether MCA: zero generated, 1,024 preserved, exact NBT/membership and the **entire file including timestamp header unchanged**. This is distinct from the same-process reopen checks.

Actual Minecraft 26.3 decoding on Java 25.0.1 uses the actual current-version RegionFileStorage and SerializableChunkData APIs with Nether height0/256. All six 1,024-chunk passes succeed, each with900 light-correct interior chunks and 18,184 section records; the source MCA remains byte-identical. The external verifier copies the existing decoder flow and changes only class name and explicit Nether bounds/storage dimension. These 6,144 repeated decodes prove compatibility, not decoding speed or live activation.

Five native CPU-lighting tests pass, including the independent full-source randomized/face/emitter cases and a new short-height/padding-traits cross product. The explicit real-Metal integer-light test also passes all six CPU/GPU comparison cases (three profiles × two output formats). Four storage tests pass, covering timestamp/partial preservation, write/read failures, malformed data, oversize records and failed publication. The kernel changes no owner/cancellation/publication mechanism; no new full Java lifecycle or Windows runtime test is claimed.

D's separate 24 rejected-screen processes complete 48 fresh MCAs and 24,576 strict NBT comparisons. Their results are preserved as rejected evidence rather than favorable E samples. No JAR or release is built. Cumulative goal-baseline comparison and actual Windows compact-transport validation remain separate work.
