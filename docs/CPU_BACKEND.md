# Native CPU backend

Implementation is in progress. Native engine construction now selects CPU when
no permitted hardware adapter/device can initialize or the requested API set is empty. GPU handles and the worker are now isolated in `compute::GpuResources`;
engine-owned definitions, caches and lifecycle can exist without graphics resources.
Loader configuration and headless Java selection integration remain pending. The goal includes all operations below, all three
dimensions, and every existing supported registry program and adapter capability.

| Operation boundary | Current source | Shared input/output | CPU implementation and proof |
| --- | --- | --- | --- |
| Registry programs | program.wgsl, program.rs | Loaded instructions, noise parameters, spline points, context → six roots | cpu/program.rs: prepared reachable instructions, constants, per-job scratch, cached interpolation vertices; exhaustive opcode fixtures |
| Noise primitives | simplex.wgsl, noise3.wgsl | XYZ, seed, coefficients → scalar | cpu/noise.rs: wrapping integer hashes, Perlin/simplex/FBM; primitive fixtures; hardware comparison pending |
| Terrain/climate/biomes | simplex.wgsl, climate.wgsl, gpu.rs | Saved world profile and requests → columns, biomes | Shared climate index, registered fields and coherent column sampler implemented; CPU engine dispatch connected; fallback/platform acceptance pending |
| Interpolation and sparse queries | program/interpolation.rs, queries.rs | Global coordinates, field/program → density/height/biome | CPU evaluator and field sampler cache global interpolation/climate vertices and surface heights; shared column-program reuse analysis; CPU engine query dispatch connected |
| Surfaces/materials | materials.wgsl, program.wgsl | Density, material context/palettes → blocks/runs | All surface predicate opcodes implemented; complete material column assembly implemented; broader acceptance pending |
| Caves/aquifers | caves.wgsl, aquifers.wgsl, geology.rs | Registered profiles, fields → masks/materials | Native cave/carver and local/global aquifer fields implemented; complete mask/material assembly implemented; broader acceptance pending |
| Ores | geology.rs, geology/raster_gpu.rs | Loaded ore recipes → placement masks | CPU planning reused when graphics resources are absent; additional end-to-end coverage pending |
| Counts/provider noise | decoration/counts/gpu.rs, provider_noise.wgsl | Count rules/registered Perlin stacks, sparse points → values | Native count and Perlin-stack kernels implemented; CPU batch dispatch connected; numerical hardware comparison covers loaded permutations and far coordinates |
| Vines | decoration/vine_gpu.rs | Loaded recipe, substrate queries → writes | CPU mode selects the existing scalar replay without compiling or pausing for GPU vine programs |
| Lighting | lighting.rs, lighting.wgsl | Opacity/emission/face rules → section light | Queue-based CPU propagation uses the same opacity/emission/occlusion data and section format; reuses constant-light proofs; hardware equality checks cover integer channels and crop/halo behavior |
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

## Sparse sampling numerical comparison

The CPU count kernel consumes the same BIOME_INFO_NOISE permutation and count
rules. Provider kernels consume the same registered Perlin layers, offsets,
frequencies, weights and optional rounded slow-coordinate scale. CPU provider
coordinates use f64 rather than emulating shader integer limbs.

The bounded Metal comparison exercises three loaded permutations, near and
world-border coordinates, and both count-rule kinds. In 768 nearby count queries
there was one difference of one at a rounding boundary. In 768 world-border
queries the largest individual difference was 31 (a ratio-120 noise count), while
total counts differed by at most 1.47% per permutation. A separate phase probe
matched the hardware sample by shifting its divided f32 X by one representable
step; this is meaningful near ±30 million blocks. The test bounds each result by
the loaded rule sampled across a two-step numerical coordinate interval, plus
one final ceil step where relevant; it does not accept arbitrary count drift.
The maximum absolute error across 1,536 provider comparisons was 0.00000514.
These are kernel comparisons, not complete CPU terrain acceptance or benchmarks.

## CPU lighting

The CPU propagator uses a bounded tile, a monotonic frontier queue, the loaded
material emission/opacity and the exact registered face-pair occlusion table.
Its outputs use the existing voxel or section-packed light representation. It
also consumes the same conservative constant-light proofs as GPU packing: a
fully proven tile requires no dense light arrays or propagation. CPU region
lighting retains the existing outer-ring boundary policy and lifecycle.

Six bounded Metal comparisons matched every integer light byte exactly, across
sky/no-sky, partial-face rules and voxel/section outputs. Unit checks cover
attenuation, invalid material IDs, section packing, and an empty 32x32 region
using only constant descriptors with no graphics resources. No regions or client
instances were created for these checks.

## Shared climate and registered fields

CPU lookup and GPU table encoding now use the same climate-tree builder, including
registry-order ties, depth intervals, shore projection and unplaced biome niches.
CPU samplers evaluate registered climate, density, explicit heights and surface
searches from the same programs. Climate and density caches align to global grids;
job/request origins do not change their values. Final-field height searches retain
the existing crossing calculation; composed graphs evaluate actual block positions.
The complete backend will add interval acceleration after profiling.

Property checks compare indexed queries to linear targets across weights, depth,
flags and ties. Actual vanilla catalog samples for Overworld and Nether are finite
and repeatable at negative, ordinary and far coordinates, independently of request
origin. These checks initialize no engine or GPU, create no world files, and do not
claim complete CPU terrain/material generation yet.

## CPU terrain columns

The column sampler now consumes the same loaded biome sites, climate targets,
registered height fields, lake recipes, temperatures, surface programs and packed
column format. Site and lake caches reuse immutable-coordinate work. Fractional
lake-bank probes retain their coordinates through registered height sampling.

Focused checks cover actual vanilla Overworld/Nether catalogs, legacy profiles,
negative and far coordinates, independent request origins, and synthetic loaded
lake barriers, lava, snow and terracotta flags. A small real Metal comparison
matched all 32 sampled column heights, biome IDs and representative materials
exactly. This fixture is not a claim of general CPU/GPU bit equality. Complete
cavity/aquifer and layered material assembly, dispatch and fallback remain pending.

## CPU cavity and fluid fields

The CPU cave sampler now uses the loaded cave noises, density programs, biome
selection, tunnel/room/ravine settings, surface entrances and generation bounds.
Its globally aligned four-block lattice and bounded job-local caches preserve
request-order independence. The carver recipe-to-wire parameter mapping is shared
with GPU uploads. Registered aquifers use the same surface/flood/spread/lava/barrier
programs, seeded centers, fluid palettes and pressure decisions; global-fluid mode
remains available when local aquifers are disabled.

Forty-eight CPU-related checks pass, including actual vanilla/legacy cave fields
and local/global aquifer samples. A direct Metal comparison exercises 256 loaded
carver fixtures. Maximum sampled absolute errors were 0.00000138 for tunnels,
0.814 for room distance and 0.0000153 for ravines. The room difference occurs near
±30 million blocks. That comparison propagates a coordinate-spacing interval for
(cell+jitter)*size through squared normalized distance, while near-coordinate and
other kernel comparisons retain tight numerical tolerances. These are field
checks; complete voxel/material masks, engine dispatch and production fallback
are still pending and must be validated before enabling CPU selection.

## Complete terrain/material dispatch

A common compute result now carries columns, masks and timing data without device
handles. CPU dispatch supplies dense columns, surface/height/underground probes,
quart biomes, cavity/exposure planes and complete downward material runs. Material
assembly reuses per-tile voxel/depth buffers and the existing End-compatible run
layout. World-owned prepared programs retire with their profiles. CPU work has its
own opt-in preparation/column/mask/query counters, without fabricating GPU times.

Fifty CPU-related checks pass. Overlapping one- and four-chunk cores agree in
material, cavity and biome data for Overworld and Nether. A bounded Metal comparison
matched all 163,840 sampled material voxels, cavity bits and biome samples exactly
near the origin. Four complete CPU chunk calls also reached features/NBT status:
Overworld, Nether, central End and outer End. These checks constructed engines
without graphics resources, kept outputs in memory and opened no client.

This establishes complete native terrain dispatch, not final delivery or speed.
Native automatic selection is described below. Configuration/CLI integration,
complete-operation coverage fixtures, measured
optimization, platform/loader acceptance and release verification remain pending.
The first complete chunk check exposed substantial Nether work beyond raw terrain;
that must be profiled and optimized before performance acceptance.

## Native backend selection

An empty API set or `RETINA_GPU_BACKEND=cpu` selects CPU before a graphics worker
or instance is created. With permitted APIs, Retina enumerates actual adapters,
tries hardware devices in performance order and retains a successful device for
the session. Integrated/virtual GPUs remain candidates. Software adapters select
the native CPU path. A failed hardware device allows another enumerated hardware
candidate to be tried; no permitted API is re-enabled.

Typed initialization errors distinguish adapter/device unavailability from
configuration and implementation failures. Shader initialization panics keep their
error details and never trigger successful CPU fallback. Runtime device loss still
reports failure without changing the backend halfway through generation.

Four isolated native process scenarios pass: empty API set, explicit CPU override,
actual unavailable permitted API, and invalid override rejection. Debug output
confirms the first two create no graphics instance. The unavailable-API case really
attempts adapter initialization. This is not yet proof on a GPU-free machine; that
remains a required platform/CI acceptance check. The normal Metal terrain/material
and world-retirement regression checks also pass. Loader all-APIs-disabled
activation and Java CPU override support are the next integration pass.
