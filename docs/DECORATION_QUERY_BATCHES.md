# Batched decoration samples

A decoration replay can discover both unresolved placement counts and block
provider noise. Those queries come from the same provisional anchor pass; neither
set depends on the results of the other. They previously took two GPU submissions,
two polls and two readback cycles under separate acquisitions of the sampling lock.

The sparse sampler now submits both compute passes in one command buffer and maps
one result buffer. Count rules and provider stacks remain separate resident tables;
the shaders and all numerical operations are unchanged. Each shader gets only its
own active query range. Input/output binding offsets respect the device's storage
alignment, and the small padding gap never becomes a result. Results retain their
original order and are installed in the same replay round as before.

One-kind batches still use one pass; empty batches do no GPU work. Invalid provider
IDs and missing count noise fail without submitting work. Both readback buffers
are unmapped on success or error. The existing sampling mutex owns the reusable
buffers until readback completes, including with concurrent profile callers.

The sampler retains one input/output/readback allocation set, growing to the
largest combined active batch seen, rather than allocating additional in-flight
sets. Capacity is the sum of the two query counts plus at most one storage-alignment
gap (less than 64 query slots with the default 256-byte alignment). Each slot costs
24 bytes across the three buffers. Compared with separate calls this can retain
both sets' capacity rather than the larger set alone; the change does not add
threads, concurrent regions or queues. Static profile tables retain their previous
lifetime. It is not an absolute process-memory cap.

Count and provider device timestamps still occupy their existing independent F3
slots. Upload/readback accounting includes the actual alignment and timestamp
bytes once. The normal timing ABI is unchanged. Opt-in planner diagnostics add
`combined_sample_batches`, so an apparent overall gain cannot be attributed to
batching when the plan never uses it.

## Bounded evidence

Apple M4 Max/Metal, one warmup then seven alternating pairs, exact result-vector
comparison on every pair. The sparse harness uses the native test profile
(`opt-level=1`); it is a dispatcher diagnostic, not a release region benchmark.
The reference uses the same sampler/shaders with separate count and provider calls.

| Count / provider queries | Separate median | Combined median | Separate max | Combined max |
| --- | ---: | ---: | ---: | ---: |
| 64 / 128 | 0.472 ms | 0.266 ms | 0.537 ms | 0.307 ms |
| 1024 / 4096 | 0.427 ms | 0.266 ms | 0.546 ms | 0.271 ms |
| 67 / 0 | 0.207 ms | 0.208 ms | 0.318 ms | 0.225 ms |
| 0 / 4096 | 0.226 ms | 0.224 ms | 0.292 ms | 0.227 ms |

Mixed sampling latency fell 43.6% and 37.7%. Single-kind medians were essentially
unchanged. These small samples do not establish p95. Maximum RSS was 22.4 MiB;
macOS reported a 173.0 MiB peak footprint. Both paths share that process, so these
are not paired memory results or total GPU-allocation measurements.

A release Terralith 8x8 retained-field plan at chunk (0,0), seed 123456789, had
zero combined rounds: its 68 count queries preceded its 82 provider queries.
Its 7.009 ms median is a standalone observation, not an A/B speedup. Batching
helps recipes that discover both kinds in one round, such as providers combined
with counts after live per-floor expansion; it does not remove sequential data
dependencies or speed every world profile.

A separate pre-change/candidate release-library comparison checked complete final
block and column bytes for eight uncached chunks per vanilla/Terralith profile,
including two concurrent callers. Every output matched. Batch medians were
67.688 -> 69.102 ms for vanilla and 95.930 -> 96.010 ms for Terralith. These small
mixed results do not demonstrate a whole-generator improvement. They exclude
MCA encoding, lighting and publication. Raw samples, hashes, transfer counts,
memory observations and detailed planning counters are in
[`benchmarks/decoration-query-batches.json`](benchmarks/decoration-query-batches.json).

## Validation

- 68 normal native unit tests passed; typecheck, release library/example build,
  formatting and diff checks passed.
- Actual-GPU tests compare every count and provider float bit with separate
  dispatches, including odd lengths, binding gaps, resized/reused buffers, zero
  inputs, two-dimensional dispatch, and positive/negative distant positions.
- Complete mixed-query replay fixtures compare every ordered placement at chunk
  origins 0, -33 and 700000, including trees, paired plants, live floor counts,
  nested providers and resumed anchors. The test asserts that mixed rounds occur.
- Devices with timestamps enabled and disabled are tested. Two callers interleave
  several profile IDs, and valid work is checked after invalid requests.
- Runtime checks used 30-second process-group deadlines; builds used two jobs and
  codegen units at reduced priority with 60-second deadlines. Planner checks use
  two workers. No game or full-region benchmark was launched.

Run the ignored native tests `combined_samples_match_separate_dispatches` and
`combined_samples_benchmark` explicitly under a process-group deadline. The latter
prints the raw alternating A/B samples and verifies outputs outside the timings.

Worker scratch reuse, GPU tree/leaf investigation, GPU ore preparation and Java
column-cache optimization remain follow-up cycles.
