# Conservative compilation follow-up

The compilation audit found the main reuse mechanisms already present:

- Base fallback pipelines use `OnceLock`; asking for one again does not recreate it.
- A generated module is created once per required shader variant, then shared
  by its entry points. Different buffer layouts/dispatch wrappers remain separate.
- The specialization cache checks exact generated-source equality and execution
  options, not just a weak source hash.
- Shared interpreter and material dispatch follow real immediate-size capability;
  backend names do not select different terrain algorithms.

No additional cross-module translation cache, shader precision change, driver
optimization default change, or compilation-thread increase is justified by the
available local evidence. Such changes need representative Windows captures and
per-stage timing, with their complete layout/options identity preserved.

One concrete test-path mismatch is corrected: the optional `vulkan-portability`
build feature enables Vulkan-on-Metal, and the Apple compile guards now forward
the same scoped interpreter compilation policy/feedback used on Windows. Normal
builds retain their existing features. Selecting Vulkan is explicit, and the
smoke test asserts the adapter's actual backend before validating output.

The local portability runtime is Homebrew MoltenVK 1.4.2 with Vulkan loader
1.4.363.0. It translates Vulkan to Metal and cannot establish NVIDIA Windows
compile speed or generated-code performance. The developer feature is not used
for regular release libraries.

## Actual portability smoke result

A fresh process selected `Vulkan: Apple M4 Max` and passed four deterministic
256-column GPU queries, including negative coordinates. A second process using
the same diagnostic-only cache passed the same queries.

| Observation | First process | Second process |
| --- | ---: | ---: |
| Process wall time | 4.972 s | 0.197 s |
| Driver pipeline creation (`main`) | 3881.303 ms | 0.679 ms |
| Terrain cache input | Cold | Loaded, 96,433 bytes |
| Feedback supported / valid | true / true | true / true |
| Driver-reported application-cache hit | no | no |

The second run is faster, but the reported application-cache-hit flag remains
false. We do not attribute that speedup to Retina's file alone: Metal/driver
internal caches may contribute. This validates the feedback and persistence path,
not an NVIDIA performance prediction. 398 native tests also pass in the optional
portability build (104 environment-dependent tests ignored).

Reproduce on this Mac with the separate diagnostic cache:

```sh
RETINA_GPU_BACKEND=vulkan \
RETINA_PIPELINE_CACHE_DIR=/tmp/retina-vulkan-smoke-cache \
VK_DRIVER_FILES=/opt/homebrew/etc/vulkan/icd.d/MoltenVK_icd.json \
DYLD_LIBRARY_PATH=/opt/homebrew/lib \
cargo test --manifest-path native/Cargo.toml --features vulkan-portability \
  vulkan_portability_smoke -- --ignored --nocapture --test-threads=1
```

Run the command twice to compare first/repeated process startup. A Vulkan loader
and portability driver must actually be installed; the test makes the normal
adapter request and reports a real initialization error if unavailable. It does
not silently fall back to Metal.
