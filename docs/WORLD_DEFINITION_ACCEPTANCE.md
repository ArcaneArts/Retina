# World-definition implementation acceptance — 2026-10-27

Fabric, NeoForge and the standalone Rust runner now generate from `<world-root>/retina.dat` through one Rust loader and engine. The implementation is merged through `5d7ed3cc` (PR #312); the delivery table below lists all 38 implementation PRs. GitHub merge state and merge commits were checked for every entry. This report is delivered in a separate documentation PR.

## Delivered behavior

- On first world startup, one world-level coordinator compiles all effective dimensions after resources resolve and before level construction. It writes real structural NBT with high-compression gzip, atomically publishes `retina.dat`, discards the compiler result, then opens the published file through Rust. Ordinary use needs no export command.
- Subsequent startup loads the existing file. Changing datapacks does not replace saved generation. Actual decoding and platform identity binding report concrete incompatibilities and preserve the source. Supported saved records never recompile. Historical records explicitly deferred for the formerly missing End adapter have a narrow, validated one-time support upgrade with an exact original-byte backup.
- The versioned snapshot owns the exact 64-bit seed, generation/storage bounds, flags, DataVersion, shared canonical types, noise/density/surface programs, resolved material/tag behavior, decorations, structures, pools/processors/templates and typed embedded NBT. Unsupported dimensions and exporter omissions remain explicit.
- Runtime block, biome and structure lookups resolve platform identities. They do not provide generation rules. Biome queries, spawn/sea heights, structure selection/search, previews, chunk delivery and region preparation use loaded metadata. Native calls reject altered seed, bounds or float parameter bits before cache lookup or dispatch.
- Immutable world data is shared across dimensions and active jobs. Handles are not reused for different worlds; shutdown stops admission, drains work, and closes ownership. Worker counts, GPU dispatch preferences and output compression remain execution options.
- Production Java/FFM JSON upload and native JSON registration have been removed. Private `cfg(test)` migration/shader oracles retain legacy JSON references. Current generation tools and integration fixtures publish or open actual saved definitions.

Implementation details and repeatable commands are in [WORLD_DEFINITION.md](WORLD_DEFINITION.md). The [machine-readable acceptance record](benchmarks/world-definition-acceptance-2026-10-27.json) includes exact hashes, load samples, merge commits and evidence locations.

## Executed verification

All GPU results below were observed on Apple M4 Max / Metal. Campaign counts describe separate runs and should not be added as unique coverage. Full decoded NBT comparisons include every emitted field, rather than relying on compressed MCA bytes or screenshots.

| Verification | Executed result | Delivery / evidence |
| --- | --- | --- |
| Actual mod startup | Fabric and NeoForge created the file normally, then reopened with compilation forced to throw. Saved player edits and definition bytes survived. The missing-file control hit the intentional compiler error. | [#259](https://github.com/ArcaneArts/Retina/pull/259), [#265](https://github.com/ArcaneArts/Retina/pull/265); `world-definition-startup-*` and retained world caches |
| File-only fresh processes | Nine actual mod worlds, both Overworld and Nether: **18,432 complete decoded chunk comparisons passed**. Includes multiple full-width seeds, fixed procedural, cropped native/storage ranges, two badlands seeds, flat, negative coordinates, custom biomes and legitimate empty features. Children ran in fresh directories containing only copied `retina.dat`, without a JVM or datapack input. | `world-definition-{geometry,geometry-registry,feature}-headless-proofs-20261026.json` under the evidence root |
| Cross-boundary structures | Six structure families and 219 targeted Java chunk checks; a separate fresh-process campaign matched **12,288 complete decoded chunks in 12 regions**, including blocks, 3D biomes, heightmaps, lighting, structures, entities, block entities and ticks. | [#293](https://github.com/ArcaneArts/Retina/pull/293), `world-definition-structures-20261027` |
| Frozen live resources | Real datapack B reused A’s identifiers but changed noise and template blocks/NBT. Fresh B changed 195/400 canonical biome probes and placed gold. Reopened A retained all three decoded chunks, five diamonds and two block entities. Fabric mixins installed frozen structure starts and block-entity data under B’s template manager and a different live random-state seed. Generation registry lookups were forbidden. | [#312](https://github.com/ArcaneArts/Retina/pull/312), `world-definition-resource-authority-20261027` |
| Numeric and embedded NBT | Java/Rust structural equality, full-width seeds, mixed numeric arrays, modified UTF-8, negative zero/subnormals and typed template/entity NBT passed. Actual out-of-width values and malformed/truncated/unsupported files fail without replacement. | [#257](https://github.com/ArcaneArts/Retina/pull/257), [#281](https://github.com/ArcaneArts/Retina/pull/281), [#291](https://github.com/ArcaneArts/Retina/pull/291) |
| Interrupted publication | SIGKILL terminated an actual writer with a 2,391,562-byte partial gzip. Rust rejected those bytes; canonical `retina.dat` was absent. A subsequent real publication succeeded while preserving the orphan. | [#294](https://github.com/ArcaneArts/Retina/pull/294) |
| Existing output and cancellation | Complete regions preserved byte-for-byte; partial repair retained 1,023 records and player edits. Corrupt existing output failed unchanged. Actual interrupted CLI generation completed the active region and exited 130. | [#261](https://github.com/ArcaneArts/Retina/pull/261), final release recheck in [#311](https://github.com/ArcaneArts/Retina/pull/311) |
| End compatibility | All ten End fixture families use saved files. Twenty combined reference chunks had zero block/metadata differences; four complete regions (4,096 chunks) passed Minecraft decoding, with saved palettes and partial repair. The cooperating End work retained its own [acceptance report](benchmarks/end-performance-acceptance-2026-10-27.md). | [#300](https://github.com/ArcaneArts/Retina/pull/300); combined checks repeated with final native changes |
| Other supported generation | Flat presets, climate/material programs, shoreline, geology/caves, decorations, providers, block-feature survival, Nether terrain/features and structure families passed their existing reference and MCA checks after migration to files. Search, preview, cache and shutdown fixtures also passed. | [#278](https://github.com/ArcaneArts/Retina/pull/278)–[#308](https://github.com/ArcaneArts/Retina/pull/308), scoped to the deliveries listed below |
| Same-backend migration parity | Legacy-versus-saved structural/GPU/material and region comparisons passed. Final interpreter-reuse comparisons against the retained pre-retirement library matched **1,966,080 block IDs and 5,120 complete columns**, with non-vacuous variant controls. | [#258](https://github.com/ArcaneArts/Retina/pull/258), [#310](https://github.com/ArcaneArts/Retina/pull/310) |
| Final native and packaged artifacts | **351 native units passed**, 82 optional tests remained ignored in that invocation; local Cargo targets/features checked and release library/CLI built. Three production-linked GPU integration tests passed. Exact final release CLI independently reproduced another **2,048** copied-file mod chunks and cancellation. Both host JARs contain the exact tested native image; each generated all three dimensions on a JDK-only classpath using its bundled resource, without Minecraft classes or a native-path override. | [#311](https://github.com/ArcaneArts/Retina/pull/311), `world-definition-native-retirement-20261027` |
| Final repository check | Root `check --no-watch-fs` passed in 42 s after the resource-authority test was integrated. It used the already tested release image and excluded native build/package/unit tasks; production native code was unchanged. The full Fabric suite also exercised the new test in its shared test JVM. | [#312](https://github.com/ArcaneArts/Retina/pull/312), retained `check.log` |

## Size and loader measurements

Fresh measurements used the final release native library through its actual file-open FFI, without a JVM or GPU binding. Each artifact was opened and closed eight times. The median uses the seven subsequent opens; the OS page cache was not flushed. Timings include file reading, gzip/NBT decoding, validation and native profile construction, and exclude metadata serialization, GPU initialization, Java compilation and close. These are local observations, not performance guarantees.

| Snapshot | Gzip bytes | Decoded NBT bytes | First open | Subsequent median |
| --- | ---: | ---: | ---: | ---: |
| Vanilla, all three dimensions | 2,431,176 (2.32 MiB) | 14,552,320 | 709.6 ms | 680.0 ms |
| Terralith, Overworld benchmark only | 3,953,724 (3.77 MiB) | 19,707,057 | 1151.1 ms | 1120.6 ms |

The vanilla fixture contains normal Overworld/Nether/End definitions and seed `-9223372036854775791`. The fresh Terralith export uses seed `123456789`, 151 biomes, 3,953 materials, 58 executable structure definitions and 1,258 templates. It deliberately contains only the selected benchmark dimension; it is not presented as a complete three-dimension Terralith world. Both snapshots retain exporter omissions and approximations. Their exact hashes and all timing samples are in the acceptance JSON.

## Standalone use and decisive proof

Build the host executable, copy only the saved definition into an empty working directory, then generate a finite inclusive region range:

```sh
cargo build --manifest-path native/Cargo.toml --release --bin retina
mkdir /tmp/retina-proof
cp /path/to/world/retina.dat /tmp/retina-proof/retina.dat
cd /tmp/retina-proof
/path/to/Retina/native/target/release/retina generate \
  --definition ./retina.dat \
  --dimension minecraft:overworld \
  --region-min=-2,1 --region-max=-2,1 \
  --output ./generated-world
```

This selects one 32×32-chunk region, producing `generated-world/region/r.-2.1.mca`. Nether and End use `DIM-1/region/` and `DIM1/region/`. There is no seed override. The actual process proof used copied artifacts, fresh environment-cleared child processes and full decoded comparisons; an example command alone was not counted as verification. The final release recheck reproduced 1,024 Overworld and 1,024 Nether chunks and passed cancellation.

For repeatable mod-capture comparisons, set `RETINA_HEADLESS_PROOFS` to a JSON array of `{name, definition, regions}` records and run:

```sh
RETINA_HEADLESS_PROOFS=/absolute/proofs.json \
RETINA_HEADLESS_EXECUTABLE=/absolute/release/retina \
  cargo test --manifest-path native/Cargo.toml --test headless \
  copied_definition_matches_mod_regions_in_fresh_processes -- --ignored --nocapture
```

The harness reads capture paths only for comparison; the child executable receives the copied definition as its sole generation input. Use `RETINA_STRUCTURE_PROOFS` and `copied_definition_matches_structure_regions_in_fresh_processes` for the retained cross-region structure cases.

## Tested release artifacts

Artifacts retained under `~/.cache/retina/world-definition-native-retirement-20261027` were built from the last production change, PR #311. PR #312 changes QA and documentation only. The packaged mod evidence checks the host macOS/aarch64 native member; it does not claim execution on other OS/GPU combinations.

| Artifact | SHA-256 |
| --- | --- |
| `libretina_worldgen.dylib` | `251a14af11490aaca0af9c54688a522731f3a9dc0643deddfbced0ea5a1d1bff` |
| `retina` CLI | `01903a22fcdbf80fd7069fbccae607821379db2112d35c18a0cff4863a2d00e2` |
| `retina-fabric-26.3-0.392.jar` | `7632473bf21219f814608c63824e7ed0583785e6ff61beb221eec5973e023a6f` |
| `retina-neoforge-26.3-0.392.jar` | `14b1d34efc3e532f8cd6653e8cbdcec4a87f45a40157c5b6eb16cff08b99f89d` |

## Scope and remaining limits

- Snapshots are exact and seed-bound. Changing the seed, building seed-independent vanilla catalogs, replacing generation definitions and general future migrations are outside this delivery.
- Existing Retina generator coverage and approximations remain. A snapshot preserves supported rules; it does not implement arbitrary datapack/mod logic. Unsupported dimensions retain reasons, and profiles retain specific omission reports. Required platform types still must exist when the mod resolves their identities.
- A supported GPU remains required. Tests establish Retina-to-Retina equality on the tested Metal backend, not new complete vanilla parity or cross-GPU determinism.
- The CLI produces region data and native metadata/ticks. Creating `level.dat`, spawn setup and a completely initialized playable save is outside scope. Stop a server before writing its output; the CLI lock coordinates CLI writers only.
- Bukkit, CraftBukkit and launch-wrapper targets are intentionally not implemented here. The file/engine boundary is the foundation for those adapters.
- Optional external test-pack branches without their input packs were not claimed as executed. Ignored native tests were not relabeled as passing; explicitly run GPU/process proofs are listed separately.

## Merged implementation deliveries

Each cycle used an isolated worktree/branch, a ready PR and squash auto-merge. No administrative check bypass was used. The primary checkout was fast-forwarded after each confirmed merge; only this task’s worktrees were cleaned up. The cooperating World Types agent’s deliveries and worktrees are separate.

| PR | Change | Merge commit |
| --- | --- | --- |
| [#255](https://github.com/ArcaneArts/Retina/pull/255) | Add compressed NBT world definition schema and native reader | `34dc61ac` |
| [#257](https://github.com/ArcaneArts/Retina/pull/257) | Compile world-level retina.dat files and verify Java/Rust fidelity | `357b5b24` |
| [#258](https://github.com/ArcaneArts/Retina/pull/258) | Bind saved world definitions to the native engine | `39ba7485` |
| [#259](https://github.com/ArcaneArts/Retina/pull/259) | Initialize mod generation from the saved world definition | `9dd9e51b` |
| [#261](https://github.com/ArcaneArts/Retina/pull/261) | Generate region data headlessly from retina.dat | `4fea3c09` |
| [#265](https://github.com/ArcaneArts/Retina/pull/265) | Preserve procedural terrain and cropped generation in world definitions | `fb931c4e` |
| [#266](https://github.com/ArcaneArts/Retina/pull/266) | Retain saved structure metadata across native families | `68e775c3` |
| [#269](https://github.com/ArcaneArts/Retina/pull/269) | Persist generation exporter omissions in world definitions | `234347cc` |
| [#270](https://github.com/ArcaneArts/Retina/pull/270) | Use saved world definitions in pipeline and column benchmarks | `6eb7d165` |
| [#271](https://github.com/ArcaneArts/Retina/pull/271) | Load saved definitions in ore planning and raster tools | `78b494ec` |
| [#274](https://github.com/ArcaneArts/Retina/pull/274) | Bind file-backed integration fixtures with runtime dispatch options | `d477560f` |
| [#275](https://github.com/ArcaneArts/Retina/pull/275) | Read saved definitions in decoration and material diagnostics | `344c8dc8` |
| [#277](https://github.com/ArcaneArts/Retina/pull/277) | Run Python generation campaigns from saved world definitions | `060ce913` |
| [#278](https://github.com/ArcaneArts/Retina/pull/278) | Load lighting integration rules from saved definition files | `02ac3fc9` |
| [#279](https://github.com/ArcaneArts/Retina/pull/279) | Load analytic terrain fixtures through saved world definitions | `64a20d20` |
| [#280](https://github.com/ArcaneArts/Retina/pull/280) | Honor saved density semantics and load compilation diagnostics from retina.dat | `d9665e8c` |
| [#281](https://github.com/ArcaneArts/Retina/pull/281) | Validate embedded NBT numeric widths in saved world definitions | `ac56758e` |
| [#282](https://github.com/ArcaneArts/Retina/pull/282) | Exercise flat generation through saved definitions and runtime palettes | `42b136b1` |
| [#287](https://github.com/ArcaneArts/Retina/pull/287) | Load Nether component fixtures from definitions and accept valid zero depths | `0aaf4094` |
| [#291](https://github.com/ArcaneArts/Retina/pull/291) | Preserve exact procedural float parameters in retina.dat | `c7629a83` |
| [#293](https://github.com/ArcaneArts/Retina/pull/293) | Generate structure fixtures from retina.dat and prove headless parity | `bb120ef1` |
| [#294](https://github.com/ArcaneArts/Retina/pull/294) | Prove recovery after interrupting retina.dat publication | `6e7c6c6a` |
| [#295](https://github.com/ArcaneArts/Retina/pull/295) | Generate Nether terrain and feature fixtures through retina.dat | `509e7895` |
| [#297](https://github.com/ArcaneArts/Retina/pull/297) | Load structure contract fixtures and seed bindings from retina.dat | `7eb52305` |
| [#298](https://github.com/ArcaneArts/Retina/pull/298) | Load structure family and baseline fixtures through retina.dat | `be35af52` |
| [#300](https://github.com/ArcaneArts/Retina/pull/300) | Load End fixtures and region publication through retina.dat | `1af915b4` |
| [#301](https://github.com/ArcaneArts/Retina/pull/301) | Load registry program and biome fixtures through retina.dat | `8ea3c026` |
| [#302](https://github.com/ArcaneArts/Retina/pull/302) | Load geology, feature and sampler fixtures through retina.dat | `2f1f1d3a` |
| [#303](https://github.com/ArcaneArts/Retina/pull/303) | Load block feature reference fixtures through retina.dat | `8715d02a` |
| [#304](https://github.com/ArcaneArts/Retina/pull/304) | Load datapack and registry adapter fixtures through retina.dat | `2bee723e` |
| [#305](https://github.com/ArcaneArts/Retina/pull/305) | Load preview and search runtimes through retina.dat | `0c57d865` |
| [#306](https://github.com/ArcaneArts/Retina/pull/306) | Require retina.dat for Java generation and remove profile upload | `81b60d47` |
| [#307](https://github.com/ArcaneArts/Retina/pull/307) | Enforce native generation inputs from retina.dat | `46b5a34f` |
| [#308](https://github.com/ArcaneArts/Retina/pull/308) | Load primitive bridge and MCA fixtures through retina.dat | `299a243b` |
| [#309](https://github.com/ArcaneArts/Retina/pull/309) | Load Rust GPU integration fixtures through retina.dat | `0ae092cb` |
| [#310](https://github.com/ArcaneArts/Retina/pull/310) | Load interpreter reuse proof variants through retina.dat | `594c8a87` |
| [#311](https://github.com/ArcaneArts/Retina/pull/311) | Retire native JSON registration and require loaded world handles | `391a7ed0` |
| [#312](https://github.com/ArcaneArts/Retina/pull/312) | Prove retina.dat remains authoritative after datapack changes | `5d7ed3cc` |

## Retained evidence

Local evidence lives under `~/.cache/retina/`. The JSON record identifies exact artifacts and samples; these caches are not runtime dependencies. Relevant groups include:

- `world-definition-startup-fabric-fresh-20261026`, `world-definition-startup-neoforge-20261026` and the procedural/cropped/badlands/flat/empty world directories: actual world files, captures and compiler-disabled reopen receipts.
- `world-definition-structures-20261027`, `world-definition-end-fixtures-20261027` and the scoped feature/registry/Nether fixture caches: definitions, decoded-reference receipts and generation logs.
- `world-definition-native-retirement-20261027`: final native binaries, both packaged mod JARs, packaged-member hashes, JDK-only probe source/classes, unit/build/GPU/headless logs.
- `world-definition-resource-authority-20261027`: both real datapacks, both saved definitions, passing Fabric test XML, receipt and final root-check log.
- `world-definition-acceptance-20261027`: measured vanilla and fresh Terralith definitions, loaded metadata, all load samples and Terralith export log.
