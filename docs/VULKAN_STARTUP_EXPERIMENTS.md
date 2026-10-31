# Vulkan startup experiments

The October 31 pass preserves established compiler and scheduling defaults.
Cache lifecycle fixes, bounded compilation admission, precise stage reporting
and verified Chunky catalog selection apply normally. New compiler policies are
opt-in until matched Windows runs establish a winner. They preserve generation
precision, bounds checks and validation.

| Environment variable | Default | Comparison |
| --- | --- | --- |
| `RETINA_VULKAN_TIERED_INTERPRETER` | off | `1`: fast initial interpreter followed by optimized promotion |
| `RETINA_INCREMENTAL_SPECIALIZATION` | off | `1`: publish compatible specialization cohorts as they finish |
| `RETINA_INTERPRETER_COMPILE_THREADS` | `1` | `2`: bounded overlap of required world/cave compilation |
| `RETINA_VULKAN_SPIRV` | `naga` | `tools`: explicit safe SPIRV-Tools sequence before the driver |
| `RETINA_INTERPRETER_GROUPS` | `1` | `2`: bounded compact dispatcher families |
| `RETINA_INTERPRETER_DISPATCH` | existing backend policy | `0`: independent-pipeline control; overrides grouping |
| `RETINA_REGION_THREADS` | shared pool, CPU-relative/clamped | Positive value up to 16: native assembly budget |

These are process environment variables, not TOML keys or required JVM flags.
Restart the whole game/server process between comparisons. Remove experimental
variables to return to normal behavior. Tiered promotion and compiler-input
processing apply to Vulkan; unsupported API paths keep their existing behavior.
The bounded compiler-thread option is also usable by native Metal comparisons.

## Windows retest

In `config/retina.toml`, use `debug = true` for a diagnostic run. To isolate
Vulkan, set `allow_vulkan = true`, `allow_directx = false`, and
`allow_metal = false`; reverse Vulkan/DirectX for its control. Confirm the
reported adapter/backend rather than inferring it from Minecraft's renderer.
Use the same definition, seed, coordinates, power state and workload. Start with
the defaults, then compare each of the first five experiments separately before
combining winners. Keep user `mca_parallelism` fixed for compiler comparisons.

For each setting retain a first and repeated-process log. Distinguish an empty
Retina application cache from an actually cold driver cache; a loaded cache file
is not confirmed driver cache-hit feedback. Collect the full log from startup
through preparation and specialization readiness, then at least 60 seconds of
the same Chunky workload. Separate first usable terrain, optimized interpreter
readiness, partial/full specialization and steady generation. Debug driver phase
records and progress show stage completion; the driver exposes no reliable
percentage inside a single pipeline creation call. No debugger attachment or
standalone probe executable is required.

Use `scripts/backend-startup-benchmark.py` for native startup comparisons and
`scripts/generation-concurrency-benchmark.py` for matched region/assembly/compiler
sweeps. Run headline throughput with normal diagnostics off after a separate
diagnostic run. Keep GPU pipeline depth unchanged unless queue-idle evidence
supports a bounded experiment.

## Evidence and limits

The original RTX 3060 logs attribute about 148.6 seconds to required Vulkan
interpreter creation versus 20.8 seconds for DirectX. SPIR-V generation itself
took milliseconds. Both logs end before specialization is ready, and their
definition preparation differs because only one included Chunky. The eight-way
Chunky throughput ranges are indicative, not a controlled comparison.

Local Metal checks and optional MoltenVK correctness tests exercise the new
lifecycle, grouping, cache and compiler-input paths. MoltenVK is not NVIDIA
performance evidence. Its pre-existing full-material translation failure and
slow forced full-specialization control limit the Vulkan-on-Mac coverage; the
terrain-only controls are explicitly labeled. Grouping was slower to create in
the local diagnostic and remains experimental. The concurrency sweep also
encountered unrelated CPU contention, so it does not establish new budgets.

The existing pinned static DXC compiler can generate DXIL but rejects SPIR-V
because its SPIR-V backend was built out. The reproducible
`scripts/dxc-spirv-probe` records that concrete blocker; no unsupported runtime
mode is advertised or packaged.

Detailed reports: [baseline](benchmarks/vulkan-rtx3060-baseline-2026-10-31.md),
[cache](benchmarks/vulkan-cache-lifecycle-2026-10-31.md),
[promotion](benchmarks/vulkan-interpreter-promotion-2026-10-31.md),
[parallel compilation](benchmarks/parallel-interpreter-compilation-2026-10-31.md),
[compiler input](benchmarks/compiler-input-experiments-2026-10-31.md),
[grouping](benchmarks/interpreter-dispatch-groups-2026-10-31.md),
[concurrency](benchmarks/generation-concurrency-2026-10-31.md).
