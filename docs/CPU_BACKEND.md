# Native CPU backend

Implementation is in progress. CPU generation is not yet selected by production
entry points. GPU handles and the worker are now isolated in `compute::GpuResources`;
engine-owned definitions, caches and lifecycle can exist without graphics resources.
Existing GPU behavior remains active until complete CPU workloads
can be connected and verified. The goal includes all operations below, all three
dimensions, and every existing supported registry program and adapter capability.

| Operation boundary | Current source | Shared input/output | CPU implementation and proof |
| --- | --- | --- | --- |
| Registry programs | program.wgsl, program.rs | Loaded instructions, noise parameters, spline points, context → six roots | cpu/program.rs: prepared reachable instructions, constants, per-job scratch, cached interpolation vertices; exhaustive opcode fixtures |
| Noise primitives | simplex.wgsl, noise3.wgsl | XYZ, seed, coefficients → scalar | cpu/noise.rs: wrapping integer hashes, Perlin/simplex/FBM; primitive fixtures; hardware comparison pending |
| Terrain/climate/biomes | simplex.wgsl, climate.wgsl, gpu.rs | Saved world profile and requests → columns, biomes | Pending CPU executor using the same profile and program evaluator |
| Interpolation and sparse queries | program/interpolation.rs, queries.rs | Global coordinates, field/program → density/height/biome | CPU evaluator caches globally aligned interpolation corners; shared column-program reuse analysis; engine queries pending |
| Surfaces/materials | materials.wgsl, program.wgsl | Density, material context/palettes → blocks/runs | All surface predicate opcodes implemented; material column assembly pending |
| Caves/aquifers | caves.wgsl, aquifers.wgsl, geology.rs | Registered profiles, fields → masks/materials | Existing CPU carver/planning algorithms reused; shader calculations pending |
| Ores | geology.rs, geology/raster_gpu.rs | Loaded ore recipes → placement masks | CPU planning exists; dispatch integration and coverage pending |
| Counts/provider noise | decoration/counts/gpu.rs, provider_noise.wgsl | Count rules/registered Perlin stacks, sparse points → values | CPU kernels pending; retain existing CPU placement machinery |
| Vines | decoration/vine_gpu.rs | Loaded recipe, substrate queries → writes | CPU replay exists for non-GPU batches; route CPU execution without a device |
| Lighting | lighting.rs, lighting.wgsl | Opacity/emission/face rules → section light | CPU propagation pending; retain encoding and palettes |
| End | end/, decoration/end_features/ | Saved island/density/feature settings → substrate/metadata | Existing CPU terrain/features reused; remove mandatory engine GPU initialization |
| Storage/lifecycle | region/, world_definition/ | Same authoritative retina.dat → MCA/NBT | Reuse existing Rust publication, compression, metadata and ownership |

## Shared contracts

`program/opcode.rs` names the actual wire opcodes. Registry validation accepts
only these operations. CPU dispatch matches the enum exhaustively, and a test
reads the actual GPU interpreter switch to require matching coverage. Adding an
opcode requires updating the common contract and implementing its CPU branch.
This is an executable test of source coverage, not a runtime availability gate.

Both executors consume RegistryProgram, its noise/spline/interpolation data, and
the existing material/feature palettes. CPU preparation uses the same dependency
and horizontal-column analysis as GPU specialization. Job-local evaluators own
scratch frames and bounded caches; no sampled data changes saved definitions.

Numerical CPU/GPU equality is not required, but shared rules, integer seed/hash
behavior, valid metadata and repeatability within CPU execution are required.
Hardware numerical comparisons, complete generation coverage, automatic fallback,
platform integration and performance acceptance remain outstanding.
