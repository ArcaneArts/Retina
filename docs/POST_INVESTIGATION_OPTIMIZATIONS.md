# Post-investigation implementation goal

This tracks the implementation goal following the October 19 performance
investigation. It is separate from the completed earlier cycles in
`OPTIMIZATION_INTEGRATION_2026_10.md`. The complete goal remains open.

| Workstream | Current disposition | Remaining work |
| --- | --- | --- |
| Decoration overhead and diagnostics | [Compact GPU continuations](COMPACT_GPU_CONTINUATIONS.md) reduce dense checkpoint retention and CPU replay, fix internal queue handoff, and add opt-in phase/fallback diagnostics. Whole-region throughput remains mixed. | Bounded ready-batch scheduling and earlier-suspension experiments; further queue/cache compaction where measured. |
| Material and placement checks | Compiled immutable material sets and bounded sharing are implemented and measured; see [the report](MATERIAL_PREDICATE_OPTIMIZATION.md). | Prepared material layouts, repeated-column reuse, biome membership and other hot-path allocation/dispatch work. |
| Effective GPU decoration coverage | Exact vine handoff and fallback pass real-GPU tests; diagnostics attribute sparse, overflow, unresolved and device paths. | Expand profitable coverage, address measured overflow/sparse continuations and reduce substrate preparation/transfers. |
| Optional LZ4 MCA codec | Previous isolated codec experiment is evidence only. | Implement type-4 framing, typed records, configuration, promotion/repair compatibility, and full-pipeline/readback measurements. |
| NBT encoding | Previous rejected section-metadata trial remains documented. | Implement and measure different scan/metadata reuse approaches without repeating that experiment unchanged. |
| GPU final-section encoding and specialization | Not implemented in this goal yet. | Bounded palette/heightmap/compact-output and placement-specialization prototypes; promote proven wins or retain reproducible rejections. |
| Combined integration and documentation | Individual predicate and compact-continuation cycles pass matched complete-region comparisons. Compact continuations also pass eight-caller native NBT comparison with two CPU workers. | Lifecycle/promotion/repair/edits/failure coverage, final combined eight-caller checks and benchmarks, README and completion audit. |

Each cycle uses its own worktree and PR with squash auto-merge. Preserve unrelated
active worktrees, use local bounded checks, and do not launch the game or trigger
GitHub Actions. Isolated stage savings must not be reported as complete-generator
gains. Recheck current source and merged changes before each subsequent cycle.
