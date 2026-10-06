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
runtime code has no Fabric API imports. Splitting these sources into `common`,
`fabric`, and `neoforge` modules is the next migration increment; this increment
still builds the existing Fabric artifact.

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
