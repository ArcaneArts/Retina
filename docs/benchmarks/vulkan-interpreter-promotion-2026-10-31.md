# Optional two-stage Vulkan interpreter

`RETINA_VULKAN_TIERED_INTERPRETER=1` opts Vulkan into an initial interpreter
compiled with the existing disable-optimization policy. Its exact same source
is then compiled with normal optimization on the existing single background compiler.
Metal and DirectX retain their normal lifecycle. The option is off by default
until NVIDIA startup and execution measurements justify changing it.

The stage policy is thread-local and restored across nested calls and unwinds.
It overrides the older whole-run `RETINA_VULKAN_FAST_COMPILE` experiment for these
two stages: the initial job is fast and its replacement is optimized. Specialized
pipelines retain normal optimization. Separate cache variants retain fast and
optimized data, and terrain/compiler lanes keep separate objects and locks.

Compact preload and on-demand wide/deep interpreter bundles use promotion.
Procedural base fallbacks retain their existing lifecycle. Optimized jobs reuse
exact source, compatibility masks and dispatch mode. They publish immutable
complete bundles; later submissions adopt them while encoded commands and
material continuations retain their original resources. A failed optional job
leaves the initial interpreter usable. If on-demand fast creation fails, normal
creation is attempted.

Idle reuse does not own pending jobs after their live profile owners retire.
A driver invocation already executing is allowed to complete; no unsafe driver
cancellation or generation deadline is introduced.

The existing native program snapshot ABI is unchanged. An additional
`retina_gpu_interpreter_snapshot` uses that same version-1 structure: status 0
means no optimization requested, 1 pending, 2 optimized-ready, and 3 failed.
The headless region runner accepts `--tiered-interpreter enabled` and
`--readiness optimized-interpreter` after at least one warmup. Its JSON retains
optimization status and duration separately from specialization.

Use `--readiness driver-warm` to observe initial generation, then a separate
optimized-ready run on identical coordinates to measure final throughput.
Compare native NBT and per-stage timings as well as startup; a fast initial
compile may execute more slowly. Normal optimization of this interpreter is
necessary even when `RETINA_SPECIALIZED_TERRAIN=0`.

Validation: 418 native tests passed (110 opt-in tests ignored). Four real GPU
lifecycle runs passed: Metal and Vulkan, each with preload enabled and disabled.
Every run compared initial versus optimized Overworld and Nether output at
negative coordinates with expanded height, while both submissions were in flight.
That is 18,432 column comparisons across eight dimension cases. Metal also
compared material output; Vulkan explicitly disabled materials in its test
fixture to avoid the documented MoltenVK workgroup-initialization translation
error. No production definition is modified by these test-only fixtures.

The Metal test explicitly exercises the lifecycle through a test-only constructor
override; the shipping option remains Vulkan-only. Vulkan feedback confirms
`fast_compile=Some(true)` for initial creation and `Some(false)` for optimized
creation. Cache state is uncontrolled beyond the private application directories;
these tests establish behavior and sampled equality, not NVIDIA speed. Unit tests
cover failed optional compilation, immutable in-flight owners, retired pending
work, nested/unwound policy scopes, and completed readiness reused by new owners.

On macOS, run the Vulkan command directly with the loader environment. Putting
`nice` between that environment and Cargo causes macOS to strip the DYLD loader
path. Native Metal benchmarks can still use `nice`.
