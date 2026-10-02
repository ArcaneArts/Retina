package art.arcane.retina.worldgen;

import art.arcane.retina.Retina;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLevelEvents;
import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry;
import net.fabricmc.fabric.api.networking.v1.PlayerLookup;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.core.Registry;
import net.minecraft.core.registries.BuiltInRegistries;

public final class TerrainWorldgen {
    private TerrainWorldgen() { }

    public static void initialize() {
        Registry.register(BuiltInRegistries.CHUNK_GENERATOR, Retina.id("gpu"), RetinaChunkGenerator.CODEC);
        Registry.register(BuiltInRegistries.BIOME_SOURCE, Retina.id("voronoi"), RetinaBiomeSource.CODEC);
        PayloadTypeRegistry.clientboundPlay().register(TerrainStatsPayload.TYPE, TerrainStatsPayload.CODEC);
        ServerLevelEvents.UNLOAD.register((server, level) -> {
            if (level.getChunkSource().getGenerator() instanceof RetinaChunkGenerator retina) retina.closePreviews();
        });
        ServerTickEvents.END_SERVER_TICK.register(server -> {
            if (server.getTickCount() % 20 != 0) return;
            for (var player : PlayerLookup.all(server)) {
                if (!ServerPlayNetworking.canSend(player, TerrainStatsPayload.TYPE)) continue;
                var generator = player.level().getChunkSource().getGenerator();
                if (generator instanceof RetinaChunkGenerator retina) {
                    TerrainQa.check(player, retina);
                }
                var payload = generator instanceof RetinaChunkGenerator retina
                        ? new TerrainStatsPayload(true, retina.backend(), retina.mode(), retina.metrics().snapshot())
                        : TerrainStatsPayload.INACTIVE;
                ServerPlayNetworking.send(player, payload);
            }
        });
    }
}
