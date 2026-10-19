# Bounded GPU material-output experiment

Both evaluated prototypes retain exact output but do not establish a useful
whole-generator improvement. Production keeps its existing asynchronous material
continuations. No experimental switches, counters or arena allocations are enabled
or retained in the runtime by this cycle.

[Raw results](benchmarks/material-arena-trial-2026-10-19.json) preserve nine native
runs, individual region times, stage counters, library/profile hashes, logical
traffic, peak RSS and validation logs. Two patches preserve the implemented
experiments against `1bc1009a450db57b952f7b851675206a9cc3c527`:
[v1](benchmarks/material-arena-v1-2026-10-19.patch) and
[v2](benchmarks/material-arena-v2-2026-10-19.patch). Both pass `git apply --check`
on that base. Apply only one to a separate experimental checkout for reproduction.

## Implementations

The current pipeline counts runs, snapshots immutable job inputs, reads the count,
then allocates exact pooled output and submits emission. That continuation overlaps
other jobs. The experiment reserves a bounded output arena in the initial GPU
submission, runs count/prefix/emission together and reads the reserved arena with
the existing result. The count header tells Rust how many words are meaningful.

V1 uses `RETINA_MATERIAL_ARENA_RUNS=16` to reserve sixteen run words per column.
The shader checks the total before writing anything. Overflow uses the unchanged
exact continuation with private inputs. Logical binding sizes prevent a previously
grown pooled buffer from disguising an insufficient reservation. Flights own their
readbacks, while explicit timestamps include inline emission.

V2 additionally stages the first sixteen runs per column during counting. The
emission pass copies these runs into prefix-assigned output instead of evaluating
materials again. If an individual column exceeds sixteen runs but the total fits,
only that column is reevaluated. Global overflow retains the exact continuation.
Staging is excluded from fallback snapshots; a separate 64-byte request clears the
staging flag for continuation. Allocation bounds account for both scratch and final
output planes and the device's storage binding limit.

The experimental diagnostic API counts jobs, arena hits, continuations,
GPU-to-GPU snapshot/continuation copies, reserved readback bytes, actual run bytes
and peak cached/shared buffers. This last counter excludes live private snapshots
and flights; process RSS is reported separately. Neither counter is a complete
measure of device memory residency. No established production telemetry ABI changed.

## Matched whole-generation results

Apple M4 Max, Metal, reduced priority (`nice +10`), two shared region workers and
two Rayon workers. Each six-region batch uses two concurrent callers, seed
123456789, height 384, X=-1..1/Z=-1..0 and a separate warmup at (8,8). Measured
regions reached shader status 4 (the current mixed specialized/interpreted path).
The reference is merged PR #120, including compact lighting input and output.

All comparison libraries use final-crate optimization level 2 without LTO to
stay within the 60-second compilation deadline. Other release settings and
dependencies are unchanged. Every execution has a 30-second process-group limit.
Normal-release unit/GPU fixtures retain optimization level 3 and thin LTO. These
rates must not be compared directly with older normal-release benchmark rates.

| Profile / variant | Chunks/s | Mean region ms | Sample p95 ms | Peak RSS GiB | Logical MiB/region | Material device ms/region |
| --- | ---: | ---: | ---: | ---: | ---: | ---: |
| Terralith reference A | 1,588 | 1,253 | 1,643 | 2.825 | 200.28 | 20.96 |
| Terralith v1 | 1,527 | 1,323 | 1,726 | 2.957 | 209.53 | 20.88 |
| Terralith v2 | 1,548 | 1,204 | 1,649 | 2.989 | 209.50 | 11.98 |
| Terralith reference B | 1,600 | 1,214 | 1,760 | 2.614 | 200.26 | 20.96 |
| Terralith v2 repeat | 1,613 | 1,206 | 1,919 | 3.182 | 209.52 | 12.22 |
| Vanilla v2 | 2,380 | 794 | 1,276 | 2.201 | 200.05 | 9.17 |
| Vanilla reference | 2,563 | 796 | 899 | 1.981 | 190.30 | 15.99 |

Order was Terralith reference A → v1 → v2 → reference B, then vanilla v2 →
reference, then the Terralith v2 repeat. The artifact also retains two preliminary
single-region Terralith correctness/readiness runs; their caller count and sample
size differ, so their rates are not evidence against the six-region references.

Throughput is completed chunks divided by batch wall time through MCA publication,
not inverse region latency. Six-sample tails are observations, not stable production
p95 estimates. V1 was about 4% slower than reference A. V2's Terralith results range
from about 3% lower to 1% higher than the references, while the single vanilla pair
was about 7% lower. This does not prove a universal regression, but does not justify
shipping extra runtime complexity. Material device work falls about 42–43%; that
isolated win does not predict complete generation throughput.

Both candidates fit every measured full-region arena, removing all six host
continuations in each batch. The six regions read 127,401,984 reserved arena bytes,
although only 69,208,332 bytes (Terralith) or 66,043,444 (vanilla) contain actual
runs. V2 still snapshots 358,144,992 bytes for potential fallback. Logical traffic
increases about 9–10 MiB/region. Peak cached/shared buffers increase from v1's
224,627,680 to v2's 245,861,344 bytes; production has no matching diagnostic counter
for this subset. Process RSS is higher in these comparisons. Extra buffering and
readback are plausible offsets to the faster material kernel, not an isolated
causal measurement.

## Isolated replay and correctness

Two 8×8-chunk, height-192 jobs at negative and positive coordinates produce
66,329 and 70,068 runs. Three alternating v1 pairs took about 111 ms for the
continuation versus 116–118 ms for the arena. V2 took 100–101 ms versus 111–112 ms,
roughly 10% shorter. Complete material outputs match in every pair. The isolated
fixture uses the interpreter explicitly; full generation uses shader-ready mixed
execution. These are different workloads, not additive improvements.

Validation in the preserved source includes:

- 82 ordinary unit tests passing for each version.
- Two real-GPU arena tests, including exact replay and eight reverse-completed
  flights with distinct profiles, heights and sizes.
- Global overflow, v2 individual-column overflow with a fitting global total,
  alternating growth/shrink and fit/overflow buffer reuse.
- Abandoned direct flights and overflow continuations, timestamps disabled and
  bounded cache reuse.
- The existing material-continuation interleaving/failure fixture passing.
- Every measured six-region candidate corpus matching all 6,144 decompressed NBT
  records against the retained reference, including materials, features and light.
  Both single-region candidate checks match all 1,024 records.

An initial v2 build failed on a Rust borrow conflict and was repaired before the
successful build and tests. A test invoked after that failed build used the old
v1 binary; its log is explicitly excluded from v2 evidence. The artifact retains
the subsequent successful build and actual v2 test logs.

## Startup and reproduction limits

The initial v1 full request exhausted its 12-second shader-readiness allowance.
A reduced one-region check with a 22-second readiness allowance completed within
the same 30-second process deadline. V2 used the reduced check first. Cold observed
program compilation was about 10.9 seconds for v1 and 16.5 seconds for v2; warm
runs were about 4.1–4.3 seconds, comparable to the references. Cache conditions
were not matched for the cold observations, so no startup regression is claimed.

Use `scripts/native-region-benchmark.py` from the selected patch with
`--readiness shader-ready --count 6 --warmups 1 --parallel 2`, separate output
folders, the same exported profiles and `--compare` against baseline corpora.
Set `RETINA_MATERIAL_ARENA_RUNS=16`; v2 staging defaults on and can be disabled with
`RETINA_MATERIAL_STAGING=0`. Keep the same build/resource limits. The patches
include their exact GPU fixtures and additional benchmark counters.

No game launch or GitHub Actions was used. The rejected library in the shared
ignored build output was replaced with the retained production reference. Ore
planning, final-section processing and combined lifecycle validation remain next.
