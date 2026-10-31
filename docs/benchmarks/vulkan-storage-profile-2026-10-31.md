# Avoid the immutable noise-profile aggregate copy

The `biome_sites` shader loaded a complete NoiseProfile from immutable storage,
including its 32-float modifier array. Other samplers already read individual
storage fields. Use that existing pattern here without changing arithmetic,
loop order, buffer layout, seeds, or bounds behavior.

The unchanged Vulkan control on MoltenVK 1.4.2 failed with Metal error
`no viable conversion from device float[32] to spvUnsafeArray<float,32>` in this
load. Disabling Metal argument buffers reproduced the same failure. The modified
shader creates successfully on the same Vulkan adapter, and repeated access
returns the same retained pipeline.

Validation: 411 native tests passed (108 opt-in tests ignored); the real Vulkan
biome pipeline creation test passed; release native build passed. Two complete
Metal regions matched all 2,048 earlier chunk NBT records from before this change.
This proves the reproduced compatibility fix and sampled terrain equality, not
a Windows speedup. No percentage performance improvement is claimed.

Broader MoltenVK coverage remains limited: material-prefix and ore pipelines
with initialized workgroup arrays fail in generated Metal with an undefined
`gl_WorkGroupSize`. This is distinct from the fixed aggregate-copy error and
is present with the normal interpreter policy. Preserve those controls when
evaluating compiler-input processing later. Procedural smoke, cache recovery,
and the biome pipeline can run on Vulkan; full material/region parity remains
covered on native Metal pending a supported portability translation.
