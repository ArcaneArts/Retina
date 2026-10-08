# Generation throughput scope and October 2026 measurements

## Finding

The reported change from more than 2,000 chunks/s to roughly 500 chunks/s is not,
by itself, evidence of a native region-generation regression. Retina currently has
several rates with different completion boundaries:

- **Native region / MCA production** measures 1,024 complete chunks per native
  region request. With production lighting disabled it is a terrain-production
  diagnostic; with lighting enabled it measures fully encoded, compressed and
  written lit MCA output.
- **Chunk-mode completion** measures individual Java/native bridge requests. It
  includes a different request size and scheduling path and cannot be compared to
  the 1,024-chunk region batch by using the same `chunks/s` label.
- **Promotion** publishes an already prepared region and is a count, not new
  terrain production.
- **Live activation** additionally includes Minecraft loading and scheduling and
  is outside the native benchmark boundary.

On the Apple M4 Max / Metal test machine, the real-device `gpuTest` Java bridge
reported **614.99 chunk-mode completions/s** while the canonical lit-MCA benchmark
reported **3,037–3,204 chunks/s** for the retained `8d41f8d` library and
**3,310–3,322 chunks/s** for the lighting candidate. A one-region diagnostic with
the same vanilla profile measured about 2,434 chunks/s without lighting versus
1,888 / 2,160 chunks/s with retained/candidate lighting. These figures describe
different scopes; the approximately 500–600 rate is therefore consistent with
observing chunk mode rather than a fall of the same region workload.

F3 now labels lit-MCA production and chunk-mode completion separately, labels
promotion as publication rather than production, and reports rolling mean and p95
region latency.

## Canonical benchmark method

`scripts/native-region-benchmark.py` writes schema-versioned results containing
the library hash and revision label, dirty harness fingerprint, platform, exact
profile hash, coordinates, readiness, lighting/scope, per-region latency, p95,
peak RSS, transfers, GPU pipeline gaps, stage counters and a decompressed-NBT
corpus manifest. `--compare` requires identical record membership and byte-exact
decompressed NBT and reports the first decoded NBT path on a mismatch.

`scripts/native-region-benchmark-ab.py` executes A/B/B/A order, optionally waits
for shader specialization to reach a final state, compares every candidate corpus
to the retained baseline and records a deterministic bootstrap interval. Builds,
tests and other benchmarks must not overlap a measured trial.

Example production comparison:

```sh
python3 scripts/native-region-benchmark-ab.py \
  --baseline-library build/lighting-next-baseline.dylib \
  --baseline-revision 8d41f8d \
  --candidate-library build/native-target/release/libretina_worldgen.dylib \
  --candidate-revision working-tree-lighting \
  --profile build/task-vanilla-profile.json \
  --out build/vanilla-lit-ab --trials 8 --count 20 --warmups 5 \
  --parallel 1 --lighting enabled --readiness shader-ready
```

Use `--lighting disabled --scope native-region` only as a clearly labeled
diagnostic. The harness rejects a `lit-mca` label when the exported profile lacks
lighting properties.

## Lighting candidate

The candidate removes CPU air padding, packs Minecraft section nibbles on the GPU,
reuses lighting bind groups and timestamp queries, and prunes impossible source
brick searches. Complete outputs match the retained library.

The first four vanilla trials (five warmups and twenty measured regions each) had
median throughput of 3,120.60 chunks/s for the retained library and 3,316.12 for
the candidate, a 6.27% increase. All 40,960 candidate chunk records matched the
baseline and total MCA bytes were identical.

The longer shader-ready Terralith run was system-noise limited. Across eight
trials its retained/candidate medians were 1,135.48 / 1,051.22 chunks/s, but the
95% bootstrap interval for the change was **−29.64% to +21.02%**. Unrelated CPU
stages moved with trial order, so this is inconclusive rather than proof of either
a speedup or regression. Do not use it for a whole-generator claim.

The controlled same-process evidence in
[`benchmarks/gpu-lighting-preparation.json`](benchmarks/gpu-lighting-preparation.json)
is the acceptance basis for the local preparation change: 51,200 paired chunk NBT
records matched, aggregate NBT CPU time fell 27–31%, and complete-region wall-time
changes stayed below 5% and were noisy. Lighting fixture replay also matched the
dense reference and retained implementation. This establishes a dominant-stage
CPU reduction, not a universal end-to-end throughput increase.

## Correctness and validation

- Benchmark helper tests cover percentiles, sparse MCA membership and nested
  mismatch paths.
- Native release tests pass: 64 active tests; 11 explicitly device/fixture-gated.
- `nativeLightingGpuTest` passes section/voxel packing parity with crop, padding
  and buffer reuse.
- `gpuTest` passes the real Metal backend, interpreter/specialized comparisons,
  sparse/dense supporting oracles, cancellation/recovery, negative coordinates,
  rolling telemetry and packet round trips.
- Candidate region comparisons completed 20-region vanilla and Terralith trials
  with 20,480 byte-identical decompressed chunks per candidate trial.
- Serial primary-profile trials, two-caller comparisons and seeds `42` and
  `987654321` together verified **176,128** byte-identical candidate chunks across
  vanilla and Terralith, positive and negative region coordinates.

The complete release gate, loader packaging, isolated server and second-backend
results must be recorded before release. No second GPU/backend was available for
the measurements above, so backend-sensitive scheduling changes must remain
guarded until that validation exists.

## Rejected or inconclusive conclusions

- Do not compare chunk-mode bridge throughput with 1,024-chunk MCA batches.
- Do not invert overlapping request latency to claim concurrent throughput.
- Do not treat driver-warm results as shader-ready unless the recorded program
  state is final.
- Do not claim a Terralith end-to-end gain from the noisy local runs.
- Do not multiply isolated optimization percentages into a combined speedup.
