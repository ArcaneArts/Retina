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
            var release = RegionGenerationBudget.SHARED.bulk();
            try {
                var pump = new RegionAheadPump(DISPATCHER, RegionGenerationBudget.BULK_LIMIT, selection::regions,
                        key -> io.retina$generateAhead(new ChunkPos(((int) key) << 5, ((int) (key >> 32)) << 5)), result -> {
                            release.run();
                            if (result.failure() != null) Retina.LOGGER.warn(
                                    "Retina Chunky region pre-generation stopped after {} regions; ordinary chunk loading remains active",
                                    result.completed(), result.failure());
                            else Retina.LOGGER.info("Retina Chunky region pre-generation {}: {} region requests published",
                                    result.stopped() ? "stopped" : "complete", result.completed());
                        });
                pump.start();
                Retina.LOGGER.info("Retina is generating Chunky's selection ahead to MCA: up to {} regions in flight",
                        RegionGenerationBudget.BULK_LIMIT);
                return pump;
            } catch (Throwable error) { release.run(); throw error; }
        } catch (Exception error) {
            Retina.LOGGER.warn("Could not start Retina's optional Chunky region pre-generation", error);
            return () -> {};
        }
    }
}
