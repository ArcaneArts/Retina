package art.arcane.retina.worldgen;

import art.arcane.retina.minecraft1201.LegacyRegionIo;
import com.mojang.logging.LogUtils;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.ChunkPos;
import org.slf4j.Logger;

import java.util.concurrent.Executor;
import java.util.concurrent.Executors;

/** Starts alongside the real 1.20.1 Chunky task while Minecraft retains chunk I/O ownership. */
public final class LegacyChunkyPregenerator {
    private static final Logger LOG = LogUtils.getLogger();
    private static final Executor DISPATCHER = Executors.newSingleThreadExecutor(task -> {
        var thread = new Thread(task, "retina-1201-chunky-ahead");
        thread.setDaemon(true);
        return thread;
    });

    private LegacyChunkyPregenerator() { }

    public static AutoCloseable start(Object task) {
        try {
            var selection = new ChunkySelection(task, type ->
                    type.getName().equals("org.popcraft.chunky.platform.FabricWorld") ? "getServerWorld" : "getWorld");
            if (!(selection.world instanceof ServerLevel level)
                    || !(level.getChunkSource().chunkScanner() instanceof LegacyRegionIo io)) return null;
            int limit = io.retina$regionLimit();
            if (limit == 0) return null;
            var pump = new RegionAheadPump(DISPATCHER, limit, selection::regions,
                    key -> io.retina$generateAhead(new ChunkPos(((int) key) << 5, ((int) (key >> 32)) << 5)),
                    result -> {
                        if (result.failure() != null) LOG.warn(
                                "Retina Chunky region pre-generation stopped after {} region requests", result.completed(), result.failure());
                        else LOG.info("Retina Chunky region pre-generation {}: {} region requests finished",
                                result.stopped() ? "stopped" : "complete", result.completed());
                    });
            LOG.info("Retina is generating Chunky's selection ahead to MCA: up to {} regions in flight", limit);
            pump.start();
            return pump;
        } catch (ReflectiveOperationException | RuntimeException error) {
            LOG.warn("Could not start Retina's optional Chunky region pre-generation", error);
            return null;
        }
    }
}
