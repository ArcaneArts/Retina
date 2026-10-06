# Minecraft 26.3 loader migration

Retina supports Fabric and NeoForge on Minecraft 26.3, with shared Java,
resources and Rust/WGSL. Forge and older Minecraft versions are outside this
migration. Both mod metadata files accept exactly 26.3; future Minecraft
releases require a separate port and validation.

## Architecture and distribution

- `common/`: generation, registry/profile export, generator/biome codecs,
  storage/cache integration, telemetry codec, shared mixins, client display and
  Minecraft-reference fixtures. Runtime sources do not import loader APIs.
- `fabric/`: Fabric entrypoints, registration, events, networking and metadata.
- `neoforge/`: equivalent NeoForge adapters and metadata, using ModDevGradle.
- `native/`: one Rust crate and WGSL engine for both loaders.
- `gradle/`: shared Java, native build and verification conventions.

Fabric uses Loom. Common compilation uses NeoForm; loader artifacts compile the
shared sources against their own actual Minecraft classpaths. Explicit
`RetinaPlatform` and client-only `RetinaClientPlatform` interfaces supply the
loader hooks. There is no Architectury runtime dependency. Server startup does
not initialize client classes.

Stable IDs include `retina:gpu`, `retina:voronoi`, `retina:template`, the MCA and
chunk world presets, and `retina:terrain_stats`. Networking remains optional
for clients, with main-thread reception and statistics cleanup on disconnect.

A combined macOS distribution build compiles once per OS/architecture and puts
the same six native binaries in both jars. Native resource paths, native-library
override, two Cargo jobs and lowered POSIX process priority are retained. Shared
native unit tests run once. CI builds both host-only Linux artifacts; GPU tests
remain explicit and require hardware. See the README for release toolchains,
artifact names, loader commands and Java 25 native-access arguments.

## Delivery cycles

Every cycle used a fresh worktree and branch, a ready PR, and squash auto-merge.

| Cycle | Increment | PR |
| --- | --- | --- |
| 1 | Fabric baseline and explicit loader hooks | [#8](https://github.com/ArcaneArts/Retina/pull/8) |
| 2 | Common/Fabric modules and centralized native packaging | [#9](https://github.com/ArcaneArts/Retina/pull/9) |
| 3 | NeoForge registration, lifecycle, networking and client entrypoint | [#10](https://github.com/ArcaneArts/Retina/pull/10) |
| 4 | Shared Minecraft-reference verification | [#11](https://github.com/ArcaneArts/Retina/pull/11) |
| 5 | Actual client gameplay, effective registries and packaged installations | [#12](https://github.com/ArcaneArts/Retina/pull/12) |
| 6 | Matching DH/Chunky installations and exact Minecraft metadata | [#13](https://github.com/ArcaneArts/Retina/pull/13) |
| 7 | Template entity attachment correction and final audit | This change |

## Baseline comparisons

The Fabric source baseline is `f7618000c22628bb2699e651b0699bc8c1efa8ad`.
Its effective vanilla profile contains 56 biomes, 30 structure definitions and
182 decoration recipes, with SHA-256
`2949064f5f62fc889b706b2c7911727e94e76c0cb40cf92f3e7340dd7732fea3`.
The final Fabric export is byte-identical. Using that identical profile and
seed 123456789, the final native binary produces identical decompressed chunk
NBT for regions (-1,-1), (0,-1) and (1,-1): 3,072 chunks compared.

NeoForm's rebuilt `BitRandomSource.nextDouble()` uses a float intermediate
where the official Minecraft bytecode uses double arithmetic. Common and
NeoForge exports consequently differ in 24 decoration-noise offsets; all have
identical f32 representations. These profiles are not claimed byte-identical.
The actual modified-registry fixture contains 56 biomes and 183 decoration
recipes on both loaders and exhibits the same bounded precision difference.
Comparisons retain their exact effective profiles, seeds and coordinates.

The narrow native change in cycle 7 fixes necessary template entity metadata:
attachment anchors now come from transformed wrapper `blockPos`, and current
frame/painting direction codecs rotate with the piece. Entity ownership uses
the template anchor, matching Minecraft's placement bounds. UUID generation,
entity contents, terrain algorithms, ABI and serialized formats are retained.
Previously invalid attachment records are intentionally different in affected
structures. Already saved chunks are preserved rather than rewritten.

## Validation performed

The host build, Java/Fabric transformation tests and native unit tests passed.
The full distribution build contains macOS, Linux and Windows binaries for x64
and ARM64; corresponding binaries in the loader jars are byte-identical.
Actual GPU and loader execution used an Apple M4 Max Metal host. Cross-compilation
is not evidence of Windows/Linux GPU runtime validation.

The shared suite passed GPU/mapping/interpolation/coordinate, material,
aquifer, biome, geology, feature, registered block-feature, shoreline, structure,
Terralith datapack, region, preview/cache and GPU lighting checks. Recorded
baseline fixture failures were corrected without changing terrain algorithms:
fixtures distinguish density interpolation from composition, identify MCA
sections by world Y, isolate fallback placement budgets, and refresh lighting
metadata when synthetic palettes change.

Region/cache checks exercise negative coordinates, concurrent request sharing,
absent/empty/partial-region promotion, retained chunks and edits, eviction,
failed reads, native errors and shutdown. They retain the existing 1,024-region
cache policy and warm-memory limits. After the entity correction, native GPU,
biome, region, preview, lighting and structure checks passed again. The new
Minecraft-reference attachment fixture checks 64 entities across every rotation
and negative chunk boundaries; 64 ordinary native unit tests pass.

Actual development and ordinary packaged installations passed:

- Integrated gameplay in MCA and chunk modes on both loaders, with actual
  telemetry reception, the registered F3 entry, an edit at (-17,120,-17),
  disconnect, save/reopen and retained generator settings.
- Dedicated TCP connections on both loaders, actual received packets and
  disconnect/reconnect callbacks with cleared statistics between sessions.
- Effective biome modifications supplied by Fabric's biome API or NeoForge's
  biome modifier, verified in both loaded feature holders and native recipes.
- Fresh/reopened structures in both modes on both loaders: 81 native pieces,
  all 98,304 target-chunk blocks, decoded block entities and 132 processed gold
  blocks, including temporary preview promotion in MCA mode.
- Fresh/reopened template entities in both modes on both loaders: actual frame,
  glow frame and painting UUIDs, world anchors, directions and survival against
  their support blocks, with no invalid-attachment messages.

These checks use disposable isolated worlds. Offline authentication/Realms
errors are expected from the local QA identity. On the macOS QA host, VSync and
FML's optional early loading window were disabled in those isolated directories.
Long snapshot comparisons freeze random ticks in their disposable worlds so
normal fire/vegetation updates do not invalidate comparisons with generated
terrain. Normal gameplay defaults are unchanged.

## Distant Horizons and Chunky

Matching installations use Distant Horizons 3.3.4, Chunky Fabric 1.5.3 and Chunky
NeoForge 1.5.4. Both dedicated loaders completed 1,089-chunk and 4,225-chunk
pregeneration jobs at negative coordinates, including pause/resume of the
larger job. DH disabled generation during Chunky work and restored it afterward.
After normal shutdown each dedicated DH database contains 5,395 chunk hashes
and generated full data.

Packaged integrated MCA clients with these mods and the registry QA pack passed
telemetry, modified-feature export, negative edits and save/reopen. Actual DH
world-gen threads consumed Retina's temporary MCA chunks before and after
reopening; saved DH databases contain full data. The larger job exposed the
attachment defect fixed in cycle 7. Reproduction commands and current QA packs
are documented in [RUNTIME_QA.md](RUNTIME_QA.md).

The final artifacts repeated the full 4,225-chunk dedicated job on both loaders
without invalid attachment messages. Final packaged integrated clients with
these mods passed the gameplay, registry and save/reopen checks in both modes.

## Future Minecraft ports

Start each future release from a separate branch and update the Minecraft,
Loom/NeoForm, Fabric API and NeoForge versions together. Adapt Minecraft-facing
code in `common/worldgen`, shared mixins and client integration, then adapt the
thin platform modules for any changed loader events or networking APIs. Keep
native changes scoped to verified profile or game-format changes.

Validate both artifacts against that release's effective registries, saved-world
codecs, storage paths and matching optional mods. Repeat the shared reference,
GPU and packaged client/dedicated matrix before widening that release's metadata.
Older versions can receive their own maintenance branch if needed; this migration
does not introduce multi-version build tooling or claim forward compatibility.

Existing terrain approximations and unsupported recipes remain documented in
the README and generation-specific documents and are reported during export.
This migration preserves those boundaries; it does not implement all vanilla
structures/features or promise compatibility with arbitrary datapacks/mods.
