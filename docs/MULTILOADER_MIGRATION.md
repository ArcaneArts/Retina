# Minecraft 26.3 loader migration

The migration targets Fabric and NeoForge on 26.3. Future Minecraft releases
need their own port and validation; Forge and older versions are outside this
migration. The Rust/WGSL engine remains shared.

## Loader boundary

`Retina.initialize(RetinaPlatform)` owns shared server initialization. The
platform supplies registry registration, payload registration and transport,
server ticking, and level unload callbacks. The shared tick retains the existing
20-tick telemetry interval and optional-client channel check.

`RetinaClient.initialize(RetinaClientPlatform)` owns shared debug display logic.
Its client-only platform supplies startup, payload reception, and disconnect
callbacks. Server initialization never references the client platform.

Fabric entrypoints and adapters live in `art.arcane.retina.fabric`. Shared
runtime code has no Fabric API imports. Shared sources/resources now live in
`common`, with Fabric adapters and transformation tests in `fabric` and NeoForge
adapters in `neoforge`.

## Baseline and first increment

The source baseline is `f7618000c22628bb2699e651b0699bc8c1efa8ad`.
`exportBenchmarkProfile` retained an effective vanilla profile with 56 biomes,
30 structure definitions and 182 decoration recipes. Its SHA-256 is
`2949064f5f62fc889b706b2c7911727e94e76c0cb40cf92f3e7340dd7732fea3`.
The exported profile after extracting the hooks is byte-identical.

Using seed 123456789, three adjacent regions at (-1,-1), (0,-1), and (1,-1)
have identical decompressed chunk NBT before and after the extraction: 3,072
chunks compared. Profiles, MCA files, decoded NBT, the baseline native binary,
and execution logs are retained outside the disposable implementation worktree.

Validation of the first increment passed:

- Host artifact build and existing Java and native unit tests.
- `gpuTest`, including its native GPU, mapping, ore, interpolation, coordinate,
  material and aquifer dependencies on an Apple M4 Max Metal device.
- `regionTest` and `previewTest`, including preservation of edits, concurrent
  requests, promotion, partial regions, eviction, shutdown and error cleanup.
- A fresh Fabric dedicated-server MCA world, registry initialization, live
  generation, stage-timing payload round trip, and normal save/shutdown.

The initial unchanged-source baseline also exposed two outstanding checks:

- `biomeTest`: density-first surface at (-16,2), cell 4x8, expected 58 and got 57.
- `structureTest`: its section decoder attempted to decode `block_states` from
  an empty compound (`No key palette in MapLike[{}]`).

These are recorded as remaining validation work. They are not evidence that
NeoForge support is complete. The full cross-platform artifact build initially
failed at `cargo zigbuild` because that local tool was absent; the successful
host build used `-PretinaHostOnly`.

## Module split and centralized native packaging

The second increment moves shared source and Minecraft-reference fixtures into
`common`, compiles shared main/client code independently against NeoForm, and
compiles it against Fabric's actual runtime for the Fabric artifact. The native
crate stays at the root; host and cross compilation tasks are defined once and
their resource trees can be consumed by both loader jars. Root build, check,
test and Fabric launch commands remain available.

The full distribution build passed with macOS, Linux and Windows libraries for
both x64 and ARM64, preserving `natives/<os>-<arch>/`. The host build, shared
registry/reference checks, native unit tests, Fabric transformation tests,
region checks and preview/cache checks also passed.

Fresh Fabric dedicated servers passed in MCA and chunk modes, including live
timing payload round trips, saving and normal shutdown. An opt-in real-client
startup check verified the shared debug-registry invoker and extracted native
library, then closed the isolated client normally. The invoker removes a
dependency on Fabric's access widening of vanilla's private registration method.

`exportFabricBenchmarkProfile` produced a byte-identical baseline profile and
another 3,072 identical decompressed NBT records. The common NeoForm export
differs in 24 decoration-noise offsets because its rebuilt `BitRandomSource`
uses float arithmetic in `nextDouble()` while the official Minecraft bytecode
uses double arithmetic. All 24 offsets have identical f32 representations, and
the compared MCA output is identical. Both exports and the precise differences
are retained; this is not a claim that the serialized profiles are identical.

## NeoForge registration, lifecycle and client integration

The third increment adds NeoForge 26.3.0.51-beta with ModDevGradle 2.0.147.
Its mod event bus registers the generator, biome-source and structure-piece
codecs and the optional clientbound telemetry payload. The main event bus
supplies server ticks and server-level unload callbacks. A client-only entrypoint
registers the payload receiver and supplies startup and disconnect callbacks;
the dedicated server loads without client initialization.

The combined distribution build passed. Both loader jars contain byte-identical
native libraries for macOS, Linux and Windows on x64 and ARM64, compiled once
per target. Shared native unit tests run once in the combined build. The host
build and Fabric transformation tests also passed.

Fresh NeoForge dedicated worlds passed in MCA and individual-chunk modes.
The MCA world also reopened and passed live preview promotion, concurrent
requested-region publication, GPU lighting, timing payload round trips and
normal save/shutdown. Actual NeoForge client startup passed the debug-registry
invoker, native extraction and Metal initialization and then shut down normally.
On this macOS QA host, OpenGL presentation initially stalled with VSync; the
isolated QA directory disables VSync in `options.txt` and FML's optional early
loading window in `config/fml.toml`. These are local QA settings, not mod defaults.

NeoForge development runs use Java 25's `--illegal-native-access=allow` in
addition to `--enable-native-access=ALL-UNNAMED`. FML constructs named mod modules
after JVM startup, so naming `retina` in the startup native-access flag produces
an unknown-module warning without enabling that module. The explicit Java 25
policy permits the native calls and was verified in real server/client runs.
Packaged installations require the same JVM arguments; later Java versions
need separate validation.

Integrated gameplay, actual client telemetry and reconnect, packaged-loader,
modified-registry and NeoForge DH/Chunky validation remain pending.

## Shared reference validation

The fourth increment resolves the recorded baseline fixture failures and runs
the broader shared suite. The density-lattice fixture now explicitly selects
final-density interpolation; separate Minecraft-reference checks continue to
exercise composition. MCA fixtures select terrain sections by their world Y
instead of list position, validate adjacent lighting padding, and reject
missing or duplicate terrain sections. This preserves full voxel comparisons
when GPU lighting adds boundary sections.

The cave-adapter fixture now isolates the fallback material adapter from loaded
recipes that own its placement budgets. The actual loaded patch/column recipes
remain covered by block-feature Minecraft-reference and MCA/chunk parity checks.
Synthetic fixtures that extend the block palette regenerate matching light
metadata. Native parity tasks now locate exported profiles under root `build/`,
matching the shared Java fixtures' working directory.

Validation passed the host build, Java/Fabric transformation and native unit
tests, GPU/coordinate/interpolation/material/aquifer dependencies, biome,
structure, geology, cave/feature, registered block-feature, shoreline,
Terralith datapack, region, preview/cache and lighting checks. The first broad
run exposed the fixture/path failures; focused rechecks passed after corrections.
The Rust/WGSL algorithms, ABI and serialized profile format are unchanged.

Actual integrated gameplay, client telemetry/reconnect, packaged installations,
effective loader registry modifications and DH/Chunky on both loaders remain
for subsequent increments.
