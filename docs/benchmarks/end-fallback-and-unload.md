# End dynamic gateway fallback and dedicated shutdown

The remaining gateway fallback is now checked without another client launch. The actual Minecraft `TheEndGatewayBlockEntity.getPortalPosition` runs against a controlled empty block store, using loaded vanilla registries and generator. Minecraft creates 427 end-stone island blocks, places the reciprocal gateway, chooses a landing on the island, and saves the source exit. All 472 block writes fit Retina's prepared region set. A second resolution reuses the known exit without any writes; both gateway NBT records round-trip exactly. The fixture does not claim lighting or scheduler validation.

The existing unload harness also has an End-only mode. It queues two actual End region requests, cancels a duplicate read observer, and shuts down once generation is in flight. This is opt-in QA only; the original Nether mode is unchanged. No native generator is replaced, delayed or held in the final implementation.

Fabric and NeoForge dedicated servers both reused the same saved world and native library. Both observed two generation jobs at world-storage close, explicit close results for the three pending request observers, no generation failures, no remaining in-flight jobs, removal of temporary preview folders, and native definition closure after drain. NeoForge reopened the Fabric-checked save and verified the prior preview folder had been removed. Each run generated only two regions.

The initial Fabric probe included a completion barrier, but its logs show storage closed before either native call returned: that barrier never waited. It was removed entirely before the NeoForge run, which confirmed the unmodified shutdown path with two jobs still in flight. The final code contains no completion barrier or extra production generation hook.

Run the dedicated scenario only for a new lifecycle question:

```sh
./gradlew --no-daemon --no-watch-fs -PretinaHostOnly -PretinaQa -PretinaEndUnloadQa -PretinaServerWorldQa=retina-qa-mca -PretinaNativePath=/tmp/retina-end-portal-preparation/build/native-target/release/libretina_worldgen.dylib -PretinaDatapackQa=/tmp/retina-end-portal-preparation/.qa/20261027-022646-normal-end-portal-entry-and-retu/fabric2 :neoforge:runNeoForgeServer -x :buildNative -x :packageNative
```

Use `:fabric:runServer` for Fabric. Both are headless, preserve the existing client instance/options, and stop automatically after the scenario. These are lifecycle results, not performance acceptance. Automatic End activation and final integration/performance verification remain outstanding.
