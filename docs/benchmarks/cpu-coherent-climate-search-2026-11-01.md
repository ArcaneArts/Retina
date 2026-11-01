# Exact coherent CPU climate search — 2026-11-01

After height acceleration and saved-biome reuse, CPU profiles showed climate target distance/traversal work dominating the sampled biome phase. Neighboring queries can now seed traversal with the previous winning leaf's freshly recomputed exact distance, tightening the initial upper bound without changing biome decisions.

## Semantics and scope

Hints live in the CPU sampler (surface/underground) or weighted site sampler. Each lookup validates the hint against the current query range, leaf tag and shore filter, computes its current distance and ocean penalty, and uses it only if it strictly beats the caller's initial bound. Initial-candidate precedence and original registry order still resolve ties. The full tree traversal continues to find any better or earlier equal target. Arbitrary stale hints therefore affect work only, never authority over the answer.

Linear tables and a single leaf group use ordinary traversal: an extra seed evaluation cannot prune further distance work there. No target data, distance arithmetic, float precision, GPU tree layout or serialized GPU table changes. Production search has no visit counters; a const-generic test path observes actual distance evaluations.

## Measurements

For 1,536 actual vanilla quart-climate samples, distance evaluations fall from 885,903 to 534,443 (39.7% fewer), including reevaluating each hint. Every result equals ordinary search.

Matched complete lit MCA A/B/B/A against the preceding biome-reuse build, normal seed 123456789, region (0, 0), one fresh region/process, zero warmups, global Rayon 2:

| Case | Median wall seconds | Median process CPU seconds |
| --- | ---: | ---: |
| overworld-16 | 14.871 → 14.707 | 56.826 → 48.927 (-13.9%) |
| overworld-4 | 33.669 → 26.126 | 54.356 → 45.186 (-16.9%) |
| nether-16 | 27.764 → 27.352 | 135.524 → 135.404 (-0.1%) |

Overworld process CPU work falls 14–17%. The 16-worker wall samples show no established gain, while the smaller budget benefits in this round. Unrelated desktop contention caused substantial timing variation, so absolute values must not be compared across rounds. The Nether intentionally keeps its small-table traversal and is essentially unchanged. These two samples per variant are not production tail estimates and do not prove a target on the user's unspecified hardware. Hardware is an Apple M4 Max on AC/normal power; no unrelated processes or power settings were changed.

## Validation and next ranking

Five climate tests passed, including an independent linear oracle over weighting, depth, ocean penalty, shore exclusion, negative/zero/large initial bounds, registry tie order, out-of-range/branch hints and nonfinite query inputs. The actual climate sequence test checks both answers and distance-work counts. 68 CPU regression tests passed; the five opt-in exclusions are four hardware comparisons and the separate full CPU acceptance test (the latter ran successfully in the preceding cycle).

Every repeated CPU full-region trial matches all baseline decompressed NBT. Custom-pack, negative-coordinate second-seed, central End and outer End checks match original retained corpora, with exact reopen preservation of 1,024 records. Actual CPU selection, zero GPU jobs and no GPU readiness wait are verified.

Real Metal A/B/B/A execution also passes full-region NBT equality (1,024 records/trial), with actual GPU jobs. Candidate timings 0.565/0.570 seconds fall within baseline 0.546/0.878 seconds; this is a regression guard, not a Metal speedup claim. Its first setup attempt checked readiness before any terrain query (zero warmups) and failed at status zero; the retained rerun performs one real warmup region before readiness and timing.

Reprofiling the CPU result now emphasizes evaluator scheduling, corners and noise in the sampled material/cavity phase; late samples include lighting. Registered material calls still number 39.9 million per region. Investigate conservative context proofs that can bypass material evaluation only where every returned root is known finite and the selected result is proven, with full fallback elsewhere. Exact horizontal-distance reuse remains a later candidate if early climate cost still warrants it. Leaf sample counts are diagnostic leads, not elapsed-time shares.

## Reproduction

Evidence under `~/.cache/retina/cpu-optimization-20261101/` includes `cycle6-screen.py`, `cycle6-parity.py`, command JSON, per-trial measurements, hashes, NBT corpora and profile samples. The corrected GPU guard is `cycle6-metal-guard-warm.py`; the failed setup log is retained separately. `cycle6-timed-source.patch` preserves the timed implementation before a whitespace-only rustfmt pass. The adjacent compact JSON retains medians, work counts, parity and Metal results. Final delivery will rebuild the merged source.
