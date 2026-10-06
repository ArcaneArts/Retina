package art.arcane.retina.client;

import art.arcane.retina.Retina;

public final class RetinaClient {
    private RetinaClient() { }

    public static void initialize(RetinaClientPlatform platform) {
        TerrainDebugEntry.initialize(platform);
        Retina.LOGGER.info("Retina client initialized");
    }
}
