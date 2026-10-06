# Six-workstream generation audit

This audit maps the requested work to the implementation and validation. Retina
continues to approximate loaded generation data using GPU → Rust → chunk/MCA.
It does not reproduce Minecraft's seed RNG or every feature class. Unsupported
recipes and graph/rule approximations remain explicit export reports.

## Implemented scope

| Requested workstream | Result | Primary evidence |
| --- | --- | --- |
| Complete active Overworld biomes and indexed lookup | Registry-backed presets import the complete referenced pool; surface targets remove depth-only duplicates while preserving ordinals/ties; underground lookup retains depth. Separate resident stackless interval trees handle both. Dimension boundaries and imported custom biomes remain supported. | `905b5ad`; [biome coverage and indexed comparisons](GENERATION_UPGRADE.md#registered-biome-coverage-and-climate-index) |
| Layered GPU surfaces | Actual floor/ceiling depths, secondary depth, fluids and registered rules produce compact vertical material runs, including sediments, cliffs and terracotta. Rust consumes those runs in both assembly paths. | `23cdf3d`; [material format, oracle and transfers](GPU_MATERIAL_LAYERS.md); [complete halo substrate](GPU_DECORATION_SUBSTRATE.md) |
| Local GPU aquifers and loaded lakes | Registered aquifer channels, local center levels and pressure/barrier fields classify cavities as air/water/lava/solid on the GPU. Surface lake budgets and types come from loaded recipes. Region-local masks reuse resident fields. | `3a66ee2`; [aquifer equations, Minecraft oracle and costs](GPU_AQUIFERS.md) |
| Broader features/providers/placements | Ordered native adapters cover bamboo, columns, aquatic plants, fallen trees, giant mushrooms, vegetation patches, simple blocks, attachment growth, sediment disks and composites. Registered counts, heights, scans, cuboids, floor layers, filters, nullable/current-state providers and selector draws participate in replay. Spatial count/provider noise executes in sparse GPU batches. | [block features](REGISTERED_BLOCK_FEATURES.md), [fallen trees](REGISTERED_FALLEN_TREES.md), [patches](REGISTERED_VEGETATION_PATCHES.md), [disks/composites](REGISTERED_DISKS_COMPOSITES.md), [count fields](GPU_FEATURE_COUNTS.md), [provider noise](GPU_PROVIDER_NOISE.md) |
| Specialized GPU graphs and correct operator scopes | Profile-source fingerprints cache folded/pruned specialized DAGs, horizontal fields and stage inventories. Compact GPU interpretation remains available during asynchronous compilation or real specialization failure. Registered coordinate slices and each interpolation operator retain their scopes. New presets save block-position composition; old saves retain legacy interpolation. | [compiler](GPU_PROGRAM_SPECIALIZATION.md), [coordinate scopes](GPU_COORDINATE_SCOPES.md), [field scopes](GPU_INTERPOLATION_SCOPES.md), [production composition/startup](PRODUCTION_DENSITY_COMPOSITION.md) |
| Measured Rust scan/allocation reductions | Cave dressing limits scans to actual relevant GPU biome ranges. Bulk ore masks avoid large candidate lists; local crown grids and coordinate lookups reduce planner work. Material assembly clears upper air in bulk. Direct palette packing removes the temporary mixed-section index traversal. Final palettes and heightmaps still read final blocks. | `c13436d`, `9c7e3e6`, `06af2e9`; [sparse planning](GPU_SPARSE_PLANNING.md), [palette A/B measurements](DIRECT_PALETTE_PACKING.md), [rejected heightmap alternative](HEIGHTMAP_SCAN_EXPERIMENT.md) |

The individual improvements were measured at their own revisions and profiles.
Their speedup percentages cannot be multiplied to claim a combined final gain.
The final configuration and actual-profile measurements are in
[production density composition](PRODUCTION_DENSITY_COMPOSITION.md).

## Requirements carried through

Both MCA and individual chunks share loaded profiles, material/substrate output,
feature replay and final metadata. DH surface generation consumes complete
temporary regions with exact base-material companions instead of reverting to
per-chunk GPU generation. Cache capacity remains 1,024 regions. Promotion writes
missing saved slots, preserves player edits and retires temporary companions.

The saved `density_composition` setting survives generator serialization and
datapack adaptation. Absent fields decode as false, preserving legacy worlds.
New MCA/chunk preset files explicitly select true. Existing generated chunks are
not rewritten. Native profiles also retain the absent-flag compatibility path.
The runtime diagnostic can disable composition without forcing legacy saves in.

Each request captures its GPU pipelines and layouts before submission, including
the deferred material emission pass. Completing shader compilation cannot change
a request midway through. Resident registry/noise/index inputs are reused;
interpolation atlases, bounds and aquifer fields remain GPU-only. Final material
runs and sparse feature/provider queries keep readback bounded by actual work.
Parallel replay retains loaded order and live canopy/substrate state.

F3 retains its twenty-region rolling average, derived chunks/s and amortized
chunk time, individually colored stage rows, compiler state, transfer counters,
and temporary-region/promotion counts. Device stages overlap host phases;
parallel worker totals are estimates rather than additive latency fractions.
No new device stage or timing ABI was needed for production composition selection.

Native compilation remains limited to two Cargo jobs and two release codegen
units. Builds/tests/benchmarks run at lowered priority and do not overlap each
other. Interactive desktop load remains uncontrolled in the reported measurements.

## Final validation

The final `build gpuTest materialTest aquiferTest regionTest previewTest
structureTest blockFeatureTest datapackTest` run passes 65 native unit tests and
90 QA pass events. It uses normal runtime defaults, with the interpolation
oracle's existing explicit diagnostic selection. Evidence:
`build/production-composition-validation.log`.

This includes actual Minecraft-reference comparisons for 6,680,576 material
voxels, 3,072,000 aquifer classifications, 10,240 composed-density columns and
483 snow-support states. The snow regression generates 256 bare regular-ice
columns and 256 snowy land columns, then matches 196,608 chunk/MCA blocks.
It directly covers the reported frozen-ocean defect, alongside the final village
grass-support and tree-crown fixes from earlier milestones.

The integration harnesses verify negative coordinates, overlapping and concurrent
requests, registry/preset loading, saved-mode serialization, biome distributions,
feature budgets, structure pieces/entities/loot, finalized palettes and heightmaps,
chunk/MCA parity, temporary caching, 1,024-region retirement, partial promotion,
save/read/promotion, saved edits and failure cleanup. Terralith import continues
to use Retina in both modes. Final Terralith reference and benchmark evidence is
listed in the production-selection report. The separate pack/reference run
passes 62 QA events, including 2,509,927 Terralith survival cases. Final warm and
fresh-entry startup comparisons preserve 348,160 complete chunk NBT records;
startup can still await required generic compilation and has mixed timing results.

Semantics-preserving milestones compare complete decompressed chunk NBT against
their retained libraries, not just terrain heights. Fidelity changes additionally
use Minecraft reference evaluators and loaded feature data. The final composition
selection matches the previously validated composed output, while its legacy
selection matches the retained ordinary output. Fresh-identity startup checks
exercise the GPU interpreter while specialization is pending.

Visual checks remain optional. Prior human checks confirmed blended terrain,
decorations, jungle crowns, ores/caves and DH promotion. This final audit does not
invent a new visual result or substitute visual feedback for the automated checks.

## Remaining approximations and measurement limits

- Loaded export logs still identify unsupported coral geometry, root systems,
  geodes, speleothem clusters, underwater magma, some mushroom/shelf survival,
  custom provider/predicate/decorator classes and enclosing feature geometry
  beyond the supported halo. Supported sequence/overlay wrappers do not silently
  drop an unsupported child. Ore and snow entries in decoration omission logs
  may belong to their separate geology/final-snow adapters.
- Aquifer center randomness uses GPU positional hashing; barrier fields use
  four-block interpolation, and registered carvers use a documented negative
  density proxy. Source fluids do not enqueue Minecraft's full selective fluid
  tick schedule. Lakes approximate registered basin behavior and counts rather
  than reproducing the exact ellipsoid/RNG implementation.
- Material preliminary surface is derived from final height/depth; old blended
  noise and some registered rules remain reported approximations. Support-shape
  tables use an empty block getter and cannot emulate arbitrary neighbor-dependent
  custom shapes. Noise/sample precision follows the current GPU backend.
- No cross-backend determinism or Minecraft seed equality is promised. Metal is
  measured; Vulkan/DX12 keep compatible existing selections without a claimed
  speedup. The numerical interval optimizations have GPU-oracle and boundary
  checks, rather than a formal proof for every possible modded graph/backend.
- Startup measurements distinguish new generic entry identities from warm
  specialized driver caches. Full empty-driver-cache desktop world startup,
  Minecraft loading/lighting/rendering and unrelated desktop work are outside
  the native measurements. Optional specialization proceeds asynchronously;
  the diagnostic forced mode intentionally waits for compilation.

These are disclosed limits of the requested approximator, not hidden fallback to
Minecraft generation. The next extensions can target the logged unsupported
recipes and discrete-backend measurements without reopening the six implemented
workstreams or treating a single worker timer as a whole-generator speedup.
