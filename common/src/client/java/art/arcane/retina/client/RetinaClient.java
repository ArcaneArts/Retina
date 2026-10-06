package art.arcane.retina.client;

import art.arcane.retina.Retina;
import art.arcane.retina.worldgen.NativeTerrain;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.debug.DebugScreenEntries;

public final class RetinaClient {
    private RetinaClient() { }

    public static void initialize(RetinaClientPlatform platform) {
        TerrainDebugEntry.initialize(platform);
        platform.onClientStarted(() -> {
            if (!Boolean.getBoolean("retina.qa.client.startup")) return;
            if (!(DebugScreenEntries.getEntry(Retina.id("generation")) instanceof TerrainDebugEntry)) {
                throw new IllegalStateException("Retina debug entry was not registered in the loaded client");
            }
            Retina.LOGGER.info("QA_EVT {\"event\":\"minecraft_client_loader_startup\",\"status\":\"pass\",\"context\":{\"backend\":\"{}\"}}",
                    NativeTerrain.instance().backend());
            Minecraft.getInstance().stop();
        });
        Retina.LOGGER.info("Retina client initialized");
    }
}
