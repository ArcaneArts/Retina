package art.arcane.retina.worldgen;

import art.arcane.retina.Retina;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.dimension.DimensionType;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;

/** Publish portal destination terrain without blocking server ticks or network keepalives. */
public final class PortalRegionPreparation {
    // PortalForcer searches 16 blocks for a new frame, which can extend three
    // blocks farther. Entity-ticking chunks require two neighboring chunk rings.
    private static final int FRAME_REACH = 19;
    // An existing portal rectangle can extend 20 blocks beyond the POI found.
    private static final int EXISTING_FRAME_REACH = 20;
    private static final int TICKING_HALO = 32;
    private record Bounds(ResourceKey<Level> dimension, int minX, int maxX, int minZ, int maxZ) {}
    private Bounds pendingBounds;
    private CompletableFuture<Void> pending;
    private long began;
    private boolean reported;

    public boolean ready(ServerLevel source, Entity entity) {
        var targetKey = source.dimension().equals(Level.NETHER) ? Level.OVERWORLD : Level.NETHER;
        var target = source.getServer().getLevel(targetKey);
        if (target == null || !(target.getChunkSource().getGenerator() instanceof RetinaChunkGenerator generator)
                || !generator.regionMode()) return true;
        double scale = DimensionType.getTeleportationScale(source.dimensionType(), target.dimensionType());
        var center = target.getWorldBorder().clampToBounds(entity.getX() * scale, entity.getY(), entity.getZ() * scale);
        int radius = destinationRadius(targetKey.equals(Level.NETHER));
        var bounds = bounds(targetKey, center, radius);
        if (!bounds.equals(pendingBounds)) {
            pendingBounds = bounds;
            began = System.nanoTime();
            reported = false;
            pending = prepare(target, regions(center, radius));
        }
        if (!pending.isDone()) return false;
        pending.join(); // An accepted generation failure must reach the caller.
        if (!reported && Boolean.getBoolean("retina.qa")) {
            reported = true;
            Retina.LOGGER.info("QA_EVT {\"event\":\"nether_portal_regions_ready\",\"status\":\"pass\",\"context\":{\"dimension\":\"{}\",\"regions\":{},\"ms\":{}}}",
                    targetKey.identifier(), regions(center, radius).size(), (System.nanoTime() - began) / 1e6);
        }
        return true;
    }

    public static CompletableFuture<Void> prepareArea(ServerLevel level, BlockPos center, int radius) {
        return prepare(level, regions(center, radius));
    }

    static int destinationRadius(boolean toNether) {
        int searchRadius = toNether ? 16 : 128;
        return Math.max(searchRadius + EXISTING_FRAME_REACH, FRAME_REACH) + TICKING_HALO;
    }

    private static CompletableFuture<Void> prepare(ServerLevel level, List<ChunkPos> regions) {
        var worker = (RegionIoBridge) level.getChunkSource().chunkMap.chunkScanner();
        if (!worker.retina$regionMode()) return CompletableFuture.completedFuture(null);
        var futures = regions.stream().map(worker::retina$generateAhead).toArray(CompletableFuture[]::new);
        return CompletableFuture.allOf(futures);
    }

    static List<ChunkPos> regions(BlockPos center, int radius) {
        var result = new ArrayList<ChunkPos>();
        for (int rz = Math.floorDiv(center.getZ() - radius, 512); rz <= Math.floorDiv(center.getZ() + radius, 512); rz++)
            for (int rx = Math.floorDiv(center.getX() - radius, 512); rx <= Math.floorDiv(center.getX() + radius, 512); rx++)
                result.add(new ChunkPos(rx * 32, rz * 32));
        return result;
    }

    private static Bounds bounds(ResourceKey<Level> dimension, BlockPos center, int radius) {
        return new Bounds(dimension, Math.floorDiv(center.getX() - radius, 512),
                Math.floorDiv(center.getX() + radius, 512), Math.floorDiv(center.getZ() - radius, 512),
                Math.floorDiv(center.getZ() + radius, 512));
    }
}
