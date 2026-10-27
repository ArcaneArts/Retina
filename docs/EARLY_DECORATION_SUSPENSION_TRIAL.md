# Early decoration group suspension trial

Final status: see the [combined integration audit](POST_INVESTIGATION_INTEGRATION.md).
References below to remaining goal work describe this cycle’s historical state.

The prototype reduces speculative CPU evaluation and improves the isolated dense
Terralith planner by 7.5%, but complete-generation results are mixed or slower.
It is rejected for production. The existing replay policy remains enabled.

The [source and test patch](benchmarks/early-decoration-suspension-trial.patch)
applies to `245fdb6e5501e2a36814ca971f2f36201f71a034` with
`git apply --unidiff-zero`. The experimental switch
`RETINA_DECORATION_SUSPEND_GROUPS=1` exists only in that patch.
[Raw measurements](benchmarks/early-decoration-suspension-trial-2026-10-19.json)
retain all samples, build identities, test logs, ordered-plan comparisons,
individual region latencies, memory and transfer counters.

## Implementation

When a provider or live-layer count is missing, the existing planner saves the
valid prefix but continues collecting queries from subsequent groups. Work after
the first missing input is speculative and may be repeated after sampling.

The prototype finishes query collection for the first unresolved RNG group, then
suspends before entering the next group. This includes boundaries reached during
lazy queue expansion, before that expansion consumes a child from the next group.
It resumes the existing checkpoint after sampling. Collecting a whole group
preserves batching of sibling queries rather than creating one GPU round trip
per missing candidate.

The implementation changes scheduling, not registered attempts, random streams,
predicate semantics, overlay precedence, or final feature order. It retains the
64 MiB checkpoint budget and exact full-replay recovery when the checkpoint
cannot be retained. GPU handoff and successful-prefix recovery remain intact.

## Correctness

- 101 ordinary Rust tests passed; 37 GPU/manual tests were explicitly ignored.
- A dedicated fixture compares complete ordered placements for three seeds and
  origins 0, −33 and 700,000, with both source-order policies. It exercises provider
  and lazy live-layer count boundaries with zero, 16 KiB and 64 MiB budgets,
  verifies actual suspension, and checks retention and discarded-state recovery.
- The actual Metal vine handoff test passed, including early suspension with zero
  and 64 MiB retention at origins 0, −17 and 1,000,000. The fixture also retains
  its existing overflow and successful-prefix discard coverage.
- Complete exported ordered-plan files match byte for byte: 246,425 vanilla
  placements and 485,663 Terralith placements.
- 40,960 complete decompressed chunk NBT comparisons passed across six matched
  full-generation pairs, including both eight-caller workloads below.

The tested patch was removed from production before this documentation PR.
There is no new MCA format, Java integration or publication behavior to validate.
This trial does not replace the final combined lifecycle audit.

## Isolated planning

Normal opt-level-3/thin-LTO release builds, Apple M4 Max / Metal, two CPU workers,
seed 123456789 and a 32×32 region plus planning halo. One warmup precedes five
samples. Vanilla uses chunk origin (−32,−32); Terralith uses (0,0). A separate
diagnostic plan must match the measured plan and supplies the detailed counters.

| Metric | Vanilla reference → candidate | Terralith reference → candidate |
| --- | ---: | ---: |
| Median planner ms | 81.65 → 83.13 | 336.66 → 311.26 |
| CPU candidate evaluations | 606,828 → 606,492 | 2,412,278 → 2,184,586 |
| Speculative evaluations | 2,209 → 1,873 | 230,972 → 3,267 |
| Replay rounds | 2 → 2 | 4 → 4 |
| Provider queries | 1,018 → 1,018 | 1,491 → 1,491 |
| Count queries | 1,593 → 1,593 | 33,839 → 33,839 |
| Peak retained bytes | 432,808 → 432,808 | 32,112,264 → 32,113,484 |

The dense fixture still submits 13 vine batches with 17 sparse CPU anchors.
Vine uploads remain 28,464,524 bytes and readbacks 4,253,232 bytes. The candidate
does not reduce missing-material fallbacks. CPU counts include replay/halo work
and exclude candidates completed entirely on the GPU. Diagnostic worker times
are nested within replay wall time and are not additional elapsed time.

## Complete lit-MCA generation

All pairs use the same pinned profiles, seed, coordinates, build settings, two
CPU workers, zlib output and lighting. Each process first generates a warmup at
region (8,8). Measured grids start at (−1,−1), with width 3 for six-region runs
and width 4 for eight-region runs. Each rate uses measured batch wall time through
MCA publication; Java loading, DH conversion and rendering are excluded.

| Workload | Callers / regions | Order | Reference chunks/s | Candidate chunks/s | Change |
| --- | ---: | --- | ---: | ---: | ---: |
| Vanilla, interpreter | 2 / 6 | Reference first | 1,821.9 | 1,815.7 | −0.3% |
| Vanilla, interpreter | 2 / 6 | Candidate first | 1,706.7 | 1,764.1 | +3.4% |
| Terralith, interpreter | 2 / 6 | Reference first | 832.3 | 866.3 | +4.1% |
| Terralith, interpreter | 2 / 6 | Candidate first | 791.6 | 771.3 | −2.6% |
| Terralith, interpreter | 8 / 8 | Reference first | 645.9 | 638.2 | −1.2% |
| Vanilla, shader-ready auto | 8 / 8 | Reference first | 2,738.5 | 2,544.5 | −7.1% |

Interpreter runs explicitly disable interpreter preload. The vanilla auto pair
waits for specialization after the warmup, with an eight-second readiness limit;
both reach status 4. No Terralith shader-ready gain is claimed.

The eight-caller planning wall estimates increase from 1,023.7 to 1,305.5 ms/region
for Terralith and from 320.9 to 1,171.0 ms/region for vanilla. These include shared
worker contention and cannot be interpreted as isolated instruction costs.
Peak process RSS is 1.63 → 2.09 GiB and 2.01 → 2.05 GiB respectively; it includes
the Python corpus reader/comparison, not just the generator. Eight callers still
use two CPU workers and do not imply eight simultaneous GPU dispatches.

These short desktop measurements do not establish that early suspension itself
causes every wall-time difference. They do establish that the isolated saving is
insufficient evidence to enable it as a complete-generator optimization. There
is no reliable throughput gain across the measured workloads, so further retries
of this unchanged prototype are not justified.

## Reproduction and next work

Build the reference from `245fdb6`, preserve its library and
`decoration_plan_benchmark` executable, then apply the patch and rebuild with
the same release settings. Enable the experimental environment switch only for
the candidate. Use two Cargo jobs, two CPU workers, nice +10, a 60-second build
deadline and a 30-second execution deadline, with process-group cleanup.

Run `decoration_plan_benchmark DEFINITION DIMENSION X Z 5 32 2` with interpreter
preload disabled. Set `RETINA_PLAN_OUTPUT` to retain each full ordered plan.
Use `scripts/native-region-benchmark.py` with the table's caller/count settings,
one warmup, matching execution/readiness policies, and `--compare` against the
paired output. Raw data includes the source-profile and library SHA-256 values.

No job timed out, no game was launched, and no GitHub Actions was requested.
The next distinct experiments are bounded ready-batch scheduling and further
placement-program specialization. The overall implementation goal remains open.
