# Native CPU backend

Retina prefers an available, permitted hardware GPU. It selects its native CPU
backend when actual adapter/device initialization finds no usable hardware, or
when `allow_directx`, `allow_metal` and `allow_vulkan` are all false. An integrated
GPU is a hardware candidate. A software graphics adapter is not required for CPU
execution.

Disabling graphics APIs keeps Retina generation active. Supported world types
retain their Retina adapters in all three dimensions. Existing unsupported
adapter/generator capabilities keep their existing handling, including Bukkit
limitations. CPU execution does not expand those capabilities.

The all-disabled path creates no wgpu instance, device, queue or graphics worker
and compiles no shaders. GPU unavailability is distinct from invalid data,
configuration, missing symbols and implementation failures, which retain their
errors. Backend selection stays fixed for an engine session. Device-loss recovery
mid-region is not implemented.

## Controls

The existing TOML and YAML configuration keys work unchanged:

```toml
allow_directx = false
allow_metal = false
allow_vulkan = false
```

Headless interfaces share the same native selection:

```sh
RETINA_GPU_BACKEND=cpu java -jar retina-26.3-1.1.N.jar generate --world /path/to/world
java -Dretina.gpuBackend=cpu -jar retina-26.3-1.1.N.jar generate --world /path/to/world
RETINA_GPU_BACKEND=cpu retina generate --definition /path/to/world/retina.dat \
  --dimension minecraft:overworld --region-min=0,0 --region-max=0,0 --output /path/to/output
```

Use the CLI's help for required generation bounds and catalog options. `auto`
attempts permitted graphics APIs; explicit GPU overrides cannot re-enable APIs
excluded by configuration. Startup reports the selected backend and reason.

The standalone runner also supports `init --catalog FILE --world DIRECTORY
--seed LONG [--variant normal]` and `inspect --world DIRECTORY` (or
`--definition FILE`). These commands reuse the same native catalog materializer
and reader without initializing a generation engine. Initialization never replaces
an existing definition, including an invalid one.

`RETINA_REGION_THREADS` controls the combined native CPU worker budget, clamped
to 2–16. Its default is available parallelism within that range. One quarter, at
least one worker, handles dependency/assembly work; the rest compute terrain.
The two-worker minimum leaves a producer available while dependencies wait.
Startup reports the actual split. Regions and worlds share these global pools;
region concurrency does not allocate another full pool. Host/JVM threads are
outside this budget. GPU assembly retains its previous control behavior.

## Shared data and execution

CPU and GPU consume the same authoritative `retina.dat`, materialized catalogs,
registry instructions, noise parameters, palettes, recipes, structures and
settings. Backend changes do not rewrite saved definitions or existing chunks.
Reusable packaged data remains `retina-catalog.dat`; materialization uses the
actual world seed/settings. Live registry compilation remains available.

| Operation | CPU implementation | Shared contract |
| --- | --- | --- |
| Registered programs and noise | `cpu/program.rs`, `cpu/noise.rs` | Wire opcode enum, loaded DAG/noise/spline data, context and six roots |
| Climate, biomes, density and queries | `cpu/fields.rs`, `cpu/columns.rs` | Common climate index, global interpolation grids, dimension bounds and column format |
| Surfaces, materials, caves and fluids | `cpu/materials.rs`, `cpu/caves.rs`, `cpu/aquifers.rs` | Registered rules, carver parameters, aquifer inputs, cavity/biome/run layout |
| Ores and structures | Existing Rust planners and geometry | Same recipes, seeded placement, support queries and metadata |
| Decoration counts/provider noise | `cpu/features.rs`, existing noise samplers | Same loaded permutations, count rules and provider stacks |
| Vines and other feature replay | Existing scalar Rust implementations | Same ordered recipes, substrate queries and output |
| Lighting | `lighting/cpu.rs` | Loaded opacity/emission/face rules, bounds and packing |
| End and sparse terrain | Existing `end/` algorithms | Same island/density/biome data, sparse proofs, features and structure metadata |
| Storage and lifetime | Existing region/definition machinery | Same MCA/NBT publication, preservation, ownership and retirement |

Programs preprocess reachability, constants, dependency lists and column reuse
once per loaded profile. Demand-driven execution skips unselected branches and
spline knots, using reusable frames and explicit stacks for deep DAGs. Sampling
jobs own bounded interpolation/field/cavity caches. Conservative density intervals
skip only proven decisions, retaining scalar evaluation when unknown. Tile workers
share immutable definitions/columns, reuse scratch and merge output canonically.
CPU execution interprets the generation program, not arbitrary WGSL.

## Numerical policy

CPU and GPU implement the same rules and supported features; floating-point
results need not be identical. Integer seed/random behavior, settings, dimension
bounds, valid metadata and storage remain required. CPU results must repeat across
worker counts, request order and reopen. Newly generated CPU/GPU terrain can differ
at a boundary when a world's backend changes; saved chunks remain preserved.

Focused Metal comparisons cover columns, complete material/cavity/biome tiles,
lighting, carvers, provider noise and feature counts. Near-origin material fixtures
matched 163,840 voxels exactly. This does not promise general bit equality. Provider
comparisons observed maximum absolute error 0.00000514. At world-border coordinates,
count/carver comparisons use explicit coordinate-spacing bounds; numerical changes
must be investigated rather than accepting arbitrary drift. Backend-specific
performance changes may remain separate, while generation behavior stays aligned.

## Contributor checks

`program/opcode.rs` is the common wire contract. CPU matching is exhaustive. A
source check enumerates the actual GPU switch, and synthetic fixtures execute all
47 current instructions, including non-folded reference scheduling. Rare branches,
material contexts, negative/far coordinates, deep graphs and interpolation have
explicit fixtures. Unknown operations produce an error.

When adding behavior, update shared definitions/compiler inputs first. Share Rust
algorithms and parameter encoding wherever practical. If CPU/GPU kernels differ,
add shared fixtures and an explicit numerical comparison. Update the CPU boundary
and GPU implementation together; an opcode or feature must not silently become
GPU-only. Run:

```sh
cargo test --manifest-path native/Cargo.toml --no-default-features --lib cpu::
cargo test --manifest-path native/Cargo.toml --no-default-features --lib program::opcode::tests
```

Hardware comparisons are separately ignored tests and run only on a suitable
hardware host. They do not require a client. Release checks execute CPU contracts
and the packaged native libraries; see [headless acceptance](CPU_ACCEPTANCE.md).

## Performance evidence

Optimized native CPU diagnostics on an Apple M4 Max reduced one complete lit
Nether region from 132.055 to 9.950 seconds while retaining all 1,024 decompressed
chunk records. Process CPU time fell from 211.032 to 73.674 seconds. The final
16-worker split was 4 dependency/assembly plus 12 terrain workers, averaging 7.403
scheduled cores. These are bounded diagnostics, not production percentiles or a
claim of GPU-like throughput. Final packaged all-dimension measurements and
platform evidence are retained with the release acceptance results.

The measured steps and excluded experiments are recorded in
[density certificates](reports/cpu-density-certificates-2026-10-31.md),
[demand scheduling](reports/cpu-demand-evaluation-2026-10-31.md),
[sampling caches](reports/cpu-sampling-caches-2026-10-31.md),
[tile parallelism](reports/cpu-tile-parallelism-2026-10-31.md), and
[worker budgets](reports/cpu-worker-budget-2026-10-31.md). A subsequent
[column preparation pass](reports/cpu-column-parallelism-2026-10-31.md) reduced the
complete Overworld diagnostic from 42.753 to 10.175 seconds with identical NBT.
