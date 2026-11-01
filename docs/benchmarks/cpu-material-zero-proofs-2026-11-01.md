# CPU proven zero material rules — 2026-11-01

Many solid voxels invoke the material VM only to receive zero, meaning keep the block already computed. Prepare a conservative proof for this case rather than evaluating the whole graph again.

## Proof and implementation

With registered material layers enabled, `PreliminarySurface` is false below the exact preliminary floor supplied by the column builder. A `VerticalChance` condition is false at or above its upper anchor for every seed. Fold constants and exact And/Or/Not/RangeChoice branch semantics under those assumptions. Each known result carries only the gradient thresholds actually consumed by its proof, so dead branches do not unnecessarily restrict the band.

A shortcut is stored only if root zero is zero and all six returned roots (including implicit node zero) are proven finite. Outside that exact Y band, unsupported graphs and unproven roots use ordinary evaluation. Geometry, caves, biomes, block assembly and feature generation still run. No numeric approximation or GPU algorithm changes.

The initial prototype stored thresholds in general evaluation plans. Its small-budget benefit was diluted at 16 workers. The retained refinement uses a compact material-only threshold table and its exact minimum lower Y, preserving the old general-plan layout and avoiding table reads when no material proof could apply. Both native images and measurements are retained.

Also correct density-bound lookup for accepted empty root lists: use implicit node zero, matching ordinary evaluation, rather than indexing an empty vector and panicking. A regression test compares the resulting certificate with scalar output.

## Matched complete lit MCA results

Normal vanilla seed 123456789, region (0, 0), portable release, global Rayon 2. Each trial is one fresh complete lit, encoded and saved region. Eight alternating A/B/B/A/A/B/B/A trials for each Overworld budget; four for Nether. Baseline is the preceding climate-hint build.

| Case | Median wall seconds | Median process CPU seconds |
| --- | ---: | ---: |
| overworld-16 | 8.329 → 7.894 | 42.935 → 39.720 (-7.5%) |
| overworld-4 | 15.833 → 16.108 | 35.293 → 34.593 (-2.0%) |
| nether-16 | 20.225 → 17.162 | 119.330 → 121.890 (+2.1%) |

The 16-worker CPU reduction repeats across all four candidates (39.44–40.02 CPU seconds versus 42.23–43.63 for all baselines). The small-budget wall difference and the small Nether CPU difference are within the observed variability; no benefit is claimed there. The 4-worker target remains unproven for the retained version. Scheduling and competing desktop work changed during this long session, so compare paired trials rather than absolute times across cycles. These samples are not production p95 estimates.

One diagnostic region proves 15,870,517 material calls unnecessary: actual material VM calls fall from 39,923,712 to 24,053,195; all VM calls from 69,429,442 to 53,558,925; node visits from 2,237,314,028 to 1,824,680,586. Primitive VM noise3 calls remain exactly 189,580,440. This identifies remaining noise/interpolation work rather than implying every removed node has equal cost.

## Validation and evidence

71 CPU tests pass; five opt-in tests excluded (four GPU comparisons and one full CPU chunk acceptance, separately run successfully earlier in this goal). 8,800 actual material proof samples compare against the original evaluator across seeds, contexts, negative/far coordinates and clipped/expanded Y/preliminary ranges. Focused tests cover exact threshold edges, dead-gradient requirements, disabled layers, nonfinite/unproven returned roots, unknown program IDs and implicit node-zero behavior.

All CPU full-region trials compare exact decompressed NBT against the first baseline. The diagnostic matches the original source corpus; custom-pack, negative-coordinate second-seed, central End and outer End regions match original retained output. Reopen preserves all 1,024 records; actual backend is CPU, with zero GPU jobs and no shader-readiness wait. GPU logic is unchanged; the preceding cycle's real Metal guard remains applicable to its shared climate change.

Raw commands, native hashes, profiles, per-trial measurements and corpora live under `~/.cache/retina/cpu-optimization-20261101/`. Retained commands are `cycle7-flat-screen.py` and `cycle7-flat-parity.py`; the earlier `cycle7-*` results are the first prototype. The selected image is `cycle7-flat-candidate.dylib`. The adjacent compact JSON retains trial summaries and parity markers.

During the refinement build, an owned compiler spent only about 40 CPU seconds in nine wall minutes at priority 4. `taskpolicy -B` was attempted for that compiler/Cargo, then uniformly for baseline/candidate shells. Immediate priority remained 4, so this does not establish enforced foreground priority. No system power settings, focus, or unrelated processes changed. The original scheduling observation is retained in `progress.json` and the compiler sample.
