# Windows startup diagnostics

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

The double-clickable GPU probe runs a real procedural terrain query when no
definition is supplied. Dropping `retina.dat` onto it also exercises the actual
world pipeline path. Engine initialization alone no longer means all base
pipelines have compiled.

For a host-GPU regression check, run the ignored
`deferred_base_selection_preserves_world_and_procedural_output` native test with
`RETINA_EAGER_PIPELINES=0` and `RETINA_STARTUP_DEFINITION` pointing to a vanilla
`retina.dat`. It checks unused entrypoints stay uncompiled, selected entrypoints
compile once, world columns/material masks match the fully compiled inventory,
and procedural generation still works. This is output/scheduling coverage, not a
Windows driver timing benchmark.
