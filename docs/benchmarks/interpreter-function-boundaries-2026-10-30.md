# Preserve interpreter function boundaries

The reported Windows run explicitly selected DX12 on an RTX 3060. Its first
compact `retina_world_dispatch` source was 80,249 bytes with one entry point.
DXC took 40.918 seconds; NVIDIA's subsequent pipeline creation was still running
at 299.1 seconds, with 292.953 seconds of CPU time on the calling thread. The
matching Metal log reported the same policy, source size, and entry point,
compiled in 0.980 seconds. Source sizes do not establish binary identity or
matching driver-cache state.

The common application path already matched Metal. The investigation found that
DXC expanded this small bytecode interpreter into almost 2 MB of DXIL. Merely
sharing its scratch arrays reduced that by about 4% and did not materially
improve DXC time; that experiment was discarded.

The implemented change gives each large interpreter body a void, zero-argument
ABI, using private per-invocation input/output storage at each interpolation
depth. Its original signature becomes a small wrapper. Register initialization,
bytecode operations, request scheduling, and the original reference interpreter
are preserved. The DX12 backend adds `[noinline]` only to these body definitions.
Vulkan marks the same bodies `DontInline`, using emitted function names to locate
their SPIR-V IDs. Metal receives the same WGSL and keeps its normal compiler
policy. Driver compilers may still choose to inline functions.

Simply applying `[noinline]` to the original functions fails DXIL validation:
those signatures return arrays and pass vectors/structs. The void ABI avoids
that limitation without disabling validation or compiler optimization.

## Host compiler experiment

Naga 30.0.1; mach-dxcompiler-rs 0.1.7+2026.10.26-141dd7f.1; Apple Silicon host.
DXC options: `-E <entry> -T cs_6_7 -HV 2018 -no-warnings -Ges`.
Synthetic HLSL binding numbers; no GPU pipeline creation. Single sequential
observations while other build work ran, not a statistical benchmark.

| Input | WGSL bytes | DXIL bytes | DXC seconds | Retained bodies |
|---|---:|---:|---:|---:|
| Original compact world | 80,249 | 1,993,088 | 18.700 | 0 |
| Outlined compact world | 82,197 | 239,208 | 1.734 | 3 |
| Outlined compact caves | 96,103 | 145,300 | 0.793 | 3 |
| Outlined composed world | 82,277 | 82,716 | 0.345 | 3 |
| Outlined composed caves | 96,083 | 130,884 | 0.731 | 3 |

All four outlined outputs passed DXC validation and `spirv-val --target-env
vulkan1.1` (SPIRV-Tools 1.4.363.0). HLSL and SPIR-V retained the same three bodies.
The compiler audit tool and test-only `RETINA_COMPILE_DUMP` make this repeatable.

## Generation and build validation

- Native release tests: 384 passed, 98 opt-in GPU/integration tests ignored.
- Backend transformation tests: 2 passed.
- `compact_sampling_matches_reference_world_generation`: 8 Overworld/Nether
  cases, 14,336 columns and 6 material masks matched the original interpreter.
- `common_policy_shared_dispatch_matches_independent_generation`: 8 cases,
  18,432 columns and 8 material masks matched independent pipelines, with and
  without specialization. End remains on its separate generation path.
- Windows x64 release DLL and standalone GPU probe cross-built with bundled DXC
  and static CRT.

These measurements demonstrate reduced DXC expansion. They do not establish
NVIDIA driver startup time or Windows generation correctness; the next Windows
run must test that. The runtime workload log now includes `outlined_bodies`,
and existing driver captures include the actual transformed shader artifacts.
