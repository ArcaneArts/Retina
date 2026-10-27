package art.arcane.retina.worldgen;

import art.arcane.retina.platform.RetinaPlatform;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.level.ServerLevelAccessor;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.WeakHashMap;

public final class TerrainWorldgen {
    private static RetinaPlatform platform;
    private static final Map<MinecraftServer, List<TemporaryRegions>> CLOSING = new WeakHashMap<>();

    private TerrainWorldgen() { }

    public static boolean finalizeStructureMob(Mob mob, ServerLevelAccessor level) {
        return platform.finalizeStructureMob(mob, level);
    }

    public static void initialize(RetinaPlatform platform) {
        TerrainWorldgen.platform = platform;
        platform.registerWorldgen();
        platform.registerTerrainStats();
        platform.registerServerHooks(server -> endTick(server, platform), TerrainWorldgen::unload, TerrainWorldgen::stopped);
    }

    private static void unload(ServerLevel level) {
        NetherUnloadQa.beforeUnload(level);
        TerrainQa.unload(level);
        NetherLifecycleQa.unload(level);
        NetherProgressionQa.unload(level);
        NetherStructuresQa.unload(level);
        NetherIntegrationQa.unload(level);
        if (level.getChunkSource().getGenerator() instanceof RetinaChunkGenerator retina) {
            var cache = retina.previewCache();
            if (cache != null) synchronized (CLOSING) {
                CLOSING.computeIfAbsent(level.getServer(), ignored -> new ArrayList<>()).add(cache);
            }
            retina.closePreviews();
        }
    }

    private static void stopped(MinecraftServer server) {
        List<TemporaryRegions> caches;
        synchronized (CLOSING) { caches = CLOSING.remove(server); }
        // Every dimension's I/O queue is closed now. Its pending preparations can
        // no longer publish or start another batch while another dimension drains.
        // The loader still owns its classes until this lifecycle callback returns.
        if (caches != null) for (var cache : caches) cache.closeAndAwaitGeneration();
        WorldDefinitions.close(server);
        NetherUnloadQa.serverDrained();
    }

    private static void endTick(MinecraftServer server, RetinaPlatform platform) {
        EndLifecycleQa.tick(server);
        EndTravelQa.tick(server);
        NetherUnloadQa.tick(server);
        NetherLifecycleQa.tick(server);
        NetherProgressionQa.tick(server);
        NetherStructuresQa.tick(server);
        NetherIntegrationQa.tick(server);
        AutomaticGenerationQa.tick(server);
        TerrainQa.checkPromotion(server);
        TerrainQa.checkTimings(server);
        TerrainQa.checkStructures(server);
        TerrainQa.checkEntities(server);
        TerrainLoadBenchmark.check(server);
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
                    ? new TerrainStatsPayload(true, retina.backend(), retina.mode(), retina.metrics().snapshot(),NativeTerrain.instance().gpuDiagnostics(retina.profile()==null?0:retina.profile().nativeId()),player.level().dimension().identifier().toString())
                    : TerrainStatsPayload.INACTIVE;
            platform.sendTerrainStats(player, payload);
        }
    }
}
