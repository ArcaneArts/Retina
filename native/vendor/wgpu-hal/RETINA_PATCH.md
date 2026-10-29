# Retina diagnostic patch

This is the published `wgpu-hal` **30.0.1** crate, pinned through
`native/Cargo.toml`'s `[patch.crates-io]`. Original crates.io archive SHA-256:
`b6b7fb58561a792bc237628ba0792e332de418fefe145f13b5ed8201e6d52f58`.
Original license files are retained. Cargo registry bookkeeping and the upstream
standalone Cargo.lock are omitted; Retina uses its own locked dependency graph.
Git attributes preserve the snapshot's original bytes, including CRLF in
`src/vulkan/descriptor.rs`, while retaining ordinary trailing-space checks.

Only these upstream source files are changed:

- `src/dx12/mod.rs`: export diagnostics; retain serialized root signature bytes
  when the observer is enabled before layout creation.
- `src/dx12/device.rs`: observe root signature, compiled shader bytes and driver
  result around the existing compute-pipeline path.
- `src/dx12/shader_compilation.rs`: observe exact HLSL and arguments immediately
  before the existing DXC call; expose isolated compilation through the same
  bundled DXC factory.
- `src/dx12/diagnostics.rs` (new): one-time synchronous observer and isolated
  compiler/driver replay entrypoints. Observer panics are caught. No inputs are
  mutable and the production compiler arguments, driver descriptor, and shader
  generation remain unchanged.

All Metal, Vulkan, Naga translation and GPU execution code is unmodified.
Retina owns file persistence in `native/src/startup/capture.rs`; the backend has
no filesystem policy. When capture is disabled, root-signature copies and
argument conversion are skipped. Removing this patch requires removing the
capture/replay integration before returning Cargo to the registry package.

The replay driver helper creates and releases a root signature and compute
pipeline. It submits no GPU work. DXC optimization changes are restricted to
explicit standalone replay modes, never applied during normal generation.
