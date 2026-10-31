# Bounded interpreter dispatcher groups

The established compact interpreter shares one world and one cave dispatcher.
`RETINA_INTERPRETER_GROUPS=2` adds a fixed alternative when shared interpretation
is enabled. The default `1` retains the existing source and cache identities.
`RETINA_INTERPRETER_DISPATCH=0` retains independent stage pipelines and takes
precedence over grouping. Wide independent interpreters remain independent.

The native region runner accepts `--interpreter-dispatch enabled|grouped|disabled`
and records both dispatch and group settings. Each run must start a fresh process
because compilation policy and cache identity are immutable during that process.

The two world families contain field producers (climate, height, density,
horizontal and interpolation nodes) and queries/consumers (surface and lakes).
The two cave families contain density/aquifers/queries and material counting/
emission. Empty families create no pipeline. Compatibility masks still omit
stages that use established base fallbacks. This bounds compact bundles to two
world and two cave pipelines, independent of the number of interpolation levels.

Stage bodies and workgroup sizes remain unchanged. Each family uses the existing
four-byte immediate layout with local selectors; each logical stage retains the
correct pipeline handle and selector. Material continuations retain those same
handles through emission. Prefix scans with 256 lanes remain separate. Source
validation, optional interpreter promotion, bounded parallel compilation and
SPIRV-Tools processing compose with the new grouping.

Grouped cache variants use `compact-dispatch-groups-v1`; default shared cache
filenames are unchanged. The option adds a fixed partition, not arbitrary group
sizes or per-world permutation choices. Exact-source and compatibility reuse
remain within one immutable process policy.

## Validation and interpretation

438 native tests passed. The shader fixture validates both renderings with Naga,
checks unchanged stage bodies, verifies each wrapper uses 64 lanes, and preserves
the 256-lane material prefix entry. Cache tests distinguish grouping/input/worker
policies while retaining the legacy default identity.

Eight headless GPU processes matched the previous shared fingerprints for all
four samples each (4,771,840 column comparisons plus masks/materials):

- Metal and Vulkan, each with shared, grouped and independent dispatch.
- Metal grouped on-demand compilation without preload.
- Vulkan grouped on-demand compilation with SPIRV-Tools and two compiler threads.

Metal includes material runs. Vulkan explicitly excludes them because of the
documented baseline MoltenVK prefix-scan limit. Runtime assertions confirm two
distinct world pipeline handles and one or two cave handles in grouped bundles.
Samples include both dimensions, negative coordinates and expanded height.

Separate driver-creation and per-region GPU stage records were captured for each
case. These diagnostic runs include hashing and uncontrolled driver caches;
their total process times are not a controlled startup or throughput benchmark.
Grouped compilation was slower in these local runs, so no default change or
NVIDIA speedup is claimed. More pipelines can cost extra compilation while
changing the code visible to an individual driver pipeline. Register allocation
and spills have not been measured, and are hypotheses rather than an established
cause. Windows comparisons must assess cold/repeated startup and execution
together. The final Metal guard uses the unchanged default.
