# CPU sampling work counters — 2026-11-01

The next optimization target is repeated registered density evaluation and interpolation lookup work. This cycle adds opt-in measurement; it does not claim a generation speedup.

## Implementation and scope

Existing performance diagnostics now report evaluator calls by terrain/material/auxiliary/interpolation-field category, node visits/computations, constant/ready reuse, horizontal and corner cache lookups/hits/clears, interpolation operations, noise profiles, and primitive noise3 calls. Counters cover the registered CPU VM, not every independent feature noise implementation.

Counts belong to a bounded evaluator and flush once into existing per-profile counters on drop. Relaxed atomics retain its Send/Sync contract without global contention on every node. Normal evaluation chooses a const-generic uninstrumented path; per-node recording compiles out when diagnostics are disabled. CPU execution does not initialize or wait for GPU pipelines.

## Measured work

One complete lit, saved Overworld region at (0, 0), vanilla normal seed 123456789, native budget 16/global Rayon 2:

| Counter | Count |
| --- | ---: |
| VM calls | 189,825,213 |
| Terrain calls | 138,458,578 |
| Material calls | 39,923,712 |
| Auxiliary calls | 378,572 |
| Interpolation field calls | 11,064,351 |
| Node visits | 6,518,078,378 |
| Computed nodes | 2,499,566,906 |
| Interpolations | 515,614,183 |
| Corner lookups | 2,944,869,619 |
| Corner hits | 2,933,805,268 (99.624%) |
| Corner map clears | 293 |
| Horizontal lookups / hits | 19,165,656 / 17,029,262 |
| Primitive VM noise3 calls | 181,285,742 |

Independent diagnostic repetitions have identical work counts. All corner lookups use the bit-exact positive-zero context fast path from the previous cycle. Many cache hits still perform expensive repeated bookkeeping.

Code inspection identifies a larger potential source: composed surface-height queries scan density at every descending block Y. Investigate conservative interval proofs to skip only guaranteed-air intervals while retaining exact integer-block crossing and unknown-interval fallback. Direct reuse of interpolation-cell vertices is a second candidate. Neither change is implemented in this measurement cycle.

## Default-off overhead and correctness

Separate A/B/B/A runs compare the previous compact-corner library against this library with diagnostics disabled. Each process generates the same fresh complete region, with strict decompressed NBT comparison against the first baseline. All four corpora match (1,024 records each), and the diagnostic run also matches the original source baseline and preserves all 1,024 chunks on same-process definition reopen. Actual backend is CPU; GPU jobs and shader-readiness time are zero.

| Variant | Wall seconds (two trials) | Median process CPU seconds |
| --- | --- | ---: |
| Previous | 11.954, 14.243 | 98.100 |
| Counters disabled | 12.550, 12.507 | 99.346 |

Median throughput difference +3.74%, bootstrap interval −4.75% to +13.86%; median CPU work +1.27%. These noisy small samples do not establish a speedup or a material overhead. They are not production tail estimates. This Mac was on AC with normal power mode; unrelated desktop workloads were left running. Absolute times are not comparable to earlier busier runs or the user's unspecified CPU.

Validation: 64 CPU tests passed, 5 optional hardware tests ignored; all 47 registered opcodes compare instrumented/uninstrumented/reference output bits in both surface modes; exact primitive-noise and cache-reuse counts and Send/Sync checked. Portable ordinary release build passed.

## Reproduction and retained evidence

Use `scripts/native-region-benchmark.py --backend cpu --diagnostics --preservation-check` with the full arguments recorded in the raw logs under `~/.cache/retina/cpu-optimization-20261101/`. `cycle3-final-diagnostic/measurements.json` contains all counters and corpus hashes; `cycle3-uninstrumented-abba/comparison.json` and each trial contain timings, library/definition hashes, CPU accounting and output identity. The adjacent compact JSON retains the selected facts. Do not enable trace counters for headline performance comparisons.
