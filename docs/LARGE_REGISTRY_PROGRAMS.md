# Large datapack density programs

The Iris `overworld-26.3.zip` export exposed an obsolete 1,024-instruction limit
in the Java registry exporter. The native definition reader enforced the same
limit. This rejected valid expressions before GPU initialization, even though
the interpreter already reuses temporary values after their final read.

Graph length and live-value capacity are now separate. The exporter and native
reader retain the complete graph. Existing validation of roots, dependency order,
noise references, spline points and interpolation references remains in place.
The interpreter uses its actual live-register requirement to select scratch size:

| Simultaneously live values | Shader capacity |
| --- | --- |
| Up to 64 | 64, unchanged |
| 65–1,024 | 1,024, unchanged |
| More than 1,024 | Exact required capacity |

Large or deeply nested interpreters retain explicit function-call boundaries on
Metal, as the other backends already do for outlined functions. This is limited
to capacities above 1,024 or interpolation depths above two; ordinary Metal
profiles keep their existing compiler policy. Without retained calls, the test
pack caused Metal's compiler service to disconnect while compiling `lake_density`.

The asynchronous production queue now waits for the actual mapping result and
checks device health on every poll. A pending result after 30 seconds is no longer
classified as a GPU failure. Real device/poll errors, failed mapping callbacks and
disconnected callbacks still propagate; diagnostic synchronous waits stay bounded.

Direct-height programs now populate interpolation caches independently of the
terrain density lattice. For these programs, fields proven independent of Y use
one horizontal plane; unknown operations and vertical dependencies retain the
three-dimensional cache or direct evaluation. Density-search profiles keep
their existing layout. Direct-height specialization no longer emits horizontal
column-cache fallbacks for the density lattice it does not use.

Specialization emits large graphs or graphs with more than 32 nested conditions with the interpreter's eager dependency
semantics, sharing each node once instead of duplicating it into conditional
arms. This avoids exponential source expansion and excessive syntactic nesting.
Outlining also copies source spans once rather than rewriting the entire module
for every generated function.

The selected capacity is part of the existing interpreter pipeline cache key,
including graphs without interpolation fields. Specialized density bounds use
the same capacity. This avoids allocating a temporary per instruction and does
not enlarge the shaders used by existing small profiles. Device compilation and
allocation errors still propagate normally; this does not promise unlimited
GPU resources or identical performance for large packs.

The supplied pack exports a largest subgraph of 16,890 instructions and needs
1,080 live values, selecting a 1,080-value shader. Its Java-exported program passes
native validation. Synthetic regression tests cover a 4,096-instruction chain
using only three live values, a program crossing the old live-value capacity,
and WGSL validation for 1,080-value shaders with and without interpolation.
The actual pack also passes the automatic Java/native integration test: saved
world-definition reopen, a GPU column query, full 1,024-chunk region generation,
MCA promotion, and Minecraft block/biome palette readback across four chunks.
This test uses the pack's actual vertical bounds rather than vanilla's bounds.
After the cache dispatch fix, the full-region test completed in 7.4 seconds on
the local M4 Max with a warm shader cache. Cached and uncached GPU column output
also matched byte for byte at chunks (0,0) and (-3,-3).
The background specialization source remains large (about 127 MB for this pack).
The end-to-end test proves generation through the interpreter while specialization
runs; it does not assert completion or timing of every optional background
pipeline, nor has this pack been tested on Windows in this pass.
The user-supplied ZIP is not committed to the repository.

Reproduce the focused export with a local copy of the pack:

```sh
./gradlew largeRegistryProgramTest --no-watch-fs \
  -PtestDatapack=/path/to/overworld-26.3.zip \
  -PtestNoiseSettings=iris_export:overworld
RETINA_TEST_PROGRAM="$PWD/common/build/large-registry-program.json" \
  cargo test --manifest-path native/Cargo.toml --lib program:: \
  -- --include-ignored --nocapture
```

Without the Gradle properties, `largeRegistryProgramTest` runs the small synthetic
export regression without needing a datapack or GPU.

Run the complete saved-definition path (requires a built native library and GPU):

```sh
./gradlew datapackTest --no-watch-fs \
  -PtestDatapack=/path/to/overworld-26.3.zip \
  -PtestDatapackPhase=automatic -PtestDatapackDebug=true
```

The GPU cache parity test accepts the resulting saved definition through
`RETINA_LARGE_DEFINITION` and compares cached and uncached output at positive
and negative chunk coordinates:

```sh
RETINA_LARGE_DEFINITION="$PWD/build/terralith-definition-automatic/retina.dat" \
  cargo test --manifest-path native/Cargo.toml --lib \
  direct_height_interpolation_cache_matches_uncached_queries -- --ignored --nocapture
```
