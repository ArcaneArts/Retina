package art.arcane.retina.client;

import art.arcane.retina.worldgen.TerrainStatsPayload;

import java.util.function.Consumer;

/** Client-only hooks; this type is never reached by server initialization. */
public interface RetinaClientPlatform {
    void onClientStarted(Runnable callback);

    void registerTerrainStatsReceiver(Consumer<TerrainStatsPayload> receiver);

    void onDisconnect(Runnable callback);
}
