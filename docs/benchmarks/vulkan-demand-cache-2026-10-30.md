# Vulkan startup demand compilation and persistent cache

## Reported Windows run

`message (5).txt`: Retina 1.1.442, Vulkan, NVIDIA GeForce RTX 3060.
Generation succeeds. The trace ends while background specialization is still
compiling, so it does not establish steady-state Vulkan versus DX12 throughput.

| Completed stage | Wall time |
| --- | ---: |
| Native engine initialization / eager fallback compilation | 139.224 s |
| Required composed interpreter world dispatcher | 94.320 s |
| Required composed interpreter cave dispatcher | 55.146 s |
| Required interpreter bundle | 149.500 s |
| Unrequested legacy interpreter bundle, before specialization | 63.847 s |
| Minecraft reported spawn preparation | 172.498 s |

The large costs are inside `vkCreateComputePipelines`, with substantial CPU time
on the compiler thread. SPIR-V emission is milliseconds. These stages are nested;
do not add the dispatcher times to their enclosing bundle time.

## Change

- Base fallback pipelines are lazy on every backend. Existing environment
  overrides still permit the former eager policy for comparisons.
- A loaded profile queues only its required, compatible interpreter layout,
  before its specialization job. Legacy layouts still compile when requested.
- Vulkan's wgpu pipeline cache is explicitly enabled when supported and supplied
  to terrain and specialized pipelines. Opaque cache data is persisted after
  completed compiles, separately for foreground and background compilation.
- Atomic replacement, bounded reads, a length/checksum envelope and wgpu's own
  compatibility validation keep disposable cache failures from blocking generation.
  Cache load/save messages expose what actually happened; loading is not reported
  as a confirmed hit.

The terrain arithmetic, specialization flags and driver optimization policy are
unchanged. Shader-driver compilation can still dominate a first cold run. This
change removes unused work and retains completed work between runs; it does not
claim to make an individual NVIDIA compiler invocation faster.

## Validation

- Native release suite: 389 passed, 100 ignored, including cache replacement,
  truncated/corrupt payload rejection and unchanged backend-selection tests.
- Metal demand-selection test: exactly 8 of 22 base pipelines used for the
  Overworld fixture; output equals the fully compiled inventory. Procedural
  generation also succeeds.
- Metal shared-versus-independent generation oracle covers Overworld and Nether,
  interpreter and specialized modes, negative coordinates and expanded heights.
  An additional legacy-composition case verifies demand loading of that layout.
- Windows x64 static-CRT native library/probe cross-build.

Windows Vulkan cache-hit timings require a same-JAR, same-driver second launch.
The Metal tests do not validate NVIDIA's runtime or establish a Windows speedup.

wgpu's [PipelineCache documentation](https://docs.rs/wgpu/30.0.1/wgpu/struct.PipelineCache.html)
explains the Vulkan implementation and opaque persistence contract. Driver-managed
caches may already cover some work, so the benefit must be measured.
