# End gateway preparation and respawning (26.3)

Gateways retain Minecraft's destination selection, dynamic island/reciprocal gateway placement, saved exit data, cooldown and pearl transitions. Before invoking that destination path, Retina prepares foreground regions covering its possible reads and writes. Unknown exits cover the actual repeated-vector search: up to sixteen backward steps, then up to sixteen forward steps. Coverage includes the complete chosen chunk, the radius-six possible island, radius-sixteen tallest-block search, final radius-five exit search, rounding, gateway frame and ticking neighbors. Known exact/nonexact exits use their respective smaller coverage.

An accepted gateway portal remains pending while publication completes. Minecraft's cooldown continues normally; the processor does not require another contact during that cooldown. Pending pearls hold their flight so a source-side impact cannot consume them early. Their ordinary superclass ticks, ownership/death rules and ticket updates continue. Once ready, Minecraft performs the original transfer and subsequent destination impact. Other projectiles retain their original path.

## Focused evidence

- Seventy-two calls to Minecraft's actual private gateway search, with controlled solid/empty/transition/alternating chunk occupancy and twelve positive/negative/axis/diagonal/zero-direction inputs, stay inside prepared regions. Six known-exit coverage cases pass. Existing Nether and End spawn coverage checks also pass.
- Common, Fabric client/server and NeoForge compile. An actual Fabric loader test checks the gateway metadata accessor and holds a pearl's position/motion while its vanilla base tick runs. This is headless and generates no regions.
- One combined NeoForge client run reused the existing muted Fabric-created instance and frozen `retina.dat`. Normal End entry, unknown gateway travel with an actual owned pearl, destination impact, vanilla owner cooldown, reciprocal return, four actual crystal-item placements, vanilla dragon respawn, disconnect and reopen passed. The respawned dragon UUID and both gateway exit positions/flags persisted.
- The pearl held for seven server ticks. One live observation reported 353.8 ms for publication of four destination regions, 834.6 ms from pearl creation to owner arrival, and 0.7 ms for the known return preparation. These are gameplay diagnostics, not final performance acceptance.
- No fight flags or stages were set by the scenario. Minecraft rebuilt the spikes, ran the animation and created the dragon. The scenario resets only the fixture player's portal cooldown between explicit travel checks.

For a repeat only when needed, run from the gateway worktree with the existing instance:

```sh
./gradlew --no-daemon --no-watch-fs -PretinaHostOnly -PretinaQa -PretinaEndGatewayQa -PretinaClientGameplayQa -PretinaClientPresetQa=minecraft:flat -PretinaClientOpenExistingQa=true -PretinaNativePath=/tmp/retina-end-portal-preparation/build/native-target/release/libretina_worldgen.dylib -PretinaClientRunDir=/tmp/retina-end-portal-preparation/.qa/20261027-022646-normal-end-portal-entry-and-retu/fabric2 :neoforge:runNeoForgeClient -x :buildNative -x :packageNative
```

The completed fixture resumes with persistence checks; do not create a fresh instance or rerun the successful campaign without a specific new question. Master volume remains zero and narrator onboarding remains disabled.

The live run did not explicitly force the dynamic-island fallback. Its code is unchanged and its maximum reach is covered by preparation. Pending-unload validation, dedicated integration, production End activation/migration and final shipping-default/multicaller performance remain outstanding. Production End eligibility is still disabled in this cycle.
