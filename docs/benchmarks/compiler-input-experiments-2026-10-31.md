# Vulkan compiler input experiments

`RETINA_VULKAN_SPIRV=naga` retains the established input path and cache filenames.
It remains the default. `tools` opts into statically linked SPIRV-Tools 0.13.3
processing; no installed SDK, executable, or compiler DLL is required at runtime.
The native benchmark accepts `--vulkan-spirv naga|tools`. Metal and DX12 do not
enter this Vulkan-only path. A source build can exclude the optional
`spirv-tools` Cargo feature; requesting it then falls back with a diagnostic.

The explicit HAL transformation hook is separate from read-only observers. It
receives the actual Naga output after wgpu resolves pipeline overrides, retains
the intended function controls, and runs before Vulkan shader-module creation.
Existing shader capture sees the final input actually passed to the driver.

`spirv-tools-0.13.3-safe-v1` runs this fixed sequence, not a generic preset:

1. FreezeSpecConstantValue, FoldSpecConstantOpAndComposite.
2. CombineAccessChains, LocalAccessChainConvert.
3. LocalSingleBlockLoadStoreElim, LocalSingleStoreElim.
4. DeadBranchElim, BlockMerge.
5. EliminateDeadFunctions, EliminateDeadConstant, CompactIds.

It preserves bindings and validates input and output. There is no inlining,
unrolling, floating algebra/precision relaxation, interface-removal pass or
bounds-check-removal pass. An actual processing/validation error retains the
original Naga module and reports the fallback. Driver errors retain their normal
handling; a lost device is not hidden by attempting more submissions.

Processed cache files include the pinned tool version and recipe revision.
Foreground/background/worker/policy variants retain their separate identities.
Debug `Compiler input` JSON records applied/fallback status, input/output hashes,
word/function/call counts, no-inline counts and processing duration. Processing
time is separate from driver creation and GPU timestamp stages. The startup
report and benchmark metadata retain these records and the selected mode.
Admission timing ends when a compiler slot is acquired, so long driver calls
are no longer mislabeled as time spent waiting for admission.

## Local correctness and size evidence

436 native tests passed, including an actual SPIR-V processing fixture that
retains descriptor bindings, calls, floating arithmetic, no-inline function
controls and dynamic array bounds. A malformed module is rejected recoverably.

Two fresh Vulkan processes generated matching SHA-256 fingerprints for all four
samples: Overworld and Nether, each with a 32-by-32 chunk interior plus border
and a second negative-coordinate case. The expanded-height sample is 448 blocks.
Together they compare 596,480 columns plus cave/biome masks. Materials are
explicitly disabled in these fixtures because of the baseline portability limit.
All seven modules in the processed run applied the recipe successfully.

Representative interpreter inputs changed from 91,089 to 88,633 words (world)
and 91,848 to 88,923 words (cave). Each retained all three no-inline functions.
Processing took approximately 101–122 ms per interpreter on this host. These
modest size reductions do not establish faster NVIDIA compilation or execution.
Process times include compilation and hashing, with uncontrolled driver caches.

Matched full-material controls fail in both `naga` and `tools` at the existing
MoltenVK `material_prefix_blocks` creation limit, reporting virtual-device loss
before a terrain submission. The recipe does not resolve this baseline limit.
The processed control applied all 12 modules before that failure. Full material
coverage uses Metal; Windows Vulkan remains a required user retest.

A further forced full-terrain specialization comparison hit the same 240-second
test limit in both input modes, inside specialized `cave_nodes` driver creation.
Neither produced a sample before the bounded test stopped its process. Full
specialized Vulkan output is therefore unverified locally; this is not reported
as a successful comparison or as a processing regression. Production generation
has no corresponding timeout, and the established default remains unchanged.

After integration of the concurrent hardware-selection/CPU-fallback work,
438 native tests passed. The Vulkan processed route matched the original four
fingerprints again with parallel compilation enabled (all five newly created
modules processed). Metal produced matching full-material fingerprints with
both mode settings and processed zero SPIR-V modules, as expected. Five report
tests, the normal configuration check, and a build check excluding the optional
tools feature passed.

The Windows x64 release cross-build also passed with static CRT. Inspection of
the actual 40,911,360-byte DLL found only system imports, no compiler/CRT DLL
dependency, and all 45 native exports, including the three readiness snapshots.
This is native build/link evidence, not Windows runtime evidence. Final packaging
and matched Metal performance checks remain part of cross-cycle delivery.

## DXC-to-SPIR-V investigation

The exact static DXC package used by the Windows library was invoked through
its C API, without creating a GPU device:

```sh
cargo run --manifest-path scripts/dxc-spirv-probe/Cargo.toml --locked
```

The same minimal compute shader produced a valid 2,848-byte DXIL object. Adding
`-spirv -fspv-target-env=vulkan1.1` produced no object and returned:

> SPIR-V CodeGen not available. Please recompile with -DENABLE_SPIRV_CODEGEN=ON.

This is a concrete blocker to reusing the currently bundled compiler for the
alternate route. A different pinned compiler build with that backend enabled,
plus complete HLSL/Vulkan binding and push-constant mapping, would be required.
An unsupported `dxc` runtime mode is deliberately not advertised. The pinned,
locked capability probe remains reproducible source and is not packaged in the
JAR or added to release assets. It only probes a compiler and never opens a GPU.
