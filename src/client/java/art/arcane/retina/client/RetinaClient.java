package art.arcane.retina.client;

import art.arcane.retina.Retina;
import net.fabricmc.api.ClientModInitializer;

public final class RetinaClient implements ClientModInitializer {
    @Override
    public void onInitializeClient() {
        TerrainDebugEntry.initialize();
        Retina.LOGGER.info("Retina client initialized");
    }
}
