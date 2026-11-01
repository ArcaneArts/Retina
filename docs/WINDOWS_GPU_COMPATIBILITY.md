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
