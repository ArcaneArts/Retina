# CPU optimization results — 2026-11-01

Complete vanilla CPU MCA generation now meets the local 15-second target at both tested headline budgets. At four workers, median latency falls from **42.780 to 12.566 seconds (3.4×)** and process CPU work from **104.680 to 27.785 seconds (73.5% less)**. At 16 workers, latency falls from 18.967 to 6.217 seconds. These are native, offline, complete lit and saved 1,024-chunk regions; they are not Minecraft FPS measurements or a guarantee for the unspecified machine behind the user's approximately 50-second observation.

## Comparable final round

Local Apple M4 Max, 16 logical processors, 128 GiB RAM; AC attached, recorded power mode 0. Ordinary portable Rust 1.93.0 release builds, locked dependencies, no `target-cpu=native`, reduced precision or changed compression. All headline trials use global Rayon 2, fixed definitions/seeds/coordinates, fresh destinations and alternating A/B/B/A order. No task-owned builds, profilers or other benchmarks overlap the headline runs. Other desktop activity and scheduling remain uncontrolled; observations and raw counters are retained.

Baseline is source revision `60c6f7e` matching release 1.1.481. Published/source vanilla NBT identity was verified at the start. Final code is `b9db5cfb`, through PR 410. The native source tree and both timed image hashes are in the adjacent JSON. Actual backend is `CPU: native`, zero GPU generation jobs, zero shader-readiness wait.

| Complete lit MCA | Trials per variant | Median wall seconds | Median process CPU seconds | Completed chunks/sec | Median generation peak GiB |
| --- | ---: | ---: | ---: | ---: | ---: |
| overworld-16 | 4 | 18.967 → 6.217 | 134.028 → 35.269 | 54.0 → 164.8 | 1.054 → 1.009 |
| overworld-4 | 4 | 42.780 → 12.566 | 104.680 → 27.785 | 24.0 → 81.5 | 0.915 → 0.913 |
| nether-16 | 2 | 34.218 → 19.854 | 119.074 → 108.393 | 34.3 → 51.8 | 1.476 → 1.484 |
| custom-16 | 2 | 21.216 → 7.236 | 118.947 → 33.606 | 48.5 → 142.0 | 0.720 → 0.678 |
| end-central | 2 | 0.394 → 0.213 | 0.380 → 0.199 | 2637.0 → 4927.1 | 0.514 → 0.506 |
| end-outer | 2 | 3.036 → 1.186 | 2.862 → 1.121 | 337.7 → 876.3 | 0.520 → 0.517 |

Peak memory is the median of each process's generation high-water mark, excluding later corpus-comparison growth; maxima remain in JSON. Four-trial Overworld descriptive p95 is 48.390 → 16.489 seconds at four workers. This is an observed small-sample statistic, not a reliable production tail estimate. Other cases have only two trials per variant.

Nether baseline wall times were especially variable and had lower scheduled-core utilization. Its CPU-work reduction is much smaller than the wall-time difference. Do not attribute its entire latency change to the algorithm. The large pack also remains a separate, much heavier workload; vanilla's speedup is not universal.

## Retained cycles

Every retained cycle used its own worktree, branch and squash auto-merged PR. Primary was synchronized after every observed merge; owned implementation worktrees were removed.

| PR | Change and evidence |
| --- | --- |
| [400](https://github.com/ArcaneArts/Retina/pull/400) | Real CPU MCA/A-B tooling, CPU resource measurements, strict NBT and reopen checks, broad verified baseline. |
| [402](https://github.com/ArcaneArts/Retina/pull/402) | Compact exact-zero-context corner keys; roughly 8–11% less Overworld CPU work in its paired round. |
| [403](https://github.com/ArcaneArts/Retina/pull/403) | Opt-in VM/cache/noise work counters; default-off hot paths compile counting out. |
| [404](https://github.com/ArcaneArts/Retina/pull/404) | Conservative density certificates skip only proven-air height scan cells; the largest CPU-work reduction. |
| [405](https://github.com/ArcaneArts/Retina/pull/405) | Reuse already-produced quart biomes; roughly 15–16% less CPU work in its round. |
| [406](https://github.com/ArcaneArts/Retina/pull/406) | Exact previous-leaf climate bounds; about 40% fewer measured distance evaluations, preserved target/tie semantics. |
| [407](https://github.com/ArcaneArts/Retina/pull/407) | Proven zero material bands skip 15.9 million VM calls; keep all six output finiteness requirements. Also fix implicit-node-zero density bounds. |
| [408](https://github.com/ArcaneArts/Retina/pull/408) | Bounded current interpolation cells; 65% fewer corner-map lookups and about 4–5% less CPU work. |
| [409](https://github.com/ArcaneArts/Retina/pull/409) | Exact light-source frontiers; serial lighting about 2.7 → 1.1 seconds in separate diagnostics. |
| [410](https://github.com/ArcaneArts/Retina/pull/410) | Compact step inputs and inline dependency selection; 11–15% less CPU work with all 25 VM counters unchanged. |

These percentages are separate matched rounds, not factors to multiply: desktop load changed during the session. The final original-versus-final table above is the cumulative comparison. Individual reports in this directory preserve each cycle's measurements, proof and limitations.

The diagnostic trace falls from approximately 189.8 million VM calls/6.52 billion node visits before work elimination to 53.6 million calls/1.82 billion visits. Interval certificates require more corner noise in exchange for skipping over 121 million height points: primitive VM noise calls are slightly higher, approximately 181.3 → 189.6 million. This is exact work elimination and lower execution overhead, not approximate noise.

## Correctness, lifetime and platform guards

All paired trials compare strict decompressed NBT, including block states, biomes, heightmaps, lighting, features, block entities, structures and metadata. All measured fresh regions generate 1,024 records; preserved-region checks retain all 1,024. Definitions remain authoritative and byte-identical.

Coverage includes normal Overworld, a negative second seed, Nether, central/outer End, a representative custom pack, far region (55000, -55000), synthetic expanded storage (-80/416 around an unchanged generator), and the actual large pack's -256/768 generator and storage bounds. The latter completes with every original chunk identical in a separate full diagnostic/profile run. Its sampled timing is excluded from headline claims; it still takes minutes and remains dominated by scalar VM evaluation across all sampled phases.

The supported minimum (two native workers, global Rayon 1) completes exact full MCA output: one descriptive original/final pair is 125.754 → 33.666 seconds. Warm-process fresh neighbors and two concurrent regions preserve 2,048 exact records each. Two concurrent worlds with different seeds, global Rayon 1 and a shared 16-worker budget also match original output. A real I/O error is followed by valid generation; retiring one world leaves the other usable and produces an exact single chunk. A new process reopens a pre-existing MCA and preserves every compressed file byte, all NBT and the definition.

Final source checks pass 75 CPU tests, 29 world-definition tests and the actual shader/opcode coverage contract. Five default CPU opt-ins are excluded, then the full CPU chunk acceptance is explicitly executed successfully; opaque-binding/profile-retirement acceptance is also explicitly executed on CPU. Existing owned-handoff panic/error and blocked-root progress tests remain covered. No new pool, caller count or cross-pool stealing is used for a speed claim.

The standalone Rust CLI finishes its active region after SIGINT, stops before the next region and preserves all bytes on a fresh-process resume. Actual Metal A/B/B/A generates identical GPU output with positive device jobs at roughly 0.4–0.5 seconds per region. Six exact real-Metal CPU/GPU integer-lighting cases also passed during the lighting cycle. These GPU checks are separate from CPU performance claims.

## Diminishing returns and remaining costs

The storage-only VM prototype offered only about 0.9–3.3% CPU-work gains; inlining supplied the substantial additional win and was retained. A subsequent identity-index shortcut was measured over 20 complete paired trials plus original secondary corpora. CPU work moved -0.8%, -0.3%, +0.9% across Overworld-16/Overworld-4/Nether; latency moved in both directions. It was discarded, with its patch and results retained outside Git. No unmeasured unique-root or frame-clear prototype is shipped.

After the large skips and dispatch fix, the next easy local candidates offer small or unproven benefits. Profiles show little frame-clearing cost; the remaining large cost is interpreting the actual scalar graph, especially the large pack. Larger prepared execution kernels, careful material specializations and portable batching/SIMD remain future investigations requiring their own proof and workload-specific measurements. Vertical chance's hash fraction includes exactly one: a full-weight condition cannot simply be assumed true for every column. Numeric behavior and complete feature coverage remain constraints.

## Reproduction and delivery

Raw commands, immutable libraries, definition hashes, per-trial JSON, profiles, rejected patches and reference MCA files are retained under `~/.cache/retina/cpu-optimization-20261101/`. `final-compare.py`, `final-secondary.py`, `final-lifecycle.py`, `cold-reopen-check.py`, `concurrent-worlds-check.py` and `cli-cancel-check.py` preserve exact local acceptance commands. The adjacent compact JSON includes all paired results and key lifecycle records.

A normal comparison uses existing repository tooling:

```sh
RAYON_NUM_THREADS=2 python3 scripts/native-region-benchmark-ab.py \
  --backend cpu --baseline-library /path/original.dylib \
  --candidate-library /path/final.dylib --definition /path/world/retina.dat \
  --dimension minecraft:overworld --assembly-threads 4 --parallel 1 \
  --trials 8 --count 1 --warmups 0 --lighting enabled \
  --preservation-check --out /path/fresh-comparison
```

The delivery path is `build/release/retina-windows-x64.jar` in the primary checkout. The delivery record `final-windows-jar-verification.json` in the evidence directory records the actual version, size, SHA-256, PE imports/exports and packaged native identity. Windows runtime hardware is unavailable here. Local executable-JAR CPU checks use the same-source Mac native image, and packaged-resource smoke uses an explicitly labeled local classpath fixture; neither is Windows runtime acceptance. No GitHub release is published by this goal.

For the Windows retest, install that JAR, set Retina's `allow_directx`, `allow_metal`, and `allow_vulkan` to false, leave debug false, confirm the CPU backend line, and repeat the same seed/definition, fresh region and worker/concurrency settings. Measure through complete region save and record elapsed time/completed chunks. Existing saved regions must not be counted as newly generated work. No debugger is required.
