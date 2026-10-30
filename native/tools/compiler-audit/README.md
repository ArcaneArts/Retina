# Host shader compiler audit

Compile Retina's exact test-dumped WGSL with Naga 30.0.1 and the same bundled
DXC revision used by the Windows native library. This works on macOS without a
Windows GPU. It measures DXC, **not** `CreateComputePipelineState` or Vulkan's
driver compiler. HLSL binding numbers are synthetic; no GPU pipeline is created.

Generate production shader sources with a real vanilla `retina.dat`:

```sh
RETINA_COMPILE_DUMP=/tmp/retina-shaders \
RETINA_STARTUP_DEFINITION=/path/to/retina.dat \
cargo test --manifest-path native/Cargo.toml --release --lib \
  common_policy_shared_dispatch_matches_independent_generation \
  -- --ignored --nocapture --test-threads=1
```

Compile one dumped entry:

```sh
cargo run --manifest-path native/tools/compiler-audit/Cargo.toml --locked --release -- \
  /tmp/retina-shaders/0000-retina_world_dispatch.wgsl retina_world_dispatch
spirv-val --target-env vulkan1.1 /tmp/retina-shaders/0000-retina_world_dispatch.spv
```

The tool writes `.hlsl`, `.spv`, and `.dxil` next to its input and reports
compilation duration, artifact sizes, and retained function counts. It uses the
production HLSL/SPIR-V boundary transforms and DXC validates the resulting DXIL.
The optional `spirv-val` command uses Khronos SPIRV-Tools. Nothing is executed on
a GPU by this audit. Captures may contain world-definition data; keep them local.

Run the backend transformation tests with:

```sh
cargo test --manifest-path native/tools/compiler-audit/Cargo.toml --locked --release
```

The host tool has an independent lockfile and is not included in release JARs.
