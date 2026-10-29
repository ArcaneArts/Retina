# Windows startup diagnostics

The subsequent RTX 4090 / NVIDIA 617.14 Vulkan report initialized the engine in
0.435 seconds. Emitting SPIR-V took roughly 1 ms, but `vkCreateComputePipelines`
for the world interpreter's `main` took 67.288 seconds (67.172 seconds of calling
thread CPU). `lake_candidates` and `lake_density` then took 23.746 and 12.609
seconds in the driver. Background specialization had not started. This localizes
the delay to driver compilation of required interpreter pipelines, independently
of DXC, eager initialization, and concurrent specialized compilation. It does
not identify which optimizer pass is responsible or establish a GPU execution
performance ratio between Metal and Vulkan.

## Vulkan interpreter compilation policy

Windows NVIDIA Vulkan now requests `VK_PIPELINE_CREATE_DISABLE_OPTIMIZATION_BIT`
for generic interpreter pipelines, including the demand-created base fallbacks.
Specialized pipelines keep normal optimization. Other vendors/platforms retain
their previous default; Metal and DX12 compilation are unchanged. The Vulkan
flag permits a compilation/runtime tradeoff; it does not guarantee faster
compilation, and interpreter generation throughput must also be measured.
Shader code, output semantics, bounds checks, and validation are unchanged.

This tests a whole-pipeline compilation policy, rather than changing individual
shader operations. Startup still compiles the selected interpreter entries
synchronously. A fast result would implicate driver optimization of the generic
interpreter; it would not establish which internal optimization is expensive.
`RETINA_VULKAN_FAST_COMPILE=0` restores previous interpreter flags for an A/B run;
`1` forces the fast policy on any Vulkan device. Each call uses a thread-local
scope, so a background specialized compilation cannot inherit the fast policy.

Keep Vulkan for this build (`-Dretina.gpuBackend=vulkan`). The log reports the
actual flags at the driver boundary, e.g. `optimization_disabled=true` for the
interpreter and `false` for specialization. If the same driver phase exceeds
90 seconds, stop and send the complete log and the newest directory under
`retina-diagnostics/vulkan-<time>-<pid>/`. Its absolute location is logged.

Each pipeline directory saves `shader.spv` from the exact Naga output passed to
Vulkan, plus `case.json` with entrypoint, adapter/driver version, byte length,
SHA-256, effective pipeline flags, and phase timestamps. SPIR-V is saved before
shader-module creation and flags before `vkCreateComputePipelines`, preserving
evidence if either call hangs. These are shader inspection captures, not complete
standalone Vulkan replay cases (pipeline layouts are not serialized). The existing
probe's replay menu remains DX12-only. Capture errors never prevent generation;
`RETINA_SHADER_CAPTURE=0` and `RETINA_SHADER_CAPTURE_DIR` apply to both backends.

## Earlier DX12 report and startup scheduling

The 1.1.434 RTX 3060 / DX12 report completed engine initialization in 299.347
seconds, then spent another 270.679 seconds compiling the world interpreter's
`main` pipeline. Of that second operation, 24.631 seconds were in DXC and 246.042
seconds were in `ID3D12Device::CreateComputePipelineState`. The generic and
specialized compiler threads were both accumulating CPU time. This report shows
slow compilation and real progress; it does not show an idle deadlock.

Vulkan and DX12 now retain base interpreter descriptors and compile each base
entrypoint only when a request selects it. A world-specific replacement does not
compile the unused base entrypoint. Each selected base pipeline remains cached
in memory for the lifetime of the GPU device. Metal retains eager initialization.
`RETINA_EAGER_PIPELINES=0` or `1` overrides this policy for controlled comparisons.
Shader modules and layouts are still validated during engine initialization.

Automatic specialization on Vulkan and DX12 starts after the first request's
required pipelines, including base fallbacks, have compiled. Explicit specialized
execution still waits for specialization; explicit interpreter execution never
starts it. `RETINA_DEFER_SPECIALIZATION=0` or `1` overrides scheduling. These
changes avoid unused work and initial compiler contention; they do not establish
that a required Windows driver compilation is fast.

`[Retina startup]` messages include:

- The selected backend, adapter, and compiler/pipeline policy.
- The entrypoint inventory for each world pipeline compilation workload.
- BEGIN, END, and periodic WAIT stages. Driver phases carry `pipeline_id` and
  `entry`, tying DXC/FXC or driver work to its enclosing compute pipeline.
- Windows cumulative calling-thread CPU time on WAIT and END. This excludes
  compiler/driver work delegated to other threads.

Keep the same backend for successive comparisons (`-Dretina.gpuBackend=dx12`
in the launcher for DX12). Send the complete launcher log, including the selected
adapter and completed END lines. If the same driver phase exceeds 90 seconds,
the log is already useful; a ten-minute wait is not required. A second launch
without changing JAR, driver, world, or backend can reveal driver-cache reuse,
but a warm result does not measure cold startup.

The double-clickable GPU probe tests device initialization when no definition
is supplied and says explicitly that world pipelines remain untested. Dropping
`retina.dat` onto it exercises the actual world pipeline path. Engine
initialization alone does not mean all base pipelines have compiled.

For a host-GPU regression check, run the ignored
`deferred_base_selection_preserves_world_and_procedural_output` native test with
`RETINA_EAGER_PIPELINES=0` and `RETINA_STARTUP_DEFINITION` pointing to a vanilla
`retina.dat`. It checks unused entrypoints stay uncompiled, selected entrypoints
compile once, world columns/material masks match the fully compiled inventory,
and procedural generation still works. This is output/scheduling coverage, not a
Windows driver timing benchmark.

## Exact DX12 captures and standalone replay

Windows x64 DX12 now saves the real compiler/driver inputs automatically in
`retina-diagnostics/dx12-<time>-<pid>/` under the process working directory
(normally the instance's `.minecraft` directory). The startup log prints the
absolute path. Each compute pipeline has its own directory, such as
`000050-surface_columns`, containing:

- `case.json`: adapter/driver identity, original DXC arguments, phase timestamps,
  input lengths and SHA-256 hashes, and the last completed capture phase.
- `shader.hlsl`: the exact string about to be passed to DXC.
- `shader.dxil`: the exact compiled bytes about to be passed to the DX12 driver.
- `root-signature.bin`: the serialized root signature used for that pipeline.

Files are written before their respective blocking calls. A process killed while
the driver compiles still leaves the bytecode, root signature and a manifest
marked `driver-running`. If DXC has not finished, the HLSL/arguments are already
available. An in-memory wgpu shader-cache hit may have no HLSL/arguments in that
case directory, but driver-only replay remains available. Persistence errors
are logged and do not prevent generation. `RETINA_SHADER_CAPTURE=0` disables
capture; `RETINA_SHADER_CAPTURE_DIR` changes the parent directory. Captures stay
on disk until deleted and are never uploaded automatically.

For a DX12 capture, use `-Dretina.gpuBackend=dx12`. After a driver phase
has run for 90 seconds, send the launcher log and a ZIP of the newest capture
directory. No Java debug tools are needed. The DX12 path keeps production shader
generation and compiler optimization settings unchanged.

Drag a captured `case.json` or its containing directory onto the Windows
`retina-gpu-probe-windows-x64.exe`. The menu runs one isolated child process:

1. **Driver only**: captured DXIL and root signature directly into D3D12. No DXC,
   Naga, Minecraft, or terrain generation runs.
2. **DXC original**: captured HLSL and arguments through the same bundled DXC.
   No GPU device is needed and no pipeline is created.
3. **DXC without optimization**: same input with only optimization arguments
   replaced by `-Od`. No pipeline is created.
4. **Without optimization, then driver**: compile with `-Od`, then create the
   pipeline from the resulting bytecode and captured root signature.

No replay mode dispatches the shader. Compiler/driver stages retain periodic
CPU-time diagnostics, and Ctrl+C stops the child process. Original capture files
remain unchanged; each replay writes a new `replay-<mode>-<time>-<pid>` directory
with `report.json`, input/output hashes, timings, actual replay adapter, and any
new DXIL. The console log is beside the executable. Driver timing in the JSON
includes root-signature creation; the log splits out the actual D3D12 calls.
Changing drivers/adapters or warm driver caches affects comparisons and is
recorded rather than blocked. Edited HLSL can be replayed for reduction; its
actual hash is recorded rather than rejected against the original hash.

For automation:

```text
retina-gpu-probe --replay <case.json> driver
retina-gpu-probe --replay <case.json> dxc
retina-gpu-probe --replay <case.json> dxc-no-opt
retina-gpu-probe --replay <case.json> no-opt-driver
```

Capture uses an observer in the pinned wgpu-hal source, not a parallel Naga
translation that might differ from the real input. See
[`RETINA_PATCH.md`](../native/vendor/wgpu-hal/RETINA_PATCH.md) for the upstream
archive checksum and exact patch scope. Gradle tracks those sources as native
build inputs. The scoped Vulkan changes are described above; Metal keeps its
existing code path.
