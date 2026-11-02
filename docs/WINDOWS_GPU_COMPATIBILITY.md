# Windows GPU compatibility

The supplied 1.1.481 RX 9070 XT logs expose three separate failures:

- Vulkan completed its first terrain dispatch, then a host `bytemuck::cast_slice`
  panicked with `TargetAlignmentGreaterAndInputNotAligned`. This is a readback
  decoding failure, not evidence of slow device execution.
- DX12 lost its device during `CreateComputePipelineState` for the combined
  `retina_world_dispatch`, before any queue submission. Subsequent invalid-buffer
  errors were consequences of that device loss.
- Background specialized compilation reported `missing material count evaluator
  for shared dispatch`. The released Windows native manifest confirms CRLF WGSL
  input, while the transformation searched for an LF-only function declaration.

Mapped terrain columns, cave/material words, ore masks and GPU timestamps now use
one checked decoder. It borrows suitably aligned data and uses unaligned POD
copies otherwise. Truncated records return an error. GPU buffers, layouts and
generation arithmetic are unchanged. This follows the byte-slice interface of
[wgpu mapped views](https://docs.rs/wgpu/30.0.1/wgpu/struct.BufferView.html).

Material dispatch normalizes CRLF before transformation and validates both LF and
CRLF source through the actual Naga parser/validator. Native source files also
have an explicit LF checkout policy; the pinned upstream vendor snapshot retains
its existing binary-preservation rule.

The default DX12 policy uses the existing independent interpreter and material
entrypoints. This avoids the combined shader implicated in the driver reset while
continuing GPU generation with the same stage bodies, buffers and program data.
It selects shader organization after actual adapter enumeration, without GPU-name
lists, availability guesses or a CPU detour. Metal/Vulkan defaults remain shared
when the actual immediate capability is available. Existing explicit
`RETINA_INTERPRETER_DISPATCH=1` / `RETINA_MATERIAL_DISPATCH=1` diagnostics can still
exercise sharing; they are not the DX12 compatibility default.

Regressions cover intentionally unaligned columns/words/timestamps, truncated
payloads, Windows line endings and backend policy. The existing hardware parity
test compares shared execution with the actual default DX12 compiler policy on
the available GPU, including interpreter/specialized modes and both dimensions.
This establishes generation parity, not RX 9070 XT driver validation. Confirmation
of that driver-specific mitigation still requires rerunning on the affected card.

## RX 9070 XT capture from 1.1.493

The follow-up capture confirms the independent interpreter `main` still fails in
`CreateComputePipelineState`, in 36 ms, before any terrain dispatch. DXC finished
successfully first. The background specialized `main` encounters the same device
after it has been removed. Switching dispatcher organization alone did not fix it.

The captured interpreter DXIL retains two `retina_noinline_*` functions and nine
calls with scalar and output-pointer arguments. The two successful utility
pipelines have no such retained functions. Retina had injected `[noinline]` into
Naga's HLSL; AMD DX12 now passes the original HLSL to DXC, allowing normal inlining.
This removes the retained-call representation from both captured failing shaders
without changing their calculations, workgroups, resources or root signatures.
The selection uses the actual initialized adapter's PCI vendor ID. NVIDIA/Intel
DX12, Metal and SPIR-V retain their existing function-boundary policies. In
particular, earlier NVIDIA evidence found severe compiler expansion without
retained calls, so the AMD compatibility change does not remove that optimization.

Offline comparisons used both Retina's exact bundled mach-dxcompiler-rs
0.1.7+2026.10.26-141dd7f.1 on ARM64 macOS and Microsoft's DXC 1.9.2609 on Linux
with the captured `cs_6_7`, HLSL 2018 and strictness arguments. Removing only
`[noinline]` produced valid DXIL
with one entry function and zero retained Retina calls for both the interpreter
and specialized capture. This is compiler validation; confirmation of the AMD
driver fix requires a retest on the RX 9070 XT. DX12 failure reports now include
the actual `GetDeviceRemovedReason` result in both the log and capture manifest.

On the local M4 Max, bundled DXC compiled the interpreter in 1.82 s with retained
calls and 7.73 s with normal inlining; the specialized capture took 6.61 s and
32.52 s respectively. These are single compiler observations, not GPU-generation
timings. Normal inlining produced 696,708-byte and 1,229,376-byte DXIL objects.

Native release fingerprints now include the complete vendored backend tree.
Previously those files were omitted, allowing an exact output-cache hit to reuse
a native library after a vendor-only compiler fix. A regression verifies changed,
added and removed backend files invalidate the cache and rejects stale native
output, while packaging version changes preserve native reuse.
