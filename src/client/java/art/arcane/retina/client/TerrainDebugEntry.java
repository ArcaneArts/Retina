package art.arcane.retina.client;

import art.arcane.retina.Retina;
import art.arcane.retina.worldgen.RetinaChunkGenerator;
import art.arcane.retina.worldgen.NativeTimings;
import art.arcane.retina.worldgen.TerrainStatsPayload;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientLifecycleEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.minecraft.client.gui.components.debug.DebugScreenDisplayer;
import net.minecraft.client.gui.components.debug.DebugScreenEntries;
import net.minecraft.client.gui.components.debug.DebugScreenEntry;
import net.minecraft.client.gui.components.debug.DebugScreenEntryStatus;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.chunk.LevelChunk;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import static art.arcane.retina.worldgen.NativeTimings.*;

public final class TerrainDebugEntry implements DebugScreenEntry {
    private static volatile TerrainStatsPayload remote = TerrainStatsPayload.INACTIVE;

    public static void initialize() {
        var id = Retina.id("generation");
        DebugScreenEntries.register(id, new TerrainDebugEntry());
        ClientLifecycleEvents.CLIENT_STARTED.register(client -> client.debugEntries.setStatus(id, DebugScreenEntryStatus.IN_OVERLAY));
        ClientPlayNetworking.registerGlobalReceiver(TerrainStatsPayload.TYPE, (payload, context) -> remote = payload);
        ClientPlayConnectionEvents.DISCONNECT.register((handler, client) -> remote = TerrainStatsPayload.INACTIVE);
    }

    @Override
    public void display(DebugScreenDisplayer displayer, Level level, LevelChunk clientChunk, LevelChunk serverChunk) {
        var payload = remote;
        if (level instanceof ServerLevel server && server.getChunkSource().getGenerator() instanceof RetinaChunkGenerator generator) {
            payload = new TerrainStatsPayload(true, generator.backend(), generator.mode(), generator.metrics().snapshot());
        }
        if (!payload.active()) return;
        var stats = payload.stats();
        var lines = new ArrayList<>(List.of(
                String.format(Locale.ROOT, "Retina %s: %.1f chunks/s (5s) | %.2f ms/chunk%s", payload.mode(), stats.chunksPerSecond(), stats.msPerChunk(), payload.mode().equals("mca") ? " (amortized)" : ""),
                String.format(Locale.ROOT, "Native: %.2f ms | Convert: %.2f ms | In flight jobs: %d", stats.nativeMs(), stats.conversionMs(), stats.inFlight()),
                String.format(Locale.ROOT, "Regions: %d | Last region: %.2f ms", stats.regions(), stats.lastRegionMs()),
                String.format(Locale.ROOT, "Temporary regions: %d | Cache hits: %d | Promoted: %d", stats.previewRegions(), stats.previewCacheHits(), stats.promotions()),
                "Terrain chunks: " + stats.total() + " | Failed: " + stats.failures(),
                "GPU " + payload.backend()));
        var timing = stats.stages();
        if (timing.chunks() > 0) {
            lines.add("Rust session: worker ms/chunk (% of parallel CPU work), " + timing.chunks() + " chunks");
            lines.add("Base " + worker(timing, ASSEMBLY) + " | Ores/carve " + worker(timing, GEOLOGY) + " | Cave deco " + worker(timing, CAVE_FEATURES));
            lines.add("Plants " + worker(timing, VEGETATION) + " | Structures " + worker(timing, STRUCTURES) + " | Snow " + worker(timing, SNOW));
            lines.add("NBT " + worker(timing, NBT) + " | Zip " + worker(timing, COMPRESS));
            lines.add(String.format(Locale.ROOT, "Plans wall ms/chunk: structures %.2f | plants %.2f | ores %.2f | File I/O %.2f",
                    timing.chunkMs(STRUCTURE_PLAN), timing.chunkMs(VEGETATION_PLAN), timing.chunkMs(ORE_PLAN), timing.chunkMs(IO)));
        }
        if (timing.gpuJobs() > 0) {
            if (timing.gpuMeasured()) {
                lines.add(String.format(Locale.ROOT, "GPU device ms/256 columns: heights/climate %.3f | sites %.3f | columns %.3f",
                        timing.gpuChunkMs(HEIGHT), timing.gpuChunkMs(SITES), timing.gpuChunkMs(COLUMNS)));
                lines.add(String.format(Locale.ROOT, "GPU caves ms/256 columns: density %.3f | mask %.3f",
                        timing.gpuChunkMs(CAVE_DENSITY), timing.gpuChunkMs(CAVE_MASK)));
            } else lines.add("GPU device timestamps unavailable on this adapter");
            lines.add(String.format(Locale.ROOT, "GPU host ms/dispatch: encode %.2f | wait+copy %.2f | Queue %.2f ms/chunk",
                    timing.dispatchMs(ENCODE), timing.dispatchMs(WAIT_COPY), timing.chunkMs(QUEUE)));
        }
        displayer.addToGroup(Retina.id("generation"), lines);
    }
    private static String worker(NativeTimings timing, int stage) {
        return String.format(Locale.ROOT,"%.2f (%.0f%%)",timing.chunkMs(stage),timing.workerPercent(stage));
    }
}
