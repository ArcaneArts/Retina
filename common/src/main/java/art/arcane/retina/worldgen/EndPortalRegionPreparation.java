package art.arcane.retina.worldgen;

import art.arcane.retina.Retina;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.dimension.end.EnderDragonFight;
import net.minecraft.world.level.gamerules.GameRules;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.concurrent.CompletableFuture;

/** Entity-owned, server-thread-only preparation. Vanilla still selects and performs the transition. */
public final class EndPortalRegionPreparation {
    private static final int TICKING_HALO = 32;
    // Fight scans chunks -8..8 and tickets radius 9; include the entire outer chunk and ticking neighbors.
    static final int ARENA_RADIUS = (EnderDragonFight.ARENA_TICKET_LEVEL + 1) * 16 + TICKING_HALO;
    private record Target(ServerLevel level, List<ChunkPos> regions) { }
    private List<Target> targets = List.of();
    private CompletableFuture<Void> pending = CompletableFuture.completedFuture(null);
    private long began;
    private boolean reported;

    public boolean ready(ServerLevel source, Entity entity) {
        var requested = new LinkedHashMap<ServerLevel, LinkedHashSet<ChunkPos>>();
        if (source.dimension() != Level.END) {
            var end = source.getServer().getLevel(Level.END);
            if (end != null) {
                add(requested, end, PortalRegionPreparation.regions(BlockPos.ZERO, ARENA_RADIUS));
                add(requested, end, PortalRegionPreparation.regions(ServerLevel.END_SPAWN_POINT, 2 + TICKING_HALO));
            }
        } else if (entity instanceof ServerPlayer player) {
            // Do not resolve/consume a bed or anchor here. Preparing both possibilities also covers
            // missing/obstructed spawn blocks and the first-exit credits path, which has no processor.
            var config = player.getRespawnConfig();
            if (config != null) {
                var data = config.respawnData();
                var level = source.getServer().getLevel(data.dimension());
                if (level != null) add(requested, level, PortalRegionPreparation.regions(data.pos(), 2 + TICKING_HALO));
            }
            var fallback = source.getServer().findRespawnDimension();
            var pos = fallback.getRespawnData().pos();
            int radius = fallback.getServer().getWorldData().getGameType() == GameType.ADVENTURE ? 0
                    : sharedSpawnRadius(fallback, pos);
            add(requested, fallback, sharedSpawnRegions(pos, radius));
        } else {
            var data = source.getRespawnData();
            var level = source.getServer().getLevel(data.dimension());
            // Entity.adjustSpawnLocation uses the target level's shared spawn, not the suggestion.
            if (level != null) add(requested, level, PortalRegionPreparation.regions(level.getRespawnData().pos(), TICKING_HALO));
        }
        var plan = new ArrayList<Target>();
        requested.forEach((level, regions) -> plan.add(new Target(level, List.copyOf(regions))));
        if (!plan.equals(targets)) {
            targets = List.copyOf(plan);
            began = System.nanoTime();
            reported = false;
            pending = CompletableFuture.allOf(targets.stream()
                    .map(target -> PortalRegionPreparation.prepare(target.level(), target.regions()))
                    .toArray(CompletableFuture[]::new));
        }
        if (!pending.isDone()) return false;
        pending.join(); // Propagate actual accepted generation failures.
        if (!reported && Boolean.getBoolean("retina.qa")) {
            reported = true;
            Retina.LOGGER.info("QA_EVT {\"event\":\"end_portal_regions_ready\",\"status\":\"pass\",\"context\":{\"regions\":{},\"ms\":{}}}",
                    targets.stream().mapToInt(target -> target.regions().size()).sum(), (System.nanoTime() - began) / 1e6);
        }
        return true;
    }

    private static void add(LinkedHashMap<ServerLevel, LinkedHashSet<ChunkPos>> requested, ServerLevel level, List<ChunkPos> regions) {
        requested.computeIfAbsent(level, ignored -> new LinkedHashSet<>()).addAll(regions);
    }

    private static int sharedSpawnRadius(ServerLevel level, BlockPos pos) {
        int radius = Math.max(0, level.getGameRules().get(GameRules.RESPAWN_RADIUS));
        int border = net.minecraft.util.Mth.floor(level.getWorldBorder().getDistanceToBorder(pos.getX(), pos.getZ()));
        if (border < radius) radius = border;
        if (border <= 1) radius = 1;
        return radius;
    }

    /** PlayerSpawnFinder permutes at most 1024 candidates; no need to prepare an enormous radius. */
    static List<ChunkPos> sharedSpawnRegions(BlockPos center, int radius) {
        var regions = new LinkedHashSet<>(PortalRegionPreparation.regions(center, TICKING_HALO + 1));
        long side = radius * 2L + 1;
        int count = side > 32 ? 1024 : (int) (side * side);
        // Keep the coordinate operations identical to PlayerSpawnFinder, including Java int arithmetic.
        int width = radius * 2 + 1;
        for (int candidate = 0; candidate < count; candidate++) {
            int x = center.getX() + candidate % width - radius;
            int z = center.getZ() + candidate / width - radius;
            regions.addAll(PortalRegionPreparation.regions(new BlockPos(x, center.getY(), z), TICKING_HALO + 1));
        }
        return List.copyOf(regions);
    }
}
