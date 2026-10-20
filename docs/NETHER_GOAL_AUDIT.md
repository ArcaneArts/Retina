# Nether delivery audit

Retina automatically generates supported Nether worlds through its native/GPU
pipeline on creation and reopening. The implementation covers all five vanilla
biomes, enclosed terrain, lava, surfaces, loaded features, bastions, fortresses,
fossils, ruined portals and lighting. The End remains on its original generator.

Users keep Minecraft's ordinary world-type selection. There is no Retina enable
switch. Supported fixed and multi-noise biome sources and compatible loaded
settings/datapacks retain their effective definitions. Unsupported generator or
biome-source classes keep their original generator with a diagnostic. A failure
after Retina accepts a source propagates instead of silently switching terrain
generators.

## Requirement evidence

| Requirement | Delivered behavior and validation |
| --- | --- |
| Correct vertical contexts | Storage, density bounds and height-anchor contexts are distinct. Loaded Nether and customized offset/height fixtures verify storage `[0,256)` independently of ordinary terrain `[0,128)`. Above-roof saved edits survive. |
| Native terrain, surfaces and fluids | GPU density/material runs retain multiple floors, ceilings and overhangs; source climate and fluid behavior replace Overworld assumptions. Five-biome base-column, material, heightmap, negative-coordinate and boundary checks pass. |
| Complete vanilla Nether decoration | Export inventory accounts for the loaded feature graphs. Ores, springs and scheduled fluid metadata, fire, glowstone, fungi, plants/vines and basalt constructs use ordered source roots and neighboring accepted placements. Reference and region fixtures cover placement/support semantics. |
| Structures and progression | Native bastions, connected fortress pieces, fossils and ruined portals retain spacing/weights/exclusions, metadata, loot, block entities and spawn behavior. Structures-off fixtures contain no starts. Live locate, reference, loot and progression checks pass across saves and region boundaries. |
| Automatic activation and saved definitions | Runtime adaptation preserves original world-generation settings and dimensions. Both loaders pass dedicated/integrated creation and reopening, supported customization, normal Overworld and Superflat regressions, and unchanged End checks. |
| Portals and lighting | Actual portal round trips, cold destination creation, effective coordinate scaling, simultaneous dimension requests and zero-skylight Nether lighting pass. Lava/glowstone/shroomlight propagation and source removal are checked numerically. |
| Unload and publication | Global scheduling, duplicate cancellation, preview/promotion, partial saves and player edits pass. Unload drains owned native work before loader teardown; late DH decoration callbacks cannot acquire new work from a closed cache. |
| Installed integrations | DH 3.3.4 and Chunky 1.5.3/1.5.4 complete integrated fresh/reopen pairs on both loaders, including pause/resume, real native Nether requests and saved-data audits. Scope limits are below. |
| Visuals | Twenty final framebuffer captures, ten per loader, were opened and inspected. Five fixed-biome cross-sections and a saved-region seam supplement the landscapes, structures and ordinary lighting. [Images and observations](NETHER_VISUAL_QA.md). |
| Throughput and memory | Two matched 20,480-chunk native workloads have identical complete NBT: 91.6/90.9 chunks/s and 4.557/4.555 GiB peak process RSS on M4 Max. [Method and measurements](NETHER_PERFORMANCE.md). |

The [implementation record](NETHER_IMPLEMENTATION.md) preserves each staged
milestone, source contracts and focused tests. Statements there that activation
or later checks were deferred describe that milestone's state. The current
result is summarized here.

## Runtime matrix

The final validation is cumulative across the isolated cycles; these are
different scenarios, not repeated counts of one all-purpose test.

- **Progression:** 12 sessions across Fabric/NeoForge integrated create/reopen and
  dedicated fresh/reconnect/process restart. Generated fortress spawners produce
  blazes, a survival player kills one and collects a rod, loot persists, and
  actual spawn-placement routines produce blazes and wither skeletons.
- **Other structure gameplay:** 12 sessions cover bastions, fossils and ruined
  portals, including entity initialization, all intersecting references, loot,
  locate behavior and reopening; 455 passing runtime events.
- **Active unload:** eight server lifetimes, 304 owned requests reaching completion
  or explicit close results, sixteen preview cleanups and 290 passing events.
  Saved region inspection finds no publication after storage close.
- **Customization and preservation:** sixteen server lifetimes in twelve
  invocations, 370 passing events and eight retained worlds. Normal and customized
  Flat worlds cover effective fixed-biome/noise/dimension overrides and disabled
  structures. Six native preview cases compare 393,216 block values and preserve
  6,138 existing compressed records during partial repair.
- **Installed DH/Chunky:** four integrated lifetimes, 344 passing events, four
  completed 625-chunk Chunky selections and fresh DH-owned native Nether work in
  each lifetime. Saved sources, both edits and all dimension databases pass the
  offline audit. Both loaders also start with neither optional mod installed.
- **Final rendered tour:** two copied-world lifetimes, twenty inspected captures
  and 62 passing events. Original source definitions match the pre-tour save;
  Overworld and Y=200 Nether diamond edits persist. Both host builds pass, with
  104 native unit tests passed (37 ignored GPU/diagnostic fixtures) and five
  Fabric tests passed (three optional/native-fixture skips).

The broader compatibility regression also covers all nine built-in Superflat
presets, 294,912 reference light comparisons and 96 flat feature fixtures.
Commands, fixture effects and completion signals are in
[runtime QA](RUNTIME_QA.md). GPU-backed tests were run in their relevant cycles;
the ignored native unit fixtures do not imply those separate runs were absent.

## Pull requests

Each listed implementation cycle used an isolated worktree and branch and was
confirmed squash-merged before the next cycle. Concurrent unrelated optimization
PRs are excluded.

| Cycle | Merged PRs |
| --- | --- |
| 1 — generation contexts | [#130](https://github.com/ArcaneArts/Retina/pull/130) |
| 2 — terrain, biomes, surfaces and fluids | [#131](https://github.com/ArcaneArts/Retina/pull/131) |
| 3 — features, ordering and dependency reads | [#133](https://github.com/ArcaneArts/Retina/pull/133), [#134](https://github.com/ArcaneArts/Retina/pull/134), [#136](https://github.com/ArcaneArts/Retina/pull/136), [#137](https://github.com/ArcaneArts/Retina/pull/137), [#138](https://github.com/ArcaneArts/Retina/pull/138), [#139](https://github.com/ArcaneArts/Retina/pull/139), [#140](https://github.com/ArcaneArts/Retina/pull/140), [#142](https://github.com/ArcaneArts/Retina/pull/142), [#144](https://github.com/ArcaneArts/Retina/pull/144) |
| 4 — structures and progression | [#145](https://github.com/ArcaneArts/Retina/pull/145), [#146](https://github.com/ArcaneArts/Retina/pull/146), [#147](https://github.com/ArcaneArts/Retina/pull/147), [#149](https://github.com/ArcaneArts/Retina/pull/149), [#150](https://github.com/ArcaneArts/Retina/pull/150), [#151](https://github.com/ArcaneArts/Retina/pull/151), [#152](https://github.com/ArcaneArts/Retina/pull/152), [#154](https://github.com/ArcaneArts/Retina/pull/154), [#155](https://github.com/ArcaneArts/Retina/pull/155), [#156](https://github.com/ArcaneArts/Retina/pull/156), [#158](https://github.com/ArcaneArts/Retina/pull/158), [#161](https://github.com/ArcaneArts/Retina/pull/161), [#162](https://github.com/ArcaneArts/Retina/pull/162) |
| 5 — activation, portals and lifecycle | [#159](https://github.com/ArcaneArts/Retina/pull/159), [#160](https://github.com/ArcaneArts/Retina/pull/160), [#163](https://github.com/ArcaneArts/Retina/pull/163) |
| 6 — compatibility, installed integrations and performance | [#164](https://github.com/ArcaneArts/Retina/pull/164), [#165](https://github.com/ArcaneArts/Retina/pull/165), [#166](https://github.com/ArcaneArts/Retina/pull/166) |

The final visual/documentation cycle supplies this audit, the framebuffer harness,
the published images and their [capture metadata](benchmarks/nether-visual-2026-10-20.json).
Full logs, worlds, profiles, binaries, commands and diagnostic attempts are
retained outside disposable worktrees in the goal's per-cycle evidence archive.

## Limits that remain explicit

- Retina approximates Minecraft world generation. Exact vanilla seed layouts,
  all custom worldgen classes and cross-backend bit identity are not promised.
  Unsupported custom features/processors remain reported; the vanilla Nether
  feature inventory has no silently omitted required root.
- The End remains deferred. Unsupported custom generators/biome sources retain
  their implementation; compatible settings do not make arbitrary Java worldgen
  executable on the GPU.
- Existing chunks and edits are preserved. Previously generated terrain can
  meet newly generated Retina terrain at visible seams.
- Installed DH coverage is integrated `CHUNKS_ONLY`/`FEATURES`. Completed
  `SURFACE_THEN_CHUNKS` gameplay and an installed dedicated DH/Chunky pair are not
  claimed. General dedicated-server Nether coverage did run.
- Survival progression uses controlled equipment, defensive effects and spawn
  attempts. It proves functioning generated progression components, not an
  unaided playthrough or normal mob-spawn rates.
- Performance is a native completion baseline on Metal, not live game frame time.
  The visual tours include server catch-up warnings, up to roughly twelve
  seconds, during loading/travel. They do not establish smooth traversal latency.
  The repeated native workload includes structures, decoration, lighting and
  durable publication; process RSS includes warmups and post-run corpus checks.
- Visual checks inspect representative locations. Full terrain, structure and
  lighting coverage relies on the separate numerical, metadata and runtime tests.
  Unload tests cover orderly shutdown, not forced process-kill durability.
