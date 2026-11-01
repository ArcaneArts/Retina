# CPU initial light frontiers — 2026-11-01

Lighting was the largest remaining serial assembly cost in the late profiles. The old CPU implementation calls the initial propagation function for every voxel, including full-sun interiors that cannot raise a neighbor. Retain only initial light sources capable of changing another voxel, then use the existing propagation queue unchanged.

## Exact source reduction

Direct sunlight is a contiguous suffix of each column. Record its lowest Y during the original initialization. Seed its bottom voxel and the union of horizontal sun-to-shadow edges, determined by neighboring columns' lowest sunlight. Seed original material emission above one separately; initial levels zero or one cannot survive the minimum attenuation of one. Check original emission rather than propagated values. No emitter-index list or per-voxel metadata is added: one `usize` per horizontal column, released with the existing tile buffers.

Omitted full-sun interior cells cannot increase sky light, because all their neighbors already start at 15. Omitted dark cells still enter the queue whenever raised; any block-light increase also queues full-sun cells. Updates are monotone integer maxima on a fixed graph with positive attenuation, giving the same fixed point for any source order. Loaded opacity, face occlusion, halo, packing and the propagation kernel are unchanged. The old complete source scan remains a separate const-generic test reference.

## Complete lit MCA measurements

Portable release, local M4 Max, vanilla seed 123456789, region (0, 0), global Rayon 2. Baseline is the preceding interpolation-cell build. Eight alternating trials per Overworld budget; four for Nether. Every measured region saves all 1,024 chunks with lighting and all normal generation stages.

| Case | Median wall seconds | Median process CPU seconds |
| --- | ---: | ---: |
| overworld-16 | 8.530 → 6.380 | 41.314 → 39.196 (-5.1%) |
| overworld-4 | 14.491 → 13.177 | 32.179 → 30.349 (-5.7%) |
| nether-16 | 17.729 → 17.777 | 124.833 → 125.509 (+0.5%) |

The serial reduction improves wall time more than summed CPU work. All four small-budget candidate trials finish below 15 seconds locally. This is not a promise for the unspecified machine behind the user's 50-second observation. Nether is effectively neutral. Trial counts are too small for production p95 estimates; unrelated desktop scheduling remains variable.

A separate diagnostic region records lighting host time around 1.1 seconds versus the preceding build's 2.7 seconds. These diagnostic runs are not the headline comparison. Reprofiling and raw stage counters are retained outside Git.

## Validation

74 CPU tests pass (five opt-in cases excluded), plus four focused lighting tests. Forty-eight old/new complete-volume comparisons cover open sky and no-sky, emitters including level one, opaque/partial-opacity materials, directional loaded faces, irregular cliffs/cavities, cropped halo boundaries, 16/32/80-block volumes and both voxel/packed-section formats. An explicitly executed real Metal test passes six exact CPU/GPU integer-channel cases including registered faces.

Every timed trial compares strict decompressed NBT. A diagnostic region and custom pack, negative-coordinate second seed, central End and outer End match original source corpora. All 1,024 records survive reopen. Actual CPU backend, zero GPU jobs and no shader-readiness wait are verified. This changes only CPU lighting; GPU propagation and scheduling remain untouched.

Raw commands, paired measurements, hashes, native images and profiles remain in `~/.cache/retina/cpu-optimization-20261101/` (`cycle9-screen.py`, `cycle9-parity.py`, `profile-cycle9.py`). No client, focus, power settings or unrelated process changes were used. Remaining substantial work is terrain/noise evaluation, followed by material staging and light initialization; prepared unique roots are the next bounded evaluator experiment.
