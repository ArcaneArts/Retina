package art.arcane.retina.client;

import art.arcane.retina.Retina;
import art.arcane.retina.worldgen.RetinaChunkGenerator;
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
        displayer.addToGroup(Retina.id("generation"), art.arcane.retina.debug.TerrainDebugReport.lines(payload));
    }
}
