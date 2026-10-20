package art.arcane.retina.worldgen;

import art.arcane.retina.Retina;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.ChunkPos;
import java.util.concurrent.Executors;

/** Starts alongside a Chunky task; Chunky still owns chunk loading, finalization and completion events. */
public final class ChunkyRegionPregenerator {
    private static final java.util.concurrent.Executor DISPATCHER = Executors.newSingleThreadExecutor(
            Thread.ofPlatform().daemon().name("retina-chunky-ahead").factory());
    private ChunkyRegionPregenerator() {}

    public static AutoCloseable start(Object task) {
        try {
            var selection = new ChunkySelection(task);
            if (!(selection.world instanceof ServerLevel level)
                    || !(level.getChunkSource().chunkScanner() instanceof RegionIoBridge io)
                    || !io.retina$regionMode()) return () -> {};
            var pump = new RegionAheadPump(DISPATCHER, RegionGenerationBudget.SHARED.limit(), selection::regions,
                    key -> io.retina$generateAhead(new ChunkPos(((int) key) << 5, ((int) (key >> 32)) << 5)), result -> {
                        NetherIntegrationQa.chunkyFinished(level, result);
                        if (result.failure() != null) Retina.LOGGER.warn(
                                "Retina Chunky region pre-generation stopped after {} regions; ordinary chunk loading remains active",
                                result.completed(), result.failure());
                        else Retina.LOGGER.info("Retina Chunky region pre-generation {}: {} region requests published",
                                result.stopped() ? "stopped" : "complete", result.completed());
                    });
            pump.start();
            NetherIntegrationQa.chunkyStarted(level);
            Retina.LOGGER.info("Retina is generating Chunky's selection ahead to MCA: up to {} regions in flight",
                    RegionGenerationBudget.SHARED.limit());
            return pump;
        } catch (Exception error) {
            Retina.LOGGER.warn("Could not start Retina's optional Chunky region pre-generation", error);
            return () -> {};
        }
    }
}
