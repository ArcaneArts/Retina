package art.arcane.retina.worldgen;

import art.arcane.retina.platform.RetinaPlatform;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;

public final class TerrainWorldgen {
    private TerrainWorldgen() { }

    public static void initialize(RetinaPlatform platform) {
        platform.registerWorldgen();
        platform.registerTerrainStats();
        platform.registerServerHooks(server -> endTick(server, platform), TerrainWorldgen::unload);
    }

    private static void unload(ServerLevel level) {
        if (level.getChunkSource().getGenerator() instanceof RetinaChunkGenerator retina) retina.closePreviews();
    }

    private static void endTick(MinecraftServer server, RetinaPlatform platform) {
        TerrainQa.checkPromotion(server);
        TerrainQa.checkTimings(server);
        TerrainQa.checkStructures(server);
        TerrainQa.checkEntities(server);
        if (server.getTickCount() % 20 != 0) return;
        for (var level : server.getAllLevels()) {
            if (level.getChunkSource().getGenerator() instanceof RetinaChunkGenerator retina) {
                retina.metrics().nativeTimings(NativeTerrain.instance().timings(retina.profile() == null ? 0 : retina.profile().nativeId()));
            }
        }
        for (var player : server.getPlayerList().getPlayers()) {
            if (!platform.canSendTerrainStats(player)) continue;
            var generator = player.level().getChunkSource().getGenerator();
            if (generator instanceof RetinaChunkGenerator retina) {
                TerrainQa.check(player, retina);
            }
            var payload = generator instanceof RetinaChunkGenerator retina
                    ? new TerrainStatsPayload(true, retina.backend(), retina.mode(), retina.metrics().snapshot(),NativeTerrain.instance().gpuDiagnostics(retina.profile()==null?0:retina.profile().nativeId()))
                    : TerrainStatsPayload.INACTIVE;
            platform.sendTerrainStats(player, payload);
        }
    }
}
